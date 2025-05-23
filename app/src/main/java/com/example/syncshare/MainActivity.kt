package com.example.syncshare

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar // Material 3 Bottom Nav
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.syncshare.navigation.Screen
import com.example.syncshare.navigation.bottomNavItems
import com.example.syncshare.ui.screens.* // Import your screens
import com.example.syncshare.ui.theme.SyncshareTheme
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.syncshare.viewmodels.DevicesViewModel
import com.example.syncshare.viewmodels.ManageFoldersViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("SyncShareApp", "MainActivity onCreate CALLED")
        enableEdgeToEdge() // Manages system bars for edge-to-edge
        setContent {
            SyncshareTheme {
                MainAppScreen()
            }
        }
    }
}

@Composable
fun MainAppScreen() {
    val navController = rememberNavController()
    // Provide shared ViewModels at the top level
    val devicesViewModel: DevicesViewModel = viewModel()
    val foldersViewModel: ManageFoldersViewModel = viewModel()

    Scaffold(
        bottomBar = {
            NavigationBar { // Material 3 component
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                bottomNavItems.forEach { navItem ->
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any { it.route == navItem.screen.route } == true,
                        onClick = {
                            navController.navigate(navItem.screen.route) {
                                // Pop up to the start destination of the graph to
                                // avoid building up a large stack of destinations
                                // on the back stack as users select items
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                // Avoid multiple copies of the same destination when
                                // reselecting the same item
                                launchSingleTop = true
                                // Restore state when reselecting a previously selected item
                                restoreState = true
                            }
                        },
                        icon = { Icon(navItem.icon, contentDescription = navItem.screen.title) },
                        label = { Text(navItem.screen.title) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.ManageFolders.route, // Default screen
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.ManageFolders.route) { ManageFoldersScreen(viewModel = foldersViewModel) }
            composable(Screen.Devices.route) { DevicesScreen(viewModel = devicesViewModel) }
            composable(Screen.Sync.route) { SyncScreen(devicesViewModel = devicesViewModel, foldersViewModel = foldersViewModel) }
            composable(Screen.History.route) { HistoryScreen(devicesViewModel = devicesViewModel) }
            composable(Screen.Settings.route) { SettingsScreen() }
        }
    }
}