package com.vrxfusion.remote

import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.vrxfusion.remote.rotorpy.RotorState
import com.vrxfusion.remote.ui.HudHost
import com.vrxfusion.remote.ui.HudDisplayLevel
import com.vrxfusion.remote.ui.HudPreferences
import com.vrxfusion.remote.ui.flightNotice
import com.vrxfusion.remote.ui.hud.HudTelemetry
import com.vrxfusion.remote.ui.hud.rememberHudControlSignals
import com.vrxfusion.remote.ui.theme.VrxTheme
import com.vrxfusion.remote.rotorpy.LinkPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w800dp-h360dp-land-xhdpi")
class HudTelemetryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun coordinateFramesRecomposeReadoutsWithoutRecomposingControlParent() {
        val initial = RotorState(boot_id = "telemetry-test")
        val aircraft = mutableStateOf<RotorState?>(initial)
        val rate = mutableStateOf(0.0)
        var controlCommits = 0
        rule.setContent {
            val signals by rememberHudControlSignals(aircraft)
            val notice = flightNotice(initial.copy(mode = signals.mode ?: initial.mode),
                true, true, false, LinkPhase.READY, false, false, true, null)
            val controls = cockpitTestControl(notice, inputEnabled = signals.mode != "PROTECTED")
            val telemetry = remember {
                HudTelemetry(aircraft, mutableStateOf(emptyList()), rate,
                    mutableStateOf(emptyList()), mutableStateOf(null))
            }
            val receipt = remember { mutableStateOf<String?>(null) }
            SideEffect { controlCommits++ }
            VrxTheme {
                HudHost(telemetry, signals, controls, fresh = true, wide = true, commandReceipt = receipt,
                    hud = HudPreferences(level = HudDisplayLevel.L3))
            }
        }
        rule.onNodeWithText("X 0.00  Y 0.00 m").assertIsDisplayed()
        rule.onNodeWithText("采样中").assertIsDisplayed()
        val before = rule.runOnIdle { controlCommits }
        rule.runOnIdle { rate.value = 25.0 }
        rule.onNodeWithText("25.0 Hz").assertIsDisplayed()
        for (sequence in 1..4) {
            rule.runOnIdle {
                aircraft.value = initial.copy(sequence = sequence.toLong(), position_m = listOf(sequence.toDouble(), 0.0, 1.0))
            }
            rule.onNodeWithText("X $sequence.00  Y 0.00 m").assertIsDisplayed()
        }
        rule.runOnIdle { assertEquals(before, controlCommits) }
        rule.runOnIdle { aircraft.value = aircraft.value!!.copy(mode = "PROTECTED") }
        rule.onNodeWithText("解除保护").assertIsDisplayed()
        rule.runOnIdle { assertTrue(controlCommits > before) }
    }
}
