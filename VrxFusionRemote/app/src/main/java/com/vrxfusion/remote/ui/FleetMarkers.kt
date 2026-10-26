package com.vrxfusion.remote.ui

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.vrxfusion.remote.unified.*
import com.vrxfusion.remote.ui.theme.*
import kotlin.math.*

data class FleetMarker(val member: UnifiedDeviceState,val self: Boolean,val fresh: Boolean) {
    val position get()=member.position.value!!
    val color get()=if (!fresh) Color.Gray else if (self) WarnAmber else
        listOf(AccentBlue,VirtualGreen,BoundPurple)[(member.device_id.hashCode() and Int.MAX_VALUE)%3]
}
fun shortAircraftId(id: String)=id.removePrefix("sim-drone-").takeLast(6)
fun fleetViewport(points: List<Pair<Double,Double>>): TrajectoryViewport {
    val p=points.filter {it.first.isFinite() && it.second.isFinite() && abs(it.first)<=1e6 && abs(it.second)<=1e6}
    if (p.isEmpty()) return TrajectoryViewport(0.0,0.0,3.5)
    val left=p.minOf {it.first};val right=p.maxOf {it.first};val bottom=p.minOf {it.second};val top=p.maxOf {it.second}
    return TrajectoryViewport((left+right)/2,(bottom+top)/2,max(3.5,max(right-left,top-bottom)*.65))
}
/** 坐标、单位或基准不兼容时不绘图，不能用[0,0,0]补成另一架飞机。 */
fun fleetMarkers(members: List<UnifiedDeviceState>,selfId: String?,fresh: (UnifiedDeviceState)->Boolean)=members.filter {
    it.coordinate_frame==UNIFIED_FRAME && it.position.unit=="m" && it.position.validity in setOf("VALID","STALE") &&
        it.position.value?.values()?.all { n->n.isFinite() && abs(n)<=1e6 }==true
}.map { FleetMarker(it,it.device_id==selfId,fresh(it) && it.position.valid("m")) }

fun aircraftCoordinateText(member: UnifiedDeviceState,selfId: String?,fresh: Boolean): String {
    val label=shortAircraftId(member.device_id)+(if (member.device_id==selfId) "本机" else "")
    return if (!fresh || !member.position.valid("m")) "$label · 位置失效" else member.position.value!!.let {
        "$label X%.1f Y%.1f Z%.1fm".format(java.util.Locale.ROOT,it.x,it.y,it.z)
    }
}

/** 近景里离开视野的他机用边缘空心标记，坐标行仍显示真实位置。 */
fun DrawScope.drawFleetMarker(marker: FleetMarker,point: Offset,edge: Boolean=false) {
    val p=if (edge) Offset(point.x.coerceIn(7.dp.toPx(),maxOf(7.dp.toPx(),size.width-7.dp.toPx())),
        point.y.coerceIn(7.dp.toPx(),maxOf(7.dp.toPx(),size.height-7.dp.toPx()))) else point
    val outside=p!=point
    val radius=if (marker.self) 5.dp.toPx() else 3.5.dp.toPx()
    if (outside) drawCircle(marker.color,radius,p,style=Stroke(1.5.dp.toPx())) else drawCircle(marker.color,radius,p)
    if (marker.self) drawCircle(marker.color,radius+3.dp.toPx(),p,style=Stroke(1.dp.toPx()))
    if (!outside && marker.member.attitude.valid()) marker.member.attitude.value?.let { q ->
        val yaw=atan2(2*(q.w*q.z+q.x*q.y),1-2*(q.y*q.y+q.z*q.z))
        drawLine(marker.color,p,p+Offset(-sin(yaw).toFloat()*12.dp.toPx(),-cos(yaw).toFloat()*12.dp.toPx()),1.5.dp.toPx())
    }
    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=marker.color.toArgb(); textSize=9.dp.toPx(); isFakeBoldText=marker.self }
    val text=shortAircraftId(marker.member.device_id)+(if (outside) "…" else "")
    drawContext.canvas.nativeCanvas.drawText(text,(p.x+6.dp.toPx()).coerceAtMost(maxOf(0f,size.width-paint.measureText(text))),
        (p.y-5.dp.toPx()).coerceAtLeast(paint.textSize),paint)
}
