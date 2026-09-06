package com.whispertype.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.whispertype.android.BuildConfig
import com.whispertype.android.R
import com.whispertype.android.platform.accessibility.SetupStatus
import com.whispertype.android.ui.theme.StudioCard
import com.whispertype.android.ui.theme.StudioColors
import com.whispertype.android.ui.theme.StudioPageTitle
import com.whispertype.android.ui.theme.StudioType

@Composable
fun SettingsGroupLabel(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title.uppercase(),
        modifier = modifier.padding(start = 4.dp, bottom = 6.dp, top = 8.dp),
        style = StudioType.groupLabel,
    )
}

@Composable
fun SettingsGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    StudioCard(modifier = modifier, radius = 16) {
        content()
    }
}

@Composable
fun SettingsNavRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = StudioType.rowTitle)
            if (subtitle != null) {
                Text(text = subtitle, style = StudioType.rowDesc)
            }
        }
        if (value != null) {
            Text(text = value, style = StudioType.rowDesc)
        }
        trailing?.invoke()
        if (showChevron) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = StudioColors.OnSurfaceVariant.copy(alpha = 0.4f),
            )
        }
    }
}

@Composable
fun SettingsDivider() {
    HorizontalDivider(
        color = StudioColors.Hairline,
        thickness = 1.dp,
    )
}

@Composable
fun SettingsDrillScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_back),
            style = StudioType.rowTitle.copy(color = StudioColors.Accent),
            modifier = Modifier
                .clickable(onClick = onBack)
                .padding(vertical = 8.dp),
        )
        StudioPageTitle(title)
        content()
        Spacer(Modifier.padding(bottom = 24.dp))
    }
}

@Composable
fun StudioSliderRow(
    title: String,
    description: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(text = title, style = StudioType.rowTitle)
        Text(text = description, style = StudioType.rowDesc)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = StudioColors.Accent,
                activeTrackColor = StudioColors.Accent,
                inactiveTrackColor = StudioColors.SurfaceVariant,
            ),
        )
    }
}

@Composable
fun SystemStatusScreen(
    gates: List<SetupStatus.SystemGate>,
    onBack: () -> Unit,
    onFixGate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_back),
            style = StudioType.rowTitle.copy(color = StudioColors.Accent),
            modifier = Modifier
                .clickable(onClick = onBack)
                .padding(vertical = 8.dp),
        )
        StudioPageTitle(stringResource(R.string.settings_system_title))
        val allOn = gates.all { it.on }
        val issueCount = gates.count { !it.on }
        Text(
            text = if (allOn) {
                stringResource(R.string.settings_system_ok)
            } else {
                pluralStringResource(R.plurals.settings_system_issues, issueCount, issueCount)
            },
            style = StudioType.why,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        SettingsGroupCard {
            gates.forEachIndexed { index, gate ->
                if (index > 0) SettingsDivider()
                SettingsNavRow(
                    title = gateLabel(gate.id),
                    value = stringResource(if (gate.on) R.string.status_on else R.string.status_fix),
                    onClick = if (!gate.on) {
                        { onFixGate(gate.id) }
                    } else {
                        null
                    },
                    showChevron = !gate.on,
                )
            }
        }
        Text(
            text = stringResource(
                R.string.home_version,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
                BuildConfig.GIT_COMMIT,
            ),
            style = StudioType.rowDesc,
            modifier = Modifier.padding(top = 16.dp, start = 4.dp),
        )
    }
}

@Composable
private fun gateLabel(id: String): String = stringResource(
    when (id) {
        "overlay" -> R.string.home_status_overlay
        "runtime" -> R.string.home_status_runtime
        "accessibility" -> R.string.home_status_accessibility
        "gemini_key" -> R.string.home_status_key
        "microphone" -> R.string.home_status_mic
        "notifications" -> R.string.home_status_notifications
        else -> R.string.settings_system_title
    },
)
