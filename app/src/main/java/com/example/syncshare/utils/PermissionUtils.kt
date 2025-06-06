package com.example.syncshare.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat

// Single permission
@Composable
fun rememberPermissionLauncher(
    onResult: (Boolean) -> Unit
): ManagedActivityResultLauncher<String, Boolean> {
    return rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = onResult
    )
}

// Multiple permissions
@Composable
fun rememberPermissionsLauncher(
    onResult: (Map<String, Boolean>) -> Unit
): ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>> {
    return rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = onResult
    )
}

fun Context.hasPermission(permission: String): Boolean {
    return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

fun Context.hasLocationPermissions(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
                hasPermission(Manifest.permission.BLUETOOTH_SCAN) && // Often needed with fine location for nearby
                hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // Android 10 & 11
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    } else { // Below Android 10
        hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) &&
                hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

// Specific for Wi-Fi Direct which needs fine location
fun getWifiDirectPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES
        )
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // Android 10, 11, 12
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    } else { // Below Android 10
        arrayOf(
            Manifest.permission.ACCESS_COARSE_LOCATION, // Often good to request both
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }
}

// Storage permissions (pre-Scoped Storage, mostly for READ to list files)
// For folder selection using SAF
fun getOldStoragePermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) { // Android 9 and below
        arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}
fun isLocationEnabled(context: Context): Boolean {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    if (locationManager == null) {
        Log.w("PermissionUtils", "LocationManager service not available.")
        return false
    }

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { // API 28+
        locationManager.isLocationEnabled
    } else { // Below API 28
        try {
            @Suppress("DEPRECATION")
            val locationMode = Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE)
            locationMode != Settings.Secure.LOCATION_MODE_OFF
        } catch (e: Settings.SettingNotFoundException) {
            Log.w("PermissionUtils", "LOCATION_MODE setting not found, checking providers.", e)
            // Fallback to checking providers if LOCATION_MODE setting is not found
            try {
                val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                val networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                gpsEnabled || networkEnabled
            } catch (ex: Exception) {
                Log.e("PermissionUtils", "Exception checking location providers", ex)
                false
            }
        } catch (e: Exception) { // Catch any other unexpected errors
            Log.e("PermissionUtils", "Unexpected error checking location mode", e)
            false
        }
    }
}

fun getBluetoothPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // API 31+
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION // Often required for discovery on older versions
        )
    }
}