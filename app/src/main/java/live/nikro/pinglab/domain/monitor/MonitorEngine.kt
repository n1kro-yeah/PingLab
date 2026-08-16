package live.nikro.pinglab.domain.monitor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import live.nikro.pinglab.core.model.HostSnapshot
import live.nikro.pinglab.core.model.HostState
import live.nikro.pinglab.core.model.MonitoredHost
import live.nikro.pinglab.core.model.ProbeResult
import live.nikro.pinglab.core.net.PingEngineFactory
import live.nikro.pinglab.domain.quality.QualityEvaluator
import live.nikro.pinglab.domain.stats.LatencyStatistics
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs many hosts concurrently and maintains a live snapshot for each one.
 *
 * Owned by the foreground service so monitoring survives the UI being destroyed. The UI just
 * collects [snapshots]; it never drives the probing itself.
 *
 * State machine per host:
 *
 * ```
 *            success (rtt <= degradedLatencyMs)
 *   CHECKING ------------------------------------> UP
 *        |  success (rtt > degradedLatencyMs)        |
 *        +----------------------------------> DEGRADED
 *        |  failureThreshold consecutive failures    |
 *        +----------------------------------------> DOWN
 * ```
 *
 * Transitions into DOWN and back into UP raise an [Alert], which the service turns into a
 * notification. A host must fail [MonitoredHost.failureThreshold] times in a row before it is
 * declared down, so a single dropped packet never pages anyone.
 */
