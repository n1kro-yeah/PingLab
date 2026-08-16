package live.nikro.pinglab

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import live.nikro.pinglab.data.prefs.AppSettings
import live.nikro.pinglab.di.ServiceLocator
import live.nikro.pinglab.service.PingMonitorService
import live.nikro.pinglab.ui.navigation.PingLabApp
import live.nikro.pinglab.ui.theme.PingLabTheme

/**
 * Single activity host.
 *
 * Handles three entry points:
 *  - normal launch
 *  - notification tap, which carries [EXTRA_HOST_ID]
 *  - `pinglab://host/<target>` deep link, which pre-fills the live screen
 */
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result ignored */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ServiceLocator.init(applicationContext)

        val initialHostId = intent?.getLongExtra(EXTRA_HOST_ID, 0L)?.takeIf { it > 0L }
        val initialTarget = targetFromDeepLink(intent)

        requestNotificationPermissionIfNeeded()
        maybeAutoStartMonitoring()

        setContent {
            val settings by ServiceLocator.settingsRepository.settings
                .collectAsState(initial = AppSettings())

            keepScreenOn(settings.keepScreenOn)

            PingLabTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.useDynamicColor,
            ) {
                PingLabApp(
                    initialTarget = initialTarget,
                    initialHostId = initialHostId,
                )
            }
        }
    }

    private fun keepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** Extracts the host from `pinglab://host/<target>` links and plain `ping:` style URIs. */
    private fun targetFromDeepLink(intent: Intent?): String? {
        val data = intent?.data ?: return null
        return when {
            data.scheme == "pinglab" -> data.pathSegments?.lastOrNull() ?: data.host
            data.host != null -> data.host
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Restarts background monitoring after a reboot or cold start when the user asked for it. */
    private fun maybeAutoStartMonitoring() {
        lifecycleScope.launch {
            val settings = ServiceLocator.settingsRepository.settings.first()
            if (!settings.autoStartMonitoring) return@launch
            if (PingMonitorService.isRunning.value) return@launch
            if (ServiceLocator.hostRepository.enabled().isEmpty()) return@launch
            PingMonitorService.start(this@MainActivity)
        }
    }

    companion object {
        /** Set by [live.nikro.pinglab.service.NotificationCenter] when a host alert is tapped. */
        const val EXTRA_HOST_ID = "live.nikro.pinglab.extra.OPEN_HOST_ID"
    }
}
