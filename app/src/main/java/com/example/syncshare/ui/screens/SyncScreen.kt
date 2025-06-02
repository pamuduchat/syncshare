package com.example.syncshare.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.syncshare.viewmodels.ManageFoldersViewModel
import com.example.syncshare.viewmodels.DevicesViewModel
import android.net.Uri

@Composable
fun SyncScreen(
    modifier: Modifier = Modifier,
    devicesViewModel: DevicesViewModel,
    foldersViewModel: ManageFoldersViewModel
) {
    val selectedFolders: List<Uri> = foldersViewModel.selectedFolders
    val permissionStatus by remember { devicesViewModel.permissionRequestStatus }
    val isRefreshing by devicesViewModel.isRefreshing.collectAsState()
    val btConnectionStatus by devicesViewModel.bluetoothConnectionStatus.collectAsState()
    val p2pConnectionStatus by devicesViewModel.p2pConnectionStatus.collectAsState()
    val context = LocalContext.current
    var syncInProgress by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf("") }

    // Reset syncInProgress when isRefreshing becomes false
    LaunchedEffect(isRefreshing) {
        if (!isRefreshing) {
            syncInProgress = false
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Selected Folders to Sync", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(8.dp))
        if (selectedFolders.isEmpty()) {
            Text("No folders selected. Please add folders in the Manage Folders screen.")
        } else {
            LazyColumn(modifier = Modifier.weight(1f, fill = false).fillMaxWidth()) {
                items(selectedFolders, key = { it.toString() }) { uri ->
                    val folderName = uri.lastPathSegment ?: uri.toString()
                    Text(folderName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(4.dp))
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                syncInProgress = true
                syncMessage = "Starting sync..."
                // Sequentially sync each folder
                selectedFolders.forEach { uri ->
                    devicesViewModel.initiateSyncRequest(uri)
                }
                syncMessage = "Sync requests sent. Monitor status below."
            },
            enabled = selectedFolders.isNotEmpty() && !isRefreshing && !syncInProgress,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start Sync")
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (syncMessage.isNotEmpty()) {
            Text(syncMessage, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text("Status: $permissionStatus", style = MaterialTheme.typography.bodySmall)
        Text("Bluetooth: $btConnectionStatus", style = MaterialTheme.typography.bodySmall)
        Text("P2P: $p2pConnectionStatus", style = MaterialTheme.typography.bodySmall)
        if (isRefreshing) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
        }
    }
}