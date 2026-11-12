package com.vrxfusion.remote

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.vrxfusion.remote.ui.*
import com.vrxfusion.remote.ui.theme.VrxTheme
import com.vrxfusion.remote.rotorpy.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 核验配置边界、旧版迁移、实际布局和数据质量，不能只检查配置对象存在。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rCN-w800dp-h360dp-land-xhdpi")
class AppRequirementsTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    @Test fun configurationRejectsUnknownFieldsVersionsAndOutOfRange() {
        val baseline=HudPreferences(showMap=false,swapInstruments=true,instrumentScale=.8f)
        assertEquals(baseline,HudConfigCodec.decode(HudConfigCodec.encode(baseline)))
        val valid=HudConfigCodec.encode(baseline)
        val previous = HudConfigCodec.decode("""{"schemaVersion":1,"display":{"showMap":false,"mapOpacity":0.4}}""")
        assertEquals(HudDisplayLevel.L2, previous.level)
        assertFalse(previous.showMap)
        assertEquals(.4f, previous.mapOpacity, 0f)
        listOf(valid.replace("\"schemaVersion\": 1","\"schemaVersion\": 2"),
            valid.replace("\"display\":", "\"disableProtection\": true, \"display\":"),
            valid.replace("0.65","0.01"),valid.replace("0.8","1.5"),"x".repeat(8193)).forEach {
            assertTrue(runCatching { HudConfigCodec.decode(it) }.isFailure)
        }
    }
    @Test fun legacyMigrationAndExplicitCheckpointSurviveNewStore() {
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("flight_hud",Context.MODE_PRIVATE).edit().clear().putFloat("map",.4f).commit()
        val store=HudPreferencesStore(app)
        assertEquals(.4f,store.load().mapOpacity,0f)
        store.checkpoint()
        store.save(store.load().copy(showMap=false,swapInstruments=true))
        store.save(store.load().copy(instrumentScale=.8f))
        val reloaded=HudPreferencesStore(app)
        assertEquals(2L,reloaded.revision()); assertFalse(reloaded.load().showMap)
        assertEquals(.4f,reloaded.rollback().mapOpacity,0f)
        assertTrue(reloaded.load().showMap); assertFalse(reloaded.load().swapInstruments)
        assertEquals(3L,reloaded.revision())
    }
    @Test fun automaticVideoConfigurationMigratesWithoutTurningOnManualCenterHud() {
        val app=RuntimeEnvironment.getApplication()
        val legacy="""{"schemaVersion":1,"display":{"showMap":false,"centerHudOnVideo":true,"centerHudOpacity":0.4}}"""
        app.getSharedPreferences("flight_hud",Context.MODE_PRIVATE).edit().clear()
            .putString("current_document",legacy).putString("checkpoint",legacy).commit()
        val store=HudPreferencesStore(app)
        val migrated=store.load()
        assertFalse(migrated.showCentralHud)
        assertFalse(migrated.showMap)
        assertEquals(.4f,migrated.centerHudOpacity,0f)
        store.save(migrated.copy(showCentralHud=true))
        assertTrue(HudPreferencesStore(app).load().showCentralHud)
        assertEquals(migrated,store.rollback())
        assertFalse(HudConfigCodec.encode(store.load()).contains("centerHudOnVideo"))
        assertTrue(runCatching { HudConfigCodec.decode(legacy.replace("false,", "false,\"disableProtection\":true,")) }.isFailure)
    }
    @Test fun hideAndSwapInstrumentsPreservesSafetyAndFullScreenVideo() {
        val hud=mutableStateOf(HudPreferences(level = HudDisplayLevel.L3))
        val s=RotorState(data_source="simulation_truth",selected_source="app")
        val notice=flightNotice(s,true,true,false,LinkPhase.READY,false,false,true,null)
        rule.setContent { VrxTheme { FlightCockpit(s,emptyList(),cockpitTestControl(notice),true,true,25.0,hud=hud.value) } }
        val before=rule.onNodeWithTag("attitude-panel").fetchSemanticsNode().boundsInRoot.left
        rule.runOnIdle { hud.value=hud.value.copy(swapInstruments=true,instrumentScale=.8f) }
        assertTrue(rule.onNodeWithTag("attitude-panel").fetchSemanticsNode().boundsInRoot.left>before)
        rule.runOnIdle { hud.value=hud.value.copy(showMap=false,showAttitude=false,showPosition=false) }
        rule.onNodeWithTag("position-panel").assertDoesNotExist()
        rule.onNodeWithTag("attitude-panel").assertDoesNotExist()
        rule.onNodeWithTag("position-readout").assertDoesNotExist()
        rule.onNodeWithText("HDG 000°").assertIsDisplayed()
        rule.onNodeWithTag("altitude-readout").assertIsDisplayed()
        rule.onNodeWithText("停止").assertIsDisplayed(); rule.onNodeWithText("保护").assertIsDisplayed()
        rule.onNodeWithTag("data-quality").assertIsDisplayed()
        assertEquals(rule.onNodeWithTag("cockpit").fetchSemanticsNode().boundsInRoot,
            rule.onNodeWithTag("ue-viewport").fetchSemanticsNode().boundsInRoot)
    }
    @Test fun stalePositionNumbersAreNotShownAsCurrent() {
        val s=RotorState(position_m=listOf(7.12,8.34,9.56),velocity_m_s=listOf(0.0,0.0,0.0))
        val notice=flightNotice(s,false,true,true,LinkPhase.DISCONNECTED,false,false,true,null)
        rule.setContent { VrxTheme { FlightCockpit(s,emptyList(),cockpitTestControl(notice,false,false),false,true,0.0,
            hud=HudPreferences(level=HudDisplayLevel.L3)) } }
        rule.onNodeWithTag("position-invalid-flag").assertIsDisplayed()
        rule.onNodeWithTag("attitude-invalid-flag").assertIsDisplayed()
        rule.onNodeWithTag("ground-speed-invalid-flag").assertIsDisplayed()
        rule.onNodeWithTag("vertical-speed-invalid-flag").assertIsDisplayed()
        rule.onNodeWithTag("map-invalid-flag", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("X 7.12  Y 8.34 m").assertDoesNotExist()
        rule.onNodeWithText("状态未更新 · 数据质量未知").assertIsDisplayed()
        rule.onNodeWithText("停止").assertIsNotEnabled()
    }
    @Test fun readyCannotHideFailedActionAndAckDoesNotMeanCompleted() {
        val n=flightNotice(RotorState(),true,true,false,LinkPhase.READY,false,false,true,"操作失败")
        assertEquals("REJECTED",n.id)
        assertTrue(CommandFeedback("id","停止","RECEIVED").text().contains("等待结果"))
        assertTrue(CommandFeedback("id","停止","TIMED_OUT").text().contains("待核实"))
        assertFalse(CommandFeedback("id","停止","RECEIVED").text().contains("完成"))
    }
}
