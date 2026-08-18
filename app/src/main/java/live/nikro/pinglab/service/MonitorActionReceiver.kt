package live.nikro.pinglab.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles the buttons rendered on monitoring notifications.
 *
 * A receiver (rather than a service PendingIntent) keeps the notification actions working even
 * when the process was killed: the system revives it, we forward the command to
 * [PingMonitorService] and return immediately.
 */
class MonitorActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP_ALL -> {
                context.startService(
                    Intent(context, PingMonitorService::class.java).apply {
                        action = PingMonitorService.ACTION_STOP_MONITORING
                    },
                )
            }

            ACTION_STOP_HOST -> {
                val hostId = intent.getLongExtra(EXTRA_HOST_ID, -1L)
                if (hostId > 0L) {
                    context.startService(
                        Intent(context, PingMonitorService::class.java).apply {
                            action = PingMonitorService.ACTION_STOP_HOST
                            putExtra(PingMonitorService.EXTRA_HOST_ID, hostId)
                        },
                    )
                }
            }

            ACTION_DISMISS_ALERTS -> NotificationCenter(context).cancelAllAlerts()
        }
    }

    companion object {
        const val ACTION_STOP_ALL = "live.nikro.pinglab.action.STOP_ALL"
        const val ACTION_STOP_HOST = "live.nikro.pinglab.action.STOP_HOST"
        const val ACTION_DISMISS_ALERTS = "live.nikro.pinglab.action.DISMISS_ALERTS"
        const val EXTRA_HOST_ID = "live.nikro.pinglab.extra.HOST_ID"

        private const val REQUEST_STOP_ALL = 100
        private const val REQUEST_DISMISS = 101
        private const val REQUEST_HOST_BASE = 1_000

        private val flags: Int
            get() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        fun stopAllPendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                REQUEST_STOP_ALL,
                Intent(context, MonitorActionReceiver::class.java).setAction(ACTION_STOP_ALL),
                flags,
            )

        fun stopHostPendingIntent(context: Context, hostId: Long): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                REQUEST_HOST_BASE + (hostId % 1000).toInt(),
                Intent(context, MonitorActionReceiver::class.java)
                    .setAction(ACTION_STOP_HOST)
                    .putExtra(EXTRA_HOST_ID, hostId),
                flags,
            )

        fun dismissAlertsPendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                REQUEST_DISMISS,
                Intent(context, MonitorActionReceiver::class.java).setAction(ACTION_DISMISS_ALERTS),
                flags,
            )
    }
}
