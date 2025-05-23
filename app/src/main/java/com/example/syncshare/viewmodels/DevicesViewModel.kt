package com.example.syncshare.viewmodels

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket 
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo 
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.syncshare.data.SyncHistoryEntry // Added for history
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
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID 
import android.webkit.MimeTypeMap

enum class CommunicationTechnology { BLUETOOTH, P2P }

class DevicesViewModel(application: Application) : AndroidViewModel(application) {

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing
    val permissionRequestStatus = mutableStateOf("Idle. Tap a scan button.")
    val displayableDeviceList = mutableStateListOf<DisplayableDevice>()

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

    private var connectedBluetoothSocket: BluetoothSocket? = null
    private val _bluetoothConnectionStatus = MutableStateFlow<String>("Disconnected")
    val bluetoothConnectionStatus: StateFlow<String> = _bluetoothConnectionStatus
    private var bluetoothServerJob: Job? = null 
    private var btServerSocket: BluetoothServerSocket? = null

    private var p2pServerSocket: java.net.ServerSocket? = null
    private var p2pClientSocket: java.net.Socket? = null
    private val _p2pConnectionStatus = MutableStateFlow<String>("Disconnected")
    val p2pConnectionStatus: StateFlow<String> = _p2pConnectionStatus
    private var p2pServerJob: kotlinx.coroutines.Job? = null 
    private var p2pClientConnectJob: kotlinx.coroutines.Job? = null

    private val _activeSyncDestinationUris = MutableStateFlow<Map<String, Uri>>(emptyMap())
    val activeSyncDestinationUrisState: StateFlow<Map<String, Uri>> = _activeSyncDestinationUris
    private var defaultIncomingFolderUri: Uri? = null

    // --- Sync History ---
    private val _syncHistory = mutableStateListOf<SyncHistoryEntry>()
    val syncHistory: List<SyncHistoryEntry> = _syncHistory

    // --- Pending Folder Mapping for Incoming Syncs ---
    val pendingFolderMapping = mutableStateOf<String?>(null)
    var pendingSyncMessage: SyncMessage? = null

    private val wifiDirectPeersInternal = mutableStateListOf<WifiP2pDevice>()
    private val bluetoothDevicesInternal = mutableStateListOf<BluetoothDevice>()

    private var activeSocket: java.net.Socket? = null 
    private var objectOutputStream: ObjectOutputStream? = null
    private var objectInputStream: ObjectInputStream? = null
    private var communicationJob: Job? = null
    private var currentCommunicationTechnology: CommunicationTechnology? = null

    private val outputStreamLock = Any()

    init {
        Log.d("DevicesViewModel", "DevicesViewModel - INIT BLOCK - START")
        viewModelScope.launch {
            initializeWifiP2p()
            updateBluetoothState()
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

        if (wifiP2pManager == null) { Log.e("DevicesViewModel", "P2P_INIT_FAIL: WifiP2pManager is null."); permissionRequestStatus.value = "Error: P2P Service not available."; return }
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
        } catch (e: SecurityException) { Log.e("DevicesViewModel", "SecEx P2P_INIT Channel: ${e.message}", e); permissionRequestStatus.value = "PermErr P2P Init."; return }

        if (p2pChannel == null) {  Log.e("DevicesViewModel", "P2P_INIT_FAIL: Channel is null after initialize."); permissionRequestStatus.value = "Error: P2P Channel failed to init.";  return }
        Log.d("DevicesViewModel", "P2P_INIT: Channel Initialized: $p2pChannel")
        refreshP2pGroupInfoOnResume()
        if (isReset) {
            viewModelScope.launch { delay(300); registerP2pReceiver() }
        }
    }

    fun refreshP2pGroupInfoOnResume() {
        viewModelScope.launch {
            Log.d("DevicesViewModel", "refreshP2pGroupInfoOnResume launching coroutine to update group info.")
            updateCurrentP2pGroupInfo()
        }
    }
    fun onP2pConnectionChanged() {
        viewModelScope.launch {
            Log.d("DevicesViewModel", "onP2pConnectionChanged() - will refresh group info and request connection info.")
            updateCurrentP2pGroupInfo()
            if (wifiP2pManager != null && p2pChannel != null) {
                wifiP2pManager?.requestConnectionInfo(p2pChannel, getP2pConnectionInfoListener())
            } else {
                Log.w("DevicesViewModel", "Cannot request P2P connection info onP2pConnectionChanged: manager or channel is null.")
            }
        }
    }

    private val p2pConnectionInfoListener = WifiP2pManager.ConnectionInfoListener { info ->
        Log.d("DevicesViewModel", "P2P Connection Info Available. Group formed: ${info.groupFormed}, Is owner: ${info.isGroupOwner}, Owner IP: ${info.groupOwnerAddress?.hostAddress}")
        handleP2pConnectionInfo(info)
    }

    fun getP2pConnectionInfoListener(): WifiP2pManager.ConnectionInfoListener = p2pConnectionInfoListener

    @SuppressLint("MissingPermission")
    suspend fun updateCurrentP2pGroupInfo(): WifiP2pGroup? {
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
    fun attemptDiscoveryOrRefreshGroup() {
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
    fun startP2pDiscovery(isRetry: Boolean = false) {
        if (!isRetry) {
            p2pDiscoveryRetryCount = 0
            bluetoothDevicesInternal.clear() // Clear other tech results
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
    private fun initiateActualP2pDiscoveryAfterStop() {
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
                    p2pDiscoveryTimeoutJob?.cancel()
                    if (p2pDiscoveryRetryCount < MAX_P2P_DISCOVERY_RETRIES) {
                        p2pDiscoveryRetryCount++; val d = 300L * (1 shl (p2pDiscoveryRetryCount -1)); Log.d("DevicesViewModel", "P2P Retry in ${d}ms ($p2pDiscoveryRetryCount/$MAX_P2P_DISCOVERY_RETRIES)"); permissionRequestStatus.value = "P2P Disc. failed. Retrying ${d/1000.0}s"
                        viewModelScope.launch { delay(d); startP2pDiscovery(isRetry = true) }
                    } else { Log.e("DevicesViewModel", "P2P Disc. GIVING UP after $MAX_P2P_DISCOVERY_RETRIES retries. Reason: $dr"); permissionRequestStatus.value = "P2P Disc. Failed: $dr"; _isRefreshing.value = false; p2pDiscoveryRetryCount = 0; checkWifiDirectStatus() }
                }
            })
        } catch (e: SecurityException) { Log.e("DevicesViewModel", "SecEx discoverP2pPeers: ${e.message}", e); permissionRequestStatus.value = "PermErr P2P Disc."; _isRefreshing.value = false; p2pDiscoveryRetryCount = 0; checkWifiDirectStatus(); p2pDiscoveryTimeoutJob?.cancel() }
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
        p2pDiscoveryTimeoutJob?.cancel()
        if (p2pBroadcastReceiver != null) {
            try { getApplication<Application>().applicationContext.unregisterReceiver(p2pBroadcastReceiver); Log.d("DevicesViewModel", "P2P BroadcastReceiver unregistered.") }
            catch (e: IllegalArgumentException) { Log.w("DevicesViewModel", "Error unreg P2P receiver: ${e.message}") }
            finally { p2pBroadcastReceiver = null }
        } else { Log.d("DevicesViewModel", "P2P Receiver already null.")}
    }

