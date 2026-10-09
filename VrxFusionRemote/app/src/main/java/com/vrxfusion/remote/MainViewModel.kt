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

/**
 * 主 ViewModel：登录角色、服务连接、设备目录、控制会话（MVVM）。
 * 角色 B 首周交付：真机按钮→服务→设备链路；观察员不能控制（服务端强制 + UI 禁用）。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

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

    private val _serverUrl = MutableStateFlow("ws://192.168.1.100:8765/ws")
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

    init {
        repository.start()
    }

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

    private fun abandonSession() {
        session?.releaseAuthority()
        session = null
        _sessionAuthority.value = ControlSession.Authority.IDLE
        _records.value = emptyList()
    }

    override fun onCleared() {
        repository.stop()
        super.onCleared()
    }
}
