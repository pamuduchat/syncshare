package com.example.syncshare.navigation

sealed class Screen(val route: String, val title: String) {
    data object ManageFolders : Screen("manage_folders", "Folders")
    data object Devices : Screen("devices", "Devices")
    data object Sync : Screen("sync", "Sync")
    data object History : Screen("history", "History")
    data object Settings : Screen("settings", "Settings")
}