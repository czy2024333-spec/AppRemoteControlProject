package com.vrxfusion.remote.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrxfusion.remote.ui.theme.SurfaceDark
import com.vrxfusion.remote.ui.theme.TextPrimary
import com.vrxfusion.remote.ui.theme.TextSecondary
import com.vrxfusion.remote.ui.theme.AccentBlue
import com.vrxfusion.remote.ui.theme.WarnAmber
import kotlin.math.abs

/** 标签只在按住被禁用的摇杆时绘制，使用原有底部留白。 */
@Composable
internal fun StickPad(
    label: String,
    enabled: Boolean,
    blockLabel: String?,
    opacity: Float,
    onValue: (Offset) -> Unit,
    onTouch: (Boolean) -> Unit,
    modifier: Modifier
) {
    var touched by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf(Offset.Zero) }
    val outputActive = enabled && output != Offset.Zero
    val activity = when {
        !enabled -> "受限"
        outputActive -> "有效输入"
        else -> "回中"
    }
    val ring = when {
        !enabled && touched -> WarnAmber
        outputActive -> AccentBlue
        else -> null
    }
    Box(modifier) {
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(bottom = 24.dp).semantics {
                contentDescription = label
                stateDescription = activity
            }.testTag("stick-activity"),
            contentAlignment = Alignment.Center
        ) {
            val diameter = minOf(128.dp, maxWidth, maxHeight.coerceAtLeast(24.dp))
            val ringModifier = if (ring == null) Modifier else Modifier.border(2.dp, ring, CircleShape)
            Box(Modifier.alpha(opacity).then(ringModifier)) {
                VirtualJoystick(enabled, { value ->
                    output = value
                    onValue(value)
                }, { pressed ->
                    touched = pressed
                    onTouch(pressed)
                }, diameter)
            }
        }
        if (touched && !enabled && blockLabel != null) {
            Text(
                blockLabel, color = TextPrimary, fontSize = 11.sp, lineHeight = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .background(SurfaceDark, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp).testTag("input-block-chip")
            )
        }
    }
}

/**
 * 手势持有期间持续跟踪真实按下状态，即便保护禁用输入也要等到物理松手。
 * 使用最新回调，避免双摇杆组合时闭包保留另一个摇杆的旧数值。
 */
@Composable
internal fun VirtualJoystick(enabled: Boolean,onValue: (Offset)->Unit,onTouchChanged: (Boolean)->Unit={},
    sizeDp: androidx.compose.ui.unit.Dp=132.dp) {
    val base=sizeDp; val knob=minOf(48.dp,base*.36f)
    val radius=with(LocalDensity.current) { (base-knob).toPx()/2 }
    val currentEnabled by rememberUpdatedState(enabled)
    val emit by rememberUpdatedState(onValue)
    val touch by rememberUpdatedState(onTouchChanged)
    var drag by remember { mutableStateOf(Offset.Zero) }
    var pointerDown by remember { mutableStateOf(false) }
    var blockedUntilRelease by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) {
        if (!enabled) {
            if (pointerDown) blockedUntilRelease = true
            drag = Offset.Zero
            emit(Offset.Zero)
        }
    }
    Box(Modifier.size(base).clip(CircleShape).background(SurfaceDark).border(1.dp,Color.White.copy(alpha=.14f),CircleShape).pointerInput(radius) {
        awaitEachGesture {
            val down=awaitFirstDown(requireUnconsumed=false)
            down.consume()
            pointerDown = true
            touch(true)
            var previous=down.position
            // 禁用状态下按住也属于真实按下，不能把它误判为“已松手”来解除保护。
            blockedUntilRelease = !currentEnabled
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
            } finally {
                drag = Offset.Zero
                pointerDown = false
                blockedUntilRelease = false
                emit(Offset.Zero)
                touch(false)
            }
        }
    },contentAlignment=Alignment.Center) {
        Box(Modifier.size(base*.5f).border(1.dp,Color.White.copy(alpha=.08f),CircleShape))
        Box(Modifier.size(base-24.dp,1.dp).background(TextSecondary.copy(alpha=.25f)))
        Box(Modifier.size(1.dp,base-24.dp).background(TextSecondary.copy(alpha=.25f)))
        Box(Modifier.offset { IntOffset(drag.x.toInt(),drag.y.toInt()) }.size(knob).clip(CircleShape)
            .background(if (enabled) Color.White.copy(alpha=.92f) else TextSecondary.copy(alpha=.35f)))
    }
}
