package com.xs.reader.tts

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureKeyStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "tts_secrets",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse {
            context.getSharedPreferences("tts_secrets_plain", Context.MODE_PRIVATE)
        }
    }

    fun put(engineId: String, key: String, value: String?) {
        prefs.edit().apply {
            if (value.isNullOrBlank()) remove("${engineId}__$key")
            else putString("${engineId}__$key", value)
        }.apply()
    }

    fun get(engineId: String, key: String): String? =
        prefs.getString("${engineId}__$key", null)
}
