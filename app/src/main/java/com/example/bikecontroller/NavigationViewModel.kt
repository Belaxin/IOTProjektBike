package com.example.bikecontroller

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bikecontroller.AppDatabase
import com.example.bikecontroller.Ride
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.*

class NavigationViewModel(private val bleManager: BleManager, context: Context) : ViewModel() {

    private val _routePoints = MutableStateFlow<List<GeoPoint>>(emptyList())
    val routePoints: StateFlow<List<GeoPoint>> = _routePoints.asStateFlow()

    private val _destination = MutableStateFlow<GeoPoint?>(null)
    val destination: StateFlow<GeoPoint?> = _destination.asStateFlow()

    private val _currentLocation = MutableStateFlow<GeoPoint?>(null)
    val currentLocation: StateFlow<GeoPoint?> = _currentLocation.asStateFlow()

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
    private val instructionCooldownMs = 5000L

    private var lastRerouteTime = 0L
    private val rerouteCooldownMs = 10000L

    private var lastStableHeading = 0f
    private val minSpeedForHeading = 3f

    val bleState = bleManager.connectionState
    val speed = bleManager.speed
    val distance = bleManager.distance
    val hasEspGpsFix = bleManager.hasGpsFix

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    private val db = AppDatabase.getDatabase(context)
    private var rideStartTime: Long = 0L
    private var totalPausedTime: Long = 0L
    private var pauseTimestamp: Long = 0L

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _isRideActive = MutableStateFlow(false)
    val isRideActive: StateFlow<Boolean> = _isRideActive.asStateFlow()

    private val recordedPath = mutableListOf<GeoPoint>()
    
    private val routeService = Retrofit.Builder()
        .baseUrl("https://router.project-osrm.org/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(RouteService::class.java)

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let {
                val point = GeoPoint(it.latitude, it.longitude)
                _currentLocation.value = point
                
                if (rideStartTime > 0L && !_isPaused.value) {
                    recordedPath.add(point)
                }

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

        if (distToEnd < 15 && stepIdx + 1 < steps.size) {
            _currentStepIndex.value = stepIdx + 1
            lastInstructionTime = 0L
            Log.d("NavVM", "Advanced to step ${stepIdx + 1}")
            updateNavigation()
            return
        }

        val now = System.currentTimeMillis()
        if (currentStep.instruction != lastInstruction || (now - lastInstructionTime) > instructionCooldownMs) {
            _currentInstruction.value = currentStep.instruction
            lastInstruction = currentStep.instruction
            lastInstructionTime = now
            Log.d("NavVM", "Step $stepIdx: ${currentStep.instruction}")
        }

        _distanceToNextManeuver.value = distToEnd.toInt()

        val routeOffTrack = distToEnd > 30
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

        // Allow up to 200 waypoints for long routes
        val simplified = RouteSimplifier.simplifyToTarget(points, 200)
        val routePointsToSend = simplified.map { it.latitude to it.longitude }
        Log.d("NavVM", "Sending ${routePointsToSend.size} waypoints to bike")
        
        // Split into chunks if the route is very long (BLE packet size ~512 bytes)
        if (routePointsToSend.size > 50) {
            bleManager.sendRouteChunked(routePointsToSend)
        } else {
            bleManager.sendRoute(routePointsToSend)
        }
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }

    fun startRide() {
        if (bleState.value != BleState.Connected && bleState.value != BleState.RouteSent) {
            Log.w("NavVM", "Cannot start ride: Bike not connected")
            return
        }
        rideStartTime = System.currentTimeMillis()
        totalPausedTime = 0L
        pauseTimestamp = 0L
        _isPaused.value = false
        _isRideActive.value = true
        recordedPath.clear()
        _currentLocation.value?.let { recordedPath.add(it) }
        bleManager.startRide()
    }

    fun stopRide() {
        val endTime = System.currentTimeMillis()
        
        if (_isPaused.value) {
            totalPausedTime += (endTime - pauseTimestamp)
        }

        val totalDistanceMeters = distance.value
        val durationSec = if (rideStartTime > 0) (endTime - rideStartTime - totalPausedTime) / 1000 else 0L
        
        val pathString = recordedPath.joinToString(";") { "${it.latitude},${it.longitude}" }

        viewModelScope.launch {
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = endTime
            
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val startOfDay = calendar.timeInMillis
            
            calendar.set(Calendar.HOUR_OF_DAY, 23)
            calendar.set(Calendar.MINUTE, 59)
            calendar.set(Calendar.SECOND, 59)
            calendar.set(Calendar.MILLISECOND, 999)
            val endOfDay = calendar.timeInMillis
            
            val count = db.rideDao().getRidesCountForDay(startOfDay, endOfDay)
            val dateLabel = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(endTime))
            val rideName = if (count == 0) dateLabel else "$dateLabel #${count + 1}"

            val ride = Ride(
                date = endTime,
                distanceKm = totalDistanceMeters / 1000.0,
                durationSeconds = durationSec,
                pathData = pathString,
                name = rideName
            )
            db.rideDao().insertRide(ride)
            Log.d("NavVM", "Saved ride: $rideName, ${totalDistanceMeters}m in ${durationSec}s")
            recordedPath.clear()
        }
        
        bleManager.stopRide()
        rideStartTime = 0L
        _isPaused.value = false
        _isRideActive.value = false
    }

    fun togglePauseRide() {
        if (rideStartTime == 0L) return
        
        val now = System.currentTimeMillis()
        if (_isPaused.value) {
            totalPausedTime += (now - pauseTimestamp)
            _isPaused.value = false
            bleManager.resumeRide()
        } else {
            pauseTimestamp = now
            _isPaused.value = true
            bleManager.pauseRide()
        }
    }

    fun resetRide() {
        bleManager.resetRide()
        rideStartTime = 0L
        _isPaused.value = false
    }

    override fun onCleared() {
        super.onCleared()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
}
