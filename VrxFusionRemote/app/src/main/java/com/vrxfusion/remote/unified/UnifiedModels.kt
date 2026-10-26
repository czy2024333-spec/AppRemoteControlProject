package com.vrxfusion.remote.unified

import com.vrxfusion.remote.rotorpy.RotorState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

const val UNIFIED_FRAME="SIM_WORLD_X_FORWARD_Y_LEFT_Z_UP"
@Serializable data class UnifiedAuthentication(val mode: String="UNKNOWN",val authenticated_source: String?=null,
    val user_id: String?=null,val role: String?=null,val formal_user_authentication: Boolean=false,
    val client_id: String?=null,val device_id: String?=null,val source: String?=null) {
    fun inputSource()=authenticated_source ?: source?.uppercase()
}
@Serializable data class UnifiedCapabilities(val contract_version: String="",val implementation_version: String="",
    val deployment_mode: String="",val primary_device_id: String="",val cluster_id: String="",
    val authentication: UnifiedAuthentication=UnifiedAuthentication(),val capabilities: Map<String,Boolean> = emptyMap(),
    val self_device_id: String?=null,val writable_device_ids: List<String> = emptyList()) {
    fun independent()=deployment_mode=="INDEPENDENT"
    fun controlDeviceId()=if (independent()) self_device_id else primary_device_id.takeIf { it.isNotBlank() }
    fun validate() {
        require(contract_version=="1.0" && deployment_mode in setOf("UNIFIED","INDEPENDENT")) { "需要v0.9 independent或v0.8 unified模式，契约版本1.0" }
        if (independent()) {
            require(capabilities["independent_control"]==true && cluster_id.isNotBlank()) { "后端未开放独立控制" }
            require(authentication.role in setOf("CONTROLLER","OBSERVER")) { "独立端身份不完整" }
            require(writable_device_ids.distinct()==writable_device_ids) { "设备权限重复" }
            if (authentication.role=="OBSERVER") require(self_device_id==null && writable_device_ids.isEmpty()) { "观察端不应具有写权限" }
            else require(self_device_id?.matches(Regex("[A-Za-z0-9_-]{1,64}"))==true &&
                authentication.device_id==self_device_id && writable_device_ids==listOf(self_device_id)) { "独立身份与设备权限不一致" }
        } else require(primary_device_id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "主设备标识无效" }
        require(capabilities["directory"]==true && capabilities["unified_state"]==true) { "后端未开放统一目录/状态" }
    }
}
@Serializable data class DeviceCapabilities(val motion: Boolean=false,val motion_axes: List<String> = emptyList(),
    val motion_frames: List<String> = emptyList(),val normalized_input: Boolean=false,val actions: List<String> = emptyList(),
    val takeoff: Boolean=false,val land: Boolean=false,val return_home: Boolean=false,val battery: Boolean=false,
    val gnss: Boolean=false,val local_position_velocity_measurement: Boolean=false,val ue_video: Boolean=false,
    val hardware_ring_verified: Boolean=false,val hardware_voice_verified: Boolean=false)
@Serializable data class ControlLimits(val input_ttl_ms: Int=500,val sensitivity: Double=1.0,val deadzone: Double=.08,
    val max_horizontal_speed_m_s: Double=.5,val max_vertical_speed_m_s: Double=.5,val max_yaw_rate_rad_s: Double=.5,
    val sources_enabled: Map<String,Boolean> = emptyMap())
@Serializable data class UnifiedDevice(val device_id: String,val name: String="",val reality: String="UNKNOWN",
    val integration_status: String="DISCONNECTED",val coordinate_frame: String="",
    val capabilities: DeviceCapabilities=DeviceCapabilities(),val control_limits: ControlLimits=ControlLimits(),
    val control_config_version: Int=0,val is_self: Boolean=false,val write_permitted: Boolean?=null) {
    fun permitsMotion()=integration_status=="CONNECTED" && coordinate_frame==UNIFIED_FRAME && capabilities.motion &&
        capabilities.normalized_input && capabilities.motion_axes.containsAll(listOf("X","Y","Z","YAW")) &&
        "WORLD" in capabilities.motion_frames && control_limits.sources_enabled["app"]==true && control_limits.input_ttl_ms in 50..500
}
@Serializable data class UnifiedDirectory(val contract_version: String="",val cluster_id: String="",
    val target_revision: Long=0,val devices: List<UnifiedDevice> = emptyList())
