package com.vrxfusion.remote.ui

import kotlin.math.*

/** 平面坐标以米为单位：上方为+X、左方为+Y；只改变观察比例，不改变地图框或飞行指令。 */
data class TrajectoryViewport(val centerX: Double, val centerY: Double, val halfExtent: Double) {
    val gridMeters: Double get() {
        // 网格采用1、2、5序列，轨迹较远时避免固定2米网格挤成一片。
        val raw=halfExtent/6.0
        val unit=10.0.pow(floor(log10(raw)))
        return listOf(1.0,2.0,5.0,10.0).first { it*unit >= raw }*unit
    }
    fun scale(width: Float,height: Float,padding: Float): Float =
        ((min(width,height)/2-padding).coerceAtLeast(1f)/halfExtent).toFloat()

    fun project(x: Double,y: Double,width: Float,height: Float,padding: Float): Pair<Float,Float> {
        val scale=scale(width,height,padding)
        return (width/2-(y-centerY)*scale).toFloat() to (height/2-(x-centerX)*scale).toFloat()
    }
    companion object {
        fun fit(points: List<Pair<Double,Double>>): TrajectoryViewport {
            val valid=points.filter { it.first.isFinite() && it.second.isFinite() }
            if (valid.isEmpty()) return TrajectoryViewport(0.0,0.0,10.5)
            val minX=valid.minOf { it.first }; val maxX=valid.maxOf { it.first }
            val minY=valid.minOf { it.second }; val maxY=valid.maxOf { it.second }
            // 四周留出余量，当前飞机和航向线也不会贴边被裁掉；不再固定裁切±10米。
            return TrajectoryViewport(minX/2+maxX/2,minY/2+maxY/2,
                max(10.5,max(maxX-minX,maxY-minY)*.6))
        }

        /** 近景固定以当前位置为中心显示附近5米，较远的旧轨迹由总览查看。 */
        fun focus(position: Pair<Double,Double>): TrajectoryViewport =
            if (position.first.isFinite() && position.second.isFinite())
                TrajectoryViewport(position.first,position.second,2.5)
            else TrajectoryViewport(0.0,0.0,2.5)
    }
}
