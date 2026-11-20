package com.ogenrwotdaniel.wakeproof.data.remote

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Opens an EncryptedSharedPreferences file, discarding it if it can no longer be decrypted.
 *
 * Uninstalling with `pm uninstall -k` keeps the app's files but Android deletes its Keystore
 * keys, so after the reinstall the old file's keyset is sealed under a master key that no
 * longer exists and every open throws `AEADBadTagException`. The secrets in it are lost for
 * good either way; without this the Settings screen crashed on every visit (2026-11-20).
 */
@Suppress("DEPRECATION")
fun openSecurePrefs(context: Context, name: String): SharedPreferences {
    fun open(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            name,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    return try {
        open()
    } catch (e: Exception) {
        Log.w("SecurePrefs", "$name cannot be decrypted; starting it empty", e)
        context.deleteSharedPreferences(name)
        open()
    }
}
