package com.example.syncshare.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.syncshare.protocol.FileTransferInfo
import com.example.syncshare.protocol.SyncMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream

/**
 * Handles file receiving operations including:
 * - Creating destination files/folders
 * - Writing file chunks
 * - Managing file transfer state
 */
class FileTransferManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    // Transfer state
    data class FileTransferState(
        val folderName: String,
        val relativePath: String,
        val totalSize: Long,
        var bytesReceived: Long = 0L,
        val destinationBaseUri: Uri,
        val originalPath: String = relativePath
    )

    private var currentReceivingFile: FileTransferState? = null
    private var currentFileOutputStream: OutputStream? = null
    private var isTransferActive = false
    
    // Track processed files to prevent duplicate processing
    private val processedFiles = mutableSetOf<String>()

    private val _transferStatus = MutableStateFlow("Idle")
    val transferStatus: StateFlow<String> = _transferStatus

    // Communication
    private var communicationHandler: Any? = null

    fun setCommunicationHandler(handler: Any?) {
        communicationHandler = handler
    }

    /**
     * Starts receiving a file
     */
    fun startFileReceive(
        fileInfo: FileTransferInfo,
        folderName: String,
        destinationUri: Uri,
        finalPath: String = fileInfo.relativePath,
        onStatusUpdate: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            Log.d(
                "FileTransferManager",
                "Starting to receive file: ${fileInfo.relativePath} -> $finalPath"
            )

            // Check if this file was already processed
            if (processedFiles.contains(fileInfo.relativePath)) {
                Log.w("FileTransferManager", "File ${fileInfo.relativePath} was already processed, ignoring duplicate start")
                return
            }

            // Check if a transfer is already active
            if (isTransferActive && currentReceivingFile != null) {
                val currentFile = currentReceivingFile!!
                if (currentFile.relativePath == finalPath) {
                    Log.w("FileTransferManager", "Transfer for ${finalPath} is already active, ignoring duplicate")
                    return
                } else {
                    Log.w("FileTransferManager", "Transfer for ${currentFile.relativePath} is active, cannot start ${finalPath}")
                    onError("Another file transfer is already in progress: ${currentFile.relativePath}")
                    return
                }
            }

            // Clear any previous state
            cleanup()
            
            currentReceivingFile = FileTransferState(
                folderName = folderName,
                relativePath = finalPath,
                totalSize = fileInfo.fileSize,
                destinationBaseUri = destinationUri,
                originalPath = fileInfo.relativePath
            )
            
            isTransferActive = true
            Log.d("FileTransferManager", "Transfer state set to active for: ${fileInfo.relativePath}")

            val displayPath = if (finalPath != fileInfo.relativePath) {
                "$finalPath (renamed from ${fileInfo.relativePath})"
            } else {
                finalPath
            }

            _transferStatus.value = "Receiving: $displayPath"
            onStatusUpdate("Receiving: $displayPath")

        } catch (e: Exception) {
            Log.e("FileTransferManager", "Error starting file receive", e)
            onError("Failed to start receiving file: ${e.message}")
        }
    }

    /**
     * Appends a chunk of data to the current file being received
     */
    fun appendFileChunk(chunk: ByteArray, onError: (String) -> Unit): Boolean {
        val state = currentReceivingFile ?: run {
            onError("No file is currently being received")
            return false
        }

        try {
            // Check if we would exceed the expected file size
            val potentialBytesReceived = state.bytesReceived + chunk.size
            if (potentialBytesReceived > state.totalSize) {
                Log.w(
                    "FileTransferManager",
                    "Chunk would exceed expected file size for ${state.relativePath}. Current: ${state.bytesReceived}, Chunk: ${chunk.size}, Expected: ${state.totalSize}"
                )
                
                // Only write up to the expected size
                val remainingBytes = (state.totalSize - state.bytesReceived).toInt()
                if (remainingBytes <= 0) {
                    Log.w("FileTransferManager", "File ${state.relativePath} already complete (${state.bytesReceived}/${state.totalSize} bytes), ignoring chunk of ${chunk.size} bytes")
                    return true
                }
                
                Log.i("FileTransferManager", "Truncating chunk from ${chunk.size} to $remainingBytes bytes to prevent overrun for ${state.relativePath}")
                val truncatedChunk = chunk.sliceArray(0 until remainingBytes)
                
                if (currentFileOutputStream == null) {
                    currentFileOutputStream = createFileOutputStream(state)
                    if (currentFileOutputStream == null) {
                        onError("Failed to create output stream for ${state.relativePath}")
                        return false
                    }
                }
                
                currentFileOutputStream?.write(truncatedChunk)
                state.bytesReceived += truncatedChunk.size
                
                // Log completion when expected size is reached
                if (state.bytesReceived == state.totalSize) {
                    Log.i("FileTransferManager", "File ${state.relativePath} reached expected size (${state.totalSize} bytes) with this truncated chunk")
                }
            } else {
                if (currentFileOutputStream == null) {
                    currentFileOutputStream = createFileOutputStream(state)
                    if (currentFileOutputStream == null) {
                        onError("Failed to create output stream for ${state.relativePath}")
                        return false
                    }
                }

                currentFileOutputStream?.write(chunk)
                state.bytesReceived += chunk.size
                
                // Flush periodically for data integrity (every 64KB)
                if (state.bytesReceived % 65536 == 0L) {
                    try {
                        currentFileOutputStream?.flush()
                        Log.v("FileTransferManager", "Periodic flush at ${state.bytesReceived} bytes for ${state.relativePath}")
                    } catch (e: IOException) {
                        Log.w("FileTransferManager", "Warning: Failed to flush output stream during transfer", e)
                    }
                }
            }

            Log.d(
                "FileTransferManager",
                "Received ${state.bytesReceived}/${state.totalSize} bytes for ${state.relativePath}"
            )

            return true

        } catch (e: IOException) {
            Log.e("FileTransferManager", "Error writing file chunk", e)
            cleanup()
            onError("Error writing file: ${e.message}")
            return false
        }
    }

    /**
     * Finalizes the current file being received
     */
    fun finalizeFileReceive(
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    ): String? {
        Log.d("FileTransferManager", "finalizeFileReceive called. Current state: ${currentReceivingFile?.relativePath ?: "NULL"}, isTransferActive: $isTransferActive")
        
        val state = currentReceivingFile
        if (state == null || !isTransferActive) {
            Log.w("FileTransferManager", "finalizeFileReceive called but no active file transfer. State: ${state?.relativePath ?: "NULL"}, Active: $isTransferActive")
            onError("No file is currently being received")
            return null
        }
        
        // Check if this file was already processed
        if (processedFiles.contains(state.originalPath)) {
            Log.w("FileTransferManager", "File ${state.originalPath} was already processed, ignoring duplicate finalize call")
            return state.originalPath
        }

        return try {
            Log.d("FileTransferManager", "Finalizing file: ${state.relativePath} (${state.bytesReceived}/${state.totalSize} bytes)")
            
            // Ensure all data is written to disk before closing
            try {
                currentFileOutputStream?.flush()
                Log.d("FileTransferManager", "File output stream flushed for ${state.relativePath}")
            } catch (e: IOException) {
                Log.w("FileTransferManager", "Warning: Failed to flush output stream for ${state.relativePath}", e)
            }
            
            currentFileOutputStream?.close()
            currentFileOutputStream = null
            Log.d("FileTransferManager", "File output stream closed for ${state.relativePath}")

            // Validate file size
            if (state.bytesReceived != state.totalSize) {
                Log.w(
                    "FileTransferManager",
                    "File size mismatch for ${state.relativePath}: received ${state.bytesReceived} bytes, expected ${state.totalSize} bytes"
                )
                
                if (state.bytesReceived > state.totalSize) {
                    Log.e("FileTransferManager", "File ${state.relativePath} received MORE bytes than expected - possible corruption")
                    onError("File ${state.relativePath} corrupted: received ${state.bytesReceived} bytes, expected ${state.totalSize}")
                    cleanup()
                    return null
                } else {
                    Log.e("FileTransferManager", "File ${state.relativePath} incomplete: received ${state.bytesReceived} bytes, expected ${state.totalSize}")
                    onError("File ${state.relativePath} incomplete: received ${state.bytesReceived} bytes, expected ${state.totalSize}")
                    cleanup()
                    return null
                }
            }

            val displayPath = if (state.originalPath != state.relativePath) {
                "${state.relativePath} (renamed from ${state.originalPath})"
            } else {
                state.relativePath
            }

            Log.d(
                "FileTransferManager",
                "Successfully received file: $displayPath (${state.bytesReceived} bytes)"
            )

            _transferStatus.value = "Received: $displayPath"
            onComplete("Received: $displayPath")

            val originalPath = state.originalPath
            
            // Mark as processed and clear state
            processedFiles.add(originalPath)
            currentReceivingFile = null
            isTransferActive = false

            originalPath

        } catch (e: Exception) {
            Log.e("FileTransferManager", "Error finalizing file", e)
            cleanup()
            onError("Error finalizing file: ${e.message}")
            null
        }
    }

    /**
     * Cancels the current file transfer
     */
    fun cancelCurrentTransfer() {
        cleanup()
        _transferStatus.value = "Transfer cancelled"
    }

    private fun createFileOutputStream(state: FileTransferState): OutputStream? {
        try {
            var parentDir = DocumentFile.fromTreeUri(context, state.destinationBaseUri)
            if (parentDir == null || !parentDir.isDirectory) {
                Log.e("FileTransferManager", "Invalid destination URI: ${state.destinationBaseUri}")
                return null
            }

            val pathSegments = state.relativePath.split('/').dropLastWhile { it.isEmpty() }
            val fileName = pathSegments.last()

            // Create intermediate directories
            for (i in 0 until pathSegments.size - 1) {
                val dirName = pathSegments[i]
                var existingDir = parentDir?.findFile(dirName)
                if (existingDir == null) {
                    existingDir = parentDir?.createDirectory(dirName)
                    Log.d("FileTransferManager", "Created directory: $dirName")
                }
                parentDir = existingDir
            }

            if (parentDir == null) {
                Log.e(
                    "FileTransferManager",
                    "Failed to create parent directories for ${state.relativePath}"
                )
                return null
            }

            // Create or find the file
            var targetFile = parentDir.findFile(fileName)
            if (targetFile == null || !targetFile.isFile) {
                // Determine MIME type
                val mimeType = when (fileName.substringAfterLast('.', "").lowercase()) {
                    "txt" -> "text/plain"
                    "jpg", "jpeg" -> "image/jpeg"
                    "png" -> "image/png"
                    "pdf" -> "application/pdf"
                    "mp3" -> "audio/mpeg"
                    "mp4" -> "video/mp4"
                    else -> "application/octet-stream"
                }

                targetFile = parentDir.createFile(mimeType, fileName)
                Log.d(
                    "FileTransferManager",
                    "Created new file: $fileName with MIME type: $mimeType"
                )
            } else {
                Log.d("FileTransferManager", "File already exists, will overwrite: $fileName")
            }

            if (targetFile == null) {
                Log.e("FileTransferManager", "Failed to create file: $fileName")
                return null
            }

            return context.contentResolver.openOutputStream(targetFile.uri, "wt")

        } catch (e: Exception) {
            Log.e("FileTransferManager", "Error creating output stream", e)
            return null
        }
    }

    private fun cleanup() {
        try {
            currentFileOutputStream?.close()
        } catch (e: IOException) {
            Log.e("FileTransferManager", "Error closing output stream", e)
        }
        currentFileOutputStream = null
        currentReceivingFile = null
        isTransferActive = false
        Log.d("FileTransferManager", "Transfer state cleaned up")
    }

    fun getCurrentTransferState(): FileTransferState? = currentReceivingFile

    /**
     * Check if there's an active file transfer
     */
    fun hasActiveTransfer(): Boolean = isTransferActive && currentReceivingFile != null

    /**
     * Handles FILE_TRANSFER_START message
     */
    fun handleFileTransferStart(
        message: SyncMessage,
        destinationUri: Uri?,
        fileRenameMap: (String) -> String?,
        onStatusUpdate: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val fileTransferInfo = message.fileTransferInfo
            val folderName = message.folderName

            if (fileTransferInfo == null || folderName == null) {
                onError("Invalid file transfer info")
                return
            }

            Log.d("FileTransferManager", "handleFileTransferStart - File: ${fileTransferInfo.relativePath}, Size: ${fileTransferInfo.fileSize}, Folder: $folderName")

            val finalDestinationUri = destinationUri ?: run {
                onError("No destination URI for folder: $folderName")
                return
            }

            // Check for rename mapping
            val finalPath =
                fileRenameMap(fileTransferInfo.relativePath) ?: fileTransferInfo.relativePath

            Log.d("FileTransferManager", "Final path after rename mapping: $finalPath")

            startFileReceive(
                fileInfo = fileTransferInfo,
                folderName = folderName,
                destinationUri = finalDestinationUri,
                finalPath = finalPath,
                onStatusUpdate = onStatusUpdate,
                onError = onError
            )

        } catch (e: Exception) {
            Log.e("FileTransferManager", "Error handling file transfer start", e)
            onError("Error starting file transfer: ${e.message}")
        }
    }

    /**
     * Handles FILE_CHUNK message
     */
    fun handleFileChunk(chunkData: ByteArray) {
        val currentState = currentReceivingFile
        
        // First check if this file was already processed (more specific)
        if (currentState != null && processedFiles.contains(currentState.originalPath)) {
            Log.w("FileTransferManager", "File ${currentState.originalPath} already complete, ignoring chunk")
            return
        }
        
        // Check if there is a valid receiving state
        if (currentState == null) {
            Log.w("FileTransferManager", "Ignoring chunk - no active receiving file")
            return
        }
        
        // Check if transfer is still active
        if (!isTransferActive) {
            Log.w("FileTransferManager", "Ignoring chunk - transfer is no longer active for ${currentState.originalPath}")
            return
        }
        
        val success = appendFileChunk(chunkData) { error ->
            Log.e("FileTransferManager", "Error handling chunk: $error")
        }

        if (!success) {
            Log.e("FileTransferManager", "Failed to append file chunk for ${currentState.originalPath}")
        }
    }

    /**
     * Handles FILE_TRANSFER_END message
     */
    fun handleFileTransferEnd(
        message: SyncMessage,
        onStatusUpdate: (String) -> Unit,
        onComplete: (String) -> Unit
    ) {
        try {
            val fileTransferInfo = message.fileTransferInfo
            val originalPath = fileTransferInfo?.relativePath ?: ""
            
            Log.d("FileTransferManager", "handleFileTransferEnd called for: $originalPath")
            Log.d("FileTransferManager", "Current receiving file state: ${currentReceivingFile?.relativePath ?: "NULL"}")
            Log.d("FileTransferManager", "Transfer active: $isTransferActive")
            Log.d("FileTransferManager", "Already processed: ${processedFiles.contains(originalPath)}")

            // Check if this file was already processed
            if (processedFiles.contains(originalPath)) {
                Log.w("FileTransferManager", "Ignoring duplicate FILE_TRANSFER_END for already processed file: $originalPath")
                return
            }
            
            // Check if we have an active transfer
            if (currentReceivingFile == null || !isTransferActive) {
                Log.w("FileTransferManager", "Ignoring FILE_TRANSFER_END - no active transfer")
                return
            }
            
            // Check if the message is for the current file being received
            val currentState = currentReceivingFile
            if (currentState != null && currentState.originalPath != originalPath) {
                Log.w("FileTransferManager", "FILE_TRANSFER_END for different file: expected ${currentState.originalPath}, got $originalPath")
                return
            }

            finalizeFileReceive(
                onComplete = {
                    onStatusUpdate("File received: $originalPath")
                    onComplete(originalPath)
                },
                onError = { error ->
                    Log.e("FileTransferManager", "Error finalizing file: $error")
                    onStatusUpdate("Error finalizing file: $error")
                }
            )

        } catch (e: Exception) {
            Log.e("FileTransferManager", "Error handling file transfer end", e)
            onStatusUpdate("Error ending file transfer: ${e.message}")
        }
    }

    /**
     * Clears processed file tracking (call at start of new sync session)
     */
    fun clearProcessedFiles() {
        processedFiles.clear()
        Log.d("FileTransferManager", "Cleared processed files tracking")
    }
    
    /**
     * Force cleanup of stuck transfers (useful for recovery)
     */
    fun forceCleanup() {
        Log.w("FileTransferManager", "Force cleanup called - clearing stuck transfer state")
        cleanup()
        processedFiles.clear()
        Log.d("FileTransferManager", "Force cleanup completed")
    }
    
    /**
     * Get current transfer status for debugging
     */
    fun getTransferDebugInfo(): String {
        val state = currentReceivingFile
        return buildString {
            appendLine("Transfer Debug Info:")
            appendLine("- isTransferActive: $isTransferActive")
            appendLine("- currentReceivingFile: ${state?.relativePath ?: "NULL"}")
            if (state != null) {
                appendLine("- bytesReceived: ${state.bytesReceived}/${state.totalSize}")
                appendLine("- originalPath: ${state.originalPath}")
            }
            appendLine("- processedFiles count: ${processedFiles.size}")
            appendLine("- processedFiles: $processedFiles")
        }
    }
}
