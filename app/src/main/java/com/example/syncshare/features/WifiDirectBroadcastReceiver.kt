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
                Log.d("WifiDirectReceiver", "WIFI_P2P_PEERS_CHANGED_ACTION received.")
                // ACCESS_FINE_LOCATION is generally required to get the peer list.
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    Log.d("WifiDirectReceiver", "ACCESS_FINE_LOCATION for requestPeers is GRANTED.")
                    manager?.requestPeers(channel) { peers -> // This is WifiP2pManager.PeerListListener
                        Log.i("WifiDirectReceiver", "PeerListListener.onPeersAvailable | System peer list size: ${peers?.deviceList?.size ?: "null"}")
                        if (peers != null) {
                            viewModel.onPeersAvailable(peers.deviceList ?: emptyList())
                        } else {
                            Log.w("WifiDirectReceiver", "PeerListListener.onPeersAvailable received NULL peers object.")
                            viewModel.onPeersAvailable(emptyList())
                        }
                    }
                } else {
                    Log.e("WifiDirectReceiver", "ACCESS_FINE_LOCATION permission NOT granted when WIFI_P2P_PEERS_CHANGED_ACTION received. Cannot request peers.")
                    viewModel.onPeersAvailable(emptyList()) // Inform ViewModel no peers can be fetched
                }
            }
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                // Connection state changed.
                Log.d("WifiDirectReceiver", "P2P connection changed")
            }
            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                val thisDevice = intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                Log.d("WifiDirectReceiver", "WIFI_P2P_THIS_DEVICE_CHANGED_ACTION - This device: ${thisDevice?.deviceName}, Status: ${getDeviceStatus(thisDevice?.status ?: -1)}")
                // You could update a LiveData/StateFlow in ViewModel with thisDevice object
            }
        }
    }

    fun getDeviceStatus(deviceStatus: Int): String {
        return when (deviceStatus) {
            WifiP2pDevice.AVAILABLE -> "Available"
            WifiP2pDevice.INVITED -> "Invited"
            WifiP2pDevice.CONNECTED -> "Connected"
            WifiP2pDevice.FAILED -> "Failed"
            WifiP2pDevice.UNAVAILABLE -> "Unavailable"
            else -> "Unknown ($deviceStatus)"
        }
    }
}