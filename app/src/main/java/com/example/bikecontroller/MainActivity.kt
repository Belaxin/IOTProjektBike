package com.example.bikecontroller

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.preference.PreferenceManager
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.app.ActivityCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import org.osmdroid.config.Configuration
import java.util.*

class MainActivity : ComponentActivity() {

    private lateinit var bleManager: BleManager
    private var bluetoothAdapter: BluetoothAdapter? = null

    private val SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ab")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize osmdroid configuration for map tile caching
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))

        bleManager = BleManager(this)
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        setContent {
            val navViewModel: NavigationViewModel = viewModel(
                factory = NavigationViewModelFactory(bleManager, applicationContext)
            )
            
            LaunchedEffect(Unit) {
                checkPermissionsAndScan()
            }

            NavigationScreen(viewModel = navViewModel)
        }
    }

    private fun checkPermissionsAndScan() {
        val required = getRequiredPermissions()
        val missing = required.filter { 
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED 
        }

        if (missing.isEmpty()) {
            ensureBluetoothEnabled()
        } else {
            requestPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun ensureBluetoothEnabled() {
        if (bluetoothAdapter?.isEnabled == false) {
            btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            scanForBike()
        }
    }

    private val btEnableLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) scanForBike()
    }

    private fun getRequiredPermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return perms.toTypedArray()
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) ensureBluetoothEnabled()
    }

    @SuppressLint("MissingPermission")
    private fun scanForBike() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        Toast.makeText(this, "Searching for bike...", Toast.LENGTH_SHORT).show()

        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                scanner.stopScan(this)
                bleManager.connect(result.device)
            }
        }
        scanner.startScan(listOf(filter), settings, callback)
    }
}
