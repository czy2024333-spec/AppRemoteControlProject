package com.vrxfusion.remote

import com.vrxfusion.remote.logs.*
import com.vrxfusion.remote.rotorpy.*
import com.vrxfusion.remote.unified.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** 需显式启动带日志能力的后端；三路操纵、四路状态与本次Android日志客户端并发。 */
class LogsIntegrationTest {
    private suspend fun waitFor(test: ()->Boolean) { withTimeout(8000) {while (!test()) delay(20)} }
    @Test fun liveThreePilotsAndLogsRemainIndependentForSixtySeconds()=runBlocking {
        val url=System.getenv("DRONE_TEST_LOGS_URL");assumeTrue(url!=null)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val cores=(1..4).map {RotorPyClient()};val gates=cores.map {FlightGateway(it,scope)}
        val logs=ServerLogsClient();val queries=AtomicInteger();val exports=AtomicInteger();val busy=AtomicInteger()
        val errors=CopyOnWriteArrayList<String>();var reader: Job?=null
        try {
            gates.forEachIndexed {i,g->g.connect(url!!,if (i==3) "demo-observer-token-2026" else "demo-pilot-${i+1}-token-2026",i<3)}
            waitFor {gates.all {it.connected.value}}
            assertTrue(gates.last().repository.info.value!!.capabilities["logs_query"]==true)
            gates.take(3).forEach {it.retryHandshake(scope)}
            waitFor {gates.take(3).all {it.phase.value==LinkPhase.READY && !it.busy.value}}
            val c=LogConnection(url!!,"demo-observer-token-2026")
            reader=scope.launch {
                while (isActive) {
                    var file: File?=null
                    try {
                        val filter=LogFilter(event="MEMBER_STARTED")
                        val page=logs.query(c,filter)
                        assertTrue(page.total>=3);queries.incrementAndGet()
                        if (queries.get()%10==1) {
                            val format=if (exports.get()%2==0) "jsonl" else "csv"
                            file=File.createTempFile("frontend-log-live-",".part")
                            val result=logs.download(c,filter,page,format,file)
                            assertTrue(result.bytes>0);assertEquals(page.total,result.rows)
                            if (format=="jsonl") assertEquals(page.total,file.readLines().size.toLong())
                            else assertTrue(file.readText().startsWith("\uFEFF"))
                            exports.incrementAndGet()
                        }
                    } catch(e: CancellationException) {throw e}
                    catch(e: LogIssue) {
                        if (e.code in setOf("LOG_BUSY","LOG_INDEX_BUILDING","LOG_INDEX_UNAVAILABLE")) busy.incrementAndGet()
                        else errors.add(e.code)
                    } catch(e: Throwable) {errors.add(e.javaClass.simpleName+":"+e.message)}
                    finally {file?.delete()}
                    delay(200)
                }
            }
            gates.take(3).forEach {it.onStick(StickSnapshot(ry=.12f),scope)}
            val start=monoMs()
            while (monoMs()-start<60000) {
                gates.take(3).forEachIndexed {i,g ->
                    assertTrue("飞机${i+1}控制失效：${g.lastError.value}",g.connected.value && g.phase.value==LinkPhase.READY && g.canWrite())
                }
                delay(100)
            }
            reader.cancelAndJoin();reader=null
            assertTrue(errors.toString(),errors.isEmpty());assertTrue(queries.get()>0);assertTrue(exports.get()>=2)
            val rates=gates.map {it.repository.receiveHz.value}
            assertTrue(rates.toString(),rates.all {it>=20})
            assertTrue(cores.take(3).all {it.performance.snapshot().successes>=500 && it.performance.snapshot().failures==0L})
            gates.take(3).forEach {it.onStick(StickSnapshot(),scope)};delay(300)
            assertTrue(gates.take(3).all {it.phase.value==LinkPhase.READY && it.state.value?.mode=="HOLD"})
            val summary=buildJsonObject {
                put("seconds",60);put("viewers",4);put("aircraft",3)
                put("log_queries",queries.get());put("log_exports",exports.get());put("expected_index_or_busy",busy.get())
                put("unexpected_log_errors",errors.size);put("state_receive_hz",JsonArray(rates.map {JsonPrimitive(it)}))
                put("pilots",JsonArray(cores.take(3).mapIndexed {i,core->buildJsonObject {
                    put("device_id","sim-drone-${i+1}");put("ack_successes",core.performance.snapshot().successes)
                    put("ack_failures",core.performance.snapshot().failures)
                    core.performance.snapshot().p95Ms?.let {put("p95_upper_ms",it)}
                }}))
                put("scope","Windows JVM + unchanged GitHub backend runtime; not phone/LAN/four-hour acceptance")
            }
            File("build/logs-live-summary.json").writeText(summary.toString())
        } finally {
            reader?.cancelAndJoin();logs.close()
            gates.forEach {it.stopObserving();it.releaseAll();it.close()};scope.cancel()
        }
    }
}
