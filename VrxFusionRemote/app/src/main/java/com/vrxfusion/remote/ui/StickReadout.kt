package com.vrxfusion.remote.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrxfusion.remote.ui.theme.HudSurface
import com.vrxfusion.remote.ui.theme.TextPrimary
import com.vrxfusion.remote.ui.theme.TextSecondary
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 死区阈值与 backend/control_config.py 的 `deadzone .08` 对齐。
 *
 * 边界包含规则以「后端是否会产生运动」为准：backend/app_server.py 的 `/api/input`
 * 映射用 `abs(v) <= dz` 归零，因此恰好 0.08 也视为中位（见 StickAxis.text）。
 * UI 发射侧 `ui/RotorPyScreen.kt` 的 VirtualJoystick 用 `abs(v) < .08f`，
 * 在恰好 0.08 会发出一个被后端归零的无效量，属基线既有差异，本期不改发射侧；
 * 读数不显示该无效量，避免出现「读数有量、后端归零」。
 * `ui/components/Joystick.kt` 是不可达旧页的遗留摇杆，不作为任何口径依据。
 */
const val DEFAULT_DEAD_ZONE = 0.08f

/**
 * 摇杆两轴读数文案 —— D2 交互美化。
 *
 * 方向词与 StickSnapshot.axes() 的符号严格对齐：
 *   yaw = -lx，vz = ly，vx = ry，vy = -rx（后端坐标系 Y 向左为正）。
 * 读数只表示本地杆量，不代表指令已被后端确认；真实回执仍以 FMA 与命令回执为准。
 * 百分比是归一化原始杆量，不含后端死区外重新缩放 `(abs(v)-dz)/(1-dz)`，
 * 也不代表实际速度/角速度。
 */
enum class StickAxis(val label: String, val negative: String, val positive: String) {
    /** 左杆横向：左推 lx<0 → yaw>0 左转；右推 lx>0 → yaw<0 右转 */
    YAW("转向", "左", "右"),
    /** 左杆纵向：上推 ly>0 → vz>0 升；下推 ly<0 → vz<0 降 */
    VERTICAL("升降", "降", "升"),
    /** 右杆横向：左推 rx<0 → vy>0 左移；右推 rx>0 → vy<0 右移 */
    LATERAL("横移", "左", "右"),
    /** 右杆纵向：上推 ry>0 → vx>0 前进；下推 ry<0 → vx<0 后退 */
    FORWARD("前后", "后", "前");

    fun text(value: Float, deadZone: Float = DEFAULT_DEAD_ZONE): String {
        // 与后端 /api/input 的 `abs(v) <= dz` 归零规则一致：恰好等于死区也不产生运动。
        if (!value.isFinite() || abs(value) <= deadZone) return "$label 中位"
        val direction = if (value < 0f) negative else positive
        val percent = (abs(value) * 100f).roundToInt().coerceIn(1, 100)
        return "$label $direction $percent%"
    }
}

/**
 * x/y 为 VirtualJoystick `emit` 的归一化杆量（与 onValue 同一口径）。
 * 死区判定比发射侧多包含恰好等于 0.08 的单点，见 DEFAULT_DEAD_ZONE。
 */
fun stickReadoutLines(
    x: Float, y: Float,
    xAxis: StickAxis, yAxis: StickAxis,
    deadZone: Float = DEFAULT_DEAD_ZONE
): Pair<String, String> = xAxis.text(x, deadZone) to yAxis.text(y, deadZone)

/**
 * 杆上方两行读数。按住时显示，松手 200ms 淡出；只做 alpha 动画，不触发布局重算。
 * 高度固定 24dp，读数区常驻占位，避免淡入淡出时摇杆上下跳动。
 */
@Composable
fun StickReadout(x: Float, y: Float, xAxis: StickAxis, yAxis: StickAxis, touching: Boolean) {
    val alpha by animateFloatAsState(
        targetValue = if (touching) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "stick-readout"
    )
    val (first, second) = stickReadoutLines(x, y, xAxis, yAxis)
    Box(
        Modifier.height(24.dp).widthIn(min = 92.dp)
            .alpha(alpha)
            .background(HudSurface.copy(alpha = .78f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(first, color = TextPrimary, fontSize = 9.sp, lineHeight = 10.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(second, color = TextSecondary, fontSize = 9.sp, lineHeight = 10.sp, maxLines = 1)
        }
    }
}
