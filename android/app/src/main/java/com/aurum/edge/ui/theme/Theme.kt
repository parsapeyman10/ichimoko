package com.aurum.edge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object AurumColors {
    val Gold = Color(0xFFF1BC4B)
    val Green = Color(0xFF24C69A)
    val Red = Color(0xFFEF5D6C)
    val Cyan = Color(0xFF57C7D4)
    val Purple = Color(0xFF9D83E9)
    val Bg = Color(0xFF07090D)
    val Surface = Color(0xFF0F131A)
    val SurfaceAlt = Color(0xFF141922)
    val ChartBg = Color(0xFF0B0E13)
    val Grid = Color(0xFF171B23)
    val Line = Color(0xFF232A36)
    val TextPrimary = Color(0xFFE6E9F0)
    val TextSecondary = Color(0xFF9AA3B2)
    val TextMuted = Color(0xFF8994A6)
}

private val AurumDarkScheme = darkColorScheme(
    primary = AurumColors.Gold,
    onPrimary = Color(0xFF14100A),
    primaryContainer = Color(0xFF2B2619),
    onPrimaryContainer = AurumColors.Gold,
    secondary = AurumColors.Cyan,
    onSecondary = Color(0xFF06171A),
    tertiary = AurumColors.Purple,
    background = AurumColors.Bg,
    onBackground = AurumColors.TextPrimary,
    surface = AurumColors.Surface,
    onSurface = AurumColors.TextPrimary,
    surfaceVariant = AurumColors.SurfaceAlt,
    onSurfaceVariant = AurumColors.TextSecondary,
    error = AurumColors.Red,
    onError = Color(0xFF1A0508),
    outline = AurumColors.Line,
)

private val AurumTypography = Typography(
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp),
    // Monospace distorts Persian glyphs; keep numbers explicit in the formatter instead.
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp),
)

@Composable
fun AurumTheme(content: @Composable () -> Unit) {
    // The terminal is intentionally dark-only: chart contrast matters more than theme switching.
    isSystemInDarkTheme()
    MaterialTheme(colorScheme = AurumDarkScheme, typography = AurumTypography, content = content)
}
