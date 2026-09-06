package com.whispertype.android.data.secrets

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JVM tests for the AES-GCM primitive using a generated key instead of an
 * Android Keystore, so they run without a device.
 */
class SecretCipherTest {

    private val cipher = JavaxAesGcmCipher()

    private val key: SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `encrypt then decrypt round trips`() {
        val plaintext = "The quick brown fox".toByteArray()
        val blob = cipher.encrypt(key, plaintext)
        assertContentEquals(plaintext, cipher.decrypt(key, blob))
    }

    @Test
    fun `random IV differs per encryption of identical plaintext`() {
        val plaintext = "same input".toByteArray()
        val first = cipher.encrypt(key, plaintext)
        val second = cipher.encrypt(key, plaintext)
        assertNotEquals(first.toList(), second.toList())
        val firstIv = first.copyOfRange(0, JavaxAesGcmCipher.IV_LENGTH)
        val secondIv = second.copyOfRange(0, JavaxAesGcmCipher.IV_LENGTH)
        assertFalse(firstIv.contentEquals(secondIv))
    }

    @Test
    fun `tampered ciphertext decrypts to null without throwing`() {
        val plaintext = "attack at dawn".toByteArray()
        val blob = cipher.encrypt(key, plaintext)
        val tampered = blob.copyOf()
        tampered[tampered.size / 2] = (tampered[tampered.size / 2] + 1).toByte()
        assertNull(cipher.decrypt(key, tampered))
    }

    @Test
    fun `truncated blob decrypts to null without throwing`() {
        val blob = cipher.encrypt(key, "truncate me".toByteArray())
        assertNull(cipher.decrypt(key, blob.copyOfRange(0, blob.size - 4)))
    }

    @Test
    fun `iv-only and short blobs decrypt to null without throwing`() {
        assertNull(cipher.decrypt(key, ByteArray(JavaxAesGcmCipher.IV_LENGTH)))
        assertNull(cipher.decrypt(key, ByteArray(3)))
        assertNull(cipher.decrypt(key, ByteArray(0)))
    }

    @Test
    fun `decrypt with wrong key returns null`() {
        val blob = cipher.encrypt(key, "whose key?".toByteArray())
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(cipher.decrypt(otherKey, blob))
    }

    @Test
    fun `non-ASCII plaintext round trips`() {
        val plaintext = "héllo-キー-🔑-हिंगलिश".toByteArray()
        val blob = cipher.encrypt(key, plaintext)
        assertContentEquals(plaintext, cipher.decrypt(key, blob))
    }

    @Test
    fun `empty plaintext round trips`() {
        val blob = cipher.encrypt(key, ByteArray(0))
        assertContentEquals(ByteArray(0), cipher.decrypt(key, blob))
    }

    @Test
    fun `encrypt output carries iv prefix`() {
        val blob = cipher.encrypt(key, "x".toByteArray())
        assertTrue(blob.size >= JavaxAesGcmCipher.IV_LENGTH + JavaxAesGcmCipher.TAG_BITS / 8)
    }

    @Test
    fun `decrypt is deterministic for valid blob`() {
        val plaintext = "determinism".toByteArray()
        val blob = cipher.encrypt(key, plaintext)
        assertEquals(
            String(plaintext),
            String(cipher.decrypt(key, blob)!!),
        )
        assertContentEquals(plaintext, cipher.decrypt(key, blob))
    }
}