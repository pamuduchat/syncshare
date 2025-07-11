package com.example.syncshare.utils


import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.security.MessageDigest

fun computeFileHash(context: Context, file: DocumentFile): String {
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(file.uri)?.use { inputStream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        Log.d("FileUtils", "Computed hash for '${file.name}': $hash")
        return hash
    } catch (e: Exception) {
        Log.e("FileUtils", "Error computing hash for file: ${file.name} (${file.uri})", e)
        return ""
    }
}