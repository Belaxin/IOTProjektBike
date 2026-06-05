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

    private val _hasGpsFix = MutableStateFlow(false)
    val hasGpsFix = _hasGpsFix.asStateFlow()

    private val SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ab")
    private val RX_UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ad")
    private val TX_UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ac")
    private val CLIENT_CONFIG_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    
    private var lastDevice: BluetoothDevice? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pendingCommand: String? = null
    private val ackLock = Object()
    @Volatile private var lastAckCount: Int = -1

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
                DebugLogger.log("CONNECTED")
                gatt.requestMtu(512)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connectionState.value = BleState.Disconnected
                DebugLogger.log("DISCONNECTED")
                rxChar = null
                handler.postDelayed({
                    if (_connectionState.value == BleState.Disconnected) lastDevice?.let { connect(it) }
                }, 5000)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            DebugLogger.log("SERVICES DISCOVERED: $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                gatt.services.forEach { service ->
                    DebugLogger.log("Svc: ${service.uuid}")
                    service.characteristics.forEach { char ->
                        DebugLogger.log("  Char: ${char.uuid}")
                    }
                }

                val service = gatt.getService(SERVICE_UUID)
                rxChar = service?.getCharacteristic(RX_UUID)
                
                service?.getCharacteristic(TX_UUID)?.let { txChar ->
                    DebugLogger.log("TX CHAR FOUND")
                    gatt.setCharacteristicNotification(txChar, true)
                    txChar.getDescriptor(CLIENT_CONFIG_DESCRIPTOR)?.let { descriptor ->
                        DebugLogger.log("DESCRIPTOR FOUND")
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
            val dataBytes = characteristic.value
            val dataString = if (dataBytes != null) String(dataBytes, Charsets.UTF_8) else "NULL"
            DebugLogger.log("CHG: ${characteristic.uuid.toString().takeLast(4)} -> $dataString")
            
            if (characteristic.uuid == TX_UUID && dataBytes != null) {
                parseTelemetry(dataString)
            }
        }
    }

    private fun parseTelemetry(data: String) {
        val trimmed = data.trim()
        DebugLogger.log("RX: [$trimmed]")
        
        try {
            when {
                trimmed.startsWith("STA:") -> {
                    val parts = trimmed.substring(4).split(",")
                    if (parts.size >= 3) {
                        _hasGpsFix.value = parts[0] == "1"
                        _speed.value = parts[1].toFloatOrNull() ?: 0f
                        _distance.value = parts[2].toIntOrNull() ?: 0
                    }
                }
                trimmed.startsWith("SPD:") -> _speed.value = trimmed.substring(4).toFloat()
                trimmed.startsWith("DIST:") -> _distance.value = trimmed.substring(5).toInt()
                trimmed.contains("GPS:1") -> _hasGpsFix.value = true
                trimmed.contains("GPS:0") -> _hasGpsFix.value = false
                trimmed.startsWith("ROUTE_ACK:") -> {
                    val n = trimmed.substringAfter(":").toIntOrNull() ?: -1
                    if (n >= 0) {
                        lastAckCount = n
                        synchronized(ackLock) { ackLock.notifyAll() }
                        DebugLogger.log("ACK: $n")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("BleManager", "Parse error for: [$trimmed]", e)
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

    fun pauseRide() {
        send("PAUSE")
    }

    fun resumeRide() {
        send("RESUME")
    }

    fun resetRide() = send("RESET")

    fun sendRoute(points: List<Pair<Double, Double>>) {
        if (points.isEmpty()) return
        val routeString = "ROUTE:" + points.joinToString(";") { (lat, lon) ->
            String.format(java.util.Locale.US, "%.5f,%.5f", lat, lon)
        }
        send(routeString)
    }

    fun sendRouteChunked(points: List<Pair<Double, Double>>) {
        if (points.isEmpty()) return

        // Run on background thread to avoid blocking UI / causing ANR
        Thread {
            val chunkSize = 10
            val chunks = points.chunked(chunkSize)
            Log.d("BleManager", "Sending route in ${chunks.size} chunks (${points.size} total waypoints)")

            lastAckCount = -1
            send("ROUTE_START:${points.size}")

            // Wait for ROUTE_START acknowledgement before sending chunks
            val startAckStart = System.currentTimeMillis()
            synchronized(ackLock) {
                while (lastAckCount < 0 && (System.currentTimeMillis() - startAckStart) < 5000) {
                    try { ackLock.wait(5000) } catch (_: InterruptedException) { }
                }
            }
            if (lastAckCount < 0) {
                Log.e("BleManager", "ROUTE_START not acknowledged, aborting route send")
                return@Thread
            }

            var sentSoFar = 0
            var aborted = false
            chunks.forEachIndexed { idx, chunk ->
                if (aborted) return@forEachIndexed
                val expectedAfter = sentSoFar + chunk.size
                val chunkString = "ROUTE_DATA:" + chunk.joinToString(";") { (lat, lon) ->
                    String.format(java.util.Locale.US, "%.5f,%.5f", lat, lon)
                } + ";"

                var attempts = 0
                val maxAttempts = 3
                var acked = false

                while (attempts < maxAttempts && !acked) {
                    attempts++
                    send(chunkString)

                    val start = System.currentTimeMillis()
                    synchronized(ackLock) {
                        while (lastAckCount < expectedAfter && (System.currentTimeMillis() - start) < 5000) {
                            try { ackLock.wait(5000) } catch (_: InterruptedException) { }
                        }
                    }

                    if (lastAckCount >= expectedAfter) {
                        acked = true
                        DebugLogger.log("Chunk ${idx + 1}/${chunks.size} acked: $lastAckCount")
                    } else {
                        DebugLogger.log("Chunk ${idx + 1}/${chunks.size} not acked, retry $attempts")
                        try { Thread.sleep(200) } catch (_: InterruptedException) { }
                    }
                }

                if (!acked) {
                    Log.e("BleManager", "Failed to deliver chunk ${idx + 1} after $maxAttempts attempts")
                    aborted = true
                    return@forEachIndexed
                }

                sentSoFar = expectedAfter
            }

            if (!aborted) {
                send("ROUTE_END")
                val startFinal = System.currentTimeMillis()
                synchronized(ackLock) {
                    while (lastAckCount < points.size && (System.currentTimeMillis() - startFinal) < 5000) {
                        try { ackLock.wait(5000) } catch (_: InterruptedException) { }
                    }
                }
                DebugLogger.log("Route send complete; acked ${lastAckCount} / ${points.size}")
            } else {
                Log.e("BleManager", "Route send aborted due to failed chunk")
            }
        }.start()
    }
}
