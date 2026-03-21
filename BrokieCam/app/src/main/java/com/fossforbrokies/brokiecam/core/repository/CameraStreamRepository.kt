package com.fossforbrokies.brokiecam.core.repository

import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface defining how the app interacts with the remote server
 */
interface CameraStreamRepository {

    /** An observable state that tells the UI if the app is Connected, Disconnected, or Error*/
    val connectionStatus: StateFlow<StreamStatus>

    /**
     * Initiates the three core pipelines:
     * * Network Connectivity, Video Capture, Audio Capture
     *
     * @param lifecycleOwner The lifecycle controlling the CameraX instance.
     * @param port Server port (1024-65535).
     * @param enableVideo Set to true to stream H.264 camera frames.
     * @param enableAudio Set to true to stream PCM microphone data.
     */
    fun connect(
        lifecycleOwner: LifecycleOwner,
        port: Int,
        enableVideo: Boolean,
        enableAudio: Boolean,
        )

    /** Suspends hardware operations and terminates the network socket. */
    suspend fun disconnect()

    /** Releases all hardware resources and cancels background scopes.
     * Should be called when the repository is no longer needed.
     */
    suspend fun release()

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