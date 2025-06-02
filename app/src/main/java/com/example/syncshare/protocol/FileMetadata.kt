package com.example.syncshare.protocol

import java.io.Serializable

data class FileMetadata(
    val relativePath: String, // Path relative to the root sync folder, e.g., "images/vacation/img1.jpg"
    val name: String,         // Just the file name, e.g., "img1.jpg"
    val size: Long,
    val lastModified: Long,
    val hash: String // New: hash of file contents for robust sync
) : Serializable // Implement Serializable for easy sending over ObjectOutputStream