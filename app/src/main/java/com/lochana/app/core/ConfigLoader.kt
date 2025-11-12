package com.lochana.app.core

import android.content.Context
import android.util.Log
import java.io.InputStream
import java.util.Properties

/**
 * Loads configuration from properties files
 *
 * First tries to load from assets/config.properties
 * Falls back to checking SharedPreferences if config file not found
 */
object ConfigLoader {
    private const val TAG = "ConfigLoader"
    private const val CONFIG_FILE = "config.properties"
    private const val OPENAI_API_KEY = "OPENAI_API_KEY"

    /**
     * Loads OpenAI API key from config file in assets
     * @param context Application context
     * @return API key if found, null otherwise
     */
    fun loadOpenAIKey(context: Context): String? {
        return try {
            val inputStream: InputStream = context.assets.open(CONFIG_FILE)
            val properties = Properties()
            properties.load(inputStream)
            val apiKey = properties.getProperty(OPENAI_API_KEY)

            if (apiKey != null && apiKey.isNotBlank() && apiKey != "your_api_key_here") {
                Log.d(TAG, "✅ API key loaded from config file")
                apiKey.trim()
            } else {
                Log.d(TAG, "⚠️ Config file found but API key not set")
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "📝 Config file not found in assets - will use SharedPreferences")
            null
        }
    }
}
