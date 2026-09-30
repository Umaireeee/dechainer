package io.github.warleysr.urgejournal

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The same "warm paper" dark palette as Déchaîner, so the two feel like one family.
private val Scheme = darkColorScheme(
    primary = Color(0xFFD9A55B),
    onPrimary = Color(0xFF2A1F10),
    primaryContainer = Color(0xFF3A3128),
    onPrimaryContainer = Color(0xFFE8C48A),
    secondary = Color(0xFFC9B79C),
    onSecondary = Color(0xFF2B2418),
    tertiary = Color(0xFF9DB39A),
    error = Color(0xFFE08A74),
    background = Color(0xFF1C1A17),
    onBackground = Color(0xFFEDE6DA),
    surface = Color(0xFF1C1A17),
    onSurface = Color(0xFFEDE6DA),
    surfaceVariant = Color(0xFF2E2A25),
    onSurfaceVariant = Color(0xFFA89F91),
    outline = Color(0xFF6E665B),
    outlineVariant = Color(0xFF34302A),
    surfaceContainerLowest = Color(0xFF181614),
    surfaceContainerLow = Color(0xFF221F1B),
    surfaceContainer = Color(0xFF282420),
    surfaceContainerHigh = Color(0xFF2F2A25),
    surfaceContainerHighest = Color(0xFF37312B)
)

private val Shapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp)
)

private val base = Typography()
private val Type = Typography(
    headlineLarge = base.headlineLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, lineHeight = 42.sp),
    headlineMedium = base.headlineMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 30.sp, lineHeight = 38.sp),
    headlineSmall = base.headlineSmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal),
    titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 24.sp, lineHeight = 32.sp),
    titleMedium = base.titleMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 19.sp, lineHeight = 28.sp, letterSpacing = 0.sp),
    bodyLarge = base.bodyLarge.copy(lineHeight = 26.sp),
    bodyMedium = base.bodyMedium.copy(lineHeight = 22.sp)
)

@Composable
fun UrgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, shapes = Shapes, content = content)
}
