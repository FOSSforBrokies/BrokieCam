package com.fossforbrokies.brokiecam.ui.camera

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fossforbrokies.brokiecam.core.repository.CameraStreamRepository
import com.fossforbrokies.brokiecam.core.repository.StreamStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for camera streaming screen.
 */
class CameraViewModel(
    private val repository: CameraStreamRepository
): ViewModel() {

    /** Reactive connection status exposed to UI */
    val connectionStatus: StateFlow<StreamStatus> = repository.connectionStatus

    /**
     * Connect or disconnect from the server based on current state
     *
     * @param lifecycleOwner Required by CameraX to bind hardware lifecycle to the UI.
     * @param port port for ADB cable connection
     * @param enableVideo Toggle for the H.264 video stream.
     * @param enableAudio Toggle for the PCM audio stream.
     */
    fun toggleConnection(
        lifecycleOwner: LifecycleOwner,
        port: Int,
        enableVideo: Boolean,
        enableAudio: Boolean
    ) {
        viewModelScope.launch {
            if (connectionStatus.value == StreamStatus.CONNECTED ||
                connectionStatus.value == StreamStatus.CONNECTING) {
                repository.disconnect()
            } else {
                repository.connect(
                    lifecycleOwner = lifecycleOwner,
                    port = port,
                    enableVideo = enableVideo,
                    enableAudio = enableAudio
                )
            }
        }
    }

    /** Stops the active stream */
    fun stopStream() {
        viewModelScope.launch {
            repository.disconnect()
        }
    }

    /**
     * Cleanup: Ensure the connection is fully terminated and coroutines are canceled when the ViewModel is destroyed.
     */
    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch{
            withContext(NonCancellable) {
                repository.release()
            }
        }
    }
}