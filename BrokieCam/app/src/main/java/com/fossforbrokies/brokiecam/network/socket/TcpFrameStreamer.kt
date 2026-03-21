package com.fossforbrokies.brokiecam.network.socket

import android.util.Log
import com.fossforbrokies.brokiecam.core.pool.MediaBufferPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.min

// --- LOG CONSTANTS ---
private const val LOG_TAG = "TcpFrameStreamer"

// --- NETWORK RECOVERY CONSTANTS ---
private const val CONNECT_TIMEOUT_MS = 2000
private const val INITIAL_RECONNECT_DELAY_MS = 500L
private const val MAX_RECONNECT_DELAY_MS = 5000L
private const val MAX_INITIAL_ATTEMPTS = 5 // Give up after ~10 seconds if server is dead
private const val SEND_BUFFER_SIZE = 32 * 1024 // 128KB

// --- PROTOCOL CONSTANTS ---
private const val MAGIC_BYTE_1: Byte = 0x42 // 'B' (Brokie)
private const val MAGIC_BYTE_2: Byte = 0x43 // 'C' (Cam)
private const val TYPE_VIDEO: Byte = 0x01
private const val TYPE_AUDIO: Byte = 0x02

enum class StreamState {
    CONNECTED,
    CONNECTING,
    DISCONNECTED
}

/**
 * TCP streamer for multiplexing raw video and audio over a persistent network connection.
 * Includes an auto-healing connection loop with exponential backoff.
 *
 * Custom Protocol:
 * - [2 Bytes] Magic Identifier (`0x42`, `0x43`)
 * - [1 Byte]  Payload Type (`0x01` = Video, `0x02` = Audio)
 * - [4 Bytes] Payload Length (32-bit Integer)
 * - [N Bytes] The raw payload data (H.264 NAL unit or PCM Audio chunk)
 *
 * (Note: If A/V sync timestamps are re-enabled, the header size will increase by 8 bytes)
 *
 * Designed to work locally with ADB reverse tunneling
 *
 * @param onStatusUpdate Callback invoked when connection state changes (true = connected, false = connecting)
 */
