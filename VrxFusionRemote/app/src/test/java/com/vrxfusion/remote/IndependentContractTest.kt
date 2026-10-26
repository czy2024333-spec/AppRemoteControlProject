package com.vrxfusion.remote

import com.vrxfusion.remote.rotorpy.*
import com.vrxfusion.remote.unified.*
import com.vrxfusion.remote.ui.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.io.File

/** 真实v0.9样例、真实HTTP路由以及三路同时控制；权限不由前端预设编号授予。 */
class IndependentContractTest {
    private val json=Json { ignoreUnknownKeys=true; encodeDefaults=true }
    private fun fixture(who: String,name: String)=javaClass.classLoader!!.getResource("independent/$who-$name.json")!!.readText()
    private fun data(who: String,name: String)=json.parseToJsonElement(fixture(who,name)).jsonObject["data"]!!
    private suspend fun waitFor(detail: ()->String={""},f: ()->Boolean) {
        try { withTimeout(7000) { while (!f()) delay(10) } }
        catch(e: TimeoutCancellationException) { throw AssertionError("独立控制等待超时：${detail()}",e) }
    }
    @Test fun fourIdentitiesKeepExactBindingsAndUnknownLocalAuthority() {
        for (who in listOf("pilot-1","pilot-2","pilot-3","observer")) {
            val c=json.decodeFromJsonElement<UnifiedCapabilities>(data(who,"capabilities")); c.validate()
            val s=json.decodeFromJsonElement<UnifiedSnapshot>(data(who,"state"))
            val d=json.decodeFromJsonElement<UnifiedDirectory>(data(who,"devices"))
            assertEquals(3,s.members.size); assertEquals(c.controlDeviceId(),s.self_device_id)
            assertEquals(if (who=="observer") 0 else 1,d.devices.count { it.write_permitted==true })
            s.members.forEach { assertNull(it.control.has_control); assertNull(it.battery.value); assertNull(it.gnss.value) }
            if (who!="observer") assertEquals("sim-drone-"+who.last(),c.controlDeviceId())
        }
    }
    @Test fun conflictingIdentityAndExtraWritableDevicesAreRejected() {
        val c=json.decodeFromJsonElement<UnifiedCapabilities>(data("pilot-2","capabilities"))
        assertTrue(runCatching { c.copy(self_device_id="sim-drone-1").validate() }.isFailure)
        assertTrue(runCatching { c.copy(writable_device_ids=listOf("sim-drone-1","sim-drone-2")).validate() }.isFailure)
        val observer=json.decodeFromJsonElement<UnifiedCapabilities>(data("observer","capabilities"))
        assertTrue(runCatching { observer.copy(self_device_id="sim-drone-1").validate() }.isFailure)
    }
    @Test fun mapShowsAllThreeAndKeepsSelfAndWorldDirections() {
        val s=json.decodeFromJsonElement<UnifiedSnapshot>(data("pilot-2","state"))
        val markers=fleetMarkers(s.members,"sim-drone-2") { true }
        assertEquals(3,markers.size); assertEquals("sim-drone-2",markers.single { it.self }.member.device_id)
        val viewport=TrajectoryViewport.fit(markers.map { it.position.x to it.position.y })
        assertTrue(viewport.project(0.0,2.0,140f,100f,5f).first<viewport.project(0.0,0.0,140f,100f,5f).first)
        assertTrue(aircraftCoordinateText(s.members[1],"sim-drone-2",true).contains("本机"))
        assertEquals(markers.map { it.color },fleetMarkers(s.members.reversed(),"sim-drone-2") {true}.reversed().map {it.color})
    }
    @Test fun invalidOtherAircraftNeverBecomesZeroOrARealtimeMarker() {
        val s=json.decodeFromJsonElement<UnifiedSnapshot>(data("pilot-2","state"))
        val broken=s.members[0].copy(position=VectorMeasurement())
        assertEquals(2,fleetMarkers(listOf(broken)+s.members.drop(1),"sim-drone-2") {true}.size)
        assertTrue(aircraftCoordinateText(broken,"sim-drone-2",false).contains("失效"))
        assertFalse(fleetMarkers(s.members,"sim-drone-2") {it.device_id!="sim-drone-1"}.first().fresh)
    }
    private fun dispatcher()=object: Dispatcher() {
        override fun dispatch(r: RecordedRequest): MockResponse {
            val token=r.getHeader("Authorization").orEmpty()
            val who=if (token.contains("observer")) "observer" else (1..3).firstOrNull { token.contains("pilot-$it-") }?.let {"pilot-$it"} ?: "pilot-2"
            val s=json.decodeFromJsonElement<UnifiedSnapshot>(data(who,"state"))
            val id=s.self_device_id
            fun raw(step: Int)=json.decodeFromJsonElement<RotorState>(data("pilot-2","raw")).copy(device_id=id ?: "sim-drone-2",
                boot_id=s.members.find { it.device_id==id }?.boot_id ?: "none",sequence=step.toLong(),simulation_time_s=step*.02,
                control_epoch="e",selected_source="app",mode="HOLD",online=true,state_age_ms=0.0)
            if (r.method=="POST") {
                if (id==null || !r.path.orEmpty().startsWith("/api/v1/devices/$id/")) return MockResponse().setResponseCode(403).setBody("""{"ok":false,"error":"wrong route"}""")
                val b=json.parseToJsonElement(r.body.readUtf8()).jsonObject
                if (b["device_id"]?.jsonPrimitive?.content!=id || b["cluster_id"]?.jsonPrimitive?.content!=s.cluster_id ||
                    r.path!!.endsWith("/session") && b["user_initiated"]?.jsonPrimitive?.booleanOrNull!=true)
                    return MockResponse().setResponseCode(400).setBody("""{"ok":false,"error":"binding missing"}""")
                return MockResponse().setBody("""{"ok":true,"data":{"session_id":"session-$id","control_epoch":"e","command_status":"COMPLETED"}}""")
            }
            return when(r.path) {
                "/api/v1/capabilities" -> MockResponse().setBody(fixture(who,"capabilities"))
                "/api/v1/devices" -> MockResponse().setBody(fixture(who,"devices"))
                "/api/v1/state" -> MockResponse().setBody(fixture(who,"state"))
                "/api/v1/events" -> MockResponse().addHeader("Content-Type","text/event-stream").setBody((1..120).joinToString("") { n ->
                    ("data: "+json.encodeToString(s.copy(members=s.members.map { it.copy(sequence=10000L+n) }))+"\n\n").padEnd(18000,'\n') }).throttleBody(18000,40,TimeUnit.MILLISECONDS)
                "/api/devices/$id/state" -> MockResponse().setBody("""{"ok":true,"data":${json.encodeToString(raw(0))}}""")
                "/api/devices/$id/events" -> MockResponse().addHeader("Content-Type","text/event-stream").setBody((1..120).joinToString("") {
                    ("data: "+json.encodeToString(raw(it))+"\n\n").padEnd(5000,'\n') }).throttleBody(5000,40,TimeUnit.MILLISECONDS)
                else -> MockResponse().setResponseCode(404).setBody("""{"ok":false,"error":"global route forbidden"}""")
            }
        }
    }
    @Test fun observerNeverCreatesSessionOrWritesEvenWhenControlFunctionsAreCalled()=runBlocking {
        MockWebServer().use { server ->
            server.dispatcher=dispatcher(); val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val c=RotorPyClient(); val g=FlightGateway(c,scope)
            try {
                g.connect(server.url("/").toString(),"demo-observer-token-2026",true)
                waitFor {g.connected.value}; assertFalse(g.canWrite())
                g.retryHandshake(scope); g.onStick(StickSnapshot(ry=.2f),scope); g.stop(); g.protect(); g.resume(scope)
                assertNotEquals(LinkPhase.READY,g.phase.value)
                assertTrue((1..server.requestCount).map {server.takeRequest()}.all {it.method=="GET"})
            } finally { g.close(); scope.cancel() }
        }
    }
    @Test fun explicitPilotTwoUsesOnlyItsMemberRouteAndSwitchReleasesOldBinding()=runBlocking {
        MockWebServer().use { server ->
            server.dispatcher=dispatcher(); val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val c=RotorPyClient(); val g=FlightGateway(c,scope)
            try {
                val url=server.url("/").toString()
                g.connect(url,"demo-pilot-2-token-2026",true); waitFor {g.connected.value}
                assertNotEquals(LinkPhase.READY,g.phase.value)
                val initial=(1..server.requestCount).map {server.takeRequest()}; assertTrue(initial.all {it.method=="GET"})
                g.retryHandshake(scope); waitFor({g.lastError.value.orEmpty()}) {g.phase.value==LinkPhase.READY && !g.busy.value}
                g.onStick(StickSnapshot(ry=.2f),scope); waitFor {c.performance.snapshot().successes>=2}
                g.onStick(StickSnapshot(),scope); delay(150)
                g.connect(url,"demo-pilot-3-token-2026",true); waitFor {g.connected.value}
                assertEquals("sim-drone-3",g.state.value?.device_id); assertNotEquals(LinkPhase.READY,g.phase.value)
                assertNull(g.commandFeedback.value)
                val posts=generateSequence {server.takeRequest(50,TimeUnit.MILLISECONDS)}.filter {it.method=="POST"}.toList()
                assertTrue(posts.isNotEmpty()); assertTrue(posts.all {it.path!!.startsWith("/api/v1/devices/sim-drone-2/")})
                assertTrue(posts.any {it.path!!.endsWith("/disconnect")})
            } finally {g.stopObserving();g.releaseAll();g.close();scope.cancel()}
        }
    }
    @Test fun liveThreePilotsAndObserverControlIndependentlyForSixtySeconds()=runBlocking {
        val url=System.getenv("DRONE_TEST_INDEPENDENT_URL"); assumeTrue(url!=null)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val cores=(1..4).map {RotorPyClient()}; val gates=cores.map {FlightGateway(it,scope)}
        val pilots=gates.take(3); val observer=gates.last()
        try {
            for (i in 0..3) gates[i].connect(url!!,if(i==3) "demo-observer-token-2026" else "demo-pilot-${i+1}-token-2026",i<3)
            waitFor {gates.all {it.connected.value}}
            gates.forEach {assertEquals(3,it.repository.snapshot.value!!.members.size); assertNotEquals(LinkPhase.READY,it.phase.value)}
            pilots.forEach {it.retryHandshake(scope)}
            waitFor({pilots.joinToString {it.lastError.value.orEmpty()}}) {pilots.all {it.phase.value==LinkPhase.READY && !it.busy.value}}
            val original=observer.repository.snapshot.value!!.members.associate {it.device_id to it.position.value!!.x}
            pilots.forEach {it.onStick(StickSnapshot(ry=.16f),scope)}
            val started=monoMs(); val failures=mutableListOf<String>()
            while (monoMs()-started<60000) {
                pilots.forEachIndexed {i,g -> if (!g.connected.value || !g.canWrite() || g.phase.value!=LinkPhase.READY)
                    failures.add("${i+1}: ${g.phase.value} ${g.lastError.value}") }
                if (failures.isNotEmpty()) break
                delay(100)
            }
            assertTrue("三机持续控制失败：$failures",failures.isEmpty())
            gates.forEach {g -> assertEquals(3,g.repository.snapshot.value!!.members.count {g.repository.fresh(it)})}
            val rates=gates.map {it.repository.receiveHz.value}
            assertTrue("四端状态发布接收率未到20Hz：$rates",rates.all {it>=20})
            assertTrue(cores.take(3).all {it.performance.snapshot().successes>=500})
            observer.repository.snapshot.value!!.members.forEach {assertTrue(it.position.value!!.x>original[it.device_id]!!+.1)}
            // 一机保护和退出不能撤销另两架；观察端同步看到单机保护状态。
            pilots[0].onStick(StickSnapshot(),scope); delay(100); pilots[0].protect()
            waitFor {observer.repository.snapshot.value!!.members.first().mode=="PROTECTED"}
            assertTrue(pilots.drop(1).all {it.phase.value==LinkPhase.READY && it.holding.value})
            pilots[0].stopObserving(); assertTrue(pilots[0].releaseAll())
            delay(200); assertTrue(pilots.drop(1).all {it.phase.value==LinkPhase.READY && it.holding.value})
            val wire=BackendWire()
            for (token in listOf("demo-observer-token-2026","demo-pilot-1-token-2026")) {
                val denied=runCatching {wire.post(url!!,token,"/api/v1/devices/sim-drone-2/session",
                    buildJsonObject {put("device_id","sim-drone-2");put("cluster_id","sim-cluster-1");put("user_initiated",true)})}.exceptionOrNull()
                assertEquals(403,(denied as HttpRejection).code)
            }
            assertTrue(pilots.drop(1).all {it.phase.value==LinkPhase.READY})
            pilots.drop(1).forEach {it.onStick(StickSnapshot(),scope)}; delay(300)
            assertTrue(pilots.drop(1).all {it.phase.value==LinkPhase.READY && it.state.value?.mode=="HOLD"})
            val summary=buildJsonObject {
                put("environment","Windows JVM+unchanged v0.9 backend, loopback; phone LAN acceptance pending")
                put("seconds",60);put("viewers",4);put("aircraft",3)
                put("pilots",JsonArray(cores.take(3).mapIndexed {i,c->buildJsonObject {
                    put("device_id","sim-drone-${i+1}");put("ack_successes",c.performance.snapshot().successes)
                    put("ack_failures",c.performance.snapshot().failures);put("receive_hz",rates[i])
                    c.performance.snapshot().p95Ms?.let {put("p95_upper_ms",it)}
                }}))
            }
            File("build/independent-live-summary.json").writeText(summary.toString())
        } finally {gates.forEach {it.stopObserving();it.releaseAll();it.close()};scope.cancel()}
    }
}
