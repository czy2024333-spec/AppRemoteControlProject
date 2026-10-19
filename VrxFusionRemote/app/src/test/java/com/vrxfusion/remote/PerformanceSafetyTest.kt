package com.vrxfusion.remote

import com.vrxfusion.remote.rotorpy.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

/** 验证测量口径以及安全动作在真实HTTP竞争下的优先级。 */
class PerformanceSafetyTest {
    @Test fun p95UsesNearestRankAndFailureDenominatorIsVisible() {
        val stats=PerformanceStats()
        (1..100).forEach { stats.record(it.toDouble(),true) }
        stats.record(2500.0,false)
        val result=stats.snapshot()
        assertEquals(95.0,result.p95Ms!!,0.0); assertEquals(101,result.attempts)
        assertEquals(100,result.successes); assertEquals(1,result.failures)
        stats.reset(); assertNull(stats.snapshot().p95Ms)
    }
    @Test fun latencyHistogramIsConservativeAndBounded() {
        val stats=PerformanceStats()
        repeat(20000) { stats.record(100.01,true) }
        assertEquals(101.0,stats.snapshot().p95Ms!!,0.0)
        assertEquals(20000,stats.snapshot().successes)
    }
    @Test fun stateRatesDoNotCountDuplicateFramesAndExpire() {
        val rate=StateRate()
        repeat(126) { rate.record("boot:$it",1000L+it*40); rate.record("boot:$it",1000L+it*40) }
        assertEquals(25.0,rate.hz(6000),0.1)
        assertEquals(0.0,rate.hz(12000),0.0)
    }
    private fun dispatcher(counter: AtomicInteger,slowPath: String): Dispatcher=object: Dispatcher() {
        override fun dispatch(r: RecordedRequest): MockResponse {
            if (r.path=="/api/events") return MockResponse().addHeader("Content-Type","text/event-stream")
                .setBody((1..250).joinToString("") { "data: {\"sequence\":$it,\"mode\":\"HOLD\",\"control_epoch\":\"e\",\"selected_source\":\"app\"}\n\n".padEnd(200,'\n') })
                .throttleBody(200,40,TimeUnit.MILLISECONDS)
            if (r.path==slowPath) { counter.incrementAndGet(); return MockResponse()
                .setBody("""{"ok":true,"data":{"control_epoch":"old","command_status":"COMPLETED"}}""")
                .setBodyDelay(1800,TimeUnit.MILLISECONDS) }
            val data=when(r.path) {
                "/api/state" -> """{"sequence":0,"mode":"HOLD","control_epoch":"e","selected_source":"app"}"""
                "/api/session" -> """{"session_id":"s","control_epoch":"e"}"""
                else -> """{"control_epoch":"e","command_status":"COMPLETED"}"""
            }
            return MockResponse().setBody("""{"ok":true,"data":$data}""")
        }
    }
    @Test fun protectInterruptsBusyOrdinaryConfigAndOldReplyCannotRestoreControl()=runBlocking {
        MockWebServer().use { server ->
            val slow=AtomicInteger(); server.dispatcher=dispatcher(slow,"/api/config")
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val c=RotorPyClient(server.url("/").toString().trimEnd('/'),"demo-app-token-2026")
            suspend fun waitFor(f: ()->Boolean) { withTimeout(3000) { while (!f()) delay(5) } }
            try {
                c.startPolling(scope); waitFor { c.connected.value }
                c.retryHandshake(scope); waitFor { c.phase.value==LinkPhase.READY && !c.busy.value }
                val config=scope.async { c.applyConfig(scope,1,mapOf("sensitivity" to .5),true) }
                waitFor { slow.get()>0 }; assertTrue(c.busy.value)
                val start=System.nanoTime(); c.protect()
                assertTrue("保护必须中断1.8秒的普通响应等待",(System.nanoTime()-start)/1e6<1200)
                assertFalse(config.await()); assertEquals("保护",c.commandFeedback.value?.action)
                assertEquals("COMPLETED",c.commandFeedback.value?.status)
                assertNotEquals(LinkPhase.READY,c.phase.value)
                delay(100); assertFalse(c.holding.value)
            } finally { c.stopPolling(); scope.cancel() }
        }
    }
    @Test fun unconfirmedPolicyAndBadAxesCannotSendMotion()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default); val c=RotorPyClient()
        try {
            assertFalse(c.applyConfig(scope,1,mapOf("deadzone" to .1)))
            assertFalse(c.applyConfig(scope,1,mapOf("max_horizontal_speed_m_s" to 99.0),true))
            assertFalse(c.switchTo(scope,"voice"))
            assertFalse(StickSnapshot(rx=Float.NaN).valid()); assertFalse(StickSnapshot(ly=2f).valid())
            assertTrue(StickSnapshot(rx=1f,ly=-1f).valid())
        } finally { scope.cancel() }
    }
    @Test fun stopCannotCancelAnInFlightProtection()=runBlocking {
        MockWebServer().use { server ->
            val protections=AtomicInteger(); server.dispatcher=dispatcher(protections,"/api/protect")
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val c=RotorPyClient(server.url("/").toString().trimEnd('/'),"demo-app-token-2026")
            try {
                c.startPolling(scope); withTimeout(3000) { while (!c.connected.value) delay(5) }
                val protection=scope.launch { c.protect() }
                withTimeout(3000) { while (protections.get()==0) delay(5) }
                c.stop(); protection.join()
                val requests=(1..server.requestCount).map { server.takeRequest().path }
                assertFalse(requests.contains("/api/stop"))
                assertEquals("保护",c.commandFeedback.value?.action)
                assertFalse(c.safetyBusy.value); assertFalse(c.holding.value)
            } finally { c.stopPolling(); scope.cancel() }
        }
    }
    @Test fun missingUiFrameHeartbeatStopsRenewingOldStick()=runBlocking {
        MockWebServer().use { server ->
            server.dispatcher=dispatcher(AtomicInteger(),"/unused")
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val c=RotorPyClient(server.url("/").toString().trimEnd('/'),"demo-app-token-2026")
            suspend fun waitFor(f: ()->Boolean) { withTimeout(3000) { while (!f()) delay(5) } }
            try {
                c.startPolling(scope); waitFor { c.connected.value }
                c.retryHandshake(scope); waitFor { c.phase.value==LinkPhase.READY && !c.busy.value }
                c.attachInputUi(); c.onStick(StickSnapshot(ry=.2f),scope)
                waitFor { c.performance.snapshot().successes>=2 }
                // 不再报告UI帧；网络/状态流仍正常，以区分UI冻结和断网。
                waitFor { !c.holding.value && c.phase.value!=LinkPhase.READY }
                val count=c.performance.snapshot().attempts
                delay(150); assertEquals(count,c.performance.snapshot().attempts)
            } finally { c.stopPolling(); scope.cancel() }
        }
    }
    @Test fun oversizedSseLineIsRejectedWithoutUnboundedAccumulation() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().addHeader("Content-Type","text/event-stream").setBody("data: "+"x".repeat(70000)+"\n\n"))
            val wire=BackendWire()
            assertTrue(runCatching { wire.readStream(wire.stream(server.url("/").toString().trimEnd('/'),"token","/api/events")) {} }.isFailure)
        }
    }
}
