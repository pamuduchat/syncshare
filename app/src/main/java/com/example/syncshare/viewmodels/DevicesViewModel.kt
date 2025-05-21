package com.example.syncshare.viewmodels

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent // Added
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
import com.example.syncshare.utils.getBluetoothPermissions
import com.example.syncshare.utils.getWifiDirectPermissions
import com.example.syncshare.utils.isLocationEnabled
// import com.example.syncshare.utils.hasPermission // Using ActivityCompat.checkSelfPermission directly
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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

    // --- Bluetooth Properties ---
    private val bluetoothManager by lazy {
        application.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }
    val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager?.adapter
    }
    val isBluetoothEnabled = mutableStateOf(false)
    private var bluetoothScanReceiver: BroadcastReceiver? = null

    // Internal storage for raw device objects
    private val wifiDirectPeersInternal = mutableStateListOf<WifiP2pDevice>()
    private val bluetoothDevicesInternal = mutableStateListOf<BluetoothDevice>()

    init {
        Log.d("DevicesViewModel", "DevicesViewModel - INIT BLOCK - START")
        viewModelScope.launch {
            initializeWifiP2p() // This now calls requestCurrentP2pGroupInfo internally
            updateBluetoothState()
        }
        Log.d("DevicesViewModel", "DevicesViewModel - INIT BLOCK - END")
    }

    fun updateBluetoothState() {
        isBluetoothEnabled.value = bluetoothAdapter?.isEnabled == true
        Log.d("DevicesViewModel", "Bluetooth state updated. Enabled: ${isBluetoothEnabled.value}")
    }

    // --- Wi-Fi Direct (P2P) Methods ---
    private fun initializeWifiP2p(isReset: Boolean = false) {
        Log.i("DevicesViewModel", "initializeWifiP2p() CALLED. Is reset: $isReset")
        val context = getApplication<Application>().applicationContext
        wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

        if (wifiP2pManager == null) {
            Log.e("DevicesViewModel", "P2P_INIT_FAIL: Cannot get WifiP2pManager service.")
            permissionRequestStatus.value = "Wi-Fi P2P not available."
            return
        }
        Log.d("DevicesViewModel", "P2P_INIT: WifiP2pManager obtained.")

        try {
            p2pChannel = wifiP2pManager?.initialize(context, Looper.getMainLooper(), object : WifiP2pManager.ChannelListener {
                override fun onChannelDisconnected() {
                    Log.e("DevicesViewModel", "************ P2P CHANNEL DISCONNECTED ************")
                    this@DevicesViewModel.p2pChannel = null
                    permissionRequestStatus.value = "P2P Channel Lost! Reset or restart app."
                    _isRefreshing.value = false; wifiDirectPeersInternal.clear(); _p2pGroupInfo.value = null; updateDisplayableDeviceList()
                }
            })
        } catch (e: SecurityException) {
            Log.e("DevicesViewModel", "P2P_INIT_FAIL: SecurityException channel init: ${e.message}", e)
            permissionRequestStatus.value = "Permission error P2P init."; p2pChannel = null; return
        }

        if (p2pChannel == null) {
            Log.e("DevicesViewModel", "P2P_INIT_FAIL: Channel null post-init.")
            permissionRequestStatus.value = "Failed to init Wi-Fi P2P."; return
        }
        Log.d("DevicesViewModel", "P2P_INIT: Channel Initialized: $p2pChannel")
        // Request group info after channel is confirmed
        viewModelScope.launch { // Launch as coroutine for suspend function
            updateCurrentP2pGroupInfo()
            if (isReset) { // If it was a reset, also re-register receiver after delay
                delay(300)
                registerP2pReceiver()
            }
        }
    }

    // Wrapper function to be called from non-suspending contexts like ON_RESUME in DevicesScreen
    fun refreshP2pGroupInfoOnResume() {
        viewModelScope.launch {
            Log.d("DevicesViewModel", "refreshP2pGroupInfoOnResume launching coroutine to update group info.")
            updateCurrentP2pGroupInfo() // Calls the suspend function
        }
    }

    // Called by WifiDirectBroadcastReceiver when connection state changes
    fun onP2pConnectionChanged() {
        viewModelScope.launch {
            Log.d("DevicesViewModel", "onP2pConnectionChanged() - will refresh group info.")
            updateCurrentP2pGroupInfo() // Calls the suspend function
        }
    }

    @SuppressLint("MissingPermission") // Suppress because we check ACCESS_FINE_LOCATION internally
    suspend fun updateCurrentP2pGroupInfo(): WifiP2pGroup? {
        Log.d("DevicesViewModel", "updateCurrentP2pGroupInfo() called.")
        if (wifiP2pManager == null || p2pChannel == null) {
            Log.w("DevicesViewModel", "updateP2pGroupInfo - P2PManager or Channel null.")
            _p2pGroupInfo.value = null; return null
        }
        val context = getApplication<Application>().applicationContext
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w("DevicesViewModel", "updateP2pGroupInfo - ACCESS_FINE_LOCATION missing.")
            _p2pGroupInfo.value = null; return null
        }
        try {
            // Using suspendCancellableCoroutine to bridge callback to coroutine
            return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                wifiP2pManager?.requestGroupInfo(p2pChannel, object : WifiP2pManager.GroupInfoListener {
                    override fun onGroupInfoAvailable(group: WifiP2pGroup?) {
                        Log.i("DevicesViewModel", "P2P_GROUP_INFO (update): Name: ${group?.networkName}, Owner: ${group?.owner?.deviceName}")
                        _p2pGroupInfo.value = group // Update the StateFlow
                        if (continuation.isActive) { // Check if coroutine is still active
                            continuation.resume(group, null) // Resume coroutine with the result
                        }
                    }
                })
                // Handle cancellation of the coroutine
                continuation.invokeOnCancellation {
                    Log.d("DevicesViewModel", "updateCurrentP2pGroupInfo coroutine cancelled.")
                    // You might not need to do anything specific here unless there's cleanup
                    // for the requestGroupInfo call itself, which is unlikely.
                }
            }
        } catch (e: SecurityException) {
            Log.e("DevicesViewModel", "SecEx updateCurrentP2pGroupInfo: ${e.message}", e)
            _p2pGroupInfo.value = null
            return null
        } catch (e: Exception) { // Catch other potential exceptions
            Log.e("DevicesViewModel", "Ex updateCurrentP2pGroupInfo: ${e.message}", e)
            _p2pGroupInfo.value = null
            return null
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    fun attemptDiscoveryOrRefreshGroup() {
        viewModelScope.launch {
            Log.i("DevicesViewModel", "attemptDiscoveryOrRefreshGroup called.")
            val context = getApplication<Application>().applicationContext
            if (wifiP2pManager == null || p2pChannel == null) {
                Log.e("DevicesViewModel", "attemptDiscOrRefresh - FAIL: P2PManager or Channel null."); permissionRequestStatus.value = "Error: P2P service not ready."; _isRefreshing.value = false; checkWifiDirectStatus(); return@launch
            }
            if (!getWifiDirectPermissions().all { ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
                Log.e("DevicesViewModel", "attemptDiscOrRefresh - FAIL: P2P Perms not granted."); permissionRequestStatus.value = "P2P Permissions missing."; _isRefreshing.value = false; return@launch
            }

            _isRefreshing.value = true // Set refreshing at the start
            val currentGroup = updateCurrentP2pGroupInfo() // Fetch fresh group info

            if (currentGroup != null) {
                Log.i("DevicesViewModel", "P2P_REFRESH_GROUP: Group '${currentGroup.networkName}' active. Populating members.")
                permissionRequestStatus.value = "Displaying current group members..."
                val members = mutableListOf<WifiP2pDevice>()
                if (!currentGroup.isGroupOwner && currentGroup.owner != null) {
                    members.add(currentGroup.owner)
                }
                members.addAll(currentGroup.clientList ?: emptyList())
                onP2pPeersAvailable(members.distinctBy { it.deviceAddress }, fromGroupInfo = true)
            } else {
                Log.i("DevicesViewModel", "P2P_NEW_DISCOVERY: No active group. Attempting discovery.")
                startP2pDiscovery() // This will manage _isRefreshing internally
            }
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    fun startP2pDiscovery(isRetry: Boolean = false) {
        if (!isRetry) {
            p2pDiscoveryRetryCount = 0
            // When starting a fresh P2P discovery, clear other technology results for cleaner UI
            bluetoothDevicesInternal.clear()
            updateDisplayableDeviceList() // Update UI immediately after clearing BT
        }
        Log.i("DevicesViewModel", "startP2pDiscovery() - IsRetry: $isRetry, Count: $p2pDiscoveryRetryCount")
        val context = getApplication<Application>().applicationContext
        if (wifiP2pManager == null || p2pChannel == null) { Log.e("DevicesViewModel", "startP2pDisc - P2PManager/Channel null"); _isRefreshing.value = false; return }
        if (!getWifiDirectPermissions().all { ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) { Log.e("DevicesViewModel", "startP2pDisc - Perms missing"); _isRefreshing.value = false; return }

        _p2pGroupInfo.value?.let { Log.i("DevicesViewModel", "P2P_DISCOVERY: Group status before stop: ${it.networkName}") } ?: Log.i("DevicesViewModel", "P2P_DISCOVERY: No active group before stop.")
        _isRefreshing.value = true // Ensure refreshing is true for P2P discovery
        if (!isRetry) permissionRequestStatus.value = "Stopping previous P2P discovery..."

        try {
            wifiP2pManager?.stopPeerDiscovery(p2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { Log.i("DevicesViewModel", "stopP2pDiscovery.onSuccess()"); if (!isRetry) permissionRequestStatus.value = "Preparing P2P discovery..."; initiateActualP2pDiscoveryAfterStop() }
                override fun onFailure(reasonCode: Int) { val r = getFailureReasonString(reasonCode); Log.w("DevicesViewModel", "stopP2pDiscovery.onFailure - $r ($reasonCode)"); if (!isRetry) permissionRequestStatus.value = "Stop P2P warn ($r)"; initiateActualP2pDiscoveryAfterStop() }
            })
        } catch (e: SecurityException) { Log.e("DevicesViewModel", "SecEx stopP2pDisc: ${e.message}", e); if (!isRetry) permissionRequestStatus.value = "PermErr stopP2PDisc"; initiateActualP2pDiscoveryAfterStop() }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    private fun initiateActualP2pDiscoveryAfterStop() {
        Log.d("DevicesViewModel", "initiateActualP2pDiscoveryAfterStop() - Retry: $p2pDiscoveryRetryCount")
        if (p2pChannel == null) { Log.e("DevicesViewModel", "initActualP2pDisc - Channel null"); _isRefreshing.value = false; p2pDiscoveryRetryCount = 0; checkWifiDirectStatus(); return }

        permissionRequestStatus.value = "Starting P2P discovery${if (p2pDiscoveryRetryCount > 0) " (attempt ${p2pDiscoveryRetryCount + 1})" else ""}..."
        try {
            wifiP2pManager?.discoverPeers(p2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { Log.i("DevicesViewModel", "discoverP2pPeers.onSuccess - INITIATED"); permissionRequestStatus.value = "P2P Discovery started..."; p2pDiscoveryRetryCount = 0 }
                override fun onFailure(reasonCode: Int) {
                    val dr = getDetailedFailureReasonString(reasonCode); Log.e("DevicesViewModel", "discoverP2pPeers.onFailure - FAILED: $dr ($reasonCode)")
                    if (p2pDiscoveryRetryCount < MAX_P2P_DISCOVERY_RETRIES) {
                        p2pDiscoveryRetryCount++; val d = 300L * (1 shl (p2pDiscoveryRetryCount -1)); Log.d("DevicesViewModel", "P2P Retry in ${d}ms ($p2pDiscoveryRetryCount/$MAX_P2P_DISCOVERY_RETRIES)"); permissionRequestStatus.value = "P2P Disc. failed. Retrying ${d/1000.0}s"
                        viewModelScope.launch { delay(d); startP2pDiscovery(isRetry = true) }
                    } else { Log.e("DevicesViewModel", "P2P Disc. GIVING UP after $MAX_P2P_DISCOVERY_RETRIES retries. Reason: $dr"); permissionRequestStatus.value = "P2P Disc. Failed: $dr"; _isRefreshing.value = false; p2pDiscoveryRetryCount = 0; checkWifiDirectStatus() }
                }
            })
        } catch (e: SecurityException) { Log.e("DevicesViewModel", "SecEx discoverP2pPeers: ${e.message}", e); permissionRequestStatus.value = "PermErr P2P Disc."; _isRefreshing.value = false; p2pDiscoveryRetryCount = 0; checkWifiDirectStatus() }
    }

    fun registerP2pReceiver() {
        if (p2pChannel == null) { Log.e("DevicesViewModel", "Cannot reg P2P receiver, channel null."); return }
        val context = getApplication<Application>().applicationContext
        if (p2pBroadcastReceiver == null) {
            p2pBroadcastReceiver = WifiDirectBroadcastReceiver(wifiP2pManager, p2pChannel, this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { context.registerReceiver(p2pBroadcastReceiver, p2pIntentFilter, Context.RECEIVER_NOT_EXPORTED) }
            else { context.registerReceiver(p2pBroadcastReceiver, p2pIntentFilter) }
            Log.d("DevicesViewModel", "P2P BroadcastReceiver registered.")
        } else { Log.d("DevicesViewModel", "P2P BroadcastReceiver already registered.") }
    }

    fun unregisterP2pReceiver() {
        if (p2pBroadcastReceiver != null) {
            try { getApplication<Application>().applicationContext.unregisterReceiver(p2pBroadcastReceiver); Log.d("DevicesViewModel", "P2P BroadcastReceiver unregistered.") }
            catch (e: IllegalArgumentException) { Log.w("DevicesViewModel", "Error unreg P2P receiver: ${e.message}") }
            finally { p2pBroadcastReceiver = null }
        } else { Log.d("DevicesViewModel", "P2P Receiver already null.")}
    }

    fun onP2pPeersAvailable(peers: Collection<WifiP2pDevice>, fromGroupInfo: Boolean = false) {
        viewModelScope.launch {
            Log.d("DevicesViewModel", "onP2pPeersAvailable received ${peers.size} peers. FromGroupInfo: $fromGroupInfo")
            wifiDirectPeersInternal.clear()
            wifiDirectPeersInternal.addAll(peers)
            updateDisplayableDeviceList()

            val currentStatus = permissionRequestStatus.value
            if (peers.isEmpty()) {
                Log.d("DevicesViewModel", "No P2P peers reported by system.")
                if (!fromGroupInfo && (currentStatus.startsWith("P2P Discovery started") || currentStatus.contains("Retrying") || currentStatus.startsWith("Refreshing current group members"))) {
                    permissionRequestStatus.value = "No P2P devices found nearby."
                }
            } else {
                Log.i("DevicesViewModel", "P2P Peers list updated. Count: ${peers.size}")
                if (!fromGroupInfo && (currentStatus.startsWith("P2P Discovery started") || currentStatus.startsWith("No P2P devices found") || currentStatus.contains("Retrying"))) {
                    permissionRequestStatus.value = "${peers.size} P2P device(s) found."
                } else if (fromGroupInfo && currentStatus.startsWith("Refreshing current group members")) {
                    permissionRequestStatus.value = "Group has ${peers.size} other member(s)."
                }
            }
            // Only set _isRefreshing to false if this update is not from an ongoing retry loop for P2P discovery
            // or if it's from group info refresh.
            if (fromGroupInfo || p2pDiscoveryRetryCount == 0 || !permissionRequestStatus.value.contains("Retrying")) {
                _isRefreshing.value = false
            }
        }
    }

    @SuppressLint("MissingPermission") // Internally checks
    fun forceRequestP2pPeers() {
        Log.i("DevicesViewModel", "forceRequestP2pPeers() called.")
        if (wifiP2pManager == null || p2pChannel == null) { Log.e("DevicesViewModel", "forceReqP2pPeers - P2PManager/Channel null."); permissionRequestStatus.value = "P2P System not ready."; _isRefreshing.value = false; return }
        val context = getApplication<Application>().applicationContext
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) { Log.w("DevicesViewModel", "forceReqP2pPeers - Perm missing."); permissionRequestStatus.value = "Location perm needed."; _isRefreshing.value = false; return }
        _isRefreshing.value = true // Indicate activity
        try {
            wifiP2pManager?.requestPeers(p2pChannel) { peers ->
                Log.i("DevicesViewModel", "forceReqP2pPeers - onPeersAvailable. Size: ${peers?.deviceList?.size ?: "null"}")
                onP2pPeersAvailable(peers?.deviceList ?: emptyList(), fromGroupInfo = _p2pGroupInfo.value != null)
            }
            permissionRequestStatus.value = "Requesting current P2P peer list..."
        } catch (e: SecurityException) { Log.e("DevicesViewModel", "SecEx forceReqP2pPeers: ${e.message}", e); permissionRequestStatus.value = "PermErr req P2P peers."; _isRefreshing.value = false; }
    }


    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    fun connectToP2pDevice(device: WifiP2pDevice) {
        Log.i("DevicesViewModel", "connectToP2pDevice: ${device.deviceName}")
        if (wifiP2pManager == null || p2pChannel == null) { Log.e("DevicesViewModel", "Cannot connect P2P: Manager/Channel null."); permissionRequestStatus.value = "P2P Connect Error: Service not ready."; return }
        val context = getApplication<Application>().applicationContext
        if (!getWifiDirectPermissions().all { ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }){ // Check all P2P discovery perms
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
        if (bluetoothAdapter == null) { Log.e("DevicesViewModel", "BT_DISC_FAIL: Adapter null."); permissionRequestStatus.value = "Bluetooth not supported."; _isRefreshing.value = false; return }
        if (!isBluetoothEnabled.value) { Log.w("DevicesViewModel", "BT_DISC_FAIL: Bluetooth disabled."); permissionRequestStatus.value = "Bluetooth is OFF. Please enable it."; _isRefreshing.value = false; return }

        val context = getApplication<Application>().applicationContext

        if (!isLocationEnabled(context)) { // NEW CHECK
            Log.w("DevicesViewModel", "BT_DISC_FAIL: System Location Services are OFF.")
            permissionRequestStatus.value = "Location Services are OFF. Please enable them for Bluetooth discovery."
            _isRefreshing.value = false
            return
        }
        if (!getBluetoothPermissions().all { ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            Log.e("DevicesViewModel", "BT_DISC_FAIL: Missing BT permissions."); permissionRequestStatus.value = "Bluetooth permissions needed."; _isRefreshing.value = false; return
        }
        Log.d("DevicesViewModel", "BT_DISC: All necessary Bluetooth permissions appear granted.")

        if (bluetoothAdapter?.isDiscovering == true) {
            Log.d("DevicesViewModel", "BT_DISC: Already discovering. Cancelling first.");
            try { bluetoothAdapter?.cancelDiscovery() } catch (e: SecurityException) {Log.e("DevicesViewModel", "SecEx BT cancelDiscovery (isDiscovering): ${e.message}", e)}
        }

        bluetoothDevicesInternal.clear()
        wifiDirectPeersInternal.clear() // Clear P2P results when starting BT scan
        updateDisplayableDeviceList()

        _isRefreshing.value = true
        permissionRequestStatus.value = "Scanning for Bluetooth devices..."

        if (bluetoothScanReceiver == null) {
            bluetoothScanReceiver = object : BroadcastReceiver() {
                @SuppressLint("MissingPermission")
                override fun onReceive(context: Context, intent: Intent) {
                    val action: String? = intent.action
                    Log.d("BTScanReceiver", "onReceive: action=$action")
                    when (action) {
                        BluetoothDevice.ACTION_FOUND -> {
                            val device: BluetoothDevice? =
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                                } else {
                                    @Suppress("DEPRECATION")
                                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                                }
                            device?.let {
                                Log.d("BTScanReceiver", "ACTION_FOUND: Raw Name: ${it.name ?: "No Name"}, Address: ${it.address}")
                                handleBluetoothDeviceFound(it)
                            } ?: Log.w("BTScanReceiver", "ACTION_FOUND: Device is null")
                        }
                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                            Log.d("BTScanReceiver", "ACTION_DISCOVERY_FINISHED")
                            handleBluetoothDiscoveryFinished()
                        }
                        BluetoothAdapter.ACTION_STATE_CHANGED -> {
                            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                            val oldState = isBluetoothEnabled.value
                            isBluetoothEnabled.value = state == BluetoothAdapter.STATE_ON
                            Log.d("BTScanReceiver", "ACTION_STATE_CHANGED: Bluetooth state $oldState -> ${isBluetoothEnabled.value}")
                            if (state == BluetoothAdapter.STATE_OFF && _isRefreshing.value && permissionRequestStatus.value.contains("Bluetooth")) {
                                _isRefreshing.value = false; permissionRequestStatus.value = "Bluetooth turned off during scan."
                                bluetoothDevicesInternal.clear(); updateDisplayableDeviceList()
                                stopBluetoothDiscovery() // Also stop and unregister receiver
                            }
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(bluetoothScanReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(bluetoothScanReceiver, filter)
                }
                Log.d("DevicesViewModel", "Bluetooth Scan Receiver registered.")
            } catch (e: Exception) {
                Log.e("DevicesViewModel", "Error registering Bluetooth Scan Receiver: ${e.message}", e)
                _isRefreshing.value = false; permissionRequestStatus.value = "Error setting up BT scan."; return
            }
        }

        try {
            if (bluetoothAdapter?.startDiscovery() == false) {
                Log.e("DevicesViewModel", "BT_DISC_FAIL: startDiscovery() returned false.")
                _isRefreshing.value = false; permissionRequestStatus.value = "Failed to start Bluetooth scan (system denied)."
                stopBluetoothDiscovery()
            } else {
                Log.i("DevicesViewModel", "BT_DISC: Discovery request sent to BluetoothAdapter.")
            }
        } catch (e: SecurityException) {
            Log.e("DevicesViewModel", "SecEx BT startDiscovery: ${e.message}", e)
            _isRefreshing.value = false; permissionRequestStatus.value = "Permission error starting BT scan."
            stopBluetoothDiscovery()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopBluetoothDiscovery() {
        Log.d("DevicesViewModel", "stopBluetoothDiscovery() called.")
        if (bluetoothAdapter?.isDiscovering == true) {
            try { bluetoothAdapter?.cancelDiscovery() } catch (e: SecurityException) { Log.e("DevicesViewModel", "SecEx BT cancelDiscovery: ${e.message}", e)}
            Log.d("DevicesViewModel", "Bluetooth discovery stopped/cancelled.")
        }
        if (bluetoothScanReceiver != null) {
            try { getApplication<Application>().applicationContext.unregisterReceiver(bluetoothScanReceiver); Log.d("DevicesViewModel", "Bluetooth scan receiver unregistered.") }
            catch (e: IllegalArgumentException) { Log.w("DevicesViewModel", "Error unreg BT receiver: ${e.message}") }
            finally { bluetoothScanReceiver = null }
        }
        if (_isRefreshing.value && (permissionRequestStatus.value.contains("Bluetooth") || permissionRequestStatus.value.contains("Scanning for Bluetooth"))) {
            _isRefreshing.value = false
            // if (!permissionRequestStatus.value.contains("found") && !permissionRequestStatus.value.contains("failed")) {
            //     permissionRequestStatus.value = "Bluetooth scan stopped."
            // }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToBluetoothDevice(device: BluetoothDevice) {
        Log.i("DevicesViewModel", "connectToBluetoothDevice: ${device.name ?: device.address}")
        if (bluetoothAdapter?.isDiscovering == true) {
            Log.d("DevicesViewModel", "connectToBT - Cancelling discovery before connecting.")
            try { bluetoothAdapter?.cancelDiscovery() } catch (e: SecurityException) { Log.e("DevicesViewModel", "SecEx BT cancelDisc for connect: ${e.message}", e)}
        }
        val context = getApplication<Application>().applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e("DevicesViewModel", "connectToBT - Missing BLUETOOTH_CONNECT permission.")
            permissionRequestStatus.value = "BT Connect permission needed."
            return
        }
        permissionRequestStatus.value = "Connecting to BT: ${device.name ?: device.address}..."
        Log.w("DevicesViewModel", "TODO: Implement actual Bluetooth connection to ${device.name ?: device.address}")
        viewModelScope.launch { delay(1000); permissionRequestStatus.value = "BT Connection to ${device.name ?: device.address} (simulated/TODO)." }
    }

    // --- Unified List Management ---
    @SuppressLint("MissingPermission")
    private fun handleBluetoothDeviceFound(device: BluetoothDevice) {
        val context = getApplication<Application>().applicationContext
        var deviceName: String? = "Unknown BT Device" // Default
        var canGetNameAndBondState = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                canGetNameAndBondState = true
            } else {
                Log.w("DevicesViewModel", "BT_FOUND: BLUETOOTH_CONNECT perm missing for details on API 31+. Addr: ${device.address}")
                deviceName = "Name N/A (No CONNECT Perm)"
            }
        } else { // Below API 31 (S)
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED) {
                canGetNameAndBondState = true
            } else {
                Log.w("DevicesViewModel", "BT_FOUND: BLUETOOTH perm missing for details on < API 31. Addr: ${device.address}")
                deviceName = "Name N/A (No BT Perm)"
            }
        }

        if (canGetNameAndBondState) {
            try {
                deviceName = device.name // This can still be null if device is unnamed
            } catch (se: SecurityException) {
                Log.e("DevicesViewModel", "SecEx getting BT device name for ${device.address}: ${se.message}", se)
                deviceName = "Name N/A (SecEx)"
            }
        }

        if (deviceName != null && !bluetoothDevicesInternal.any { it.address == device.address }) {
            Log.d("DevicesViewModel", "BT_DEVICE_ADDED_TO_INTERNAL_LIST: ${deviceName} - ${device.address}")
            bluetoothDevicesInternal.add(device)
            updateDisplayableDeviceList()
        } else if (deviceName == null && !bluetoothDevicesInternal.any {it.address == device.address}) { // Add unnamed devices too
            Log.d("DevicesViewModel", "BT_DEVICE_ADDED_TO_INTERNAL_LIST: (Unnamed Device) - ${device.address}")
            bluetoothDevicesInternal.add(device)
            updateDisplayableDeviceList()
        }
    }

    private fun handleBluetoothDiscoveryFinished() {
        Log.i("DevicesViewModel", "BT_DISC_FINISHED.")
        if (_isRefreshing.value && permissionRequestStatus.value.contains("Bluetooth")) {
            _isRefreshing.value = false
        }
        val btDeviceCountInDisplayList = displayableDeviceList.count { it.technology == DeviceTechnology.BLUETOOTH_CLASSIC }
        if (btDeviceCountInDisplayList == 0 && permissionRequestStatus.value.startsWith("Scanning for Bluetooth")) {
            permissionRequestStatus.value = "No new Bluetooth devices found."
        } else if (btDeviceCountInDisplayList > 0 && permissionRequestStatus.value.contains("Bluetooth")) {
            permissionRequestStatus.value = "$btDeviceCountInDisplayList Bluetooth device(s) found."
        } else if (!permissionRequestStatus.value.contains("Bluetooth")) {
            // If a P2P scan was also running and finished, don't override its status
        } else {
            permissionRequestStatus.value = "Bluetooth scan complete."
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateDisplayableDeviceList() {
        Log.d("DevicesViewModel", "updateDisplayableDeviceList. P2P(int): ${wifiDirectPeersInternal.size}, BT(int): ${bluetoothDevicesInternal.size}")
        val newList = mutableListOf<DisplayableDevice>()
        val context = getApplication<Application>().applicationContext
        var btConnectPermGranted = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            btConnectPermGranted = ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        }

        wifiDirectPeersInternal.forEach { p2pDevice ->
            newList.add(
                DisplayableDevice(
                    id = p2pDevice.deviceAddress ?: "p2p_${p2pDevice.hashCode()}",
                    name = p2pDevice.deviceName ?: "Unknown P2P Device",
                    details = "Wi-Fi P2P - ${getDeviceP2pStatusString(p2pDevice.status)}",
                    technology = DeviceTechnology.WIFI_DIRECT,
                    originalDeviceObject = p2pDevice
                )
            )
        }
        bluetoothDevicesInternal.forEach { btDevice ->
            var deviceName: String? = "Unknown BT Device"
            var bondState = BluetoothDevice.BOND_NONE
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (btConnectPermGranted) {
                        deviceName = btDevice.name
                        bondState = btDevice.bondState
                    } else { deviceName = "Name N/A (No CONNECT Perm)" }
                } else {
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED) {
                        deviceName = btDevice.name; bondState = btDevice.bondState
                    } else { deviceName = "Name N/A (No BT Perm)" }
                }
            } catch (e: SecurityException) { deviceName = "Name N/A (SecEx)"; bondState = BluetoothDevice.BOND_NONE }

            newList.add(
                DisplayableDevice(
                    id = btDevice.address,
                    name = deviceName ?: "Unknown BT Device", // Ensure null safety for name
                    details = "Bluetooth - Paired: ${getBluetoothBondState(bondState)}",
                    technology = DeviceTechnology.BLUETOOTH_CLASSIC,
                    originalDeviceObject = btDevice
                )
            )
        }
        displayableDeviceList.clear()
        // Prioritize unique devices by ID, usually MAC address
        displayableDeviceList.addAll(newList.distinctBy { it.id })
        Log.d("DevicesViewModel", "Updated displayableDeviceList. Size: ${displayableDeviceList.size}")
    }

    // --- Helper & Diagnostic Methods ---
    fun getDeviceP2pStatusString(deviceStatus: Int): String { // Made public for WifiDirectBroadcastReceiver
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
    private fun getBluetoothBondState(bondState: Int): String {
        return when (bondState) {
            BluetoothDevice.BOND_NONE -> "Not Paired"
            BluetoothDevice.BOND_BONDING -> "Pairing..."
            BluetoothDevice.BOND_BONDED -> "Paired"
            else -> "Bond State Unknown ($bondState)"
        }
    }
    private fun getFailureReasonString(reasonCode: Int): String {
        return when (reasonCode) {
            WifiP2pManager.ERROR -> "ERROR"
            WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
            WifiP2pManager.BUSY -> "BUSY"
            WifiP2pManager.NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS"
            else -> "UNKNOWN ($reasonCode)"
        }
    }
    private fun getDetailedFailureReasonString(reasonCode: Int): String {
        val baseReason = getFailureReasonString(reasonCode)
        return when (reasonCode) {
            WifiP2pManager.ERROR -> "$baseReason (Generic P2P Error) - System might be temporarily unavailable. Consider resetting Wi-Fi Direct or restarting the app/device."
            WifiP2pManager.P2P_UNSUPPORTED -> "$baseReason - This device does not support Wi-Fi Direct."
            WifiP2pManager.BUSY -> "$baseReason - The Wi-Fi Direct system is busy. Wait or reset Wi-Fi Direct."
            WifiP2pManager.NO_SERVICE_REQUESTS -> "$baseReason - No active service discovery requests."
            else -> "$baseReason - An unknown P2P error occurred ($reasonCode). Try resetting."
        }
    }
    fun resetWifiDirectSystem() {
        Log.i("DevicesViewModel", "resetWifiDirectSystem CALLED")
        permissionRequestStatus.value = "Resetting Wi-Fi Direct..."
        _isRefreshing.value = true
        try {
            if (p2pChannel != null) { wifiP2pManager?.stopPeerDiscovery(p2pChannel, null) }
        } catch (e: Exception) { Log.w("DevicesViewModel", "Ex stopP2PDisc during reset: ${e.message}")}
        unregisterP2pReceiver()
        p2pChannel = null; wifiDirectPeersInternal.clear(); _p2pGroupInfo.value = null; updateDisplayableDeviceList()
        viewModelScope.launch {
            delay(500); initializeWifiP2p(isReset = true); delay(300)
            permissionRequestStatus.value = if (p2pChannel != null) "P2P Reset complete. Try discovery." else "P2P Reset failed to re-init channel."
            _isRefreshing.value = false; checkWifiDirectStatus()
        }
    }
    fun checkWifiDirectStatus(): String {
        Log.i("DevicesViewModel", "checkWifiDirectStatus CALLED")
        val context = getApplication<Application>().applicationContext
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val isWifiEnabled = wifiManager?.isWifiEnabled ?: false
        val diagnosticInfo = StringBuilder().apply {
            append("P2P Diagnostics:\n")
            append("- Wi-Fi Enabled: $isWifiEnabled\n")
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
        // Basic status update to UI
        if (!isWifiEnabled) permissionRequestStatus.value = "Error: Wi-Fi is OFF."
        else if (wifiP2pManager == null || p2pChannel == null) permissionRequestStatus.value = "Error: P2P system not ready."
        else if (bluetoothAdapter == null) permissionRequestStatus.value = "Error: Bluetooth not supported."
        else permissionRequestStatus.value = "Diagnostic check complete. See logs." // Default if major issues not found by this check
        return diagnosticInfo.toString()
    }

    // --- Full Lifecycle Cleanup ---
    override fun onCleared() {
        super.onCleared()
        Log.d("DevicesViewModel", "onCleared called.")
        unregisterP2pReceiver()
        stopBluetoothDiscovery()
        if (p2pChannel != null && wifiP2pManager != null) {
            try { wifiP2pManager?.stopPeerDiscovery(p2pChannel, null) }
            catch (e: SecurityException) { Log.w("DevicesViewModel", "SecEx onCleared/stopP2pDisc: ${e.message}")}
            catch (e: Exception) { Log.w("DevicesViewModel", "Ex onCleared/stopP2pDisc: ${e.message}")}
        }
        Log.d("DevicesViewModel", "onCleared finished.")
    }
}