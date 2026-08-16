package live.nikro.pinglab.ui.screens.tools

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import live.nikro.pinglab.core.model.DnsLookupResult
import live.nikro.pinglab.core.model.PortProbe
import live.nikro.pinglab.core.model.PortState
import live.nikro.pinglab.core.model.ScanTrust
import live.nikro.pinglab.core.model.TracerouteHop
import live.nikro.pinglab.core.model.TlsReport
import live.nikro.pinglab.core.net.PortScanner
import live.nikro.pinglab.core.net.TracerouteEngine
import live.nikro.pinglab.core.net.WolSender
import live.nikro.pinglab.core.util.HostValidator
import live.nikro.pinglab.core.util.TargetValidation
import live.nikro.pinglab.data.export.ExportManager
import live.nikro.pinglab.di.ServiceLocator

enum class ToolTab(val label: String) {
    TRACEROUTE("Trace"),
    DNS("DNS"),
    PORTS("Ports"),
    TLS("TLS"),
    WOL("WOL"),
}

/** Which set of ports the scanner should sweep. */
enum class PortPreset(val label: String) {
    COMMON("Common"),
    TOP_1024("1-1024"),
    CUSTOM("Custom"),
}

data class TracerouteState(
    val target: String = "",
    val running: Boolean = false,
    val resolvedAddress: String? = null,
    val hops: List<TracerouteHop> = emptyList(),
    val status: String? = null,
    val reachedDestination: Boolean = false,
)

data class DnsState(
    val host: String = "",
    val running: Boolean = false,
    val result: DnsLookupResult? = null,
)

/** Certificate and HTTP health of one endpoint. */
data class TlsState(
    val host: String = "",
    val port: String = "443",
    val running: Boolean = false,
    val report: TlsReport? = null,
)

/** Wake-on-LAN form plus the last few send attempts. */
data class WolState(
    val mac: String = "",
    val broadcast: String = WolSender.DEFAULT_BROADCAST,
    val port: String = "9",
    val sending: Boolean = false,
    val log: List<String> = emptyList(),
)

data class PortScanState(
    val host: String = "",
    val preset: PortPreset = PortPreset.COMMON,
    val customRange: String = "1-1024",
    val running: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    val results: List<PortProbe> = emptyList(),
    val elapsedMs: Long = 0L,
    val finished: Boolean = false,
    val resolvedAddress: String? = null,
    val stage: PortScanner.Stage? = null,
    val trust: ScanTrust = ScanTrust.UNKNOWN,
    val controlAccepted: Int = 0,
    val controlSamples: Int = 0,
    val openCount: Int = 0,
    val acceptedCount: Int = 0,
    val closedCount: Int = 0,
    val filteredCount: Int = 0,
) {
    val progress: Float? get() = if (total <= 0) null else completed.toFloat() / total

    /** Ports where a service actually proved itself. */
    val open: List<PortProbe> get() = results.filter { it.state == PortState.OPEN }

    /** Handshake completed, but nothing behind it behaved like a service. */
    val unverified: List<PortProbe> get() = results.filter { it.state == PortState.ACCEPTED }
}

data class ToolsUiState(
    val tab: ToolTab = ToolTab.TRACEROUTE,
    val traceroute: TracerouteState = TracerouteState(),
    val dns: DnsState = DnsState(),
    val ports: PortScanState = PortScanState(),
    val tls: TlsState = TlsState(),
    val wol: WolState = WolState(),
    val message: String? = null,
    val pendingExport: ExportManager.Export? = null,
)

/**
 * Diagnostics that run on demand. Each tool owns its own Job so switching tabs never
 * silently kills a scan the user started.
 */
class ToolsViewModel : ViewModel() {

    private val tracerouteEngine: TracerouteEngine = ServiceLocator.tracerouteEngine
    private val dnsLookupTool = ServiceLocator.dnsLookupTool
    private val portScanner: PortScanner = ServiceLocator.portScanner
    private val tlsInspector = ServiceLocator.tlsInspector
    private val wolSender = ServiceLocator.wolSender
    private val exportManager = ServiceLocator.exportManager

