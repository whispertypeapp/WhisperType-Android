package com.whispertype.android.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whispertype.android.ui.theme.AppLogo
import com.whispertype.android.ui.theme.StudioCard
import com.whispertype.android.ui.theme.StudioColors
import com.whispertype.android.ui.theme.StudioType
import kotlin.math.max

@Composable
fun WeekSparkline(
    dailyWords: List<Int>,
    modifier: Modifier = Modifier,
) {
    if (dailyWords.size < 2) return
    val accent = StudioColors.Accent
    Canvas(modifier = modifier.height(44.dp).fillMaxWidth()) {
        val w = size.width
        val h = size.height
        val maxVal = max(dailyWords.maxOrNull() ?: 1, 1).toFloat()
        val step = w / (dailyWords.size - 1)
        val path = Path()
        dailyWords.forEachIndexed { i, count ->
            val x = i * step
            val y = h - (count / maxVal) * (h * 0.82f) - h * 0.08f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = accent,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
fun MetricChip(label: String, value: String, modifier: Modifier = Modifier) {
    StudioCard(modifier = modifier, radius = 16) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(text = value, style = StudioType.metricValue)
            Text(text = label, style = StudioType.metricLabel)
        }
    }
}

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
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = StudioColors.ErrorBanner,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
fun RecentSnippet(
    text: String,
    meta: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(bottom = 6.dp)) {
        Text(
            text = text,
            style = StudioType.snippet,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(text = meta, style = StudioType.rowDesc)
    }
}

@Composable
fun HeroCard(
    heroLabel: String,
    heroValue: String,
    heroSub: String,
    dailyWords: List<Int>,
    modifier: Modifier = Modifier,
) {
    StudioCard(modifier = modifier.fillMaxHeight(), radius = 22) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
            verticalArrangement = if (dailyWords.any { it > 0 }) {
                Arrangement.SpaceBetween
            } else {
                Arrangement.Center
            },
        ) {
            Column {
                Text(
                    text = heroLabel.uppercase(),
                    style = StudioType.heroLabel,
                )
                Text(
                    text = heroValue,
                    style = StudioType.displayHero,
                )
                Text(text = heroSub, style = StudioType.heroSub)
            }
            if (dailyWords.any { it > 0 }) {
                WeekSparkline(
                    dailyWords = dailyWords,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .height(72.dp),
                )
            }
        }
    }
}

@Composable
fun HeroEmptyCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    StudioCard(modifier = modifier, radius = 22) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AppLogo(size = 72.dp)
            Spacer(Modifier.height(16.dp))
            Text(text = title, style = StudioType.rowTitle)
            Spacer(Modifier.height(8.dp))
            Text(
                text = body,
                style = StudioType.why,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
fun RecentCard(
    title: String,
    snippets: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    StudioCard(modifier = modifier.fillMaxHeight(), radius = 16) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = title, style = StudioType.heroLabel)
            Column {
                snippets.forEach { (text, meta) ->
                    RecentSnippet(text = text, meta = meta)
                }
            }
        }
    }
}
