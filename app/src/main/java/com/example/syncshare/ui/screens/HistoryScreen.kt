package com.example.syncshare.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.syncshare.viewmodels.DevicesViewModel
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf

@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier,
    devicesViewModel: DevicesViewModel
) {
    val syncHistory = devicesViewModel.syncHistory
    val showDialog = remember { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Sync History", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Button(
                onClick = { showDialog.value = true },
                enabled = syncHistory.isNotEmpty()
            ) {
                Text("Clear History")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (syncHistory.isEmpty()) {
            Text("No sync history yet.")
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                items(syncHistory) { entry ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(entry.formattedTimestamp, style = MaterialTheme.typography.labelSmall)
                            Text("Folder: ${entry.folderName}", style = MaterialTheme.typography.bodyLarge)
                            Text("Status: ${entry.status}", style = MaterialTheme.typography.bodyMedium)
                            Text(entry.details, style = MaterialTheme.typography.bodySmall)
                            entry.peerDeviceName?.let {
                                Text("Peer: $it", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
    if (showDialog.value) {
        AlertDialog(
            onDismissRequest = { showDialog.value = false },
            title = { Text("Clear Sync History") },
            text = { Text("Are you sure you want to clear the sync history?") },
            confirmButton = {
                TextButton(onClick = {
                    devicesViewModel.clearHistory()
                    showDialog.value = false
                }) { Text("Yes") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog.value = false }) { Text("No") }
            }
        )
    }
}