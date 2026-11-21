package com.vrxfusion.remote

import com.vrxfusion.remote.rotorpy.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.*
import java.util.concurrent.TimeUnit

class BackendContractTest {
    @Test fun liveSingleMeasuresEffectiveMotionP95()=runBlocking {
        val url=System.getenv("DRONE_TEST_PERF_URL"); assumeTrue(url!=null)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val c=RotorPyClient(url!!,"demo-app-token-2026")
        suspend fun waitFor(f: ()->Boolean) { withTimeout(12000) { while (!f()) delay(10) } }
        try {
            c.startPolling(scope); waitFor { c.connected.value }
            c.retryHandshake(scope); waitFor { c.phase.value==LinkPhase.READY && !c.busy.value }
            c.performance.reset(); c.onStick(StickSnapshot(ry=.12f),scope)
            waitFor { c.performance.snapshot().successes>=120 }
            c.onStick(StickSnapshot(),scope)
            val s=c.performance.snapshot()
            assertEquals(0L,s.failures); assertTrue(s.successes>=120)
            val record="""{"environment":"Windows JVM client and real RotorPy on loopback; not phone LAN acceptance","samples":${s.successes},"failures":${s.failures},"p95_upper_ms":${s.p95Ms},"max_ms":${s.maxMs},"state_receive_hz":${c.receiveHz.value},"app_compose_hz":null,"voice_ms":null,"model_ms":null}"""
            java.io.File("build/frontend-perf-baseline.json").writeText(record)
            println("FRONTEND_PERF_BASELINE $record")
        } finally { c.disconnect(); scope.cancel() }
    }
    @Test fun offlineOrAgedServerStateCannotBecomeFresh()=runBlocking {
        for (fields in listOf("\"online\":false", "\"state_age_ms\":750")) MockWebServer().use { server ->
            val state="""{"sequence":1,$fields}"""
            server.enqueue(MockResponse().setBody("""{"ok":true,"data":$state}"""))
            server.enqueue(MockResponse().addHeader("Content-Type","text/event-stream").setBody("data: $state\n\n"))
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val c=RotorPyClient(server.url("/").toString().trimEnd('/'),"demo-app-token-2026")
            try {
                c.startPolling(scope)
                withTimeout(3000) { while (c.state.value==null) delay(10) }
                assertTrue(c.stale.value); assertFalse(c.connected.value); assertFalse(c.holding.value)
            } finally { c.stopPolling(); scope.cancel() }
        }
    }
    @Test fun discreteFeedbackUsesServerStatusInsteadOfHttpSuccess()=runBlocking {
        MockWebServer().use { server ->
            server.dispatcher=object: Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when(request.path) {
                    "/api/state" -> MockResponse().setBody("""{"ok":true,"data":{"sequence":1,"control_epoch":"e"}}""")
                    "/api/events" -> MockResponse().addHeader("Content-Type","text/event-stream")
                        .setBody(":keepalive\n\n".repeat(100)).throttleBody(12,100,TimeUnit.MILLISECONDS)
                    "/api/session" -> MockResponse().setBody("""{"ok":true,"data":{"session_id":"s"}}""")
                    "/api/stop" -> MockResponse().setBody("""{"ok":true,"data":{"command_status":"COMPLETED"}}""")
                    else -> MockResponse().setBody("""{"ok":true,"data":{}}""")
                }
            }
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val c=RotorPyClient(server.url("/").toString().trimEnd('/'),"demo-app-token-2026")
            try {
                c.startPolling(scope); withTimeout(3000) { while (!c.connected.value) delay(5) }
                c.stop(); assertEquals("COMPLETED",c.commandFeedback.value?.status)
                c.protect(); assertEquals("RECEIVED",c.commandFeedback.value?.status)
            } finally { c.stopPolling(); scope.cancel() }
        }
    }
    @Test fun quaternionAxesAreNotSwapped() {
        val s=sin(PI/8); val c=cos(PI/8)
        val roll=RotorState(quaternion_xyzw=listOf(s,0.0,0.0,c))
        assertEquals(PI/4,roll.rollRad(),1e-8); assertEquals(0.0,roll.pitchRad(),1e-8)
        val pitch=RotorState(quaternion_xyzw=listOf(0.0,s,0.0,c))
        assertEquals(PI/4,pitch.pitchRad(),1e-8); assertEquals(0.0,pitch.rollRad(),1e-8)
        assertEquals(PI/4,RotorState(quaternion_xyzw=listOf(0.0,0.0,s,c)).yawRad(),1e-8)
    }
    @Test fun joystickHeadingAxesMatchBackend() {
        assertEquals(listOf(1.0,-0.5,0.25,0.75),StickSnapshot(rx=.5f,ry=1f,lx=-.75f,ly=.25f).axes())
        assertTrue(StickSnapshot().isNeutral())
        assertFalse(StickSnapshot(ry=.1f).isNeutral())
    }
    @Test fun rejectsInvalidConnectionBeforeNetwork() {
        assertNull(connectionIssue("http://192.168.1.100:8765","demo-app-token-2026"))
        listOf("ws://host:8765","http://host/api/state","http://user:password@host","http://host:99999",
            "http://host?token=abc","invalid").forEach { assertNotNull(it,connectionIssue(it,"demo-app-token-2026")) }
        assertNotNull(connectionIssue("http://localhost:8765","Bearer demo-app-token-2026"))
    }
    @Test fun authorizationUsesHeaderAndPreservesErrorCode() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"ok":false,"error":"来源错误","error_code":"UNAUTHORIZED"}"""))
            try { BackendWire().get(server.url("/").toString().trimEnd('/'),"abc","/api/state"); fail("应拒绝") }
            catch (e: HttpRejection) { assertEquals(401,e.code); assertEquals("UNAUTHORIZED",e.errorCode) }
            val r=server.takeRequest(); assertEquals("Bearer abc",r.getHeader("Authorization")); assertEquals("/api/state",r.path)
        }
    }
    @Test fun neverFollowsCredentialRedirects() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location","http://127.0.0.1:1/steal"))
            try { BackendWire().get(server.url("/").toString().trimEnd('/'),"abc","/api/state"); fail() }
            catch (e: HttpRejection) { assertEquals(302,e.code) }
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun parsesSseFramesAndIgnoresComments() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().addHeader("Content-Type","text/event-stream")
                .setBody(": ping\nevent: state\ndata: {\ndata: \"sequence\":1}\n\nevent: state\ndata: {\"sequence\":2}\n\n"))
            val w=BackendWire(); val frames=mutableListOf<String>()
            w.readStream(w.stream(server.url("/").toString().trimEnd('/'),"abc","/api/events")) { frames+=it }
            assertEquals(listOf("{\n\"sequence\":1}","{\"sequence\":2}"),frames)
        }
    }
    @Test fun connectingToSingleIsReadOnly()=runBlocking {
        MockWebServer().use { server ->
            val s="""{"sequence":1,"boot_id":"boot","control_epoch":"epoch","mode":"HOLD"}"""
            server.enqueue(MockResponse().setBody("""{"ok":true,"data":$s}"""))
            server.enqueue(MockResponse().addHeader("Content-Type","text/event-stream").setBody("data: $s\n\n"))
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val c=RotorPyClient(server.url("/").toString().trimEnd('/'),"demo-app-token-2026")
            try {
                c.startPolling(scope)
                withTimeout(3000) { while (server.requestCount<2) delay(10) }
                assertEquals("GET",server.takeRequest().method); assertEquals("GET",server.takeRequest().method)
                assertNotEquals(LinkPhase.READY,c.phase.value)
                assertFalse(c.holding.value)
            } finally { c.stopPolling(); scope.cancel() }
        }
    }
    @Test fun delayedPreNeutralStreamCannotRevokeConfirmedControl()=runBlocking {
        MockWebServer().use { server ->
            fun state(seq: Int,source: String?)="""{"boot_id":"b","sequence":$seq,"control_epoch":"e","mode":"HOLD","selected_source":${source?.let { "\"$it\"" } ?: "null"}}"""
            server.dispatcher=object: Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when(request.path) {
                    "/api/state" -> MockResponse().setBody("""{"ok":true,"data":${state(1,null)}}""")
                    "/api/events" -> MockResponse().addHeader("Content-Type","text/event-stream")
                        .setBody(listOf(state(10,null).replace("\"control_epoch\":\"e\"","\"control_epoch\":\"old-epoch\""),state(31,"app"),state(32,"ring"))
                            .joinToString("") { ("data: $it\n\n").padEnd(256,'\n') })
                        .setBodyDelay(200,TimeUnit.MILLISECONDS).throttleBody(256,150,TimeUnit.MILLISECONDS)
                    "/api/session" -> MockResponse().setBody("""{"ok":true,"data":{"session_id":"session","control_epoch":"e"}}""")
                    "/api/neutral" -> MockResponse().setBody("""{"ok":true,"data":{"control_epoch":"e","sequence":30}}""")
                    else -> MockResponse().setBody("""{"ok":true,"data":{"control_epoch":"e"}}""")
                }
            }
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val c=RotorPyClient(server.url("/").toString().trimEnd('/'),"demo-app-token-2026")
            suspend fun waitFor(condition: ()->Boolean)=withTimeout(3000) { while (!condition()) delay(5) }
            try {
                c.startPolling(scope); waitFor { c.connected.value }
                c.retryHandshake(scope); waitFor { c.phase.value==LinkPhase.READY && !c.busy.value }
                waitFor { c.state.value?.sequence==10L }
                assertEquals("旧的待归中帧不能撤销已确认控制权",LinkPhase.READY,c.phase.value)
                waitFor { c.state.value?.sequence==31L }
                assertEquals(LinkPhase.READY,c.phase.value)
                waitFor { c.state.value?.sequence==32L }
                // 遥测与控制权由两条状态流发布，等第 32 帧对应的撤销状态可见后再断言。
                waitFor { c.phase.value != LinkPhase.READY }
                assertNotEquals("新的来源变化必须撤销控制权",LinkPhase.READY,c.phase.value)
            } finally { c.disconnect(); scope.cancel() }
        }
    }
    // 需要真实RotorPy服务时显式设置环境变量；普通离线单测不会启动/控制用户已有服务。
    @Test fun liveSingleHandshakeMotionProtectionAndResume()=runBlocking {
        val url=System.getenv("DRONE_TEST_SINGLE_URL"); assumeTrue(url != null)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val c=RotorPyClient(url!!,"demo-app-token-2026")
        suspend fun waitFor(condition: ()->Boolean) {
            try { withTimeout(6000) { while (!condition()) delay(20) } }
            catch (e: TimeoutCancellationException) { throw AssertionError(
                "等待超时：phase=${c.phase.value}, busy=${c.busy.value}, mode=${c.state.value?.mode}, "+
                    "selected=${c.state.value?.selected_source}, reason=${c.state.value?.protection_reason}, error=${c.lastError.value}",e) }
        }
        try {
            c.startPolling(scope); waitFor { c.connected.value }
            assertNotEquals(LinkPhase.READY,c.phase.value)
            c.retryHandshake(scope); waitFor { c.phase.value == LinkPhase.READY && !c.busy.value }
            println("方向回归初始偏航deg=${c.state.value!!.yawRad()*180/PI}")
            // 在独立服务初始化机头朝后时也必须与固定地图同向；旧heading发送会在这里失败。
            data class Direction(val name: String,val stick: StickSnapshot,val axis: Int,val sign: Double)
            for ((name,stick,axis,sign) in listOf(
                Direction("前",StickSnapshot(ry=.3f),0,1.0),Direction("左",StickSnapshot(rx=-.3f),1,1.0),
                Direction("后",StickSnapshot(ry=-.3f),0,-1.0),Direction("右",StickSnapshot(rx=.3f),1,-1.0))) {
                val start=c.state.value!!.position_m.toList()
                c.onStick(stick,scope); delay(650)
                c.onStick(StickSnapshot(),scope); delay(450)
                assertNull(c.lastError.value)
                val delta=c.state.value!!.position_m.mapIndexed { i,v -> v-start[i] }
                assertTrue("$name 方向必须与地图坐标一致，实际=$delta",delta[axis]*sign>.015)
                println("真实方向$name 位移m=$delta; 接收Hz=${c.receiveHz.value}")
            }
            c.protect(); waitFor { c.state.value?.mode == "PROTECTED" }
            c.resume(scope); waitFor { c.state.value?.mode == "HOLD" }
            assertNotEquals(LinkPhase.READY,c.phase.value)
            assertFalse(c.holding.value)
            assertNull(c.lastError.value)
            c.retryHandshake(scope); waitFor { c.phase.value == LinkPhase.READY && !c.busy.value }
            c.onStick(StickSnapshot(ry=.2f),scope); delay(150)
            c.disconnect()
            assertFalse(c.running.value); assertFalse(c.holding.value)
            c.startPolling(scope); waitFor { c.connected.value }
            assertNotEquals("重连不能自动接管",LinkPhase.READY,c.phase.value)
            assertFalse(c.holding.value)
        } finally { c.disconnect(); scope.cancel() }
    }
    @Test fun liveClusterMonitorReceivesThreeIndependentMembers()=runBlocking {
        val url=System.getenv("DRONE_TEST_CLUSTER_URL"); assumeTrue(url != null)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default); val c=ClusterMonitorClient()
        try {
            c.start(scope,url!!,"demo-app-token-2026")
            withTimeout(6000) { while (!c.connected.value || c.state.value?.members?.size != 3) delay(20) }
            assertEquals(3,c.state.value!!.members.map { it.device_id }.toSet().size)
            assertNull(c.state.value!!.selection)
            withTimeout(4000) { while (c.receiveHz.value<20) delay(50) }
            println("真实集群成员=${c.state.value!!.members.map { it.device_id }}; 接收Hz=${c.receiveHz.value}; selection=${c.state.value!!.selection}")
        } finally { c.stop(); scope.cancel() }
    }
}
