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
    backgroundTop = Color(0xFFF7F8FB),
    backgroundBottom = Color(0xFFC9D0DA),
    glowBlue = Color(0x4A6C8AA6),
    glowViolet = Color(0x3D8C96A3),
    glass = Color(0xB8FFFFFF),
    glassStrong = Color(0xF2FFFFFF),
    glassBorder = Color(0xD9FFFFFF),
    textPrimary = Color(0xFF12161C),
    textSecondary = Color(0xFF4A525E),
    textMuted = Color(0xFF78818E),
    accent = Color(0xFF4E657D),
    accentDeep = Color(0xFF34495E),
    accentSoft = Color(0x1F4E657D),
    bubbleIn = Color(0xE8FFFFFF),
    bubbleOutStart = Color(0xFF667384),
    bubbleOutEnd = Color(0xFF303945),
    danger = Color(0xFFC24357),
    success = Color(0xFF1F8A68),
    divider = Color(0x2A2B3440),
    navGlass = Color(0xF2FAFBFC),
    silver = Color(0xFFC2CAD5),
)

val DarkSilver = NexPalette(
    dark = true,
    backgroundTop = Color(0xFF191D23),
    backgroundBottom = Color(0xFF07090C),
    glowBlue = Color(0x405D7C99),
    glowViolet = Color(0x3D7D8794),
    glass = Color(0x1FFFFFFF),
    glassStrong = Color(0x30FFFFFF),
    glassBorder = Color(0x3DFFFFFF),
    textPrimary = Color(0xFFF3F5F8),
    textSecondary = Color(0xFFC2C9D2),
    textMuted = Color(0xFF858E9A),
    accent = Color(0xFFB6C4D4),
    accentDeep = Color(0xFF8493A5),
    accentSoft = Color(0x2AB6C4D4),
    bubbleIn = Color(0x16FFFFFF),
    bubbleOutStart = Color(0xFF5D6A7A),
    bubbleOutEnd = Color(0xFF262E38),
    danger = Color(0xFFFF7A8A),
    success = Color(0xFF56D5AE),
    divider = Color(0x24FFFFFF),
    navGlass = Color(0xC0161A20),
    silver = Color(0xFFD3DAE4),
)

val LocalNexPalette = staticCompositionLocalOf { LightSilver }

private val lightColors = lightColorScheme(
    primary = LightSilver.accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE3EB),
    onPrimaryContainer = Color(0xFF1B2633),
    secondary = Color(0xFF7E8B99),
    background = LightSilver.backgroundTop,
    onBackground = LightSilver.textPrimary,
    surface = Color(0xFFF9FAFC),
    onSurface = LightSilver.textPrimary,
    surfaceVariant = Color(0xFFE3E7ED),
    onSurfaceVariant = LightSilver.textSecondary,
    error = LightSilver.danger,
)

private val darkColors = darkColorScheme(
    primary = DarkSilver.accent,
    onPrimary = Color(0xFF1A2330),
    primaryContainer = Color(0xFF33404F),
    onPrimaryContainer = Color(0xFFEAF0F6),
    secondary = Color(0xFF9EABB9),
    background = DarkSilver.backgroundTop,
    onBackground = DarkSilver.textPrimary,
    surface = Color(0xFF1B2027),
    onSurface = DarkSilver.textPrimary,
    surfaceVariant = Color(0xFF262C34),
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
