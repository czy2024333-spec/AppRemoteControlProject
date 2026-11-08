package com.vrxfusion.remote

import android.graphics.Bitmap
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.drawToBitmap
import androidx.lifecycle.ViewModelProvider
import com.vrxfusion.remote.logs.*
import com.vrxfusion.remote.ui.*
import com.vrxfusion.remote.ui.theme.VrxTheme
import com.vrxfusion.remote.unified.*
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
@Config(sdk=[34],qualifiers="zh-rCN-w360dp-h780dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ServerLogsUiTest {
    @get:Rule val rule=createAndroidComposeRule<ComponentActivity>()
    private val json=Json {ignoreUnknownKeys=true}
    private fun vm(supported: Boolean): MainViewModel {
        lateinit var vm: MainViewModel
        rule.activityRule.scenario.onActivity {vm=ViewModelProvider(it)[MainViewModel::class.java]}
        val caps=json.decodeFromJsonElement<UnifiedCapabilities>(json.parseToJsonElement(javaClass.classLoader!!
            .getResource("independent/observer-capabilities.json")!!.readText()).jsonObject["data"]!!)
        vm.flightGateway.repository.info.value=caps.copy(capabilities=caps.capabilities+mapOf("logs_query" to supported,"logs_export" to supported))
        vm.flightGateway.repository.connected.value=true;vm.flightGateway.repository.running.value=true
        rule.setContent {VrxTheme {ServerLogsPanel(vm)}}
        return vm
    }
    private fun save(name: String) {
        val file=File("build/cockpit-screenshots/$name.png");file.parentFile!!.mkdirs()
        val bitmap=rule.runOnIdle {rule.activity.findViewById<View>(android.R.id.content).drawToBitmap(Bitmap.Config.ARGB_8888)}
        file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun unsupportedBackendNeverShowsWorkingQueryOrExportButtons() {
        val vm=vm(false)
        try {
            rule.onNodeWithTag("logs-query").assertDoesNotExist()
            rule.onNodeWithTag("logs-export-jsonl").assertDoesNotExist()
            rule.onNodeWithText("当前后端未声明日志能力，请连接GitHub最新日志后端。").assertIsDisplayed()
        } finally {vm.pauseAll();vm.serverLogs.close()}
    }
    @Test fun editingFilterDisablesOldSnapshotExportAndNextPage() {
        val vm=vm(true)
        try {
            rule.runOnIdle {vm.serverLogs.view.value=ServerLogsView(page=LogPage(emptyList(),4,0,2,"g:1",true,0,true),filter=LogFilter())}
            rule.onNodeWithTag("logs-next").assertIsEnabled()
            rule.onNodeWithTag("logs-export-jsonl").assertIsEnabled()
            rule.onNodeWithTag("logs-event").performTextInput("PROTECTED")
            rule.onNodeWithTag("logs-next").assertIsNotEnabled()
            rule.onNodeWithTag("logs-export-jsonl").assertIsNotEnabled()
            rule.onNodeWithTag("logs-query").assertIsEnabled()
            rule.onNodeWithText("筛选已修改，请重新查询后翻页或导出。").assertIsDisplayed()
            save("logs-filter-guard")
        } finally {vm.pauseAll();vm.serverLogs.close()}
    }
    @Test fun indexingMessageDoesNotChangeConnectionOrControlPhaseAndDefaultResetHasOneRollbackPoint() {
        val vm=vm(true)
        try {
            val before=vm.flightGateway.phase.value
            rule.runOnIdle {vm.serverLogs.view.value=ServerLogsView(message=logIssueText(LogIssue("LOG_INDEX_BUILDING","r-2")))}
            rule.onNodeWithTag("logs-message").assertTextContains("索引正在建立",substring=true)
            assertTrue(vm.flightGateway.repository.connected.value)
            assertEquals(before,vm.flightGateway.phase.value)
            val custom=HudPreferences(mapOpacity=.4f)
            rule.runOnIdle {vm.setHudPreferences(custom);vm.resetHudDefaults()}
            assertEquals(HudPreferences(),vm.hudPreferences.value);assertTrue(vm.hudCheckpoint.value)
            rule.runOnIdle {vm.rollbackHud()};assertEquals(custom,vm.hudPreferences.value)
            save("logs-index-building")
        } finally {vm.pauseAll();vm.serverLogs.close()}
    }
}
