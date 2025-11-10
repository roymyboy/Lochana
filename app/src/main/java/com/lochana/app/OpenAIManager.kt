package com.lochana.app

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Manages OpenAI GPT-4 Omni API integration for enhanced AI responses.
 * 
 * Features:
 * - GPT-4 Omni model integration with vision capabilities
 * - Contextual responses based on camera analysis
 * - Error handling and fallback mechanisms
 * - API key management and security
 * - Async processing with coroutines
 * - Rate limiting and timeout handling
 */
class OpenAIManager(
    private val context: Context
) {
    
    companion object {
        private const val TAG = "OpenAIManager"
        private const val OPENAI_API_URL = "https://api.openai.com/v1/chat/completions"
        private const val MODEL_NAME = "gpt-4o"
        private const val MAX_TOKENS = 1000
        private const val TIMEOUT_SECONDS = 60L
        private const val MAX_VIDEO_FRAMES = 5 // Maximum frames to send in one request
        private const val INSTRUCTIONS_FILE = "openai_instructions.json"
        private const val TEXT_SYSTEM_MESSAGE = "You are a helpful conversational assistant. Answer clearly using natural language."
    }
    
    // API Configuration
    private var apiKey: String? = null
    private var isEnabled = false
    private var isProcessing = false
    
    // Instructions loaded from file
    private data class Instructions(
        val singleFrameSystemMessage: String,
        val singleFrameUserPrompt: String,
        val videoSystemMessage: String,
        val videoUserPrompt: String
    )
    private var instructions: Instructions? = null
    
    // Smart analysis state - supports both single frame and video analysis
    private val videoFrameBuffer = mutableListOf<Bitmap>()
    private var videoAnalysisActive = false
    private var lastSingleFrameAnalysisTime = 0L
    private val SINGLE_FRAME_ANALYSIS_INTERVAL = 5000L // 5 seconds between single frame analyses
    
    // Coroutine scope for async operations
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // HTTP client
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    
    // Callbacks
    private var onResponse: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var onAnalysisComplete: (() -> Unit)? = null
    
    init {
        // Load instructions from file on initialization
        loadInstructions()
    }
    
    /**
     * Loads instructions from the JSON file in assets
     */
    private fun loadInstructions() {
        try {
            val inputStream = context.assets.open(INSTRUCTIONS_FILE)
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(jsonString)
            
            val singleFrame = json.getJSONObject("singleFrameAnalysis")
            val video = json.getJSONObject("videoAnalysis")
            
            instructions = Instructions(
                singleFrameSystemMessage = singleFrame.getString("systemMessage"),
                singleFrameUserPrompt = singleFrame.getString("userPrompt"),
                videoSystemMessage = video.getString("systemMessage"),
                videoUserPrompt = video.getString("userPrompt")
            )
            
            Log.d(TAG, "✅ OpenAI instructions loaded successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load OpenAI instructions from file: ${e.message}")
            e.printStackTrace()
            Log.w(TAG, "⚠️ Using default instructions as fallback")
            
            // Fallback to default instructions
            try {
                instructions = Instructions(
                    singleFrameSystemMessage = "You are an AI assistant that analyzes a scene from a live camera feed. Look at the provided image and describe what you can see happening in the scene. Focus on the overall scene, people, objects, activities, and any interesting details. Always start your response with \"I can see\" and describe the scene naturally. Provide a clear, comprehensive description of what you observe.",
                    singleFrameUserPrompt = "Look at this scene and tell me what you can see happening. Describe the overall scene, any people, objects, activities, or interesting details you observe. Always start your response with 'I can see' and describe what you observe naturally.",
                    videoSystemMessage = "You are an AI assistant that analyzes a scene from a live camera feed. Look at the provided images and describe what you can see happening in the scene. Focus on the overall scene, activities, people, objects, and any movements or events. Provide a natural, comprehensive description of what is happening. Always start your response with \"I can see\" and describe the scene as if you are observing it directly. Do not mention individual frames, sequences, or images - just describe what you observe in the scene.",
                    videoUserPrompt = "Look at this scene and tell me what you can see happening. Describe the overall scene, any people, objects, activities, or movements you observe. Always start your response with 'I can see' and describe what you observe naturally. Focus on what is happening in the scene as a whole."
                )
            } catch (err: Exception) {
                Log.e(TAG, "❌ Fatal: Cannot create fallback instructions: ${err.message}")
                err.printStackTrace()
            }
        }
    }
    
    /**
     * Sets the OpenAI API key and enables the service
     * @param apiKey The OpenAI API key
     */
    fun setApiKey(apiKey: String) {
        this.apiKey = apiKey
        this.isEnabled = apiKey.isNotEmpty()
        Log.d(TAG, "🔑 API key set - enabled: $isEnabled")
    }
    
    /**
     * Captures a single frame for static scene analysis
     * @param bitmap The frame bitmap to capture
     * @param customUserPrompt Optional custom user prompt. If provided, will be used instead of default
     */
    fun captureSingleFrame(bitmap: Bitmap, customUserPrompt: String? = null) {
        if (!isEnabled || apiKey == null) {
            Log.w(TAG, "⚠️ OpenAI not enabled or API key not set")
            return
        }
        
        if (isProcessing) {
            Log.w(TAG, "⚠️ Already processing a request - skipping single frame capture")
            return
        }
        
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSingleFrameAnalysisTime < SINGLE_FRAME_ANALYSIS_INTERVAL) {
            Log.d(TAG, "⏱️ Single frame analysis too soon - waiting")
            return
        }
        
        Log.d(TAG, "📸 Capturing single frame for static scene analysis")
        
        // Resize bitmap to reduce API payload
        val resizedBitmap = resizeBitmap(bitmap, 512, 512)
        
        // Immediately analyze the single frame
        analyzeSingleFrame(resizedBitmap, customUserPrompt)
        lastSingleFrameAnalysisTime = currentTime
    }
    
    /**
     * Analyzes a single frame for static scene description
     */
    private fun analyzeSingleFrame(bitmap: Bitmap, customUserPrompt: String? = null) {
        if (isProcessing) {
            Log.w(TAG, "⚠️ Already processing a request - skipping single frame analysis")
            return
        }
        
        Log.d(TAG, "📸 Starting single frame analysis")
        
        isProcessing = true
        
        coroutineScope.launch {
            try {
                val response = makeSingleFrameRequest(bitmap, customUserPrompt)
                
                Log.d(TAG, "✅ Single frame analysis completed successfully")
                withContext(Dispatchers.Main) {
                    onResponse?.invoke(response)
                    isProcessing = false
                    onAnalysisComplete?.invoke()
                }
                
            } catch (e: Exception) {
                val errorMessage = when {
                    e.message?.contains("timeout") == true -> "Single frame analysis timeout - check internet connection"
                    e.message?.contains("network") == true -> "Network error during single frame analysis"
                    e.message?.contains("401") == true -> "Invalid API key for single frame analysis"
                    e.message?.contains("429") == true -> "Rate limit exceeded for single frame analysis"
                    e.message?.contains("SSL") == true -> "SSL error during single frame analysis"
                    else -> "Single frame analysis failed: ${e.message}"
                }
                Log.e(TAG, "❌ Single frame analysis failed: $errorMessage")
                withContext(Dispatchers.Main) {
                    onError?.invoke(errorMessage)
                    isProcessing = false
                }
            }
        }
    }
    
    /**
     * Makes a single frame request to GPT-4 Omni
     */
    private suspend fun makeSingleFrameRequest(bitmap: Bitmap, customUserPrompt: String? = null): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "📸 Preparing single frame request")
        
        val instr = instructions ?: throw Exception("Instructions not loaded")
        
        val messages = JSONArray()
        
        // System message for single frame analysis (loaded from file)
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", instr.singleFrameSystemMessage)
        })
        
        // User message with single image
        val userMessage = JSONObject().apply {
            put("role", "user")
            val content = JSONArray()
            
            // Add text content - use custom prompt if provided, otherwise use default
            val userPromptText = customUserPrompt?.takeIf { it.isNotBlank() } ?: instr.singleFrameUserPrompt
            content.put(JSONObject().apply {
                put("type", "text")
                put("text", userPromptText)
            })
            
            // Add single image
            val base64Image = bitmapToBase64(bitmap)
            content.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/jpeg;base64,$base64Image")
                })
            })
            
            put("content", content)
        }
        
        messages.put(userMessage)
        
        val requestBody = JSONObject().apply {
            put("model", MODEL_NAME)
            put("max_tokens", MAX_TOKENS)
            put("temperature", 0.7)
            put("messages", messages)
        }
        
        Log.d(TAG, "📸 Sending single frame request to GPT-4 Omni...")
        val rawResponse = makeHttpRequest(requestBody.toString())
        parseResponse(rawResponse)
    }
    
    /**
     * Starts video analysis system (but not continuous analysis)
     */
    fun startVideoAnalysis() {
        if (!isEnabled || apiKey == null) {
            Log.w(TAG, "⚠️ OpenAI not enabled or API key not set")
            return
        }
        
        if (videoAnalysisActive) {
            Log.w(TAG, "⚠️ Video analysis already active")
            return
        }
        
        videoAnalysisActive = true
        videoFrameBuffer.clear()
        
        Log.d(TAG, "🎥 Video analysis system ready - will analyze when frames are collected")
        
        // No continuous analysis - only manual triggering
    }
    
    /**
     * Stops continuous video analysis
     */
    fun stopVideoAnalysis() {
        videoAnalysisActive = false
        videoFrameBuffer.clear()
        Log.d(TAG, "🎥 Video analysis stopped")
    }
    
    /**
     * Resets analysis state to allow new analysis
     */
    fun resetAnalysisState() {
        isProcessing = false
        lastSingleFrameAnalysisTime = 0L
        Log.d(TAG, "🔄 Analysis state reset - ready for new analysis")
    }

    /**
     * Sends a text-only prompt to GPT-4 Omni
     */
    fun sendTextPrompt(prompt: String): Boolean {
        if (prompt.isBlank()) {
            Log.w(TAG, "⚠️ Empty prompt - skipping text request")
            return false
        }

        if (!isEnabled || apiKey == null) {
            Log.w(TAG, "⚠️ OpenAI not enabled or API key not set")
            onError?.invoke("OpenAI is not configured")
            return false
        }

        if (isProcessing) {
            Log.w(TAG, "⚠️ Already processing a request - skipping text prompt")
            return false
        }

        Log.d(TAG, "💬 Sending text prompt to GPT-4 Omni")
        isProcessing = true

        coroutineScope.launch {
            try {
                val response = makeTextPromptRequest(prompt)
                withContext(Dispatchers.Main) {
                    onResponse?.invoke(response)
                    isProcessing = false
                    onAnalysisComplete?.invoke()
                }
            } catch (e: Exception) {
                val errorMessage = when {
                    e.message?.contains("timeout") == true -> "Text prompt timeout - check internet connection"
                    e.message?.contains("network") == true -> "Network error during text prompt"
                    e.message?.contains("401") == true -> "Invalid API key for text prompt"
                    e.message?.contains("429") == true -> "Rate limit exceeded for text prompt"
                    e.message?.contains("SSL") == true -> "SSL error during text prompt"
                    else -> "Text prompt failed: ${e.message}"
                }
                Log.e(TAG, "❌ Text prompt failed: $errorMessage")
                withContext(Dispatchers.Main) {
                    onError?.invoke(errorMessage)
                    isProcessing = false
                }
            }
        }

        return true
    }
    
    /**
     * Captures a frame for video analysis (buffers only, no automatic analysis)
     * @param bitmap The frame bitmap to capture
     */
    fun captureVideoFrame(bitmap: Bitmap) {
        if (!videoAnalysisActive) {
            return
        }
        
        // Resize bitmap to reduce API payload
        val resizedBitmap = resizeBitmap(bitmap, 512, 512)
        
        // Add frame to buffer
        videoFrameBuffer.add(resizedBitmap)
        
        // Keep only the most recent frames
        if (videoFrameBuffer.size > MAX_VIDEO_FRAMES) {
            videoFrameBuffer.removeAt(0)
        }
        
        Log.d(TAG, "🎥 Video frame buffered: ${videoFrameBuffer.size}/$MAX_VIDEO_FRAMES (no analysis triggered)")
    }
    
    /**
     * Manually triggers video analysis with current buffer
     * This is used when all frames have been captured and ready for aggregate analysis
     * @param customUserPrompt Optional custom user prompt. If provided, will be used instead of default
     */
    fun triggerVideoAnalysis(customUserPrompt: String? = null) {
        if (!videoAnalysisActive) {
            Log.w(TAG, "⚠️ Video analysis not active")
            return
        }
        
        if (videoFrameBuffer.isEmpty()) {
            Log.w(TAG, "⚠️ No video frames to analyze")
            return
        }
        
        if (isProcessing) {
            Log.w(TAG, "⚠️ Already processing a request - skipping video analysis")
            return
        }
        
        Log.d(TAG, "🎬 Manually triggering video analysis with ${videoFrameBuffer.size} frames")
        
        // Analyze the frames
        analyzeVideoFrames(customUserPrompt)
    }
    
    /**
     * Gets the number of frames currently in the buffer
     */
    fun getVideoFrameCount(): Int = videoFrameBuffer.size
    
    fun getLatestFrameForPreview(): Bitmap? {
        val source = when {
            videoFrameBuffer.isNotEmpty() -> videoFrameBuffer.last()
            else -> null
        } ?: return null

        return try {
            source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "❌ Out of memory copying preview frame")
            null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error copying preview frame: ${e.message}")
            null
        }
    }
    
    /**
     * Analyzes video frames for active feedback
     */
    private fun analyzeVideoFrames(customUserPrompt: String? = null) {
        if (videoFrameBuffer.isEmpty()) {
            Log.w(TAG, "⚠️ No video frames to analyze")
            return
        }
        
        if (isProcessing) {
            Log.w(TAG, "⚠️ Already processing a request - skipping video analysis")
            return
        }
        
        Log.d(TAG, "🎥 Starting video analysis with ${videoFrameBuffer.size} frames")
        
        isProcessing = true
        
        coroutineScope.launch {
            try {
                val response = makeVideoAnalysisRequest(videoFrameBuffer.toList(), customUserPrompt)
                
                Log.d(TAG, "✅ Video analysis completed successfully")
                withContext(Dispatchers.Main) {
                    onResponse?.invoke(response)
                    isProcessing = false
                    onAnalysisComplete?.invoke()
                }
                
            } catch (e: Exception) {
                val errorMessage = when {
                    e.message?.contains("timeout") == true -> "Video analysis timeout - check internet connection"
                    e.message?.contains("network") == true -> "Network error during video analysis"
                    e.message?.contains("401") == true -> "Invalid API key for video analysis"
                    e.message?.contains("429") == true -> "Rate limit exceeded for video analysis"
                    e.message?.contains("SSL") == true -> "SSL error during video analysis"
                    else -> "Video analysis failed: ${e.message}"
                }
                Log.e(TAG, "❌ Video analysis failed: $errorMessage")
                withContext(Dispatchers.Main) {
                    onError?.invoke(errorMessage)
                    isProcessing = false
                }
            }
        }
    }
    
    /**
     * Makes a video analysis request to GPT-4 Omni with multiple frames
     */
    private suspend fun makeVideoAnalysisRequest(frames: List<Bitmap>, customUserPrompt: String? = null): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "🎥 Preparing video analysis request with ${frames.size} frames")
        
        val instr = instructions ?: throw Exception("Instructions not loaded")
        
        val messages = JSONArray()
        
        // System message for video analysis (loaded from file)
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", instr.videoSystemMessage)
        })
        
        // User message with multiple images
        val userMessage = JSONObject().apply {
            put("role", "user")
            val content = JSONArray()
            
            // Add text content - use custom prompt if provided, otherwise use default
            val userPromptText = customUserPrompt?.takeIf { it.isNotBlank() } ?: instr.videoUserPrompt
            content.put(JSONObject().apply {
                put("type", "text")
                put("text", userPromptText)
            })
            
            // Add multiple images as a video sequence
            Log.d(TAG, "🎥 Sending ${frames.size} frames to GPT-4 Omni for video analysis")
            
            frames.forEach { bitmap ->
                val base64Image = bitmapToBase64(bitmap)
                content.put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:image/jpeg;base64,$base64Image")
                    })
                })
            }
            
            put("content", content)
        }
        
        messages.put(userMessage)
        
        val requestBody = JSONObject().apply {
            put("model", MODEL_NAME)
            put("max_tokens", MAX_TOKENS)
            put("temperature", 0.7)
            put("messages", messages)
        }
        
        Log.d(TAG, "🎥 Sending video analysis request to GPT-4 Omni...")
        val rawResponse = makeHttpRequest(requestBody.toString())
        parseResponse(rawResponse)
    }

    /**
     * Makes a text-only request to GPT-4 Omni
     */
    private suspend fun makeTextPromptRequest(prompt: String): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "💬 Preparing text prompt request")

        val messages = JSONArray()

        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", TEXT_SYSTEM_MESSAGE)
        })

        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", prompt)
                })
            })
        })

        val requestBody = JSONObject().apply {
            put("model", MODEL_NAME)
            put("max_tokens", MAX_TOKENS)
            put("temperature", 0.7)
            put("messages", messages)
        }

        val rawResponse = makeHttpRequest(requestBody.toString())
        parseResponse(rawResponse)
    }
    
    /**
     * Sets callback functions for responses and errors
     * @param onResponse Callback for successful responses
     * @param onError Callback for errors
     * @param onAnalysisComplete Callback when analysis is complete
     */
    fun setCallbacks(
        onResponse: ((String) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
        onAnalysisComplete: (() -> Unit)? = null
    ) {
        this.onResponse = onResponse
        this.onError = onError
        this.onAnalysisComplete = onAnalysisComplete
    }
    
    /**
     * Converts bitmap to base64 string
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        return try {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            val byteArray = outputStream.toByteArray()
            Base64.encodeToString(byteArray, Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error converting bitmap to base64: ${e.message}")
            e.printStackTrace()
            throw Exception("Failed to encode image: ${e.message}")
        }
    }
    
    /**
     * Resizes bitmap to specified dimensions
     */
    private fun resizeBitmap(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        return try {
            val ratio = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
            val targetWidth = (bitmap.width * ratio).toInt()
            val targetHeight = (bitmap.height * ratio).toInt()
            Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error resizing bitmap: ${e.message}")
            e.printStackTrace()
            // Return original bitmap if resize fails
            bitmap
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "❌ Out of memory resizing bitmap")
            // Try smaller size
            try {
                val smallerWidth = width / 2
                val smallerHeight = height / 2
                val ratio = minOf(smallerWidth.toFloat() / bitmap.width, smallerHeight.toFloat() / bitmap.height)
                val targetWidth = (bitmap.width * ratio).toInt()
                val targetHeight = (bitmap.height * ratio).toInt()
                Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
            } catch (err: Exception) {
                Log.e(TAG, "❌ Failed to resize with smaller size: ${err.message}")
                bitmap
            }
        }
    }
    
    /**
     * Makes the HTTP request to OpenAI API using OkHttp
     */
    private suspend fun makeHttpRequest(requestBody: String): String = withContext(Dispatchers.IO) {
        if (!isEnabled || apiKey.isNullOrEmpty()) {
            Log.e(TAG, "❌ OpenAI service not enabled or API key missing")
            throw Exception("OpenAI service not enabled or API key missing")
        }
        
        // Log the request details
        try {
            val requestJson = JSONObject(requestBody)
            Log.d(TAG, "📤 OpenAI API Request:")
            Log.d(TAG, "   URL: $OPENAI_API_URL")
            Log.d(TAG, "   Model: ${requestJson.optString("model", "N/A")}")
            Log.d(TAG, "   Max Tokens: ${requestJson.optInt("max_tokens", 0)}")
            Log.d(TAG, "   Temperature: ${requestJson.optDouble("temperature", 0.0)}")
            
            val messages = requestJson.optJSONArray("messages")
            if (messages != null) {
                Log.d(TAG, "   Messages Count: ${messages.length()}")
                for (i in 0 until messages.length()) {
                    val message = messages.getJSONObject(i)
                    val role = message.optString("role", "N/A")
                    Log.d(TAG, "   Message $i - Role: $role")
                    
                    val content = message.opt("content")
                    when {
                        content is String -> {
                            Log.d(TAG, "   Message $i - Content (text): ${content.take(200)}${if (content.length > 200) "..." else ""}")
                        }
                        content is JSONArray -> {
                            Log.d(TAG, "   Message $i - Content (array): ${content.length()} items")
                            for (j in 0 until content.length()) {
                                val item = content.getJSONObject(j)
                                val type = item.optString("type", "N/A")
                                when (type) {
                                    "text" -> {
                                        val text = item.optString("text", "")
                                        Log.d(TAG, "      Item $j - Type: text, Text: ${text.take(200)}${if (text.length > 200) "..." else ""}")
                                    }
                                    "image_url" -> {
                                        val imageUrl = item.optJSONObject("image_url")?.optString("url", "") ?: ""
                                        val base64Preview = imageUrl.take(100)
                                        Log.d(TAG, "      Item $j - Type: image_url, Data preview: $base64Preview... (length: ${imageUrl.length} chars)")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            // Log full request body (may be large, but useful for debugging)
            Log.d(TAG, "📤 Full Request Body:")
            Log.d(TAG, requestBody)
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to parse request for logging: ${e.message}")
            Log.d(TAG, "📤 Raw Request Body: $requestBody")
        }
        
        val mediaType = "application/json".toMediaType()
        val body = requestBody.toRequestBody(mediaType)
        
        val request = Request.Builder()
            .url(OPENAI_API_URL)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("User-Agent", "Lochana-AI-Camera/1.0")
            .build()
        
        try {
            Log.d(TAG, "🤖 Making OpenAI API request...")
            val response = httpClient.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: throw IOException("Empty response body")
                
                // Log the response details
                Log.d(TAG, "✅ OpenAI API Response Received:")
                Log.d(TAG, "   Status Code: ${response.code}")
                Log.d(TAG, "   Response Length: ${responseBody.length} chars")
                
                try {
                    val responseJson = JSONObject(responseBody)
                    val choices = responseJson.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val firstChoice = choices.getJSONObject(0)
                        val message = firstChoice.optJSONObject("message")
                        val role = message?.optString("role", "N/A") ?: "N/A"
                        val content = message?.optString("content", "") ?: ""
                        
                        Log.d(TAG, "   Choices Count: ${choices.length()}")
                        Log.d(TAG, "   First Choice Role: $role")
                        Log.d(TAG, "   First Choice Content: ${content.take(500)}${if (content.length > 500) "..." else ""}")
                        
                        val usage = responseJson.optJSONObject("usage")
                        if (usage != null) {
                            Log.d(TAG, "   Usage - Prompt Tokens: ${usage.optInt("prompt_tokens", 0)}")
                            Log.d(TAG, "   Usage - Completion Tokens: ${usage.optInt("completion_tokens", 0)}")
                            Log.d(TAG, "   Usage - Total Tokens: ${usage.optInt("total_tokens", 0)}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Failed to parse response for detailed logging: ${e.message}")
                }
                
                // Log full response body
                Log.d(TAG, "📥 Full Response Body:")
                Log.d(TAG, responseBody)
                
                responseBody
            } else {
                val errorResponseBody = response.body?.string() ?: "No error body"
                Log.e(TAG, "❌ OpenAI API Error Response:")
                Log.e(TAG, "   Status Code: ${response.code}")
                Log.e(TAG, "   Error Body: $errorResponseBody")
                
                val errorMessage = when (response.code) {
                    401 -> "Invalid API key - check your OpenAI API key"
                    429 -> "Rate limit exceeded - too many requests"
                    500 -> "OpenAI server error - try again later"
                    503 -> "OpenAI service unavailable - try again later"
                    else -> "API request failed: ${response.code}"
                }
                Log.e(TAG, "❌ OpenAI API error: $errorMessage")
                throw Exception(errorMessage)
            }
            
        } catch (e: Exception) {
            val errorMessage = when {
                e.message?.contains("timeout") == true -> "Request timeout - check internet connection"
                e.message?.contains("network") == true -> "Network error - check internet connection"
                e.message?.contains("SSL") == true -> "SSL error - check device security settings"
                e.message?.contains("401") == true -> "Invalid API key - check your OpenAI API key"
                e.message?.contains("429") == true -> "Rate limit exceeded - wait before trying again"
                else -> "Request failed: ${e.message}"
            }
            Log.e(TAG, "❌ OpenAI API request failed: $errorMessage")
            Log.e(TAG, "❌ Exception details: ${e.message}")
            e.printStackTrace()
            throw Exception(errorMessage)
        }
    }
    
    /**
     * Parses the OpenAI API response
     */
    private fun parseResponse(responseBody: String): String {
        return try {
            if (responseBody.isBlank()) {
                throw Exception("Empty response body")
            }
            
            val jsonResponse = try {
                JSONObject(responseBody)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Invalid JSON response: ${responseBody.take(200)}")
                throw Exception("Invalid JSON response from API")
            }
            
            val choices = try {
                jsonResponse.getJSONArray("choices")
            } catch (e: Exception) {
                Log.e(TAG, "❌ No 'choices' array in response")
                throw Exception("Invalid response format - missing choices")
            }
            
            if (choices.length() > 0) {
                try {
                    val firstChoice = choices.getJSONObject(0)
                    val message = firstChoice.getJSONObject("message")
                    val content = message.getString("content")
                    
                    if (content.isBlank()) {
                        throw Exception("Empty content in response")
                    }
                    
                    Log.d(TAG, "📝 Parsed response: ${content.take(100)}...")
                    content.trim()
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error extracting content from choice: ${e.message}")
                    throw Exception("Failed to extract response content")
                }
            } else {
                throw Exception("No response from OpenAI")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to parse OpenAI response: ${e.message}")
            e.printStackTrace()
            throw Exception("Failed to parse AI response: ${e.message}")
        }
    }
    
    /**
     * Checks if OpenAI service is enabled and ready
     */
    fun isEnabled(): Boolean = isEnabled && apiKey != null
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        try {
            // Stop video analysis
            try {
                stopVideoAnalysis()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error stopping video analysis: ${e.message}")
            }
            
            // Cancel coroutine scope
            try {
                coroutineScope.cancel()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error canceling coroutine scope: ${e.message}")
            }
            
            // Clear frame buffers
            try {
                videoFrameBuffer.clear()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error clearing frame buffer: ${e.message}")
            }
            
            // Shutdown HTTP client
            try {
                httpClient.dispatcher.executorService.shutdown()
                if (!httpClient.dispatcher.executorService.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)) {
                    httpClient.dispatcher.executorService.shutdownNow()
                    Log.w(TAG, "⚠️ HTTP client forced shutdown")
                }
            } catch (e: InterruptedException) {
                try {
                    httpClient.dispatcher.executorService.shutdownNow()
                    Thread.currentThread().interrupt()
                } catch (err: Exception) {
                    Log.e(TAG, "❌ Error shutting down HTTP client: ${err.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error shutting down HTTP client: ${e.message}")
            }
            
            // Clear callbacks
            try {
                onResponse = null
                onError = null
                onAnalysisComplete = null
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error clearing callbacks: ${e.message}")
            }
            
            // Reset state
            isProcessing = false
            videoAnalysisActive = false
            lastSingleFrameAnalysisTime = 0L
            
            Log.d(TAG, "🧹 OpenAI manager cleaned up - all resources released")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during OpenAI cleanup: ${e.message}")
            e.printStackTrace()
        }
    }
}
