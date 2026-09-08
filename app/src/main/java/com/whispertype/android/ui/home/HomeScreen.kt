package com.whispertype.android.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.whispertype.android.R
import com.whispertype.android.data.history.HistoryRepository
import com.whispertype.android.platform.accessibility.EligibilityExplanation
import com.whispertype.android.platform.accessibility.SetupStatus
import com.whispertype.android.ui.theme.StudioColors
import com.whispertype.android.ui.theme.StudioLayout
import com.whispertype.android.ui.theme.StudioType
import com.whispertype.android.ui.theme.WhisperTypeTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    historyEnabled: Boolean,
    entries: List<HistoryRepository.HistoryEntry>,
    setupBannerReasons: List<String>,
    onSetupBannerTap: () -> Unit,
    modifier: Modifier = Modifier,
    updateRelease: com.whispertype.android.core.updates.AppReleaseInfo? = null,
    onDownloadUpdate: (String) -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onEnableHistory: () -> Unit = {},
) {
    val now = remember { System.currentTimeMillis() }
    val greeting = rememberHomeGreeting(now)
    val dateLabel = rememberDateLabel(now)

    val fmtLocale: Locale = LocalConfiguration.current.locales[0]
    val todayLabel = stringResource(R.string.history_day_today)
    val insertedLabel = stringResource(R.string.history_outcome_inserted)
    val copiedLabel = stringResource(R.string.history_outcome_copied)

    Surface(modifier = modifier.fillMaxSize(), color = StudioColors.Background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = StudioLayout.GutterHorizontal, vertical = 16.dp),
        ) {
            Text(text = dateLabel, style = StudioType.greet)
            Spacer(Modifier.height(2.dp))
            Text(text = greeting, style = StudioType.greeting)
            Spacer(Modifier.height(StudioLayout.SpacingSection))

            if (updateRelease != null && updateRelease.isNewerThanCurrent) {
                UpdateBanner(
                    versionName = updateRelease.tagName,
                    onClick = { onDownloadUpdate(updateRelease.downloadUrl) },
                )
                Spacer(Modifier.height(StudioLayout.SpacingRelated))
            }

            if (setupBannerReasons.isNotEmpty()) {
                SetupBanner(
                    message = setupBannerMessage(setupBannerReasons),
                    actionLabel = stringResource(R.string.status_fix),
                    onClick = onSetupBannerTap,
                )
                Spacer(Modifier.height(StudioLayout.SpacingRelated))
            }

            if (!historyEnabled) {
                HistoryOffCard(
                    onEnableHistory = onEnableHistory,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(StudioLayout.GutterBottom))
            } else {
                val barsState = remember(entries, now) {
                    HomeHelpers.computeWeeklyBarsState(entries, now)
                }

                val heroSub = when {
                    barsState.weekSessions == 0 -> {
                        stringResource(R.string.home_hero_no_sessions_week)
                    }
                    barsState.weekWpm != null -> {
                        pluralStringResource(
                            R.plurals.home_hero_week_wpm,
                            barsState.weekSessions,
                            barsState.weekSessions,
                            barsState.weekWpm,
                        )
                    }
                    else -> {
                        pluralStringResource(
                            R.plurals.home_hero_week_sub,
                            barsState.weekSessions,
                            barsState.weekSessions,
                        )
                    }
                }

                WeeklyHeroCard(
                    weekWords = HomeHelpers.formatCount(barsState.weekWords),
                    contextLine = heroSub,
                    barsState = barsState,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(StudioLayout.SpacingSection))

                val glanceMetrics = remember(entries) {
                    HomeHelpers.computeGlanceMetrics(entries)
                }
                AtAGlanceSection(
                    metrics = glanceMetrics,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(24.dp))

                if (entries.isEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = StudioLayout.SpacingInside),
                    ) {
                        Text(
                            text = stringResource(R.string.home_recent_title).uppercase(),
                            style = StudioType.heroLabel,
                        )
                    }
                    RecentEmptyCard(modifier = Modifier.fillMaxWidth())
                } else {
                    val recentSnippets = remember(entries, now, fmtLocale) {
                        entries.take(2).map { entry ->
                            val meta = HomeHelpers.formatRecentSnippetMeta(
                                entry = entry,
                                nowMillis = now,
                                todayLabel = todayLabel,
                                insertedLabel = insertedLabel,
                                copiedLabel = copiedLabel,
                                locale = fmtLocale,
                            )
                            entry.text to meta
                        }
                    }
                    RecentSection(
                        snippets = recentSnippets,
                        onOpenHistory = onOpenHistory,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(StudioLayout.GutterBottom))
            }
        }
    }
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

@Composable
private fun rememberHomeGreeting(nowMillis: Long): String {
    val cal = Calendar.getInstance()
    cal.timeInMillis = nowMillis
    val hour = cal.get(Calendar.HOUR_OF_DAY)
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
private fun HomeOneItemPreview() {
    WhisperTypeTheme {
        HomeScreen(
            historyEnabled = true,
            entries = listOf(
                previewEntry(
                    text = "Ship the build to internal testers tonight.",
                    minutesAgo = 12L,
                    durationMs = 15_000L,
                ),
            ),
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

@Preview(showBackground = true, backgroundColor = 0xFF10100E, widthDp = 360, heightDp = 720)
@Composable
private fun HomeHistoryOffPreview() {
    WhisperTypeTheme {
        HomeScreen(
            historyEnabled = false,
            entries = emptyList(),
            setupBannerReasons = emptyList(),
            onSetupBannerTap = {},
        )
    }
}
