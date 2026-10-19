package com.vrxfusion.remote

import android.graphics.Bitmap
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.drawToBitmap
import com.vrxfusion.remote.ui.*
import com.vrxfusion.remote.ui.theme.VrxTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 验证实际设置界面的同窗口、点击隔离、配置保存和横竖屏布局。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="zh-rCN-w800dp-h360dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsLayoutTest {
    @Test @Config(qualifiers="zh-rCN-w640dp-h320dp-land-xhdpi")
    fun compactSafetyButtonsStayVisibleWhileCategoriesScroll() {
        val tab=mutableIntStateOf(0); var protection=0
        rule.setContent { VrxTheme { FlightSettingsPanel(tab.intValue,{ tab.intValue=it },HudPreferences(),{},{},
            videoContent={ VideoSettingsPanel(com.vrxfusion.remote.video.VideoSettings(),"",com.vrxfusion.remote.video.VideoSource.NONE,"未启用测试画面",{},{},{},{}) },
            safetyContent={ FlightSafetyButtons(true,{}, {protection++}) }) { Fields() } } }
        rule.onNodeWithText("保护").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1,protection) }
        rule.onNodeWithText("画面测试").performScrollTo().performClick()
        rule.onNodeWithText("启用测试画面").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("停止").assertIsDisplayed()
        rule.onNodeWithTag("settings-done").assertIsDisplayed()
        save("settings-safety-compact")
    }
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    private fun save(name: String) {
        val file=File("build/cockpit-screenshots/$name.png"); file.parentFile?.mkdirs()
        val bitmap=rule.runOnIdle { rule.activity.findViewById<View>(android.R.id.content).drawToBitmap(Bitmap.Config.ARGB_8888) }
        file.outputStream().buffered().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Composable private fun Fields(running: Boolean=false) {
        ConnectionFields("http://192.168.1.100:8765","demo-app-token-2026",running,{},{},{},{})
    }
    private fun singleWindow() {
        rule.onAllNodes(isRoot()).assertCountEquals(1)
        rule.onAllNodes(isDialog()).assertCountEquals(0)
        rule.onNodeWithTag("settings-done").assertIsDisplayed()
        val panel=rule.onNodeWithTag("settings-panel").fetchSemanticsNode().boundsInRoot
        val done=rule.onNodeWithTag("settings-done").fetchSemanticsNode().boundsInRoot
        assertTrue(panel.contains(done.topLeft)); assertTrue(panel.contains(done.bottomRight-Offset(1f,1f)))
    }
    @Test fun settingsUsesOneRootBlocksClickThroughAndBackClosesOnlyPanel() {
        val open=mutableStateOf(true); val tab=mutableIntStateOf(1); var behindClicks=0
        rule.setContent { VrxTheme { Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color(0xFF102033)).clickable { behindClicks++ }.testTag("underlying-cockpit"))
            if (open.value) FlightSettingsPanel(tab.intValue,{ tab.intValue=it },HudPreferences(),{},
                { open.value=false },"已连接 · 25.0 Hz",true) { Fields() }
        } } }
        singleWindow(); save("settings-landscape")
        // 点击面板空白处不能触发下层操控，也不能关闭设置。
        rule.onNodeWithTag("settings-panel").performTouchInput { click(Offset(3f,height/2f)) }
        rule.runOnIdle { assertEquals(0,behindClicks); assertTrue(open.value) }
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithTag("flight-settings-overlay").assertDoesNotExist()
        rule.onNodeWithTag("underlying-cockpit").assertIsDisplayed()
        rule.runOnIdle { assertEquals(0,behindClicks) }
    }
    @Test fun presetsAndSlidersSaveWithoutChangingOtherFields() {
        val store=HudPreferencesStore(RuntimeEnvironment.getApplication())
        val value=mutableStateOf(store.save(HudPreferences()))
        rule.setContent { VrxTheme { FlightSettingsPanel(1,{},value.value,{ value.value=store.save(it) },{}) { Fields() } } }
        rule.onNodeWithText("沉浸").performClick()
        rule.runOnIdle { assertEquals(HudPreset.IMMERSIVE.preferences,store.load()) }
        rule.onNodeWithContentDescription("地图不透明度").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(.4f) }
        rule.runOnIdle {
            val saved=store.load(); assertEquals(.4f,saved.mapOpacity,.001f)
            assertEquals(HudPreset.IMMERSIVE.preferences.joystickOpacity,saved.joystickOpacity,0f)
        }
        rule.onNodeWithText("显示预览").performScrollTo(); save("settings-preview")
        rule.onNodeWithText("恢复显示默认值").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(HudPreferences(),store.load()) }
    }
    @Test @Config(qualifiers="zh-rCN-w360dp-h780dp-port-xhdpi")
    fun portraitSettingsKeepsDoneVisibleAndCanReachAllControls() {
        val tab=mutableIntStateOf(1)
        rule.setContent { VrxTheme { FlightSettingsPanel(tab.intValue,{ tab.intValue=it },HudPreferences(),{},{}) { Fields() } } }
        singleWindow(); save("settings-portrait")
        rule.onNodeWithText("恢复显示默认值").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("settings-done").assertIsDisplayed()
        rule.onNodeWithText("连接服务").performClick()
        rule.onNodeWithTag("backend-address").assertIsNotFocused()
        rule.onNodeWithTag("backend-token").assertIsNotFocused()
        rule.onNodeWithText("连接并查看状态").performScrollTo().assertIsDisplayed()
        singleWindow(); save("settings-connection-portrait")
    }
    @Test @Config(qualifiers="zh-rCN-w640dp-h320dp-land-xhdpi")
    fun compactConnectionDoesNotAutoFocusAndCredentialsLockWhileRunning() {
        val running=mutableStateOf(false)
        rule.setContent { VrxTheme { FlightSettingsPanel(0,{},HudPreferences(),{},{} ) { Fields(running.value) } } }
        rule.onNodeWithTag("backend-address").performScrollTo().assertIsNotFocused()
        rule.onNodeWithTag("backend-token").performScrollTo().assertIsNotFocused()
        rule.onNodeWithText("连接并查看状态").performScrollTo().assertIsDisplayed()
        singleWindow(); save("settings-connection-compact")
        rule.runOnIdle { running.value=true }
        rule.onNodeWithTag("backend-address").assertIsNotEnabled()
        rule.onNodeWithTag("backend-token").assertIsNotEnabled()
        rule.onNodeWithText("断开连接 / 修改地址").performScrollTo().assertIsEnabled()
    }
}
