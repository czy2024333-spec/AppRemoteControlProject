package com.vrxfusion.remote.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 统一控制协议 —— 对应需求书第 8 章（IF-001 ~ IF-005）：
 * 版本化、可追溯的统一消息封装，APP 与统一控制服务之间双向通信。
 * 传输层默认 MQTT（与报价模块 4「MQTT控制与安全」一致）。
 */
object Protocol {
    const val PROTO_VERSION = 1
    /** 连续指令默认发送频率（Hz）—— IF-003 */
    const val CMD_RATE_HZ = 10
    /** 连续指令有效期（毫秒）—— IF-003 看门狗策略 */
    const val CMD_TTL_MS = 500L
    /** 输入中断看门狗（毫秒）—— SAFE-004 */
    const val INPUT_WATCHDOG_MS = 600L
    /** 状态显示频率（Hz）—— PERF-002 */
    const val STATE_RATE_HZ = 20

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }
}

/** 消息类型 —— IF-002 */
enum class MessageType { CMD, CMD_ACK, STATE, ALARM, TASK, RESULT, EVENT }

/** 数据来源标识 —— MAP-006 */
enum class DataSource { APP, UNIFIED_CONTROL, REAL_ADAPTER, SIM_ADAPTER }

/**
 * 统一消息头 —— IF-002：
 * 协议版本、消息类型、设备编号、会话编号、序列号、时间戳、关联编号、数据来源、载荷。
 */
@Serializable
data class UnifiedMessage(
    @SerialName("ver") val protoVersion: Int = Protocol.PROTO_VERSION,
    val type: MessageType,
    @SerialName("dev") val deviceId: String,
    @SerialName("sess") val sessionId: String,
    val seq: Long,
    val ts: Long,
    val src: DataSource,
    /** 关联编号：CMD_ACK / 执行结果回填对应 CMD 的 seq */
    val ref: Long? = null,
    val payload: JsonObject? = null
) {
    fun encode(): String = Protocol.json.encodeToString(serializer(), this)

    companion object {
        fun decode(raw: String): UnifiedMessage? = runCatching {
            Protocol.json.decodeFromString(serializer(), raw)
        }.getOrNull()
    }
}

/** MQTT 主题规划 —— 单一设备单一主题树，版本化前缀便于演进 */
object Topics {
    private const val BASE = "vrx/v1"
    /** APP → 统一控制服务：控制指令 */
    fun cmd(deviceId: String) = "$BASE/dev/$deviceId/cmd"
    /** 统一控制服务 → APP：设备状态 */
    fun state(deviceId: String) = "$BASE/dev/$deviceId/state"
    /** 统一控制服务 → APP：告警 */
    fun alarm(deviceId: String) = "$BASE/dev/$deviceId/alarm"
    /** 指令执行结果 */
    fun result(deviceId: String) = "$BASE/dev/$deviceId/result"
    /** 设备目录广播 */
    const val DIRECTORY = "$BASE/directory"
}
