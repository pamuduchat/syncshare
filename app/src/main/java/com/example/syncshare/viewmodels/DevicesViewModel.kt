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
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.syncshare.data.SyncHistoryEntry
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
import android.webkit.MimeTypeMap
import java.security.MessageDigest
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

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

    private var objectOutputStream: ObjectOutputStream? = null
    private var objectInputStream: ObjectInputStream? = null
    private var communicationJob: Job? = null
    private var currentCommunicationTechnology: CommunicationTechnology? = null

    private val outputStreamLock = Any()

    // --- Add at the top of DevicesViewModel class ---
    private var syncMetadataSentForSession = false

    // --- Conflict data class and state ---
    data class FileConflict(
        val folderName: String,
        val relativePath: String,
        val local: FileMetadata?,
        val remote: FileMetadata?
    )
    private val _fileConflicts = MutableStateFlow<List<FileConflict>>(emptyList())
    val fileConflicts: StateFlow<List<FileConflict>> = _fileConflicts

    // --- Conflict resolution options ---
    enum class ConflictResolutionOption { KEEP_LOCAL, USE_REMOTE, KEEP_BOTH, SKIP }

    // --- Expose a function to resolve a conflict ---
    fun resolveFileConflict(conflict: FileConflict, option: ConflictResolutionOption) {
        // Remove the conflict from the list
        val updated = _fileConflicts.value.toMutableList().apply { remove(conflict) }
        _fileConflicts.value = updated
        when (option) {
            ConflictResolutionOption.KEEP_LOCAL -> {
                // Do nothing, keep local file
            }
            ConflictResolutionOption.USE_REMOTE -> {
                // Request the remote file
                sendMessage(SyncMessage(MessageType.FILES_REQUESTED_BY_PEER, folderName = conflict.folderName, requestedFilePaths = listOf(conflict.relativePath)))
            }
            ConflictResolutionOption.KEEP_BOTH -> {
                // Request remote file, but with a new name (e.g., append _remote or timestamp)
                val newPath = conflict.relativePath + "_remote_${System.currentTimeMillis()}"
                // The receiver will need to handle this rename on receipt
                sendMessage(SyncMessage(MessageType.FILES_REQUESTED_BY_PEER, folderName = conflict.folderName, requestedFilePaths = listOf(conflict.relativePath)))
            }
            ConflictResolutionOption.SKIP -> {
                // Do nothing
            }
        }
        // If all conflicts resolved, resume sync if needed
        if (_fileConflicts.value.isEmpty()) {
            _isRefreshing.value = false
            permissionRequestStatus.value = "All conflicts resolved. Sync can continue."
        }
    }

    private val prefs: SharedPreferences = application.getSharedPreferences("syncshare_prefs", Context.MODE_PRIVATE)
    private val KEY_HISTORY = "sync_history"
    private val gson = Gson()

    fun persistHistory() {
        val json = gson.toJson(_syncHistory)
        prefs.edit().putString(KEY_HISTORY, json).apply()
    }
    private fun loadHistory() {
        val json = prefs.getString(KEY_HISTORY, null)
        if (json != null) {
            val type = object : TypeToken<MutableList<SyncHistoryEntry>>() {}.type
            val list: MutableList<SyncHistoryEntry> = gson.fromJson(json, type) ?: mutableListOf()
            _syncHistory.clear()
            _syncHistory.addAll(list)
            persistHistory()
        }
    }

    init {
        Log.d("DevicesViewModel", "DevicesViewModel - INIT BLOCK - START")
        fullResetP2pConnection() // Always start with a clean P2P state
        loadHistory()
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
                    _isRefreshing.value = false; wifiDirectPeersInternal.clear(); updateDisplayableDeviceList()
                    p2pDiscoveryTimeoutJob?.cancel()
                }
            })
        } catch (e: SecurityException) { Log.e("DevicesViewModel", "SecEx P2P_INIT Channel: ${e.message}", e); permissionRequestStatus.value = "PermErr P2P Init."; return }

        if (p2pChannel == null) {  Log.e("DevicesViewModel", "P2P_INIT_FAIL: Channel is null after initialize."); permissionRequestStatus.value = "Error: P2P Channel failed to init.";  return }
        Log.d("DevicesViewModel", "P2P_INIT: Channel Initialized: $p2pChannel")
        if (isReset) {
            viewModelScope.launch { delay(300); registerP2pReceiver() }
        }
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

    fun onP2pPeersAvailable(peers: Collection<WifiP2pDevice>) {
        wifiDirectPeersInternal.clear()
        wifiDirectPeersInternal.addAll(peers)
        updateDisplayableDeviceList()
        if (peers.isEmpty()) {
            permissionRequestStatus.value = "No Wi-Fi Direct peers found."
        } else {
            permissionRequestStatus.value = "${peers.size} Wi-Fi Direct peer(s) found."
        }
        _isRefreshing.value = false
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

        _isRefreshing.value = true
        if (!isRetry) permissionRequestStatus.value = "Stopping previous P2P discovery..."

        // Create a timeout job for stopping discovery
        val stopTimeoutJob = viewModelScope.launch {
            delay(5000) // 5 second timeout for stopping discovery
            if (_isRefreshing.value && permissionRequestStatus.value.contains("Stopping previous P2P discovery")) {
                Log.w("DevicesViewModel", "Timeout waiting for stopPeerDiscovery to complete. Proceeding with new discovery.")
                initiateActualP2pDiscoveryAfterStop()
            }
        }

        try {
            wifiP2pManager?.stopPeerDiscovery(p2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { 
                    Log.i("DevicesViewModel", "stopP2pDiscovery.onSuccess()")
                    stopTimeoutJob.cancel()
                    if (!isRetry) permissionRequestStatus.value = "Preparing P2P discovery..."
                    initiateActualP2pDiscoveryAfterStop() 
                }
                override fun onFailure(reasonCode: Int) { 
                    val r = getFailureReasonString(reasonCode)
                    Log.w("DevicesViewModel", "stopP2pDiscovery.onFailure - $r ($reasonCode)")
                    stopTimeoutJob.cancel()
                    if (!isRetry) permissionRequestStatus.value = "Stop P2P warn ($r)"
                    initiateActualP2pDiscoveryAfterStop() 
                }
            })
        } catch (e: SecurityException) { 
            Log.e("DevicesViewModel", "SecEx stopP2pDisc: ${e.message}", e)
            stopTimeoutJob.cancel()
            if (!isRetry) permissionRequestStatus.value = "PermErr stopP2PDisc"
            initiateActualP2pDiscoveryAfterStop() 
        }
        catch (e: Exception) { 
            Log.e("DevicesViewModel", "GenEx stopP2pDisc: ${e.message}", e)
            stopTimeoutJob.cancel()
            if (!isRetry) permissionRequestStatus.value = "Err stopP2PDisc"
            initiateActualP2pDiscoveryAfterStop()
        }
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


    @SuppressLint("MissingPermission")
    fun connectToP2pDevice(device: WifiP2pDevice) {
        Log.i("DevicesViewModel", "connectToP2pDevice: ${device.deviceName} (status: ${device.status})")
        if (wifiP2pManager == null || p2pChannel == null) {
            Log.e("DevicesViewModel", "Cannot connect P2P: Manager/Channel null.")
            permissionRequestStatus.value = "P2P Connect Error: Service not ready."
            return
        }
        val context = getApplication<Application>().applicationContext
        if (!getWifiDirectPermissions().all { ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            Log.e("DevicesViewModel", "connectToP2pDevice - Missing P2P permissions.")
            permissionRequestStatus.value = "P2P perm needed for connect."
            return
        }

        // Only connect if device is AVAILABLE
        if (device.status != WifiP2pDevice.AVAILABLE) {
            Log.w("DevicesViewModel", "Device ${device.deviceName} is not AVAILABLE (status: ${getDeviceP2pStatusString(device.status)}), skipping connect.")
            permissionRequestStatus.value = "Device is not available for connection."
            return
        }

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }

        try {
            wifiP2pManager?.connect(p2pChannel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i("DevicesViewModel", "P2P Connect INITIATION to ${device.deviceName} SUCCEEDED.")
                    permissionRequestStatus.value = "P2P Connecting to ${device.deviceName}..."
                }
                override fun onFailure(reasonCode: Int) {
                    val r = getDetailedFailureReasonString(reasonCode)
                    Log.e("DevicesViewModel", "P2P Connect INITIATION FAILED to ${device.deviceName}. Reason: $r ($reasonCode)")
                    permissionRequestStatus.value = "P2P Connect Failed: $r"
                    // Restart discovery on failure
                    startP2pDiscovery()
                }
            })
        } catch (e: SecurityException) {
            Log.e("DevicesViewModel", "SecEx P2P connect: ${e.message}", e)
            permissionRequestStatus.value = "PermErr P2P connect."
        } catch (e: Exception) {
            Log.e("DevicesViewModel", "Unexpected error during P2P connect: ${e.message}", e)
            permissionRequestStatus.value = "P2P Connect Error: ${e.message}"
        }
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
        p2pChannel = null; wifiDirectPeersInternal.clear(); updateDisplayableDeviceList()
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
                    persistHistory()
                    val folderName = message.folderName
                    val localFolderUri = _activeSyncDestinationUris.value[folderName]

                    if (localFolderUri == null) {
                        pendingFolderMapping.value = folderName
                        pendingSyncMessage = message
                        return@launch
                    }

                    launch(Dispatchers.IO) {
                        val localFiles = getLocalFileMetadata(localFolderUri)
                        val remoteFiles = message.fileMetadataList ?: emptyList()
                        val filesToRequest = mutableListOf<String>()
                        val localFileMap = localFiles.associateBy { it.relativePath }
                        val remoteFileMap = remoteFiles.associateBy { it.relativePath }
                        val conflicts = mutableListOf<FileConflict>()

                        for ((remotePath, remoteMeta) in remoteFileMap) {
                            val localMeta = localFileMap[remotePath]
                            if (localMeta == null) {
                                // File exists on remote but not local, request it
                                filesToRequest.add(remotePath)
                            } else {
                                // File exists on both, compare size first
                                if (remoteMeta.size != localMeta.size) {
                                    // Sizes differ, check hash
                                    if (remoteMeta.hash != localMeta.hash) {
                                        // Conflict detected: both exist, but content differs
                                        conflicts.add(FileConflict(folderName ?: "", remotePath, localMeta, remoteMeta))
                                    }

                                }
                                // If sizes are the same, treat as identical, skip (even if hash/lastModified differ)
                            }
                        }
                        // --- Pause sync and show conflicts if any ---
                        if (conflicts.isNotEmpty()) {
                            _fileConflicts.value = conflicts
                            withContext(Dispatchers.Main) {
                                _isRefreshing.value = false
                                permissionRequestStatus.value = "File conflicts detected. Please resolve." 
                            }
                            return@launch
                        }
                        // --- No conflicts, proceed as before ---
                        Log.d("DevicesViewModel", "Requesting ${filesToRequest.size} files for folder '${message.folderName}': $filesToRequest")
                        sendMessage(SyncMessage(MessageType.FILES_REQUESTED_BY_PEER, folderName = message.folderName, requestedFilePaths = filesToRequest))
                        sendMessage(
                            SyncMessage(
                                type = MessageType.SYNC_REQUEST_METADATA,
                                folderName = folderName,
                                fileMetadataList = localFiles
                            )
                        )
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
                        persistHistory()
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
                        // --- Fix: Re-enable sync button if no files to send ---
                        _isRefreshing.value = false
                        permissionRequestStatus.value = "Sync complete (no files to send)."
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
                        persistHistory()
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
                        persistHistory()
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
                    persistHistory()
                }
                MessageType.FILE_CHUNK -> { message.fileChunkData?.let { appendFileChunk(it) } }
                MessageType.FILE_TRANSFER_END -> {
                    val info = message.fileTransferInfo; Log.i("DevicesViewModel", "File transfer finished for: ${info?.relativePath}"); permissionRequestStatus.value = "Received: ${info?.relativePath}"; finalizeReceivedFile(); info?.let { sendMessage(SyncMessage(MessageType.FILE_RECEIVED_ACK, fileTransferInfo = FileTransferInfo(it.relativePath, 0L)))}; currentReceivingFile = null
                }
                MessageType.FILE_RECEIVED_ACK -> { Log.i("DevicesViewModel", "Peer ACKed file: ${message.fileTransferInfo?.relativePath}") }
                MessageType.SYNC_COMPLETE -> {
                    Log.i("DevicesViewModel", "SYNC_COMPLETE received for folder: ${message.folderName}"); permissionRequestStatus.value = "Sync complete for '${message.folderName}'."; _isRefreshing.value = false
                    _syncHistory.add(0, SyncHistoryEntry(folderName = message.folderName ?: "Unknown", status = "Completed", details = "Sync successfully completed for folder."))
                    persistHistory()
                    // --- Reset syncMetadataSentForSession at the end of a sync ---
                    syncMetadataSentForSession = false
                }
                MessageType.ERROR_MESSAGE -> {
                    Log.e("DevicesViewModel", "Received ERROR_MESSAGE: ${message.errorMessage}"); permissionRequestStatus.value = "Error from peer: ${message.errorMessage}"
                    _syncHistory.add(0, SyncHistoryEntry(folderName = message.folderName ?: "Associated with error", status = "Error", details = "Error during sync: ${message.errorMessage}"))
                    persistHistory()
                }
                MessageType.DISCONNECT -> {
                    _p2pConnectionStatus.value = "Disconnected (by peer)"
                    permissionRequestStatus.value = "Peer disconnected."
                    // Close the P2P connection if still open
                    viewModelScope.launch(Dispatchers.IO) {
                        closeCommunicationStreams()
                        closeP2pSockets()
                        withContext(Dispatchers.Main) {
                            _isRefreshing.value = false
                        }
                    }
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
        persistHistory()

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            _bluetoothConnectionStatus.value = "Accepted connection from $remoteDeviceName"
            permissionRequestStatus.value = "BT Peer connected: $remoteDeviceName"
        }
    }

    fun handleP2pConnectionInfo(info: WifiP2pInfo) {
        viewModelScope.launch {
            if (info.groupFormed) {
                val peerDeviceName = if (info.isGroupOwner) "P2P Group Client" else info.groupOwnerAddress?.hostAddress ?: "P2P Group Owner"
                if (info.isGroupOwner) {
                    Log.i("DevicesViewModel", "P2P Group Owner. Starting P2P Server.")
                    withContext(Dispatchers.Main) { _p2pConnectionStatus.value = "Group Owner: Starting Server..." }
                    startP2pServer()
                    _syncHistory.add(0, SyncHistoryEntry(folderName = "N/A", status = "Connected (Owner)", details = "P2P Group formed, acting as owner.", peerDeviceName = "Group Client (TBD)"))
                    persistHistory()
                } else {
                    Log.i("DevicesViewModel", "P2P Client. Connecting to Group Owner: ${info.groupOwnerAddress?.hostAddress}")
                    if (info.groupOwnerAddress?.hostAddress != null) {
                        withContext(Dispatchers.Main) { _p2pConnectionStatus.value = "Client: Connecting to Owner..." }
                        connectToP2pOwner(info.groupOwnerAddress.hostAddress)
                        _syncHistory.add(0, SyncHistoryEntry(folderName = "N/A", status = "Connected (Client)", details = "P2P Group formed, acting as client.", peerDeviceName = info.groupOwnerAddress.hostAddress))
                        persistHistory()
                    } else {
                        Log.e("DevicesViewModel", "P2P Client: Group owner address is null!")
                        withContext(Dispatchers.Main) { _p2pConnectionStatus.value = "Error: Owner address null" }
                        _syncHistory.add(0, SyncHistoryEntry(folderName = "N/A", status = "Error", details = "P2P Connection failed: Group owner address null."))
                        persistHistory()
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
                persistHistory()
            } catch (se: SecurityException) {
                Log.e("DevicesViewModel", "SecEx starting P2P server: ${se.message}", se)
                withContext(Dispatchers.Main) { _p2pConnectionStatus.value = "P2P Server Permission Error" }
                 _syncHistory.add(0, SyncHistoryEntry(folderName = "N/A", status = "Error", details = "P2P Server permission error: ${se.message}"))
                persistHistory()
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
            persistHistory()
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
                persistHistory()
                closeP2pClientSocket()
            } catch (se: SecurityException) {
                Log.e("DevicesViewModel", "SecurityException during P2P client connection: ${se.message}", se)
                withContext(Dispatchers.Main) { _p2pConnectionStatus.value = "P2P Connection Permission Error" }
                 _syncHistory.add(0, SyncHistoryEntry(folderName = "N/A", status = "Error", details = "P2P client connection permission error: ${se.message}", peerDeviceName = ownerAddress))
                persistHistory()
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
            // Send DISCONNECT message to peer before closing
            try {
                sendMessage(com.example.syncshare.protocol.SyncMessage(com.example.syncshare.protocol.MessageType.DISCONNECT))
            } catch (e: Exception) {
                Log.w("DevicesViewModel", "Failed to send DISCONNECT message: ${e.message}")
            }
            if (_isRefreshing.value && (p2pClientSocket != null || p2pServerSocket != null) ) { // If a sync was active
                _syncHistory.add(0, SyncHistoryEntry(folderName = "Active Sync", status = "Error", details = "P2P Disconnected during active sync."))
                persistHistory()
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
                        // --- Compute hash for file ---
                        val hash = computeFileHash(context, file)
                        metadataList.add(
                            FileMetadata(
                                relativePath = relativePath,
                                name = file.name ?: "Unknown Name",
                                size = file.length(),
                                lastModified = file.lastModified(),
                                hash = hash
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

    // --- Helper to compute SHA-256 hash of a file ---
    private fun computeFileHash(context: Context, file: DocumentFile): String {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(file.uri)?.use { inputStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e("DevicesViewModel", "Error computing hash for file: ${file.uri}", e)
            return ""
        }
    }

    fun initiateSyncRequest(folderUri: Uri) {
        if (objectOutputStream == null) {
            Log.e("DevicesViewModel", "Cannot initiate sync: Communication streams not ready.")
            permissionRequestStatus.value = "Error: Not connected for sync."
            val folderNameForHistory = DocumentFile.fromTreeUri(getApplication(), folderUri)?.name ?: folderUri.toString()
            _syncHistory.add(0, SyncHistoryEntry(folderName = folderNameForHistory, status = "Error", details = "Cannot initiate sync: Not connected."))
            persistHistory()
            return
        }
        val context = getApplication<Application>().applicationContext
        val folderNameForSyncMessage = DocumentFile.fromTreeUri(context, folderUri)?.name ?: folderUri.toString()

        // --- Store the mapping from folder name to URI ---
        val currentMap = _activeSyncDestinationUris.value.toMutableMap()
        currentMap[folderNameForSyncMessage] = folderUri
        _activeSyncDestinationUris.value = currentMap.toMap()
        // -------------------------------------------------

        // --- Reset syncMetadataSentForSession at the start of a sync ---
        syncMetadataSentForSession = true

        viewModelScope.launch {
            permissionRequestStatus.value = "Preparing to sync folder: $folderNameForSyncMessage"
            _isRefreshing.value = true

            val folderNameForHistory = DocumentFile.fromTreeUri(getApplication(), folderUri)?.name ?: folderUri.toString()
            _syncHistory.add(0, SyncHistoryEntry(folderName = folderNameForHistory, status = "Initiated (Sender)", details = "Sync request initiated for folder."))
            persistHistory()

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

    // --- Track outstanding file sends for two-way sync ---
    private var pendingFileSends = mutableSetOf<String>()

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
            persistHistory()
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
                persistHistory()
                return
            }
        }

        if (targetDocumentFile == null || !targetDocumentFile.isFile) {
            Log.e("DevicesViewModel", "Target DocumentFile is null or not a file for relativePath: $relativePath")
            sendMessage(SyncMessage(MessageType.ERROR_MESSAGE, folderName = syncFolderName, errorMessage = "File not found or is not a file on sender: $relativePath"))
            _syncHistory.add(0, SyncHistoryEntry(folderName = syncFolderName, status = "Error", details = "File not found or is not a file on sender: $relativePath."))
            persistHistory()
            return
        }

        val transferInfo = FileTransferInfo(relativePath, targetDocumentFile.length())
        sendMessage(SyncMessage(MessageType.FILE_TRANSFER_START, folderName = syncFolderName, fileTransferInfo = transferInfo))

        withContext(Dispatchers.Main) {
            permissionRequestStatus.value = "Sending: $relativePath from $syncFolderName..."
        }

        // --- Track this file as pending ---
        pendingFileSends.add(relativePath)

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
                pendingFileSends.remove(relativePath)
                return
            }
            while (fis.read(buffer).also { bytesRead = it } != -1 && currentCoroutineContext().isActive) {
                if (bytesRead > 0) {
                    try {
                        val chunkToSend = buffer.copyOf(bytesRead)
                        sendMessage(SyncMessage(MessageType.FILE_CHUNK, folderName = syncFolderName, fileChunkData = chunkToSend))
                        totalBytesSent += bytesRead
                        chunkCount++
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
            pendingFileSends.remove(relativePath)
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
            persistHistory()
        }

        // --- Remove from pending and re-enable sync button if done ---
        pendingFileSends.remove(relativePath)
        if (pendingFileSends.isEmpty()) {
            withContext(Dispatchers.Main) {
                _isRefreshing.value = false
                permissionRequestStatus.value = "Sync complete for '$syncFolderName'."
            }
        }
    }

    fun setDestinationUriForSync(folderName: String, destinationUri: Uri) {
        val currentMap = _activeSyncDestinationUris.value.toMutableMap()
        currentMap[folderName] = destinationUri
        _activeSyncDestinationUris.value = currentMap.toMap()
        val context = getApplication<Application>().applicationContext
        permissionRequestStatus.value = "Set '${DocumentFile.fromTreeUri(context,destinationUri)?.name ?: destinationUri}' as destination for syncs named '$folderName'."
        Log.d("DevicesViewModel", "Destination URI for sync folder '$folderName' set to '$destinationUri'. Current map: ${_activeSyncDestinationUris.value}")
        // --- Also add to ManageFoldersViewModel for future syncs ---
        manageFoldersViewModel?.addFolder(destinationUri)
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
                    persistHistory()
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
                            persistHistory()
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
                    persistHistory()
                    return
                }
                // --- Always open in 'w' mode, only once per file ---
                currentFileOutputStream = context.contentResolver.openOutputStream(targetDocFile.uri, "w")

                if (currentFileOutputStream == null) {
                    Log.e("DevicesViewModel", "Failed to open OutputStream for ${targetDocFile.uri}")
                     _syncHistory.add(0, SyncHistoryEntry(folderName = state.folderName, status = "Error", details = "Failed to open output stream for ${state.relativePath}."))
                    persistHistory()
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
            persistHistory()
            currentReceivingFile = null
        } catch (e: Exception) {
            Log.e("DevicesViewModel", "Exception writing file chunk for ${state.relativePath}: ${e.message}", e)
            try { currentFileOutputStream?.close() } catch (ioe: IOException) {}
            currentFileOutputStream = null
            _syncHistory.add(0, SyncHistoryEntry(folderName = state.folderName, status = "Error", details = "Exception writing chunk for ${state.relativePath}: ${e.message}"))
            persistHistory()
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
            persistHistory()
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
                val context = getApplication<Application>().applicationContext
                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(uri.toString()),
                    null
                ) { path, uri ->
                    Log.d("DevicesViewModel", "Media scan completed for $uri at $path")
                }
                Log.d("DevicesViewModel", "Media scan triggered for $uri (MediaScannerConnection)")
                stateMediaScanUri = null
            }
        } catch (e: IOException) {
            Log.e("DevicesViewModel", "IOException finalizing file ${state.relativePath}: ${e.message}", e)
            _syncHistory.add(0, SyncHistoryEntry(folderName = state.folderName, status = "Error", details = "IOException finalizing file ${state.relativePath}: ${e.message}"))
            persistHistory()
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

    /**
     * Fully resets all P2P state, peer/device lists, and statuses, as if the app just started.
     * Use this for a true 'fresh start' after disconnect.
     */
    fun fullResetP2pConnection() {
        Log.i("DevicesViewModel", "fullResetP2pConnection CALLED - Full P2P state reset")
        // Try to remove the group at the system level
        try {
            if (wifiP2pManager != null && p2pChannel != null) {
                wifiP2pManager?.removeGroup(p2pChannel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.i("DevicesViewModel", "removeGroup succeeded in fullResetP2pConnection")
                    }
                    override fun onFailure(reason: Int) {
                        Log.w("DevicesViewModel", "removeGroup failed in fullResetP2pConnection: reason $reason")
                    }
                })
            }
        } catch (e: Exception) {
            Log.e("DevicesViewModel", "Exception in removeGroup during fullResetP2pConnection: ${e.message}", e)
        }
        resetWifiDirectSystem()
        // Clear all peer/device lists and statuses
        wifiDirectPeersInternal.clear()
        bluetoothDevicesInternal.clear()
        displayableDeviceList.clear()
        _p2pConnectionStatus.value = "Disconnected"
        _isRefreshing.value = false
        permissionRequestStatus.value = "Idle. Tap a scan button."
    }

    // --- Reference to ManageFoldersViewModel for folder registration ---
    private var manageFoldersViewModel: ManageFoldersViewModel? = null
    fun setManageFoldersViewModel(vm: ManageFoldersViewModel) { manageFoldersViewModel = vm }

    fun clearHistory() {
        _syncHistory.clear()
        persistHistory()
    }
}