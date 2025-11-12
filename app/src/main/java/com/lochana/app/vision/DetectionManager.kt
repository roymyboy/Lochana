package com.lochana.app.vision

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.ExperimentalGetImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import kotlinx.coroutines.*

class DetectionManager(
    private val cameraExecutor: ExecutorService,
    private val yoloManager: YOLOv11Manager? = null
) {
    
    companion object {
        private const val TAG = "DetectionManager"
        private const val MOVEMENT_THRESHOLD = 0.10
        private const val YOLO_MIN_INTERVAL_MS = 50L  // Fast processing interval
    }

    private var detectionEnabled = false
    private var frameSkipCounter = 0
    private var frameSkipThreshold = 4
    private var onVideoFrameCaptured: ((Bitmap) -> Unit)? = null
    private var onSingleFrameCaptured: ((Bitmap) -> Unit)? = null
    private var onVideoCaptureComplete: (() -> Unit)? = null
    private var onVideoCaptureFinished: (() -> Unit)? = null
    private var onYOLODetections: ((List<YOLOv11Manager.Detection>, Bitmap) -> Unit)? = null
    private var previousBitmap: Bitmap? = null
    private var isAnalysisInProgress = false
    private var analysisComplete = false
    private val detectionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isProcessingYOLO = false
    private var lastYOLOProcessTime = 0L
    
    // Temporal smoothing
    private val detectionTracker = DetectionTracker()
    private var framesWithoutDetections = 0
    
    fun setCallbacks(
        onVideoFrameCaptured: ((Bitmap) -> Unit)? = null,
        onSingleFrameCaptured: ((Bitmap) -> Unit)? = null,
        onVideoCaptureComplete: (() -> Unit)? = null,
        onVideoCaptureFinished: (() -> Unit)? = null,
        onYOLODetections: ((List<YOLOv11Manager.Detection>, Bitmap) -> Unit)? = null
    ) {
        this.onVideoFrameCaptured = onVideoFrameCaptured
        this.onSingleFrameCaptured = onSingleFrameCaptured
        this.onVideoCaptureComplete = onVideoCaptureComplete
        this.onVideoCaptureFinished = onVideoCaptureFinished
        this.onYOLODetections = onYOLODetections
    }
    
    /**
     * Marks analysis as complete to protect message from overwriting
     */
    fun markAnalysisComplete() {
        analysisComplete = true
        Log.d(TAG, "✅ Analysis marked as complete - message protected from overwriting")
    }
    
    /**
     * Resets analysis state when camera moves to new scene
     */
    fun resetAnalysisForNewScene() {
        analysisComplete = false
        isAnalysisInProgress = false
        detectionTracker.clearTracks()  // Clear tracking history on scene change
        Log.d(TAG, "🔄 Analysis reset for new scene - ready for new analysis")
    }
    
    /**
     * Detects movement between two bitmaps
     */
    private fun detectMovement(bitmap1: Bitmap, bitmap2: Bitmap): Boolean {
        return try {
            if (bitmap1.width != bitmap2.width || bitmap1.height != bitmap2.height) {
                return true // Different sizes indicate movement
            }
            
            val pixels1 = IntArray(bitmap1.width * bitmap1.height)
            val pixels2 = IntArray(bitmap2.width * bitmap2.height)
            
            try {
                bitmap1.getPixels(pixels1, 0, bitmap1.width, 0, 0, bitmap1.width, bitmap1.height)
                bitmap2.getPixels(pixels2, 0, bitmap2.width, 0, 0, bitmap2.width, bitmap2.height)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error getting pixels: ${e.message}")
                return true // Assume movement on error
            }
            
            var differentPixels = 0
            val totalPixels = pixels1.size
            
            for (i in pixels1.indices) {
                val pixel1 = pixels1[i]
                val pixel2 = pixels2[i]
                
                // Calculate RGB difference
                val r1 = (pixel1 shr 16) and 0xFF
                val g1 = (pixel1 shr 8) and 0xFF
                val b1 = pixel1 and 0xFF
                
                val r2 = (pixel2 shr 16) and 0xFF
                val g2 = (pixel2 shr 8) and 0xFF
                val b2 = pixel2 and 0xFF
                
                val diff = kotlin.math.abs(r1 - r2) + kotlin.math.abs(g1 - g2) + kotlin.math.abs(b1 - b2)
                
                if (diff > 30) { // Threshold for significant pixel difference
                    differentPixels++
                }
            }
            
            val changePercentage = differentPixels.toDouble() / totalPixels
            changePercentage > MOVEMENT_THRESHOLD
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error detecting movement: ${e.message}")
            e.printStackTrace()
            true // Assume movement on error to be safe
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "❌ Out of memory detecting movement")
            true
        }
    }
    
    /**
     * Starts object detection and analysis for OpenAI
     * Note: YOLOv11 detection runs continuously and is independent of this state
     * This only controls OpenAI frame capture for analysis
     */
    fun startDetection() {
        detectionEnabled = true
        // Reset analysis state to allow new manual analysis
        analysisComplete = false
        isAnalysisInProgress = false
        Log.d(TAG, "🔍 OpenAI detection started - ready for new analysis")
        Log.d(TAG, "🎯 YOLOv11 continues running independently")
    }
    
    fun createImageAnalyzer(): ImageAnalysis.Analyzer {
        return ImageAnalyzer()
    }
    
    private inner class ImageAnalyzer : ImageAnalysis.Analyzer {
        @ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            try {
                val mediaImage = imageProxy.image
                if (mediaImage == null) {
                    imageProxy.close()
                    return
                }
                
                val currentBitmap = try {
                    mediaImageToBitmap(mediaImage, imageProxy.imageInfo.rotationDegrees)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error converting image: ${e.message}")
                    imageProxy.close()
                    return
                }
                
                if (currentBitmap == null) {
                    imageProxy.close()
                    return
                }
                
                // Run YOLOv11 detection continuously if available (independent of detectionEnabled)
                // This provides real-time object detection overlay on camera preview
                if (yoloManager?.isReady() == true && !isProcessingYOLO) {
                    val currentTime = System.currentTimeMillis()
                    
                    if (currentTime - lastYOLOProcessTime >= YOLO_MIN_INTERVAL_MS) {
                        isProcessingYOLO = true
                        lastYOLOProcessTime = currentTime
                        
                        detectionScope.launch {
                            try {
                                // Run detection
                                val rawDetections = yoloManager.detectObjects(currentBitmap)
                                
                                // Aggressively clear tracker when no detections
                                if (rawDetections.isEmpty()) {
                                    framesWithoutDetections++
                                    // Clear immediately after 1 empty frame
                                    if (framesWithoutDetections >= 1) {
                                        detectionTracker.clearTracks()
                                        framesWithoutDetections = 0
                                    }
                                } else {
                                    framesWithoutDetections = 0
                                }
                                
                                // Apply temporal smoothing
                                val smoothedDetections = detectionTracker.updateTracking(rawDetections)
                                
                                withContext(Dispatchers.Main) {
                                    onYOLODetections?.invoke(smoothedDetections, currentBitmap)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "❌ YOLOv11 detection failed: ${e.message}")
                            } finally {
                                isProcessingYOLO = false
                            }
                        }
                    }
                }
                
                // Only process OpenAI analysis when detection is enabled
                if (!detectionEnabled) {
                    imageProxy.close()
                    return
                }
                
                // If analysis is complete, only reset on significant scene change
                if (analysisComplete && previousBitmap != null) {
                    val sceneChanged = detectMovement(previousBitmap!!, currentBitmap)
                    if (sceneChanged) {
                        // Significant scene change detected - reset and start fresh 10-second capture
                        resetAnalysisForNewScene()
                        // Don't analyze immediately - wait for movement detection to trigger 10-second capture
                        Log.d(TAG, "🔄 Scene changed - resetting and waiting for 10-second capture")
                        previousBitmap = currentBitmap
                        imageProxy.close()
                        return
                    } else {
                        Log.d(TAG, "🔒 Analysis complete - message protected")
                        previousBitmap = currentBitmap
                        imageProxy.close()
                        return
                    }
                }
                
                // Only proceed if analysis is not complete
                if (!analysisComplete && !isAnalysisInProgress) {
                    
                    // Check if we're in video capture mode
                    if (isCapturingVideo) {
                        captureFrameForVideo(currentBitmap)
                    } else {
                        // Check if manual analysis was triggered
                        if (frameSkipCounter >= frameSkipThreshold) {
                            Log.d(TAG, "📷 Manual analysis triggered - processing single frame")
                            onSingleFrameCaptured?.invoke(currentBitmap)
                            // Reset frame skip counter after manual analysis
                            frameSkipCounter = 0
                        }
                    }
                }
                
                // Store current bitmap for next comparison
                previousBitmap = currentBitmap
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error in image analyzer: ${e.message}")
                e.printStackTrace()
            } finally {
                try {
                    imageProxy.close()
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error closing image proxy: ${e.message}")
                }
            }
        }
    }
    
    
    /**
     * Converts MediaImage to Bitmap for scene analysis
     */
    private fun mediaImageToBitmap(mediaImage: android.media.Image, rotationDegrees: Int): Bitmap? {
        return try {
            val yBuffer = mediaImage.planes[0].buffer
            val uBuffer = mediaImage.planes[1].buffer
            val vBuffer = mediaImage.planes[2].buffer
            
            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()
            
            val nv21 = ByteArray(ySize + uSize + vSize)
            
            try {
                yBuffer.get(nv21, 0, ySize)
                vBuffer.get(nv21, ySize, vSize)
                uBuffer.get(nv21, ySize + vSize, uSize)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error reading image buffers: ${e.message}")
                return null
            }
            
            val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, mediaImage.width, mediaImage.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(android.graphics.Rect(0, 0, mediaImage.width, mediaImage.height), 95, out)
            val imageBytes = out.toByteArray()
            
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            
            if (bitmap == null) {
                Log.e(TAG, "❌ Failed to decode bitmap from image bytes")
                return null
            }
            
            // Rotate bitmap if needed
            if (rotationDegrees != 0) {
                try {
                    val matrix = Matrix()
                    matrix.postRotate(rotationDegrees.toFloat())
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error rotating bitmap: ${e.message}")
                    bitmap // Return unrotated bitmap on error
                }
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error converting media image to bitmap: ${e.message}")
            e.printStackTrace()
            null
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "❌ Out of memory converting image to bitmap")
            null
        }
    }
    
    /**
     * Triggers immediate manual analysis of the current frame
     * Bypasses all stability checks and frame skipping for instant analysis
     */
    fun triggerManualAnalysis() {
        Log.d(TAG, "📷 Manual analysis triggered")
        
        // Check if detection is enabled
        if (!detectionEnabled) {
            Log.w(TAG, "⚠️ Manual analysis requested but detection is disabled")
            return
        }
        
        // Check if analysis is already in progress
        if (isAnalysisInProgress) {
            Log.w(TAG, "⚠️ Manual analysis requested but analysis already in progress")
            return
        }
        
        // Force immediate analysis by setting flags
        frameSkipCounter = frameSkipThreshold // Skip frame counting for immediate analysis
        
        Log.d(TAG, "📷 Manual analysis setup complete - will trigger on next frame")
    }
    
    // Video capture variables
    private var isCapturingVideo = false
    private var capturedFrames = mutableListOf<Bitmap>()
    private var videoCaptureStartTime = 0L
    private var videoCaptureHandler: Handler? = null
    private var videoCaptureRunnable: Runnable? = null
    private val VIDEO_CAPTURE_DURATION = 5000L // 5 seconds
    private val VIDEO_FRAME_INTERVAL = 500L // Capture frame every 500ms (half second) for better diversity
    
    /**
     * Triggers 5-second video capture for long press
     */
    fun triggerLongPressCapture() {
        Log.d(TAG, "📷 Long press capture triggered - starting 5-second video capture")
        
        // Check if detection is enabled
        if (!detectionEnabled) {
            Log.w(TAG, "⚠️ Long press capture requested but detection is disabled")
            return
        }
        
        // Check if already capturing
        if (isCapturingVideo) {
            Log.w(TAG, "⚠️ Long press capture already in progress")
            return
        }
        
        // Start video capture
        isCapturingVideo = true
        capturedFrames.clear()
        videoCaptureStartTime = System.currentTimeMillis()
        
        // Enable detection temporarily
        detectionEnabled = true
        
        // Set up video capture handler
        videoCaptureHandler = Handler(Looper.getMainLooper())
        startVideoCaptureTimer()
        
        Log.d(TAG, "📷 Long press capture setup complete - will capture video for 5 seconds (1 frame every 500ms)")
    }
    
    /**
     * Starts the video capture timer
     */
    private fun startVideoCaptureTimer() {
        videoCaptureRunnable = object : Runnable {
            override fun run() {
                if (!isCapturingVideo) {
                    completeVideoCapture()
                    return
                }
                
                val currentTime = System.currentTimeMillis()
                val elapsedTime = currentTime - videoCaptureStartTime
                
                // Check if 5 seconds have passed
                if (elapsedTime >= VIDEO_CAPTURE_DURATION) {
                    completeVideoCapture()
                    return
                }
                
                // Capture current frame if available
                previousBitmap?.let { bitmap ->
                    captureFrameForVideo(bitmap)
                }
                
                // Schedule next capture
                videoCaptureHandler?.postDelayed(this, VIDEO_FRAME_INTERVAL)
            }
        }
        
        // Start the timer
        videoCaptureHandler?.post(videoCaptureRunnable!!)
    }
    
    /**
     * Captures a frame for the video sequence
     */
    fun captureFrameForVideo(bitmap: Bitmap) {
        if (!isCapturingVideo) return
        
        capturedFrames.add(bitmap)
        Log.d(TAG, "📷 Frame captured for video (${capturedFrames.size} frames)")
    }
    
    /**
     * Completes the video capture and sends frames for analysis
     */
    private fun completeVideoCapture() {
        // Stop capturing immediately to prevent concurrent modifications
        isCapturingVideo = false
        
        // Cancel the timer handler
        videoCaptureHandler?.removeCallbacks(videoCaptureRunnable!!)
        videoCaptureHandler = null
        videoCaptureRunnable = null
        
        if (capturedFrames.isEmpty()) {
            Log.w(TAG, "⚠️ Video capture completed but no frames captured")
            return
        }
        
        Log.d(TAG, "📷 Video capture complete - sending ${capturedFrames.size} frames for analysis")
        
        // Create a copy of frames to avoid ConcurrentModificationException
        // The camera thread might still be adding frames, so we need to safely copy
        val framesToAnalyze = capturedFrames.toList()
        capturedFrames.clear()
        
        // Send all captured frames for analysis
        framesToAnalyze.forEach { frame ->
            onVideoFrameCaptured?.invoke(frame)
        }
        
        // Trigger analysis
        onVideoCaptureComplete?.invoke()
        
        // Notify that video capture is finished (for UI animation)
        onVideoCaptureFinished?.invoke()
    }
    
    /**
     * Clear all tracking history (call when switching cameras or scene changes dramatically)
     */
    fun clearTracking() {
        detectionTracker.clearTracks()
        Log.d(TAG, "🧹 Tracking history cleared")
    }
    
    fun cleanup() {
        try {
            // Cancel coroutine scope
            detectionScope.cancel()
            
            // Clear frame buffers
            capturedFrames.clear()
            
            // Clear bitmap references
            previousBitmap = null
            
            // Clear tracking
            detectionTracker.clearTracks()
            
            // Reset flags
            detectionEnabled = false
            isCapturingVideo = false
            analysisComplete = false
            isAnalysisInProgress = false
            isProcessingYOLO = false
            framesWithoutDetections = 0
            
            Log.d(TAG, "🧹 DetectionManager cleanup completed - all resources released")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during detection cleanup: ${e.message}")
        }
    }
}