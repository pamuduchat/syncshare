package com.example.syncshare.ui.screens

import android.Manifest
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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

@Composable
fun DevicesScreen(
    modifier: Modifier = Modifier,
    viewModel: DevicesViewModel = viewModel()
) {
    val context = LocalContext.current
    // Directly use the SnapshotStateList from the ViewModel
    val discoveredPeers: List<WifiP2pDevice> = viewModel.discoveredPeers
    val permissionStatus by remember { viewModel.permissionRequestStatus }
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current


    // Permission Launcher
    val permissionsLauncher = rememberPermissionsLauncher { permissionsResult ->
        var allGranted = true
        permissionsResult.forEach { (perm, granted) ->
            if (!granted) allGranted = false
            viewModel.permissionRequestStatus.value = "Permission $perm ${if (granted) "Granted" else "Denied"}"
        }
        if (allGranted) {
            try {
                viewModel.registerReceiver()
                viewModel.startDiscovery()
            } catch (e: SecurityException) {
                viewModel.permissionRequestStatus.value = "Security exception: ${e.message}"
            }
        } else {
            viewModel.permissionRequestStatus.value = "Required permissions denied. Cannot discover devices."
        }
    }

    // Handle lifecycle for registering/unregistering broadcast receiver
    DisposableEffect(lifecycleOwner) {
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (getWifiDirectPermissions().all { context.hasPermission(it) }) {
                        try {
                            viewModel.registerReceiver()
                        } catch (e: SecurityException) {
                            viewModel.permissionRequestStatus.value = "Security exception: ${e.message}"
                        }
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
                try {
                    viewModel.startDiscovery()
                } catch (e: SecurityException) {
                    viewModel.permissionRequestStatus.value = "Security exception: ${e.message}"
                }
            } else {
                permissionsLauncher.launch(permsToRequest)
            }
        }) {
            Text("Search for Devices")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(permissionStatus, style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(16.dp))

        // Use SwipeRefreshLayout alternative
        val coroutineScope = rememberCoroutineScope()

        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            if (discoveredPeers.isEmpty() && !isRefreshing) {
                Text(
                    "No devices found. Tap 'Search' to discover nearby devices running SyncShare.",
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                if (isRefreshing) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Refreshing...")
                        }
                    }
                }

                items(
                    items = discoveredPeers,
                    key = { device: WifiP2pDevice -> device.deviceAddress ?: device.hashCode().toString() }
                ) { device ->
                    DeviceItem(device = device, onClick = {
                        viewModel.permissionRequestStatus.value = "Clicked on ${device.deviceName}"
                        // Add connection logic here
                        try {
                            // Instead of directly using connectToDevice, add your connection logic here
                            coroutineScope.launch {
                                // Example implementation - replace with your actual connection logic
                                viewModel.permissionRequestStatus.value = "Connecting to ${device.deviceName}..."
                                // viewModel.connectToDevice(device)
                            }
                        } catch (e: SecurityException) {
                            viewModel.permissionRequestStatus.value = "Permission denied: ${e.message}"
                        } catch (e: Exception) {
                            viewModel.permissionRequestStatus.value = "Connection error: ${e.message}"
                        }
                    })
                    Divider()
                }
            }
        }
    }
}

@Composable
fun DeviceItem(device: WifiP2pDevice, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.deviceName.takeIf { !it.isNullOrBlank() } ?: "Unknown Device",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Status: ${getDeviceStatus(device.status)}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Address: ${device.deviceAddress ?: "N/A"}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

// Device status helper function
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