package com.example.syncshare.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SyncHistoryEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val folderName: String, // Or Uri.toString()
    val status: String, // e.g., "Started", "Completed", "Error"
    val details: String,
    val peerDeviceName: String? = null // Optional: name of the device synced with
) {
    val formattedTimestamp: String
        get() {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
}
