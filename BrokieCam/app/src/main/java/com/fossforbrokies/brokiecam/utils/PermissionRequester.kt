package com.fossforbrokies.brokiecam.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Composable helper to request runtime permissions
 *
 * @param onPermissionGranted Callback invoked when the permission is granted (or was already granted)
 */
@Composable
fun PermissionRequester(
    onPermissionGranted: () -> Unit
){
    val context = LocalContext.current

    // Define permissions
    val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA
        // Manifest.permission.RECORD_AUDIO
    )

    // Launcher to request permissions
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        val allGranted = permissionsMap.values.all { it }

        if (allGranted) {
            onPermissionGranted()
        }
//        else {
//            // TODO: Toast
//        }
    }

    LaunchedEffect(Unit) {
        val  allAlreadyGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allAlreadyGranted){
            onPermissionGranted()
        } else{
            launcher.launch(requiredPermissions)
        }
    }
}