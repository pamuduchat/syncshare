package com.example.syncshare.ui.model

/**
 * A wrapper data class to represent a discovered device in the UI,
 * abstracting the underlying technology-specific device object.
 *
 * @param id A unique identifier for the device (e.g., MAC address, P2P device address).
 * @param name The display name of the device.
 * @param details Additional information or status about the device (e.g., "Wi-Fi P2P - Available", "Bluetooth - Paired", "IP:Port").
 * @param technology The [DeviceTechnology] used to discover or connect to this device.
 * @param originalDeviceObject The raw platform device object (e.g., WifiP2pDevice, BluetoothDevice, NsdServiceInfo).
 *                             This is kept to allow technology-specific operations like connection.
 */
data class DisplayableDevice(
    val id: String,
    val name: String,
    val details: String,
    val technology: DeviceTechnology,
    val originalDeviceObject: Any // Store the WifiP2pDevice, BluetoothDevice, etc.
) {
    // Optional: Override equals and hashCode if you want to ensure distinctness based on id and technology
    // By default, data class will use all primary constructor properties.
    // If 'originalDeviceObject' causes issues with equality/state updates (e.g. if it's mutable or lacks good equals/hashCode),
    // you might exclude it or provide custom implementations. For now, default should be fine.

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DisplayableDevice

        if (id != other.id) return false
        if (technology != other.technology) return false
        // Consider if name changes should make it "unequal" for UI updates
        // if (name != other.name) return false
        // if (details != other.details) return false


        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + technology.hashCode()
        // result = 31 * result + name.hashCode()
        // result = 31 * result + details.hashCode()
        return result
    }
}