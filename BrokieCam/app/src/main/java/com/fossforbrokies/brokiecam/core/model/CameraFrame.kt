package com.fossforbrokies.brokiecam.core.model

/**
 * Represents a single video frame captured from the camera
 * @param data The raw binary data of the image (JPEG ByteArray)
 * @param timestamp The time the frame was captured
 * @param width The width of the captured frame
 * @param height The height of the captured frame
 */
data class CameraFrame(
    val data: ByteArray,
    val timestamp: Long = System.currentTimeMillis(),
    val width: Int,
    val height: Int
)
