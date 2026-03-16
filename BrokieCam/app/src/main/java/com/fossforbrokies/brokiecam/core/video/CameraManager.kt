package com.fossforbrokies.brokiecam.core.video

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.concurrent.futures.await
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor

private const val LOG_TAG = "CameraManager"

/**
 * Manages the CameraX lifecycle and pipes frames to the H264Encoder.
 * Designed for headless operation (no UI/PreviewView).
 *
 * @property context The application or activity context to initialize [ProcessCameraProvider].
 * @property encoder The hardware video encoder that will consume the camera frames.
 */
class CameraManager(
    private val context: Context,
    private val encoder: H264Encoder
){
    private var cameraProvider: ProcessCameraProvider? = null

    /** Flow exposing the encoder's NAL units */
    val videoFrameFlow = encoder.videoFrameFlow

    /**
     * Prepares the encoder, initializes the CameraX , binds the CameraX pipeline to the encoder's input surface.
     *
     * @param lifecycleOwner The Activity or Fragment lifecycle controlling the camera hardware.
     * @param scope CoroutineScope used to manage background encoding tasks.
     * @param width The target resolution width. Defaults to 1280.
     * @param height The target resolution height. Defaults to 720.
     * @throws IllegalStateException If the encoder fails to provide a valid input surface.
     */
    suspend fun startStreaming(
        lifecycleOwner: LifecycleOwner,
        scope: CoroutineScope,
        width: Int = 1280,
        height: Int = 720
    ){
        try{
            // Start the encoder to generate the input surface
            encoder.start(scope)
            val surface = encoder.inputSurface ?: throw IllegalStateException("Encoder Surface not ready")

            // Initialize CameraX provider
            cameraProvider = ProcessCameraProvider.getInstance(context).await()

            // Define the resolution strategy
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(width, height),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            // Set up preview use case
            val preview = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build()

            // Connect CameraX directly to  encoder's surface
            // CameraX -> Surface -> H264Encoder
            preview.setSurfaceProvider(Dispatchers.IO.asExecutor()){ request ->
                Log.d(LOG_TAG, "Providing Surface to CameraX: ${width}x${height}")
                request.provideSurface(surface, Dispatchers.IO.asExecutor()){ result ->
                    Log.d(LOG_TAG, "Surface delivery result: ${result.resultCode}")
                }
            }

            // Bind to a lifecycle
            cameraProvider?.unbindAll()
            cameraProvider?.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview
            )

            Log.i(LOG_TAG, "CameraX-to-H264 pipeline successfully bound")

        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to setup CameraX pipeline", e)
            stopStreaming()
        }
    }

    /**
     * Safely tears down the camera pipeline.
     * Unbinds all CameraX use cases and signals the [H264Encoder] to release its resources
     */
    suspend fun stopStreaming() {
        try{
            cameraProvider?.unbindAll()
            encoder.stop()
            Log.i(LOG_TAG, "CameraManager stopped streaming")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error during CameraManager shutdown", e)
        } finally {
            cameraProvider = null
        }
    }
}