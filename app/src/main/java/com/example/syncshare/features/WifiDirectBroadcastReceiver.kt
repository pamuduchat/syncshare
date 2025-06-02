package com.example.syncshare.features

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pInfo
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import com.example.syncshare.viewmodels.DevicesViewModel
import com.example.syncshare.utils.getDeviceP2pStatusString

class WifiDirectBroadcastReceiver(
    private val manager: WifiP2pManager?,
    private val channel: WifiP2pManager.Channel?,
    private val viewModel: DevicesViewModel
) : BroadcastReceiver() {

    @SuppressLint("MissingPermission") // Permissions are expected to be checked by the caller initiating discovery/connection
    override fun onReceive(context: Context, intent: Intent) {
        val action: String? = intent.action

        when (action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    Log.d("WifiDirectReceiver", "Wi-Fi P2P is enabled")
                } else {
                    Log.d("WifiDirectReceiver", "Wi-Fi P2P is not enabled")
                }
            }
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                Log.d("WifiDirectReceiver", "WIFI_P2P_PEERS_CHANGED_ACTION received.")
                // ACCESS_FINE_LOCATION is required to get the peer list.
                // NEARBY_WIFI_DEVICES is for initiating discovery/connection on API 33+.
                // For requestPeers, ACCESS_FINE_LOCATION is the key.
                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    Log.d("WifiDirectReceiver", "ACCESS_FINE_LOCATION for requestPeers is GRANTED. Requesting peers...")
                    manager?.requestPeers(channel, object : WifiP2pManager.PeerListListener {
                        override fun onPeersAvailable(peerList: WifiP2pDeviceList?) {
                            Log.i("WifiDirectReceiver", "PeerListListener.onPeersAvailable | System peer list size: ${peerList?.deviceList?.size ?: "null"}")
                            if (peerList != null) {
                                // Corrected call to the renamed method in ViewModel
                                viewModel.onP2pPeersAvailable(peerList.deviceList ?: emptyList())
                            } else {
                                Log.w("WifiDirectReceiver", "PeerListListener.onPeersAvailable received NULL peerList object.")
                                viewModel.onP2pPeersAvailable(emptyList())
                            }
                        }
                    })
                } else {
                    Log.e("WifiDirectReceiver", "ACCESS_FINE_LOCATION permission NOT granted when WIFI_P2P_PEERS_CHANGED_ACTION received. Cannot request peers.")
                    viewModel.onP2pPeersAvailable(emptyList()) // Inform ViewModel no peers can be fetched
                }
            }
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                Log.d("WifiDirectReceiver", "WIFI_P2P_CONNECTION_CHANGED_ACTION received.")
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    Log.d("WifiDirectReceiver", "Requesting P2P connection info...")
                    manager?.requestConnectionInfo(channel, object : WifiP2pManager.ConnectionInfoListener {
                        override fun onConnectionInfoAvailable(info: WifiP2pInfo) {
                            Log.i("WifiDirectReceiver", "Connection info available. Group formed: ${info.groupFormed}, Is GO: ${info.isGroupOwner}")
                            viewModel.handleP2pConnectionInfo(info)
                        }
                    })
                } else {
                    Log.e("WifiDirectReceiver", "ACCESS_FINE_LOCATION permission NOT granted for WIFI_P2P_CONNECTION_CHANGED_ACTION.")
                }
            }
            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                val thisDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                }
                Log.d("WifiDirectReceiver", "WIFI_P2P_THIS_DEVICE_CHANGED_ACTION - This device: ${thisDevice?.deviceName}, Status: ${getDeviceP2pStatusString(thisDevice?.status ?: -1)}")
            }
        }
    }
}