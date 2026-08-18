package live.nikro.pinglab.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Timeline
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
import live.nikro.pinglab.ui.screens.theme.ThemeScreen
import live.nikro.pinglab.ui.screens.tools.ToolsScreen

/** Route constants. Kept as plain strings so deep links stay trivial to build. */
object Routes {
    const val DASHBOARD = "dashboard"
    const val LIVE = "live"
    const val HOSTS = "hosts"
    const val TOOLS = "tools"
    const val THEME = "theme"
    const val SETTINGS = "settings"
    const val HOST_DETAIL = "host/{hostId}"

    fun hostDetail(hostId: Long): String = "host/" + hostId
}

/**
 * The five entries of the Material 3 navigation bar.
 *
 * Two icons per destination on purpose: M3 asks for the outlined symbol while a destination
 * is inactive and the filled one once it is selected, which is what makes the active pill
 * read as "you are here" without relying on colour alone.
 *
 * Settings is intentionally not a bar destination \\u2014 the bar is capped at five items, and
 * settings is reachable from the action in the Theme top bar.
 */
enum class TopLevelDestination(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    DASHBOARD(
        Routes.DASHBOARD,
        R.string.nav_dashboard,
        Icons.Filled.Dashboard,
        Icons.Outlined.Dashboard,
    ),
    LIVE(
        Routes.LIVE,
        R.string.nav_live,
        Icons.Filled.Timeline,
        Icons.Outlined.Timeline,
    ),
    HOSTS(
        Routes.HOSTS,
        R.string.nav_hosts,
        Icons.Filled.Dns,
        Icons.Outlined.Dns,
    ),
    TOOLS(
        Routes.TOOLS,
        R.string.nav_tools,
        Icons.Filled.Build,
        Icons.Outlined.Build,
    ),
    THEME(
        Routes.THEME,
        R.string.nav_theme,
        Icons.Filled.Palette,
        Icons.Outlined.Palette,
    ),
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
    // Settings keeps the bar on screen even though it is not one of its items, so the user
    // is never stranded on a screen with no way back to a top-level destination.
    val barVisible = TopLevelDestination.entries.any { it.route == currentRoute } ||
        currentRoute == Routes.SETTINGS

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
                            icon = {
                                Icon(
                                    imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = null,
                                )
                            },
                            label = {
                                // Five tabs on a phone: a wrapping label used to break
                                // "Instruments" across two lines and shove the bar taller.
                                Text(
                                    text = stringResource(item.labelRes),
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            },
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

            composable(Routes.THEME) {
                ThemeScreen(
                    onOpenSettings = {
                        navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                    },
                )
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
