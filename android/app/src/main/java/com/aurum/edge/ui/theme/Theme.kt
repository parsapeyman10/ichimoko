package com.aurum.edge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

object AurumColors {
    val Gold = Color(0xFFF3C65F)
    val Orange = Color(0xFFFF9F1C)
    // TradingView Pro Emerald & Coral palette
    val Green = Color(0xFF089981)
    val Red = Color(0xFFF23645)
    val Cyan = Color(0xFF38BDF8)
    val Purple = Color(0xFFA78BFA)
    val Bg = Color(0xFF06080C)
    val Background = Bg
    val Surface = Color(0xFF0D1117)
    val SurfaceAlt = Color(0xFF151B24)
    val ChartBg = Color(0xFF090C10)
    val Grid = Color(0xFF161C26)
    val Line = Color(0xFF212938)
    val Border = Line
    val TextPrimary = Color(0xFFF0F4F8)
    val TextSecondary = Color(0xFF9FB0C4)
    val TextMuted = Color(0xFF7D8DA1)
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
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 11.5.sp, lineHeight = 17.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 10.5.sp, lineHeight = 14.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, lineHeight = 16.sp),
)

@Composable
fun AurumTheme(content: @Composable () -> Unit) {
    isSystemInDarkTheme()
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = AurumDarkScheme,
            typography = AurumTypography,
            content = content,
        )
    }
}
