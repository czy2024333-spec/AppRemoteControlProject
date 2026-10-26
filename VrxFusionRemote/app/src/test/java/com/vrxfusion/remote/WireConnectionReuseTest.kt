package com.vrxfusion.remote

import com.vrxfusion.remote.rotorpy.BackendWire
import com.vrxfusion.remote.rotorpy.HttpRejection
import kotlinx.serialization.json.buildJsonObject
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

/** 模拟后端已关闭空闲连接：只读查询可重连，写控制绝不因此自动重放。 */
class WireConnectionReuseTest {
    @Test fun authenticationAndPermissionRejectionsAreNotRetriedOrHidden() {
        for (code in listOf(401,403)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(code).setBody("""{"ok":false,"error":"denied"}"""))
            server.enqueue(MockResponse().setBody("""{"ok":true,"data":{}}"""))
            val error=runCatching { BackendWire().get(server.url("/").toString().trimEnd('/'),"test-source-token","/directory") }.exceptionOrNull()
            assertTrue(error is HttpRejection); assertEquals(code,(error as HttpRejection).code)
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun readOnlyGetRecoversWhenThePooledConnectionWasClosedByServer() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true,"data":{}}""").setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
            server.enqueue(MockResponse().setBody("""{"ok":true,"data":{}}"""))
            val wire=BackendWire(); val url=server.url("/").toString().trimEnd('/')
            wire.get(url,"test-source-token","/directory")
            Thread.sleep(150) // 等服务器关闭连接，客户端连接池仍可能保留这条旧连接。
            wire.get(url,"test-source-token","/directory")
            assertEquals(2,server.requestCount)
            assertEquals("GET",server.takeRequest().method); assertEquals("GET",server.takeRequest().method)
        }
    }
    @Test fun aLostWriteReceiptNeverCausesTheControlPostToBeRetried() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setBody("""{"ok":true,"data":{}}"""))
            val wire=BackendWire(); val url=server.url("/").toString().trimEnd('/')
            assertTrue(runCatching { wire.post(url,"test-source-token","/api/input",buildJsonObject {}) }.isFailure)
            assertEquals(1,server.requestCount); assertEquals("POST",server.takeRequest().method)
        }
    }
}
