package com.vrxfusion.remote.ui

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/** 格式版本仅属于显示配置，禁止导入权限、Token或保护开关。 */
@Serializable
data class HudDocument(val schemaVersion: Int=1,val display: HudPreferences)

object HudConfigCodec {
    private val json=Json { encodeDefaults=true; prettyPrint=true; ignoreUnknownKeys=false }
    fun encode(value: HudPreferences)=json.encodeToString(HudDocument(display=value))
    fun decode(text: String): HudPreferences {
        require(text.length<=8192) { "配置文件超过8KB" }
        val root = json.parseToJsonElement(text).jsonObject
        val display = root["display"]
        val document = if (display is JsonObject && "centerHudOnVideo" in display) {
            // 旧版默认为随视频显示。迁移时保留其他设置，等待用户主动开启中央 HUD。
            val legacy = display["centerHudOnVideo"] as? JsonPrimitive
            require(legacy != null && !legacy.isString && legacy.booleanOrNull != null) { "旧版中央 HUD 设置必须为布尔值" }
            JsonObject(root + ("display" to JsonObject(display - "centerHudOnVideo")))
        } else root
        val doc=json.decodeFromJsonElement<HudDocument>(document)
        require(doc.schemaVersion==1) { "不支持的显示配置格式版本" }
        val v=doc.display
        require(listOf(v.joystickOpacity,v.attitudeOpacity,v.mapOpacity,v.positionOpacity,v.centerHudOpacity)
            .all { it.isFinite() && it in .2f..1f }) { "不透明度必须在0.2～1之间" }
        require(v.instrumentScale.isFinite() && v.instrumentScale in .8f..1f) { "仪表尺寸必须在0.8～1之间" }
        return v
    }
}

/** 保存白名单显示字段和明确的回退点，旧版不透明度自动迁移。 */
class HudPreferencesStore(context: Context) {
    private val preferences=context.applicationContext.getSharedPreferences("flight_hud",Context.MODE_PRIVATE)
    fun load(): HudPreferences {
        preferences.getString("current_document",null)?.let {
            return runCatching { HudConfigCodec.decode(it) }.getOrDefault(HudPreferences())
        }
        val defaults=HudPreferences()
        fun read(key: String,fallback: Float)=runCatching { preferences.getFloat(key,fallback) }.getOrDefault(fallback)
        return HudPreferences(read("joystick",defaults.joystickOpacity),read("attitude",defaults.attitudeOpacity),
            read("map",defaults.mapOpacity),read("position",defaults.positionOpacity)).normalized()
    }
    fun revision()=preferences.getLong("revision",0)
    fun hasCheckpoint()=preferences.contains("checkpoint")
    fun save(value: HudPreferences): HudPreferences = value.normalized().also { v ->
        if (v!=load() || !preferences.contains("current_document"))
            preferences.edit().putString("current_document",HudConfigCodec.encode(v)).putLong("revision",revision()+1).apply()
    }
    // 回退点由用户显式保存，避免滑块每一帧覆盖上一份可用配置。
    fun checkpoint() { preferences.edit().putString("checkpoint",HudConfigCodec.encode(load())).apply() }
    fun rollback(): HudPreferences {
        val saved=preferences.getString("checkpoint",null) ?: error("尚未保存回退点")
        return save(HudConfigCodec.decode(saved))
    }
}
