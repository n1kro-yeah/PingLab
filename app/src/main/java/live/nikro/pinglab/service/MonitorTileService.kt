package live.nikro.pinglab.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import live.nikro.pinglab.MainActivity
import live.nikro.pinglab.R

/**
 * Quick Settings tile that turns background monitoring on and off from the shade.
 *
 * Two platform rules shape this class:
 *  - a tile may not start a foreground service on recent Android versions, so starting monitoring
 *    goes through [MainActivity], which is allowed to do it the moment it is on screen;
 *  - stopping is fine, because the service is already foreground: the same broadcast the
 *    notification's Stop button uses is reused here.
 */
class MonitorTileService : TileService() {

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        val listeningScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        scope = listeningScope
        // The tile is only visible while the shade is open, so the collector lives exactly as
        // long as the system listens.
        PingMonitorService.isRunning
            .onEach { running -> render(running) }
            .launchIn(listeningScope)
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        render(PingMonitorService.isRunning.value)
    }

    override fun onClick() {
        super.onClick()
        if (PingMonitorService.isRunning.value) {
            runCatching { MonitorActionReceiver.stopAllPendingIntent(this).send() }
            render(running = false)
        } else {
            openApp()
        }
    }

    private fun render(running: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_monitor_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_notification)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                if (running) R.string.tile_monitor_on else R.string.tile_monitor_off,
            )
        }
        tile.updateTile()
    }

    private fun openApp() {
        if (isSecure && isLocked) {
            unlockAndRun { launchApp() }
        } else {
            launchApp()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun launchApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
            .putExtra(MainActivity.EXTRA_START_MONITORING, true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14 rejects the Intent overload outright and demands a PendingIntent.
            val pending = PendingIntent.getActivity(
                this,
                REQUEST_OPEN_APP,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pending)
        } else {
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        const val REQUEST_OPEN_APP = 4_200
    }
}
