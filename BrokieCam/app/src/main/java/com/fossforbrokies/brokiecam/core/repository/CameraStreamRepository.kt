package com.fossforbrokies.brokiecam.core.repository

import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.StateFlow

/**
 * Interfaces defining how the app interacts with the remote server
 */
interface CameraStreamRepository {

    /** An observable state that tells the UI if the app is Connected, Disconnected, or Error*/
    val connectionStatus: StateFlow<StreamStatus>

    /** Initiates the three core pipelines:
     * * Network Connectivity, Camera Hardware Processing, Data Transmission
     */
    fun connect(lifecycleOwner: LifecycleOwner, port: Int)

    /** Suspends hardware operations and terminates the network socket. */
    suspend fun disconnect()

}

/**
 * Represents the current state of the network bridge
 */
enum class StreamStatus {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    ERROR
}