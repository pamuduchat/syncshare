package com.example.syncshare.viewmodels

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.webkit.MimeTypeMap
import com.example.syncshare.data.SyncHistoryEntry
import com.example.syncshare.management.BluetoothConnectionManager
import com.example.syncshare.management.WifiDirectManager
import com.example.syncshare.protocol.FileMetadata
import com.example.syncshare.protocol.FileTransferInfo
import com.example.syncshare.protocol.MessageType
import com.example.syncshare.protocol.SyncMessage
import com.example.syncshare.sync.ConnectionManager
import com.example.syncshare.sync.FileTransferManager
import com.example.syncshare.sync.SyncHistoryManager
import com.example.syncshare.sync.SyncManager
import com.example.syncshare.sync.TwoWaySyncCoordinator
import com.example.syncshare.ui.model.DeviceTechnology
import com.example.syncshare.ui.model.DisplayableDevice
import com.example.syncshare.utils.computeFileHash
import com.example.syncshare.utils.getBluetoothBondState
import com.example.syncshare.utils.getDeviceP2pStatusString
import com.example.syncshare.viewmodels.ManageFoldersViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

enum class CommunicationTechnology { BLUETOOTH, P2P }

class DevicesViewModel(application: Application) : AndroidViewModel(application) {

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing
    val permissionRequestStatus = mutableStateOf("Idle. Tap a scan button.")
    val displayableDeviceList = mutableStateListOf<DisplayableDevice>()

    // Wi-Fi Direct Manager
    private val wifiDirectManager = WifiDirectManager(
        context = application.applicationContext,
        scope = viewModelScope
    )

    // Bluetooth Connection Manager
    private val bluetoothConnectionManager = BluetoothConnectionManager(
        context = application.applicationContext,
        scope = viewModelScope
    )

    // Expose P2P connection status from WifiDirectManager
    val p2pConnectionStatus: StateFlow<String> = wifiDirectManager.connectionStatus
    
    // Expose Bluetooth connection status from BluetoothConnectionManager
    val bluetoothConnectionStatus: StateFlow<String> = bluetoothConnectionManager.connectionStatus
    val isBluetoothEnabled: StateFlow<Boolean> = bluetoothConnectionManager.isBluetoothEnabled

    // Sync-related managers
    private val syncManager = SyncManager(application.applicationContext, viewModelScope)
    private val fileTransferManager = FileTransferManager(application.applicationContext, viewModelScope)
    private val syncHistoryManager = SyncHistoryManager(application.applicationContext)
    private val connectionManager = ConnectionManager(viewModelScope)
    private val twoWaySyncCoordinator = TwoWaySyncCoordinator(syncManager, viewModelScope)

    // Expose sync-related state
    val fileConflicts: StateFlow<List<SyncManager.FileConflict>> = syncManager.conflicts
    val syncHistory: StateFlow<List<SyncHistoryEntry>> = syncHistoryManager.syncHistory

    private val _activeSyncDestinationUris = MutableStateFlow<Map<String, Uri>>(emptyMap())
    private var defaultIncomingFolderUri: Uri? = null

    // --- Pending Folder Mapping for Incoming Syncs ---
    val pendingFolderMapping = mutableStateOf<String?>(null)
    var pendingSyncMessage: SyncMessage? = null

    private var currentCommunicationTechnology: CommunicationTechnology? = null
    
    // Sync timeout mechanism - reduced to 2 minutes for faster recovery
    private var syncTimeoutJob: Job? = null
    private val SYNC_TIMEOUT_DURATION = 120_000L // 2 minutes

    // --- Expose a function to resolve a conflict ---
    fun resolveFileConflict(conflict: SyncManager.FileConflict, option: SyncManager.ConflictResolutionOption) {
        syncManager.resolveConflict(conflict, option)
        
        Log.d("DevicesViewModel", "Conflict resolved for ${conflict.relativePath}: $option")
        
        // If all conflicts resolved, proceed with sync
        if (fileConflicts.value.isEmpty()) {
            // For now, just log that conflicts are resolved
            // The actual sync resumption logic will be handled by the SyncManager
            Log.d("DevicesViewModel", "All conflicts resolved, sync will continue")
        }
    }
    
