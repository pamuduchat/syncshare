package com.example.syncshare.protocol

import java.io.Serializable

enum class MessageType : Serializable {
    SYNC_REQUEST_METADATA, // Client sends its list of files in a folder
    SYNC_METADATA_RESPONSE, // Server responds with its own list of files for two-way sync
    FILES_REQUESTED_BY_PEER, // Server responds with list of files it needs
    FILE_TRANSFER_START,     // Client indicates start of a file transfer
    FILE_CHUNK,              // A chunk of file data
    FILE_TRANSFER_END,       // Client indicates end of a file transfer
    FILE_RECEIVED_ACK,       // Server acknowledges a file
    SYNC_COMPLETE,           // Client indicates all requested files sent
    ERROR_MESSAGE,
    DISCONNECT,
}
