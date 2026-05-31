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
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.app.ActivityCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.osmdroid.config.Configuration
import java.util.*

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Map : Screen("map", "Map", Icons.Default.Map)
    object Rides : Screen("rides", "Rides", Icons.Default.History)
    object Debug : Screen("debug", "Debug", Icons.Default.BugReport)
    object RideDetails : Screen("rideDetails/{rideId}", "Details", Icons.Default.History)
}

class MainActivity : ComponentActivity() {

    private lateinit var bleManager: BleManager
    private var bluetoothAdapter: BluetoothAdapter? = null

    private val SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ab")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))

        bleManager = BleManager(this)
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        setContent {
            val navViewModel: NavigationViewModel = viewModel(
                factory = NavigationViewModelFactory(bleManager, applicationContext)
            )
            val navController = rememberNavController()
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination
            
            LaunchedEffect(Unit) {
                checkPermissionsAndScan()
            }

            Scaffold(
                bottomBar = {
                    val currentRoute = currentDestination?.route
                    if (currentRoute == Screen.Map.route || currentRoute == Screen.Rides.route || currentRoute == Screen.Debug.route) {
                        NavigationBar(
                            containerColor = Color.Black,
                            contentColor = Color.White
                        ) {
                            val items = listOf(Screen.Map, Screen.Rides, Screen.Debug)
                            items.forEach { screen ->
                                NavigationBarItem(
                                    icon = { Icon(screen.icon, contentDescription = screen.label) },
                                    label = { Text(screen.label) },
                                    selected = currentDestination?.route == screen.route,
                                    onClick = {
                                        navController.navigate(screen.route) {
                                            popUpTo(navController.graph.startDestinationId) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color.Cyan,
                                        selectedTextColor = Color.Cyan,
                                        unselectedIconColor = Color.Gray,
                                        unselectedTextColor = Color.Gray,
                                        indicatorColor = Color.DarkGray
                                    )
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                NavHost(
                    navController = navController,
                    startDestination = Screen.Map.route,
                    modifier = Modifier.padding(innerPadding)
                ) {
                    composable(Screen.Map.route) {
                        NavigationScreen(viewModel = navViewModel)
                    }
                    composable(Screen.Rides.route) {
                        RidesScreen(onRideClick = { id ->
                            navController.navigate("rideDetails/$id")
                        })
                    }
                    composable(Screen.Debug.route) {
                        DebugScreen()
                    }
                    composable(
                        route = Screen.RideDetails.route,
                        arguments = listOf(navArgument("rideId") { type = NavType.IntType })
                    ) { backStackEntry ->
                        val rideId = backStackEntry.arguments?.getInt("rideId") ?: 0
                        RideDetailsScreen(rideId = rideId, onBack = {
                            navController.popBackStack()
                        })
                    }
                }
            }
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
