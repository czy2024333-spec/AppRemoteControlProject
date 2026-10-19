package com.vrxfusion.remote

import android.graphics.Bitmap
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.drawToBitmap
import androidx.lifecycle.ViewModelStore
import com.vrxfusion.remote.ui.*
import com.vrxfusion.remote.ui.theme.VrxTheme
import com.vrxfusion.remote.video.*
import com.vrxfusion.remote.rotorpy.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rCN-w800dp-h360dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoUiTest {
    @Test fun stalledVideoShowsTimeoutInsteadOfLiveStatus() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val source=mutableStateOf(VideoSource.PC); val status=mutableStateOf("")
            rule.setContent { VrxTheme { TestVideoViewport(source.value,
                VideoSettings(VideoSource.PC,server.url("/video").toString()),"camera-code-2026-abc",{status.value=it}) } }
            try {
                rule.waitUntil(3000) { server.requestCount>0 }
                // 同时推进Robolectric系统时钟并派发主线程队列，模拟完整帧连续缺失。
                org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(3500))
                rule.waitUntil(4500) {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(250,TimeUnit.MILLISECONDS)
                    status.value.contains("超过3秒未更新")
                }
                rule.waitForIdle()
                assertTrue(status.value,status.value.contains("超过3秒未更新"))
                rule.onNodeWithText("电脑画面超过3秒未更新，请检查视频源后重新启用").assertIsDisplayed()
            } finally { rule.runOnIdle { source.value=VideoSource.NONE }; rule.waitForIdle() }
        }
    }
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    private fun bitmap()=rule.runOnIdle { rule.activity.findViewById<View>(android.R.id.content).drawToBitmap(Bitmap.Config.ARGB_8888) }
    private fun save(name: String) {
        val file=File("build/cockpit-screenshots/$name.png"); file.parentFile?.mkdirs()
        file.outputStream().buffered().use { bitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun selectingSourceDoesNotActivateCameraOrNetwork() {
        val settings=mutableStateOf(VideoSettings()); var enabled=0
        rule.setContent { VrxTheme { FlightSettingsPanel(2,{},HudPreferences(),{},{},videoContent={
            VideoSettingsPanel(settings.value,"",VideoSource.NONE,"未启用测试画面",{ settings.value=it },{},{ enabled++ },{})
        }) {} } }
        rule.onNodeWithText("电脑摄像头").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(0,enabled); assertEquals(VideoSource.PC,settings.value.source) }
        rule.onNodeWithText("启用测试画面").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("手机摄像头").performScrollTo().performClick()
        rule.onNodeWithText("启用测试画面").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1,enabled) }
    }
    @Test fun preferencesPersistButActiveCameraAndAccessCodeDoNotRestore() {
        val app=RuntimeEnvironment.getApplication()
        val first=MainViewModel(app); val second=MainViewModel(app)
        val store=ViewModelStore(); store.put("first",first); store.put("second",second)
        try {
            first.setVideoSettings(VideoSettings(VideoSource.PHONE,"http://host:8768/video.mjpeg",true))
            first.videoCode.value="camera-secret-2026"
            first.startVideo(); assertEquals(VideoSource.PHONE,first.videoSource.value)
            first.pauseAll(); assertEquals(VideoSource.NONE,first.videoSource.value)
            val saved=VideoSettingsStore(app).load(); assertTrue(saved.frontCamera)
            assertEquals(VideoSource.NONE,second.videoSource.value); assertEquals("",second.videoCode.value)
        } finally { store.clear() }
    }
    @Test @Config(qualifiers="zh-rCN-w360dp-h780dp-port-xhdpi")
    fun portraitVideoCategoryHasNoDialogAndCanReachControls() {
        val settings=mutableStateOf(VideoSettings(VideoSource.PC))
        rule.setContent { VrxTheme { FlightSettingsPanel(2,{},HudPreferences(),{},{},videoContent={
            VideoSettingsPanel(settings.value,"camera-code-2026-abc",VideoSource.NONE,"未启用测试画面",{settings.value=it},{},{},{})
        }) {} } }
        rule.onAllNodes(isDialog()).assertCountEquals(0)
        rule.onAllNodesWithText("画面测试").assertCountEquals(2)
        rule.onNodeWithTag("settings-done").assertIsDisplayed()
        rule.onNodeWithTag("video-url").performScrollTo().assertIsNotFocused()
        save("settings-video-pc")
        rule.onNodeWithText("启用测试画面").performScrollTo().assertIsEnabled()
    }
    @Test fun actualJpegVideoDrawsBelowFlightHud() {
        MockWebServer().use { server ->
            val frame=Bitmap.createBitmap(160,90,Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(20,150,80)) }
            val bytes=ByteArrayOutputStream().also { frame.compress(Bitmap.CompressFormat.JPEG,90,it) }.toByteArray()
            val part="--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray()+bytes+"\r\n".toByteArray()
            val buffer=Buffer(); repeat(100) { buffer.write(part) }
            server.enqueue(MockResponse().addHeader("Content-Type","multipart/x-mixed-replace; boundary=frame")
                .setBody(buffer).throttleBody(part.size.toLong(),100,TimeUnit.MILLISECONDS))
            val source=mutableStateOf(VideoSource.PC); val status=mutableStateOf("")
            var stopClicks=0
            val state=RotorState(selected_source="app")
            val notice=flightNotice(state,true,true,false,LinkPhase.READY,false,false,true,null)
            rule.setContent { VrxTheme { FlightCockpit(state,emptyList(),notice,true,true,25.0,true,true,
                onStop={ stopClicks++ },
                videoLabel="电脑摄像头（测试图）",ueContent={ TestVideoViewport(source.value,
                    VideoSettings(VideoSource.PC,server.url("/video").toString()),"camera-code-2026-abc",{status.value=it}) }) } }
            try {
                try { rule.waitUntil(6000) {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    status.value.contains("播放中")
                } } catch (e: Exception) { throw AssertionError("PC画面状态=${status.value}; 请求数=${server.requestCount}",e) }
                rule.waitForIdle()
                val root=rule.onNodeWithTag("cockpit").fetchSemanticsNode().boundsInRoot
                assertEquals(root,rule.onNodeWithTag("ue-viewport").fetchSemanticsNode().boundsInRoot)
                rule.onNodeWithTag("position-panel").assertIsDisplayed()
                rule.onNodeWithTag("attitude-panel").assertIsDisplayed()
                val pixel=bitmap().getPixel(root.center.x.toInt(),root.center.y.toInt())
                assertTrue("底层必须实际绘出JPEG，而非只显示已连接文字",android.graphics.Color.green(pixel)>100)
                save("video-pc-hud")
                rule.onNodeWithText("停止").performClick()
                rule.runOnIdle { assertEquals("实际视频不能挡住HUD按钮点击",1,stopClicks) }
            } finally { rule.runOnIdle { source.value=VideoSource.NONE }; rule.waitForIdle() }
        }
    }
    @Test @Config(qualifiers="zh-rCN-w640dp-h320dp-land-xhdpi")
    fun compactLandscapeCanReachAllThreeCategories() {
        val tab=mutableIntStateOf(2)
        rule.setContent { VrxTheme { FlightSettingsPanel(tab.intValue,{tab.intValue=it},HudPreferences(),{},{},videoContent={
            VideoSettingsPanel(VideoSettings(),"",VideoSource.NONE,"未启用测试画面",{},{},{},{})
        }) {} } }
        rule.onNodeWithText("显示布局").assertIsDisplayed()
        rule.onNodeWithText("连接服务").assertIsDisplayed()
        rule.onAllNodesWithText("画面测试")[0].assertIsDisplayed()
        rule.onNodeWithTag("settings-done").assertIsDisplayed()
        save("settings-video-phone-landscape")
        rule.onNodeWithText("启用测试画面").performScrollTo().assertIsEnabled()
    }
}
