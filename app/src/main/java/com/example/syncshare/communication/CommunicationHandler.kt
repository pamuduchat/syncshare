package com.example.syncshare.communication

import android.bluetooth.BluetoothSocket
import android.util.Log
import com.example.syncshare.protocol.SyncMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.Socket

/**
 * Handles communication over a generic socket connection.
 * Abstracts the SyncMessage protocol for both P2P and Bluetooth connections.
 */
class CommunicationHandler(
    private val socket: Any, // Can be Socket (P2P) or BluetoothSocket (Bluetooth)
    private val scope: CoroutineScope
) {
    
    private var objectOutputStream: ObjectOutputStream? = null
    private var objectInputStream: ObjectInputStream? = null
    private var messageListenerJob: Job? = null
    
    // SharedFlow for incoming messages
    private val _incomingMessages = MutableSharedFlow<SyncMessage>()
    val incomingMessages: Flow<SyncMessage> = _incomingMessages.asSharedFlow()
    
    // Lock for thread-safe output stream operations
    private val outputStreamLock = Any()
    
    var isInitialized = false
        private set
    
    /**
     * Initialize the communication streams and start listening for messages
     */
    suspend fun initialize(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d("CommunicationHandler", "Initializing communication streams...")
                
                // Setup streams based on socket type and role
                when (socket) {
                    is BluetoothSocket -> {
                        objectOutputStream = ObjectOutputStream(socket.outputStream)
                        objectInputStream = ObjectInputStream(socket.inputStream)
                    }
                    is Socket -> {
                        objectOutputStream = ObjectOutputStream(socket.getOutputStream())
                        objectInputStream = ObjectInputStream(socket.inputStream)
                    }
                    else -> {
                        Log.e("CommunicationHandler", "Unsupported socket type: ${socket::class.java}")
                        return@withContext false
                    }
                }
                
                objectOutputStream?.flush()
                
                isInitialized = true
                
                // Start listening for incoming messages
                startMessageListener()
                
                Log.i("CommunicationHandler", "Communication streams initialized successfully")
                true
            } catch (e: IOException) {
                Log.e("CommunicationHandler", "Error initializing communication streams: ${e.message}", e)
                cleanup()
                false
            }
        }
    }
    
    /**
     * Send a message through the output stream
     */
    suspend fun sendMessage(message: SyncMessage): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                synchronized(outputStreamLock) {
                    if (message.type == com.example.syncshare.protocol.MessageType.FILE_CHUNK) {
                        val chunk = message.fileChunkData
                        Log.d("CommunicationHandler", "sendMessage: FILE_CHUNK, chunk size: ${chunk?.size}")
                        objectOutputStream?.reset()
                    }
                    objectOutputStream?.writeObject(message)
                    objectOutputStream?.flush()
                }
                Log.d("CommunicationHandler", "Sent message: Type: ${message.type}, Folder: ${message.folderName}")
                true
            } catch (e: IOException) {
                Log.e("CommunicationHandler", "Error sending message: ${e.message}", e)
                false
            }
        }
    }
    
    /**
     * Start listening for incoming messages
     */
    private fun startMessageListener() {
        messageListenerJob?.cancel()
        messageListenerJob = scope.launch(Dispatchers.IO) {
            Log.i("CommunicationHandler", "Started listening for incoming messages...")
            
            while (isActive && objectInputStream != null) {
                try {
                    val message = objectInputStream?.readObject() as? SyncMessage
                    message?.let {
                        Log.d("CommunicationHandler", "Received message: Type: ${it.type}, Folder: ${it.folderName}")
                        _incomingMessages.emit(it)
                    }
                } catch (e: IOException) {
                    if (isActive) {
                        Log.e("CommunicationHandler", "IOException while listening for messages: ${e.message}", e)
                        // Emit a special message to indicate connection loss
                        try {
                            _incomingMessages.emit(
                                SyncMessage(
                                    type = com.example.syncshare.protocol.MessageType.ERROR_MESSAGE,
                                    errorMessage = "Connection lost: ${e.message}"
                                )
                            )
                        } catch (emitException: Exception) {
                            Log.e("CommunicationHandler", "Failed to emit connection loss message", emitException)
                        }
                    }
                    break
                } catch (e: ClassNotFoundException) {
                    Log.e("CommunicationHandler", "ClassNotFoundException while listening: ${e.message}", e)
                    try {
                        _incomingMessages.emit(
                            SyncMessage(
                                type = com.example.syncshare.protocol.MessageType.ERROR_MESSAGE,
                                errorMessage = "Protocol error"
                            )
                        )
                    } catch (emitException: Exception) {
                        Log.e("CommunicationHandler", "Failed to emit protocol error message", emitException)
                    }
                    break
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e("CommunicationHandler", "Unexpected error listening for messages: ${e.message}", e)
                        try {
                            _incomingMessages.emit(
                                SyncMessage(
                                    type = com.example.syncshare.protocol.MessageType.ERROR_MESSAGE,
                                    errorMessage = "Communication error"
                                )
                            )
                        } catch (emitException: Exception) {
                            Log.e("CommunicationHandler", "Failed to emit communication error message", emitException)
                        }
                    }
                    break
                }
            }
            
            Log.i("CommunicationHandler", "Stopped listening for messages")
        }
    }
    
    /**
     * Close all streams and cleanup resources
     */
    fun cleanup() {
        Log.d("CommunicationHandler", "Cleaning up communication handler...")
        
        messageListenerJob?.cancel()
        messageListenerJob = null
        
        try { 
            objectInputStream?.close() 
        } catch (e: IOException) { 
            Log.w("CommunicationHandler", "Error closing input stream: ${e.message}") 
        }
        
        try { 
            objectOutputStream?.close() 
        } catch (e: IOException) { 
            Log.w("CommunicationHandler", "Error closing output stream: ${e.message}") 
        }
        
        objectInputStream = null
        objectOutputStream = null
        isInitialized = false
        
        Log.d("CommunicationHandler", "Communication handler cleanup complete")
    }
    
    /**
     * Check if the handler is ready to send/receive messages
     */
    fun isReady(): Boolean = isInitialized && objectOutputStream != null && objectInputStream != null
}
