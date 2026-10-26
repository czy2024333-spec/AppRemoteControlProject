package com.vrxfusion.remote

import com.vrxfusion.remote.diagnostics.FlightDiagnostics
import com.vrxfusion.remote.rotorpy.RotorPyClient
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.*

/** 短时验证采样和落盘，不宣称替代真实终端4小时运行。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class DiagnosticsTest {
    @Test fun recordsResourcesLifecycleAndCanExportWithoutCredentials()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val d=FlightDiagnostics(RuntimeEnvironment.getApplication(),scope)
        val client=RotorPyClient(token="secret-must-not-appear")
        try {
            d.conditions="API34 synthetic test"
            d.start(client)
            client.performance.record(22.2,true); client.performance.record(2500.0,false)
            d.foreground(false); d.foreground(true)
            delay(10_700)
            d.finish()
            val output=ByteArrayOutputStream(); d.exportTo(output)
            val text=output.toString("UTF-8")
            val records=text.lineSequence().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }.toList()
            assertTrue(records.any { it["type"]?.jsonPrimitive?.content=="sample" })
            assertTrue(records.any { it["type"]?.jsonPrimitive?.content=="end" })
            assertTrue(text.contains("pss_kb")); assertTrue(text.contains("cpu_one_core_percent"))
            assertFalse(text.contains("secret-must-not-appear")); assertFalse(d.view.value.active)
            assertTrue(d.view.value.elapsedS<60); assertTrue(d.view.value.note.contains("审核"))
            assertEquals(1L,d.view.value.interruptions)
        } finally { d.close(); scope.cancel() }
    }
    @Test fun abandonedRecordIsMarkedUnfinishedOnNextInstance()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val app=RuntimeEnvironment.getApplication()
        val d=FlightDiagnostics(app,scope)
        try {
            d.start(RotorPyClient()); d.close()
            val next=FlightDiagnostics(app,scope)
            assertTrue(next.view.value.note.contains("未正常结束"))
            assertFalse(next.view.value.active)
        } finally { scope.cancel() }
    }
}
