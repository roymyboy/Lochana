package com.lochana.app

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import android.os.Build
import com.lochana.app.camera.CameraManager
import com.lochana.app.core.ConfigLoader
import com.lochana.app.core.CrashHandler
import com.lochana.app.core.PermissionManager
import com.lochana.app.databinding.ActivityMainBinding
import com.lochana.app.ocr.OcrProcessor
import com.lochana.app.pipeline.HighResolutionCapturePipeline
import com.lochana.app.ui.UIManager
import com.lochana.app.ui.UIManager.CaptureMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.lochana.app.vision.DetectionManager
import com.lochana.app.vision.YOLOv11Manager
import com.lochana.app.openai.OpenAIManager
import com.lochana.app.openai.OpenAIKeyManager

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var cameraManager: CameraManager
    private lateinit var detectionManager: DetectionManager
    private lateinit var permissionManager: PermissionManager
    private lateinit var uiManager: UIManager
    private lateinit var openAIManager: OpenAIManager
    private lateinit var yoloManager: YOLOv11Manager
    private lateinit var highResPipeline: HighResolutionCapturePipeline
    private val ocrProcessor: OcrProcessor by lazy { OcrProcessor() }
    private var pendingCustomPrompt: String? = null
    private var pendingPreviewImagePath: String? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            // Initialize crash handler first
            CrashHandler.initialize(this)
            
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            setupFullScreen()
            initializeComponents()
            setupManagers()
            setupCallbacks()
            setupUI()
            requestPermissions()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Fatal error in onCreate: ${e.message}")
            e.printStackTrace()
            // Try to show error to user
            try {
                android.widget.Toast.makeText(this, "App initialization failed - restart app", android.widget.Toast.LENGTH_LONG).show()
            } catch (err: Exception) {
                Log.e(TAG, "❌ Cannot show error toast: ${err.message}")
            }
        }
    }
    
    private fun setupFullScreen() {
        supportActionBar?.hide()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        ViewCompat.setOnApplyWindowInsetsListener(binding.cameraContainer) { view, insets ->
            val statusInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(view.paddingLeft, 0, view.paddingRight, view.paddingBottom)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.viewFinder) { _, insets ->
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun initializeComponents() {
        try {
            cameraExecutor = Executors.newSingleThreadExecutor()
            yoloManager = YOLOv11Manager(this)
            cameraManager = CameraManager(this, binding, cameraExecutor)
            detectionManager = DetectionManager(cameraExecutor, yoloManager)
            permissionManager = PermissionManager(this, binding)
            uiManager = UIManager(this, binding)
            openAIManager = OpenAIManager(this)
            highResPipeline = HighResolutionCapturePipeline(cameraManager, cameraExecutor)
            
            loadOpenAIKey()
            yoloManager.initialize()
            
            if (openAIManager.isEnabled()) {
                startVideoAnalysis()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize components: ${e.message}")
            e.printStackTrace()
            throw e // Re-throw to be caught by onCreate
        }
    }

    private fun setupManagers() {
        permissionManager.setCallbacks(
            onAllPermissionsGranted = { startApp() },
            onNoPermissionsGranted = { showPermissionRequired() }
        )
        uiManager.initialize()
    }
    
    private fun setupCallbacks() {
        try {
            // Check if managers are initialized before setting callbacks
            if (!::cameraManager.isInitialized) {
                Log.e(TAG, "❌ Camera manager not initialized - cannot set callbacks")
                return
            }
            if (!::uiManager.isInitialized) {
                Log.e(TAG, "❌ UI manager not initialized - cannot set callbacks")
                return
            }
            if (!::openAIManager.isInitialized) {
                Log.e(TAG, "❌ OpenAI manager not initialized - cannot set callbacks")
                return
            }
            if (!::detectionManager.isInitialized) {
                Log.e(TAG, "❌ Detection manager not initialized - cannot set callbacks")
                return
            }
            
            cameraManager.setCallbacks(
                onCameraReady = {
                    try {
                        if (::openAIManager.isInitialized && openAIManager.isEnabled()) {
                            startVideoAnalysis()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error in camera ready callback: ${e.message}")
                    }
                },
                onCameraError = { error ->
                    try {
                        Log.e(TAG, "📷 Camera error: $error")
                        runOnUiThread {
                            try {
                                if (::uiManager.isInitialized) {
                                    uiManager.updateDescription("⚠️ Camera Error\n\n$error\n\nTroubleshooting:\n• Restart the app\n• Check camera permissions\n• Close other camera apps")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "❌ Error updating description: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error in camera error callback: ${e.message}")
                    }
                }
            )

            detectionManager.setCallbacks(
                onVideoFrameCaptured = { bitmap ->
                    try {
                        if (::openAIManager.isInitialized) {
                            openAIManager.captureVideoFrame(bitmap)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error capturing video frame: ${e.message}")
                    }
                },
                onSingleFrameCaptured = { bitmap ->
                    try {
                        if (!::uiManager.isInitialized) return@setCallbacks

                        when (uiManager.getCaptureMode()) {
                            CaptureMode.ANALYSIS -> handleAnalysisSingleFrame(bitmap)
                            CaptureMode.OCR -> handleOcrSingleFrame(bitmap)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error in single frame capture: ${e.message}")
                        e.printStackTrace()
                        pendingCustomPrompt = null
                        pendingPreviewImagePath = null
                        if (::uiManager.isInitialized) {
                            uiManager.setCaptureButtonEnabled(true)
                        }
                    }
                },
                onVideoCaptureComplete = {
                    try {
                        if (::uiManager.isInitialized && uiManager.getCaptureMode() == CaptureMode.OCR) {
                            uiManager.hideTypingIndicator()
                            uiManager.showToast(getString(R.string.toast_ocr_video_not_supported))
                            uiManager.setCaptureButtonEnabled(true)
                            if (::detectionManager.isInitialized) {
                                detectionManager.markAnalysisComplete()
                            }
                            return@setCallbacks
                        }

                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            try {
                                if (::uiManager.isInitialized && ::openAIManager.isInitialized) {
                                    pendingPreviewImagePath = null
                                    val previewFrame = openAIManager.getLatestFrameForPreview()
                                    pendingPreviewImagePath = uiManager.prepareResponsePreview(previewFrame)

                                    val promptSource = when {
                                        pendingCustomPrompt?.isNotBlank() == true -> pendingCustomPrompt
                                        else -> uiManager.consumeUserPrompt()
                                    }

                                    uiManager.ensureUserPromptVisible(promptSource)
                                    openAIManager.triggerVideoAnalysis(promptSource)
                                    pendingCustomPrompt = null
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "❌ Error in video analysis trigger: ${e.message}")
                                e.printStackTrace()
                                pendingCustomPrompt = null
                                pendingPreviewImagePath = null
                                if (::uiManager.isInitialized) {
                                    uiManager.setCaptureButtonEnabled(true)
                                }
                            }
                        }, 500)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error scheduling video analysis: ${e.message}")
                        if (::uiManager.isInitialized) {
                            uiManager.setCaptureButtonEnabled(true)
                        }
                    }
                },
                onVideoCaptureFinished = {
                    try {
                        if (::uiManager.isInitialized) {
                            uiManager.stopCaptureButtonAnimation()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error stopping capture animation: ${e.message}")
                    }
                },
                onYOLODetections = { detections, bitmap ->
                    try {
                        binding.detectionOverlay.updateDetections(detections, bitmap.width, bitmap.height)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error updating YOLO detections: ${e.message}")
                    }
                }
            )
            
            openAIManager.setCallbacks(
                onResponse = { response ->
                    try {
                        if (::uiManager.isInitialized) {
                            val imagePath = pendingPreviewImagePath
                            pendingPreviewImagePath = null
                            uiManager.updateDescriptionWithTypewriter(response, imagePath = imagePath) {
                                try {
                                    if (::uiManager.isInitialized) {
                                        uiManager.setCaptureButtonEnabled(true)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "❌ Error enabling capture button: ${e.message}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error in OpenAI response callback: ${e.message}")
                    }
                },
                onError = { error ->
                    try {
                        Log.e(TAG, "🤖 OpenAI error: $error")
                        if (::uiManager.isInitialized) {
                            uiManager.setCaptureButtonEnabled(true)
                        }
                        pendingPreviewImagePath = null
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error in OpenAI error callback: ${e.message}")
                    }
                },
                onAnalysisComplete = {
                    try {
                        if (::detectionManager.isInitialized) {
                            detectionManager.markAnalysisComplete()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error marking analysis complete: ${e.message}")
                    }
                }
            )

            uiManager.setCallbacks(
                onSingleTap = { point ->
                    try {
                        if (::cameraManager.isInitialized && ::uiManager.isInitialized) {
                            cameraManager.focusOnPoint(point)
                            uiManager.showFocusAnimation(point)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error in single tap callback: ${e.message}")
                    }
                },
                onScale = { scaleFactor ->
                    try {
                        if (::cameraManager.isInitialized) {
                            val newZoom = (cameraManager.getCurrentZoomRatio() * scaleFactor).coerceIn(1.0f, 5.0f)
                            cameraManager.setZoomRatio(newZoom)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error in scale callback: ${e.message}")
                    }
                },
                onCameraToggle = {
                    try {
                        handleCameraToggle()
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error in camera toggle callback: ${e.message}")
                    }
                },
                onCaptureButton = {
                    try {
                        handleManualCapture()
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error in capture button callback: ${e.message}")
                    }
                },
                onLongPressCapture = {
                    try {
                        handleLongPressCapture()
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error in long press capture callback: ${e.message}")
                    }
                }
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error setting up callbacks: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun setupUI() {
        try {
            // Use FIT_CENTER to show maximum coverage like stock camera
            // This shows more of the scene without cropping
            binding.viewFinder.scaleType = PreviewView.ScaleType.FILL_CENTER
            binding.viewFinder.implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            
            Log.d(TAG, "✅ PreviewView configured - ScaleType: FILL_CENTER (edge-to-edge feed)")
            
            val scaleGestureDetector = uiManager.createScaleGestureDetector()
            uiManager.setupScaleGestureDetector(scaleGestureDetector)
            cameraManager.setImageAnalyzer(detectionManager.createImageAnalyzer())
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error setting up UI: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun requestPermissions() {
        permissionManager.requestPermissions()
    }
    
    private fun startApp() {
        Log.d(TAG, "🚀 Starting full app functionality")
        Log.d(TAG, "📱 Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        Log.d(TAG, "📱 Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        
        // More robust initialization for real devices
        var retryCount = 0
        val maxRetries = 30 // Increased for slower devices
        
        fun attemptStart() {
            binding.viewFinder.post {
                val width = binding.viewFinder.width
                val height = binding.viewFinder.height
                val displayWidth = resources.displayMetrics.widthPixels
                val displayHeight = resources.displayMetrics.heightPixels
                
                Log.d(TAG, "📷 Start attempt ${retryCount + 1}: PreviewView=${width}x${height}, Display=${displayWidth}x${displayHeight}")
                
                if (width > 0 && height > 0) {
                    Log.d(TAG, "✅ PreviewView ready: ${width}x${height}")
                    
                    // Start camera
                    cameraManager.startCamera()
                    
                    // YOLOv11 detection runs continuously (independent of detection state)
                    // OpenAI detection is manual only - no automatic analysis
                    uiManager.updateDetectionStatus(false, false)
                    Log.d(TAG, "🔍 OpenAI detection disabled - manual analysis only")
                    Log.d(TAG, "🎯 YOLOv11 detection active - real-time object detection enabled")
                    
                    // Start video analysis for active feedback
                    if (openAIManager.isEnabled()) {
                        Log.d(TAG, "🎥 Starting video analysis for active feedback")
                        startVideoAnalysis()
                    }
                } else if (retryCount < maxRetries) {
                    retryCount++
                    Log.d(TAG, "⏳ PreviewView not ready, retry ${retryCount}/${maxRetries} in 100ms...")
                    binding.viewFinder.postDelayed({ attemptStart() }, 100)
                } else {
                    Log.e(TAG, "❌ PreviewView failed to initialize after ${maxRetries} attempts")
                    uiManager.updateDescription("⚠️ Camera Display Error\n\nThe camera preview failed to initialize.\n\nPlease:\n• Restart the app\n• Check device orientation\n• Ensure no other apps are using the camera")
                    
                    // Try starting camera anyway - might work even if dimensions aren't set
                    Log.w(TAG, "⚠️ Attempting camera start despite PreviewView issues...")
                    cameraManager.startCamera()
                }
            }
        }
        
        // Initial delay to allow view hierarchy to settle (important for real devices)
        binding.viewFinder.postDelayed({
            attemptStart()
        }, 200) // Longer initial delay for real devices
    }
    
    private fun showPermissionRequired() {
        uiManager.updateDescription("Camera permission is required for object detection.")
    }

    private fun handleCameraToggle() {
        Log.d(TAG, "📷 handleCameraToggle called - switching camera")
        
        try {
            Log.d(TAG, "📷 Calling cameraManager.switchCamera()")
            cameraManager.switchCamera()
            
            // Clear detection tracking when switching cameras to prevent stale bounding boxes
            detectionManager.clearTracking()
            Log.d(TAG, "🧹 Cleared detection tracking on camera switch")
            
            Log.d(TAG, "📷 Camera switch call completed")
            Log.d(TAG, "✅ Camera switch completed successfully")
        } catch (e: Exception) {
            val errorMessage = when {
                e.message?.contains("camera") == true -> "Camera hardware error during switch - check device camera"
                e.message?.contains("permission") == true -> "Camera permission error during switch - check app permissions"
                e.message?.contains("binding") == true -> "Camera binding error during switch - restart app"
                else -> "Error switching camera: ${e.message}"
            }
            Log.e(TAG, "❌ Error switching camera: $errorMessage")
            // Don't display camera errors on screen - only log them
        }
    }
    
    /**
     * Handles single tap on capture button for single frame analysis
     */
    private fun handleManualCapture() {
        Log.d(TAG, "📷 Single tap capture requested")
        
        val currentPrompt = if (::uiManager.isInitialized) {
            uiManager.consumeUserPrompt()
        } else {
            null
        }
        pendingCustomPrompt = currentPrompt
        if (::uiManager.isInitialized) {
            uiManager.ensureUserPromptVisible(currentPrompt)
        }
        
        // Show typing indicator while awaiting response
        uiManager.showTypingIndicator()
        Log.d(TAG, "⌛ Awaiting analysis response with typing indicator")
        
        // Enable detection temporarily for manual OpenAI analysis
        // YOLOv11 detection is already running continuously
        detectionManager.startDetection()
        
        // Trigger immediate frame capture and analysis
        detectionManager.triggerManualAnalysis()
        
        // Update UI status
        uiManager.updateDetectionStatus(true, true)
        Log.d(TAG, "📷 Manual capture triggered - YOLOv11 continues in background")
    }

    private fun handleAnalysisSingleFrame(bitmap: Bitmap) {
        if (!::uiManager.isInitialized || !::openAIManager.isInitialized) return

        pendingPreviewImagePath = null
        val fallbackCopy = try {
            bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
        } catch (e: Exception) {
            null
        }

        val promptSource = when {
            pendingCustomPrompt?.isNotBlank() == true -> pendingCustomPrompt
            else -> uiManager.consumeUserPrompt()
        }

        pendingCustomPrompt = null

        uiManager.ensureUserPromptVisible(promptSource)

        val proceedWithAnalysis: (Bitmap?) -> Unit = { previewBitmap ->
            if (previewBitmap != null) {
                pendingPreviewImagePath = uiManager.prepareResponsePreview(previewBitmap)
            } else if (fallbackCopy != null) {
                pendingPreviewImagePath = uiManager.prepareResponsePreview(fallbackCopy)
            }

        openAIManager.captureSingleFrame(bitmap, promptSource)
        }

        highResPipeline.captureBitmap(
            onSuccess = { highResBitmap ->
                proceedWithAnalysis(highResBitmap)
            },
            onError = { error ->
                Log.e(TAG, "❌ High-res capture failed: ${error.message}")
                proceedWithAnalysis(null)
            }
        )
    }

    private fun handleOcrSingleFrame(bitmap: Bitmap) {
        if (!::uiManager.isInitialized) return

        pendingPreviewImagePath = null

        val promptSource = when {
            pendingCustomPrompt?.isNotBlank() == true -> pendingCustomPrompt
            else -> uiManager.consumeUserPrompt()
        }

        uiManager.ensureUserPromptVisible(promptSource)
        pendingCustomPrompt = null

        val previewCopy = try {
            bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "⚠️ Unable to copy bitmap for OCR preview: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error copying bitmap for OCR preview: ${e.message}")
            null
        }

        ocrProcessor.process(
            bitmap,
            onSuccess = { recognizedText ->
                val sanitized = recognizedText.ifBlank { getString(R.string.toast_ocr_no_text) }
                runOnUiThread {
                    try {
                        uiManager.hideTypingIndicator()
                        uiManager.updateDescriptionWithTypewriter(
                            sanitized,
                            previewBitmap = previewCopy
                        ) {
                            uiManager.setCaptureButtonEnabled(true)
                        }
                    } finally {
                        if (::detectionManager.isInitialized) {
                            detectionManager.markAnalysisComplete()
                        }
                    }
                }
            },
            onError = { error ->
                Log.e(TAG, "❌ OCR processing error: ${error.message}")
                runOnUiThread {
                    try {
                        uiManager.hideTypingIndicator()
                        uiManager.showToast(getString(R.string.toast_ocr_failed))
                        uiManager.setCaptureButtonEnabled(true)
                    } finally {
                        if (::detectionManager.isInitialized) {
                            detectionManager.markAnalysisComplete()
                        }
                    }
                }
            }
        )
    }
    
    /**
     * Handles long press on capture button for 5-second video capture
     */
    private fun handleLongPressCapture() {
        Log.d(TAG, "📷 Long press capture requested")

        if (::uiManager.isInitialized && uiManager.getCaptureMode() == CaptureMode.OCR) {
            uiManager.showToast(getString(R.string.toast_ocr_video_not_supported))
            uiManager.setCaptureButtonEnabled(true)
            return
        }
        
        val currentPrompt = if (::uiManager.isInitialized) {
            uiManager.consumeUserPrompt()
        } else {
            null
        }
        pendingCustomPrompt = currentPrompt
        if (::uiManager.isInitialized) {
            uiManager.ensureUserPromptVisible(currentPrompt)
        }
        
        // Show typing indicator while awaiting response
        uiManager.showTypingIndicator()
        Log.d(TAG, "⌛ Awaiting long capture response with typing indicator")
        
        // Start the progress animation
        uiManager.startCaptureButtonAnimation()
        
        // Enable detection temporarily for video capture
        detectionManager.startDetection()
        
        // Trigger 5-second video capture
        detectionManager.triggerLongPressCapture()
        
        // Update UI status
        uiManager.updateDetectionStatus(true, true)
    }
    
    override fun onResume() {
        super.onResume()
        try {
            setupFullScreen()
            
            // Clear old detection overlay to prevent showing stale detections
            try {
                if (::binding.isInitialized) {
                    binding.detectionOverlay.clear()
                    Log.d(TAG, "🧹 Cleared stale detection overlays on resume")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error clearing overlays: ${e.message}")
            }
            
            // Restart camera if it was previously started
            if (::cameraManager.isInitialized && 
                ::permissionManager.isInitialized &&
                permissionManager.isCameraPermissionGranted()) {
                
                // Always try to restart camera on resume
                binding.viewFinder.post {
                    try {
                        if (binding.viewFinder.width > 0 && binding.viewFinder.height > 0) {
                            Log.d(TAG, "📷 Restarting camera on resume")
                            // Reset camera state to allow restart
                            cameraManager.resetCameraState()
                            cameraManager.startCamera()
                        } else {
                            Log.w(TAG, "⚠️ ViewFinder not ready yet")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error restarting camera in onResume: ${e.message}")
                        e.printStackTrace()
                    }
                }
            }
            
            Log.d(TAG, "📱 App resumed")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in onResume: ${e.message}")
            e.printStackTrace()
        }
    }
    
    override fun onPause() {
        super.onPause()
        try {
            // Stop OpenAI video analysis
            if (::openAIManager.isInitialized) {
                openAIManager.stopVideoAnalysis()
            }
            
            if (::uiManager.isInitialized) {
                uiManager.stopAllSpeech()
            }
            
            // Clear detection overlay to prevent stale detections when resuming
            try {
                if (::binding.isInitialized) {
                    binding.detectionOverlay.clear()
                    Log.d(TAG, "🧹 Cleared detection overlays on pause")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error clearing overlays on pause: ${e.message}")
            }
            
            Log.d(TAG, "📱 App paused")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in onPause: ${e.message}")
            e.printStackTrace()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setupFullScreen()
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            if (::cameraManager.isInitialized) {
                try {
                    val cameraProvider = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(this).get()
                    cameraProvider.unbindAll()
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error unbinding camera: ${e.message}")
                }
            }
            if (::openAIManager.isInitialized) {
                openAIManager.resetAnalysisState()
            }
            if (::uiManager.isInitialized) {
                uiManager.stopAllSpeech()
            }
            Log.d(TAG, "📱 App stopped")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in onStop: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private fun loadOpenAIKey() {
        val configKey = ConfigLoader.loadOpenAIKey(this)
        if (configKey != null) {
            openAIManager.setApiKey(configKey)
            return
        }
        val keyManager = OpenAIKeyManager(this)
        val storedKey = keyManager.getApiKey()
        if (storedKey != null) {
            openAIManager.setApiKey(storedKey)
        }
    }

    fun setOpenAIApiKey(apiKey: String) {
        openAIManager.setApiKey(apiKey)
        OpenAIKeyManager(this).storeApiKey(apiKey)
    }

    fun startVideoAnalysis() {
        if (openAIManager.isEnabled()) {
            openAIManager.startVideoAnalysis()
        }
    }

    fun stopVideoAnalysis() {
        openAIManager.stopVideoAnalysis()
        openAIManager.resetAnalysisState()
        uiManager.updateVideoAnalysisStatus(false)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            if (::openAIManager.isInitialized) openAIManager.cleanup()
            if (::detectionManager.isInitialized) detectionManager.cleanup()
            if (::yoloManager.isInitialized) yoloManager.cleanup()
            if (::cameraManager.isInitialized) cameraManager.cleanup()
            if (::uiManager.isInitialized) uiManager.cleanup()
            ocrProcessor.close()
            if (::cameraExecutor.isInitialized) {
                cameraExecutor.shutdown()
                if (!cameraExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    cameraExecutor.shutdownNow()
                }
            }
            Log.d(TAG, "📱 App destroyed")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during cleanup: ${e.message}")
        }
    }
}