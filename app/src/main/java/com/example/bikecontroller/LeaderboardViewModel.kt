package com.example.bikecontroller

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LeaderboardViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val rideDao = db.rideDao()

    private val _topDistanceRides = MutableStateFlow<List<Ride>>(emptyList())
    val topDistanceRides: StateFlow<List<Ride>> = _topDistanceRides.asStateFlow()

    private val _topDurationRides = MutableStateFlow<List<Ride>>(emptyList())
    val topDurationRides: StateFlow<List<Ride>> = _topDurationRides.asStateFlow()

    private val _topSpeedRides = MutableStateFlow<List<Ride>>(emptyList())
    val topSpeedRides: StateFlow<List<Ride>> = _topSpeedRides.asStateFlow()

    init {
        refreshLeaderboards()
    }

    fun refreshLeaderboards() {
        viewModelScope.launch {
            _topDistanceRides.value = rideDao.getTopDistanceRides()
            _topDurationRides.value = rideDao.getTopDurationRides()
            _topSpeedRides.value = rideDao.getTopSpeedRides()
        }
    }
}
