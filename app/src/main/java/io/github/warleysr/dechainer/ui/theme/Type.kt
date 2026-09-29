package io.github.warleysr.dechainer.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// Headlines in the phone's own serif (Noto Serif on Android) at a regular weight: quiet and
// classic, never shouting. Everything you read or tap stays in the system sans, with a little
// more line height than the default so paragraphs breathe. No extra font files to ship.
private val Display = FontFamily.Serif
private val base = Typography()

val AppTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Display, fontWeight = FontWeight.Normal, letterSpacing = (-0.01).em),
    displayMedium = base.displayMedium.copy(fontFamily = Display, fontWeight = FontWeight.Normal, letterSpacing = (-0.01).em),
    displaySmall = base.displaySmall.copy(fontFamily = Display, fontWeight = FontWeight.Normal),
    headlineLarge = base.headlineLarge.copy(fontFamily = Display, fontWeight = FontWeight.Normal),
    headlineMedium = base.headlineMedium.copy(fontFamily = Display, fontWeight = FontWeight.Normal),
    headlineSmall = base.headlineSmall.copy(fontFamily = Display, fontWeight = FontWeight.Normal, lineHeight = 32.sp),
    titleLarge = base.titleLarge.copy(fontFamily = Display, fontWeight = FontWeight.Normal, lineHeight = 30.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Medium),
    bodyLarge = base.bodyLarge.copy(lineHeight = 26.sp),
    bodyMedium = base.bodyMedium.copy(lineHeight = 22.sp),
    bodySmall = base.bodySmall.copy(lineHeight = 18.sp),
    labelLarge = base.labelLarge.copy(letterSpacing = 0.02.em),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium),
    labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.04.em),
)

/** The block screens' single calm line: larger, lighter, a little more air between the lines. */
val CalmLineStyle: TextStyle
    get() = AppTypography.headlineMedium.copy(lineHeight = 38.sp)
