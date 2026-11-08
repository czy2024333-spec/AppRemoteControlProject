package com.vrxfusion.remote

import com.vrxfusion.remote.logs.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** 故障注入验证读取前/读取中上限、快照、断流和取消，不使用真实飞行控制接口。 */
class ServerLogsTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun connection(s: MockWebServer)=LogConnection(s.url("/").toString(),"observer-test-token")
    private fun page()=LogPage(emptyList(),2,0,null,"generation:9",true,0,true)
    private fun export(body: String,type: String="application/x-ndjson")=MockResponse().setBody(body)
        .setHeader("Content-Type",type).setHeader("X-Log-Snapshot","generation:9").setHeader("X-Log-Rows",2)
    private fun result(offset: Int=0,next: Int?=2,snapshot: String="generation:9")="""{
        "ok":true,"data":{"contract_version":"1.0","events":[{"event":"ACK"},{"event":"HOLD"}],
        "total":4,"offset":$offset,"has_more":${next!=null},"next_offset":$next,"snapshot":"$snapshot",
        "index":{"caught_up":false},"integrity":{"complete":false,"pending_tail":1,"invalid_rows":0}}} """

    @Test fun queryAndNextPageCarryOneSnapshotAndEncodeFilters()=runBlocking {
        MockWebServer().use { s ->
            val client=ServerLogsClient()
            try {
                s.enqueue(MockResponse().setBody(result())); s.enqueue(MockResponse().setBody(result(2,null)))
                val filter=LogFilter("sim-drone-2","ACK + 中",1,999)
                val first=client.query(connection(s),filter)
                assertFalse(first.caughtUp); assertFalse(first.complete); assertEquals(1,first.pendingTail)
                val second=client.query(connection(s),filter,first.nextOffset!!,first.snapshot)
                assertNull(second.nextOffset)
                val a=s.takeRequest(); val b=s.takeRequest()
                assertEquals("GET",a.method); assertEquals("Bearer observer-test-token",a.getHeader("Authorization"))
                assertEquals("ACK + 中",a.requestUrl!!.queryParameter("event"))
                assertEquals("20",a.requestUrl!!.queryParameter("limit"))
                assertEquals("generation:9",b.requestUrl!!.queryParameter("snapshot"))
                assertEquals("2",b.requestUrl!!.queryParameter("offset"))
                assertFalse(a.path!!.contains("token"))
            } finally { client.close() }
        }
    }
    @Test fun structuredErrorsKeepRequestIdAndDoNotMasqueradeAsControlLoss()=runBlocking {
        MockWebServer().use { s ->
            val client=ServerLogsClient()
            try {
                for ((status,code) in listOf(503 to "LOG_INDEX_BUILDING",503 to "LOG_BUSY",409 to "LOG_SNAPSHOT_EXPIRED",
                    409 to "LOG_DATA_CORRUPT",413 to "LOG_EXPORT_TOO_LARGE",503 to "LOG_READ_FAILED")) {
                    s.enqueue(MockResponse().setResponseCode(status).setBody("""{"ok":false,"error_code":"$code","request_id":"req-123"}"""))
                    val e=runCatching {client.query(connection(s),LogFilter())}.exceptionOrNull() as LogIssue
                    assertEquals(code,e.code); assertEquals("req-123",e.requestId)
                    assertTrue(logIssueText(e).contains(code)); assertTrue(logIssueText(e).contains("req-123"))
                }
                repeat(6) { assertEquals("GET",s.takeRequest().method) }
            } finally { client.close() }
        }
    }
    @Test fun knownOversizedBodyIsRejectedBeforeAnyFileIsKept()=runBlocking {
        MockWebServer().use { s ->
            val client=ServerLogsClient(exportLimit=100)
            val file=temporary.newFile()
            try {
                s.enqueue(export("small").setHeader("Content-Length",5000))
                val e=runCatching {client.download(connection(s),LogFilter(),page(),"jsonl",file)}.exceptionOrNull() as LogIssue
                assertEquals("LOG_EXPORT_TOO_LARGE",e.code); assertFalse(file.exists())
            } finally {client.close()}
        }
    }
    @Test fun chunkedUnknownLengthAlsoRejectsOverLimitAndRemovesPartialFile()=runBlocking {
        MockWebServer().use { s ->
            val client=ServerLogsClient(exportLimit=100)
            val file=temporary.newFile()
            try {
                s.enqueue(export("").setChunkedBody("x".repeat(101),7))
                val e=runCatching {client.download(connection(s),LogFilter(),page(),"jsonl",file)}.exceptionOrNull() as LogIssue
                assertEquals("LOG_EXPORT_TOO_LARGE",e.code); assertFalse(file.exists())
            } finally {client.close()}
        }
    }
    @Test fun brokenConnectionNeverBecomesSuccessfulEmptyExport()=runBlocking {
        MockWebServer().use { s ->
            val client=ServerLogsClient()
            val file=temporary.newFile()
            try {
                s.enqueue(export("x".repeat(10000)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
                assertNotNull(runCatching {client.download(connection(s),LogFilter(),page(),"jsonl",file)}.exceptionOrNull())
                assertFalse(file.exists())
            } finally {client.close()}
        }
    }
    @Test fun jsonErrorDisguisedAsSuccessCannotBeSavedAsCsv()=runBlocking {
        MockWebServer().use { s ->
            val client=ServerLogsClient(); val file=temporary.newFile()
            try {
                s.enqueue(export("{\"error\":\"failed\"}","application/json"))
                val e=runCatching {client.download(connection(s),LogFilter(),page(),"csv",file)}.exceptionOrNull() as LogIssue
                assertEquals("LOG_BAD_RESPONSE",e.code); assertFalse(file.exists())
            } finally {client.close()}
        }
    }
    @Test fun csvSuccessPreservesBomFilterAndSnapshot()=runBlocking {
        MockWebServer().use { s ->
            val client=ServerLogsClient(); val file=temporary.newFile()
            try {
                val csv="\uFEFFevent\nACK\nHOLD\n"
                s.enqueue(export(csv,"text/csv; charset=utf-8").setHeader("X-Log-Pending-Tail",1))
                val out=client.download(connection(s),LogFilter("sim-drone-2"),page(),"csv",file)
                assertEquals(csv,file.readText()); assertEquals(csv.toByteArray().size.toLong(),out.bytes)
                assertEquals(1,out.pendingTail); assertEquals(2L,out.rows)
                val request=s.takeRequest()
                assertEquals("sim-drone-2",request.requestUrl!!.queryParameter("device_id"))
                assertEquals("generation:9",request.requestUrl!!.queryParameter("snapshot"))
            } finally {client.close()}
        }
    }
    @Test fun wrongSnapshotOrRowCountDeletesDownload()=runBlocking {
        MockWebServer().use { s ->
            val client=ServerLogsClient()
            try {
                for (response in listOf(export("{}").setHeader("X-Log-Snapshot","restarted:1"),export("{}").setHeader("X-Log-Rows",9))) {
                    s.enqueue(response); val file=temporary.newFile()
                    assertNotNull(runCatching {client.download(connection(s),LogFilter(),page(),"jsonl",file)}.exceptionOrNull())
                    assertFalse(file.exists())
                }
            } finally {client.close()}
        }
    }
    @Test fun cancellationClosesDownloadAndDeletesTemporaryFile()=runBlocking {
        MockWebServer().use { s ->
            val client=ServerLogsClient(); val file=temporary.newFile()
            try {
                s.enqueue(export("x".repeat(100000)).throttleBody(1000,100,TimeUnit.MILLISECONDS))
                val job=launch(Dispatchers.Default) {client.download(connection(s),LogFilter(),page(),"jsonl",file)}
                assertNotNull(s.takeRequest(3,TimeUnit.SECONDS)); delay(50)
                job.cancelAndJoin(); assertFalse(file.exists())
            } finally {client.close()}
        }
    }
    @Test fun boundingReaderNeverWritesOrReadsMoreThanOneExtraByte() {
        val input=ByteArrayInputStream(ByteArray(10000)); val output=ByteArrayOutputStream()
        val e=runCatching {copyBounded(input,output,100)}.exceptionOrNull() as LogIssue
        assertEquals("LOG_EXPORT_TOO_LARGE",e.code)
        assertTrue(output.size()<=100); assertEquals(9899,input.available())
    }
}
