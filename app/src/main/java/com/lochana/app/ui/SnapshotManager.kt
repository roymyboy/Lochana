package com.lochana.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

class SnapshotManager(
    private val context: Context,
    private val maxDimension: Int,
    private val jpegQuality: Int,
    private val maxFiles: Int
) {

    companion object {
        private const val TAG = "SnapshotManager"
        private const val SNAPSHOT_DIR = "chat_snapshots"
    }

    private val snapshotPaths = mutableListOf<String>()

    fun preparePreview(bitmap: Bitmap?): String? {
        return try {
            if (bitmap == null) return null
            val path = saveSnapshot(bitmap)
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
            path
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "❌ Out of memory while preparing preview")
            null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error preparing preview: ${e.message}")
            null
        }
    }

    fun loadSnapshot(path: String): Bitmap? {
        return try {
            BitmapFactory.decodeFile(path)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "❌ Out of memory loading snapshot bitmap")
            null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error loading snapshot bitmap: ${e.message}")
            null
        }
    }

    fun clearSnapshots() {
        snapshotPaths.forEach { path ->
            try {
                File(path).takeIf { it.exists() }?.delete()
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error deleting snapshot $path: ${e.message}")
            }
        }
        snapshotPaths.clear()
    }

    private fun saveSnapshot(bitmap: Bitmap): String? {
        return try {
            val snapshotDir = File(context.cacheDir, SNAPSHOT_DIR).apply {
                if (!exists()) mkdirs()
            }

            val scaledBitmap = scaleBitmapForSnapshot(bitmap)
            val file = File(snapshotDir, "snapshot_${System.currentTimeMillis()}.jpg")

            FileOutputStream(file).use { output ->
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, output)
            }

            if (scaledBitmap != bitmap && !scaledBitmap.isRecycled) {
                scaledBitmap.recycle()
            }

            snapshotPaths.add(file.absolutePath)
            trimSnapshotCache()
            file.absolutePath
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "❌ Out of memory saving snapshot")
            null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error saving snapshot: ${e.message}")
            null
        }
    }

    private fun scaleBitmapForSnapshot(bitmap: Bitmap): Bitmap {
        return try {
            val largestEdge = max(bitmap.width, bitmap.height)
            if (largestEdge <= maxDimension) {
                bitmap
            } else {
                val scale = maxDimension.toFloat() / largestEdge.toFloat()
                val targetWidth = (bitmap.width * scale).roundToInt()
                val targetHeight = (bitmap.height * scale).roundToInt()
                Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error scaling snapshot bitmap: ${e.message}")
            bitmap
        }
    }

    private fun trimSnapshotCache() {
        while (snapshotPaths.size > maxFiles) {
            val removed = snapshotPaths.removeAt(0)
            try {
                File(removed).takeIf { it.exists() }?.delete()
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Unable to delete old snapshot: ${e.message}")
            }
        }
    }
}

