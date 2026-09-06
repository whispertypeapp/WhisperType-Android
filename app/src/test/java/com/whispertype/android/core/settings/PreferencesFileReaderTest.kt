package com.whispertype.android.core.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM tests for [PreferencesFileReader]: direct, cache-free reads of a real
 * Preferences DataStore protobuf file (no Android Context required).
 */
class PreferencesFileReaderTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private fun settingsFile(name: String = "settings.preferences_pb"): File {
        val dir = File(tmp.root, "datastore").apply { mkdirs() }
        return File(dir, name)
    }

    private fun newDataStore(file: File) =
        PreferenceDataStoreFactory.create(produceFile = { file })

    @Test
    fun `missing file yields null`() {
        assertNull(PreferencesFileReader.readBoolean(File(tmp.root, "does-not-exist.pb"), "app_enabled"))
    }

    @Test
    fun `empty file yields null`() {
        val file = settingsFile()
        file.writeBytes(ByteArray(0))
        assertNull(PreferencesFileReader.readBoolean(file, "app_enabled"))
    }

    @Test
    fun `garbage bytes yield null`() {
        val file = settingsFile()
        file.writeBytes(byteArrayOf(0x00, 0x01, 0x7F, 0x80.toByte(), 0xFF.toByte(), 0x42))
        assertNull(PreferencesFileReader.readBoolean(file, "app_enabled"))
    }

    @Test
    fun `boolean true round-trips through a real datastore file`() = runTest {
        val file = settingsFile()
        val dataStore = newDataStore(file)
        dataStore.edit { it[booleanPreferencesKey("app_enabled")] = true }
        assertEquals(true, PreferencesFileReader.readBoolean(file, "app_enabled"))
    }

    @Test
    fun `boolean false round-trips through a real datastore file`() = runTest {
        val file = settingsFile()
        val dataStore = newDataStore(file)
        dataStore.edit { it[booleanPreferencesKey("app_enabled")] = false }
        assertEquals(false, PreferencesFileReader.readBoolean(file, "app_enabled"))
    }

    @Test
    fun `unknown key yields null`() = runTest {
        val file = settingsFile()
        val dataStore = newDataStore(file)
        dataStore.edit { it[booleanPreferencesKey("app_enabled")] = true }
        assertNull(PreferencesFileReader.readBoolean(file, "some_other_key"))
    }

    @Test
    fun `string-valued key yields null`() = runTest {
        val file = settingsFile()
        val dataStore = newDataStore(file)
        dataStore.edit { it[stringPreferencesKey("speech_mode")] = "HINGLISH" }
        assertNull(PreferencesFileReader.readBoolean(file, "speech_mode"))
    }

    @Test
    fun `missing int key yields null`() {
        assertNull(PreferencesFileReader.readInt(File(tmp.root, "does-not-exist.pb"), "hotkey_keycode"))
    }

    @Test
    fun `boolean-valued key yields null for int read`() = runTest {
        val file = settingsFile()
        val dataStore = newDataStore(file)
        dataStore.edit { it[booleanPreferencesKey("app_enabled")] = true }
        assertNull(PreferencesFileReader.readInt(file, "app_enabled"))
    }

    @Test
    fun `int round-trips through a real datastore file`() = runTest {
        val file = settingsFile()
        val dataStore = newDataStore(file)
        dataStore.edit { it[intPreferencesKey("hotkey_keycode")] = 96 }
        assertEquals(96, PreferencesFileReader.readInt(file, "hotkey_keycode"))
    }

    @Test
    fun `mixed ints in one file are each readable`() = runTest {
        val file = settingsFile()
        val dataStore = newDataStore(file)
        dataStore.edit {
            it[intPreferencesKey("hotkey_keycode")] = 131
            it[booleanPreferencesKey("app_enabled")] = true
        }
        assertEquals(131, PreferencesFileReader.readInt(file, "hotkey_keycode"))
        assertEquals(true, PreferencesFileReader.readBoolean(file, "app_enabled"))
    }

    @Test
    fun `multiple booleans in one file are each readable`() = runTest {
        val file = settingsFile()
        val dataStore = newDataStore(file)
        dataStore.edit {
            it[booleanPreferencesKey("history_enabled")] = true
            it[booleanPreferencesKey("app_enabled")] = false
            it[booleanPreferencesKey("dark_mode")] = true
        }
        assertFalse(PreferencesFileReader.readBoolean(file, "app_enabled")!!)
        assertTrue(PreferencesFileReader.readBoolean(file, "history_enabled")!!)
        assertTrue(PreferencesFileReader.readBoolean(file, "dark_mode")!!)
    }
}
