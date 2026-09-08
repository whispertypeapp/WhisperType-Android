package com.whispertype.android.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whispertype.android.R

object BrandColors {
    val Teal = Color(0xFF0E9B8A)
    val TealDark = Color(0xFF07695D)
    val TealLight = Color(0xFFA7F0E2)
}

/** Studio theme color palette. */
object StudioColors {
    val Background = Color(0xFF10100E)
    val Surface = Color(0xFF1A1A16)
    val SurfaceVariant = Color(0xFF24241F)
    val OnBackground = Color(0xFFF3EFE6)
    val OnSurfaceVariant = Color(0xFF9B9588)
    val Accent = Color(0xFF5EE0C4)
    val OnAccent = Color(0xFF0B1F1B)
    val AccentSoft = Color(0x1F5EE0C4)
    val Hairline = Color(0x14F3EFE6)
    val ErrorBanner = Color(0x1FFF6B5E)
    val ErrorOnBanner = Color(0xFFFF8B80)
    val OkBanner = Color(0x1A5BE39A)
}

object WhisperTypeColors {
    val Surface = Color(0xFF141414)
    val SurfaceRaised = Color(0xE01A1A16)
    val OnSurface = Color(0xFFF5F3F0)
    val RecordingAccent = Color(0xFFE8593C)
    val IdleAccent = Color(0xFFF5F3F0)
    val ErrorAccent = Color(0xFFFF6B5E)
    val SuccessAccent = Color(0xFF5BE39A)
    val WaveAccent = Color(0xFF5EE0C4)
    val PillBorder = Color(0x14F3EFE6)
}

val InstrumentSans = FontFamily(
    Font(R.font.instrument_sans, FontWeight.Normal),
    Font(R.font.instrument_sans, FontWeight.Medium),
    Font(R.font.instrument_sans, FontWeight.SemiBold),
    Font(R.font.instrument_sans, FontWeight.Bold),
)

/** Named Studio type styles — Instrument Sans throughout (no display serif). */
object StudioType {
    val displayHero = TextStyle(
        fontFamily = InstrumentSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 64.sp,
        lineHeight = 60.sp,
        letterSpacing = (-2.5).sp,
        color = StudioColors.Accent,
        fontFeatureSettings = "tnum",
    )
    val greeting = TextStyle(
        fontFamily = InstrumentSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.8).sp,
        color = StudioColors.OnBackground,
    )
    val displayTitle = greeting
    val pageTitle = TextStyle(
        fontFamily = InstrumentSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.8).sp,
        color = StudioColors.OnBackground,
    )
    val onboardBig = TextStyle(
        fontFamily = InstrumentSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.6).sp,
        color = StudioColors.OnBackground,
    )
    val greet = TextStyle(
        fontFamily = InstrumentSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        color = StudioColors.OnSurfaceVariant,
    )
    val heroLabel = TextStyle(
        fontFamily = InstrumentSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.44.sp,
        color = StudioColors.OnSurfaceVariant,
    )
    val heroSub = TextStyle(
        fontFamily = InstrumentSans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        color = StudioColors.OnSurfaceVariant,
    )
    val groupLabel = TextStyle(
        fontFamily = InstrumentSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.66.sp,
        color = StudioColors.OnSurfaceVariant,
    )
    val rowTitle = TextStyle(
        fontFamily = InstrumentSans,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        color = StudioColors.OnBackground,
    )
    val rowDesc = TextStyle(
        fontFamily = InstrumentSans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        color = StudioColors.OnSurfaceVariant,
    )
    val metricValue = TextStyle(
        fontFamily = InstrumentSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.5).sp,
        color = StudioColors.OnBackground,
        fontFeatureSettings = "tnum",
    )
    val metricLabel = TextStyle(
        fontFamily = InstrumentSans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        color = StudioColors.OnSurfaceVariant,
    )
    val tagLabel = TextStyle(
        fontFamily = InstrumentSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
        color = StudioColors.OnSurfaceVariant,
    )
    val recentSnippet = TextStyle(
        fontFamily = InstrumentSans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        color = StudioColors.OnBackground,
    )
    val snippet = TextStyle(
        fontFamily = InstrumentSans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        color = StudioColors.OnBackground,
    )
    val why = TextStyle(
        fontFamily = InstrumentSans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        color = StudioColors.OnSurfaceVariant,
    )
    val cta = TextStyle(
        fontFamily = InstrumentSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
    )
    val tabLabel = TextStyle(
        fontFamily = InstrumentSans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    )
}

