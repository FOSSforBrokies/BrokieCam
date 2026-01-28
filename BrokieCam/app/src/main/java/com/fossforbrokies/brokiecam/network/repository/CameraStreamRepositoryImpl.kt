package com.fossforbrokies.brokiecam.network.repository

import android.util.Log
import com.fossforbrokies.brokiecam.core.model.CameraFrame
import com.fossforbrokies.brokiecam.core.repository.CameraStreamRepository
import com.fossforbrokies.brokiecam.core.repository.StreamStatus
import com.fossforbrokies.brokiecam.network.socket.TcpFrameStreamer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

// Logging Tag
private const val LOG_TAG = "CameraStreamRepo"

/**
 * Implementation of the CameraStreamRepository that uses TCP sockets for data transmission
 */
class CameraStreamRepositoryImpl: CameraStreamRepository{

    // Lifecycle-aware scope for the repository
    // SupervisorJob is to ensure that if one child dies, the scope doesn't die
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Internal mutable state
    private val _connectionStatus = MutableStateFlow(StreamStatus.IDLE)
    // External read-only state for ViewModel
    override val connectionStatus: StateFlow<StreamStatus> = _connectionStatus

    // Atomic counter for thread-safe logging rate-limiter
    private val frameCounter = AtomicLong(0)

    // Dedicated streamer instance
    private val streamer = TcpFrameStreamer { isConnected ->
        if (isConnected) {
            updateState(StreamStatus.CONNECTED)
        } else {
            updateState(StreamStatus.DISCONNECTED)
        }
    }

    override fun connect(port: Int) {
        // Validate input
        if (port !in 1024..65535){
            Log.e(LOG_TAG, "Invalid port: $port. Must be between 1024-65535")
            updateState(StreamStatus.ERROR)
            return
        }

        updateState(StreamStatus.CONNECTING)

        repositoryScope.launch {
            streamer.connect(port)
        }
    }

    override fun disconnect() {
        repositoryScope.launch {
            streamer.disconnect()
        }

    }

    /**
     * Stream a single frame to the server
     *
     * @param frame CameraFrame object containing metadata and raw image data (JPEG)
     */
    override suspend fun streamFrame(frame: CameraFrame) {
        if (_connectionStatus.value != StreamStatus.CONNECTED) return

        streamer.sendFrame(frame.data)

        // Rate-limited logging
        if (frameCounter.getAndIncrement() % 100 == 0L){
            Log.v(LOG_TAG, "Sent Frame #${frameCounter.get()} (${frame.data.size} bytes)")
        }
    }

    /**
     * Cleanup method
     * Call when the ViewModel is destroyed
     */
    fun onCleared(){
        disconnect()
        repositoryScope.cancel()
    }

    /**
     * Centralized state updater to ensure consistent logging and behavior.
     */
    private fun updateState(newState: StreamStatus) {
        if (_connectionStatus.value != newState) {
            Log.i(LOG_TAG, "State Change: ${_connectionStatus.value} -> $newState")
            _connectionStatus.value = newState
        }
    }
}