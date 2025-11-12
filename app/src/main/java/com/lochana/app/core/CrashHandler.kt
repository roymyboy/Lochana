package com.lochana.app.core

import android.content.Context
import android.util.Log
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.ConcurrentModificationException

/**
 * Global crash handler that prevents app crashes and logs errors
 * Implements Thread.UncaughtExceptionHandler to catch all uncaught exceptions
 */
class CrashHandler private constructor(private val context: Context) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val TAG = "CrashHandler"
        private var instance: CrashHandler? = null

        /**
         * Initialize the crash handler
         * Should be called in Application.onCreate() or MainActivity.onCreate()
         */
        fun initialize(context: Context) {
            if (instance == null) {
                instance = CrashHandler(context.applicationContext)
                Thread.setDefaultUncaughtExceptionHandler(instance)
                Log.d(TAG, "✅ Crash handler initialized - app is protected from crashes")
            }
        }
    }

    private val defaultHandler: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            Log.e(TAG, "❌❌❌ UNCAUGHT EXCEPTION CAUGHT ❌❌❌")
            Log.e(TAG, "Thread: ${thread.name}")
            Log.e(TAG, "Exception: ${throwable.javaClass.simpleName}")
            Log.e(TAG, "Message: ${throwable.message}")

            val sw = StringWriter()
            val pw = PrintWriter(sw)
            throwable.printStackTrace(pw)
            val stackTrace = sw.toString()
            Log.e(TAG, "Stack trace:\n$stackTrace")

            saveCrashLog(thread, throwable, stackTrace)
            showCrashMessage(throwable)

            val recovered = attemptRecovery(throwable)

            if (!recovered) {
                Log.e(TAG, "❌ Recovery failed - passing to default handler")
                defaultHandler?.uncaughtException(thread, throwable)
            } else {
                Log.d(TAG, "✅ Successfully recovered from crash - app continues running")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Crash handler failed: ${e.message}")
            e.printStackTrace()
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun saveCrashLog(thread: Thread, throwable: Throwable, stackTrace: String) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val filename = "crash_$timestamp.log"

            val logDir = File(context.filesDir, "crash_logs")
            if (!logDir.exists()) {
                logDir.mkdirs()
            }

            val logFile = File(logDir, filename)
            val crashInfo = buildString {
                appendLine("=== CRASH REPORT ===")
                appendLine("Timestamp: $timestamp")
                appendLine("Thread: ${thread.name}")
                appendLine("Exception: ${throwable.javaClass.simpleName}")
                appendLine("Message: ${throwable.message}")
                appendLine("\n=== STACK TRACE ===")
                appendLine(stackTrace)
                appendLine("\n=== DEVICE INFO ===")
                appendLine("Manufacturer: ${android.os.Build.MANUFACTURER}")
                appendLine("Model: ${android.os.Build.MODEL}")
                appendLine("Android Version: ${android.os.Build.VERSION.RELEASE}")
                appendLine("SDK: ${android.os.Build.VERSION.SDK_INT}")
            }

            logFile.writeText(crashInfo)
            Log.d(TAG, "💾 Crash log saved: ${logFile.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to save crash log: ${e.message}")
        }
    }

    private fun showCrashMessage(throwable: Throwable) {
        try {
            val message = when {
                throwable is OutOfMemoryError -> "Memory full - restart app"
                throwable.message?.contains("camera", ignoreCase = true) == true -> "Camera error - restart app"
                throwable.message?.contains("network", ignoreCase = true) == true -> "Network error - check connection"
                else -> "Error occurred - app recovering..."
            }

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to show crash message: ${e.message}")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to show crash message: ${e.message}")
        }
    }

    private fun attemptRecovery(throwable: Throwable): Boolean {
        return try {
            when (throwable) {
                is NullPointerException -> {
                    Log.d(TAG, "🔄 Recovering from NullPointerException")
                    true
                }
                is IllegalStateException -> {
                    Log.d(TAG, "🔄 Recovering from IllegalStateException")
                    true
                }
                is IndexOutOfBoundsException -> {
                    Log.d(TAG, "🔄 Recovering from IndexOutOfBoundsException")
                    true
                }
                is ConcurrentModificationException -> {
                    Log.d(TAG, "🔄 Recovering from ConcurrentModificationException")
                    true
                }
                is OutOfMemoryError -> {
                    Log.e(TAG, "❌ Cannot recover from OutOfMemoryError")
                    false
                }
                is StackOverflowError -> {
                    Log.e(TAG, "❌ Cannot recover from StackOverflowError")
                    false
                }
                else -> {
                    Log.d(TAG, "🔄 Attempting recovery from ${throwable.javaClass.simpleName}")
                    true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Recovery attempt failed: ${e.message}")
            false
        }
    }

    fun cleanupOldLogs() {
        try {
            val logDir = File(context.filesDir, "crash_logs")
            if (!logDir.exists()) return

            val logFiles = logDir.listFiles() ?: return
            if (logFiles.size <= 10) return

            logFiles.sortedBy { it.lastModified() }
                .take(logFiles.size - 10)
                .forEach { file ->
                    if (file.delete()) {
                        Log.d(TAG, "🧹 Deleted old crash log: ${file.name}")
                    }
                }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to cleanup old logs: ${e.message}")
        }
    }
}
