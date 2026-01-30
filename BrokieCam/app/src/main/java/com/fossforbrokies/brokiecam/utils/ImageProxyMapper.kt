package com.fossforbrokies.brokiecam.utils

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

// Logging Tag
private const val LOG_TAG = "ImageUtils"

/**
 * Extension function to convert an ImageProxy (YUV_420_888) into a JPEG byte array
 * This function handles the complex task of flattening the Y, U, and V planes (which may include
 * hardware padding/strides) into the standard NV21 format required by Android's YuvImage compressor
 *
 * @param quality Compression quality (0-100). Lower values = faster/smaller
 * @return ByteArray containing the JPEG data or an empty array on failure
 */
fun ImageProxy.toJpegByteArray(quality: Int = 50): ByteArray {

    if (format != ImageFormat.YUV_420_888 || planes.size < 3) {
        Log.e(LOG_TAG, "Unsupported format or missing planes. Format: $format")
        return ByteArray(0)
    }

    try {
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        // Rewind buffers in case they were read elsewhere
        yBuffer.rewind()
        uBuffer.rewind()
        vBuffer.rewind()

        val ySize = yBuffer.remaining()
        // Determine the total size needed for NV21 (Y + UV)
        // NV21 size is typically: width * height * 1.5
        val nv21Size = width * height + (width * height / 2)
        val nv21 = ByteArray(nv21Size)

        // ------------------------
        // Copy Y Plane
        // ------------------------
        val yRowStride = planes[0].rowStride
        val yPixelStride = planes[0].pixelStride

        if (yRowStride == width) {
            // Optimized Path: No padding, copy everything at once
            yBuffer.get(nv21, 0, width * height)
        } else {
            // Un-optimized Path: Device adds padding to rows. Copy row-by-row.
            var inputOffset = 0
            var outputOffset = 0
            for (row in 0 until height) {
                yBuffer.position(inputOffset)
                yBuffer.get(nv21, outputOffset, width)
                inputOffset += yRowStride
                outputOffset += width
            }
        }

        // ------------------------
        // Copy UV Planes
        // ------------------------
        // NV21 layout expects V first, then U (V, U, V, U...)
        val vRowStride = planes[2].rowStride
        val vPixelStride = planes[2].pixelStride
        val uRowStride = planes[1].rowStride
        val uPixelStride = planes[1].pixelStride

        val uvWidth = width / 2
        val uvHeight = height / 2

        // Offset in the destination array where UV data begins (after all Y data)
        var outputOffset = width * height

        // Iterate rows and cols of the UV plane
        for (row in 0 until uvHeight) {

            // Calculate starting positions for this row in the source buffers
            val uRowIndex = row * uRowStride
            val vRowIndex = row * vRowStride

            for (col in 0 until uvWidth) {

                // Calculate exact position of the pixel in source buffers
                val vIndex = vRowIndex + (col * vPixelStride)
                val uIndex = uRowIndex + (col * uPixelStride)

                // NV21 Format: V comes first, then U
                // Perform bounds checking implicitly via buffer.get() behavior

                if (vIndex < vBuffer.capacity()) {
                    nv21[outputOffset++] = vBuffer.get(vIndex)
                } else {
                    // Fallback for edge case padding
                    nv21[outputOffset++] = 0
                }

                if (uIndex < uBuffer.capacity()) {
                    nv21[outputOffset++] = uBuffer.get(uIndex)
                } else {
                    nv21[outputOffset++] = 0
                }
            }
        }

        // ------------------------
        // Compress to JPEG
        // ------------------------
        // Use YuvImage (Android's optimized native compressor)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()

        yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, out)

        return out.toByteArray()

    } catch (e: Exception) {

        Log.e(LOG_TAG, "[!] Error converting ImageProxy to JPEG", e)
        return ByteArray(0)
    }
}