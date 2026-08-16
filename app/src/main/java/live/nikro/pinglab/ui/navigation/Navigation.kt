package live.nikro.pinglab.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import live.nikro.pinglab.R
import live.nikro.pinglab.ui.screens.dashboard.DashboardScreen
import live.nikro.pinglab.ui.screens.detail.HostDetailScreen
import live.nikro.pinglab.ui.screens.hosts.HostsScreen
import live.nikro.pinglab.ui.screens.live.LiveScreen
import live.nikro.pinglab.ui.screens.settings.SettingsScreen
import live.nikro.pinglab.ui.screens.tools.ToolsScreen

/** Route constants. Kept as plain strings so deep links stay trivial to build. */
object Routes {
    const val DASHBOARD = "dashboard"
    const val LIVE = "live"
    const val HOSTS = "hosts"
    const val TOOLS = "tools"
    const val SETTINGS = "settings"
    const val HOST_DETAIL = "host/{hostId}"

    fun hostDetail(hostId: Long): String = "host/" + hostId
}

/** The five entries of the Material 3 navigation bar. */
enum class TopLevelDestination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
) {
    DASHBOARD(Routes.DASHBOARD, R.string.nav_dashboard, Icons.Rounded.Dashboard),
    LIVE(Routes.LIVE, R.string.nav_live, Icons.Rounded.Timeline),
    HOSTS(Routes.HOSTS, R.string.nav_hosts, Icons.Rounded.Dns),
    TOOLS(Routes.TOOLS, R.string.nav_tools, Icons.Rounded.Build),
    SETTINGS(Routes.SETTINGS, R.string.nav_settings, Icons.Rounded.Settings),
}

/**
 * Root composable: a Material 3 [NavigationBar] that hides itself on detail screens plus the
 * [NavHost] holding every screen of the app.
 *
 * @param initialTarget host pulled out of a `pinglab://host/<target>` deep link
 * @param initialHostId monitored host id delivered by a notification tap
 */
@Composable
fun PingLabApp(
    modifier: Modifier = Modifier,
    initialTarget: String? = null,
    initialHostId: Long? = null,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val barVisible = TopLevelDestination.entries.any { it.route == currentRoute }

    LaunchedEffect(initialHostId) {
        if (initialHostId != null && initialHostId > 0L) {
            navController.navigate(Routes.hostDetail(initialHostId))
        }
    }

    LaunchedEffect(initialTarget) {
        if (!initialTarget.isNullOrBlank()) {
            navController.navigate(Routes.LIVE) {
                launchSingleTop = true
            }
        }
    }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            AnimatedVisibility(
                visible = barVisible,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                NavigationBar {
                    val destination = backStackEntry?.destination
                    TopLevelDestination.entries.forEach { item ->
                        val selected = destination?.hierarchy?.any { it.route == item.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(stringResource(item.labelRes)) },
                            alwaysShowLabel = true,
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.DASHBOARD,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    onOpenHost = { hostId -> navController.navigate(Routes.hostDetail(hostId)) },
                    onAddHost = { navController.navigate(Routes.HOSTS) },
                )
            }

            composable(Routes.LIVE) {
                LiveScreen(initialTarget = initialTarget)
            }

            composable(Routes.HOSTS) {
                HostsScreen(
                    onOpenHost = { hostId -> navController.navigate(Routes.hostDetail(hostId)) },
                )
            }

            composable(Routes.TOOLS) {
                ToolsScreen(initialTarget = initialTarget)
            }

            composable(Routes.SETTINGS) {
                SettingsScreen()
            }

            composable(
                route = Routes.HOST_DETAIL,
                arguments = listOf(navArgument("hostId") { type = NavType.LongType }),
            ) { entry ->
                val hostId = entry.arguments?.getLong("hostId") ?: 0L
                HostDetailScreen(
                    hostId = hostId,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
