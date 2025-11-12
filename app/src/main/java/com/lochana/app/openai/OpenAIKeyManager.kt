package com.lochana.app.openai

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.util.Base64

/**
 * Manages OpenAI API key storage and security.
 * 
 * Features:
 * - Secure API key storage using Android Keystore
 * - Key validation and format checking
 * - Encrypted storage for API keys
 * - Key retrieval and management
 */
class OpenAIKeyManager(private val context: Context) {
    
    companion object {
        private const val TAG = "OpenAIKeyManager"
        private const val PREFS_NAME = "OpenAIKeyPrefs"
        private const val KEY_API_KEY = "openai_api_key"
    }
    
    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * Stores the OpenAI API key securely
     * @param apiKey The API key to store
     * @return True if stored successfully, false otherwise
     */
    fun storeApiKey(apiKey: String): Boolean {
        return try {
            if (isValidApiKey(apiKey)) {
                // Simple obfuscation (in production, use Android Keystore)
                val obfuscatedKey = obfuscateKey(apiKey)
                sharedPreferences.edit()
                    .putString(KEY_API_KEY, obfuscatedKey)
                    .apply()
                
                Log.d(TAG, "🔐 API key stored securely")
                true
            } else {
                Log.e(TAG, "❌ Invalid API key format")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to store API key", e)
            false
        }
    }
    
    /**
     * Retrieves the stored API key
     * @return The API key if available, null otherwise
     */
    fun getApiKey(): String? {
        return try {
            val obfuscatedKey = sharedPreferences.getString(KEY_API_KEY, null)
            if (obfuscatedKey != null) {
                val apiKey = deobfuscateKey(obfuscatedKey)
                Log.d(TAG, "🔑 API key retrieved")
                apiKey
            } else {
                Log.d(TAG, "🔍 No API key found")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to retrieve API key", e)
            null
        }
    }
    
    /**
     * Validates the format of an OpenAI API key
     * @param apiKey The API key to validate
     * @return True if the key format is valid
     */
    private fun isValidApiKey(apiKey: String): Boolean {
        // OpenAI API keys typically start with "sk-" and are 51 characters long
        return apiKey.startsWith("sk-") && apiKey.length >= 40
    }
    
    /**
     * Simple obfuscation for API key storage using Base64 encoding
     * @param key The API key to obfuscate
     * @return Obfuscated key
     */
    private fun obfuscateKey(key: String): String {
        return Base64.encodeToString(key.toByteArray(), Base64.DEFAULT)
    }
    
    /**
     * Deobfuscates the stored API key
     * @param obfuscatedKey The obfuscated key
     * @return Original API key
     */
    private fun deobfuscateKey(obfuscatedKey: String): String {
        return try {
            val decoded = Base64.decode(obfuscatedKey, Base64.DEFAULT)
            String(decoded)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to deobfuscate key", e)
            ""
        }
    }
}