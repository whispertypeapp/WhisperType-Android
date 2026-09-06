package com.whispertype.android.data.secrets

/**
 * Access to the Gemini API key. Implementations must store the key in
 * Keystore-backed encrypted app-private storage. This interface exposes only a
 * presence boolean and a one-shot provider; it never returns the key to the
 * accessibility layer.
 */
interface KeyProvider {
    fun hasKey(): Boolean
    suspend fun provideKey(): String?
    suspend fun storeKey(key: String): Boolean
    suspend fun deleteKey(): Boolean
}