@Serializable data class Vec3Value(val x: Double,val y: Double,val z: Double) {
    fun values()=listOf(x,y,z)
}
@Serializable data class QuaternionValue(val x: Double,val y: Double,val z: Double,val w: Double) {
    fun values()=listOf(x,y,z,w)
}
@Serializable data class VectorMeasurement(val value: Vec3Value?=null,val source: String="UNKNOWN",
    val validity: String="NOT_CONNECTED",val unit: String?=null) {
    fun valid(expectedUnit: String)=validity=="VALID" && unit==expectedUnit && value?.values()?.all { it.isFinite() && kotlin.math.abs(it)<=1e6 }==true
}
@Serializable data class AttitudeMeasurement(val value: QuaternionValue?=null,val source: String="UNKNOWN",
    val validity: String="NOT_CONNECTED",val representation: String?=null) {
    fun valid()=validity=="VALID" && representation=="QUATERNION_XYZW_BODY_TO_WORLD" &&
        value?.values()?.all { it.isFinite() }==true && value.values().sumOf { it*it } in .5..1.5
}
@Serializable data class OptionalMeasurement(val value: JsonElement?=null,val source: String="UNKNOWN",val validity: String="NOT_CONNECTED")
@Serializable data class UnifiedControl(val source: String?=null,val selected_source: String?=null,
    val pending_source: String?=null,val control_epoch: String="",val phase: String="OBSERVING",val has_control: Boolean?=null,
    val write_permitted: Boolean?=null)
@Serializable data class UnifiedTask(val task_id: String?=null,val status: String="NOT_SUPPORTED")
@Serializable data class UnifiedVideo(val source: String="UE",val status: String="NOT_CONNECTED")
@Serializable data class UnifiedAlarm(val code: String,val level: String="WARNING")
@Serializable data class UnifiedDeviceState(val contract_version: String="",val device_id: String,val boot_id: String="",
    val sequence: Long=0,val server_time_unix_ms: Double=0.0,val simulation_time_s: Double=0.0,
    val state_age_ms: Double?=null,val online: Boolean=false,val mode: String="UNKNOWN",val coordinate_frame: String="",
    val position: VectorMeasurement=VectorMeasurement(),val velocity: VectorMeasurement=VectorMeasurement(),
    val attitude: AttitudeMeasurement=AttitudeMeasurement(),val battery: OptionalMeasurement=OptionalMeasurement(),
    val gnss: OptionalMeasurement=OptionalMeasurement(),val sensors: Map<String,OptionalMeasurement> = emptyMap(),
    val task: UnifiedTask=UnifiedTask(),val video: UnifiedVideo=UnifiedVideo(),val alarms: List<UnifiedAlarm> = emptyList(),
    val control: UnifiedControl=UnifiedControl(),val is_self: Boolean=false) {
    fun validTelemetry()=contract_version=="1.0" && boot_id.isNotBlank() && sequence>=0 && coordinate_frame==UNIFIED_FRAME &&
        position.valid("m") && velocity.valid("m/s") && attitude.valid()
    // 保留旧控制适配器的恢复/会话字段；v1的has_control=null绝不授予控制权。
    fun displayOver(raw: RotorState)=raw.copy(device_id=device_id,boot_id=boot_id,sequence=sequence,
        position_m=if (position.valid("m")) position.value!!.values() else emptyList(),
        velocity_m_s=if (velocity.valid("m/s")) velocity.value!!.values() else emptyList(),
        quaternion_xyzw=if (attitude.valid()) attitude.value!!.values() else emptyList(),data_source=if (position.source=="SIM_TRUTH") "simulation_truth" else position.source,
        mode=mode,online=online,state_age_ms=state_age_ms ?: Double.POSITIVE_INFINITY)
}
@Serializable data class UnifiedSelection(val selection_id: String,val target_revision: Long=0,val kind: String="",
    val cluster_id: String="",val device_id: String?=null,val group_id: String?=null,val member_ids: List<String> = emptyList())
@Serializable data class UnifiedSnapshot(val contract_version: String="",val implementation_version: String="",val cluster_id: String="",
    val boot_id: String="",val target_revision: Long=0,val selection: UnifiedSelection?=null,
    val groups: Map<String,List<String>> = emptyMap(),val members: List<UnifiedDeviceState> = emptyList(),
    val summary: Map<String,Int> = emptyMap(),val deployment_mode: String="",val self_device_id: String?=null,val viewer_role: String?=null)
@Serializable data class UnifiedReceipt(val command_id: String?=null,val device_id: String?=null,val source: String?=null,
    val phase: String="RECEIVED",val raw_code: String?=null,val reason: String?=null,val completion_scope: String?=null,
    val succeeded: Int?=null,val failed: Int?=null,val members: List<UnifiedReceipt> = emptyList())
