package com.vrxfusion.remote.diagnostics

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.Handler
import android.os.Looper
import com.vrxfusion.remote.rotorpy.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class DiagnosticView(val active: Boolean=false,val elapsedS: Long=0,val foregroundS: Long=0,
    val p95Ms: Double?=null,val samples: Long=0,val failed: Long=0,val receiveHz: Double=0.0,
    val renderHz: Double=0.0,val maxMainDelayMs: Long=0,val interruptions: Long=0,val lostRecords: Long=0,
    val note: String="尚未开始；4小时联调待执行")

/** 仅记录前端证据，不生成操纵指令。资源/异常JSONL落在APP私有目录，可用文件选择器导出。 */
class FlightDiagnostics(private val context: Context,private val scope: CoroutineScope) {
    private val prefs=context.getSharedPreferences("flight_diagnostics",Context.MODE_PRIVATE)
    val view=MutableStateFlow(DiagnosticView(note=if (prefs.getBoolean("active",false))
        "上次记录未正常结束，请导出记录核查；4小时验收未通过审核" else "尚未开始；4小时联调待执行"))
    var conditions=""
    private var currentFile: File?=prefs.getString("file",null)?.let { File(context.filesDir,it) }
    private var ticker: Job?=null
    private var writer: Job?=null
    private var records: Channel<String>?=null
    private var started=0L; private var fgMs=0L; private var lastTick=0L
    @Volatile private var foreground=true
    private val lost=AtomicLong(0); private val maxDelay=AtomicLong(0)
    private val probePending=AtomicBoolean(false)
    private val handler=Handler(Looper.getMainLooper())
    private var interruptions=0L
    private var lastSignature=""
    private var lastConnected=true
    private var client: RotorPyClient?=null

