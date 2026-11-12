package com.vrxfusion.remote.ui.state

enum class ControlStep { CONNECT, OBSERVE, REQUEST, TAKEOVER }

data class ControlStatus(val step: ControlStep?, val label: String, val tone: NoticeTone)

/** 主流程显示四步进度，失效、只读和保护显示独立状态。 */
fun ControlUi.headerStatus(): ControlStatus = when (stage) {
    ControlStage.STALE -> ControlStatus(null, "数据失效", NoticeTone.DANGER)
    ControlStage.OFFLINE -> ControlStatus(ControlStep.CONNECT, if (connecting) "连接中" else "待连接", NoticeTone.NORMAL)
    ControlStage.RECOVERING -> ControlStatus(null, "边界恢复", NoticeTone.DANGER)
    ControlStage.RELEASE, ControlStage.PROTECTED -> ControlStatus(null, "保护锁定", NoticeTone.DANGER)
    ControlStage.CAPABILITY_UNAVAILABLE -> ControlStatus(null, "能力待确认", NoticeTone.WARNING)
    ControlStage.READ_ONLY -> ControlStatus(null, "只读", NoticeTone.WARNING)
    ControlStage.REQUESTING -> ControlStatus(ControlStep.REQUEST, "握手中", NoticeTone.NORMAL)
    ControlStage.NEUTRAL -> ControlStatus(ControlStep.OBSERVE, "需归中", NoticeTone.WARNING)
    ControlStage.OBSERVING -> ControlStatus(ControlStep.OBSERVE, "观察中", NoticeTone.NORMAL)
    ControlStage.REJECTED -> if (notice.action == FlightAction.NONE) {
        ControlStatus(ControlStep.TAKEOVER, "操作有告警", NoticeTone.WARNING)
    } else {
        ControlStatus(ControlStep.REQUEST, "申请未完成", NoticeTone.WARNING)
    }
    ControlStage.READY, ControlStage.FLYING -> if (inputEnabled) {
        ControlStatus(ControlStep.TAKEOVER, if (stage == ControlStage.FLYING) "操纵中" else "控制有效", NoticeTone.GOOD)
    } else {
        ControlStatus(ControlStep.TAKEOVER, blockReason?.shortLabel() ?: "操纵暂停", NoticeTone.NORMAL)
    }
}

fun InputBlockReason.shortLabel(): String = when (this) {
    InputBlockReason.NOT_CONNECTED -> "未连接"
    InputBlockReason.STALE -> "数据失效"
    InputBlockReason.READ_ONLY -> "只读"
    InputBlockReason.BUSY -> "处理中"
    InputBlockReason.PROTECTED -> "保护中"
    InputBlockReason.SAFETY_BUSY -> "安全操作中"
    InputBlockReason.NOT_READY -> "未接管"
    InputBlockReason.SETTINGS_OPEN -> "设置中"
    InputBlockReason.ROUTE_MOVING -> "切换中"
}
