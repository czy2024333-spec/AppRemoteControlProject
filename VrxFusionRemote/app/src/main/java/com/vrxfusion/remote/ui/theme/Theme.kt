package com.vrxfusion.remote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val VrxColorScheme = darkColorScheme(
    primary = AccentBlue,
    onPrimary = TextPrimary,
    secondary = AccentBlue,
    background = BgDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceCard,
    onSurfaceVariant = TextSecondary,
    error = EstopRed
)

@Composable
fun VrxTheme(content: @Composable () -> Unit) {
    // 遥控台界面固定深色（室外强光/夜间作业可读性优先），不随系统切换
    MaterialTheme(colorScheme = VrxColorScheme, content = content)
}
