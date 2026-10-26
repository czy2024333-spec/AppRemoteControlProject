package com.vrxfusion.remote

import com.vrxfusion.remote.video.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class VideoProtocolTest {
    private val jpeg=byteArrayOf(-1,-40,1,2,-1,-39)
    private fun part(length: Int=jpeg.size,type: String="image/jpeg")=
        "--frame\r\nContent-Type: $type\r\nContent-Length: $length\r\n\r\n".toByteArray()+jpeg+"\r\n".toByteArray()
    @Test fun videoAddressAndCodeAreSeparateFromControlCredentials() {
        assertNull(videoConnectionIssue("http://192.168.1.100:8768/video.mjpeg","camera-code-2026-abc"))
        for (url in listOf("ws://host/video","http://u:p@host/video","http://host/video?code=secret","http://host:99999/video")) {
            assertNotNull(url,videoConnectionIssue(url,"camera-code-2026-abc"))
        }
        assertNotNull(videoConnectionIssue("http://host/video","short"))
    }
    @Test fun multipartFramesPreserveBoundariesAcrossReads() {
        val reader=MjpegReader(ByteArrayInputStream(part()+part()),"frame")
        assertArrayEquals(jpeg,reader.nextFrame()); assertArrayEquals(jpeg,reader.nextFrame())
        assertEquals("frame",MjpegReader.boundary("multipart/x-mixed-replace; boundary=\"frame\""))
    }
    @Test fun oversizedAndNonJpegPartsAreRejectedBeforeAllocation() {
        for (input in listOf(part(2*1024*1024+1),part(-1),part(type="text/plain"))) {
            try { MjpegReader(ByteArrayInputStream(input),"frame").nextFrame(); fail("必须拒绝异常帧") }
            catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun truncatedJpegCannotBecomeAValidFrame() {
        try { MjpegReader(ByteArrayInputStream(part().copyOf(part().size-5)),"frame").nextFrame(); fail() }
        catch (_: EOFException) {}
    }
    @Test fun rejectsWrongBoundaryAndUnboundedHeader() {
        for (data in listOf("--other\r\n".toByteArray(),ByteArray(1025){65})) {
            try { MjpegReader(ByteArrayInputStream(data),"frame").nextFrame(); fail() }
            catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun streamNeverForwardsAccessCodeToRedirectTarget() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location","http://127.0.0.1:1/steal"))
            val done=CountDownLatch(1)
            val stream=PcVideoStream(server.url("/video").toString(),"camera-code-2026-abc")
            try {
                stream.start({ fail("重定向不应产生画面") },{ done.countDown() })
                assertTrue(done.await(3,TimeUnit.SECONDS))
                val request=server.takeRequest(1,TimeUnit.SECONDS)!!
                assertEquals("Bearer camera-code-2026-abc",request.getHeader("Authorization"))
                assertEquals(1,server.requestCount)
            } finally { stream.close() }
        }
    }
    @Test fun livePcServerStreamsAuthenticatedJpegs() {
        val url=System.getenv("DRONE_TEST_VIDEO_URL"); val code=System.getenv("DRONE_TEST_VIDEO_CODE")
        assumeTrue(url!=null && code!=null)
        val frames=CountDownLatch(3); val error=AtomicReference<String?>(null)
        val stream=PcVideoStream(url!!,code!!)
        try {
            stream.start({ assertTrue(it.size>100); frames.countDown() },{ error.set(it) })
            assertTrue("真实电脑组件未发送三帧：${error.get()}",frames.await(5,TimeUnit.SECONDS))
            assertNull(error.get())
            println("真实PC视频组件：授权后已接收3帧JPEG（明确标记的合成测试图）")
        } finally { stream.close() }
    }
}
