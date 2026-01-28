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
 * Streamer for sending camera frames over a TCP socket using a simple binary protocol
 * Use a buffered DataOutputStream for efficient writes
 *
 * @param onStatusUpdate Callback invoked with true/false when the connection state changes
 */
class TcpFrameStreamer (
    private val onStatusUpdate: (Boolean) -> Unit
){
    private var socket: Socket? = null
    private var dataOutputStream: DataOutputStream? = null

    private val connectionMutex = Mutex()

    /**
     * Connect to the host via ABD reverse tunneling
     */
    suspend fun connect(port: Int) {
        return withContext(Dispatchers.IO) {
            connectionMutex.withLock {
                // Clean up ensuring no lingering sockets exist
                closeSocketInternal()

                Log.d(LOG_TAG, "Connecting to 127.0.0.1:${port}...")

                try {
                    val newSocket = Socket()

                    // Tcp optimizations for real-time video
                    newSocket.tcpNoDelay = true
                    newSocket.soTimeout = 0
                    newSocket.sendBufferSize = SEND_BUFFER_SIZE

                    newSocket.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS)


                    // DataOutputStream for easy writing
                    // Buffer the output to reduce system calls
                    socket = newSocket
                    dataOutputStream =
                        DataOutputStream(BufferedOutputStream(newSocket.getOutputStream()))

                    Log.i(LOG_TAG, "TCP connection established")
                    onStatusUpdate(true)
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Connection failed: ${e.message}")
                    onStatusUpdate(false)
                }
            }
        }
    }

    /**
     * Send a frame with a binary header
     * Protocol: [MAGIC_NUMBER (2 bytes)] + [LENGTH (4 bytes)] + [PAYLOAD (N bytes)]
     */
    suspend fun sendFrame(frameData: ByteArray){
        return withContext(Dispatchers.IO){
            if (socket?.isConnected != true) return@withContext

            connectionMutex.withLock {
                val stream = dataOutputStream ?: return@withLock

                try{
                    stream.writeShort(MAGIC_NUMBER.toInt())
                    stream.writeInt(frameData.size)
                    stream.write(frameData)
                    stream.flush()
                } catch (e: Exception){
                    Log.e(LOG_TAG, "Write error, closing socket", e)
                    closeSocketInternal()
                    onStatusUpdate(false)
                }
            }
        }
    }

    suspend fun disconnect(){
        return withContext(Dispatchers.IO){
            connectionMutex.withLock {
                closeSocketInternal()
                onStatusUpdate(false)
            }
        }
    }

    /**
     * Internal helper to close resources safely
     * Must be called inside a Mutex lock
     */
    private fun closeSocketInternal(){
        try{
            dataOutputStream?.close()
            socket?.close()
            Log.d(LOG_TAG, "Connection closed")

        } catch (e: Exception){
            Log.w(LOG_TAG, "Error during socket close", e)
        } finally {
            dataOutputStream = null
            socket = null
        }
    }
}