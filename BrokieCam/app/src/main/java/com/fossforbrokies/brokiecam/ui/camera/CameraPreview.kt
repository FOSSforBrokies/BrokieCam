package com.fossforbrokies.brokiecam.ui.camera

import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.concurrent.futures.await
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fossforbrokies.brokiecam.utils.toJpegByteArray
import java.util.concurrent.Executors

private const val LOG_TAG = "CameraPreview"

/**
 * Composable that displays live camera preview and captures frames for streaming
 *
 * Architecture:
 * - Preview UseCase: Renders camera feed to UI
 * - ImageAnalysis UseCase: Captures frames for processing
 *
 * @param modifier Compose modifier for layout
 * @param scaleType How camera preview fits in the view (FILL_CENTER crops to fit)
 * @param onFrameCaptured Callback with JPEG bytes and dimensions for each captured frame
 */
@Composable
fun CameraPreview (
    modifier: Modifier = Modifier,
    scaleType: PreviewView.ScaleType = PreviewView.ScaleType.FILL_CENTER,
    onFrameCaptured: (ByteArray, Int, Int) -> Unit
){
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    /** Dedicated thread for image processing (YUV -> JPEG conversion */
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    /** Android View for rendering camera preview */
    val previewView = remember { PreviewView(context).apply { this.scaleType = scaleType } }

    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
        }
    }

    LaunchedEffect(lifecycleOwner) {
        val cameraProvider = ProcessCameraProvider.getInstance(context).await()

        // --- PREVIEW USE CASE ---
        val preview = Preview.Builder().build().also {
            // surfaceProvider
            // - ready-made object inside PreviewView
            // - bridge object that can receive frames from a use case (Preview) and render them
            // - preview --> (frames) --> surfaceProvider --> PreviewView reads frames from surfaceProvider and renders them
            it.surfaceProvider = previewView.surfaceProvider
        }
        // --- IMAGE ANALYSIS USE CASE ---
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1280, 720),
                    FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
            )
            .build()

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(resolutionSelector)
            .build()

        imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
            try {
                // Convert YUV_420_888 → JPEG
                val jpegBytes = imageProxy.toJpegByteArray(quality = 60)

                if (jpegBytes.isNotEmpty()) {
                    onFrameCaptured(jpegBytes, imageProxy.width, imageProxy.height)
                }

            } catch (e: Exception) {
                Log.e(LOG_TAG, "[!] Frame analysis failed", e)
            } finally {
                imageProxy.close()
            }
        }

        // ---- BIND TO LIFECYCLE ----
        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                imageAnalysis
            )
        } catch (e: Exception){
            Log.e(LOG_TAG, "[!] Camera binding failed", e)
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )
}