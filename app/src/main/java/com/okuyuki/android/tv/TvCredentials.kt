package com.okuyuki.android.tv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * What the phone keeps about each TV: its name and last address, the fingerprint of its certificate,
 * and the client key the TV issued. The client key lets this phone launch apps on the TV, so it is
 * encrypted with a key that never leaves the Android Keystore.
 */
class TvCredentials(context: Context) {
    private val preferences = context.getSharedPreferences("tvs", Context.MODE_PRIVATE)

    @Serializable private data class Known(val id: String, val name: String, val address: String)

    fun known(): List<FoundTv> = preferences.all.filterKeys { it.startsWith("tv:") }.values.mapNotNull { value ->
        runCatching { Json.decodeFromString<Known>(value as String) }.getOrNull()?.let { FoundTv(it.id, it.name, it.address) }
    }.sortedBy { it.name }

    fun remember(tv: FoundTv) = preferences.edit { putString("tv:${tv.id}", Json.encodeToString(Known(tv.id, tv.name, tv.address))) }

    fun forget(id: String) = preferences.edit { remove("tv:$id"); remove("pin:$id"); remove("key:$id") }

    fun pin(id: String): String? = preferences.getString("pin:$id", null)

    fun savePin(id: String, fingerprint: String) = preferences.edit { putString("pin:$id", fingerprint) }

    fun clientKey(id: String): String? = preferences.getString("key:$id", null)?.let { runCatching { decrypt(it) }.getOrNull() }

    fun saveClientKey(id: String, key: String) = preferences.edit { putString("key:$id", encrypt(key)) }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return generator.generateKey()
    }

    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(text.toByteArray()))
    }

    private fun decrypt(text: String): String {
        val bytes = Base64.getDecoder().decode(text)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
        }
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "okuyuki-tv-client-keys"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
