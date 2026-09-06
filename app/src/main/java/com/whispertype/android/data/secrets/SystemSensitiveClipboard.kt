package com.whispertype.android.data.secrets

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

/**
 * Platform [SensitiveClipboard] backed by [ClipboardManager]. Marks the content
 * as sensitive via [ClipDescription.EXTRA_IS_SENSITIVE] (minSdk 33) so other
 * apps' clipboard previews are suppressed; returns true only on a confirmed
 * write. Device-verified path (see docs/TESTING.md).
 */
class SystemSensitiveClipboard(
    private val context: Context,
) : SensitiveClipboard {

    override suspend fun copySensitive(text: String): Boolean = try {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        val clip = ClipData.newPlainText(LABEL, text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        manager.setPrimaryClip(clip)
        true
    } catch (_: Throwable) {
        false
    }

    private companion object {
        const val LABEL = "WhisperType transcript"
    }
}
