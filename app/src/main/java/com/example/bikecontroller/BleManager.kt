package com.example.bikecontroller

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*

enum class BleState {
    Disconnected,
    Connecting,
    Connected,
    RouteSent,
    NavigationActive
}

class BleManager(private val context: Context) {

    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    
    private val _connectionState = MutableStateFlow(BleState.Disconnected)
    val connectionState = _connectionState.asStateFlow()

    private val _speed = MutableStateFlow(0f)
    val speed = _speed.asStateFlow()
    
    private val _distance = MutableStateFlow(0)
    val distance = _distance.asStateFlow()

    private val SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ab")
    private val RX_UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ad")
    private val TX_UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ac")
    private val CLIENT_CONFIG_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    
    private var lastDevice: BluetoothDevice? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pendingCommand: String? = null

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        lastDevice = device
        _connectionState.value = BleState.Connecting
        Log.d("BleManager", "Connecting to ${device.address}")
        
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, callback)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _connectionState.value = BleState.Connected
                // Request higher MTU to support longer ROUTE packets
                gatt.requestMtu(512)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connectionState.value = BleState.Disconnected
                rxChar = null
                handler.postDelayed({
                    if (_connectionState.value == BleState.Disconnected) lastDevice?.let { connect(it) }
                }, 5000)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                rxChar = service?.getCharacteristic(RX_UUID)
                
                service?.getCharacteristic(TX_UUID)?.let { txChar ->
                    gatt.setCharacteristicNotification(txChar, true)
                    txChar.getDescriptor(CLIENT_CONFIG_DESCRIPTOR)?.let { descriptor ->
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    }
                }
                
                pendingCommand?.let {
                    send(it)
                    pendingCommand = null
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == TX_UUID) {
                val data = characteristic.value.toString(Charsets.UTF_8)
                parseTelemetry(data)
            }
        }
    }

    private fun parseTelemetry(data: String) {
        try {
            when {
                data.startsWith("SPD:") -> _speed.value = data.substring(4).toFloat()
                data.startsWith("DIST:") -> _distance.value = data.substring(5).toInt()
            }
        } catch (e: Exception) {
            Log.e("BleManager", "Telemetry error: $data")
        }
    }

    @SuppressLint("MissingPermission")
    fun send(cmd: String) {
        val char = rxChar ?: run {
            pendingCommand = cmd
            gatt?.discoverServices()
            return
        }
        
        val data = cmd.toByteArray()
        
        // We MUST use WRITE_TYPE_DEFAULT (Write with response) for long packets like ROUTE:
        // This allows the Android system to automatically handle Long Writes (MTU > payload).
        // Manual chunking would break the ESP32 side which parses the whole characteristic.
        val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt?.writeCharacteristic(char, data, writeType)
        } else {
            @Suppress("DEPRECATION")
            char.value = data
            char.writeType = writeType
            @Suppress("DEPRECATION")
            gatt?.writeCharacteristic(char)
        }

        if (cmd.startsWith("ROUTE:")) _connectionState.value = BleState.RouteSent
        Log.d("BleManager", "Sent command: $cmd")
    }

    fun startRide() {
        _connectionState.value = BleState.NavigationActive
        send("START")
    }

    fun stopRide() {
        _connectionState.value = BleState.Connected
        send("STOP")
    }

    fun resetRide() = send("RESET")

    fun sendRoute(points: List<Pair<Double, Double>>) {
        if (points.isEmpty()) return
        // Take 20 points as per ESP32 firmware MAX_POINTS
        val routeString = "ROUTE:" + points.take(20).joinToString(";") { (lat, lon) ->
            String.format(java.util.Locale.US, "%.5f,%.5f", lat, lon)
        }
        send(routeString)
    }
}
