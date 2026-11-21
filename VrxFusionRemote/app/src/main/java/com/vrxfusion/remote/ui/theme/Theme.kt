package com.vrxfusion.remote.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrxfusion.remote.R

private val VrxColorScheme = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    primaryContainer = ControlFill,
    onPrimaryContainer = ControlText,
    secondary = AccentBlue,
    secondaryContainer = ControlFill,
    onSecondaryContainer = ControlText,
    outline = Color(0xFF48484A),
    outlineVariant = Separator,
    background = BgDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceCard,
    onSurfaceVariant = TextSecondary,
    error = EstopRed
)

// 按钮文字用半粗体，与 iOS 的按钮层级一致；字号保持原值，避免挤压横屏操纵页。
private val defaults = Typography()
private val consoleTypography = defaults.copy(
    bodyLarge = defaults.bodyLarge.copy(letterSpacing = 0.sp),
    bodyMedium = defaults.bodyMedium.copy(letterSpacing = 0.sp),
    bodySmall = defaults.bodySmall.copy(letterSpacing = 0.sp),
    labelLarge = defaults.labelLarge.copy(letterSpacing = 0.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = defaults.labelMedium.copy(letterSpacing = 0.sp),
    labelSmall = defaults.labelSmall.copy(letterSpacing = 0.sp)
)

private val reviewFont = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold)
)
private val interTypography = consoleTypography.copy(
    bodyLarge = consoleTypography.bodyLarge.copy(fontFamily = reviewFont),
    bodyMedium = consoleTypography.bodyMedium.copy(fontFamily = reviewFont),
    bodySmall = consoleTypography.bodySmall.copy(fontFamily = reviewFont),
    labelLarge = consoleTypography.labelLarge.copy(fontFamily = reviewFont),
    labelMedium = consoleTypography.labelMedium.copy(fontFamily = reviewFont),
    labelSmall = consoleTypography.labelSmall.copy(fontFamily = reviewFont)
)

// iOS 的圆角偏大且统一：小控件 10、卡片 14~20、面板 28。
private val appleShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun VrxTheme(useReviewInter: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VrxColorScheme,
        typography = if (useReviewInter) interTypography else consoleTypography,
        shapes = appleShapes,
        content = content
    )
}