    fun foreground(value: Boolean) {
        if (foreground==value) return
        foreground=value
        if (view.value.active) {
            if (!value) interruptions++
            event("LIFECYCLE",if (value) "FOREGROUND" else "BACKGROUND")
        }
    }
    fun event(type: String,detail: String) {
        if (!view.value.active) return
        emit(buildJsonObject {
            put("type","event"); put("elapsed_ms",monoMs()-started)
            // 只接收内部事件码，不写Token、URL、指令正文或语音文本。
            put("event",type.take(64)); put("code",detail.take(512))
        })
    }
    private fun emit(record: JsonObject) { if (records?.trySend(record.toString())?.isSuccess!=true) lost.incrementAndGet() }
    fun start(c: RotorPyClient) {
        if (view.value.active) return
        client=c; started=monoMs(); lastTick=started; fgMs=0; interruptions=0; lost.set(0); maxDelay.set(0)
        c.performance.reset(); c.receivedRate.reset(); c.renderedRate.reset()
        handler.removeCallbacksAndMessages(null); probePending.set(false)
        lastSignature=""; lastConnected=c.connected.value
        val file=File(context.filesDir,"flight-diagnostics-${System.currentTimeMillis()}.jsonl")
        currentFile=file
        val channel=Channel<String>(128); records=channel
        prefs.edit().putBoolean("active",true).putString("file",file.name).apply()
        writer=scope.launch(Dispatchers.IO) {
            try { file.bufferedWriter().use { output ->
                var bytes=0L
                for (line in channel) {
                    bytes+=line.toByteArray(Charsets.UTF_8).size+1
                    if (bytes<=16*1024*1024) { output.appendLine(line); output.flush() } else lost.incrementAndGet()
                }
            } } catch (_: Exception) { lost.incrementAndGet(); channel.close() }
        }
        view.value=DiagnosticView(active=true,note="正在记录；4小时验收需结合手机、服务端及仿真日志审核")
        emit(buildJsonObject {
            put("type","start"); put("app_version","0.9.0"); put("time_utc_ms",System.currentTimeMillis())
            put("terminal","${Build.MANUFACTURER} ${Build.MODEL}"); put("android_api",Build.VERSION.SDK_INT)
            put("screen_px","${context.resources.displayMetrics.widthPixels}x${context.resources.displayMetrics.heightPixels}")
            put("conditions",conditions.take(300)); put("baseline_successes",c.performance.snapshot().successes)
            put("latency_definition","valid_motion_generated_to_http_ok_including_send_lock_wait; rounded_up_1ms; voice_and_model_not_measured")
        })
        ticker=scope.launch(Dispatchers.Default) {
            var tick=0
            var previousCpu=Process.getElapsedCpuTime(); var previousCpuAt=monoMs()
            while (isActive) {
                delay(1000)
                val now=monoMs()
                if (foreground) fgMs+=now-lastTick
                lastTick=now
                if (foreground && probePending.compareAndSet(false,true)) {
                    val posted=monoMs()
                    handler.post { maxDelay.accumulateAndGet(monoMs()-posted,::maxOf); probePending.set(false) }
                }
                val s=c.state.value
                val signature="${s?.mode}|${s?.selected_source}|${s?.control_epoch}|${s?.config_version}"
                if (signature!=lastSignature) { event("MODE_SOURCE_CONFIG",signature); lastSignature=signature }
                if (lastConnected && !c.connected.value) event("STATE_LOST","STALE_OR_DISCONNECTED")
                lastConnected=c.connected.value
                val ack=c.performance.snapshot()
                val receive=c.receivedRate.hz(now); val render=c.renderedRate.hz(now)
                view.value=DiagnosticView(true,(now-started)/1000,fgMs/1000,ack.p95Ms,ack.successes,ack.failures,
                    receive,render,maxDelay.get(),interruptions,lost.get(),
                    if (now-started>=14_400_000) "记录已达4小时；须审核异常、控制有效性与各端日志，尚未自动验收" else "正在记录；4小时联调待完成")
                if (++tick%10==0) {
                    val cpu=Process.getElapsedCpuTime(); val cpuAt=monoMs()
                    emit(buildJsonObject {
                        put("type","sample"); put("elapsed_s",view.value.elapsedS); put("foreground_s",view.value.foregroundS)
                        put("ack_successes_total",ack.successes); put("ack_failures_total",ack.failures)
                        ack.p95Ms?.let { put("ack_p95_upper_ms",it) }; put("receive_hz",receive); put("compose_commit_hz",render)
                        put("pss_kb",Debug.getPss()); put("java_heap_bytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
                        put("cpu_one_core_percent",(cpu-previousCpu)*100.0/maxOf(1,cpuAt-previousCpuAt))
                        put("main_queue_max_delay_ms",maxDelay.get()); put("background_entries",interruptions); put("lost_records",lost.get())
                    })
                    previousCpu=cpu; previousCpuAt=cpuAt
                }
            }
        }
    }
    suspend fun finish() {
        if (!view.value.active) return
        ticker?.cancelAndJoin()
        emit(buildJsonObject { put("type","end"); put("elapsed_s",(monoMs()-started)/1000); put("lost_records",lost.get())
            put("acceptance","PENDING_JOINT_REVIEW") })
        records?.close(); writer?.join()
        handler.removeCallbacksAndMessages(null); probePending.set(false)
        prefs.edit().putBoolean("active",false).apply()
        view.value=view.value.copy(active=false,note="记录已结束，导出后结合服务端/仿真记录审核")
    }
    suspend fun exportTo(output: java.io.OutputStream) = withContext(Dispatchers.IO) {
        val file=currentFile ?: error("没有可导出的运行记录")
        // 导出开始时固定长度，运行中追加记录不影响本次快照的边界。
        var remaining=file.length()
        file.inputStream().use { input ->
            val bytes=ByteArray(8192)
            while (remaining>0) {
                val n=input.read(bytes,0,minOf(bytes.size.toLong(),remaining).toInt())
                if (n<0) break
                output.write(bytes,0,n); remaining-=n
            }
        }
    }
    fun close() { ticker?.cancel(); records?.close(); handler.removeCallbacksAndMessages(null) } // 异常结束保留active标记。
}
