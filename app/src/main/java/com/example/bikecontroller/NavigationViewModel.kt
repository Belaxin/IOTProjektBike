package com.example.bikecontroller

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class NavigationViewModel(private val bleManager: BleManager, context: Context) : ViewModel() {

    private val _routePoints = MutableStateFlow<List<GeoPoint>>(emptyList())
    val routePoints: StateFlow<List<GeoPoint>> = _routePoints.asStateFlow()

    private val _destination = MutableStateFlow<GeoPoint?>(null)
    val destination: StateFlow<GeoPoint?> = _destination.asStateFlow()

    private val _currentLocation = MutableStateFlow<GeoPoint?>(null)
    val currentLocation: StateFlow<GeoPoint?> = _currentLocation.asStateFlow()

    // Step-based navigation
    private val _navigationSteps = MutableStateFlow<List<NavigationStep>>(emptyList())
    val navigationSteps: StateFlow<List<NavigationStep>> = _navigationSteps.asStateFlow()

    private val _currentStepIndex = MutableStateFlow(0)
    val currentStepIndex: StateFlow<Int> = _currentStepIndex.asStateFlow()

    private val _currentInstruction = MutableStateFlow("Ready")
    val currentInstruction: StateFlow<String> = _currentInstruction.asStateFlow()

    private val _distanceToNextManeuver = MutableStateFlow(0)
    val distanceToNextManeuver: StateFlow<Int> = _distanceToNextManeuver.asStateFlow()

    private var lastInstructionTime = 0L
    private var lastInstruction = ""
    private val instructionCooldownMs = 5000L // 5 second cooldown

    private var lastRerouteTime = 0L
    private val rerouteCooldownMs = 10000L // 10 second cooldown

    private var lastStableHeading = 0f
    private val minSpeedForHeading = 3f // km/h

    val bleState = bleManager.connectionState
    val speed = bleManager.speed
    val distance = bleManager.distance

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    
    private val routeService = Retrofit.Builder()
        .baseUrl("https://router.project-osrm.org/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(RouteService::class.java)

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let {
                _currentLocation.value = GeoPoint(it.latitude, it.longitude)
                it.bearing?.let { bearing ->
                    if (it.speed >= minSpeedForHeading) {
                        lastStableHeading = bearing
                    }
                }
                updateNavigation()
                Log.d("NavVM", "Location: ${it.latitude}, ${it.longitude} | Speed: ${String.format("%.1f", it.speed / 3.6)} km/h")
            }
        }
    }

    init {
        startLocationUpdates()
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
            .setMinUpdateIntervalMillis(1000)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                loc?.let { _currentLocation.value = GeoPoint(it.latitude, it.longitude) }
            }
        } catch (e: SecurityException) {
            Log.e("NavVM", "Location permission missing")
        }
    }

    fun setDestination(geoPoint: GeoPoint) {
        _destination.value = geoPoint
        _currentStepIndex.value = 0
        lastInstructionTime = 0L
        lastInstruction = ""
        fetchRoute(geoPoint)
    }

    private fun fetchRoute(dest: GeoPoint) {
        val start = _currentLocation.value
        if (start == null) {
            Log.e("NavVM", "Current location unavailable")
            return
        }

        viewModelScope.launch {
            try {
                val coords = "${start.longitude},${start.latitude};${dest.longitude},${dest.latitude}"
                Log.d("NavVM", "Fetching route from OSRM")
                
                val response = routeService.getRoute(coords)
                if (response.routes.isNotEmpty()) {
                    val route = response.routes[0]
                    val allPoints = route.geometry.coordinates.map { GeoPoint(it[1], it[0]) }
                    _routePoints.value = allPoints

                    // Parse steps from legs
                    val steps = mutableListOf<NavigationStep>()
                    route.legs.forEach { leg ->
                        leg.steps.forEach { step ->
                            val stepPoints = step.geometry.coordinates.map { GeoPoint(it[1], it[0]) }
                            val instruction = ManeuverTranslator.translate(
                                step.maneuver?.type,
                                step.maneuver?.modifier,
                                step.name
                            )
                            steps.add(NavigationStep(
                                instruction = instruction,
                                distance = step.distance,
                                geometry = stepPoints,
                                maneuverType = step.maneuver?.type,
                                modifier = step.maneuver?.modifier
                            ))
                        }
                    }
                    _navigationSteps.value = steps
                    _currentStepIndex.value = 0
                    Log.d("NavVM", "Parsed ${steps.size} navigation steps from ${route.legs.size} legs")
                    updateNavigation()
                } else {
                    Log.e("NavVM", "OSRM returned no routes")
                }
            } catch (e: Exception) {
                Log.e("NavVM", "Route API call failed", e)
            }
        }
    }

    private fun updateNavigation() {
        val current = _currentLocation.value ?: return
        val steps = _navigationSteps.value
        val stepIdx = _currentStepIndex.value

        if (steps.isEmpty()) {
            _currentInstruction.value = "No route"
            _distanceToNextManeuver.value = 0
            return
        }

        if (stepIdx >= steps.size) {
            _currentInstruction.value = "Destination reached"
            _distanceToNextManeuver.value = 0
            return
        }

        val currentStep = steps[stepIdx]
        val stepEndpoint = currentStep.geometry.last()
        val distToEnd = haversine(current.latitude, current.longitude, stepEndpoint.latitude, stepEndpoint.longitude)

        // Advance to next step if current is complete (< 15m)
        if (distToEnd < 15 && stepIdx + 1 < steps.size) {
            _currentStepIndex.value = stepIdx + 1
            lastInstructionTime = 0L // Reset cooldown on step change
            Log.d("NavVM", "Advanced to step ${stepIdx + 1}")
            updateNavigation() // Recurse to update with new step
            return
        }

        // Update instruction with cooldown
        val now = System.currentTimeMillis()
        if (currentStep.instruction != lastInstruction || (now - lastInstructionTime) > instructionCooldownMs) {
            _currentInstruction.value = currentStep.instruction
            lastInstruction = currentStep.instruction
            lastInstructionTime = now
            Log.d("NavVM", "Step $stepIdx: ${currentStep.instruction}")
        }

        _distanceToNextManeuver.value = distToEnd.toInt()

        // Check reroute condition
        val routeOffTrack = distToEnd > 30 // 30m off track
        if (routeOffTrack && (now - lastRerouteTime) > rerouteCooldownMs) {
            Log.w("NavVM", "User off track by ${distToEnd}m, rerouting...")
            lastRerouteTime = now
            _destination.value?.let { fetchRoute(it) }
        }
    }

    fun sendRouteToBike() {
        val points = _routePoints.value
        if (points.isEmpty()) {
            Log.w("NavVM", "No route available")
            return
        }

        val simplified = RouteSimplifier.simplifyToTarget(points, 20)
        val routePointsToSend = simplified.map { it.latitude to it.longitude }
        Log.d("NavVM", "Sending ${routePointsToSend.size} waypoints to bike")
        bleManager.sendRoute(routePointsToSend)
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0  // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }

    fun startRide() = bleManager.startRide()
    fun stopRide() = bleManager.stopRide()
    fun resetRide() = bleManager.resetRide()

    override fun onCleared() {
        super.onCleared()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
}
