package com.vrxfusion.remote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vrxfusion.remote.control.ControlSession
import com.vrxfusion.remote.device.Capability
import com.vrxfusion.remote.device.CommandRecord
import com.vrxfusion.remote.device.DeviceCommand
import com.vrxfusion.remote.device.DeviceDescriptor
import com.vrxfusion.remote.device.DeviceRepository
import com.vrxfusion.remote.device.DeviceState
import com.vrxfusion.remote.device.GaitPosture
import com.vrxfusion.remote.device.RealityType
import com.vrxfusion.remote.device.SimAction
import com.vrxfusion.remote.protocol.Role
import com.vrxfusion.remote.protocol.WsTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 主 ViewModel：登录角色、服务连接、设备目录、控制会话（MVVM）。
 * 角色 B 首周交付：真机按钮→服务→设备链路；观察员不能控制（服务端强制 + UI 禁用）。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val diagnostics=com.vrxfusion.remote.diagnostics.FlightDiagnostics(app,viewModelScope)
    val flightGateway by lazy { com.vrxfusion.remote.unified.FlightGateway(rotorClient(),viewModelScope) }

    // 地址保存在本机，Token仅保存在当前进程内，不明文持久化。
    private val preferences = app.getSharedPreferences("backend_connection", 0)
    private val hudStore = com.vrxfusion.remote.ui.HudPreferencesStore(app)
    private val _hudPreferences = MutableStateFlow(hudStore.load())
    val hudPreferences: StateFlow<com.vrxfusion.remote.ui.HudPreferences> = _hudPreferences
    fun setHudPreferences(value: com.vrxfusion.remote.ui.HudPreferences) {
        _hudPreferences.value = hudStore.save(value); hudRevision.value=hudStore.revision()
    }
    val hudRevision=MutableStateFlow(hudStore.revision())
    val hudCheckpoint=MutableStateFlow(hudStore.hasCheckpoint())
    val hudConfigMessage=MutableStateFlow("")
    fun checkpointHud() { hudStore.checkpoint(); hudCheckpoint.value=true; hudConfigMessage.value="已保存当前显示配置为回退点" }
    fun rollbackHud() { runCatching { _hudPreferences.value=hudStore.rollback() }
        .onSuccess { hudRevision.value=hudStore.revision(); hudConfigMessage.value="已回退显示配置" }
        .onFailure { hudConfigMessage.value="回退失败：${it.message}" } }
    /** 恢复出厂显示配置：先自动存回退点，误操作可立即回退。 */
    fun resetHudDefaults() {
        hudStore.checkpoint(); hudCheckpoint.value=true
        setHudPreferences(com.vrxfusion.remote.ui.HudPreferences())
        hudConfigMessage.value="已恢复默认显示配置，原配置已保存为回退点"
    }
    fun importHud(text: String) {
        // 先完整校验再保存，失败不能破坏当前配置；导入前保留回退点。
        runCatching { com.vrxfusion.remote.ui.HudConfigCodec.decode(text) }
            .onSuccess { hudStore.checkpoint(); hudCheckpoint.value=true; setHudPreferences(it)
                hudRevision.value=hudStore.revision(); hudConfigMessage.value="配置已校验并应用，原配置已保留为回退点" }
            .onFailure { hudConfigMessage.value="导入失败：格式、版本或字段取值不合法，当前配置未改变" }
    }
    fun currentHudRevision()=hudStore.revision()
    private val videoStore=com.vrxfusion.remote.video.VideoSettingsStore(app)
    private val _videoSettings=MutableStateFlow(videoStore.load())
    val videoSettings: StateFlow<com.vrxfusion.remote.video.VideoSettings> = _videoSettings
    val videoCode=MutableStateFlow("") // 电脑画面访问码仅保留内存，不与飞控Token共用。
    val videoSource=MutableStateFlow(com.vrxfusion.remote.video.VideoSource.NONE)
    val videoGeneration=MutableStateFlow(0L)
    val videoStatus=MutableStateFlow("未启用测试画面")
    fun setVideoSettings(value: com.vrxfusion.remote.video.VideoSettings) { videoStore.save(value); _videoSettings.value=value }
    fun startVideo() {
        val source=_videoSettings.value.source
        if (source==com.vrxfusion.remote.video.VideoSource.PC) {
            com.vrxfusion.remote.video.videoConnectionIssue(_videoSettings.value.pcUrl,videoCode.value)?.let { videoStatus.value=it; return }
        }
        videoGeneration.value++; videoSource.value=source
        videoStatus.value=if (source==com.vrxfusion.remote.video.VideoSource.NONE) "未启用测试画面" else "正在开启${source.label}"
    }
    fun stopVideo(reason: String="未启用测试画面") {
        videoSource.value=com.vrxfusion.remote.video.VideoSource.NONE; videoGeneration.value++; videoStatus.value=reason
    }
    val rotorScope get() = viewModelScope
    private var releaseJob: Job? = null

    private val transport = WsTransport(viewModelScope)
    private val repository = DeviceRepository(viewModelScope, transport)

    val descriptors: StateFlow<List<DeviceDescriptor>> = repository.descriptors
    val states: StateFlow<Map<String, DeviceState>> = repository.states
    val runMode: StateFlow<DeviceRepository.RunMode> = repository.runMode
    val connState: StateFlow<WsTransport.ConnState> = transport.state

    /** 登录身份（APP-002） */
    private val _role = MutableStateFlow(Role.OPERATOR)
    val role: StateFlow<Role> = _role

    private val _userName = MutableStateFlow("")
    val userName: StateFlow<String> = _userName

    // 自建 WebSocket 控制服务改到 8767（8765 已给合伙人A 的 v0.5.1 统一控制服务）
    private val _serverUrl = MutableStateFlow("ws://10.90.247.120:8767/ws")
    val serverUrl: StateFlow<String> = _serverUrl

    /** 当前选中（待控制）设备 */
    private val _selected = MutableStateFlow<DeviceDescriptor?>(null)
    val selected: StateFlow<DeviceDescriptor?> = _selected

    /** 当前设备的控制会话 */
    private var session: ControlSession? = null
    private val _sessionAuthority = MutableStateFlow(ControlSession.Authority.IDLE)
    val sessionAuthority: StateFlow<ControlSession.Authority> = _sessionAuthority

    private val _records = MutableStateFlow<List<CommandRecord>>(emptyList())
    val records: StateFlow<List<CommandRecord>> = _records

    private var legacyStarted=false
    fun startLegacy() { if (!legacyStarted) { legacyStarted=true; repository.start() } }

    fun stateOf(deviceId: String): DeviceState = repository.stateOf(deviceId)

    // ---- 登录与连接（APP-002：登录及权限管理） ----

    fun setServerUrl(url: String) { _serverUrl.value = url }
    fun setUserName(name: String) { _userName.value = name }
    fun setRole(r: Role) { _role.value = r }

    fun connect() {
        if (_userName.value.isBlank()) _userName.value = "operator-01"
        transport.connect(_serverUrl.value.trim(), _role.value, _userName.value.trim())
    }

    fun disconnect() = transport.disconnect()

    // ---- 设备选择与控制 ----

    fun selectDevice(d: DeviceDescriptor) {
        abandonSession()
        _selected.value = d
    }

    fun backToList() {
        abandonSession()
        _selected.value = null
    }

    fun switchRunMode(mode: DeviceRepository.RunMode) {
        repository.switchRunMode(mode)
    }

    fun requestAuthority() {
        if (!_role.value.canControl) return   // 观察员不能控制（APP-002）
        val d = _selected.value ?: return
        if (d.reserved) return                 // 预留接口禁用（EXT-004）
        val adapter = repository.adapterOf(d.id) ?: return
        abandonSession()
        val s = ControlSession(viewModelScope, adapter, operatorName = transport.mySrc)
        s.requestAuthority()
        session = s
        viewModelScope.launch {
            s.authority.collect { _sessionAuthority.value = it }
        }
        viewModelScope.launch {
            s.records.collect { _records.value = it }
        }
    }

    fun releaseAuthority() {
        session?.releaseAuthority()
        session = null
        _sessionAuthority.value = ControlSession.Authority.IDLE
    }

    fun updateJoystick(vx: Float, vy: Float, wz: Float) {
        session?.updateInput(ControlSession.MotionInput(vx, vy, wz))
    }

    fun emergencyStop() = session?.emergencyStop()

    fun sendGait(posture: GaitPosture) = session?.sendCommand(DeviceCommand.Gait(posture))

    fun sendTakeoff() = session?.sendCommand(DeviceCommand.Takeoff)
    fun sendLand() = session?.sendCommand(DeviceCommand.Land)
    fun sendHover() = session?.sendCommand(DeviceCommand.Hover)
    fun sendRtl() = session?.sendCommand(DeviceCommand.ReturnHome)

    fun sendSimAction(action: SimAction) {
        // EXT-004 / SAFE-005：仿真管理指令只允许发往本地虚拟设备，不得路由至实机类型
        val d = _selected.value ?: return
        if (d.reality == RealityType.REAL) return
        if (Capability.SIM_MANAGE !in d.capabilities) return
        session?.sendCommand(DeviceCommand.SimManage(action))
    }

    // ---- RotorPy 联调（合伙人A后端，HTTP 直联） ----

    // v0.5.1 三来源硬件适配：默认 8765 + APP 来源 Token
    private val _rotorBaseUrl = MutableStateFlow(preferences.getString("url", "http://192.168.1.100:8765")!!)
    val rotorBaseUrl: StateFlow<String> = _rotorBaseUrl

    private val _connectionIdentity=MutableStateFlow(preferences.getInt("pilot_preset",0).coerceIn(0,3))
    val connectionIdentity: StateFlow<Int> = _connectionIdentity
    private val _rotorToken = MutableStateFlow(com.vrxfusion.remote.unified.connectionIdentities[_connectionIdentity.value].token)
    val rotorToken: StateFlow<String> = _rotorToken

    fun setRotorBaseUrl(url: String) { _rotorBaseUrl.value = url }
    fun setRotorToken(token: String) { _rotorToken.value = token; _connectionIdentity.value=-1 }
    fun selectConnectionIdentity(index: Int) {
        if (flightGateway.running.value || index !in 0..3) return
        _connectionIdentity.value=index
        _rotorToken.value=com.vrxfusion.remote.unified.connectionIdentities[index].token
        preferences.edit().putInt("pilot_preset",index).apply() // 仅保存公开预设编号，不落盘用户输入的Token。
    }

    fun connectRotor() {
        viewModelScope.launch {
            releaseJob?.join()
            preferences.edit().putString("url", _rotorBaseUrl.value.trim()).apply()
            flightGateway.connect(_rotorBaseUrl.value,_rotorToken.value,true)
        }
    }

    fun connectCluster() {
        viewModelScope.launch {
            releaseJob?.join()
            preferences.edit().putString("url", _rotorBaseUrl.value.trim()).apply()
            flightGateway.connect(_rotorBaseUrl.value,_rotorToken.value,false)
        }
    }

    // ---- 服务端审计日志（11/19 功能扩展：查询与导出，观察端同样可读） ----
    private val _logsSummary = MutableStateFlow<String?>(null)
    val logsSummary: StateFlow<String?> = _logsSummary

    /** 查询服务端审计日志概况；结果或失败原因写入 logsSummary。 */
    fun refreshServerLogsSummary() {
        viewModelScope.launch {
            _logsSummary.value = runCatching {
                val data = flightGateway.repository.queryLogs(_rotorBaseUrl.value,_rotorToken.value,"?limit=5")["data"]!!.jsonObject
                val total = data["total"]?.jsonPrimitive?.content ?: "?"
                "服务端审计日志共 $total 条（可按设备/事件/时间过滤；导出为全部匹配内容）"
            }.getOrElse { "服务端日志查询失败：${it.message}" }
        }
    }

    /** 导出服务端审计日志为文件字节，由页面用 SAF 保存；失败返回 null 并写提示。 */
    suspend fun exportServerLogs(format: String): ByteArray? = runCatching {
        flightGateway.repository.downloadLogs(_rotorBaseUrl.value,_rotorToken.value,format)
    }.onFailure { _logsSummary.value="服务端日志导出失败：${it.message}" }.getOrNull()

    /** 离开控制区域/进入后台立即停流；后台上报由ViewModel执行，不依赖已销毁页面的协程。 */
    fun pauseRotor() {
        flightGateway.stopObserving()
        if (releaseJob?.isActive != true) {
            releaseJob = viewModelScope.launch { rotorPyClient?.releaseAll() }
        }
    }

    fun pauseAll() { pauseRotor(); stopVideo(); abandonSession() }

    /** 客户端随 ViewModel 存活：v0.3 控制轮次(epoch)+轮次内序号由客户端绑定维护 */
    private var rotorPyClient: com.vrxfusion.remote.rotorpy.RotorPyClient? = null
    private fun rotorClient(): com.vrxfusion.remote.rotorpy.RotorPyClient {
        if (rotorPyClient == null) {
            rotorPyClient = com.vrxfusion.remote.rotorpy.RotorPyClient(
                baseUrl = _rotorBaseUrl.value,
                token = _rotorToken.value
            )
            rotorPyClient!!.audit=diagnostics::event
        }
        return rotorPyClient!!
    }
    fun startDiagnostics() { diagnostics.start(rotorClient()) }

    private fun abandonSession() {
        session?.releaseAuthority()
        session = null
        _sessionAuthority.value = ControlSession.Authority.IDLE
        _records.value = emptyList()
    }

    override fun onCleared() {
        diagnostics.close()
        flightGateway.close()
        rotorPyClient?.stopPolling()
        repository.stop()
        super.onCleared()
    }
}
