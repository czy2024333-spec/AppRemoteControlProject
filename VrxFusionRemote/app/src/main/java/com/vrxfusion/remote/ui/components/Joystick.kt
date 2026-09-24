package com.vrxfusion.remote.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vrxfusion.remote.ui.theme.AccentBlue
import com.vrxfusion.remote.ui.theme.SurfaceCard
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 虚拟摇杆 —— APP-007：
 * 支持死区、灵敏度（maxSpeed 映射）、归一化输出；
 * 松开自动回中并回调 onRelease（配合看门狗 SAFE-004）。
 *
 * 输出约定：x ∈ [-1,1] 右为正（vy），y ∈ [-1,1] 上为正（vx 前进）。
 * 灵敏度/死区可通过参数配置（APP-007 摇杆参数可配置）。
 */
@Composable
fun Joystick(
    size: Dp = 150.dp,
    deadZone: Float = 0.08f,
    onInput: (x: Float, y: Float) -> Unit,
    onRelease: () -> Unit
) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .size(size)
            .pointerInput(Unit) {
                // PointerInputScope.size：像素尺寸（IntSize），避免与 Dp 参数同名遮蔽
                val area = this.size
                val r = minOf(area.width, area.height) / 2f
                detectDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        knob = (offset - Offset(r, r)) / r
                        emit(knob.x, knob.y, deadZone, onInput)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        knob = Offset(knob.x + amount.x / r, knob.y + amount.y / r)
                        val len = hypot(knob.x.toDouble(), knob.y.toDouble()).toFloat()
                        if (len > 1f) knob = Offset(knob.x / len, knob.y / len)
                        emit(knob.x, knob.y, deadZone, onInput)
                    },
                    onDragEnd = {
                        dragging = false
                        knob = Offset.Zero
                        onRelease()
                    },
                    onDragCancel = {
                        dragging = false
                        knob = Offset.Zero
                        onRelease()
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.size(size)) {
            // DrawScope.size：Size（px）
            val r = minOf(this.size.width, this.size.height) / 2f
            val center = Offset(r, r)
            // 底盘
            drawCircle(color = SurfaceCard, radius = r, center = center)
            drawCircle(color = Color(0xFF39424D), radius = r, center = center, style = Stroke(2f))
            // 十字参考线
            drawLine(Color(0xFF39424D), center - Offset(r * 0.8f, 0f), center + Offset(r * 0.8f, 0f), 1.5f)
            drawLine(Color(0xFF39424D), center - Offset(0f, r * 0.8f), center + Offset(0f, r * 0.8f), 1.5f)
            // 摇杆头
            val knobCenter = center + Offset(knob.x * r * 0.75f, knob.y * r * 0.75f)
            drawCircle(
                color = if (dragging) AccentBlue else Color(0xFF556170),
                radius = r * 0.28f,
                center = knobCenter
            )
        }
    }
}

/** 应用死区并归一化输出 */
private fun emit(x: Float, y: Float, deadZone: Float, onInput: (Float, Float) -> Unit) {
    fun dz(v: Float): Float = if (abs(v) < deadZone) 0f else v
    onInput(dz(x), dz(-y)) // y 轴翻转：屏幕向下为正 → 前进为正
}
