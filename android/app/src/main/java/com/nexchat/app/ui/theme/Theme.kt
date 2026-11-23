package com.nexchat.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.nexchat.app.core.settings.ThemeMode

/**
 * Nexchat's design tokens.
 *
 * The palette is deliberately different between light and dark: the light
 * theme reads as calm paper with deep teal ink, while the dark theme reads as
 * a deep ink surface with a restrained mint accent. They are tuned
 * independently rather than being simple inversions.
 */
data class NexPalette(
    val dark: Boolean,
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val glowBlue: Color,
    val glowViolet: Color,
    val glass: Color,
    val glassStrong: Color,
    val glassBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val accentDeep: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val bubbleIn: Color,
    val bubbleOutStart: Color,
    val bubbleOutEnd: Color,
    val danger: Color,
    val success: Color,
    val divider: Color,
    val navGlass: Color,
    val silver: Color,
)

val LightSilver = NexPalette(
    dark = false,
    backgroundTop = Color(0xFFF6F7F4),
    backgroundBottom = Color(0xFFE8EBE6),
    glowBlue = Color(0x0A1F6F63),
    glowViolet = Color(0x081F6F63),
    glass = Color(0xFFFFFFFF),
    glassStrong = Color(0xFFFFFFFF),
    glassBorder = Color(0x221F6F63),
    textPrimary = Color(0xFF16201E),
    textSecondary = Color(0xFF495651),
    textMuted = Color(0xFF7A8782),
    accent = Color(0xFF1F6F63),
    accentDeep = Color(0xFF18574E),
    accentSoft = Color(0x1F1F6F63),
    onAccent = Color(0xFFFFFFFF),
    bubbleIn = Color(0xFFFFFFFF),
    bubbleOutStart = Color(0xFF25796D),
    bubbleOutEnd = Color(0xFF1B5B52),
    danger = Color(0xFFBE3F4E),
    success = Color(0xFF137A5C),
    divider = Color(0x1A16201E),
    navGlass = Color(0xF5FFFFFF),
    silver = Color(0xFFC9D1CB),
)

val DarkSilver = NexPalette(
    dark = true,
    backgroundTop = Color(0xFF111A19),
    backgroundBottom = Color(0xFF080D0D),
    glowBlue = Color(0x0A3DD0B8),
    glowViolet = Color(0x083DD0B8),
    glass = Color(0xFF182320),
    glassStrong = Color(0xFF1E2A27),
    glassBorder = Color(0x26FFFFFF),
    textPrimary = Color(0xFFEAF2EF),
    textSecondary = Color(0xFFA9BAB5),
    textMuted = Color(0xFF748680),
    accent = Color(0xFF4BC4AC),
    accentDeep = Color(0xFF2E9B85),
    accentSoft = Color(0x264BC4AC),
    onAccent = Color(0xFF062019),
    bubbleIn = Color(0xFF1A2624),
    bubbleOutStart = Color(0xFF1E655A),
    bubbleOutEnd = Color(0xFF174B43),
    danger = Color(0xFFFF7A86),
    success = Color(0xFF52D4AB),
    divider = Color(0x1FFFFFFF),
    navGlass = Color(0xE6101716),
    silver = Color(0xFFD3DED9),
)

val LocalNexPalette = staticCompositionLocalOf { LightSilver }

private val lightColors = lightColorScheme(
    primary = LightSilver.accent,
    onPrimary = LightSilver.onAccent,
    primaryContainer = Color(0xFFDCEAE6),
    onPrimaryContainer = Color(0xFF12322D),
    secondary = Color(0xFF5E716B),
    background = LightSilver.backgroundTop,
    onBackground = LightSilver.textPrimary,
    surface = LightSilver.glass,
    onSurface = LightSilver.textPrimary,
    surfaceVariant = Color(0xFFE4E9E4),
    onSurfaceVariant = LightSilver.textSecondary,
    error = LightSilver.danger,
)

private val darkColors = darkColorScheme(
    primary = DarkSilver.accent,
    onPrimary = DarkSilver.onAccent,
    primaryContainer = Color(0xFF1C3C35),
    onPrimaryContainer = Color(0xFFD8F3EC),
    secondary = Color(0xFF91A9A2),
    background = DarkSilver.backgroundTop,
    onBackground = DarkSilver.textPrimary,
    surface = DarkSilver.glassStrong,
    onSurface = DarkSilver.textPrimary,
    surfaceVariant = Color(0xFF22302D),
    onSurfaceVariant = DarkSilver.textSecondary,
    error = DarkSilver.danger,
)

/**
 * A restrained type ramp. Tracking is kept close to zero so small labels and
 * identifiers do not visually scatter, and every line box is proportional to
 * its font size so baseline alignment stays predictable.
 */
val NexTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 40.sp,
        lineHeight = 46.sp,
        letterSpacing = (-0.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.3).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.2).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 19.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.2.sp,
    ),
)

/**
 * Use this whenever an ad-hoc font size is unavoidable: it keeps the line box
 * proportional to the glyph, which prevents the vertical misalignment that
 * happens when `copy(fontSize = ...)` inherits a larger line height.
 */
fun TextStyle.atSize(size: TextUnit): TextStyle = copy(
    fontSize = size,
    lineHeight = size * 1.3f,
)

@Composable
fun NexChatTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val palette = if (dark) DarkSilver else LightSilver
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !dark
        }
    }
    CompositionLocalProvider(LocalNexPalette provides palette) {
        MaterialTheme(
            colorScheme = if (dark) darkColors else lightColors,
            typography = NexTypography,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(8.dp),
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(16.dp),
                large = RoundedCornerShape(20.dp),
                extraLarge = RoundedCornerShape(24.dp),
            ),
            content = content,
        )
    }
}

@Composable
fun nexPalette(): NexPalette = LocalNexPalette.current
