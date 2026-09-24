package com.vrxfusion.remote.device

import com.vrxfusion.remote.adapters.B2Ros2Adapter
import com.vrxfusion.remote.adapters.DeviceAdapter
import com.vrxfusion.remote.adapters.DjiMsdkAdapter
import com.vrxfusion.remote.adapters.IsaacSimAdapter
import com.vrxfusion.remote.adapters.LocalSimAdapter
import com.vrxfusion.remote.protocol.MqttTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 设备目录服务 —— ARC-00x / APP-002：
 * 管理所有已注册设备（真实/虚拟/虚实绑定），为每台设备装配适配器。
 *
 * 演示环境：虚拟设备由内置仿真器驱动；真实设备默认离线占位，
 * 接入统一控制服务（MQTT）后由远端 STATE 消息驱动。
 */
class DeviceRepository(
    private val scope: CoroutineScope,
    private val transport: MqttTransport
) {
    private val adapters = linkedMapOf<String, DeviceAdapter>()

    private val _descriptors = MutableStateFlow<List<DeviceDescriptor>>(emptyList())
    val descriptors: StateFlow<List<DeviceDescriptor>> = _descriptors

    private val _states = MutableStateFlow<Map<String, DeviceState>>(emptyMap())
    val states: StateFlow<Map<String, DeviceState>> = _states

    /** 运行模式 —— MODE-001~004 */
    enum class RunMode(val label: String) {
        VIRTUAL_ONLY("纯虚拟"), REAL_ONLY("纯实装"), SYNC("虚实同步")
    }

    private val _runMode = MutableStateFlow(RunMode.VIRTUAL_ONLY)
    val runMode: StateFlow<RunMode> = _runMode

    fun switchRunMode(mode: RunMode) { _runMode.value = mode }

    fun adapterOf(deviceId: String): DeviceAdapter? = adapters[deviceId]

    fun stateOf(deviceId: String): DeviceState =
        _states.value[deviceId] ?: DeviceState(online = false)

    fun start() {
        seedDemoDevices()
        adapters.values.forEach { adapter ->
            adapter.start()
            scope.launch {
                adapter.state.collect { s ->
                    _states.value = _states.value + (adapter.descriptor.id to s)
                }
            }
        }
        transport.connect()
    }

    fun stop() {
        adapters.values.forEach { it.stop() }
        transport.disconnect()
    }

    /** 首期设备清单（演示种子数据，正式版由设备目录服务下发） */
    private fun seedDemoDevices() {
        register(
            DeviceDescriptor(
                id = "B2-SIM-01", name = "B2 虚拟样机", kind = DeviceKind.QUADRUPED,
                reality = RealityType.VIRTUAL, model = "Unitree B2 (Isaac Sim)",
                capabilities = setOf(Capability.TELEMETRY, Capability.MOTION, Capability.GAIT, Capability.SIM_MANAGE)
            ),
            local = true
        )
        register(
            DeviceDescriptor(
                id = "DJI-SIM-01", name = "无人机虚拟样机", kind = DeviceKind.DRONE,
                reality = RealityType.VIRTUAL, model = "DJI Matrice (SITL)",
                capabilities = setOf(
                    Capability.TELEMETRY, Capability.MOTION, Capability.TAKEOFF_LAND,
                    Capability.HOVER, Capability.RTL, Capability.WAYPOINT_MISSION, Capability.SIM_MANAGE
                )
            ),
            local = true
        )
        // 真实设备占位：接入统一控制服务前保持离线（ACC-001 接入后自动上线）
        register(
            DeviceDescriptor(
                id = "B2-01", name = "B2 机器狗 (现场)", kind = DeviceKind.QUADRUPED,
                reality = RealityType.REAL, model = "Unitree B2",
                capabilities = setOf(Capability.TELEMETRY, Capability.MOTION, Capability.GAIT)
            )
        )
        register(
            DeviceDescriptor(
                id = "DJI-01", name = "大疆无人机 (现场)", kind = DeviceKind.DRONE,
                reality = RealityType.REAL, model = "DJI RC Pro Enterprise",
                capabilities = setOf(
                    Capability.TELEMETRY, Capability.MOTION, Capability.TAKEOFF_LAND,
                    Capability.HOVER, Capability.RTL, Capability.WAYPOINT_MISSION
                )
            )
        )
        // 虚实绑定设备（MAP-001）
        register(
            DeviceDescriptor(
                id = "BIND-B2-01", name = "B2 虚实孪生", kind = DeviceKind.QUADRUPED,
                reality = RealityType.BOUND, model = "B2 ↔ Isaac Sim", boundTo = "B2-01",
                capabilities = setOf(Capability.TELEMETRY, Capability.MOTION, Capability.GAIT, Capability.SIM_MANAGE)
            ),
            local = true
        )
    }

    private fun register(d: DeviceDescriptor, local: Boolean = false) {
        val adapter: DeviceAdapter = if (local) {
            LocalSimAdapter(scope, d)
        } else {
            when (d.kind) {
                DeviceKind.QUADRUPED -> B2Ros2Adapter(scope, d, transport)
                DeviceKind.DRONE -> DjiMsdkAdapter(scope, d, transport)
            }
        }
        adapters[d.id] = adapter
        _descriptors.value = adapters.values.map { it.descriptor }
        _states.value = _states.value + (d.id to DeviceState(online = false))
    }
}