    private val _state = MutableStateFlow(ToolsUiState())
    val state: StateFlow<ToolsUiState> = _state.asStateFlow()

    private var tracerouteJob: Job? = null
    private var dnsJob: Job? = null
    private var portJob: Job? = null
    private var tlsJob: Job? = null
    private var wolJob: Job? = null

    fun selectTab(tab: ToolTab) {
        _state.update { it.copy(tab = tab) }
    }

    // ---------------------------------------------------------------- traceroute

    fun onTracerouteTargetChange(value: String) {
        _state.update { it.copy(traceroute = it.traceroute.copy(target = value)) }
    }

    fun toggleTraceroute() {
        if (_state.value.traceroute.running) {
            tracerouteJob?.cancel()
            tracerouteJob = null
            _state.update {
                it.copy(traceroute = it.traceroute.copy(running = false, status = "Stopped"))
            }
            return
        }

        val target = _state.value.traceroute.target.trim()
        if (!HostValidator.isValid(target)) {
            _state.update { it.copy(message = "Enter a valid host or IP") }
            return
        }

        _state.update {
            it.copy(
                traceroute = it.traceroute.copy(
                    running = true,
                    hops = emptyList(),
                    status = "Tracing route...",
                    reachedDestination = false,
                    resolvedAddress = null,
                ),
            )
        }

        tracerouteJob = tracerouteEngine.trace(target)
            .onEach { event -> handleTracerouteEvent(event) }
            .onCompletion {
                _state.update { it.copy(traceroute = it.traceroute.copy(running = false)) }
            }
            .launchIn(viewModelScope)
    }

    private fun handleTracerouteEvent(event: TracerouteEngine.Event) {
        _state.update { current ->
            val trace = current.traceroute
            val updated = when (event) {
                is TracerouteEngine.Event.Started -> trace.copy(
                    resolvedAddress = event.resolvedAddress,
                    status = "Tracing to " + event.resolvedAddress,
                )

                is TracerouteEngine.Event.Hop -> trace.copy(hops = trace.hops + event.hop)

                is TracerouteEngine.Event.Finished -> trace.copy(
                    running = false,
                    reachedDestination = event.reachedDestination,
                    status = if (event.reachedDestination) {
                        "Destination reached in " + event.totalHops + " hops"
                    } else {
                        "Stopped after " + event.totalHops + " hops"
                    },
                )

                is TracerouteEngine.Event.Failed -> trace.copy(
                    running = false,
                    status = event.reason,
                )
            }
            current.copy(traceroute = updated)
        }
    }

    fun exportTraceroute() {
        val trace = _state.value.traceroute
        if (trace.hops.isEmpty()) return
        viewModelScope.launch {
            runCatching { exportManager.exportTraceroute(trace.target, trace.hops) }
                .onSuccess { export -> _state.update { it.copy(pendingExport = export) } }
                .onFailure { error ->
                    _state.update { it.copy(message = "Export failed: " + (error.message ?: "unknown")) }
                }
        }
    }

    // ----------------------------------------------------------------------- dns

    fun onDnsHostChange(value: String) {
        _state.update { it.copy(dns = it.dns.copy(host = value)) }
    }

    fun runDnsLookup() {
        val host = _state.value.dns.host.trim()
        if (host.isEmpty()) {
            _state.update { it.copy(message = "Enter a hostname") }
            return
        }
        dnsJob?.cancel()
        _state.update { it.copy(dns = it.dns.copy(running = true, result = null)) }
        dnsJob = viewModelScope.launch {
            val result = runCatching { dnsLookupTool.lookup(host) }.getOrNull()
            _state.update { it.copy(dns = it.dns.copy(running = false, result = result)) }
        }
    }