    // --- Sync Operations ---
    fun initiateSyncRequest(folderUri: Uri) {
        if (!connectionManager.isReady()) {
            permissionRequestStatus.value = "Error: Not connected for sync."
            val folderNameForHistory = DocumentFile.fromTreeUri(getApplication(), folderUri)?.name ?: folderUri.toString()
            syncHistoryManager.addEntry(SyncHistoryEntry(folderName = folderNameForHistory, status = "Error", details = "Cannot initiate sync: Not connected."))
            return
        }
        
        // Check if we're already processing a sync
        if (syncManager.isActive.value) {
            permissionRequestStatus.value = "Sync already in progress, please wait..."
            return
        }
        
        // Clear any previous file transfer state for new sync session
        fileTransferManager.clearProcessedFiles()
        
        val context = getApplication<Application>().applicationContext
        val folderNameForSyncMessage = DocumentFile.fromTreeUri(context, folderUri)?.name ?: folderUri.toString()

        // Store the mapping from folder name to URI
        val currentMap = _activeSyncDestinationUris.value.toMutableMap()
        currentMap[folderNameForSyncMessage] = folderUri
        _activeSyncDestinationUris.value = currentMap.toMap()

        _isRefreshing.value = true
        
        // Start sync timeout to prevent UI from getting stuck
        startSyncTimeout()
        
        Log.d("DevicesViewModel", "Initiating sync request for folder: $folderNameForSyncMessage")
        syncHistoryManager.addEntry(SyncHistoryEntry(folderName = folderNameForSyncMessage, status = "Initiated (Sender)", details = "Sync request initiated for folder."))
        
        // Use the two-way sync coordinator
        twoWaySyncCoordinator.initiateTwoWaySync(folderUri, folderNameForSyncMessage)
    }

