package com.vrxfusion.remote.ui

import com.vrxfusion.remote.rotorpy.*

enum class FlightAction { CONNECTION, REQUEST, RESUME, NONE }
enum class NoticeTone { NORMAL, GOOD, WARNING, DANGER }
data class FlightNotice(val id: String, val title: String, val detail: String,
    val tone: NoticeTone, val action: FlightAction, val actionLabel: String, val actionEnabled: Boolean)

/**
 * 页面只有一个提示出口。优先级：数据失效→未连接→保护/恢复→操作失败→接管→操纵/悬停。
 * 按钮和文案使用同一份决策，避免提示“可操纵”而按钮仍被禁用。
 */
fun flightNotice(state: RotorState?, connected: Boolean, running: Boolean, stale: Boolean,
    phase: LinkPhase, busy: Boolean, holding: Boolean, neutral: Boolean, error: String?,writable: Boolean=true): FlightNotice {
    fun notice(id: String,title: String,detail: String,tone: NoticeTone,action: FlightAction,
        label: String,enabled: Boolean=true) = FlightNotice(id,title,detail,tone,action,label,enabled)
    if (stale) return notice("STALE","状态失效，操纵已暂停",error ?: "等待实时状态恢复；恢复后重新申请控制。",NoticeTone.DANGER,
        FlightAction.CONNECTION,"连接设置")
    if (!connected) return notice("OFFLINE",if (running) "正在连接后端" else "尚未连接后端",
        error ?: "填写电脑IP与APP Token，再连接查看状态。",NoticeTone.WARNING,FlightAction.CONNECTION,"连接设置")
    if (state?.mode == "PROTECTED") {
        val b=state.boundary_recovery
        if (b?.required == true && !b.can_resume) return notice("RECOVERING","边界恢复中，请等待停稳",
            "高度需%.2f～%.2fm，半径≤%.2fm，速度≤%.2fm/s。".format(b.min_resume_altitude_m,b.max_resume_altitude_m,
                b.max_resume_horizontal_radius_m,b.max_resume_speed_m_s),NoticeTone.DANGER,FlightAction.RESUME,"等待恢复",false)
        if (!neutral) return notice("RELEASE","保护已锁存，请松开双摇杆","松手后才允许解除保护。",NoticeTone.DANGER,
            FlightAction.RESUME,"请先松手",false)
        val reason=when(state.protection_reason) {
            "INPUT_TIMEOUT" -> "运动输入超时（INPUT_TIMEOUT）。"
            null -> ""
            else -> "保护原因：${state.protection_reason}。"
        }
        return notice("PROTECTED","保护已锁存，可确认解除",reason+"解除后保持悬停，再主动申请控制。",NoticeTone.DANGER,
            FlightAction.RESUME,if (!writable) "只读观察" else if (busy) "处理中" else "解除保护",!busy && writable)
    }
    // 保护/断线必须先显示；目录暂时无法校验与来源只读也分别给出原因。
    if (!writable && error?.contains("设备能力刷新失败")==true)
        return notice("CAPABILITY_UNAVAILABLE","能力校验失败，操纵已暂停",error,NoticeTone.WARNING,
            FlightAction.CONNECTION,"查看连接",true)
    if (!writable) return notice("READ_ONLY","当前只读，操纵未启用",
        error ?: "请使用APP来源Token，并确认设备能力及APP通路已启用。",NoticeTone.WARNING,
        FlightAction.NONE,"只读观察",false)
    if (phase != LinkPhase.READY) {
        if (busy) return notice("REQUESTING","正在确认控制权","保持双摇杆松手，等待服务端确认。",NoticeTone.NORMAL,
            FlightAction.REQUEST,"申请中",false)
        if (!neutral) return notice("NEUTRAL","请先松开双摇杆","实际松手后才能申请APP控制。",NoticeTone.WARNING,
            FlightAction.REQUEST,"请先松手",false)
        if (!error.isNullOrBlank()) return notice("REJECTED","上一次操作未完成",error,NoticeTone.WARNING,
            FlightAction.REQUEST,"重新申请")
        return notice("OBSERVING","正在观察，尚未接管","点击申请控制，确认后再推动摇杆。",NoticeTone.NORMAL,
            FlightAction.REQUEST,"申请控制")
    }
    // 已获得控制权也可能出现操作失败，不能被“控制就绪”覆盖。
    if (!error.isNullOrBlank()) return notice("REJECTED","上一次操作未完成",error,NoticeTone.WARNING,
        FlightAction.NONE,"已接管",false)
    return notice(if (holding) "FLYING" else "READY",if (holding) "正在操纵" else "控制就绪 · 悬停中",
        if (holding) "双摇杆松手后自动停止运动。" else "推动摇杆开始；松手停止。",NoticeTone.GOOD,
        FlightAction.NONE,"已接管",false)
}
