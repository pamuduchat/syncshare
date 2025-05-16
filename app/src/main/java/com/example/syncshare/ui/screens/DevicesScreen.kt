package com.example.syncshare.ui.screens

import android.Manifest
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.syncshare.utils.getWifiDirectPermissions
import com.example.syncshare.utils.hasPermission
import com.example.syncshare.utils.rememberPermissionsLauncher
import com.example.syncshare.viewmodels.DevicesViewModel
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState

@Composable
fun DevicesScreen(
    modifier: Modifier = Modifier,
    viewModel: DevicesViewModel = viewModel()
) {
    val context = LocalContext.current
    // Corrected: Directly use the SnapshotStateList from the ViewModel
    val discoveredPeers: List<WifiP2pDevice> = viewModel.discoveredPeers
    val permissionStatus by remember { viewModel.permissionRequestStatus }
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current


    // Permission Launcher
    val permissionsLauncher = rememberPermissionsLauncher { permissionsResult ->
        var allGranted = true
        permissionsResult.forEach { (perm, granted) ->
            if (!granted) allGranted = false
            viewModel.permissionRequestStatus.value = "Permission $perm ${if (granted) "Granted" else "Denied"}"
        }
        if (allGranted) {
            viewModel.registerReceiver()
            viewModel.startDiscovery()
        } else {
            viewModel.permissionRequestStatus.value = "Required permissions denied. Cannot discover devices."
        }
    }

    // Handle lifecycle for registering/unregistering broadcast receiver
    DisposableEffect(lifecycleOwner) { // Observe lifecycleOwner
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (getWifiDirectPermissions().all { context.hasPermission(it) }) {
                        viewModel.registerReceiver()
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    viewModel.unregisterReceiver()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            // viewModel.unregisterReceiver() // ViewModel handles unregister in onCleared or on ON_PAUSE
        }
    }


    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(onClick = {
            val permsToRequest = getWifiDirectPermissions()
            if (permsToRequest.all { context.hasPermission(it) }) {
                // viewModel.registerReceiver() // Already handled by lifecycle/permission grant
                viewModel.startDiscovery()
            } else {
                permissionsLauncher.launch(permsToRequest)
            }
        }) {
            Text("Search for Devices")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(permissionStatus, style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(16.dp))

        if (discoveredPeers.isEmpty() && !isRefreshing) {
            Text("No devices found. Tap 'Search' to discover nearby devices running SyncShare.")
        }

        SwipeRefresh(
            state = rememberSwipeRefreshState(isRefreshing),
            onRefresh = {
                val permsToRequest = getWifiDirectPermissions()
                if (permsToRequest.all { context.hasPermission(it) }) {
                    // viewModel.registerReceiver() // Already handled
                    viewModel.startDiscovery()
                } else {
                    permissionsLauncher.launch(permsToRequest)
                }
            }
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // Ensure 'device' is correctly typed as WifiP2pDevice here
                items(items = discoveredPeers, key = { device: WifiP2pDevice -> device.deviceAddress ?: device.hashCode() }) { device ->
                    DeviceItem(device = device, onClick = {
                        viewModel.permissionRequestStatus.value = "Clicked on ${device.deviceName}"
                    })
                    Divider()
                }
            }
        }
    }
}

@Composable
fun DeviceItem(device: WifiP2pDevice, onClick: () -> Unit) { // device type is WifiP2pDevice
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(device.deviceName ?: "Unknown Device", style = MaterialTheme.typography.titleMedium)
            Text(
                "Status: ${getDeviceStatus(device.status)}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                // deviceAddress can be null if the device is not yet configured (e.g., group owner not decided)
                "Address: ${device.deviceAddress ?: "N/A"}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

// getDeviceStatus function remains the same
fun getDeviceStatus(deviceStatus: Int): String {
    return when (deviceStatus) {
        WifiP2pDevice.AVAILABLE -> "Available"
        WifiP2pDevice.INVITED -> "Invited"
        WifiP2pDevice.CONNECTED -> "Connected"
        WifiP2pDevice.FAILED -> "Failed"
        WifiP2pDevice.UNAVAILABLE -> "Unavailable"
        else -> "Unknown"
    }
}