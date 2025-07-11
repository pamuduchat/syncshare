package com.example.syncshare.sync

import android.net.Uri
import android.util.Log
import com.example.syncshare.protocol.MessageType
import com.example.syncshare.protocol.SyncMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Coordinates two-way sync operations between devices.
 * This class encapsulates the logic for managing bidirectional file synchronization,
 * including session tracking, file transfer coordination, and completion detection.
 */
class TwoWaySyncCoordinator(
    private val syncManager: SyncManager,
    private val scope: CoroutineScope
) {
    
    /**
     * Callbacks for sync events
     */
    interface SyncEventListener {
        fun onStatusUpdate(status: String)
        fun onSyncComplete(folderName: String)
        fun onError(error: String)
        fun onSendMessage(message: SyncMessage)
    }
    
    private var eventListener: SyncEventListener? = null
    
    fun setEventListener(listener: SyncEventListener) {
        eventListener = listener
    }
    
    /**
     * Initiates a two-way sync as the initiator
     */
    fun initiateTwoWaySync(folderUri: Uri, folderName: String) {
        Log.d("TwoWaySyncCoordinator", "Initiating two-way sync for folder: $folderName")
        
        syncManager.initiateSyncRequest(
            folderUri = folderUri,
            folderName = folderName,
            onStatusUpdate = { status -> eventListener?.onStatusUpdate(status) },
            onError = { error -> eventListener?.onError(error) }
        )
    }
    
    /**
     * Handles incoming sync request as the receiver
     */
    fun handleIncomingSyncRequest(
        message: SyncMessage,
        localFolderUri: Uri
    ) {
        Log.d("TwoWaySyncCoordinator", "Handling incoming sync request for folder: ${message.folderName}")
        
        syncManager.handleSyncRequestMetadata(
            message = message,
            localFolderUri = localFolderUri,
            onStatusUpdate = { status -> eventListener?.onStatusUpdate(status) },
            onSendFilesRequest = { filesToRequest ->
                requestFilesFromPeer(message.folderName, filesToRequest)
            },
            onSendFiles = { filesToSend ->
                sendFilesToPeer(localFolderUri, filesToSend, message.folderName ?: "", isReceiver = true)
            },
            onSyncComplete = {
                completeTwoWaySync(message.folderName ?: "")
            }
        )
    }
    
    /**
     * Handles metadata response as the initiator
     */
    fun handleMetadataResponse(
        message: SyncMessage,
        localFolderUri: Uri
    ) {
        Log.d("TwoWaySyncCoordinator", "Handling metadata response for folder: ${message.folderName}")
        
        syncManager.handleSyncMetadataResponse(
            message = message,
            localFolderUri = localFolderUri,
            onSendFiles = { filesToSend ->
                sendFilesToPeer(localFolderUri, filesToSend, message.folderName ?: "", isReceiver = false)
            },
            onSendFilesRequest = { filesToRequest ->
                requestFilesFromPeer(message.folderName, filesToRequest)
            },
            onStatusUpdate = { status -> eventListener?.onStatusUpdate(status) }
        )
    }
    
    /**
     * Handles peer file request
     */
    fun handlePeerFileRequest(
        message: SyncMessage,
        senderBaseUri: Uri
    ) {
        val requestedPaths = message.requestedFilePaths
        val baseFolderName = message.folderName
        
        Log.d("TwoWaySyncCoordinator", "Handling peer file request for ${requestedPaths?.size ?: 0} files")
        
        if (requestedPaths.isNullOrEmpty()) {
            Log.d("TwoWaySyncCoordinator", "No files requested by peer. Checking sync completion.")
            checkAndCompleteSyncIfReady(baseFolderName ?: "")
            return
        }
        
        // Update sync session with expected incoming files
        val currentSession = syncManager.getCurrentSyncSession()
        if (currentSession == null) {
            Log.d("TwoWaySyncCoordinator", "Creating new sync session for peer file request (0 to send, ${requestedPaths.size} to receive)")
            syncManager.startSyncSession(baseFolderName ?: "", 0, requestedPaths.size, isInitiator = false)
        } else {
            Log.d("TwoWaySyncCoordinator", "Adding ${requestedPaths.size} expected incoming files to existing session")
            syncManager.addExpectedIncomingFiles(requestedPaths)
        }
        
        // Send requested files
        sendRequestedFiles(senderBaseUri, requestedPaths, baseFolderName ?: "")
    }
    
    /**
     * Handles file transfer completion
     */
    fun handleFileTransferComplete(folderName: String, isIncoming: Boolean) {
        Log.d("TwoWaySyncCoordinator", "File transfer complete for folder: $folderName (incoming: $isIncoming)")
        
        val progressUpdated = if (isIncoming) {
            syncManager.updateSyncSessionReceivedProgress(folderName)
        } else {
            syncManager.updateSyncSessionProgress(folderName)
        }
        
        // Only trigger completion if progress was actually updated AND sync is truly complete
        if (progressUpdated) {
            val isComplete = syncManager.checkSyncCompletion()
            Log.d("TwoWaySyncCoordinator", "Progress updated, sync complete: $isComplete")
            if (isComplete) {
                completeTwoWaySync(folderName)
            }
        } else {
            Log.d("TwoWaySyncCoordinator", "Progress not updated - session may not exist or folder name mismatch")
        }
    }
    
    private fun requestFilesFromPeer(folderName: String?, filesToRequest: List<String>) {
        if (filesToRequest.isNotEmpty()) {
            // Add expected incoming files to sync session
            syncManager.addExpectedIncomingFiles(filesToRequest)
            
            eventListener?.onSendMessage(
                SyncMessage(
                    MessageType.FILES_REQUESTED_BY_PEER,
                    folderName = folderName,
                    requestedFilePaths = filesToRequest
                )
            )
        }
    }
    
    private fun sendFilesToPeer(
        localFolderUri: Uri, 
        filesToSend: List<String>, 
        folderName: String, 
        isReceiver: Boolean
    ) {
        if (filesToSend.isNotEmpty()) {
            Log.d("TwoWaySyncCoordinator", "${if (isReceiver) "RECEIVER" else "INITIATOR"}: Sending ${filesToSend.size} files")
            
            // Only initialize sync session if one doesn't exist
            // This prevents overriding properly initialized sessions with incomplete data
            val currentSession = syncManager.getCurrentSyncSession()
            if (currentSession == null) {
                Log.d("TwoWaySyncCoordinator", "Initializing new sync session for send operation")
                syncManager.startSyncSession(folderName, filesToSend.size, 0, isInitiator = !isReceiver)
            } else {
                Log.d("TwoWaySyncCoordinator", "Using existing sync session (send=${currentSession.totalFilesToSend}, receive=${currentSession.totalFilesToReceive})")
            }
            
            eventListener?.onStatusUpdate("Sending ${filesToSend.size} files to peer...")
            
            // Send files sequentially
            sendFilesSequentially(localFolderUri, filesToSend, folderName, 0)
        }
    }
    
    private fun sendRequestedFiles(
        senderBaseUri: Uri,
        requestedPaths: List<String>,
        folderName: String
    ) {
        requestedPaths.forEach { relativePath ->
            syncManager.sendFile(
                baseFolderUri = senderBaseUri,
                relativePath = relativePath,
                syncFolderName = folderName,
                onProgress = { status -> eventListener?.onStatusUpdate(status) },
                onComplete = { 
                    Log.d("TwoWaySyncCoordinator", "File $relativePath sent successfully.")
                    handleFileTransferComplete(folderName, isIncoming = false)
                },
                onError = { error -> 
                    Log.e("TwoWaySyncCoordinator", "Error sending file $relativePath: $error")
                    eventListener?.onError("Error sending file $relativePath: $error")
                }
            )
        }
    }
    
    private fun sendFilesSequentially(
        localFolderUri: Uri, 
        filesToSend: List<String>, 
        folderName: String, 
        index: Int
    ) {
        if (index >= filesToSend.size) {
            Log.d("TwoWaySyncCoordinator", "All files sent for folder: $folderName")
            return
        }
        
        val relativePath = filesToSend[index]
        
        // Check if this file is already being sent to avoid duplicates
        if (!syncManager.isFilePendingSend(relativePath)) {
            Log.d("TwoWaySyncCoordinator", "Sending file: $relativePath (${index + 1}/${filesToSend.size})")
            
            syncManager.sendFile(
                baseFolderUri = localFolderUri,
                relativePath = relativePath,
                syncFolderName = folderName,
                onProgress = { status -> eventListener?.onStatusUpdate(status) },
                onComplete = {
                    handleFileTransferComplete(folderName, isIncoming = false)
                    
                    // Continue with next file regardless of completion check
                    // The sync coordinator will handle final completion when all files are done
                    scope.launch {
                        kotlinx.coroutines.delay(100) // Small delay between files
                        sendFilesSequentially(localFolderUri, filesToSend, folderName, index + 1)
                    }
                },
                onError = { error ->
                    eventListener?.onError(error)
                    
                    // Continue with next file even if one fails
                    scope.launch {
                        kotlinx.coroutines.delay(100)
                        sendFilesSequentially(localFolderUri, filesToSend, folderName, index + 1)
                    }
                }
            )
        } else {
            Log.d("TwoWaySyncCoordinator", "Skipping duplicate send for file: $relativePath")
            sendFilesSequentially(localFolderUri, filesToSend, folderName, index + 1)
        }
    }
    
    private fun checkAndCompleteSyncIfReady(folderName: String) {
        val currentSession = syncManager.getCurrentSyncSession()
        if (currentSession == null) {
            // No session exists, create one with zero files and complete it
            syncManager.startSyncSession(folderName, 0, 0, isInitiator = false)
        }
        
        if (syncManager.checkSyncCompletion()) {
            completeTwoWaySync(folderName)
        }
    }
    
    private fun completeTwoWaySync(folderName: String) {
        Log.d("TwoWaySyncCoordinator", "Completing two-way sync for folder: $folderName")
        
        eventListener?.onSendMessage(
            SyncMessage(MessageType.SYNC_COMPLETE, folderName = folderName)
        )
        
        syncManager.clearSyncSession()
        eventListener?.onSyncComplete(folderName)
    }
}
