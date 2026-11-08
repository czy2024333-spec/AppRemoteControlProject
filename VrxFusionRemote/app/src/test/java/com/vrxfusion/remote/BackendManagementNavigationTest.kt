package com.vrxfusion.remote

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.vrxfusion.remote.rotorpy.*
import com.vrxfusion.remote.ui.*
import com.vrxfusion.remote.ui.theme.VrxTheme
import com.vrxfusion.remote.unified.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** 真实App路由及HTTP请求联合检查：页面切换不能暗中创建会话或回退旧WebSocket。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w360dp-h780dp-port-xhdpi")
class BackendManagementNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val requests=java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()
    private fun fixture(who: String, name: String) = javaClass.classLoader!!.getResource("independent/$who-$name.json")!!.readText()
    private fun data(who: String, name: String) = json.parseToJsonElement(fixture(who, name)).jsonObject["data"]!!
    private fun dispatcher() = object : Dispatcher() {
        override fun dispatch(r: RecordedRequest): MockResponse {
            requests.add(r)
            val token = r.getHeader("Authorization").orEmpty()
            val who = when {
                token.contains("demo-pilot-2-token-2026") -> "pilot-2"
                token.contains("demo-observer-token-2026") -> "observer"
                else -> return MockResponse().setResponseCode(401).setBody("""{"ok":false,"error":"INVALID_TOKEN"}""")
            }
            val s = json.decodeFromJsonElement<UnifiedSnapshot>(data(who, "state"))
            val id = s.self_device_id
            fun raw(step: Int) = json.decodeFromJsonElement<RotorState>(data("pilot-2", "raw")).copy(
                device_id = id ?: "sim-drone-2", boot_id = s.members.find { it.device_id == id }?.boot_id ?: "none",
                sequence = step.toLong(), simulation_time_s = step * .04, control_epoch = "e", selected_source = "app",
                mode = "HOLD", online = true, state_age_ms = 0.0)
            if (r.method == "POST") {
                if (id == null || !r.path.orEmpty().startsWith("/api/v1/devices/$id/"))
                    return MockResponse().setResponseCode(403).setBody("""{"ok":false,"error":"DEVICE_FORBIDDEN"}""")
                val body = json.parseToJsonElement(r.body.readUtf8()).jsonObject
                if (body["device_id"]?.jsonPrimitive?.content != id)
                    return MockResponse().setResponseCode(400).setBody("""{"ok":false,"error":"WRONG_TARGET"}""")
                return MockResponse().setBody("""{"ok":true,"data":{"session_id":"session-$id","control_epoch":"e","command_status":"COMPLETED"}}""")
            }
            return when (r.path) {
                "/api/v1/capabilities" -> MockResponse().setBody(fixture(who, "capabilities"))
                "/api/v1/devices" -> MockResponse().setBody(fixture(who, "devices"))
                "/api/v1/state" -> MockResponse().setBody(fixture(who, "state"))
                "/api/v1/events" -> MockResponse().addHeader("Content-Type", "text/event-stream").setBody(
                    (1..600).joinToString("") { n -> ("data: " + json.encodeToString(s.copy(
                        members = s.members.map { it.copy(sequence = 10000L + n) })) + "\n\n").padEnd(18000, '\n') })
                    .throttleBody(18000, 40, TimeUnit.MILLISECONDS)
                "/api/devices/$id/state" -> MockResponse().setBody("""{"ok":true,"data":${json.encodeToString(raw(0))}}""")
                "/api/devices/$id/events" -> MockResponse().addHeader("Content-Type", "text/event-stream").setBody(
                    (1..600).joinToString("") { n -> ("data: " + json.encodeToString(raw(n)) + "\n\n").padEnd(5000, '\n') })
                    .throttleBody(5000, 40, TimeUnit.MILLISECONDS)
                else -> MockResponse().setResponseCode(404).setBody("""{"ok":false,"error":"NO_LEGACY_FALLBACK"}""")
            }
        }
    }
    private fun openManager(server: MockWebServer, token: String, motion: Boolean=false): MainViewModel {
        // 功能协议测试关闭动效，逐帧动效和触摸隔离由MotionUiTest单独验证。
        if (motion) rule.mainClock.autoAdvance=false
        rule.setContent { VrxTheme { App(motionEnabled=motion) } }
        if (motion) rule.mainClock.advanceTimeBy(48)
        val vm = rule.runOnIdle { ViewModelProvider(rule.activity)[MainViewModel::class.java] }
        rule.runOnIdle { vm.setRotorBaseUrl(server.url("/").toString()); vm.setRotorToken(token) }
        rule.onNodeWithText("连接与设备管理").performScrollTo().performClick()
        if (motion) rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithTag("backend-address").performScrollTo().assertTextContains(server.url("/").toString())
        rule.onNodeWithText("连接并查看状态").performScrollTo().performClick()
        return vm
    }
    private fun drain(server: MockWebServer) = generateSequence { server.takeRequest(50, TimeUnit.MILLISECONDS) }.toList()
    private fun waitFor(vm: MainViewModel, server: MockWebServer, condition: () -> Boolean) {
        // Robolectric不会按墙钟自动处理Android主线程队列；每轮通过Compose同步点推进待处理任务。
        try { rule.waitUntil(8000) {
            if (!rule.mainClock.autoAdvance) rule.mainClock.advanceTimeByFrame()
            rule.runOnIdle(condition)
        } }
        catch (e: Throwable) { throw AssertionError("管理流程等待失败：running=${vm.flightGateway.running.value}, "+
            "connected=${vm.flightGateway.connected.value}, phase=${vm.flightGateway.phase.value}, "+
            "error=${vm.flightGateway.lastError.value}, repoError=${vm.flightGateway.repository.error.value}, "+
            "requests=${server.requestCount}", e) }
    }
    @Test fun sharedConnectionSurvivesBackAndCockpitWaitsForExplicitClaim() {
        MockWebServer().use { server ->
            server.dispatcher = dispatcher()
            val vm = openManager(server, "demo-pilot-2-token-2026")
            try {
                waitFor(vm, server) { vm.flightGateway.connected.value && vm.flightGateway.canWrite() }
                assertTrue(vm.descriptors.value.isEmpty())
                val initial = drain(server)
                assertTrue(initial.isNotEmpty()); assertTrue(initial.all { it.method == "GET" })
                assertEquals(1, initial.count { it.path == "/api/v1/capabilities" })
                rule.onNodeWithTag("management-back").performClick()
                // 操纵页逐帧检测UI存活，不能让测试时钟为了“空闲”无限自动推进；明确推进所需帧。
                rule.mainClock.autoAdvance = false
                repeat(3) { rule.mainClock.advanceTimeByFrame() }
                rule.onNodeWithText("01  独立操纵").performScrollTo().performClick()
                repeat(3) { rule.mainClock.advanceTimeByFrame() }
                rule.onNodeWithText("申请控制").assertIsEnabled()
                assertTrue(vm.flightGateway.connected.value)
                assertNotEquals(LinkPhase.READY, vm.flightGateway.phase.value)
                val navigation = drain(server)
                assertTrue(navigation.all { it.method == "GET" })
                assertTrue(navigation.none { it.path == "/api/v1/capabilities" })
                rule.onNodeWithText("申请控制").performClick()
                waitFor(vm, server) { vm.flightGateway.phase.value == LinkPhase.READY && !vm.flightGateway.busy.value }
                repeat(3) { rule.mainClock.advanceTimeByFrame() }
                val posts = drain(server).filter { it.method == "POST" }
                assertTrue(posts.any { it.path == "/api/v1/devices/sim-drone-2/session" })
                assertTrue(posts.all { it.path!!.startsWith("/api/v1/devices/sim-drone-2/") })
                rule.onNodeWithText("已接管").assertExists()
                // 已接管时编辑连接必须先退出原会话；设置中不再出现第二套凭据输入框。
                rule.onNodeWithContentDescription("飞行设置").performClick()
                repeat(3) { rule.mainClock.advanceTimeByFrame() }
                rule.onNodeWithText("连接状态").performClick()
                repeat(3) { rule.mainClock.advanceTimeByFrame() }
                rule.onNodeWithTag("backend-address").assertDoesNotExist()
                rule.onNodeWithTag("manage-connection").performScrollTo().performClick()
                repeat(3) { rule.mainClock.advanceTimeByFrame() }
                rule.mainClock.autoAdvance=true
                rule.onNodeWithText("返回操纵").assertIsDisplayed()
                waitFor(vm,server) { requests.any { it.path=="/api/v1/devices/sim-drone-2/disconnect" } }
                assertFalse(vm.flightGateway.running.value)
                assertNotEquals(LinkPhase.READY,vm.flightGateway.phase.value)
                rule.onNodeWithText("连接并查看状态").performScrollTo().performClick()
                waitFor(vm,server) { vm.flightGateway.connected.value && vm.flightGateway.canWrite() }
                rule.mainClock.autoAdvance=false
                rule.onNodeWithTag("management-back").performClick()
                repeat(3) { rule.mainClock.advanceTimeByFrame() }
                rule.onNodeWithText("申请控制").assertIsEnabled()
                assertEquals(1,requests.count { it.path=="/api/v1/devices/sim-drone-2/session" })
            } finally { vm.pauseAll() }
        }
    }
    @Test fun invalidTokenShowsFailureWithoutOpeningLegacyOrSeedingLocalDevices() {
        MockWebServer().use { server ->
            server.dispatcher = dispatcher()
            val vm = openManager(server, "invalid-token")
            try {
                waitFor(vm, server) { vm.flightGateway.repository.error.value != null && !vm.flightGateway.running.value }
                rule.onNodeWithText("连接失败").assertIsDisplayed()
                rule.onNodeWithTag("management-no-directory").performScrollTo().assertIsDisplayed()
                assertNull(vm.flightGateway.repository.info.value)
                assertTrue(vm.descriptors.value.isEmpty())
                val requests = drain(server)
                assertEquals(1, requests.size)
                assertEquals("/api/v1/capabilities", requests.single().path)
                assertEquals("GET", requests.single().method)
            } finally { vm.pauseAll() }
        }
    }
    @Test fun backgroundAndResumeDoNotReconnectOrRestoreAuthority() {
        MockWebServer().use { server ->
            server.dispatcher = dispatcher()
            val vm = openManager(server, "demo-observer-token-2026")
            try {
                waitFor(vm, server) { vm.flightGateway.repository.connected.value }
                rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                assertFalse(vm.flightGateway.running.value)
                drain(server)
                rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                rule.onNodeWithText("已断开 · 下方仅为历史信息").assertIsDisplayed()
                assertFalse(vm.flightGateway.running.value)
                assertNotEquals(LinkPhase.READY, vm.flightGateway.phase.value)
                assertTrue(drain(server).isEmpty())
                assertTrue(vm.descriptors.value.isEmpty())
            } finally { vm.pauseAll() }
        }
    }

    private fun enterFromWorkPage(page: String,server: MockWebServer): MainViewModel {
        // 操纵页有永久的逐帧存活检测，测试主动推进帧，不等待它自动进入空闲。
        rule.mainClock.autoAdvance=false
        rule.setContent { VrxTheme { App(initialPage=page,motionEnabled=false) } }
        repeat(3) { rule.mainClock.advanceTimeByFrame() }
        val vm=rule.runOnIdle { ViewModelProvider(rule.activity)[MainViewModel::class.java] }
        rule.runOnIdle { vm.setRotorBaseUrl(server.url("/").toString()); vm.setRotorToken("demo-observer-token-2026") }
        rule.onNodeWithTag("backend-address").assertDoesNotExist()
        if (page=="single") rule.onNodeWithText("连接设置").performClick()
        else rule.onNodeWithTag("manage-connection").performScrollTo().performClick()
        repeat(3) { rule.mainClock.advanceTimeByFrame() }
        rule.mainClock.autoAdvance=true
        rule.onNodeWithTag("backend-address").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("连接并查看状态").performScrollTo().performClick()
        waitFor(vm,server) { vm.flightGateway.repository.connected.value }
        return vm
    }
    @Test fun disconnectedCockpitUsesOnlyManagerAndReturnsAsObserver() {
        MockWebServer().use { server ->
            server.dispatcher=dispatcher()
            val vm=enterFromWorkPage("single",server)
            try {
                rule.onNodeWithText("返回操纵").assertIsDisplayed()
                rule.mainClock.autoAdvance=false
                rule.onNodeWithTag("management-back").performClick()
                repeat(3) { rule.mainClock.advanceTimeByFrame() }
                rule.onNodeWithText("只读观察").assertExists()
                rule.onNodeWithTag("backend-address").assertDoesNotExist()
                assertTrue(requests.all { it.method=="GET" })
            } finally {vm.pauseAll()}
        }
    }
    @Test fun fleetSystemBackReturnsToFleetWithoutAutoClaim() {
        MockWebServer().use { server ->
            server.dispatcher=dispatcher()
            val vm=enterFromWorkPage("cluster",server)
            try {
                rule.onNodeWithText("返回总览").assertIsDisplayed()
                rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
                rule.onNodeWithTag("fleet-title").assertIsDisplayed()
                rule.onNodeWithTag("backend-address").assertDoesNotExist()
                rule.onNodeWithTag("fleet-overview-sim-drone-3").performScrollTo().assertIsDisplayed()
                assertTrue(requests.all { it.method=="GET" })
            } finally {vm.pauseAll()}
        }
    }
    @Test fun animatedNavigationReleasesRealSessionBeforeTheTransitionEnds() {
        MockWebServer().use {server ->
            server.dispatcher=dispatcher()
            val vm=openManager(server,"demo-pilot-2-token-2026",motion=true)
            try {
                waitFor(vm,server) {vm.flightGateway.connected.value && vm.flightGateway.canWrite()}
                rule.onNodeWithTag("management-back").performClick();rule.mainClock.advanceTimeBy(400)
                rule.onNodeWithText("01  独立操纵").performScrollTo().performClick();rule.mainClock.advanceTimeBy(400)
                rule.onNodeWithText("申请控制").performClick()
                waitFor(vm,server) {vm.flightGateway.phase.value==LinkPhase.READY && !vm.flightGateway.busy.value}
                rule.onNodeWithContentDescription("飞行设置").performClick();rule.mainClock.advanceTimeBy(600)
                rule.onNodeWithText("连接状态").performClick();rule.mainClock.advanceTimeBy(250)
                rule.onNodeWithTag("manage-connection").performScrollTo().performClick()
                // 点击切页即撤销本地控制，不等待320ms的展示动画结束。
                rule.runOnIdle {assertFalse(vm.flightGateway.running.value);assertNotEquals(LinkPhase.READY,vm.flightGateway.phase.value)}
                rule.mainClock.advanceTimeBy(64)
                rule.onNodeWithTag("motion-input-shield").assertExists()
                waitFor(vm,server) {requests.any {it.path=="/api/v1/devices/sim-drone-2/disconnect"}}
                assertTrue(requests.any {it.path=="/api/v1/devices/sim-drone-2/stop"})
                assertEquals(1,requests.count {it.path=="/api/v1/devices/sim-drone-2/session"})
                rule.mainClock.advanceTimeBy(400)
                rule.onNodeWithText("返回操纵").assertIsDisplayed()
            } finally {vm.pauseAll()}
        }
    }
}
