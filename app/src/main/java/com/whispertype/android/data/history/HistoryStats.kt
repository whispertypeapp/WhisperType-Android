package com.whispertype.android.data.history

/**
 * Pure aggregates over history entries for the 0.4.2 home stats card
 * (sessions, words, today's words, and average words-per-minute from recorded
 * durations). Framework-free and host-testable.
 */
object HistoryStats {

    fun sessions(entries: List<HistoryRepository.HistoryEntry>): Int = entries.size

    /** Total content words across all entries (letter/digit-run tokenization). */
    fun totalWords(entries: List<HistoryRepository.HistoryEntry>): Int =
        entries.sumOf { contentWords(it.text).size }

    /** Content words recorded since the start of today (local wall clock). */
    fun todayWords(entries: List<HistoryRepository.HistoryEntry>, nowMillis: Long): Int {
        val startOfToday = startOfTodayMillis(nowMillis)
        return entries
            .filter { it.timestampMillis >= startOfToday }
            .sumOf { contentWords(it.text).size }
    }

    /** Content words recorded over the trailing 7 days. */
    fun weekWords(entries: List<HistoryRepository.HistoryEntry>, nowMillis: Long): Int {
        val cutoff = nowMillis - 7 * MILLIS_PER_DAY
        return entries
            .filter { it.timestampMillis >= cutoff }
            .sumOf { contentWords(it.text).size }
    }

    /** Word counts for each of the trailing [days] local calendar days, oldest
     *  first. Index 0 is (now − days + 1) at local midnight; index last is today. */
    fun dailyWords(
        entries: List<HistoryRepository.HistoryEntry>,
        nowMillis: Long,
        days: Int = 7,
    ): List<Int> {
        require(days >= 1)
        val startOfToday = startOfTodayMillis(nowMillis)
        return (days - 1 downTo 0).map { dayOffset ->
            val dayStart = startOfToday - dayOffset * MILLIS_PER_DAY
            val dayEnd = dayStart + MILLIS_PER_DAY
            entries
                .filter { it.timestampMillis in dayStart until dayEnd }
                .sumOf { contentWords(it.text).size }
        }
    }

    /** Sessions recorded today (local wall clock). */
    fun todaySessions(entries: List<HistoryRepository.HistoryEntry>, nowMillis: Long): Int {
        val startOfToday = startOfTodayMillis(nowMillis)
        return entries.count { it.timestampMillis >= startOfToday }
    }

    /** Average content words per session, rounded, or 0 when there is no history. */
    fun wordsPerSession(entries: List<HistoryRepository.HistoryEntry>): Int {
        val sessions = sessions(entries)
        if (sessions == 0) return 0
        return (totalWords(entries) + sessions / 2) / sessions
    }

    /** Average speaking rate across entries with a recorded duration, or null
     *  when there is no duration evidence (older entries / empty history). */
    fun wordsPerMinute(entries: List<HistoryRepository.HistoryEntry>): Double? {
        val totalDurationMs = entries.sumOf { it.durationMs }
        if (totalDurationMs <= 0) return null
        val minutes = totalDurationMs / 60_000.0
        if (minutes <= 0) return null
        return totalWords(entries) / minutes
    }

    fun contentWords(text: String): List<String> {
        val result = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                result.add(current.toString())
                current.setLength(0)
            }
        }
        for (ch in text) {
            if (ch.isLetterOrDigit()) {
                current.append(ch)
            } else {
                flush()
            }
        }
        flush()
        return result
    }

    /** Milliseconds at the local start of the day containing [nowMillis]. */
    fun startOfTodayMillis(nowMillis: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = nowMillis
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private const val MILLIS_PER_DAY = 86_400_000L
}