class MonitorEngine(
    private val engines: PingEngineFactory,
    private val scope: CoroutineScope,
    private val historyWindow: Int = 300,
    private val onSample: (suspend (MonitoredHost, ProbeResult) -> Unit)? = null,
) {

    enum class AlertType { DOWN, RECOVERED, DEGRADED }

    data class Alert(
        val host: MonitoredHost,
        val type: AlertType,
        val atMs: Long,
        val message: String,
        val lastResult: ProbeResult?,
    )

    private val session = LivePingSession(engines)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val history = ConcurrentHashMap<Long, ArrayDeque<ProbeResult>>()

    private val _snapshots = MutableStateFlow<Map<Long, HostSnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<Long, HostSnapshot>> = _snapshots.asStateFlow()

    private val _alerts = MutableSharedFlow<Alert>(replay = 0, extraBufferCapacity = 32)
    val alerts: SharedFlow<Alert> = _alerts.asSharedFlow()

    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    val isRunning: Boolean get() = jobs.isNotEmpty()

    /** Reconciles the running set with the desired set of enabled hosts. */
    fun sync(hosts: List<MonitoredHost>) {
        val desired = hosts.filter { it.enabled }.associateBy { it.id }

        jobs.keys.filter { it !in desired.keys }.forEach { stop(it) }

        desired.values.forEach { host ->
            val existing = _snapshots.value[host.id]?.host
            if (jobs[host.id] == null) {
                start(host)
            } else if (existing != null && configChanged(existing, host)) {
                // Interval/protocol changed \u2014 restart with the new settings but keep history.
                stop(host.id, clearHistory = false)
                start(host)
            }
        }
    }

    fun start(host: MonitoredHost) {
        if (jobs.containsKey(host.id)) return

        updateSnapshot(host.id) { current ->
            (current ?: HostSnapshot(host)).copy(host = host, state = HostState.CHECKING)
        }

        val job = scope.launch {
            val config = LivePingSession.Config(
                target = host.target,
                protocol = host.protocol,
                port = host.port,
                intervalMs = host.intervalMs,
                timeoutMs = host.timeoutMs,
                payloadSize = host.payloadSize,
            )
            session.stream(config).collect { result ->
                ingest(host, result)
                onSample?.invoke(host, result)
            }
        }
        jobs[host.id] = job
        job.invokeOnCompletion {
            jobs.remove(host.id)
            _activeCount.value = jobs.size
        }
        _activeCount.value = jobs.size
    }

    fun stop(hostId: Long, clearHistory: Boolean = true) {
        jobs.remove(hostId)?.cancel()
        if (clearHistory) history.remove(hostId)
        updateSnapshot(hostId) { current -> current?.copy(state = HostState.IDLE) }
        _activeCount.value = jobs.size
    }

    fun stopAll() {
        jobs.keys.toList().forEach { stop(it) }
        engines.releaseAll()
        _snapshots.value = emptyMap()
        history.clear()
        _activeCount.value = 0
    }

    /** Feeds one result into the rolling window and recomputes the snapshot. */
    private suspend fun ingest(host: MonitoredHost, result: ProbeResult) {
        val window = history.getOrPut(host.id) { ArrayDeque() }
        synchronized(window) {
            window.addLast(result)
            while (window.size > historyWindow) window.removeFirst()
        }
        val recent = synchronized(window) { window.toList() }

        val stats = LatencyStatistics.compute(recent)
        val quality = QualityEvaluator.evaluate(stats)
        val previous = _snapshots.value[host.id]
        val previousState = previous?.state ?: HostState.CHECKING

        val newState = resolveState(host, result, stats.consecutiveFailures, previousState)
        val changedAt = if (newState != previousState) result.timestampMs else previous?.lastChangeMs ?: result.timestampMs

        _snapshots.update { map ->
            map + (
                host.id to HostSnapshot(
                    host = host,
                    state = newState,
                    lastResult = result,
                    stats = stats,
                    recent = recent,
                    quality = quality,
                    lastChangeMs = changedAt,
                )
                )
        }

        if (newState != previousState) {
            emitAlertFor(host, previousState, newState, result, stats.consecutiveFailures)
        }
    }

    private fun resolveState(
        host: MonitoredHost,
        result: ProbeResult,
        consecutiveFailures: Int,
        previousState: HostState,
    ): HostState = when {
        result.isSuccess && (result.rttMs ?: 0.0) > host.degradedLatencyMs -> HostState.DEGRADED
        result.isSuccess -> HostState.UP
        result.status.isEnvironmental -> previousState.takeIf { it != HostState.IDLE } ?: HostState.CHECKING
        consecutiveFailures >= host.failureThreshold -> HostState.DOWN
        previousState == HostState.DOWN -> HostState.DOWN
        else -> HostState.CHECKING
    }

    private suspend fun emitAlertFor(
        host: MonitoredHost,
        from: HostState,
        to: HostState,
        result: ProbeResult,
        consecutiveFailures: Int,
    ) {
        val alert = when {
            to == HostState.DOWN && host.notifyOnDown -> Alert(
                host = host,
                type = AlertType.DOWN,
                atMs = result.timestampMs,
                message = "${host.label} is not responding ($consecutiveFailures failed probes)",
                lastResult = result,
            )

            from == HostState.DOWN && (to == HostState.UP || to == HostState.DEGRADED) && host.notifyOnRecovery -> Alert(
                host = host,
                type = AlertType.RECOVERED,
                atMs = result.timestampMs,
                message = "${host.label} is back online",
                lastResult = result,
            )

            to == HostState.DEGRADED && from == HostState.UP -> Alert(
                host = host,
                type = AlertType.DEGRADED,
                atMs = result.timestampMs,
                message = "${host.label} latency above ${host.degradedLatencyMs} ms",
                lastResult = result,
            )

            else -> null
        }
        alert?.let { _alerts.emit(it) }
    }

    private fun updateSnapshot(hostId: Long, transform: (HostSnapshot?) -> HostSnapshot?) {
        _snapshots.update { map ->
            val updated = transform(map[hostId])
            if (updated == null) map - hostId else map + (hostId to updated)
        }
    }

    private fun configChanged(old: MonitoredHost, new: MonitoredHost): Boolean =
        old.target != new.target ||
            old.protocol != new.protocol ||
            old.port != new.port ||
            old.intervalMs != new.intervalMs ||
            old.timeoutMs != new.timeoutMs ||
            old.payloadSize != new.payloadSize

    /** Aggregate line for the persistent notification. */
    fun summaryLine(): String {
        val values = _snapshots.value.values
        if (values.isEmpty()) return "No hosts monitored"
        val down = values.count { it.state == HostState.DOWN }
        val degraded = values.count { it.state == HostState.DEGRADED }
        val up = values.count { it.state == HostState.UP }
        return buildString {
            append(up).append(" up")
            if (degraded > 0) append(" \u00b7 ").append(degraded).append(" slow")
            if (down > 0) append(" \u00b7 ").append(down).append(" down")
        }
    }
}
