package com.vrxfusion.remote.device

import com.vrxfusion.remote.adapters.AdapterKind
import com.vrxfusion.remote.adapters.DeviceAdapter
import com.vrxfusion.remote.adapters.DeviceAdapterRegistry
import com.vrxfusion.remote.protocol.WsTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 设备目录服务 —— APP-002 / CLU-002：
 * 管理已注册设备并为每台设备装配适配器。
 *
 * 设备清单（需求书 V2.3 + 分工 V1.0 基线）：
 * - uav-sim-01：UE 仿真无人机，经统一控制服务路由（首周联调主对象）
 * - uav-real-01：真实无人机，EXT-001 预留接口，本期禁用
 * - dog-01：机器狗，甲方暂缓开发，仅预留 EXT-002 接口，本期禁用
 * - uav-demo-local：本地演示机（内置仿真，无服务端时调试，标记 iface_test）
 */
class DeviceRepository(
    private val scope: CoroutineScope,
    private val transport: WsTransport,
    private val registry: DeviceAdapterRegistry = DeviceAdapterRegistry(scope, transport)
) {

    private val _descriptors = MutableStateFlow<List<DeviceDescriptor>>(emptyList())
    val descriptors: StateFlow<List<DeviceDescriptor>> = _descriptors

    private val _states = MutableStateFlow<Map<String, DeviceState>>(emptyMap())
    val states: StateFlow<Map<String, DeviceState>> = _states
    private val stateJobs = mutableMapOf<String, Job>()
    private var started = false

    /** 运行模式 —— ARC-005（本期：独立仿真；回放模式后续接入） */
    enum class RunMode(val label: String) {
        VIRTUAL_ONLY("独立仿真"), REAL_ONLY("实机同步(预留)"), SYNC("回放模式(预留)")
    }

    private val _runMode = MutableStateFlow(RunMode.VIRTUAL_ONLY)
    val runMode: StateFlow<RunMode> = _runMode

    fun switchRunMode(mode: RunMode) { _runMode.value = mode }

    fun adapterOf(deviceId: String): DeviceAdapter? = registry[deviceId]

    fun stateOf(deviceId: String): DeviceState =
        _states.value[deviceId] ?: DeviceState(online = false)

    @Synchronized
    fun start() {
        if (started) return
        if (registry.values.isEmpty()) seedDevices()
        started = true
        registry.values.forEach { adapter ->
            adapter.start()
            stateJobs[adapter.descriptor.id] = scope.launch {
                adapter.state.collect { s ->
                    _states.value = _states.value + (adapter.descriptor.id to s)
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        if (started) {
            started = false
            stateJobs.values.forEach { it.cancel() }
            stateJobs.clear()
            registry.values.forEach { it.stop() }
            _states.value = _states.value.mapValues { (_, state) ->
                state.copy(online = false, controller = null, linkState = LinkState.OFFLINE)
            }
        }
        // 传输可能由 ViewModel 独立连接；即使适配器未启动也必须清理。
        transport.disconnect()
    }

    private fun seedDevices() {
        // 首周联调主对象：UE 仿真无人机（经统一服务，状态 quality 由服务端透传标记）
        register(
            DeviceDescriptor(
                id = "uav-sim-01", name = "UE 仿真无人机", kind = DeviceKind.DRONE,
                reality = RealityType.VIRTUAL, model = "UE 独立仿真（经统一服务）",
                capabilities = setOf(
                    Capability.TELEMETRY, Capability.MOTION, Capability.TAKEOFF_LAND,
                    Capability.HOVER, Capability.RTL
                )
            ),
            AdapterKind.SERVICE
        )
        // EXT-001：真实无人机预留接口（后续按目标飞控/厂商 SDK 开发适配器）
        register(
            DeviceDescriptor(
                id = "uav-real-01", name = "真实无人机（预留）", kind = DeviceKind.DRONE,
                reality = RealityType.REAL, model = "目标飞控待定",
                capabilities = setOf(Capability.TELEMETRY),
                reserved = true,
                reservedNote = "EXT-001 预留接口：注册/能力/控制权/指令/应答/状态/告警，本期以模拟适配器验证"
            ),
            AdapterKind.RESERVED
        )
        // EXT-002：机器狗暂缓，仅预留接口
        register(
            DeviceDescriptor(
                id = "dog-01", name = "机器狗（暂缓·预留）", kind = DeviceKind.QUADRUPED,
                reality = RealityType.REAL, model = "型号待定",
                capabilities = setOf(Capability.TELEMETRY),
                reserved = true,
                reservedNote = "EXT-002 预留接口：注册/能力/线速度/转向/停止/位置/姿态/电量/告警；站立趴下为可选能力编码"
            ),
            AdapterKind.RESERVED
        )
        // 本地演示机：无服务端时可验证控制台与看门狗（明确标记接口测试数据）
        register(
            DeviceDescriptor(
                id = "uav-demo-local", name = "本地演示机", kind = DeviceKind.DRONE,
                reality = RealityType.VIRTUAL, model = "内置仿真（接口测试）",
                capabilities = setOf(
                    Capability.TELEMETRY, Capability.MOTION, Capability.TAKEOFF_LAND,
                    Capability.HOVER, Capability.RTL, Capability.SIM_MANAGE
                )
            ),
            AdapterKind.LOCAL_SIM
        )
    }

    private fun register(d: DeviceDescriptor, kind: AdapterKind) {
        registry.create(d, kind)
        _descriptors.value = registry.values.map { it.descriptor }
        _states.value = _states.value + (d.id to DeviceState(online = false))
    }
}
