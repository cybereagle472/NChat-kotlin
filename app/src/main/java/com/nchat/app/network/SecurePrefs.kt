package com.nchat.app.network

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SharedPreferences whose values are encrypted with a key that never leaves the Android
 * Keystore.
 *
 * NChat's tokens are bearer credentials: anyone who can read the app's private storage (a
 * rooted device, a device backup, an adb pull) could otherwise impersonate the account until
 * the refresh token expires. The key material is generated inside the Keystore and is not
 * exportable, so a copied value is useless on another device.
 *
 * AES-256-GCM, a fresh 12-byte IV per write, `v1:` prefix. Values written by older builds
 * (no prefix) are read as plain text and re-encrypted on the next write, so upgrading does
 * not sign anyone out.
 *
 * If the Keystore is unavailable (a few emulators, some heavily modified ROMs) the store
 * degrades to plain text rather than locking the user out of their own account — the caller
 * sees the same API either way.
 */
class SecurePrefs(context: Context, name: String) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    private val key: SecretKey? by lazy { loadOrCreateKey(name) }

    fun getString(key: String, default: String? = null): String? {
        val raw = prefs.getString(key, null) ?: return default
        if (!raw.startsWith(PREFIX)) return raw // written before encryption existed
        val plain = decrypt(raw.removePrefix(PREFIX)) ?: return default
        return plain
    }

    fun putString(key: String, value: String?) {
        val editor = prefs.edit()
        if (value == null) {
            editor.remove(key).apply()
            return
        }
        val sealed = encrypt(value)
        if (sealed == null) {
            editor.putString(key, value).apply()
        } else {
            editor.putString(key, PREFIX + sealed).apply()
        }
    }

    fun getBoolean(key: String, default: Boolean = false): Boolean =
        getString(key)?.toBooleanStrictOrNull() ?: default

    fun putBoolean(key: String, value: Boolean) = putString(key, value.toString())

    fun remove(vararg keys: String) {
        val editor = prefs.edit()
        keys.forEach(editor::remove)
        editor.apply()
    }

    /** True when values really are encrypted (Keystore available). */
    val isEncrypted: Boolean get() = key != null

    private fun encrypt(plain: String): String? {
        val secret = key ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secret)
            val iv = cipher.iv
            val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + sealed, Base64.NO_WRAP)
        }.getOrNull()
    }

    private fun decrypt(payload: String): String? {
        val secret = key ?: return null
        return runCatching {
            val bytes = Base64.decode(payload, Base64.NO_WRAP)
            if (bytes.size <= IV_BYTES) return null
            val iv = bytes.copyOfRange(0, IV_BYTES)
            val sealed = bytes.copyOfRange(IV_BYTES, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(sealed), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun loadOrCreateKey(alias: String): SecretKey? = runCatching {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: generateKey(alias)
    }.getOrNull()

    private fun generateKey(alias: String): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Staying usable after an unlock reboot keeps the session intact; the key is
                // still bound to this device and this app's UID.
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val PREFIX = "v1:"
    }
}
