package com.example.syncshare.protocol

import java.io.Serializable

data class FileTransferInfo(
    val relativePath: String,
    val fileSize: Long
) : Serializable
