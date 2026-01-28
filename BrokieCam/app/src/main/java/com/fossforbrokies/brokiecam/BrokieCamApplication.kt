package com.fossforbrokies.brokiecam

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.fossforbrokies.brokiecam.network.repository.CameraStreamRepositoryImpl
import com.fossforbrokies.brokiecam.ui.camera.CameraViewModel

/**
 * Manual dependency injection container
 */
class BrokieCamApplication: Application() {
    private val repository by lazy {
        CameraStreamRepositoryImpl()
    }

    val container = AppContainer()

    inner class AppContainer(){
        val viewModelFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return CameraViewModel(repository) as T
            }
        }
    }

}