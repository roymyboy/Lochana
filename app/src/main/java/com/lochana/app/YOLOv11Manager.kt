package com.lochana.app

import ai.onnxruntime.*
import android.content.Context
import android.graphics.*
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Manages YOLOv11 object detection model for real-time inference.
 * 
 * Features:
 * - ONNX Runtime mobile integration
 * - Real-time object detection on camera frames
 * - Bounding box and confidence score extraction
 * - Performance optimization for mobile devices
 * - Support for 80 COCO classes
 */
class YOLOv11Manager(private val context: Context) {
    
    companion object {
        private const val TAG = "YOLOv11Manager"
        private const val MODEL_NAME = "yolov11n.onnx" // Using nano version for mobile
        private const val INPUT_SIZE = 640 // YOLOv11 default input size
        
        // Adaptive confidence thresholds
        private const val BASE_CONFIDENCE_THRESHOLD = 0.40f // Lowered from 0.5 for better detection
        private const val HIGH_DENSITY_THRESHOLD = 0.50f    // For crowded scenes
        private const val LOW_DENSITY_THRESHOLD = 0.30f     // For simple scenes
        private const val IOU_THRESHOLD = 0.45f
        private const val SOFT_NMS_SIGMA = 0.5f
        
        // COCO class names (80 classes)
        private val CLASS_NAMES = arrayOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
            "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
            "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
            "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
            "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
            "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake",
            "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop",
            "mouse", "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
        )
    }
    
    private var ortSession: OrtSession? = null
    private var ortEnvironment: OrtEnvironment? = null
    private var isInitialized = false
    
    // Performance tracking
    private var lastInferenceTime = 0L
    private var frameCount = 0
    private var totalInferenceTime = 0L
    
    // Letterbox preprocessing data
    private var scaleX = 1.0f
    private var scaleY = 1.0f
    private var padX = 0
    private var padY = 0
    
    data class Detection(
        val boundingBox: RectF,
        val className: String,
        val classIndex: Int,
        val confidence: Float
    )
    
    private data class LetterboxResult(
        val bitmap: Bitmap,
        val scale: Float,
        val padX: Int,
        val padY: Int
    )
    
    /**
     * Initialize the ONNX Runtime and load the YOLOv11 model
     */
    fun initialize() {
        try {
            // Create ONNX Runtime environment
            ortEnvironment = try {
                OrtEnvironment.getEnvironment()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to create ONNX environment: ${e.message}")
                isInitialized = false
                return
            }
            
            // Create session options for optimization
            val sessionOptions = try {
                OrtSession.SessionOptions().apply {
                    // Enable all optimizations
                    try {
                        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ Could not set optimization level: ${e.message}")
                    }
                    
                    // Set thread options for better parallelism
                    try {
                        setIntraOpNumThreads(4)
                        setInterOpNumThreads(4)
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ Could not set thread count: ${e.message}")
                    }
                    
                    // Use NNAPI execution provider for hardware acceleration if available
                    try {
                        addNnapi()
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ NNAPI not available, using CPU: ${e.message}")
                    }
                    
                    // Enable memory pattern optimization
                    try {
                        addConfigEntry("session.use_ort_model_bytes_directly", "1")
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ Could not enable memory optimization: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to create session options: ${e.message}")
                isInitialized = false
                return
            }
            
            // Load model from assets
            val modelBytes = try {
                loadModelFromAssets()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to load model: ${e.message}")
                e.printStackTrace()
                isInitialized = false
                return
            }
            
            ortSession = try {
                ortEnvironment?.createSession(modelBytes, sessionOptions)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to create ONNX session: ${e.message}")
                e.printStackTrace()
                isInitialized = false
                return
            }
            
            isInitialized = true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize YOLOv11: ${e.message}")
            e.printStackTrace()
            isInitialized = false
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "❌ Out of memory initializing YOLOv11")
            isInitialized = false
        }
    }
    
    /**
     * Load ONNX model from assets
     */
    private fun loadModelFromAssets(): ByteArray {
        return try {
            context.assets.open(MODEL_NAME).use { inputStream ->
                inputStream.readBytes()
            }
        } catch (e: IOException) {
            Log.e(TAG, "❌ Failed to load model from assets: ${e.message}")
            throw e
        }
    }
    
    /**
     * Run inference on a bitmap image
     */
    fun detectObjects(bitmap: Bitmap): List<Detection> {
        if (!isInitialized || ortSession == null) {
            Log.w(TAG, "⚠️ Model not initialized")
            return emptyList()
        }
        
        val startTime = System.currentTimeMillis()
        
        try {
            // Preprocess image
            val inputTensor = preprocessImage(bitmap)
            
            // Run inference
            val outputs = ortSession!!.run(mapOf("images" to inputTensor))
            
            // Process outputs
            val detections = processOutput(outputs, bitmap.width, bitmap.height)
            
            // Update performance metrics
            val inferenceTime = System.currentTimeMillis() - startTime
            lastInferenceTime = inferenceTime
            totalInferenceTime += inferenceTime
            frameCount++
            
            if (detections.isNotEmpty()) {
                Log.d(TAG, "🎯 Detected ${detections.size} objects in ${inferenceTime}ms")
            }
            
            return detections
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Inference failed: ${e.message}")
            e.printStackTrace()
            return emptyList()
        }
    }
    
    /**
     * Preprocess image for YOLOv11 input with letterbox resize
     * - Maintains aspect ratio with padding
     * - Resize to 640x640
     * - Convert to RGB
     * - Normalize to [0, 1]
     * - Convert to CHW format
     */
    private fun preprocessImage(bitmap: Bitmap): OnnxTensor {
        // Apply letterbox resize to maintain aspect ratio
        val letterboxed = letterboxResize(bitmap, INPUT_SIZE)
        scaleX = letterboxed.scale
        scaleY = letterboxed.scale
        padX = letterboxed.padX
        padY = letterboxed.padY
        
        val processedBitmap = letterboxed.bitmap
        
        // Create float buffer for normalized pixel values
        val inputSize = INPUT_SIZE * INPUT_SIZE * 3
        val buffer = FloatBuffer.allocate(inputSize)
        
        // Extract pixels and normalize
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        processedBitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        
        // Convert to CHW format and normalize
        for (i in pixels.indices) {
            val pixel = pixels[i]
            
            // Extract RGB values
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f
            
            // CHW format: all reds, then all greens, then all blues
            buffer.put(i, r)
            buffer.put(i + INPUT_SIZE * INPUT_SIZE, g)
            buffer.put(i + 2 * INPUT_SIZE * INPUT_SIZE, b)
        }
        
        // Create ONNX tensor
        val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        return OnnxTensor.createTensor(ortEnvironment, buffer, shape)
    }
    
    /**
     * Letterbox resize - maintains aspect ratio with padding
     * This prevents distortion and improves detection accuracy
     */
    private fun letterboxResize(bitmap: Bitmap, targetSize: Int): LetterboxResult {
        // Calculate scale to fit within target size while maintaining aspect ratio
        val scale = minOf(
            targetSize.toFloat() / bitmap.width,
            targetSize.toFloat() / bitmap.height
        )
        
        val newWidth = (bitmap.width * scale).toInt()
        val newHeight = (bitmap.height * scale).toInt()
        
        // Resize maintaining aspect ratio
        val resized = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        
        // Create padded image (centered)
        val padded = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(padded)
        canvas.drawColor(Color.rgb(114, 114, 114)) // Gray padding (YOLO default)
        
        val padX = (targetSize - newWidth) / 2
        val padY = (targetSize - newHeight) / 2
        canvas.drawBitmap(resized, padX.toFloat(), padY.toFloat(), null)
        
        return LetterboxResult(padded, scale, padX, padY)
    }
    
    /**
     * Get adaptive confidence threshold based on detection density
     */
    private fun getAdaptiveThreshold(preliminaryDetectionCount: Int): Float {
        return when {
            preliminaryDetectionCount > 15 -> HIGH_DENSITY_THRESHOLD  // Crowded scene
            preliminaryDetectionCount < 3 -> LOW_DENSITY_THRESHOLD    // Simple scene
            else -> BASE_CONFIDENCE_THRESHOLD                          // Normal scene
        }
    }
    
    /**
     * Process YOLOv11 output to extract detections
     */
    private fun processOutput(outputs: OrtSession.Result, imageWidth: Int, imageHeight: Int): List<Detection> {
        val detections = mutableListOf<Detection>()
        
        try {
            // YOLOv11 output format: typically [1, num_boxes, num_classes + 4]
            // Or [1, 84, 8400] where 84 = 4 bbox coords + 80 class scores
            val outputValue = outputs[0].value
            
            Log.d(TAG, "🔍 Output type: ${outputValue.javaClass.simpleName}")
            
            // Handle different output types from ONNX Runtime
            val outputArray: FloatArray
            val shape: LongArray
            
            when (outputValue) {
                is OnnxTensor -> {
                    // Direct OnnxTensor
                    val tensor = outputValue
                    shape = tensor.info.shape
                    Log.d(TAG, "🔍 Output shape: ${shape.contentToString()}")
                    val outputBuffer = tensor.floatBuffer
                    outputArray = FloatArray(outputBuffer.remaining())
                    outputBuffer.get(outputArray)
                }
                is Array<*> -> {
                    // Array format - handle primitive arrays (float[][][])
                    Log.d(TAG, "🔍 Output is Array format, size: ${outputValue.size}")
                    
                    if (outputValue.isEmpty()) {
                        Log.e(TAG, "❌ Empty array")
                        return emptyList()
                    }
                    
                    // Check if it's float[][][] (primitive array)
                    val firstElement = outputValue[0]
                    Log.d(TAG, "🔍 First element type: ${firstElement?.javaClass?.simpleName}")
                    
                    // Handle float[][] (array of float arrays)
                    if (firstElement is Array<*> && firstElement.isNotEmpty()) {
                        val secondElement = firstElement[0]
                        Log.d(TAG, "🔍 Second element type: ${secondElement?.javaClass?.simpleName}")
                        
                        // Check if it's a FloatArray (primitive float[])
                        if (secondElement is FloatArray) {
                            // Structure: [batch][features][predictions]
                            // Where batch=1, features=84, predictions=8400
                            val dim0 = outputValue.size
                            val dim1 = firstElement.size
                            val dim2 = secondElement.size
                            
                            shape = longArrayOf(dim0.toLong(), dim1.toLong(), dim2.toLong())
                            Log.d(TAG, "🔍 Output shape: [$dim0, $dim1, $dim2] (primitive float array)")
                            
                            // Flatten to 1D array
                            val totalSize = dim0 * dim1 * dim2
                            outputArray = FloatArray(totalSize)
                            var index = 0
                            
                            for (i in 0 until dim0) {
                                val batch = outputValue[i] as? Array<*> ?: continue
                                for (j in 0 until dim1) {
                                    val featureArray = batch[j] as? FloatArray ?: continue
                                    for (k in featureArray.indices) {
                                        outputArray[index++] = featureArray[k]
                                    }
                                }
                            }
                            
                            Log.d(TAG, "✅ Flattened $index values (expected: $totalSize)")
                        } else if (secondElement is Array<*>) {
                            // Boxed Float arrays
                            val dim0 = outputValue.size
                            val dim1 = firstElement.size
                            val dim2 = (secondElement as Array<*>).size
                            
                            shape = longArrayOf(dim0.toLong(), dim1.toLong(), dim2.toLong())
                            Log.d(TAG, "🔍 Output shape: [$dim0, $dim1, $dim2] (boxed array)")
                            
                            val totalSize = dim0 * dim1 * dim2
                            outputArray = FloatArray(totalSize)
                            var index = 0
                            
                            for (i in 0 until dim0) {
                                val batch = outputValue[i] as? Array<*> ?: continue
                                for (j in 0 until dim1) {
                                    val feature = batch[j] as? Array<*> ?: continue
                                    for (k in 0 until dim2) {
                                        val value = (feature[k] as? Number)?.toFloat() ?: 0f
                                        outputArray[index++] = value
                                    }
                                }
                            }
                            
                            Log.d(TAG, "✅ Flattened $index values (expected: $totalSize)")
                        } else {
                            Log.e(TAG, "❌ Unsupported array element type: ${secondElement?.javaClass?.simpleName}")
                            return emptyList()
                        }
                    } else {
                        Log.e(TAG, "❌ Not a 3D array - first level is not an array")
                        return emptyList()
                    }
                }
                else -> {
                    Log.e(TAG, "❌ Unsupported output type: ${outputValue.javaClass}")
                    return emptyList()
                }
            }
            
            // Determine output format based on shape
            when {
                // Format: [1, 84, 8400] - Feature-first format
                // Access pattern: outputArray[feature * numPredictions + prediction]
                shape.size == 3 && shape[1] == 84L && shape[2] > 1000L -> {
                    val numPredictions = shape[2].toInt()
                    val numFeatures = shape[1].toInt()
                    Log.d(TAG, "📊 Processing format [1, $numFeatures, $numPredictions]")
                    
                    // First pass: collect all detections above minimum threshold
                    val preliminaryDetections = mutableListOf<Detection>()
                    
                    // Process each prediction
                    for (predIdx in 0 until numPredictions) {
                        // Access pattern: outputArray[feature * numPredictions + predIdx]
                        val cx = outputArray[0 * numPredictions + predIdx]  // feature 0: center x
                        val cy = outputArray[1 * numPredictions + predIdx]  // feature 1: center y
                        val w = outputArray[2 * numPredictions + predIdx]   // feature 2: width
                        val h = outputArray[3 * numPredictions + predIdx]   // feature 3: height
                        
                        // Find best class (features 4-83)
                        var maxScore = 0f
                        var maxIndex = -1
                        for (classIdx in 0 until 80) {
                            val featureIdx = 4 + classIdx
                            val score = outputArray[featureIdx * numPredictions + predIdx]
                            if (score > maxScore) {
                                maxScore = score
                                maxIndex = classIdx
                            }
                        }
                        
                        // Use lower threshold for preliminary detection
                        if (maxScore > LOW_DENSITY_THRESHOLD && maxIndex >= 0) {
                            // Adjust coordinates for letterbox padding
                            val adjustedCx = cx - padX
                            val adjustedCy = cy - padY
                            
                            // Convert to image coordinates accounting for letterbox
                            val left = ((adjustedCx - w / 2) / scaleX).coerceIn(0f, imageWidth.toFloat())
                            val top = ((adjustedCy - h / 2) / scaleY).coerceIn(0f, imageHeight.toFloat())
                            val right = ((adjustedCx + w / 2) / scaleX).coerceIn(0f, imageWidth.toFloat())
                            val bottom = ((adjustedCy + h / 2) / scaleY).coerceIn(0f, imageHeight.toFloat())
                            
                            // Only add if box is valid and reasonable size
                            if (right > left && bottom > top && (right - left) > 10f && (bottom - top) > 10f) {
                                preliminaryDetections.add(Detection(
                                    RectF(left, top, right, bottom),
                                    CLASS_NAMES.getOrElse(maxIndex) { "unknown" },
                                    maxIndex,
                                    maxScore
                                ))
                            }
                        }
                    }
                    
                    // Get adaptive threshold based on scene complexity
                    val adaptiveThreshold = getAdaptiveThreshold(preliminaryDetections.size)
                    
                    // Second pass: filter by adaptive threshold
                    detections.addAll(preliminaryDetections.filter { it.confidence >= adaptiveThreshold })
                }
                
                // Format: [1, num_boxes, 84] - Direct format
                shape.size == 3 && shape[2] == 84L -> {
                    val numPredictions = shape[1].toInt()
                    Log.d(TAG, "📊 Processing format [1, $numPredictions, 84]")
                    
                    // Process each prediction
                    for (i in 0 until numPredictions) {
                        val baseIndex = i * 84
                        
                        // Get bbox coordinates (x1, y1, x2, y2) or (cx, cy, w, h)
                        val x1 = outputArray[baseIndex]
                        val y1 = outputArray[baseIndex + 1]
                        val x2 = outputArray[baseIndex + 2]
                        val y2 = outputArray[baseIndex + 3]
                        
                        // Find best class
                        var maxScore = 0f
                        var maxIndex = -1
                        
                        for (j in 4 until 84) {
                            val score = outputArray[baseIndex + j]
                            if (score > maxScore) {
                                maxScore = score
                                maxIndex = j - 4
                            }
                        }
                        
                        // Add detection if confidence is high enough
                        if (maxScore > BASE_CONFIDENCE_THRESHOLD && maxIndex >= 0) {
                            val scaleX = imageWidth.toFloat() / INPUT_SIZE
                            val scaleY = imageHeight.toFloat() / INPUT_SIZE
                            
                            // Check if coordinates are already absolute or normalized
                            val left = if (x1 < 1f) (x1 * scaleX) else (x1 * scaleX).coerceIn(0f, imageWidth.toFloat())
                            val top = if (y1 < 1f) (y1 * scaleY) else (y1 * scaleY).coerceIn(0f, imageHeight.toFloat())
                            val right = if (x2 < 1f) (x2 * scaleX) else (x2 * scaleX).coerceIn(0f, imageWidth.toFloat())
                            val bottom = if (y2 < 1f) (y2 * scaleY) else (y2 * scaleY).coerceIn(0f, imageHeight.toFloat())
                            
                            if (right > left && bottom > top) {
                                val detection = Detection(
                                    boundingBox = RectF(left, top, right, bottom),
                                    className = CLASS_NAMES.getOrElse(maxIndex) { "unknown" },
                                    classIndex = maxIndex,
                                    confidence = maxScore
                                )
                                detections.add(detection)
                            }
                        }
                    }
                }
                
                else -> {
                    Log.e(TAG, "❌ Unsupported output shape: ${shape.contentToString()}")
                    // Try default processing as fallback
                    val numPredictions = if (shape.size >= 2) shape[shape.size - 1].toInt() else 8400
                    val numValues = if (shape.size >= 2) shape[shape.size - 2].toInt() else 84
                    
                    for (i in 0 until numPredictions) {
                        val baseIndex = i * numValues
                        if (baseIndex + 83 < outputArray.size) {
                            var maxScore = 0f
                            var maxIndex = -1
                            
                            for (j in 4 until min(numValues, 84)) {
                                val score = outputArray[baseIndex + j]
                                if (score > maxScore) {
                                    maxScore = score
                                    maxIndex = j - 4
                                }
                            }
                            
                            if (maxScore > BASE_CONFIDENCE_THRESHOLD && maxIndex >= 0) {
                                val cx = outputArray[baseIndex]
                                val cy = outputArray[baseIndex + 1]
                                val w = outputArray[baseIndex + 2]
                                val h = outputArray[baseIndex + 3]
                                
                                val scaleX = imageWidth.toFloat() / INPUT_SIZE
                                val scaleY = imageHeight.toFloat() / INPUT_SIZE
                                
                                val left = ((cx - w / 2) * scaleX).coerceIn(0f, imageWidth.toFloat())
                                val top = ((cy - h / 2) * scaleY).coerceIn(0f, imageHeight.toFloat())
                                val right = ((cx + w / 2) * scaleX).coerceIn(0f, imageWidth.toFloat())
                                val bottom = ((cy + h / 2) * scaleY).coerceIn(0f, imageHeight.toFloat())
                                
                                if (right > left && bottom > top) {
                                    detections.add(Detection(
                                        RectF(left, top, right, bottom),
                                        CLASS_NAMES.getOrElse(maxIndex) { "unknown" },
                                        maxIndex,
                                        maxScore
                                    ))
                                }
                            }
                        }
                    }
                }
            }
            
            // Apply Soft-NMS (better for overlapping objects)
            val finalDetections = applySoftNMS(detections)
            
            return finalDetections
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to process output: ${e.message}")
            e.printStackTrace()
            return emptyList()
        }
    }
    
    /**
     * Apply Soft-NMS (Soft Non-Maximum Suppression)
     * Better than hard NMS for detecting overlapping objects
     * Uses Gaussian penalty instead of hard threshold
     */
    private fun applySoftNMS(detections: List<Detection>): List<Detection> {
        if (detections.isEmpty()) return emptyList()
        
        // Create mutable list with detection and updated confidence
        val scored = detections.map { it to it.confidence }.toMutableList()
        val selected = mutableListOf<Detection>()
        
        while (scored.isNotEmpty()) {
            // Find detection with max confidence
            val maxIdx = scored.withIndex().maxByOrNull { it.value.second }?.index ?: break
            val (maxDet, maxScore) = scored.removeAt(maxIdx)
            
            // Only keep if confidence is still above threshold
            if (maxScore >= BASE_CONFIDENCE_THRESHOLD * 0.5f) {
                selected.add(maxDet)
            }
            
            // Update scores of remaining detections using Gaussian penalty
            val updatedScored = mutableListOf<Pair<Detection, Float>>()
            for ((det, score) in scored) {
                var newScore = score
                
                // Apply penalty only to same class detections
                if (maxDet.classIndex == det.classIndex) {
                    val iou = calculateIoU(maxDet.boundingBox, det.boundingBox)
                    
                    // Gaussian penalty: reduces score smoothly based on IoU
                    val penalty = kotlin.math.exp(-(iou * iou) / SOFT_NMS_SIGMA)
                    newScore = score * penalty
                }
                
                // Keep detection if score is still above minimum threshold
                if (newScore >= 0.01f) {
                    updatedScored.add(det to newScore)
                }
            }
            
            scored.clear()
            scored.addAll(updatedScored)
        }
        
        return selected
    }
    
    /**
     * Calculate Intersection over Union (IoU) between two bounding boxes
     */
    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val intersectionLeft = max(box1.left, box2.left)
        val intersectionTop = max(box1.top, box2.top)
        val intersectionRight = min(box1.right, box2.right)
        val intersectionBottom = min(box1.bottom, box2.bottom)
        
        val intersectionArea = max(0f, intersectionRight - intersectionLeft) *
                             max(0f, intersectionBottom - intersectionTop)
        
        val box1Area = (box1.right - box1.left) * (box1.bottom - box1.top)
        val box2Area = (box2.right - box2.left) * (box2.bottom - box2.top)
        
        val unionArea = box1Area + box2Area - intersectionArea
        
        return if (unionArea > 0) intersectionArea / unionArea else 0f
    }
    
    /**
     * Get average FPS based on recent inferences
     */
    fun getAverageFPS(): Float {
        return if (frameCount > 0 && totalInferenceTime > 0) {
            1000f / (totalInferenceTime.toFloat() / frameCount)
        } else {
            0f
        }
    }
    
    /**
     * Get last inference time in milliseconds
     */
    fun getLastInferenceTime(): Long = lastInferenceTime
    
    /**
     * Check if model is ready for inference
     */
    fun isReady(): Boolean = isInitialized
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        try {
            try {
                ortSession?.close()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error closing ONNX session: ${e.message}")
            }
            
            try {
                ortEnvironment?.close()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error closing ONNX environment: ${e.message}")
            }
            
            ortSession = null
            ortEnvironment = null
            isInitialized = false
            
            Log.d(TAG, "🧹 YOLOv11 manager cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during cleanup: ${e.message}")
            e.printStackTrace()
        }
    }
}
