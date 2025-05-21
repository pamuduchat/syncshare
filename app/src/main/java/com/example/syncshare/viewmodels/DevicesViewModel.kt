package com.example.syncshare.viewmodels

import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.syncshare.features.WifiDirectBroadcastReceiver
import com.example.syncshare.ui.screens.getDeviceStatus
import com.example.syncshare.utils.getWifiDirectPermissions
import com.example.syncshare.utils.hasPermission
import kotlinx.coroutines.delay // Make sure to import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DevicesViewModel(application: Application) : AndroidViewModel(application) {
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    val discoveredPeers = mutableStateListOf<WifiP2pDevice>()
    val permissionRequestStatus = mutableStateOf("Idle. Tap search or pull to refresh.")

    private val _groupInfo = MutableStateFlow<android.net.wifi.p2p.WifiP2pGroup?>(null)
    val groupInfo: StateFlow<android.net.wifi.p2p.WifiP2pGroup?> = _groupInfo

    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    // --- Retry Logic Variables ---
    private var discoveryRetryCount = 0
    private val MAX_DISCOVERY_RETRIES = 3
    // --- End Retry Logic Variables ---

    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    init {
        Log.d("DevicesViewModel", "DevicesViewModel - INIT BLOCK - START")
        viewModelScope.launch {
            initializeWifiP2p()
        }
        Log.d("DevicesViewModel", "DevicesViewModel - INIT BLOCK - END")
    }

    private fun initializeWifiP2p(isReset: Boolean = false) { // Added isReset flag
        Log.i("DevicesViewModel", "initializeWifiP2p() CALLED. Is reset: $isReset")
        val context = getApplication<Application>().applicationContext
        wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

        if (wifiP2pManager == null) {
            Log.e("DevicesViewModel", "FATAL: Cannot get WifiP2pManager service.")
            permissionRequestStatus.value = "Wi-Fi P2P not available on this device."
            return
        }
        Log.d("DevicesViewModel", "WifiP2pManager obtained successfully.")

        try {
            channel = wifiP2pManager?.initialize(context, Looper.getMainLooper(), object : WifiP2pManager.ChannelListener {
                override fun onChannelDisconnected() {
                    Log.e("DevicesViewModel", "************ WIFI P2P CHANNEL DISCONNECTED ************")
                    this@DevicesViewModel.channel = null
                    permissionRequestStatus.value = "P2P Channel Lost! Try resetting or restarting app."
                    _isRefreshing.value = false
                    discoveredPeers.clear()
                    // Optionally, automatically try to reset or re-initialize
                    // resetWifiDirectSystem() // Be careful with automatic resets to avoid loops
                }
            })
        } catch (e: SecurityException) {
            Log.e("DevicesViewModel", "SecurityException during P2P channel initialization: ${e.message}", e)
            permissionRequestStatus.value = "Permission error during P2P init."
            channel = null
            return
        }

        if (channel == null) {
            Log.e("DevicesViewModel", "FATAL: Failed to initialize Wi-Fi P2P channel (returned null or exception).")
            permissionRequestStatus.value = "Failed to initialize Wi-Fi P2P."
            return
        }
        Log.d("DevicesViewModel", "Wi-Fi P2P Channel Initialized successfully: $channel")
        if (isReset) { // If this was part of a reset, re-register receiver
            viewModelScope.launch {
                delay(300) // Small delay after channel re-init before registering
                registerReceiver()
            }
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun requestCurrentGroupInfo() {
        if (wifiP2pManager == null || channel == null) return
        try {
            wifiP2pManager?.requestGroupInfo(channel) { group ->
                Log.d("DevicesViewModel", "Current Group Info: ${group?.networkName}, Owner: ${group?.owner?.deviceName}")
                Log.i("DevicesViewModel", "Current Group Info Received: Network Name: ${group?.networkName}")
                group?.let {
                    Log.i("DevicesViewModel", "Group Owner: ${it.owner.deviceName}, Address: ${it.owner.deviceAddress}, Status: ${getDeviceStatus(it.owner.status)}")
                    Log.i("DevicesViewModel", "This device in group: Is Owner: ${it.isGroupOwner}")
                    it.clientList.forEach { client ->
                        Log.i("DevicesViewModel", "Client: ${client.deviceName}, Address: ${client.deviceAddress}, Status: ${getDeviceStatus(client.status)}")
                    }
                    // To get this device's own WifiP2pDevice object if it's part of the group:
                    // You might need to iterate clientList or check owner if not GO.
                    // Or use the THIS_DEVICE_CHANGED broadcast to get its own updated WifiP2pDevice object.
                }
                _groupInfo.value = group
            }
        } catch (e: SecurityException) {
            Log.e("DevicesViewModel", "SecurityException requesting group info: ${e.message}")
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    fun attemptDiscoveryOrRefreshGroup() {
        Log.i("DevicesViewModel", "attemptDiscoveryOrRefreshGroup called.")
        val context = getApplication<Application>().applicationContext

        // First, ensure basic P2P system is ready and permissions are granted
        // (Similar checks to the beginning of startDiscovery)
        if (wifiP2pManager == null || channel == null) {
            Log.e("DevicesViewModel", "attemptDiscoveryOrRefreshGroup - FAIL: P2PManager or Channel is null.")
            permissionRequestStatus.value = "Error: P2P service not ready. Try resetting."
            _isRefreshing.value = false // Ensure isRefreshing is managed
            checkWifiDirectStatus()
            return
        }

        val permsToRequest = getWifiDirectPermissions()
        var allPermissionsGranted = true
        if (permsToRequest.isNotEmpty()) {
            permsToRequest.forEach { perm ->
                if (ActivityCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false
                }
            }
        }
        if (!allPermissionsGranted) {
            Log.e("DevicesViewModel", "attemptDiscoveryOrRefreshGroup - FAIL: Permissions not granted.")
            permissionRequestStatus.value = "Permissions missing. Please grant them first."
            _isRefreshing.value = false
            // UI should ideally re-trigger permission request flow if this happens
            return
        }
        // --- End of prerequisite checks ---


        val currentGroup = _groupInfo.value // Get current group state (updated by requestCurrentGroupInfo)

        if (currentGroup != null) {
            Log.i("DevicesViewModel", "attemptDiscoveryOrRefreshGroup: Already in a group ('${currentGroup.networkName}'). Refreshing current group members.")
            permissionRequestStatus.value = "Refreshing members of current group..."
            _isRefreshing.value = true // Set refreshing state
            // Call forceRequestPeers, which internally calls wifiP2pManager.requestPeers()
            // and updates discoveredPeers via onPeersAvailable.
            // onPeersAvailable will set _isRefreshing to false.
            forceRequestPeers()
        } else {
            Log.i("DevicesViewModel", "attemptDiscoveryOrRefreshGroup: Not currently in a P2P group. Attempting new peer discovery.")
            // Proceed with the full discovery process (stop, then discoverPeers with retries)
            // The startDiscovery() method already handles setting _isRefreshing.
            startDiscovery() // Your existing full discovery logic
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    fun startDiscovery(isRetry: Boolean = false) { // Added isRetry flag
        if (!isRetry) {
            discoveryRetryCount = 0 // Reset retry count for a fresh user-initiated discovery
        }
        Log.i("DevicesViewModel", "startDiscovery() - ENTRY. Is retry: $isRetry, Current retry count: $discoveryRetryCount")
        val context = getApplication<Application>().applicationContext

        if (wifiP2pManager == null || channel == null) {
            Log.e("DevicesViewModel", "startDiscovery - PRE-CHECK FAIL: P2PManager or Channel is null. wifiP2pManager: $wifiP2pManager, channel: $channel")
            permissionRequestStatus.value = "Error: P2P service not ready. Try resetting Wi-Fi Direct."
            _isRefreshing.value = false
            checkWifiDirectStatus() // Run diagnostics
            return
        }
        Log.d("DevicesViewModel", "startDiscovery - Channel confirmed NOT NULL before stopPeerDiscovery.")

        val permsToRequest = getWifiDirectPermissions()
        var allPermissionsGranted = true
        if (permsToRequest.isNotEmpty()) {
            permsToRequest.forEach { perm ->
                if (ActivityCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED) {
                    Log.w("DevicesViewModel", "startDiscovery - Missing permission: $perm")
                    allPermissionsGranted = false
                }
            }
        }
        if (!allPermissionsGranted) {
            Log.e("DevicesViewModel", "startDiscovery - PRE-CHECK FAIL: Permissions not granted.")
            permissionRequestStatus.value = "Permissions missing. Please grant them."
            _isRefreshing.value = false
            checkWifiDirectStatus() // Run diagnostics
            return
        }
        Log.d("DevicesViewModel", "startDiscovery - All necessary P2P permissions appear to be granted.")

        _isRefreshing.value = true
        if (!isRetry) permissionRequestStatus.value = "Attempting to stop previous discovery..."

        if (_groupInfo.value != null && _groupInfo.value?.isGroupOwner == true) {
            Log.w("DevicesViewModel", "Device is currently a P2P Group Owner. Discovery behavior might be limited or stopPeerDiscovery might hang.")
            // You might choose to behave differently here.
            // For now, we proceed, but this log is important.
        } else if (_groupInfo.value != null) {
            Log.w("DevicesViewModel", "Device is currently connected to a P2P Group: ${_groupInfo.value?.networkName}. Discovery behavior might be limited.")
        }

        try {
            wifiP2pManager?.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i("DevicesViewModel", "stopPeerDiscovery.onSuccess()")
                    if (!isRetry) permissionRequestStatus.value = "Preparing for new discovery..."
                    initiateActualDiscoveryAfterStop()
                }

                override fun onFailure(reasonCode: Int) {
                    val reason = getFailureReasonString(reasonCode)
                    Log.w("DevicesViewModel", "stopPeerDiscovery.onFailure() - Code: $reason ($reasonCode). Proceeding.")
                    if (!isRetry) permissionRequestStatus.value = "Stop prior discovery failed (Reason: $reason). Trying new one..."
                    initiateActualDiscoveryAfterStop()
                }
            })
        } catch (e: SecurityException) {
            Log.e("DevicesViewModel", "SecurityException during stopPeerDiscovery: ${e.message}", e)
            if (!isRetry) permissionRequestStatus.value = "Permission error stopping discovery. Trying new one..."
            initiateActualDiscoveryAfterStop()
        } catch (e: Exception) {
            Log.e("DevicesViewModel", "Generic Exception during stopPeerDiscovery: ${e.message}", e)
            if (!isRetry) permissionRequestStatus.value = "Error stopping discovery. Trying new one..."
            initiateActualDiscoveryAfterStop() // Or consider failing here if stop is critical
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    private fun initiateActualDiscoveryAfterStop() {
        Log.d("DevicesViewModel", "initiateActualDiscoveryAfterStop() - Called. Retry count: $discoveryRetryCount")
        if (channel == null) {
            Log.e("DevicesViewModel", "initiateActualDiscoveryAfterStop - FAILURE: channel became null or was disconnected.")
            permissionRequestStatus.value = "Error: P2P Channel lost before discovery attempt."
            _isRefreshing.value = false
            discoveryRetryCount = 0 // Reset for next full attempt
            checkWifiDirectStatus() // Run diagnostics
            return
        }

        Log.d("DevicesViewModel", "Attempting wifiP2pManager.discoverPeers(...)")
        permissionRequestStatus.value = "Starting peer discovery${if (discoveryRetryCount > 0) " (attempt ${discoveryRetryCount + 1})" else ""}..."

        try {
            wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i("DevicesViewModel", "discoverPeers.onSuccess() - Discovery successfully INITIATED by framework.")
                    permissionRequestStatus.value = "Discovery process started. Waiting for devices..."
                    discoveryRetryCount = 0 // Reset counter on success
                    // _isRefreshing is set to false in onPeersAvailable
                }

                override fun onFailure(reasonCode: Int) {
                    val detailedReason = getDetailedFailureReasonString(reasonCode) // Use detailed reason
                    Log.e("DevicesViewModel", "discoverPeers.onFailure() - INITIATION FAILED. Reason: $detailedReason ($reasonCode)")

                    if (discoveryRetryCount < MAX_DISCOVERY_RETRIES) {
                        discoveryRetryCount++
                        val delayMillis = 300L * (1 shl (discoveryRetryCount - 1)) // Exponential backoff: 300ms, 600ms, 1200ms
                        Log.d("DevicesViewModel", "Will retry discovery in ${delayMillis}ms (attempt ${discoveryRetryCount}/${MAX_DISCOVERY_RETRIES})")
                        permissionRequestStatus.value = "Discovery failed. Retrying in ${delayMillis/1000.0}s..."
                        viewModelScope.launch {
                            delay(delayMillis)
                            startDiscovery(isRetry = true) // Call startDiscovery for retry
                        }
                    } else {
                        Log.e("DevicesViewModel", "Giving up on discovery after $MAX_DISCOVERY_RETRIES retries. Final Reason: $detailedReason")
                        permissionRequestStatus.value = "Discovery Failed: $detailedReason. Please try resetting Wi-Fi Direct or check system settings."
                        _isRefreshing.value = false
                        discoveryRetryCount = 0 // Reset for next user-initiated attempt
                        checkWifiDirectStatus() // Run diagnostics
                    }
                }
            })
        } catch (e: SecurityException) {
            Log.e("DevicesViewModel", "SecurityException during discoverPeers: ${e.message}", e)
            permissionRequestStatus.value = "Permission error starting discovery."
            _isRefreshing.value = false
            discoveryRetryCount = 0
            checkWifiDirectStatus()
        }
    }

    private fun getFailureReasonString(reasonCode: Int): String { // Kept for brevity in some logs
        return when (reasonCode) {
            WifiP2pManager.ERROR -> "ERROR"
            WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
            WifiP2pManager.BUSY -> "BUSY"
            WifiP2pManager.NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS"
            else -> "UNKNOWN ($reasonCode)"
        }
    }

    // More detailed error information
    private fun getDetailedFailureReasonString(reasonCode: Int): String {
        val baseReason = getFailureReasonString(reasonCode)
        return when (reasonCode) {
            WifiP2pManager.ERROR -> "$baseReason (Generic P2P Error) - System might be temporarily unavailable. Consider resetting Wi-Fi Direct or restarting the app/device."
            WifiP2pManager.P2P_UNSUPPORTED -> "$baseReason - This device does not support Wi-Fi Direct."
            WifiP2pManager.BUSY -> "$baseReason - The Wi-Fi Direct system is busy. Wait or reset Wi-Fi Direct."
            WifiP2pManager.NO_SERVICE_REQUESTS -> "$baseReason - No active service discovery requests (less common for peer discovery)."
            else -> "$baseReason - An unknown error occurred. Try resetting Wi-Fi Direct."
        }
    }


    fun registerReceiver() {
        if (channel == null) {
            Log.e("DevicesViewModel", "Cannot register receiver, channel is null or disconnected.")
            return
        }
        val context = getApplication<Application>().applicationContext
        if (receiver == null) {
            receiver = WifiDirectBroadcastReceiver(wifiP2pManager, channel, this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, intentFilter)
            }
            Log.d("DevicesViewModel", "BroadcastReceiver registered.")
        } else {
            Log.d("DevicesViewModel", "BroadcastReceiver already registered.")
        }
    }

    fun unregisterReceiver() {
        val context = getApplication<Application>().applicationContext
        if (receiver != null) {
            try {
                context.unregisterReceiver(receiver)
                Log.d("DevicesViewModel", "BroadcastReceiver unregistered.")
            } catch (e: IllegalArgumentException) {
                Log.w("DevicesViewModel", "Error unregistering receiver: ${e.message}")
            } finally {
                receiver = null
            }
        } else {
            Log.d("DevicesViewModel", "Receiver was already null.")
        }
    }

    fun onPeersAvailable(peers: Collection<WifiP2pDevice>) {
        viewModelScope.launch {
            Log.d("DevicesViewModel", "onPeersAvailable received ${peers.size} peers.")
            discoveredPeers.clear()
            discoveredPeers.addAll(peers)
            _isRefreshing.value = false // Stop refreshing indicator

            val currentStatus = permissionRequestStatus.value
            if (peers.isEmpty()) {
                Log.d("DevicesViewModel", "No peers reported by system in this update.")
                if (currentStatus.startsWith("Discovery process started") || currentStatus.contains("Retrying")) {
                    permissionRequestStatus.value = "No devices found nearby."
                }
            } else {
                Log.i("DevicesViewModel", "Peers list updated. Count: ${peers.size}")
                peers.forEach { Log.d("DevicesViewModel", "Found Device: ${it.deviceName}, Address: ${it.deviceAddress}, Status: ${it.status}") }
                if (currentStatus.startsWith("Discovery process started") || currentStatus.startsWith("No devices found") || currentStatus.contains("Retrying")) {
                    permissionRequestStatus.value = "${peers.size} device(s) found."
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun connectToDevice(device: WifiP2pDevice) {
        Log.i("DevicesViewModel", "connectToDevice called for: ${device.deviceName}")
        if (wifiP2pManager == null || channel == null) {
            Log.e("DevicesViewModel", "Cannot connect: P2PManager or Channel is null.")
            permissionRequestStatus.value = "Connection Error: P2P service not ready."
            return
        }
        // Simplified permission check for connect, assuming discovery perms imply connect perms for now
        // A more robust check could be added if specific connect issues arise.

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }
        Log.d("DevicesViewModel", "Initiating connection with config to ${device.deviceAddress}")
        try {
            wifiP2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i("DevicesViewModel", "Connection INITIATION to ${device.deviceName} SUCCEEDED.")
                    permissionRequestStatus.value = "Connecting to ${device.deviceName}..."
                }
                override fun onFailure(reasonCode: Int) {
                    val reason = getDetailedFailureReasonString(reasonCode)
                    Log.e("DevicesViewModel", "Connection INITIATION to ${device.deviceName} FAILED. Reason: $reason ($reasonCode)")
                    permissionRequestStatus.value = "Connection Failed: $reason"
                }
            })
        } catch (e: SecurityException) {
            Log.e("DevicesViewModel", "SecurityException during connect: ${e.message}", e)
            permissionRequestStatus.value = "Permission error during connection."
        }
    }

    /**
     * Add this method to your DevicesViewModel to reset the Wi-Fi Direct subsystem
     */
    fun resetWifiDirectSystem() {
        Log.i("DevicesViewModel", "resetWifiDirectSystem() - Attempting to reset Wi-Fi Direct system")
        permissionRequestStatus.value = "Resetting Wi-Fi Direct system..."
        _isRefreshing.value = true // Show refreshing during reset

        // 1. Stop any ongoing discovery and unregister the existing receiver
        try {
            wifiP2pManager?.stopPeerDiscovery(channel, null) // Fire and forget stop
        } catch (e: Exception) { Log.w("DevicesViewModel", "Exception stopping discovery during reset: ${e.message}")}
        unregisterReceiver()

        // 2. Clear current channel
        // Note: The channel is managed by WifiP2pManager.initialize().
        // We don't manually close it beyond letting go of our reference.
        // Forcing a re-initialization is the key.
        channel = null // Let go of our reference

        // 3. Clear discovered peers
        discoveredPeers.clear()

        // 4. Re-initialize Wi-Fi P2P system with slight delay
        viewModelScope.launch {
            delay(500) // Give the system a moment to settle after unregistering

            // Re-create the Wi-Fi P2P manager and channel
            // WifiP2pManager is obtained from system service, so we re-initialize channel
            initializeWifiP2p(isReset = true) // Pass flag to re-register receiver after init

            // Brief delay before updating UI message
            delay(300)
            if (channel != null) {
                permissionRequestStatus.value = "Wi-Fi Direct reset complete. Try discovery."
            } else {
                permissionRequestStatus.value = "Wi-Fi Direct reset failed to re-initialize channel. Check logs."
            }
            _isRefreshing.value = false
            checkWifiDirectStatus() // Log current status after reset
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun forceRequestPeers() {
        Log.i("DevicesViewModel", "forceRequestPeers() called.")
        if (wifiP2pManager == null || channel == null) {
            Log.e("DevicesViewModel", "forceRequestPeers - P2PManager or Channel is null.")
            permissionRequestStatus.value = "P2P System not ready for peer request."
            _isRefreshing.value = false
            return
        }
        // Define or reuse your PeerListListener here
        val peerListListener = WifiP2pManager.PeerListListener { peers ->
            Log.i("DevicesViewModel", "forceRequestPeers - PeerListListener.onPeersAvailable. System peer list size: ${peers?.deviceList?.size ?: "null"}")
            onPeersAvailable(peers?.deviceList ?: emptyList())
        }
        try {
            wifiP2pManager?.requestPeers(channel, peerListListener)
        } catch (e: SecurityException) {
            Log.e("DevicesViewModel", "SecurityException during forceRequestPeers: ${e.message}", e)
            permissionRequestStatus.value = "Permission error requesting peers."
        }
    }

    /**
     * Check the overall status of Wi-Fi Direct subsystem
     * This can be called whenever there's an issue to report system status
     */
    fun checkWifiDirectStatus(): String { // Return String for potential UI display or testing
        Log.i("DevicesViewModel", "checkWifiDirectStatus() - Running diagnostic check")
        val context = getApplication<Application>().applicationContext
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager

        val isWifiEnabled = wifiManager?.isWifiEnabled ?: false
        val diagnosticInfo = StringBuilder()
        diagnosticInfo.append("Wi-Fi Direct Diagnostics:\n")
        diagnosticInfo.append("- Wi-Fi Enabled: $isWifiEnabled\n")
        diagnosticInfo.append("- WifiP2pManager: ${if (wifiP2pManager != null) "Initialized" else "NULL"}\n")
        diagnosticInfo.append("- Channel: ${if (channel != null) "Active" else "NULL or Disconnected"}\n")
        diagnosticInfo.append("- Broadcast Receiver: ${if (receiver != null) "Registered Instance Present" else "Not Registered or Null Instance"}\n")

        val androidVersion = Build.VERSION.SDK_INT
        diagnosticInfo.append("- Android API Level: $androidVersion (${Build.VERSION.RELEASE})\n")

        var permsOk = true
        getWifiDirectPermissions().forEach { perm ->
            val granted = context.hasPermission(perm)
            diagnosticInfo.append("- Permission '$perm': $granted\n")
            if (!granted) permsOk = false
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        var isLocationEnabled = false
        if (locationManager != null) {
            isLocationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                locationManager.isLocationEnabled // API 28+
            } else {
                // For older versions, check specific providers
                try {
                    val gpsEnabled = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                    val networkEnabled = locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
                    gpsEnabled || networkEnabled
                } catch (e: Exception) { false }
            }
        }
        diagnosticInfo.append("- System Location Enabled: $isLocationEnabled\n")
// ...
        if (!isLocationEnabled) {
            // Prepend or append this to permissionRequestStatus.value
            // This is a critical piece of info for the user.
            val currentStatus = permissionRequestStatus.value
            permissionRequestStatus.value = "Error: System Location is OFF. Please enable it. ($currentStatus)"
        }

        Log.i("DevicesViewModel", diagnosticInfo.toString())

        // Update UI with brief status if diagnostics reveal issues
        if (!isWifiEnabled) {
            permissionRequestStatus.value = "Error: Wi-Fi is disabled."
        } else if (!permsOk) {
            permissionRequestStatus.value = "Error: Required P2P permissions missing."
        } else if (wifiP2pManager == null || channel == null) {
            permissionRequestStatus.value = "Error: P2P system not ready. Try reset."
        }
        // If all checks pass, don't override current status unless it's an error one
        return diagnosticInfo.toString()
    }


    override fun onCleared() {
        super.onCleared()
        Log.d("DevicesViewModel", "onCleared called - ViewModel is being destroyed.")
        unregisterReceiver()
        // Consider if stopPeerDiscovery or removeGroup is needed here.
        // If the channel is already null (e.g., from onChannelDisconnected), these calls might not work.
        if (channel != null && wifiP2pManager != null) {
            try {
                wifiP2pManager?.stopPeerDiscovery(channel, null) // Best effort
            } catch (e: Exception) { Log.w("DevicesViewModel", "Exception in onCleared/stopPeerDiscovery: ${e.message}")}
            // wifiP2pManager?.removeGroup(channel, null) // Use with caution
        }
        Log.d("DevicesViewModel", "onCleared finished.")
    }
}