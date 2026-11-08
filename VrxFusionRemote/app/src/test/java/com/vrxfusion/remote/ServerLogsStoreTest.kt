package com.vrxfusion.remote

import com.vrxfusion.remote.logs.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException

class ServerLogsStoreTest {
    @get:Rule val temporary=TemporaryFolder()
    private val body="""{"ok":true,"data":{"contract_version":"1.0","events":[{"event":"ACK"}],"total":1,"offset":0,
        "has_more":false,"next_offset":null,"snapshot":"g:1","index":{"caught_up":true},"integrity":{"complete":true,"pending_tail":0}}}"""
    private suspend fun waitFor(test: ()->Boolean) { withTimeout(5000) { while (!test()) delay(10) } }
    private fun export()=MockResponse().setBody("{\"event\":\"ACK\"}\n")
        .setHeader("Content-Type","application/x-ndjson").setHeader("X-Log-Snapshot","g:1").setHeader("X-Log-Rows",1)

    @Test fun busyErrorIsRetryableAndDoesNotLeaveAStaleResult()=runBlocking {
        MockWebServer().use { s ->
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val store=ServerLogsStore(scope,temporary.newFolder())
            val c=LogConnection(s.url("/").toString(),"read-only-token")
            try {
                s.enqueue(MockResponse().setResponseCode(503).setBody("""{"error_code":"LOG_BUSY","request_id":"r1"}"""))
                store.query(c,LogFilter()); waitFor {!store.view.value.busy}
                assertNull(store.view.value.page); assertTrue(store.view.value.message.contains("LOG_BUSY"))
                s.enqueue(MockResponse().setBody(body));store.query(c,LogFilter());waitFor {store.view.value.page!=null}
                assertEquals(1L,store.view.value.page!!.total)
                repeat(2) {assertEquals("GET",s.takeRequest().method)}
            } finally {store.close();scope.cancel()}
        }
    }
    @Test fun onlyVerifiedFileIsOfferedForSavingAndSuccessfulSaveDeletesCache()=runBlocking {
        MockWebServer().use { s ->
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val store=ServerLogsStore(scope,temporary.newFolder());val c=LogConnection(s.url("/").toString(),"token")
            try {
                s.enqueue(MockResponse().setBody(body)); store.query(c,LogFilter()); waitFor {store.view.value.page!=null}
                s.enqueue(export()); store.export(c,"jsonl"); waitFor {store.view.value.download!=null}
                val file=store.view.value.download!!.file;assertTrue(file.length()>0)
                store.pickerStarted();assertTrue(store.view.value.pickerStarted)
                val output=ByteArrayOutputStream()
                store.save({output},{fail("成功保存不应删除目标文档")})
                waitFor {store.view.value.message.startsWith("已保存") && !file.exists()}
                assertTrue(output.toString("UTF-8").contains("ACK"));assertNull(store.view.value.download)
            } finally {store.close();scope.cancel()}
        }
    }
    @Test fun failedSaveRemovesDestinationAndCacheWithoutReportingSuccess()=runBlocking {
        MockWebServer().use { s ->
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val store=ServerLogsStore(scope,temporary.newFolder());val c=LogConnection(s.url("/").toString(),"token")
            try {
                s.enqueue(MockResponse().setBody(body));store.query(c,LogFilter());waitFor {store.view.value.page!=null}
                s.enqueue(export());store.export(c,"jsonl");waitFor {store.view.value.download!=null}
                val file=store.view.value.download!!.file
                val cleaned=java.util.concurrent.atomic.AtomicBoolean(false)
                store.save({object:java.io.OutputStream(){override fun write(b: Int){throw IOException("磁盘已满")}}},{cleaned.set(true)})
                waitFor {store.view.value.message.startsWith("保存失败") && !file.exists()}
                assertTrue(cleaned.get());assertFalse(store.view.value.busy)
            } finally {store.close();scope.cancel()}
        }
    }
}
