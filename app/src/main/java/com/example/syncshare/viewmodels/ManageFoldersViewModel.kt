package com.example.syncshare.viewmodels

import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel

class ManageFoldersViewModel : ViewModel() {
    // Store selected folder URIs. Using mutableStateListOf for Compose to observe changes.
    val selectedFolders = mutableStateListOf<Uri>()

    fun addFolder(uri: Uri) {
        if (!selectedFolders.contains(uri)) {
            selectedFolders.add(uri)
        }
    }

    fun removeFolder(uri: Uri) {
        selectedFolders.remove(uri)
    }
}