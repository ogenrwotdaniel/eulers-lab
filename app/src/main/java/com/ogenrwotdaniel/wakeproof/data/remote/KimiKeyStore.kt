package com.ogenrwotdaniel.wakeproof.data.remote

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Read/write access to the user's Gemini API key. */
interface ApiKeyStore {
    fun getKey(): String?
    fun setKey(value: String)
    fun clear()
    fun hasKey(): Boolean = getKey() != null
}

/**
 * Stores the Gemini key in EncryptedSharedPreferences — never in code, never in
 * regular (backup-able) prefs. Keeps the preference file name (`kimi_secure_prefs`)
 * and entry name (`api_key_gemini`) so existing Gemini keys on device keep
 * working without re-entry.
 */
@Singleton
class GeminiKeyStore @Inject constructor(
    @ApplicationContext private val context: Context
) : ApiKeyStore {

    private val prefs by lazy { openSecurePrefs(context, "kimi_secure_prefs") }

    override fun getKey(): String? =
        prefs.getString(KEY_GEMINI, null)?.takeIf { it.isNotBlank() }

    override fun setKey(value: String) {
        prefs.edit().putString(KEY_GEMINI, value.trim()).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_GEMINI).apply()
    }

    private companion object {
        const val KEY_GEMINI = "api_key_gemini"
    }
}

typealias KimiKeyStore = GeminiKeyStore
