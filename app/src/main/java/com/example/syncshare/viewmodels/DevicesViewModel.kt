package com.example.syncshare.viewmodels

import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.syncshare.features.WifiDirectBroadcastReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DevicesViewModel(application: Application) : AndroidViewModel(application) {
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    val discoveredPeers = mutableStateListOf<WifiP2pDevice>()
    val permissionRequestStatus = mutableStateOf("") // For simple feedback

    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    init {
        viewModelScope.launch {
            initializeWifiP2p()
        }
    }

    private fun initializeWifiP2p() {
        val context = getApplication<Application>().applicationContext
        wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        if (wifiP2pManager == null) {
            Log.e("DevicesViewModel", "Cannot get WifiP2pManager service.")
            permissionRequestStatus.value = "Wi-Fi P2P not available on this device."
            return
        }
        channel = wifiP2pManager?.initialize(context, Looper.getMainLooper(), null)
        channel?.also {
            // Receiver setup will be done once permissions are granted
            Log.d("DevicesViewModel", "Wi-Fi P2P Initialized. Channel: $it")
        } ?: run {
            Log.e("DevicesViewModel", "Failed to initialize Wi-Fi P2P channel.")
            permissionRequestStatus.value = "Failed to initialize Wi-Fi P2P."
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    fun startDiscovery() {
        if (channel == null) {
            permissionRequestStatus.value = "Wi-Fi P2P not initialized."
            Log.e("DevicesViewModel", "Channel is null, cannot start discovery.")
            _isRefreshing.value = false
            return
        }
        _isRefreshing.value = true
        discoveredPeers.clear() // Clear previous results

        wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                permissionRequestStatus.value = "Discovery Initiated"
                Log.d("DevicesViewModel", "Peer discovery initiated successfully.")
                // Peers will be found via BroadcastReceiver
            }

            override fun onFailure(reasonCode: Int) {
                permissionRequestStatus.value = "Discovery Failed: $reasonCode"
                Log.e("DevicesViewModel", "Peer discovery initiation failed. Reason: $reasonCode")
                _isRefreshing.value = false
            }
        })
    }

    fun registerReceiver() {
        val context = getApplication<Application>().applicationContext
        if (receiver == null) {
            receiver = WifiDirectBroadcastReceiver(wifiP2pManager, channel, this)
            context.registerReceiver(receiver, intentFilter)
            Log.d("DevicesViewModel", "BroadcastReceiver registered.")
        }
    }

    fun unregisterReceiver() {
        val context = getApplication<Application>().applicationContext
        if (receiver != null) {
            try {
                context.unregisterReceiver(receiver)
                receiver = null // Important to nullify after unregistering
                Log.d("DevicesViewModel", "BroadcastReceiver unregistered.")
            } catch (e: IllegalArgumentException) {
                Log.e("DevicesViewModel", "Receiver not registered or already unregistered: ${e.message}")
            }
        }
    }


    // Called from BroadcastReceiver
    fun onPeersAvailable(peers: Collection<WifiP2pDevice>) {
        viewModelScope.launch { // Ensure UI updates are on the main thread for Compose
            _isRefreshing.value = false // Stop refreshing indicator
            discoveredPeers.clear()
            discoveredPeers.addAll(peers)
            if (peers.isEmpty()) {
                Log.d("DevicesViewModel", "No peers found.")
                permissionRequestStatus.value = "No devices found."
            } else {
                Log.d("DevicesViewModel", "Peers found: ${peers.size}")
                peers.forEach { Log.d("DevicesViewModel", "Device: ${it.deviceName} - ${it.deviceAddress} - Status: ${it.status}") }
                permissionRequestStatus.value = "${peers.size} device(s) found."
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        unregisterReceiver()
        wifiP2pManager?.removeGroup(channel, null)
    }
}