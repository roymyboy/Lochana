package com.lochana.app.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import com.lochana.app.camera.CameraManager
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executor

class HighResolutionCapturePipeline(
    private val cameraManager: CameraManager,
    private val callbackExecutor: Executor,
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
) {

    companion object {
        private const val TAG = "HighResPipeline"
    }

    fun captureBitmap(
        onSuccess: (Bitmap) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val imageCapture = cameraManager.getImageCapture()
        if (imageCapture == null) {
            Log.e(TAG, "❌ ImageCapture not available")
            mainHandler.post { onError(IllegalStateException("Image capture not initialized")) }
            return
        }

        imageCapture.takePicture(
            callbackExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val bitmap = imageProxyToBitmap(image)
                        val rotated = rotateBitmapIfNeeded(bitmap, image.imageInfo.rotationDegrees)
                        mainHandler.post { onSuccess(rotated) }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Failed to convert high-res image: ${e.message}")
                        mainHandler.post { onError(e) }
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "❌ High-res capture failed: ${exception.message}")
                    mainHandler.post { onError(exception) }
                }
            }
        )
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val bytes = when (image.format) {
            ImageFormat.YUV_420_888 -> yuv420ToJpegByteArray(image)
            ImageFormat.JPEG -> bufferToByteArray(image.planes[0].buffer)
            else -> throw IllegalArgumentException("Unsupported image format: ${image.format}")
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun yuv420ToJpegByteArray(image: ImageProxy): ByteArray {
        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = android.graphics.YuvImage(
            nv21,
            ImageFormat.NV21,
            image.width,
            image.height,
            null
        )

        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)
        return out.toByteArray()
    }

    private fun bufferToByteArray(buffer: ByteBuffer): ByteArray {
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }

    private fun rotateBitmapIfNeeded(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        val matrix = android.graphics.Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
