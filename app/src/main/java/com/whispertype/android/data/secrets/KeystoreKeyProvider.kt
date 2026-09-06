package com.whispertype.android.data.secrets

import android.content.Context

/**
 * [KeyProvider] implementation that stores the Gemini API key encrypted with an
 * Android Keystore key, in an app-private file. The plaintext key is only
 * materialized inside [provideKey] and is never logged.
 */
class KeystoreKeyProvider(context: Context) : KeyProvider {

    private val store: SecretStore = SecretStore(
        keystore = AndroidKeystoreKeyStore(KEY_ALIAS),
        cipher = JavaxAesGcmCipher(),
        blobStore = FileBlobStore(context, FILE_NAME),
    )

    override fun hasKey(): Boolean = store.hasKey()

    override suspend fun provideKey(): String? = store.provideKey()

    override suspend fun storeKey(key: String): Boolean = store.storeKey(key)

    override suspend fun deleteKey(): Boolean = store.deleteKey()

    private companion object {
        const val KEY_ALIAS = "whispertype_gemini_key"
        const val FILE_NAME = "gemini_api_key.bin"
    }
}