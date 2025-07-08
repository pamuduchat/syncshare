package com.example.syncshare.sync

import android.util.Log
import com.example.syncshare.communication.CommunicationHandler
import com.example.syncshare.protocol.MessageType
import com.example.syncshare.protocol.SyncMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Manages communication setup and message handling
 */
class ConnectionManager(private val scope: CoroutineScope) {
    
    private var communicationHandler: CommunicationHandler? = null
    private var messageListenerJob: Job? = null
    
    private val _connectionStatus = MutableStateFlow("Disconnected")
    val connectionStatus: StateFlow<String> = _connectionStatus
    
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady
    
    // Message handlers
    private var messageHandler: ((SyncMessage) -> Unit)? = null
    private var onSyncRequest: ((SyncMessage) -> Unit)? = null
    private var onSyncResponse: ((SyncMessage) -> Unit)? = null
    private var onFileRequest: ((SyncMessage) -> Unit)? = null
    private var onFileTransferStart: ((SyncMessage) -> Unit)? = null
    private var onFileChunk: ((SyncMessage) -> Unit)? = null
    private var onFileTransferEnd: ((SyncMessage) -> Unit)? = null
    private var onFileAck: ((SyncMessage) -> Unit)? = null
    private var onSyncComplete: ((SyncMessage) -> Unit)? = null
    private var onError: ((SyncMessage) -> Unit)? = null
    private var onDisconnect: ((SyncMessage) -> Unit)? = null
    
    /**
     * Sets up communication with the given socket
     */
    suspend fun setupCommunication(
        socket: Any,
        technology: String,
        onStatusUpdate: (String) -> Unit
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Clean up existing handler
                cleanup()
                
                Log.d("ConnectionManager", "Setting up communication for $technology")
                
                // Create and initialize handler
                communicationHandler = CommunicationHandler(socket, scope)
                val initialized = communicationHandler!!.initialize()
                
                if (!initialized) {
                    throw IOException("Failed to initialize communication handler")
                }
                
                _connectionStatus.value = "Connected ($technology)"
                _isReady.value = true
                
                withContext(Dispatchers.Main) {
                    onStatusUpdate("Communication ready")
                }
                
                startListeningForMessages()
                
                Log.d("ConnectionManager", "Communication setup successful for $technology")
                true
                
            } catch (e: Exception) {
                Log.e("ConnectionManager", "Error setting up communication", e)
                withContext(Dispatchers.Main) {
                    onStatusUpdate("Communication setup failed: ${e.message}")
                }
                cleanup()
                false
            }
        }
    }
    
    /**
     * Sends a message to the peer
     */
    suspend fun sendMessage(message: SyncMessage): Boolean {
        return try {
            if (communicationHandler?.isReady() == true) {
                communicationHandler!!.sendMessage(message)
                true
            } else {
                Log.w("ConnectionManager", "Cannot send message: handler not ready")
                false
            }
        } catch (e: Exception) {
            Log.e("ConnectionManager", "Error sending message", e)
            false
        }
    }
    
    /**
     * Sets message handlers
     */
    fun setMessageHandlers(
        onSyncRequest: ((SyncMessage) -> Unit)? = null,
        onSyncResponse: ((SyncMessage) -> Unit)? = null,
        onFileRequest: ((SyncMessage) -> Unit)? = null,
        onFileTransferStart: ((SyncMessage) -> Unit)? = null,
        onFileChunk: ((SyncMessage) -> Unit)? = null,
        onFileTransferEnd: ((SyncMessage) -> Unit)? = null,
        onFileAck: ((SyncMessage) -> Unit)? = null,
        onSyncComplete: ((SyncMessage) -> Unit)? = null,
        onError: ((SyncMessage) -> Unit)? = null,
        onDisconnect: ((SyncMessage) -> Unit)? = null
    ) {
        this.onSyncRequest = onSyncRequest
        this.onSyncResponse = onSyncResponse
        this.onFileRequest = onFileRequest
        this.onFileTransferStart = onFileTransferStart
        this.onFileChunk = onFileChunk
        this.onFileTransferEnd = onFileTransferEnd
        this.onFileAck = onFileAck
        this.onSyncComplete = onSyncComplete
        this.onError = onError
        this.onDisconnect = onDisconnect
    }
    
    /**
     * Checks if communication is ready
     */
    fun isReady(): Boolean = _isReady.value && communicationHandler?.isReady() == true
    
    /**
     * Cleans up communication resources
     */
    fun cleanup() {
        messageListenerJob?.cancel()
        messageListenerJob = null
        
        communicationHandler?.cleanup()
        communicationHandler = null
        
        _connectionStatus.value = "Disconnected"
        _isReady.value = false
        
        Log.d("ConnectionManager", "Communication cleaned up")
    }
    
    private fun startListeningForMessages() {
        messageListenerJob?.cancel()
        messageListenerJob = scope.launch {
            Log.d("ConnectionManager", "Starting to listen for messages")
            
            communicationHandler?.incomingMessages?.collect { message ->
                Log.d("ConnectionManager", "Received message: ${message.type}")
                
                // Handle connection errors
                if (message.type == MessageType.ERROR_MESSAGE && 
                    message.errorMessage?.contains("Connection lost") == true) {
                    handleConnectionLost(message.errorMessage)
                    return@collect
                }
                
                // Route message to general handler if set
                messageHandler?.invoke(message)
                
                // Route message to specific handlers
                when (message.type) {
                    MessageType.SYNC_REQUEST_METADATA -> onSyncRequest?.invoke(message)
                    MessageType.SYNC_METADATA_RESPONSE -> onSyncResponse?.invoke(message)
                    MessageType.FILES_REQUESTED_BY_PEER -> onFileRequest?.invoke(message)
                    MessageType.FILE_TRANSFER_START -> onFileTransferStart?.invoke(message)
                    MessageType.FILE_CHUNK -> onFileChunk?.invoke(message)
                    MessageType.FILE_TRANSFER_END -> onFileTransferEnd?.invoke(message)
                    MessageType.FILE_RECEIVED_ACK -> onFileAck?.invoke(message)
                    MessageType.SYNC_COMPLETE -> onSyncComplete?.invoke(message)
                    MessageType.ERROR_MESSAGE -> onError?.invoke(message)
                    MessageType.DISCONNECT -> onDisconnect?.invoke(message)
                }
            }
            
            Log.d("ConnectionManager", "Stopped listening for messages")
        }
    }
    
    private fun handleConnectionLost(errorMessage: String) {
        Log.w("ConnectionManager", "Connection lost: $errorMessage")
        _connectionStatus.value = "Connection lost"
        _isReady.value = false
        cleanup()
    }
    
    fun setMessageHandler(handler: (SyncMessage) -> Unit) {
        messageHandler = handler
    }
    
    fun getCommunicationHandler(): CommunicationHandler? = communicationHandler
}
