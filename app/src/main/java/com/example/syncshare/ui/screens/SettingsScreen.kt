package com.example.syncshare.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.net.Uri
import com.example.syncshare.viewmodels.DevicesViewModel

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    devicesViewModel: DevicesViewModel? = null
) {
    val context = LocalContext.current
    var autoSync by remember { mutableStateOf(false) }
    var conflictResolution by remember { mutableStateOf("Ask") }
    var preferConnection by remember { mutableStateOf("Wi-Fi Direct") }
    var allowMetered by remember { mutableStateOf(false) }
    var notifySync by remember { mutableStateOf(true) }
    var notifyError by remember { mutableStateOf(true) }
    var diagnosticsDialog by remember { mutableStateOf(false) }
    var diagnosticsText by remember { mutableStateOf("") }
    var defaultIncomingFolder by remember { mutableStateOf<Uri?>(null) }
    var aboutDialog by remember { mutableStateOf(false) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri: Uri? ->
            if (uri != null) {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                defaultIncomingFolder = uri
                // TODO: Save to ViewModel or preferences
            }
        }
    )

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Divider()
        // Default Incoming Folder
        Text("Default Incoming Folder", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { folderPickerLauncher.launch(null) }) {
                Text("Choose Folder")
            }
            Spacer(Modifier.width(8.dp))
            Text(defaultIncomingFolder?.let { it.toString().take(30) + if (it.toString().length > 30) "..." else "" } ?: "Not set")
        }
        // Sync Options
        Text("Sync Options", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = autoSync, onCheckedChange = { autoSync = it })
            Spacer(Modifier.width(8.dp))
            Text("Auto-sync on connect")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Conflict resolution default:")
            Spacer(Modifier.width(8.dp))
            DropdownMenuBox(
                selected = conflictResolution,
                options = listOf("Ask", "Keep Local", "Use Remote", "Keep Both"),
                onSelected = { conflictResolution = it }
            )
        }
        // Connection Preferences
        Text("Connection Preferences", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Prefer:")
            Spacer(Modifier.width(8.dp))
            DropdownMenuBox(
                selected = preferConnection,
                options = listOf("Wi-Fi Direct", "Bluetooth"),
                onSelected = { preferConnection = it }
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = allowMetered, onCheckedChange = { allowMetered = it })
            Spacer(Modifier.width(8.dp))
            Text("Allow sync over metered networks")
        }
        // Notifications
        Text("Notifications", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = notifySync, onCheckedChange = { notifySync = it })
            Spacer(Modifier.width(8.dp))
            Text("Sync notifications")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = notifyError, onCheckedChange = { notifyError = it })
            Spacer(Modifier.width(8.dp))
            Text("Error notifications")
        }
        // Diagnostics
        Text("Diagnostics & Advanced", style = MaterialTheme.typography.titleMedium)
        Button(onClick = {
            diagnosticsText = devicesViewModel?.checkWifiDirectStatus() ?: "Diagnostics not available."
            diagnosticsDialog = true
        }) {
            Text("Show Diagnostics")
        }
        // About
        Text("About", style = MaterialTheme.typography.titleMedium)
        Button(onClick = { aboutDialog = true }) {
            Text("About this app")
        }
    }
    if (diagnosticsDialog) {
        AlertDialog(
            onDismissRequest = { diagnosticsDialog = false },
            title = { Text("Diagnostics") },
            text = { Text(diagnosticsText) },
            confirmButton = {
                Button(onClick = { diagnosticsDialog = false }) { Text("OK") }
            }
        )
    }
    if (aboutDialog) {
        val context = LocalContext.current
        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
        AlertDialog(
            onDismissRequest = { aboutDialog = false },
            title = { Text("About SyncShare") },
            text = {
                Column {
                    Text("Version: $versionName")
                    Text("Device-to-device file sync and share app.")
                    Text("Developed by Your Team.")
                    Text("© 2024")
                }
            },
            confirmButton = {
                Button(onClick = { aboutDialog = false }) { Text("OK") }
            }
        )
    }
}

@Composable
private fun DropdownMenuBox(selected: String, options: List<String>, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Button(onClick = { expanded = true }) {
            Text(selected)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = {
                    onSelected(option)
                    expanded = false
                })
            }
        }
    }
}