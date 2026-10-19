package com.vrxfusion.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrxfusion.remote.MainViewModel
import com.vrxfusion.remote.rotorpy.*
import com.vrxfusion.remote.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.*

/** 同一个操控页面根据屏幕尺寸适配布局，不再打开独立横屏窗口。 */
@Composable
fun RotorPyScreen(vm: MainViewModel, onBack: () -> Unit) {
    FlightControlScreen(vm, onBack)
}

/**
 * 手势持有期间持续跟踪真实按下状态，即便保护禁用输入也要等到物理松手。
 * 使用最新回调，避免双摇杆组合时闭包保留另一个摇杆的旧数值。
 */
@Composable
fun VirtualJoystick(enabled: Boolean,onValue: (Offset)->Unit,onTouchChanged: (Boolean)->Unit={},
    sizeDp: androidx.compose.ui.unit.Dp=132.dp) {
    val base=sizeDp; val knob=minOf(48.dp,base*.36f)
    val radius=with(LocalDensity.current) { (base-knob).toPx()/2 }
    val currentEnabled by rememberUpdatedState(enabled)
    val emit by rememberUpdatedState(onValue)
    val touch by rememberUpdatedState(onTouchChanged)
    var drag by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(enabled) { if (!enabled) { drag=Offset.Zero; emit(Offset.Zero) } }
    Box(Modifier.size(base).clip(CircleShape).background(SurfaceDark).pointerInput(radius) {
        awaitEachGesture {
            val down=awaitFirstDown(requireUnconsumed=false)
            down.consume(); touch(true)
            var previous=down.position
            // 禁用状态下按住也属于真实按下，不能把它误判为“已松手”来解除保护。
            var blockedUntilRelease=!currentEnabled
            try {
                do {
                    val event=awaitPointerEvent()
                    val change=event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    change.consume()
                    // 禁用过的手势必须真正松手，弹窗关闭/状态恢复不能让原按压重新发运动。
                    if (!currentEnabled) blockedUntilRelease=true
                    if (currentEnabled && !blockedUntilRelease) {
                        val next=drag+(change.position-previous)
                        drag=if (next.getDistance() > radius) next*(radius/next.getDistance()) else next
                        val x=(drag.x/radius).coerceIn(-1f,1f); val y=(-drag.y/radius).coerceIn(-1f,1f)
                        emit(Offset(if (abs(x)<.08f) 0f else x,if (abs(y)<.08f) 0f else y))
                    } else { drag=Offset.Zero; emit(Offset.Zero) }
                    previous=change.position
                } while (true)
            } finally { drag=Offset.Zero; emit(Offset.Zero); touch(false) }
        }
    },contentAlignment=Alignment.Center) {
        Box(Modifier.size(base-24.dp,1.dp).background(TextSecondary.copy(alpha=.25f)))
        Box(Modifier.size(1.dp,base-24.dp).background(TextSecondary.copy(alpha=.25f)))
        Box(Modifier.offset { IntOffset(drag.x.toInt(),drag.y.toInt()) }.size(knob).clip(CircleShape)
            .background(if (enabled) AccentBlue else TextSecondary.copy(alpha=.35f)))
    }
}
