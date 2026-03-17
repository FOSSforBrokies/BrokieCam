package com.fossforbrokies.brokiecam.network.repository

import android.util.Log
import androidx.lifecycle.LifecycleOwner
import com.fossforbrokies.brokiecam.core.repository.CameraStreamRepository
import com.fossforbrokies.brokiecam.core.repository.StreamStatus
import com.fossforbrokies.brokiecam.core.video.CameraManager
import com.fossforbrokies.brokiecam.network.socket.StreamState
import com.fossforbrokies.brokiecam.network.socket.TcpFrameStreamer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

// Logging Tag
private const val LOG_TAG = "CameraStreamRepo"

/**
 * Orchestrator repository that links the hardware camera pipeline to the TCP network socket.
 *
 * Architecture Flow:
 * - [CameraManager] produces a continuous flow of encoded H.264 frames.
 * - This repository collects that flow and pipes it directly to the [TcpFrameStreamer].
 * - Uses [StateFlow] to expose network connection status reactively to the ViewModel/UI.
 *
 * @property cameraManager Manages camera lifecycle and encoding.
 * @param streamerFactory Factory injecting the network status callback into the TCP client.
 */
class CameraStreamRepositoryImpl(
    private val cameraManager: CameraManager,
    streamerFactory: (onStatusUpdate: (StreamState) -> Unit) -> TcpFrameStreamer
): CameraStreamRepository{
    /**
     * Dedicated scope for background operations.
     * [SupervisorJob] prevents a failure in one child coroutine from automatically cancelling sibling coroutines
     */
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Active jobs
    private var connectionJob: Job? = null
    private var cameraJob: Job? = null
    private var collectorJob: Job? = null

    /** Internal mutable state for network status */
    private val _connectionStatus = MutableStateFlow(StreamStatus.DISCONNECTED)

    /** public read-only state for the UI layer to observe status changes */
    override val connectionStatus: StateFlow<StreamStatus> = _connectionStatus

    /** Instantiated TCP client with mapped status callback. */
    private val streamer: TcpFrameStreamer = streamerFactory { state ->
        val newState = when (state) {
            StreamState.CONNECTED -> StreamStatus.CONNECTED
            StreamState.CONNECTING -> StreamStatus.CONNECTING
            StreamState.DISCONNECTED -> StreamStatus.DISCONNECTED
        }
        updateState(newState)
    }

    /**
     * Initiates the three core pipelines:
     * * Network Connectivity, Camera Hardware Processing, Data Transmission
     *
     * @param lifecycleOwner The lifecycle controlling the CameraX instance.
     * @param port Server port (1024-65535).
     */
    override fun connect(lifecycleOwner: LifecycleOwner, port: Int) {
        // Prevent multiple simultaneous connection attempts
        if (_connectionStatus.value == StreamStatus.CONNECTED ||
            _connectionStatus.value == StreamStatus.CONNECTING){
            Log.d(LOG_TAG, "Already connected or connecting. Ignoring duplicate request.")
            return
        }


        // Validate port range
        if (port !in 1024..65535){
            Log.e(LOG_TAG, "Invalid port: $port. Must be between 1024-65535")
            updateState(StreamStatus.ERROR)
            return
        }

        updateState(StreamStatus.CONNECTING)

        // Start auto-healing network loop
        connectionJob = repositoryScope.launch {
            try {
                streamer.maintainConnectionLoop(port)
            } catch (e: CancellationException) {
                Log.d(LOG_TAG, "Network loop cancelled by repository")
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Unexpected error in network loop", e)
                updateState(StreamStatus.ERROR)
            }
        }

        // Set up hardware
        cameraJob = repositoryScope.launch {
            try{
                cameraManager.startStreaming(lifecycleOwner, this)

            } catch (e: CancellationException) {
                Log.d(LOG_TAG, "Camera pipeline cancelled by repository")
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to start camera pipeline", e)
                updateState(StreamStatus.ERROR)
            }
        }

        // Pipe the emitted frames directly into the active socket
        collectorJob = repositoryScope.launch {
            try{
                cameraManager.videoFrameFlow.collect { frameData ->
                    if (_connectionStatus.value == StreamStatus.CONNECTED){
                        streamer.sendFrame(frameData)
                    }
                }
            } catch (e: CancellationException) {
                Log.d(LOG_TAG, "Video streaming pipe cancelled by repository")
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to start video streaming pipeline", e)
                updateState(StreamStatus.ERROR)
            }
        }

    }

    /**
     * Suspends hardware operations and terminates the network socket.
     * Ensures all background coroutines complete their final suspension points safely.
     */
    override suspend fun disconnect() {
        connectionJob?.cancelAndJoin()
        cameraJob?.cancelAndJoin()
        collectorJob?.cancelAndJoin()

        connectionJob = null
        cameraJob = null
        collectorJob = null

        // Clean up hardware and sockets
        streamer.disconnect()
        cameraManager.stopStreaming()

        updateState(StreamStatus.DISCONNECTED)
    }

    /**
     * Hook to be called from ViewModel.onCleared() to prevent memory leaks.
     * Destroys the entire repository scope.
     */
    override suspend fun release(){
        disconnect()
        repositoryScope.cancel() // Cancel all pending coroutines
        Log.i(LOG_TAG, "Repository resources released")
    }

    /**
     * Centralized state updater to ensure consistent logging and behavior.
     */
    private fun updateState(newState: StreamStatus) {
        if (_connectionStatus.value != newState) {
            Log.d(LOG_TAG, "State Change: ${_connectionStatus.value} -> $newState")
            _connectionStatus.value = newState
        }
    }
}