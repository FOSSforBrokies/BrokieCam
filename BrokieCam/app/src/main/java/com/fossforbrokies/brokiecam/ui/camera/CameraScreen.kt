package com.fossforbrokies.brokiecam.ui.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fossforbrokies.brokiecam.core.repository.StreamStatus

private const val CAMERA_STREAM_PORT = 5000

@Composable
fun CameraScreen(viewModel: CameraViewModel){

    val connectionStatus by viewModel.connectionStatus.collectAsState()

    Box(modifier = Modifier.fillMaxSize()){

        // Full screen camera
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            onFrameCaptured = { byteArray, width, height ->
                viewModel.onNewFrameCaptured(byteArray, width, height)
            }
        )

        // Control overlay
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 50.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status indicator
            Text(
                text = "STATUS: ${connectionStatus.name}",
                color = getStatusColor(connectionStatus),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Main Action Button
            Button(
                onClick = { viewModel.toggleConnection(CAMERA_STREAM_PORT) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (connectionStatus == StreamStatus.CONNECTED)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.height(56.dp)
            ) {
                Text(
                    text = if (connectionStatus == StreamStatus.CONNECTED) "STOP USB STREAM" else "START USB STREAM"
                )
            }

            if (connectionStatus == StreamStatus.IDLE || connectionStatus == StreamStatus.ERROR) {
                Text(
                    text = "Run 'adb reverse tcp:5000 tcp:5000' on laptop first",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }

}

fun getStatusColor(status: StreamStatus): Color = when(status) {
    StreamStatus.CONNECTED -> Color(0xFF4CAF50) // Green
    StreamStatus.CONNECTING -> Color(0xFFFFC107) // Amber
    StreamStatus.ERROR -> Color(0xFFF44336) // Red
    else -> Color.White
}