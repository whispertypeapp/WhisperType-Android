package com.whispertype.android.data.secrets

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Source of the AES key used to encrypt a secret blob. The production
 * implementation keeps the key inside the Android Keystore (never leaves
 * secure hardware); tests inject a fake key so no device is required.
 */
interface KeystoreKeyStore {
    val alias: String

    /** Returns the existing key for [alias] or creates it. May throw. */
    fun getOrCreateKey(): SecretKey

    fun exists(): Boolean

    fun deleteKey()
}

/**
 * Android Keystore-backed key source. minSdk 34 supports AES/GCM/NoPadding
 * keys with no user-auth requirement.
 */
class AndroidKeystoreKeyStore(override val alias: String) : KeystoreKeyStore {

    private fun androidKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun getOrCreateKey(): SecretKey {
        val store = androidKeyStore()
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    override fun exists(): Boolean =
        try {
            (androidKeyStore().getKey(alias, null) as? SecretKey) != null
        } catch (_: Throwable) {
            false
        }

    override fun deleteKey() {
        try {
            val store = androidKeyStore()
            if (store.containsAlias(alias)) {
                store.deleteEntry(alias)
            }
        } catch (_: Throwable) {
            // Best-effort; deletion failure is surfaced by the caller's blob delete.
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}