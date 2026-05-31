package com.example.bikecontroller

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

object DebugLogger {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val formatted = "[$time] $message"
        _logs.value = (_logs.value + formatted).takeLast(100)
    }
}

@Composable
fun DebugScreen() {
    val logs by DebugLogger.logs.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        Image(
            painter = painterResource(id = R.drawable.capture1),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = 0.6f
        )


        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .padding(16.dp)
        ) {
            Text(
                "BLE DEBUG LOG",
                color = Color.Yellow,
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(8.dp))

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF1A1A1A).copy(alpha = 0.5f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.padding(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(logs) { log ->
                        Text(
                            text = log,
                            color = if (log.contains("RX:")) Color.Cyan else Color.White,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}
