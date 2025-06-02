package com.example.syncshare

import android.os.Bundle
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
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.syncshare.viewmodels.DevicesViewModel.ConflictResolutionOption
import com.example.syncshare.viewmodels.DevicesViewModel.FileConflict

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    // --- Global folder mapping dialog ---
    PendingFolderMappingDialog(devicesViewModel)
    // --- Global conflict resolution dialog ---
    ConflictResolutionDialog(devicesViewModel)

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

// --- Global dialog composable ---
@Composable
fun PendingFolderMappingDialog(devicesViewModel: DevicesViewModel) {
    val context = LocalContext.current
    val pendingFolder = devicesViewModel.pendingFolderMapping.value

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri: android.net.Uri? ->
            if (uri != null && pendingFolder != null) {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                devicesViewModel.setDestinationUriForSync(pendingFolder, uri)
                devicesViewModel.pendingFolderMapping.value = null
                devicesViewModel.retryPendingSyncIfNeeded()
            }
        }
    )

    if (pendingFolder != null) {
        AlertDialog(
            onDismissRequest = { devicesViewModel.pendingFolderMapping.value = null },
            title = { Text("Select Destination Folder") },
            text = { Text("Please select a destination folder for incoming sync: '$pendingFolder'") },
            confirmButton = {
                Button(onClick = { folderPickerLauncher.launch(null) }) {
                    Text("Choose Folder")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { devicesViewModel.pendingFolderMapping.value = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ConflictResolutionDialog(devicesViewModel: DevicesViewModel) {
    val conflicts by devicesViewModel.fileConflicts.collectAsState()
    if (conflicts.isNotEmpty()) {
        val conflict = conflicts.first()
        val local = conflict.local
        val remote = conflict.remote
        val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
        AlertDialog(
            onDismissRequest = { devicesViewModel.resolveFileConflict(conflict, ConflictResolutionOption.SKIP) },
            title = { Text("File Conflict Detected") },
            text = {
                Column {
                    Text("A conflict was detected for file:", fontWeight = FontWeight.Bold)
                    Text(conflict.relativePath)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("Local Version", fontWeight = FontWeight.Bold)
                            if (local != null) {
                                Text("Size: ${local.size} bytes")
                                Text("Modified: ${dateFormat.format(Date(local.lastModified))}")
                                Text("Hash: ${local.hash.take(8)}...")
                            } else {
                                Text("(Missing)")
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Remote Version", fontWeight = FontWeight.Bold)
                            if (remote != null) {
                                Text("Size: ${remote.size} bytes")
                                Text("Modified: ${dateFormat.format(Date(remote.lastModified))}")
                                Text("Hash: ${remote.hash.take(8)}...")
                            } else {
                                Text("(Missing)")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    Button(onClick = { devicesViewModel.resolveFileConflict(conflict, ConflictResolutionOption.KEEP_LOCAL) }) {
                        Text("Keep Local")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { devicesViewModel.resolveFileConflict(conflict, ConflictResolutionOption.USE_REMOTE) }) {
                        Text("Use Remote")
                    }
                }
            },
            dismissButton = {
                Column {
                    OutlinedButton(onClick = { devicesViewModel.resolveFileConflict(conflict, ConflictResolutionOption.KEEP_BOTH) }) {
                        Text("Keep Both")
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = { devicesViewModel.resolveFileConflict(conflict, ConflictResolutionOption.SKIP) }) {
                        Text("Skip")
                    }
                }
            }
        )
    }
}