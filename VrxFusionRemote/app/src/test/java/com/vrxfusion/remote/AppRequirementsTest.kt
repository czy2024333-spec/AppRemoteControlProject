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
    @Test fun hideAndSwapInstrumentsPreservesSafetyAndFullScreenVideo() {
        val hud=mutableStateOf(HudPreferences())
        val s=RotorState(data_source="simulation_truth",selected_source="app")
        val notice=flightNotice(s,true,true,false,LinkPhase.READY,false,false,true,null)
        rule.setContent { VrxTheme { FlightCockpit(s,emptyList(),notice,true,true,25.0,true,true,hud=hud.value) } }
        val before=rule.onNodeWithTag("attitude-panel").fetchSemanticsNode().boundsInRoot.left
        rule.runOnIdle { hud.value=hud.value.copy(swapInstruments=true,instrumentScale=.8f) }
        assertTrue(rule.onNodeWithTag("attitude-panel").fetchSemanticsNode().boundsInRoot.left>before)
        rule.runOnIdle { hud.value=hud.value.copy(showMap=false,showAttitude=false,showPosition=false) }
        rule.onNodeWithTag("position-panel").assertDoesNotExist()
        rule.onNodeWithTag("attitude-panel").assertDoesNotExist()
        rule.onNodeWithTag("position-readout").assertDoesNotExist()
        rule.onNodeWithText("停止").assertIsDisplayed(); rule.onNodeWithText("保护").assertIsDisplayed()
        rule.onNodeWithTag("data-quality").assertIsDisplayed()
        assertEquals(rule.onNodeWithTag("cockpit").fetchSemanticsNode().boundsInRoot,
            rule.onNodeWithTag("ue-viewport").fetchSemanticsNode().boundsInRoot)
    }
    @Test fun stalePositionNumbersAreNotShownAsCurrent() {
        val s=RotorState(position_m=listOf(7.12,8.34,9.56))
        val notice=flightNotice(s,false,true,true,LinkPhase.DISCONNECTED,false,false,true,null)
        rule.setContent { VrxTheme { FlightCockpit(s,emptyList(),notice,false,true,0.0,false,false) } }
        rule.onNodeWithText("X —  Y —  Z — m").assertIsDisplayed()
        rule.onNodeWithText("状态未更新 · 仪表仅供历史参考").assertIsDisplayed()
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
