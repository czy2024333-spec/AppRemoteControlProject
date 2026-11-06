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
import com.vrxfusion.remote.unified.ControlLimits
import com.vrxfusion.remote.unified.UnifiedReceipt

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

data class CommandFeedback(val id: String,val action: String,val status: String,val reason: String="",
    val completionScope: String?=null,val deviceId: String?=null,val rawCode: String?=null,
    val succeeded: Int?=null,val failed: Int?=null) {
    fun text(): String = "$action · "+when(status) {
        "SENDING" -> "发送中"
        "RECEIVED" -> "已接收，等待结果"
        "EXECUTING" -> "执行中"
        "COMPLETED" -> if (completionScope=="SERVICE_APPLIED") "服务已应用，运动结果以状态为准" else "服务端已完成"
        "PARTIAL" -> "部分成功：成功${succeeded ?: 0}，失败${failed ?: 0}"
        "OUTCOME_UNKNOWN" -> "结果未知，请查询原命令"
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
    val recent_commands: List<CommandReceipt> = emptyList(),val config_version: Int=0,
    val control_config: ControlLimits?=null) {
    // xyzw、机体到世界，标准 ZYX 欧拉角；修正原包横滚和俯仰互换的问题。
    fun yawRad(): Double = quaternion_xyzw.let { q -> if (q.size < 4) 0.0 else
        atan2(2*(q[3]*q[2]+q[0]*q[1]),1-2*(q[1]*q[1]+q[2]*q[2])) }
    fun rollRad(): Double = quaternion_xyzw.let { q -> if (q.size < 4) 0.0 else
        atan2(2*(q[3]*q[0]+q[1]*q[2]),1-2*(q[0]*q[0]+q[1]*q[1])) }
    fun pitchRad(): Double = quaternion_xyzw.let { q -> if (q.size < 4) 0.0 else
        asin((2*(q[3]*q[1]-q[2]*q[0])).coerceIn(-1.0,1.0)) }
    fun speed() = sqrt(velocity_m_s.sumOf { it*it })
}
class HttpRejection(val code: Int,val reason: String,val errorCode: String?=null,
    val category: String?=null,val retryPolicy: String?=null,val requestId: String?=null) : Exception(reason)
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
    @Volatile var expectedDeviceId: String?=null
    @Volatile var unifiedReceiptQueries=false
    private data class DeviceRoute(val deviceId: String,val clusterId: String)
    @Volatile private var deviceRoute: DeviceRoute?=null
    /** 只在旧会话已释放后绑定；飞机编号不能随摇杆输入或地图选择而改变。 */
    fun configureDeviceRoute(deviceId: String?,clusterId: String?) {
        check(!running.value && sessionId==null) { "先停止并释放旧会话再更换操纵飞机" }
        deviceRoute=if (clusterId==null) null else {
            require(deviceId?.matches(Regex("[A-Za-z0-9_-]{1,64}"))==true && clusterId.isNotBlank())
            DeviceRoute(deviceId!!,clusterId)
        }
        expectedDeviceId=deviceId
        state.value=null; trail.value=emptyList(); commandFeedback.value=null; controlLossReason.value=null
        lastError.value=null; receivedRate.reset(); renderedRate.reset()
        audit("DEVICE_ROUTE_BOUND","${deviceId ?: "observer"}|independent=${clusterId!=null}")
    }
    private fun endpoint(path: String): String {
        val route=deviceRoute ?: return path
        if (!path.startsWith("/api/") || path.startsWith("/api/v1/")) return path
        // v1状态不含全部恢复信息，安全适配器只额外订阅本机原始状态；全机地图共用一条v1流。
        val prefix=if (path in setOf("/api/state","/api/events")) "/api/devices/" else "/api/v1/devices/"
        return prefix+route.deviceId+"/"+path.removePrefix("/api/")
    }
    @Volatile private var inputTtlMs=500
    @Volatile private var appSourceEnabled=true
    private val wire = BackendWire()
    private val sendLock = Mutex()
    val state = MutableStateFlow<RotorState?>(null)
    val connected = MutableStateFlow(false)
    val lastError = MutableStateFlow<String?>(null)
    // 控制失效原因保留到用户重新申请成功，不能被下一帧正常遥测抹掉。
    val controlLossReason=MutableStateFlow<String?>(null)
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
    private val authorityMonitor=Any()
    @Volatile private var pendingStopVersion: Long?=null
    @Volatile private var safetyKind: String?=null
    var audit: (String,String)->Unit = { _,_-> }
    @Volatile private var activeUrl = ""
    @Volatile private var activeToken = ""
    @Volatile private var generation = 0L
    @Volatile private var lastStateAt = 0L
    @Volatile private var sessionId: String? = null
    @Volatile private var epoch = ""
    @Volatile private var clockOffset = 0L
    @Volatile private var clockFromAckAt=0L
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
    private var controlScope: CoroutineScope?=null
    private var recoveryStopJob: Job?=null
    private var windowStart = 0L
    private var frames = 0
    private var lastIdentity = ""
    @Volatile private var inputUiRequired=false
    @Volatile private var inputUiPulseAt=0L
    fun attachInputUi() { inputUiRequired=true; inputUiPulseAt=monoMs() }
    fun pulseInputUi() { inputUiPulseAt=monoMs() }
    fun detachInputUi() { inputUiPulseAt=0 }
    fun invalidateControl(reason: String) {
        val hadMotion=holding.value
        if (!hadMotion && phase.value!=LinkPhase.READY && controlLossReason.value==reason) return
        operationVersion.incrementAndGet(); wire.cancelPending(); loseControl()
        controlLossReason.value=reason; lastError.value=reason
        audit("CONTROL_INVALIDATED","$reason|motion=$hadMotion|seq=${state.value?.sequence}|mode=${state.value?.mode}|source=${state.value?.selected_source}")
        // 停发不是已停止：撤销正在运动的通路后，独立提交停止，不等待TTL触发保护。
        // 不恢复READY、不重放旧杆值；链路断开时仍保留“停止未确认”并由后端TTL兜底。
        if (hadMotion && running.value && recoveryStopJob?.isActive!=true) {
            val gen=generation
            recoveryStopJob=controlScope?.launch(Dispatchers.IO) {
                safetyAction("/api/stop")
                if (gen==generation) {
                    val failure=lastError.value
                    controlLossReason.value=if (failure?.startsWith("安全操作未确认")==true) "$reason；停止未确认，请检查链路" else reason
                    lastError.value=controlLossReason.value
                }
            }
        }
    }

    fun startPolling(scope: CoroutineScope) {
        if (running.value) return
        connectionIssue(baseUrl,token)?.let { lastError.value = it; return }
        activeUrl = baseUrl.trim().trimEnd('/'); activeToken = token.trim()
        val gen = ++generation
        controlScope=scope; recoveryStopJob?.cancel(); recoveryStopJob=null; controlLossReason.value=null
        state.value = null; trail.value = emptyList(); sessionId = null; epoch = ""; commandFeedback.value=null
        clockFromAckAt=0L
        lastIdentity=""; frames=0; windowStart=0
        connected.value = false; stale.value = false; phase.value = LinkPhase.DISCONNECTED
        running.value = true; receiveHz.value = 0.0; lastError.value = null
        streamJob = scope.launch(Dispatchers.IO) {
            while (isActive && gen == generation) {
                try {
                    val initial = wire.get(activeUrl,activeToken,endpoint("/api/state"))["data"] ?: error("状态格式异常")
                    ensureActive()
                    accept(wire.json.decodeFromJsonElement<RotorState>(initial),gen)
                    streamCall = wire.stream(activeUrl,activeToken,endpoint("/api/events"))
                    wire.readStream(streamCall!!) { if (gen == generation) accept(wire.json.decodeFromString<RotorState>(it),gen) }
                    if (isActive && gen == generation) error("状态流已关闭")
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (gen == generation) {
                        invalidateControl(explainConnection(e)); connected.value = false; stale.value = true
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
                    invalidateControl("状态超过500ms未更新，已停发；恢复后请重新申请控制")
                    streamCall?.cancel()
                }
                delay(50)
            }
        }
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val start=monoMs()
                if (sessionId != null && connected.value && !stale.value && !busy.value && !safetyBusy.value && recoveryStopJob?.isActive!=true) {
                    val version=operationVersion.get()
                    try { signed("/api/heartbeat",false) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { if (version==operationVersion.get()) invalidateControl("心跳失败：${e.message}，请重新申请控制") }
                }
                // 来源连接租约与运动TTL都来自设备配置。100ms租约不能仍每100ms才心跳一次。
                delay((minOf(100L,inputTtlMs/4L)-(monoMs()-start)).coerceAtLeast(1))
            }
        }
    }
    private fun accept(s: RotorState, gen: Long) {
        if (gen != generation) return
        val now = monoMs()
        require(expectedDeviceId==null || expectedDeviceId==s.device_id) { "主设备不匹配，拒绝静默换机" }
        s.control_config?.let { limits ->
            require(limits.input_ttl_ms in 50..500) { "设备有效期配置不在协议安全范围" }
            inputTtlMs=limits.input_ttl_ms; appSourceEnabled=limits.sources_enabled["app"]==true
            if (!appSourceEnabled && phase.value==LinkPhase.READY) invalidateControl("APP通路已禁用")
        }
        require(s.position_m.size==3 && s.position_m.all { it.isFinite() } &&
            (s.velocity_m_s.isEmpty() || s.velocity_m_s.size==3 && s.velocity_m_s.all { it.isFinite() }) &&
            s.quaternion_xyzw.size==4 && s.quaternion_xyzw.all { it.isFinite() } &&
            s.quaternion_xyzw.sumOf { it*it } in .5..1.5) { "遥测参数无效" }
        // 服务端明确标记离线或过期时，即使刚收到HTTP/SSE报文也不能当作实时状态。
        if (!s.online || !s.state_age_ms.isFinite() || s.state_age_ms>500 || s.state_age_ms<0) {
            state.value=s; connected.value=false; stale.value=true; receiveHz.value=0.0
            invalidateControl("状态超过500ms或设备离线，已停发；恢复后请重新申请控制")
            return
        }
        // 同一步号重发不算新鲜状态，不抬高频率。
        val identity = "${s.boot_id}:${s.sequence}"
        if (lastIdentity == identity) return
        lastIdentity = identity
        if (state.value?.let { it.boot_id != s.boot_id || s.sequence < it.sequence || s.simulation_time_s < it.simulation_time_s } == true) {
            invalidateControl("后端重启或状态序号回退，请重新申请控制"); sessionId = null; trail.value = emptyList()
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
        // 观察帧可能已排队，不得覆盖近期HTTP确认时间给出的保守时钟校准。
        if (s.server_time_ms > 0 && now-clockFromAckAt>3000) clockOffset = s.server_time_ms.toLong()-System.currentTimeMillis()
        // epoch 只由明确申请和有序回执更新，禁止旧状态覆盖较新的回执。
        // HTTP中立应答可能先于较旧的SSE帧到达；旧的“尚未选中”状态不能撤销刚确认的控制权。
        // 保护始终立即处理；达到确认步号之后的来源变化仍正常撤销控制，不能自动恢复。
        synchronized(authorityMonitor) {
        // 自己的松杆STOP会改变epoch，SSE可能早于HTTP回执到达。
        // 仅暂缓这一个在途STOP的“APP仍选中且HOLD”状态；保护、换来源立即撤销。
        // 只有STOP回执确认后才接受新epoch，失败时仍撤销控制，不能靠观察帧自动接管。
        val ownStopPending=pendingStopVersion==operationVersion.get() && s.mode=="HOLD" &&
            s.selected_source=="app" && latestStick.isNeutral()
        if (s.mode == "PROTECTED" || (phase.value == LinkPhase.READY && !ownStopPending &&
                s.sequence>readyAfterSequence && (s.control_epoch!=epoch || s.selected_source != "app"))) {
            if (phase.value==LinkPhase.READY) {
                audit("CONTROL_REVOKED","STATE_CHANGED|seq=${s.sequence}|mode=${s.mode}|reason=${s.protection_reason}")
                if (s.mode=="PROTECTED") {
                    operationVersion.incrementAndGet(); wire.cancelPending(); loseControl()
                    controlLossReason.value="后端保护：${s.protection_reason ?: "未说明原因"}；解除后需重新申请控制"
                } else invalidateControl("后端控制来源或控制轮次已改变，请重新申请控制")
            } else loseControl()
        }
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
        streamJob?.cancel(); watchJob?.cancel(); heartbeatJob?.cancel(); handshakeJob?.cancel(); recoveryStopJob?.cancel()
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
        if (busy.value || safetyBusy.value || recoveryStopJob?.isActive==true) return
        busy.value=true
        val gen=generation
        val version=operationVersion.get()
        val allowed={ version==operationVersion.get() && gen==generation && !safetyBusy.value }
        handshakeJob=scope.launch(Dispatchers.IO) {
            try {
                require(connected.value && !stale.value) { "请先连接并等待实时状态" }
                require(appSourceEnabled) { "APP输入通路已禁用" }
                require(latestStick.isNeutral()) { "请先松开双摇杆" }
                require(state.value?.mode in setOf("HOLD","ACTIVE")) { "当前运行模式不允许接管，请先确认状态或解除保护" }
                loseControl()
                sendLock.withLock {
                    if (sessionId == null) {
                        phase.value=LinkPhase.SESSION
                        val d=createSession(allowed)
                        sessionId=d["session_id"]!!.jsonPrimitive.content; sequence=0
                    }
                    epoch=wire.get(activeUrl,activeToken,endpoint("/api/state"),allowed)["data"]!!.jsonObject["control_epoch"]!!.jsonPrimitive.content
                }
                phase.value=LinkPhase.HANDSHAKE
                check(allowed()) { "操作已被安全动作中断" }
                signed("/api/switch",true,buildJsonObject { put("target_source","app"); put("user_initiated",true) },version)
                require(latestStick.isNeutral()) { "握手期间有输入，请松手后重新申请" }
                signed("/api/neutral",true,buildJsonObject { put("axes",JsonArray(latestStick.axes().map(::JsonPrimitive))) },version)
                ensureActive()
                check(allowed()) { "连接或安全状态已变化，请重新申请" }
                controlLossReason.value=null; phase.value=LinkPhase.READY; lastError.value=null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (allowed()) { loseControl(); lastError.value="申请控制失败：${e.message}" } }
            finally { busy.value=false }
        }
    }
    fun onStick(snapshot: StickSnapshot, scope: CoroutineScope) {
        if (!snapshot.valid()) { invalidateControl("摇杆参数无效，已停发"); audit("INVALID_INPUT","RANGE_OR_NONFINITE")
            scope.launch(Dispatchers.IO) { stop() }; return }
        val wasNeutral=latestStick.isNeutral(); latestStick=snapshot
        if (snapshot.isNeutral()) {
            if (controlJob?.isActive == true) {
                controlJob?.cancel(); controlJob=null; holding.value=false
                // 阻塞HTTP不会随协程cancel自动关闭；仅取消旧运动请求，不能误取消来源心跳。
                wire.cancelPath(endpoint("/api/input"))
                audit("STICK_RELEASE","STOP_REQUESTED")
                scope.launch(Dispatchers.IO) {
                    if (!action("/api/stop")) {
                        invalidateControl("松杆停止未确认，请检查链路并重新申请")
                        stop()
                    } else audit("STICK_RELEASE","STOP_CONFIRMED")
                }
            }
            return
        }
        if (!connected.value || stale.value || !appSourceEnabled || safetyBusy.value || state.value?.mode !in setOf("HOLD","ACTIVE") || phase.value != LinkPhase.READY) return
        if (controlJob?.isActive == true || !wasNeutral) return
        intent++; holding.value=true
        val version=operationVersion.get()
        controlJob=scope.launch(Dispatchers.IO) {
            var previousStart=0L
            try {
                while (isActive && phase.value == LinkPhase.READY && !latestStick.isNeutral()) {
                    val start=monoMs() // 本轮有效指令生成时刻，包含等待发送锁的时间。
                    if (previousStart!=0L && start-previousStart>150)
                        audit("MOTION_SEND_GAP","interval_ms=${start-previousStart}|ttl_ms=$inputTtlMs")
                    previousStart=start
                    val generatedAtNs=System.nanoTime()
                    // IO线程不能在界面冻结后无限续发旧杆值；界面帧心跳失效必须停止并重新申请。
                    if (inputUiRequired && start-inputUiPulseAt>500) {
                        audit("UI_INPUT_EXPIRED","NO_UI_FRAME_500MS")
                        invalidateControl("界面更新中断，运动已停发，请重新申请控制")
                        break
                    }
                    // 获取锁后再采样，已松手或已失效的旧快照不允许排队补发。
                    sendLock.withLock {
                        if (!isActive || latestStick.isNeutral() || phase.value != LinkPhase.READY || version!=operationVersion.get()) return@withLock
                        signedLocked("/api/input",false,buildJsonObject { put("intent_sequence",intent); put("frame",APP_STICK_FRAME)
                            put("axes",JsonArray(latestStick.axes().map(::JsonPrimitive))) },version,generatedAtNs,
                            { isActive && !latestStick.isNeutral() && phase.value==LinkPhase.READY })
                    }
                    delay((minOf(50L,inputTtlMs/4L)-(monoMs()-start)).coerceAtLeast(1))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // 松杆主动取消的HTTP可能抛IOException，不能当作新故障撤销刚完成的停止。
                if (!isActive) throw CancellationException("运动任务已取消",e)
                if (version==operationVersion.get()) invalidateControl("控制已停发：${e.message}；请松手并重新申请")
            }
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
                    val d=createSession(allowed)
                    sessionId=d["session_id"]!!.jsonPrimitive.content; sequence=0
                }
                epoch=wire.get(activeUrl,activeToken,endpoint("/api/state"),allowed)["data"]!!.jsonObject["control_epoch"]!!.jsonPrimitive.content
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
        var confirmed=true
        if (sessionId != null) {
            // 停止会改变epoch。逐步获取本机最新轮次，不能把停止前的轮次用于disconnect。
            runCatching { sendLock.withLock {
                epoch=wire.get(activeUrl,activeToken,endpoint("/api/state"))["data"]!!.jsonObject["control_epoch"]!!.jsonPrimitive.content
                signedLocked("/api/stop",true,buildJsonObject {})
                epoch=wire.get(activeUrl,activeToken,endpoint("/api/state"))["data"]!!.jsonObject["control_epoch"]!!.jsonPrimitive.content
                signedLocked("/api/disconnect",true,buildJsonObject { put("reason","APP_EXIT") })
            } }.onFailure { confirmed=false; audit("RELEASE_UNCONFIRMED",it.javaClass.simpleName) }
        }
        sendLock.withLock { sessionId=null; epoch=""; latestStick=StickSnapshot(); phase.value=LinkPhase.DISCONNECTED }
        confirmed
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
                    val d=createSession(allowed)
                    sessionId=d["session_id"]!!.jsonPrimitive.content; sequence=0; epoch=state.value?.control_epoch.orEmpty()
                }
                // 用户明确按下离散操作时获取当前轮次，运动流绝不以此方式刷新后重试。
                epoch=wire.get(activeUrl,activeToken,endpoint("/api/state"),allowed)["data"]!!.jsonObject["control_epoch"]!!.jsonPrimitive.content
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
    /** 只查询原ID，不重放运动或自动换ID重试；正式完成语义以v1回执为准。 */
    suspend fun queryLastReceipt()=withContext(Dispatchers.IO) {
        val previous=commandFeedback.value ?: return@withContext
        val device=previous.deviceId ?: expectedDeviceId ?: state.value?.device_id ?: return@withContext
        if (!unifiedReceiptQueries || !device.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return@withContext
        val gen=generation
        val version=operationVersion.get()
        try {
            val response=wire.get(activeUrl,activeToken,"/api/v1/devices/$device/commands/app/${previous.id}",
                {gen==generation && version==operationVersion.get()})
            val receipt=wire.json.decodeFromJsonElement<UnifiedReceipt>(response["data"]!!)
            require(receipt.command_id==null || receipt.command_id==previous.id) { "命令回执ID不匹配" }
            require(receipt.device_id==null || receipt.device_id==device) { "命令回执设备不匹配" }
            if (gen==generation && version==operationVersion.get() && commandFeedback.value?.id==previous.id) {
                commandFeedback.value=previous.withReceipt(receipt); lastError.value=null
            }
        } catch (e: Exception) { if (gen==generation && version==operationVersion.get()) lastError.value="回执查询失败，未重发原指令：${e.message}" }
    }
    private suspend fun signed(path: String,discrete: Boolean,extra: JsonObject=buildJsonObject { },version: Long=operationVersion.get()) =
        sendLock.withLock { signedLocked(path,discrete,extra,version) }
    private fun createSession(allowed: ()->Boolean): JsonObject {
        val data=wire.post(activeUrl,activeToken,endpoint("/api/session"),buildJsonObject {
            deviceRoute?.let { put("device_id",it.deviceId); put("cluster_id",it.clusterId); put("user_initiated",true) }
        },allowed)["data"]!!.jsonObject
        check(allowed()) { "旧会话请求已失效" }; synchronizeClock(data)
        return data
    }
    private fun synchronizeClock(data: JsonObject) {
        val confirmed=data["ack"]?.jsonObject?.get("confirmed_at_ms")?.jsonPrimitive?.doubleOrNull ?: return
        if (!confirmed.isFinite()) return
        // 服务确认时刻不晚于客户端收齐回执；取近期最紧的下界，避免用滞后的SSE时间缩短TTL。
        val now=monoMs(); val lowerBound=confirmed.toLong()-System.currentTimeMillis()
        clockOffset=if (now-clockFromAckAt>3000) lowerBound else maxOf(clockOffset,lowerBound)
        clockFromAckAt=now
    }
    private fun signedLocked(path: String,discrete: Boolean,extra: JsonObject,version: Long=operationVersion.get(),generatedAt: Long=System.nanoTime(),requestAllowed: ()->Boolean={true}) {
        check(version==operationVersion.get()) { "操作已被安全动作中断" }
        val sid=sessionId ?: error("尚未建立控制会话")
        val start=monoMs()
        val commandId=if (discrete) UUID.randomUUID().toString() else null
        val actionName=mapOf("/api/stop" to "停止","/api/protect" to "保护","/api/resume" to "解除保护",
            "/api/switch" to "切换通路","/api/neutral" to "归中确认","/api/config" to "控制配置",
            "/api/cancel" to "取消","/api/disconnect" to "释放控制")[path] ?: path
        if (commandId!=null) commandFeedback.value=CommandFeedback(commandId,actionName,"SENDING")
        if (path=="/api/stop" && phase.value==LinkPhase.READY)
            synchronized(authorityMonitor) { pendingStopVersion=version }
        try {
            val r=wire.post(activeUrl,activeToken,endpoint(path),buildJsonObject {
                deviceRoute?.let { put("cluster_id",it.clusterId) }
                put("device_id",state.value?.device_id ?: expectedDeviceId ?: "sim-drone-1"); put("session_id",sid); put("control_epoch",epoch)
                put("command_sequence",++sequence); put("ttl_ms",inputTtlMs)
                put("sent_at_ms",System.currentTimeMillis()+clockOffset-minOf(20,inputTtlMs/10))
                if (commandId!=null) put("command_id",commandId)
                extra.forEach { (k,v)->put(k,v) }
            },{ version==operationVersion.get() && requestAllowed() })
            check(version==operationVersion.get() && requestAllowed()) { "旧操作回执已失效" }
            r["data"]?.jsonObject?.let(::synchronizeClock)
            if (path=="/api/input") performance.record((System.nanoTime()-generatedAt)/1_000_000.0,true)
            synchronized(authorityMonitor) {
                r["data"]?.jsonObject?.get("control_epoch")?.jsonPrimitive?.content?.let { epoch=it }
                // epoch与确认步号一起提交，避免SSE读取一半新、一半旧的控制屏障。
                r["data"]?.jsonObject?.get("sequence")?.jsonPrimitive?.longOrNull
                    ?.let { readyAfterSequence=it }
                if (path=="/api/stop" && pendingStopVersion==version) pendingStopVersion=null
            }
            if (path != "/api/heartbeat") lastAckMs.value=(monoMs()-start).toDouble()
            // HTTP成功只表示接收；完成状态必须来自服务端明确的command_status。
            if (commandId!=null) {
                val local=CommandFeedback(commandId,actionName,
                    r["data"]?.jsonObject?.get("command_status")?.jsonPrimitive?.content ?: "RECEIVED",deviceId=state.value?.device_id)
                val receipt=r["receipt"]?.let { wire.json.decodeFromJsonElement<UnifiedReceipt>(it) }
                require(receipt?.command_id==null || receipt.command_id==commandId) { "命令回执ID不匹配" }
                require(receipt?.device_id==null || receipt.device_id==state.value?.device_id) { "命令回执设备不匹配" }
                commandFeedback.value=if (receipt!=null) local.withReceipt(receipt) else local
            }
        } catch (e: Exception) {
            if (path=="/api/input") performance.record((System.nanoTime()-generatedAt)/1_000_000.0,false)
            if (version!=operationVersion.get()) throw e
            if (path=="/api/input" && !requestAllowed()) audit("MOTION_CANCELLED","RELEASE_OR_CONTROL_LOSS")
            else audit("COMMAND_FAILURE","$path|${e.javaClass.simpleName}|${(e as? HttpRejection)?.errorCode ?: "NO_SERVER_CODE"}")
            if (commandId!=null) commandFeedback.value=CommandFeedback(commandId,actionName,
                if (e is java.io.IOException || e is HttpRejection && e.category=="OUTCOME_UNKNOWN") "OUTCOME_UNKNOWN" else "FAILED",
                e.message.orEmpty(),deviceId=state.value?.device_id)
            if (e is HttpRejection && e.errorCode in setOf("STALE_SESSION","DISCONNECTED")) sessionId=null
            throw e
        } finally {
            if (monoMs()-start>150) audit("HTTP_ACK_SLOW","$path|elapsed_ms=${monoMs()-start}|ttl_ms=$inputTtlMs")
            if (path=="/api/stop") synchronized(authorityMonitor) {
                if (pendingStopVersion==version) pendingStopVersion=null
            }
        }
    }
}
fun CommandFeedback.withReceipt(r: UnifiedReceipt)=copy(status=r.phase,reason=r.reason.orEmpty(),
    completionScope=r.completion_scope,deviceId=r.device_id ?: deviceId,rawCode=r.raw_code,succeeded=r.succeeded,failed=r.failed)
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
    // 目录每3秒刷新，后端空闲连接也可能在3秒关闭。GET复用旧连接失败时允许重连，
    // 不能让一次空闲连接EOF直接清空能力目录。此策略仅用于无副作用的只读GET。
    private val readOnly=http.newBuilder().retryOnConnectionFailure(true).build()
    private val streaming=http.newBuilder().readTimeout(0,TimeUnit.MILLISECONDS).callTimeout(0,TimeUnit.MILLISECONDS).build()
    private val pending=mutableSetOf<Call>()
    fun cancelPending() { synchronized(pending) { pending.forEach { it.cancel() } } }
    fun cancelPath(path: String) { synchronized(pending) { pending.filter { it.request().url.encodedPath==path }.forEach { it.cancel() } } }
    fun get(url: String,token: String,path: String,allowed: ()->Boolean={true})=execute(Request.Builder().url(url+path).header("Authorization","Bearer $token").build(),allowed,readOnly)
    fun post(url: String,token: String,path: String,body: JsonObject,allowed: ()->Boolean={true})=execute(Request.Builder().url(url+path)
        .header("Authorization","Bearer $token").post(body.toString().toRequestBody("application/json".toMediaType())).build(),allowed)
    private fun execute(request: Request,allowed: ()->Boolean,client: OkHttpClient=http): JsonObject {
        val call=client.newCall(request)
        synchronized(pending) { check(allowed()) { "操作已被安全动作中断" }; pending.add(call) }
        try { return call.execute().use { r ->
        val body=r.body?.source()
        require(body==null || !body.request(1_048_577)) { "HTTP应答超过1MB" }
        val d=runCatching { json.parseToJsonElement(body?.readUtf8().orEmpty()).jsonObject }.getOrNull()
        if (!r.isSuccessful || d?.get("ok")?.jsonPrimitive?.booleanOrNull != true)
            throw HttpRejection(r.code,d?.get("error")?.jsonPrimitive?.content ?: "HTTP ${r.code}",d?.get("error_code")?.jsonPrimitive?.content,
                d?.get("error_detail")?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull,
                d?.get("error_detail")?.jsonObject?.get("retry_policy")?.jsonPrimitive?.contentOrNull,
                d?.get("request_id")?.jsonPrimitive?.contentOrNull)
        d
        } } finally { synchronized(pending) { pending.remove(call) } }
    }
    fun stream(url: String,token: String,path: String): Call=streaming.newCall(Request.Builder().url(url+path)
        .header("Authorization","Bearer $token").header("Accept","text/event-stream").build())
    // 日志导出是文件下载（非JSON应答），读超时放宽；32MB上限防止移动端内存失控。
    // 必须允许重连：后端会主动关闭空闲keep-alive连接，复用旧连接会直接EOF（unexpected end of stream），
    // 而基础客户端retryOnConnectionFailure=false，不重试就会把一次过期连接当成导出失败。
    private val downloading=http.newBuilder().retryOnConnectionFailure(true)
        .readTimeout(30,TimeUnit.SECONDS).callTimeout(0,TimeUnit.MILLISECONDS).build()
    fun download(url: String,token: String,path: String,maxBytes: Long=32L*1024*1024): ByteArray {
        val call=downloading.newCall(Request.Builder().url(url+path).header("Authorization","Bearer $token").build())
        return call.execute().use { r ->
            val bytes=r.body?.bytes() ?: ByteArray(0)
            if (!r.isSuccessful) {
                val detail=runCatching { json.parseToJsonElement(bytes.decodeToString()).jsonObject }.getOrNull()
                throw HttpRejection(r.code,detail?.get("error")?.jsonPrimitive?.content ?: "HTTP ${r.code}",
                    detail?.get("error_code")?.jsonPrimitive?.contentOrNull)
            }
            require(bytes.size<=maxBytes) { "导出文件超过${maxBytes/(1024*1024)}MB上限" }
            bytes }
    }
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
