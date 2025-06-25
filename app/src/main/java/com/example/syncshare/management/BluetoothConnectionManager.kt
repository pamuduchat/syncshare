package com.example.syncshare.management

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import com.example.syncshare.utils.AppConstants
import com.example.syncshare.utils.getBluetoothPermissions
import com.example.syncshare.utils.isLocationEnabled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class BluetoothConnectionManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    
    // StateFlow for discovered Bluetooth devices
    private val _discoveredDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<BluetoothDevice>> = _discoveredDevices.asStateFlow()
    
    // StateFlow for connection status
    private val _connectionStatus = MutableStateFlow<String>("Disconnected")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()
    
    // StateFlow for scanning status
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    
    // StateFlow for status message
    private val _statusMessage = MutableStateFlow("Idle. Ready for Bluetooth operations.")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()
    
    // StateFlow for connected socket (null when not connected)
    private val _connectedSocket = MutableStateFlow<BluetoothSocket?>(null)
    val connectedSocket: StateFlow<BluetoothSocket?> = _connectedSocket.asStateFlow()
    
    // StateFlow for Bluetooth enabled state
    private val _isBluetoothEnabled = MutableStateFlow(false)
    val isBluetoothEnabled: StateFlow<Boolean> = _isBluetoothEnabled.asStateFlow()
    
    // StateFlow for connected device address (null when not connected)
    private val _connectedDeviceAddress = MutableStateFlow<String?>(null)
    val connectedDeviceAddress: StateFlow<String?> = _connectedDeviceAddress.asStateFlow()
    
    private val bluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager?.adapter
    }
    
    private var bluetoothScanReceiver: BroadcastReceiver? = null
    private var discoveryTimeoutJob: Job? = null
    private val DISCOVERY_TIMEOUT_MS = 15000L
    
    private var bluetoothServerJob: Job? = null
    private var btServerSocket: BluetoothServerSocket? = null
    
    init {
        Log.d("BluetoothConnectionManager", "Initializing BluetoothConnectionManager")
        updateBluetoothState()
    }
    
    fun updateBluetoothState() {
        val enabled = bluetoothAdapter?.isEnabled == true
        _isBluetoothEnabled.value = enabled
        Log.d("BluetoothConnectionManager", "Bluetooth state updated. Enabled: $enabled")
    }
    
    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        Log.i("BluetoothConnectionManager", "startDiscovery() - ENTRY")
        updateBluetoothState()
        
        if (bluetoothAdapter == null) {
            _isScanning.value = false
            _statusMessage.value = "Bluetooth not supported."
            return
        }
        
        if (!_isBluetoothEnabled.value) {
            _isScanning.value = false
            _statusMessage.value = "Bluetooth is OFF."
            return
        }

        if (!isLocationEnabled(context)) {
            Log.w("BluetoothConnectionManager", "BT_DISC_FAIL: System Location Services are OFF.")
            _statusMessage.value = "Location Services OFF. Enable for BT discovery."
            _isScanning.value = false
            return
        }
        
        if (!getBluetoothPermissions().all { ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            Log.e("BluetoothConnectionManager", "BT_DISC_FAIL: Missing BT permissions.")
            _statusMessage.value = "Bluetooth permissions needed."
            _isScanning.value = false
            return
        }
        
        Log.d("BluetoothConnectionManager", "BT_DISC: All necessary Bluetooth permissions granted.")

        if (bluetoothAdapter?.isDiscovering == true) {
            Log.d("BluetoothConnectionManager", "BT_DISC: Already discovering. Cancelling first.")
            try {
                bluetoothAdapter?.cancelDiscovery()
            } catch (e: SecurityException) {
                Log.e("BluetoothConnectionManager", "SecEx BT cancelDiscovery (isDiscovering): ${e.message}", e)
            }
        }

        _discoveredDevices.value = emptyList()
        _isScanning.value = true
        _statusMessage.value = "Scanning for Bluetooth devices..."

        if (bluetoothScanReceiver == null) {
            bluetoothScanReceiver = object : BroadcastReceiver() {
                @SuppressLint("MissingPermission")
                override fun onReceive(context: Context, intent: Intent) {
                    val action: String? = intent.action
                    Log.d("BTScanReceiver", "onReceive: action=$action")
                    
                    when (action) {
                        BluetoothDevice.ACTION_FOUND -> {
                            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                            }
                            device?.let {
                                Log.d("BTScanReceiver", "ACTION_FOUND: Raw Name: ${try{it.name}catch(e:SecurityException){"N/A (SecEx)"} ?: "No Name"}, Address: ${it.address}")
                                handleBluetoothDeviceFound(it)
                            } ?: Log.w("BTScanReceiver", "ACTION_FOUND: Device is null")
                        }
                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                            Log.d("BTScanReceiver", "ACTION_DISCOVERY_FINISHED")
                            handleBluetoothDiscoveryFinished()
                        }
                        BluetoothAdapter.ACTION_STATE_CHANGED -> {
                            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                            val oldState = _isBluetoothEnabled.value
                            _isBluetoothEnabled.value = state == BluetoothAdapter.STATE_ON
                            Log.d("BTScanReceiver", "ACTION_STATE_CHANGED: Bluetooth state $oldState -> ${_isBluetoothEnabled.value}")
                            
                            if (state == BluetoothAdapter.STATE_OFF && _isScanning.value) {
                                _isScanning.value = false
                                _statusMessage.value = "Bluetooth turned off during scan."
                                _discoveredDevices.value = emptyList()
                                stopDiscovery()
                            }
                        }
                    }
                }
            }
            
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(bluetoothScanReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(bluetoothScanReceiver, filter)
                }
                Log.d("BluetoothConnectionManager", "Bluetooth Scan Receiver registered.")
            } catch (e: Exception) {
                Log.e("BluetoothConnectionManager", "Error registering BT Scan Receiver: ${e.message}", e)
                _isScanning.value = false
                _statusMessage.value = "Error setting up BT scan."
                return
            }
        }

        try {
            if (bluetoothAdapter?.startDiscovery() == false) {
                Log.e("BluetoothConnectionManager", "BT_DISC_FAIL: startDiscovery() returned false.")
                _isScanning.value = false
                _statusMessage.value = "Failed to start BT scan (denied)."
                stopDiscovery()
            } else {
                Log.i("BluetoothConnectionManager", "BT_DISC: Discovery request sent to BluetoothAdapter.")
                discoveryTimeoutJob?.cancel()
                discoveryTimeoutJob = scope.launch {
                    delay(DISCOVERY_TIMEOUT_MS)
                    if (isActive && _isScanning.value) {
                        Log.w("BluetoothConnectionManager", "Bluetooth Discovery timed out after ${DISCOVERY_TIMEOUT_MS}ms.")
                        _statusMessage.value = "Bluetooth scan timed out."
                        stopDiscovery()
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e("BluetoothConnectionManager", "SecEx BT startDiscovery: ${e.message}", e)
            _isScanning.value = false
            _statusMessage.value = "PermErr starting BT scan."
            stopDiscovery()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        Log.d("BluetoothConnectionManager", "stopDiscovery() called.")
        discoveryTimeoutJob?.cancel()
        
        if (bluetoothAdapter?.isDiscovering == true) {
            try {
                bluetoothAdapter?.cancelDiscovery()
            } catch (e: SecurityException) {
                Log.e("BluetoothConnectionManager", "SecEx BT cancelDiscovery: ${e.message}", e)
            }
            Log.d("BluetoothConnectionManager", "Bluetooth discovery explicit stop/cancel initiated.")
        }
        
        if (bluetoothScanReceiver != null) {
            try {
                context.unregisterReceiver(bluetoothScanReceiver)
                Log.d("BluetoothConnectionManager", "Bluetooth scan receiver unregistered.")
            } catch (e: IllegalArgumentException) {
                Log.w("BluetoothConnectionManager", "Error unreg BT receiver: ${e.message}")
            } finally {
                bluetoothScanReceiver = null
            }
        }
        
        if (_isScanning.value) {
            _isScanning.value = false
            _statusMessage.value = "Bluetooth scan stopped."
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleBluetoothDeviceFound(device: BluetoothDevice) {
        val currentDevices = _discoveredDevices.value.toMutableList()
        
        if (!currentDevices.any { it.address == device.address }) {
            var deviceName: String? = "Unknown BT Device"
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        deviceName = device.name
                    } else {
                        deviceName = "Name N/A (No CONNECT Perm)"
                    }
                } else {
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED) {
                        deviceName = device.name
                    } else {
                        deviceName = "Name N/A (No BT Perm)"
                    }
                }
            } catch (se: SecurityException) {
                deviceName = "Name N/A (SecEx)"
            }
            
            Log.d("BluetoothConnectionManager", "BT_DEVICE_ADDED: ${deviceName ?: "(Unnamed)"} - ${device.address}")
            currentDevices.add(device)
            _discoveredDevices.value = currentDevices
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleBluetoothDiscoveryFinished() {
        Log.i("BluetoothConnectionManager", "BT_DISC_FINISHED (received from broadcast).")
        discoveryTimeoutJob?.cancel()
        
        if (_isScanning.value) {
            _isScanning.value = false
        }
        
        val deviceCount = _discoveredDevices.value.size
        if (deviceCount == 0) {
            _statusMessage.value = "No new Bluetooth devices found."
        } else {
            _statusMessage.value = "$deviceCount Bluetooth device(s) found."
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        val deviceNameForLog = try { device.name } catch(e: SecurityException){ null } ?: device.address
        Log.i("BluetoothConnectionManager", "connectToDevice called for: $deviceNameForLog")

        if (bluetoothAdapter?.isDiscovering == true) {
            Log.d("BluetoothConnectionManager", "connectToBT - Cancelling discovery before connecting.")
            try {
                bluetoothAdapter?.cancelDiscovery()
            } catch (e: SecurityException) {
                Log.e("BluetoothConnectionManager", "SecEx BT cancelDisc for connect: ${e.message}", e)
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e("BluetoothConnectionManager", "connectToBT - Missing BLUETOOTH_CONNECT permission.")
            _statusMessage.value = "BT Connect permission needed."
            _connectionStatus.value = "Error: Permission Missing"
            return
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
            Log.e("BluetoothConnectionManager", "connectToBT - Missing BLUETOOTH permission for connect (API <31).")
            _statusMessage.value = "BT permission needed."
            _connectionStatus.value = "Error: Permission Missing"
            return
        }

        _statusMessage.value = "Connecting to BT: $deviceNameForLog..."
        _connectionStatus.value = "Connecting..."
        _connectedDeviceAddress.value = device.address

        scope.launch(Dispatchers.IO) {
            var socket: BluetoothSocket? = null
            try {
                Log.d("BluetoothConnectionManager", "Creating RFCOMM socket to service with UUID: ${AppConstants.BLUETOOTH_SERVICE_UUID}")
                socket = device.createRfcommSocketToServiceRecord(AppConstants.BLUETOOTH_SERVICE_UUID)
                Log.d("BluetoothConnectionManager", "Attempting to connect socket...")
                socket.connect()
                Log.i("BluetoothConnectionManager", "Bluetooth connection established with $deviceNameForLog")
                
                withContext(Dispatchers.Main) {
                    _connectedSocket.value = socket
                    _statusMessage.value = "Connected via BT to $deviceNameForLog"
                    _connectionStatus.value = "Connected to $deviceNameForLog"
                }
            } catch (e: IOException) {
                Log.e("BluetoothConnectionManager", "Bluetooth connection failed for $deviceNameForLog: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "BT Connection Failed: ${e.localizedMessage}"
                    _connectionStatus.value = "Connection Failed"
                    _connectedDeviceAddress.value = null
                }
                try {
                    socket?.close()
                } catch (closeException: IOException) {
                    Log.e("BluetoothConnectionManager", "Could not close client socket post-failure", closeException)
                }
            } catch (se: SecurityException) {
                Log.e("BluetoothConnectionManager", "SecurityException during BT connection: ${se.message}", se)
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "BT Connection Permission Error"
                    _connectionStatus.value = "Error: Permission Denied"
                    _connectedDeviceAddress.value = null
                }
            }
        }
    }

    fun disconnect() {
        scope.launch(Dispatchers.IO) {
            try {
                _connectedSocket.value?.close()
            } catch (e: IOException) {
                Log.e("BluetoothConnectionManager", "Could not close connected Bluetooth socket during disconnect: ${e.message}")
            }
            
            withContext(Dispatchers.Main) {
                _connectedSocket.value = null
                _connectedDeviceAddress.value = null
                _connectionStatus.value = "Disconnected"
                _statusMessage.value = "Bluetooth disconnected"
            }
            
            Log.i("BluetoothConnectionManager", "Bluetooth disconnected.")
        }
    }

    @SuppressLint("MissingPermission")
    fun startServer() {
        if (bluetoothServerJob?.isActive == true) {
            Log.d("BluetoothConnectionManager", "BT server job already active.")
            return
        }
        
        if (bluetoothAdapter == null || !_isBluetoothEnabled.value) {
            Log.e("BluetoothConnectionManager", "Cannot start BT server: Adapter null or BT disabled.")
            _statusMessage.value = "Cannot start BT server: BT not ready."
            return
        }

        val connectPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
        if (ActivityCompat.checkSelfPermission(context, connectPerm) != PackageManager.PERMISSION_GRANTED) {
            Log.e("BluetoothConnectionManager", "Missing $connectPerm permission for BT server.")
            _statusMessage.value = "BT Connect perm needed for server."
            return
        }

        bluetoothServerJob = scope.launch(Dispatchers.IO) {
            Log.i("BluetoothConnectionManager", "Starting Bluetooth server thread...")
            _statusMessage.value = "Bluetooth server starting..."
            var tempSocket: BluetoothSocket?
            
            try {
                btServerSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(AppConstants.BLUETOOTH_SERVICE_NAME, AppConstants.BLUETOOTH_SERVICE_UUID)
                Log.d("BluetoothConnectionManager", "BT ServerSocket listening with UUID: ${AppConstants.BLUETOOTH_SERVICE_UUID}")
                
                while (isActive) {
                    try {
                        Log.d("BluetoothConnectionManager", "BT server calling btServerSocket.accept()...")
                        tempSocket = btServerSocket?.accept()
                    } catch (e: IOException) {
                        if (isActive) {
                            Log.e("BluetoothConnectionManager", "BT server socket accept() failed or closed.", e)
                        } else {
                            Log.d("BluetoothConnectionManager", "BT server socket accept() interrupted by cancellation.")
                        }
                        break
                    }
                    
                    tempSocket?.let { socket ->
                        val remoteDeviceName = try {
                            socket.remoteDevice.name
                        } catch(e: SecurityException) {
                            null
                        } ?: socket.remoteDevice.address
                        
                        Log.i("BluetoothConnectionManager", "BT connection accepted from: $remoteDeviceName")
                        handleAcceptedConnection(socket)
                    }
                }
            } catch (e: IOException) {
                Log.e("BluetoothConnectionManager", "BT server listenUsingRfcomm failed", e)
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "BT Server Error: ${e.message}"
                }
            } catch (se: SecurityException) {
                Log.e("BluetoothConnectionManager", "SecEx starting BT server: ${se.message}", se)
                withContext(Dispatchers.Main) {
                    _statusMessage.value = "BT Server Permission Error"
                }
            } finally {
                Log.d("BluetoothConnectionManager", "Bluetooth server thread ending.")
                try {
                    btServerSocket?.close()
                } catch (e: IOException) {
                    Log.e("BluetoothConnectionManager", "Could not close BT server socket on exit: ${e.message}")
                }
                btServerSocket = null
            }
        }
    }

    fun stopServer() {
        Log.i("BluetoothConnectionManager", "Stopping Bluetooth server...")
        bluetoothServerJob?.cancel()
        bluetoothServerJob = null
        _statusMessage.value = "Bluetooth server stopped."
    }

    @SuppressLint("MissingPermission")
    private fun handleAcceptedConnection(socket: BluetoothSocket) {
        val remoteDeviceName = try {
            socket.remoteDevice.name
        } catch(e: SecurityException) {
            null
        } ?: socket.remoteDevice.address
        
        Log.i("BluetoothConnectionManager", "Handling accepted BT connection from $remoteDeviceName")
        
        // Add the connected device to discovered devices if it's not already there
        val currentDevices = _discoveredDevices.value.toMutableList()
        if (!currentDevices.any { it.address == socket.remoteDevice.address }) {
            Log.d("BluetoothConnectionManager", "Adding accepted connection device to discovered list: ${socket.remoteDevice.address}")
            currentDevices.add(socket.remoteDevice)
            _discoveredDevices.value = currentDevices
        }
        
        scope.launch(Dispatchers.Main) {
            _connectedSocket.value = socket
            _connectedDeviceAddress.value = socket.remoteDevice.address
            _connectionStatus.value = "Accepted connection from $remoteDeviceName"
            _statusMessage.value = "BT Peer connected: $remoteDeviceName"
        }
    }

    /**
     * Called by external components (like DevicesViewModel) when they detect a communication failure
     */
    fun notifyConnectionLost() {
        Log.i("BluetoothConnectionManager", "External notification of connection loss")
        scope.launch(Dispatchers.Main) {
            _connectedSocket.value = null
            _connectedDeviceAddress.value = null
            _connectionStatus.value = "Disconnected"
            _statusMessage.value = "Remote device disconnected"
        }
    }

    fun prepareService() {
        updateBluetoothState()
        if (_isBluetoothEnabled.value) {
            val connectPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
            if (ActivityCompat.checkSelfPermission(context, connectPerm) == PackageManager.PERMISSION_GRANTED) {
                startServer()
            } else {
                Log.w("BluetoothConnectionManager", "BT_PREPARE: Missing $connectPerm. BT Server not started.")
            }
        } else {
            Log.w("BluetoothConnectionManager", "BT_PREPARE: Bluetooth not enabled, cannot start server.")
        }
    }

    fun cleanup() {
        scope.launch {
            stopDiscovery()
            stopServer()
            disconnect()
        }
    }

}
