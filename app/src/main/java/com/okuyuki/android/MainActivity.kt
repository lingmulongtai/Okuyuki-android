package com.okuyuki.android

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val app get() = application as OkuyukiApplication
    private val photosPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { app.library.refresh() }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startHost() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PhoneApp(app, requestPhotos = {
            photosPermission.launch(com.okuyuki.android.library.PhotoLibrary.MEDIA_PERMISSIONS)
        }, toggleHost = { enabled ->
            if (enabled) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else stopService(Intent(this, com.okuyuki.android.host.HostService::class.java))
        }) }
    }
    override fun onResume() { super.onResume(); app.library.refresh() }
    private fun startHost() {
        try { ContextCompat.startForegroundService(this, Intent(this, com.okuyuki.android.host.HostService::class.java)) }
        catch (_: Exception) { com.okuyuki.android.host.HostRuntime.state.value = com.okuyuki.android.host.HostState(error = "待機を開始できませんでした。アプリを開いた状態で再試行してください") }
    }
}
