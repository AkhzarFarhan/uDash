package com.ddpai.uploader.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ddpai.uploader.ui.config.ConfigScreen
import com.ddpai.uploader.ui.dashboard.DashboardScreen
import com.ddpai.uploader.ui.files.FileListScreen
import com.ddpai.uploader.ui.logs.LogConsoleScreen
import com.ddpai.uploader.ui.player.PlayerScreen

sealed class Dest(val route: String, val label: String, val icon: ImageVector) {
    data object Dashboard : Dest("dashboard", "Dashboard", Icons.Filled.Dashboard)
    data object Files : Dest("files", "Files", Icons.Filled.VideoLibrary)
    data object Logs : Dest("logs", "Logs", Icons.Filled.Terminal)
    data object Config : Dest("config", "Config", Icons.Filled.Settings)
}

@Composable
fun AppNav() {
    val navController = rememberNavController()
    val destinations = listOf(Dest.Dashboard, Dest.Files, Dest.Logs, Dest.Config)

    Scaffold(
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = navBackStackEntry?.destination?.route

            // Hide bottom bar when on Player screen
            if (currentRoute != null && !currentRoute.startsWith("player/")) {
                NavigationBar {
                    destinations.forEach { dest ->
                        NavigationBarItem(
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label) },
                            selected = currentRoute == dest.route,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Dest.Dashboard.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Dest.Dashboard.route) {
                DashboardScreen(navController)
            }
            composable(Dest.Files.route) {
                FileListScreen(navController)
            }
            composable(Dest.Logs.route) {
                LogConsoleScreen()
            }
            composable(Dest.Config.route) {
                ConfigScreen()
            }
            composable(
                route = "player/{fileName}",
                arguments = listOf(navArgument("fileName") { type = NavType.StringType })
            ) { backStackEntry ->
                val fileName = backStackEntry.arguments?.getString("fileName") ?: ""
                PlayerScreen(fileName)
            }
        }
    }
}
