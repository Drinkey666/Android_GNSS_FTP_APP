package com.example.ftpget.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF604585),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE7F4),
    onPrimaryContainer = Color(0xFF44305F),
    secondary = Color(0xFF26796F),
    onSecondary = Color.White,
    tertiary = Color(0xFF8B6A42),
    background = Color(0xFFF7F7F6),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF0EFF2),
    onSurface = Color(0xFF23252D),
    onSurfaceVariant = Color(0xFF666A75),
    outline = Color(0xFF858895),
    outlineVariant = Color(0xFFE1E2E6),
    error = Color(0xFFB3261E),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFD4C1EA),
    onPrimary = Color(0xFF3A2757),
    primaryContainer = Color(0xFF493958),
    onPrimaryContainer = Color(0xFFF1E7FA),
    secondary = Color(0xFF8CCFC1),
    onSecondary = Color(0xFF0B3B35),
    tertiary = Color(0xFFE2BE90),
    background = Color(0xFF12151B),
    surface = Color(0xFF1B1E25),
    surfaceVariant = Color(0xFF292C35),
    onSurface = Color(0xFFE9EAF0),
    onSurfaceVariant = Color(0xFFAFB2BE),
    outline = Color(0xFF9296A4),
    outlineVariant = Color(0xFF3A3E49),
    error = Color(0xFFFFB4AB),
)

object GnssSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val screen = 20.dp
}

private val AppShapes = Shapes(
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(16.dp),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.Medium),
    headlineMedium = TextStyle(fontSize = 27.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 23.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
)

internal val LocalGnssDark = compositionLocalOf { false }

@Composable
fun GnssTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGnssDark provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
