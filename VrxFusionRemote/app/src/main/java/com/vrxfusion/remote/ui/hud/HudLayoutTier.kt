package com.vrxfusion.remote.ui.hud

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val hudCenterMinimum = 120.dp
internal val hudInstrumentSpacing = 8.dp

internal data class HudLayout(
    val stickWidth: Dp,
    val sideInset: Dp,
    val attitudeWidth: Dp,
    val mapWidth: Dp,
    val bandWidth: Dp,
    val full: Boolean,
    val numericAttitude: Boolean,
    val centerWidth: Dp,
    val centralHudWidth: Dp
)

/** width 已扣除导航安全区及操纵页两侧边距。 */
internal fun hudLayout(
    width: Dp,
    landscape: Boolean,
    scale: Float,
    showAttitude: Boolean,
    showMap: Boolean,
    showMetrics: Boolean,
    centered: Boolean
): HudLayout {
    val full = landscape && width + 16.dp >= 760.dp
    val margin = if (landscape) hudCenterMinimum + 16.dp else 16.dp
    val stick = minOf(if (landscape) 128.dp else 144.dp, ((width - margin) / 2).coerceAtLeast(0.dp))
    val inset = if (landscape) stick + hudInstrumentSpacing else 0.dp
    var attitude = if (showAttitude && !centered) (if (landscape) 96.dp else 104.dp) * scale else 0.dp
    var map = if (showMap) (if (landscape) 128.dp else 140.dp) * scale else 0.dp
    val band = if (full && showMetrics && !centered) 36.dp else 0.dp
    fun leftWidth() = attitude + band + if (attitude > 0.dp && band > 0.dp) hudInstrumentSpacing else 0.dp
    fun rightWidth() = map + band + if (map > 0.dp && band > 0.dp) hudInstrumentSpacing else 0.dp
    fun available() = width - inset * 2 - leftWidth() - rightWidth()
    fun symmetricSpace() = width - (inset + maxOf(leftWidth(), rightWidth())) * 2
    val desiredCentral = if (full) 220.dp else 176.dp
    if (landscape && (available() < hudCenterMinimum || centered && symmetricSpace() < desiredCentral)) map = 0.dp
    val numericAttitude = landscape && attitude > 0.dp && available() < hudCenterMinimum
    if (numericAttitude) attitude = 0.dp
    return HudLayout(stick, inset, attitude, map, band, full, numericAttitude,
        available().coerceAtLeast(0.dp), minOf(desiredCentral, symmetricSpace().coerceAtLeast(0.dp)))
}
