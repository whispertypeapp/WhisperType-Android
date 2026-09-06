package com.whispertype.android.data.secrets

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [SecretStore]'s deterministic client recovery path when stored
 * ciphertext is corrupt or tampered with. A real AES-GCM cipher plus injected
 * fake Keystore and in-memory blob store run without a device; only the
 * production Android Keystore wiring ([AndroidKeystoreKeyStore],
 * [KeystoreKeyProvider], [SystemSensitiveClipboard]) must be device-verified.
 */
class ClientInvalidRecoveryTest {

    private class FakeKeystoreKeyStore : KeystoreKeyStore {
        private val generated = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        override val alias: String = "test_key"
        override fun getOrCreateKey(): SecretKey = generated
        override fun exists(): Boolean = true
        override fun deleteKey() = Unit
    }

    private class InMemoryBlobStore : BlobStore {
        var bytes: ByteArray? = null

        override fun read(): ByteArray? = bytes
        override fun write(data: ByteArray): Boolean {
            bytes = data.copyOf()
            return true
        }

        override fun delete(): Boolean {
            bytes = null
            return true
        }
    }

    private fun newStore(
        blobStore: InMemoryBlobStore = InMemoryBlobStore(),
    ): SecretStore =
        SecretStore(
            keystore = FakeKeystoreKeyStore(),
            cipher = JavaxAesGcmCipher(),
            blobStore = blobStore,
        )

    @Test
    fun `store then provide round trips the plaintext`() = runTest {
        val store = newStore()

        assertTrue(store.storeKey("gemini-api-key"))
        assertEquals(
            "gemini-api-key",
            store.provideKey(),
        )
    }

    @Test
    fun `empty key is rejected`() = runTest {
        assertFalse(newStore().storeKey(""))
    }

    @Test
    fun `provide with no stored blob returns null`() = runTest {
        assertNull(newStore().provideKey())
    }

    @Test
    fun `tampered ciphertext returns null deletes the blob and does not throw`() = runTest {
        val blobStore = InMemoryBlobStore()
        val store = newStore(blobStore)
        assertTrue(store.storeKey("gemini-api-key"))
        val tampered = blobStore.bytes!!.copyOf()
        tampered[tampered.lastIndex] = (tampered[tampered.lastIndex].toInt() xor 0x01).toByte()
        blobStore.bytes = tampered

        assertNull(store.provideKey())
        assertNull(blobStore.bytes)
        assertFalse(store.hasKey())
    }

    @Test
    fun `truncated ciphertext returns null deletes the blob and does not throw`() = runTest {
        val blobStore = InMemoryBlobStore()
        val store = newStore(blobStore)
        assertTrue(store.storeKey("gemini-api-key"))
        blobStore.bytes = blobStore.bytes!!.copyOfRange(0, 5)

        assertNull(store.provideKey())
        assertNull(blobStore.bytes)
        assertFalse(store.hasKey())
    }

    @Test
    fun `store recovers after a corrupt blob is removed`() = runTest {
        val blobStore = InMemoryBlobStore()
        val store = newStore(blobStore)
        assertTrue(store.storeKey("first-key"))
        blobStore.bytes = ByteArray(JavaxAesGcmCipher.IV_LENGTH)
        assertNull(store.provideKey())

        assertTrue(store.storeKey("second-key"))
        assertEquals("second-key", store.provideKey())
    }

    @Test
    fun `deleteKey removes the blob`() = runTest {
        val blobStore = InMemoryBlobStore()
        val store = newStore(blobStore)
        store.storeKey("gemini-api-key")

        assertTrue(store.deleteKey())
        assertNull(blobStore.bytes)
        assertFalse(store.hasKey())
    }

    @Test
    fun `provideKey never throws for a throwing keystore`() = runTest {
        val failingKeystore =
            object : KeystoreKeyStore {
                override val alias: String = "failing"
                override fun getOrCreateKey(): SecretKey = throw IllegalStateException("keystore unavailable")
                override fun exists(): Boolean = false
                override fun deleteKey() = Unit
            }
        val blobStore = InMemoryBlobStore().apply { bytes = ByteArray(16) }
        val store =
            SecretStore(
                keystore = failingKeystore,
                cipher = JavaxAesGcmCipher(),
                blobStore = blobStore,
            )

        assertNull(store.provideKey())
        assertNull(blobStore.bytes)
    }
}