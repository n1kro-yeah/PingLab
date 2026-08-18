package live.nikro.pinglab.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import live.nikro.pinglab.di.ServiceLocator
import live.nikro.pinglab.domain.monitor.MonitorEngine

/**
 * Foreground service that keeps probing the enabled hosts while the app is in the background.
 *
 * Design notes:
 * - `specialUse` is the honest foreground type for continuous reachability probing; `dataSync`
 *   is capped at 6 h on Android 15 and would silently kill long monitoring runs.
 * - A partial wake lock is held while monitoring so 1 s intervals stay accurate with the
 *   screen off. Doze can still batch us, which is expected and documented in the UI.
 * - The notification is refreshed on a slow ticker instead of on every sample: at 10 hosts
 *   x 1 s that would be 600 notification updates per minute.
 */
class PingMonitorService : LifecycleService() {

    private lateinit var notifications: NotificationCenter
    private lateinit var engine: MonitorEngine
    private var wakeLock: PowerManager.WakeLock? = null
    private var isForeground = false

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(applicationContext)
        notifications = NotificationCenter(this)
        notifications.ensureChannels()

        engine = MonitorEngine(
            engines = ServiceLocator.engineFactory,
            scope = lifecycleScope,
            historyWindow = HISTORY_WINDOW,
            onSample = { host, result ->
                ServiceLocator.sampleRepository.record(host.id, result)
            },
        )
        activeEngine = engine

        observeHosts()
        observeAlerts()
        startNotificationTicker()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP_MONITORING -> {
                shutdown()
                return START_NOT_STICKY
            }

            ACTION_STOP_HOST -> {
                val hostId = intent.getLongExtra(EXTRA_HOST_ID, -1L)
                if (hostId > 0L) {
                    engine.stop(hostId)
                    notifications.cancelAlertsForHost(hostId)
                    lifecycleScope.launch {
                        ServiceLocator.hostRepository.setEnabled(hostId, false)
                    }
                }
            }
        }

        promoteToForeground()
        _isRunning.value = true
        lifecycleScope.launch { applyRetention() }
        return START_STICKY
    }

    /** Mirrors the database into the engine: enabling a host in the UI starts probing it. */
    private fun observeHosts() {
        lifecycleScope.launch {
            ServiceLocator.hostRepository.enabledHosts.collect { hosts ->
                engine.sync(hosts)
                if (hosts.isEmpty()) {
                    // Nothing left to watch: do not keep an ongoing notification around.
                    shutdown()
                }
            }
        }
    }

    private fun observeAlerts() {
        lifecycleScope.launch {
            engine.alerts.collect { alert ->
                val settings = ServiceLocator.settingsRepository.settings.first()
                if (!settings.notificationsEnabled) return@collect
                val notifyForType = when (alert.type) {
                    MonitorEngine.AlertType.DOWN -> alert.host.notifyOnDown
                    MonitorEngine.AlertType.RECOVERED -> alert.host.notifyOnRecovery
                    MonitorEngine.AlertType.DEGRADED -> alert.host.notifyOnDown
                }
                if (notifyForType) {
                    notifications.postAlert(alert, withSound = settings.alertSound)
                }
            }
        }
    }

    private fun startNotificationTicker() {
        lifecycleScope.launch {
            while (isActive) {
                if (isForeground) {
                    notifications.updateMonitorNotification(
                        activeHosts = engine.activeCount.value,
                        summary = engine.summaryLine(),
                    )
                }
                delay(NOTIFICATION_REFRESH_MS)
            }
        }
    }

    private fun promoteToForeground() {
        if (isForeground) return
        val notification = notifications.buildMonitorNotification(
            activeHosts = engine.activeCount.value,
            summary = engine.summaryLine(),
        )
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NotificationCenter.MONITOR_NOTIFICATION_ID,
            notification,
            type,
        )
        isForeground = true
        acquireWakeLock()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            runCatching { acquire(WAKE_LOCK_TIMEOUT_MS) }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
        wakeLock = null
    }

    /** Housekeeping so a week of 1 s sampling cannot fill the device. */
    private suspend fun applyRetention() {
        val settings = ServiceLocator.settingsRepository.settings.first()
        val hostIds = ServiceLocator.hostRepository.all().map { it.id }
        ServiceLocator.sampleRepository.applyRetention(
            cutoffMs = System.currentTimeMillis() - settings.retentionMs,
            maxPerHost = settings.maxSamplesPerHost,
            hostIds = hostIds,
        )
    }

    private fun shutdown() {
        engine.stopAll()
        lifecycleScope.launch { ServiceLocator.sampleRepository.flush() }
        releaseWakeLock()
        isForeground = false
        _isRunning.value = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        engine.stopAll()
        releaseWakeLock()
        _isRunning.value = false
        if (activeEngine === engine) activeEngine = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_MONITORING = "live.nikro.pinglab.service.START"
        const val ACTION_STOP_MONITORING = "live.nikro.pinglab.service.STOP"
        const val ACTION_STOP_HOST = "live.nikro.pinglab.service.STOP_HOST"
        const val EXTRA_HOST_ID = "live.nikro.pinglab.service.HOST_ID"

        private const val HISTORY_WINDOW = 300
        private const val NOTIFICATION_REFRESH_MS = 4_000L
        private const val WAKE_LOCK_TAG = "PingLab:monitor"
        private const val WAKE_LOCK_TIMEOUT_MS = 12L * 60L * 60L * 1_000L

        private val _isRunning = MutableStateFlow(false)

        /** Observed by the dashboard so the monitoring toggle reflects reality. */
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        /**
         * The engine of the currently running service instance, exposed so UI can subscribe to
         * live snapshots without binding. Null when monitoring is stopped.
         */
        @Volatile
        var activeEngine: MonitorEngine? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, PingMonitorService::class.java).apply {
                action = ACTION_START_MONITORING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, PingMonitorService::class.java).apply {
                    action = ACTION_STOP_MONITORING
                },
            )
        }
    }
}
