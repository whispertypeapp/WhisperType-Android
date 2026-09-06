package com.whispertype.android.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.whispertype.android.R
import com.whispertype.android.data.history.HistoryRepository
import com.whispertype.android.data.history.HistoryStats
import com.whispertype.android.platform.accessibility.EligibilityExplanation
import com.whispertype.android.platform.accessibility.SetupStatus
import com.whispertype.android.ui.theme.StudioColors
import com.whispertype.android.ui.theme.StudioType
import com.whispertype.android.ui.theme.WhisperTypeTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    historyEnabled: Boolean,
    entries: List<HistoryRepository.HistoryEntry>,
    setupBannerReasons: List<String>,
    onSetupBannerTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = remember { System.currentTimeMillis() }
    val greeting = rememberHomeGreeting(now)
    val dateLabel = rememberDateLabel(now)
    val hasStats = historyEnabled && entries.isNotEmpty()

    Surface(modifier = modifier.fillMaxSize(), color = StudioColors.Background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(text = dateLabel, style = StudioType.greet, modifier = Modifier.padding(top = 6.dp))
            Text(text = greeting, style = StudioType.greeting)
            Spacer(Modifier.height(14.dp))

            if (setupBannerReasons.isNotEmpty()) {
                SetupBanner(
                    message = setupBannerMessage(setupBannerReasons),
                    actionLabel = stringResource(R.string.status_fix),
                    onClick = onSetupBannerTap,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            if (hasStats) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val weekWords = HistoryStats.weekWords(entries, now)
                    val sessions = HistoryStats.sessions(entries)
                    val wpm = HistoryStats.wordsPerMinute(entries)?.roundToInt()
                    val perSession = HistoryStats.wordsPerSession(entries)
                    val weekSessions = entries.count { it.timestampMillis >= now - 7 * 86_400_000L }
                    val heroSub = if (wpm != null) {
                        pluralStringResource(R.plurals.home_hero_week_wpm, weekSessions, weekSessions, wpm)
                    } else {
                        pluralStringResource(R.plurals.home_hero_week_sub, weekSessions, weekSessions)
                    }
                    HeroCard(
                        heroLabel = stringResource(R.string.home_hero_week_label),
                        heroValue = formatCount(weekWords),
                        heroSub = heroSub,
                        dailyWords = HistoryStats.dailyWords(entries, now),
                        modifier = Modifier.weight(1f),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MetricChip(
                            label = stringResource(R.string.home_stats_sessions),
                            value = sessions.toString(),
                            modifier = Modifier.weight(1f),
                        )
                        MetricChip(
                            label = stringResource(R.string.home_stats_wpm),
                            value = wpm?.toString() ?: "—",
                            modifier = Modifier.weight(1f),
                        )
                        MetricChip(
                            label = stringResource(R.string.home_stats_per_session),
                            value = perSession.toString(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    RecentSection(
                        entries = entries.take(2),
                        nowMillis = now,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    HeroEmptyCard(
                        title = stringResource(R.string.home_empty_title),
                        body = stringResource(R.string.home_empty_body),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentSection(
    entries: List<HistoryRepository.HistoryEntry>,
    nowMillis: Long,
    modifier: Modifier = Modifier,
) {
    val fmtLocale: Locale = LocalConfiguration.current.locales[0]
    val timeFmt = remember(fmtLocale) { SimpleDateFormat("h:mm a", fmtLocale) }
    val startOfToday = HistoryStats.startOfTodayMillis(nowMillis)
    val todayLabel = stringResource(R.string.history_day_today)
    RecentCard(
        modifier = modifier,
        title = stringResource(R.string.home_recent_title),
        snippets = entries.map { entry ->
            val whenLabel = if (entry.timestampMillis >= startOfToday) {
                todayLabel
            } else {
                SimpleDateFormat("MMM d", fmtLocale).format(Date(entry.timestampMillis))
            }
            val outcome = when (entry.outcome) {
                "Success" -> stringResource(R.string.history_outcome_inserted)
                "CopiedToClipboard", "CopyAvailable" -> stringResource(R.string.history_outcome_copied)
                else -> null
            }
            val meta = buildString {
                append(whenLabel)
                append(" · ")
                append(timeFmt.format(Date(entry.timestampMillis)).lowercase(fmtLocale))
                if (outcome != null) {
                    append(" · ")
                    append(outcome)
                }
            }
            entry.text to meta
        },
    )
}

@Composable
private fun setupBannerMessage(reasons: List<String>): String {
    if (reasons.size > 1) {
        return pluralStringResource(R.plurals.home_banner_multiple, reasons.size, reasons.size)
    }
    return when (reasons.firstOrNull()) {
        EligibilityExplanation.REASON_SERVICE_NOT_CONNECTED ->
            stringResource(R.string.home_banner_accessibility)
        EligibilityExplanation.REASON_API_KEY_MISSING ->
            stringResource(R.string.home_banner_key)
        EligibilityExplanation.REASON_MICROPHONE_NOT_GRANTED ->
            stringResource(R.string.home_banner_mic)
        EligibilityExplanation.REASON_APP_DISABLED ->
            stringResource(R.string.home_banner_disabled)
        SetupStatus.REASON_OVERLAY_NOT_GRANTED ->
            stringResource(R.string.home_banner_overlay)
        SetupStatus.REASON_RUNTIME_NOT_RUNNING ->
            stringResource(R.string.home_banner_runtime)
        SetupStatus.REASON_NOTIFICATIONS_NOT_GRANTED ->
            stringResource(R.string.home_banner_notifications)
        else -> stringResource(R.string.home_banner_generic)
    }
}

private fun formatCount(n: Int): String =
    if (n >= 1000) {
        val k = n / 1000f
        if (k >= 10) "${k.roundToInt()}k" else String.format(Locale.US, "%.1fk", k)
    } else {
        n.toString()
    }

@Composable
private fun rememberHomeGreeting(nowMillis: Long): String {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = nowMillis
    val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
    return stringResource(
        when (hour) {
            in 5..11 -> R.string.home_greeting_morning
            in 12..16 -> R.string.home_greeting_afternoon
            in 17..21 -> R.string.home_greeting_evening
            else -> R.string.home_greeting_night
        },
    )
}

@Composable
private fun rememberDateLabel(nowMillis: Long): String {
    val fmtLocale: Locale = LocalConfiguration.current.locales[0]
    val fmt = remember(fmtLocale) { SimpleDateFormat("EEEE d MMMM", fmtLocale) }
    return fmt.format(Date(nowMillis))
}

private fun previewEntry(
    text: String,
    minutesAgo: Long,
    outcome: String = "Success",
    durationMs: Long = 60_000L,
) = HistoryRepository.HistoryEntry(
    id = text.hashCode().toString(),
    timestampMillis = System.currentTimeMillis() - minutesAgo * 60_000L,
    text = text,
    language = "HINGLISH",
    charCount = text.length,
    outcome = outcome,
    durationMs = durationMs,
)

@Preview(showBackground = true, backgroundColor = 0xFF10100E, widthDp = 360, heightDp = 720)
@Composable
private fun HomeHealthyPreview() {
    val words = (1..79).joinToString(" ") { "word$it" }
    WhisperTypeTheme {
        HomeScreen(
            historyEnabled = true,
            entries = List(36) { i ->
                previewEntry(
                    text = if (i == 0) "Can you send the deck before standup tomorrow?" else words,
                    minutesAgo = if (i < 8) i * 40L else 200L + i,
                    durationMs = 33_000L,
                )
            },
            setupBannerReasons = emptyList(),
            onSetupBannerTap = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF10100E, widthDp = 360, heightDp = 720)
@Composable
private fun HomeEmptyPreview() {
    WhisperTypeTheme {
        HomeScreen(
            historyEnabled = true,
            entries = emptyList(),
            setupBannerReasons = emptyList(),
            onSetupBannerTap = {},
        )
    }
}
