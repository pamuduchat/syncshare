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
import com.example.syncshare.ui.model.DeviceTechnology
import com.example.syncshare.ui.model.DisplayableDevice
import com.example.syncshare.utils.computeFileHash
import com.example.syncshare.utils.getBluetoothBondState
import com.example.syncshare.utils.getDeviceP2pStatusString
import com.example.syncshare.viewmodels.ManageFoldersViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    // Expose sync-related state
    val fileConflicts: StateFlow<List<SyncManager.FileConflict>> = syncManager.conflicts
    val syncHistory: StateFlow<List<SyncHistoryEntry>> = syncHistoryManager.syncHistory

    private val _activeSyncDestinationUris = MutableStateFlow<Map<String, Uri>>(emptyMap())
    private var defaultIncomingFolderUri: Uri? = null

    // --- Pending Folder Mapping for Incoming Syncs ---
    val pendingFolderMapping = mutableStateOf<String?>(null)
    var pendingSyncMessage: SyncMessage? = null

    private var currentCommunicationTechnology: CommunicationTechnology? = null

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
        
        syncManager.initiateSyncRequest(
            folderUri = folderUri,
            folderName = folderNameForSyncMessage,
            onStatusUpdate = { status ->
                permissionRequestStatus.value = status
                syncHistoryManager.addEntry(SyncHistoryEntry(folderName = folderNameForSyncMessage, status = "Initiated (Sender)", details = "Sync request initiated for folder."))
            },
            onError = { error ->
                permissionRequestStatus.value = error
                _isRefreshing.value = false
            }
        )
    }

    // Helper function to send multiple files sequentially
    private fun sendFiles(localFolderUri: Uri, filesToSend: List<String>, folderName: String, expectedIncomingFiles: Int = 0) {
        viewModelScope.launch(Dispatchers.Main) {
            permissionRequestStatus.value = "Sending ${filesToSend.size} files to peer..."
        }
        
        // Start sync session with both send and receive counts
        syncManager.startSyncSession(folderName, filesToSend.size, expectedIncomingFiles, isInitiator = true)
        
        // Send files sequentially to avoid transfer conflicts
        sendFilesSequentially(localFolderUri, filesToSend, folderName, 0)
    }
    
    // Helper function to send files one by one
    private fun sendFilesSequentially(localFolderUri: Uri, filesToSend: List<String>, folderName: String, index: Int) {
        if (index >= filesToSend.size) {
            Log.d("DevicesViewModel", "All files sent for folder: $folderName")
            return
        }
        
        val relativePath = filesToSend[index]
        
        // Check if this file is already being sent to avoid duplicates
        if (!syncManager.isFilePendingSend(relativePath)) {
            syncManager.sendFile(
                baseFolderUri = localFolderUri,
                relativePath = relativePath,
                syncFolderName = folderName,
                onProgress = { status ->
                    permissionRequestStatus.value = status
                },
                onComplete = {
                    // Update sent file progress
                    syncManager.updateSyncSessionProgress(folderName)
                    
                    // Check if sync is complete
                    if (syncManager.checkSyncCompletion()) {
                        sendMessage(SyncMessage(MessageType.SYNC_COMPLETE, folderName = folderName))
                        syncManager.clearSyncSession()
                        _isRefreshing.value = false
                        permissionRequestStatus.value = "Sync complete - all files synchronized."
                    } else {
                        // Add a small delay before sending the next file to prevent receiver overload
                        viewModelScope.launch {
                            kotlinx.coroutines.delay(100) // 100ms delay between files
                            sendFilesSequentially(localFolderUri, filesToSend, folderName, index + 1)
                        }
                    }
                },
                onError = { error ->
                    permissionRequestStatus.value = error
                    syncHistoryManager.addEntry(SyncHistoryEntry(folderName = folderName, status = "Error", details = error))
                    
                    // Continue with next file even if one fails
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(100) // Small delay before retry
                        sendFilesSequentially(localFolderUri, filesToSend, folderName, index + 1)
                    }
                }
            )
        } else {
            Log.d("DevicesViewModel", "Skipping duplicate send for file: $relativePath")
            // Skip to next file
            sendFilesSequentially(localFolderUri, filesToSend, folderName, index + 1)
        }
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
                if (scanning != _isRefreshing.value && 
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
                    permissionRequestStatus.value = message
                }
            }
        }
        
        viewModelScope.launch {
            bluetoothConnectionManager.isScanning.collect { scanning ->
                if (scanning != _isRefreshing.value && 
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
                    setupCommunicationStreams(socket, CommunicationTechnology.BLUETOOTH)
                    syncHistoryManager.addEntry(SyncHistoryEntry(
                        folderName = "N/A", 
                        status = "Connected", 
                        details = "Bluetooth connection established.", 
                        peerDeviceName = socket.remoteDevice?.address ?: "Unknown"
                    ))
                    updateDisplayableDeviceList()
                } else {
                    if (currentCommunicationTechnology == CommunicationTechnology.BLUETOOTH) {
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
    fun startBluetoothDiscovery() = bluetoothConnectionManager.startDiscovery()
    
    fun stopBluetoothDiscovery() = bluetoothConnectionManager.stopDiscovery()

    fun connectToBluetoothDevice(device: BluetoothDevice) {
        bluetoothConnectionManager.connectToDevice(device)
    }

    fun disconnectBluetooth() {
        bluetoothConnectionManager.disconnect()
    }

    fun stopBluetoothServer() = bluetoothConnectionManager.stopServer()

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
                    
                    val folderName = message.folderName
                    val localFolderUri = _activeSyncDestinationUris.value[folderName]

                    if (localFolderUri == null) {
                        pendingFolderMapping.value = folderName
                        pendingSyncMessage = message
                        return@launch
                    }

                    syncManager.handleSyncRequestMetadata(
                        message = message,
                        localFolderUri = localFolderUri,
                        onStatusUpdate = { status -> permissionRequestStatus.value = status },
                        onSendFilesRequest = { filesToRequest ->
                            sendMessage(SyncMessage(
                                MessageType.FILES_REQUESTED_BY_PEER, 
                                folderName = folderName, 
                                requestedFilePaths = filesToRequest
                            ))
                        },
                        onSendFiles = { filesToSend ->
                            // RECEIVER SIDE: Send files immediately, but coordinate with protocol
                            Log.d("DevicesViewModel", "RECEIVER: Sending ${filesToSend.size} files after metadata exchange")
                            if (filesToSend.isNotEmpty()) {
                                // The receiver doesn't know how many files it will receive yet
                                // That will be determined when FILES_REQUESTED_BY_PEER is received
                                sendFiles(localFolderUri, filesToSend, folderName ?: "", expectedIncomingFiles = 0)
                            }
                        },
                        onSyncComplete = {
                            sendMessage(SyncMessage(MessageType.SYNC_COMPLETE, folderName = folderName))
                            _isRefreshing.value = false
                            permissionRequestStatus.value = "Sync complete - folders are synchronized."
                        }
                    )
                }
                
                MessageType.SYNC_METADATA_RESPONSE -> {
                    Log.d("DevicesViewModel", "Received SYNC_METADATA_RESPONSE for folder: ${message.folderName}")
                    val folderName = message.folderName
                    val localFolderUri = _activeSyncDestinationUris.value[folderName]
                    
                    if (localFolderUri == null) {
                        Log.e("DevicesViewModel", "Cannot process metadata response: No URI for folder $folderName")
                        return@launch
                    }
                    
                    syncManager.handleSyncMetadataResponse(
                        message = message,
                        localFolderUri = localFolderUri,
                        onSendFiles = { filesToSend ->
                            Log.d("DevicesViewModel", "INITIATOR: Sending ${filesToSend.size} files after metadata response")
                            sendFiles(localFolderUri, filesToSend, folderName ?: "")
                        },
                        onSendFilesRequest = { filesToRequest ->
                            Log.d("DevicesViewModel", "INITIATOR: Requesting ${filesToRequest.size} files from peer")
                            // Add expected incoming files to sync session
                            syncManager.addExpectedIncomingFiles(filesToRequest)
                            sendMessage(SyncMessage(
                                MessageType.FILES_REQUESTED_BY_PEER,
                                folderName = folderName,
                                requestedFilePaths = filesToRequest
                            ))
                        },
                        onStatusUpdate = { status -> permissionRequestStatus.value = status }
                    )
                }
                
                MessageType.FILES_REQUESTED_BY_PEER -> {
                    Log.d("DevicesViewModel", "Received FILES_REQUESTED_BY_PEER for folder: ${message.folderName}")
                    val requestedPaths = message.requestedFilePaths
                    val baseFolderName = message.folderName
                    val senderBaseUri = _activeSyncDestinationUris.value[baseFolderName]

                    if (senderBaseUri == null) {
                        Log.e("DevicesViewModel", "Sender URI for folder '${baseFolderName}' not found")
                        sendMessage(SyncMessage(MessageType.ERROR_MESSAGE, folderName = baseFolderName, errorMessage = "Source folder '${baseFolderName}' not found/mappable on sender."))
                        syncHistoryManager.addEntry(SyncHistoryEntry(folderName = baseFolderName ?: "Unknown", status = "Error", details = "Source folder not found/mappable on sender."))
                        return@launch
                    }

                    permissionRequestStatus.value = "Peer requested ${requestedPaths?.size ?: 0} files from '${baseFolderName}'."
                    
                    if (requestedPaths.isNullOrEmpty()) {
                        // No files requested, check if sync is complete
                        if (syncManager.checkSyncCompletion()) {
                            sendMessage(SyncMessage(MessageType.SYNC_COMPLETE, folderName = message.folderName))
                            syncManager.clearSyncSession()
                            _isRefreshing.value = false
                            permissionRequestStatus.value = "Sync complete - all files synchronized."
                        }
                    } else {
                        // Just send the files, no need to track ACKs as received files
                        sendFiles(senderBaseUri, requestedPaths, baseFolderName ?: "", expectedIncomingFiles = 0)
                    }
                }
                
                MessageType.FILE_TRANSFER_START -> {
                    Log.d("DevicesViewModel", "Received FILE_TRANSFER_START for ${message.fileTransferInfo?.relativePath ?: "unknown"} in folder ${message.folderName}")
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
                        fileTransferManager.handleFileChunk(it)
                    }
                }
                
                MessageType.FILE_TRANSFER_END -> {
                    fileTransferManager.handleFileTransferEnd(
                        message = message,
                        onStatusUpdate = { status -> permissionRequestStatus.value = status },
                        onComplete = { originalPath ->
                            syncManager.clearFileRenameMapping(originalPath)
                            
                            // Update received file progress
                            val folderName = message.folderName ?: ""
                            if (syncManager.updateSyncSessionReceivedProgress(folderName)) {
                                Log.d("DevicesViewModel", "File received, checking sync completion")
                                if (syncManager.checkSyncCompletion()) {
                                    Log.d("DevicesViewModel", "Sync completion detected after file receive")
                                    sendMessage(SyncMessage(MessageType.SYNC_COMPLETE, folderName = folderName))
                                    syncManager.clearSyncSession()
                                    _isRefreshing.value = false
                                    permissionRequestStatus.value = "Sync complete - all files synchronized."
                                }
                            }
                            
                            sendMessage(SyncMessage(
                                MessageType.FILE_RECEIVED_ACK, 
                                fileTransferInfo = message.fileTransferInfo
                            ))
                        }
                    )
                }
                
                MessageType.FILE_RECEIVED_ACK -> {
                    Log.i("DevicesViewModel", "Peer ACKed file: ${message.fileTransferInfo?.relativePath}")
                    // ACK is just a confirmation, no additional tracking needed
                }
                
                MessageType.SYNC_COMPLETE -> {
                    Log.i("DevicesViewModel", "SYNC_COMPLETE received for folder: ${message.folderName}")
                    syncHistoryManager.addEntry(SyncHistoryEntry(folderName = message.folderName ?: "Unknown", status = "Completed", details = "Sync successfully completed for folder."))
                    
                    // Clear sync state
                    syncManager.clearSyncSession()
                    
                    // Update UI
                    _isRefreshing.value = false
                    permissionRequestStatus.value = "Sync complete for '${message.folderName}'."
                }
                
                MessageType.ERROR_MESSAGE -> {
                    Log.e("DevicesViewModel", "Received ERROR_MESSAGE: ${message.errorMessage}")
                    syncHistoryManager.addEntry(SyncHistoryEntry(folderName = message.folderName ?: "Associated with error", status = "Error", details = "Error during sync: ${message.errorMessage}"))
                    
                    // Clear sync state on error
                    syncManager.clearSyncSession()
                    _isRefreshing.value = false
                    permissionRequestStatus.value = "Error from peer: ${message.errorMessage}"
                }
                
                MessageType.DISCONNECT -> {
                    // Clear all sync state when peer disconnects
                    syncManager.clearSyncSession()
                    permissionRequestStatus.value = "Peer disconnected."
                    
                    // Close the communication and disconnect
                    viewModelScope.launch(Dispatchers.IO) {
                        closeCommunicationStreams()
                        disconnectP2p()
                        disconnectBluetooth()
                        withContext(Dispatchers.Main) {
                            _isRefreshing.value = false
                        }
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
                    launch(Dispatchers.Main) { permissionRequestStatus.value = "Error sending data." }
                    if (message.type != MessageType.ERROR_MESSAGE) { // Avoid infinite error loops
                        syncHistoryManager.addEntry(SyncHistoryEntry(folderName = message.folderName ?: "N/A", status = "Error", details = "Failed to send message type ${message.type}"))
                    }
                }
            } catch (e: Exception) {
                Log.e("DevicesViewModel", "Exception sending message: ${e.message}", e)
                launch(Dispatchers.Main) { permissionRequestStatus.value = "Error sending data." }
                if (message.type != MessageType.ERROR_MESSAGE) { // Avoid infinite error loops
                    syncHistoryManager.addEntry(SyncHistoryEntry(folderName = message.folderName ?: "N/A", status = "Error", details = "Exception sending message type ${message.type}: ${e.message}"))
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d("DevicesViewModel", "onCleared called.")
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
}