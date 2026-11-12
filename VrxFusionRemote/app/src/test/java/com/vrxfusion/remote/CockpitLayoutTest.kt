package com.vrxfusion.remote

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import androidx.activity.ComponentActivity
import androidx.core.view.drawToBitmap
import androidx.core.graphics.Insets
import androidx.core.view.DisplayCutoutCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.vrxfusion.remote.rotorpy.*
import com.vrxfusion.remote.ui.*
import com.vrxfusion.remote.ui.design.SafetyDock
import com.vrxfusion.remote.ui.design.VirtualJoystick
import com.vrxfusion.remote.ui.state.ControlUi
import com.vrxfusion.remote.ui.state.ControlSnapshot
import com.vrxfusion.remote.ui.state.deriveControlUi
import com.vrxfusion.remote.ui.theme.VrxTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/** 实际Compose布局截图与边界检查，避免用另画的示意图替代界面验证。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rCN-w800dp-h360dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CockpitLayoutTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    private val normal=RotorState(selected_source="app", data_source="simulation_truth", velocity_m_s=listOf(0.0, 0.0, 0.0))
    private fun notice(s: RotorState)=flightNotice(s,true,true,false,LinkPhase.READY,false,false,true,null)
    private fun save(name: String) {
        val file=File("build/cockpit-screenshots/$name.png"); file.parentFile?.mkdirs()
        val bitmap=captureRoot()
        file.outputStream().buffered().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG,100,out)
        }
        bitmap.recycle()
    }
    private fun captureRoot(): Bitmap = rule.runOnIdle {
        rule.activity.findViewById<View>(android.R.id.content).drawToBitmap(Bitmap.Config.ARGB_8888)
    }
    private fun captureTag(tag: String): Bitmap {
        val bounds = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val root = captureRoot()
        val crop = Bitmap.createBitmap(root, bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(), bounds.height.toInt())
        if (crop !== root) root.recycle()
        return crop
    }
    private fun captureDial(): Bitmap = captureTag("attitude-dial")
    private fun nextHudFrame(ms: Long, change: () -> Unit = {}) {
        rule.runOnIdle { change(); Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(ms)
    }
    private fun assertNoOverlap() {
        val root=rule.onNodeWithTag("cockpit").fetchSemanticsNode().boundsInRoot
        assertEquals("UE必须铺满全页",root,rule.onNodeWithTag("ue-viewport").fetchSemanticsNode().boundsInRoot)
        rule.onNodeWithTag("position-panel").assertIsDisplayed()
        rule.onNodeWithTag("attitude-panel").assertIsDisplayed()
        val tags=listOf("attitude-panel","position-panel","left-stick-panel","right-stick-panel","critical-actions") +
            listOf("speed-band","altitude-band").filter { rule.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() }
        for (i in tags.indices) for (j in i+1 until tags.size) {
            val a=rule.onNodeWithTag(tags[i]).fetchSemanticsNode().boundsInRoot
            val b=rule.onNodeWithTag(tags[j]).fetchSemanticsNode().boundsInRoot
            assertFalse("HUD控件不能互相遮挡：${tags[i]} / ${tags[j]}",a.overlaps(b))
        }
    }
    @Test fun landscapeWarningsDoNotMoveOrCoverInstruments() {
        val state=mutableStateOf(normal)
        rule.setContent { VrxTheme { FlightCockpit(state.value,emptyList(),cockpitTestControl(notice(state.value),state.value.mode != "PROTECTED"),true,true,25.0) } }
        assertNoOverlap()
        val before=rule.onNodeWithTag("ue-viewport").fetchSemanticsNode().boundsInRoot
        val stickBefore=rule.onNodeWithTag("left-stick-panel").fetchSemanticsNode().boundsInRoot
        save("landscape-ready")
        rule.runOnIdle { state.value=normal.copy(mode="PROTECTED",boundary_recovery=BoundaryRecovery(required=true)) }
        rule.waitForIdle(); assertNoOverlap()
        rule.onNodeWithText("等待恢复").assertIsNotEnabled()
        assertEquals(stickBefore,rule.onNodeWithTag("left-stick-panel").fetchSemanticsNode().boundsInRoot)
        assertEquals(before,rule.onNodeWithTag("ue-viewport").fetchSemanticsNode().boundsInRoot)
        save("landscape-boundary")
        rule.onNodeWithTag("position-panel").assertExists()
        rule.onNodeWithTag("ue-viewport").assertExists()
        assertEquals(before,rule.onNodeWithTag("ue-viewport").fetchSemanticsNode().boundsInRoot)
        save("landscape-map-and-ue")
    }
    @Test fun changingPositionDoesNotAlterAttitudePixels() {
        val state=mutableStateOf(normal)
        rule.setContent { VrxTheme { FlightCockpit(state.value,emptyList(),cockpitTestControl(notice(state.value)),true,true,25.0) } }
        val before=captureDial()
        rule.runOnIdle { state.value=normal.copy(position_m=listOf(8.0,-6.0,4.0)) }
        rule.waitForIdle()
        val after=captureDial()
        assertTrue("仅位移变化不能驱动姿态仪",before.sameAs(after))
        val top=after.getPixel((after.width*.7).toInt(),after.height/4)
        val bottom=after.getPixel((after.width*.7).toInt(),after.height*3/4)
        assertTrue("水平时上半部应为天空",android.graphics.Color.blue(top)>android.graphics.Color.red(top))
        assertTrue("水平时下半部应为地面",android.graphics.Color.red(bottom)>android.graphics.Color.blue(bottom))
    }
    @Test fun attitudeReadsRawAnglesAndStopsAnimatingOnInvalidFrame() {
        val roll = Math.toRadians(32.0) / 2
        val pitch = Math.toRadians(14.0) / 2
        val level = normal.copy(data_source = "simulation_truth", position_m = listOf(4.8, -2.3, 1.4),
            velocity_m_s = listOf(.9, .55, .24))
        val tilted = level.copy(quaternion_xyzw = listOf(sin(roll) * cos(pitch), cos(roll) * sin(pitch),
            -sin(roll) * sin(pitch), cos(roll) * cos(pitch)))
        val state = mutableStateOf(level)
        val fresh = mutableStateOf(true)
        rule.mainClock.autoAdvance = false
        rule.setContent { VrxTheme {
            val control = flightNotice(state.value, fresh.value, true, !fresh.value, LinkPhase.READY, false, false, true, null)
            FlightCockpit(state.value, emptyList(), cockpitTestControl(control, fresh.value), fresh.value, true, 25.0)
        } }
        nextHudFrame(256)
        val centered = captureDial()
        nextHudFrame(64) { state.value = tilted }
        rule.onNodeWithText("横滚 32.0°").assertIsDisplayed()
        rule.onNodeWithText("俯仰 14.0°").assertIsDisplayed()
        val intermediate = captureDial()
        nextHudFrame(160)
        val finalAngle = captureDial()
        assertFalse("球面已经起动", centered.sameAs(intermediate))
        assertFalse("数字已到目标，球面仍在过渡", intermediate.sameAs(finalAngle))
        assertNoOverlap()
        save("hud-adi-tilted")

        nextHudFrame(64) { state.value = level }
        assertFalse("失效前正在向新目标转动", finalAngle.sameAs(captureDial()))
        nextHudFrame(16) { fresh.value = false }
        rule.onNodeWithTag("attitude-invalid-flag").assertIsDisplayed()
        rule.onNodeWithText("横滚 0.0°").assertDoesNotExist()
        val frozen = captureDial()
        save("hud-adi-stale")
        nextHudFrame(320) { state.value = tilted }
        assertTrue("失效后旧动画和迟到样本都不能转动球面", frozen.sameAs(captureDial()))
    }

    @Test fun hudStatusUsesVisualBufferAndCriticalAlertInterruptsImmediately() {
        val offline = flightNotice(null, false, false, false, LinkPhase.DISCONNECTED, false, false, true, null)
        val ready = notice(normal)
        val observing = flightNotice(normal, true, true, false, LinkPhase.SESSION, false, false, true, null)
        val requesting = flightNotice(normal, true, true, false, LinkPhase.HANDSHAKE, true, false, true, null)
        val rejected = flightNotice(normal, true, true, false, LinkPhase.READY, false, false, true, "演示错误：服务端未确认操作")
        val critical = notice(normal.copy(mode = "PROTECTED"))
        val current = mutableStateOf(offline)
        rule.mainClock.autoAdvance = false
        rule.setContent { VrxTheme {
            val shown = current.value
            FlightCockpit(normal, emptyList(), cockpitTestControl(shown, shown == ready || shown == rejected, shown != offline),
                fresh = shown != offline, wide = true, receiveHz = 25.0)
        } }
        nextHudFrame(224)
        val buttons = rule.onNodeWithTag("critical-actions").fetchSemanticsNode().boundsInRoot
        save("hud-flow-00-offline")
        nextHudFrame(224) { current.value = observing }
        save("hud-flow-01-observing")
        nextHudFrame(32) { current.value = requesting }
        rule.onNodeWithText("握手中").assertExists()
        rule.onNodeWithText("观察中").assertDoesNotExist()
        rule.onNodeWithText("申请中").assertIsNotEnabled()
        save("hud-flow-02-requesting")
        nextHudFrame(224) { current.value = ready }
        rule.onNodeWithText("控制有效").assertExists()
        save("hud-flow-03-ready")

        nextHudFrame(48) { current.value = rejected }
        rule.onNodeWithText("上一次操作未完成").assertIsDisplayed()
        val entering = captureTag("flight-notice")
        save("hud-flow-04-alert-in")
        repeat(4) {
            nextHudFrame(32)
            save("hud-fade-in-${it + 1}")
        }
        nextHudFrame(48)
        val fullyVisible = captureTag("flight-notice")
        assertFalse("非关键错误提示有渐入", entering.sameAs(fullyVisible))
        save("hud-flow-05-alert-full")
        nextHudFrame(640) { current.value = ready }
        rule.onNodeWithText("上一次操作未完成").assertIsDisplayed()
        assertTrue("缓冲停留期间亮度固定", fullyVisible.sameAs(captureTag("flight-notice")))
        save("hud-fade-hold")
        nextHudFrame(176)
        repeat(2) {
            nextHudFrame(32)
            save("hud-fade-out-${it + 1}")
        }
        val fading = captureTag("flight-notice")
        assertFalse("停留结束后逐渐淡出", fullyVisible.sameAs(fading))
        save("hud-flow-06-alert-out")
        repeat(3) {
            nextHudFrame(32)
            save("hud-fade-out-${it + 3}")
        }
        nextHudFrame(128)
        rule.onNodeWithTag("flight-notice").assertDoesNotExist()
        save("hud-flow-07-cleared")

        nextHudFrame(48) { current.value = rejected }
        nextHudFrame(16) { current.value = critical }
        rule.onNodeWithText(critical.title).assertIsDisplayed()
        rule.onNodeWithText(rejected.title).assertDoesNotExist()
        val immediatelyVisible = captureTag("flight-notice")
        save("hud-flow-08-protected")
        nextHudFrame(224)
        assertTrue("关键告警从首帧保持可见", immediatelyVisible.sameAs(captureTag("flight-notice")))
        assertEquals(buttons, rule.onNodeWithTag("critical-actions").fetchSemanticsNode().boundsInRoot)
        nextHudFrame(16) { current.value = ready }
        rule.onNodeWithTag("flight-notice").assertDoesNotExist()
    }
    @Test fun reducedMotionKeepsReadingTimeWithoutFadingHudError() {
        val ready = notice(normal)
        val rejected = flightNotice(normal, true, true, false, LinkPhase.READY, false, false, true, "演示错误")
        val current = mutableStateOf(ready)
        rule.mainClock.autoAdvance = false
        rule.setContent { VrxTheme { CompositionLocalProvider(LocalAppMotionEnabled provides false) {
            FlightCockpit(normal, emptyList(), cockpitTestControl(current.value), true, true, 25.0)
        } } }
        nextHudFrame(48)
        nextHudFrame(16) { current.value = rejected }
        val first = captureTag("flight-notice")
        nextHudFrame(224)
        assertTrue(first.sameAs(captureTag("flight-notice")))
        nextHudFrame(16) { current.value = ready }
        assertTrue(first.sameAs(captureTag("flight-notice")))
        nextHudFrame(1000)
        rule.onNodeWithTag("flight-notice").assertDoesNotExist()
    }

    @Test fun alertDetailsKeepWarningVisibleAndPreserveStickBounds() {
        val snapshot = ControlSnapshot(connected = true, running = true, stale = false, writable = false,
            phase = LinkPhase.READY, busy = false, safetyBusy = false, holding = false, mode = "PROTECTED",
            neutral = true, error = "设备能力刷新失败", sheetBlocking = false, routeMoving = false, safetyAllowed = true)
        val locked = deriveControlUi(snapshot)
        val limited = deriveControlUi(snapshot.copy(mode = "HOLD"))
        val critical = locked.notice
        val access = limited.notice
        val shown = mutableStateOf(locked)
        rule.mainClock.autoAdvance = false
        rule.setContent { VrxTheme {
            FlightCockpit(normal.copy(mode = if (shown.value == locked) "PROTECTED" else "HOLD"),
                emptyList(), shown.value, true, true, 25.0)
        } }
        nextHudFrame(48)
        val left = rule.onNodeWithTag("left-stick-panel").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithText("+1").assertIsDisplayed().performClick()
        nextHudFrame(48)
        rule.onNodeWithText(critical.title).assertIsDisplayed()
        rule.onNodeWithText(access.title).assertIsDisplayed()
        assertEquals(left, rule.onNodeWithTag("left-stick-panel").fetchSemanticsNode().boundsInRoot)
        save("hud-alert-list")
        rule.onNodeWithTag("flight-alert-collapse").performClick()
        nextHudFrame(32)
        rule.onNodeWithText(critical.title).assertIsDisplayed()
        nextHudFrame(64) { shown.value = limited }
        rule.onNodeWithText(access.title).assertIsDisplayed()
        rule.onNodeWithText("+1").performClick()
        nextHudFrame(48)
        rule.onNodeWithTag("flight-alert-recovered").assertIsDisplayed()
        nextHudFrame(5120)
        rule.onNodeWithTag("flight-alert-recovered").assertDoesNotExist()
        rule.onNodeWithText(access.title).assertIsDisplayed()
    }
    @Test @Config(qualifiers="zh-rCN-w640dp-h320dp-land-xhdpi")
    fun compactLandscapeHasNoOverlay() {
        rule.setContent { VrxTheme { FlightCockpit(normal,emptyList(),cockpitTestControl(notice(normal)),true,true,25.0) } }
        assertNoOverlap()
        val attitude = rule.onNodeWithTag("attitude-panel").fetchSemanticsNode().boundsInRoot
        val map = rule.onNodeWithTag("position-panel").fetchSemanticsNode().boundsInRoot
        assertEquals(128f, (map.left - attitude.right) / 2, .01f)
        save("landscape-compact")
        checkMapModes("landscape-compact-focus")
        rule.runOnIdle {
            val nav = WindowInsetsCompat.Type.navigationBars()
            val insets = WindowInsetsCompat.Builder().setInsetsIgnoringVisibility(nav, Insets.of(0,0,96,0))
                .setVisible(nav, false).build()
            ViewCompat.dispatchApplyWindowInsets(rule.activity.findViewById<View>(android.R.id.content), insets)
        }
        rule.onNodeWithTag("position-panel").assertDoesNotExist()
        rule.onNodeWithTag("attitude-panel").assertIsDisplayed()
        rule.onNodeWithTag("heading-readout").assertIsDisplayed()
        save("hud-compact-inset")
        rule.runOnIdle {
            val nav = WindowInsetsCompat.Type.navigationBars()
            val insets = WindowInsetsCompat.Builder().setInsetsIgnoringVisibility(nav, Insets.of(0,0,328,0))
                .setVisible(nav, false).build()
            ViewCompat.dispatchApplyWindowInsets(rule.activity.findViewById<View>(android.R.id.content), insets)
        }
        rule.onNodeWithTag("position-panel").assertDoesNotExist()
        rule.onNodeWithTag("attitude-panel").assertDoesNotExist()
        save("hud-numeric-attitude")
        listOf("numeric-attitude", "heading-readout", "altitude-readout", "ground-speed-readout", "vertical-speed-readout")
            .forEach { tag ->
                val layouts = mutableListOf<TextLayoutResult>()
                rule.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
                val layout = layouts.single()
                val last = layout.layoutInput.text.length
                assertFalse("$tag 读数不能省略", layout.isLineEllipsized(0))
                assertEquals("$tag 数值和单位完整显示", last, layout.getLineEnd(0, visibleEnd = true))
                assertFalse("$tag 读数高度超出文字框", layout.didOverflowHeight)
                assertTrue("$tag 末尾字形须在文字框内",
                    layout.getBoundingBox(last - 1).right <= layout.size.width + 1f)
            }
    }

    @Test @Config(qualifiers="zh-rCN-w640dp-h320dp-land-xhdpi")
    fun centralHudFollowsManualChoiceAndPreservesTheCockpitTouchWhitelist() {
        val showVideo = mutableStateOf(false)
        val viewportStatus = mutableStateOf<String?>("UE 画面未接入")
        val preferences = mutableStateOf(HudPreferences())
        val aircraft = normal.copy(velocity_m_s = listOf(.9,.55,.24))
        var stops = 0
        rule.setContent { VrxTheme {
            val background: (@Composable () -> Unit)? = if (showVideo.value) {
                { Box(Modifier.fillMaxSize().background(Color(0xFF527788))) }
            } else null
            FlightCockpit(aircraft, emptyList(), cockpitTestControl(notice(aircraft)), true, true, 25.0,
                onStop = { stops++ }, hud = preferences.value, viewportStatus = viewportStatus.value,
                ueContent = background)
        } }
        rule.onNodeWithTag("central-hud").assertDoesNotExist()
        val stick = rule.onNodeWithTag("left-stick-panel").fetchSemanticsNode().boundsInRoot
        rule.runOnIdle { preferences.value = preferences.value.copy(showCentralHud = true) }
        rule.onNodeWithTag("central-hud").assertIsDisplayed()
        val frame = rule.onNodeWithTag("cockpit").fetchSemanticsNode().boundsInRoot
        val instruments = rule.onNodeWithTag("central-hud").fetchSemanticsNode().boundsInRoot
        assertEquals(frame.center.x, instruments.center.x, 1f)
        assertEquals(frame.center.y, instruments.center.y, 24f)
        rule.onNodeWithTag("position-panel").assertDoesNotExist()
        rule.onNodeWithTag("attitude-panel").assertDoesNotExist()
        listOf("heading-readout","ground-speed-readout","vertical-speed-readout","altitude-readout").forEach {
            rule.onAllNodesWithTag(it, useUnmergedTree = true).assertCountEquals(1)
        }
        val clickable = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .map { it.config.getOrNull(SemanticsProperties.TestTag) }.toSet()
        assertEquals(setOf("flight-back","flight-primary","flight-stop","flight-protect","flight-settings"), clickable)
        rule.onNodeWithTag("central-hud").performTouchInput { click(center) }
        rule.runOnIdle { assertEquals(0, stops) }
        rule.onNodeWithTag("flight-stop").performTouchInput { click(center) }
        rule.runOnIdle { assertEquals(1, stops) }
        assertEquals(stick, rule.onNodeWithTag("left-stick-panel").fetchSemanticsNode().boundsInRoot)
        val original = captureTag("central-hud")
        save("hud-central-manual-no-video")
        rule.runOnIdle { preferences.value = preferences.value.copy(centerHudOpacity = .25f) }
        assertFalse(original.sameAs(captureTag("central-hud")))
        rule.runOnIdle { showVideo.value = true; viewportStatus.value = null }
        assertEquals(instruments, rule.onNodeWithTag("central-hud").fetchSemanticsNode().boundsInRoot)
        rule.runOnIdle { showVideo.value = false; viewportStatus.value = "测试画面已中断" }
        rule.onNodeWithText("测试画面已中断").assertIsDisplayed()
        assertEquals(instruments, rule.onNodeWithTag("central-hud").fetchSemanticsNode().boundsInRoot)
        rule.runOnIdle { preferences.value = preferences.value.copy(showCentralHud = false) }
        rule.onNodeWithTag("central-hud").assertDoesNotExist()
        rule.onNodeWithTag("position-panel").assertIsDisplayed()
    }

    @Test fun bothStickReleasesUpdateOnlyTheirOwnAxisAndSafetyReceivesTouch() {
        val control = cockpitTestControl(notice(normal))
        var left = Offset.Zero
        var right = Offset.Zero
        var stops = 0
        var protections = 0
        rule.setContent { VrxTheme {
            FlightCockpit(normal, emptyList(), control, true, true, 25.0,
                onLeft = { left = it }, onRight = { right = it }, onStop = { stops++ }, onProtect = { protections++ })
        } }
        val leftCenter = rule.onNodeWithTag("left-stick-panel").fetchSemanticsNode().boundsInRoot.center
        val rightCenter = rule.onNodeWithTag("right-stick-panel").fetchSemanticsNode().boundsInRoot.center
        val stopCenter = rule.onNodeWithTag("flight-stop").fetchSemanticsNode().boundsInRoot.center
        val protectCenter = rule.onNodeWithTag("flight-protect").fetchSemanticsNode().boundsInRoot.center
        val root = rule.onRoot()
        for (first in 0..1) {
            root.performTouchInput {
                down(0, leftCenter)
                down(1, rightCenter)
                moveTo(0, leftCenter - Offset(0f, 34f))
                moveTo(1, rightCenter + Offset(34f, 0f))
            }
            rule.runOnIdle { assertTrue(left.y > 0f && right.x > 0f) }
            root.performTouchInput { up(first) }
            rule.runOnIdle {
                if (first == 0) {
                    assertEquals(Offset.Zero, left)
                    assertTrue(right.x > 0f)
                } else {
                    assertEquals(Offset.Zero, right)
                    assertTrue(left.y > 0f)
                }
            }
            if (first == 0) {
                root.performTouchInput { down(2, stopCenter); up(2); down(3, protectCenter); up(3) }
                rule.runOnIdle { assertEquals(1, stops); assertEquals(1, protections); assertTrue(right.x > 0f) }
            }
            root.performTouchInput { up(1-first) }
            rule.runOnIdle { assertEquals(Offset.Zero, left); assertEquals(Offset.Zero, right) }
        }
    }

    @Test fun manualLevelOnlyRisesWhileWarningPersistsOrAttentionIsNew() {
        val base = cockpitTestControl(notice(normal))
        val alert = mutableStateOf(base)
        rule.mainClock.autoAdvance = false
        rule.setContent { VrxTheme {
            FlightCockpit(normal, emptyList(), alert.value, true, true, 25.0, hud = HudPreferences(level = HudDisplayLevel.L1))
        } }
        nextHudFrame(48)
        rule.onNodeWithTag("heading-readout").assertIsDisplayed()
        rule.onNodeWithTag("speed-band").assertDoesNotExist()
        val readOnly = flightNotice(normal, true, true, false, LinkPhase.READY, false, false, true, null, writable = false)
        nextHudFrame(64) { alert.value = cockpitTestControl(readOnly, false) }
        rule.onNodeWithTag("speed-band").assertIsDisplayed()
        nextHudFrame(1200)
        rule.onNodeWithTag("speed-band").assertDoesNotExist()
        rule.onNodeWithTag("heading-readout").assertIsDisplayed()
        nextHudFrame(64) { alert.value = cockpitTestControl(notice(normal.copy(mode = "PROTECTED")), false) }
        nextHudFrame(1200)
        rule.onNodeWithTag("speed-band").assertIsDisplayed()
        nextHudFrame(48) { alert.value = base }
        rule.onNodeWithTag("speed-band").assertDoesNotExist()
    }

    @Test fun mapReturnsToUserOpacityThreeSecondsAfterMotionStops() {
        val base = cockpitTestControl(notice(normal))
        val control = mutableStateOf(base)
        rule.mainClock.autoAdvance = false
        rule.setContent { VrxTheme { FlightCockpit(normal,emptyList(),control.value,true,true,25.0) } }
        nextHudFrame(64)
        val original = captureTag("position-panel")
        nextHudFrame(48) { control.value = base.copy(moving = true) }
        val dimmed = captureTag("position-panel")
        assertFalse(original.sameAs(dimmed))
        nextHudFrame(2900) { control.value = base }
        assertTrue(dimmed.sameAs(captureTag("position-panel")))
        nextHudFrame(180)
        assertTrue(original.sameAs(captureTag("position-panel")))
    }
    @Test @Config(qualifiers="zh-rCN-w360dp-h780dp-port-xhdpi")
    fun portraitKeepsSingleNotice() {
        rule.setContent { VrxTheme { FlightCockpit(normal,emptyList(),cockpitTestControl(notice(normal)),true,false,25.0) } }
        assertNoOverlap(); save("portrait"); checkMapModes("portrait-focus")
    }
    @Test fun disabledPressMustReleaseBeforeItCanBecomeMotion() {
        val enabled=mutableStateOf(false); val touched=mutableStateOf(false); val values=mutableListOf<Offset>()
        rule.setContent { VrxTheme { Box(Modifier.size(132.dp).testTag("stick")) {
            VirtualJoystick(enabled.value,onValue={ values+=it },onTouchChanged={ touched.value=it })
        } } }
        rule.onNodeWithTag("stick").performTouchInput { down(center) }
        rule.runOnIdle { assertTrue("禁用时按住也不能伪装成松手",touched.value); enabled.value=true }
        rule.onNodeWithTag("stick").performTouchInput { moveBy(Offset(30f,0f)) }
        rule.runOnIdle { assertTrue("旧按压不能在重新启用后续发",values.all { it == Offset.Zero }) }
        rule.onNodeWithTag("stick").performTouchInput { up(); down(center); moveBy(Offset(30f,0f)); up() }
        rule.runOnIdle { assertTrue("松手后的新手势应正常输出",values.any { it.x>0 }); assertFalse(touched.value) }
    }
    @Test fun transparencyChangesHudButNeverCriticalButtonsOrNotice() {
        val hud=mutableStateOf(HudPreferences())
        val protected=normal.copy(mode="PROTECTED")
        rule.setContent { VrxTheme { FlightCockpit(protected,emptyList(),cockpitTestControl(notice(protected),false),true,true,25.0,hud=hud.value) } }
        fun capture(tag: String): Bitmap {
            val b=rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            return Bitmap.createBitmap(captureRoot(),b.left.toInt(),b.top.toInt(),b.width.toInt(),b.height.toInt())
        }
        val buttons=capture("critical-actions"); val banner=capture("flight-notice"); val map=capture("position-panel")
        rule.runOnIdle { hud.value=HudPreferences(.2f,.2f,.2f,.2f) }
        rule.waitForIdle(); assertNoOverlap()
        assertTrue("关键按钮不能随显示透明度变淡",buttons.sameAs(capture("critical-actions")))
        assertTrue("关键提示不能随显示透明度变淡",banner.sameAs(capture("flight-notice")))
        assertFalse("地图透明度必须实际生效",map.sameAs(capture("position-panel")))
        save("landscape-low-opacity")
    }
    /** 放大的是当前位置附近的观察比例，切换前后地图框、UE和操纵控件不移动。 */
    private fun checkMapModes(screenshot: String) {
        for (text in listOf("位置与轨迹","总览","近景","远景")) rule.onNodeWithText(text).assertDoesNotExist()
        val frame=rule.onNodeWithTag("position-panel").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag("position-panel").assert(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"完整轨迹总览"))
        rule.onNodeWithTag("position-panel").performClick()
        rule.onNodeWithTag("position-panel").assert(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"当前位置近景"))
        assertEquals(frame,rule.onNodeWithTag("position-panel").fetchSemanticsNode().boundsInRoot)
        assertNoOverlap(); save(screenshot)
        rule.onNodeWithTag("position-panel").performClick()
        rule.onNodeWithTag("position-panel").assert(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"完整轨迹总览"))
        assertEquals(frame,rule.onNodeWithTag("position-panel").fetchSemanticsNode().boundsInRoot)
    }
    @Test fun tapFocusesCurrentPositionWithoutEnlargingMapFrame() {
        val sample=normal.copy(position_m=listOf(8.0,-6.0,1.0))
        rule.setContent { VrxTheme { FlightCockpit(sample,listOf(0.0 to 0.0,8.0 to -6.0),cockpitTestControl(notice(sample)),true,true,25.0) } }
        rule.onNodeWithText("X 8.00  Y -6.00",useUnmergedTree=true).assertIsDisplayed()
        rule.onNodeWithText("2m/格",useUnmergedTree=true).assertIsDisplayed()
        save("landscape-trajectory-overview")
        checkMapModes("landscape-current-focus")
    }
    @Test fun routinePromptIsRemovedAndImportantWarningsStayAboveTheHud() {
        val current=mutableStateOf(notice(normal))
        val background=Color(0xFFE3D9B7)
        rule.setContent { VrxTheme { FlightCockpit(normal,emptyList(),cockpitTestControl(current.value),true,true,25.0,
            viewportStatus="UE 画面未接入",
            ueContent={ Box(Modifier.fillMaxSize().background(background)) }) } }
        fun checkAlert() {
            rule.onNodeWithText(current.value.title).assertIsDisplayed()
            rule.onNodeWithText("详情").assertDoesNotExist()
            val b=rule.onNodeWithTag("flight-notice").fetchSemanticsNode().boundsInRoot
            val stick=rule.onNodeWithTag("left-stick-panel").fetchSemanticsNode().boundsInRoot
            val video=rule.onNodeWithTag("video-viewport-status").fetchSemanticsNode().boundsInRoot
            val screen=rule.onNodeWithTag("cockpit").fetchSemanticsNode().boundsInRoot
            assertTrue(b.bottom<=stick.top)
            assertTrue(b.width<=screen.width*.6f)
            assertTrue(video.bottom<=b.top)
            assertEquals(0xFFE3D9B7.toInt(),captureRoot().getPixel((screen.right-24).toInt(),b.center.y.toInt()))
            assertNotEquals(0xFFE3D9B7.toInt(),captureRoot().getPixel(b.right.toInt()-12,b.center.y.toInt()))
        }
        rule.onNodeWithTag("flight-notice").assertDoesNotExist()
        rule.onNodeWithText(current.value.detail).assertDoesNotExist()
        rule.onNodeWithText("升降 / 转向").assertDoesNotExist()
        rule.onNodeWithContentDescription("升降 / 转向").assertIsDisplayed()
        rule.runOnIdle { current.value=notice(normal.copy(mode="PROTECTED")) }
        checkAlert(); save("landscape-bright-video-warning")
        rule.runOnIdle { current.value=flightNotice(normal,false,true,true,LinkPhase.DISCONNECTED,false,false,true,null) }
        checkAlert()
    }
    @Test fun transientSystemBarsKeepTheCockpitAndSettingsStationary() {
        val settings=mutableStateOf(false)
        val offline=flightNotice(null,false,false,false,LinkPhase.DISCONNECTED,false,false,true,null,writable=false)
        rule.setContent { VrxTheme { Box(Modifier.fillMaxSize()) {
            FlightCockpit(null,emptyList(),cockpitTestControl(offline,false,false),false,true,0.0)
            if (settings.value) FlightSettingsPanel(0,{},HudPreferences(),{},{},
                safetyContent={SafetyDock(true,true,{},{})}) { }
        } } }
        val statusHeight=48
        fun applyBars(size: Insets, navigationVisible: Boolean, statusSize: Insets=Insets.of(0,statusHeight,0,0),
            statusVisible: Boolean=false, cutout: DisplayCutoutCompat?=null) {
            rule.runOnIdle {
                val navigation=WindowInsetsCompat.Type.navigationBars()
                val status=WindowInsetsCompat.Type.statusBars()
                val insets=WindowInsetsCompat.Builder()
                    .setInsetsIgnoringVisibility(navigation,size)
                    .setInsetsIgnoringVisibility(status,statusSize)
                    .setInsets(navigation,if (navigationVisible) size else Insets.NONE)
                    .setInsets(status,if (statusVisible) statusSize else Insets.NONE)
                    .setVisible(navigation,navigationVisible)
                    .setVisible(status,statusVisible)
                    .setDisplayCutout(cutout).build()
                ViewCompat.dispatchApplyWindowInsets(rule.activity.findViewById(android.R.id.content),insets)
            }
        }
        fun bounds(tag: String)=rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        for (size in listOf(Insets.of(96,0,0,0),Insets.of(0,0,96,0),Insets.of(0,0,0,48))) {
            rule.runOnIdle { settings.value=true }
            applyBars(size,false,statusSize=Insets.NONE)
            val headerTop=bounds("critical-actions").top
            val settingsTop=bounds("settings-panel").top
            applyBars(size,false)
            assertEquals(headerTop,bounds("critical-actions").top)
            assertEquals(settingsTop,bounds("settings-panel").top)
            val left=bounds("left-stick-panel")
            val right=bounds("right-stick-panel")
            val dock=bounds("settings-safety-dock")
            val screen=bounds("cockpit")
            for (item in listOf(left,right,dock)) {
                assertTrue(item.left-screen.left>=size.left)
                assertTrue(screen.right-item.right>=size.right)
                assertTrue(screen.bottom-item.bottom>=size.bottom)
            }
            applyBars(size,true)
            assertEquals(left,bounds("left-stick-panel"))
            assertEquals(right,bounds("right-stick-panel"))
            assertEquals(dock,bounds("settings-safety-dock"))
            assertEquals(screen,bounds("ue-viewport"))
            if (size.right>0) {
                applyBars(size,true,statusVisible=true)
                assertEquals(headerTop,bounds("critical-actions").top)
                assertEquals(settingsTop,bounds("settings-panel").top)
                assertEquals(left,bounds("left-stick-panel"))
                assertEquals(right,bounds("right-stick-panel"))
                assertEquals(dock,bounds("settings-safety-dock"))
                assertEquals(screen,bounds("ue-viewport"))
                val cutoutHeight=30
                val cutout=DisplayCutoutCompat(Rect(0,cutoutHeight,0,0),listOf(Rect(760,0,840,cutoutHeight)))
                applyBars(size,true,cutout=cutout)
                assertEquals(headerTop+cutoutHeight,bounds("critical-actions").top)
                assertEquals(settingsTop+cutoutHeight,bounds("settings-panel").top)
                applyBars(size,true)
                assertEquals(headerTop,bounds("critical-actions").top)
                assertEquals(settingsTop,bounds("settings-panel").top)
            }
            rule.runOnIdle { settings.value=false }
            if (size.right>0) {
                rule.onNodeWithText("UE 画面未接入").assertIsDisplayed()
                rule.onNodeWithText("尚未连接后端").assertIsDisplayed()
                save("landscape-offline-side-nav")
            }
        }
    }
    @Test fun fourDirectionGesturesAgreeWithTheFixedMap() {
        val values=mutableListOf<Offset>()
        rule.setContent { VrxTheme { FlightCockpit(normal,emptyList(),cockpitTestControl(notice(normal)),true,true,25.0,
            onRight={ values+=it }) } }
        val samples=mutableListOf<String>()
        val viewport=TrajectoryViewport(0.0,0.0,10.5)
        val origin=viewport.project(0.0,0.0,128f,128f,8f)
        for ((name,delta) in listOf("left" to Offset(-30f,0f),"right" to Offset(30f,0f),
            "forward" to Offset(0f,-30f),"backward" to Offset(0f,30f))) {
            values.clear()
            rule.onNodeWithTag("right-stick-panel").performTouchInput { down(center); moveBy(delta) }
            rule.runOnIdle {
                val value=values.lastOrNull { it != Offset.Zero } ?: error("真实手势没有产生方向输入")
                val axes=StickSnapshot(rx=value.x,ry=value.y).axes()
                val pixel=viewport.project(axes[0],axes[1],128f,128f,8f)
                val displacement=Offset(pixel.first-origin.first,pixel.second-origin.second)
                assertTrue("$name 手势投影到地图后应与手指同向",displacement.x*delta.x+displacement.y*delta.y>0)
                assertEquals("不能串到另一个平面轴",0f,displacement.x*delta.y-displacement.y*delta.x,.001f)
                assertEquals(0.0,axes[2],0.0); assertEquals(0.0,axes[3],0.0)
                samples+="""{"direction":"$name","frame":"$APP_STICK_FRAME","axes":${axes}}"""
            }
            rule.onNodeWithTag("right-stick-panel").performTouchInput { up() }
            rule.runOnIdle { assertEquals("松手必须归零",Offset.Zero,values.last()) }
        }
        // 导出真实Compose手势产生的报文样本，供独立RotorPy服务逐个回放验证实际位移。
        File("build/control-direction-samples.json").writeText(samples.joinToString(",",prefix="[",postfix="]"))
    }
    @Test fun opacitySliderSavesAndRestoresDefaults() {
        val store=HudPreferencesStore(RuntimeEnvironment.getApplication())
        val value=mutableStateOf(store.save(HudPreferences()))
        rule.setContent { VrxTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            HudSettingsPanel(value.value) { value.value=store.save(it) }
        } } }
        rule.onNodeWithContentDescription("地图不透明度").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(.35f) }
        rule.runOnIdle { assertEquals(.35f,HudPreferencesStore(RuntimeEnvironment.getApplication()).load().mapOpacity,.001f) }
        rule.onNodeWithText("恢复显示默认值").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(HudPreferences(),store.load()) }
    }
}

internal fun cockpitTestControl(notice: FlightNotice, inputEnabled: Boolean = true, safetyEnabled: Boolean = true) =
    ControlUi(notice, inputEnabled, blockReason = null, stopEnabled = safetyEnabled, protectEnabled = safetyEnabled)
