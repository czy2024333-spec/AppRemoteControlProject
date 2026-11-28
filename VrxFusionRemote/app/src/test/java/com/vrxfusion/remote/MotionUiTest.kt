package com.vrxfusion.remote

import android.graphics.Bitmap
import android.provider.Settings
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.drawToBitmap
import androidx.lifecycle.ViewModelProvider
import com.vrxfusion.remote.rotorpy.LinkPhase
import com.vrxfusion.remote.rotorpy.RotorPyClient
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import com.vrxfusion.remote.ui.*
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
                safetyContent={FlightSafetyButtons(true,{stop++},{protect++})}) {
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
    @Test fun pressScaleLongHoldDoesNotClickUntilReleaseAndNeverRepeats() {
        var clicks=0
        val measuredSizes=mutableListOf<androidx.compose.ui.unit.IntSize>()
        rule.mainClock.autoAdvance=false
        rule.setContent {VrxTheme {
            val interaction=remember { MutableInteractionSource() }
            Button(onClick={clicks++},interactionSource=interaction,
                modifier=Modifier.onSizeChanged {measuredSizes+=it}
                    .testTag("press-scale-target").pressScale(interaction)) {Text("停止")}
        }}
        frames()
        val sizeBefore=measuredSizes.last()
        val restBounds=rule.onNodeWithTag("press-scale-target").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag("press-scale-target").performTouchInput {down(center)}
        frames(120)
        rule.runOnIdle {assertEquals("按下只更新视觉状态，不能提前点击",0,clicks)}
        frames(480)
        rule.runOnIdle {assertEquals("长按期间不能重复触发",0,clicks)}
        assertEquals("graphicsLayer 按压反馈不改变组件测量尺寸",sizeBefore,measuredSizes.last())
        val pressedBounds=rule.onNodeWithTag("press-scale-target").fetchSemanticsNode().boundsInRoot
        assertTrue("按下时应实际缩放",pressedBounds.width<restBounds.width)
        rule.onNodeWithTag("press-scale-target").performTouchInput {up()}
        rule.runOnIdle {assertEquals("松手后原 Button 回调只触发一次",1,clicks)}
        frames(120)
        val settledBounds=rule.onNodeWithTag("press-scale-target").fetchSemanticsNode().boundsInRoot
        // 允许 0.5px 栅格化误差；Compose 按帧采样，120ms 目标落在帧边界之间。
        assertEquals("120ms 后水平位置复原",restBounds.left,settledBounds.left,.5f)
        assertEquals("120ms 后顶部复原",restBounds.top,settledBounds.top,.5f)
        assertEquals("120ms 后右边界复原",restBounds.right,settledBounds.right,.5f)
        assertEquals("120ms 后底部复原",restBounds.bottom,settledBounds.bottom,.5f)
    }
    @Test fun safetyButtonGesturesSendOneStopPerReleaseAndNoMotionRequests() {
        val requests=CopyOnWriteArrayList<String>()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        MockWebServer().use { server ->
            server.dispatcher=object: Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests+=request.path.orEmpty()
                    return when(request.path) {
                        "/api/state" -> MockResponse().setBody("""{"ok":true,"data":{"boot_id":"press-test","sequence":0,"mode":"HOLD","control_epoch":"e","selected_source":"app"}}""")
                        "/api/events" -> MockResponse().addHeader("Content-Type","text/event-stream")
                            .setBody((1..1000).joinToString("") {
                                "data: {\"boot_id\":\"press-test\",\"sequence\":$it,\"mode\":\"HOLD\",\"control_epoch\":\"e\",\"selected_source\":\"app\"}\n\n".padEnd(260,'\n')
                            }).throttleBody(260,40,TimeUnit.MILLISECONDS)
                        else -> MockResponse().setBody("""{"ok":true,"data":{"session_id":"s","control_epoch":"e","command_status":"COMPLETED"}}""")
                    }
                }
            }
            server.start()
            val client=RotorPyClient(server.url("/").toString(),"demo-app-token-2026")
            try {
                client.startPolling(scope)
                rule.waitUntil(5000) {client.connected.value}
                client.retryHandshake(scope)
                rule.waitUntil(5000) {client.phase.value==LinkPhase.READY && !client.busy.value}
                rule.mainClock.autoAdvance=false
                rule.setContent {VrxTheme {FlightSafetyButtons(true,
                    {scope.launch {client.stop()}},{scope.launch {client.protect()}})}}
                frames()
                rule.onNodeWithText("停止").performTouchInput {down(center);advanceEventTime(650)}
                frames(650)
                assertEquals("真实按压期间不能发送停止或运动",0,requests.count {it=="/api/stop" || it=="/api/input"})
                rule.onNodeWithText("停止").performTouchInput {up()}
                rule.waitUntil(5000) {requests.count {it=="/api/stop"}==1 && !client.safetyBusy.value}
                repeat(2) { index ->
                    rule.onNodeWithText("停止").performTouchInput {click()}
                    rule.waitUntil(5000) {requests.count {it=="/api/stop"}==index+2 && !client.safetyBusy.value}
                }
                rule.onNodeWithText("停止").performTouchInput {down(center);cancel()}
                frames(200)
                rule.runOnIdle {
                    assertEquals("三次有效松手只产生三条停止请求，取消不产生请求",3,requests.count {it=="/api/stop"})
                    assertEquals("按压不能额外产生 /api/input",0,requests.count {it=="/api/input"})
                }
                println("D3_HTTP_EVIDENCE stop=${requests.count {it=="/api/stop"}} input=${requests.count {it=="/api/input"}}")
            } finally {client.stopPolling();scope.cancel()}
        }
    }
    @Test fun reducedMotionKeepsPressedButtonAtRestWithoutSuppressingClick() {
        var clicks=0
        rule.mainClock.autoAdvance=false
        rule.setContent {VrxTheme {CompositionLocalProvider(LocalAppMotionEnabled provides false) {
            val interaction=remember {MutableInteractionSource()}
            Button(onClick={clicks++},interactionSource=interaction,
                modifier=Modifier.testTag("reduced-press").pressScale(interaction,StopPressScale)) {Text("停止")}
        }}}
        frames()
        val rest=rule.onNodeWithTag("reduced-press").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag("reduced-press").performTouchInput {down(center)}
        frames(200)
        assertEquals(rest,rule.onNodeWithTag("reduced-press").fetchSemanticsNode().boundsInRoot)
        rule.onNodeWithTag("reduced-press").performTouchInput {up()}
        rule.runOnIdle {assertEquals(1,clicks)}
    }
    @Test fun stickReadoutShowsAcceptanceValuesAndFadesWithoutChangingMeasuredSize() {
        val x=mutableStateOf(.05f);val touching=mutableStateOf(true)
        val measuredSizes=mutableListOf<androidx.compose.ui.unit.IntSize>()
        rule.mainClock.autoAdvance=false
        rule.setContent {VrxTheme {Box(Modifier.size(160.dp).background(Color.Black)
            .onSizeChanged {measuredSizes+=it}) {
            StickReadout(x.value,0f,StickAxis.YAW,StickAxis.VERTICAL,touching.value)
        }}}
        frames(240)
        rule.onNodeWithText("转向 中位").assertExists()
        change {x.value=.1f};frames()
        rule.onNodeWithText("转向 右 10%").assertExists()
        val size=measuredSizes.last()
        fun capture()=rule.runOnIdle {rule.activity.findViewById<View>(android.R.id.content)
            .drawToBitmap(Bitmap.Config.ARGB_8888)}
        val visible=capture()
        change {touching.value=false};frames(96)
        val fading=capture()
        frames(144)
        val hidden=capture()
        assertFalse("淡出中途应有真实像素变化",visible.sameAs(fading))
        assertFalse("淡出结束应不同于中途",fading.sameAs(hidden))
        assertEquals("淡出不改变测量尺寸",size,measuredSizes.last())
        visible.recycle();fading.recycle();hidden.recycle()
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
            save("page-forward-00")
            rule.onNodeWithText("连接与设备管理").performClick()
            repeat(14) {n->frames(32);save("page-forward-${(n+1).toString().padStart(2,'0')}")}
            // 横屏下字段可能需滚动；冻结录帧时钟时仅确认目标页面已经挂载。
            rule.onNodeWithTag("backend-address").assertExists()
            save("page-back-00")
            rule.onNodeWithTag("management-back").performClick()
            repeat(14) {n->frames(32);save("page-back-${(n+1).toString().padStart(2,'0')}")}
            rule.onNodeWithText("01  独立操纵").performClick()
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
