package com.whispertype.android.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.whispertype.android.R
import com.whispertype.android.core.model.LanguageMode
import com.whispertype.android.data.history.HistoryRepository
import com.whispertype.android.data.history.HistoryStats
import com.whispertype.android.ui.theme.StudioCard
import com.whispertype.android.ui.theme.StudioColors
import com.whispertype.android.ui.theme.StudioPageTitle
import com.whispertype.android.ui.theme.StudioType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private data class HistoryDayGroup(
    val label: String,
    val entries: List<HistoryRepository.HistoryEntry>,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    historyRepository: HistoryRepository,
    onCopied: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clearedLabel = stringResource(R.string.history_cleared)
    val copiedLabel = stringResource(R.string.history_copied)
    val fmtLocale: Locale = LocalConfiguration.current.locales[0]
    val nowMillis = System.currentTimeMillis()
    val todayLabel = stringResource(R.string.history_day_today)
    val yesterdayLabel = stringResource(R.string.history_day_yesterday)

    var refreshKey by remember { mutableStateOf(0) }
    var entries by remember { mutableStateOf<List<HistoryRepository.HistoryEntry>>(emptyList()) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refreshKey) { entries = historyRepository.events().first() }

    val dayGroups = remember(entries, nowMillis, todayLabel, yesterdayLabel) {
        groupEntriesByDay(entries, nowMillis, fmtLocale, todayLabel, yesterdayLabel)
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.history_delete_all_confirm_title)) },
            text = { Text(stringResource(R.string.history_delete_all_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        scope.launch {
                            historyRepository.clear()
                            refreshKey++
                        }
                        onCopied(clearedLabel)
                    },
                ) { Text(stringResource(R.string.history_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.history_cancel))
                }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StudioPageTitle(
                    stringResource(R.string.history_title),
                    modifier = Modifier.weight(1f).padding(top = 6.dp),
                )
                if (entries.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.history_clear),
                        style = StudioType.rowDesc,
                        modifier = Modifier
                            .padding(bottom = 14.dp)
                            .combinedClickable(onClick = { showClearConfirm = true }),
                    )
                }
            }
        }
        if (entries.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.history_empty),
                        style = StudioType.why,
                    )
                }
            }
        } else {
            dayGroups.forEach { group ->
                item(key = "header-${group.label}") {
                    Text(
                        text = group.label.uppercase(fmtLocale),
                        style = StudioType.groupLabel,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                }
                items(group.entries, key = { it.id }) { entry ->
                    HistoryEntryCard(
                        entry = entry,
                        locale = fmtLocale,
                        expanded = expandedId == entry.id,
                        onToggle = {
                            expandedId = if (expandedId == entry.id) null else entry.id
                        },
                        onCopy = {
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText(null, entry.text))
                            onCopied(copiedLabel)
                        },
                        onDelete = {
                            scope.launch {
                                historyRepository.delete(entry.id)
                                refreshKey++
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun groupEntriesByDay(
    entries: List<HistoryRepository.HistoryEntry>,
    nowMillis: Long,
    locale: Locale,
    todayLabel: String,
    yesterdayLabel: String,
): List<HistoryDayGroup> {
    if (entries.isEmpty()) return emptyList()
    val startOfToday = HistoryStats.startOfTodayMillis(nowMillis)
    val dayLabelFmt = SimpleDateFormat("EEEE", locale)
    return entries
        .groupBy { HistoryStats.startOfTodayMillis(it.timestampMillis) }
        .toSortedMap(compareByDescending { it })
        .map { (dayStart, dayEntries) ->
            val label = when (dayStart) {
                startOfToday -> todayLabel
                startOfToday - MILLIS_PER_DAY -> yesterdayLabel
                else -> dayLabelFmt.format(Date(dayStart))
            }
            HistoryDayGroup(
                label = label,
                entries = dayEntries.sortedByDescending { it.timestampMillis },
            )
        }
}

private const val MILLIS_PER_DAY = 86_400_000L

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryEntryCard(
    entry: HistoryRepository.HistoryEntry,
    locale: Locale,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    val languageRes = LanguageMode.entries.firstOrNull { it.name == entry.language }?.let {
        when (it) {
            LanguageMode.ENGLISH -> R.string.language_english
            LanguageMode.HINGLISH -> R.string.language_hinglish
        }
    }
    val languageLabel = if (languageRes != null) stringResource(languageRes) else entry.language
    val formattedTime = SimpleDateFormat("h:mm a", locale).format(Date(entry.timestampMillis))
    val outcomeRes = outcomeBadgeRes(entry.outcome)

    StudioCard(radius = 14) {
        Column(
            modifier = Modifier
                .combinedClickable(onClick = onToggle, onLongClick = onCopy)
                .padding(11.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = entry.text, style = StudioType.snippet)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$formattedTime · $languageLabel",
                    style = StudioType.metricLabel,
                )
                if (outcomeRes != null) {
                    Surface(
                        shape = RoundedCornerShape(99.dp),
                        color = StudioColors.AccentSoft,
                    ) {
                        Text(
                            text = stringResource(outcomeRes),
                            style = StudioType.metricLabel.copy(color = StudioColors.Accent),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            if (expanded) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextButton(onClick = onCopy) {
                        Text(stringResource(R.string.history_copy), style = StudioType.rowTitle.copy(color = StudioColors.Accent))
                    }
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.history_delete), style = StudioType.rowTitle.copy(color = StudioColors.ErrorOnBanner))
                    }
                }
            }
        }
    }
}

private fun outcomeBadgeRes(outcome: String): Int? = when (outcome) {
    "Success" -> R.string.history_outcome_inserted
    "CopiedToClipboard", "CopyAvailable" -> R.string.history_outcome_copied
    else -> null
}
