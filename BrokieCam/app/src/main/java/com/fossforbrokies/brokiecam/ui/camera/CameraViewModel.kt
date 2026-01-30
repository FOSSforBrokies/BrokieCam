package com.fossforbrokies.brokiecam.ui.camera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fossforbrokies.brokiecam.core.model.CameraFrame
import com.fossforbrokies.brokiecam.core.repository.CameraStreamRepository
import com.fossforbrokies.brokiecam.core.repository.StreamStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for camera streaming screen
 *
 * Architecture Pattern: Producer-Consumer
 * - Producer: CameraX analyzer (background thread) → onNewFrameCaptured()
 * - Queue: frameChannel (capacity=2, drop oldest on overflow)
 * - Consumer: Single coroutine reading from channel → repository.streamFrame()
 */
class CameraViewModel(
    private val repository: CameraStreamRepository
): ViewModel() {

    /** Reactive connection status exposed to UI */
    val connectionStatus: StateFlow<StreamStatus> = repository.connectionStatus

    /** Buffered channel for frame transmission */
    private val frameChannel = Channel<CameraFrame>(
        capacity = 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        startFrameConsumer()
    }

    /**
     * Start the consumer loop that takes frames from the channel and sends them to the repo
     * This runs on a single persistent coroutine
     */
    private fun startFrameConsumer(){
        viewModelScope.launch(Dispatchers.IO){
            for (frame in frameChannel){
                repository.streamFrame(frame)
            }
        }
    }

    /**
     * Connect or disconnect from the server based on current state
     * @param port port for ADB cable connection
     */
    fun toggleConnection(port: Int) {
        if (connectionStatus.value == StreamStatus.CONNECTED){
            repository.disconnect()
        } else {
            repository.connect(port)
        }
    }

    /**
     * Called by CameraX analyzer whenever a new frame is ready
     *
     * Runs on background executor thread
     * Uses trySend() to non-blocking push to the channel
     *
     * @param jpegBytes JPEG-encoded image data
     * @param width Frame width in pixels
     * @param height Frame height in pixels
     */
    fun onNewFrameCaptured(jpegBytes: ByteArray, width: Int, height: Int){
        if (connectionStatus.value != StreamStatus.CONNECTED) return

        val frame = CameraFrame(
            data = jpegBytes,
            width = width,
            height = height,
            timestamp = System.currentTimeMillis()
        )

        frameChannel.trySend(frame)
    }

    /**
     * Cleanup: Ensure the connection is closed when the ViewModel is destroyed
     */
    override fun onCleared() {
        super.onCleared()
        repository.disconnect()
        frameChannel.close() // Signals consumer coroutine to exit
    }
}