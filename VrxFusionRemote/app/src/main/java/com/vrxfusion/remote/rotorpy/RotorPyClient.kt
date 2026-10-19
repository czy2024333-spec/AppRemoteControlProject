package com.vrxfusion.remote.rotorpy

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

@Serializable
data class WindState(val wind_m_s: List<Double> = emptyList(), val compensation_enabled: Boolean = false,
    val compensation_acceleration_m_s2: List<Double> = emptyList())
@Serializable
data class BoundaryRecovery(val required: Boolean = false, val can_resume: Boolean = false,
    val actual_altitude_m: Double = 0.0, val actual_horizontal_radius_m: Double = 0.0,
    val actual_speed_m_s: Double = 0.0, val min_resume_altitude_m: Double = 0.35,
    val max_resume_altitude_m: Double = 9.95, val max_resume_horizontal_radius_m: Double = 9.95,
    val max_resume_speed_m_s: Double = 0.05)
@Serializable
data class CommandReceipt(val source: String="",val command_id: String="",val status: String="",val reason: String="")

data class CommandFeedback(val id: String,val action: String,val status: String,val reason: String="") {
    fun text(): String = "$action · "+when(status) {
        "SENDING" -> "发送中"
        "RECEIVED" -> "已接收，等待结果"
        "EXECUTING" -> "执行中"
        "COMPLETED" -> "服务端已完成"
        "WAITING_NEUTRAL" -> "等待新通路归中"
        "WAITING_CONFIRMATION" -> "等待确认"
        "CANCELLED" -> "已取消"
        "TIMED_OUT", "EXPIRED" -> "超时，结果待核实"
        "FAILED", "REJECTED" -> "失败：$reason"
        else -> "状态 $status"
    }
}

@Serializable
data class RotorState(val api_version: String = "", val implementation_version: String = "",
    val device_id: String = "sim-drone-1", val boot_id: String = "", val data_source: String = "",
    val server_time_ms: Double = 0.0, val simulation_time_s: Double = 0.0, val sequence: Long = 0,
    val control_epoch: String = "", val mode: String = "HOLD", val protection_reason: String? = null,
    val selected_source: String? = null, val pending_source: String? = null, val active_source: String? = null,
    val position_m: List<Double> = listOf(0.0, 0.0, 1.0), val velocity_m_s: List<Double> = emptyList(),
    val quaternion_xyzw: List<Double> = listOf(0.0, 0.0, 0.0, 1.0), val target_yaw_rad: Double = 0.0,
    val environment: WindState? = null, val boundary_recovery: BoundaryRecovery? = null,
    val last_sequences: Map<String, Long>? = null, val online: Boolean = true,
    val state_age_ms: Double = 0.0, val loss_reason: String? = null,
    val recent_commands: List<CommandReceipt> = emptyList(),val config_version: Int=0) {
    // xyzw、机体到世界，标准 ZYX 欧拉角；修正原包横滚和俯仰互换的问题。
    fun yawRad(): Double = quaternion_xyzw.let { q -> if (q.size < 4) 0.0 else
        atan2(2*(q[3]*q[2]+q[0]*q[1]),1-2*(q[1]*q[1]+q[2]*q[2])) }
    fun rollRad(): Double = quaternion_xyzw.let { q -> if (q.size < 4) 0.0 else
        atan2(2*(q[3]*q[0]+q[1]*q[2]),1-2*(q[0]*q[0]+q[1]*q[1])) }
    fun pitchRad(): Double = quaternion_xyzw.let { q -> if (q.size < 4) 0.0 else
        asin((2*(q[3]*q[1]-q[2]*q[0])).coerceIn(-1.0,1.0)) }
    fun speed() = sqrt(velocity_m_s.sumOf { it*it })
}
class HttpRejection(val code: Int, val reason: String, val errorCode: String? = null) : Exception(reason)
enum class LinkPhase { DISCONNECTED, SESSION, HANDSHAKE, READY }

