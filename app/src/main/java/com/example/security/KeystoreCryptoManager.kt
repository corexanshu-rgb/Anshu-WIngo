package com.example.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

fun interface CryptoKeyProvider {
    fun getOrCreateSecretKey(): SecretKey
}

object AndroidKeyStoreKeyProvider : CryptoKeyProvider {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "anshu_wingo_session_aes_key"

    override fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}

/**
 * Hardware-backed Android Keystore AES-256-GCM helper for persisting app-owned session metadata
 * and cooperative website-granted API session tokens.
 * Never used to store website passwords or OTPs.
 */
class KeystoreCryptoManager(
    context: Context,
    private val keyProvider: CryptoKeyProvider = AndroidKeyStoreKeyProvider,
    prefsName: String = PREFS_NAME
) {

    private val prefs = context.applicationContext.getSharedPreferences(
        prefsName,
        Context.MODE_PRIVATE
    )

    fun putEncryptedString(key: String, value: String) {
        if (value.isBlank()) {
            remove(key)
            return
        }
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, keyProvider.getOrCreateSecretKey())
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val combined = Base64.encodeToString(iv + cipherBytes, Base64.NO_WRAP)
            prefs.edit().putString(key, combined).apply()
        }
    }

    fun getDecryptedString(key: String): String? {
        val encoded = prefs.getString(key, null) ?: return null
        return runCatching {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size <= GCM_IV_LENGTH) return null
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val cipherBytes = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                keyProvider.getOrCreateSecretKey(),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            )
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        }.getOrNull()
    }

    fun hasEncryptedEntry(key: String): Boolean = prefs.contains(key)

    fun getRawStoredValueForAudit(key: String): String? = prefs.getString(key, null)

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "anshu_wingo_secure_prefs"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