    // --------------------------------------------------------------------- ports

    fun onPortHostChange(value: String) {
        _state.update { it.copy(ports = it.ports.copy(host = value)) }
    }

    fun onPresetChange(preset: PortPreset) {
        _state.update { it.copy(ports = it.ports.copy(preset = preset)) }
    }

    fun onCustomRangeChange(value: String) {
        _state.update { it.copy(ports = it.ports.copy(customRange = value)) }
    }

    fun togglePortScan() {
        if (_state.value.ports.running) {
            portJob?.cancel()
            portJob = null
            _state.update { it.copy(ports = it.ports.copy(running = false)) }
            return
        }

        val ports = _state.value.ports
        val host = ports.host.trim()
        if (!HostValidator.isValid(host)) {
            _state.update { it.copy(message = "Enter a valid host or IP") }
            return
        }

        val portList = resolvePorts(ports)
        if (portList.isEmpty()) {
            _state.update { it.copy(message = "No ports to scan") }
            return
        }

        _state.update {
            it.copy(
                ports = it.ports.copy(
                    running = true,
                    finished = false,
                    results = emptyList(),
                    completed = 0,
                    total = portList.size,
                    elapsedMs = 0L,
                    resolvedAddress = null,
                    stage = PortScanner.Stage.CONTROL,
                    trust = ScanTrust.UNKNOWN,
                    controlAccepted = 0,
                    controlSamples = 0,
                    openCount = 0,
                    acceptedCount = 0,
                    closedCount = 0,
                    filteredCount = 0,
                ),
            )
        }

        portJob = portScanner.scan(host, portList)
            .onEach { event -> handlePortEvent(event) }
            .onCompletion {
                _state.update { it.copy(ports = it.ports.copy(running = false)) }
            }
            .launchIn(viewModelScope)
    }

    private fun handlePortEvent(event: PortScanner.Event) {
        _state.update { current ->
            val ports = current.ports
            var message = current.message
            val updated = when (event) {
                is PortScanner.Event.Resolved -> ports.copy(resolvedAddress = event.address)

                is PortScanner.Event.Control -> ports.copy(
                    trust = event.trust,
                    controlAccepted = event.accepted,
                    controlSamples = event.samples,
                )

                is PortScanner.Event.Progress -> ports.copy(
                    completed = event.completed,
                    total = event.total,
                    stage = event.stage,
                )

                is PortScanner.Event.Result -> ports.copy(
                    results = (ports.results + event.probe).sortedBy { it.port },
                )

                is PortScanner.Event.Done -> ports.copy(
                    running = false,
                    finished = true,
                    stage = null,
                    completed = event.summary.scanned,
                    total = event.summary.scanned,
                    elapsedMs = event.summary.elapsedMs,
                    trust = event.summary.trust,
                    openCount = event.summary.open,
                    acceptedCount = event.summary.accepted,
                    closedCount = event.summary.closed,
                    filteredCount = event.summary.filtered,
                )

                is PortScanner.Event.Failed -> {
                    message = event.reason
                    ports.copy(running = false, finished = true, stage = null)
                }
            }
            current.copy(ports = updated, message = message)
        }
    }

    private fun resolvePorts(state: PortScanState): List<Int> = when (state.preset) {
        PortPreset.COMMON -> PortScanner.COMMON_PORTS
        PortPreset.TOP_1024 -> PortScanner.rangeOf(1, 1_024)
        PortPreset.CUSTOM -> parseRange(state.customRange)
    }

    /** Accepts `80`, `80,443`, `1-1024` and any mix of those. */
    private fun parseRange(raw: String): List<Int> {
        val ports = linkedSetOf<Int>()
        raw.split(',').forEach { chunk ->
            val part = chunk.trim()
            if (part.isEmpty()) return@forEach
            if (part.contains('-')) {
                val bounds = part.split('-')
                val from = bounds.getOrNull(0)?.trim()?.toIntOrNull()
                val to = bounds.getOrNull(1)?.trim()?.toIntOrNull()
                if (from != null && to != null) ports.addAll(PortScanner.rangeOf(from, to))
            } else {
                part.toIntOrNull()?.takeIf { it in 1..65_535 }?.let(ports::add)
            }
        }
        return ports.toList().take(MAX_CUSTOM_PORTS)
    }

