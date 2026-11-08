package com.vrxfusion.remote.logs

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class LogConnection(val url: String,val token: String) {
    override fun toString()="LogConnection(凭据隐藏)"
}
data class LogFilter(val device: String="",val event: String="",val sinceMs: Long?=null,val untilMs: Long?=null) {
    init {
        require(device.length<=64 && event.length<=128) { "设备或事件名称过长" }
        require(sinceMs==null || sinceMs>=0); require(untilMs==null || untilMs>=0)
        require(sinceMs==null || untilMs==null || sinceMs<=untilMs) { "开始时间不得晚于结束时间" }
    }
    fun parameters()=buildMap {
        if (device.isNotBlank()) put("device_id",device)
        if (event.isNotBlank()) put("event",event)
        sinceMs?.let { put("since_ms",it.toString()) }; untilMs?.let { put("until_ms",it.toString()) }
    }
}
data class LogPage(val events: List<JsonObject>,val total: Long,val offset: Int,val nextOffset: Int?,
    val snapshot: String?,val caughtUp: Boolean,val pendingTail: Int,val complete: Boolean)
data class LogDownload(val file: File,val format: String,val bytes: Long,val rows: Long,val pendingTail: Int,
    val snapshot: String?)
class LogIssue(val code: String,val requestId: String?=null): IOException(code)

