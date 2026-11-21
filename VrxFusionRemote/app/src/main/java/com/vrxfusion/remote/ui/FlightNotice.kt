package com.vrxfusion.remote.ui

import com.vrxfusion.remote.rotorpy.LinkPhase
import com.vrxfusion.remote.rotorpy.RotorState
import com.vrxfusion.remote.ui.state.deriveFlightNotice

typealias FlightAction = com.vrxfusion.remote.ui.state.FlightAction
typealias NoticeTone = com.vrxfusion.remote.ui.state.NoticeTone
typealias FlightNotice = com.vrxfusion.remote.ui.state.FlightNotice

/** 操纵页仍使用原来的调用入口，状态推导放在独立的纯函数中。 */
fun flightNotice(
    state: RotorState?, connected: Boolean, running: Boolean, stale: Boolean,
    phase: LinkPhase, busy: Boolean, holding: Boolean, neutral: Boolean, error: String?, writable: Boolean = true
): FlightNotice = deriveFlightNotice(
    state?.mode, state?.boundary_recovery, state?.protection_reason, connected, running, stale,
    phase, busy, holding, neutral, error, writable
)