    fun onP2pPeersAvailable(peers: Collection<WifiP2pDevice>, fromGroupInfo: Boolean = false) {
        p2pDiscoveryTimeoutJob?.cancel()
        viewModelScope.launch {
            Log.d("DevicesViewModel", "onP2pPeersAvailable received ${peers.size} peers. FromGroupInfo: $fromGroupInfo")
            if(fromGroupInfo){
                wifiDirectPeersInternal.clear()
                wifiDirectPeersInternal.addAll(peers)
            } else {
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
            _isRefreshing.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun forceRequestP2pPeers() {
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
    fun connectToP2pDevice(device: WifiP2pDevice) {
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
        if (bluetoothAdapter == null) { _isRefreshing.value = false; permissionRequestStatus.value = "Bluetooth not supported."; return }
        if (!isBluetoothEnabled.value) { _isRefreshing.value = false; permissionRequestStatus.value = "Bluetooth is OFF."; return }

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
        wifiDirectPeersInternal.clear() // Clear P2P results
        updateDisplayableDeviceList()

        _isRefreshing.value = true
        permissionRequestStatus.value = "Scanning for Bluetooth devices..."

        if (bluetoothScanReceiver == null) {
            bluetoothScanReceiver = object : BroadcastReceiver() {
                @SuppressLint("MissingPermission")
                override fun onReceive(context: Context, intent: Intent) {
                    val action: String? = intent.action; Log.d("BTScanReceiver", "onReceive: action=$action")
                    when (action) {
                        BluetoothDevice.ACTION_FOUND -> {
                            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) } else { @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) }
                            device?.let { Log.d("BTScanReceiver", "ACTION_FOUND: Raw Name: ${try{it.name}catch(e:SecurityException){"N/A (SecEx)"} ?: "No Name"}, Address: ${it.address}"); handleBluetoothDeviceFound(it) } ?: Log.w("BTScanReceiver", "ACTION_FOUND: Device is null")
                        }
                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> { Log.d("BTScanReceiver", "ACTION_DISCOVERY_FINISHED"); handleBluetoothDiscoveryFinished() }
                        BluetoothAdapter.ACTION_STATE_CHANGED -> {
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
                stopBluetoothDiscovery()
            } else {
                Log.i("DevicesViewModel", "BT_DISC: Discovery request sent to BluetoothAdapter.")
                btDiscoveryTimeoutJob?.cancel()
                btDiscoveryTimeoutJob = viewModelScope.launch {
                    delay(BT_DISCOVERY_TIMEOUT_MS)
                    if (isActive && _isRefreshing.value && permissionRequestStatus.value.startsWith("Scanning for Bluetooth")) {
                        Log.w("DevicesViewModel", "Bluetooth Discovery timed out after ${BT_DISCOVERY_TIMEOUT_MS}ms.")
                        permissionRequestStatus.value = "Bluetooth scan timed out."
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
        if (_isRefreshing.value && (permissionRequestStatus.value.contains("Bluetooth") || permissionRequestStatus.value.contains("Scanning for Bluetooth"))) {
            _isRefreshing.value = false
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
            if (permissionRequestStatus.value.startsWith("Scanning for Bluetooth")) {
                permissionRequestStatus.value = "Bluetooth scan complete."
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToBluetoothDevice(device: BluetoothDevice) {
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
            if (currentCommunicationTechnology == CommunicationTechnology.BLUETOOTH) {
                closeCommunicationStreams()
            } else {
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
                while (isActive) {
                    try {
                        Log.d("DevicesViewModel", "BT server calling btServerSocket.accept()...")
                        tempSocket = btServerSocket?.accept()
                    } catch (e: IOException) {
                        if (isActive) { Log.e("DevicesViewModel", "BT server socket accept() failed or closed.", e) }
                        else { Log.d("DevicesViewModel", "BT server socket accept() interrupted by cancellation.")}
                        break
                    }
                    tempSocket?.let { socket ->
                        val remoteDeviceName = try {socket.remoteDevice.name} catch(e:SecurityException){null} ?: socket.remoteDevice.address
                        Log.i("DevicesViewModel", "BT connection accepted from: $remoteDeviceName")
                        handleAcceptedBluetoothConnection(socket)
                    }
                }
            } catch (e: IOException) { Log.e("DevicesViewModel", "BT server listenUsingRfcomm failed", e); launch(kotlinx.coroutines.Dispatchers.Main) { permissionRequestStatus.value = "BT Server Error: ${e.message}" } }
            catch (se: SecurityException) { Log.e("DevicesViewModel", "SecEx starting BT server: ${se.message}", se); launch(kotlinx.coroutines.Dispatchers.Main) { permissionRequestStatus.value = "BT Server Permission Error" } }
            finally {
                Log.d("DevicesViewModel", "Bluetooth server thread ending.")
                try { btServerSocket?.close() } catch (e: IOException) { Log.e("DevicesViewModel", "Could not close BT server socket on exit: ${e.message}") }
                btServerSocket = null
            }
        } else {
            Log.d("DevicesViewModel", "Receiver was already null.")
        }
    }

    fun stopBluetoothServer() {
        Log.i("DevicesViewModel", "Stopping Bluetooth server...")
        bluetoothServerJob?.cancel()
        bluetoothServerJob = null
        permissionRequestStatus.value = "Bluetooth server stopped."
    }

    fun prepareBluetoothService() {
        updateBluetoothState()
        if (isBluetoothEnabled.value) {
            val context = getApplication<Application>().applicationContext
            val connectPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
            if (ActivityCompat.checkSelfPermission(context, connectPerm) == PackageManager.PERMISSION_GRANTED) {
                startBluetoothServer()
            } else {
                Log.w("DevicesViewModel", "BT_PREPARE: Missing $connectPerm. BT Server not started.")
            }
        } else {
            Log.w("DevicesViewModel", "BT_PREPARE: Bluetooth not enabled, cannot start server.")
        }
    }


    // --- Unified List & Helpers ---
    @SuppressLint("MissingPermission")
    private fun handleBluetoothDeviceFound(device: BluetoothDevice) {
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
    private fun updateDisplayableDeviceList() {
        Log.d("DevicesViewModel", "updateDisplayableDeviceList. P2P(int): ${wifiDirectPeersInternal.size}, BT(int): ${bluetoothDevicesInternal.size}")
        val newList = mutableListOf<DisplayableDevice>()
        val context = getApplication<Application>().applicationContext
        var btConnectPermGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        wifiDirectPeersInternal.forEach { p2pDevice ->
            newList.add(DisplayableDevice(id = p2pDevice.deviceAddress ?: "p2p_${p2pDevice.hashCode()}", name = p2pDevice.deviceName ?: "Unknown P2P Device", details = "Wi-Fi P2P - ${getDeviceP2pStatusString(p2pDevice.status)}", technology = DeviceTechnology.WIFI_DIRECT, originalDeviceObject = p2pDevice))
        }
        bluetoothDevicesInternal.forEach { btDevice ->
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

    fun getDeviceP2pStatusString(deviceStatus: Int): String {
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
        p2pDiscoveryTimeoutJob?.cancel()

        try {
            if (p2pChannel != null && wifiP2pManager != null) {
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
    fun checkWifiDirectStatus(): String {
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
        permissionRequestStatus.value = statusMessage
        return diagnosticInfo.toString()
    }


    private fun setupCommunicationStreams(socket: Any, technology: CommunicationTechnology) {
        Log.d("DevicesViewModel", "Setting up communication streams for ${technology.name} socket.")
        try {
            when (technology) {
                CommunicationTechnology.BLUETOOTH -> {
                    val btSocket = socket as BluetoothSocket
                    objectOutputStream = ObjectOutputStream(btSocket.outputStream)
                    objectInputStream = ObjectInputStream(btSocket.inputStream)
                }
                CommunicationTechnology.P2P -> {
                    val p2pSocket = socket as java.net.Socket
                    objectOutputStream = ObjectOutputStream(p2pSocket.getOutputStream())
                    objectInputStream = ObjectInputStream(p2pSocket.inputStream)
                }
            }
            objectOutputStream?.flush()
            Log.i("DevicesViewModel", "Communication streams established for ${technology.name}.")
            permissionRequestStatus.value = "Streams open. Ready to sync."
            startListeningForMessages()

        } catch (e: IOException) {
            Log.e("DevicesViewModel", "Error setting up communication streams for ${technology.name}: ", e)
            permissionRequestStatus.value = "Error: Stream setup failed."
            if (technology == CommunicationTechnology.BLUETOOTH) {
                disconnectBluetooth()
            } else {
                disconnectP2p()
            }
        }
    }

    private fun startListeningForMessages() {
        communicationJob?.cancel()
        communicationJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            Log.i("DevicesViewModel", "Listening for incoming SyncMessages...")
            while (isActive && objectInputStream != null) {
                try {
                    val message = objectInputStream?.readObject() as? SyncMessage
                    message?.let {
                        Log.d("DevicesViewModel", "Received message: Type: ${it.type}, Folder: ${it.folderName}")
                        handleIncomingMessage(it)
                    }
                } catch (e: IOException) {
                    if (isActive) {
                        Log.e("DevicesViewModel", "IOException while listening for messages: ${e.message}", e)
                        launch(kotlinx.coroutines.Dispatchers.Main) { permissionRequestStatus.value = "Connection lost: ${e.message}" }
                    }
                    break
                } catch (e: ClassNotFoundException) {
                    Log.e("DevicesViewModel", "ClassNotFoundException while listening: ${e.message}", e)
                    launch(kotlinx.coroutines.Dispatchers.Main) { permissionRequestStatus.value = "Protocol error." }
                    break
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e("DevicesViewModel", "Unexpected error listening for messages: ${e.message}", e)
                        launch(kotlinx.coroutines.Dispatchers.Main) { permissionRequestStatus.value = "Communication error."}
                    }
                    break
                }
            }
            Log.i("DevicesViewModel", "Stopped listening for messages.")
            if (connectedBluetoothSocket != null || p2pIsConnected()) {
                launch(kotlinx.coroutines.Dispatchers.Main) {
                    if (_bluetoothConnectionStatus.value.startsWith("Connected")) _bluetoothConnectionStatus.value = "Disconnected (stream ended)"
                }
            }
        }
    }

    private fun p2pIsConnected(): Boolean {
        return _p2pConnectionStatus.value.startsWith("Connected") || _p2pConnectionStatus.value.startsWith("P2P Client Connected")
    }


    private fun handleIncomingMessage(message: SyncMessage) {
        viewModelScope.launch(Dispatchers.Main) {
            when (message.type) {
                MessageType.SYNC_REQUEST_METADATA -> {
                    Log.d("DevicesViewModel", "Received SYNC_REQUEST_METADATA for folder: ${message.folderName}")
                    permissionRequestStatus.value = "Received sync request for '${message.folderName}'"
                    _syncHistory.add(0, SyncHistoryEntry(folderName = message.folderName ?: "Unknown", status = "Initiated (Receiver)", details = "Received sync request for folder."))
                    val folderName = message.folderName
                    val localFolderUri = _activeSyncDestinationUris.value[folderName]

                    if (localFolderUri == null) {
                        pendingFolderMapping.value = folderName
                        pendingSyncMessage = message
                        return@launch
                    }

                    launch(Dispatchers.IO) {
                        val localFilesToCompare = getLocalFileMetadata(localFolderUri)
                        val remoteFileMetadata = message.fileMetadataList ?: emptyList()
                        val filesToRequest = mutableListOf<String>()
                        val localFileMap = localFilesToCompare.associateBy { it.relativePath }

                        remoteFileMetadata.forEach { remoteFile ->
                            val localFile = localFileMap[remoteFile.relativePath]
                            if (localFile == null || remoteFile.lastModified > localFile.lastModified) {
                                filesToRequest.add(remoteFile.relativePath)
                            }
                        }
                        Log.d("DevicesViewModel", "Requesting ${filesToRequest.size} files for folder '${message.folderName}': $filesToRequest")
                        sendMessage(SyncMessage(MessageType.FILES_REQUESTED_BY_PEER, folderName = message.folderName, requestedFilePaths = filesToRequest))
                    }
                }
                MessageType.FILES_REQUESTED_BY_PEER -> {
                    Log.d("DevicesViewModel", "Received FILES_REQUESTED_BY_PEER for folder: ${message.folderName}")
                    val requestedPaths = message.requestedFilePaths
                    val baseFolderName = message.folderName

                    val senderBaseUri : Uri? = _activeSyncDestinationUris.value[baseFolderName] 

                    if (senderBaseUri == null) {
                        Log.e("DevicesViewModel", "Sender URI for folder '${baseFolderName}' not found. Cannot send files. This requires sender-side URI management or a different way to identify source URIs.")
                        sendMessage(SyncMessage(MessageType.ERROR_MESSAGE, folderName = baseFolderName, errorMessage = "Source folder '${baseFolderName}' not found/mappable on sender."))
                        _syncHistory.add(0, SyncHistoryEntry(folderName = baseFolderName ?: "Unknown", status = "Error", details = "Source folder not found/mappable on sender."))
                        return@launch
                    }

                    permissionRequestStatus.value = "Peer requested ${requestedPaths?.size ?: 0} files from '${baseFolderName}'."
                    requestedPaths?.forEach { relativePath ->
                        launch(Dispatchers.IO) {
                            sendFile(senderBaseUri, relativePath, baseFolderName ?: "")
                        }
                    }
                    if (requestedPaths.isNullOrEmpty()){
                        sendMessage(SyncMessage(MessageType.SYNC_COMPLETE, folderName = message.folderName))
                    } else {
                        Log.d("DevicesViewModel", "TODO: Need to track completion of ${requestedPaths.size} file sends before sending SYNC_COMPLETE.")
                    }
                }
                MessageType.FILE_TRANSFER_START -> {
                    val info = message.fileTransferInfo
                    val folderName = message.folderName
                    if (info == null || folderName == null) {
                        Log.e("DevicesViewModel", "FILE_TRANSFER_START missing info or folderName.")
                        sendMessage(SyncMessage(MessageType.ERROR_MESSAGE, errorMessage = "Incomplete file transfer request from sender."))
                        _syncHistory.add(0, SyncHistoryEntry(folderName = folderName ?: "Unknown", status = "Error", details = "Incomplete file transfer request from sender."))
                        return@launch
                    }

                    var destinationUri = _activeSyncDestinationUris.value[folderName]

                    if (destinationUri == null) {
                        Log.w("DevicesViewModel", "No specific destination URI for '$folderName', checking default.")
                        destinationUri = defaultIncomingFolderUri
                    }

                    if (destinationUri == null) {
                        Log.e("DevicesViewModel", "No specific or default destination URI set for folder: $folderName. Cannot receive file: ${info.relativePath}")
                        sendMessage(SyncMessage(MessageType.ERROR_MESSAGE, folderName = folderName, errorMessage = "Destination folder '$folderName' not configured on receiver for file '${info.relativePath}'."))
                        _syncHistory.add(0, SyncHistoryEntry(folderName = folderName, status = "Error", details = "Destination folder not configured for file '${info.relativePath}'."))
                        return@launch
                    }

                    Log.i("DevicesViewModel", "Receiving file: ${info.relativePath}, Size: ${info.fileSize} into folder '$folderName' (URI: $destinationUri)")
                    permissionRequestStatus.value = "Receiving: ${info.relativePath} for $folderName"
                    currentReceivingFile = FileTransferState(
                        folderName = folderName,
                        relativePath = info.relativePath,
                        totalSize = info.fileSize,
                        destinationBaseUri = destinationUri
                    )
                    _syncHistory.add(0, SyncHistoryEntry(folderName = folderName, status = "File Transfer", details = "Receiving file: ${info.relativePath} (${info.fileSize} bytes)"))
                }
                MessageType.FILE_CHUNK -> { message.fileChunkData?.let { appendFileChunk(it) } }
                MessageType.FILE_TRANSFER_END -> {
                    val info = message.fileTransferInfo; Log.i("DevicesViewModel", "File transfer finished for: ${info?.relativePath}"); permissionRequestStatus.value = "Received: ${info?.relativePath}"; finalizeReceivedFile(); info?.let { sendMessage(SyncMessage(MessageType.FILE_RECEIVED_ACK, fileTransferInfo = FileTransferInfo(it.relativePath, 0L)))}; currentReceivingFile = null
                }
                MessageType.FILE_RECEIVED_ACK -> { Log.i("DevicesViewModel", "Peer ACKed file: ${message.fileTransferInfo?.relativePath}") }
                MessageType.SYNC_COMPLETE -> {
                    Log.i("DevicesViewModel", "SYNC_COMPLETE received for folder: ${message.folderName}"); permissionRequestStatus.value = "Sync complete for '${message.folderName}'."; _isRefreshing.value = false
                    _syncHistory.add(0, SyncHistoryEntry(folderName = message.folderName ?: "Unknown", status = "Completed", details = "Sync successfully completed for folder."))
                }
                MessageType.ERROR_MESSAGE -> {
                    Log.e("DevicesViewModel", "Received ERROR_MESSAGE: ${message.errorMessage}"); permissionRequestStatus.value = "Error from peer: ${message.errorMessage}"
                    _syncHistory.add(0, SyncHistoryEntry(folderName = message.folderName ?: "Associated with error", status = "Error", details = "Error during sync: ${message.errorMessage}"))
                }
            }
        }
    }
    @SuppressLint("MissingPermission")
    private fun handleAcceptedBluetoothConnection(socket: BluetoothSocket) {
        val remoteDeviceName = try {socket.remoteDevice.name} catch(e:SecurityException){null} ?: socket.remoteDevice.address
        Log.i("DevicesViewModel", "Handling accepted BT connection from $remoteDeviceName")

        if (currentCommunicationTechnology == CommunicationTechnology.P2P) {
            Log.d("DevicesViewModel", "Switching from P2P to BT. Closing P2P connection.")
            disconnectP2p()
        }
        connectedBluetoothSocket = socket
        setupCommunicationStreams(socket, CommunicationTechnology.BLUETOOTH)
        _syncHistory.add(0, SyncHistoryEntry(folderName = "N/A", status = "Connected", details = "Bluetooth connection accepted.", peerDeviceName = remoteDeviceName))


        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            _bluetoothConnectionStatus.value = "Accepted connection from $remoteDeviceName"
            permissionRequestStatus.value = "BT Peer connected: $remoteDeviceName"
        }
    }

    fun handleP2pConnectionInfo(info: WifiP2pInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            if (info.groupFormed) {
                val peerDeviceName = if (info.isGroupOwner) "P2P Group Client" else info.groupOwnerAddress?.hostAddress ?: "P2P Group Owner"
                if (info.isGroupOwner) {
                    Log.i("DevicesViewModel", "P2P Group Owner. Starting P2P Server.")
                    withContext(Dispatchers.Main) { _p2pConnectionStatus.value = "Group Owner: Starting Server..." }
                    startP2pServer()
                    _syncHistory.add(0, SyncHistoryEntry(folderName = "N/A", status = "Connected (Owner)", details = "P2P Group formed, acting as owner.", peerDeviceName = "Group Client (TBD)"))
                } else {
                    Log.i("DevicesViewModel", "P2P Client. Connecting to Group Owner: ${info.groupOwnerAddress?.hostAddress}")
                    if (info.groupOwnerAddress?.hostAddress != null) {
                        withContext(Dispatchers.Main) { _p2pConnectionStatus.value = "Client: Connecting to Owner..." }
                        connectToP2pOwner(info.groupOwnerAddress.hostAddress)
                        _syncHistory.add(0, SyncHistoryEntry(folderName = "N/A", status = "Connected (Client)", details = "P2P Group formed, acting as client.", peerDeviceName = info.groupOwnerAddress.hostAddress))
                    } else {
                        Log.e("DevicesViewModel", "P2P Client: Group owner address is null!")
                        withContext(Dispatchers.Main) { _p2pConnectionStatus.value = "Error: Owner address null" }
                        _syncHistory.add(0, SyncHistoryEntry(folderName = "N/A", status = "Error", details = "P2P Connection failed: Group owner address null."))
                        disconnectP2p()
                    }
                }
            } else {
                Log.i("DevicesViewModel", "P2P Group not formed or connection lost.")
                withContext(Dispatchers.Main) { _p2pConnectionStatus.value = "Disconnected (Group not formed)" }
                // Add history if a sync was active
                if (_isRefreshing.value) {
                     _syncHistory.add(0, SyncHistoryEntry(folderName = "Active Sync", status = "Error", details = "P2P Group not formed or connection lost during active sync."))
                }
                disconnectP2p()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startP2pServer() {
        if (p2pServerJob?.isActive == true) {
            Log.d("DevicesViewModel", "P2P server job already active."); return
        }
        if (currentCommunicationTechnology == CommunicationTechnology.BLUETOOTH && connectedBluetoothSocket != null) {
            Log.d("DevicesViewModel", "Switching from BT to P2P Server. Closing BT connection.")
            disconnectBluetooth()
        }

        p2pServerJob = viewModelScope.launch(Dispatchers.IO) {
            Log.i("DevicesViewModel", "Starting P2P server...")
            try {
                closeP2pSockets()
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
                            // History entry for accepted P2P connection already handled in handleP2pConnectionInfo (group owner part)
                            setupCommunicationStreams(p2pClientSocket!!, CommunicationTechnology.P2P)
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
                 _syncHistory.add(0, SyncHistoryEntry(folderName = "N/A", status = "Error", details = "P2P Server failed to start: ${e.message}"))
            } catch (se: SecurityException) {
                Log.e("DevicesViewModel", "SecEx starting P2P server: ${se.message}", se)
                withContext(Dispatchers.Main) { _p2pConnectionStatus.value = "P2P Server Permission Error" }
                 _syncHistory.add(0, SyncHistoryEntry(folderName = "N/A", status = "Error", details = "P2P Server permission error: ${se.message}"))
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
             _syncHistory.add(0, SyncHistoryEntry(folderName = "N/A", status = "Error", details = "Cannot connect to P2P owner: address is null."))
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
                // History entry for successful P2P client connection handled in handleP2pConnectionInfo
                setupCommunicationStreams(p2pClientSocket!!, CommunicationTechnology.P2P)
            } catch (e: IOException) {
                Log.e("DevicesViewModel", "P2P client connection failed: ${e.message}", e)
                withContext(Dispatchers.Main) { _p2pConnectionStatus.value = "P2P Connection Failed: ${e.localizedMessage}" }
                _syncHistory.add(0, SyncHistoryEntry(folderName = "N/A", status = "Error", details = "P2P client connection failed: ${e.message}", peerDeviceName = ownerAddress))
                closeP2pClientSocket()
            } catch (se: SecurityException) {
                Log.e("DevicesViewModel", "SecurityException during P2P client connection: ${se.message}", se)
                withContext(Dispatchers.Main) { _p2pConnectionStatus.value = "P2P Connection Permission Error" }
                 _syncHistory.add(0, SyncHistoryEntry(folderName = "N/A", status = "Error", details = "P2P client connection permission error: ${se.message}", peerDeviceName = ownerAddress))
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
            if (_isRefreshing.value && (p2pClientSocket != null || p2pServerSocket != null) ) { // If a sync was active
                _syncHistory.add(0, SyncHistoryEntry(folderName = "Active Sync", status = "Error", details = "P2P Disconnected during active sync."))
            }
            p2pServerJob?.cancel()
            p2pServerJob = null

            if (currentCommunicationTechnology == CommunicationTechnology.P2P) {
                closeCommunicationStreams()
            }
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

    // --- SAF Integrated File Handling ---
    private fun getLocalFileMetadata(folderUri: Uri): List<FileMetadata> {
        Log.d("DevicesViewModel", "getLocalFileMetadata for folderUri: $folderUri")
        val context = getApplication<Application>().applicationContext
        val documentFolder = DocumentFile.fromTreeUri(context, folderUri)
        if (documentFolder == null || !documentFolder.isDirectory) {
            Log.e("DevicesViewModel", "Failed to get DocumentFile for folderUri or not a directory: $folderUri")
            return emptyList()
        }

        val metadataList = mutableListOf<FileMetadata>()
        fun traverse(currentDocumentFile: DocumentFile, currentRelativePath: String) {
            if (!currentDocumentFile.isDirectory) return

            val children = currentDocumentFile.listFiles()
            Log.d("DevicesViewModel", "Traversing: ${currentDocumentFile.uri}, found ${children.size} children")
            for (file in children) {
                Log.d("DevicesViewModel", "Child: ${file.name}, isFile=${file.isFile}, isDirectory=${file.isDirectory}, uri=${file.uri}")
                if (file.isFile) {
                    val relativePath = if (currentRelativePath.isEmpty()) file.name ?: "" else "$currentRelativePath/${file.name}"
                    if (relativePath.isNotEmpty()) {
                        metadataList.add(
                            FileMetadata(
                                relativePath = relativePath,
                                name = file.name ?: "Unknown Name",
                                size = file.length(),
                                lastModified = file.lastModified()
                            )
                        )
                    }
                } else if (file.isDirectory) {
                    val nextRelativePath = if (currentRelativePath.isEmpty()) file.name ?: "" else "$currentRelativePath/${file.name}"
                    if (nextRelativePath.isNotEmpty()) {
                        traverse(file, nextRelativePath)
                    }
                }
            }
        }
        traverse(documentFolder, "")
        Log.d("DevicesViewModel", "Found ${metadataList.size} files in $folderUri")
        return metadataList
    }

    fun initiateSyncRequest(folderUri: Uri) {
        if (objectOutputStream == null) {
            Log.e("DevicesViewModel", "Cannot initiate sync: Communication streams not ready.")
            permissionRequestStatus.value = "Error: Not connected for sync."
            val folderNameForHistory = DocumentFile.fromTreeUri(getApplication(), folderUri)?.name ?: folderUri.toString()
            _syncHistory.add(0, SyncHistoryEntry(folderName = folderNameForHistory, status = "Error", details = "Cannot initiate sync: Not connected."))
            return
        }
        val context = getApplication<Application>().applicationContext
        val folderNameForSyncMessage = DocumentFile.fromTreeUri(context, folderUri)?.name ?: folderUri.toString()

        // --- Store the mapping from folder name to URI ---
        val currentMap = _activeSyncDestinationUris.value.toMutableMap()
        currentMap[folderNameForSyncMessage] = folderUri
        _activeSyncDestinationUris.value = currentMap.toMap()
        // -------------------------------------------------

        viewModelScope.launch {
            permissionRequestStatus.value = "Preparing to sync folder: $folderNameForSyncMessage"
            _isRefreshing.value = true

            val folderNameForHistory = DocumentFile.fromTreeUri(getApplication(), folderUri)?.name ?: folderUri.toString()
            _syncHistory.add(0, SyncHistoryEntry(folderName = folderNameForHistory, status = "Initiated (Sender)", details = "Sync request initiated for folder."))

            val localMetadata = withContext(Dispatchers.IO) {
                getLocalFileMetadata(folderUri)
            }

            Log.d("DevicesViewModel", "Sending SYNC_REQUEST_METADATA for $folderNameForSyncMessage with ${localMetadata.size} files.")
            sendMessage(
                SyncMessage(
                    type = MessageType.SYNC_REQUEST_METADATA,
                    folderName = folderNameForSyncMessage,
                    fileMetadataList = localMetadata
                )
            )
        }
    }

    private suspend fun sendFile(baseFolderUri: Uri, relativePath: String, syncFolderName: String) {
        Log.d("DevicesViewModel", "sendFile: relativePath '$relativePath' from base URI '$baseFolderUri' for sync folder '$syncFolderName'")
        val context = getApplication<Application>().applicationContext

        if (!currentCoroutineContext().isActive) {
            Log.w("DevicesViewModel", "sendFile for $relativePath called but coroutine is already inactive.")
            return
        }

        var targetDocumentFile: DocumentFile? = DocumentFile.fromTreeUri(context, baseFolderUri)
        if (targetDocumentFile == null || !targetDocumentFile.isDirectory) {
            Log.e("DevicesViewModel", "Base folder URI is invalid or not a directory: $baseFolderUri")
            sendMessage(SyncMessage(MessageType.ERROR_MESSAGE, folderName = syncFolderName, errorMessage = "Source folder not found on sender: $syncFolderName"))
            _syncHistory.add(0, SyncHistoryEntry(folderName = syncFolderName, status = "Error", details = "Source folder not found on sender for file $relativePath."))
            return
        }

        val pathSegments = relativePath.split('/')
        for (segment in pathSegments) {
            if (segment.isEmpty()) continue
            targetDocumentFile = targetDocumentFile?.findFile(segment)
            if (targetDocumentFile == null) {
                Log.e("DevicesViewModel", "File segment not found: '$segment' in '$relativePath' under URI '$baseFolderUri'")
                sendMessage(SyncMessage(MessageType.ERROR_MESSAGE, folderName = syncFolderName, errorMessage = "File not found on sender: $relativePath"))
                 _syncHistory.add(0, SyncHistoryEntry(folderName = syncFolderName, status = "Error", details = "File not found on sender: $relativePath (segment: $segment)."))
                return
            }
        }

        if (targetDocumentFile == null || !targetDocumentFile.isFile) {
            Log.e("DevicesViewModel", "Target DocumentFile is null or not a file for relativePath: $relativePath")
            sendMessage(SyncMessage(MessageType.ERROR_MESSAGE, folderName = syncFolderName, errorMessage = "File not found or is not a file on sender: $relativePath"))
            _syncHistory.add(0, SyncHistoryEntry(folderName = syncFolderName, status = "Error", details = "File not found or is not a file on sender: $relativePath."))
            return
        }

        val transferInfo = FileTransferInfo(relativePath, targetDocumentFile.length())
        sendMessage(SyncMessage(MessageType.FILE_TRANSFER_START, folderName = syncFolderName, fileTransferInfo = transferInfo))

        withContext(Dispatchers.Main) {
            permissionRequestStatus.value = "Sending: $relativePath from $syncFolderName..."
        }

        val bufferSize = 4096
        val buffer = ByteArray(bufferSize)
        var bytesRead: Int
        var totalBytesSent: Long = 0
        var chunkCount = 0

        Log.d("DevicesViewModel", "Sending file: $relativePath, size: ${targetDocumentFile.length()}")
        context.contentResolver.openInputStream(targetDocumentFile.uri).use { fis ->
            if (fis == null) {
                Log.e("DevicesViewModel", "Failed to open InputStream for ${targetDocumentFile.uri}")
                sendMessage(SyncMessage(MessageType.ERROR_MESSAGE, folderName = syncFolderName, errorMessage = "Failed to read file on sender: $relativePath"))
                _syncHistory.add(0, SyncHistoryEntry(folderName = syncFolderName, status = "Error", details = "Failed to read file on sender: $relativePath."))
                return
            }
            while (fis.read(buffer).also { bytesRead = it } != -1 && currentCoroutineContext().isActive) {
                if (bytesRead > 0) {
                    try {
                        val chunkToSend = buffer.copyOf(bytesRead)
                        if (chunkToSend.size != bytesRead) {
                            Log.w("DevicesViewModel", "Chunk size mismatch: chunkToSend.size=${chunkToSend.size}, bytesRead=$bytesRead")
                        }
                        Log.d("DevicesViewModel", "Preparing to send chunk $chunkCount: bytesRead=$bytesRead, chunkToSend.size=${chunkToSend.size}, first 8 bytes: ${chunkToSend.take(8).joinToString(" ") { String.format("%02x", it) }}, last 8 bytes: ${chunkToSend.takeLast(8).joinToString(" ") { String.format("%02x", it) }}")
                        try {
                            sendMessage(SyncMessage(MessageType.FILE_CHUNK, folderName = syncFolderName, fileChunkData = chunkToSend))
                        } catch (e: Exception) {
                            Log.e("DevicesViewModel", "Exception sending chunk $chunkCount: ${e.message}", e)
                            throw e
                        }
                        totalBytesSent += bytesRead
                        chunkCount++
                        Log.d("DevicesViewModel", "Sent chunk $chunkCount of size $bytesRead for $relativePath (total sent: $totalBytesSent)")
                    } catch (e: Exception) {
                        Log.e("DevicesViewModel", "Exception preparing chunk $chunkCount: bytesRead=$bytesRead, buffer.size=${buffer.size}", e)
                        throw e
                    }
                } else if (bytesRead < 0) {
                    Log.w("DevicesViewModel", "Negative bytesRead ($bytesRead) at chunk $chunkCount. Not sending.")
                }
                delay(5)
            }
        }
        Log.d("DevicesViewModel", "Finished sending $relativePath, total bytes sent: $totalBytesSent (expected: ${targetDocumentFile.length()})")

        if (!currentCoroutineContext().isActive) {
            Log.w("DevicesViewModel", "File sending for $relativePath was cancelled during/after read loop.")
            _syncHistory.add(0, SyncHistoryEntry(folderName = syncFolderName, status = "Cancelled", details = "File sending for $relativePath was cancelled."))
            return
        }
        Log.i("DevicesViewModel", "Finished sending chunks for $relativePath from $syncFolderName, total $totalBytesSent bytes.")

        if (currentCoroutineContext().isActive) {
            sendMessage(SyncMessage(MessageType.FILE_TRANSFER_END, folderName = syncFolderName, fileTransferInfo = transferInfo))
            Log.i("DevicesViewModel", "Sent FILE_TRANSFER_END for $relativePath from $syncFolderName")
            withContext(Dispatchers.Main) {
                if (permissionRequestStatus.value.contains("Sending: $relativePath from $syncFolderName")) {
                    permissionRequestStatus.value = "Sent: $relativePath from $syncFolderName"
                }
            }
        } else {
            Log.w("DevicesViewModel", "File sending for $relativePath was cancelled, FILE_TRANSFER_END not sent.")
             _syncHistory.add(0, SyncHistoryEntry(folderName = syncFolderName, status = "Cancelled", details = "File sending for $relativePath cancelled, FILE_TRANSFER_END not sent."))
        }
    }

    fun setDestinationUriForSync(folderName: String, destinationUri: Uri) {
        val currentMap = _activeSyncDestinationUris.value.toMutableMap()
        currentMap[folderName] = destinationUri
        _activeSyncDestinationUris.value = currentMap.toMap()
        val context = getApplication<Application>().applicationContext
        permissionRequestStatus.value = "Set '${DocumentFile.fromTreeUri(context,destinationUri)?.name ?: destinationUri}' as destination for syncs named '$folderName'."
        Log.d("DevicesViewModel", "Destination URI for sync folder '$folderName' set to '$destinationUri'. Current map: ${_activeSyncDestinationUris.value}")
    }

    fun setDefaultIncomingUri(uri: Uri) {
        defaultIncomingFolderUri = uri
        val context = getApplication<Application>().applicationContext
        permissionRequestStatus.value = "Default incoming folder set to '${DocumentFile.fromTreeUri(context, uri)?.name ?: uri}'."
        Log.i("DevicesViewModel", "Default incoming URI set to $uri")
    }

    private var currentReceivingFile: FileTransferState? = null
    private var currentFileOutputStream: java.io.OutputStream? = null

    data class FileTransferState(
        val folderName: String,
        val relativePath: String,
        val totalSize: Long,
        var bytesReceived: Long = 0L,
        val destinationBaseUri: Uri
    )

    private fun appendFileChunk(chunk: ByteArray) {
        val state = currentReceivingFile ?: run {
            Log.e("DevicesViewModel", "appendFileChunk called but currentReceivingFile is null.")
            return
        }
        val context = getApplication<Application>().applicationContext

        try {
            if (currentFileOutputStream == null) {
                var parentDocFile = DocumentFile.fromTreeUri(context, state.destinationBaseUri)
                if (parentDocFile == null || !parentDocFile.isDirectory) {
                    Log.e("DevicesViewModel", "Destination base URI is invalid or not a directory: ${state.destinationBaseUri}")
                     _syncHistory.add(0, SyncHistoryEntry(folderName = state.folderName, status = "Error", details = "Cannot receive ${state.relativePath}: Destination base URI invalid."))
                    return
                }

                val pathSegments = state.relativePath.split('/').dropLastWhile { it.isEmpty() }
                val fileName = pathSegments.last()

                for (i in 0 until pathSegments.size - 1) {
                    val segment = pathSegments[i]
                    var childDocFile = parentDocFile?.findFile(segment)
                    if (childDocFile == null) {
                        childDocFile = parentDocFile?.createDirectory(segment)
                        if (childDocFile == null) {
                            Log.e("DevicesViewModel", "Failed to create directory: $segment in ${state.destinationBaseUri}")
                            _syncHistory.add(0, SyncHistoryEntry(folderName = state.folderName, status = "Error", details = "Cannot receive ${state.relativePath}: Failed to create directory $segment."))
                            return
                        }
                    }
                    parentDocFile = childDocFile
                }

                var targetDocFile = parentDocFile?.findFile(fileName)
                if (targetDocFile == null) {
                    // --- Use correct MIME type ---
                    val extension = fileName.substringAfterLast('.', "")
                    val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase()) ?: "application/octet-stream"
                    targetDocFile = parentDocFile?.createFile(mimeType, fileName)
                }

                if (targetDocFile == null || !targetDocFile.canWrite()) {
                    Log.e("DevicesViewModel", "Cannot create or write to target file: ${state.relativePath} in ${state.destinationBaseUri}")
                    _syncHistory.add(0, SyncHistoryEntry(folderName = state.folderName, status = "Error", details = "Cannot create/write target file: ${state.relativePath}."))
                    return
                }
                // --- Always open in 'w' mode, only once per file ---
                currentFileOutputStream = context.contentResolver.openOutputStream(targetDocFile.uri, "w")

                if (currentFileOutputStream == null) {
                    Log.e("DevicesViewModel", "Failed to open OutputStream for ${targetDocFile.uri}")
                     _syncHistory.add(0, SyncHistoryEntry(folderName = state.folderName, status = "Error", details = "Failed to open output stream for ${state.relativePath}."))
                    return
                }
                Log.d("DevicesViewModel", "Receiving to file: ${targetDocFile.uri} (size: ${state.totalSize})")
                // --- Store for media scan after finalize ---
                stateMediaScanUri = targetDocFile.uri
                // Store the file's uri for later logging
                stateFileUriForDebug = targetDocFile.uri
            }

            // --- Log chunk size and first 16 bytes ---
            val firstBytes = chunk.take(16).joinToString(" ") { String.format("%02x", it) }
            Log.d("DevicesViewModel", "Writing chunk of size ${chunk.size} to ${state.relativePath}, first 16 bytes: $firstBytes")
            currentFileOutputStream?.write(chunk)
            state.bytesReceived += chunk.size
            Log.d("DevicesViewModel", "Received ${state.bytesReceived}/${state.totalSize} for ${state.relativePath} in folder ${state.folderName}")

        } catch (e: IOException) {
            Log.e("DevicesViewModel", "IOException writing file chunk for ${state.relativePath}: ${e.message}", e)
            try { currentFileOutputStream?.close() } catch (ioe: IOException) {}
            currentFileOutputStream = null
            _syncHistory.add(0, SyncHistoryEntry(folderName = state.folderName, status = "Error", details = "IOException writing chunk for ${state.relativePath}: ${e.message}"))
            currentReceivingFile = null
        } catch (e: Exception) {
            Log.e("DevicesViewModel", "Exception writing file chunk for ${state.relativePath}: ${e.message}", e)
            try { currentFileOutputStream?.close() } catch (ioe: IOException) {}
            currentFileOutputStream = null
            _syncHistory.add(0, SyncHistoryEntry(folderName = state.folderName, status = "Error", details = "Exception writing chunk for ${state.relativePath}: ${e.message}"))
            currentReceivingFile = null
        }
    }

    // --- Store the last written file's URI for media scan and debug ---
    private var stateMediaScanUri: android.net.Uri? = null
    private var stateFileUriForDebug: android.net.Uri? = null

    private fun finalizeReceivedFile() {
        val state = currentReceivingFile ?: return
        try {
            currentFileOutputStream?.flush()
            currentFileOutputStream?.close()
            Log.i("DevicesViewModel", "File ${state.relativePath} finalized in ${state.destinationBaseUri}. Total bytes: ${state.bytesReceived}/${state.totalSize}")
            if (state.bytesReceived != state.totalSize) {
                Log.w("DevicesViewModel", "File size mismatch for ${state.relativePath}! Expected ${state.totalSize}, got ${state.bytesReceived}")
                _syncHistory.add(0, SyncHistoryEntry(folderName = state.folderName, status = "Error", details = "File size mismatch for ${state.relativePath}. Expected ${state.totalSize}, got ${state.bytesReceived}."))
            } else {
                 _syncHistory.add(0, SyncHistoryEntry(folderName = state.folderName, status = "File Received", details = "File ${state.relativePath} received successfully."))
            }
            // --- Log first 16 bytes of the written file ---
            stateFileUriForDebug?.let { uri ->
                try {
                    val context = getApplication<Application>().applicationContext
                    context.contentResolver.openInputStream(uri)?.use { fis ->
                        val buf = ByteArray(16)
                        val n = fis.read(buf)
                        val hex = buf.take(n).joinToString(" ") { String.format("%02x", it) }
                        Log.d("DevicesViewModel", "First $n bytes of written file ${state.relativePath}: $hex")
                    }
                } catch (e: Exception) {
                    Log.e("DevicesViewModel", "Error reading first bytes of written file: ${e.message}")
                }
            }
            // --- Trigger media scan if file is an image or media ---
            stateMediaScanUri?.let { uri ->
                val scanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                scanIntent.data = uri
                getApplication<Application>().applicationContext.sendBroadcast(scanIntent)
                Log.d("DevicesViewModel", "Media scan triggered for $uri")
                stateMediaScanUri = null
            }
        } catch (e: IOException) {
            Log.e("DevicesViewModel", "IOException finalizing file ${state.relativePath}: ${e.message}", e)
            _syncHistory.add(0, SyncHistoryEntry(folderName = state.folderName, status = "Error", details = "IOException finalizing file ${state.relativePath}: ${e.message}"))
        } finally {
            currentFileOutputStream = null
            currentReceivingFile = null
            stateFileUriForDebug = null
        }
    }

    private fun closeCommunicationStreams() {
        Log.d("DevicesViewModel", "Closing communication streams.")
        communicationJob?.cancel()
        communicationJob = null
        try { objectInputStream?.close() } catch (e: IOException) { Log.w("DevicesViewModel", "Error closing objectInputStream: ${e.message}") }
        try { objectOutputStream?.close() } catch (e: IOException) { Log.w("DevicesViewModel", "Error closing objectOutputStream: ${e.message}") }
        objectInputStream = null
        objectOutputStream = null
    }

    private fun sendMessage(message: SyncMessage) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                synchronized(outputStreamLock) {
                    if (message.type == MessageType.FILE_CHUNK) {
                        val chunk = message.fileChunkData
                        Log.d("DevicesViewModel", "sendMessage: FILE_CHUNK, chunk size: ${chunk?.size}, first 8 bytes: ${chunk?.take(8)?.joinToString(" ") { String.format("%02x", it) }}, last 8 bytes: ${chunk?.takeLast(8)?.joinToString(" ") { String.format("%02x", it) }}")
                        objectOutputStream?.reset()
                    }
                    objectOutputStream?.writeObject(message)
                    objectOutputStream?.flush()
                }
                Log.d("DevicesViewModel", "Sent message: Type: ${message.type}, Folder: ${message.folderName}")
            } catch (e: IOException) {
                Log.e("DevicesViewModel", "Error sending message: ${e.message}", e)
                launch(kotlinx.coroutines.Dispatchers.Main) { permissionRequestStatus.value = "Error sending data." }
                if (message.type != MessageType.ERROR_MESSAGE) { // Avoid infinite error loops
                    _syncHistory.add(0, SyncHistoryEntry(folderName = message.folderName ?: "N/A", status = "Error", details = "Failed to send message type ${message.type}: ${e.message}"))
                }
            }
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

    // Add a public function to retry pending sync after folder mapping
    fun retryPendingSyncIfNeeded() {
        val msg = pendingSyncMessage
        if (msg != null && pendingFolderMapping.value == null) {
            pendingSyncMessage = null
            handleIncomingMessage(msg)
        }
    }
}