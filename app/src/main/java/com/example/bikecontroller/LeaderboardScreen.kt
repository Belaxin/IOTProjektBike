package com.example.bikecontroller

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun LeaderboardScreen(viewModel: LeaderboardViewModel = viewModel()) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Distance", "Speed", "Duration")

    val topDistanceRides by viewModel.topDistanceRides.collectAsState()
    val topSpeedRides by viewModel.topSpeedRides.collectAsState()
    val topDurationRides by viewModel.topDurationRides.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Text(
            text = "LEADERBOARDS",
            color = Color.Cyan,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp)
        )

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Black,
            contentColor = Color.Cyan,
            indicator = { tabPositions ->
                if (selectedTab < tabPositions.size) {
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = Color.Cyan
                    )
                }
            }
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title, fontWeight = FontWeight.Bold) }
                )
            }
        }

        val displayList = when (selectedTab) {
            0 -> topDistanceRides
            1 -> topSpeedRides
            else -> topDurationRides
        }

        val unit = when (selectedTab) {
            0 -> "km"
            1 -> "km/h"
            else -> ""
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(displayList) { index, ride ->
                LeaderboardItem(index + 1, ride, selectedTab, unit)
            }
        }
    }
}

@Composable
fun LeaderboardItem(rank: Int, ride: Ride, tabIndex: Int, unit: String) {
    val valueText = when (tabIndex) {
        0 -> String.format(Locale.getDefault(), "%.2f", ride.distanceKm)
        1 -> {
            val avgSpeed = if (ride.durationSeconds > 0) ride.distanceKm / (ride.durationSeconds / 3600.0) else 0.0
            String.format(Locale.getDefault(), "%.1f", avgSpeed)
        }
        else -> {
            val h = ride.durationSeconds / 3600
            val m = (ride.durationSeconds % 3600) / 60
            val s = ride.durationSeconds % 60
            if (h > 0) String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s) else String.format(Locale.getDefault(), "%02d:%02d", m, s)
        }
    }

    val dateStr = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(ride.date))

    Surface(
        color = Color.DarkGray.copy(alpha = 0.3f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (rank <= 3) Color.Cyan else Color.Gray)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "#$rank",
                color = if (rank <= 3) Color.Cyan else Color.Gray,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.width(40.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(text = ride.name, color = Color.White, fontWeight = FontWeight.Bold)
                Text(text = dateStr, color = Color.Gray, fontSize = 12.sp)
            }

            Text(
                text = "$valueText $unit",
                color = Color.Cyan,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        }
    }
}
