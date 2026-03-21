package com.fossforbrokies.brokiecam.network.repository

import android.annotation.SuppressLint
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import com.fossforbrokies.brokiecam.core.audio.MicManager
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

// Logging Tag
private const val LOG_TAG = "CameraStreamRepo"

/**
 * Orchestrator repository that links the hardware camera pipeline to the TCP network socket.
 *
 * Architecture Flow:
 * - [CameraManager] produces a continuous flow of encoded H.264 frames.
 * - [MicManager] produces raw PCM audio chunks.
 * - This repository collects both flows concurrently and pipes them directly to the [TcpFrameStreamer].
 * - Ensures every collected frame is returned to its pool
 * - Uses [StateFlow] to expose network connection status reactively to the ViewModel/UI.
 * - Can run video + audio, video-only, audio-only
 *
 * @property cameraManager Manages camera lifecycle and H.264 hardware encoding.
 * @property micManager Manages microphone lifecycle and PCM audio capture.
 * @param streamerFactory Factory injecting the network status callback into the TCP client.
 */
class CameraStreamRepositoryImpl(
    private val cameraManager: CameraManager,
    private val micManager: MicManager,
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
    private var micJob: Job? = null
    private var videoCollectorJob: Job? = null
    private var audioCollectorJob: Job? = null

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
     * * Network Connectivity, Video Capture, Audio Capture
     *
     * @param lifecycleOwner The lifecycle controlling the CameraX instance.
     * @param port Server port (1024-65535).
     * @param enableVideo Set to true to stream H.264 camera frames.
     * @param enableAudio Set to true to stream PCM microphone data.
     */
    override fun connect(
        lifecycleOwner: LifecycleOwner,
        port: Int,
        enableVideo: Boolean,
        enableAudio: Boolean
    ) {
        // Prevent multiple simultaneous connection attempts
        if (_connectionStatus.value == StreamStatus.CONNECTED ||
            _connectionStatus.value == StreamStatus.CONNECTING){
            Log.d(LOG_TAG, "Already connected or connecting. Ignoring duplicate request.")
            return
        }

        if (!enableVideo && !enableAudio) {
            Log.e(LOG_TAG, "Cannot connect: Both video and audio are disabled.")
            return
        }

        // Validate port range
        if (port !in 1024..65535){
            Log.e(LOG_TAG, "Invalid port: $port. Must be between 1024-65535")
            updateState(StreamStatus.ERROR)
            return
        }

        updateState(StreamStatus.CONNECTING)

        startNetworkPipeline(port)

        if (enableVideo) startVideoPipeline(lifecycleOwner)
        if (enableAudio) startAudioPipeline()
    }

    /**
     * Launches the auto-healing connection loop, running independently of the hardware pipelines.
     */
    private fun startNetworkPipeline(port: Int){
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
    }

    /**
     * Initializes the CameraX hardware pipeline and launches a dedicated collector
     * coroutine to pipe compressed H.264 NAL units into the socket.
     */
    private fun startVideoPipeline(lifecycleOwner: LifecycleOwner){
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

        // Pipe the emitted video frames directly into the active socket
        videoCollectorJob = repositoryScope.launch {
            try{
                cameraManager.videoFrameFlow.collect { frameData ->
                    try{
                        if (_connectionStatus.value == StreamStatus.CONNECTED){
                            streamer.sendVideoFrame(frameData)
                        }
                    } finally {
                        frameData.release()
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
     * Initializes the microphone hardware and launches a dedicated collector
     * coroutine to pipe raw PCM audio chunks into the socket.
     */
    @SuppressLint("MissingPermission")
    private fun startAudioPipeline(){
        micJob = repositoryScope.launch {
            try{
                micManager.start(this)

            } catch (e: CancellationException){
                Log.d(LOG_TAG, "Mic pipeline cancelled by repository")
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to start mic pipeline", e)
            }
        }

        // Pipe the emitted video frames directly into the active socket
        audioCollectorJob = repositoryScope.launch {
            try{
                micManager.audioFrameFlow.collect { frameData ->
                    try{
                        if (_connectionStatus.value == StreamStatus.CONNECTED){
                            streamer.sendAudioFrame(frameData)
                        }
                    } finally {
                        frameData.release()
                    }
                }
            } catch (e: CancellationException) {
                Log.d(LOG_TAG, "Audio streaming pipe cancelled by repository")
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to start audio streaming pipeline", e)
                updateState(StreamStatus.ERROR)
            }
        }
    }

    /**
     * Suspends hardware operations and terminates the network socket.
     * Ensures all background coroutines complete their final suspension points safely.
     */
    override suspend fun disconnect() {
        // Stop network loop
        connectionJob?.cancelAndJoin()

        // Stop collectors
        videoCollectorJob?.cancelAndJoin()
        audioCollectorJob?.cancelAndJoin()

        // Stop hardware
        cameraJob?.cancelAndJoin()
        micJob?.cancelAndJoin()

        connectionJob = null
        videoCollectorJob = null
        audioCollectorJob = null
        cameraJob = null
        micJob = null

        // Clean up hardware and sockets
        streamer.disconnect()
        cameraManager.stopStreaming()
        micManager.stop()

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