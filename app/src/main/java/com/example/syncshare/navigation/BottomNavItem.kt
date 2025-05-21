package com.example.syncshare.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.ui.graphics.vector.ImageVector

data class BottomNavItem(
    val screen: Screen,
    val icon: ImageVector
)

val bottomNavItems = listOf(
    BottomNavItem(Screen.ManageFolders, Icons.Filled.Folder),
    BottomNavItem(Screen.Devices, Icons.Filled.Share),
    BottomNavItem(Screen.Sync, Icons.Filled.Sync),
    BottomNavItem(Screen.History, Icons.Filled.History),
    BottomNavItem(Screen.Settings, Icons.Filled.Settings)
)