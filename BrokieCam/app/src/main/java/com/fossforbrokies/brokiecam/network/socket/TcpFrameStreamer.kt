package com.fossforbrokies.brokiecam.network.socket

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

private const val LOG_TAG = "TcpFrameStreamer"
private const val CONNECT_TIMEOUT_MS = 3000
private const val SEND_BUFFER_SIZE = 64 * 1024 // 64KB

private const val MAGIC_NUMBER: Short = 0xFEED.toShort()

/**
 * Low-level TCP streamer for sending camera frames using a binary protocol
 * Uses DataOutputStream for efficient binary writes and BufferedOutputStream to reduce syscalls
 *
 * Protocol Format:
 * [MAGIC_NUMBER (2 bytes BE)] + [LENGTH (4 bytes BE)] + [JPEG DATA (N bytes)]
 *
 * @param onStatusUpdate Callback invoked when connection state changes (true = connected, false = disconnected)
 */
class TcpFrameStreamer (
    private val onStatusUpdate: (Boolean) -> Unit
){
    private var socket: Socket? = null
    private var dataOutputStream: DataOutputStream? = null

    /** Ensures thread-safe access to socket resources */
    private val connectionMutex = Mutex()

    /**
     * Establishes a TCP connection via ADB reverse tunneling
     * Always connects to localhost (127.0.0.1) since ADB forwards the port
     *
     * @param port Server port (forwarded via 'adb reverse tcp:PORT tcp:PORT')
     */
    suspend fun connect(port: Int) {
        return withContext(Dispatchers.IO) {
            connectionMutex.withLock {
                // Clean up any lingering connections
                closeSocketInternal()

                Log.d(LOG_TAG, "[+] Connecting to 127.0.0.1:${port} via ADB reverse......")

                try {
                    val newSocket = Socket()

                    // Tcp optimizations for real-time video streaming
                    newSocket.tcpNoDelay = true // Disable Nagle's algorithm
                    newSocket.soTimeout = 0 // No read timeout
                    newSocket.sendBufferSize = SEND_BUFFER_SIZE // Optimize kernel buffer

                    newSocket.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS)

                    socket = newSocket
                    dataOutputStream =
                        DataOutputStream(
                            BufferedOutputStream(newSocket.getOutputStream()) // Buffer the output to reduce system calls
                        )

                    Log.i(LOG_TAG, "[+] TCP connection established")
                    onStatusUpdate(true)
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "[!] Connection failed: ${e.message}")
                    onStatusUpdate(false)
                }
            }
        }
    }

    /**
     * Sends a JPEG frame with binary header protocol
     * Protocol: [MAGIC_NUMBER (2 bytes)] + [LENGTH (4 bytes)] + [PAYLOAD (N bytes)]
     *
     * @param frameData JPEG-encoded image bytes
     */
    suspend fun sendFrame(frameData: ByteArray){
        return withContext(Dispatchers.IO){
            if (socket?.isConnected != true) return@withContext

            connectionMutex.withLock {
                val stream = dataOutputStream ?: return@withLock

                try{
                    // Write protocol header
                    stream.writeShort(MAGIC_NUMBER.toInt())
                    stream.writeInt(frameData.size)
                    stream.write(frameData)
                    stream.flush()
                } catch (e: Exception){
                    Log.e(LOG_TAG, "[!] Write error, closing socket", e)
                    closeSocketInternal()
                    onStatusUpdate(false)
                }
            }
        }
    }

    /**
     * Closes the TCP connection
     */
    suspend fun disconnect(){
        return withContext(Dispatchers.IO){
            connectionMutex.withLock {
                closeSocketInternal()
                onStatusUpdate(false)
            }
        }
    }

    /**
     * Internal helper to close streams and socket safely
     * Must be called inside a Mutex lock
     */
    private fun closeSocketInternal(){
        try{
            dataOutputStream?.close()
            socket?.close()
            Log.d(LOG_TAG, "[-] Connection closed")

        } catch (e: Exception){
            Log.w(LOG_TAG, "[!] Error during socket close", e)
        } finally {
            dataOutputStream = null
            socket = null
        }
    }
}