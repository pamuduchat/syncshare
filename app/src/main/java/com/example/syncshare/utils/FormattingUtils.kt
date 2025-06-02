package com.example.syncshare.utils

import android.bluetooth.BluetoothDevice
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager

fun getDeviceP2pStatusString(deviceStatus: Int): String {
    return when (deviceStatus) {
        WifiP2pDevice.AVAILABLE -> "Available"
        WifiP2pDevice.INVITED -> "Invited"
        WifiP2pDevice.CONNECTED -> "Connected"
        WifiP2pDevice.FAILED -> "Failed"
        WifiP2pDevice.UNAVAILABLE -> "Unavailable"
        else -> "Unknown P2P ($deviceStatus)"
    }
}

fun getBluetoothBondState(bondState: Int): String {
    return when (bondState) {
        BluetoothDevice.BOND_NONE -> "Not Paired"
        BluetoothDevice.BOND_BONDING -> "Pairing..."
        BluetoothDevice.BOND_BONDED -> "Paired"
        else -> "Bond State Unknown ($bondState)"
    }
}

fun getFailureReasonString(reasonCode: Int): String {
    return when (reasonCode) {
        WifiP2pManager.ERROR -> "ERROR"
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
        WifiP2pManager.BUSY -> "BUSY"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS"
        else -> "UNKNOWN ($reasonCode)"
    }
}

fun getDetailedFailureReasonString(reasonCode: Int): String {
    val baseReason = getFailureReasonString(reasonCode) // Call the function above
    return when (reasonCode) {
        WifiP2pManager.ERROR -> "$baseReason (Generic P2P Error) - System might be temporarily unavailable. Consider resetting Wi-Fi Direct or restarting the app/device."
        WifiP2pManager.P2P_UNSUPPORTED -> "$baseReason - This device does not support Wi-Fi Direct."
        WifiP2pManager.BUSY -> "$baseReason - The Wi-Fi Direct system is busy. Wait or reset Wi-Fi Direct."
        WifiP2pManager.NO_SERVICE_REQUESTS -> "$baseReason - No active service discovery requests."
        else -> "$baseReason - An unknown P2P error occurred ($reasonCode). Try resetting."
    }
}