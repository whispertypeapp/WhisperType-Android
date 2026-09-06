package com.whispertype.android.data.secrets

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM encryption/decryption primitive. Encryption prepends a fresh random
 * 12-byte IV to the ciphertext so [decrypt] can recover it without prior
 * state. [decrypt] never throws for corrupt or tampered input; it returns null
 * so callers have a deterministic recovery path.
 *
 * The implementation uses only JVM crypto (javax.crypto) so it is unit-testable
 * on the JVM with a generated key rather than an Android Keystore.
 */
interface AesGcmCipher {
    /** @return concatenation of the random IV and the GCM ciphertext+tag. */
    fun encrypt(key: SecretKey, plaintext: ByteArray): ByteArray

    /** @return plaintext, or null when the blob is malformed/tampered/corrupt. */
    fun decrypt(key: SecretKey, blob: ByteArray): ByteArray?
}

class JavaxAesGcmCipher : AesGcmCipher {

    override fun encrypt(key: SecretKey, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    override fun decrypt(key: SecretKey, blob: ByteArray): ByteArray? =
        try {
            if (blob.size <= IV_LENGTH) {
                null
            } else {
                val iv = blob.copyOfRange(0, IV_LENGTH)
                val ciphertext = blob.copyOfRange(IV_LENGTH, blob.size)
                val cipher = Cipher.getInstance(TRANSFORM)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                cipher.doFinal(ciphertext)
            }
        } catch (_: Throwable) {
            null
        }

    companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}