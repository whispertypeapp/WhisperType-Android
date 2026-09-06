package com.whispertype.android.core.dictionary

/** One custom-dictionary correction: whenever the ASR output contains [match]
 *  as a word, replace it with [replace] (spelling correction). */
data class DictionaryEntry(val match: String, val replace: String)

/** Pure, stateless correction-rule engine (custom dictionary, correction rules
 *  only — never injected into the prompt). */
object DictionaryCorrections {

    /**
     * Applies all [entries] to [text]: case-insensitive, word-boundary
     * replacements, longest match first. The replacement keeps the case of the
     * first letter of the matched occurrence. Text with no matches, an empty
     * [entries] list, or empty [text] is returned unchanged.
     */
    fun apply(text: String, entries: List<DictionaryEntry>): String {
        if (text.isEmpty() || entries.isEmpty()) return text

        val result = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val entry = entries
                .filter { matchesAt(text, index, it) }
                .maxByOrNull { it.match.length }
            if (entry == null) {
                result.append(text[index])
                index += 1
            } else {
                result.append(preserveCase(text[index], entry.replace))
                index += entry.match.length
            }
        }
        return result.toString()
    }

    /** True when [entry] matches at [index] of [text] with word boundaries. */
    private fun matchesAt(text: String, index: Int, entry: DictionaryEntry): Boolean {
        val length = entry.match.length
        if (length == 0 || index + length > text.length) return false
        return hasBoundaryBefore(text, index) &&
            hasBoundaryAfter(text, index + length) &&
            text.regionMatches(index, entry.match, 0, length, ignoreCase = true)
    }

    /** True when the character before [index] is a boundary (or string start). */
    private fun hasBoundaryBefore(text: String, index: Int): Boolean =
        index == 0 || !text[index - 1].isLetterOrDigit()

    /** True when the character at [index] is a boundary (or string end). */
    private fun hasBoundaryAfter(text: String, index: Int): Boolean =
        index == text.length || !text[index].isLetterOrDigit()

    /** Capitalizes the first letter of [replacement] when [firstChar] is uppercase. */
    private fun preserveCase(firstChar: Char, replacement: String): String =
        if (firstChar.isUpperCase()) replacement.replaceFirstChar { it.titlecase() } else replacement
}
