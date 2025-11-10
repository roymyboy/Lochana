package com.lochana.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.lochana.app.R
import com.lochana.app.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

class ChatController(
    private val context: Context,
    private val binding: ActivityMainBinding,
    private val snapshotManager: SnapshotManager,
    private val previewDialogController: PreviewDialogController,
    private val listener: Listener
) {

    interface Listener {
        fun onSpeakRequested(index: Int, message: MessageItem)
        fun onRequestHaptic()
        fun onPromptFocusReset()
        fun onKeyboardHide()
        fun onToast(message: String)
        fun isStatusMessage(text: String?): Boolean
    }

    data class MessageItem(
        val role: UserRole,
        val text: String,
        val imagePath: String? = null,
        val isTyping: Boolean = false,
        val isSpeaking: Boolean = false
    )

    enum class UserRole { USER, ASSISTANT }

    companion object {
        private const val TAG = "ChatController"
        private const val TYPEWRITER_DELAY_MS = 100L
        private const val PREVIEW_HEIGHT_DP = 96
    }

    private val chatMessages = mutableListOf<MessageItem>()
    private var typingIndicatorJob: Job? = null
    private var typingIndicatorIndex: Int = -1
    private var typewriterJob: Job? = null
    private var chatFontScale: Float = 1f

    fun clear() {
        chatMessages.clear()
        renderMessages()
    }

    fun setFontScale(scale: Float) {
        chatFontScale = scale
        renderMessages()
    }

    fun getFontScale(): Float = chatFontScale

    fun addUserMessage(text: String) {
        val sanitized = text.trim()
        if (sanitized.isEmpty()) return

        chatMessages.add(MessageItem(UserRole.USER, sanitized))
        renderMessages()
        scrollToBottom()
    }

    fun addAssistantMessage(
        text: String,
        imagePath: String? = null,
        animated: Boolean = true,
        onComplete: (() -> Unit)? = null
    ) {
        val sanitized = text.trim()
        hideTypingIndicator()
        if (sanitized.isEmpty()) {
            onComplete?.invoke()
            return
        }

        listener.onPromptFocusReset()
        listener.onKeyboardHide()

        if (!animated) {
            if (listener.isStatusMessage(sanitized) &&
                chatMessages.lastOrNull()?.role == UserRole.ASSISTANT &&
                listener.isStatusMessage(chatMessages.lastOrNull()?.text)
            ) {
                chatMessages.removeAt(chatMessages.lastIndex)
            }
            chatMessages.add(MessageItem(UserRole.ASSISTANT, sanitized, imagePath = imagePath))
            renderMessages()
            scrollToBottom()
            onComplete?.invoke()
            return
        }

        typewriterJob?.cancel()
        val baseMessages = chatMessages.filterNot { it.isTyping }.toMutableList()
        chatMessages.clear()
        chatMessages.addAll(baseMessages)
        chatMessages.add(MessageItem(UserRole.ASSISTANT, "", imagePath = imagePath, isTyping = true))
        renderMessages()
        scrollToBottom()

        val handler = CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "❌ Assistant typewriter coroutine error: ${throwable.message}")
            onComplete?.invoke()
        }

        typewriterJob = CoroutineScope(Dispatchers.Main + handler).launch {
            try {
                val words = sanitized.split(" ").filter { it.isNotBlank() }
                if (words.isEmpty()) {
                    onComplete?.invoke()
                    return@launch
                }

                var currentText = ""
                words.forEachIndexed { index, word ->
                    val trimmedWord = word.trim()
                    if (trimmedWord.isEmpty()) return@forEachIndexed
                    currentText = if (index == 0) trimmedWord else "$currentText $trimmedWord"
                    if (chatMessages.isNotEmpty()) {
                        chatMessages[chatMessages.lastIndex] =
                            chatMessages.last().copy(text = currentText, isTyping = true)
                    } else {
                        chatMessages.add(
                            MessageItem(UserRole.ASSISTANT, currentText, imagePath = imagePath, isTyping = true)
                        )
                    }
                    renderMessages()
                    listener.onRequestHaptic()
                    scrollToBottom()
                    delay(TYPEWRITER_DELAY_MS)
                }
                if (chatMessages.isNotEmpty()) {
                    chatMessages[chatMessages.lastIndex] =
                        chatMessages.last().copy(isTyping = false)
                    renderMessages()
                    scrollToBottom()
                }
                onComplete?.invoke()
            } catch (e: CancellationException) {
                chatMessages.clear()
                chatMessages.addAll(baseMessages)
                renderMessages()
                scrollToBottom()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error during assistant typewriter: ${e.message}")
                onComplete?.invoke()
            }
        }
    }

    fun showTypingIndicator() {
        try {
            hideTypingIndicator()

            chatMessages.add(MessageItem(UserRole.ASSISTANT, ".", isTyping = true))
            typingIndicatorIndex = chatMessages.lastIndex
            renderMessages()
            scrollToBottom()

            val handler = CoroutineExceptionHandler { _, throwable ->
                Log.e(TAG, "❌ Typing indicator error: ${throwable.message}")
            }

            typingIndicatorJob = CoroutineScope(Dispatchers.Main + handler).launch {
                val frames = listOf(".", "..", "...", "..")
                var frameIndex = 0
                while (isActive) {
                    if (typingIndicatorIndex in chatMessages.indices && chatMessages[typingIndicatorIndex].isTyping) {
                        chatMessages[typingIndicatorIndex] =
                            chatMessages[typingIndicatorIndex].copy(text = frames[frameIndex])
                        renderMessages()
                        scrollToBottom()
                        frameIndex = (frameIndex + 1) % frames.size
                    } else {
                        break
                    }
                    delay(350)
                }
            }
        } catch (_: CancellationException) {
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing typing indicator: ${e.message}")
        }
    }

    fun hideTypingIndicator() {
        try {
            typingIndicatorJob?.cancel()
            typingIndicatorJob = null
            if (typingIndicatorIndex in chatMessages.indices && chatMessages[typingIndicatorIndex].isTyping) {
                chatMessages.removeAt(typingIndicatorIndex)
                renderMessages()
                scrollToBottom()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error hiding typing indicator: ${e.message}")
        } finally {
            typingIndicatorIndex = -1
        }
    }

    fun ensureUserPromptVisible(prompt: String?) {
        val sanitized = prompt?.trim().orEmpty()
        if (sanitized.isEmpty()) return

        val lastUserMessage = chatMessages.lastOrNull { it.role == UserRole.USER }?.text
        if (lastUserMessage == sanitized) return

        chatMessages.add(MessageItem(UserRole.USER, sanitized))
        renderMessages()
        scrollToBottom()
    }

    fun stopTypewriter() {
        typewriterJob?.cancel()
        typewriterJob = null
    }

    fun setMessageSpeakingState(index: Int, speaking: Boolean) {
        if (index !in chatMessages.indices) return
        val message = chatMessages[index]
        if (message.isSpeaking == speaking) return
        chatMessages[index] = message.copy(isSpeaking = speaking)
        renderMessages()
    }

    fun getMessage(index: Int): MessageItem? = chatMessages.getOrNull(index)

    fun getMessageCount(): Int = chatMessages.size

    fun renderMessages() {
        binding.chatContainer.removeAllViews()
        val padding = 12.dp()
        val margin = 6.dp()

        chatMessages.forEachIndexed { index, message ->
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = margin
                    bottomMargin = margin
                    gravity = if (message.role == UserRole.USER) Gravity.END else Gravity.START
                }
            }

            var previewClickListener: (() -> Unit)? = null

            if (!message.imagePath.isNullOrBlank() && !message.isTyping && message.role == UserRole.ASSISTANT) {
                previewClickListener = {
                    previewDialogController.show(message.imagePath, message.text)
                }

                val imageContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = 8.dp()
                    }
                    background = ContextCompat.getDrawable(context, R.drawable.chat_image_background)
                    clipToOutline = true
                }

                val imageView = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        PREVIEW_HEIGHT_DP.dp()
                    )
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = context.getString(R.string.chat_image_preview_content_description)

                    val bitmap = snapshotManager.loadSnapshot(message.imagePath)
                    if (bitmap != null) {
                        setImageBitmap(bitmap)
                    } else {
                        setImageResource(R.drawable.ic_image_placeholder)
                    }
                }

                imageContainer.addView(imageView)
                imageContainer.isClickable = true
                imageContainer.isFocusable = true
                imageContainer.setOnClickListener { previewClickListener?.invoke() }
                container.addView(imageContainer)
            }

            val textView = TextView(context).apply {
                text = message.text
                tag = message.role
                setTextColor(Color.parseColor("#E6E9F5"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * chatFontScale)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

                if (message.role == UserRole.USER) {
                    setPadding(padding, padding, padding, padding)
                    background = ContextCompat.getDrawable(context, R.drawable.chat_bubble_user)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.END
                    }
                } else {
                    setPadding(padding, padding, padding, padding / 2)
                    background = null
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.START
                    }
                }

                if (message.isTyping) {
                    alpha = 0.7f
                    setTypeface(typeface, Typeface.ITALIC)
                } else {
                    alpha = 1.0f
                }
            }

            if (previewClickListener != null) {
                textView.setOnClickListener { previewClickListener?.invoke() }
            } else {
                textView.setOnClickListener(null)
            }

            container.addView(textView)

            if (message.role == UserRole.ASSISTANT && !message.isTyping) {
                val actionRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.START
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }

                val copyButton = ImageButton(context).apply {
                    setImageResource(R.drawable.ic_copy)
                    contentDescription = context.getString(R.string.copy_single_response)
                    background = ContextCompat.getDrawable(context, R.drawable.translucent_button_background)
                    setPadding(16, 16, 16, 16)
                    imageTintList = ContextCompat.getColorStateList(context, android.R.color.white)
                    setOnClickListener { copyMessageToClipboard(message.text) }
                }

                val speakerButton = ImageButton(context).apply {
                    setImageResource(R.drawable.ic_speaker)
                    contentDescription = context.getString(R.string.read_single_response)
                    setPadding(16, 16, 16, 16)
                    setOnClickListener { listener.onSpeakRequested(index, message) }
                }

                if (message.isSpeaking) {
                    speakerButton.background = ContextCompat.getDrawable(context, R.drawable.translucent_button_background_active)
                    speakerButton.imageTintList = ContextCompat.getColorStateList(context, R.color.primary_blue)
                    speakerButton.alpha = 1f
                } else {
                    speakerButton.background = ContextCompat.getDrawable(context, R.drawable.translucent_button_background)
                    speakerButton.imageTintList = ContextCompat.getColorStateList(context, android.R.color.white)
                    speakerButton.alpha = 0.8f
                }

                val buttonParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    rightMargin = 8.dp()
                }

                actionRow.addView(copyButton, buttonParams)
                actionRow.addView(speakerButton)
                container.addView(actionRow)
            }

            binding.chatContainer.addView(container)
        }
    }

    fun refresh() {
        renderMessages()
    }

    fun scrollToBottom() {
        try {
            binding.scrollView.post {
                binding.scrollView.fullScroll(LinearLayout.FOCUS_DOWN)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error scrolling to bottom: ${e.message}")
        }
    }

    fun copyMessageToClipboard(text: String) {
        try {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                listener.onToast("Nothing to copy yet")
                return
            }
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Assistant response", trimmed))
            listener.onRequestHaptic()
            listener.onToast("Text Copied")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error copying response: ${e.message}")
            listener.onToast("Failed to copy response")
        }
    }

    private fun Int.dp(): Int = (this * context.resources.displayMetrics.density).roundToInt()
}

