package com.vrxfusion.remote.ui

import kotlinx.serialization.Serializable

@Serializable
enum class HudDisplayLevel(val label: String) {
    AUTO("自动"), L1("极简"), L2("标准"), L3("完整")
}

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
    val instrumentScale: Float = 1f,
    val level: HudDisplayLevel = HudDisplayLevel.L2,
    val dimDuringFlight: Boolean = true,
    val showCentralHud: Boolean = false,
    val centerHudOpacity: Float = .85f
) {
    fun normalized(): HudPreferences {
        val defaults=HudPreferences()
        fun clean(value: Float,fallback: Float)=if (value.isFinite()) value.coerceIn(.2f,1f) else fallback
        return copy(joystickOpacity=clean(joystickOpacity,defaults.joystickOpacity),attitudeOpacity=clean(attitudeOpacity,defaults.attitudeOpacity),
            mapOpacity=clean(mapOpacity,defaults.mapOpacity),positionOpacity=clean(positionOpacity,defaults.positionOpacity),
            instrumentScale=if (instrumentScale.isFinite()) instrumentScale.coerceIn(.8f,1f) else 1f,
            centerHudOpacity=clean(centerHudOpacity,defaults.centerHudOpacity))
    }
}
