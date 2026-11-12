package com.vrxfusion.remote

import android.graphics.Bitmap
import android.provider.Settings
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.drawToBitmap
import androidx.lifecycle.ViewModelProvider
import com.vrxfusion.remote.ui.*
import com.vrxfusion.remote.ui.design.SafetyDock
import com.vrxfusion.remote.ui.theme.VrxTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 固定时钟逐帧检查真实Compose动效：中途状态、触摸隔离、系统关闭动画和安全按钮。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rCN-w360dp-h780dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MotionUiTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    private fun frames(ms: Long=48) {rule.mainClock.advanceTimeBy(ms)}
    private fun change(block: ()->Unit) {
        // 手动暂停时钟时，先发布测试线程直接写入的快照，再推进动画帧。
        rule.runOnIdle {block();Snapshot.sendApplyNotifications()}
    }
    private fun save(name: String) {
        val file=File("build/motion-frames/$name.png");file.parentFile!!.mkdirs()
        val bitmap=rule.runOnIdle {rule.activity.findViewById<View>(android.R.id.content).drawToBitmap(Bitmap.Config.ARGB_8888)}
        file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    @Test fun forwardBackAndInterruptedNavigationNeverMountTwoLivePages() {
        val page=mutableStateOf("A");val forward=mutableStateOf(true)
        val mounted=mutableSetOf<String>();var peak=0
        rule.mainClock.autoAdvance=false
        rule.setContent {VrxTheme {MotionContentHost(page.value,forward.value) {p ->
            DisposableEffect(p) {mounted.add(p);peak=maxOf(peak,mounted.size);onDispose {mounted.remove(p)}}
            Text("PAGE-$p")
        }}}
        frames();rule.onNodeWithText("PAGE-A").assertExists()
        change {page.value="B"};frames(64)
        assertTrue("target=${page.value} mounted=$mounted\n"+rule.onRoot().printToString(),
            rule.onAllNodesWithTag("motion-input-shield").fetchSemanticsNodes().isNotEmpty())
        rule.runOnIdle {assertEquals(setOf("B"),mounted)}
        frames(400);rule.onNodeWithText("PAGE-B").assertExists()
        change {forward.value=false;page.value="A"};frames(32)
        change {forward.value=true;page.value="C"};frames(400)
        rule.onNodeWithText("PAGE-C").assertExists()
        rule.onNodeWithTag("motion-input-shield").assertDoesNotExist()
        rule.runOnIdle {assertEquals(1,peak);assertEquals(setOf("C"),mounted)}
    }
    @Test fun aPressStartedOnTransitionShieldCannotClickTheNewPageAfterItDisappears() {
        val page=mutableStateOf("A");var clicks=0
        rule.mainClock.autoAdvance=false
        rule.setContent {VrxTheme {MotionContentHost(page.value) {p ->
            Box(Modifier.fillMaxSize().background(Color.DarkGray).clickable {clicks++},contentAlignment=Alignment.Center) {Text(p)}
        }}}
        frames();change {page.value="B"};frames(32)
        rule.onNodeWithTag("motion-page-host").performTouchInput {down(center)}
        frames(400)
        rule.onNodeWithTag("motion-page-host").performTouchInput {up()}
        rule.runOnIdle {assertEquals(0,clicks)}
        rule.onNodeWithTag("motion-page-host").performTouchInput {click()}
        rule.runOnIdle {assertEquals(1,clicks)}
    }
    @Test fun closingSheetRetainsInputBlockWhileSafetyDockRemainsImmediate() {
        val open=mutableStateOf(true);var stop=0;var protect=0
        var current=MotionSheetState(false,0f,false)
        rule.mainClock.autoAdvance=false
        rule.setContent {VrxTheme {
            val sheet=rememberMotionSheet(open.value)
            SideEffect {current=sheet}
            if (sheet.visible) FlightSettingsPanel(0,{},HudPreferences(),{},{open.value=false},
                motionProgress=sheet.progress,motionInputBlocked=!sheet.ready,
                safetyContent={SafetyDock(true,true,{stop++},{protect++})}) {
                Button(onClick={},modifier=Modifier.testTag("ordinary-body-button")) {Text("普通操作")}
            }
        }}
        frames(48)
        rule.onNodeWithTag("ordinary-body-button").assertDoesNotExist()
        rule.onNodeWithText("停止").assertIsDisplayed().performClick()
        rule.onNodeWithText("保护").assertIsDisplayed().performClick()
        val dock=rule.onNodeWithTag("settings-safety-dock").fetchSemanticsNode().boundsInRoot
        rule.runOnIdle {assertEquals(1,stop);assertEquals(1,protect);assertTrue(current.visible)}
        frames(700)
        rule.onNodeWithTag("ordinary-body-button").assertIsDisplayed()
        assertEquals(dock,rule.onNodeWithTag("settings-safety-dock").fetchSemanticsNode().boundsInRoot)
        val cardTop=rule.onNodeWithTag("settings-card").fetchSemanticsNode().boundsInRoot.top
        rule.onNodeWithTag("settings-done").performClick();frames(32)
        rule.runOnIdle {assertTrue(current.visible);assertFalse(current.ready)}
        rule.onNodeWithTag("ordinary-body-button").assertDoesNotExist()
        rule.onNodeWithText("停止").performClick()
        rule.runOnIdle {assertEquals(2,stop)}
        frames(80)
        assertTrue(rule.onNodeWithTag("settings-card").fetchSemanticsNode().boundsInRoot.top>cardTop)
        assertEquals(dock,rule.onNodeWithTag("settings-safety-dock").fetchSemanticsNode().boundsInRoot)
        frames(220)
        rule.onNodeWithTag("flight-settings-overlay").assertDoesNotExist()
        rule.runOnIdle {assertFalse(current.visible)}
    }
    @Test fun reducedMotionChangesPageAndRemovesSheetWithoutAnimatedTail() {
        val page=mutableStateOf("A");val open=mutableStateOf(true)
        var sheet=MotionSheetState(false,0f,false)
        rule.mainClock.autoAdvance=false
        rule.setContent {VrxTheme {CompositionLocalProvider(LocalAppMotionEnabled provides false) {
            val current=rememberMotionSheet(open.value);SideEffect {sheet=current}
            MotionContentHost(page.value) {Text("REDUCED-$it")}
        }}}
        frames();change {assertEquals(1f,sheet.progress,0f);assertTrue(sheet.ready);page.value="B";open.value=false}
        frames(48)
        rule.onNodeWithText("REDUCED-B").assertExists()
        rule.onNodeWithTag("motion-input-shield").assertDoesNotExist()
        rule.runOnIdle {assertFalse(sheet.visible);assertEquals(0f,sheet.progress,0f)}
    }
    @Test fun systemAnimationSwitchIsObservedWhileAppIsRunning() {
        val resolver=rule.activity.contentResolver
        val before=Settings.Global.getFloat(resolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)
        val transitions=Settings.Global.getFloat(resolver,Settings.Global.TRANSITION_ANIMATION_SCALE,1f)
        try {
            Settings.Global.putFloat(resolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)
            Settings.Global.putFloat(resolver,Settings.Global.TRANSITION_ANIMATION_SCALE,1f)
            rule.setContent {val enabled=rememberSystemMotionEnabled();Text(if (enabled) "MOTION-ON" else "MOTION-OFF")}
            rule.onNodeWithText("MOTION-ON").assertExists()
            rule.runOnIdle {Settings.Global.putFloat(resolver,Settings.Global.ANIMATOR_DURATION_SCALE,0f)}
            rule.waitUntil(3000) {rule.onAllNodesWithText("MOTION-OFF").fetchSemanticsNodes().size==1}
        } finally {
            Settings.Global.putFloat(resolver,Settings.Global.ANIMATOR_DURATION_SCALE,before)
            Settings.Global.putFloat(resolver,Settings.Global.TRANSITION_ANIMATION_SCALE,transitions)
        }
    }
    @Test fun homeCardPressFeedbackDoesNotDelayItsClickCallback() {
        var clicks=0
        rule.mainClock.autoAdvance=false
        rule.setContent {VrxTheme {FlightHomeScreen({clicks++},{},{})}}
        frames()
        rule.onNodeWithText("01  独立操纵").performScrollTo().performTouchInput {down(center)}
        frames(64)
        rule.runOnIdle {assertEquals(0,clicks)}
        rule.onNodeWithText("01  独立操纵").performTouchInput {up()}
        rule.runOnIdle {assertEquals(1,clicks)}
    }
    @Test fun navigationPreviewHasABoundedPixelSizeEvenOnVeryLargeDisplays() {
        val view=View(rule.activity)
        view.layout(0,0,4000,8000)
        val image=captureMotionPreview(view)
        assertNotNull(image)
        assertEquals(1280,image!!.height)
        assertEquals(640,image.width)
    }
    @Test @Config(qualifiers="zh-rCN-w800dp-h360dp-land-xhdpi")
    fun recordsRealAppNavigationAndSettingsFrames() {
        val originalOrientation=rule.activity.requestedOrientation
        rule.mainClock.autoAdvance=false
        rule.setContent {VrxTheme {App(motionEnabled=true)}}
        frames()
        val vm=rule.runOnIdle {ViewModelProvider(rule.activity)[MainViewModel::class.java]}
        try {
            // 首页分组列表在横屏需要先滚动，恢复时钟以完成滚动后再逐帧录制。
            rule.mainClock.autoAdvance=true
            val managementRow=rule.onNodeWithText("连接与设备管理").performScrollTo().assertIsDisplayed()
            rule.mainClock.autoAdvance=false
            save("page-forward-00")
            managementRow.performClick()
            repeat(14) {n->frames(32);save("page-forward-${(n+1).toString().padStart(2,'0')}")}
            // 横屏下字段可能需滚动；冻结录帧时钟时仅确认目标页面已经挂载。
            rule.onNodeWithTag("backend-address").assertExists()
            save("page-back-00")
            rule.onNodeWithTag("management-back").performClick()
            repeat(14) {n->frames(32);save("page-back-${(n+1).toString().padStart(2,'0')}")}
            rule.onNodeWithText("01  独立操纵").assertIsDisplayed().performClick()
            frames(450)
            assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,rule.activity.requestedOrientation)
            rule.onNodeWithContentDescription("切换竖屏").assertDoesNotExist()
            save("sheet-open-00")
            rule.onNodeWithContentDescription("飞行设置").performClick()
            repeat(18) {n->frames(32);save("sheet-open-${(n+1).toString().padStart(2,'0')}")}
            rule.onNodeWithTag("settings-done").assertIsDisplayed()
            save("sheet-close-00")
            rule.onNodeWithTag("settings-done").performClick()
            repeat(10) {n->frames(32);save("sheet-close-${(n+1).toString().padStart(2,'0')}")}
            rule.onNodeWithTag("flight-settings-overlay").assertDoesNotExist()
            assertFalse(vm.flightGateway.running.value)
            assertTrue(vm.descriptors.value.isEmpty())
            rule.onNodeWithContentDescription("返回首页").performClick()
            frames(450)
            assertEquals(originalOrientation,rule.activity.requestedOrientation)
        } finally {vm.pauseAll()}
    }
}
