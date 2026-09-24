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
import com.vrxfusion.remote.protocol.MqttTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 主 ViewModel：串联设备目录、控制会话与运行模式（MVVM）。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val transport = MqttTransport(viewModelScope)
    private val repository = DeviceRepository(viewModelScope, transport)

    val descriptors: StateFlow<List<DeviceDescriptor>> = repository.descriptors
    val states: StateFlow<Map<String, DeviceState>> = repository.states
    val runMode: StateFlow<DeviceRepository.RunMode> = repository.runMode
    val mqttState: StateFlow<MqttTransport.ConnState> = transport.state

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

    fun selectDevice(d: DeviceDescriptor) {
        abandonSession()
        _selected.value = d
    }

    fun backToList() {
        abandonSession()
        _selected.value = null
    }

    fun switchRunMode(mode: DeviceRepository.RunMode) {
        // MODE-005：模式切换须明确提示指令接收对象 —— UI 层弹窗确认
        repository.switchRunMode(mode)
    }

    fun requestAuthority() {
        val d = _selected.value ?: return
        val adapter = repository.adapterOf(d.id) ?: return
        abandonSession()
        val s = ControlSession(viewModelScope, adapter)
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
        // SAFE-005：仿真管理指令只允许发往虚拟/绑定设备 —— 控制层再做一次硬校验
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
