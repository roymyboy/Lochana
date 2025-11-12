package com.lochana.app.core

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lochana.app.databinding.ActivityMainBinding

class PermissionManager(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding
) {

    companion object {
        private const val TAG = "PermissionManager"
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }

    private var onAllPermissionsGranted: (() -> Unit)? = null
    private var onNoPermissionsGranted: (() -> Unit)? = null

    fun setCallbacks(
        onAllPermissionsGranted: (() -> Unit)? = null,
        onNoPermissionsGranted: (() -> Unit)? = null
    ) {
        this.onAllPermissionsGranted = onAllPermissionsGranted
        this.onNoPermissionsGranted = onNoPermissionsGranted
    }

    private val requestPermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val microphoneGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false

        Log.d(TAG, "Permission results - Camera: $cameraGranted, Microphone: $microphoneGranted")

        when {
            cameraGranted && microphoneGranted -> {
                Log.d(TAG, "✅ All permissions granted")
                onAllPermissionsGranted?.invoke()
            }
            cameraGranted && !microphoneGranted -> {
                Log.d(TAG, "✅ Camera permission granted, ⚠️ Microphone permission denied")
                onAllPermissionsGranted?.invoke()
            }
            else -> {
                Log.w(TAG, "❌ Camera permission denied")
                onNoPermissionsGranted?.invoke()
            }
        }
    }

    fun requestPermissions() {
        val cameraPermission = ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val microphonePermission = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        Log.d(TAG, "Checking permissions - Camera: $cameraPermission, Microphone: $microphonePermission")

        when {
            cameraPermission && microphonePermission -> {
                Log.d(TAG, "✅ All permissions already granted")
                onAllPermissionsGranted?.invoke()
            }
            else -> {
                val permissionsToRequest = mutableListOf<String>()

                if (!cameraPermission) {
                    permissionsToRequest.add(Manifest.permission.CAMERA)
                    Log.d(TAG, "🔐 Need camera permission...")
                }

                if (!microphonePermission) {
                    permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
                    Log.d(TAG, "🔐 Need microphone permission...")
                }

                Log.d(TAG, "This app needs camera access to help you identify objects and microphone access for voice input.")

                requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
            }
        }
    }

    fun isMicrophonePermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isCameraPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }
}
