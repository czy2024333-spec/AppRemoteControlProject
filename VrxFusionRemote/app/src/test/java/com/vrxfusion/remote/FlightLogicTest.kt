package com.vrxfusion.remote

import com.vrxfusion.remote.rotorpy.*
import com.vrxfusion.remote.ui.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class FlightLogicTest {
    @Test fun invalidHudOpacityCannotMakeControlsInvisible() {
        val cleaned=HudPreferences(-1f,Float.NaN,2f,Float.POSITIVE_INFINITY).normalized()
        assertEquals(.2f,cleaned.joystickOpacity,0f)
        assertEquals(HudPreferences().attitudeOpacity,cleaned.attitudeOpacity,0f)
        assertEquals(1f,cleaned.mapOpacity,0f)
        assertEquals(HudPreferences().positionOpacity,cleaned.positionOpacity,0f)
    }
    @Test fun displayChangesDoNotModifyOtherDisplayFields() {
        val original=HudPreferences()
        val changed=original.copy(mapOpacity=.3f).normalized()
        assertEquals(original.joystickOpacity,changed.joystickOpacity,0f)
        assertEquals(original.attitudeOpacity,changed.attitudeOpacity,0f)
        assertEquals(original.positionOpacity,changed.positionOpacity,0f)
    }
    private fun notice(s: RotorState=RotorState(), connected: Boolean=true, stale: Boolean=false,
        phase: LinkPhase=LinkPhase.READY, neutral: Boolean=true, error: String?=null) =
        flightNotice(s,connected,true,stale,phase,false,false,neutral,error)

    @Test fun lostDataOutranksProtectionAndDisablesFlightMessage() {
        val n=notice(RotorState(mode="PROTECTED"),connected=false,stale=true)
        assertEquals("STALE",n.id); assertEquals(FlightAction.CONNECTION,n.action)
    }
    @Test fun boundaryRecoveryOutranksGenericErrorsAndCannotResume() {
        val n=notice(RotorState(mode="PROTECTED",boundary_recovery=BoundaryRecovery(required=true,can_resume=false)),error="网络错误")
        assertEquals("RECOVERING",n.id); assertEquals(FlightAction.RESUME,n.action); assertFalse(n.actionEnabled)
    }
    @Test fun protectedMustReallyReleaseSticksBeforeResume() {
        val s=RotorState(mode="PROTECTED")
        assertFalse(notice(s,neutral=false).actionEnabled)
        assertTrue(notice(s,neutral=true).actionEnabled)
    }
    @Test fun initialReadOnlyIsNotPretendedAsReady() {
        val n=notice(phase=LinkPhase.DISCONNECTED)
        assertEquals("OBSERVING",n.id); assertEquals(FlightAction.REQUEST,n.action)
        assertEquals("READY",notice().id)
    }
    @Test fun positionsCannotChangeAttitude() {
        val angle=PI/12
        val s=RotorState(quaternion_xyzw=listOf(sin(angle),0.0,0.0,cos(angle)))
        val before=AttitudeMath.fromQuaternion(s.quaternion_xyzw)
        val after=AttitudeMath.fromQuaternion(s.copy(position_m=listOf(8.0,-6.0,3.0),velocity_m_s=listOf(2.0,1.0,1.0)).quaternion_xyzw)
        assertEquals(before,after); assertEquals(30.0,before!!.rollDeg,1e-8)
    }
    @Test fun sameQuaternionSignAndScaleHaveSameAttitude() {
        val q=listOf(.1,.2,.3,.9)
        val a=AttitudeMath.fromQuaternion(q)!!
        val b=AttitudeMath.fromQuaternion(q.map { it * -3 })!!
        assertEquals(a.rollDeg,b.rollDeg,1e-8); assertEquals(a.pitchDeg,b.pitchDeg,1e-8)
        assertNull(AttitudeMath.fromQuaternion(listOf(0.0,0.0,0.0,0.0)))
        assertNull(AttitudeMath.fromQuaternion(listOf(Double.NaN,0.0,0.0,1.0)))
    }
    @Test fun levelHorizonIsAtCanvasCenter() {
        assertEquals(52f,AttitudeMath.horizonY(104f,0.0),0f)
        assertTrue(AttitudeMath.horizonY(104f,10.0)>52f)
        assertEquals(1f,AttitudeMath.stableAngle(1f,1.05),0f)
    }
}
