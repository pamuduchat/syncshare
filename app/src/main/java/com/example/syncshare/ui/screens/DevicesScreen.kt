package com.example.syncshare.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner // Corrected Import
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.syncshare.ui.model.DisplayableDevice
import com.example.syncshare.ui.model.DeviceTechnology
import com.example.syncshare.utils.getBluetoothPermissions
import com.example.syncshare.utils.getWifiDirectPermissions
import com.example.syncshare.utils.hasPermission
import com.example.syncshare.utils.isLocationEnabled
import com.example.syncshare.utils.rememberPermissionsLauncher
import com.example.syncshare.viewmodels.DevicesViewModel
import kotlinx.coroutines.launch
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultLauncher

@SuppressLint("MissingPermission")
@Composable
fun DevicesScreen(
    modifier: Modifier = Modifier,
    viewModel: DevicesViewModel = viewModel()
) {
    Log.d("DevicesScreen", "DevicesScreen Composable INVOKED - START")
    val context = LocalContext.current
    val displayableDevices: List<DisplayableDevice> = viewModel.displayableDeviceList

    // Declare permissionStatus by viewModel.permissionRequestStatus before it's used in remember for locationServicesOn
    val permissionStatus by remember { viewModel.permissionRequestStatus }
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val isBluetoothActuallyEnabled by viewModel.isBluetoothEnabled
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    // This remember block now correctly sees permissionStatus
    val locationServicesOn by remember(permissionStatus) { // Re-evaluate if permissionStatus changes
        mutableStateOf(isLocationEnabled(context))
    }

    val wifiDirectPermissionsLauncher = rememberPermissionsLauncher { permissionsResult ->
        var allGranted = true
        permissionsResult.forEach { (perm, granted) ->
            Log.d("DevicesScreen", "Wi-Fi Direct Perm $perm ${if (granted) "Granted" else "Denied"}")
            if (!granted) allGranted = false
        }
        if (allGranted) {
            Log.d("DevicesScreen", "All Wi-Fi Direct permissions GRANTED by launcher.")
            viewModel.permissionRequestStatus.value = "Wi-Fi Direct permissions granted. Scanning..."
            try {
                viewModel.registerP2pReceiver()
                viewModel.startP2pDiscovery()
            } catch (e: SecurityException) {
                Log.e("DevicesScreen", "SecurityException (Wi-Fi Direct launch): ${e.message}", e)
                viewModel.permissionRequestStatus.value = "Security exception: ${e.message}"
            }
        } else {
            Log.e("DevicesScreen", "Wi-Fi Direct permissions DENIED by launcher.")
            viewModel.permissionRequestStatus.value = "Wi-Fi Direct permissions denied."
        }
    }

    val bluetoothPermissionsLauncher = rememberPermissionsLauncher { permissionsResult ->
        var allGranted = true
        permissionsResult.forEach { (perm, granted) ->
            Log.d("DevicesScreen", "Bluetooth Perm $perm ${if (granted) "Granted" else "Denied"}")
            if (!granted) allGranted = false
        }
        if (allGranted) {
            Log.d("DevicesScreen", "All Bluetooth permissions GRANTED by launcher.")
            viewModel.permissionRequestStatus.value = "Bluetooth permissions granted. Scanning..."
            try {
                viewModel.startBluetoothDiscovery()
            } catch (e: SecurityException) {
                Log.e("DevicesScreen", "SecurityException (Bluetooth launch): ${e.message}", e)
                viewModel.permissionRequestStatus.value = "Security exception: ${e.message}"
            }
        } else {
            Log.e("DevicesScreen", "Bluetooth permissions DENIED by launcher.")
            viewModel.permissionRequestStatus.value = "Bluetooth permissions denied."
        }
    }

    DisposableEffect(lifecycleOwner) {
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    Log.d("DevicesScreen", "ON_RESUME triggered.")
                    if (getWifiDirectPermissions().all { context.hasPermission(it) }) {
                        try { viewModel.registerP2pReceiver() } catch (e: SecurityException) { Log.e("DevicesScreen", "SecEx P2P Reg: ${e.message}", e) }
                    } else { Log.w("DevicesScreen", "ON_RESUME: Wi-Fi Direct permissions MISSING.") }

                    viewModel.updateBluetoothState()
                    if (viewModel.isBluetoothEnabled.value) {
                        // Check Bluetooth permissions before starting server
                        val btPerms = getBluetoothPermissions()
                        if (btPerms.all { ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
                            Log.d("DevicesScreen", "ON_RESUME: Bluetooth permissions OK, preparing BT service.")
                            viewModel.prepareBluetoothService() // This will start the BT server
                        } else {
                            Log.w("DevicesScreen", "ON_RESUME: Bluetooth permissions MISSING. BT Server not started.")
                            // Optionally, you could trigger bluetoothPermissionsLauncher.launch(btPerms) here
                            // if you want to proactively ask for permissions on resume.
                        }
                    } else {
                        Log.d("DevicesScreen", "ON_RESUME: Bluetooth is not enabled.")
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    Log.d("DevicesScreen", "ON_PAUSE triggered.")
                    viewModel.unregisterP2pReceiver()
                    viewModel.stopBluetoothDiscovery()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            Log.d("DevicesScreen", "ON_DISPOSE: Removing lifecycle observer.")
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            viewModel.stopBluetoothDiscovery()
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Display location status message first for prominence
        if (!locationServicesOn && (permissionStatus.contains("Location Services are OFF", ignoreCase = true) || permissionStatus.contains("Location is off", ignoreCase = true))) {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Location Services are OFF.",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        "Device discovery requires system Location Services to be enabled.",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(onClick = {
                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }) {
                        Text("Open Location Settings")
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        val btConnectionStatus by viewModel.bluetoothConnectionStatus.collectAsState()
        Text(
            text = "BT Status: $btConnectionStatus",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth()
        )


        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    Log.d("DevicesScreen", "Wi-Fi Direct Scan button clicked.")
                    val perms = getWifiDirectPermissions()
                    if (perms.all { context.hasPermission(it) }) {
                        try {
                            viewModel.registerP2pReceiver()
                            viewModel.startP2pDiscovery()
                        } catch (e: SecurityException) { Log.e("DevicesScreen", "SecEx P2P Scan: ${e.message}", e) }
                    } else {
                        wifiDirectPermissionsLauncher.launch(perms)
                    }
                },
                enabled = !isRefreshing && locationServicesOn // Also disable if location is off
            ) {
                Icon(Icons.Filled.Search, contentDescription = "Scan Wi-Fi Direct", modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("Scan P2P")
            }

            Button(
                onClick = {
                    Log.d("DevicesScreen", "Bluetooth Scan button clicked.")
                    val perms = getBluetoothPermissions()
                    if (perms.all { context.hasPermission(it) }) {
                        try { viewModel.startBluetoothDiscovery() } catch (e: SecurityException) { Log.e("DevicesScreen", "SecEx BT Scan: ${e.message}", e) }
                    } else {
                        bluetoothPermissionsLauncher.launch(perms)
                    }
                },
                enabled = !isRefreshing && isBluetoothActuallyEnabled && locationServicesOn // Also disable if location is off
            ) {
                Icon(Icons.Filled.Bluetooth, contentDescription = "Scan Bluetooth", modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("Scan BT")
            }
        }
        if (!isBluetoothActuallyEnabled) {
            Text("Bluetooth is OFF", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            OutlinedButton(onClick = { viewModel.resetWifiDirectSystem() }, enabled = !isRefreshing) {
                Icon(Icons.Filled.Refresh, contentDescription = "Reset P2P", modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("Reset P2P")
            }
            OutlinedButton(onClick = { viewModel.checkWifiDirectStatus() }, enabled = !isRefreshing) {
                Icon(Icons.Filled.Info, contentDescription = "Check P2P Status", modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("P2P Status")
            }
        }

        // --- New Row: P2P Disconnect Button ---
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            OutlinedButton(onClick = { viewModel.fullResetP2pConnection() }, enabled = !isRefreshing) {
                Text("Disconnect P2P")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(text = permissionStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            if (isRefreshing && displayableDevices.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (displayableDevices.isEmpty() && !isRefreshing) {
                Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No devices found.")
                    Text("Tap a scan button to discover devices.")
                }
            }

            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                if (isRefreshing && displayableDevices.isNotEmpty()) {
                    item { Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } }
                }
                items(items = displayableDevices, key = { it.id }) { device ->
                    UnifiedDeviceItem(displayableDevice = device, onClick = {
                        Log.d("DevicesScreen", "Clicked on ${device.name} (${device.technology})")
                        try {
                            when (device.technology) {
                                DeviceTechnology.WIFI_DIRECT -> {
                                    val p2pDevice = device.originalDeviceObject as? WifiP2pDevice
                                    p2pDevice?.let { coroutineScope.launch { viewModel.connectToP2pDevice(it) } }
                                }
                                DeviceTechnology.BLUETOOTH_CLASSIC -> {
                                    val btDevice = device.originalDeviceObject as? BluetoothDevice
                                    val perms = getBluetoothPermissions()
                                    if (perms.all { context.hasPermission(it) }) {
                                        btDevice?.let {
                                            Log.d("DevicesScreen", "Attempting BT connect to: ${it.name ?: it.address}")
                                            viewModel.connectToBluetoothDevice(it)
                                        }
                                    } else {
                                        bluetoothPermissionsLauncher.launch(perms)
                                    }
                                }
                                DeviceTechnology.UNKNOWN -> {
                                    Log.w("DevicesScreen", "Clicked on device with UNKNOWN technology: ${device.name}")
                                    viewModel.permissionRequestStatus.value = "Cannot connect: Unknown device type."
                                }
                            }
                        } catch (e: SecurityException) {
                            Log.e("DevicesScreen", "SecurityException on item click connect: ${e.message}", e)
                            viewModel.permissionRequestStatus.value = "Permission error connecting."
                        } catch (e: Exception) {
                            Log.e("DevicesScreen", "Exception on item click connect: ${e.message}", e)
                            viewModel.permissionRequestStatus.value = "Error initiating connection."
                        }
                    })
                    HorizontalDivider() // Corrected
                }
            }
        }
    }
    Log.d("DevicesScreen", "DevicesScreen Composable INVOKED - END")
}

@Composable
fun UnifiedDeviceItem(displayableDevice: DisplayableDevice, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icon = when(displayableDevice.technology) {
            DeviceTechnology.WIFI_DIRECT -> Icons.Filled.Search // Consider Icons.Filled.Wifi
            DeviceTechnology.BLUETOOTH_CLASSIC -> Icons.Filled.Bluetooth
            DeviceTechnology.UNKNOWN -> Icons.Filled.Info
            // Add 'else -> Icons.Default.Help' or similar for future-proofing
        }
        Icon(icon, contentDescription = displayableDevice.technology.name, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(text = displayableDevice.name, style = MaterialTheme.typography.titleMedium)
            Text(text = displayableDevice.details, style = MaterialTheme.typography.bodySmall)
        }
    }
}