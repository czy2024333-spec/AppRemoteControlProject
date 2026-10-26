package com.vrxfusion.remote.video

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 视频走独立连接和独立访问码，不复用飞控Token；断流停止，不重发任何飞行指令。 */
class PcVideoStream(private val url: String,private val code: String) : Closeable {
    private val stopped=AtomicBoolean(false)
    private val worker=Executors.newSingleThreadExecutor { runnable -> Thread(runnable,"pc-camera-video").apply { isDaemon=true } }
    @Volatile private var call: Call?=null
    fun start(frame: (ByteArray)->Unit,onError: (String)->Unit) {
        require(videoConnectionIssue(url,code)==null) { "视频地址或访问码无效" }
        worker.execute {
            try {
                val current=http.newCall(Request.Builder().url(url.trim()).header("Authorization","Bearer $code")
                    .header("Accept","multipart/x-mixed-replace").build())
                call=current
                if (stopped.get()) { current.cancel(); return@execute }
                current.execute().use { response ->
                    require(response.code!=401) { "摄像头访问码错误，请使用电脑窗口中的访问码" }
                    require(response.isSuccessful) { "电脑画面服务返回 HTTP ${response.code}" }
                    val boundary=MjpegReader.boundary(response.header("Content-Type").orEmpty())
                    val reader=MjpegReader(response.body?.byteStream() ?: error("画面为空"),boundary)
                    while (!stopped.get()) frame(reader.nextFrame())
                }
            } catch (e: Exception) {
                if (!stopped.get()) onError(if (e is IllegalArgumentException) e.message ?: "视频格式不支持"
                    else "电脑画面已断开，请检查IP、端口及摄像头服务后重新启用")
            }
        }
    }
    override fun close() { stopped.set(true); call?.cancel(); worker.shutdownNow() }
    companion object {
        // 禁止跟随重定向，防止访问码被转发到其他主机；视频解码不会占用飞控发送锁。
        private val http=OkHttpClient.Builder().connectTimeout(3,TimeUnit.SECONDS).readTimeout(5,TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    }
}