// 右上轨迹图固定为“上+X、左+Y”，本页右杆必须使用同一世界坐标基准。
// 若用heading，机头转180度后左推会变成世界-Y，图上就会向右移动。
const val APP_STICK_FRAME = "world"

data class StickSnapshot(val rx: Float = 0f, val ry: Float = 0f, val lx: Float = 0f, val ly: Float = 0f) {
    fun valid()=listOf(rx,ry,lx,ly).all { it.isFinite() && it in -1f..1f }
    fun isNeutral() = rx == 0f && ry == 0f && lx == 0f && ly == 0f
    // 触屏x向右为正；后端Y向左为正，因此这里只翻转水平轴一次。竖直推杆已经是向上为正。
    fun axes() = listOf(ry.toDouble(),-rx.toDouble(),ly.toDouble(),-lx.toDouble())
}

/** 单机直联：SSE 只读；用户主动申请时才建会话、切换、真实归中。所有POST串行，运动不重试。 */
class RotorPyClient(var baseUrl: String = "http://192.168.1.100:8765", var token: String = "demo-app-token-2026") {
    private val wire = BackendWire()
    private val sendLock = Mutex()
    val state = MutableStateFlow<RotorState?>(null)
    val connected = MutableStateFlow(false)
    val lastError = MutableStateFlow<String?>(null)
    val holding = MutableStateFlow(false)
    val stale = MutableStateFlow(false)
    val phase = MutableStateFlow(LinkPhase.DISCONNECTED)
    val trail = MutableStateFlow<List<Pair<Double,Double>>>(emptyList())
    val receiveHz = MutableStateFlow(0.0)
    val lastAckMs = MutableStateFlow<Double?>(null)
    val busy = MutableStateFlow(false)
    val commandFeedback = MutableStateFlow<CommandFeedback?>(null)
    val running = MutableStateFlow(false)
    val performance=PerformanceStats()
    val receivedRate=StateRate()
    val renderedRate=StateRate()
    val safetyBusy=MutableStateFlow(false)
    private val operationVersion=AtomicLong(0)
    private val safetyMonitor=Any()
    @Volatile private var safetyKind: String?=null
    var audit: (String,String)->Unit = { _,_-> }
    @Volatile private var activeUrl = ""
    @Volatile private var activeToken = ""
    @Volatile private var generation = 0L
    @Volatile private var lastStateAt = 0L
    @Volatile private var sessionId: String? = null
    @Volatile private var epoch = ""
    @Volatile private var clockOffset = 0L
    @Volatile private var latestStick = StickSnapshot()
    @Volatile private var readyAfterSequence = 0L
    private var sequence = 0L
    private var intent = 0L
    private var streamJob: Job? = null
    private var streamCall: Call? = null
    private var watchJob: Job? = null
    private var heartbeatJob: Job? = null
    private var controlJob: Job? = null
    private var handshakeJob: Job? = null
    private var windowStart = 0L
    private var frames = 0
    private var lastIdentity = ""
    @Volatile private var inputUiRequired=false
    @Volatile private var inputUiPulseAt=0L
    fun attachInputUi() { inputUiRequired=true; inputUiPulseAt=monoMs() }
    fun pulseInputUi() { inputUiPulseAt=monoMs() }
    fun detachInputUi() { inputUiPulseAt=0 }

