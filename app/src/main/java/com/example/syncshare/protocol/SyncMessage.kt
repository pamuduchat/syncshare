package com.example.syncshare.protocol

import java.io.Serializable

data class SyncMessage(
    val type: MessageType,
    val folderName: String? = null,          // For SYNC_REQUEST_METADATA
    val fileMetadataList: List<FileMetadata>? = null, // For SYNC_REQUEST_METADATA
    val requestedFilePaths: List<String>? = null,   // For FILES_REQUESTED_BY_PEER
    val fileTransferInfo: FileTransferInfo? = null, // For FILE_TRANSFER_START / FILE_TRANSFER_END
    val fileChunkData: ByteArray? = null,         // For FILE_CHUNK
    val chunkOffset: Long? = null,                // For FILE_CHUNK, if needed for resume
    val errorMessage: String? = null             // For ERROR_MESSAGE
) : Serializable {
    // Custom equals and hashCode for ByteArray comparison if you directly embed chunks
    // For now, relying on default behavior. If issues arise, implement these.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SyncMessage
        // ... compare all fields ...
        if (fileChunkData != null) {
            if (other.fileChunkData == null) return false
            if (!fileChunkData.contentEquals(other.fileChunkData)) return false
        } else if (other.fileChunkData != null) return false
        return true
    }
    override fun hashCode(): Int {
        var result = type.hashCode()
        // ... include other fields ...
        result = 31 * result + (fileChunkData?.contentHashCode() ?: 0)
        return result
    }
}