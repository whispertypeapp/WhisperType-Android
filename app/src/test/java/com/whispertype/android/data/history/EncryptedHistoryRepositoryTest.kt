package com.whispertype.android.data.history

import com.whispertype.android.data.secrets.AesGcmCipher
import com.whispertype.android.data.secrets.BlobStore
import com.whispertype.android.data.secrets.JavaxAesGcmCipher
import com.whispertype.android.data.secrets.KeystoreKeyStore
import java.nio.charset.StandardCharsets
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/** JVM tests for [EncryptedHistoryRepository] using a fake key and in-memory blob. */
class EncryptedHistoryRepositoryTest {

    private class FakeKeystoreKeyStore : KeystoreKeyStore {
        val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        override val alias: String = "test_key"
        override fun getOrCreateKey(): SecretKey = key
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

    private class FailingCipher : AesGcmCipher {
        override fun encrypt(key: SecretKey, plaintext: ByteArray): ByteArray =
            throw IllegalStateException("cipher unavailable")

        override fun decrypt(key: SecretKey, blob: ByteArray): ByteArray? = null
    }

    private class Harness(
        var retentionDays: Int = 365,
        var nowMillis: Long = 0L,
        val blobStore: InMemoryBlobStore = InMemoryBlobStore(),
        cipher: AesGcmCipher = JavaxAesGcmCipher(),
        maxEntries: Int = 500,
    ) {
        val keystore = FakeKeystoreKeyStore()

        val repo =
            EncryptedHistoryRepository(
                keystore = keystore,
                cipher = cipher,
                blobStore = blobStore,
                retentionDays = { retentionDays },
                maxEntries = maxEntries,
                nowMillis = { nowMillis },
            )
    }

    private fun entry(
        id: String,
        timestampMillis: Long,
        text: String = "transcript text",
    ): HistoryRepository.HistoryEntry =
        HistoryRepository.HistoryEntry(
            id = id,
            timestampMillis = timestampMillis,
            text = text,
            language = "en",
            charCount = text.length,
            outcome = "ok",
        )

    @Test
    fun `record then events returns newest-first ordering`() = runTest {
        val h = Harness()
        h.nowMillis = 1_000
        assertTrue(h.repo.record(entry("oldest", 1_000)))
        h.nowMillis = 2_000
        assertTrue(h.repo.record(entry("middle", 2_000)))
        h.nowMillis = 3_000
        assertTrue(h.repo.record(entry("newest", 3_000)))

        assertEquals(listOf("newest", "middle", "oldest"), h.repo.events().first().map { it.id })
    }

    @Test
    fun `record overrides charCount with text length`() = runTest {
        val h = Harness()
        h.repo.record(
            entry("a", 1_000, text = "hello").copy(charCount = 99),
        )

        val stored = h.repo.events().first().single()
        assertEquals(5, stored.charCount)
    }

    @Test
    fun `record prunes entries older than retention days`() = runTest {
        val h = Harness(retentionDays = 1)
        h.nowMillis = 1_000
        assertTrue(h.repo.record(entry("old", 1_000)))

        h.nowMillis = 1_000 + 86_400_000L + 1L
        assertTrue(h.repo.record(entry("fresh", h.nowMillis)))

        assertEquals(listOf("fresh"), h.repo.events().first().map { it.id })
    }

    @Test
    fun `record trims to maxEntries`() = runTest {
        val h = Harness(maxEntries = 3)
        for (i in 1..5) {
            h.nowMillis = i * 1_000L
            assertTrue(h.repo.record(entry("id-$i", h.nowMillis)))
        }

        assertEquals(listOf("id-5", "id-4", "id-3"), h.repo.events().first().map { it.id })
    }

    @Test
    fun `delete removes only the matching id`() = runTest {
        val h = Harness()
        listOf("a", "b", "c").forEachIndexed { index, id ->
            h.nowMillis = index * 1_000L
            h.repo.record(entry(id, h.nowMillis))
        }

        assertTrue(h.repo.delete("b"))

        assertEquals(listOf("c", "a"), h.repo.events().first().map { it.id })
    }

    @Test
    fun `delete with no stored blob returns true`() = runTest {
        assertTrue(Harness().repo.delete("missing"))
    }

    @Test
    fun `clear empties stored entries and removes the blob`() = runTest {
        val h = Harness()
        h.repo.record(entry("a", 1_000))

        assertTrue(h.repo.clear())

        assertTrue(h.repo.events().first().isEmpty())
        assertNull(h.blobStore.bytes)
    }

    @Test
    fun `blob on disk is encrypted not plaintext`() = runTest {
        val h = Harness()
        h.repo.record(entry("a", 1_000, text = "top secret transcript"))

        val blob = h.blobStore.bytes!!
        val asText = String(blob, StandardCharsets.UTF_8)
        assertFalse(asText.contains("top secret transcript"))
        assertFalse(asText.contains("{\"entries\""))

        val decrypted =
            String(
                JavaxAesGcmCipher().decrypt(h.keystore.key, blob)!!,
                StandardCharsets.UTF_8,
            )
        assertTrue(decrypted.contains("top secret transcript"))
    }

    @Test
    fun `corrupt blob yields empty events and record recovers`() = runTest {
        val h = Harness()
        h.blobStore.bytes = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1)

        assertTrue(h.repo.events().first().isEmpty())

        assertTrue(h.repo.record(entry("a", 1_000)))
        assertEquals(listOf("a"), h.repo.events().first().map { it.id })
    }

    @Test
    fun `record returns false and events stays empty when cipher fails`() = runTest {
        val h = Harness(cipher = FailingCipher())
        h.blobStore.bytes = byteArrayOf(1, 2, 3)

        assertFalse(h.repo.record(entry("a", 1_000)))
        assertTrue(h.repo.events().first().isEmpty())
    }
}
