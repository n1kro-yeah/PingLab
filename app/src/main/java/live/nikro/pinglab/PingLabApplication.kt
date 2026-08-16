package live.nikro.pinglab

import android.app.Application
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import live.nikro.pinglab.di.ServiceLocator
import live.nikro.pinglab.service.NotificationCenter
import live.nikro.pinglab.service.PingMonitorService

class PingLabApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        NotificationCenter(this).ensureChannels()

        ServiceLocator.applicationScope.launch {
            val settings = ServiceLocator.settingsRepository.settings.first()

            // First launch: give the user something to look at instead of an empty list.
            if (!settings.hasSeededHosts) {
                val seeded = ServiceLocator.hostRepository.seedDefaultsIfEmpty()
                if (seeded) ServiceLocator.settingsRepository.markHostsSeeded()
            }

            // Retention sweep on cold start: monitoring may have been killed mid-run.
            val hostIds = ServiceLocator.hostRepository.all().map { it.id }
            ServiceLocator.sampleRepository.applyRetention(
                cutoffMs = System.currentTimeMillis() - settings.retentionMs,
                maxPerHost = settings.maxSamplesPerHost,
                hostIds = hostIds,
            )
            ServiceLocator.exportManager.pruneOldExports()

            if (settings.autoStartMonitoring && ServiceLocator.hostRepository.enabled().isNotEmpty()) {
                PingMonitorService.start(this@PingLabApplication)
            }
        }
    }
}
