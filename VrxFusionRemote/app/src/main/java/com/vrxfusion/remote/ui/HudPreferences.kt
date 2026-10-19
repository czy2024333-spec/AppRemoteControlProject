package com.vrxfusion.remote.ui

import kotlinx.serialization.Serializable

/** 仅管理HUD显示，不接触控制参数、权限或保护开关。数值越低越透明。 */
@Serializable
data class HudPreferences(
    val joystickOpacity: Float = .65f,
    val attitudeOpacity: Float = .85f,
    val mapOpacity: Float = .75f,
    val positionOpacity: Float = .85f,
    val showAttitude: Boolean = true,
    val showMap: Boolean = true,
    val showPosition: Boolean = true,
    val swapInstruments: Boolean = false,
    val instrumentScale: Float = 1f
) {
    fun normalized(): HudPreferences {
        val defaults=HudPreferences()
        fun clean(value: Float,fallback: Float)=if (value.isFinite()) value.coerceIn(.2f,1f) else fallback
        return copy(joystickOpacity=clean(joystickOpacity,defaults.joystickOpacity),attitudeOpacity=clean(attitudeOpacity,defaults.attitudeOpacity),
            mapOpacity=clean(mapOpacity,defaults.mapOpacity),positionOpacity=clean(positionOpacity,defaults.positionOpacity),
            instrumentScale=if (instrumentScale.isFinite()) instrumentScale.coerceIn(.8f,1f) else 1f)
    }
}
