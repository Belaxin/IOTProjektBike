package com.example.bikecontroller

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
var rideState = false;
@Composable
fun NavigationScreen(viewModel: NavigationViewModel) {
    var isRideActive by remember { mutableStateOf(false) }
    val routePoints by viewModel.routePoints.collectAsState()
    val bleState by viewModel.bleState.collectAsState()
    val hasEspGpsFix by viewModel.hasEspGpsFix.collectAsState()
    val destination by viewModel.destination.collectAsState()
    val currentLocation by viewModel.currentLocation.collectAsState()
    val currentInstruction by viewModel.currentInstruction.collectAsState()
    val distanceToNextManeuver by viewModel.distanceToNextManeuver.collectAsState()
    val currentStepIndex by viewModel.currentStepIndex.collectAsState()
    val isPaused by viewModel.isPaused.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                MapView(context).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    setMultiTouchControls(true)
                    controller.setZoom(15.0)
                    
                    val receiver = object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                            viewModel.setDestination(p)
                            return true
                        }
                        override fun longPressHelper(p: GeoPoint): Boolean = false
                    }
                    overlays.add(MapEventsOverlay(receiver))
                }
            },
            update = { mapView ->
                val overlaysToRemove = mapView.overlays.filter {
                    it !is MapEventsOverlay
                }

                mapView.overlays.removeAll(overlaysToRemove)
                
                if (routePoints.isNotEmpty()) {
                    val line = Polyline(mapView)
                    line.setPoints(routePoints)
                    line.outlinePaint.color = android.graphics.Color.CYAN
                    line.outlinePaint.strokeWidth = 10f
                    mapView.overlays.add(line)
                }

                currentLocation?.let { loc ->
                    val marker = Marker(mapView)
                    marker.position = loc
                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    marker.title = "You"
                    mapView.overlays.add(marker)
                    
                    if (mapView.mapCenter.latitude == 0.0 && mapView.mapCenter.longitude == 0.0) {
                         mapView.controller.setCenter(loc)
                    }
                }

                destination?.let { dest ->
                    val marker = Marker(mapView)
                    marker.position = dest
                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    marker.title = "Destination"
                    mapView.overlays.add(marker)
                }
                
                mapView.invalidate()
            }
        )

        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp).align(Alignment.TopCenter),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusCard(bleState, Modifier.weight(1f))
                GpsStatusCard(hasEspGpsFix, bleState == BleState.Connected || bleState == BleState.NavigationActive)
            }
            if (currentInstruction.isNotEmpty() && currentInstruction != "Ready" && currentInstruction != "No route") {
                NavigationCard(currentInstruction, distanceToNextManeuver, currentStepIndex)
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp).align(Alignment.BottomCenter),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (routePoints.isNotEmpty()) {
                Button(
                    onClick = { viewModel.sendRouteToBike() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("SEND ROUTE TO BIKE", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!isRideActive) {
                    ActionButton(
                        text = "START",
                        color = Color(0xFF2E7D32),
                        modifier = Modifier.weight(1f),
                        enabled = bleState == BleState.Connected || bleState == BleState.RouteSent
                    ) {
                        isRideActive = true
                        viewModel.startRide()
                    }
                } else {
                    ActionButton(
                        text = "STOP",
                        color = Color(0xFFC62828),
                        modifier = Modifier.weight(1f)
                    ) {
                        viewModel.stopRide()
                        isRideActive = false
                    }
                }
                
                ActionButton(
                    text = if (isPaused) "RESUME" else "PAUSE",
                    color = Color.DarkGray,
                    modifier = Modifier.weight(1f),
                    enabled = isRideActive && bleState != BleState.Disconnected
                ) {
                    viewModel.togglePauseRide()
                }
            }
        }
    }
}

@Composable
fun StatusCard(state: BleState, modifier: Modifier = Modifier) {
    val color = when (state) {
        BleState.Connected -> Color(0xFF2E7D32)
        BleState.Connecting -> Color(0xFFF9A825)
        BleState.Disconnected -> Color(0xFFC62828)
        BleState.RouteSent -> Color.Cyan
        BleState.NavigationActive -> Color.Blue
    }

    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.8f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.Gray)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(12.dp).background(color, RoundedCornerShape(50)))
            Spacer(Modifier.width(12.dp))
            Text(text = state.name.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun GpsStatusCard(hasFix: Boolean, isConnected: Boolean) {
    val color = if (!isConnected) Color.Gray else if (hasFix) Color(0xFF2E7D32) else Color(0xFFF9A825)
    val text = if (!isConnected) "OFFLINE" else if (hasFix) "GPS FIXED" else "NO GPS FIX"

    Surface(
        color = Color.Black.copy(alpha = 0.8f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.Gray)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(12.dp).background(color, RoundedCornerShape(50)))
            Spacer(Modifier.width(12.dp))
            Text(text = text, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ActionButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(60.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            disabledContainerColor = color.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(text, fontWeight = FontWeight.Bold, color = if (enabled) Color.White else Color.Gray)
    }
}

@Composable
fun NavigationCard(instruction: String, distanceMeters: Int, stepIndex: Int) {
    Surface(
        color = Color.Black.copy(alpha = 0.85f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.Cyan)
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth()
        ) {
            Text(text = instruction, color = Color.Cyan, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "In ${if (distanceMeters < 100) distanceMeters else distanceMeters / 1000}${if (distanceMeters < 100) "m" else "km"} • Step ${stepIndex + 1}",
                color = Color.Gray,
                fontSize = 12.sp
            )
        }
    }
}
