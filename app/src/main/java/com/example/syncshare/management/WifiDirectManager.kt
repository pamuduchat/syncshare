package com.example.syncshare.management

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import com.example.syncshare.features.WifiDirectBroadcastReceiver
import com.example.syncshare.utils.AppConstants
import com.example.syncshare.utils.getDeviceP2pStatusString
import com.example.syncshare.utils.getDetailedFailureReasonString
import com.example.syncshare.utils.getFailureReasonString
import com.example.syncshare.utils.getWifiDirectPermissions
import com.example.syncshare.utils.isLocationEnabled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

class WifiDirectManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    
    // StateFlow for discovered peers
    private val _discoveredPeers = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val discoveredPeers: StateFlow<List<WifiP2pDevice>> = _discoveredPeers.asStateFlow()
    
    // StateFlow for connection status
    private val _connectionStatus = MutableStateFlow<String>("Disconnected")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()
    
    // StateFlow for scanning status
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    
    // StateFlow for status message
    private val _statusMessage = MutableStateFlow("Idle. Ready for P2P operations.")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()
    
    // StateFlow for connected socket (null when not connected)
    private val _connectedSocket = MutableStateFlow<Socket?>(null)
    val connectedSocket: StateFlow<Socket?> = _connectedSocket.asStateFlow()
    
    // StateFlow for connected device address (null when not connected)
    private val _connectedDeviceAddress = MutableStateFlow<String?>(null)
    val connectedDeviceAddress: StateFlow<String?> = _connectedDeviceAddress.asStateFlow()
    
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
    
    private var p2pServerSocket: ServerSocket? = null
    private var p2pClientSocket: Socket? = null
    private var p2pServerJob: Job? = null
    
    init {
        Log.d("WifiDirectManager", "Initializing WifiDirectManager")
        scope.launch {
            initializeWifiP2p()
            // Always register receiver after initialization
            delay(300)
            registerP2pReceiver()
        }
    }
    
    private fun initializeWifiP2p(isReset: Boolean = false) {
        Log.i("WifiDirectManager", "initializeWifiP2p() CALLED. Is reset: $isReset")
        wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

        if (wifiP2pManager == null) {
            Log.e("WifiDirectManager", "P2P_INIT_FAIL: WifiP2pManager is null.")
            _statusMessage.value = "Error: P2P Service not available."
            return
        }
        Log.d("WifiDirectManager", "P2P_INIT: WifiP2pManager obtained.")

        try {
            p2pChannel = wifiP2pManager?.initialize(context, Looper.getMainLooper(), object : WifiP2pManager.ChannelListener {
                override fun onChannelDisconnected() {
                    Log.w("WifiDirectManager", "P2P Channel disconnected")
                    _statusMessage.value = "P2P Channel disconnected"
                }
            })
        } catch (e: SecurityException) {
            Log.e("WifiDirectManager", "SecEx P2P_INIT Channel: ${e.message}", e)
            _statusMessage.value = "PermErr P2P Init."
            return
        }

        if (p2pChannel == null) {
            Log.e("WifiDirectManager", "P2P_INIT_FAIL: Channel is null after initialize.")
            _statusMessage.value = "Error: P2P Channel failed to init."
            return
        }
        
        Log.d("WifiDirectManager", "P2P_INIT: Channel Initialized: $p2pChannel")
        if (isReset) {
            scope.launch {
                delay(300)
                registerP2pReceiver()
            }
        }
    }
    
    fun registerP2pReceiver() {
        if (p2pChannel == null) {
            Log.e("WifiDirectManager", "Cannot reg P2P receiver, channel null.")
            return
        }
        
        if (p2pBroadcastReceiver == null) {
            try {
                p2pBroadcastReceiver = WifiDirectBroadcastReceiver(wifiP2pManager, p2pChannel, this)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(p2pBroadcastReceiver, p2pIntentFilter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(p2pBroadcastReceiver, p2pIntentFilter)
                }
                Log.d("WifiDirectManager", "P2P BroadcastReceiver registered.")
            } catch (e: SecurityException) {
                Log.e("WifiDirectManager", "SecurityException registering P2P receiver: ${e.message}", e)
                p2pBroadcastReceiver = null
            } catch (e: Exception) {
                Log.e("WifiDirectManager", "Exception registering P2P receiver: ${e.message}", e)
                p2pBroadcastReceiver = null
            }
        } else {
            Log.d("WifiDirectManager", "P2P BroadcastReceiver already registered.")
        }
    }
    
    fun unregisterP2pReceiver() {
        p2pDiscoveryTimeoutJob?.cancel()
        if (p2pBroadcastReceiver != null) {
            try {
                context.unregisterReceiver(p2pBroadcastReceiver)
                Log.d("WifiDirectManager", "P2P BroadcastReceiver unregistered.")
            } catch (e: IllegalArgumentException) {
                Log.w("WifiDirectManager", "Error unreg P2P receiver: ${e.message}")
            } finally {
                p2pBroadcastReceiver = null
            }
        } else {
            Log.d("WifiDirectManager", "P2P Receiver already null.")
        }
    }
    
    fun onP2pPeersAvailable(peers: Collection<WifiP2pDevice>) {
        _discoveredPeers.value = peers.toList()
        if (peers.isEmpty()) {
            _statusMessage.value = "No Wi-Fi Direct peers found."
        } else {
            _statusMessage.value = "${peers.size} Wi-Fi Direct peer(s) found."
        }
        _isScanning.value = false
    }
    
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    fun startDiscovery(isRetry: Boolean = false) {
        if (!isRetry) {
            p2pDiscoveryRetryCount = 0
        }
        Log.i("WifiDirectManager", "startDiscovery() - IsRetry: $isRetry, Count: $p2pDiscoveryRetryCount")
        
        if (wifiP2pManager == null || p2pChannel == null) {
            Log.e("WifiDirectManager", "startDiscovery - P2PManager/Channel null")
            _isScanning.value = false
            return
        }
        
        // Ensure receiver is registered before starting discovery
        registerP2pReceiver()
        
        if (!getWifiDirectPermissions().all { ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            Log.e("WifiDirectManager", "startDiscovery - Perms missing")
            _isScanning.value = false
            return
        }

        _isScanning.value = true
        if (!isRetry) _statusMessage.value = "Stopping previous P2P discovery..."

        // Create a timeout job for stopping discovery
        val stopTimeoutJob = scope.launch {
            delay(5000) // 5 second timeout for stopping discovery
            if (_isScanning.value && _statusMessage.value.contains("Stopping previous P2P discovery")) {
                Log.w("WifiDirectManager", "Timeout stopping P2P discovery, proceeding anyway")
                initiateActualP2pDiscoveryAfterStop()
            }
        }

        try {
            wifiP2pManager?.stopPeerDiscovery(p2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d("WifiDirectManager", "stopPeerDiscovery SUCCESS")
                    stopTimeoutJob.cancel()
                    initiateActualP2pDiscoveryAfterStop()
                }

                override fun onFailure(reason: Int) {
                    Log.w("WifiDirectManager", "stopPeerDiscovery FAILURE: ${getFailureReasonString(reason)} (${getDetailedFailureReasonString(reason)})")
                    stopTimeoutJob.cancel()
                    initiateActualP2pDiscoveryAfterStop()
                }
            })
        } catch (e: SecurityException) {
            Log.e("WifiDirectManager", "SecEx stopP2pDisc: ${e.message}", e)
            stopTimeoutJob.cancel()
            if (!isRetry) _statusMessage.value = "PermErr stopP2PDisc"
            _isScanning.value = false
        } catch (e: Exception) {
            Log.e("WifiDirectManager", "GenEx stopP2pDisc: ${e.message}", e)
            stopTimeoutJob.cancel()
            if (!isRetry) _statusMessage.value = "Err stopP2PDisc"
            _isScanning.value = false
        }
    }
    
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    private fun initiateActualP2pDiscoveryAfterStop() {
        Log.d("WifiDirectManager", "initiateActualP2pDiscoveryAfterStop() - Retry: $p2pDiscoveryRetryCount")
        if (p2pChannel == null) {
            Log.e("WifiDirectManager", "initActualP2pDisc - Channel null")
            _isScanning.value = false
            p2pDiscoveryRetryCount = 0
            checkWifiDirectStatus()
            return
        }

        _statusMessage.value = "Starting P2P discovery${if (p2pDiscoveryRetryCount > 0) " (attempt ${p2pDiscoveryRetryCount + 1})" else ""}..."
        
        try {
            wifiP2pManager?.discoverPeers(p2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d("WifiDirectManager", "discoverPeers SUCCESS")
                    _statusMessage.value = "Discovering P2P peers..."
                    p2pDiscoveryRetryCount = 0
                    
                    // Start timeout for discovery
                    p2pDiscoveryTimeoutJob?.cancel()
                    p2pDiscoveryTimeoutJob = scope.launch {
                        delay(P2P_DISCOVERY_TIMEOUT_MS)
                        if (_isScanning.value) {
                            Log.w("WifiDirectManager", "P2P discovery timeout reached")
                            _isScanning.value = false
                            if (_discoveredPeers.value.isEmpty()) {
                                _statusMessage.value = "P2P discovery timeout. No peers found."
                            }
                        }
                    }
                }

                override fun onFailure(reason: Int) {
                    Log.w("WifiDirectManager", "discoverPeers FAILURE: ${getFailureReasonString(reason)} (${getDetailedFailureReasonString(reason)})")
                    p2pDiscoveryRetryCount++
                    if (p2pDiscoveryRetryCount < MAX_P2P_DISCOVERY_RETRIES) {
                        Log.i("WifiDirectManager", "Retrying P2P discovery in 2 seconds... (attempt $p2pDiscoveryRetryCount)")
                        _statusMessage.value = "P2P discovery failed, retrying..."
                        scope.launch {
                            delay(2000)
                            startDiscovery(isRetry = true)
                        }
                    } else {
                        Log.e("WifiDirectManager", "P2P discovery failed after $MAX_P2P_DISCOVERY_RETRIES attempts")
                        _statusMessage.value = "P2P discovery failed after retries: ${getFailureReasonString(reason)}"
                        _isScanning.value = false
                        p2pDiscoveryRetryCount = 0
                        checkWifiDirectStatus()
                        p2pDiscoveryTimeoutJob?.cancel()
                    }
                }
            })
        } catch (e: SecurityException) {
            Log.e("WifiDirectManager", "SecEx discoverP2pPeers: ${e.message}", e)
            _statusMessage.value = "PermErr P2P Disc."
            _isScanning.value = false
            p2pDiscoveryRetryCount = 0
            checkWifiDirectStatus()
            p2pDiscoveryTimeoutJob?.cancel()
        }
    }
    
    @SuppressLint("MissingPermission")
    fun connectToDevice(device: WifiP2pDevice) {
        Log.i("WifiDirectManager", "connectToDevice: ${device.deviceName} (status: ${device.status})")
        if (wifiP2pManager == null || p2pChannel == null) {
            Log.e("WifiDirectManager", "Cannot connect P2P: Manager/Channel null.")
            _statusMessage.value = "P2P Connect Error: Service not ready."
            return
        }
        
        // Ensure receiver is registered before connecting
        registerP2pReceiver()
        
        if (!getWifiDirectPermissions().all { ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            Log.e("WifiDirectManager", "connectToDevice - Missing P2P permissions.")
            _statusMessage.value = "P2P perm needed for connect."
            return
        }

        // Only connect if device is AVAILABLE
        if (device.status != WifiP2pDevice.AVAILABLE) {
            Log.w("WifiDirectManager", "Device ${device.deviceName} is not AVAILABLE (status: ${getDeviceP2pStatusString(device.status)}), skipping connect.")
            _statusMessage.value = "Device is not available for connection."
            return
        }

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }

        // Update UI immediately when user initiates connection
        _statusMessage.value = "Initiating connection to ${device.deviceName}..."
        _connectionStatus.value = "Connecting..."
        _connectedDeviceAddress.value = device.deviceAddress

        try {
            wifiP2pManager?.connect(p2pChannel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d("WifiDirectManager", "P2P connect SUCCESS to ${device.deviceName}")
                    _statusMessage.value = "Connection initiated to ${device.deviceName}..."
                    // Keep connection status as "Connecting..." until group is formed
                }

                override fun onFailure(reason: Int) {
                    Log.w("WifiDirectManager", "P2P connect FAILURE: ${getFailureReasonString(reason)}")
                    _statusMessage.value = "P2P Connect failed: ${getFailureReasonString(reason)}"
                    _connectionStatus.value = "Connection failed"
                    _connectedDeviceAddress.value = null
                }
            })
        } catch (e: SecurityException) {
            Log.e("WifiDirectManager", "SecEx P2P connect: ${e.message}", e)
            _statusMessage.value = "PermErr P2P connect."
            _connectionStatus.value = "Permission error"
            _connectedDeviceAddress.value = null
        } catch (e: IllegalArgumentException) {
            Log.e("WifiDirectManager", "IllegalArg P2P connect: ${e.message}", e)
            _statusMessage.value = "Invalid device for connection."
            _connectionStatus.value = "Invalid device"
            _connectedDeviceAddress.value = null
        } catch (e: Exception) {
            Log.e("WifiDirectManager", "Unexpected error during P2P connect: ${e.message}", e)
            _statusMessage.value = "P2P Connect Error: ${e.message}"
            _connectionStatus.value = "Connection error"
            _connectedDeviceAddress.value = null
        }
    }
    
    fun handleConnectionInfo(info: WifiP2pInfo) {
        scope.launch @androidx.annotation.RequiresPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) {
            try {
                if (info.groupFormed) {
                    Log.i("WifiDirectManager", "P2P Group formed. Group owner: ${info.isGroupOwner}, Owner address: ${info.groupOwnerAddress}")
                    _connectionStatus.value = "Group formed"
                    
                    // Request peers to get the actual connected device address
                    forceRequestPeersForConnection()
                    
                    if (info.isGroupOwner) {
                        Log.d("WifiDirectManager", "This device is the Group Owner. Starting P2P server...")
                        _statusMessage.value = "Group Owner. Starting server..."
                        startP2pServer()
                    } else {
                        Log.d("WifiDirectManager", "This device is a Group Client. Connecting to owner at ${info.groupOwnerAddress}")
                        _statusMessage.value = "Group Client. Connecting to owner..."
                        connectToP2pOwner(info.groupOwnerAddress?.hostAddress)
                    }
                } else {
                    Log.i("WifiDirectManager", "P2P Group NOT formed or disconnected.")
                    _connectionStatus.value = "Disconnected"
                    _connectedSocket.value = null
                    _connectedDeviceAddress.value = null
                    _statusMessage.value = "P2P Group disconnected"
                    disconnect()
                }
            } catch (e: Exception) {
                Log.e("WifiDirectManager", "Error handling connection info: ${e.message}", e)
                _statusMessage.value = "Error handling P2P connection"
                _connectionStatus.value = "Connection error"
            }
        }
    }
    
    @SuppressLint("MissingPermission")
    fun startP2pServer() {
        if (p2pServerJob?.isActive == true) {
            Log.d("WifiDirectManager", "P2P server job already active.")
            return
        }

        p2pServerJob = scope.launch(Dispatchers.IO) {
            Log.i("WifiDirectManager", "Starting P2P server...")
            try {
                p2pServerSocket = ServerSocket(AppConstants.P2P_PORT)
                withContext(Dispatchers.Main) {
                    _connectionStatus.value = "Server listening on port ${AppConstants.P2P_PORT}"
                    _statusMessage.value = "P2P Server started. Waiting for connections..."
                }
                
                while (isActive && p2pServerSocket?.isClosed == false) {
                    Log.d("WifiDirectManager", "P2P Server waiting for client connection...")
                    val clientSocket = try {
                        p2pServerSocket?.accept()
                    } catch (e: IOException) {
                        if (isActive) {
                            Log.w("WifiDirectManager", "Server socket accept interrupted: ${e.message}")
                        }
                        null
                    }
                    
                    if (clientSocket != null && isActive) {
                        Log.i("WifiDirectManager", "P2P Server: Client connected from ${clientSocket.remoteSocketAddress}")
                        withContext(Dispatchers.Main) {
                            _connectedSocket.value = clientSocket
                            _connectionStatus.value = "P2P Server Connected to ${clientSocket.remoteSocketAddress}"
                            _statusMessage.value = "P2P Client connected"
                        }
                        break // Accept only one connection for now
                    }
                }
            } catch (e: IOException) {
                if (isActive) {
                    Log.e("WifiDirectManager", "P2P server IOException", e)
                    withContext(Dispatchers.Main) {
                        _statusMessage.value = "P2P Server Error: ${e.message}"
                        _connectionStatus.value = "Server error"
                    }
                }
            } catch (se: SecurityException) {
                Log.e("WifiDirectManager", "SecEx P2P server: ${se.message}", se)
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "P2P Server Permission Error"
                    _connectionStatus.value = "Permission error"
                }
            } catch (e: Exception) {
                Log.e("WifiDirectManager", "Unexpected P2P server error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "P2P Server unexpected error"
                    _connectionStatus.value = "Server error"
                }
            } finally {
                closeP2pServerSocket()
                if (isActive) {
                    Log.i("WifiDirectManager", "P2P server stopped.")
                    withContext(Dispatchers.Main) {
                        _statusMessage.value = "P2P server stopped"
                    }
                }
            }
        }
    }
    
    @SuppressLint("MissingPermission")
    fun connectToP2pOwner(ownerAddress: String?) {
        if (ownerAddress == null) {
            Log.e("WifiDirectManager", "Cannot connect to P2P owner: address is null.")
            _connectionStatus.value = "Error: Owner address null"
            return
        }

        scope.launch(Dispatchers.IO) {
            Log.i("WifiDirectManager", "Connecting to P2P Group Owner at $ownerAddress:${AppConstants.P2P_PORT}")
            withContext(Dispatchers.Main) {
                _connectionStatus.value = "Connecting to P2P Group Owner..."
            }
            
            try {
                p2pClientSocket = Socket()
                p2pClientSocket?.connect(java.net.InetSocketAddress(ownerAddress, AppConstants.P2P_PORT), 10000) // 10 second timeout
                Log.i("WifiDirectManager", "P2P Client: Connected to Group Owner at $ownerAddress:${AppConstants.P2P_PORT}")
                
                withContext(Dispatchers.Main) {
                    _connectedSocket.value = p2pClientSocket
                    _connectionStatus.value = "P2P Client Connected to $ownerAddress"
                    _statusMessage.value = "Connected to P2P Group Owner"
                }
            } catch (e: java.net.SocketTimeoutException) {
                Log.e("WifiDirectManager", "P2P client connection timeout", e)
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "P2P Connect Timeout"
                    _connectionStatus.value = "Connection timeout"
                }
                closeP2pClientSocket()
            } catch (e: IOException) {
                Log.e("WifiDirectManager", "P2P client connection failed", e)
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "P2P Connect Error: ${e.message}"
                    _connectionStatus.value = "Connection failed"
                }
                closeP2pClientSocket()
            } catch (se: SecurityException) {
                Log.e("WifiDirectManager", "SecEx P2P client connect: ${se.message}", se)
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "P2P Connect Permission Error"
                    _connectionStatus.value = "Permission error"
                }
                closeP2pClientSocket()
            } catch (e: Exception) {
                Log.e("WifiDirectManager", "Unexpected P2P client error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "P2P Connect unexpected error"
                    _connectionStatus.value = "Connection error"
                }
                closeP2pClientSocket()
            }
        }
    }
    
    fun disconnect() {
        scope.launch(Dispatchers.IO) {
            Log.i("WifiDirectManager", "Disconnecting P2P...")
            
            p2pServerJob?.cancel()
            p2pServerJob = null
            
            closeP2pSockets()
            
            withContext(Dispatchers.Main) {
                _connectedSocket.value = null
                _connectedDeviceAddress.value = null
                _connectionStatus.value = "Disconnected"
                _statusMessage.value = "P2P disconnected"
            }
            
            Log.i("WifiDirectManager", "P2P disconnected.")
        }
    }
    
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun forceRequestPeers() {
        Log.i("WifiDirectManager", "forceRequestPeers() called.")
        if (wifiP2pManager == null || p2pChannel == null) {
            Log.e("WifiDirectManager", "forceRequestPeers - P2PManager or Channel is null.")
            _statusMessage.value = "P2P System not ready for peer request."
            _isScanning.value = false
            return
        }
        
        val peerListListener = WifiP2pManager.PeerListListener { peers ->
            Log.i("WifiDirectManager", "forceRequestPeers - PeerListListener.onPeersAvailable. System peer list size: [${peers?.deviceList?.size ?: "null"}]")
            onP2pPeersAvailable(peers?.deviceList ?: emptyList())
        }
        
        try {
            wifiP2pManager?.requestPeers(p2pChannel, peerListListener)
        } catch (e: SecurityException) {
            Log.e("WifiDirectManager", "SecurityException during forceRequestPeers: ${e.message}", e)
            _statusMessage.value = "Permission error requesting peers."
        }
    }
    
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun forceRequestPeersForConnection() {
        Log.i("WifiDirectManager", "forceRequestPeersForConnection() called to determine connected device.")
        if (wifiP2pManager == null || p2pChannel == null) {
            Log.e("WifiDirectManager", "forceRequestPeersForConnection - P2PManager or Channel is null.")
            return
        }
        
        val peerListListener = WifiP2pManager.PeerListListener { peers ->
            Log.i("WifiDirectManager", "forceRequestPeersForConnection - PeerListListener.onPeersAvailable. Peer list size: [${peers?.deviceList?.size ?: "null"}]")
            val peerList = peers?.deviceList ?: emptyList()
            
            // When a group is formed, find the connected device (status should be CONNECTED)
            val connectedPeer = peerList.find { it.status == WifiP2pDevice.CONNECTED }
            if (connectedPeer != null) {
                Log.i("WifiDirectManager", "Found connected peer: ${connectedPeer.deviceName} (${connectedPeer.deviceAddress})")
                _connectedDeviceAddress.value = connectedPeer.deviceAddress
            } else {
                Log.w("WifiDirectManager", "No peer with CONNECTED status found in group. Available peers: ${peerList.map { "${it.deviceName}(${getDeviceP2pStatusString(it.status)})" }}")
                // If no CONNECTED peer found, but we're in a group, use the first available peer
                if (peerList.isNotEmpty()) {
                    val firstPeer = peerList.first()
                    Log.i("WifiDirectManager", "Using first available peer as connected device: ${firstPeer.deviceName} (${firstPeer.deviceAddress})")
                    _connectedDeviceAddress.value = firstPeer.deviceAddress
                }
            }
            
            // Also update the discovered peers list
            onP2pPeersAvailable(peerList)
        }
        
        try {
            wifiP2pManager?.requestPeers(p2pChannel, peerListListener)
        } catch (e: SecurityException) {
            Log.e("WifiDirectManager", "SecurityException in forceRequestPeersForConnection: ${e.message}", e)
        }
    }
    
    fun resetWifiDirectSystem() {
        Log.i("WifiDirectManager", "resetWifiDirectSystem CALLED")
        _statusMessage.value = "Resetting Wi-Fi Direct..."
        _isScanning.value = true
        p2pDiscoveryTimeoutJob?.cancel()

        try {
            if (p2pChannel != null && wifiP2pManager != null) {
                wifiP2pManager?.stopPeerDiscovery(p2pChannel, null)
            }
        } catch (e: SecurityException) {
            Log.w("WifiDirectManager", "SecEx stopP2PDisc during reset: ${e.message}", e)
        } catch (e: Exception) {
            Log.w("WifiDirectManager", "Ex stopP2PDisc during reset: ${e.message}")
        }
        
        unregisterP2pReceiver()
        p2pChannel = null
        _discoveredPeers.value = emptyList()
        
        scope.launch {
            delay(500)
            initializeWifiP2p(isReset = true)
            delay(300)
            _statusMessage.value = if (p2pChannel != null) "P2P Reset complete. Try discovery." else "P2P Reset failed to re-init channel."
            _isScanning.value = false
            checkWifiDirectStatus()
        }
    }
    
    fun checkWifiDirectStatus(): String {
        Log.i("WifiDirectManager", "checkWifiDirectStatus CALLED")
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val isWifiEnabled = wifiManager?.isWifiEnabled == true
        val systemLocationEnabled = isLocationEnabled(context)

        val diagnosticInfo = StringBuilder().apply {
            append("P2P Diagnostics:\n")
            append("- Wi-Fi Enabled: $isWifiEnabled\n")
            append("- System Location Enabled: $systemLocationEnabled\n")
            append("- P2P Manager: ${if (wifiP2pManager != null) "OK" else "NULL"}\n")
            append("- P2P Channel: ${if (p2pChannel != null) "OK" else "NULL"}\n")
            append("- P2P Receiver: ${if (p2pBroadcastReceiver != null) "Reg" else "Not Reg"}\n")
            append("Permissions (P2P):\n")
            getWifiDirectPermissions().forEach {
                append("  - $it: ${ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED}\n")
            }
        }
        Log.i("WifiDirectManager", diagnosticInfo.toString())

        var statusMessage = "Diagnostic check done. See logs."
        if (!isWifiEnabled) statusMessage = "Error: Wi-Fi is OFF."
        else if (!systemLocationEnabled) statusMessage = "Error: System Location is OFF. Scans may fail."
        _statusMessage.value = statusMessage
        return diagnosticInfo.toString()
    }
    
    fun fullReset() {
        Log.i("WifiDirectManager", "fullReset CALLED - Full P2P state reset")
        // Try to remove the group at the system level
        try {
            if (wifiP2pManager != null && p2pChannel != null) {
                wifiP2pManager?.removeGroup(p2pChannel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d("WifiDirectManager", "removeGroup SUCCESS during fullReset")
                    }
                    override fun onFailure(reason: Int) {
                        Log.w("WifiDirectManager", "removeGroup FAILURE during fullReset: ${getFailureReasonString(reason)}")
                    }
                })
            }
        } catch (e: Exception) {
            Log.e("WifiDirectManager", "Exception in removeGroup during fullReset: ${e.message}", e)
        }
        
        resetWifiDirectSystem()
        // Clear all state
        _discoveredPeers.value = emptyList()
        _connectedSocket.value = null
        _connectedDeviceAddress.value = null
        _connectionStatus.value = "Disconnected"
        _isScanning.value = false
        _statusMessage.value = "Idle. Ready for P2P operations."
    }
    
    private fun closeP2pClientSocket() {
        try {
            p2pClientSocket?.close()
        } catch (e: IOException) {
            Log.w("WifiDirectManager", "Error closing p2pClientSocket: ${e.message}")
        } finally {
            p2pClientSocket = null
        }
    }
    
    private fun closeP2pServerSocket() {
        try {
            p2pServerSocket?.close()
        } catch (e: IOException) {
            Log.w("WifiDirectManager", "Error closing p2pServerSocket: ${e.message}")
        } finally {
            p2pServerSocket = null
        }
    }
    
    private fun closeP2pSockets() {
        closeP2pClientSocket()
        closeP2pServerSocket()
    }
    
    fun cleanup() {
        Log.d("WifiDirectManager", "cleanup called.")
        unregisterP2pReceiver()
        if (p2pChannel != null && wifiP2pManager != null) {
            try {
                wifiP2pManager?.stopPeerDiscovery(p2pChannel, null)
            } catch (e: SecurityException) {
                Log.w("WifiDirectManager", "SecEx cleanup/stopP2pDisc: ${e.message}")
            } catch (e: Exception) {
                Log.w("WifiDirectManager", "Ex cleanup/stopP2pDisc: ${e.message}")
            }
        }
        closeP2pSockets()
        p2pServerJob?.cancel()
        Log.d("WifiDirectManager", "cleanup finished.")
    }
}
