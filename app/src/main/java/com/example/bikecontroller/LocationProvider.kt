package com.example.bikecontroller

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LocationProvider(
    context: Context,
    private val fusedLocationClient: FusedLocationProviderClient
) {

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private val _isLocationEnabled = MutableStateFlow(false)
    val isLocationEnabled: StateFlow<Boolean> = _isLocationEnabled.asStateFlow()

    private var locationCallback: LocationCallback? = null

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            5000 // 5 seconds
        ).setMinUpdateDistanceMeters(10f).build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation
                if (location != null) {
                    _currentLocation.value = location
                    _isLocationEnabled.value = true
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                null
            )
        } catch (e: Exception) {
            _isLocationEnabled.value = false
        }
    }

    fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        _isLocationEnabled.value = false
    }

    @SuppressLint("MissingPermission")
    fun getLastLocation(callback: (Location?) -> Unit) {
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            callback(location)
        }.addOnFailureListener {
            callback(null)
        }
    }
}
