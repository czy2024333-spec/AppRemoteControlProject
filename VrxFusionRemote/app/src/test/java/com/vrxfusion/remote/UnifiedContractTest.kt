package com.vrxfusion.remote

import com.vrxfusion.remote.unified.*
import com.vrxfusion.remote.rotorpy.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 使用后端zip真实契约样例；统一读取与兼容写入分开核验。 */
class UnifiedContractTest {
    private val json=Json { ignoreUnknownKeys=true; encodeDefaults=true }
    private fun fixture(name: String)=javaClass.classLoader!!.getResource("unified/$name.json")!!.readText()
    private fun data(name: String)=json.parseToJsonElement(fixture(name)).jsonObject["data"]!!
    private fun snapshot()=json.decodeFromJsonElement<UnifiedSnapshot>(data("state"))
    @Test fun backendFixturesKeepNullsSimulationAndUnknownLease() {
        val state=snapshot(); assertEquals(3,state.members.size)
        state.members.forEach { m ->
            assertTrue(m.validTelemetry()); assertNull(m.battery.value); assertNull(m.gnss.value)
            assertEquals("NOT_CONNECTED",m.battery.validity); assertNull(m.control.has_control)
        }
        val info=json.decodeFromJsonElement<UnifiedCapabilities>(data("capabilities"))
        info.validate(); assertFalse(info.capabilities["unified_intent_write"]!!)
        assertFalse(info.authentication.formal_user_authentication); assertNull(info.authentication.role)
    }
    @Test fun invalidMeasurementCannotBeFilledWithZeroOrGrantControl() {
        val original=snapshot().members.first()
        val missing=original.copy(position=VectorMeasurement())
        assertFalse(missing.validTelemetry()); assertTrue(missing.displayOver(RotorState()).position_m.isEmpty())
        assertFalse(original.copy(coordinate_frame="UNKNOWN").validTelemetry())
        assertFalse(original.copy(position=original.position.copy(unit="cm")).validTelemetry())
        assertFalse(original.copy(attitude=original.attitude.copy(value=QuaternionValue(0.0,0.0,0.0,0.0))).validTelemetry())
    }
    @Test fun capabilityAndSourceAvailabilityAreRequiredForMotion() {
        val directory=json.decodeFromJsonElement<UnifiedDirectory>(data("devices"))
        val device=directory.devices.first(); assertTrue(device.permitsMotion())
        assertFalse(device.copy(capabilities=device.capabilities.copy(motion=false)).permitsMotion())
        assertFalse(device.copy(control_limits=device.control_limits.copy(sources_enabled=mapOf("app" to false))).permitsMotion())
        assertFalse(device.copy(control_limits=device.control_limits.copy(input_ttl_ms=1000)).permitsMotion())
    }
    @Test fun serviceCompletionAndPartialResultsAreNotPhysicalCompletion() {
        val applied=CommandFeedback("id","停止","SENDING").withReceipt(UnifiedReceipt(phase="COMPLETED",completion_scope="SERVICE_APPLIED"))
        assertEquals("SERVICE_APPLIED",applied.completionScope); assertTrue(applied.text().contains("运动结果以状态为准"))
        val partial=applied.withReceipt(UnifiedReceipt(phase="PARTIAL",succeeded=2,failed=1))
        assertEquals("PARTIAL",partial.status); assertTrue(partial.text().contains("失败1"))
    }
    private fun readDispatcher(source: String="APP",duplicates: Boolean=false,frames: Int=35)=object: Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            return when(request.path) {
                "/api/v1/capabilities" -> MockResponse().setBody(fixture("capabilities").replace("\"APP\"","\"$source\""))
                "/api/v1/devices" -> MockResponse().setBody(fixture("devices"))
                "/api/v1/state" -> MockResponse().setBody(fixture("state"))
                "/api/v1/events" -> {
                    val s=snapshot()
                    val parts=(1..frames).map { step ->
                        val members=s.members.mapIndexed { i,m -> m.copy(sequence=if (duplicates && i==0) m.sequence else 10000L+step) }
                        ("data: "+json.encodeToString(s.copy(members=members))+"\n\n").padEnd(16000,'\n')
                    }
                    MockResponse().addHeader("Content-Type","text/event-stream").setBody(parts.joinToString(""))
                        .throttleBody(16000,50,TimeUnit.MILLISECONDS)
                }
                else -> MockResponse().setResponseCode(404).setBody("""{"ok":false,"error":"not supported"}""")
            }
        }
    }
    @Test fun connectionAndCatalogNeverSendControlOrFallbackToOldProtocol()=runBlocking {
        MockWebServer().use { server ->
            server.dispatcher=readDispatcher()
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default); val repository=UnifiedRepository()
            try {
                assertTrue(repository.start(scope,server.url("/").toString(),"demo-app-token-2026"))
                assertEquals(3,repository.directory.value!!.devices.size)
                assertTrue(repository.connected.value)
                delay(80)
                val requests=(1..server.requestCount).map { server.takeRequest() }
                assertTrue(requests.all { it.method=="GET" && it.path!!.startsWith("/api/v1/") })
                assertNull(repository.snapshot.value!!.selection)
            } finally { repository.stop(); scope.cancel() }
        }
    }
    @Test fun aFrozenMemberDoesNotBecomeFreshBecauseOtherMembersMove()=runBlocking {
        MockWebServer().use { server ->
            server.dispatcher=readDispatcher(duplicates=true)
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default); val repository=UnifiedRepository()
            try {
                assertTrue(repository.start(scope,server.url("/").toString(),"demo-app-token-2026"))
                delay(700)
                assertTrue(repository.connected.value)
                assertFalse(repository.fresh(repository.snapshot.value!!.members.first()))
                assertTrue(repository.fresh(repository.snapshot.value!!.members.last()))
            } finally { repository.stop(); scope.cancel() }
        }
    }
    @Test fun freshSseCannotEraseCatalogFailureAndRecoveryDoesNotSendControl()=runBlocking {
        MockWebServer().use { server ->
            val reads=AtomicInteger(); val base=readDispatcher(frames=180)
            server.dispatcher=object: Dispatcher() {
                override fun dispatch(r: RecordedRequest): MockResponse =
                    if (r.path=="/api/v1/devices" && reads.incrementAndGet()==2)
                        MockResponse().setResponseCode(503).setBody("""{"ok":false,"error":"temporary catalog failure"}""")
                    else base.dispatch(r)
            }
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default); val repository=UnifiedRepository()
            suspend fun waitFor(f: ()->Boolean)=withTimeout(8000) { while (!f()) delay(10) }
            try {
                assertTrue(repository.start(scope,server.url("/").toString(),"demo-app-token-2026"))
                waitFor { repository.directory.value==null && repository.error.value!=null }
                delay(180) // 期间仍有新SSE，不能把能力校验失败错误清空。
                assertTrue(repository.connected.value); assertTrue(repository.error.value!!.contains("设备能力刷新失败"))
                waitFor { repository.directory.value!=null }
                assertNull(repository.error.value)
                assertTrue((1..server.requestCount).map { server.takeRequest() }.all { it.method=="GET" })
            } finally { repository.stop(); scope.cancel() }
        }
    }
    @Test fun oldDeploymentIsRejectedInsteadOfSilentlyUsingDemo()=runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"ok":false,"error":"old endpoint"}"""))
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default); val repository=UnifiedRepository()
            try {
                assertFalse(repository.start(scope,server.url("/").toString(),"demo-app-token-2026"))
                assertTrue(repository.error.value!!.contains("--mode unified")); assertEquals(1,server.requestCount)
            } finally { repository.stop(); scope.cancel() }
        }
    }
    @Test fun sourceTokenCannotPromoteItselfToAppControl()=runBlocking {
        MockWebServer().use { server ->
            server.dispatcher=readDispatcher("RING")
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default); val c=RotorPyClient()
            val gateway=FlightGateway(c,scope)
            try {
                gateway.connect(server.url("/").toString(),"demo-ring-token-2026",false)
                assertFalse(gateway.canWrite()); gateway.retryHandshake(scope); gateway.protect()
                val requests=(1..server.requestCount).map { server.takeRequest() }
                assertTrue(requests.all { it.method=="GET" }); assertNotEquals(LinkPhase.READY,gateway.phase.value)
            } finally { gateway.close(); scope.cancel() }
        }
    }
    @Test fun liveV08ReadOnlyDiscoveryPrimaryControlAndReceiptQuery()=runBlocking {
        val url=System.getenv("DRONE_TEST_UNIFIED_URL"); assumeTrue(url!=null)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default); val core=RotorPyClient()
        core.audit={type,code -> println("CONTROL_TRACE|$type|$code")}
        val gateway=FlightGateway(core,scope)
        suspend fun waitFor(f: ()->Boolean) {
            try { withTimeout(7000) { while (!f()) delay(10) } }
            catch(e: TimeoutCancellationException) { throw AssertionError("统一入口等待超时 ${gateway.phase.value} ${gateway.lastError.value}",e) }
        }
        try {
            gateway.connect(url!!,"demo-app-token-2026",true); waitFor { gateway.connected.value }
            assertEquals(3,gateway.repository.directory.value!!.devices.size)
            assertNull(gateway.repository.snapshot.value!!.selection)
            assertNotEquals(LinkPhase.READY,gateway.phase.value)
            gateway.retryHandshake(scope); waitFor { gateway.phase.value==LinkPhase.READY && !gateway.busy.value }
            val start=gateway.state.value!!.position_m[1]
            // 跨过3秒设备目录刷新，并使用100ms TTL，验证持续操纵不会自行退回观察。
            gateway.onStick(StickSnapshot(rx=-.3f),scope); delay(3300)
            gateway.onStick(StickSnapshot(),scope); delay(400)
            assertTrue("左移未生效：phase=${gateway.phase.value}, connected=${gateway.connected.value}, canWrite=${gateway.canWrite()}, "+
                "error=${gateway.lastError.value}, rawError=${core.lastError.value}, stats=${core.performance.snapshot()}, mode=${core.state.value?.mode}",
                gateway.state.value!!.position_m[1]>start+.01)
            assertEquals("正常松手应停止悬停并保留控制：${gateway.lastError.value}，${gateway.state.value?.protection_reason}",LinkPhase.READY,gateway.phase.value)
            assertNotEquals("正常松手不应落入输入超时保护","PROTECTED",gateway.state.value?.mode)
            gateway.protect(); waitFor { gateway.state.value?.mode=="PROTECTED" }
            assertEquals("SERVICE_APPLIED",gateway.commandFeedback.value?.completionScope)
            val id=gateway.commandFeedback.value!!.id
            gateway.queryLastReceipt(); assertEquals(id,gateway.commandFeedback.value!!.id)
            assertEquals("SERVICE_APPLIED",gateway.commandFeedback.value!!.completionScope)
            gateway.resume(scope); waitFor { gateway.state.value?.mode=="HOLD" }
            assertNotEquals(LinkPhase.READY,gateway.phase.value)
        } finally { gateway.stopObserving(); gateway.releaseAll(); gateway.close(); scope.cancel() }
    }
}
