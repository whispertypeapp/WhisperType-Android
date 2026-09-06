package com.whispertype.android.data.history

import com.whispertype.android.data.secrets.AesGcmCipher
import com.whispertype.android.data.secrets.BlobStore
import com.whispertype.android.data.secrets.KeystoreKeyStore
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [HistoryRepository] that stores the whole entry list as one AES-GCM encrypted
 * JSON blob. Corrupt blobs decode to an empty list and are overwritten on the
 * next write, so storage recovers deterministically.
 */
class EncryptedHistoryRepository(
    private val keystore: KeystoreKeyStore,
    private val cipher: AesGcmCipher,
    private val blobStore: BlobStore,
    private val retentionDays: () -> Int,
    private val maxEntries: Int = 500,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val json: Json = Json { ignoreUnknownKeys = true },
) : HistoryRepository {

    private val lock = Any()

    override fun events(): Flow<List<HistoryRepository.HistoryEntry>> =
        flow { emit(currentEntries()) }

    override suspend fun record(entry: HistoryRepository.HistoryEntry): Boolean =
        // Blob read + AES-GCM decrypt + JSON re-encode + re-encrypt + write must
        // never block the caller's thread (the main dispatcher from
        // onSessionFinished); hoist the whole read-modify-write to IO (0.6.0).
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                try {
                    val id = entry.id.ifBlank { idFactory() }
                    val resolved = entry.copy(id = id, charCount = entry.text.length)
                    val current = readEntries() ?: emptyList()
                    val merged = prune(listOf(resolved) + current)
                    write(merged)
                } catch (_: Throwable) {
                    false
                }
            }
        }

    override suspend fun delete(id: String): Boolean =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                try {
                    val current = readEntries() ?: return@synchronized true
                    write(current.filterNot { it.id == id })
                } catch (_: Throwable) {
                    false
                }
            }
        }

    override suspend fun clear(): Boolean =
        withContext(Dispatchers.IO) {
            try {
                blobStore.delete()
            } catch (_: Throwable) {
                false
            }
        }

    private fun currentEntries(): List<HistoryRepository.HistoryEntry> =
        prune(readEntries() ?: emptyList())
            .sortedByDescending { it.timestampMillis }

    /** Decrypted, unsorted entries; null when no blob exists, empty when corrupt. */
    private fun readEntries(): List<HistoryRepository.HistoryEntry>? {
        val blob = blobStore.read() ?: return null
        val plaintext = try {
            cipher.decrypt(keystore.getOrCreateKey(), blob)
        } catch (_: Throwable) {
            null
        }
        if (plaintext == null) return emptyList()
        return try {
            json.decodeFromString(HistoryBlob.serializer(), String(plaintext, StandardCharsets.UTF_8))
                .entries
                .map { it.toEntry() }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun prune(entries: List<HistoryRepository.HistoryEntry>): List<HistoryRepository.HistoryEntry> {
        val cutoff = nowMillis() - retentionDays() * MILLIS_PER_DAY
        return entries
            .filter { it.timestampMillis >= cutoff }
            .take(maxEntries)
    }

    private fun write(entries: List<HistoryRepository.HistoryEntry>): Boolean {
        val plaintext = json.encodeToString(
            HistoryBlob.serializer(),
            HistoryBlob(entries = entries.map { it.toDto() }),
        )
        val key = keystore.getOrCreateKey()
        return blobStore.write(cipher.encrypt(key, plaintext.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun HistoryRepository.HistoryEntry.toDto() =
        HistoryEntryDto(
            id = id,
            timestampMillis = timestampMillis,
            text = text,
            language = language,
            charCount = charCount,
            outcome = outcome,
            durationMs = durationMs,
        )

    private fun HistoryEntryDto.toEntry() =
        HistoryRepository.HistoryEntry(
            id = id,
            timestampMillis = timestampMillis,
            text = text,
            language = language,
            charCount = charCount,
            outcome = outcome,
            durationMs = durationMs,
        )

    companion object {
        private const val MILLIS_PER_DAY = 86_400_000L

        /** Shared production identity: keystore alias + blob file used by both the
         *  runtime (writer) and the UI (reader) in the same process. */
        const val DEFAULT_KEY_ALIAS = "whispertype_history_key"
        const val DEFAULT_FILE_NAME = "dictation_history.json.enc"
    }
}

@Serializable
private data class HistoryBlob(
    val entries: List<HistoryEntryDto> = emptyList(),
)

@Serializable
private data class HistoryEntryDto(
    val id: String,
    val timestampMillis: Long,
    val text: String,
    val language: String,
    val charCount: Int,
    val outcome: String,
    val durationMs: Long = 0L,
)
