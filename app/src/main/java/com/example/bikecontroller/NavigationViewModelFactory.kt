package com.example.bikecontroller

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class NavigationViewModelFactory(
    private val bleManager: BleManager,
    private val context: Context
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(NavigationViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return NavigationViewModel(bleManager, context.applicationContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
