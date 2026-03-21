package com.fossforbrokies.brokiecam

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.fossforbrokies.brokiecam.core.audio.MicManager
import com.fossforbrokies.brokiecam.core.video.CameraManager
import com.fossforbrokies.brokiecam.core.video.H264Encoder
import com.fossforbrokies.brokiecam.network.repository.CameraStreamRepositoryImpl
import com.fossforbrokies.brokiecam.network.socket.TcpFrameStreamer
import com.fossforbrokies.brokiecam.ui.camera.CameraViewModel

/**
 * Custom Application class that holds manual dependency injection container.
 */
class BrokieCamApplication: Application() {
    /** The global dependency container. */
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        // Initialize the container, passing the application context so Singletons can use it
        container = AppContainer(this)
    }
}

/**
 * Manual Dependency Injection Container.
 *
 * @property application The application context, passed down to components that need OS-level access.
 */
class AppContainer(private val application: Application){

    /** Hardware encoder */
    private val h264Encoder by lazy {
        H264Encoder(
            width = 1280,
            height = 720,
            frameRate = 30,
            bitRate = 4_000_000
        )
    }

    /** CameraX lifecycle manager */
    private val cameraManager by lazy {
        CameraManager(
            context = application,
            encoder = h264Encoder
        )
    }

    /** Mic lifecycle manager */
    private val micManager by lazy {
        MicManager()
    }

    // Repository
    val repository by lazy {
        CameraStreamRepositoryImpl(
            cameraManager = cameraManager,
            micManager = micManager,
            streamerFactory = { onStatusUpdateCallback ->
                TcpFrameStreamer(onStatusUpdate = onStatusUpdateCallback)
            }
        )
    }

    // ViewModel
    val viewModelFactory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(CameraViewModel::class.java)) {
                return CameraViewModel(repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}