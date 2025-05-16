package com.example.syncshare.features

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.example.syncshare.viewmodels.DevicesViewModel

class WifiDirectBroadcastReceiver(
    private val manager: WifiP2pManager?,
    private val channel: WifiP2pManager.Channel?,
    private val viewModel: DevicesViewModel // Or an interface to update UI
) : BroadcastReceiver() {

    @SuppressLint("MissingPermission") // Permissions checked before discovery
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    Log.d("WifiDirectReceiver", "Wi-Fi P2P is enabled")
                    // Wifi P2P is enabled. Interface with ViewModel or Activity.
                } else {
                    Log.d("WifiDirectReceiver", "Wi-Fi P2P is not enabled")
                    // Wi-Fi P2P is not enabled.
                }
            }
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                Log.d("WifiDirectReceiver", "P2P peers changed")
                // Request available peers from the wifi p2p manager.
                // This is an asynchronous call and the calling activity/fragment
                // is notified with a callback on PeerListListener.onPeersAvailable()
                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION // Or appropriate nearby devices perm for API 31+
                    ) == PackageManager.PERMISSION_GRANTED // Ensure permission is still granted
                ) {
                    manager?.requestPeers(channel) { peers ->
                        Log.d("WifiDirectReceiver", "Peers available: ${peers.deviceList.size}")
                        viewModel.onPeersAvailable(peers.deviceList)
                    }
                } else {
                    Log.w("WifiDirectReceiver", "Location permission not granted for requesting peers.")
                    // Handle missing permission case, maybe notify user
                }
            }
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                // Connection state changed.
                Log.d("WifiDirectReceiver", "P2P connection changed")
            }
            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                // This device's details have changed.
                Log.d("WifiDirectReceiver", "This device details changed")
            }
        }
    }
}