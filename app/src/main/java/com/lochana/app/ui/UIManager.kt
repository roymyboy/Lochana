package com.lochana.app.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.lochana.app.R
import com.lochana.app.databinding.ActivityMainBinding
import com.lochana.app.ui.components.CaptureButtonDrawable
import com.lochana.app.ui.SpeechController
import com.lochana.app.ui.SpeechController.SpeakStateListener

class UIManager(
    private val context: Context,
    private val binding: ActivityMainBinding
) {

    enum class CaptureMode { ANALYSIS, OCR }
    
    companion object {
        private const val TAG = "UIManager"
        private const val LONG_PRESS_TIMEOUT = 500L
        private const val DOUBLE_TAP_TIMEOUT = 300L
        private const val DEFAULT_DIVIDER_PERCENT = 0.65f
        private const val MIN_DIVIDER_PERCENT = 0.3f
        private const val MAX_DIVIDER_PERCENT = 0.9f
        private const val MIN_CHAT_FONT_SCALE = 0.75f
        private const val MAX_CHAT_FONT_SCALE = 1.6f
        private const val SNAPSHOT_MAX_DIMENSION = 1440
        private const val SNAPSHOT_JPEG_QUALITY = 95
        private const val MAX_SNAPSHOT_FILES = 30
        private val STATUS_KEYWORDS = listOf("analyzing", "capturing", "camera error", "processing", "initializing", "ocr")
    }

    private var lastTapTime = 0L
    private var tapCount = 0
    private var longPressHandler: Handler? = null
    private var longPressRunnable: Runnable? = null
    private var doubleTapHandler: Handler? = null
    private var doubleTapRunnable: Runnable? = null
    private var scaleGestureDetector: ScaleGestureDetector? = null
    private var isScaling = false
    private var onSingleTap: ((PointF) -> Unit)? = null
    private var onLongPress: (() -> Unit)? = null
    private var onScale: ((Float) -> Unit)? = null
    private var onCameraToggle: (() -> Unit)? = null
    private var onCaptureButton: (() -> Unit)? = null
    private var onLongPressCapture: (() -> Unit)? = null
    private var onPromptSubmit: ((String) -> Unit)? = null

    private var captureButtonDrawable: CaptureButtonDrawable? = null
    private var vibrator: Vibrator? = null
    private var dividerPercent = DEFAULT_DIVIDER_PERCENT
    private var dragStartPercent = DEFAULT_DIVIDER_PERCENT
    private var dragStartY = 0f
    private var chatScaleDetector: ScaleGestureDetector? = null
    private var isChatScaling = false
    private var activeSpeechIndex: Int? = null
    private var captureMode: CaptureMode = CaptureMode.ANALYSIS

    private val snapshotManager = SnapshotManager(
        context,
        SNAPSHOT_MAX_DIMENSION,
        SNAPSHOT_JPEG_QUALITY,
        MAX_SNAPSHOT_FILES
    )
    private val previewDialogController = PreviewDialogController(context, snapshotManager, ::showToast)
    private lateinit var promptController: PromptController
    private lateinit var chatController: ChatController
    private lateinit var speechController: SpeechController
    
    init {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }
    
    fun setCallbacks(
        onSingleTap: ((PointF) -> Unit)? = null,
        onLongPress: (() -> Unit)? = null,
        onScale: ((Float) -> Unit)? = null,
        onCameraToggle: (() -> Unit)? = null,
        onCaptureButton: (() -> Unit)? = null,
        onLongPressCapture: (() -> Unit)? = null,
        onPromptSubmit: ((String) -> Unit)? = null
    ) {
        this.onSingleTap = onSingleTap
        this.onLongPress = onLongPress
        this.onScale = onScale
        this.onCameraToggle = onCameraToggle
        this.onCaptureButton = onCaptureButton
        this.onLongPressCapture = onLongPressCapture
        this.onPromptSubmit = onPromptSubmit
    }
    
    fun initialize() {
        setupControllers()
        setupTouchListener()
        setupCaptureButton()
        setupResizableDivider()
        setupChatScaling()
    }

    private fun setupControllers() {
        promptController = PromptController(context, binding, object : PromptController.Listener {
            override fun onPromptSubmitted(text: String) {
                addUserMessage(text)
                showTypingIndicator()
                onPromptSubmit?.invoke(text)
            }

            override fun onHaptic() {
                triggerHapticFeedback()
            }
        }).also { controller ->
            controller.initialize()
        }

        chatController = ChatController(
            context,
            binding,
            snapshotManager,
            previewDialogController,
            object : ChatController.Listener {
                override fun onSpeakRequested(index: Int, message: ChatController.MessageItem) {
                    toggleMessageSpeech(index)
                }

                override fun onRequestHaptic() {
                    triggerHapticFeedback()
                }

                override fun onPromptFocusReset() {
                    promptController.clearFocus()
                }

                override fun onKeyboardHide() {
                    promptController.hideKeyboard()
                }

                override fun onToast(message: String) {
                    showToast(message)
                }

                override fun isStatusMessage(text: String?): Boolean = this@UIManager.isStatusMessage(text)
            }
        )

        speechController = SpeechController(
            context,
            binding,
            promptController,
            ::showToast,
            ::triggerHapticFeedback,
            object : SpeakStateListener {
                override fun onSpeechResult(text: String) {
                    appendPromptWithTypewriter(text)
                }

                override fun onSpeechRecognitionStart() {}
                override fun onSpeechRecognitionStop() {}
                override fun onSpeechStart() {}

                override fun onSpeechComplete() {
                    activeSpeechIndex?.let { chatController.setMessageSpeakingState(it, false) }
                    activeSpeechIndex = null
                }

                override fun onSpeechError() {
                    activeSpeechIndex?.let { chatController.setMessageSpeakingState(it, false) }
                    activeSpeechIndex = null
                }
            }
        ).also { controller ->
            controller.initialize()
        }

        promptController.updateFontScale(chatController.getFontScale())
        binding.btnMic.setOnClickListener { speechController.startVoiceInput() }
        setupModeSelector()
    }

    private fun setupModeSelector() {
        try {
            val options = listOf(
                context.getString(R.string.mode_analysis),
                context.getString(R.string.mode_ocr)
            )
            val adapter = ArrayAdapter(
                context,
                R.layout.item_capture_mode,
                options
            )
            binding.captureModeDropdown.setAdapter(adapter)
            binding.captureModeDropdown.setText(
                if (captureMode == CaptureMode.ANALYSIS) options[0] else options[1],
                false
            )
            binding.captureModeDropdown.setOnItemClickListener { _, _, position, _ ->
                val newMode = if (position == 0) CaptureMode.ANALYSIS else CaptureMode.OCR
                if (captureMode != newMode) {
                    captureMode = newMode
                    triggerHapticFeedback()
                }
                binding.captureModeDropdown.clearFocus()
                promptController.hideKeyboard()
            }
            binding.captureModeDropdown.setOnClickListener {
                promptController.hideKeyboard()
                binding.captureModeDropdown.showDropDown()
            }
            binding.captureModeDropdown.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    promptController.hideKeyboard()
                    binding.captureModeDropdown.showDropDown()
                }
            }
            binding.captureModeContainer.setOnClickListener {
                promptController.hideKeyboard()
                binding.captureModeDropdown.requestFocus()
                binding.captureModeDropdown.showDropDown()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error setting up mode selector: ${e.message}")
        }
    }

    fun getCaptureMode(): CaptureMode = captureMode
    
    private fun setupTouchListener() {
        try {
            longPressHandler = Handler(Looper.getMainLooper())
            doubleTapHandler = Handler(Looper.getMainLooper())
            
            binding.viewFinder.setOnTouchListener { _, event ->
                try {
                    try {
                        scaleGestureDetector?.onTouchEvent(event)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Scale gesture error: ${e.message}")
                    }
                    
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            val currentTime = System.currentTimeMillis()
                            if (event.pointerCount == 1) {
                                tapCount = if (currentTime - lastTapTime < DOUBLE_TAP_TIMEOUT) 2 else 1
                                lastTapTime = currentTime
                                
                                if (!isScaling) {
                                        longPressRunnable = Runnable {
                                                if (!isScaling) {
                                                    onLongPress?.invoke()
                                            }
                                        }
                                        longPressHandler?.postDelayed(longPressRunnable!!, LONG_PRESS_TIMEOUT)
                                    }
                                }
                            true
                        }

                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP -> {
                            if (event.pointerCount <= 1) {
                                isScaling = false
                            }
                            longPressRunnable?.let { longPressHandler?.removeCallbacks(it) }

                            if (event.pointerCount == 1) {
                                when (tapCount) {
                                    1 -> {
                                            doubleTapRunnable = Runnable {
                                                    val point = PointF(event.x, event.y)
                                                    onSingleTap?.invoke(point)
                                            }
                                            doubleTapHandler?.postDelayed(doubleTapRunnable!!, DOUBLE_TAP_TIMEOUT)
                                    }

                                    2 -> {
                                        doubleTapRunnable?.let { doubleTapHandler?.removeCallbacks(it) }
                                            onCameraToggle?.invoke()
                                    }
                                }
                                tapCount = 0
                            }
                            true
                        }

                        MotionEvent.ACTION_MOVE -> true
                        else -> false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Touch event processing error: ${e.message}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Touch listener setup error: ${e.message}")
        }
    }
    
    fun setupScaleGestureDetector(scaleGestureDetector: ScaleGestureDetector) {
        this.scaleGestureDetector = scaleGestureDetector
    }
    
    fun createScaleGestureDetector(): ScaleGestureDetector {
        return ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isScaling = true
                longPressRunnable?.let { longPressHandler?.removeCallbacks(it) }
                return true
            }
            
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                onScale?.invoke(detector.scaleFactor)
                return true
            }
            
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
            }
        })
    }
    
    fun showFocusAnimation(point: PointF) {
        try {
            val focusView = binding.focusIndicator
            focusView.visibility = View.VISIBLE
            
            val focusSize = 100f
            val halfSize = focusSize / 2f
            focusView.x = point.x - halfSize
            focusView.y = point.y - halfSize
            
            val scaleX = ObjectAnimator.ofFloat(focusView, "scaleX", 0f, 1.2f, 1f)
            val scaleY = ObjectAnimator.ofFloat(focusView, "scaleY", 0f, 1.2f, 1f)
            val alpha = ObjectAnimator.ofFloat(focusView, "alpha", 0f, 1f, 0.8f)
            
            AnimatorSet().apply {
                playTogether(scaleX, scaleY, alpha)
                duration = 300
                interpolator = DecelerateInterpolator()
                start()
            }

            focusView.postDelayed({
                try {
                    ObjectAnimator.ofFloat(focusView, "alpha", focusView.alpha, 0f).apply {
                        duration = 200
                        start()
                        addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                                focusView.visibility = View.GONE
                        }
                    })
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Focus fade out error: ${e.message}")
                }
            }, 800)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Focus animation error: ${e.message}")
        }
    }
    
    fun updateDetectionStatus(enabled: Boolean, active: Boolean) {}
    
    fun updateVideoAnalysisStatus(isMoving: Boolean) {}
    
    fun updateDescription(text: String) {
        addAssistantMessage(text, imagePath = null, animated = false)
    }

    fun updateDescriptionWithTypewriter(
        text: String,
        imagePath: String? = null,
        previewBitmap: Bitmap? = null,
        onComplete: (() -> Unit)? = null
    ) {
        val resolvedPath = imagePath ?: previewBitmap?.let { snapshotManager.preparePreview(it) }
        addAssistantMessage(text, imagePath = resolvedPath, animated = true, onComplete = onComplete)
    }

    fun showTypingIndicator() {
        chatController.showTypingIndicator()
    }

    fun hideTypingIndicator() {
        chatController.hideTypingIndicator()
    }

    private fun scrollToBottom() {
        chatController.scrollToBottom()
    }

    private fun addUserMessage(text: String) {
        chatController.addUserMessage(text)
    }

    private fun addAssistantMessage(
        text: String,
        imagePath: String? = null,
        animated: Boolean = true,
        onComplete: (() -> Unit)? = null
    ) {
        chatController.addAssistantMessage(text, imagePath, animated, onComplete)
    }

    fun prepareResponsePreview(bitmap: Bitmap?): String? = snapshotManager.preparePreview(bitmap)

    fun getCustomPromptText(): String = promptController.getPromptText()

    fun consumeUserPrompt(): String? = promptController.consumePrompt()

    fun ensureUserPromptVisible(prompt: String?) {
        chatController.ensureUserPromptVisible(prompt)
    }

    fun setCaptureButtonEnabled(enabled: Boolean) {
        captureButtonDrawable?.setDisabled(!enabled)
        binding.btnCapture.isEnabled = enabled
        binding.btnCapture.isClickable = enabled
    }

    @SuppressLint("MissingPermission")
    private fun triggerHapticFeedback() {
        val vibrator = vibrator ?: return
        if (!vibrator.hasVibrator()) return

        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> vibrator.vibrate(
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                )
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> vibrator.vibrate(
                    VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE)
                )
                else -> @Suppress("DEPRECATION") vibrator.vibrate(10)
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to trigger haptic feedback: ${e.message}")
        }
    }

    fun clearCustomPromptText() {
        promptController.clearPrompt()
    }

    private fun hideKeyboard() {
        promptController.hideKeyboard()
    }

    private fun isStatusMessage(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val normalized = text.lowercase()
        return STATUS_KEYWORDS.any { normalized.contains(it) }
    }

    fun stopTypewriter() {
        chatController.stopTypewriter()
    }

    fun resetTextBoxSize() {
        applyDividerPercent(DEFAULT_DIVIDER_PERCENT)
    }

    fun showToast(message: String) {
        try {
            val inflater = LayoutInflater.from(context)
            val toastLayout = inflater.inflate(R.layout.custom_toast, null)
            toastLayout.findViewById<TextView>(R.id.toastText).text = message

            Toast(context).apply {
                duration = Toast.LENGTH_SHORT
                @Suppress("DEPRECATION")
                view = toastLayout
                setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 100)
                show()
            }
            } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing custom toast: ${e.message}")
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun startCaptureButtonAnimation() {
        captureButtonDrawable?.startProgressAnimation()
    }

    fun stopCaptureButtonAnimation() {
        captureButtonDrawable?.stopProgressAnimation()
    }

    private fun updateChatFontScale(newScale: Float, preserveScroll: Boolean = true) {
        val clamped = clamp(newScale, MIN_CHAT_FONT_SCALE, MAX_CHAT_FONT_SCALE)
        val previousScroll = if (preserveScroll) binding.scrollView.scrollY else null
        chatController.setFontScale(clamped)
        promptController.updateFontScale(clamped)
        previousScroll?.let { scroll ->
            binding.scrollView.post { binding.scrollView.scrollTo(0, scroll) }
        }
    }

    private fun toggleMessageSpeech(index: Int) {
        val message = chatController.getMessage(index) ?: return
        if (activeSpeechIndex == index) {
            speechController.stopAllSpeech()
            chatController.setMessageSpeakingState(index, false)
            activeSpeechIndex = null
            return
        }

        speechController.stopAllSpeech()
        activeSpeechIndex?.let { chatController.setMessageSpeakingState(it, false) }
        activeSpeechIndex = index
        chatController.setMessageSpeakingState(index, true)

        speechController.speakMessage(
            message.text,
            onStart = {},
            onDone = {
                chatController.setMessageSpeakingState(index, false)
                activeSpeechIndex = null
            },
            onError = {
                chatController.setMessageSpeakingState(index, false)
                activeSpeechIndex = null
            }
        )
    }

    fun stopAllSpeech() {
        speechController.stopAllSpeech()
        activeSpeechIndex?.let { chatController.setMessageSpeakingState(it, false) }
        activeSpeechIndex = null
    }

    private fun setupCaptureButton() {
        captureButtonDrawable = CaptureButtonDrawable()
        binding.btnCapture.background = captureButtonDrawable
        
        binding.btnCapture.setOnClickListener {
            triggerHapticFeedback()
            setCaptureButtonEnabled(false)
            onCaptureButton?.invoke()
        }
        
        binding.btnCapture.setOnLongClickListener {
            triggerHapticFeedback()
            setCaptureButtonEnabled(false)
            onLongPressCapture?.invoke()
            true
        }
    }

    private fun setupResizableDivider() {
        binding.cameraDividerGuideline.setGuidelinePercent(dividerPercent)

        val touchListener = View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartY = event.rawY
                    dragStartPercent = dividerPercent
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val parentHeight = binding.root.height.takeIf { it > 0 } ?: return@OnTouchListener true
                    val deltaPercent = (event.rawY - dragStartY) / parentHeight
                    val clamped = clamp(dragStartPercent + deltaPercent, MIN_DIVIDER_PERCENT, MAX_DIVIDER_PERCENT)
                    applyDividerPercent(clamped)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }

        binding.resizeHandle.setOnTouchListener(touchListener)
        binding.resizeHandleTouchArea.setOnTouchListener(touchListener)
    }

    private fun setupChatScaling() {
        chatScaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isChatScaling = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                updateChatFontScale(chatController.getFontScale() * detector.scaleFactor)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isChatScaling = false
            }
        })

        binding.scrollView.setOnTouchListener { view, event ->
            chatScaleDetector?.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isChatScaling = false
                    view.performClick()
                }
            }
            isChatScaling
        }
    }

    private fun applyDividerPercent(percent: Float) {
        dividerPercent = percent
        binding.cameraDividerGuideline.setGuidelinePercent(percent)
    }

    private fun clamp(value: Float, min: Float, max: Float): Float = when {
        value < min -> min
        value > max -> max
        else -> value
    }
    
    fun cleanup() {
        speechController.cleanup()
        promptController.stopMicTypewriter()
        chatController.stopTypewriter()
        chatController.hideTypingIndicator()
        snapshotManager.clearSnapshots()
        activeSpeechIndex = null

        longPressRunnable?.let { longPressHandler?.removeCallbacks(it) }
        doubleTapRunnable?.let { doubleTapHandler?.removeCallbacks(it) }
            longPressHandler = null
            doubleTapHandler = null
            longPressRunnable = null
            doubleTapRunnable = null
    }

    private fun appendPromptWithTypewriter(text: String) {
        promptController.appendSpeechWithTypewriter(text)
    }
}
