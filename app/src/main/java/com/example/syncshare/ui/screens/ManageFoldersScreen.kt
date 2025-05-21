package com.example.syncshare.ui.screens

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.syncshare.viewmodels.ManageFoldersViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageFoldersScreen(
    modifier: Modifier = Modifier,
    viewModel: ManageFoldersViewModel = viewModel()
) {
    val context = LocalContext.current
    val selectedFolders: List<Uri> = viewModel.selectedFolders // This is a SnapshotStateList<Uri>

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri: Uri? -> // uri is nullable android.net.Uri
            uri?.let { nonNullUri ->
                val contentResolver = context.contentResolver
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                try {
                    // IMPORTANT: Pass nonNullUri to takePersistableUriPermission
                    contentResolver.takePersistableUriPermission(nonNullUri, takeFlags)
                    viewModel.addFolder(nonNullUri)
                } catch (e: SecurityException) {
                    Log.e("ManageFoldersScreen", "Failed to take persistable URI permission for $nonNullUri: ${e.message}")

                } catch (e: Exception) {
                    Log.e("ManageFoldersScreen", "Error processing URI $nonNullUri: ${e.message}")
                }
            }
        }
    )

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = {
                folderPickerLauncher.launch(null)
            }) {
                Icon(Icons.Filled.Add, "Add Folder")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Selected Folders", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(16.dp))

            if (selectedFolders.isEmpty()) { // This should be fine for a List<Uri>
                Text("No folders selected. Tap '+' to add a folder for synchronization.")
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    // Use the named parameter 'items' to guide overload resolution
                    items(
                        items = selectedFolders, // Pass the list here
                        key = { folderUri -> folderUri.toString() } // folderUri should be Uri
                    ) { folderItemUri: Uri -> // Explicitly type folderUri as Uri
                        // Now folderUri inside this lambda is correctly typed as Uri
                        FolderItem(
                            uri = folderItemUri, // Pass Uri
                            onRemoveClick = { viewModel.removeFolder(folderItemUri) }
                        )
                        Divider()
                    }
                }
            }
        }
    }
}


@Composable
fun FolderItem(uri: Uri, onRemoveClick: () -> Unit) { // uri is android.net.Uri
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Simplify the text extraction for now to see if substringAfterLast is the main culprit
        val folderName = uri.lastPathSegment // This can be null
        val displayText = if (folderName != null) {
            folderName.substringAfterLast(':', folderName) // If ':' not found, use folderName itself
        } else {
            uri.toString() // Fallback if lastPathSegment is null
        }

        Text(
            text = displayText,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            style = MaterialTheme.typography.bodyLarge
        )
        IconButton(onClick = onRemoveClick) {
            Icon(Icons.Filled.Delete, contentDescription = "Remove Folder")
        }
    }
}