package com.example.bikecontroller

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideDetailsScreen(rideId: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    var ride by remember { mutableStateOf<Ride?>(null) }
    val scope = rememberCoroutineScope()
    var showDeleteDialog by remember { mutableStateOf(false) }

    val pathPoints = remember(ride) {
        ride?.pathData?.split(";")?.filter { it.isNotEmpty() }?.map {
            val parts = it.split(",")
            GeoPoint(parts[0].toDouble(), parts[1].toDouble())
        } ?: emptyList()
    }

    LaunchedEffect(rideId) {
        ride = db.rideDao().getAllRides().find { it.id == rideId }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Ride?") },
            text = { Text("This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            ride?.let { db.rideDao().deleteRide(it) }
                            showDeleteDialog = false
                            onBack()
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                ) {
                    Text("DELETE")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("CANCEL")
                }
            },
            containerColor = Color.DarkGray,
            titleContentColor = Color.White,
            textContentColor = Color.LightGray
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ride Details", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        containerColor = Color.Black
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp),
                color = Color.DarkGray,
                shape = RoundedCornerShape(12.dp)
            ) {
                if (pathPoints.isNotEmpty()) {
                    AndroidView(
                        factory = { ctx ->
                            MapView(ctx).apply {
                                setTileSource(TileSourceFactory.MAPNIK)
                                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                                setMultiTouchControls(true)
                                
                                val line = Polyline(this)
                                line.setPoints(pathPoints)
                                line.outlinePaint.color = android.graphics.Color.CYAN
                                line.outlinePaint.strokeWidth = 10f
                                overlays.add(line)
                                
                                post {
                                    if (pathPoints.isNotEmpty()) {
                                        controller.setZoom(15.0)
                                        controller.setCenter(pathPoints[0])
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(contentAlignment = Alignment.Center) {
                        Text("No path data available", color = Color.Gray)
                    }
                }
            }

            ride?.let { r ->
                val dateStr = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(r.date))
                val hours = r.durationSeconds / 3600
                val minutes = (r.durationSeconds % 3600) / 60
                val seconds = r.durationSeconds % 60
                val durationStr = if (hours > 0) {
                    String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
                } else {
                    String.format(Locale.US, "%02d:%02d", minutes, seconds)
                }

                val avgSpeed = if (r.durationSeconds > 0) (r.distanceKm / (r.durationSeconds / 3600.0)) else 0.0

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.DarkGray),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                        Text(text = r.name, color = Color.Cyan, fontWeight = FontWeight.Bold)
                        Text(text = dateStr, color = Color.LightGray, fontSize = 14.sp)
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            StatItem("Distance", String.format(Locale.US, "%.1f km", r.distanceKm))
                            StatItem("Duration", durationStr)
                            StatItem("Avg Speed", String.format(Locale.US, "%.1f km/h", avgSpeed))
                        }
                    }
                }
            } ?: run {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.Cyan)
                }
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.Gray, fontSize = 12.sp)
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}