    private fun setupTwoWaySyncCoordinator() {
        twoWaySyncCoordinator.setEventListener(object : TwoWaySyncCoordinator.SyncEventListener {
            override fun onStatusUpdate(status: String) {
                permissionRequestStatus.value = status
            }
            
            override fun onSyncComplete(folderName: String) {
                // Reset all sync state
                resetSyncState()
                permissionRequestStatus.value = "Sync complete for '$folderName'."
                syncHistoryManager.addEntry(SyncHistoryEntry(folderName = folderName, status = "Completed", details = "Two-way sync successfully completed."))
            }
            
            override fun onError(error: String) {
                Log.e("DevicesViewModel", "Sync error: $error")
                // Ensure UI state is reset on any sync error
                resetSyncState()
                permissionRequestStatus.value = error
            }
            
            override fun onSendMessage(message: SyncMessage) {
                sendMessage(message)
                
                // Add fallback for SYNC_COMPLETE messages that might not be received properly
                if (message.type == MessageType.SYNC_COMPLETE) {
                    Log.d("DevicesViewModel", "Sent SYNC_COMPLETE message for ${message.folderName}")
                    // Give a short delay to allow for message processing, then ensure state is reset
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(500) // Brief delay for message to be sent
                        if (_isRefreshing.value) {
                            Log.d("DevicesViewModel", "Ensuring sync state is reset after SYNC_COMPLETE sent")
                            resetSyncState()
                        }
                    }
                }
            }
        })
    }

    fun setDestinationUriForSync(folderName: String, destinationUri: Uri) {
        val currentMap = _activeSyncDestinationUris.value.toMutableMap()
        currentMap[folderName] = destinationUri
        _activeSyncDestinationUris.value = currentMap.toMap()
        val context = getApplication<Application>().applicationContext
        permissionRequestStatus.value = "Set '${DocumentFile.fromTreeUri(context,destinationUri)?.name ?: destinationUri}' as destination for syncs named '$folderName'."
        Log.d("DevicesViewModel", "Destination URI for sync folder '$folderName' set to '$destinationUri'. Current map: ${_activeSyncDestinationUris.value}")
        // add to ManageFoldersViewModel for future syncs 
        manageFoldersViewModel?.addFolder(destinationUri)
    }

    init {
        Log.d("DevicesViewModel", "DevicesViewModel - INIT BLOCK - START")
        
        // Set up connection manager message handlers
        setupMessageHandlers()
        
        // Set up sync manager with connection manager
        syncManager.setCommunicationHandler(null) // Will be set when connection is established
        
        // Set up two-way sync coordinator
        setupTwoWaySyncCoordinator()
        
        // Initialize Bluetooth server for incoming connections
        initializeBluetoothServer()
        
        // Collect from WifiDirectManager state flows
        viewModelScope.launch {
            wifiDirectManager.discoveredPeers.collect { peers ->
                updateDisplayableDeviceList()
            }
        }
        
        viewModelScope.launch {
            wifiDirectManager.statusMessage.collect { message ->
                if (message.contains("P2P") || message.contains("Wi-Fi Direct")) {
                    permissionRequestStatus.value = message
                }
            }
        }
        
        viewModelScope.launch {
            wifiDirectManager.isScanning.collect { scanning ->
                // Only update scanning state if we're not in a sync operation
                if (scanning != _isRefreshing.value && 
                    !syncManager.isActive.value &&
                    (permissionRequestStatus.value.contains("P2P") || 
                     permissionRequestStatus.value.contains("Wi-Fi Direct"))) {
                    _isRefreshing.value = scanning
                }
            }
        }
        
        // Collect from BluetoothConnectionManager state flows
        viewModelScope.launch {
            bluetoothConnectionManager.discoveredDevices.collect { devices ->
                updateDisplayableDeviceList()
            }
        }
        
        viewModelScope.launch {
            bluetoothConnectionManager.statusMessage.collect { message ->
                if (message.contains("Bluetooth") || message.contains("BT")) {
                    Log.d("DevicesViewModel", "Bluetooth status update: $message")
                    permissionRequestStatus.value = message
                }
            }
        }
        
        viewModelScope.launch {
            bluetoothConnectionManager.isScanning.collect { scanning ->
                // Only update scanning state if we're not in a sync operation
                if (scanning != _isRefreshing.value && 
                    !syncManager.isActive.value &&
                    (permissionRequestStatus.value.contains("Bluetooth") || 
                     permissionRequestStatus.value.contains("BT"))) {
                    _isRefreshing.value = scanning
                }
            }
        }
        
        // Handle socket connections
        viewModelScope.launch {
            bluetoothConnectionManager.connectedSocket.collect { socket ->
                if (socket != null) {
                    Log.d("DevicesViewModel", "Bluetooth socket connected, setting up communication")
                    setupCommunicationStreams(socket, CommunicationTechnology.BLUETOOTH)
                    syncHistoryManager.addEntry(SyncHistoryEntry(
                        folderName = "N/A", 
                        status = "Connected", 
                        details = "Bluetooth connection established.", 
                        peerDeviceName = socket.remoteDevice?.address ?: "Unknown"
                    ))
                    updateDisplayableDeviceList()
                } else {
                    Log.d("DevicesViewModel", "Bluetooth socket disconnected")
                    if (currentCommunicationTechnology == CommunicationTechnology.BLUETOOTH) {
                        Log.d("DevicesViewModel", "Cleaning up Bluetooth communication manager")
                        connectionManager.cleanup()
                        currentCommunicationTechnology = null
                    }
                    updateDisplayableDeviceList()
                }
            }
        }
        
        viewModelScope.launch {
            wifiDirectManager.connectedSocket.collect { socket ->
                if (socket != null) {
                    setupCommunicationStreams(socket, CommunicationTechnology.P2P)
                    syncHistoryManager.addEntry(SyncHistoryEntry(
                        folderName = "N/A", 
                        status = "Connected", 
                        details = "P2P connection established.", 
                        peerDeviceName = socket.remoteSocketAddress?.toString() ?: "Unknown"
                    ))
                    updateDisplayableDeviceList()
                } else {
                    if (currentCommunicationTechnology == CommunicationTechnology.P2P) {
                        connectionManager.cleanup()
                        currentCommunicationTechnology = null
                    }
                    updateDisplayableDeviceList()
                }
            }
        }
        
        // Update device list when connection status changes
        viewModelScope.launch {
            bluetoothConnectionManager.connectedDeviceAddress.collect { 
                updateDisplayableDeviceList()
            }
        }
        
        viewModelScope.launch {
            wifiDirectManager.connectedDeviceAddress.collect { 
                updateDisplayableDeviceList()
            }
        }
        
        viewModelScope.launch {
            wifiDirectManager.connectionStatus.collect { 
                updateDisplayableDeviceList()
            }
        }
        
        // Monitor Bluetooth enabled state to restart server when needed
        viewModelScope.launch {
            bluetoothConnectionManager.isBluetoothEnabled.collect { enabled ->
                Log.d("DevicesViewModel", "Bluetooth enabled state changed: $enabled")
                if (enabled) {
                    // Restart server when Bluetooth is enabled
                    initializeBluetoothServer()
                } else {
                    // Reset sync state if Bluetooth is disabled during sync
                    if (currentCommunicationTechnology == CommunicationTechnology.BLUETOOTH && _isRefreshing.value) {
                        Log.w("DevicesViewModel", "Bluetooth disabled during sync, resetting sync state")
                        resetSyncState()
                        permissionRequestStatus.value = "Sync interrupted: Bluetooth disabled"
                    }
                }
            }
        }
        
        Log.d("DevicesViewModel", "DevicesViewModel - INIT BLOCK - END")
    }

    // P2P Methods - Delegated to WifiDirectManager
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    fun startP2pDiscovery() {
        updateDisplayableDeviceList()
        wifiDirectManager.startDiscovery()
    }

    fun connectToP2pDevice(device: com.example.syncshare.ui.model.DisplayableDevice) {
        val p2pDevice = device.originalDeviceObject as? android.net.wifi.p2p.WifiP2pDevice
        if (p2pDevice != null) {
            wifiDirectManager.connectToDevice(p2pDevice)
        } else {
            Log.e("DevicesViewModel", "Cannot connect: Device is not a WifiP2pDevice")
        }
    }

    // Simplified P2P control - only keep essential user-facing functions
    fun resetWifiDirectSystem() = wifiDirectManager.resetWifiDirectSystem()
    
    // P2P disconnection is delegated to WifiDirectManager
    fun disconnectP2p() {
        wifiDirectManager.disconnect()
    }

    // Get diagnostics information
    fun getDiagnosticsInfo(): String = wifiDirectManager.checkWifiDirectStatus()

    // --- Bluetooth Methods - Delegated to BluetoothConnectionManager ---
    fun startBluetoothDiscovery() {
        bluetoothConnectionManager.startDiscovery()
        // Also add paired devices to the list for better connectivity options
        bluetoothConnectionManager.addPairedDevices()
    }
    
    fun stopBluetoothDiscovery() = bluetoothConnectionManager.stopDiscovery()

    fun connectToBluetoothDevice(device: BluetoothDevice) {
        bluetoothConnectionManager.connectToDevice(device)
    }

    fun disconnectBluetooth() {
        bluetoothConnectionManager.disconnect()
    }

    fun stopBluetoothServer() = bluetoothConnectionManager.stopServer()
    
    // Initialize Bluetooth server for incoming connections
    private fun initializeBluetoothServer() {
        Log.d("DevicesViewModel", "Initializing Bluetooth server for incoming connections")
        bluetoothConnectionManager.prepareService()
    }

    // --- Unified List & Helpers ---
    @SuppressLint("MissingPermission")
    private fun updateDisplayableDeviceList() {
        val p2pPeers = wifiDirectManager.discoveredPeers.value
        val connectedP2pDeviceAddress = wifiDirectManager.connectedDeviceAddress.value
        val bluetoothDevices = bluetoothConnectionManager.discoveredDevices.value.toMutableList()
        val connectedBluetoothDeviceAddress = bluetoothConnectionManager.connectedDeviceAddress.value
        val connectedBluetoothSocket = bluetoothConnectionManager.connectedSocket.value
        
        Log.d("DevicesViewModel", "updateDisplayableDeviceList. P2P(manager): ${p2pPeers.size}, BT(manager): ${bluetoothDevices.size}, Connected P2P: $connectedP2pDeviceAddress, Connected BT: $connectedBluetoothDeviceAddress")
        
        // Add connected Bluetooth device to the list if it's not already there
        if (connectedBluetoothSocket != null && connectedBluetoothDeviceAddress != null) {
            val connectedDevice = connectedBluetoothSocket.remoteDevice
            if (!bluetoothDevices.any { it.address == connectedBluetoothDeviceAddress }) {
                Log.d("DevicesViewModel", "Adding connected BT device to discovered list: ${connectedBluetoothDeviceAddress}")
                bluetoothDevices.add(connectedDevice)
            }
        }
        
        val newList = mutableListOf<DisplayableDevice>()
        val context = getApplication<Application>().applicationContext
        var btConnectPermGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        p2pPeers.forEach { p2pDevice ->
            // Check if this device is currently connected
            val isConnected = connectedP2pDeviceAddress != null && p2pDevice.deviceAddress == connectedP2pDeviceAddress
            val statusText = if (isConnected) "Connected" else getDeviceP2pStatusString(p2pDevice.status)
            
            Log.d("DevicesViewModel", "P2P Device: ${p2pDevice.deviceName} (${p2pDevice.deviceAddress}) - Status: $statusText, Connected to: $connectedP2pDeviceAddress, isConnected: $isConnected")
            
            newList.add(DisplayableDevice(
                id = p2pDevice.deviceAddress ?: "p2p_${p2pDevice.hashCode()}", 
                name = p2pDevice.deviceName ?: "Unknown P2P Device", 
                details = "Wi-Fi P2P - $statusText", 
                technology = DeviceTechnology.WIFI_DIRECT, 
                originalDeviceObject = p2pDevice
            ))
        }
        
        bluetoothDevices.forEach { btDevice ->
            var deviceNameStr: String? = "Unknown BT Device"
            var bondStateInt = BluetoothDevice.BOND_NONE
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (btConnectPermGranted) { 
                        deviceNameStr = btDevice.name
                        bondStateInt = btDevice.bondState 
                    } else { 
                        deviceNameStr = "Name N/A (No CONNECT)" 
                    }
                } else {
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED) { 
                        deviceNameStr = btDevice.name
                        bondStateInt = btDevice.bondState 
                    } else { 
                        deviceNameStr = "Name N/A (No BT Perm)" 
                    }
                }
            } catch (e: SecurityException) { 
                deviceNameStr = "Name N/A (SecEx)"
            }

            // Check if this Bluetooth device is currently connected
            val isConnected = connectedBluetoothDeviceAddress != null && btDevice.address == connectedBluetoothDeviceAddress
            val connectionText = if (isConnected) "Connected" else "Paired: ${getBluetoothBondState(bondStateInt)}"
            
            Log.d("DevicesViewModel", "BT Device: ${deviceNameStr} (${btDevice.address}) - Connection: $connectionText, Connected to: $connectedBluetoothDeviceAddress, isConnected: $isConnected")

            newList.add(DisplayableDevice(
                id = btDevice.address, 
                name = deviceNameStr ?: "Unknown BT Device", 
                details = "Bluetooth - $connectionText", 
                technology = DeviceTechnology.BLUETOOTH_CLASSIC, 
                originalDeviceObject = btDevice
            ))
        }
        
        displayableDeviceList.clear()
        displayableDeviceList.addAll(newList.distinctBy { it.id })
        Log.d("DevicesViewModel", "Updated displayableDeviceList. Size: ${displayableDeviceList.size}")
    }

    // Rest of the methods are implemented in WifiDirectManager

    private fun setupMessageHandlers() {
        connectionManager.setMessageHandler { message ->
            handleIncomingMessage(message)
        }
    }

    private fun setupCommunicationStreams(socket: Any, technology: CommunicationTechnology) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d("DevicesViewModel", "Setting up communication for ${technology.name}")
                
                connectionManager.setupCommunication(
                    socket = socket,
                    technology = technology.name,
                    onStatusUpdate = { status -> 
                        permissionRequestStatus.value = status 
                    }
                )
                syncManager.setCommunicationHandler(connectionManager.getCommunicationHandler())
                fileTransferManager.setCommunicationHandler(connectionManager.getCommunicationHandler())
                
                currentCommunicationTechnology = technology
                
                withContext(Dispatchers.Main) {
                    permissionRequestStatus.value = "Communication ready. Ready to sync."
                }
                
            } catch (e: Exception) {
                Log.e("DevicesViewModel", "Error setting up communication", e)
                withContext(Dispatchers.Main) {
                    permissionRequestStatus.value = "Error: Communication setup failed."
                }
                
                if (technology == CommunicationTechnology.BLUETOOTH) {
                    disconnectBluetooth()
                } else {
                    disconnectP2p()
                }
            }
        }
    }

    private fun handleIncomingMessage(message: SyncMessage) {
        viewModelScope.launch(Dispatchers.Main) {
            when (message.type) {
                MessageType.SYNC_REQUEST_METADATA -> {
                    Log.d("DevicesViewModel", "Received SYNC_REQUEST_METADATA for folder: ${message.folderName}")
                    
                    // Check if we're already processing a sync for this folder
                    if (syncManager.isActive.value) {
                        Log.w("DevicesViewModel", "Already processing sync, ignoring duplicate SYNC_REQUEST_METADATA")
                        return@launch
                    }
                    
                    permissionRequestStatus.value = "Received sync request for '${message.folderName}'"
                    syncHistoryManager.addEntry(SyncHistoryEntry(folderName = message.folderName ?: "Unknown", status = "Initiated (Receiver)", details = "Received sync request for folder."))
                    
                    // Clear any previous file transfer state
                    fileTransferManager.clearProcessedFiles()
                    
                    // Start sync timeout for incoming syncs too
                    _isRefreshing.value = true
                    startSyncTimeout()
                    
                    val folderName = message.folderName
                    val localFolderUri = _activeSyncDestinationUris.value[folderName]

                    if (localFolderUri == null) {
                        pendingFolderMapping.value = folderName
                        pendingSyncMessage = message
                        return@launch
                    }

                    // Use the two-way sync coordinator
                    twoWaySyncCoordinator.handleIncomingSyncRequest(message, localFolderUri)
                }
                
                MessageType.SYNC_METADATA_RESPONSE -> {
                    Log.d("DevicesViewModel", "Received SYNC_METADATA_RESPONSE for folder: ${message.folderName}")
                    val folderName = message.folderName
                    val localFolderUri = _activeSyncDestinationUris.value[folderName]
                    
                    if (localFolderUri == null) {
                        Log.e("DevicesViewModel", "Cannot process metadata response: No URI for folder $folderName")
                        return@launch
                    }
                    
                    // Use the two-way sync coordinator
                    twoWaySyncCoordinator.handleMetadataResponse(message, localFolderUri)
                }
                
                MessageType.FILES_REQUESTED_BY_PEER -> {
                    Log.d("DevicesViewModel", "Received FILES_REQUESTED_BY_PEER for folder: ${message.folderName}")
                    val baseFolderName = message.folderName
                    val senderBaseUri = _activeSyncDestinationUris.value[baseFolderName]

                    if (senderBaseUri == null) {
                        Log.e("DevicesViewModel", "Cannot process file request: No URI for folder $baseFolderName")
                        return@launch
                    }

                    permissionRequestStatus.value = "Peer requested ${message.requestedFilePaths?.size ?: 0} files from '${baseFolderName}'."
                    
                    // Use the two-way sync coordinator
                    twoWaySyncCoordinator.handlePeerFileRequest(message, senderBaseUri)
                }
                
                MessageType.FILE_TRANSFER_START -> {
                    Log.d("DevicesViewModel", "Received FILE_TRANSFER_START for ${message.fileTransferInfo?.relativePath ?: "unknown"} in folder ${message.folderName}")
                    
                    // Check if this is a duplicate transfer start
                    val currentTransfer = fileTransferManager.getCurrentTransferState()
                    if (currentTransfer != null && fileTransferManager.hasActiveTransfer()) {
                        val incomingPath = message.fileTransferInfo?.relativePath
                        if (currentTransfer.originalPath == incomingPath || currentTransfer.relativePath == incomingPath) {
                            Log.w("DevicesViewModel", "Ignoring duplicate FILE_TRANSFER_START for ${incomingPath}")
                            return@launch
                        }
                    }
                    
                    fileTransferManager.handleFileTransferStart(
                        message = message,
                        destinationUri = _activeSyncDestinationUris.value[message.folderName] ?: defaultIncomingFolderUri,
                        fileRenameMap = { path -> syncManager.getFileRenameMapping(path) },
                        onStatusUpdate = { status -> permissionRequestStatus.value = status },
                        onError = { error ->
                            sendMessage(SyncMessage(MessageType.ERROR_MESSAGE, folderName = message.folderName, errorMessage = error))
                            syncHistoryManager.addEntry(SyncHistoryEntry(folderName = message.folderName ?: "Unknown", status = "Error", details = error))
                        }
                    )
                }
                
                MessageType.FILE_CHUNK -> {
                    message.fileChunkData?.let { 
                        if (fileTransferManager.hasActiveTransfer()) {
                            fileTransferManager.handleFileChunk(it)
                            // Send acknowledgment for flow control
                            sendMessage(SyncMessage(MessageType.FILE_CHUNK_ACK, folderName = message.folderName))
                        } else {
                            Log.d("DevicesViewModel", "Ignoring FILE_CHUNK message - no active file transfer")
                        }
                    }
                }
                
                MessageType.FILE_CHUNK_ACK -> {
                    // Handle chunk acknowledgment for flow control
                    // Only process if there's an active transfer
                    if (fileTransferManager.hasActiveTransfer()) {
                        Log.d("DevicesViewModel", "Received chunk ACK for folder: ${message.folderName}")
                        // This can be used to implement proper backpressure in the future
                    } else {
                        Log.d("DevicesViewModel", "Ignoring FILE_CHUNK_ACK message - no active file transfer")
                    }
                }
                
                MessageType.FILE_TRANSFER_END -> {
                    fileTransferManager.handleFileTransferEnd(
                        message = message,
                        onStatusUpdate = { status -> permissionRequestStatus.value = status },
                        onComplete = { originalPath ->
                            syncManager.clearFileRenameMapping(originalPath)
                            
                            // Use the coordinator to handle file transfer completion
                            val folderName = message.folderName ?: ""
                            twoWaySyncCoordinator.handleFileTransferComplete(folderName, isIncoming = true)
                            
                            sendMessage(SyncMessage(
                                MessageType.FILE_RECEIVED_ACK, 
                                fileTransferInfo = message.fileTransferInfo
                            ))
                            
                            // Add fallback completion check to ensure sync doesn't get stuck
                            checkAndForceSyncCompletion(folderName)
                        }
                    )
                }
                
                MessageType.FILE_RECEIVED_ACK -> {
                    Log.i("DevicesViewModel", "Peer ACKed file: ${message.fileTransferInfo?.relativePath}")
                    
                    // Additional safety net: if we receive an ACK and the sync seems stuck, 
                    // try to force completion after a brief delay
                    val folderName = message.folderName ?: message.fileTransferInfo?.let { 
                        // Try to extract folder name from the message context
                        _activeSyncDestinationUris.value.entries.firstOrNull()?.key 
                    } ?: ""
                    
                    if (folderName.isNotEmpty()) {
                        checkAndForceSyncCompletion(folderName)
                    }
                }
                
                MessageType.SYNC_COMPLETE -> {
                    Log.i("DevicesViewModel", "SYNC_COMPLETE received for folder: ${message.folderName}")
                    syncHistoryManager.addEntry(SyncHistoryEntry(folderName = message.folderName ?: "Unknown", status = "Completed", details = "Sync successfully completed for folder."))
                    
                    // Reset all sync state
                    resetSyncState()
                    permissionRequestStatus.value = "Sync complete for '${message.folderName}'."
                }
                
                MessageType.ERROR_MESSAGE -> {
                    Log.e("DevicesViewModel", "Received ERROR_MESSAGE: ${message.errorMessage}")
                    syncHistoryManager.addEntry(SyncHistoryEntry(folderName = message.folderName ?: "Associated with error", status = "Error", details = "Error during sync: ${message.errorMessage}"))
                    
                    // Reset all sync state on error
                    resetSyncState()
                    permissionRequestStatus.value = "Error from peer: ${message.errorMessage}"
                }
                
                MessageType.DISCONNECT -> {
                    Log.i("DevicesViewModel", "Received DISCONNECT message")
                    // Reset all sync state when peer disconnects
                    resetSyncState()
                    permissionRequestStatus.value = "Peer disconnected."
                    
                    // Close the communication and disconnect
                    viewModelScope.launch(Dispatchers.IO) {
                        closeCommunicationStreams()
                        disconnectP2p()
                        disconnectBluetooth()
                    }
                }
            }
        }
    }
    private fun closeCommunicationStreams() {
        Log.d("DevicesViewModel", "Closing communication.")
        connectionManager.cleanup()
    }

    private fun sendMessage(message: SyncMessage) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!connectionManager.isReady()) {
                    Log.w("DevicesViewModel", "Connection manager not ready, cannot send message")
                    launch(Dispatchers.Main) { permissionRequestStatus.value = "Not connected." }
                    return@launch
                }
                
                val success = connectionManager.sendMessage(message)
                if (!success) {
                    Log.e("DevicesViewModel", "Failed to send message: Type: ${message.type}")
                    launch(Dispatchers.Main) { 
                        permissionRequestStatus.value = "Error sending data."
                        // Reset sync state if we can't send sync-related messages
                        if (isSyncRelatedMessage(message.type)) {
                            resetSyncState()
                        }
                    }
                    if (message.type != MessageType.ERROR_MESSAGE) { // Avoid infinite error loops
                        syncHistoryManager.addEntry(SyncHistoryEntry(folderName = message.folderName ?: "N/A", status = "Error", details = "Failed to send message type ${message.type}"))
                    }
                }
            } catch (e: Exception) {
                Log.e("DevicesViewModel", "Exception sending message: ${e.message}", e)
                launch(Dispatchers.Main) { 
                    permissionRequestStatus.value = "Error sending data."
                    // Reset sync state if we can't send sync-related messages
                    if (isSyncRelatedMessage(message.type)) {
                        resetSyncState()
                    }
                }
                if (message.type != MessageType.ERROR_MESSAGE) { // Avoid infinite error loops
                    syncHistoryManager.addEntry(SyncHistoryEntry(folderName = message.folderName ?: "N/A", status = "Error", details = "Exception sending message type ${message.type}: ${e.message}"))
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d("DevicesViewModel", "onCleared called.")
        
        // Cancel any sync timeout to prevent memory leaks
        syncTimeoutJob?.cancel()
        syncTimeoutJob = null
        
        wifiDirectManager.cleanup()
        stopBluetoothDiscovery()
        disconnectBluetooth()
        stopBluetoothServer()
        closeCommunicationStreams()
        Log.d("DevicesViewModel", "onCleared finished.")
    }

    fun retryPendingSyncIfNeeded() {
        val msg = pendingSyncMessage
        if (msg != null && pendingFolderMapping.value == null) {
            pendingSyncMessage = null
            handleIncomingMessage(msg)
        }
    }
    // --- Reference to ManageFoldersViewModel for folder registration ---
    private var manageFoldersViewModel: ManageFoldersViewModel? = null
    fun setManageFoldersViewModel(vm: ManageFoldersViewModel) { manageFoldersViewModel = vm }

    fun clearHistory() {
        syncHistoryManager.clearHistory()
    }

    // Helper functions for debugging connection state
    private fun logConnectionState(context: String) {
        Log.d("DevicesViewModel", "$context - Current tech: $currentCommunicationTechnology")
    }

    /**
     * Centralized method to reset all sync state and UI state
     */
    private fun resetSyncState() {
        Log.d("DevicesViewModel", "Resetting sync state and UI state")
        
        // Cancel any sync timeout
        syncTimeoutJob?.cancel()
        syncTimeoutJob = null
        
        _isRefreshing.value = false
        syncManager.clearSyncSession()
    }

    /**
     * Check if a message type is related to sync operations
     */
    private fun isSyncRelatedMessage(messageType: MessageType): Boolean {
        return when (messageType) {
            MessageType.SYNC_REQUEST_METADATA,
            MessageType.SYNC_METADATA_RESPONSE,
            MessageType.FILES_REQUESTED_BY_PEER,
            MessageType.FILE_TRANSFER_START,
            MessageType.FILE_CHUNK,
            MessageType.FILE_CHUNK_ACK,
            MessageType.FILE_TRANSFER_END,
            MessageType.FILE_RECEIVED_ACK,
            MessageType.SYNC_COMPLETE -> true
            else -> false
        }
    }

    /**
     * Start a timeout for sync operations to prevent UI from getting stuck
     */
    private fun startSyncTimeout() {
        // Cancel any existing timeout
        syncTimeoutJob?.cancel()
        
        Log.d("DevicesViewModel", "Starting sync timeout (${SYNC_TIMEOUT_DURATION / 1000} seconds)")
        
        syncTimeoutJob = viewModelScope.launch {
            delay(SYNC_TIMEOUT_DURATION)
            Log.w("DevicesViewModel", "Sync timeout reached, resetting sync state")
            resetSyncState()
            permissionRequestStatus.value = "Sync timeout - operation took too long"
            syncHistoryManager.addEntry(SyncHistoryEntry(
                folderName = "Timeout", 
                status = "Error", 
                details = "Sync operation timed out after ${SYNC_TIMEOUT_DURATION / 1000} seconds"
            ))
        }
    }

    /**
     * Fallback method to ensure sync completes even if session tracking fails
     */
    private fun checkAndForceSyncCompletion(folderName: String) {
        // Check if we have been in a sync state for too long without proper completion
        // This is a fallback to prevent the UI from getting stuck
        val isActive = syncManager.isActive.value
        if (isActive) {
            Log.d("DevicesViewModel", "Checking for forced sync completion for folder: $folderName")
            
            // Give the normal completion logic a moment to work
            viewModelScope.launch {
                kotlinx.coroutines.delay(1000) // Wait 1 second
                
                // If still active after delay, force completion
                if (syncManager.isActive.value) {
                    Log.w("DevicesViewModel", "Forcing sync completion for folder: $folderName - sync session may be stuck")
                    
                    // Send completion message and reset state
                    sendMessage(SyncMessage(MessageType.SYNC_COMPLETE, folderName = folderName))
                    resetSyncState()
                    permissionRequestStatus.value = "Sync completed for '$folderName' (auto-recovered)."
                    syncHistoryManager.addEntry(SyncHistoryEntry(
                        folderName = folderName, 
                        status = "Completed", 
                        details = "Sync completed with fallback completion logic - session tracking may have failed."
                    ))
                }
            }
        }
    }
}