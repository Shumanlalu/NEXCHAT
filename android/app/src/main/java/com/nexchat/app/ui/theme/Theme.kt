package com.nexchat.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.nexchat.app.core.settings.ThemeMode

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
    backgroundTop = Color(0xFFF4F6FB),
    backgroundBottom = Color(0xFFD9DEEA),
    glowBlue = Color(0x556F7DFF),
    glowViolet = Color(0x446C4DFF),
    glass = Color(0xA8FFFFFF),
    glassStrong = Color(0xE8FFFFFF),
    glassBorder = Color(0xCCFFFFFF),
    textPrimary = Color(0xFF11152A),
    textSecondary = Color(0xFF4E566D),
    textMuted = Color(0xFF7D859B),
    accent = Color(0xFF4E60F5),
    accentDeep = Color(0xFF3A45C9),
    accentSoft = Color(0x1F4E60F5),
    bubbleIn = Color(0xE7FFFFFF),
    bubbleOutStart = Color(0xFF343D6D),
    bubbleOutEnd = Color(0xFF1A2042),
    danger = Color(0xFFCF3D5D),
    success = Color(0xFF1C9A6B),
    divider = Color(0x2A253055),
    navGlass = Color(0xE8FDFDFF),
    silver = Color(0xFFC8D0DE),
)

val DarkSilver = NexPalette(
    dark = true,
    backgroundTop = Color(0xFF111624),
    backgroundBottom = Color(0xFF070A12),
    glowBlue = Color(0x445E6BFF),
    glowViolet = Color(0x3D7C4DFF),
    glass = Color(0x1CFFFFFF),
    glassStrong = Color(0x2BFFFFFF),
    glassBorder = Color(0x33FFFFFF),
    textPrimary = Color(0xFFF1F4FC),
    textSecondary = Color(0xFFB9C1D6),
    textMuted = Color(0xFF7F889F),
    accent = Color(0xFF7C8AFF),
    accentDeep = Color(0xFF5C68E8),
    accentSoft = Color(0x2A7C8AFF),
    bubbleIn = Color(0x14FFFFFF),
    bubbleOutStart = Color(0xFF4A56C8),
    bubbleOutEnd = Color(0xFF232A63),
    danger = Color(0xFFFF6B86),
    success = Color(0xFF4ED3A3),
    divider = Color(0x24FFFFFF),
    navGlass = Color(0xB3121725),
    silver = Color(0xFFB9C3D6),
)

val LocalNexPalette = staticCompositionLocalOf { LightSilver }

private val lightColors = lightColorScheme(
    primary = LightSilver.accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE1FF),
    onPrimaryContainer = Color(0xFF12163A),
    secondary = Color(0xFF6C5CE7),
    background = LightSilver.backgroundTop,
    onBackground = LightSilver.textPrimary,
    surface = Color(0xFFF9FAFD),
    onSurface = LightSilver.textPrimary,
    surfaceVariant = Color(0xFFE6EAF3),
    onSurfaceVariant = LightSilver.textSecondary,
    error = LightSilver.danger,
)

private val darkColors = darkColorScheme(
    primary = DarkSilver.accent,
    onPrimary = Color(0xFF0B0F1B),
    primaryContainer = Color(0xFF2A326B),
    onPrimaryContainer = Color(0xFFEFF1FF),
    secondary = Color(0xFFB9A7FF),
    background = DarkSilver.backgroundTop,
    onBackground = DarkSilver.textPrimary,
    surface = Color(0xFF161C2C),
    onSurface = DarkSilver.textPrimary,
    surfaceVariant = Color(0xFF222A3D),
    onSurfaceVariant = DarkSilver.textSecondary,
    error = DarkSilver.danger,
)

val NexTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 46.sp,
        lineHeight = 50.sp,
        letterSpacing = (-1.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 41.sp,
        letterSpacing = (-1).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 35.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.4).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 25.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 21.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
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
            shapes = androidx.compose.material3.Shapes(
                extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                small = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                medium = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                large = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(36.dp),
            ),
            content = content,
        )
    }
}

@Composable
fun nexPalette(): NexPalette = LocalNexPalette.current
