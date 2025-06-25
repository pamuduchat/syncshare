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
import com.example.syncshare.data.SyncHistoryEntry
import com.example.syncshare.communication.CommunicationHandler
import com.example.syncshare.management.BluetoothConnectionManager
import com.example.syncshare.management.WifiDirectManager
import com.example.syncshare.protocol.FileMetadata
import com.example.syncshare.protocol.FileTransferInfo
import com.example.syncshare.protocol.MessageType
import com.example.syncshare.protocol.SyncMessage
import com.example.syncshare.ui.model.DeviceTechnology
import com.example.syncshare.ui.model.DisplayableDevice
import com.example.syncshare.utils.getBluetoothBondState
import com.example.syncshare.utils.getDeviceP2pStatusString
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
import android.webkit.MimeTypeMap
import android.content.SharedPreferences
import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.example.syncshare.utils.computeFileHash

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

    private val _activeSyncDestinationUris = MutableStateFlow<Map<String, Uri>>(emptyMap())
    private var defaultIncomingFolderUri: Uri? = null

    // --- Sync History ---
    private val _syncHistory = mutableStateListOf<SyncHistoryEntry>()
    val syncHistory: List<SyncHistoryEntry> = _syncHistory

    // --- Pending Folder Mapping for Incoming Syncs ---
    val pendingFolderMapping = mutableStateOf<String?>(null)
    var pendingSyncMessage: SyncMessage? = null

    private var communicationHandler: CommunicationHandler? = null
    private var messageListenerJob: Job? = null
    private var currentCommunicationTechnology: CommunicationTechnology? = null

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
        
        // Store the resolution for processing
        val currentResolutions = conflictResolutions.toMutableMap()
        currentResolutions[conflict.relativePath] = option
        conflictResolutions = currentResolutions
        
        Log.d("DevicesViewModel", "Conflict resolved for ${conflict.relativePath}: $option")
        
        // If all conflicts resolved, process the resolutions
        if (_fileConflicts.value.isEmpty()) {
            processConflictResolutions(conflict.folderName)
        }
    }
    
    // Store conflict resolutions temporarily
    private var conflictResolutions = mapOf<String, ConflictResolutionOption>()
    
    // Store pending sync state for resuming after conflict resolution
    private data class PendingSyncState(
        val folderName: String,
        val localFolderUri: Uri,
        val filesToRequest: List<String>,
        val filesToSend: List<String>
    )
    private var pendingSyncState: PendingSyncState? = null
    
    // Store sync session state for tracking file send completion
    private data class SyncSession(
        val folderName: String,
        val totalFilesToSend: Int,
        val filesSentSuccessfully: Int = 0
    )
    private var currentSyncSession: SyncSession? = null
    
    private fun processConflictResolutions(folderName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val filesToRequest = mutableListOf<String>()
            val localFolderUri = _activeSyncDestinationUris.value[folderName]
            
            if (localFolderUri == null) {
                Log.e("DevicesViewModel", "Cannot process conflicts: No URI for folder $folderName")
                return@launch
            }
            
            // Process each resolution
            for ((relativePath, resolution) in conflictResolutions) {
                when (resolution) {
                    ConflictResolutionOption.KEEP_LOCAL -> {
                        // Do nothing, keep local file as is
                        Log.d("DevicesViewModel", "Keeping local version of $relativePath")
                    }
                    ConflictResolutionOption.USE_REMOTE -> {
                        // Request the remote file to overwrite local
                        filesToRequest.add(relativePath)
                        Log.d("DevicesViewModel", "Will request remote version of $relativePath")
                    }
                    ConflictResolutionOption.KEEP_BOTH -> {
                        // For KEEP_BOTH, we request the file normally but mark it for renaming
                        filesToRequest.add(relativePath)
                        
                        // Generate the rename target
                        val extension = relativePath.substringAfterLast('.', "")
                        val nameWithoutExtension = relativePath.substringBeforeLast('.', relativePath)
                        val newName = if (extension.isNotEmpty()) {
                            "${nameWithoutExtension}_remote.$extension"
                        } else {
                            "${relativePath}_remote"
                        }
                        
                        // Store the rename mapping for later use
                        fileRenameMap[relativePath] = newName
                        Log.d("DevicesViewModel", "Will request remote version of $relativePath and save as $newName")
                    }
                    ConflictResolutionOption.SKIP -> {
                        // Do nothing with this file
                        Log.d("DevicesViewModel", "Skipping $relativePath")
                    }
                }
            }
            
            // Get the pending sync state (files that need to be sent/received)
            val syncState = pendingSyncState
            if (syncState != null) {
                // Add the original non-conflicting files to the request list
                filesToRequest.addAll(syncState.filesToRequest)
                
                Log.d("DevicesViewModel", "Resuming two-way sync after conflict resolution:")
                Log.d("DevicesViewModel", "- Requesting ${filesToRequest.size} files (${conflictResolutions.size} from conflicts + ${syncState.filesToRequest.size} non-conflicting)")
                Log.d("DevicesViewModel", "- Sending ${syncState.filesToSend.size} files")
                
                // Request all files we need (conflict resolutions + non-conflicting)
                if (filesToRequest.isNotEmpty()) {
                    sendMessage(SyncMessage(MessageType.FILES_REQUESTED_BY_PEER, folderName = folderName, requestedFilePaths = filesToRequest))
                }
                
                // Send files that remote needs from us
                if (syncState.filesToSend.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        permissionRequestStatus.value = "Sending ${syncState.filesToSend.size} files to peer..."
                    }
                    
                    // Send each file
                    syncState.filesToSend.forEach { relativePath ->
                        if (currentCoroutineContext().isActive) {
                            sendFile(syncState.localFolderUri, relativePath, folderName)
                        }
                    }
                }
                
                // If neither device needs files after conflict resolution, sync is complete
                if (filesToRequest.isEmpty() && syncState.filesToSend.isEmpty()) {
                    Log.d("DevicesViewModel", "No files to sync after conflict resolution - folders are synchronized")
                    sendMessage(SyncMessage(MessageType.SYNC_COMPLETE, folderName = folderName))
                    withContext(Dispatchers.Main) {
                        _isRefreshing.value = false
                        permissionRequestStatus.value = "Sync complete - conflicts resolved, folders synchronized."
                    }
                }
            } else {
                Log.e("DevicesViewModel", "No pending sync state found after conflict resolution")
                // Fallback: just request the conflict resolution files
                if (filesToRequest.isNotEmpty()) {
                    Log.d("DevicesViewModel", "Requesting ${filesToRequest.size} files after conflict resolution")
                    sendMessage(SyncMessage(MessageType.FILES_REQUESTED_BY_PEER, folderName = folderName, requestedFilePaths = filesToRequest))
                } else {
                    // No files to request, sync is complete
                    withContext(Dispatchers.Main) {
                        _isRefreshing.value = false
                        permissionRequestStatus.value = "Conflict resolution complete. No files to sync."
                    }
                }
            }
            
            // Clear the resolutions and pending state
            conflictResolutions = emptyMap()
            pendingSyncState = null
        }
    }
    
    // Map to track file renames for KEEP_BOTH option
    private var fileRenameMap = mutableMapOf<String, String>()
    
    // Clear all conflicts (for testing or if user wants to cancel conflict resolution)
    fun clearAllConflicts() {
        _fileConflicts.value = emptyList()
        conflictResolutions = emptyMap()
        fileRenameMap.clear()
        pendingSyncState = null
        currentSyncSession = null
        pendingFileSends.clear()
        _isRefreshing.value = false
        permissionRequestStatus.value = "Conflicts cleared. Ready to sync."
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
        loadHistory()
        
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
        
        viewModelScope.launch {
            bluetoothConnectionManager.connectedSocket.collect { socket ->
                logConnectionState("BT Socket collect: ${socket != null}")
                if (socket != null) {
                    // Run socket setup on IO thread to avoid NetworkOnMainThreadException
                    launch(Dispatchers.IO) {
                        setupCommunicationStreams(socket, CommunicationTechnology.BLUETOOTH)
                    }
                    val peerAddress = socket.remoteDevice?.address ?: "Unknown"
                    _syncHistory.add(0, SyncHistoryEntry(
                        folderName = "N/A", 
                        status = "Connected", 
                        details = "Bluetooth connection established.", 
                        peerDeviceName = peerAddress
                    ))
                    persistHistory()
                    // Update device list to show connected status
                    updateDisplayableDeviceList()
                } else {
                    // Bluetooth connection lost - only clear if we're using Bluetooth
                    if (currentCommunicationTechnology == CommunicationTechnology.BLUETOOTH) {
                        closeCommunicationStreams()
                        currentCommunicationTechnology = null
                        logConnectionState("BT disconnected and cleared")
                    }
                    // Update device list to reflect disconnected status
                    updateDisplayableDeviceList()
                }
            }
        }
        
        viewModelScope.launch {
            bluetoothConnectionManager.connectedDeviceAddress.collect { deviceAddress ->
                // Update device list when Bluetooth connection status changes
                updateDisplayableDeviceList()
            }
        }
        
        viewModelScope.launch {
            wifiDirectManager.connectedSocket.collect { socket ->
                logConnectionState("P2P Socket collect: ${socket != null}")
                if (socket != null) {
                    // Run socket setup on IO thread to avoid NetworkOnMainThreadException
                    launch(Dispatchers.IO) {
                        setupCommunicationStreams(socket, CommunicationTechnology.P2P)
                    }
                    val peerAddress = socket.remoteSocketAddress?.toString() ?: "Unknown"
                    _syncHistory.add(0, SyncHistoryEntry(
                        folderName = "N/A", 
                        status = "Connected", 
                        details = "P2P connection established.", 
                        peerDeviceName = peerAddress
                    ))
                    persistHistory()
                    // Update device list when connection is established
                    updateDisplayableDeviceList()
                } else {
                    // P2P connection lost - only clear if we're using P2P
                    if (currentCommunicationTechnology == CommunicationTechnology.P2P) {
                        closeCommunicationStreams()
                        currentCommunicationTechnology = null
                        logConnectionState("P2P disconnected and cleared")
                    }
                    // Update device list when connection is lost
                    updateDisplayableDeviceList()
                }
            }
        }
        
        viewModelScope.launch {
            wifiDirectManager.connectedDeviceAddress.collect { deviceAddress ->
                // Update device list whenever connected device address changes
                updateDisplayableDeviceList()
            }
        }
        
        viewModelScope.launch {
            wifiDirectManager.connectionStatus.collect { status ->
                // Update device list when P2P connection status changes
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
    
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    fun restartP2pDiscovery() {
        updateDisplayableDeviceList()
        wifiDirectManager.restartDiscovery()
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
    
    // Helper function to determine if P2P is connected
    fun isP2pConnected(): Boolean = wifiDirectManager.isConnected()

    // Helper function to determine if Bluetooth is connected
    fun isBluetoothConnected(): Boolean = bluetoothConnectionManager.connectedSocket.value != null

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

    fun startBluetoothServer() = bluetoothConnectionManager.startServer()

    fun stopBluetoothServer() = bluetoothConnectionManager.stopServer()

    fun prepareBluetoothService() = bluetoothConnectionManager.prepareService()


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

    private suspend fun setupCommunicationStreams(socket: Any, technology: CommunicationTechnology) {
        Log.d("DevicesViewModel", "Setting up communication handler for ${technology.name} socket.")
        try {
            // Clean up any existing handler
            communicationHandler?.cleanup()
            messageListenerJob?.cancel()
            
            Log.d("DevicesViewModel", "Setting up communication handler for ${technology.name}")
            
            // Create new communication handler
            communicationHandler = CommunicationHandler(socket, viewModelScope)
            
            // Initialize the handler
            val initialized = communicationHandler!!.initialize()
            if (!initialized) {
                throw IOException("Failed to initialize communication handler")
            }
            
            Log.i("DevicesViewModel", "Communication handler established for ${technology.name}.")
            
            // Set current communication technology
            currentCommunicationTechnology = technology
            
            // Update UI on Main thread
            withContext(Dispatchers.Main) {
                permissionRequestStatus.value = "Communication ready. Ready to sync."
            }
            
            startListeningForMessages()

        } catch (e: IOException) {
            Log.e("DevicesViewModel", "Error setting up communication handler for ${technology.name}: ", e)
            
            // Update UI on Main thread
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

    private fun startListeningForMessages() {
        messageListenerJob?.cancel()
        messageListenerJob = viewModelScope.launch {
            Log.i("DevicesViewModel", "Starting to listen for incoming SyncMessages...")
            
            communicationHandler?.incomingMessages?.collect { message ->
                Log.d("DevicesViewModel", "Received message: Type: ${message.type}, Folder: ${message.folderName}")
                
                // Handle connection error messages
                if (message.type == MessageType.ERROR_MESSAGE && message.errorMessage?.contains("Connection lost") == true) {
                    withContext(Dispatchers.Main) {
                        permissionRequestStatus.value = message.errorMessage
                    }
                    
                    // Close communication and notify connection managers of the broken connection
                    closeCommunicationStreams()
                    
                    // Disconnect from both technologies to ensure clean state
                    if (currentCommunicationTechnology == CommunicationTechnology.BLUETOOTH) {
                        bluetoothConnectionManager.notifyConnectionLost()
                        disconnectBluetooth()
                    } else if (currentCommunicationTechnology == CommunicationTechnology.P2P) {
                        disconnectP2p()
                    }
                    currentCommunicationTechnology = null
                    return@collect
                }
                
                handleIncomingMessage(message)
            }
            
            Log.i("DevicesViewModel", "Stopped listening for messages.")
        }
    }

    private fun p2pIsConnected(): Boolean {
        return wifiDirectManager.connectedSocket.value != null
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
                        val filesToSend = mutableListOf<String>()
                        val localFileMap = localFiles.associateBy { it.relativePath }
                        val remoteFileMap = remoteFiles.associateBy { it.relativePath }
                        val conflicts = mutableListOf<FileConflict>()

                        // Check files that exist on remote but not local (need to request)
                        for ((remotePath, remoteMeta) in remoteFileMap) {
                            val localMeta = localFileMap[remotePath]
                            if (localMeta == null) {
                                // File exists on remote but not local, request it
                                filesToRequest.add(remotePath)
                            } else {
                                // File exists on both, check if they're actually different (content-wise)
                                val sizesDifferent = remoteMeta.size != localMeta.size
                                val hashesDifferent = remoteMeta.hash != null && localMeta.hash != null && remoteMeta.hash != localMeta.hash
                                val modifiedTimesDifferent = remoteMeta.lastModified != localMeta.lastModified
                                
                                // A conflict occurs only if the content is actually different
                                // (different sizes OR different hashes when both are available)
                                val hasContentDifference = sizesDifferent || hashesDifferent
                                
                                if (hasContentDifference) {
                                    // Files have different content - this is a true conflict
                                    Log.d("DevicesViewModel", "Content conflict detected for $remotePath: size(${localMeta.size} vs ${remoteMeta.size}), hash(${localMeta.hash} vs ${remoteMeta.hash}), modified(${localMeta.lastModified} vs ${remoteMeta.lastModified})")
                                    conflicts.add(FileConflict(folderName ?: "", remotePath, localMeta, remoteMeta))
                                } else {
                                    // Files have same content (same size and hash if available)
                                    // Even if modified times differ, this is not a conflict
                                    if (modifiedTimesDifferent) {
                                        Log.d("DevicesViewModel", "File $remotePath has same content but different modified time (${localMeta.lastModified} vs ${remoteMeta.lastModified}), no sync needed")
                                    } else {
                                        Log.d("DevicesViewModel", "File $remotePath is identical on both devices, skipping")
                                    }
                                }
                            }
                        }
                        
                        // Check files that exist locally but not on remote (need to send)
                        for ((localPath, localMeta) in localFileMap) {
                            if (!remoteFileMap.containsKey(localPath)) {
                                // File exists locally but not on remote, we should send it
                                filesToSend.add(localPath)
                            }
                        }
                        
                        // --- Pause sync and show conflicts if any ---
                        if (conflicts.isNotEmpty()) {
                            _fileConflicts.value = conflicts
                            
                            // Store the pending sync state for resuming after conflict resolution
                            pendingSyncState = PendingSyncState(
                                folderName = folderName ?: "",
                                localFolderUri = localFolderUri,
                                filesToRequest = filesToRequest,
                                filesToSend = filesToSend
                            )
                            
                            Log.d("DevicesViewModel", "Conflicts detected. Stored pending sync state: ${filesToRequest.size} files to request, ${filesToSend.size} files to send")
                            
                            withContext(Dispatchers.Main) {
                                _isRefreshing.value = false
                                permissionRequestStatus.value = "File conflicts detected. Please resolve." 
                            }
                            return@launch
                        }
                        
                        // --- Two-way sync: Request files we need AND send files they need ---
                        Log.d("DevicesViewModel", "Two-way sync for folder '${message.folderName}': Requesting ${filesToRequest.size} files, Sending ${filesToSend.size} files")
                        
                        // First, request files that we need from remote
                        if (filesToRequest.isNotEmpty()) {
                            Log.d("DevicesViewModel", "Requesting files: $filesToRequest")
                            sendMessage(SyncMessage(MessageType.FILES_REQUESTED_BY_PEER, folderName = message.folderName, requestedFilePaths = filesToRequest))
                        }
                        
                        // Then, send files that remote needs from us
                        if (filesToSend.isNotEmpty()) {
                            Log.d("DevicesViewModel", "Sending files: $filesToSend")
                            withContext(Dispatchers.Main) {
                                permissionRequestStatus.value = "Sending ${filesToSend.size} files to peer..."
                            }
                            
                            // Send each file
                            filesToSend.forEach { relativePath ->
                                if (currentCoroutineContext().isActive) {
                                    sendFile(localFolderUri, relativePath, folderName ?: "")
                                }
                            }
                        }
                        
                        // If neither device needs files, sync is complete
                        if (filesToRequest.isEmpty() && filesToSend.isEmpty()) {
                            Log.d("DevicesViewModel", "No files to sync - folders are already synchronized")
                            sendMessage(SyncMessage(MessageType.SYNC_COMPLETE, folderName = message.folderName))
                            withContext(Dispatchers.Main) {
                                _isRefreshing.value = false
                                permissionRequestStatus.value = "Sync complete - folders are synchronized."
                            }
                        }
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
                    
                    if (requestedPaths.isNullOrEmpty()){
                        // No files to send, sync is complete
                        sendMessage(SyncMessage(MessageType.SYNC_COMPLETE, folderName = message.folderName))
                        _isRefreshing.value = false
                        permissionRequestStatus.value = "Sync complete (no files to send)."
                    } else {
                        // Store the expected completion for this sync session
                        currentSyncSession = SyncSession(baseFolderName ?: "", requestedPaths.size)
                        Log.d("DevicesViewModel", "Starting to send ${requestedPaths.size} files for sync session")
                        
                        requestedPaths.forEach { relativePath ->
                            launch(Dispatchers.IO) {
                                sendFile(senderBaseUri, relativePath, baseFolderName ?: "")
                            }
                        }
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

                    // Check if this file should be renamed (KEEP_BOTH option)
                    val finalPath = fileRenameMap[info.relativePath] ?: info.relativePath
                    val isRenamed = finalPath != info.relativePath
                    
                    if (isRenamed) {
                        Log.i("DevicesViewModel", "File ${info.relativePath} will be saved as $finalPath (KEEP_BOTH conflict resolution)")
                    }

                    Log.i("DevicesViewModel", "Receiving file: ${info.relativePath} -> $finalPath, Size: ${info.fileSize} into folder '$folderName'")
                    permissionRequestStatus.value = if (isRenamed) {
                        "Receiving: ${info.relativePath} as $finalPath for $folderName"
                    } else {
                        "Receiving: $finalPath for $folderName"
                    }
                    
                    currentReceivingFile = FileTransferState(
                        folderName = folderName,
                        relativePath = finalPath, // Use the final path (renamed if needed)
                        totalSize = info.fileSize,
                        destinationBaseUri = destinationUri,
                        originalPath = info.relativePath // Store original for tracking
                    )
                    
                    val displayPath = if (isRenamed) "$finalPath (renamed from ${info.relativePath})" else finalPath
                    _syncHistory.add(0, SyncHistoryEntry(folderName = folderName, status = "File Transfer", details = "Receiving file: $displayPath (${info.fileSize} bytes)"))
                    persistHistory()
                }
                MessageType.FILE_CHUNK -> { message.fileChunkData?.let { appendFileChunk(it) } }
                MessageType.FILE_TRANSFER_END -> {
                    val info = message.fileTransferInfo
                    Log.i("DevicesViewModel", "File transfer finished for: ${info?.relativePath}")
                    
                    val state = currentReceivingFile
                    val displayPath = if (state != null && state.originalPath != state.relativePath) {
                        "${state.relativePath} (renamed from ${state.originalPath})"
                    } else {
                        info?.relativePath ?: "unknown file"
                    }
                    
                    permissionRequestStatus.value = "Received: $displayPath"
                    finalizeReceivedFile()
                    
                    // Clean up rename map if this was a renamed file
                    if (state != null && fileRenameMap.containsKey(state.originalPath)) {
                        fileRenameMap.remove(state.originalPath)
                        Log.d("DevicesViewModel", "Cleaned up rename mapping for ${state.originalPath}")
                    }
                    
                    info?.let { 
                        sendMessage(SyncMessage(MessageType.FILE_RECEIVED_ACK, fileTransferInfo = FileTransferInfo(it.relativePath, 0L)))
                    }
                    currentReceivingFile = null
                }
                MessageType.FILE_RECEIVED_ACK -> { Log.i("DevicesViewModel", "Peer ACKed file: ${message.fileTransferInfo?.relativePath}") }
                MessageType.SYNC_COMPLETE -> {
                    Log.i("DevicesViewModel", "SYNC_COMPLETE received for folder: ${message.folderName}")
                    _syncHistory.add(0, SyncHistoryEntry(folderName = message.folderName ?: "Unknown", status = "Completed", details = "Sync successfully completed for folder."))
                    persistHistory()
                    
                    // Clear sync state
                    currentSyncSession = null
                    pendingFileSends.clear()
                    syncMetadataSentForSession = false
                    
                    // Update UI
                    _isRefreshing.value = false
                    permissionRequestStatus.value = "Sync complete for '${message.folderName}'."
                }
                MessageType.ERROR_MESSAGE -> {
                    Log.e("DevicesViewModel", "Received ERROR_MESSAGE: ${message.errorMessage}")
                    _syncHistory.add(0, SyncHistoryEntry(folderName = message.folderName ?: "Associated with error", status = "Error", details = "Error during sync: ${message.errorMessage}"))
                    persistHistory()
                    
                    // Clear sync state on error
                    currentSyncSession = null
                    pendingFileSends.clear()
                    _isRefreshing.value = false
                    permissionRequestStatus.value = "Error from peer: ${message.errorMessage}"
                }
                MessageType.DISCONNECT -> {
                    // Clear all sync state when peer disconnects
                    currentSyncSession = null
                    pendingFileSends.clear()
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
    // P2P connection handling is now managed by WifiDirectManager
    // The connected socket is automatically handled via the StateFlow collection in init{}
    // Bluetooth connection handling is now managed by BluetoothConnectionManager
    // The connected socket is automatically handled via the StateFlow collection in init{}

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

    fun initiateSyncRequest(folderUri: Uri) {
        if (communicationHandler == null || !communicationHandler!!.isReady()) {
            Log.e("DevicesViewModel", "Cannot initiate sync: Communication handler not ready.")
            permissionRequestStatus.value = "Error: Not connected for sync."
            val folderNameForHistory = DocumentFile.fromTreeUri(getApplication(), folderUri)?.name ?: folderUri.toString()
            _syncHistory.add(0, SyncHistoryEntry(folderName = folderNameForHistory, status = "Error", details = "Cannot initiate sync: Not connected."))
            persistHistory()
            return
        }
        
        // Clear any previous sync state
        currentSyncSession = null
        pendingFileSends.clear()
        
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

        // --- Remove from pending and track sync session completion ---
        pendingFileSends.remove(relativePath)
        
        // Update sync session progress
        currentSyncSession?.let { session ->
            if (session.folderName == syncFolderName) {
                val updatedSession = session.copy(filesSentSuccessfully = session.filesSentSuccessfully + 1)
                currentSyncSession = updatedSession
                
                Log.d("DevicesViewModel", "Sync session progress: ${updatedSession.filesSentSuccessfully}/${updatedSession.totalFilesToSend} files sent for folder '${session.folderName}'")
                
                // Check if all files in this sync session have been sent
                if (updatedSession.filesSentSuccessfully >= updatedSession.totalFilesToSend) {
                    Log.d("DevicesViewModel", "All files sent for sync session '${session.folderName}'. Sending SYNC_COMPLETE.")
                    sendMessage(SyncMessage(MessageType.SYNC_COMPLETE, folderName = session.folderName))
                    currentSyncSession = null // Clear the session
                    
                    withContext(Dispatchers.Main) {
                        _isRefreshing.value = false
                        permissionRequestStatus.value = "Sync complete for '${session.folderName}'."
                    }
                }
            }
        }
        
        // Fallback: if no sync session but all pending sends are done, complete anyway
        if (currentSyncSession == null && pendingFileSends.isEmpty()) {
            withContext(Dispatchers.Main) {
                _isRefreshing.value = false
                permissionRequestStatus.value = "All file transfers complete."
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
        val destinationBaseUri: Uri,
        val originalPath: String = relativePath // Store original path for tracking
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
    private var stateMediaScanUri: Uri? = null
    private var stateFileUriForDebug: Uri? = null

    private fun finalizeReceivedFile() {
        val state = currentReceivingFile ?: return
        try {
            currentFileOutputStream?.flush()
            currentFileOutputStream?.close()
            
            val displayPath = if (state.originalPath != state.relativePath) {
                "${state.relativePath} (renamed from ${state.originalPath})"
            } else {
                state.relativePath
            }
            
            Log.i("DevicesViewModel", "File $displayPath finalized in ${state.destinationBaseUri}. Total bytes: ${state.bytesReceived}/${state.totalSize}")
            
            if (state.bytesReceived != state.totalSize) {
                Log.w("DevicesViewModel", "File size mismatch for $displayPath! Expected ${state.totalSize}, got ${state.bytesReceived}")
                _syncHistory.add(0, SyncHistoryEntry(folderName = state.folderName, status = "Error", details = "File size mismatch for $displayPath. Expected ${state.totalSize}, got ${state.bytesReceived}."))
            } else {
                val successDetails = if (state.originalPath != state.relativePath) {
                    "File ${state.relativePath} received successfully (renamed from ${state.originalPath} due to conflict resolution)."
                } else {
                    "File ${state.relativePath} received successfully."
                }
                _syncHistory.add(0, SyncHistoryEntry(folderName = state.folderName, status = "File Received", details = successDetails))
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
        Log.d("DevicesViewModel", "Closing communication handler.")
        messageListenerJob?.cancel()
        messageListenerJob = null
        communicationHandler?.cleanup()
        communicationHandler = null
    }

    private fun sendMessage(message: SyncMessage) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val handler = communicationHandler
                if (handler == null || !handler.isReady()) {
                    Log.w("DevicesViewModel", "Communication handler not ready, cannot send message")
                    launch(Dispatchers.Main) { permissionRequestStatus.value = "Not connected." }
                    return@launch
                }
                
                val success = handler.sendMessage(message)
                if (!success) {
                    Log.e("DevicesViewModel", "Failed to send message: Type: ${message.type}")
                    launch(Dispatchers.Main) { permissionRequestStatus.value = "Error sending data." }
                    if (message.type != MessageType.ERROR_MESSAGE) { // Avoid infinite error loops
                        _syncHistory.add(0, SyncHistoryEntry(folderName = message.folderName ?: "N/A", status = "Error", details = "Failed to send message type ${message.type}"))
                    }
                }
            } catch (e: Exception) {
                Log.e("DevicesViewModel", "Exception sending message: ${e.message}", e)
                launch(Dispatchers.Main) { permissionRequestStatus.value = "Error sending data." }
                if (message.type != MessageType.ERROR_MESSAGE) { // Avoid infinite error loops
                    _syncHistory.add(0, SyncHistoryEntry(folderName = message.folderName ?: "N/A", status = "Error", details = "Exception sending message type ${message.type}: ${e.message}"))
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

    // Add a public function to retry pending sync after folder mapping
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
        _syncHistory.clear()
        persistHistory()
    }

    // Helper functions for debugging connection state
    private fun logConnectionState(context: String) {
        Log.d("DevicesViewModel", "$context - Current tech: $currentCommunicationTechnology")
    }

    // Helper function to disconnect all connections
    fun disconnectAll() {
        Log.d("DevicesViewModel", "Disconnecting all connections")
        if (currentCommunicationTechnology == CommunicationTechnology.BLUETOOTH) {
            disconnectBluetooth()
        } else if (currentCommunicationTechnology == CommunicationTechnology.P2P) {
            disconnectP2p()
        }
        currentCommunicationTechnology = null
        permissionRequestStatus.value = "All connections disconnected."
    }
    
    // Helper function to check if any connection is active
    fun hasActiveConnection(): Boolean {
        return currentCommunicationTechnology != null
    }
    
    // Helper function to get current connection type
    fun getCurrentConnectionType(): String {
        return when (currentCommunicationTechnology) {
            CommunicationTechnology.BLUETOOTH -> "Bluetooth"
            CommunicationTechnology.P2P -> "Wi-Fi P2P" 
            null -> "None"
        }
    }
}