package com.example.syncshare.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.syncshare.data.SyncHistoryEntry
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages sync history persistence and retrieval
 */
class SyncHistoryManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("syncshare_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val historyKey = "sync_history"
    
    private val _syncHistory = MutableStateFlow<List<SyncHistoryEntry>>(emptyList())
    val syncHistory: StateFlow<List<SyncHistoryEntry>> = _syncHistory
    
    init {
        loadHistory()
    }
    
    /**
     * Adds a new entry to the sync history
     */
    fun addEntry(entry: SyncHistoryEntry) {
        val currentList = _syncHistory.value.toMutableList()
        currentList.add(0, entry) // Add to the beginning
        _syncHistory.value = currentList
        persistHistory()
        
        Log.d("SyncHistoryManager", "Added history entry: ${entry.folderName} - ${entry.status}")
    }
    
    /**
     * Clears all sync history
     */
    fun clearHistory() {
        _syncHistory.value = emptyList()
        persistHistory()
        Log.d("SyncHistoryManager", "Cleared all sync history")
    }
    
    /**
     * Gets the current sync history list
     */
    fun getHistory(): List<SyncHistoryEntry> = _syncHistory.value
    
    private fun loadHistory() {
        try {
            val json = prefs.getString(historyKey, null)
            if (json != null) {
                val type = object : TypeToken<List<SyncHistoryEntry>>() {}.type
                val list: List<SyncHistoryEntry> = gson.fromJson(json, type) ?: emptyList()
                _syncHistory.value = list
                Log.d("SyncHistoryManager", "Loaded ${list.size} history entries")
            }
        } catch (e: Exception) {
            Log.e("SyncHistoryManager", "Error loading sync history", e)
            _syncHistory.value = emptyList()
        }
    }
    
    private fun persistHistory() {
        try {
            val json = gson.toJson(_syncHistory.value)
            prefs.edit().putString(historyKey, json).apply()
        } catch (e: Exception) {
            Log.e("SyncHistoryManager", "Error persisting sync history", e)
        }
    }
}
