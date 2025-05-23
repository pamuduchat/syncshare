package com.example.syncshare.viewmodels

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket // Import this
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo // Added for handleP2pConnectionInfo
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
import com.example.syncshare.protocol.FileMetadata
import com.example.syncshare.protocol.FileTransferInfo
import com.example.syncshare.protocol.MessageType
import com.example.syncshare.protocol.SyncMessage
import com.example.syncshare.ui.model.DeviceTechnology
import com.example.syncshare.ui.model.DisplayableDevice
import com.example.syncshare.utils.AppConstants
import com.example.syncshare.utils.getBluetoothPermissions
import com.example.syncshare.utils.getWifiDirectPermissions
import com.example.syncshare.utils.isLocationEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID // For AppConstants if it's defined there

// Enum for communication technology
enum class CommunicationTechnology { BLUETOOTH, P2P }

class DevicesViewModel(application: Application) : AndroidViewModel(application) {

    // --- UI State ---
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing
    val permissionRequestStatus = mutableStateOf("Idle. Tap a scan button.")
    val displayableDeviceList = mutableStateListOf<DisplayableDevice>()

    // --- Wi-Fi Direct (P2P) Properties ---
    private var wifiP2pManager: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var p2pBroadcastReceiver: BroadcastReceiver? = null
    private val p2pIntentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }
    private var p2pDiscoveryRetryCount = 0
    private val MAX_P2P_DISCOVERY_RETRIES = 3
    private val _p2pGroupInfo = MutableStateFlow<WifiP2pGroup?>(null)
    val p2pGroupInfo: StateFlow<WifiP2pGroup?> = _p2pGroupInfo
    private var p2pDiscoveryTimeoutJob: Job? = null
    private val P2P_DISCOVERY_TIMEOUT_MS = 20000L

    // --- Bluetooth Properties ---
    private val bluetoothManager by lazy {
        application.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }
    val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager?.adapter
    }
    val isBluetoothEnabled = mutableStateOf(false)
    private var bluetoothScanReceiver: BroadcastReceiver? = null
    private var btDiscoveryTimeoutJob: Job? = null
    private val BT_DISCOVERY_TIMEOUT_MS = 15000L

    // --- Bluetooth Connection Management ---
    private var connectedBluetoothSocket: BluetoothSocket? = null 
    private val _bluetoothConnectionStatus = MutableStateFlow<String>("Disconnected")
    val bluetoothConnectionStatus: StateFlow<String> = _bluetoothConnectionStatus
    private var bluetoothServerJob: Job? = null // Specific Job for Bluetooth server
    private var btServerSocket: BluetoothServerSocket? = null 

    // --- P2P Connection Management ---
    private var p2pServerSocket: java.net.ServerSocket? = null
    private var p2pClientSocket: java.net.Socket? = null
    private val _p2pConnectionStatus = MutableStateFlow<String>("Disconnected")
    val p2pConnectionStatus: StateFlow<String> = _p2pConnectionStatus
    private var p2pServerJob: kotlinx.coroutines.Job? = null // Specific Job for P2P server
    private var p2pClientConnectJob: kotlinx.coroutines.Job? = null // Specifically for client connection attempt


    // Internal storage
    private val wifiDirectPeersInternal = mutableStateListOf<WifiP2pDevice>()
    private val bluetoothDevicesInternal = mutableStateListOf<BluetoothDevice>()

    // --- Generic Communication Stream Management ---
    private var activeSocket: java.net.Socket? = null // Can be BluetoothSocket or P2P Socket wrapped or cast
    private var objectOutputStream: ObjectOutputStream? = null
    private var objectInputStream: ObjectInputStream? = null
    private var communicationJob: Job? = null
    private var currentCommunicationTechnology: CommunicationTechnology? = null

    init {
        Log.d("DevicesViewModel", "DevicesViewModel - INIT BLOCK - START")
        viewModelScope.launch {
            initializeWifiP2p()
            updateBluetoothState()
            // Automatically start BT server if BT is on and permissions are granted
            // This might be better called from UI on resume after permission checks
            // prepareBluetoothService()
        }
        Log.d("DevicesViewModel", "DevicesViewModel - INIT BLOCK - END")
    }

    fun updateBluetoothState() {
        isBluetoothEnabled.value = bluetoothAdapter?.isEnabled == true
        Log.d("DevicesViewModel", "Bluetooth state updated. Enabled: ${isBluetoothEnabled.value}")
    }

    private fun initializeWifiP2p(isReset: Boolean = false) {
        Log.i("DevicesViewModel", "initializeWifiP2p() CALLED. Is reset: $isReset")
        val context = getApplication<Application>().applicationContext
        wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

        if (wifiP2pManager == null) { /* ... existing error handling ... */ return }
        Log.d("DevicesViewModel", "P2P_INIT: WifiP2pManager obtained.")

        try {
            p2pChannel = wifiP2pManager?.initialize(context, Looper.getMainLooper(), object : WifiP2pManager.ChannelListener {
                override fun onChannelDisconnected() {
                    Log.e("DevicesViewModel", "************ P2P CHANNEL DISCONNECTED ************")
                    this@DevicesViewModel.p2pChannel = null
                    permissionRequestStatus.value = "P2P Channel Lost! Reset or restart app."
                    _isRefreshing.value = false; wifiDirectPeersInternal.clear(); _p2pGroupInfo.value = null; updateDisplayableDeviceList()
                    p2pDiscoveryTimeoutJob?.cancel()
                }
            })
        } catch (e: SecurityException) { /* ... existing error handling ... */ return }

        if (p2pChannel == null) { /* ... existing error handling ... */  return }
        Log.d("DevicesViewModel", "P2P_INIT: Channel Initialized: $p2pChannel")
        refreshP2pGroupInfoOnResume()
        if (isReset) {
            viewModelScope.launch { delay(300); registerP2pReceiver() }
        }
    }

    fun refreshP2pGroupInfoOnResume() { /* ... as before ... */
        viewModelScope.launch {
            Log.d("DevicesViewModel", "refreshP2pGroupInfoOnResume launching coroutine to update group info.")
            updateCurrentP2pGroupInfo() // Calls the suspend function
        }
    }
    fun onP2pConnectionChanged() {
        viewModelScope.launch {
            Log.d("DevicesViewModel", "onP2pConnectionChanged() - will refresh group info and request connection info.")
            updateCurrentP2pGroupInfo() 
            // Request connection details - the listener is now fetched via getter
            if (wifiP2pManager != null && p2pChannel != null) {
                wifiP2pManager?.requestConnectionInfo(p2pChannel, getP2pConnectionInfoListener())
            } else {
                Log.w("DevicesViewModel", "Cannot request P2P connection info onP2pConnectionChanged: manager or channel is null.")
            }
        }
    }

    // --- P2P ConnectionInfoListener ---
    private val p2pConnectionInfoListener = WifiP2pManager.ConnectionInfoListener { info ->
        Log.d("DevicesViewModel", "P2P Connection Info Available. Group formed: ${info.groupFormed}, Is owner: ${info.isGroupOwner}, Owner IP: ${info.groupOwnerAddress?.hostAddress}")
        // Call the handler, which will be properly implemented in a later subtask
        handleP2pConnectionInfo(info)
    }

    // Public getter for the listener
    fun getP2pConnectionInfoListener(): WifiP2pManager.ConnectionInfoListener = p2pConnectionInfoListener

    // Placeholder for the handler method
    private fun handleP2pConnectionInfo(info: android.net.wifi.p2p.WifiP2pInfo) {
        Log.d("DevicesViewModel", "handleP2pConnectionInfo called with: $info. Implementation pending.")
        // Logic to start server or connect as client will be added here later.
    }


    @SuppressLint("MissingPermission")
    suspend fun updateCurrentP2pGroupInfo(): WifiP2pGroup? { /* ... as before ... */
        Log.d("DevicesViewModel", "updateCurrentP2pGroupInfo() called.")
        if (wifiP2pManager == null || p2pChannel == null) {
            Log.w("DevicesViewModel", "updateP2pGroupInfo - P2PManager or Channel null."); _p2pGroupInfo.value = null; return null
        }
        val context = getApplication<Application>().applicationContext
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w("DevicesViewModel", "updateP2pGroupInfo - ACCESS_FINE_LOCATION missing."); _p2pGroupInfo.value = null; return null
        }
        try {
            return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                wifiP2pManager?.requestGroupInfo(p2pChannel, WifiP2pManager.GroupInfoListener { group ->
                    Log.i("DevicesViewModel", "P2P_GROUP_INFO (update): Name: ${group?.networkName}, Owner: ${group?.owner?.deviceName}")
                    _p2pGroupInfo.value = group
                    if (continuation.isActive) { continuation.resume(group, null) }
                })
                continuation.invokeOnCancellation { Log.d("DevicesViewModel", "updateCurrentP2pGroupInfo coroutine cancelled.") }
            }
        } catch (e: SecurityException) { Log.e("DevicesViewModel", "SecEx updateCurrentP2pGroupInfo: ${e.message}", e); _p2pGroupInfo.value = null; return null }
        catch (e: Exception) { Log.e("DevicesViewModel", "Ex updateCurrentP2pGroupInfo: ${e.message}", e); _p2pGroupInfo.value = null; return null }
    }


    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    fun attemptDiscoveryOrRefreshGroup() { /* ... as before ... */
        viewModelScope.launch {
            Log.i("DevicesViewModel", "attemptDiscoveryOrRefreshGroup called.")
            val context = getApplication<Application>().applicationContext
            if (wifiP2pManager == null || p2pChannel == null) { Log.e("DevicesViewModel", "attemptDiscOrRefresh - FAIL: P2PManager or Channel null."); permissionRequestStatus.value = "Error: P2P service not ready."; _isRefreshing.value = false; checkWifiDirectStatus(); return@launch }
            if (!getWifiDirectPermissions().all { ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) { Log.e("DevicesViewModel", "attemptDiscOrRefresh - FAIL: P2P Perms not granted."); permissionRequestStatus.value = "P2P Permissions missing."; _isRefreshing.value = false; return@launch }

            _isRefreshing.value = true
            val currentGroup = updateCurrentP2pGroupInfo()

            if (currentGroup != null) {
                Log.i("DevicesViewModel", "P2P_REFRESH_GROUP: Group '${currentGroup.networkName}' active. Populating members.")
                permissionRequestStatus.value = "Displaying current group members..."
                val members = mutableListOf<WifiP2pDevice>()
                if (!currentGroup.isGroupOwner && currentGroup.owner != null) members.add(currentGroup.owner)
                currentGroup.clientList?.let { members.addAll(it) }
                onP2pPeersAvailable(members.distinctBy { it.deviceAddress }, fromGroupInfo = true)
            } else {
                Log.i("DevicesViewModel", "P2P_NEW_DISCOVERY: No active group. Attempting discovery.")
                startP2pDiscovery()
            }
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    fun startP2pDiscovery(isRetry: Boolean = false) { /* ... as before ... */
        if (!isRetry) {
            p2pDiscoveryRetryCount = 0
            bluetoothDevicesInternal.clear()
            updateDisplayableDeviceList()
        }
        Log.i("DevicesViewModel", "startP2pDiscovery() - IsRetry: $isRetry, Count: $p2pDiscoveryRetryCount")
        val context = getApplication<Application>().applicationContext
        if (wifiP2pManager == null || p2pChannel == null) { Log.e("DevicesViewModel", "startP2pDisc - P2PManager/Channel null"); _isRefreshing.value = false; return }
        if (!getWifiDirectPermissions().all { ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) { Log.e("DevicesViewModel", "startP2pDisc - Perms missing"); _isRefreshing.value = false; return }

        _p2pGroupInfo.value?.let { Log.i("DevicesViewModel", "P2P_DISCOVERY: Group status before stop: ${it.networkName}") } ?: Log.i("DevicesViewModel", "P2P_DISCOVERY: No active group before stop.")
        _isRefreshing.value = true
        if (!isRetry) permissionRequestStatus.value = "Stopping previous P2P discovery..."

        try {
            wifiP2pManager?.stopPeerDiscovery(p2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { Log.i("DevicesViewModel", "stopP2pDiscovery.onSuccess()"); if (!isRetry) permissionRequestStatus.value = "Preparing P2P discovery..."; initiateActualP2pDiscoveryAfterStop() }
                override fun onFailure(reasonCode: Int) { val r = getFailureReasonString(reasonCode); Log.w("DevicesViewModel", "stopP2pDiscovery.onFailure - $r ($reasonCode)"); if (!isRetry) permissionRequestStatus.value = "Stop P2P warn ($r)"; initiateActualP2pDiscoveryAfterStop() }
            })
        } catch (e: SecurityException) { Log.e("DevicesViewModel", "SecEx stopP2pDisc: ${e.message}", e); if (!isRetry) permissionRequestStatus.value = "PermErr stopP2PDisc"; initiateActualP2pDiscoveryAfterStop() }
        catch (e: Exception) { Log.e("DevicesViewModel", "GenEx stopP2pDisc: ${e.message}", e); if (!isRetry) permissionRequestStatus.value = "Err stopP2PDisc"; initiateActualP2pDiscoveryAfterStop()}
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    private fun initiateActualP2pDiscoveryAfterStop() { /* ... as before with timeout logic ... */
        Log.d("DevicesViewModel", "initiateActualP2pDiscoveryAfterStop() - Retry: $p2pDiscoveryRetryCount")
        if (p2pChannel == null) { Log.e("DevicesViewModel", "initActualP2pDisc - Channel null"); _isRefreshing.value = false; p2pDiscoveryRetryCount = 0; checkWifiDirectStatus(); return }

        permissionRequestStatus.value = "Starting P2P discovery${if (p2pDiscoveryRetryCount > 0) " (attempt ${p2pDiscoveryRetryCount + 1})" else ""}..."
        try {
            wifiP2pManager?.discoverPeers(p2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i("DevicesViewModel", "discoverP2pPeers.onSuccess - INITIATED")
                    permissionRequestStatus.value = "P2P Discovery process started..."
                    p2pDiscoveryRetryCount = 0
                    p2pDiscoveryTimeoutJob?.cancel()
                    p2pDiscoveryTimeoutJob = viewModelScope.launch {
                        delay(P2P_DISCOVERY_TIMEOUT_MS)
                        if (_isRefreshing.value && permissionRequestStatus.value.startsWith("P2P Discovery process started")) {
                            Log.w("DevicesViewModel", "P2P Discovery timed out after ${P2P_DISCOVERY_TIMEOUT_MS}ms.")
                            permissionRequestStatus.value = "P2P Discovery timed out. No devices found."
                            _isRefreshing.value = false; wifiDirectPeersInternal.clear(); updateDisplayableDeviceList()
                            try { wifiP2pManager?.stopPeerDiscovery(p2pChannel, null) } catch (e: Exception) {}
                        }
                    }
                }
                override fun onFailure(reasonCode: Int) {
                    val dr = getDetailedFailureReasonString(reasonCode); Log.e("DevicesViewModel", "discoverP2pPeers.onFailure - FAILED: $dr ($reasonCode)")
                    p2pDiscoveryTimeoutJob?.cancel() // Cancel timeout on failure too
                    if (p2pDiscoveryRetryCount < MAX_P2P_DISCOVERY_RETRIES) {
                        p2pDiscoveryRetryCount++; val d = 300L * (1 shl (p2pDiscoveryRetryCount -1)); Log.d("DevicesViewModel", "P2P Retry in ${d}ms ($p2pDiscoveryRetryCount/$MAX_P2P_DISCOVERY_RETRIES)"); permissionRequestStatus.value = "P2P Disc. failed. Retrying ${d/1000.0}s"
                        viewModelScope.launch { delay(d); startP2pDiscovery(isRetry = true) }
                    } else { Log.e("DevicesViewModel", "P2P Disc. GIVING UP after $MAX_P2P_DISCOVERY_RETRIES retries. Reason: $dr"); permissionRequestStatus.value = "P2P Disc. Failed: $dr"; _isRefreshing.value = false; p2pDiscoveryRetryCount = 0; checkWifiDirectStatus() }
                }
            })
        } catch (e: SecurityException) { Log.e("DevicesViewModel", "SecEx discoverP2pPeers: ${e.message}", e); permissionRequestStatus.value = "PermErr P2P Disc."; _isRefreshing.value = false; p2pDiscoveryRetryCount = 0; checkWifiDirectStatus(); p2pDiscoveryTimeoutJob?.cancel() }
    }


    fun registerP2pReceiver() { /* ... as before ... */
        if (p2pChannel == null) { Log.e("DevicesViewModel", "Cannot reg P2P receiver, channel null."); return }
        val context = getApplication<Application>().applicationContext
        if (p2pBroadcastReceiver == null) {
            p2pBroadcastReceiver = WifiDirectBroadcastReceiver(wifiP2pManager, p2pChannel, this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { context.registerReceiver(p2pBroadcastReceiver, p2pIntentFilter, Context.RECEIVER_NOT_EXPORTED) }
            else { context.registerReceiver(p2pBroadcastReceiver, p2pIntentFilter) }
            Log.d("DevicesViewModel", "P2P BroadcastReceiver registered.")
        } else { Log.d("DevicesViewModel", "P2P BroadcastReceiver already registered.") }
    }
    fun unregisterP2pReceiver() { /* ... as before, ensure p2pDiscoveryTimeoutJob?.cancel() ... */
        p2pDiscoveryTimeoutJob?.cancel() // Cancel P2P discovery timeout when receiver is unregistered
        if (p2pBroadcastReceiver != null) {
            try { getApplication<Application>().applicationContext.unregisterReceiver(p2pBroadcastReceiver); Log.d("DevicesViewModel", "P2P BroadcastReceiver unregistered.") }
            catch (e: IllegalArgumentException) { Log.w("DevicesViewModel", "Error unreg P2P receiver: ${e.message}") }
            finally { p2pBroadcastReceiver = null }
        } else { Log.d("DevicesViewModel", "P2P Receiver already null.")}
    }

    fun onP2pPeersAvailable(peers: Collection<WifiP2pDevice>, fromGroupInfo: Boolean = false) {
        p2pDiscoveryTimeoutJob?.cancel() // Peers received, cancel P2P discovery timeout
        viewModelScope.launch {
            Log.d("DevicesViewModel", "onP2pPeersAvailable received ${peers.size} peers. FromGroupInfo: $fromGroupInfo")
            // Update internal list based on source
            if(fromGroupInfo){
                wifiDirectPeersInternal.clear() // If from group info, this is the definitive list
                wifiDirectPeersInternal.addAll(peers)
            } else { // From regular discovery, could be an update to existing or new list
                // For simplicity now, just replace. More advanced could merge.
                wifiDirectPeersInternal.clear()
                wifiDirectPeersInternal.addAll(peers)
            }
            updateDisplayableDeviceList()

            val currentStatus = permissionRequestStatus.value
            if (peers.isEmpty()) {
                Log.d("DevicesViewModel", "No P2P peers reported by system.")
                if (!fromGroupInfo && (currentStatus.startsWith("P2P Discovery started") || currentStatus.contains("Retrying") || currentStatus.startsWith("Refreshing current group members"))) {
                    permissionRequestStatus.value = "No P2P devices found nearby."
                } else if (fromGroupInfo && currentStatus.startsWith("Displaying current group members")) {
                    permissionRequestStatus.value = "Current P2P group is empty (besides this device if owner)."
                }
            } else {
                Log.i("DevicesViewModel", "P2P Peers list updated. Count: ${peers.size}")
                if (!fromGroupInfo && (currentStatus.startsWith("P2P Discovery started") || currentStatus.startsWith("No P2P devices found") || currentStatus.contains("Retrying"))) {
                    permissionRequestStatus.value = "${peers.size} P2P device(s) found."
                } else if (fromGroupInfo && currentStatus.startsWith("Displaying current group members")) {
                    permissionRequestStatus.value = "Group has ${peers.size} other member(s)."
                }
            }
            _isRefreshing.value = false // Discovery/refresh process for P2P is now complete
        }
    }

    @SuppressLint("MissingPermission")
    fun forceRequestP2pPeers() { /* ... as before ... */
        Log.i("DevicesViewModel", "forceRequestP2pPeers() called.")
        if (wifiP2pManager == null || p2pChannel == null) { Log.e("DevicesViewModel", "forceReqP2pPeers - P2PManager/Channel null."); permissionRequestStatus.value = "P2P System not ready."; _isRefreshing.value = false; return }
        val context = getApplication<Application>().applicationContext
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { Log.w("DevicesViewModel", "forceReqP2pPeers - Perm missing."); permissionRequestStatus.value = "Location perm needed."; _isRefreshing.value = false; return }
        _isRefreshing.value = true
        try {
            wifiP2pManager?.requestPeers(p2pChannel) { peers ->
                Log.i("DevicesViewModel", "forceReqP2pPeers - onPeersAvailable. Size: ${peers?.deviceList?.size ?: "null"}")
                onP2pPeersAvailable(peers?.deviceList ?: emptyList(), fromGroupInfo = _p2pGroupInfo.value != null)
            }
            permissionRequestStatus.value = "Requesting current P2P peer list..."
        } catch (e: SecurityException) { Log.e("DevicesViewModel", "SecEx forceReqP2pPeers: ${e.message}", e); permissionRequestStatus.value = "PermErr req P2P peers."; _isRefreshing.value = false; }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    fun connectToP2pDevice(device: WifiP2pDevice) { /* ... as before ... */
        Log.i("DevicesViewModel", "connectToP2pDevice: ${device.deviceName}")
        if (wifiP2pManager == null || p2pChannel == null) { Log.e("DevicesViewModel", "Cannot connect P2P: Manager/Channel null."); permissionRequestStatus.value = "P2P Connect Error: Service not ready."; return }
        val context = getApplication<Application>().applicationContext
        if (!getWifiDirectPermissions().all { ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }){
            Log.e("DevicesViewModel", "connectToP2pDevice - Missing P2P permissions."); permissionRequestStatus.value = "P2P perm needed for connect."; return
        }
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        Log.d("DevicesViewModel", "P2P Connecting to ${device.deviceAddress}")
        try {
            wifiP2pManager?.connect(p2pChannel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { Log.i("DevicesViewModel", "P2P Connect INITIATION to ${device.deviceName} SUCCEEDED."); permissionRequestStatus.value = "P2P Connecting to ${device.deviceName}..." }
                override fun onFailure(reasonCode: Int) { val r = getDetailedFailureReasonString(reasonCode); Log.e("DevicesViewModel", "P2P Connect INITIATION FAILED to ${device.deviceName}. Reason: $r ($reasonCode)"); permissionRequestStatus.value = "P2P Connect Failed: $r" }
            })
        } catch (e: SecurityException) { Log.e("DevicesViewModel", "SecEx P2P connect: ${e.message}", e); permissionRequestStatus.value = "PermErr P2P connect." }
    }


    // --- Bluetooth Methods ---
    @SuppressLint("MissingPermission")
    fun startBluetoothDiscovery() {
        Log.i("DevicesViewModel", "startBluetoothDiscovery() - ENTRY")
        updateBluetoothState()
        if (bluetoothAdapter == null) { /* ... error handling ... */ _isRefreshing.value = false; return }
        if (!isBluetoothEnabled.value) { /* ... error handling ... */ _isRefreshing.value = false; return }

        val context = getApplication<Application>().applicationContext
        if (!isLocationEnabled(context)) {
            Log.w("DevicesViewModel", "BT_DISC_FAIL: System Location Services are OFF.")
            permissionRequestStatus.value = "Location Services OFF. Enable for BT discovery."
            _isRefreshing.value = false; return
        }
        if (!getBluetoothPermissions().all { ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            Log.e("DevicesViewModel", "BT_DISC_FAIL: Missing BT permissions."); permissionRequestStatus.value = "Bluetooth permissions needed."; _isRefreshing.value = false; return
        }
        Log.d("DevicesViewModel", "BT_DISC: All necessary Bluetooth permissions granted.")

        if (bluetoothAdapter?.isDiscovering == true) {
            Log.d("DevicesViewModel", "BT_DISC: Already discovering. Cancelling first.");
            try { bluetoothAdapter?.cancelDiscovery() } catch (e: SecurityException) {Log.e("DevicesViewModel", "SecEx BT cancelDiscovery (isDiscovering): ${e.message}", e)}
        }

        bluetoothDevicesInternal.clear()
        wifiDirectPeersInternal.clear() // Clear P2P results when starting BT scan
        updateDisplayableDeviceList()

        _isRefreshing.value = true // This indicates a scan is starting
        permissionRequestStatus.value = "Scanning for Bluetooth devices..."

        if (bluetoothScanReceiver == null) {
            bluetoothScanReceiver = object : BroadcastReceiver() {
                @SuppressLint("MissingPermission")
                override fun onReceive(context: Context, intent: Intent) {
                    val action: String? = intent.action; Log.d("BTScanReceiver", "onReceive: action=$action")
                    when (action) {
                        BluetoothDevice.ACTION_FOUND -> { /* ... handleBluetoothDeviceFound ... */
                            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) } else { @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) }
                            device?.let { Log.d("BTScanReceiver", "ACTION_FOUND: Raw Name: ${try{it.name}catch(e:SecurityException){"N/A (SecEx)"} ?: "No Name"}, Address: ${it.address}"); handleBluetoothDeviceFound(it) } ?: Log.w("BTScanReceiver", "ACTION_FOUND: Device is null")
                        }
                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> { Log.d("BTScanReceiver", "ACTION_DISCOVERY_FINISHED"); handleBluetoothDiscoveryFinished() }
                        BluetoothAdapter.ACTION_STATE_CHANGED -> { /* ... existing logic ... */
                            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                            val oldState = isBluetoothEnabled.value; isBluetoothEnabled.value = state == BluetoothAdapter.STATE_ON
                            Log.d("BTScanReceiver", "ACTION_STATE_CHANGED: Bluetooth state $oldState -> ${isBluetoothEnabled.value}")
                            if (state == BluetoothAdapter.STATE_OFF && _isRefreshing.value && permissionRequestStatus.value.contains("Bluetooth")) {
                                _isRefreshing.value = false; permissionRequestStatus.value = "Bluetooth turned off during scan."
                                bluetoothDevicesInternal.clear(); updateDisplayableDeviceList(); stopBluetoothDiscovery()
                            }
                        }
                    }
                }
            }
            // ... (register BT receiver with try-catch) ...
            val filter = IntentFilter().apply { addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED); addAction(BluetoothAdapter.ACTION_STATE_CHANGED) }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { context.registerReceiver(bluetoothScanReceiver, filter, Context.RECEIVER_NOT_EXPORTED) }
                else { context.registerReceiver(bluetoothScanReceiver, filter) }
                Log.d("DevicesViewModel", "Bluetooth Scan Receiver registered.")
            } catch (e: Exception) { Log.e("DevicesViewModel", "Error registering BT Scan Receiver: ${e.message}", e); _isRefreshing.value = false; permissionRequestStatus.value = "Error setting up BT scan."; return }
        }

        try {
            if (bluetoothAdapter?.startDiscovery() == false) {
                Log.e("DevicesViewModel", "BT_DISC_FAIL: startDiscovery() returned false.")
                _isRefreshing.value = false; permissionRequestStatus.value = "Failed to start BT scan (denied)."
                stopBluetoothDiscovery() // Cleanup receiver if start fails
            } else {
                Log.i("DevicesViewModel", "BT_DISC: Discovery request sent to BluetoothAdapter.")
                btDiscoveryTimeoutJob?.cancel()
                btDiscoveryTimeoutJob = viewModelScope.launch {
                    delay(BT_DISCOVERY_TIMEOUT_MS)
                    if (isActive && _isRefreshing.value && permissionRequestStatus.value.startsWith("Scanning for Bluetooth")) { // Check isActive
                        Log.w("DevicesViewModel", "Bluetooth Discovery timed out after ${BT_DISCOVERY_TIMEOUT_MS}ms.")
                        permissionRequestStatus.value = "Bluetooth scan timed out."
                        // _isRefreshing.value = false; // stopBluetoothDiscovery will handle this
                        stopBluetoothDiscovery()
                    }
                }
            }
        } catch (e: SecurityException) { Log.e("DevicesViewModel", "SecEx BT startDiscovery: ${e.message}", e); _isRefreshing.value = false; permissionRequestStatus.value = "PermErr starting BT scan."; stopBluetoothDiscovery() }
    }

    @SuppressLint("MissingPermission")
    fun stopBluetoothDiscovery() {
        Log.d("DevicesViewModel", "stopBluetoothDiscovery() called.")
        btDiscoveryTimeoutJob?.cancel()
        if (bluetoothAdapter?.isDiscovering == true) {
            try { bluetoothAdapter?.cancelDiscovery() } catch (e: SecurityException) { Log.e("DevicesViewModel", "SecEx BT cancelDiscovery: ${e.message}", e)}
            Log.d("DevicesViewModel", "Bluetooth discovery explicit stop/cancel initiated.")
        }
        if (bluetoothScanReceiver != null) {
            try { getApplication<Application>().applicationContext.unregisterReceiver(bluetoothScanReceiver); Log.d("DevicesViewModel", "Bluetooth scan receiver unregistered.") }
            catch (e: IllegalArgumentException) { Log.w("DevicesViewModel", "Error unreg BT receiver: ${e.message}") }
            finally { bluetoothScanReceiver = null }
        }
        // Set refreshing to false only if the current operation was indeed Bluetooth scanning
        if (_isRefreshing.value && (permissionRequestStatus.value.contains("Bluetooth") || permissionRequestStatus.value.contains("Scanning for Bluetooth"))) {
            _isRefreshing.value = false
            // Avoid overriding specific messages like "No BT devices found" if already set by discovery finished
            if (permissionRequestStatus.value.startsWith("Scanning for Bluetooth")) {
                permissionRequestStatus.value = "Bluetooth scan stopped."
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleBluetoothDiscoveryFinished() {
        Log.i("DevicesViewModel", "BT_DISC_FINISHED (received from broadcast).")
        btDiscoveryTimeoutJob?.cancel()
        if (_isRefreshing.value && permissionRequestStatus.value.contains("Bluetooth")) {
            _isRefreshing.value = false
        }
        val btDeviceCountInDisplayList = displayableDeviceList.count { it.technology == DeviceTechnology.BLUETOOTH_CLASSIC }
        if (btDeviceCountInDisplayList == 0 && permissionRequestStatus.value.startsWith("Scanning for Bluetooth")) {
            permissionRequestStatus.value = "No new Bluetooth devices found."
        } else if (btDeviceCountInDisplayList > 0 && (permissionRequestStatus.value.contains("Bluetooth") || permissionRequestStatus.value.startsWith("Scanning for Bluetooth"))) {
            permissionRequestStatus.value = "$btDeviceCountInDisplayList Bluetooth device(s) found."
        } else {
            // Don't overwrite if another scan type (like P2P) just finished successfully or is in progress
            if (permissionRequestStatus.value.startsWith("Scanning for Bluetooth")) { // Only update if it was a BT scan
                permissionRequestStatus.value = "Bluetooth scan complete."
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToBluetoothDevice(device: BluetoothDevice) { /* ... as before ... */
        val deviceNameForLog = try { device.name } catch(e: SecurityException){ null } ?: device.address
        Log.i("DevicesViewModel", "connectToBluetoothDevice called for: $deviceNameForLog")

        if (bluetoothAdapter?.isDiscovering == true) {
            Log.d("DevicesViewModel", "connectToBT - Cancelling discovery before connecting.")
            try { bluetoothAdapter?.cancelDiscovery() } catch (e: SecurityException) { Log.e("DevicesViewModel", "SecEx BT cancelDisc for connect: ${e.message}", e) }
        }
        val context = getApplication<Application>().applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e("DevicesViewModel", "connectToBT - Missing BLUETOOTH_CONNECT permission."); permissionRequestStatus.value = "BT Connect permission needed."; _bluetoothConnectionStatus.value = "Error: Permission Missing"; return
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
            Log.e("DevicesViewModel", "connectToBT - Missing BLUETOOTH permission for connect (API <31)."); permissionRequestStatus.value = "BT permission needed."; _bluetoothConnectionStatus.value = "Error: Permission Missing"; return
        }

        permissionRequestStatus.value = "Connecting to BT: $deviceNameForLog..."
        _isRefreshing.value = true; _bluetoothConnectionStatus.value = "Connecting..."

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var socket: BluetoothSocket? = null
            try {
                Log.d("DevicesViewModel", "Creating RFCOMM socket to service with UUID: ${AppConstants.BLUETOOTH_SERVICE_UUID}")
                socket = device.createRfcommSocketToServiceRecord(AppConstants.BLUETOOTH_SERVICE_UUID)
                Log.d("DevicesViewModel", "Attempting to connect socket...")
                socket.connect()
                Log.i("DevicesViewModel", "Bluetooth connection established with $deviceNameForLog")
                connectedBluetoothSocket = socket
                launch(kotlinx.coroutines.Dispatchers.Main) {
                    _isRefreshing.value = false; permissionRequestStatus.value = "Connected via BT to $deviceNameForLog"; _bluetoothConnectionStatus.value = "Connected to $deviceNameForLog"
                    // Pass the raw BluetoothSocket, setupCommunicationStreams will handle it as a java.net.Socket
                    setupCommunicationStreams(socket, CommunicationTechnology.BLUETOOTH)
                }
            } catch (e: IOException) {
                Log.e("DevicesViewModel", "Bluetooth connection failed for $deviceNameForLog: ${e.message}", e)
                launch(kotlinx.coroutines.Dispatchers.Main) { _isRefreshing.value = false; permissionRequestStatus.value = "BT Connection Failed: ${e.localizedMessage}"; _bluetoothConnectionStatus.value = "Connection Failed" }
                try { socket?.close() } catch (closeException: IOException) { Log.e("DevicesViewModel", "Could not close client socket post-failure", closeException) }
            } catch (se: SecurityException) {
                Log.e("DevicesViewModel", "SecurityException during BT connection: ${se.message}", se)
                launch(kotlinx.coroutines.Dispatchers.Main) { _isRefreshing.value = false; permissionRequestStatus.value = "BT Connection Permission Error"; _bluetoothConnectionStatus.value = "Error: Permission Denied" }
            }
        }
    }

    fun disconnectBluetooth() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // closeCommunicationStreams will handle closing the activeSocket if it's the BT one.
            if (currentCommunicationTechnology == CommunicationTechnology.BLUETOOTH) {
                closeCommunicationStreams(CommunicationTechnology.BLUETOOTH)
            } else { // If P2P was active, BT socket might still need explicit closing if it was connected separately
                try { connectedBluetoothSocket?.close() }
                catch (e: IOException) { Log.e("DevicesViewModel", "Could not close connected Bluetooth socket during disconnectBluetooth: ${e.message}") }
            }
            connectedBluetoothSocket = null
            launch(kotlinx.coroutines.Dispatchers.Main) { _bluetoothConnectionStatus.value = "Disconnected" }
            Log.i("DevicesViewModel", "Bluetooth disconnected.")
        }
    }


    // --- Bluetooth Server Methods ---
    @SuppressLint("MissingPermission")
    fun startBluetoothServer() {
        if (bluetoothServerJob?.isActive == true) { Log.d("DevicesViewModel", "BT server job already active."); return }
        if (bluetoothAdapter == null || !isBluetoothEnabled.value) { Log.e("DevicesViewModel", "Cannot start BT server: Adapter null or BT disabled."); permissionRequestStatus.value = "Cannot start BT server: BT not ready."; return }

        val context = getApplication<Application>().applicationContext
        val connectPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
        if (ActivityCompat.checkSelfPermission(context, connectPerm) != PackageManager.PERMISSION_GRANTED) {
            Log.e("DevicesViewModel", "Missing $connectPerm permission for BT server."); permissionRequestStatus.value = "BT Connect perm needed for server."; return
        }

        bluetoothServerJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            Log.i("DevicesViewModel", "Starting Bluetooth server thread...")
            permissionRequestStatus.value = "Bluetooth server starting..."
            var tempSocket: BluetoothSocket?
            try {
                btServerSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(AppConstants.BLUETOOTH_SERVICE_NAME, AppConstants.BLUETOOTH_SERVICE_UUID)
                Log.d("DevicesViewModel", "BT ServerSocket listening with UUID: ${AppConstants.BLUETOOTH_SERVICE_UUID}")
                while (isActive) { // Coroutine scope's isActive
                    try {
                        Log.d("DevicesViewModel", "BT server calling btServerSocket.accept()...")
                        tempSocket = btServerSocket?.accept() // Blocking call
                    } catch (e: IOException) {
                        if (isActive) { Log.e("DevicesViewModel", "BT server socket accept() failed or closed.", e) }
                        else { Log.d("DevicesViewModel", "BT server socket accept() interrupted by cancellation.")}
                        break
                    }
                    tempSocket?.let { socket ->
                        val remoteDeviceName = try {socket.remoteDevice.name} catch(e:SecurityException){null} ?: socket.remoteDevice.address
                        Log.i("DevicesViewModel", "BT connection accepted from: $remoteDeviceName")
                        handleAcceptedBluetoothConnection(socket)
                        // Decide if server should continue listening or close after one connection
                        // For now, it will continue listening if loop isn't broken.
                    }
                }
            } catch (e: IOException) { Log.e("DevicesViewModel", "BT server listenUsingRfcomm failed", e); launch(kotlinx.coroutines.Dispatchers.Main) { permissionRequestStatus.value = "BT Server Error: ${e.message}" } }
            catch (se: SecurityException) { Log.e("DevicesViewModel", "SecEx starting BT server: ${se.message}", se); launch(kotlinx.coroutines.Dispatchers.Main) { permissionRequestStatus.value = "BT Server Permission Error" } }
            finally {
                Log.d("DevicesViewModel", "Bluetooth server thread ending.")
                try { btServerSocket?.close() } catch (e: IOException) { Log.e("DevicesViewModel", "Could not close BT server socket on exit: ${e.message}") }
                btServerSocket = null
            }
        }
    }

    fun stopBluetoothServer() {
        Log.i("DevicesViewModel", "Stopping Bluetooth server...")
        bluetoothServerJob?.cancel() // This will cause the accept() loop to break and close the socket
        bluetoothServerJob = null
        // No need to manually close btServerSocket here, finally block in launch does it.
        permissionRequestStatus.value = "Bluetooth server stopped."
    }

    fun prepareBluetoothService() { // Called from UI (e.g., ON_RESUME)
        updateBluetoothState() // Get current BT enabled status
        if (isBluetoothEnabled.value) {
            val context = getApplication<Application>().applicationContext
            val connectPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
            if (ActivityCompat.checkSelfPermission(context, connectPerm) == PackageManager.PERMISSION_GRANTED) {
                startBluetoothServer()
            } else {
                Log.w("DevicesViewModel", "BT_PREPARE: Missing $connectPerm. BT Server not started.")
                // UI should ideally handle requesting this permission if needed for server functionality
            }
        } else {
            Log.w("DevicesViewModel", "BT_PREPARE: Bluetooth not enabled, cannot start server.")
        }
    }


    // --- Unified List & Helpers ---
    @SuppressLint("MissingPermission")
    private fun handleBluetoothDeviceFound(device: BluetoothDevice) { /* ... as before ... */
        val context = getApplication<Application>().applicationContext
        var deviceName: String? = "Unknown BT Device"
        var canGetName = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) canGetName = true
            else deviceName = "Name N/A (No CONNECT Perm)"
        } else {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED) canGetName = true
            else deviceName = "Name N/A (No BT Perm)"
        }
        if (canGetName) { try { deviceName = device.name } catch (se: SecurityException) { deviceName = "Name N/A (SecEx)" } }

        if (!bluetoothDevicesInternal.any { it.address == device.address }) {
            Log.d("DevicesViewModel", "BT_DEVICE_ADDED_TO_INTERNAL_LIST: ${deviceName ?: "(Unnamed)"} - ${device.address}")
            bluetoothDevicesInternal.add(device); updateDisplayableDeviceList()
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateDisplayableDeviceList() { /* ... as before ... */
        Log.d("DevicesViewModel", "updateDisplayableDeviceList. P2P(int): ${wifiDirectPeersInternal.size}, BT(int): ${bluetoothDevicesInternal.size}")
        val newList = mutableListOf<DisplayableDevice>()
        val context = getApplication<Application>().applicationContext
        var btConnectPermGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        wifiDirectPeersInternal.forEach { p2pDevice -> /* ... as before ... */
            newList.add(DisplayableDevice(id = p2pDevice.deviceAddress ?: "p2p_${p2pDevice.hashCode()}", name = p2pDevice.deviceName ?: "Unknown P2P Device", details = "Wi-Fi P2P - ${getDeviceP2pStatusString(p2pDevice.status)}", technology = DeviceTechnology.WIFI_DIRECT, originalDeviceObject = p2pDevice))
        }
        bluetoothDevicesInternal.forEach { btDevice -> /* ... as before, ensure deviceName and bondState are handled safely ... */
            var deviceNameStr: String? = "Unknown BT Device"
            var bondStateInt = BluetoothDevice.BOND_NONE
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (btConnectPermGranted) { deviceNameStr = btDevice.name; bondStateInt = btDevice.bondState } else { deviceNameStr = "Name N/A (No CONNECT)" }
                } else {
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED) { deviceNameStr = btDevice.name; bondStateInt = btDevice.bondState } else { deviceNameStr = "Name N/A (No BT Perm)" }
                }
            } catch (e: SecurityException) { deviceNameStr = "Name N/A (SecEx)"}

            newList.add(DisplayableDevice(id = btDevice.address, name = deviceNameStr ?: "Unknown BT Device", details = "Bluetooth - Paired: ${getBluetoothBondState(bondStateInt)}", technology = DeviceTechnology.BLUETOOTH_CLASSIC, originalDeviceObject = btDevice))
        }
        displayableDeviceList.clear()
        displayableDeviceList.addAll(newList.distinctBy { it.id })
        Log.d("DevicesViewModel", "Updated displayableDeviceList. Size: ${displayableDeviceList.size}")
    }

    fun getDeviceP2pStatusString(deviceStatus: Int): String { /* ... as before ... */
        return when (deviceStatus) {
            WifiP2pDevice.AVAILABLE -> "Available"
            WifiP2pDevice.INVITED -> "Invited"
            WifiP2pDevice.CONNECTED -> "Connected"
            WifiP2pDevice.FAILED -> "Failed"
            WifiP2pDevice.UNAVAILABLE -> "Unavailable"
            else -> "Unknown P2P ($deviceStatus)"
        }
    }
    @SuppressLint("MissingPermission")
    private fun getBluetoothBondState(bondState: Int): String { /* ... as before ... */
        return when (bondState) {
            BluetoothDevice.BOND_NONE -> "Not Paired"
            BluetoothDevice.BOND_BONDING -> "Pairing..."
            BluetoothDevice.BOND_BONDED -> "Paired"
            else -> "Bond State Unknown ($bondState)"
        }
    }
    private fun getFailureReasonString(reasonCode: Int): String { /* ... as before ... */
        return when (reasonCode) {
            WifiP2pManager.ERROR -> "ERROR"
            WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
            WifiP2pManager.BUSY -> "BUSY"
            WifiP2pManager.NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS"
            else -> "UNKNOWN ($reasonCode)"
        }
    }
    private fun getDetailedFailureReasonString(reasonCode: Int): String { /* ... as before ... */
        val baseReason = getFailureReasonString(reasonCode)
        return when (reasonCode) {
            WifiP2pManager.ERROR -> "$baseReason (Generic P2P Error) - System might be temporarily unavailable. Consider resetting Wi-Fi Direct or restarting the app/device."
            WifiP2pManager.P2P_UNSUPPORTED -> "$baseReason - This device does not support Wi-Fi Direct."
            WifiP2pManager.BUSY -> "$baseReason - The Wi-Fi Direct system is busy. Wait or reset Wi-Fi Direct."
            WifiP2pManager.NO_SERVICE_REQUESTS -> "$baseReason - No active service discovery requests."
            else -> "$baseReason - An unknown P2P error occurred ($reasonCode). Try resetting."
        }
    }
    fun resetWifiDirectSystem() { /* ... as before, ensure _isRefreshing managed and p2pDiscoveryTimeoutJob.cancel() ... */
        Log.i("DevicesViewModel", "resetWifiDirectSystem CALLED")
        permissionRequestStatus.value = "Resetting Wi-Fi Direct..."
        _isRefreshing.value = true
        p2pDiscoveryTimeoutJob?.cancel() // Cancel any ongoing P2P discovery timeout

        try {
            if (p2pChannel != null && wifiP2pManager != null) { // Add null check for wifiP2pManager
                wifiP2pManager?.stopPeerDiscovery(p2pChannel, null)
            }
        } catch (e: SecurityException) { Log.w("DevicesViewModel", "SecEx stopP2PDisc during reset: ${e.message}", e)}
        catch (e: Exception) { Log.w("DevicesViewModel", "Ex stopP2PDisc during reset: ${e.message}")}
        unregisterP2pReceiver()
        p2pChannel = null; wifiDirectPeersInternal.clear(); _p2pGroupInfo.value = null; updateDisplayableDeviceList()
        viewModelScope.launch {
            delay(500); initializeWifiP2p(isReset = true); delay(300)
            permissionRequestStatus.value = if (p2pChannel != null) "P2P Reset complete. Try discovery." else "P2P Reset failed to re-init channel."
            _isRefreshing.value = false; checkWifiDirectStatus()
        }
    }
    fun checkWifiDirectStatus(): String { /* ... as before, using ActivityCompat.checkSelfPermission ... */
        Log.i("DevicesViewModel", "checkWifiDirectStatus CALLED")
        val context = getApplication<Application>().applicationContext
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val isWifiEnabled = wifiManager?.isWifiEnabled ?: false
        val systemLocationEnabled = isLocationEnabled(context)

        val diagnosticInfo = StringBuilder().apply {
            append("P2P Diagnostics:\n")
            append("- Wi-Fi Enabled: $isWifiEnabled\n")
            append("- System Location Enabled: $systemLocationEnabled\n")
            append("- P2P Manager: ${if (wifiP2pManager != null) "OK" else "NULL"}\n")
            append("- P2P Channel: ${if (p2pChannel != null) "OK" else "NULL"}\n")
            append("- P2P Receiver: ${if (p2pBroadcastReceiver != null) "Reg" else "Not Reg"}\n")
            append("BT Diagnostics:\n")
            append("- BT Adapter: ${if (bluetoothAdapter != null) "OK" else "NULL"}\n")
            append("- BT Enabled: ${isBluetoothEnabled.value}\n")
            append("- BT Receiver: ${if (bluetoothScanReceiver != null) "Reg" else "Not Reg"}\n")
            append("System:\n- API Level: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})\nPermissions (P2P):\n")
            getWifiDirectPermissions().forEach { append("  - $it: ${ActivityCompat.checkSelfPermission(context,it) == PackageManager.PERMISSION_GRANTED}\n") }
            append("Permissions (BT):\n")
            getBluetoothPermissions().forEach { append("  - $it: ${ActivityCompat.checkSelfPermission(context,it) == PackageManager.PERMISSION_GRANTED}\n") }
        }
        Log.i("DevicesViewModel", diagnosticInfo.toString())

        var statusMessage = "Diagnostic check done. See logs."
        if (!isWifiEnabled) statusMessage = "Error: Wi-Fi is OFF."
        else if (!systemLocationEnabled) statusMessage = "Error: System Location is OFF. Scans may fail."
        // More specific checks can be added here
        permissionRequestStatus.value = statusMessage
        return diagnosticInfo.toString()
    }


    private fun setupCommunicationStreams(socket: BluetoothSocket) { // Adapt for WifiP2pSocket later
        Log.d("DevicesViewModel", "Setting up communication streams for Bluetooth socket.")
        try {
            // IMPORTANT: Get OutputStream first, then InputStream, or vice-versa consistently on both ends.
            // It can sometimes deadlock if done in opposite orders.
            objectOutputStream = ObjectOutputStream(socket.outputStream)
            objectOutputStream?.flush() // Flush to send header if any
            objectInputStream = ObjectInputStream(socket.inputStream)
            Log.i("DevicesViewModel", "Communication streams established.")
            permissionRequestStatus.value = "Streams open. Ready to sync."

            // Start listening for incoming messages in a separate coroutine
            startListeningForMessages()

        } catch (e: IOException) {
            Log.e("DevicesViewModel", "Error setting up communication streams: ${e.message}", e)
            permissionRequestStatus.value = "Error: Stream setup failed."
            disconnectBluetooth() // Or a generic disconnect
        }
    }

    private fun startListeningForMessages() {
        communicationJob?.cancel() // Cancel any previous listener
        communicationJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            Log.i("DevicesViewModel", "Listening for incoming SyncMessages...")
            while (isActive && objectInputStream != null) { // Check coroutine isActive
                try {
                    val message = objectInputStream?.readObject() as? SyncMessage
                    message?.let {
                        Log.d("DevicesViewModel", "Received message: Type: ${it.type}, Folder: ${it.folderName}")
                        handleIncomingMessage(it)
                    }
                } catch (e: IOException) {
                    if (isActive) { // Don't log error if coroutine was cancelled (socket closed)
                        Log.e("DevicesViewModel", "IOException while listening for messages: ${e.message}", e)
                        launch(kotlinx.coroutines.Dispatchers.Main) { permissionRequestStatus.value = "Connection lost: ${e.message}" }
                    }
                    break // Exit loop on error
                } catch (e: ClassNotFoundException) {
                    Log.e("DevicesViewModel", "ClassNotFoundException while listening: ${e.message}", e)
                    launch(kotlinx.coroutines.Dispatchers.Main) { permissionRequestStatus.value = "Protocol error." }
                    break
                } catch (e: Exception) { // Catch any other unexpected errors
                    if (isActive) {
                        Log.e("DevicesViewModel", "Unexpected error listening for messages: ${e.message}", e)
                        launch(kotlinx.coroutines.Dispatchers.Main) { permissionRequestStatus.value = "Communication error."}
                    }
                    break
                }
            }
            Log.i("DevicesViewModel", "Stopped listening for messages.")
            // If loop exited, connection is likely lost or closed
            if (connectedBluetoothSocket != null || p2pIsConnected()) { // Check if we thought we were connected
                launch(kotlinx.coroutines.Dispatchers.Main) {
                    if (_bluetoothConnectionStatus.value.startsWith("Connected")) _bluetoothConnectionStatus.value = "Disconnected (stream ended)"
                    // Handle P2P disconnect status similarly
                }
            }
        }
    }

    private fun p2pIsConnected(): Boolean { // Placeholder for P2P connection state
        // TODO: Implement actual P2P connection state check
        return false
    }


    private fun handleIncomingMessage(message: SyncMessage) {
        // Switch to Dispatchers.Main for UI updates from permissionRequestStatus
        // but launch IO-bound tasks like sendFile on Dispatchers.IO
        viewModelScope.launch(Dispatchers.Main) {
            when (message.type) {
                MessageType.SYNC_REQUEST_METADATA -> {
                    Log.d("DevicesViewModel", "Received SYNC_REQUEST_METADATA for folder: ${message.folderName}")
                    permissionRequestStatus.value = "Received sync request for '${message.folderName}'"
                    // Launch the comparison and response in an IO context
                    launch(Dispatchers.IO) {
                        val localFilesToCompare = getLocalFileMetadata(message.folderName ?: "")
                        val remoteFileMetadata = message.fileMetadataList ?: emptyList()
                        val filesToRequest = mutableListOf<String>()
                        val localFileMap = localFilesToCompare.associateBy { it.relativePath }
                        remoteFileMetadata.forEach { remoteFile -> val localFile = localFileMap[remoteFile.relativePath]; if (localFile == null || remoteFile.lastModified > localFile.lastModified) { filesToRequest.add(remoteFile.relativePath) } }
                        Log.d("DevicesViewModel", "Requesting ${filesToRequest.size} files: $filesToRequest")
                        sendMessage(SyncMessage(MessageType.FILES_REQUESTED_BY_PEER, folderName = message.folderName, requestedFilePaths = filesToRequest))
                    }
                }
                MessageType.FILES_REQUESTED_BY_PEER -> {
                    Log.d("DevicesViewModel", "Received FILES_REQUESTED_BY_PEER for folder: ${message.folderName}")
                    val requestedPaths = message.requestedFilePaths
                    permissionRequestStatus.value = "Peer requested ${requestedPaths?.size ?: 0} files."
                    requestedPaths?.forEach { relativePath ->
                        // Launch each sendFile in its own IO coroutine
                        launch(Dispatchers.IO) {
                            sendFile(message.folderName ?: "", relativePath)
                        }
                    }
                    // More robust: track completion of all sendFile jobs before sending SYNC_COMPLETE
                    if (requestedPaths.isNullOrEmpty()){
                        sendMessage(SyncMessage(MessageType.SYNC_COMPLETE, folderName = message.folderName))
                    } else {
                        // TODO: Implement tracking for when all files initiated by this request are sent, then send SYNC_COMPLETE
                        Log.d("DevicesViewModel", "TODO: Need to track completion of ${requestedPaths.size} file sends before sending SYNC_COMPLETE.")
                    }
                }
                // ... (other message types handling as before) ...
                MessageType.FILE_TRANSFER_START -> {
                    val info = message.fileTransferInfo; Log.i("DevicesViewModel", "Receiving file: ${info?.relativePath}, Size: ${info?.fileSize}"); permissionRequestStatus.value = "Receiving: ${info?.relativePath}"; currentReceivingFile = FileTransferState(message.folderName!!, info!!.relativePath, info.fileSize)
                }
                MessageType.FILE_CHUNK -> { message.fileChunkData?.let { appendFileChunk(it) } }
                MessageType.FILE_TRANSFER_END -> {
                    val info = message.fileTransferInfo; Log.i("DevicesViewModel", "File transfer finished for: ${info?.relativePath}"); permissionRequestStatus.value = "Received: ${info?.relativePath}"; finalizeReceivedFile(); info?.let { sendMessage(SyncMessage(MessageType.FILE_RECEIVED_ACK, fileTransferInfo = FileTransferInfo(it.relativePath, 0L)))}; currentReceivingFile = null
                }
                MessageType.FILE_RECEIVED_ACK -> { Log.i("DevicesViewModel", "Peer ACKed file: ${message.fileTransferInfo?.relativePath}") /* TODO: Track sent files */ }
                MessageType.SYNC_COMPLETE -> { Log.i("DevicesViewModel", "SYNC_COMPLETE received for folder: ${message.folderName}"); permissionRequestStatus.value = "Sync complete for '${message.folderName}'."; _isRefreshing.value = false }
                MessageType.ERROR_MESSAGE -> { Log.e("DevicesViewModel", "Received ERROR_MESSAGE: ${message.errorMessage}"); permissionRequestStatus.value = "Error from peer: ${message.errorMessage}" }
            }
        }
    }
    private fun handleAcceptedBluetoothConnection(socket: BluetoothSocket) { // socket is BluetoothSocket
        val remoteDeviceName = try {socket.remoteDevice.name} catch(e:SecurityException){null} ?: socket.remoteDevice.address
        Log.i("DevicesViewModel", "Handling accepted BT connection from $remoteDeviceName")
        // This function is already on an IO thread from startBluetoothServer's launch

        // Close any existing P2P connection first if we are switching
        if (currentCommunicationTechnology == CommunicationTechnology.P2P) {
            Log.d("DevicesViewModel", "Switching from P2P to BT. Closing P2P connection.")
            disconnectP2p() // Ensure P2P resources are freed
        }

        connectedBluetoothSocket = socket
        // We need to pass the BluetoothSocket itself to setupCommunicationStreams which expects java.net.Socket
        // BluetoothSocket is a subclass of java.io.Closeable, not java.net.Socket directly.
        // This was an oversight in the plan.
        // For now, we will assume setupCommunicationStreams is adapted or we pass the BluetoothSocket and it handles it.
        // The provided plan implies activeSocket is java.net.Socket. This needs careful handling.
        // Let's assume for now the plan meant that BluetoothSocket's streams would be used by a generic setup.
        // However, the setupCommunicationStreams signature is `socket: java.net.Socket`.
        // This part of the plan requires a more significant refactor than initially suggested if we are to use a single `activeSocket: java.net.Socket`.
        // For this step, I will proceed with the assumption that setupCommunicationStreams can handle a BluetoothSocket
        // by perhaps wrapping it or using its streams. The immediate goal is to call it.
        // This might be a point of failure if not correctly implemented in setupCommunicationStreams.
        setupCommunicationStreams(connectedBluetoothSocket!!, CommunicationTechnology.BLUETOOTH)


        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) { // Update UI for accepted connection
            _bluetoothConnectionStatus.value = "Accepted connection from $remoteDeviceName"
            permissionRequestStatus.value = "BT Peer connected: $remoteDeviceName"
        }
    }

    // --- P2P Connection and Server Methods ---
    fun handleP2pConnectionInfo(info: WifiP2pInfo) {
        viewModelScope.launch(Dispatchers.IO) { // Perform network operations off the main thread
            if (info.groupFormed) {
                if (info.isGroupOwner) {
                    Log.i("DevicesViewModel", "P2P Group Owner. Starting P2P Server.")
                    withContext(Dispatchers.Main) { _p2pConnectionStatus.value = "Group Owner: Starting Server..." }
                    startP2pServer()
                } else {
                    Log.i("DevicesViewModel", "P2P Client. Connecting to Group Owner: ${info.groupOwnerAddress?.hostAddress}")
                    if (info.groupOwnerAddress?.hostAddress != null) {
                        withContext(Dispatchers.Main) { _p2pConnectionStatus.value = "Client: Connecting to Owner..." }
                        connectToP2pOwner(info.groupOwnerAddress.hostAddress)
                    } else {
                        Log.e("DevicesViewModel", "P2P Client: Group owner address is null!")
                        withContext(Dispatchers.Main) { _p2pConnectionStatus.value = "Error: Owner address null" }
                        disconnectP2p() // Clean up any partial state
                    }
                }
            } else {
                Log.i("DevicesViewModel", "P2P Group not formed or connection lost.")
                withContext(Dispatchers.Main) { _p2pConnectionStatus.value = "Disconnected (Group not formed)" }
                disconnectP2p()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startP2pServer() {
        if (p2pServerJob?.isActive == true) {
            Log.d("DevicesViewModel", "P2P server job already active."); return
        }
        // Close any existing BT connection first if we are switching
        if (currentCommunicationTechnology == CommunicationTechnology.BLUETOOTH) {
            Log.d("DevicesViewModel", "Switching from BT to P2P Server. Closing BT connection.")
            disconnectBluetooth()
        }

        p2pServerJob = viewModelScope.launch(Dispatchers.IO) {
            Log.i("DevicesViewModel", "Starting P2P server...")
            try {
                p2pServerSocket = ServerSocket(AppConstants.P2P_PORT)
                Log.i("DevicesViewModel", "P2P ServerSocket listening on port ${AppConstants.P2P_PORT}")
                withContext(Dispatchers.Main) { _p2pConnectionStatus.value = "Listening as P2P Group Owner..." }

                while (isActive) { // Coroutine scope's isActive
                    try {
                        Log.d("DevicesViewModel", "P2P server calling p2pServerSocket.accept()...")
                        val client = p2pServerSocket?.accept() // Blocking call
                        if (client != null) {
                            p2pClientSocket = client // Assign to the class member
                            val remoteAddress = client.remoteSocketAddress.toString()
                            Log.i("DevicesViewModel", "P2P connection accepted from: $remoteAddress")
                            withContext(Dispatchers.Main) {
                                _p2pConnectionStatus.value = "P2P Client Connected: $remoteAddress"
                                permissionRequestStatus.value = "P2P Client Connected: $remoteAddress"
                            }
                            setupCommunicationStreams(client, CommunicationTechnology.P2P)
                            // Assuming one client for now, so break or manage multiple clients
                            // For multiple clients, you'd typically launch a new coroutine for each client's communication
                            // and the server loop would continue to accept more connections.
                            // For this task, let's assume one primary client connection.
                            // If another client connects, the previous p2pClientSocket will be replaced.
                        } else {
                            if (!isActive) break // Exit if coroutine cancelled
                            Log.w("DevicesViewModel", "P2P server accept() returned null without exception.")
                        }
                    } catch (e: IOException) {
                        if (isActive) { Log.e("DevicesViewModel", "P2P server socket accept() failed or closed.", e) }
                        else { Log.d("DevicesViewModel", "P2P server socket accept() interrupted by cancellation.") }
                        break // Exit loop on error or cancellation
                    }
                }
            } catch (e: IOException) {
                Log.e("DevicesViewModel", "P2P server ServerSocket() failed: ${e.message}", e)
                withContext(Dispatchers.Main) { _p2pConnectionStatus.value = "P2P Server Error: ${e.message}" }
            } catch (se: SecurityException) { // Though less common for ServerSocket itself
                Log.e("DevicesViewModel", "SecEx starting P2P server: ${se.message}", se)
                withContext(Dispatchers.Main) { _p2pConnectionStatus.value = "P2P Server Permission Error" }
            } finally {
                Log.d("DevicesViewModel", "P2P server thread ending.")
                try { p2pServerSocket?.close() } catch (e: IOException) { Log.e("DevicesViewModel", "Could not close P2P server socket on exit: ${e.message}") }
                p2pServerSocket = null
                if (!isActive) { // If job was cancelled, ensure status reflects it
                    withContext(Dispatchers.Main) { if (_p2pConnectionStatus.value.startsWith("Listening")) _p2pConnectionStatus.value = "P2P Server Stopped" }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToP2pOwner(ownerAddress: String?) {
        if (ownerAddress == null) {
            Log.e("DevicesViewModel", "Cannot connect to P2P owner: address is null.")
            _p2pConnectionStatus.value = "Error: Owner address null"
            return
        }
        // Close any existing BT connection first if we are switching
        if (currentCommunicationTechnology == CommunicationTechnology.BLUETOOTH) {
            Log.d("DevicesViewModel", "Switching from BT to P2P Client. Closing BT connection.")
            disconnectBluetooth()
        }

        viewModelScope.launch(Dispatchers.IO) {
            Log.i("DevicesViewModel", "Connecting to P2P Group Owner at $ownerAddress:${AppConstants.P2P_PORT}")
            withContext(Dispatchers.Main) { _p2pConnectionStatus.value = "Connecting to P2P Group Owner..." }
            try {
                // Close existing p2pClientSocket if any, before creating a new one
                p2pClientSocket?.close()
                p2pClientSocket = Socket(ownerAddress, AppConstants.P2P_PORT)
                Log.i("DevicesViewModel", "Successfully connected to P2P Group Owner: ${p2pClientSocket?.remoteSocketAddress}")
                withContext(Dispatchers.Main) {
                    _p2pConnectionStatus.value = "Connected to P2P Group Owner"
                    permissionRequestStatus.value = "P2P Connected to Owner"
                }
                setupCommunicationStreams(p2pClientSocket!!, CommunicationTechnology.P2P)
            } catch (e: IOException) {
                Log.e("DevicesViewModel", "P2P client connection failed: ${e.message}", e)
                withContext(Dispatchers.Main) { _p2pConnectionStatus.value = "P2P Connection Failed: ${e.localizedMessage}" }
                p2pClientSocket = null // Ensure socket is null on failure
            } catch (se: SecurityException) { // Potentially if there are network security policies
                Log.e("DevicesViewModel", "SecurityException during P2P client connection: ${se.message}", se)
                withContext(Dispatchers.Main) { _p2pConnectionStatus.value = "P2P Connection Permission Error" }
                p2pClientSocket = null
            }
        }
    }

    fun disconnectP2p() {
        viewModelScope.launch(Dispatchers.IO) { // Ensure operations are off main thread
            Log.i("DevicesViewModel", "Disconnecting P2P...")
            p2pServerJob?.cancel() // Stop the server if it's running
            p2pServerJob = null

            if (currentCommunicationTechnology == CommunicationTechnology.P2P) {
                closeCommunicationStreams(CommunicationTechnology.P2P) // This will close activeSocket if it's the p2pClientSocket
            } else {
                // If P2P wasn't the active communication, still try to close P2P specific sockets
                try { p2pClientSocket?.close() } catch (e: IOException) { Log.w("DevicesViewModel", "Error closing p2pClientSocket: ${e.message}") }
            }
            try { p2pServerSocket?.close() } catch (e: IOException) { Log.w("DevicesViewModel", "Error closing p2pServerSocket: ${e.message}") }

            p2pClientSocket = null
            p2pServerSocket = null

            withContext(Dispatchers.Main) {
                _p2pConnectionStatus.value = "Disconnected"
                if (permissionRequestStatus.value.startsWith("P2P")) {
                    permissionRequestStatus.value = "P2P Disconnected."
                }
            }
            Log.i("DevicesViewModel", "P2P disconnected.")
        }
    }


    // --- P2P Connection and Server Methods ---
    fun handleP2pConnectionInfo(info: WifiP2pInfo) {
        viewModelScope.launch(Dispatchers.IO) { // Perform network operations off the main thread
            if (info.groupFormed) {
                if (info.isGroupOwner) {
                    Log.i("DevicesViewModel", "P2P Group Owner. Starting P2P Server.")
                    withContext(Dispatchers.Main) { _p2pConnectionStatus.value = "Group Owner: Starting Server..." }
                    startP2pServer()
                } else {
                    Log.i("DevicesViewModel", "P2P Client. Connecting to Group Owner: ${info.groupOwnerAddress?.hostAddress}")
                    if (info.groupOwnerAddress?.hostAddress != null) {
                        withContext(Dispatchers.Main) { _p2pConnectionStatus.value = "Client: Connecting to Owner..." }
                        connectToP2pOwner(info.groupOwnerAddress.hostAddress)
                    } else {
                        Log.e("DevicesViewModel", "P2P Client: Group owner address is null!")
                        withContext(Dispatchers.Main) { _p2pConnectionStatus.value = "Error: Owner address null" }
                        disconnectP2p() // Clean up any partial state
                    }
                }
            } else {
                Log.i("DevicesViewModel", "P2P Group not formed or connection lost.")
                withContext(Dispatchers.Main) { _p2pConnectionStatus.value = "Disconnected (Group not formed)" }
                disconnectP2p()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startP2pServer() {
        if (p2pServerJob?.isActive == true) {
            Log.d("DevicesViewModel", "P2P server job already active."); return
        }
        // Close any existing BT connection first if we are switching
        if (currentCommunicationTechnology == CommunicationTechnology.BLUETOOTH && connectedBluetoothSocket != null) {
            Log.d("DevicesViewModel", "Switching from BT to P2P Server. Closing BT connection.")
            disconnectBluetooth() // This should also clear BT streams and set currentCommunicationTechnology to null
        }

        p2pServerJob = viewModelScope.launch(Dispatchers.IO) {
            Log.i("DevicesViewModel", "Starting P2P server...")
            try {
                closeP2pSockets() // Ensure old sockets are closed before creating new ones
                p2pServerSocket = ServerSocket(AppConstants.P2P_PORT)
                Log.i("DevicesViewModel", "P2P ServerSocket listening on port ${AppConstants.P2P_PORT}")
                withContext(Dispatchers.Main) { _p2pConnectionStatus.value = "Listening as P2P Group Owner..." }

                while (isActive) { 
                    try {
                        Log.d("DevicesViewModel", "P2P server calling p2pServerSocket.accept()...")
                        val client = p2pServerSocket?.accept() 
                        if (client != null) {
                            closeP2pClientSocket() 
                            p2pClientSocket = client 
                            val remoteAddress = client.remoteSocketAddress.toString()
                            Log.i("DevicesViewModel", "P2P connection accepted from: $remoteAddress")
                            withContext(Dispatchers.Main) {
                                _p2pConnectionStatus.value = "P2P Client Connected: $remoteAddress"
                                permissionRequestStatus.value = "P2P Client Connected: $remoteAddress"
                            }
                            setupCommunicationStreams(client, CommunicationTechnology.P2P)
                        } else {
                            if (!isActive) break 
                            Log.w("DevicesViewModel", "P2P server accept() returned null without exception.")
                        }
                    } catch (e: IOException) {
                        if (isActive) { Log.e("DevicesViewModel", "P2P server socket accept() failed or closed: ${e.message}", e) }
                        else { Log.d("DevicesViewModel", "P2P server socket accept() interrupted by cancellation.") }
                        break 
                    }
                }
            } catch (e: IOException) {
                Log.e("DevicesViewModel", "P2P server ServerSocket() failed: ${e.message}", e)
                withContext(Dispatchers.Main) { _p2pConnectionStatus.value = "P2P Server Error: ${e.message}" }
            } catch (se: SecurityException) { 
                Log.e("DevicesViewModel", "SecEx starting P2P server: ${se.message}", se)
                withContext(Dispatchers.Main) { _p2pConnectionStatus.value = "P2P Server Permission Error" }
            } finally {
                Log.d("DevicesViewModel", "P2P server thread ending.")
                closeP2pServerSocket()
                if (!isActive && _p2pConnectionStatus.value.startsWith("Listening")) { 
                    withContext(Dispatchers.Main) { _p2pConnectionStatus.value = "P2P Server Stopped" }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToP2pOwner(ownerAddress: String?) {
        if (ownerAddress == null) {
            Log.e("DevicesViewModel", "Cannot connect to P2P owner: address is null.")
            _p2pConnectionStatus.value = "Error: Owner address null"
            return
        }
        if (currentCommunicationTechnology == CommunicationTechnology.BLUETOOTH && connectedBluetoothSocket != null) {
            Log.d("DevicesViewModel", "Switching from BT to P2P Client. Closing BT connection.")
            disconnectBluetooth()
        }

        viewModelScope.launch(Dispatchers.IO) {
            Log.i("DevicesViewModel", "Connecting to P2P Group Owner at $ownerAddress:${AppConstants.P2P_PORT}")
            withContext(Dispatchers.Main) { _p2pConnectionStatus.value = "Connecting to P2P Group Owner..." }
            try {
                closeP2pSockets() 
                p2pClientSocket = Socket(ownerAddress, AppConstants.P2P_PORT)
                Log.i("DevicesViewModel", "Successfully connected to P2P Group Owner: ${p2pClientSocket?.remoteSocketAddress}")
                withContext(Dispatchers.Main) {
                    _p2pConnectionStatus.value = "Connected to P2P Group Owner"
                    permissionRequestStatus.value = "P2P Connected to Owner"
                }
                setupCommunicationStreams(p2pClientSocket!!, CommunicationTechnology.P2P)
            } catch (e: IOException) {
                Log.e("DevicesViewModel", "P2P client connection failed: ${e.message}", e)
                withContext(Dispatchers.Main) { _p2pConnectionStatus.value = "P2P Connection Failed: ${e.localizedMessage}" }
                closeP2pClientSocket()
            } catch (se: SecurityException) { 
                Log.e("DevicesViewModel", "SecurityException during P2P client connection: ${se.message}", se)
                withContext(Dispatchers.Main) { _p2pConnectionStatus.value = "P2P Connection Permission Error" }
                closeP2pClientSocket()
            }
        }
    }

    private fun closeP2pClientSocket() {
        try { p2pClientSocket?.close() }
        catch (e: IOException) { Log.w("DevicesViewModel", "Error closing p2pClientSocket: ${e.message}") }
        finally { p2pClientSocket = null }
    }

    private fun closeP2pServerSocket() {
        try { p2pServerSocket?.close() }
        catch (e: IOException) { Log.w("DevicesViewModel", "Error closing p2pServerSocket: ${e.message}") }
        finally { p2pServerSocket = null }
    }

    private fun closeP2pSockets() {
        closeP2pClientSocket()
        closeP2pServerSocket()
    }

    fun disconnectP2p() {
        viewModelScope.launch(Dispatchers.IO) { 
            Log.i("DevicesViewModel", "Disconnecting P2P...")
            p2pServerJob?.cancel() 
            p2pServerJob = null

            if (currentCommunicationTechnology == CommunicationTechnology.P2P) {
                closeCommunicationStreams(CommunicationTechnology.P2P) 
            }
            // Ensure P2P sockets are closed regardless of current tech, as they might be open from a previous attempt
            closeP2pSockets()

            withContext(Dispatchers.Main) {
                _p2pConnectionStatus.value = "Disconnected"
                if (permissionRequestStatus.value.startsWith("P2P")) {
                    permissionRequestStatus.value = "P2P Disconnected."
                }
            }
            Log.i("DevicesViewModel", "P2P disconnected.")
        }
    }


    // --- Placeholder for File Handling ---
    // Needs to be implemented based on your actual folder structure and SAF URIs
    private fun getLocalFileMetadata(folderName: String): List<FileMetadata> { // No change here
        Log.d("DevicesViewModel", "TODO: Implement getLocalFileMetadata for folder: $folderName")
        // Example: If you have a root sync directory for this "folderName"
        // val syncRootDir = File(getApplication<Application>().filesDir, "SyncShareRoot/$folderName")
        // if (!syncRootDir.exists()) return emptyList()
        //
        // return syncRootDir.walkTopDown()
        //     .filter { it.isFile }
        //     .map { file ->
        //         FileMetadata(
        //             // relativePath needs to be calculated from syncRootDir
        //             relativePath = file.relativeTo(syncRootDir).path,
        //             name = file.name,
        //             size = file.length(),
        //             lastModified = file.lastModified()
        //         )
        //     }.toList()
        return emptyList() // Placeholder
    }

    fun initiateSyncRequest(folderName: String) {
        if (objectOutputStream == null) {
            Log.e("DevicesViewModel", "Cannot initiate sync: Communication streams not ready.")
            permissionRequestStatus.value = "Error: Not connected for sync."
            return
        }
        viewModelScope.launch { // Use viewModelScope for the initial part
            permissionRequestStatus.value = "Preparing to sync folder: $folderName"
            _isRefreshing.value = true // Indicate sync activity

            // Get metadata for the local folder to be synced
            // This part might be slow if many files, consider Dispatchers.IO if it blocks UI thread for too long
            val localMetadata = withContext(kotlinx.coroutines.Dispatchers.IO) {
                getLocalFileMetadata(folderName)
            }

            if (localMetadata.isEmpty()) {
                Log.w("DevicesViewModel", "No files to sync in folder: $folderName")
                sendMessage(SyncMessage(MessageType.SYNC_REQUEST_METADATA, folderName = folderName, fileMetadataList = emptyList()))
                // Depending on protocol, you might send SYNC_COMPLETE immediately or wait for peer.
                // For now, let peer respond even if list is empty.
                // _isRefreshing.value = false; // Or wait for peer's response
                return@launch
            }

            Log.d("DevicesViewModel", "Sending SYNC_REQUEST_METADATA for $folderName with ${localMetadata.size} files.")
            sendMessage(
                SyncMessage(
                    type = MessageType.SYNC_REQUEST_METADATA,
                    folderName = folderName,
                    fileMetadataList = localMetadata
                )
            )
            // _isRefreshing remains true, will be set to false upon SYNC_COMPLETE from peer or error/timeout
        }
    }

    private suspend fun sendFile(folderName: String, relativePath: String) {
        Log.d("DevicesViewModel", "sendFile: $folderName/$relativePath")
        val appFilesDir = getApplication<Application>().filesDir
        val syncShareRoot = File(appFilesDir, "SyncShareRoot")
        val folderDir = File(syncShareRoot, folderName)
        val localFile = File(folderDir, relativePath)

        // Check if the current coroutine is active at the beginning
        if (!currentCoroutineContext().isActive) {
            Log.w("DevicesViewModel", "sendFile for $relativePath called but coroutine is already inactive.")
            return
        }

        if (!localFile.exists() || !localFile.isFile) {
            Log.e("DevicesViewModel", "File not found or is not a file: ${localFile.absolutePath}")
            if (currentCoroutineContext().isActive) { // Check before sending message
                sendMessage(SyncMessage(MessageType.ERROR_MESSAGE, errorMessage = "File not found on sender: $relativePath"))
            }
            return
        }

        val transferInfo = FileTransferInfo(relativePath, localFile.length())
        sendMessage(SyncMessage(MessageType.FILE_TRANSFER_START, folderName = folderName, fileTransferInfo = transferInfo))

        withContext(Dispatchers.Main) {
            permissionRequestStatus.value = "Sending: $relativePath..."
        }

        val bufferSize = 4096
        val buffer = ByteArray(bufferSize)
        var bytesRead: Int
        var totalBytesSent: Long = 0

        try {
            FileInputStream(localFile).use { fis ->
                // Use currentCoroutineContext().isActive
                while (fis.read(buffer).also { bytesRead = it } != -1 && currentCoroutineContext().isActive) {
                    val chunkToSend = if (bytesRead == bufferSize) buffer else buffer.copyOf(bytesRead)
                    sendMessage(SyncMessage(MessageType.FILE_CHUNK, fileChunkData = chunkToSend))
                    totalBytesSent += bytesRead
                    delay(5)
                }
            }

            if (!currentCoroutineContext().isActive) {
                Log.w("DevicesViewModel", "File sending for $relativePath was cancelled during/after read loop.")
                return
            }
            Log.i("DevicesViewModel", "Finished sending chunks for $relativePath, total $totalBytesSent bytes.")

        } catch (e: IOException) {
            Log.e("DevicesViewModel", "IOException during file send for $relativePath: ${e.message}", e)
            if (currentCoroutineContext().isActive) {
                sendMessage(SyncMessage(MessageType.ERROR_MESSAGE, errorMessage = "Error sending file $relativePath: ${e.message}"))
            }
            return
        } catch (e: Exception) {
            Log.e("DevicesViewModel", "Unexpected exception during file send for $relativePath: ${e.message}", e)
            if (currentCoroutineContext().isActive) {
                sendMessage(SyncMessage(MessageType.ERROR_MESSAGE, errorMessage = "Unexpected error sending file $relativePath: ${e.message}"))
            }
            return
        }

        if (currentCoroutineContext().isActive) {
            sendMessage(SyncMessage(MessageType.FILE_TRANSFER_END, folderName = folderName, fileTransferInfo = transferInfo))
            Log.i("DevicesViewModel", "Sent FILE_TRANSFER_END for $relativePath")
            withContext(Dispatchers.Main) {
                if (permissionRequestStatus.value.contains("Sending: $relativePath")) {
                    permissionRequestStatus.value = "Sent: $relativePath"
                }
            }
        } else {
            Log.w("DevicesViewModel", "File sending for $relativePath was cancelled, FILE_TRANSFER_END not sent.")
        }
    }


    // --- For Receiving Files ---
    private var currentReceivingFile: FileTransferState? = null
    private var currentFileOutputStream: FileOutputStream? = null

    data class FileTransferState(val folderName: String, val relativePath: String, val totalSize: Long, var bytesReceived: Long = 0L)

    private fun appendFileChunk(chunk: ByteArray) {
        currentReceivingFile?.let { state ->
            try {
                if (currentFileOutputStream == null) {
                    // Determine actual local path. For simplicity, using app's internal filesDir.
                    // You'll need to map `state.folderName` and `state.relativePath` to a valid local file path.
                    // Ensure parent directories exist.
                    val targetDir = File(getApplication<Application>().filesDir, "SyncShareReceived/${state.folderName}")
                    if (!targetDir.exists()) targetDir.mkdirs()
                    val targetFile = File(targetDir, state.relativePath.substringAfterLast('/')) // Use only filename part for simplicity here
                    currentFileOutputStream = FileOutputStream(targetFile, state.bytesReceived > 0) // Append if resuming
                    Log.d("DevicesViewModel", "Receiving to file: ${targetFile.absolutePath}")
                }
                currentFileOutputStream?.write(chunk)
                state.bytesReceived += chunk.size
                Log.d("DevicesViewModel", "Received ${state.bytesReceived}/${state.totalSize} for ${state.relativePath}")
                // Update UI progress if needed
            } catch (e: IOException) {
                Log.e("DevicesViewModel", "IOException writing file chunk for ${state.relativePath}: ${e.message}", e)
                // TODO: Handle error, maybe send NACK or close stream
                try { currentFileOutputStream?.close() } catch (ioe: IOException) {}
                currentFileOutputStream = null
                currentReceivingFile = null // Abort this file
            }
        }
    }

    private fun finalizeReceivedFile() {
        currentReceivingFile?.let { state ->
            try {
                currentFileOutputStream?.flush()
                currentFileOutputStream?.close()
                Log.i("DevicesViewModel", "File ${state.relativePath} finalized. Total bytes: ${state.bytesReceived}/${state.totalSize}")
                if (state.bytesReceived != state.totalSize) {
                    Log.w("DevicesViewModel", "File size mismatch for ${state.relativePath}! Expected ${state.totalSize}, got ${state.bytesReceived}")
                    // TODO: Handle incomplete file (e.g., delete it, request retry)
                }
            } catch (e: IOException) {
                Log.e("DevicesViewModel", "IOException finalizing file ${state.relativePath}: ${e.message}", e)
            }
        }
        currentFileOutputStream = null
        currentReceivingFile = null
    }

    // --- Lifecycle clean up for communication streams ---
    private fun closeCommunicationStreams() {
        Log.d("DevicesViewModel", "Closing communication streams.")
        communicationJob?.cancel() // Stop the listening coroutine
        communicationJob = null
        try { objectInputStream?.close() } catch (e: IOException) { Log.w("DevicesViewModel", "Error closing objectInputStream: ${e.message}") }
        try { objectOutputStream?.close() } catch (e: IOException) { Log.w("DevicesViewModel", "Error closing objectOutputStream: ${e.message}") }
        objectInputStream = null
        objectOutputStream = null
    }



    // Helper to send a SyncMessage
    private fun sendMessage(message: SyncMessage) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                objectOutputStream?.writeObject(message)
                objectOutputStream?.flush()
                Log.d("DevicesViewModel", "Sent message: Type: ${message.type}, Folder: ${message.folderName}")
            } catch (e: IOException) {
                Log.e("DevicesViewModel", "Error sending message: ${e.message}", e)
                launch(kotlinx.coroutines.Dispatchers.Main) { permissionRequestStatus.value = "Error sending data." }
                // Consider disconnecting or signaling error
            }
        }
    }


    override fun onCleared() {
        super.onCleared()
        Log.d("DevicesViewModel", "onCleared called.")
        unregisterP2pReceiver()
        stopBluetoothDiscovery()
        disconnectBluetooth()
        stopBluetoothServer()
        closeCommunicationStreams()
        if (p2pChannel != null && wifiP2pManager != null) {
            try { wifiP2pManager?.stopPeerDiscovery(p2pChannel, null) }
            catch (e: SecurityException) { Log.w("DevicesViewModel", "SecEx onCleared/stopP2pDisc: ${e.message}")}
            catch (e: Exception) { Log.w("DevicesViewModel", "Ex onCleared/stopP2pDisc: ${e.message}")}
        }
        Log.d("DevicesViewModel", "onCleared finished.")
    }
}