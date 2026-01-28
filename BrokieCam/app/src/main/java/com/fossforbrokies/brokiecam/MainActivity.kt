package com.fossforbrokies.brokiecam

//import android.os.Bundle
//import androidx.activity.ComponentActivity
//import androidx.activity.compose.setContent
//import androidx.activity.enableEdgeToEdge
//import com.fossforbrokies.brokiecam.ui.theme.BrokieCamTheme

//class MainActivity : ComponentActivity() {
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        enableEdgeToEdge()
//        setContent {
//            BrokieCamTheme {
////                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
////                    Greeting(
////                        name = "Android",
////                        modifier = Modifier.padding(innerPadding)
////                    )
////                }
//            }
//        }
//    }
//}

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.*
import com.fossforbrokies.brokiecam.ui.camera.CameraScreen
import com.fossforbrokies.brokiecam.ui.camera.CameraViewModel
import com.fossforbrokies.brokiecam.utils.PermissionRequester

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // App instance to access DI container
        val app = application as BrokieCamApplication

        val viewModel: CameraViewModel by viewModels { app.container.viewModelFactory }

        setContent {
            var canShowCamera by remember { mutableStateOf(false) }

            if (canShowCamera){
                CameraScreen(viewModel)
            } else {
                PermissionRequester(
                    onPermissionGranted = { canShowCamera = true }
                )
            }
        }
    }
}