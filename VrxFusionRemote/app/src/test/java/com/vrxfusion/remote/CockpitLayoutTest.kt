package com.vrxfusion.remote

import android.graphics.Bitmap
import android.view.View
import androidx.activity.ComponentActivity
import androidx.core.view.drawToBitmap
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.vrxfusion.remote.rotorpy.*
import com.vrxfusion.remote.ui.*
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

/** 实际Compose布局截图与边界检查，避免用另画的示意图替代界面验证。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rCN-w800dp-h360dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CockpitLayoutTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    private val normal=RotorState(selected_source="app")
    private fun notice(s: RotorState)=flightNotice(s,true,true,false,LinkPhase.READY,false,false,true,null)
    private fun save(name: String) {
        val file=File("build/cockpit-screenshots/$name.png"); file.parentFile?.mkdirs()
        val bitmap=captureRoot()
        file.outputStream().buffered().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG,100,out)
        }
    }
    private fun captureRoot(): Bitmap = rule.runOnIdle {
        rule.activity.findViewById<View>(android.R.id.content).drawToBitmap(Bitmap.Config.ARGB_8888)
    }
    private fun captureDial(): Bitmap {
        val bounds=rule.onNodeWithTag("attitude-dial").fetchSemanticsNode().boundsInRoot
        return Bitmap.createBitmap(captureRoot(),bounds.left.toInt(),bounds.top.toInt(),bounds.width.toInt(),bounds.height.toInt())
    }
    private fun assertNoOverlap() {
        rule.onAllNodesWithTag("flight-notice").assertCountEquals(1)
        val root=rule.onNodeWithTag("cockpit").fetchSemanticsNode().boundsInRoot
        assertEquals("UE必须铺满全页",root,rule.onNodeWithTag("ue-viewport").fetchSemanticsNode().boundsInRoot)
        rule.onNodeWithTag("position-panel").assertIsDisplayed()
        rule.onNodeWithTag("attitude-panel").assertIsDisplayed()
        val tags=listOf("attitude-panel","position-panel","left-stick-panel","right-stick-panel","critical-actions","flight-notice")
        for (i in tags.indices) for (j in i+1 until tags.size) {
            val a=rule.onNodeWithTag(tags[i]).fetchSemanticsNode().boundsInRoot
            val b=rule.onNodeWithTag(tags[j]).fetchSemanticsNode().boundsInRoot
            assertFalse("HUD控件不能互相遮挡：${tags[i]} / ${tags[j]}",a.overlaps(b))
        }
    }
    @Test fun landscapeWarningsDoNotMoveOrCoverInstruments() {
        val state=mutableStateOf(normal)
        rule.setContent { VrxTheme { FlightCockpit(state.value,emptyList(),notice(state.value),true,true,25.0,state.value.mode != "PROTECTED",true) } }
        assertNoOverlap()
        val before=rule.onNodeWithTag("ue-viewport").fetchSemanticsNode().boundsInRoot
        save("landscape-ready")
        rule.runOnIdle { state.value=normal.copy(mode="PROTECTED",boundary_recovery=BoundaryRecovery(required=true)) }
        rule.waitForIdle(); assertNoOverlap()
        rule.onNodeWithText("等待恢复").assertIsNotEnabled()
        assertEquals(before,rule.onNodeWithTag("ue-viewport").fetchSemanticsNode().boundsInRoot)
        save("landscape-boundary")
        rule.onNodeWithTag("position-panel").assertExists()
        rule.onNodeWithTag("ue-viewport").assertExists()
        assertEquals(before,rule.onNodeWithTag("ue-viewport").fetchSemanticsNode().boundsInRoot)
        save("landscape-map-and-ue")
    }
    @Test fun changingPositionDoesNotAlterAttitudePixels() {
        val state=mutableStateOf(normal)
        rule.setContent { VrxTheme { FlightCockpit(state.value,emptyList(),notice(state.value),true,true,25.0,true,true) } }
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
    @Test @Config(qualifiers="zh-rCN-w640dp-h320dp-land-xhdpi")
    fun compactLandscapeHasNoOverlay() {
        rule.setContent { VrxTheme { FlightCockpit(normal,emptyList(),notice(normal),true,true,25.0,true,true) } }
        assertNoOverlap(); save("landscape-compact"); checkMapModes("landscape-compact-focus")
    }
    @Test @Config(qualifiers="zh-rCN-w360dp-h780dp-port-xhdpi")
    fun portraitKeepsSingleNotice() {
        rule.setContent { VrxTheme { FlightCockpit(normal,emptyList(),notice(normal),true,false,25.0,true,true) } }
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
        rule.setContent { VrxTheme { FlightCockpit(normal,emptyList(),notice(normal),true,true,25.0,true,true,hud=hud.value) } }
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
        rule.setContent { VrxTheme { FlightCockpit(sample,listOf(0.0 to 0.0,8.0 to -6.0),notice(sample),true,true,25.0,true,true) } }
        rule.onNodeWithText("X 8.00  Y -6.00",useUnmergedTree=true).assertIsDisplayed()
        rule.onNodeWithText("Z 1.00 m · 2m/格",useUnmergedTree=true).assertIsDisplayed()
        save("landscape-trajectory-overview")
        checkMapModes("landscape-current-focus")
    }
    @Test fun noticeHasTransparentBackgroundAndNoDetailsEntry() {
        val current=mutableStateOf(notice(normal))
        val background=Color(0xFFE3D9B7)
        rule.setContent { VrxTheme { FlightCockpit(normal,emptyList(),current.value,true,true,25.0,true,true,
            ueContent={ Box(Modifier.fillMaxSize().background(background)) }) } }
        fun checkBackground() {
            rule.onNodeWithText(current.value.title).assertIsDisplayed()
            rule.onNodeWithText("详情").assertDoesNotExist()
            val b=rule.onNodeWithTag("flight-notice").fetchSemanticsNode().boundsInRoot
            val pixel=captureRoot().getPixel(b.right.toInt()-2,b.center.y.toInt())
            assertEquals("所有告警状态的提示框都必须透明",0xFFE3D9B7.toInt(),pixel)
        }
        checkBackground()
        rule.runOnIdle { current.value=notice(normal.copy(mode="PROTECTED")) }
        checkBackground(); save("landscape-transparent-warning")
        rule.runOnIdle { current.value=flightNotice(normal,false,true,true,LinkPhase.DISCONNECTED,false,false,true,null) }
        checkBackground()
    }
    @Test fun fourDirectionGesturesAgreeWithTheFixedMap() {
        val values=mutableListOf<Offset>()
        rule.setContent { VrxTheme { FlightCockpit(normal,emptyList(),notice(normal),true,true,25.0,true,true,
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
