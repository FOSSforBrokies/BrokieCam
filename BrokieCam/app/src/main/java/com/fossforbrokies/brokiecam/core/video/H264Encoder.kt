package com.fossforbrokies.brokiecam.core.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val LOG_TAG = "H264Encoder"

/**
 * Hardware-accelerated H.264 Video Encoder
 * Captures raw frames from a surface and outputs Annex-B formatted NAL units.
 */
class H264Encoder(
    private val width: Int = 1280,
    private val height: Int = 720,
    private val frameRate: Int = 30,
    private val bitRate: Int = 4_000_000, // 4 Mbps stream
    private val onFrameEncoded: (ByteArray) -> Unit
) {
    private var codec: MediaCodec? = null
    var inputSurface: Surface? = null
        private set

    private var isEncoding = false
    private var encoderJob: Job? = null

    // Cache the Sequence Parameter Set (SPS) and Picture Parameter Set (PPS)
    private var configData: ByteArray? = null

    /**
     * Starts the hardware encoder and prepares the input surface
     */
    fun start(scope: CoroutineScope){
        if (isEncoding) return

        try{

            // Encoder configurations
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            // Set up encoder
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = createInputSurface()
                start()
            }

            isEncoding = true

            encoderJob = scope.launch(Dispatchers.IO){
                drainOutputLoop()
            }

        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to start H.264 Encoder", e)
        }
    }

    /**
     * Continuously reads encoded NAL units from MediaCodec and formats them
     */
    private suspend fun drainOutputLoop(){
        // Metadata for each encoded frame (size, offset, timestamp, flags)
        // dequeueOutputBuffer() dynamically overwrites those values in the loop
        val bufferInfo = MediaCodec.BufferInfo()
        val currentCodec = codec ?: return

        while (currentCoroutineContext().isActive && isEncoding){
            try{
                // Wait up to 10ms for a new buffer from internal buffer pool
                val outputBufferId = currentCodec.dequeueOutputBuffer(bufferInfo, 10000)

                if (outputBufferId >= 0){
                    val outputBuffer = currentCodec.getOutputBuffer(outputBufferId)

                    if (outputBuffer != null && bufferInfo.size > 0){
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        // Save to
                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.get(chunk)

                        // If the buffer contains SPS/PPS headers, cache them
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0){
                            Log.d(LOG_TAG, "Cached SPS/PPS Config Data (${chunk.size} bytes)")
                            configData = chunk
                        } else{
                            // If it's a keyframe, inject the SPS/PPS config data right before it
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 && configData != null){
                                val keyframeWithConfig = configData!! + chunk
                                onFrameEncoded(keyframeWithConfig)

                            // If it's a normal frame
                            } else{
                                onFrameEncoded(chunk)
                            }
                        }
                    }

                    currentCodec.releaseOutputBuffer(outputBufferId, false)
                }

            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error draining encoder buffer", e)
                break
            }
        }
    }

    /**
     * Stops the hardware encoder
     */
    fun stop(){
        if (!isEncoding) return

        isEncoding = false
        encoderJob?.cancel()

        try{
            codec?.stop()
            codec?.release()
            inputSurface?.release()
        } catch (e: Exception){
            Log.e(LOG_TAG, "Error tearing down encoder", e)
        } finally {
            codec = null
            inputSurface = null
            Log.i(LOG_TAG, "H.264 hardware encoder stopped")
        }
    }
}