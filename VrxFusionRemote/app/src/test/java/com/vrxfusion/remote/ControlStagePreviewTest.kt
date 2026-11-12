package com.vrxfusion.remote

import android.graphics.Bitmap
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.drawToBitmap
import com.vrxfusion.remote.rotorpy.LinkPhase
import com.vrxfusion.remote.rotorpy.RotorState
import com.vrxfusion.remote.ui.FlightCockpit
import com.vrxfusion.remote.ui.state.ControlSnapshot
import com.vrxfusion.remote.ui.state.deriveControlUi
import com.vrxfusion.remote.ui.state.headerStatus
import com.vrxfusion.remote.ui.theme.VrxTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 用同一个真实操纵布局展示样例状态，样例只存在于测试中。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w800dp-h360dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ControlStagePreviewTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun captureStagesInTheActualCockpitLayout() {
        val observing = ControlSnapshot(
            connected = true, running = true, stale = false, writable = true,
            phase = LinkPhase.HANDSHAKE, busy = false, safetyBusy = false, holding = false,
            mode = "HOLD", neutral = true, error = null, sheetBlocking = false,
            routeMoving = false, safetyAllowed = true
        )
        val current = mutableStateOf(observing)
        val telemetry = RotorState(device_id = "sim-drone-1", selected_source = "app", data_source = "simulation_truth",
            velocity_m_s = listOf(0.0, 0.0, 0.0))
        rule.setContent {
            val snapshot = current.value
            val ui = deriveControlUi(snapshot)
            VrxTheme {
                FlightCockpit(
                    state = telemetry.copy(mode = snapshot.mode ?: "HOLD"),
                    trail = emptyList(),
                    control = ui,
                    fresh = snapshot.connected && !snapshot.stale,
                    wide = true,
                    receiveHz = 25.0
                )
            }
        }
        val stages = listOf(
            Triple("01-offline", observing.copy(connected = false, running = false), "连接设置"),
            Triple("02-observing", observing, "申请控制"),
            Triple("03-requesting", observing.copy(busy = true), "申请中"),
            Triple("04-ready", observing.copy(phase = LinkPhase.READY), "已接管"),
            Triple("05-protected", observing.copy(mode = "PROTECTED", neutral = false), "请先松手"),
            Triple("06-stale", observing.copy(connected = false, stale = true), "连接设置")
        )
        for ((file, snapshot, label) in stages) {
            rule.runOnIdle { current.value = snapshot }
            val action = rule.onNodeWithText(label).assertIsDisplayed()
            val control = deriveControlUi(snapshot)
            if (control.notice.actionEnabled) action.assertIsEnabled() else action.assertIsNotEnabled()
            rule.onNodeWithTag("control-stage-strip").assertIsDisplayed().assertTextContains(control.headerStatus().label)
            capture(file)
        }
        rule.runOnIdle { current.value = observing.copy(protectAvailable = false) }
        rule.onNodeWithText("停止").assertIsEnabled()
        rule.onNodeWithText("保护").assertIsNotEnabled()
        rule.onNodeWithTag("input-block-chip").assertDoesNotExist()
        val left = rule.onNodeWithTag("left-stick-panel")
        left.performTouchInput { down(center) }
        rule.onNodeWithTag("input-block-chip").assertIsDisplayed().assertTextContains("未接管")
        capture("07-blocked-stick")
        left.performTouchInput { up() }
        rule.onNodeWithTag("input-block-chip").assertDoesNotExist()
    }

    private fun capture(name: String) {
        val file = File("build/control-stage-preview/$name.png")
        file.parentFile?.mkdirs()
        val bitmap = rule.runOnIdle {
            rule.activity.findViewById<View>(android.R.id.content).drawToBitmap(Bitmap.Config.ARGB_8888)
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