class TcpFrameStreamer (
    private val onStatusUpdate: (StreamState) -> Unit
){
    private var socket: Socket? = null
    private var dataOutputStream: DataOutputStream? = null

    /** Mutex for state management */
    private val connectionMutex = Mutex()

    /** Mutex for thread-safe writing */
    private val writeMutex = Mutex()

    @Volatile
    private var isStreaming = false

    /**
     * Infinite suspending loop that maintains the socket connection.
     *
     * Implements an exponential backoff strategy for reconnection.
     *
     * @param port The target port on localhost to connect to.
     */
    suspend fun maintainConnectionLoop(port: Int){
        isStreaming = true
        var currentDelay = INITIAL_RECONNECT_DELAY_MS
        var consecutiveFailures = 0

        while (currentCoroutineContext().isActive && isStreaming){
            val isConnected = socket != null && socket?.isConnected == true && socket?.isClosed == false

            if (!isConnected){
                onStatusUpdate(StreamState.CONNECTING)
                val success = connect(port)

                if (success){
                    // Reset
                    consecutiveFailures = 0
                    currentDelay = INITIAL_RECONNECT_DELAY_MS
                } else{
                    consecutiveFailures++

                    // If we fail [MAX_INITIAL_ATTEMPTS] times in a row, give up
                    if (consecutiveFailures >= MAX_INITIAL_ATTEMPTS){
                        Log.w(LOG_TAG, "Server not found after $MAX_INITIAL_ATTEMPTS attempts. Giving up.")
                        isStreaming = false
                        break
                    }

                    // Exponential backoff for subsequent reconnect attempts
                    currentDelay = min(currentDelay * 2, MAX_RECONNECT_DELAY_MS)
                    Log.d(LOG_TAG, "Reconnect failed. Retrying in ${currentDelay}ms...")
                }
            } else{
                // Reset
                currentDelay = INITIAL_RECONNECT_DELAY_MS
                consecutiveFailures = 0
            }

            delay(currentDelay)
        }

        onStatusUpdate(StreamState.DISCONNECTED)
    }

    /**
     * Attempts a TCP connection to the specified port on localhost.
     *
     * Designed to work with ADB reverse tunneling.
     *
     * @param port Server port (forwarded via 'adb reverse tcp:PORT tcp:PORT')
     * @return "true" if the connection was established successfully, "false" otherwise.
     *
     */
    suspend fun connect(port: Int): Boolean {
        if (!isStreaming) return false

        return withContext(Dispatchers.IO) {
            connectionMutex.withLock {
                // Clean up any lingering connections
                closeSocketInternal()

                Log.d(LOG_TAG, "Connecting to 127.0.0.1:${port} via ADB reverse......")

                try {
                    val newSocket = Socket()

                    // Configurations for real-time video streaming
                    newSocket.tcpNoDelay = true // Disable Nagle's algorithm
                    newSocket.soTimeout = 0 // No read timeout
                    newSocket.sendBufferSize = SEND_BUFFER_SIZE // Optimize kernel buffer
                    newSocket.keepAlive = true

                    newSocket.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS)
                    socket = newSocket

                    // BufferedOutputStream to reduce the overhead of constant small native network writes
                    // DataOutputStream to easily writeInt() and writeLong() for the protocol header.
                    dataOutputStream = DataOutputStream(
                        BufferedOutputStream(newSocket.getOutputStream(), SEND_BUFFER_SIZE)
                    )

                    Log.i(LOG_TAG, "TCP connection established")
                    onStatusUpdate(StreamState.CONNECTED)

                    return@withContext true;
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Connection failed: ${e.message}")
                    closeSocketInternal()

                    return@withContext false;
                }
            }
        }
    }

    /** Routes a video frame through a multiplexer */
    suspend fun sendVideoFrame(frame: MediaBufferPool.PooledBuffer){
        sendFrame(TYPE_VIDEO, frame)
    }

    /** Routes an audio frame through a multiplexer */
    suspend fun sendAudioFrame(frame: MediaBufferPool.PooledBuffer){
        sendFrame(TYPE_AUDIO, frame)
    }

    /**
     * Synchronously writes a frame to the socket using our custom binary protocol.
     *
     * @param type Stream identifier ([TYPE_VIDEO] or [TYPE_AUDIO]).
     * @param frame Pooled buffer containing the payload and metadata.
     */
    private suspend fun sendFrame(type: Byte, frame: MediaBufferPool.PooledBuffer){
        if (!isStreaming) return

        return withContext(Dispatchers.IO){
            if (socket == null || socket?.isClosed == true) return@withContext

            writeMutex.withLock {
                val stream = dataOutputStream ?: return@withLock

                try{
                    // Magic Bytes (2 Bytes)
                    stream.writeByte(MAGIC_BYTE_1.toInt())
                    stream.writeByte(MAGIC_BYTE_2.toInt())

                    // Metadata header (5/13 bytes)
                    stream.writeByte(type.toInt())
                    // TODO: Uncomment when A/V sync is implemented
                    // stream.writeLong(frame.presentationTimeMs)
                    stream.writeInt(frame.length)

                    // Payload (N bytes)
                    stream.write(frame.data, 0, frame.length)

                    stream.flush()

                } catch (e: Exception){
                    Log.e(LOG_TAG, "Write error, closing socket", e)
                    connectionMutex.withLock {
                        closeSocketInternal()
                    }
                }
            }
        }
    }

    /**
     * Closes the TCP connection and cleans up the resources.
     */
    suspend fun disconnect(){
        Log.i(LOG_TAG, "Disconnect requested. Shutting down streamer.")
        isStreaming = false

        return withContext(Dispatchers.IO){
            connectionMutex.withLock {
                closeSocketInternal()
                onStatusUpdate(StreamState.DISCONNECTED)
            }
        }
    }

    /**
     * Internal helper to close streams and socket safely
     * WARNING: Must be called inside a Mutex lock to prevent race conditions.
     */
    private fun closeSocketInternal(){
        try{
            dataOutputStream?.close()
            socket?.close()
            Log.i(LOG_TAG, "Connection closed")

        } catch (e: Exception){
            Log.e(LOG_TAG, "Error during socket close", e)
        } finally {
            dataOutputStream = null
            socket = null
        }
    }
}