    // -------------------------------------------------------------------- shared

    fun prefillFromTarget(target: String) {
        val host = (HostValidator.parseOrNull(target))?.host ?: target
        _state.update {
            it.copy(
                traceroute = it.traceroute.copy(target = host),
                dns = it.dns.copy(host = host),
                ports = it.ports.copy(host = host),
            )
        }
    }

    fun validateTarget(raw: String): String? =
        (HostValidator.validate(raw) as? TargetValidation.Invalid)
            ?.reason
            ?.let(HostValidator::describe)

    // ---------------------------------------------------------------- tls / https

    fun onTlsHostChange(value: String) {
        _state.update { it.copy(tls = it.tls.copy(host = value)) }
    }

    fun onTlsPortChange(value: String) {
        val digits = value.filter { character -> character.isDigit() }.take(5)
        _state.update { it.copy(tls = it.tls.copy(port = digits)) }
    }

    /** Runs a full handshake plus one HTTP request; slow by design, roughly a second. */
    fun inspectTls() {
        val current = _state.value.tls
        if (current.running) return
        val host = current.host.trim()
        if (host.isEmpty()) {
            _state.update { it.copy(message = "Enter a host, for example example.com") }
            return
        }
        val port = current.port.toIntOrNull() ?: tlsInspector.portHint(host) ?: 443
        if (port !in 1..65_535) {
            _state.update { it.copy(message = "Port must be between 1 and 65535") }
            return
        }
        _state.update { it.copy(tls = it.tls.copy(running = true, report = null)) }
        tlsJob?.cancel()
        tlsJob = viewModelScope.launch {
            val report = tlsInspector.inspect(host, port)
            _state.update { it.copy(tls = it.tls.copy(running = false, report = report)) }
        }
    }

    // ---------------------------------------------------------------- wake on lan

    fun onMacChange(value: String) {
        _state.update { it.copy(wol = it.wol.copy(mac = value.take(23))) }
    }

    fun onWolBroadcastChange(value: String) {
        _state.update { it.copy(wol = it.wol.copy(broadcast = value.trim())) }
    }

    fun onWolPortChange(value: String) {
        val digits = value.filter { character -> character.isDigit() }.take(5)
        _state.update { it.copy(wol = it.wol.copy(port = digits)) }
    }

    fun sendWol() {
        val current = _state.value.wol
        if (current.sending) return
        if (wolSender.parseMac(current.mac) == null) {
            _state.update { it.copy(message = "MAC must look like AA:BB:CC:DD:EE:FF") }
            return
        }
        val port = current.port.toIntOrNull() ?: WolSender.DEFAULT_PORT
        _state.update { it.copy(wol = it.wol.copy(sending = true)) }
        wolJob?.cancel()
        wolJob = viewModelScope.launch {
            val result = wolSender.wake(current.mac, current.broadcast, port)
            _state.update { state ->
                state.copy(
                    wol = state.wol.copy(
                        sending = false,
                        log = (listOf(result.message) + state.wol.log).take(5),
                    ),
                    message = if (result.success) result.message else "Failed: " + result.message,
                )
            }
        }
    }

    fun shareIntentFor(export: ExportManager.Export): Intent = exportManager.shareIntent(export)

    fun consumeExport() {
        _state.update { it.copy(pendingExport = null) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    override fun onCleared() {
        tracerouteJob?.cancel()
        dnsJob?.cancel()
        portJob?.cancel()
        tlsJob?.cancel()
        wolJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val MAX_CUSTOM_PORTS = 4_096
    }
}
