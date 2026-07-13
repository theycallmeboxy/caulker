package com.theycallmeboxy.caulker.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.theycallmeboxy.caulker.ui.screens.collections.CollectionsScreen
import com.theycallmeboxy.caulker.ui.screens.dashboard.DashboardScreen
import com.theycallmeboxy.caulker.ui.screens.firmware.FirmwareScreen
import com.theycallmeboxy.caulker.ui.screens.gamedetail.GameDetailScreen
import com.theycallmeboxy.caulker.ui.screens.games.GamesScreen
import com.theycallmeboxy.caulker.ui.screens.login.LoginScreen
import com.theycallmeboxy.caulker.ui.screens.platformsettings.PlatformSettingsScreen
import com.theycallmeboxy.caulker.ui.screens.platforms.PlatformsScreen
import com.theycallmeboxy.caulker.ui.screens.savesync.SaveSyncAllScreen
import com.theycallmeboxy.caulker.ui.screens.savesync.SaveSyncScreen
import com.theycallmeboxy.caulker.ui.screens.settings.SettingsScreen
import com.theycallmeboxy.caulker.ui.screens.setup.SetupScreen
import com.theycallmeboxy.caulker.ui.screens.sync.SyncProgressScreen

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Setup : Screen("setup")
    object Dashboard : Screen("dashboard")
    object Platforms : Screen("platforms")
    object Games : Screen("games/{platformId}") {
        fun route(platformId: Int) = "games/$platformId"
    }
    object GameDetail : Screen("game/{romId}") {
        fun route(romId: Int) = "game/$romId"
    }
    object SaveSync : Screen("savesync/{romId}") {
        fun route(romId: Int) = "savesync/$romId"
    }
    object Firmware : Screen("firmware/{platformId}") {
        fun route(platformId: Int) = "firmware/$platformId"
    }
    object GlobalSaveSync : Screen("savesync_all")
    object Settings : Screen("settings")
    object PlatformSettings : Screen("platform_settings/{platformId}") {
        fun route(platformId: Int) = "platform_settings/$platformId"
    }
    object SyncProgress : Screen("sync_progress")
    object Collections : Screen("collections")
}

@Composable
fun AppNavigation(startDestination: String) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = startDestination) {

        composable(Screen.Login.route) {
            LoginScreen(onLoginSuccess = {
                navController.navigate(Screen.Setup.route) {
                    popUpTo(Screen.Login.route) { inclusive = true }
                }
            })
        }

        composable(Screen.Setup.route) {
            SetupScreen(onComplete = {
                navController.navigate(Screen.Dashboard.route) {
                    popUpTo(Screen.Setup.route) { inclusive = true }
                }
            })
        }

        composable(Screen.Dashboard.route) {
            DashboardScreen(
                onLibraryClick = { navController.navigate(Screen.Platforms.route) },
                onCollectionsClick = { navController.navigate(Screen.Collections.route) },
                onSaveSyncClick = { navController.navigate(Screen.GlobalSaveSync.route) },
                onSettingsClick = { navController.navigate(Screen.Settings.route) },
                onSyncDatabaseClick = { navController.navigate(Screen.SyncProgress.route) }
            )
        }

        composable(Screen.Collections.route) {
            CollectionsScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Platforms.route) {
            PlatformsScreen(
                onPlatformClick = { navController.navigate(Screen.Games.route(it)) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.GlobalSaveSync.route) {
            SaveSyncAllScreen(
                onBack = { navController.popBackStack() },
                onGameClick = { navController.navigate(Screen.GameDetail.route(it)) }
            )
        }

        composable(
            route = Screen.Games.route,
            arguments = listOf(navArgument("platformId") { type = NavType.IntType })
        ) { back ->
            val platformId = back.arguments!!.getInt("platformId")
            GamesScreen(
                platformId = platformId,
                onGameClick = { navController.navigate(Screen.GameDetail.route(it)) },
                onBack = { navController.popBackStack() },
                onSettingsClick = { navController.navigate(Screen.PlatformSettings.route(platformId)) }
            )
        }

        composable(
            route = Screen.GameDetail.route,
            arguments = listOf(navArgument("romId") { type = NavType.IntType })
        ) { back ->
            GameDetailScreen(
                romId = back.arguments!!.getInt("romId"),
                onSyncSavesClick = { navController.navigate(Screen.SaveSync.route(it)) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.SaveSync.route,
            arguments = listOf(navArgument("romId") { type = NavType.IntType })
        ) { back ->
            SaveSyncScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Firmware.route,
            arguments = listOf(navArgument("platformId") { type = NavType.IntType })
        ) { back ->
            FirmwareScreen(
                platformId = back.arguments!!.getInt("platformId"),
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onLogout = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onForceFullResync = { navController.navigate(Screen.SyncProgress.route) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.PlatformSettings.route,
            arguments = listOf(navArgument("platformId") { type = NavType.IntType })
        ) { back ->
            val platformId = back.arguments!!.getInt("platformId")
            PlatformSettingsScreen(
                onBack = { navController.popBackStack() },
                onFirmwareClick = { navController.navigate(Screen.Firmware.route(platformId)) }
            )
        }

        composable(Screen.SyncProgress.route) {
            SyncProgressScreen(
                onDone = { navController.popBackStack() },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
