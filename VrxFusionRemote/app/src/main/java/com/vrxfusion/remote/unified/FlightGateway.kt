package com.vrxfusion.remote.unified

import com.vrxfusion.remote.rotorpy.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** APP唯一生产控制入口：v1负责发现/统一展示，0.5兼容适配器负责已验证的写控制和恢复安全信息。 */
class FlightGateway(private val controller: RotorPyClient,private val scope: CoroutineScope) {
    val repository=UnifiedRepository()
    val state=MutableStateFlow<RotorState?>(null)
    val connected=MutableStateFlow(false)
    val stale=MutableStateFlow(false)
    val lastError=MutableStateFlow<String?>(null)
    val writable=MutableStateFlow(false)
    val running=repository.running
    val phase=controller.phase; val busy=controller.busy; val safetyBusy=controller.safetyBusy
    val holding=controller.holding; val commandFeedback=controller.commandFeedback
    val receiveHz=repository.receiveHz; val trail=controller.trail; val renderedRate=controller.renderedRate
    private var observer: Job
    private val connectionLock=Mutex()
    @Volatile private var controlAdapter=false
    @Volatile private var connectionGeneration=0L
    init {
        repository.audit={type,code->controller.audit(type,code)}
        observer=scope.launch {
            combine(controller.state,controller.connected,controller.lastError,repository.snapshot,repository.connected) { _,_,_,_,_->Unit }
                .combine(repository.directory) { _,_->Unit }.combine(repository.error) { _,_->Unit }
                .combine(controller.controlLossReason) { _,_->Unit }.collect { refresh() }
        }
    }
    private fun refresh() {
        val raw=controller.state.value
        val info=repository.info.value
        val target=info?.controlDeviceId()
        val primary=repository.snapshot.value?.members?.find { it.device_id==target }
        val fresh=if (controlAdapter && target!=null) primary!=null && repository.fresh(primary) &&
            raw?.device_id==target && raw.boot_id==primary.boot_id && controller.connected.value else repository.connected.value
        state.value=if (raw!=null && primary!=null && raw.device_id==target) primary.displayOver(raw).copy(
            mode=if (raw.mode=="PROTECTED") "PROTECTED" else primary.mode) else raw
        if (!controlAdapter || raw?.device_id!=target) state.value=null
        connected.value=fresh
        stale.value=repository.running.value && !fresh && raw!=null
        val permissionError=permissionIssue()
        writable.value=permissionError==null
        lastError.value=repository.error.value ?: permissionError ?: controller.controlLossReason.value ?: controller.lastError.value
        if (controller.phase.value==LinkPhase.READY && (!fresh || !writable.value)) {
            val reason=when {
                !writable.value -> permissionError ?: "来源或设备能力不允许操纵"
                primary==null -> "统一状态缺少主设备"
                raw?.boot_id!=primary.boot_id -> "两条状态链路的启动标识不一致"
                !controller.connected.value -> "控制状态链路中断或过期"
                else -> "统一状态过期、离线或测量无效"
            }
            controller.audit("GATEWAY_CONTROL_LOST","$reason|unified_seq=${primary?.sequence}|control_seq=${raw?.sequence}")
            controller.invalidateControl("$reason；已停发，请松手并重新申请控制")
        }
    }
    fun primaryDevice()=repository.directory.value?.devices?.find { it.device_id==repository.info.value?.controlDeviceId() }
    fun primaryState()=repository.snapshot.value?.members?.find { it.device_id==repository.info.value?.controlDeviceId() }
    // 分项说明只读原因，不能把目录传输故障说成用户角色变成观察员。
    private fun permissionIssue(): String? {
        val info=repository.info.value
        val source=info?.authentication?.inputSource()
        if (source!="APP") return "当前来源为${source ?: "只读观察端"}，单机操纵需要APP来源Token"
        if (!controlAdapter) return "本页面仅观察，不申请控制"
        if (info?.independent()==true && info.capabilities["device_bound_legacy_write"]!=true)
            return "后端未开放设备绑定控制适配，当前仅观察"
        val d=primaryDevice() ?: return repository.error.value ?: "本机能力目录尚未校验完成"
        if (info?.independent()==true && (info.authentication.role!="CONTROLLER" || d.device_id !in info.writable_device_ids ||
                !d.is_self || d.write_permitted!=true || primaryState()?.control?.write_permitted!=true))
            return "当前凭据没有该飞机的独立操纵权限"
        return when {
            d.integration_status!="CONNECTED" -> "主设备目录状态为${d.integration_status}，操纵已停用"
            d.coordinate_frame!=UNIFIED_FRAME -> "主设备坐标基准不兼容"
            !d.capabilities.motion || !d.capabilities.normalized_input -> "主设备未开放归一化运动输入"
            !d.capabilities.motion_axes.containsAll(listOf("X","Y","Z","YAW")) -> "主设备未开放全部三维控制轴"
            "WORLD" !in d.capabilities.motion_frames -> "主设备未开放世界坐标控制"
            d.control_limits.sources_enabled["app"]!=true -> "设备配置已禁用APP输入通路"
            d.control_limits.input_ttl_ms !in 50..500 -> "设备输入有效期超出50～500ms协议范围"
            else -> null
        }
    }
    fun canWrite()=permissionIssue()==null
    suspend fun connect(url: String,token: String,enableControlAdapter: Boolean)=connectionLock.withLock {
        val gen=++connectionGeneration
        controlAdapter=false; writable.value=false; state.value=null
        controller.stopPolling()
        if (!controller.releaseAll()) {
            repository.stop(); lastError.value="旧飞机停止或释放未确认，请确认停稳后再连接"; return@withLock
        }
        if (gen!=connectionGeneration) return@withLock
        if (repository.start(scope,url,token) && gen==connectionGeneration && repository.running.value) {
            controller.baseUrl=url; controller.token=token
            val info=repository.info.value!!
            val device=info.controlDeviceId()
            controller.configureDeviceRoute(device,if (info.independent() && device!=null) info.cluster_id else null)
            controller.unifiedReceiptQueries=true
            controlAdapter=enableControlAdapter && device!=null && info.authentication.inputSource()=="APP"
            if (controlAdapter && primaryDevice()!=null) controller.startPolling(scope)
        }
        refresh()
    }
    fun retryHandshake(scope: CoroutineScope) {
        if (!canWrite() || !connected.value) {
            controller.lastError.value="当前来源/设备能力不允许接管，或统一状态尚未有效"; refresh(); return
        }
        controller.retryHandshake(scope)
    }
    fun onStick(value: StickSnapshot,scope: CoroutineScope) {
        if (!value.isNeutral() && (!connected.value || !canWrite())) {
            if (controller.phase.value==LinkPhase.READY) controller.invalidateControl("控制条件已失效；请松手并重新申请")
            return
        }
        controller.onStick(value,scope)
    }
    private fun safetyAllowed(action: String)=controlAdapter && repository.info.value?.authentication?.inputSource()=="APP" &&
        (repository.info.value?.independent()!=true || primaryDevice()?.write_permitted==true) &&
        action in primaryDevice()?.capabilities?.actions.orEmpty()
    fun canUseSafety()=safetyAllowed("STOP") || safetyAllowed("PROTECT")
    suspend fun stop() { if (safetyAllowed("STOP")) controller.stop() else denySafety() }
    suspend fun protect() { if (safetyAllowed("PROTECT")) controller.protect() else denySafety() }
    suspend fun resume(scope: CoroutineScope) { if (safetyAllowed("RESUME") && connected.value) controller.resume(scope) else denySafety() }
    private fun denySafety() { controller.lastError.value="当前来源或设备不支持该操作"; refresh() }
    suspend fun queryLastReceipt()=controller.queryLastReceipt()
    fun attachInputUi()=controller.attachInputUi()
    fun detachInputUi()=controller.detachInputUi()
    fun pulseInputUi()=controller.pulseInputUi()
    fun stopObserving() { ++connectionGeneration; controlAdapter=false; repository.stop(); controller.stopPolling(); connected.value=false; stale.value=false; writable.value=false }
    suspend fun releaseAll()=controller.releaseAll()
    fun close() { stopObserving(); observer.cancel() }
}
