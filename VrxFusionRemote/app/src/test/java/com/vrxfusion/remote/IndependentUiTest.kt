package com.vrxfusion.remote

import android.graphics.Bitmap
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.drawToBitmap
import com.vrxfusion.remote.rotorpy.*
import com.vrxfusion.remote.unified.*
import com.vrxfusion.remote.ui.*
import com.vrxfusion.remote.ui.theme.VrxTheme
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rCN-w800dp-h360dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IndependentUiTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    private val json=Json {ignoreUnknownKeys=true}
    private fun state(who: String="pilot-2")=json.decodeFromJsonElement<UnifiedSnapshot>(json.parseToJsonElement(
        javaClass.classLoader!!.getResource("independent/$who-state.json")!!.readText()).jsonObject["data"]!!)
    private fun save(name: String) {
        val f=File("build/cockpit-screenshots/$name.png"); f.parentFile!!.mkdirs()
        val bitmap=rule.runOnIdle {rule.activity.findViewById<View>(android.R.id.content).drawToBitmap(Bitmap.Config.ARGB_8888)}
        f.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun cockpitKeepsThreeCoordinatesAndSelfVisibleThroughFocusChange() {
        val s=state(); val own=s.members.single {it.is_self}.displayOver(RotorState(selected_source="app"))
        rule.setContent {VrxTheme {FlightCockpit(own,emptyList(),cockpitTestControl(flightNotice(own,true,true,false,LinkPhase.READY,false,false,true,null)),
            true,true,25.0,fleetMembers=s.members,selfDeviceId="sim-drone-2",memberFresh={true})}}
        save("independent-pilot2-initial")
        s.members.forEach {rule.onNodeWithTag("fleet-coordinate-${it.device_id}",useUnmergedTree=true).assertIsDisplayed()}
        rule.onNodeWithText("2本机 X2.0 Y0.0").assertExists()
        rule.onNodeWithTag("altitude-readout").assertIsDisplayed()
        val before=rule.onNodeWithTag("position-panel").fetchSemanticsNode().boundsInRoot
        save("independent-pilot2-landscape")
        rule.onNodeWithTag("position-panel").performClick()
        assertEquals(before,rule.onNodeWithTag("position-panel").fetchSemanticsNode().boundsInRoot)
        s.members.forEach {rule.onNodeWithTag("fleet-coordinate-${it.device_id}",useUnmergedTree=true).assertIsDisplayed()}
        save("independent-pilot2-focused")
    }
    @Test @Config(qualifiers="zh-rCN-w360dp-h780dp-port-xhdpi")
    fun portraitCockpitKeepsOtherAircraftStaleWithoutHidingSelf() {
        val s=state("pilot-1")
        val own=s.members.first().displayOver(RotorState(selected_source="app"))
        rule.setContent {VrxTheme {FlightCockpit(own,emptyList(),cockpitTestControl(flightNotice(own,true,true,false,LinkPhase.READY,false,false,true,null)),
            true,false,25.0,fleetMembers=s.members,selfDeviceId="sim-drone-1",memberFresh={it.device_id!="sim-drone-3"})}}
        s.members.forEach {rule.onNodeWithTag("fleet-coordinate-${it.device_id}",useUnmergedTree=true).assertIsDisplayed()}
        rule.onNodeWithText("3 · 位置失效",useUnmergedTree=true).assertIsDisplayed()
        save("independent-pilot1-portrait")
    }
    @Test @Config(qualifiers="zh-rCN-w360dp-h780dp-port-xhdpi")
    fun observerShowsAllThreePositionsAndStatesWithNoControlButtons() {
        val s=state("observer")
        rule.setContent {VrxTheme {Column(Modifier.fillMaxSize().background(Color(0xFF101D2B)).verticalScroll(rememberScrollState())) {
            UnifiedFleetContent(s,null,true) {true}
        }}}
        s.members.forEach {rule.onNodeWithText(it.device_id).performScrollTo().assertIsDisplayed()
            rule.onNodeWithText(positionText(it,true)).assertExists()}
        rule.onAllNodesWithText("仿真真值 · 悬停").assertCountEquals(3)
        rule.onAllNodesWithText("申请控制").assertCountEquals(0)
        rule.onNodeWithTag("fleet-quick-overview").performScrollTo()
        s.members.forEach {rule.onNodeWithTag("fleet-overview-${it.device_id}",useUnmergedTree=true).assertIsDisplayed()}
        save("independent-observer-three")
    }
    @Test fun identityPresetOnlyChangesDraftAndLocksWhileConnected() {
        var selected by mutableStateOf(0); var connected by mutableStateOf(false); var changes=0
        rule.setContent {VrxTheme {Column {IdentityPresets(selected,connected) {selected=it;changes++}}}}
        rule.onNodeWithTag("identity-2").performClick()
        rule.runOnIdle {assertEquals(2,selected);assertEquals(1,changes);connected=true}
        rule.onNodeWithTag("identity-0").assertIsNotEnabled()
        rule.runOnIdle {assertEquals(1,changes)}
    }
}
