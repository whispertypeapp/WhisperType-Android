package com.whispertype.android.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.whispertype.android.R
import com.whispertype.android.ui.theme.AppLogo
import com.whispertype.android.ui.theme.StudioCard
import com.whispertype.android.ui.theme.StudioColors
import com.whispertype.android.ui.theme.StudioGhostCta
import com.whispertype.android.ui.theme.StudioLayout
import com.whispertype.android.ui.theme.StudioType

@Composable
fun SetupBanner(
    message: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    actionLabel: String,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(StudioLayout.RadiusField),
        color = StudioColors.ErrorBanner,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier.size(8.dp),
                shape = CircleShape,
                color = StudioColors.ErrorOnBanner,
            ) {}
            Text(
                text = message,
                style = StudioType.snippet.copy(color = StudioColors.ErrorOnBanner),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = actionLabel,
                style = StudioType.heroLabel.copy(color = StudioColors.ErrorOnBanner),
            )
        }
    }
}

@Composable
fun UpdateBanner(
    versionName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(StudioLayout.RadiusField),
        color = StudioColors.AccentSoft,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier.size(8.dp),
                shape = CircleShape,
                color = StudioColors.Accent,
            ) {}
            Text(
                text = stringResource(R.string.update_banner_message, versionName),
                style = StudioType.snippet.copy(color = StudioColors.Accent),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.update_banner_action),
                style = StudioType.heroLabel.copy(color = StudioColors.Accent),
            )
        }
    }
}

@Composable
fun WeeklyHeroCard(
    weekWords: String,
    contextLine: String,
    barsState: WeeklyBarsState,
    modifier: Modifier = Modifier,
) {
    StudioCard(
        radius = StudioLayout.RadiusHero,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 204.dp)
            .clearAndSetSemantics {
                contentDescription = barsState.semanticsDescription
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = stringResource(R.string.home_hero_week_label).uppercase(),
                    style = StudioType.heroLabel,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = weekWords,
                    style = StudioType.displayHero,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = contextLine,
                    style = StudioType.heroSub,
                )
            }
            Spacer(Modifier.height(16.dp))
            WeeklyBarsView(bars = barsState.days)
        }
    }
}

@Composable
fun WeeklyBarsView(
    bars: List<DayBarData>,
    modifier: Modifier = Modifier,
) {
    val maxWords = maxOf(bars.maxOfOrNull { it.words } ?: 0, 1)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        bars.forEach { day ->
            val heightFraction = day.words.toFloat() / maxWords
            val barHeight = if (day.words == 0) {
                4.dp
            } else {
                (6.dp + 36.dp * heightFraction).coerceIn(6.dp, 42.dp)
            }
            val barColor = when {
                day.isCurrentDay -> StudioColors.Accent
                day.isEarlierActive -> StudioColors.Accent.copy(alpha = 0.45f)
                else -> StudioColors.OnSurfaceVariant.copy(alpha = 0.2f)
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    modifier = Modifier.height(44.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .width(10.dp)
                            .height(barHeight)
                            .clip(RoundedCornerShape(5.dp))
                            .background(barColor),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = day.initial,
                    style = StudioType.tagLabel.copy(
                        color = if (day.isCurrentDay) StudioColors.Accent else StudioColors.OnSurfaceVariant,
                    ),
                )
            }
        }
    }
}

@Composable
fun GlanceMetricCard(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    minHeight: Dp = 88.dp,
) {
    StudioCard(
        radius = StudioLayout.RadiusCard,
        modifier = modifier.heightIn(min = minHeight),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = value,
                style = StudioType.metricValue,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = label,
                style = StudioType.metricLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun AtAGlanceSection(
    metrics: HomeGlanceMetrics,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.home_glance_title).uppercase(),
            style = StudioType.heroLabel,
            modifier = Modifier.padding(bottom = StudioLayout.SpacingInside),
        )

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val spacing = StudioLayout.SpacingRelated
            val minTwoColWidth = 132.dp * 2 + spacing

            if (maxWidth >= minTwoColWidth) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing),
                    ) {
                        GlanceMetricCard(
                            value = metrics.sessionsValue,
                            label = metrics.sessionsLabel,
                            minHeight = 88.dp,
                            modifier = Modifier.weight(1f),
                        )
                        GlanceMetricCard(
                            value = metrics.wpmValue,
                            label = metrics.wpmLabel,
                            minHeight = 88.dp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    GlanceMetricCard(
                        value = metrics.perSessionValue,
                        label = metrics.perSessionLabel,
                        minHeight = 76.dp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                    GlanceMetricCard(
                        value = metrics.sessionsValue,
                        label = metrics.sessionsLabel,
                        minHeight = 76.dp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    GlanceMetricCard(
                        value = metrics.wpmValue,
                        label = metrics.wpmLabel,
                        minHeight = 76.dp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    GlanceMetricCard(
                        value = metrics.perSessionValue,
                        label = metrics.perSessionLabel,
                        minHeight = 76.dp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
fun RecentSection(
    snippets: List<Pair<String, String>>,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.home_recent_title).uppercase(),
                style = StudioType.heroLabel,
            )
            Text(
                text = stringResource(R.string.home_recent_view_history),
                style = StudioType.rowDesc.copy(color = StudioColors.Accent),
                modifier = Modifier
                    .clickable(onClick = onOpenHistory)
                    .padding(vertical = 4.dp, horizontal = 4.dp),
            )
        }
        Spacer(Modifier.height(StudioLayout.SpacingInside))
        StudioCard(
            radius = StudioLayout.RadiusCard,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                snippets.forEachIndexed { index, (text, meta) ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = StudioColors.Hairline,
                            thickness = 1.dp,
                        )
                    }
                    Column {
                        Text(
                            text = text,
                            style = StudioType.recentSnippet,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = meta,
                            style = StudioType.rowDesc,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RecentEmptyCard(
    modifier: Modifier = Modifier,
) {
    StudioCard(
        radius = StudioLayout.RadiusCard,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AppLogo(size = 36.dp)
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.home_recent_empty_title),
                style = StudioType.rowTitle,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.home_recent_empty_body),
                style = StudioType.rowDesc,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun HistoryOffCard(
    onEnableHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StudioCard(
        radius = StudioLayout.RadiusCard,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 176.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.home_privacy_card_title),
                style = StudioType.rowTitle,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.home_privacy_card_body),
                style = StudioType.why,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            StudioGhostCta(
                text = stringResource(R.string.home_privacy_turn_on),
                onClick = onEnableHistory,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
