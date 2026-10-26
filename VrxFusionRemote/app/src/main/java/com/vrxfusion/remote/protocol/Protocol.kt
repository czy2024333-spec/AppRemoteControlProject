package com.vrxfusion.remote.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 统一控制/状态协议 v0.1 —— 对应 docs/PROTOCOL_v0.1.md（10/16 冻结首版接口）。
 * 传输：WebSocket 文本帧（UTF-8 JSON，一帧一条）。
 * 角色 B 交付：APP 与统一控制服务之间双向通信。
 */
object Protocol {
    const val PROTO_VERSION = 1
    /** 连续指令默认发送频率（Hz）—— IF-003 */
    const val CMD_RATE_HZ = 10
    /** 连续指令有效期（毫秒）—— 与服务端 LEASE_TTL 对齐 */
    const val CMD_TTL_MS = 1000L
    /** 输入中断看门狗（毫秒）—— SAFE-004 */
    const val INPUT_WATCHDOG_MS = 600L
    /** 状态显示频率（Hz）—— PERF-002 */
    const val STATE_RATE_HZ = 20
    /** 状态过期阈值（毫秒）—— APP-005：过期不得显示为实时 */
    const val STATE_STALE_MS = 500L
    /** 心跳周期（毫秒） */
    const val HEARTBEAT_MS = 2000L

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }
}

/** 消息类型（协议第 2 节） */
object MsgType {
    const val HELLO = "hello"
    const val WELCOME = "welcome"
    const val ROSTER = "roster"
    const val LEASE_REQ = "lease_req"
    const val LEASE_REL = "lease_rel"
    const val LEASE_STATE = "lease_state"
    const val CMD = "cmd"
    const val ACK = "ack"
    const val STATE = "state"
    const val HEARTBEAT = "heartbeat"
    const val ERROR = "error"
}

/** 角色（APP-002）：观察员只读，服务端强制拒绝其指令（E_ROLE） */
enum class Role(val wire: String, val label: String) {
    ADMIN("admin", "管理员"),
    OPERATOR("operator", "操作员"),
    OBSERVER("observer", "观察员");

    val canControl: Boolean get() = this != OBSERVER
}

/** 应答状态（APP-006 / CTL-004） */
enum class AckStatus(val wire: String) {
    RECEIVED("RECEIVED"), EXECUTING("EXECUTING"), DONE("DONE"),
    FAILED("FAILED"), TIMEOUT("TIMEOUT"), REJECTED("REJECTED");

    companion object {
        fun of(w: String?) = entries.firstOrNull { it.wire == w }
    }
}

/**
 * 协议信封 —— 通用消息头（协议第 1 节）。
 * 字段与 docs/PROTOCOL_v0.1.md 一致，变更须先评估，不擅自改字段。
 */
data class WsMessage(
    val type: String,
    val session: String = "",
    val seq: Long = 0,
    val ts: Long = System.currentTimeMillis(),
    val src: String = "",
    val tgt: String? = null,
    val corr: String? = null,
    val ttlMs: Long? = null,
    val payload: JsonObject = buildJsonObject {}
) {
    fun encode(): String {
        val o = buildJsonObject {
            put("v", Protocol.PROTO_VERSION)
            put("type", type)
            put("session", session)
            put("seq", seq)
            put("ts", ts)
            put("src", src)
            tgt?.let { put("tgt", it) }
            corr?.let { put("corr", it) }
            ttlMs?.let { put("ttl_ms", it) }
            put("payload", payload)
        }
        return o.toString()
    }

    fun str(key: String): String? =
        (payload[key] as? JsonPrimitive)?.content

    companion object {
        fun decode(raw: String): WsMessage? = runCatching {
            val o = Protocol.json.parseToJsonElement(raw).jsonObject
            fun s(k: String) = o[k]?.jsonPrimitive?.content
            fun l(k: String) = o[k]?.jsonPrimitive?.content?.toLongOrNull()
            WsMessage(
                type = s("type") ?: return null,
                session = s("session") ?: "",
                seq = l("seq") ?: 0,
                ts = l("ts") ?: System.currentTimeMillis(),
                src = s("src") ?: "",
                tgt = s("tgt"),
                corr = s("corr"),
                ttlMs = l("ttl_ms"),
                payload = (o["payload"] as? JsonObject) ?: buildJsonObject {}
            )
        }.getOrNull()
    }
}
