package com.lochana.app.ocr

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.TaskExecutors
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class OcrProcessor {

    companion object {
        private const val TAG = "OcrProcessor"
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun process(
        bitmap: Bitmap,
        onSuccess: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener(TaskExecutors.MAIN_THREAD) { result ->
                    onSuccess(result.text.trim())
                }
                .addOnFailureListener(TaskExecutors.MAIN_THREAD) { error ->
                    Log.e(TAG, "❌ OCR failed: ${error.message}")
                    onError(error)
                }
        } catch (e: Exception) {
            Log.e(TAG, "❌ OCR processing error: ${e.message}")
            onError(e)
        }
    }

    fun close() {
        try {
            recognizer.close()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error closing OCR recognizer: ${e.message}")
        }
    }
}

