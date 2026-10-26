package com.vrxfusion.remote

import com.vrxfusion.remote.ui.TrajectoryViewport
import org.junit.Assert.*
import org.junit.Test

class TrajectoryViewportTest {
    @Test fun overviewFitsFarTrajectoryAndCurrentPositionWithMargin() {
        val points=listOf(-40.0 to 100.0,80.0 to -120.0,0.0 to 0.0)
        val viewport=TrajectoryViewport.fit(points)
        for ((x,y) in points) {
            val (px,py)=viewport.project(x,y,256f,128f,12f)
            assertTrue(px in 12f..244f); assertTrue(py in 12f..116f)
        }
    }
    @Test fun closeViewCentersOnCurrentAircraftAndMagnifiesNearbyMotion() {
        val position=8.0 to -6.0
        val overview=TrajectoryViewport.fit(listOf(0.0 to 0.0,position))
        val close=TrajectoryViewport.focus(position)
        assertEquals(64f,close.project(8.0,-6.0,128f,100f,10f).first,0f)
        assertEquals(50f,close.project(8.0,-6.0,128f,100f,10f).second,0f)
        assertTrue(close.scale(128f,100f,10f)>overview.scale(128f,100f,10f))
        val moved=TrajectoryViewport.focus(9.0 to -7.0)
        assertEquals(64f,moved.project(9.0,-7.0,128f,100f,10f).first,0f)
        assertEquals(50f,moved.project(9.0,-7.0,128f,100f,10f).second,0f)
    }
    @Test fun missingAndInvalidPositionsCannotPoisonViewport() {
        for (points in listOf(emptyList(),listOf(Double.NaN to 1.0,0.0 to Double.POSITIVE_INFINITY))) {
            val viewport=TrajectoryViewport.fit(points)
            assertTrue(viewport.gridMeters.isFinite() && viewport.gridMeters>0)
            assertTrue(viewport.scale(128f,64f,10f).isFinite())
        }
        assertEquals(TrajectoryViewport.focus(0.0 to 0.0),TrajectoryViewport.focus(Double.NaN to 0.0))
    }
}
