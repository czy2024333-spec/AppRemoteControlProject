package com.vrxfusion.remote

import android.graphics.Bitmap
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.drawToBitmap
import androidx.lifecycle.ViewModelProvider
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

/** 使用后端真实样例渲染管理页，核验身份、只读边界及历史数据提示。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w360dp-h780dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackendManagementUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val json = Json { ignoreUnknownKeys = true }
    private inline fun <reified T> sample(who: String, name: String): T = json.decodeFromJsonElement(
        json.parseToJsonElement(javaClass.classLoader!!.getResource("independent/$who-$name.json")!!.readText())
            .jsonObject["data"]!!)
    private fun save(name: String) {
        val f = File("build/cockpit-screenshots/$name.png"); f.parentFile!!.mkdirs()
        val b = rule.runOnIdle { rule.activity.findViewById<View>(android.R.id.content).drawToBitmap(Bitmap.Config.ARGB_8888) }
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Composable private fun fields() {
        IdentityPresets(0, true) {}
        ConnectionFields("http://192.168.1.100:8765", "demo-pilot-1-token-2026", true, {}, {}, {}, {})
    }
    @Test fun serverIdentityWinsOverTheSelectedDraftAndOnlyOwnMemberCanBeRequested() {
        val info = sample<UnifiedCapabilities>("pilot-2", "capabilities")
        val directory = sample<UnifiedDirectory>("pilot-2", "devices")
        val snapshot = sample<UnifiedSnapshot>("pilot-2", "state")
        rule.setContent { VrxTheme { BackendManagementContent(info, directory, snapshot, true, true, 25.0, null, { true }, {}) { fields() } } }
        save("management-pilot2-connection")
        rule.onNodeWithText("客户端：pilot-2").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("本端绑定：sim-drone-2").assertIsDisplayed()
        directory.devices.forEach { rule.onNodeWithTag("management-device-${it.device_id}").performScrollTo().assertIsDisplayed() }
        rule.onAllNodesWithText("本端绑定 · 可到操纵页申请").assertCountEquals(1)
        rule.onAllNodesWithText("其他成员 · 仅观察").assertCountEquals(2)
        rule.onAllNodesWithText("已接管").assertCountEquals(0)
        rule.onNodeWithTag("management-device-sim-drone-2").performScrollTo()
        save("management-pilot2-devices")
    }
    @Test fun observerReadsThreeMembersWithoutControlOrRolePicker() {
        val i = sample<UnifiedCapabilities>("observer", "capabilities")
        val d = sample<UnifiedDirectory>("observer", "devices")
        val s = sample<UnifiedSnapshot>("observer", "state")
        rule.setContent { VrxTheme { BackendManagementContent(i, d, s, true, true, 25.0, null, { true }, {}) {} } }
        rule.onNodeWithText("后端身份：观察端").assertIsDisplayed()
        rule.onNodeWithText("本端绑定：无（只读观察）").assertIsDisplayed()
        d.devices.forEach { rule.onNodeWithTag("management-device-${it.device_id}").performScrollTo().assertIsDisplayed() }
        rule.onAllNodesWithText("其他成员 · 仅观察").assertCountEquals(3)
        rule.onAllNodesWithText("本端绑定 · 可到操纵页申请").assertCountEquals(0)
        rule.onAllNodesWithText("申请控制").assertCountEquals(0)
        rule.onAllNodesWithText("管理员").assertCountEquals(0)
        rule.onNodeWithText("设备与能力").performScrollTo()
        save("management-observer")
    }
    @Test fun disconnectRetainsOnlyClearlyMarkedHistoryEvenIfFreshCallbackIsWrong() {
        val i = sample<UnifiedCapabilities>("pilot-2", "capabilities")
        val d = sample<UnifiedDirectory>("pilot-2", "devices")
        val s = sample<UnifiedSnapshot>("pilot-2", "state")
        var connected by mutableStateOf(true)
        rule.setContent { VrxTheme { BackendManagementContent(i, d, s, connected, connected, 25.0, null, { true }, {}) {} } }
        rule.runOnIdle { connected = false }
        rule.onNodeWithText("已断开 · 下方仅为历史信息").assertIsDisplayed()
        rule.onNodeWithText("最近确认的历史身份，不代表当前已认证。").assertIsDisplayed()
        rule.onAllNodesWithText("历史状态").assertCountEquals(3)
        rule.onAllNodes(hasText("X ",substring=true)).assertCountEquals(0)
        rule.onAllNodesWithText("本端绑定 · 可到操纵页申请").assertCountEquals(0)
        rule.onNodeWithTag("management-device-sim-drone-2").performScrollTo()
        save("management-disconnected-history")
    }
    @Test fun oneStaleMemberDoesNotInheritFreshnessFromOtherMembers() {
        val i = sample<UnifiedCapabilities>("pilot-2", "capabilities")
        val d = sample<UnifiedDirectory>("pilot-2", "devices")
        val s = sample<UnifiedSnapshot>("pilot-2", "state")
        rule.setContent { VrxTheme { BackendManagementContent(i, d, s, true, true, 25.0, null,
            { it.device_id != "sim-drone-3" }, {}) {} } }
        rule.onNodeWithTag("management-device-sim-drone-3").performScrollTo()
        rule.onNode(hasText("状态过期") and hasAnyAncestor(hasTestTag("management-device-sim-drone-3"))).assertIsDisplayed()
        rule.onAllNodes(hasText("X ",substring=true)).assertCountEquals(0)
        rule.onAllNodesWithText("在线 · 状态有效").assertCountEquals(2)
    }
    @Test fun missingCatalogDoesNotTurnSnapshotOrLocalDemoIntoADeviceDirectory() {
        val i = sample<UnifiedCapabilities>("pilot-2", "capabilities")
        val s = sample<UnifiedSnapshot>("pilot-2", "state")
        rule.setContent { VrxTheme { BackendManagementContent(i, null, s, true, true, 25.0,
            "设备能力刷新失败（HTTP_503）", { true }, {}) {} } }
        rule.onNodeWithTag("management-no-directory").performScrollTo().assertIsDisplayed()
        s.members.forEach { rule.onNodeWithTag("management-device-${it.device_id}").assertDoesNotExist() }
        rule.onNodeWithText("状态在线 · 设备目录待确认").assertIsDisplayed()
        rule.onNodeWithText("本地演示机").assertDoesNotExist()
        save("management-catalog-unavailable")
    }
    @Test fun partialDiscoveryFailureAndOfflineBoundAircraftNeverLookReady() {
        val i = sample<UnifiedCapabilities>("pilot-2", "capabilities")
        val d = sample<UnifiedDirectory>("pilot-2", "devices")
        val s = sample<UnifiedSnapshot>("pilot-2", "state")
        var discoveryFailed by mutableStateOf(true)
        rule.setContent { VrxTheme { BackendManagementContent(i, if (discoveryFailed) null else d,
            if (discoveryFailed) null else s.copy(members = s.members.map {
                if (it.device_id == "sim-drone-2") it.copy(online = false) else it
            }), !discoveryFailed, !discoveryFailed, 25.0,
            if (discoveryFailed) "设备目录与本端权限不一致" else null, { true }, {}) {} } }
        rule.onNodeWithText("连接失败").assertIsDisplayed()
        rule.onAllNodesWithText("本端绑定 · 可到操纵页申请").assertCountEquals(0)
        rule.runOnIdle { discoveryFailed = false }
        rule.onNodeWithTag("management-device-sim-drone-2").performScrollTo()
        rule.onNodeWithText("设备离线").assertIsDisplayed()
        rule.onNodeWithText("本端绑定 · 当前仅观察").assertIsDisplayed()
        rule.onAllNodesWithText("本端绑定 · 可到操纵页申请").assertCountEquals(0)
        rule.onAllNodes(hasText("X ",substring=true)).assertCountEquals(0)
    }
    @Test @Config(qualifiers = "zh-rCN-w800dp-h360dp-land-xhdpi")
    fun landscapeKeepsHeaderVisibleWhileDirectoryAndConnectionScrollIndependently() {
        val i = sample<UnifiedCapabilities>("pilot-2", "capabilities")
        val d = sample<UnifiedDirectory>("pilot-2", "devices")
        val s = sample<UnifiedSnapshot>("pilot-2", "state")
        rule.setContent { VrxTheme { BackendManagementContent(i, d, s, true, true, 25.0, null, { true }, {}) { fields() } } }
        save("management-landscape-initial")
        rule.onNodeWithTag("backend-address").performScrollTo().assertIsDisplayed()
        val before = rule.onNodeWithTag("backend-address").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag("management-device-sim-drone-3").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("management-back").assertIsDisplayed()
        assertEquals(before, rule.onNodeWithTag("backend-address").fetchSemanticsNode().boundsInRoot)
        save("management-landscape-devices")
    }
    @Test fun restoredLegacyRouteOpensUnifiedManagementWithoutStartingLegacyDevices() {
        rule.setContent { VrxTheme { App(initialPage = "legacy") } }
        rule.onNodeWithText("连接与设备管理").assertIsDisplayed()
        rule.onNodeWithText("用户名").assertDoesNotExist()
        rule.onNodeWithText("本地演示机").assertDoesNotExist()
        val vm = rule.runOnIdle { ViewModelProvider(rule.activity)[MainViewModel::class.java] }
        rule.runOnIdle { assertTrue(vm.descriptors.value.isEmpty()); assertFalse(vm.flightGateway.running.value) }
        rule.onNodeWithTag("management-back").performClick()
        rule.onNodeWithText("旧协议联调（高级）").assertDoesNotExist()
        rule.onNodeWithText("连接与设备管理").performScrollTo().assertIsDisplayed()
    }
}
