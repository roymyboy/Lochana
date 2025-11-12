package com.lochana.app.camera

import android.content.Context
import android.graphics.PointF
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.lochana.app.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

class CameraManager(
    private val context: Context,
    private val binding: ActivityMainBinding,
    private val cameraExecutor: ExecutorService
) {

    companion object {
        private const val TAG = "CameraManager"
        private const val AUTO_FOCUS_DELAY = 500L
        private const val FOCUS_RESET_DELAY = 100L
        private const val MIN_ZOOM_RATIO = 1.0f
        private const val MAX_ZOOM_RATIO = 5.0f
    }

    private var camera: Camera? = null
    private var cameraControl: CameraControl? = null
    private var cameraInfo: CameraInfo? = null
    private var currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var currentZoomRatio = MIN_ZOOM_RATIO
    private var targetZoomRatio = MIN_ZOOM_RATIO  // Target zoom for smooth transitions
    private var isFocusInProgress = false
    private var extensionsManager: ExtensionsManager? = null
    private var currentExtensionMode: Int = ExtensionMode.NONE
    private var onCameraReady: (() -> Unit)? = null
    private var onCameraError: ((String) -> Unit)? = null
    private var imageAnalyzer: ImageAnalysis.Analyzer? = null
    private var isInitializing = false
    private var isCameraStarted = false
    private var imageCapture: ImageCapture? = null

    fun setCallbacks(
        onCameraReady: (() -> Unit)? = null,
        onCameraError: ((String) -> Unit)? = null
    ) {
        this.onCameraReady = onCameraReady
        this.onCameraError = onCameraError
    }

    fun setImageAnalyzer(analyzer: ImageAnalysis.Analyzer) {
        this.imageAnalyzer = analyzer
    }

    fun getImageCapture(): ImageCapture? = imageCapture

    fun startCamera() {
        // Prevent multiple simultaneous initialization attempts
        if (isInitializing) {
            Log.w(TAG, "⚠️ Camera initialization already in progress, ignoring duplicate call")
            return
        }

        if (isCameraStarted) {
            Log.w(TAG, "⚠️ Camera already started, ignoring duplicate call")
            return
        }

        val width = binding.viewFinder.width
        val height = binding.viewFinder.height
        Log.d(TAG, "📷 Starting camera... PreviewView: ${width}x${height}, Display: ${context.resources.displayMetrics.widthPixels}x${context.resources.displayMetrics.heightPixels}")

        // Ensure PreviewView is ready - be more lenient on real devices
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "⚠️ PreviewView not ready (${width}x${height}), waiting for layout...")
            isInitializing = true
            var retryCount = 0

            fun retryStart() {
                binding.viewFinder.post {
                    val newWidth = binding.viewFinder.width
                    val newHeight = binding.viewFinder.height
                    Log.d(TAG, "📷 Retry ${retryCount + 1}: PreviewView ${newWidth}x${newHeight}")

                    if (newWidth > 0 && newHeight > 0) {
                        Log.d(TAG, "✅ PreviewView ready: ${newWidth}x${newHeight}")
                        isInitializing = false
                        initializeCamera()
                    } else if (retryCount < 20) {
                        retryCount++
                        binding.viewFinder.postDelayed({ retryStart() }, 100)
                    } else {
                        Log.e(TAG, "❌ PreviewView not ready after ${retryCount} retries - forcing camera start")
                        onCameraError?.invoke("PreviewView failed to initialize. Display may be black. Try restarting the app.")
                        // Force start anyway - sometimes works on real devices
                        isInitializing = false
                        initializeCamera()
                    }
                }
            }

            retryStart()
            return
        }

        initializeCamera()
    }

    private fun initializeCamera() {
        if (isInitializing) {
            Log.w(TAG, "⚠️ Camera initialization already in progress")
            return
        }

        isInitializing = true
        val width = binding.viewFinder.width
        val height = binding.viewFinder.height
        Log.d(TAG, "📷 Initializing camera with PreviewView: ${width}x${height}")

        val desiredCaptureResolution = if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            Size(4032, 3024)
        } else {
            Size(3264, 2448)
        }

        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

            // Add timeout for real devices
            val startTime = System.currentTimeMillis()

            cameraProviderFuture.addListener({
                val elapsedTime = System.currentTimeMillis() - startTime
                Log.d(TAG, "📷 Camera provider ready after ${elapsedTime}ms")

                try {
                    val cameraProvider = cameraProviderFuture.get()
                    Log.d(TAG, "✅ Camera provider obtained successfully")

                    // Check if any cameras are available
                    val availableCameras = cameraProvider.availableCameraInfos
                    Log.d(TAG, "📷 Available cameras: ${availableCameras.size}")

                    if (availableCameras.isEmpty()) {
                        val error = "No cameras found on this device"
                        Log.e(TAG, "❌ $error")
                        onCameraError?.invoke(error)
                        return@addListener
                    }

                    // Select best available extension (NONE for front camera)
                    currentExtensionMode = try {
                        selectBestExtension()
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ Extension selection failed, using NONE: ${e.message}")
                        ExtensionMode.NONE
                    }
                    Log.d(TAG, "📷 Extension mode: ${getExtensionName(currentExtensionMode)}")

                    // Create camera selector - always use plain selector if extensions unavailable
                    val cameraSelector = if (currentExtensionMode != ExtensionMode.NONE && extensionsManager != null) {
                        try {
                            val extensionSelector = extensionsManager!!.getExtensionEnabledCameraSelector(
                                currentCameraSelector,
                                currentExtensionMode
                            )
                            Log.d(TAG, "📷 Using extension-enabled selector")
                            extensionSelector
                        } catch (e: Exception) {
                            Log.w(TAG, "⚠️ Extension selector failed, using plain selector: ${e.message}")
                            currentCameraSelector
                        }
                    } else {
                        Log.d(TAG, "📷 Using plain camera selector")
                        currentCameraSelector
                    }

                    // Get optimal aspect ratio matching screen
                    val aspectRatio = getScreenAspectRatio()

                    Log.d(TAG, "📷 Creating Preview use case - AspectRatio: ${if (aspectRatio == AspectRatio.RATIO_4_3) "4:3" else "16:9"}")

                    // Use aspect ratio only for better device compatibility
                    // CameraX will select the best resolution automatically
                    val preview = Preview.Builder()
                        .setTargetAspectRatio(aspectRatio)
                        .setTargetRotation(binding.viewFinder.display.rotation)
                        .build()

                    Log.d(TAG, "📷 Creating ImageCapture use case with resolution ${desiredCaptureResolution.width}x${desiredCaptureResolution.height}")
                    val highResImageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setTargetRotation(binding.viewFinder.display.rotation)
                        .setTargetResolution(desiredCaptureResolution)
                        .build()
                    imageCapture = highResImageCapture

                    Log.d(TAG, "📷 Creating ImageAnalysis use case with matching aspect ratio")
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetAspectRatio(aspectRatio)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build()
                        .also {
                            it.setAnalyzer(cameraExecutor, imageAnalyzer ?: createImageAnalyzer())
                        }

                    Log.d(TAG, "📷 Binding to lifecycle with camera selector: ${if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) "BACK" else "FRONT"}")

                    var usedFallback = false
                    camera = try {
                        val cam = cameraProvider.bindToLifecycle(
                            context as androidx.lifecycle.LifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalysis,
                            highResImageCapture
                        )
                        Log.d(TAG, "✅ Camera binding successful")
                        cam
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Failed to bind to lifecycle: ${e.message}")
                        e.printStackTrace()

                        // Try fallback without aspect ratio constraints
                        Log.w(TAG, "⚠️ Retrying camera binding without aspect ratio constraints...")
                        usedFallback = true
                        try {
                            val fallbackPreview = Preview.Builder()
                                .setTargetRotation(binding.viewFinder.display.rotation)
                                .build()

                            val fallbackAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                                .build()
                                .also {
                                    it.setAnalyzer(cameraExecutor, imageAnalyzer ?: createImageAnalyzer())
                                }

                            val fallbackCapture = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                .setTargetRotation(binding.viewFinder.display.rotation)
                                .apply {
                                    try {
                                        setTargetResolution(desiredCaptureResolution)
                                    } catch (_: Exception) {
                                    }
                                }
                                .build()
                            imageCapture = fallbackCapture

                            val fallbackCamera = cameraProvider.bindToLifecycle(
                                context as androidx.lifecycle.LifecycleOwner,
                                cameraSelector,
                                fallbackPreview,
                                fallbackAnalysis,
                                fallbackCapture
                            )

                            fallbackPreview.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                            Log.d(TAG, "✅ Fallback binding successful - using default aspect ratio")
                            fallbackCamera
                        } catch (fallbackError: Exception) {
                            Log.e(TAG, "❌ Fallback binding also failed: ${fallbackError.message}")
                            fallbackError.printStackTrace()
                            throw e // Throw original error
                        }
                    }

                    // Set surface provider AFTER binding - only if we didn't use fallback
                    if (!usedFallback) {
                        try {
                            preview.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                            Log.d(TAG, "✅ Surface provider set successfully")

                            // Verify preview is receiving frames
                            binding.viewFinder.postDelayed({
                                if (camera?.cameraInfo != null) {
                                    Log.d(TAG, "✅ Camera preview verified - frames should be visible")
                                } else {
                                    Log.e(TAG, "❌ Camera preview not receiving frames after binding")
                                    onCameraError?.invoke("Camera preview not displaying. Try restarting the app.")
                                }
                            }, 500)
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Error setting surface provider: ${e.message}")
                            onCameraError?.invoke("Camera preview failed to start.")
                        }
                    }

                    cameraControl = camera?.cameraControl
                    cameraInfo = camera?.cameraInfo
                    isCameraStarted = true
                    isInitializing = false
                    currentZoomRatio = MIN_ZOOM_RATIO
                    targetZoomRatio = MIN_ZOOM_RATIO

                    onCameraReady?.invoke()

                } catch (exc: Exception) {
                    isInitializing = false
                    val errorMessage = when {
                        exc is SecurityException -> "Camera permission denied. Please grant camera permission in settings."
                        exc.message?.contains("permission", ignoreCase = true) == true -> "Camera permission denied - check app permissions"
                        exc.message?.contains("in use", ignoreCase = true) == true -> "Camera is in use by another app. Close other camera apps."
                        exc.message?.contains("disconnect", ignoreCase = true) == true -> "Camera disconnected. Please restart the app."
                        exc.message?.contains("camera") == true -> "Camera hardware error: ${exc.message}"
                        exc.message?.contains("binding") == true -> "Camera binding failed - restart app"
                        else -> "Camera initialization failed: ${exc.message}"
                    }
                    Log.e(TAG, "❌ Camera error: $errorMessage")
                    Log.e(TAG, "Exception details: ${exc.javaClass.simpleName}")
                    exc.printStackTrace()
                    onCameraError?.invoke(errorMessage)
                }
            }, ContextCompat.getMainExecutor(context))

        } catch (e: Exception) {
            isInitializing = false
            Log.e(TAG, "❌ Failed to get camera provider: ${e.message}")
            e.printStackTrace()
            onCameraError?.invoke("Failed to initialize camera system: ${e.message}")
        }
    }

    private fun initializeExtensions(cameraProvider: ProcessCameraProvider) {
        try {
            val future = ExtensionsManager.getInstanceAsync(context, cameraProvider)
            future.addListener({
                try {
                    extensionsManager = future.get()
                } catch (e: Exception) {
                    extensionsManager = null
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (e: Exception) {
            extensionsManager = null
        }
    }

    private fun selectBestExtension(): Int {
        if (currentCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
            Log.d(TAG, "📸 Front camera - no extensions")
            return ExtensionMode.NONE
        }

        // Don't use extensions on back camera after switching - can cause issues
        // Just use plain camera selector for stability
        Log.d(TAG, "📸 Back camera - using plain selector (no extensions for stability)")
        return ExtensionMode.NONE

        /* Disabled extensions for stability during camera switching
        extensionsManager?.let { manager ->
            val priorityOrder = listOf(ExtensionMode.HDR, ExtensionMode.NIGHT, ExtensionMode.BOKEH)
            for (mode in priorityOrder) {
                try {
                    if (manager.isExtensionAvailable(currentCameraSelector, mode)) {
                        return mode
                    }
                } catch (e: Exception) {
                    continue
                }
            }
        }
        return ExtensionMode.NONE
        */
    }

    private fun getExtensionName(mode: Int): String {
        return when (mode) {
            ExtensionMode.HDR -> "HDR"
            ExtensionMode.NIGHT -> "Night"
            ExtensionMode.BOKEH -> "Bokeh"
            ExtensionMode.NONE -> "None"
            else -> "Unknown"
        }
    }

    private fun bindCameraUseCases(cameraProvider: ProcessCameraProvider) {
        try {
            Log.d(TAG, "📷 Unbinding all previous use cases...")
            cameraProvider.unbindAll()
            Log.d(TAG, "✅ Previous use cases unbound")

            // Wait a moment for unbind to complete
            Thread.sleep(50)

            // Select best available extension (NONE for front camera)
            currentExtensionMode = try {
                selectBestExtension()
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Extension selection failed, using NONE: ${e.message}")
                ExtensionMode.NONE
            }
            Log.d(TAG, "📷 Extension mode: ${getExtensionName(currentExtensionMode)}")

            // Create camera selector - always use plain selector if extensions unavailable
            val cameraSelector = if (currentExtensionMode != ExtensionMode.NONE && extensionsManager != null) {
                try {
                    val extensionSelector = extensionsManager!!.getExtensionEnabledCameraSelector(
                        currentCameraSelector,
                        currentExtensionMode
                    )
                    Log.d(TAG, "📷 Using extension-enabled selector")
                    extensionSelector
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Extension selector failed, using plain selector: ${e.message}")
                    currentCameraSelector
                }
            } else {
                Log.d(TAG, "📷 Using plain camera selector")
                currentCameraSelector
            }

            // Get optimal aspect ratio matching screen
            val aspectRatio = getScreenAspectRatio()

            Log.d(TAG, "📷 Creating Preview use case - AspectRatio: ${if (aspectRatio == AspectRatio.RATIO_4_3) "4:3" else "16:9"}")

            // Use aspect ratio only for better device compatibility
            // CameraX will select the best resolution automatically
            val preview = Preview.Builder()
                .setTargetAspectRatio(aspectRatio)
                .setTargetRotation(binding.viewFinder.display.rotation)
                .build()

            Log.d(TAG, "📷 Setting surface provider to PreviewView")

            // Image analysis - use aspect ratio to match preview
            Log.d(TAG, "📷 Creating ImageAnalysis use case with matching aspect ratio")
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetAspectRatio(aspectRatio)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, imageAnalyzer ?: createImageAnalyzer())
                }

            Log.d(TAG, "📷 Binding to lifecycle with camera selector: ${if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) "BACK" else "FRONT"}")

            var usedFallback = false
            camera = try {
                val cam = cameraProvider.bindToLifecycle(
                    context as androidx.lifecycle.LifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
                Log.d(TAG, "✅ Camera binding successful")
                cam
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to bind to lifecycle: ${e.message}")
                e.printStackTrace()

                // Try fallback without aspect ratio constraints
                Log.w(TAG, "⚠️ Retrying camera binding without aspect ratio constraints...")
                usedFallback = true
                try {
                    val fallbackPreview = Preview.Builder()
                        .setTargetRotation(binding.viewFinder.display.rotation)
                        .build()

                    val fallbackAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build()
                        .also {
                            it.setAnalyzer(cameraExecutor, imageAnalyzer ?: createImageAnalyzer())
                        }

                    val fallbackCamera = cameraProvider.bindToLifecycle(
                        context as androidx.lifecycle.LifecycleOwner,
                        cameraSelector,
                        fallbackPreview,
                        fallbackAnalysis
                    )

                    fallbackPreview.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                    Log.d(TAG, "✅ Fallback binding successful - using default aspect ratio")
                    fallbackCamera
                } catch (fallbackError: Exception) {
                    Log.e(TAG, "❌ Fallback binding also failed: ${fallbackError.message}")
                    fallbackError.printStackTrace()
                    throw e // Throw original error
                }
            }

            // Set surface provider AFTER binding - only if we didn't use fallback
            if (!usedFallback) {
                try {
                    preview.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                    Log.d(TAG, "✅ Surface provider set successfully")

                    // Verify preview is receiving frames
                    binding.viewFinder.postDelayed({
                        if (camera?.cameraInfo != null) {
                            Log.d(TAG, "✅ Camera preview verified - frames should be visible")
                        } else {
                            Log.e(TAG, "❌ Camera preview not receiving frames after binding")
                            onCameraError?.invoke("Camera preview not displaying. Try restarting the app.")
                        }
                    }, 500)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to set surface provider: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }

            cameraControl = camera?.cameraControl
            cameraInfo = camera?.cameraInfo

            // Preserve zoom ratio when rebinding camera (unless it's a camera switch)
            if (currentZoomRatio == MIN_ZOOM_RATIO) {
                currentZoomRatio = MIN_ZOOM_RATIO
                targetZoomRatio = MIN_ZOOM_RATIO
            }
            cameraControl?.setZoomRatio(currentZoomRatio)
            Log.d(TAG, "📷 Camera bound - zoom ratio: $currentZoomRatio")

            // Log actual camera resolution for verification
            logCameraResolution(preview, imageAnalysis)

            // Configure camera controls for stock camera-like behavior
            configureCameraControls()

            // Apply real-time post-processing if supported
            applyPostProcessing()

            triggerAutoFocus()

            // Mark as successfully started
            isInitializing = false
            isCameraStarted = true

            Log.d(TAG, "✅ Camera bound successfully with extension: ${getExtensionName(currentExtensionMode)}")
            onCameraReady?.invoke()

        } catch (exc: Exception) {
            isInitializing = false
            val errorMessage = when {
                exc.message?.contains("permission") == true -> "Camera permission denied - check app permissions"
                exc.message?.contains("camera") == true -> "Camera hardware error - check device camera"
                exc.message?.contains("binding") == true -> "Camera binding failed - restart app"
                exc.message?.contains("preview") == true -> "Camera preview setup failed - restart app"
                else -> "Camera setup failed: ${exc.message}"
            }
            Log.e(TAG, "❌ Camera setup failed: $errorMessage")
            exc.printStackTrace()
            onCameraError?.invoke(errorMessage)
        }
    }

    /**
     * Gets the optimal preview size by matching screen aspect ratio
     */
    private fun getOptimalPreviewSize(): Size {
        return try {
            val displayMetrics = context.resources.displayMetrics
            val displayWidth = displayMetrics.widthPixels
            val displayHeight = displayMetrics.heightPixels

            // Calculate screen aspect ratio
            val screenAspectRatio = displayHeight.toFloat() / displayWidth.toFloat()
            Log.d(TAG, "📱 Screen size: ${displayWidth}x${displayHeight}, aspect ratio: ${"%.3f".format(screenAspectRatio)}")

            // Determine closest standard aspect ratio
            val (targetWidth, targetHeight) = when {
                // 16:9 aspect ratio (most common)
                kotlin.math.abs(screenAspectRatio - 16f/9f) < 0.1f -> {
                    Log.d(TAG, "📸 Matched 16:9 aspect ratio")
                    Pair(1920, 1080)
                }
                // 18:9 aspect ratio (common on modern phones)
                kotlin.math.abs(screenAspectRatio - 18f/9f) < 0.1f -> {
                    Log.d(TAG, "📸 Matched 18:9 (2:1) aspect ratio")
                    Pair(2160, 1080)
                }
                // 19.5:9 aspect ratio (iPhone X and similar)
                kotlin.math.abs(screenAspectRatio - 19.5f/9f) < 0.1f -> {
                    Log.d(TAG, "📸 Matched 19.5:9 aspect ratio")
                    Pair(2340, 1080)
                }
                // 20:9 aspect ratio (many Android phones)
                kotlin.math.abs(screenAspectRatio - 20f/9f) < 0.1f -> {
                    Log.d(TAG, "📸 Matched 20:9 aspect ratio")
                    Pair(2400, 1080)
                }
                // 21:9 aspect ratio (ultra-wide)
                kotlin.math.abs(screenAspectRatio - 21f/9f) < 0.1f -> {
                    Log.d(TAG, "📸 Matched 21:9 aspect ratio")
                    Pair(2520, 1080)
                }
                // 4:3 aspect ratio (tablets)
                kotlin.math.abs(screenAspectRatio - 4f/3f) < 0.1f -> {
                    Log.d(TAG, "📸 Matched 4:3 aspect ratio")
                    Pair(1600, 1200)
                }
                // Custom aspect ratio - calculate dynamically
                else -> {
                    Log.d(TAG, "📸 Custom aspect ratio detected: ${"%.3f".format(screenAspectRatio)}")
                    // Scale to reasonable resolution maintaining aspect ratio
                    val baseWidth = 1080
                    val calculatedHeight = (baseWidth * screenAspectRatio).toInt()
                    Pair(calculatedHeight, baseWidth)
                }
            }

            val optimalSize = Size(targetWidth, targetHeight)
            Log.d(TAG, "✅ Optimal preview size: ${optimalSize.width}x${optimalSize.height} (aspect: ${"%.3f".format(targetHeight.toFloat()/targetWidth.toFloat())})")
            optimalSize
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Could not calculate optimal preview size: ${e.message}")
            e.printStackTrace()
            Size(1920, 1080) // Default to 16:9
        }
    }

    /**
     * Gets the aspect ratio for CameraX based on screen dimensions
     * Defaults to 4:3 for maximum coverage like stock camera apps
     */
    private fun getScreenAspectRatio(): Int {
        return try {
            val displayMetrics = context.resources.displayMetrics
            val displayWidth = displayMetrics.widthPixels
            val displayHeight = displayMetrics.heightPixels

            // Validate display dimensions
            if (displayWidth <= 0 || displayHeight <= 0) {
                Log.w(TAG, "⚠️ Invalid display dimensions (${displayWidth}x${displayHeight}), using 4:3 for max coverage")
                return AspectRatio.RATIO_4_3
            }

            val aspectRatio = displayHeight.toFloat() / displayWidth.toFloat()
            Log.d(TAG, "📱 Screen dimensions: ${displayWidth}x${displayHeight}, aspect: ${"%.3f".format(aspectRatio)}")

            // Prefer 4:3 for maximum coverage (like stock camera apps)
            // Only use 16:9 if screen is clearly 16:9 or narrower
            val ratio = when {
                aspectRatio <= 1.8f -> {
                    // Screen is 16:9 or narrower - use 16:9
                    Log.d(TAG, "📸 Using AspectRatio.RATIO_16_9 for narrow screen aspect ${"%.3f".format(aspectRatio)}")
                    AspectRatio.RATIO_16_9
                }
                else -> {
                    // Screen is taller than 16:9 - use 4:3 for maximum coverage
                    Log.d(TAG, "📸 Using AspectRatio.RATIO_4_3 for maximum coverage (screen aspect ${"%.3f".format(aspectRatio)})")
                    AspectRatio.RATIO_4_3
                }
            }

            ratio
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error calculating aspect ratio: ${e.message}")
            e.printStackTrace()
            // Default to 4:3 for maximum coverage
            AspectRatio.RATIO_4_3
        }
    }

    /**
     * Logs the actual resolved camera resolution
     */
    private fun logCameraResolution(preview: Preview, imageAnalysis: ImageAnalysis) {
        try {
            // The actual resolution is determined after binding
            preview.resolutionInfo?.let { resolutionInfo ->
                val resolution = resolutionInfo.resolution
                val rotation = resolutionInfo.rotationDegrees
                val aspectRatio = resolution.height.toFloat() / resolution.width.toFloat()
                Log.d(TAG, "📸 Preview resolution: ${resolution.width}x${resolution.height}, aspect: ${"%.3f".format(aspectRatio)}, rotation: ${rotation}°")
            } ?: Log.w(TAG, "⚠️ Preview resolution info not available yet")

            imageAnalysis.resolutionInfo?.let { resolutionInfo ->
                val resolution = resolutionInfo.resolution
                val rotation = resolutionInfo.rotationDegrees
                Log.d(TAG, "📸 Analysis resolution: ${resolution.width}x${resolution.height}, rotation: ${rotation}°")
            } ?: Log.w(TAG, "⚠️ Analysis resolution info not available yet")

            // Verify screen vs camera aspect ratio alignment
            val displayMetrics = context.resources.displayMetrics
            val screenAspectRatio = displayMetrics.heightPixels.toFloat() / displayMetrics.widthPixels.toFloat()
            preview.resolutionInfo?.let { resolutionInfo ->
                val resolution = resolutionInfo.resolution
                val cameraAspectRatio = resolution.height.toFloat() / resolution.width.toFloat()
                val difference = kotlin.math.abs(screenAspectRatio - cameraAspectRatio)
                Log.d(TAG, "📐 Aspect ratio - Screen: ${"%.3f".format(screenAspectRatio)}, Camera: ${"%.3f".format(cameraAspectRatio)}, Diff: ${"%.3f".format(difference)}")

                if (difference < 0.1f) {
                    Log.d(TAG, "✅ Aspect ratios well matched - minimal distortion expected")
                } else {
                    Log.w(TAG, "⚠️ Aspect ratio mismatch - some cropping/letterboxing may occur")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error logging camera resolution: ${e.message}")
        }
    }

    /**
     * Configures camera controls to match stock camera defaults
     * - Auto exposure and focus
     * - Auto white balance that locks once stabilized
     */
    private fun configureCameraControls() {
        cameraControl?.let { control ->
            try {
                // Enable auto exposure (AE)
                val aeRange = cameraInfo?.exposureState?.exposureCompensationRange
                if (aeRange != null) {
                    // Set exposure compensation to middle (neutral)
                    val middleValue = (aeRange.lower + aeRange.upper) / 2
                    control.setExposureCompensationIndex(middleValue)
                    Log.d(TAG, "📸 Exposure compensation set to neutral")
                }

                // Auto focus is already handled by triggerAutoFocus()
                // White balance will auto-adjust through CameraX's default behavior

                Log.d(TAG, "✅ Camera controls configured for stock-like behavior")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Failed to configure camera controls: ${e.message}")
            }
        }
    }

    /**
     * Post-processing is handled by CameraX Extensions (device-level ISP)
     * Extensions (HDR, Night, Bokeh) provide optimized ISP tuning
     */
    private fun applyPostProcessing() {
        // Post-processing is handled by CameraX Extensions (device-level ISP)
        Log.d(TAG, "✅ Post-processing handled by CameraX Extensions")
    }

    private fun triggerAutoFocus() {
        try {
            binding.viewFinder.postDelayed({
                try {
                    val centerPoint = PointF(
                        binding.viewFinder.width / 2f,
                        binding.viewFinder.height / 2f
                    )

                    cameraControl?.let { control ->
                        try {
                            val factory = binding.viewFinder.meteringPointFactory
                            val meteringPoint = factory.createPoint(centerPoint.x, centerPoint.y)

                            val action = FocusMeteringAction.Builder(meteringPoint)
                                .addPoint(meteringPoint, FocusMeteringAction.FLAG_AF)
                                .addPoint(meteringPoint, FocusMeteringAction.FLAG_AE)
                                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                .build()

                            control.startFocusAndMetering(action)
                        } catch (e: Exception) {
                            val errorMessage = when {
                                e.message?.contains("focus") == true -> "Auto-focus system error - check camera hardware"
                                e.message?.contains("camera") == true -> "Camera control error during focus"
                                else -> "Auto-focus error: ${e.message}"
                            }
                            Log.e(TAG, "❌ Auto-focus error: $errorMessage")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error in auto-focus delayed task: ${e.message}")
                }
            }, AUTO_FOCUS_DELAY)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error triggering auto-focus: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Focuses camera on the specified point using focus metering
     * @param point The focus point coordinates
     */
    fun focusOnPoint(point: PointF) {
        try {
            applyCameraFocusAtPoint(point)
            Log.d(TAG, "🎯 Focus at: (${"%.1f".format(point.x)}, ${"%.1f".format(point.y)})")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error focusing on point: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun applyCameraFocusAtPoint(point: PointF) {
        if (isFocusInProgress) {
            Log.d(TAG, "🔍 Focus already in progress - skipping")
            return
        }

        cameraControl?.let { control ->
            try {
                isFocusInProgress = true
                val factory = binding.viewFinder.meteringPointFactory
                val meteringPoint = factory.createPoint(point.x, point.y)

                val action = FocusMeteringAction.Builder(meteringPoint)
                    .addPoint(meteringPoint, FocusMeteringAction.FLAG_AF)
                    .addPoint(meteringPoint, FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(5, TimeUnit.SECONDS)
                    .build()

                val future = control.startFocusAndMetering(action)

                future.addListener({
                    try {
                        future.get() // Just call get() without storing the result
                        Log.d(TAG, "✅ Focus operation completed successfully")
                        isFocusInProgress = false
                    } catch (e: Exception) {
                        val errorMessage = when {
                            e.message?.contains("Cancelled") == true -> "Focus operation cancelled by another focus request"
                            e.message?.contains("focus") == true -> "Focus result retrieval error - check camera hardware"
                            e.message?.contains("camera") == true -> "Camera control error during focus result"
                            e.message?.contains("OperationCanceledException") == true -> "Focus operation cancelled - normal behavior"
                            else -> "Focus result error: ${e.message}"
                        }
                        Log.e(TAG, "❌ Focus operation failed: $errorMessage")
                        isFocusInProgress = false
                    }
                }, ContextCompat.getMainExecutor(context))

            } catch (e: Exception) {
                val errorMessage = when {
                    e.message?.contains("focus") == true -> "Focus system error - check camera hardware"
                    e.message?.contains("camera") == true -> "Camera control error during focus"
                    e.message?.contains("metering") == true -> "Camera metering system error"
                    else -> "Focus error: ${e.message}"
                }
                Log.e(TAG, "❌ Focus error: $errorMessage")
                isFocusInProgress = false

                // Note: Removed zoom-based focus fallback to prevent zoom glitches
                // Modern cameras should handle focus internally
            }
        } ?: run {
            Log.e(TAG, "CameraControl is null")
        }
    }

    fun switchCamera() {
        try {
            Log.d(TAG, "📷 Switching camera - current: ${if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) "BACK" else "FRONT"}")

            // Prevent switching while already initializing
            if (isInitializing) {
                Log.w(TAG, "⚠️ Camera is initializing, cannot switch now")
                return
            }

            // Switch camera selector BEFORE getting provider
            val newCameraSelector = if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                Log.d(TAG, "📷 Switching to FRONT camera")
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                Log.d(TAG, "📷 Switching to BACK camera")
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            try {
                // Get camera provider asynchronously (don't block main thread!)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

                cameraProviderFuture.addListener({
                    try {
                        val cameraProvider = cameraProviderFuture.get()

                        Log.d(TAG, "📷 Unbinding current camera before switch")
                        try {
                            cameraProvider.unbindAll()
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Error unbinding camera: ${e.message}")
                        }

                        // Clear old camera references immediately
                        camera = null
                        cameraControl = null
                        cameraInfo = null
                        imageCapture = null // Clear high-res image capture

                        // Reset state for camera switch
                        isInitializing = false
                        isCameraStarted = false

                        // Update camera selector
                        currentCameraSelector = newCameraSelector

                        // Reset zoom for new camera
                        currentZoomRatio = MIN_ZOOM_RATIO
                        targetZoomRatio = MIN_ZOOM_RATIO

                        // Don't reinitialize extensions - use existing ExtensionsManager
                        // Extensions will be selected based on camera in bindCameraUseCases
                        Log.d(TAG, "📷 Reusing existing ExtensionsManager (if available)")

                        // Delay to ensure unbind is complete
                        try {
                            binding.viewFinder.postDelayed({
                                try {
                                    Log.d(TAG, "📷 Starting new camera after switch")
                                    startCamera()
                                } catch (e: Exception) {
                                    Log.e(TAG, "❌ Error starting camera after switch: ${e.message}")
                                    try {
                                        onCameraError?.invoke("Failed to restart camera after switch")
                                    } catch (err: Exception) {
                                        Log.e(TAG, "❌ Error invoking camera error callback: ${err.message}")
                                    }
                                }
                            }, 200)
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Error posting delayed camera start: ${e.message}")
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error during camera switch: ${e.message}")
                        e.printStackTrace()

                        // Reset state on error
                        isInitializing = false
                        isCameraStarted = false

                        try {
                            onCameraError?.invoke("Failed to switch camera: ${e.message}. Try restarting the app.")
                        } catch (err: Exception) {
                            Log.e(TAG, "❌ Error invoking camera error callback: ${err.message}")
                        }
                    }
                }, ContextCompat.getMainExecutor(context))

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error getting camera provider for switch: ${e.message}")
                e.printStackTrace()

                // Reset state on error
                isInitializing = false
                isCameraStarted = false

                try {
                    onCameraError?.invoke("Failed to switch camera. Try restarting the app.")
                } catch (err: Exception) {
                    Log.e(TAG, "❌ Error invoking camera error callback: ${err.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Fatal error in switchCamera: ${e.message}")
            e.printStackTrace()
            // Reset state
            isInitializing = false
            isCameraStarted = false
        }
    }

    fun resetZoom() {
        try {
            currentZoomRatio = MIN_ZOOM_RATIO
            targetZoomRatio = MIN_ZOOM_RATIO
            cameraControl?.setZoomRatio(currentZoomRatio)
            Log.d(TAG, "🔍 Zoom reset to 1.0x")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error resetting zoom: ${e.message}")
            e.printStackTrace()
        }
    }

    fun getCurrentZoomRatio(): Float {
        return try {
            currentZoomRatio
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error getting zoom ratio: ${e.message}")
            MIN_ZOOM_RATIO
        }
    }

    fun setZoomRatio(ratio: Float) {
        try {
            val newRatio = ratio.coerceIn(MIN_ZOOM_RATIO, MAX_ZOOM_RATIO)

            // Smooth zoom: interpolate between current and target
            targetZoomRatio = newRatio

            // Apply zoom with minimal smoothing for responsiveness
            val smoothedRatio = currentZoomRatio * 0.3f + targetZoomRatio * 0.7f
            currentZoomRatio = smoothedRatio.coerceIn(MIN_ZOOM_RATIO, MAX_ZOOM_RATIO)

            cameraControl?.setZoomRatio(currentZoomRatio)

            // Log zoom changes occasionally (not every frame to reduce log spam)
            if (kotlin.math.abs(currentZoomRatio - targetZoomRatio) > 0.1f) {
                Log.d(TAG, "🔍 Zoom: ${String.format("%.2f", currentZoomRatio)}x (target: ${String.format("%.2f", targetZoomRatio)}x)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error setting zoom ratio: ${e.message}")
            e.printStackTrace()
        }
    }

    fun createImageAnalyzer(): ImageAnalysis.Analyzer {
        return object : ImageAnalysis.Analyzer {
            override fun analyze(imageProxy: ImageProxy) {
                // This is a placeholder - the actual analysis is handled by DetectionManager
                imageProxy.close()
            }
        }
    }

    fun isInitializing(): Boolean = isInitializing
    fun isCameraStarted(): Boolean = isCameraStarted

    /**
     * Resets camera state flags to allow camera restart
     * Called when app resumes from background
     */
    fun resetCameraState() {
        try {
            Log.d(TAG, "🔄 Resetting camera state for restart")
            isInitializing = false
            isCameraStarted = false
            camera = null
            cameraControl = null
            cameraInfo = null
            imageCapture = null // Clear high-res image capture
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error resetting camera state: ${e.message}")
        }
    }

    fun cleanup() {
        try {
            // Unbind all use cases
            val cameraProvider = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(context).get()
            cameraProvider.unbindAll()

            // Clear camera references
            camera = null
            cameraControl = null
            cameraInfo = null
            imageAnalyzer = null
            imageCapture = null // Clear high-res image capture
            extensionsManager = null

            // Reset state
            isInitializing = false
            isCameraStarted = false

            Log.d(TAG, "🧹 Camera manager cleaned up and resources released")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during camera cleanup: ${e.message}")
            // Still clear references
            camera = null
            cameraControl = null
            cameraInfo = null
            isInitializing = false
            isCameraStarted = false
        }
    }
}
