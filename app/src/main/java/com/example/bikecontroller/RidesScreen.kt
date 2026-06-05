package com.example.bikecontroller

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun RidesScreen(onRideClick: (Int) -> Unit) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    var rides by remember { mutableStateOf(listOf<Ride>()) }

    LaunchedEffect(Unit) {
        rides = db.rideDao().getAllRides()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp)
    ) {
        Text(
            text = "PREVIOUS RIDES",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (rides.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No rides recorded yet", color = Color.Gray)
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(rides) { ride ->
                    RideItem(ride = ride, onClick = onRideClick)
                }
            }
        }
    }
}

@Composable
fun RideItem(ride: Ride, onClick: (Int) -> Unit) {
    val dateStr = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(ride.date))
    
    Surface(
        color = Color.DarkGray,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(ride.id) }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(ride.name, color = Color.White, fontWeight = FontWeight.Bold)
                Text(dateStr, color = Color.Gray, fontSize = 12.sp)
            }
            
            Text("${String.format(Locale.US, "%.1f", ride.distanceKm)} km", color = Color.Cyan, fontWeight = FontWeight.Bold)
        }
    }
}

