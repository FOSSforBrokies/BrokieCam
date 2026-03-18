package com.fossforbrokies.brokiecam.core.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import com.fossforbrokies.brokiecam.core.pool.MediaBufferPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val LOG_TAG = "MicManager"

/**
 * Audio capture manager.
 *
 * Captures raw, uncompressed PCM audio directly from the device's microphone.
 *
 * @param sampleRate Sample rate in Hz. Defaults to 48000.
 * @param channelConfig Channel configuration. Defaults to Mono.
 * @param audioFormat Encoding format. Defaults to 16-bit PCM.
 */
class MicManager (
    private val sampleRate: Int = 48000,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
){
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordJob: Job? = null

    /** Minimum byte size required by the OS hardware to initialize the microphone. */
    private val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    /** Fixed size of the memory read each iteration. */
    private val readBufferSize = 2048

    /** Audio Pool */
    private val audioPool = MediaBufferPool(poolSize = 51, bufferSize = readBufferSize)

    /** A sinkhole array used to safely drain the OS microphone buffer when internal pool is empty */
    private val trashBuffer = ByteArray(readBufferSize)

    /** Backpressure-aware internal channel */
    private val _audioChannel = Channel<MediaBufferPool.PooledBuffer>(
        capacity = 50,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { droppedFrame ->
            Log.w(LOG_TAG, "Network lagging: Dropped OLDEST audio frame to maintain real-time.")
            droppedFrame.release()
        }
    )

    /** Flow exposing raw PCM audio chunks */
    val audioFrameFlow = _audioChannel.receiveAsFlow()

    /**
     * Initializes the microphone hardware.
     * Spawns a background coroutine to continuously feed audio data into [audioFrameFlow]
     *
     * @param scope The coroutine scope tied to the lifecycle of the recording session.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(scope: CoroutineScope){
        if (isRecording) return

        try{
            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE){
                Log.e(LOG_TAG, "Hardware does not support this audio format")
                return
            }

            // Set up AudioRecord
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                minBufferSize * 2 // allocate twice the minimum size for safety
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED){
                Log.e(LOG_TAG, "AudioRecord failed to initialize")
                return
            }


            audioRecord?.startRecording()
            isRecording = true

            // Spin up a dedicated loop to feed audio data into the channel
            recordJob = scope.launch(Dispatchers.IO){
                readAudioLoop()
            }

            Log.i(LOG_TAG, "MicManager started successfully")

        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to start MicManager", e)
        }
    }

    /**
     * Continuously read PCM audio chunks from [AudioRecord] and send them to [_audioChannel]
     *
     * If pool is empty, the loop will intentionally read and discard the audio into a sinkhole
     * to prevent the OS hardware buffer from overflowing
     */
    private suspend fun readAudioLoop(){
        while (currentCoroutineContext().isActive && isRecording){
            val pooledBuffer = audioPool.acquire()

            if (pooledBuffer == null){
                audioRecord?.read(trashBuffer, 0, readBufferSize)
                Log.w(LOG_TAG, "Network lag: Pool empty, dropping new audio.")
            } else {
                val bytesRead = audioRecord?.read(pooledBuffer.data, 0, readBufferSize ) ?: 0

                if (bytesRead > 0){
                    pooledBuffer.length = bytesRead
                    pooledBuffer.presentationTimeMs = System.nanoTime() / 1000

                    _audioChannel.trySend(pooledBuffer)
                } else {
                    pooledBuffer.release()

                    if (bytesRead < 0) break
                }
            }
        }
    }

    /**
     * Tears down the audio pipeline, cancels the read coroutine,
     * and releases the microphone hardware,
     */
    suspend fun stop(){
        if (!isRecording) return

        isRecording = false
        recordJob?.cancelAndJoin()

        try{
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception){
            Log.e(LOG_TAG, "Error stopping MicManager", e)
        } finally {
            audioRecord = null
            Log.i(LOG_TAG, "MicManager stopped")
        }
    }
}
