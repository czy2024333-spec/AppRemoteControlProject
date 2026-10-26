package com.vrxfusion.remote

import com.vrxfusion.remote.rotorpy.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 用真实HTTP竞争复现：不能只停发运动，留下心跳等待后端TTL保护。 */
class ControlContinuityTest {
    private suspend fun waitFor(f: ()->Boolean) = withTimeout(4000) { while (!f()) delay(5) }
    private class Service(val rejectMotion: Boolean=false,val ttlMs: Int=500,val changingStopEpoch: Boolean=false,val protectOnStop: Boolean=false): Dispatcher() {
        val motions=AtomicInteger(); val stops=AtomicInteger(); val heartbeats=AtomicInteger()
        val epoch=AtomicReference("e")
        override fun dispatch(r: RecordedRequest): MockResponse {
            if (r.path=="/api/events") return MockResponse().addHeader("Content-Type","text/event-stream")
                .setBody((1..220).joinToString("") {
                    val e=if (changingStopEpoch && it>=25) "e2" else "e"
                    val mode=if (protectOnStop && it>=25) "PROTECTED" else "HOLD"
                    "data: {\"boot_id\":\"b\",\"sequence\":$it,\"mode\":\"$mode\",\"control_epoch\":\"$e\",\"selected_source\":\"app\"}\n\n".padEnd(260,'\n') })
                .throttleBody(260,40,TimeUnit.MILLISECONDS)
            if (r.path=="/api/input") {
                motions.incrementAndGet()
                return if (rejectMotion) MockResponse().setResponseCode(409).setBody("""{"ok":false,"error":"motion rejected","error_code":"SOURCE_NOT_SELECTED"}""")
                    else MockResponse().setBody("""{"ok":true,"data":{"control_epoch":"e"}}""")
                        .setBodyDelay(if (changingStopEpoch) 0 else 1500,TimeUnit.MILLISECONDS)
            }
            if (r.path=="/api/stop") stops.incrementAndGet()
            if (r.path=="/api/stop" && changingStopEpoch) {
                epoch.set("e2")
                return MockResponse().setBody("""{"ok":true,"data":{"control_epoch":"e2","sequence":25,"command_status":"COMPLETED"}}""")
                    .setBodyDelay(350,TimeUnit.MILLISECONDS)
            }
            if (r.path=="/api/heartbeat") heartbeats.incrementAndGet()
            val body=when(r.path) {
                "/api/state" -> """{"boot_id":"b","sequence":0,"mode":"HOLD","control_epoch":"e","selected_source":"app","control_config":{"input_ttl_ms":$ttlMs,"sources_enabled":{"app":true}}}"""
                "/api/session" -> """{"session_id":"s","control_epoch":"e"}"""
                else -> """{"control_epoch":"${epoch.get()}","command_status":"COMPLETED"}"""
            }
            return MockResponse().setBody("""{"ok":true,"data":$body}""")
        }
    }
    private fun client(server: MockWebServer)=RotorPyClient(server.url("/").toString(),"demo-app-token-2026")
    @Test fun protectionStillRevokesImmediatelyWhileOwnStopIsPending()=runBlocking {
        MockWebServer().use { server ->
            val service=Service(changingStopEpoch=true,protectOnStop=true); server.dispatcher=service
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default); val c=client(server)
            try {
                c.startPolling(scope); waitFor { c.connected.value }; c.retryHandshake(scope)
                waitFor { c.phase.value==LinkPhase.READY && !c.busy.value && (c.state.value?.sequence ?: 0)>=20 }
                c.onStick(StickSnapshot(ry=.2f),scope); waitFor { service.motions.get()>0 }
                c.onStick(StickSnapshot(),scope)
                waitFor { c.state.value?.mode=="PROTECTED" && c.phase.value!=LinkPhase.READY }
                assertFalse(c.holding.value)
            } finally { c.stopPolling(); scope.cancel() }
        }
    }
    @Test fun ownStopStateArrivingBeforeHttpReceiptDoesNotRevokeAuthority()=runBlocking {
        MockWebServer().use { server ->
            val service=Service(changingStopEpoch=true); server.dispatcher=service
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default); val c=client(server)
            try {
                c.startPolling(scope); waitFor { c.connected.value }; c.retryHandshake(scope)
                waitFor { c.phase.value==LinkPhase.READY && !c.busy.value && (c.state.value?.sequence ?: 0)>=20 }
                c.onStick(StickSnapshot(ry=.2f),scope); waitFor { service.motions.get()>0 }
                c.onStick(StickSnapshot(),scope)
                waitFor { service.stops.get()>0 && !c.busy.value }
                delay(100)
                assertEquals("自己的STOP新轮次先到SSE不能被当成外部抢权：${c.lastError.value}",LinkPhase.READY,c.phase.value)
                assertNull(c.controlLossReason.value)
            } finally { c.stopPolling(); scope.cancel() }
        }
    }
    @Test fun shortLeaseUsesHeartbeatWithMarginEvenWhenSticksAreNeutral()=runBlocking {
        MockWebServer().use { server ->
            val service=Service(ttlMs=100); server.dispatcher=service
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default); val c=client(server)
            try {
                c.startPolling(scope); waitFor { c.connected.value }; c.retryHandshake(scope)
                waitFor { c.phase.value==LinkPhase.READY && !c.busy.value }
                service.heartbeats.set(0); delay(450)
                assertTrue("100ms租约应留有发送和处理余量，不能仍每100ms才心跳",service.heartbeats.get()>=7)
                assertEquals(0,service.motions.get()); assertEquals(LinkPhase.READY,c.phase.value)
            } finally { c.stopPolling(); scope.cancel() }
        }
    }
    @Test fun releaseInterruptsPendingMotionAndConfirmsStopWithoutLosingAuthority()=runBlocking {
        MockWebServer().use { server ->
            val service=Service(); server.dispatcher=service
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default); val c=client(server)
            try {
                c.startPolling(scope); waitFor { c.connected.value }; c.retryHandshake(scope)
                waitFor { c.phase.value==LinkPhase.READY && !c.busy.value }
                c.onStick(StickSnapshot(ry=.2f),scope); waitFor { service.motions.get()>0 }
                val start=monoMs(); c.onStick(StickSnapshot(),scope)
                waitFor { service.stops.get()>0 && !c.busy.value }
                assertTrue("松杆停止不应被1.5秒旧运动应答阻塞",monoMs()-start<600)
                assertEquals(LinkPhase.READY,c.phase.value)
                assertEquals("COMPLETED",c.commandFeedback.value?.status)
            } finally { c.stopPolling(); scope.cancel() }
        }
    }
    @Test fun revokedMotionSubmitsStopInsteadOfLeavingOnlyHeartbeats()=runBlocking {
        MockWebServer().use { server ->
            val service=Service(); server.dispatcher=service
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default); val c=client(server)
            try {
                c.startPolling(scope); waitFor { c.connected.value }; c.retryHandshake(scope)
                waitFor { c.phase.value==LinkPhase.READY && !c.busy.value }
                c.onStick(StickSnapshot(ry=.2f),scope); waitFor { service.motions.get()>0 }
                c.invalidateControl("统一状态失效")
                waitFor { service.stops.get()>0 && !c.safetyBusy.value }
                assertNotEquals(LinkPhase.READY,c.phase.value)
                assertTrue(c.lastError.value.orEmpty().contains("统一状态失效"))
                delay(100); assertEquals(1,service.motions.get())
            } finally { c.stopPolling(); scope.cancel() }
        }
    }
    @Test fun rejectedMotionAlsoAttemptsStopAndNeverAutomaticallyReacquires()=runBlocking {
        MockWebServer().use { server ->
            val service=Service(true); server.dispatcher=service
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default); val c=client(server)
            try {
                c.startPolling(scope); waitFor { c.connected.value }; c.retryHandshake(scope)
                waitFor { c.phase.value==LinkPhase.READY && !c.busy.value }
                c.onStick(StickSnapshot(ry=.2f),scope)
                waitFor { service.stops.get()>0 && !c.safetyBusy.value }
                assertNotEquals(LinkPhase.READY,c.phase.value)
                assertTrue(c.lastError.value.orEmpty().contains("motion rejected"))
                delay(100); assertEquals(1,service.motions.get())
            } finally { c.stopPolling(); scope.cancel() }
        }
    }
}