/** Studio layout spacing, radii, and gutter metrics. */
object StudioLayout {
    val GutterHorizontal = 20.dp
    val GutterBottom = 24.dp
    val SpacingInside = 8.dp
    val SpacingRelated = 12.dp
    val SpacingSection = 20.dp
    val RadiusHero = 24.dp
    val RadiusCard = 18.dp
    val RadiusField = 14.dp
    val MinInteractiveTarget = 48.dp
}

private val StudioColorScheme = darkColorScheme(
    primary = StudioColors.Accent,
    onPrimary = StudioColors.OnAccent,
    primaryContainer = Color(0xFF005047),
    onPrimaryContainer = BrandColors.TealLight,
    secondary = Color(0xFFB2CCC5),
    onSecondary = Color(0xFF1D352F),
    background = StudioColors.Background,
    onBackground = StudioColors.OnBackground,
    surface = StudioColors.Background,
    onSurface = StudioColors.OnBackground,
    surfaceVariant = StudioColors.SurfaceVariant,
    onSurfaceVariant = StudioColors.OnSurfaceVariant,
    outline = StudioColors.Hairline,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val StudioTypography = Typography(
    headlineLarge = StudioType.displayHero,
    headlineMedium = StudioType.pageTitle,
    headlineSmall = StudioType.displayTitle.copy(fontSize = 24.sp, lineHeight = 28.sp),
    titleMedium = StudioType.rowTitle.copy(fontSize = 17.sp, lineHeight = 23.sp),
    bodyLarge = TextStyle(
        fontFamily = InstrumentSans,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 25.sp,
        color = StudioColors.OnBackground,
    ),
    bodyMedium = StudioType.snippet,
    bodySmall = StudioType.rowDesc,
    labelLarge = StudioType.cta,
    labelSmall = StudioType.heroLabel,
)

@Composable
fun WhisperTypeTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = StudioColorScheme,
        typography = StudioTypography,
        content = content,
    )
}

@Composable
fun StudioCard(
    modifier: Modifier = Modifier,
    radius: Dp = StudioLayout.RadiusCard,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(radius),
        color = StudioColors.Surface,
        border = BorderStroke(1.dp, StudioColors.Hairline),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Column(content = content)
    }
}

@Composable
fun StudioCard(
    modifier: Modifier = Modifier,
    radius: Int,
    content: @Composable ColumnScope.() -> Unit,
) = StudioCard(modifier = modifier, radius = radius.dp, content = content)

@Composable
fun StudioPageTitle(text: String, modifier: Modifier = Modifier) {
    Text(text = text, style = StudioType.pageTitle, modifier = modifier.padding(bottom = 14.dp))
}

@Composable
fun StudioCta(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = StudioColors.Accent,
            contentColor = StudioColors.OnAccent,
            disabledContainerColor = StudioColors.SurfaceVariant,
            disabledContentColor = StudioColors.OnSurfaceVariant,
        ),
    ) {
        Text(text = text, style = StudioType.cta, modifier = Modifier.padding(vertical = 4.dp))
    }
}

@Composable
fun StudioGhostCta(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, StudioColors.Accent.copy(alpha = 0.3f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioColors.Accent),
    ) {
        Text(text = text, style = StudioType.cta, modifier = Modifier.padding(vertical = 4.dp))
    }
}

@Composable
fun StudioSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = StudioColors.OnAccent,
            checkedTrackColor = StudioColors.Accent,
            uncheckedThumbColor = StudioColors.OnBackground,
            uncheckedTrackColor = StudioColors.SurfaceVariant,
            uncheckedBorderColor = Color.Transparent,
        ),
    )
}

@Composable
fun AppLogo(
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
) {
    Image(
        // Adaptive @mipmap/ic_launcher crashes painterResource(); use the logo PNG.
        painter = painterResource(R.drawable.ic_bubble_logo),
        contentDescription = null,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f)),
        contentScale = ContentScale.Crop,
    )
}
