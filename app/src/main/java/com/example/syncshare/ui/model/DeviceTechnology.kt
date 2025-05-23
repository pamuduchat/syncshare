package com.example.syncshare.ui.model

/**
 * Enum to represent the technology through which a device was discovered or is connected.
 */
enum class DeviceTechnology {
    WIFI_DIRECT,
    BLUETOOTH_CLASSIC,
    // BLUETOOTH_LE, // Placeholder for future Low Energy Bluetooth support
    // NSD,          // Placeholder for future Network Service Discovery over WLAN support
    UNKNOWN
}