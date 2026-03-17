package com.fossforbrokies.brokiecam.ui.camera

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fossforbrokies.brokiecam.core.repository.StreamStatus

private const val CAMERA_STREAM_PORT = 5000

@Composable
fun CameraScreen(viewModel: CameraViewModel){

    val connectionStatus by viewModel.connectionStatus.collectAsState()

    val lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current

    // Smoothly animate the background color changes
    val animatedTopColor by animateColorAsState(
        targetValue = when (connectionStatus) {
            StreamStatus.CONNECTED -> Color(0xFF0F361A) // Dark emerald
            StreamStatus.CONNECTING -> Color(0xFF362204) // Dark bronze
            StreamStatus.ERROR -> Color(0xFF360A0A) // Dark crimson
            else -> Color(0xFF1A1A1A) // Dark gray
        },
        animationSpec = tween(durationMillis = 800),
        label = "bg_color_anim"
    )

    // Animate button color
    val animatedButtonColor by animateColorAsState(
        targetValue = if (connectionStatus == StreamStatus.CONNECTED || connectionStatus == StreamStatus.CONNECTING) {
            Color(0xFFE53935) // Vibrant Red
        } else {
            Color(0xFF1E88E5) // Vibrant Blue
        },
        animationSpec = tween(durationMillis = 500),
        label = "btn_color_anim"
    )

    // Status color
    val statusColor = getStatusColor(connectionStatus)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(animatedTopColor, Color(0xFF0A0A0A)) // Gradient fades to pure black
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            // --- HEADER ---
            Text(
                text = "BROKIECAM",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Black,
                color = Color.White,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "HARDWARE ENCODER LIVE",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.5f),
                letterSpacing = 3.sp
            )

            Spacer(modifier = Modifier.height(80.dp))

            // --- STATUS CARD (Glassmorphism effect) ---
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White.copy(alpha = 0.05f)) // Translucent background
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.1f), // Subtle border
                        shape = RoundedCornerShape(24.dp)
                    )
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "NETWORK STATUS",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.4f),
                        letterSpacing = 1.5.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = connectionStatus.name,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = statusColor,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(80.dp))

            // --- MAIN ACTION BUTTON ---
            Button(
                onClick = { viewModel.toggleConnection(lifecycleOwner, CAMERA_STREAM_PORT) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = animatedButtonColor,
                    contentColor = Color.White
                ),
                shape = CircleShape,
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(64.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = if (connectionStatus == StreamStatus.CONNECTED || connectionStatus == StreamStatus.CONNECTING)
                            "STOP STREAM"
                        else "START STREAM",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

fun getStatusColor(status: StreamStatus): Color = when(status) {
    StreamStatus.CONNECTED -> Color(0xFF69F0AE) // Neon Green
    StreamStatus.CONNECTING -> Color(0xFFFFD54F) // Neon Amber
    StreamStatus.ERROR -> Color(0xFFFF5252) // Neon Red
    else -> Color(0xFFB0BEC5) // Slate Gray
}