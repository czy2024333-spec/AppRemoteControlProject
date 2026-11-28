package com.vrxfusion.remote

import com.vrxfusion.remote.ui.DefaultPressScale
import com.vrxfusion.remote.ui.PressScaleSpec
import com.vrxfusion.remote.ui.PressVisual
import com.vrxfusion.remote.ui.StopPressScale
import com.vrxfusion.remote.ui.pressTargetProgress
import com.vrxfusion.remote.ui.pressVisual
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * D3 验收：统一按压回弹的参数边界、插值、减弱动态降级，以及「按压态不额外产生指令」的
 * 纯逻辑与静态契约验证。
 *
 * 边界：本测试不发起 HTTP，也未验证真实 `/api/input` 请求链路；它证明的是视觉层
 * 只读按压态、不接管点击、不引用任何指令发送符号，与 D2 单测同一口径。
 */
class PressScaleTest {

    @Test fun restAndPressedProgress() {
        assertEquals(0f, pressTargetProgress(pressed = false, motionEnabled = true), 0f)
        assertEquals(1f, pressTargetProgress(pressed = true, motionEnabled = true), 0f)
        // 减弱动态：即使按住也保持常态
        assertEquals(0f, pressTargetProgress(pressed = true, motionEnabled = false), 0f)
        assertEquals(0f, pressTargetProgress(pressed = false, motionEnabled = false), 0f)
    }

    @Test fun defaultSpecInterpolatesFromRestToPressed() {
        assertEquals(PressVisual.REST, pressVisual(0f))
        val half = pressVisual(.5f)
        assertEquals(1f + (DefaultPressScale.scale - 1f) * .5f, half.scale, 1e-6f)
        assertEquals(DefaultPressScale.sinkDp * .5f, half.sinkDp, 1e-6f)
        val full = pressVisual(1f)
        assertEquals(DefaultPressScale.scale, full.scale, 1e-6f)
        assertEquals(DefaultPressScale.sinkDp, full.sinkDp, 1e-6f)
    }

    @Test fun stopSpecIsDeeperAndSinksTwiceAsMuch() {
        val stop = pressVisual(1f, StopPressScale)
        val normal = pressVisual(1f, DefaultPressScale)
        assertEquals(0.975f, stop.scale, 1e-6f)
        assertEquals(2f, stop.sinkDp, 1e-6f)
        assertTrue("停止键缩放应更小", stop.scale < normal.scale)
        assertTrue("停止键下沉应更大", stop.sinkDp > normal.sinkDp)
    }

    @Test fun outOfRangeAndNonFiniteProgressAreClamped() {
        assertEquals(pressVisual(0f), pressVisual(-3f))
        assertEquals(pressVisual(1f), pressVisual(9f))
        assertEquals(PressVisual.REST, pressVisual(Float.NaN))
        assertEquals(PressVisual.REST, pressVisual(Float.POSITIVE_INFINITY))
        assertEquals(PressVisual.REST, pressVisual(Float.NEGATIVE_INFINITY))
        assertTrue(pressVisual(2f).scale >= 0.5f)
    }

    @Test fun specRejectsInvalidParameters() {
        assertTrue(runCatching { PressScaleSpec(scale = 1.2f) }.isFailure)   // 按下态不得放大
        assertTrue(runCatching { PressScaleSpec(scale = 0.4f) }.isFailure)
        assertTrue(runCatching { PressScaleSpec(sinkDp = -1f) }.isFailure)
        assertTrue(runCatching { PressScaleSpec(durationMs = -1) }.isFailure)
        assertTrue(runCatching { PressScaleSpec(scale = 1f, sinkDp = 0f, durationMs = 0) }.isSuccess)
    }

    @Test fun pressSequenceProducesExactlyOneVisualTargetPerChange() {
        // D3「按压态不产生额外 /api/input」的纯逻辑对应：每个按压态变化恰好一个视觉目标，
        // 持续按住不累积、不重复；视觉层没有任何命令输出。
        val sequence = listOf(false, true, true, false, true, false, false)
        val visuals = sequence.map { pressVisual(pressTargetProgress(it, true)) }
        assertEquals(sequence.size, visuals.size)
        assertEquals(PressVisual.REST, visuals[0])
        assertEquals(pressVisual(1f), visuals[1])
        assertEquals(visuals[1], visuals[2]) // 持续按住：不累积、不重复
        assertEquals(PressVisual.REST, visuals[3])
        assertEquals(pressVisual(1f), visuals[4])
        assertEquals(PressVisual.REST, visuals[5])
        assertEquals(visuals[5], visuals[6])
        assertTrue(visuals.all { it.scale in 0.5f..1f && it.sinkDp >= 0f })
    }

    @Test fun reducedMotionSequenceStaysAtRest() {
        val visuals = listOf(true, true, false, true).map { pressVisual(pressTargetProgress(it, false)) }
        assertTrue(visuals.all { it == PressVisual.REST })
    }

    @Test fun pressScaleContractDoesNotReferenceControlLink() {
        val pressFile = listOf(
            File("src/main/java/com/vrxfusion/remote/ui/PressScale.kt"),
            File("app/src/main/java/com/vrxfusion/remote/ui/PressScale.kt"),
        ).firstOrNull { it.isFile }
        assertNotNull("应能定位 PressScale.kt 源码（cwd=${System.getProperty("user.dir")}）", pressFile)
        val src = pressFile!!.readText()
        assertTrue("应能定位 pressScale 函数", src.contains("fun Modifier.pressScale("))
        val body = src.substringAfter("fun Modifier.pressScale(")
            .lineSequence()
            .map { it.trim() }
            .filterNot { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") }
            .joinToString("\n")
        listOf("onClick", "/api/input", "report(", "onStick", "onStop", "onProtect",
            "FlightGateway", "sendCommand", "http").forEach {
            assertFalse("pressScale 函数体不应触碰 $it", body.contains(it))
        }
        // 既有 motionPress 已收敛到 pressScale，全仓不再有两套缩放实现
        val motionFile = listOf(
            File("src/main/java/com/vrxfusion/remote/ui/AppMotion.kt"),
            File("app/src/main/java/com/vrxfusion/remote/ui/AppMotion.kt"),
        ).firstOrNull { it.isFile }
        assertNotNull("应能定位 AppMotion.kt 源码", motionFile)
        val motionBody = motionFile!!.readText().substringAfter("fun Modifier.motionPress(")
        assertTrue("motionPress 应委托 pressScale", motionBody.contains("pressScale("))
        assertFalse("motionPress 不应再自带 animateFloatAsState", motionBody.contains("animateFloatAsState"))
    }
}
