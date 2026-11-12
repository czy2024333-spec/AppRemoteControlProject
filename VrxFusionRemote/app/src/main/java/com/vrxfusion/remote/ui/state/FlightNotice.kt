package com.vrxfusion.remote.ui.state

import androidx.compose.runtime.Immutable
import com.vrxfusion.remote.rotorpy.BoundaryRecovery
import com.vrxfusion.remote.rotorpy.LinkPhase

enum class ControlStage {
    STALE, OFFLINE, RECOVERING, RELEASE, PROTECTED, CAPABILITY_UNAVAILABLE, READ_ONLY,
    REQUESTING, NEUTRAL, REJECTED, OBSERVING, READY, FLYING
}

enum class FlightAction { CONNECTION, REQUEST, RESUME, NONE }
enum class NoticeTone { NORMAL, GOOD, WARNING, DANGER }

@Immutable
data class FlightNotice(
    val stage: ControlStage,
    val title: String,
    val detail: String,
    val tone: NoticeTone,
    val action: FlightAction,
    val actionLabel: String,
    val actionEnabled: Boolean
) {
    // 旧 UI 用例仍可读取字符串标识，新展示逻辑使用 stage 枚举。
    val id: String get() = stage.name
}

/** 旧页面与新快照共用提示优先级、文案及主按钮判断。 */
internal fun deriveFlightNotice(
    mode: String?,
    boundary: BoundaryRecovery?,
    protectionReason: String?,
    connected: Boolean,
    running: Boolean,
    stale: Boolean,
    phase: LinkPhase,
    busy: Boolean,
    holding: Boolean,
    neutral: Boolean,
    error: String?,
    writable: Boolean,
    resumeAvailable: Boolean = true
): FlightNotice {
    fun notice(
        stage: ControlStage, title: String, detail: String, tone: NoticeTone, action: FlightAction,
        label: String, enabled: Boolean = true
    ) = FlightNotice(stage, title, detail, tone, action, label, enabled)

    if (stale) return notice(ControlStage.STALE, "状态失效，操纵已暂停",
        error ?: "等待实时状态恢复；恢复后重新申请控制。", NoticeTone.DANGER, FlightAction.CONNECTION, "连接设置")
    if (!connected) return notice(ControlStage.OFFLINE, if (running) "正在连接后端" else "尚未连接后端",
        error ?: "填写电脑IP与APP Token，再连接查看状态。", NoticeTone.WARNING, FlightAction.CONNECTION, "连接设置")
    if (mode == "PROTECTED") {
        if (boundary?.required == true && !boundary.can_resume) {
            val limits = "高度需%.2f～%.2fm，半径≤%.2fm，速度≤%.2fm/s。".format(
                boundary.min_resume_altitude_m, boundary.max_resume_altitude_m,
                boundary.max_resume_horizontal_radius_m, boundary.max_resume_speed_m_s
            )
            return notice(ControlStage.RECOVERING, "边界恢复中，请等待停稳", limits, NoticeTone.DANGER,
                FlightAction.RESUME, "等待恢复", false)
        }
        if (!neutral) return notice(ControlStage.RELEASE, "保护已锁存，请松开双摇杆", "松手后才允许解除保护。",
            NoticeTone.DANGER, FlightAction.RESUME, "请先松手", false)
        val reason = when (protectionReason) {
            "INPUT_TIMEOUT" -> "运动输入超时（INPUT_TIMEOUT）。"
            null -> ""
            else -> "保护原因：$protectionReason。"
        }
        if (!writable || !resumeAvailable) {
            val detail = if (!writable) "当前凭据仅可观察，无法解除保护。" else "当前设备未开放解除保护。"
            return notice(ControlStage.PROTECTED, "保护已锁存", reason + detail, NoticeTone.DANGER,
                FlightAction.RESUME, if (!writable) "只读观察" else "无法解除", false)
        }
        return notice(ControlStage.PROTECTED, "保护已锁存，可确认解除", reason + "解除后保持悬停，再主动申请控制。",
            NoticeTone.DANGER, FlightAction.RESUME, if (busy) "处理中" else "解除保护", !busy)
    }
    if (!writable) return flightAccessNotice(error)
    if (phase != LinkPhase.READY) {
        if (busy) return notice(ControlStage.REQUESTING, "正在确认控制权", "保持双摇杆松手，等待服务端确认。",
            NoticeTone.NORMAL, FlightAction.REQUEST, "申请中", false)
        if (!neutral) return notice(ControlStage.NEUTRAL, "请先松开双摇杆", "实际松手后才能申请APP控制。",
            NoticeTone.WARNING, FlightAction.REQUEST, "请先松手", false)
        if (!error.isNullOrBlank()) return notice(ControlStage.REJECTED, "上一次操作未完成", error,
            NoticeTone.WARNING, FlightAction.REQUEST, "重新申请")
        return notice(ControlStage.OBSERVING, "正在观察，尚未接管", "点击申请控制，确认后再推动摇杆。",
            NoticeTone.NORMAL, FlightAction.REQUEST, "申请控制")
    }
    // READY 时单次操作失败仍须显示原因，不能据此推断握手已失效。
    if (!error.isNullOrBlank()) return notice(ControlStage.REJECTED, "上一次操作未完成", error,
        NoticeTone.WARNING, FlightAction.NONE, "已接管", false)
    return notice(if (holding) ControlStage.FLYING else ControlStage.READY,
        if (holding) "正在操纵" else "控制就绪 · 悬停中",
        if (holding) "双杆回中后停发运动并请求停止。" else "推动摇杆开始，双杆回中后停发。",
        NoticeTone.GOOD, FlightAction.NONE, "已接管", false)
}

internal fun flightAccessNotice(error: String?): FlightNotice =
    if (error?.contains("设备能力刷新失败") == true) {
        FlightNotice(ControlStage.CAPABILITY_UNAVAILABLE, "能力校验失败，操纵已暂停", error,
            NoticeTone.WARNING, FlightAction.CONNECTION, "查看连接", true)
    } else {
        FlightNotice(ControlStage.READ_ONLY, "当前只读，操纵未启用",
            error ?: "请使用APP来源Token，并确认设备能力及APP通路已启用。", NoticeTone.WARNING,
            FlightAction.NONE, "只读观察", false)
    }
