package com.example.syncshare.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.syncshare.communication.CommunicationHandler
import com.example.syncshare.protocol.FileMetadata
import com.example.syncshare.protocol.FileTransferInfo
import com.example.syncshare.protocol.MessageType
import com.example.syncshare.protocol.SyncMessage
import com.example.syncshare.utils.computeFileHash
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay

/**
 * Manages all sync operations including:
 * - File metadata comparison
 * - Conflict detection
 * - File sending/receiving coordination
 * - Sync session tracking
 */
class SyncManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    // Sync state
    private val _syncStatus = MutableStateFlow("Idle")
    val syncStatus: StateFlow<String> = _syncStatus
    
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive

    // Conflict handling
    data class FileConflict(
        val folderName: String,
        val relativePath: String,
        val local: FileMetadata?,
        val remote: FileMetadata?
    )
    
    enum class ConflictResolutionOption { KEEP_LOCAL, USE_REMOTE, KEEP_BOTH, SKIP }
    
    private val _conflicts = MutableStateFlow<List<FileConflict>>(emptyList())
    val conflicts: StateFlow<List<FileConflict>> = _conflicts
    
    // File transfer state
    data class SyncSession(
        val folderName: String,
        val totalFilesToSend: Int,
        val filesSentSuccessfully: Int = 0,
        val isInitiator: Boolean = false  // Track if we initiated this sync
    )
    private var currentSyncSession: SyncSession? = null
    private var pendingFileSends = mutableSetOf<String>()
    
    // File rename mappings for KEEP_BOTH conflicts
    private var fileRenameMap = mutableMapOf<String, String>()
    
    // Conflict resolution tracking
    private var conflictResolutions = mutableMapOf<String, ConflictResolutionOption>()
    private var pendingSyncCallbacks: PendingSyncCallbacks? = null
    
    data class PendingSyncCallbacks(
        val onSendFilesRequest: (List<String>) -> Unit,
        val onSendFiles: (List<String>) -> Unit,
        val onSyncComplete: () -> Unit,
        val onStatusUpdate: (String) -> Unit,
        val localFolderUri: Uri,
        val remoteFiles: List<FileMetadata>,
        val localFiles: List<FileMetadata>
    )
    
    // Communication
    private var communicationHandler: CommunicationHandler? = null
    
    fun setCommunicationHandler(handler: CommunicationHandler?) {
        communicationHandler = handler
    }
    
    /**
     * Initiates a sync request by sending local file metadata to the peer
     */
    fun initiateSyncRequest(
        folderUri: Uri, 
        folderName: String,
        onStatusUpdate: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (communicationHandler?.isReady() != true) {
            onError("Cannot initiate sync: Not connected to peer")
            return
        }
        
        _isActive.value = true
        scope.launch {
            try {
                onStatusUpdate("Preparing to sync folder: $folderName")
                
                val localMetadata = withContext(Dispatchers.IO) {
                    getLocalFileMetadata(folderUri)
                }
                
                Log.d("SyncManager", "Sending SYNC_REQUEST_METADATA for $folderName with ${localMetadata.size} files")
                sendMessage(
                    SyncMessage(
                        type = MessageType.SYNC_REQUEST_METADATA,
                        folderName = folderName,
                        fileMetadataList = localMetadata
                    )
                )
                
                _syncStatus.value = "Sync request sent"
                
            } catch (e: Exception) {
                Log.e("SyncManager", "Error initiating sync", e)
                onError("Failed to initiate sync: ${e.message}")
                _isActive.value = false
            }
        }
    }
    
    /**
     * Processes incoming sync metadata and detects conflicts
     */
    fun processSyncRequest(
        message: SyncMessage,
        localFolderUri: Uri,
        onConflictsDetected: (List<FileConflict>) -> Unit,
        onSyncReady: (filesToRequest: List<String>, filesToSend: List<String>) -> Unit,
        onStatusUpdate: (String) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val folderName = message.folderName ?: "Unknown"
                val localFiles = getLocalFileMetadata(localFolderUri)
                val remoteFiles = message.fileMetadataList ?: emptyList()
                
                Log.d("SyncManager", "Processing sync request: Local=${localFiles.size}, Remote=${remoteFiles.size}")
                
                // Send our metadata back for two-way sync
                sendMessage(
                    SyncMessage(
                        type = MessageType.SYNC_METADATA_RESPONSE,
                        folderName = folderName,
                        fileMetadataList = localFiles
                    )
                )
                
                val result = compareFileMetadata(localFiles, remoteFiles, folderName)
                
                if (result.conflicts.isNotEmpty()) {
                    _conflicts.value = result.conflicts
                    withContext(Dispatchers.Main) {
                        onConflictsDetected(result.conflicts)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onSyncReady(result.filesToRequest, result.filesToSend)
                    }
                }
                
            } catch (e: Exception) {
                Log.e("SyncManager", "Error processing sync request", e)
                withContext(Dispatchers.Main) {
                    onStatusUpdate("Error processing sync: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Resolves a single file conflict
     */
    fun resolveConflict(conflict: FileConflict, option: ConflictResolutionOption) {
        val updated = _conflicts.value.toMutableList().apply { remove(conflict) }
        _conflicts.value = updated
        
        // Store the resolution
        conflictResolutions[conflict.relativePath] = option
        
        // Store rename mapping for KEEP_BOTH
        if (option == ConflictResolutionOption.KEEP_BOTH) {
            val extension = conflict.relativePath.substringAfterLast('.', "")
            val nameWithoutExtension = conflict.relativePath.substringBeforeLast('.', conflict.relativePath)
            val newName = if (extension.isNotEmpty()) {
                "${nameWithoutExtension}_remote.$extension"
            } else {
                "${conflict.relativePath}_remote"
            }
            fileRenameMap[conflict.relativePath] = newName
            Log.d("SyncManager", "KEEP_BOTH: Will rename ${conflict.relativePath} to $newName")
        }
        
        Log.d("SyncManager", "Conflict resolved for ${conflict.relativePath}: $option")
        
        // If all conflicts are resolved, automatically continue sync
        if (_conflicts.value.isEmpty() && pendingSyncCallbacks != null) {
            Log.d("SyncManager", "All conflicts resolved, continuing sync...")
            scope.launch {
                processResolvedConflicts()
            }
        }
    }
    
    /**
     * Sends a file to the peer
     */
    fun sendFile(
        baseFolderUri: Uri,
        relativePath: String,
        syncFolderName: String,
        onProgress: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                Log.d("SyncManager", "Sending file: $relativePath (pending sends: ${pendingFileSends.size})")
                
                // Check if already pending to prevent duplicates
                if (pendingFileSends.contains(relativePath)) {
                    Log.w("SyncManager", "File $relativePath is already being sent, skipping duplicate")
                    onError("File $relativePath is already being sent")
                    return@launch
                }
                
                val documentFile = findFileInFolder(baseFolderUri, relativePath)
                if (documentFile == null || !documentFile.isFile) {
                    onError("File not found: $relativePath")
                    return@launch
                }
                
                val fileSize = documentFile.length()
                val transferInfo = FileTransferInfo(relativePath, fileSize)
                Log.d("SyncManager", "Starting transfer for $relativePath, size: $fileSize bytes")
                
                sendMessage(SyncMessage(MessageType.FILE_TRANSFER_START, folderName = syncFolderName, fileTransferInfo = transferInfo))
                
                withContext(Dispatchers.Main) {
                    onProgress("Sending: $relativePath")
                }
                
                pendingFileSends.add(relativePath)
                Log.d("SyncManager", "Added $relativePath to pending sends (now ${pendingFileSends.size} pending)")
                
                // Send file chunks
                val bufferSize = 4096
                val buffer = ByteArray(bufferSize)
                var totalBytesSent = 0L
                
                context.contentResolver.openInputStream(documentFile.uri)?.use { inputStream ->
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1 && currentCoroutineContext().isActive) {
                        if (bytesRead > 0) {
                            val chunk = buffer.copyOf(bytesRead)
                            sendMessage(SyncMessage(MessageType.FILE_CHUNK, folderName = syncFolderName, fileChunkData = chunk))
                            totalBytesSent += bytesRead
                        }
                        delay(5)
                    }
                }
                
                if (currentCoroutineContext().isActive) {
                    sendMessage(SyncMessage(MessageType.FILE_TRANSFER_END, folderName = syncFolderName, fileTransferInfo = transferInfo))
                    pendingFileSends.remove(relativePath)
                    Log.d("SyncManager", "Completed transfer for $relativePath ($totalBytesSent bytes sent, pending sends: ${pendingFileSends.size})")
                    
                    withContext(Dispatchers.Main) {
                        onProgress("Sent: $relativePath")
                        onComplete()
                    }
                    
                    Log.d("SyncManager", "Successfully sent $relativePath ($totalBytesSent bytes)")
                } else {
                    pendingFileSends.remove(relativePath)
                    Log.w("SyncManager", "Transfer cancelled for $relativePath")
                }
                
            } catch (e: Exception) {
                Log.e("SyncManager", "Error sending file $relativePath", e)
                pendingFileSends.remove(relativePath)
                onError("Failed to send $relativePath: ${e.message}")
            }
        }
    }
    
    /**
     * Checks if a file is already pending send to prevent duplicates
     */
    fun isFilePendingSend(relativePath: String): Boolean {
        return pendingFileSends.contains(relativePath)
    }
    
    /**
     * Gets the rename mapping for a file (used for KEEP_BOTH conflicts)
     */
    fun getFileRenameMapping(originalPath: String): String? {
        return fileRenameMap[originalPath]
    }
    
    /**
     * Clears file rename mapping after use
     */
    fun clearFileRenameMapping(originalPath: String) {
        fileRenameMap.remove(originalPath)
    }
    
    /**
     * Starts a new sync session
     */
    fun startSyncSession(folderName: String, totalFiles: Int, isInitiator: Boolean = false) {
        // Clear any previous pending sends to start fresh
        pendingFileSends.clear()
        currentSyncSession = SyncSession(folderName, totalFiles, 0, isInitiator)
        Log.d("SyncManager", "Started sync session for $folderName with $totalFiles files (initiator: $isInitiator)")
    }
    
    /**
     * Gets the current sync session
     */
    fun getCurrentSyncSession(): SyncSession? = currentSyncSession
    
    /**
     * Clears the current sync session
     */
    fun clearSyncSession() {
        currentSyncSession = null
        pendingFileSends.clear()
        conflictResolutions.clear()
        pendingSyncCallbacks = null
        _conflicts.value = emptyList()
        _isActive.value = false
    }
    
    /**
     * Updates the progress of the current sync session
     */
    fun updateSyncSessionProgress(folderName: String): Boolean {
        val session = currentSyncSession
        return if (session != null && session.folderName == folderName) {
            val updatedSession = session.copy(filesSentSuccessfully = session.filesSentSuccessfully + 1)
            currentSyncSession = updatedSession
            updatedSession.filesSentSuccessfully >= updatedSession.totalFilesToSend
        } else {
            false
        }
    }
    
    // Private helper methods
    
    private fun getLocalFileMetadata(folderUri: Uri): List<FileMetadata> {
        val documentFolder = DocumentFile.fromTreeUri(context, folderUri)
        if (documentFolder == null || !documentFolder.isDirectory) {
            Log.e("SyncManager", "Invalid folder URI: $folderUri")
            return emptyList()
        }
        
        val metadataList = mutableListOf<FileMetadata>()
        
        fun traverse(currentDir: DocumentFile, currentPath: String) {
            currentDir.listFiles().forEach { file ->
                if (file.isFile) {
                    val relativePath = if (currentPath.isEmpty()) file.name ?: "" else "$currentPath/${file.name}"
                    if (relativePath.isNotEmpty()) {
                        val hash = computeFileHash(context, file)
                        metadataList.add(
                            FileMetadata(
                                relativePath = relativePath,
                                name = file.name ?: "Unknown",
                                size = file.length(),
                                lastModified = file.lastModified(),
                                hash = hash
                            )
                        )
                    }
                } else if (file.isDirectory) {
                    val nextPath = if (currentPath.isEmpty()) file.name ?: "" else "$currentPath/${file.name}"
                    if (nextPath.isNotEmpty()) {
                        traverse(file, nextPath)
                    }
                }
            }
        }
        
        traverse(documentFolder, "")
        return metadataList
    }
    
    private data class ComparisonResult(
        val filesToRequest: List<String>,
        val filesToSend: List<String>,
        val conflicts: List<FileConflict>
    )
    
    private fun compareFileMetadata(
        localFiles: List<FileMetadata>,
        remoteFiles: List<FileMetadata>,
        folderName: String
    ): ComparisonResult {
        val localFileMap = localFiles.associateBy { it.relativePath }
        val remoteFileMap = remoteFiles.associateBy { it.relativePath }
        val filesToRequest = mutableListOf<String>()
        val filesToSend = mutableListOf<String>()
        val conflicts = mutableListOf<FileConflict>()
        
        // Check files from remote
        for ((remotePath, remoteMeta) in remoteFileMap) {
            val localMeta = localFileMap[remotePath]
            if (localMeta == null) {
                // File exists on remote but not local - request it
                filesToRequest.add(remotePath)
            } else {
                // File exists on both - check for conflicts
                val hasContentDifference = (remoteMeta.size != localMeta.size) || 
                    (remoteMeta.hash != null && localMeta.hash != null && remoteMeta.hash != localMeta.hash)
                
                if (hasContentDifference) {
                    conflicts.add(FileConflict(folderName, remotePath, localMeta, remoteMeta))
                }
            }
        }
        
        // Check files that exist locally but not on remote
        for ((localPath, _) in localFileMap) {
            if (!remoteFileMap.containsKey(localPath)) {
                filesToSend.add(localPath)
            }
        }
        
        return ComparisonResult(filesToRequest, filesToSend, conflicts)
    }
    
    private fun findFileInFolder(baseFolderUri: Uri, relativePath: String): DocumentFile? {
        var currentDir = DocumentFile.fromTreeUri(context, baseFolderUri) ?: return null
        
        val pathSegments = relativePath.split('/')
        for (segment in pathSegments) {
            if (segment.isEmpty()) continue
            currentDir = currentDir.findFile(segment) ?: return null
        }
        
        return currentDir
    }
    
    private suspend fun sendMessage(message: SyncMessage) {
        communicationHandler?.sendMessage(message)
    }
    
    /**
     * Handles incoming sync request metadata - for receivers
     */
    fun handleSyncRequestMetadata(
        message: SyncMessage,
        localFolderUri: Uri,
        onStatusUpdate: (String) -> Unit,
        onSendFilesRequest: (List<String>) -> Unit,
        onSendFiles: (List<String>) -> Unit,
        onSyncComplete: () -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val folderName = message.folderName ?: "Unknown"
                val remoteFiles = message.fileMetadataList ?: emptyList()
                
                onStatusUpdate("Processing sync request for '$folderName'...")
                
                val localFiles = getLocalFileMetadata(localFolderUri)
                
                // Send our metadata back for two-way sync
                sendMessage(
                    SyncMessage(
                        type = MessageType.SYNC_METADATA_RESPONSE,
                        folderName = folderName,
                        fileMetadataList = localFiles
                    )
                )
                
                val result = compareFileMetadata(localFiles, remoteFiles, folderName)
                
                if (result.conflicts.isNotEmpty()) {
                    // Store callbacks for later when conflicts are resolved
                    pendingSyncCallbacks = PendingSyncCallbacks(
                        onSendFilesRequest = onSendFilesRequest,
                        onSendFiles = onSendFiles,
                        onSyncComplete = onSyncComplete,
                        onStatusUpdate = onStatusUpdate,
                        localFolderUri = localFolderUri,
                        remoteFiles = remoteFiles,
                        localFiles = localFiles
                    )
                    
                    _conflicts.value = result.conflicts
                    withContext(Dispatchers.Main) {
                        onStatusUpdate("Conflicts detected. Please resolve them to continue.")
                    }
                } else {
                    // No conflicts, proceed with sync
                    withContext(Dispatchers.Main) {
                        if (result.filesToRequest.isNotEmpty()) {
                            onSendFilesRequest(result.filesToRequest)
                        }
                        if (result.filesToSend.isNotEmpty()) {
                            onSendFiles(result.filesToSend)
                        }
                        if (result.filesToRequest.isEmpty() && result.filesToSend.isEmpty()) {
                            onSyncComplete()
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e("SyncManager", "Error handling sync request metadata", e)
                withContext(Dispatchers.Main) {
                    onStatusUpdate("Error processing sync request: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Handles incoming sync metadata response - for initiators
     */
    fun handleSyncMetadataResponse(
        message: SyncMessage,
        localFolderUri: Uri,
        onSendFiles: (List<String>) -> Unit,
        onStatusUpdate: (String) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val folderName = message.folderName ?: "Unknown"
                val remoteFiles = message.fileMetadataList ?: emptyList()
                
                onStatusUpdate("Processing metadata response for '$folderName'...")
                
                val localFiles = getLocalFileMetadata(localFolderUri)
                val result = compareFileMetadata(localFiles, remoteFiles, folderName)
                
                if (result.conflicts.isNotEmpty()) {
                    // Store callbacks for later when conflicts are resolved
                    pendingSyncCallbacks = PendingSyncCallbacks(
                        onSendFilesRequest = { /* Not used in response */ },
                        onSendFiles = onSendFiles,
                        onSyncComplete = { onStatusUpdate("Sync complete - waiting for peer") },
                        onStatusUpdate = onStatusUpdate,
                        localFolderUri = localFolderUri,
                        remoteFiles = remoteFiles,
                        localFiles = localFiles
                    )
                    
                    _conflicts.value = result.conflicts
                    withContext(Dispatchers.Main) {
                        onStatusUpdate("Conflicts detected. Please resolve them to continue.")
                    }
                } else {
                    // No conflicts, proceed with sending files we need to send
                    withContext(Dispatchers.Main) {
                        if (result.filesToSend.isNotEmpty()) {
                            onSendFiles(result.filesToSend)
                        } else {
                            onStatusUpdate("No files to send. Waiting for peer to complete sync.")
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e("SyncManager", "Error handling metadata response", e)
                withContext(Dispatchers.Main) {
                    onStatusUpdate("Error processing metadata response: ${e.message}")
                }
            }
        }
    }

    /**
     * Processes conflict resolutions after all conflicts are resolved
     */
    fun processConflictResolutions(
        onSendFilesRequest: (List<String>) -> Unit,
        onSendFiles: (List<String>) -> Unit,
        onSyncComplete: () -> Unit,
        onStatusUpdate: (String) -> Unit
    ) {
        // This method would contain the logic to process all resolved conflicts
        // For now, just call onSyncComplete as conflicts have been resolved
        onSyncComplete()
    }
    
    /**
     * Processes resolved conflicts and continues sync
     */
    private suspend fun processResolvedConflicts() {
        val callbacks = pendingSyncCallbacks ?: return
        
        try {
            // Determine which files to send and request based on resolutions
            val filesToSend = mutableListOf<String>()
            val filesToRequest = mutableListOf<String>()
            
            val result = compareFileMetadata(callbacks.localFiles, callbacks.remoteFiles, "")
            
            // Process original file comparison results with conflict resolutions
            for (fileToSend in result.filesToSend) {
                val resolution = conflictResolutions[fileToSend]
                when (resolution) {
                    ConflictResolutionOption.KEEP_LOCAL -> {
                        // Don't send this file, keep local version
                        Log.d("SyncManager", "Skipping send of $fileToSend (KEEP_LOCAL)")
                    }
                    ConflictResolutionOption.USE_REMOTE -> {
                        // Don't send this file, will receive remote version
                        Log.d("SyncManager", "Skipping send of $fileToSend (USE_REMOTE)")
                    }
                    ConflictResolutionOption.KEEP_BOTH -> {
                        // Send the file, will be renamed on remote
                        filesToSend.add(fileToSend)
                        Log.d("SyncManager", "Will send $fileToSend (KEEP_BOTH)")
                    }
                    ConflictResolutionOption.SKIP -> {
                        // Skip this file entirely
                        Log.d("SyncManager", "Skipping $fileToSend (SKIP)")
                    }
                    null -> {
                        // No conflict, send as normal
                        filesToSend.add(fileToSend)
                    }
                }
            }
            
            for (fileToRequest in result.filesToRequest) {
                val resolution = conflictResolutions[fileToRequest]
                when (resolution) {
                    ConflictResolutionOption.KEEP_LOCAL -> {
                        // Don't request this file, keep local version
                        Log.d("SyncManager", "Skipping request of $fileToRequest (KEEP_LOCAL)")
                    }
                    ConflictResolutionOption.USE_REMOTE -> {
                        // Request this file, will overwrite local
                        filesToRequest.add(fileToRequest)
                        Log.d("SyncManager", "Will request $fileToRequest (USE_REMOTE)")
                    }
                    ConflictResolutionOption.KEEP_BOTH -> {
                        // Request this file, will be renamed locally
                        filesToRequest.add(fileToRequest)
                        Log.d("SyncManager", "Will request $fileToRequest (KEEP_BOTH)")
                    }
                    ConflictResolutionOption.SKIP -> {
                        // Skip this file entirely
                        Log.d("SyncManager", "Skipping request of $fileToRequest (SKIP)")
                    }
                    null -> {
                        // No conflict, request as normal
                        filesToRequest.add(fileToRequest)
                    }
                }
            }
            
            withContext(Dispatchers.Main) {
                if (filesToRequest.isNotEmpty()) {
                    callbacks.onStatusUpdate("Requesting ${filesToRequest.size} files from peer...")
                    callbacks.onSendFilesRequest(filesToRequest)
                }
                if (filesToSend.isNotEmpty()) {
                    callbacks.onStatusUpdate("Sending ${filesToSend.size} files to peer...")
                    callbacks.onSendFiles(filesToSend)
                }
                if (filesToRequest.isEmpty() && filesToSend.isEmpty()) {
                    callbacks.onStatusUpdate("Sync complete - no files to transfer")
                    callbacks.onSyncComplete()
                }
            }
            
            // Clear the pending callbacks
            pendingSyncCallbacks = null
            conflictResolutions.clear()
            
        } catch (e: Exception) {
            Log.e("SyncManager", "Error processing resolved conflicts", e)
            withContext(Dispatchers.Main) {
                callbacks.onStatusUpdate("Error processing conflicts: ${e.message}")
            }
        }
    }
    
    fun cleanup() {
        _isActive.value = false
        _conflicts.value = emptyList()
        fileRenameMap.clear()
        pendingFileSends.clear()
        currentSyncSession = null
        conflictResolutions.clear()
        pendingSyncCallbacks = null
    }
}