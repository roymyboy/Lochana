package com.lochana.app.ui

import android.content.Context
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import com.lochana.app.databinding.ActivityMainBinding
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class PromptController(
    private val context: Context,
    private val binding: ActivityMainBinding,
    private val listener: Listener
) {

    interface Listener {
        fun onPromptSubmitted(text: String)
        fun onHaptic()
    }

    companion object {
        private const val TAG = "PromptController"
        private const val MIC_TYPEWRITER_DELAY = 35L
    }

    private var userInitiatedFocus = false
    private var micTypewriterJob: Job? = null
    private var fontScale = 1f
    private val rootPadding = IntArray(4)

    fun initialize() {
        try {
            rootPadding[0] = binding.root.paddingLeft
            rootPadding[1] = binding.root.paddingTop
            rootPadding[2] = binding.root.paddingRight
            rootPadding[3] = binding.root.paddingBottom

            binding.etPrompt.apply {
                imeOptions = EditorInfo.IME_ACTION_SEND
                setRawInputType(
                    InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                )
                setHorizontallyScrolling(false)
                setOnEditorActionListener { _, actionId, event ->
                    val isSendAction = actionId == EditorInfo.IME_ACTION_SEND ||
                        actionId == EditorInfo.IME_ACTION_DONE ||
                        actionId == EditorInfo.IME_ACTION_GO ||
                        (actionId == EditorInfo.IME_NULL && event?.keyCode == KeyEvent.KEYCODE_ENTER)
                    if (isSendAction) {
                        submit()
                    } else {
                        false
                    }
                }
                setOnKeyListener { _, keyCode, event ->
                    micTypewriterJob?.cancel()
                    if (keyCode == KeyEvent.KEYCODE_ENTER && event?.action == KeyEvent.ACTION_UP) {
                        if (event.isShiftPressed) {
                            return@setOnKeyListener false
                        }
                        submit()
                    } else {
                        false
                    }
                }
                setOnFocusChangeListener { view, hasFocus ->
                    if (hasFocus) {
                        micTypewriterJob?.cancel()
                        if (userInitiatedFocus) {
                            try {
                                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
                            } catch (e: Exception) {
                                Log.e(TAG, "❌ Error showing keyboard: ${e.message}")
                            } finally {
                                userInitiatedFocus = false
                            }
                        } else {
                            hideKeyboard()
                        }
                    } else {
                        userInitiatedFocus = false
                    }
                }
                setOnClickListener {
                    micTypewriterJob?.cancel()
                    userInitiatedFocus = true
                    requestFocus()
                    try {
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error focusing prompt input: ${e.message}")
                    }
                }
            }

            ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
                val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
                val bottomPadding = if (imeInsets.bottom > 0) rootPadding[3] + imeInsets.bottom else rootPadding[3]
                view.setPadding(rootPadding[0], rootPadding[1], rootPadding[2], bottomPadding)
                WindowInsetsCompat.CONSUMED
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error setting up prompt input: ${e.message}")
        }
    }

    fun submit(): Boolean {
        return try {
            val text = binding.etPrompt.text?.toString()?.trim().orEmpty()
            if (text.isBlank()) {
                false
            } else {
                listener.onHaptic()
                listener.onPromptSubmitted(text)
                binding.etPrompt.setText("")
                binding.etPrompt.clearFocus()
                hideKeyboard()
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error submitting prompt: ${e.message}")
            false
        }
    }

    fun getPromptText(): String = binding.etPrompt.text?.toString()?.trim().orEmpty()

    fun consumePrompt(): String? {
        val text = getPromptText()
        return if (text.isBlank()) {
            null
        } else {
            binding.etPrompt.setText("")
            text
        }
    }

    fun clearPrompt() {
        binding.etPrompt.setText("")
        binding.etPrompt.clearFocus()
        hideKeyboard()
    }

    fun clearFocus() {
        binding.etPrompt.clearFocus()
    }

    fun appendSpeechWithTypewriter(text: String) {
        val sanitized = text.trim()
        if (sanitized.isEmpty()) return

        try {
            hideKeyboard()
            micTypewriterJob?.cancel()

            val existing = binding.etPrompt.text?.toString().orEmpty()
            val builder = StringBuilder(existing)
            if (builder.isNotEmpty() && !builder.last().isWhitespace()) {
                builder.append(' ')
            }

            binding.etPrompt.setText(builder.toString())
            updateFontScale(fontScale)
            binding.etPrompt.setSelection(binding.etPrompt.text?.length ?: 0)

            val handler = CoroutineExceptionHandler { _, throwable ->
                Log.e(TAG, "❌ Mic typewriter error: ${throwable.message}")
            }

            micTypewriterJob = CoroutineScope(Dispatchers.Main + handler).launch {
                try {
                    sanitized.forEach { char ->
                        builder.append(char)
                        binding.etPrompt.setText(builder.toString())
                        updateFontScale(fontScale)
                        binding.etPrompt.setSelection(builder.length)
                        delay(MIC_TYPEWRITER_DELAY)
                    }
                } catch (_: CancellationException) {
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error during mic typewriter: ${e.message}")
                } finally {
                    binding.etPrompt.setSelection(binding.etPrompt.text?.length ?: 0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to append prompt with typewriter: ${e.message}")
        }
    }

    fun updateFontScale(scale: Float) {
        fontScale = scale
        try {
            binding.etPrompt.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f * fontScale)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error updating prompt text size: ${e.message}")
        }
    }

    fun hideKeyboard() {
        try {
            userInitiatedFocus = false
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.etPrompt.windowToken, 0)
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to hide keyboard: ${e.message}")
        }
    }

    fun stopMicTypewriter() {
        micTypewriterJob?.cancel()
        micTypewriterJob = null
    }
}

