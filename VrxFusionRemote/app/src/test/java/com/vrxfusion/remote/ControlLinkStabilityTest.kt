package com.vrxfusion.remote

import com.vrxfusion.remote.rotorpy.*
import com.vrxfusion.remote.unified.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 跨过多轮3秒目录刷新；不能只验证首轮新建连接后就宣布稳定。 */
class ControlLinkStabilityTest {
    @Test fun liveContinuousInputKeepsAuthorityAcrossRepeatedDirectoryRefreshes()=runBlocking {
        val url=System.getenv("DRONE_TEST_STABILITY_URL"); assumeTrue(url!=null)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val core=RotorPyClient(); val gateway=FlightGateway(core,scope)
        val trace=java.util.concurrent.CopyOnWriteArrayList<String>()
        core.audit={ type,code -> trace.add("$type|$code") }
        suspend fun waitFor(f: ()->Boolean)=withTimeout(6000) { while (!f()) delay(10) }
        try {
            gateway.connect(url!!,"demo-app-token-2026",true); waitFor { gateway.connected.value }
            gateway.retryHandshake(scope); waitFor { gateway.phase.value==LinkPhase.READY && !gateway.busy.value }
            gateway.onStick(StickSnapshot(ry=.2f),scope)
            val failures=mutableListOf<String>(); val started=monoMs()
            // 持续一分钟跨过约20轮刷新；不能把退回观察后继续收状态当成成功。
            while (monoMs()-started<60000) {
                if (!gateway.canWrite() || gateway.phase.value!=LinkPhase.READY || !gateway.connected.value) {
                    if (failures.size<20) failures.add("t=${monoMs()-started}|write=${gateway.canWrite()}|phase=${gateway.phase.value}|${gateway.lastError.value}")
                }
                delay(20)
            }
            gateway.onStick(StickSnapshot(),scope); delay(350)
            println("STABILITY_TRACE|"+trace.joinToString("\n"))
            println("STABILITY_SUMMARY|elapsed_ms=${monoMs()-started}|stats=${core.performance.snapshot()}|failures=${failures.size}")
            assertTrue("持续推杆发生只读/控制权失效：\n"+failures.joinToString("\n"),failures.isEmpty())
            assertEquals(LinkPhase.READY,gateway.phase.value)
            assertTrue(core.performance.snapshot().successes>=400)
        } finally { gateway.stopObserving(); gateway.releaseAll(); gateway.close(); scope.cancel() }
    }
}
