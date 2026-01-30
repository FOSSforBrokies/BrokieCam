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
 * Repository implementation that bridges camera frames to TCP socket transmission
 *
 * Architecture:
 * - ViewModel produces frames → Repository queues frames → Streamer sends over TCP
 * - Uses StateFlow to expose connection status reactively to UI
 * - Manages its own coroutine scope (independent of ViewModel lifecycle)
 */
class CameraStreamRepositoryImpl: CameraStreamRepository{

    /**
     * Repository-scoped coroutine scope
     * - SupervisorJob: Child failures don't cancel siblings or parent
     */
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Internal mutable state - only modified by this repository */
    private val _connectionStatus = MutableStateFlow(StreamStatus.IDLE)

    /** External read-only state exposed to ViewModel/UI */
    override val connectionStatus: StateFlow<StreamStatus> = _connectionStatus

    /** TCP streamer instance - handles low-level socket operations */
    private val streamer = TcpFrameStreamer { isConnected ->
        if (isConnected) {
            updateState(StreamStatus.CONNECTED)
        } else {
            updateState(StreamStatus.DISCONNECTED)
        }
    }

    /**
     * Initiates connection to the TCP server via ADB reverse tunneling.
     *
     * @param port Server port (1024-65535)
     */
    override fun connect(port: Int) {
        // Validate port range
        if (port !in 1024..65535){
            Log.e(LOG_TAG, "[!] Invalid port: $port. Must be between 1024-65535")
            updateState(StreamStatus.ERROR)
            return
        }

        updateState(StreamStatus.CONNECTING)

        repositoryScope.launch {
            streamer.connect(port)
        }
    }

    /**
     * Closes the active TCP connection.
     */
    override fun disconnect() {
        repositoryScope.launch {
            streamer.disconnect()
        }

    }

    /**
     * Transmits a single camera frame to the server.
     *
     * @param frame CameraFrame containing JPEG data and metadata
     */
    override suspend fun streamFrame(frame: CameraFrame) {
        if (_connectionStatus.value != StreamStatus.CONNECTED) return

        streamer.sendFrame(frame.data)
    }

    /**
     * Cleanup hook - call when repository is no longer needed
     * Called when the ViewModel is destroyed - ViewModel.onCleared()
     */
    fun onCleared(){
        disconnect()
        repositoryScope.cancel() // Cancel all pending coroutines
    }

    /**
     * Centralized state updater to ensure consistent logging and behavior.
     */
    private fun updateState(newState: StreamStatus) {
        if (_connectionStatus.value != newState) {
            Log.i(LOG_TAG, "[*] State Change: ${_connectionStatus.value} -> $newState")
            _connectionStatus.value = newState
        }
    }
}