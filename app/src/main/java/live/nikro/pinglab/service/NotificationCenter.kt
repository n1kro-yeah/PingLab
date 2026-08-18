package live.nikro.pinglab.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import live.nikro.pinglab.MainActivity
import live.nikro.pinglab.R
import live.nikro.pinglab.core.util.Formatters
import live.nikro.pinglab.domain.monitor.MonitorEngine

/**
 * Owns every notification the app posts.
 *
 * Two channels on purpose: the ongoing monitoring notification must be silent and
 * un-dismissable (IMPORTANCE_LOW), while up/down alerts must be able to buzz
 * (IMPORTANCE_HIGH). Mixing them into one channel would let the user only choose
 * between "annoying" and "never notified".
 */
class NotificationCenter(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val monitor = NotificationChannel(
            CHANNEL_MONITOR,
            context.getString(R.string.notif_channel_monitor_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notif_channel_monitor_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            context.getString(R.string.notif_channel_alerts_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notif_channel_alerts_desc)
            enableVibration(true)
            setShowBadge(true)
        }
        manager.createNotificationChannels(listOf(monitor, alerts))
    }

    /** True when we are actually allowed to post. On API 33+ the user may have said no. */
    fun canPost(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            manager.areNotificationsEnabled()
        }

    fun buildMonitorNotification(activeHosts: Int, summary: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_monitor_title, activeHosts))
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setContentIntent(openAppIntent())
            .addAction(
                R.drawable.ic_notification,
                context.getString(R.string.notif_action_stop),
                MonitorActionReceiver.stopAllPendingIntent(context),
            )
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

    fun updateMonitorNotification(activeHosts: Int, summary: String) {
        if (!canPost()) return
        manager.notify(MONITOR_NOTIFICATION_ID, buildMonitorNotification(activeHosts, summary))
    }

    fun postAlert(alert: MonitorEngine.Alert, withSound: Boolean) {
        if (!canPost()) return
        val host = alert.host
        val title = when (alert.type) {
            MonitorEngine.AlertType.DOWN -> host.label + " is down"
            MonitorEngine.AlertType.RECOVERED -> host.label + " recovered"
            MonitorEngine.AlertType.DEGRADED -> host.label + " is degraded"
        }
        val body = buildString {
            append(host.displayTarget)
            append(" \u00b7 ")
            append(alert.message)
            alert.lastResult?.rttMs?.let {
                append(" \u00b7 ")
                append(Formatters.latencyWithUnit(it))
            }
            append(" \u00b7 ")
            append(Formatters.clock(alert.atMs))
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openHostIntent(host.id))
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setSilent(!withSound)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(alert.atMs)
            .apply {
                if (alert.type == MonitorEngine.AlertType.DOWN) {
                    addAction(
                        R.drawable.ic_notification,
                        context.getString(R.string.notif_action_stop),
                        MonitorActionReceiver.stopHostPendingIntent(context, host.id),
                    )
                }
            }
            .build()
        // One slot per host and alert type: a flapping host must not spam a hundred rows.
        manager.notify(alertId(host.id, alert.type), notification)
    }

    fun cancelAlertsForHost(hostId: Long) {
        MonitorEngine.AlertType.entries.forEach { type ->
            manager.cancel(alertId(hostId, type))
        }
    }

    fun cancelAllAlerts() {
        manager.cancelAll()
    }

    private fun alertId(hostId: Long, type: MonitorEngine.AlertType): Int =
        ALERT_ID_BASE + (hostId.toInt() * 8) + type.ordinal

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openHostIntent(hostId: Long): PendingIntent =
        PendingIntent.getActivity(
            context,
            hostId.toInt() + 500,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_HOST_ID, hostId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        const val CHANNEL_MONITOR = "monitor"
        const val CHANNEL_ALERTS = "alerts"
        const val MONITOR_NOTIFICATION_ID = 1_001
        private const val ALERT_ID_BASE = 5_000
    }
}
