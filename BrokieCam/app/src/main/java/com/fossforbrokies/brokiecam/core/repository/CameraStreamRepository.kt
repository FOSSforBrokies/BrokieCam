package com.fossforbrokies.brokiecam.core.repository

import com.fossforbrokies.brokiecam.core.model.CameraFrame
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface showing how the app interacts with the remote server
 */
interface CameraStreamRepository {

    // An observable state that tells the UI if the app is Connected, Disconnected, or Error
    val connectionStatus: StateFlow<StreamStatus>

    // Start the connection to the server
    fun connect(port: Int)

    // Close the connection
    fun disconnect()

    // Send actual frame data to the server
    suspend fun streamFrame(frame: CameraFrame)

}

/**
 * Represent the current state of the network bridge
 */
enum class StreamStatus {
    IDLE,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    ERROR
}