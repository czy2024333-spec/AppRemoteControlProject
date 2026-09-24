package com.vrxfusion.remote.control

import com.vrxfusion.remote.adapters.DeviceAdapter
import com.vrxfusion.remote.device.Capability
import com.vrxfusion.remote.device.CommandRecord
import com.vrxfusion.remote.device.CommandStatus
import com.vrxfusion.remote.device.DeviceCommand
import com.vrxfusion.remote.protocol.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 控制会话 —— 对应需求书 9 章（安全与控制保护）与 IF-003：
 *
 * - 控制权状态机（APP-005 / SAFE-001）：空闲 -> 申请 -> 持有 -> 释放/被回收
 * - 连续指令 10Hz 发送，带 TTL（IF-003 看门狗策略）
 * - 输入中断看门狗（SAFE-004）：摇杆松开/输入中断超时 -> 自动发送停止
 * - 能力校验（PRI-004）：发送前检查设备能力清单，不支持则拒绝
 * - 紧急停止（SAFE-002）：优先级最高，立即发送
 */
class ControlSession(
    private val scope: CoroutineScope,
    private val adapter: DeviceAdapter,
    private val operatorName: String = "operator-01"
) {
    enum class Authority { IDLE, REQUESTING, GRANTED, LOST }

    private val _authority = MutableStateFlow(Authority.IDLE)
    val authority: StateFlow<Authority> = _authority

    private val _records = MutableStateFlow<List<CommandRecord>>(emptyList())
    val records: StateFlow<List<CommandRecord>> = _records

    /** 当前连续运动输入（摇杆归一化值，机体系） */
    data class MotionInput(val vx: Float = 0f, val vy: Float = 0f, val wz: Float = 0f) {
        val isZero: Boolean get() = vx == 0f && vy == 0f && wz == 0f
    }

    @Volatile private var currentInput = MotionInput()
    @Volatile private var lastInputAt = 0L
    private var loopJob: Job? = null

    init {
        // 镜像适配器的指令记录由 ViewModel 订阅，供 UI 展示（APP-010）
    }

    /** 申请控制权（演示模式直接授予；正式版经统一控制服务仲裁） */
    fun requestAuthority() {
        _authority.value = Authority.REQUESTING
        adapter.setController(operatorName)
        _authority.value = Authority.GRANTED
        startLoop()
    }

    /** 释放控制权 */
    fun releaseAuthority() {
        stopLoop()
        sendImmediate(DeviceCommand.Stop)
        adapter.setController(null)
        _authority.value = Authority.IDLE
    }

    /** 更新连续运动输入（摇杆拖动时高频调用） */
    fun updateInput(input: MotionInput) {
        currentInput = input
        lastInputAt = System.currentTimeMillis()
    }

    /** 紧急停止 —— SAFE-002：安全指令优先级最高 */
    fun emergencyStop() {
        currentInput = MotionInput()
        sendImmediate(DeviceCommand.EmergencyStop)
    }

    /** 发送离散指令（起降/返航/姿态/仿真管理等），带能力校验 */
    fun sendCommand(cmd: DeviceCommand): CommandRecord? {
        if (_authority.value != Authority.GRANTED) return null
        if (!checkCapability(cmd)) return null
        val rec = adapter.send(cmd)
        pushRecord(rec)
        return rec
    }

    // ---- 内部实现 ----

    private fun checkCapability(cmd: DeviceCommand): Boolean =
        cmd.requires in adapter.descriptor.capabilities

    private fun startLoop() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            var stopSent = true
            while (isActive && _authority.value == Authority.GRANTED) {
                val inIdle = currentInput.isZero
                val stale = System.currentTimeMillis() - lastInputAt > Protocol.INPUT_WATCHDOG_MS
                if (!inIdle && !stale) {
                    // 正常连续发送（10Hz）
                    adapter.send(DeviceCommand.Motion(currentInput.vx, currentInput.vy, currentInput.wz))
                    stopSent = false
                } else if (!stopSent) {
                    // 输入为零或看门狗超时 -> 发送一次停止并进入静默
                    // （SAFE-004：输入中断自动停止连续指令）
                    adapter.send(DeviceCommand.Stop)
                    stopSent = true
                }
                delay(1000L / Protocol.CMD_RATE_HZ)
            }
        }
    }

    private fun stopLoop() {
        loopJob?.cancel()
        loopJob = null
    }

    private fun sendImmediate(cmd: DeviceCommand) {
        val rec = adapter.send(cmd)
        pushRecord(rec)
    }

    private fun pushRecord(rec: CommandRecord) {
        _records.value = (listOf(rec) + _records.value).takeLast(20)
    }

    companion object {
        /** 将记录标记为超时（演示环境无应答时 UI 侧调用） */
        fun markTimeout(rec: CommandRecord): CommandRecord =
            rec.copy(status = CommandStatus.TIMEOUT)
    }
}
