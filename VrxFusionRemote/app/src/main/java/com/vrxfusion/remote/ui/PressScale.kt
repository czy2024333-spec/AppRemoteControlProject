package com.vrxfusion.remote.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * D3 统一按压回弹参数：按下时缩放到 [scale]、向下沉 [sinkDp]，松手用 [durationMs] 回弹。
 *
 * 这里只描述**视觉目标**，不含任何指令发送；点击仍由原 clickable / Button 的 onClick
 * 即时执行。参数受约束，避免误配出放大或负向下沉的按键。
 */
data class PressScaleSpec(
    val scale: Float = 0.985f,
    val sinkDp: Float = 1f,
    val durationMs: Int = 120,
) {
    init {
        require(scale in 0.5f..1f) { "scale 必须落在 0.5..1，按下态不得放大按钮" }
        require(sinkDp >= 0f && sinkDp.isFinite()) { "sinkDp 必须是非负有限值" }
        require(durationMs >= 0) { "durationMs 不能为负" }
    }
}

/** 常规按钮：98.5% + 1dp 下沉 + 120ms 回弹。 */
val DefaultPressScale = PressScaleSpec()

/** 停止键：97.5% + 2dp 下沉，手感更沉，与危险操作的语义一致。 */
val StopPressScale = PressScaleSpec(scale = 0.975f, sinkDp = 2f)

/** 插值后的视觉状态。纯数据，便于 JVM 单测，不依赖 Compose 运行时。 */
data class PressVisual(val scale: Float, val sinkDp: Float) {
    companion object { val REST = PressVisual(1f, 0f) }
}

/** 按压目标进度：仅「按下 **且** 系统动效开启」为 1；松手或减弱动态均为 0。 */
fun pressTargetProgress(pressed: Boolean, motionEnabled: Boolean): Float =
    if (pressed && motionEnabled) 1f else 0f

/**
 * 由进度插值出视觉状态。progress 越界或非有限值收敛到 [0,1]，
 * 保证任何情况下都不会出现越界缩放 / 位移；0 恒等于 [PressVisual.REST]。
 */
fun pressVisual(progress: Float, spec: PressScaleSpec = DefaultPressScale): PressVisual {
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    return PressVisual(
        scale = 1f + (spec.scale - 1f) * p,
        sinkDp = spec.sinkDp * p,
    )
}

/**
 * 统一按压回弹 —— 两周分期 D3。
 *
 * 只读取 [source] 的 pressed 状态做 `graphicsLayer` 变换：
 * **不接管点击、不延迟 onClick、不排队、不重复触发**，因此不会额外产生 `/api/input`。
 * 缩放与位移都在 graphicsLayer 内读取动画 [State]，不触发布局重算；
 * 尊重 [LocalAppMotionEnabled]，系统关闭动画时用 `snap()` 直接回到常态，无缩放与位移。
 *
 * [animationSpec] 仅供既有的 [motionPress] 保留 spring 手感；新增按钮用默认 tween(120ms)。
 */
@Composable
fun Modifier.pressScale(
    source: MutableInteractionSource,
    spec: PressScaleSpec = DefaultPressScale,
    animationSpec: AnimationSpec<Float>? = null,
): Modifier {
    val pressed by source.collectIsPressedAsState()
    val motionEnabled = LocalAppMotionEnabled.current
    val target = pressTargetProgress(pressed, motionEnabled)
    val progress: State<Float> = animateFloatAsState(
        targetValue = target,
        animationSpec = when {
            !motionEnabled -> snap()
            animationSpec != null -> animationSpec
            else -> tween(spec.durationMs)
        },
        label = "press-progress",
    )
    val sinkPx = with(LocalDensity.current) { spec.sinkDp.dp.toPx() }
    return graphicsLayer {
        val p = if (progress.value.isFinite()) progress.value.coerceIn(0f, 1f) else 0f
        val v = pressVisual(p, spec)
        scaleX = v.scale
        scaleY = v.scale
        translationY = sinkPx * p
    }
}
