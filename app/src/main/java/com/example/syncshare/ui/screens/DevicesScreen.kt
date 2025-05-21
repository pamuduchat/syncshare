package com.example.syncshare.ui.screens

import android.Manifest
import android.annotation.SuppressLint // Make sure this is imported
import android.content.pm.PackageManager // For PackageManager.PERMISSION_GRANTED
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat // For ActivityCompat.checkSelfPermission
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.syncshare.utils.getWifiDirectPermissions
import com.example.syncshare.utils.hasPermission // Your util, can also use ActivityCompat
import com.example.syncshare.utils.rememberPermissionsLauncher
import com.example.syncshare.viewmodels.DevicesViewModel
import kotlinx.coroutines.launch

@SuppressLint("MissingPermission") // Add to the Composable if viewModel methods called directly are known to be guarded
@Composable
fun DevicesScreen(
    modifier: Modifier = Modifier,
    viewModel: DevicesViewModel = viewModel()
) {
    Log.d("DevicesScreen", "DevicesScreen Composable INVOKED - START")
    val context = LocalContext.current
    val discoveredPeers: List<WifiP2pDevice> = viewModel.discoveredPeers
    val permissionStatus by remember { viewModel.permissionRequestStatus }
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    val permissionsLauncher = rememberPermissionsLauncher { permissionsResult ->
        var allGranted = true
        permissionsResult.forEach { (perm, granted) ->
            Log.d("DevicesScreen", "Permission $perm ${if (granted) "Granted" else "Denied"}")
            if (!granted) allGranted = false
        }
        if (allGranted) {
            Log.d("DevicesScreen", "All required permissions GRANTED by launcher.")
            viewModel.permissionRequestStatus.value = "Permissions granted. Initializing P2P..."
            try {
                viewModel.registerReceiver()
                // Call attemptDiscoveryOrRefreshGroup now that permissions are granted
                viewModel.attemptDiscoveryOrRefreshGroup()
            } catch (e: SecurityException) {
                Log.e("DevicesScreen", "SecurityException after permission grant: ${e.message}", e)
                viewModel.permissionRequestStatus.value = "Security exception: ${e.message}"
            }
        } else {
            Log.e("DevicesScreen", "One or more required permissions DENIED by launcher.")
            viewModel.permissionRequestStatus.value = "Required permissions denied. Cannot discover devices."
        }
    }

    DisposableEffect(lifecycleOwner) {
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    Log.d("DevicesScreen", "ON_RESUME triggered.")
                    // Check if all necessary P2P discovery permissions are granted
                    val p2pPermissionsGranted = getWifiDirectPermissions().all { context.hasPermission(it) }
                    // Check if ACCESS_FINE_LOCATION is granted for requestCurrentGroupInfo
                    val fineLocationGranted = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

                    if (p2pPermissionsGranted) {
                        Log.d("DevicesScreen", "ON_RESUME: P2P discovery permissions granted, registering receiver.")
                        try {
                            viewModel.registerReceiver()
                        } catch (e: SecurityException) {
                            Log.e("DevicesScreen", "SecurityException during ON_RESUME registerReceiver: ${e.message}", e)
                            viewModel.permissionRequestStatus.value = "Security exception on resume: ${e.message}"
                        }
                    } else {
                        Log.d("DevicesScreen", "ON_RESUME: P2P discovery permissions not (or no longer) granted.")
                    }

                    if (fineLocationGranted) {
                        Log.d("DevicesScreen", "ON_RESUME: Fine location granted, requesting group info.")
                        // No need for @SuppressLint here if the viewModel.requestCurrentGroupInfo() is internally guarded
                        // or if you've already annotated the calling Composable or the method itself in the ViewModel.
                        // For safety, if Lint still complains, the @SuppressLint("MissingPermission") at the top of DevicesScreen covers it.
                        viewModel.requestCurrentGroupInfo()
                    } else {
                        Log.w("DevicesScreen", "ON_RESUME: Fine location NOT granted, cannot request group info.")
                        // Optionally inform user or request permission if critical for initial view
                        // viewModel.permissionRequestStatus.value = "Location permission needed for group status."
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    Log.d("DevicesScreen", "ON_PAUSE triggered. Unregistering receiver.")
                    viewModel.unregisterReceiver()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            Log.d("DevicesScreen", "ON_DISPOSE: Removing lifecycle observer.")
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    Log.d("DevicesScreen", "Search/Refresh button clicked.")
                    // The attemptDiscoveryOrRefreshGroup method in ViewModel should handle its own permission checks.
                    // The UI can optimistically call it, and the ViewModel will update status if perms are missing.
                    // Or, re-check here for better immediate UI feedback before calling ViewModel.
                    val permsToRequest = getWifiDirectPermissions()
                    if (permsToRequest.all { context.hasPermission(it) }) {
                        Log.d("DevicesScreen", "Search/Refresh: Permissions OK.")
                        try {
                            viewModel.registerReceiver() // Ensure receiver is active if not already
                            viewModel.attemptDiscoveryOrRefreshGroup()
                        } catch (e: SecurityException) {
                            Log.e("DevicesScreen", "Search/Refresh: SecurityException: ${e.message}", e)
                            viewModel.permissionRequestStatus.value = "Security exception: ${e.message}"
                        }
                    } else {
                        Log.d("DevicesScreen", "Search/Refresh: Requesting permissions.")
                        viewModel.permissionRequestStatus.value = "Requesting P2P permissions..."
                        permissionsLauncher.launch(permsToRequest)
                    }
                },
                enabled = !isRefreshing
            ) {
                Icon(Icons.Filled.Search, contentDescription = "Search", modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                val groupInfo = viewModel.groupInfo.collectAsState().value
                Text(if (groupInfo != null) "Refresh Group" else "Search Peers")
            }

            OutlinedButton(
                onClick = {
                    Log.d("DevicesScreen", "Reset P2P button clicked.")
                    viewModel.resetWifiDirectSystem()
                },
                enabled = !isRefreshing
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "Reset P2P", modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("Reset P2P")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            Button(
                onClick = {
                    Log.d("DevicesScreen", "Check P2P Status button clicked.")
                    viewModel.checkWifiDirectStatus()
                },
                // modifier = Modifier.weight(1f), // If you want buttons to take equal space
                enabled = !isRefreshing
            ) {
                Icon(Icons.Filled.Info, contentDescription = "Check Status", modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("Check Status")
            }

            Button(
                onClick = {
                    Log.d("DevicesScreen", "Force Request Peers button clicked.")
                    // Permission for forceRequestPeers (ACCESS_FINE_LOCATION) should be checked.
                    // The viewModel.forceRequestPeers() itself has an internal check.
                    // If Lint still complains here, it's because it wants an explicit check or try-catch at this call site too.
                    // Adding @SuppressLint("MissingPermission") to the entire DevicesScreen composable is a broader way to handle these.
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        try {
                            viewModel.forceRequestPeers()
                        } catch (e: SecurityException) {
                            Log.e("DevicesScreen", "ForceRequestPeers Click: SecurityException: ${e.message}", e)
                            viewModel.permissionRequestStatus.value = "Permission error forcing peer request."
                        }
                    } else {
                        Log.w("DevicesScreen", "ForceRequestPeers Click: ACCESS_FINE_LOCATION permission missing.")
                        viewModel.permissionRequestStatus.value = "Location permission needed to force peer request."
                        // Optionally, launch a specific permission request for ACCESS_FINE_LOCATION here if desired.
                        // For simplicity, relying on the main permission launcher for now.
                        permissionsLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                    }
                },
                // modifier = Modifier.weight(1f),
                enabled = !isRefreshing
            ) {
                Text("Force Peers Req.") // Shorter text
            }
        }


        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = permissionStatus,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            if (isRefreshing && discoveredPeers.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (discoveredPeers.isEmpty() && !isRefreshing) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("No devices found.")
                    Text("Tap 'Search' or 'Refresh Group'.")
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp)
            ) {
                if (isRefreshing && discoveredPeers.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                }
                items(
                    items = discoveredPeers,
                    key = { device: WifiP2pDevice -> device.deviceAddress ?: device.hashCode().toString() }
                ) { device ->
                    DeviceItem(device = device, onClick = {
                        Log.d("DevicesScreen", "DeviceItem clicked: ${device.deviceName}")
                        try {
                            coroutineScope.launch {
                                viewModel.connectToDevice(device)
                            }
                        } catch (e: SecurityException) {
                            Log.e("DevicesScreen", "DeviceItem Click: SecurityException for connect: ${e.message}", e)
                            viewModel.permissionRequestStatus.value = "Permission denied for connection: ${e.message}"
                        } catch (e: Exception) {
                            Log.e("DevicesScreen", "DeviceItem Click: General error for connect: ${e.message}", e)
                            viewModel.permissionRequestStatus.value = "Connection error: ${e.message}"
                        }
                    })
                    Divider()
                }
            }
        }
    }
    Log.d("DevicesScreen", "DevicesScreen Composable INVOKED - END")
}

// DeviceItem and getDeviceStatus functions (ensure they are correctly defined as before)
@Composable
fun DeviceItem(device: WifiP2pDevice, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
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
            device.deviceAddress?.let {
                Text(
                    "Address: $it",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

fun getDeviceStatus(deviceStatus: Int): String {
    return when (deviceStatus) {
        WifiP2pDevice.AVAILABLE -> "Available"
        WifiP2pDevice.INVITED -> "Invited"
        WifiP2pDevice.CONNECTED -> "Connected"
        WifiP2pDevice.FAILED -> "Failed"
        WifiP2pDevice.UNAVAILABLE -> "Unavailable"
        else -> "Unknown ($deviceStatus)"
    }
}