    fun startPolling(scope: CoroutineScope) {
        if (running.value) return
        connectionIssue(baseUrl,token)?.let { lastError.value = it; return }
        activeUrl = baseUrl.trim().trimEnd('/'); activeToken = token.trim()
        val gen = ++generation
        state.value = null; trail.value = emptyList(); sessionId = null; epoch = ""; commandFeedback.value=null
        lastIdentity=""; frames=0; windowStart=0
        connected.value = false; stale.value = false; phase.value = LinkPhase.DISCONNECTED
        running.value = true; receiveHz.value = 0.0; lastError.value = null
        streamJob = scope.launch(Dispatchers.IO) {
            while (isActive && gen == generation) {
                try {
                    val initial = wire.get(activeUrl,activeToken,"/api/state")["data"] ?: error("状态格式异常")
                    ensureActive()
                    accept(wire.json.decodeFromJsonElement<RotorState>(initial),gen)
                    streamCall = wire.stream(activeUrl,activeToken,"/api/events")
                    wire.readStream(streamCall!!) { if (gen == generation) accept(wire.json.decodeFromString<RotorState>(it),gen) }
                    if (isActive && gen == generation) error("状态流已关闭")
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (gen == generation) {
                        loseControl(); connected.value = false; stale.value = true
                        lastError.value = explainConnection(e)
                    }
                    delay(1500) // 重连只恢复观察，不自动接管。
                }
            }
        }
        watchJob = scope.launch {
            while (isActive) {
                if (connected.value && monoMs()-lastStateAt > 500) {
                    stale.value = true; connected.value = false; receiveHz.value = 0.0
                    loseControl(); lastError.value = "状态超过500ms未更新，已停发；恢复后请重新申请控制"
                    streamCall?.cancel()
                }
                delay(50)
            }
        }
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (sessionId != null && connected.value && !stale.value && !busy.value) {
                    val version=operationVersion.get()
                    try { signed("/api/heartbeat",false) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { if (version==operationVersion.get()) { loseControl(); lastError.value = "心跳失败：${e.message}，请重新申请控制" } }
                }
                delay(100)
            }
        }
    }
    private fun accept(s: RotorState, gen: Long) {
        if (gen != generation) return
        val now = monoMs()
        require(s.position_m.size==3 && s.position_m.all { it.isFinite() } &&
            (s.velocity_m_s.isEmpty() || s.velocity_m_s.size==3 && s.velocity_m_s.all { it.isFinite() }) &&
            s.quaternion_xyzw.size==4 && s.quaternion_xyzw.all { it.isFinite() } &&
            s.quaternion_xyzw.sumOf { it*it } in .5..1.5) { "遥测参数无效" }
        // 服务端明确标记离线或过期时，即使刚收到HTTP/SSE报文也不能当作实时状态。
        if (!s.online || !s.state_age_ms.isFinite() || s.state_age_ms>500 || s.state_age_ms<0) {
            state.value=s; connected.value=false; stale.value=true; receiveHz.value=0.0; loseControl()
            lastError.value="状态超过500ms或设备离线，已停发；恢复后请重新申请控制"
            return
        }
        // 同一步号重发不算新鲜状态，不抬高频率。
        val identity = "${s.boot_id}:${s.sequence}"
        if (lastIdentity == identity) return
        lastIdentity = identity
        if (state.value?.let { it.boot_id != s.boot_id || s.sequence < it.sequence || s.simulation_time_s < it.simulation_time_s } == true) {
            loseControl(); sessionId = null; trail.value = emptyList()
        }
        val previous=state.value
        if (previous?.mode!=s.mode || previous?.selected_source!=s.selected_source || previous?.config_version!=s.config_version)
            audit("STATE_CHANGE","${s.mode}|${s.selected_source ?: "none"}|config=${s.config_version}")
        state.value = s; connected.value = true; stale.value = false; lastStateAt = now
        receivedRate.record(identity,now)
        commandFeedback.value?.let { feedback ->
            s.recent_commands.lastOrNull { it.source=="app" && it.command_id==feedback.id }?.let {
                commandFeedback.value=feedback.copy(status=it.status,reason=it.reason)
            }
        }
        // 连接恢复后清除已解决的传输告警，不能让旧断线提示持续盖住当前状态。
        if (lastError.value?.let { it.startsWith("连接中断") || it.startsWith("状态超过500ms") } == true)
            lastError.value = null
        if (s.server_time_ms > 0) clockOffset = s.server_time_ms.toLong()-System.currentTimeMillis()
        // epoch 只由明确申请和有序回执更新，禁止旧状态覆盖较新的回执。
        // HTTP中立应答可能先于较旧的SSE帧到达；旧的“尚未选中”状态不能撤销刚确认的控制权。
        // 保护始终立即处理；达到确认步号之后的来源变化仍正常撤销控制，不能自动恢复。
        if (s.mode == "PROTECTED" || (phase.value == LinkPhase.READY &&
                s.sequence>readyAfterSequence && (s.control_epoch!=epoch || s.selected_source != "app"))) {
            if (phase.value==LinkPhase.READY) { operationVersion.incrementAndGet(); wire.cancelPending(); audit("CONTROL_REVOKED","STATE_CHANGED") }
            loseControl()
        }
        if (windowStart == 0L) windowStart = now
        frames++
        if (now-windowStart >= 1000) { receiveHz.value = frames*1000.0/(now-windowStart); frames=0; windowStart=now }
        val p = s.position_m
        if (p.size >= 2) {
            val last = trail.value.lastOrNull()
            if (last == null || hypot(last.first-p[0],last.second-p[1]) > 0.005)
                trail.value = (trail.value+(p[0] to p[1])).takeLast(400)
        }
    }
    fun stopPolling() {
        synchronized(safetyMonitor) { safetyKind=null; safetyBusy.value=false }
        operationVersion.incrementAndGet(); wire.cancelPending()
        ++generation; running.value = false
        streamCall?.cancel(); streamCall = null
        streamJob?.cancel(); watchJob?.cancel(); heartbeatJob?.cancel(); handshakeJob?.cancel()
        loseControl(); connected.value = false; stale.value = false; receiveHz.value = 0.0
        lastIdentity=""; frames=0; windowStart=0
    }
    suspend fun disconnect() { stopPolling(); releaseAll() }
    private fun loseControl() {
        controlJob?.cancel(); controlJob=null; holding.value=false
        phase.value = if (sessionId == null) LinkPhase.DISCONNECTED else LinkPhase.HANDSHAKE
        // 不替用户伪造摇杆归中，实际手势结束/取消回调负责更新。
    }
    fun retryHandshake(scope: CoroutineScope) {
        if (busy.value || safetyBusy.value) return
        busy.value=true
        val gen=generation
        val version=operationVersion.get()
        val allowed={ version==operationVersion.get() && gen==generation && !safetyBusy.value }
        handshakeJob=scope.launch(Dispatchers.IO) {
            try {
                require(connected.value && !stale.value) { "请先连接并等待实时状态" }
                require(latestStick.isNeutral()) { "请先松开双摇杆" }
                require(state.value?.mode in setOf("HOLD","ACTIVE")) { "当前运行模式不允许接管，请先确认状态或解除保护" }
                loseControl()
                sendLock.withLock {
                    if (sessionId == null) {
                        phase.value=LinkPhase.SESSION
                        val d=wire.post(activeUrl,activeToken,"/api/session",buildJsonObject { },allowed)["data"]!!.jsonObject
                        sessionId=d["session_id"]!!.jsonPrimitive.content; sequence=0
                    }
                    epoch=wire.get(activeUrl,activeToken,"/api/state",allowed)["data"]!!.jsonObject["control_epoch"]!!.jsonPrimitive.content
                }
                phase.value=LinkPhase.HANDSHAKE
                check(allowed()) { "操作已被安全动作中断" }
                signed("/api/switch",true,buildJsonObject { put("target_source","app"); put("user_initiated",true) },version)
                require(latestStick.isNeutral()) { "握手期间有输入，请松手后重新申请" }
                signed("/api/neutral",true,buildJsonObject { put("axes",JsonArray(latestStick.axes().map(::JsonPrimitive))) },version)
                ensureActive()
                check(allowed()) { "连接或安全状态已变化，请重新申请" }
                phase.value=LinkPhase.READY; lastError.value=null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (allowed()) { loseControl(); lastError.value="申请控制失败：${e.message}" } }
            finally { busy.value=false }
        }
    }
    fun onStick(snapshot: StickSnapshot, scope: CoroutineScope) {
        if (!snapshot.valid()) { loseControl(); lastError.value="摇杆参数无效，已停发"; audit("INVALID_INPUT","RANGE_OR_NONFINITE")
            scope.launch(Dispatchers.IO) { stop() }; return }
        val wasNeutral=latestStick.isNeutral(); latestStick=snapshot
        if (snapshot.isNeutral()) {
            if (controlJob?.isActive == true) {
                controlJob?.cancel(); controlJob=null; holding.value=false
                scope.launch(Dispatchers.IO) { if (!action("/api/stop")) loseControl() }
            }
            return
        }
        if (!connected.value || stale.value || safetyBusy.value || state.value?.mode !in setOf("HOLD","ACTIVE") || phase.value != LinkPhase.READY) return
        if (controlJob?.isActive == true || !wasNeutral) return
        intent++; holding.value=true
        val version=operationVersion.get()
        controlJob=scope.launch(Dispatchers.IO) {
            try {
                while (isActive && phase.value == LinkPhase.READY && !latestStick.isNeutral()) {
                    val start=monoMs() // 本轮有效指令生成时刻，包含等待发送锁的时间。
                    val generatedAtNs=System.nanoTime()
                    // IO线程不能在界面冻结后无限续发旧杆值；界面帧心跳失效必须停止并重新申请。
                    if (inputUiRequired && start-inputUiPulseAt>500) {
                        audit("UI_INPUT_EXPIRED","NO_UI_FRAME_500MS")
                        loseControl(); lastError.value="界面更新中断，运动已停发，请重新申请控制"
                        scope.launch(Dispatchers.IO) { stop() }
                        break
                    }
                    // 获取锁后再采样，已松手或已失效的旧快照不允许排队补发。
                    sendLock.withLock {
                        if (!isActive || latestStick.isNeutral() || phase.value != LinkPhase.READY || version!=operationVersion.get()) return@withLock
                        signedLocked("/api/input",false,buildJsonObject { put("intent_sequence",intent); put("frame",APP_STICK_FRAME)
                            put("axes",JsonArray(latestStick.axes().map(::JsonPrimitive))) },version,generatedAtNs)
                    }
                    delay((50-(monoMs()-start)).coerceAtLeast(1))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (version==operationVersion.get()) { loseControl(); lastError.value="控制已停发：${e.message}；请松手并重新申请" } }
            finally { holding.value=false }
        }
    }
    suspend fun protect() { safetyAction("/api/protect") }
    suspend fun stop() { safetyAction("/api/stop") }
    /** 保护可抢占停止，停止不能取消正在提交的保护；取消普通HTTP后仍串行下发安全动作。 */
    private suspend fun safetyAction(path: String)=withContext(Dispatchers.IO) {
        val version=synchronized(safetyMonitor) {
            if (safetyKind=="/api/protect" || safetyKind==path) return@withContext
            safetyKind=path; safetyBusy.value=true; operationVersion.incrementAndGet()
        }
        audit("SAFETY_REQUEST",if (path.endsWith("protect")) "PROTECT" else "STOP")
        handshakeJob?.cancel(); loseControl(); wire.cancelPending()
        val allowed={ version==operationVersion.get() }
        try {
            sendLock.withLock {
                check(allowed() && activeUrl.isNotEmpty()) { "请先连接后端" }
                // 安全动作可以在状态流过期时尝试提交，不能被普通busy状态丢弃。
                if (sessionId==null) {
                    val d=wire.post(activeUrl,activeToken,"/api/session",buildJsonObject {},allowed)["data"]!!.jsonObject
                    sessionId=d["session_id"]!!.jsonPrimitive.content; sequence=0
                }
                epoch=wire.get(activeUrl,activeToken,"/api/state",allowed)["data"]!!.jsonObject["control_epoch"]!!.jsonPrimitive.content
                signedLocked(path,true,buildJsonObject {},version)
            }
            if (allowed()) lastError.value=null
        } catch (e: Exception) {
            if (allowed()) { lastError.value="安全操作未确认，请检查链路；运动已停发"; audit("SAFETY_FAILURE",e.javaClass.simpleName) }
        } finally { synchronized(safetyMonitor) { if (allowed()) { safetyKind=null; safetyBusy.value=false } } }
    }
    suspend fun resume(scope: CoroutineScope) {
        if (!latestStick.isNeutral()) { lastError.value="请先松开双摇杆"; return }
        val b=state.value?.boundary_recovery
        if (b?.required == true && !b.can_resume) { lastError.value="请等待进入安全区并停稳"; return }
        loseControl()
        // 解除只恢复 HOLD，随后需要用户主动申请控制。
        action("/api/resume",buildJsonObject { put("neutral_confirmed",true) })
    }
    suspend fun releaseAll() = withContext(NonCancellable+Dispatchers.IO) {
        if (sessionId != null) {
            runCatching { signed("/api/stop",true) }
            runCatching { signed("/api/disconnect",true,buildJsonObject { put("reason","APP_EXIT") }) }
        }
        sendLock.withLock { sessionId=null; epoch=""; latestStick=StickSnapshot(); phase.value=LinkPhase.DISCONNECTED }
    }
    private suspend fun action(path: String, extra: JsonObject=buildJsonObject { }): Boolean = withContext(Dispatchers.IO) {
        if (busy.value || safetyBusy.value) return@withContext false
        val gen=generation
        val version=operationVersion.get()
        val allowed={ version==operationVersion.get() && gen==generation && !safetyBusy.value }
        busy.value=true
        try {
            sendLock.withLock {
                check(allowed() && connected.value && !stale.value) { "请先连接并等待实时状态" }
                if (sessionId == null) {
                    val d=wire.post(activeUrl,activeToken,"/api/session",buildJsonObject { },allowed)["data"]!!.jsonObject
                    sessionId=d["session_id"]!!.jsonPrimitive.content; sequence=0; epoch=state.value?.control_epoch.orEmpty()
                }
                // 用户明确按下离散操作时获取当前轮次，运动流绝不以此方式刷新后重试。
                epoch=wire.get(activeUrl,activeToken,"/api/state",allowed)["data"]!!.jsonObject["control_epoch"]!!.jsonPrimitive.content
                signedLocked(path,true,extra,version)
            }
            if (allowed()) { lastError.value=null; true } else false
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (allowed()) lastError.value="操作失败：${e.message}"; false }
        finally { busy.value=false }
    }
    suspend fun switchTo(scope: CoroutineScope,target: String,userConfirmed: Boolean=false): Boolean {
        if (!userConfirmed || target !in setOf("app","ring","voice")) {
            lastError.value="切换通路需用户确认且目标必须可用"; audit("SWITCH_REJECTED","NOT_CONFIRMED_OR_INVALID"); return false
        }
        audit("SWITCH_REQUEST",target); loseControl(); return action("/api/switch",
        buildJsonObject { put("target_source",target); put("user_initiated",true) }) }
    suspend fun cancel(targetSource: String,targetCommandId: String?=null)=action("/api/cancel",buildJsonObject {
        put("target_source",targetSource); targetCommandId?.let { put("target_command_id",it) } })
    suspend fun applyConfig(scope: CoroutineScope,version: Int,patch: Map<String,Double>,userConfirmed: Boolean=false): Boolean {
        if (!userConfirmed) { lastError.value="控制参数更新需用户明确确认"; audit("CONFIG_REJECTED","NOT_CONFIRMED"); return false }
        val limits=mapOf("sensitivity" to .1..1.0,"deadzone" to 0.0..0.4,
            "max_horizontal_speed_m_s" to .05..0.5,"max_vertical_speed_m_s" to .05..0.5,
            "max_yaw_rate_rad_s" to .05..0.5,"input_ttl_ms" to 50.0..500.0)
        if (version<1 || patch.isEmpty() || patch.any { (k,v)-> !v.isFinite() || limits[k]?.contains(v)!=true }) {
            lastError.value="控制参数字段或范围无效"; audit("CONFIG_REJECTED","INVALID_PATCH"); return false
        }
        audit("CONFIG_REQUEST","USER_CONFIRMED|v=$version|"+patch.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" })
        loseControl(); return action("/api/config",buildJsonObject { put("config_version",version)
            put("patch",JsonObject(patch.mapValues { JsonPrimitive(it.value) })) })
    }
    private suspend fun signed(path: String,discrete: Boolean,extra: JsonObject=buildJsonObject { },version: Long=operationVersion.get()) =
        sendLock.withLock { signedLocked(path,discrete,extra,version) }
    private fun signedLocked(path: String,discrete: Boolean,extra: JsonObject,version: Long=operationVersion.get(),generatedAt: Long=System.nanoTime()) {
        check(version==operationVersion.get()) { "操作已被安全动作中断" }
        val sid=sessionId ?: error("尚未建立控制会话")
        val start=monoMs()
        val commandId=if (discrete) UUID.randomUUID().toString() else null
        val actionName=mapOf("/api/stop" to "停止","/api/protect" to "保护","/api/resume" to "解除保护",
            "/api/switch" to "切换通路","/api/neutral" to "归中确认","/api/config" to "控制配置",
            "/api/cancel" to "取消","/api/disconnect" to "释放控制")[path] ?: path
        if (commandId!=null) commandFeedback.value=CommandFeedback(commandId,actionName,"SENDING")
        try {
            val r=wire.post(activeUrl,activeToken,path,buildJsonObject {
                put("device_id",state.value?.device_id ?: "sim-drone-1"); put("session_id",sid); put("control_epoch",epoch)
                put("command_sequence",++sequence); put("ttl_ms",500); put("sent_at_ms",System.currentTimeMillis()+clockOffset-20)
                if (commandId!=null) put("command_id",commandId)
                extra.forEach { (k,v)->put(k,v) }
            },{ version==operationVersion.get() })
            check(version==operationVersion.get()) { "旧操作回执已失效" }
            if (path=="/api/input") performance.record((System.nanoTime()-generatedAt)/1_000_000.0,true)
            r["data"]?.jsonObject?.get("control_epoch")?.jsonPrimitive?.content?.let { epoch=it }
            // 同一步内可能先发布旧SSE再返回较新的HTTP回执；保护立即生效，其余控制变化等更新步号确认。
            r["data"]?.jsonObject?.get("sequence")?.jsonPrimitive?.longOrNull
                ?.let { readyAfterSequence=it }
            if (path != "/api/heartbeat") lastAckMs.value=(monoMs()-start).toDouble()
            // HTTP成功只表示接收；完成状态必须来自服务端明确的command_status。
            if (commandId!=null) commandFeedback.value=CommandFeedback(commandId,actionName,
                r["data"]?.jsonObject?.get("command_status")?.jsonPrimitive?.content ?: "RECEIVED")
        } catch (e: Exception) {
            if (path=="/api/input") performance.record((System.nanoTime()-generatedAt)/1_000_000.0,false)
            if (version!=operationVersion.get()) throw e
            audit("COMMAND_FAILURE",e.javaClass.simpleName)
            if (commandId!=null) commandFeedback.value=CommandFeedback(commandId,actionName,
                if (e is java.io.InterruptedIOException) "TIMED_OUT" else "FAILED",e.message.orEmpty())
            if (e is HttpRejection && e.errorCode in setOf("STALE_SESSION","DISCONNECTED")) sessionId=null
            throw e
        }
    }
}
fun monoMs()=System.nanoTime()/1_000_000
fun connectionIssue(url: String,token: String): String? {
    val u=runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return "后端地址格式不正确"
    if (u.scheme !in setOf("http","https") || u.host.isNullOrBlank() || !u.rawUserInfo.isNullOrEmpty() ||
        !u.rawQuery.isNullOrEmpty() || !u.rawFragment.isNullOrEmpty() || u.path !in listOf("","/") ||
        (u.port != -1 && u.port !in 1..65535)) return "地址应为 http://电脑IPv4:8765，不要填写接口路径"
        if (token.trim().isEmpty() || token.trim().any { it.isWhitespace() || it.code !in 33..126 })
        return "请填写来源 Token，不要加 Bearer 前缀"
    return null
}
fun explainConnection(e: Exception): String=when {
    e is HttpRejection && e.code == 401 -> "Token无效（401），请填写APP来源Token"
    e is HttpRejection && e.code == 404 -> "接口不匹配（404）：操纵页需要 --mode single；三机请用集群监控"
    else -> "连接中断：${e.message ?: "请检查电脑IP、局域网及端口"}"
}
/** HTTP共用层：不重试控制请求，不跟随重定向，Token不写入URL和日志。 */
class BackendWire {
    val json=Json { ignoreUnknownKeys=true }
    private val http=OkHttpClient.Builder().connectTimeout(2,TimeUnit.SECONDS).readTimeout(2,TimeUnit.SECONDS)
        .callTimeout(2500,TimeUnit.MILLISECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    private val streaming=http.newBuilder().readTimeout(0,TimeUnit.MILLISECONDS).callTimeout(0,TimeUnit.MILLISECONDS).build()
    private val pending=mutableSetOf<Call>()
    fun cancelPending() { synchronized(pending) { pending.forEach { it.cancel() } } }
    fun get(url: String,token: String,path: String,allowed: ()->Boolean={true})=execute(Request.Builder().url(url+path).header("Authorization","Bearer $token").build(),allowed)
    fun post(url: String,token: String,path: String,body: JsonObject,allowed: ()->Boolean={true})=execute(Request.Builder().url(url+path)
        .header("Authorization","Bearer $token").post(body.toString().toRequestBody("application/json".toMediaType())).build(),allowed)
    private fun execute(request: Request,allowed: ()->Boolean): JsonObject {
        val call=http.newCall(request)
        synchronized(pending) { check(allowed()) { "操作已被安全动作中断" }; pending.add(call) }
        try { return call.execute().use { r ->
        val body=r.body?.source()
        require(body==null || !body.request(1_048_577)) { "HTTP应答超过1MB" }
        val d=runCatching { json.parseToJsonElement(body?.readUtf8().orEmpty()).jsonObject }.getOrNull()
        if (!r.isSuccessful || d?.get("ok")?.jsonPrimitive?.booleanOrNull != true)
            throw HttpRejection(r.code,d?.get("error")?.jsonPrimitive?.content ?: "HTTP ${r.code}",d?.get("error_code")?.jsonPrimitive?.content)
        d
        } } finally { synchronized(pending) { pending.remove(call) } }
    }
    fun stream(url: String,token: String,path: String): Call=streaming.newCall(Request.Builder().url(url+path)
        .header("Authorization","Bearer $token").header("Accept","text/event-stream").build())
    fun readStream(call: Call,receive: (String)->Unit) {
        call.execute().use { r ->
            if (!r.isSuccessful) throw HttpRejection(r.code,"状态订阅失败 HTTP ${r.code}")
            require(r.header("Content-Type").orEmpty().startsWith("text/event-stream")) { "服务未返回事件流" }
            val source=r.body?.source() ?: error("状态流为空")
            val lines=mutableListOf<String>()
            var eventSize=0
            while (!call.isCanceled() && !source.exhausted()) {
                val line=source.readUtf8LineStrict(65536)
                if (line.isEmpty()) { if (lines.isNotEmpty()) receive(lines.joinToString("\n")); lines.clear(); eventSize=0 }
                else if (line.startsWith("data:")) {
                    eventSize+=line.length; require(eventSize<=262144) { "状态事件超过256KB" }
                    lines+=line.substring(5).trimStart()
                }
            }
        }
    }
}
