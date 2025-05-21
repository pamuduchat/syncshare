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
import com.example.syncshare.ui.model.DeviceTechnology
import com.example.syncshare.ui.model.DisplayableDevice
import com.example.syncshare.utils.AppConstants
import com.example.syncshare.utils.getBluetoothPermissions
import com.example.syncshare.utils.getWifiDirectPermissions
import com.example.syncshare.utils.isLocationEnabled
import kotlinx.coroutines.Job // Import this
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive // Import this
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID // For AppConstants if it's defined there

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
    private var bluetoothServerJob: Job? = null
    private var btServerSocket: BluetoothServerSocket? = null // Renamed to avoid conflict with local var

    // Internal storage
    private val wifiDirectPeersInternal = mutableStateListOf<WifiP2pDevice>()
    private val bluetoothDevicesInternal = mutableStateListOf<BluetoothDevice>()

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
    fun onP2pConnectionChanged() { /* ... as before ... */
        viewModelScope.launch {
            Log.d("DevicesViewModel", "onP2pConnectionChanged() - will refresh group info.")
            updateCurrentP2pGroupInfo() // Calls the suspend function
        }
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
                    // TODO: Start data transfer
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

    fun disconnectBluetooth() { /* ... as before ... */
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try { connectedBluetoothSocket?.close(); Log.i("DevicesViewModel", "Bluetooth socket closed.") }
            catch (e: IOException) { Log.e("DevicesViewModel", "Could not close connected Bluetooth socket", e) }
            finally { connectedBluetoothSocket = null; launch(kotlinx.coroutines.Dispatchers.Main) { _bluetoothConnectionStatus.value = "Disconnected" } }
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

    private fun handleAcceptedBluetoothConnection(socket: BluetoothSocket) {
        val remoteDeviceName = try {socket.remoteDevice.name} catch(e:SecurityException){null} ?: socket.remoteDevice.address
        Log.i("DevicesViewModel", "Handling accepted BT connection from $remoteDeviceName")
        connectedBluetoothSocket = socket // Manages one connection at a time for now
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            _bluetoothConnectionStatus.value = "Accepted connection from $remoteDeviceName"
            permissionRequestStatus.value = "BT Peer connected: $remoteDeviceName"
            // TODO: Start data transfer logic on this socket
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

    override fun onCleared() {
        super.onCleared()
        Log.d("DevicesViewModel", "onCleared called.")
        unregisterP2pReceiver()
        stopBluetoothDiscovery()
        disconnectBluetooth()
        stopBluetoothServer()
        if (p2pChannel != null && wifiP2pManager != null) {
            try { wifiP2pManager?.stopPeerDiscovery(p2pChannel, null) }
            catch (e: SecurityException) { Log.w("DevicesViewModel", "SecEx onCleared/stopP2pDisc: ${e.message}")}
            catch (e: Exception) { Log.w("DevicesViewModel", "Ex onCleared/stopP2pDisc: ${e.message}")}
        }
        Log.d("DevicesViewModel", "onCleared finished.")
    }
}