/** 日志独立使用只读HTTP客户端；重试、限额和错误不触碰飞控会话及其网络参数。 */
class ServerLogsClient(private val exportLimit: Long=16L*1024*1024) {
    private val http=OkHttpClient.Builder().connectTimeout(3,TimeUnit.SECONDS)
        .readTimeout(30,TimeUnit.SECONDS).callTimeout(35,TimeUnit.SECONDS)
        .retryOnConnectionFailure(true).followRedirects(false).followSslRedirects(false).build()
    private val json=Json { ignoreUnknownKeys=true }
    private fun JsonObject.text(key: String)=(this[key] as? JsonPrimitive)?.contentOrNull
    private fun url(c: LogConnection,path: String,params: Map<String,String>)=
        (c.url.trim().trimEnd('/')+path).toHttpUrl().newBuilder().apply {
            params.forEach { (k,v)->addQueryParameter(k,v) }
        }.build()
    private suspend fun <T> get(c: LogConnection,path: String,params: Map<String,String>,read: (Response)->T): T =
        suspendCancellableCoroutine { continuation ->
            val call=http.newCall(Request.Builder().url(url(c,path,params))
                .header("Authorization","Bearer ${c.token.trim()}").build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object: Callback {
                override fun onFailure(call: Call,e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call,response: Response) {
                    try {
                        val result=response.use {
                            if (!continuation.isActive) throw IOException("日志操作已取消")
                            if (!it.isSuccessful) {
                                val raw=it.body?.byteStream()?.use { input->runCatching { boundedBytes(input,64L*1024) }.getOrNull() }
                                val detail=raw?.let { bytes->runCatching { json.parseToJsonElement(bytes.decodeToString()).jsonObject }.getOrNull() }
                                throw LogIssue(detail?.text("error_code") ?: "HTTP_${it.code}",detail?.text("request_id"))
                            }
                            read(it)
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch(e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }

    suspend fun query(c: LogConnection,filter: LogFilter,offset: Int=0,snapshot: String?=null): LogPage {
        require(offset in 0..100000)
        val params=filter.parameters()+mapOf("limit" to "20","offset" to offset.toString())+
            (snapshot?.let { mapOf("snapshot" to it) } ?: emptyMap())
        return get(c,"/api/v1/logs",params) { response ->
            val body=response.body ?: throw LogIssue("LOG_BAD_RESPONSE")
            if (body.contentLength()>3L*1024*1024) throw LogIssue("LOG_PAGE_TOO_LARGE")
            val bytes=try { body.byteStream().use { boundedBytes(it,3L*1024*1024) } }
                catch(e: LogIssue) { throw LogIssue("LOG_PAGE_TOO_LARGE") }
            val result=json.parseToJsonElement(bytes.decodeToString()).jsonObject
            require(result["ok"]?.jsonPrimitive?.booleanOrNull==true) { "日志应答无效" }
            val d=result["data"]!!.jsonObject
            require(d.text("contract_version")=="1.0") { "日志契约不兼容" }
            val events=d["events"]!!.jsonArray.map { it.jsonObject }
            require(events.size<=20) { "日志页超出请求数量" }
            val actualOffset=d.text("offset")!!.toInt()
            val total=d.text("total")!!.toLong()
            require(actualOffset==offset && total>=0)
            val token=d.text("snapshot")
            if (snapshot!=null && token!=snapshot) throw LogIssue("LOG_SNAPSHOT_EXPIRED")
            val more=d["has_more"]?.jsonPrimitive?.booleanOrNull==true
            val next=if (more && token!=null) d.text("next_offset")?.toInt() else null
            require(!more || token==null || next!=null && next==offset+events.size && next>offset)
            val integrity=d["integrity"] as? JsonObject
            val invalid=integrity?.text("invalid_rows")?.toLongOrNull() ?: 0
            if (invalid>0) throw LogIssue("LOG_DATA_CORRUPT")
            LogPage(events,total,offset,next,token,
                (d["index"] as? JsonObject)?.get("caught_up")?.jsonPrimitive?.booleanOrNull ?: false,
                integrity?.text("pending_tail")?.toIntOrNull() ?: 0,
                integrity?.get("complete")?.jsonPrimitive?.booleanOrNull ?: false)
        }
    }

    /** 读取前检查Content-Length，读取中逐块限额；未完整成功的临时文件立即删除。 */
    suspend fun download(c: LogConnection,filter: LogFilter,page: LogPage,format: String,file: File): LogDownload {
        require(format in setOf("jsonl","csv"))
        val params=filter.parameters()+mapOf("format" to format)+
            (page.snapshot?.let { mapOf("snapshot" to it) } ?: emptyMap())
        try {
            return get(c,"/api/v1/logs/export",params) { response ->
                val body=response.body ?: throw LogIssue("LOG_BAD_RESPONSE")
                val type=body.contentType()?.toString().orEmpty().lowercase()
                if (!(if (format=="csv") type.startsWith("text/csv") else type.startsWith("application/x-ndjson")))
                    throw LogIssue("LOG_BAD_RESPONSE")
                val length=body.contentLength()
                if (length>exportLimit) throw LogIssue("LOG_EXPORT_TOO_LARGE")
                val snapshot=response.header("X-Log-Snapshot")
                if (page.snapshot!=null && page.snapshot!=snapshot) throw LogIssue("LOG_SNAPSHOT_EXPIRED")
                val rows=response.header("X-Log-Rows")?.toLongOrNull() ?: page.total
                require(rows==page.total) { "导出条数与查询快照不一致" }
                val bytes=file.outputStream().use { out->body.byteStream().use { input->copyBounded(input,out,exportLimit) } }
                if (length>=0 && bytes!=length) throw LogIssue("LOG_DOWNLOAD_INCOMPLETE")
                LogDownload(file,format,bytes,rows,response.header("X-Log-Pending-Tail")?.toIntOrNull() ?: 0,snapshot)
            }
        } catch (e: Throwable) { file.delete(); throw e }
    }
    fun close() { http.dispatcher.cancelAll(); http.connectionPool.evictAll() }
}

/** 不调用body.bytes()；含未知长度/分块响应时也只允许多读取一个字节来识别越界。 */
internal fun copyBounded(input: InputStream,out: OutputStream,limit: Long): Long {
    var total=0L; val buffer=ByteArray(64*1024)
    while (true) {
        val n=input.read(buffer,0,minOf(buffer.size.toLong(),limit-total+1).toInt())
        if (n<0) return total
        if (total+n>limit) throw LogIssue("LOG_EXPORT_TOO_LARGE")
        out.write(buffer,0,n); total+=n
    }
}
private fun boundedBytes(input: InputStream,limit: Long)=java.io.ByteArrayOutputStream().use {
    copyBounded(input,it,limit); it.toByteArray()
}

fun logIssueText(e: Throwable): String {
    val issue=e as? LogIssue
    val text=when(issue?.code) {
        "LOG_INDEX_BUILDING" -> "日志索引正在建立，请稍后重新查询；飞控连接不受此提示影响。"
        "LOG_BUSY" -> "服务端日志正在处理其他操作，请稍后重试。"
        "LOG_SNAPSHOT_EXPIRED" -> "日志快照已失效，请重新查询第一页。"
        "LOG_DATA_CORRUPT" -> "服务端日志存在损坏，请保留原始文件并交由维护人员排查；未导出部分结果。"
        "LOG_EXPORT_TOO_LARGE" -> "导出超过16MiB上限，请缩小设备或时间范围。"
        "LOG_READ_FAILED" -> "服务端日志读取失败，请检查磁盘或文件权限。"
        "LOG_INDEX_UNAVAILABLE","LOG_QUERY_TIMEOUT" -> "日志索引暂不可用或查询超时，请稍后重试或缩小范围。"
        "INVALID_ARGUMENT" -> "日志筛选参数不合法，请检查设备、事件和时间范围。"
        "LOG_DOWNLOAD_INCOMPLETE" -> "日志下载不完整，未生成可保存文件，请重新导出。"
        "HTTP_401","HTTP_403" -> "日志访问被拒绝，请在连接管理中确认身份和权限。"
        "HTTP_404" -> "后端尚未提供日志接口。"
        "LOG_BAD_RESPONSE","LOG_PAGE_TOO_LARGE" -> "日志响应格式或大小异常，本次操作已停止。"
        else -> "日志操作未完成，请检查连接后重试；未改变飞行控制权。"
    }
    val id=issue?.requestId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,80}")) }
    return text+(issue?.code?.takeIf { it.matches(Regex("[A-Z0-9_]{1,80}")) }?.let { " [$it]" } ?: "")+
        (id?.let { " 请求编号：$it" } ?: "")
}
