package com.lochana.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.animation.AlphaAnimation
import androidx.core.content.ContextCompat
import com.lochana.app.R
import com.lochana.app.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class SpeechController(
    private val context: Context,
    private val binding: ActivityMainBinding,
    private val promptController: PromptController,
    private val toast: (String) -> Unit,
    private val haptic: () -> Unit,
    private val speakStateListener: SpeakStateListener
) {

    interface SpeakStateListener {
        fun onSpeechResult(text: String)
        fun onSpeechRecognitionStart()
        fun onSpeechRecognitionStop()
        fun onSpeechStart()
        fun onSpeechComplete()
        fun onSpeechError()
    }

    companion object {
        private const val TAG = "SpeechController"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var micAnimationJob: Job? = null
    private var textToSpeech: TextToSpeech? = null
    private var isSpeaking = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeUtteranceId: String? = null

    fun initialize() {
        setupSpeechRecognition()
        setupTextToSpeech()
    }

    fun startVoiceInput() {
        if (isListening) {
            stopVoiceInput()
            return
        }

        val hasMicrophonePermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasMicrophonePermission) {
            toast(context.getString(R.string.voice_permission_required))
            Log.w(TAG, "⚠️ Microphone permission not granted")
            return
        }

        try {
            promptController.hideKeyboard()
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            }

            speechRecognizer?.startListening(intent)
            isListening = true
            startMicAnimation()
            haptic.invoke()
            speakStateListener.onSpeechRecognitionStart()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting voice input: ${e.message}")
            toast(context.getString(R.string.voice_input_error))
            isListening = false
            showIdleMic()
        }
    }

    fun stopVoiceInput() {
        if (isListening) {
            speechRecognizer?.stopListening()
            isListening = false
            showIdleMic()
            speakStateListener.onSpeechRecognitionStop()
        }
    }

    fun speakMessage(
        text: String,
        onStart: () -> Unit,
        onDone: () -> Unit,
        onError: () -> Unit
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            toast(context.getString(R.string.speech_no_text))
            return
        }
        if (textToSpeech == null) {
            toast(context.getString(R.string.speech_not_ready))
            Log.w(TAG, "⚠️ Text-to-speech engine not initialized for single response")
            return
        }

        val utteranceId = "tts_single_message_${System.currentTimeMillis()}"
        activeUtteranceId = utteranceId

        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId == activeUtteranceId) {
                    mainHandler.post {
                        if (utteranceId == activeUtteranceId) {
                            isSpeaking = true
                            onStart()
                            speakStateListener.onSpeechStart()
                        }
                    }
                }
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == activeUtteranceId) {
                    mainHandler.post {
                        if (utteranceId == activeUtteranceId) {
                            isSpeaking = false
                            onDone()
                            speakStateListener.onSpeechComplete()
                        }
                    }
                }
            }

            override fun onError(utteranceId: String?) {
                if (utteranceId == activeUtteranceId) {
                    mainHandler.post {
                        if (utteranceId == activeUtteranceId) {
                            isSpeaking = false
                            onError()
                            speakStateListener.onSpeechError()
                        }
                    }
                }
            }
        })

        val params = android.os.Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            }
        }

        textToSpeech?.speak(trimmed, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        haptic.invoke()
    }

    fun stopAllSpeech() {
        try {
            if (textToSpeech?.isSpeaking == true) {
                textToSpeech?.stop()
            }
            isSpeaking = false
            activeUtteranceId = null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error stopping text-to-speech: ${e.message}")
        }
    }

    fun cleanup() {
        try {
            stopAllSpeech()
            textToSpeech?.shutdown()
            textToSpeech = null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error cleaning up text-to-speech: ${e.message}")
        }

        try {
            stopVoiceInput()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error destroying speech recognizer: ${e.message}")
        }

        try {
            micAnimationJob?.cancel()
            micAnimationJob = null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error canceling mic animation: ${e.message}")
        }
    }

    fun isSpeaking(): Boolean = isSpeaking

    private fun setupSpeechRecognition() {
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    showListeningMic()
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    if (error == SpeechRecognizer.ERROR_CLIENT) {
                        Log.d(TAG, "Speech recognizer client callback (likely manual stop)")
                        showIdleMic()
                        isListening = false
                        speakStateListener.onSpeechRecognitionStop()
                        return
                    }

                    val errorMessage = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
                        SpeechRecognizer.ERROR_SERVER -> "Server error"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                        else -> "Unknown error"
                    }
                    Log.e(TAG, "❌ Speech recognition error: $errorMessage")
                    toast(errorMessage)
                    showIdleMic()
                    isListening = false
                    speakStateListener.onSpeechRecognitionStop()
                }

                override fun onResults(results: android.os.Bundle?) {
                    try {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val trimmedText = matches[0].trim()
                            if (trimmedText.isNotBlank()) {
                                speakStateListener.onSpeechResult(trimmedText)
                            }
                        }

                        if (isListening) {
                            startVoiceInput()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error processing speech results: ${e.message}")
                        showIdleMic()
                        isListening = false
                        speakStateListener.onSpeechRecognitionStop()
                    }
                }

                override fun onPartialResults(partialResults: android.os.Bundle?) {}
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
        } catch (e: Exception) {
            Log.e(TAG, "❌ Speech recognition setup error: ${e.message}")
            toast(context.getString(R.string.voice_input_error))
            speechRecognizer = null
        }
    }

    private fun setupTextToSpeech() {
        try {
            textToSpeech = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    try {
                        val result = textToSpeech?.setLanguage(Locale.US)
                        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                            Log.w(TAG, "⚠️ Language not supported for text-to-speech")
                            return@TextToSpeech
                        }
                        textToSpeech?.apply {
                            setSpeechRate(0.8f)
                            setPitch(0.9f)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                val voices = voices
                                val preferredVoice = voices?.find { voice ->
                                    voice.locale.language == "en" &&
                                        voice.quality == android.speech.tts.Voice.QUALITY_VERY_HIGH &&
                                        !voice.isNetworkConnectionRequired
                                } ?: voices?.find { voice ->
                                    voice.locale.language == "en" &&
                                        voice.quality == android.speech.tts.Voice.QUALITY_HIGH &&
                                        !voice.isNetworkConnectionRequired
                                }
                                preferredVoice?.let { voice = it }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error configuring text-to-speech: ${e.message}")
                    }
                } else {
                    Log.e(TAG, "❌ Text-to-speech initialization failed")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error setting up text-to-speech: ${e.message}")
        }
    }

    private fun startMicAnimation() {
        micAnimationJob?.cancel()
        val handler = CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "❌ Mic animation coroutine error: ${throwable.message}")
        }

        micAnimationJob = CoroutineScope(Dispatchers.Main + handler).launch {
            try {
                while (isActive) {
                    binding.btnMic.alpha = 1.0f
                    delay(500)
                    binding.btnMic.alpha = 0.5f
                    delay(500)
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Mic animation error: ${e.message}")
            }
        }
    }

    private fun showListeningMic() {
        binding.btnMic.setImageResource(R.drawable.ic_mic_listening)
        binding.btnMic.alpha = 1.0f
        startMicAnimation()
    }

    private fun showIdleMic() {
        micAnimationJob?.cancel()
        binding.btnMic.setImageResource(R.drawable.ic_mic)
        binding.btnMic.alpha = 0.8f
    }
}

