package com.vrxfusion.remote.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

private val VrxColorScheme = darkColorScheme(
    primary = AccentBlue,
    onPrimary = BgDark,
    primaryContainer = ControlFill,
    onPrimaryContainer = ControlText,
    secondary = AccentBlue,
    secondaryContainer = ControlFill,
    onSecondaryContainer = ControlText,
    outline = Color(0xFF69727F),
    outlineVariant = Color(0xFF3C434D),
    background = BgDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceCard,
    onSurfaceVariant = TextSecondary,
    error = EstopRed
)

private val defaults = Typography()
private val consoleTypography = defaults.copy(
    bodyLarge = defaults.bodyLarge.copy(letterSpacing = 0.sp),
    bodyMedium = defaults.bodyMedium.copy(letterSpacing = 0.sp),
    bodySmall = defaults.bodySmall.copy(letterSpacing = 0.sp),
    labelLarge = defaults.labelLarge.copy(letterSpacing = 0.sp),
    labelMedium = defaults.labelMedium.copy(letterSpacing = 0.sp),
    labelSmall = defaults.labelSmall.copy(letterSpacing = 0.sp)
)

@Composable
fun VrxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = VrxColorScheme, typography = consoleTypography, content = content)
}
