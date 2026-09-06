package com.whispertype.android.core.dictionary

import kotlin.test.Test
import kotlin.test.assertEquals

/** Unit tests for the [DictionaryCorrections] correction engine. */
class DictionaryCorrectionsTest {

    private val catToDog = listOf(DictionaryEntry("cat", "dog"))

    @Test
    fun `replaces a word case-insensitively`() {
        val entries = listOf(DictionaryEntry("theek", "thik"))
        assertEquals("thik hai", DictionaryCorrections.apply("theek hai", entries))
    }

    @Test
    fun `capitalizes the replacement when the matched occurrence starts uppercase`() {
        val entries = listOf(DictionaryEntry("theek", "thik"))
        assertEquals("Thik hai", DictionaryCorrections.apply("Theek hai", entries))
    }

    @Test
    fun `matches only on word boundaries`() {
        assertEquals("the dog sat", DictionaryCorrections.apply("the cat sat", catToDog))
        assertEquals("dog!", DictionaryCorrections.apply("cat!", catToDog))
        assertEquals("category", DictionaryCorrections.apply("category", catToDog))
        assertEquals("scatter", DictionaryCorrections.apply("scatter", catToDog))
    }

    @Test
    fun `longest matching entry wins at the same position`() {
        val entries = listOf(DictionaryEntry("new", "N"), DictionaryEntry("new york", "NY"))
        assertEquals("go to NY", DictionaryCorrections.apply("go to new york", entries))
    }

    @Test
    fun `supports multi-word matches with a space`() {
        val entries = listOf(DictionaryEntry("new york", "New York"))
        assertEquals("go to New York", DictionaryCorrections.apply("go to new york", entries))
        assertEquals("I love New York!", DictionaryCorrections.apply("I love new york!", entries))
    }

    @Test
    fun `preserves punctuation around a replacement`() {
        val entries = listOf(DictionaryEntry("world", "earth"))
        assertEquals("Hello, earth!", DictionaryCorrections.apply("Hello, world!", entries))
    }

    @Test
    fun `returns text unchanged when nothing matches`() {
        assertEquals("nothing to change", DictionaryCorrections.apply("nothing to change", catToDog))
    }

    @Test
    fun `is a no-op for empty entries`() {
        assertEquals("hello", DictionaryCorrections.apply("hello", emptyList()))
    }

    @Test
    fun `is a no-op for empty text`() {
        assertEquals("", DictionaryCorrections.apply("", catToDog))
    }

    @Test
    fun `applies multiple distinct entries in one pass`() {
        val entries = listOf(DictionaryEntry("teh", "the"), DictionaryEntry("recieve", "receive"))
        assertEquals("i receive the package", DictionaryCorrections.apply("i recieve teh package", entries))
    }

    @Test
    fun `does not match a substring inside a longer word`() {
        assertEquals("concatenate", DictionaryCorrections.apply("concatenate", catToDog))
    }
}
