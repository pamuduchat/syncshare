package com.example.syncshare.viewmodels

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel

class ManageFoldersViewModel(context: Context) : ViewModel() {
    // Store selected folder URIs. Using mutableStateListOf for Compose to observe changes.
    val selectedFolders = mutableStateListOf<Uri>()

    private val prefs: SharedPreferences = context.getSharedPreferences("folders_prefs", Context.MODE_PRIVATE)
    private val KEY_FOLDERS = "selected_folders"

    init {
        // Load folders from SharedPreferences
        val folderStrings = prefs.getStringSet(KEY_FOLDERS, emptySet()) ?: emptySet()
        selectedFolders.addAll(folderStrings.map { Uri.parse(it) })
    }

    private fun persistFolders() {
        prefs.edit().putStringSet(KEY_FOLDERS, selectedFolders.map { it.toString() }.toSet()).apply()
    }

    fun addFolder(uri: Uri) {
        if (!selectedFolders.contains(uri)) {
            selectedFolders.add(uri)
            persistFolders()
        }
    }

    fun removeFolder(uri: Uri) {
        selectedFolders.remove(uri)
        persistFolders()
    }
}