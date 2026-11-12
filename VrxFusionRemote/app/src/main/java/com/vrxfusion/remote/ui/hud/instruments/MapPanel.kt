package com.vrxfusion.remote.ui.hud.instruments

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrxfusion.remote.ui.TrajectoryViewport
import com.vrxfusion.remote.ui.aircraftCoordinateText
import com.vrxfusion.remote.ui.drawFleetMarker
import com.vrxfusion.remote.ui.fleetMarkers
import com.vrxfusion.remote.ui.fleetViewport
import com.vrxfusion.remote.ui.shortAircraftId
import com.vrxfusion.remote.ui.hud.HudModel
import com.vrxfusion.remote.ui.hud.HudTelemetry
import com.vrxfusion.remote.ui.theme.AccentBlue
import com.vrxfusion.remote.ui.theme.HudSurface
import com.vrxfusion.remote.ui.theme.TextPrimary
import com.vrxfusion.remote.ui.theme.TextSecondary
import com.vrxfusion.remote.ui.theme.WarnAmber
import com.vrxfusion.remote.unified.UnifiedDeviceState
import com.vrxfusion.remote.unified.Vec3Value
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

@Composable
internal fun HudMap(telemetry: HudTelemetry, model: State<HudModel>, opacity: Float, modifier: Modifier, selfId: String?,
    memberFresh: (UnifiedDeviceState) -> Boolean, showTrail: Boolean, showOwnCoordinates: Boolean) {
    val position by remember(model) { derivedStateOf(structuralEqualityPolicy()) { model.value.position } }
    val yaw by remember(model) { derivedStateOf(structuralEqualityPolicy()) { model.value.attitude?.yawDeg } }
    PositionMap(position, yaw, if (showTrail) telemetry.trail.value else emptyList(), opacity, modifier,
        telemetry.members.value, selfId, memberFresh, showOwnCoordinates)
}

/** 单击只切换地图观察比例，框的尺寸和位置始终不变；不触发申请控制或运动输入。 */
@Composable
private fun PositionMap(
    coordinates: Vec3Value?, yawDeg: Double?, trail: List<Pair<Double, Double>>, opacity: Float, modifier: Modifier,
    members: List<UnifiedDeviceState>, selfId: String?, memberFresh: (UnifiedDeviceState) -> Boolean, showOwnCoordinates: Boolean
) {
    var focused by rememberSaveable { mutableStateOf(false) }
    val position = coordinates?.let { it.x to it.y }
    val validTrail = if (position == null) emptyList() else remember(trail) {
        trail.filter { it.first.isFinite() && it.second.isFinite() && abs(it.first) <= 1e6 && abs(it.second) <= 1e6 }
    }
    val markers = fleetMarkers(members, selfId, memberFresh).filter { position != null || !it.self }
    val mapPoints=validTrail+listOfNotNull(position,0.0 to 0.0)+markers.map { it.position.x to it.position.y }
    val near = focused && position != null
    val viewport=if (focused && position != null) TrajectoryViewport.focus(position)
        else if (members.isNotEmpty()) fleetViewport(mapPoints) else TrajectoryViewport.fit(mapPoints)
    val mode=if (near) "当前位置近景" else "完整轨迹总览"
    val gridLabel="%.2f".format(Locale.ROOT,viewport.gridMeters).trimEnd('0').trimEnd('.')
    Box(modifier.clip(RoundedCornerShape(12.dp))
        .clickable(enabled = position != null, role = Role.Button,
            onClickLabel = if (near) "恢复轨迹总览" else "放大当前位置") { focused = !focused }
        .semantics { stateDescription = mode }.testTag("position-panel")) {
        Column(Modifier.fillMaxSize().alpha(opacity).background(HudSurface).padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            // 地图只用有效的本机位置定位，其他成员保留各自的有效性判断。
            Canvas(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(Color(0x40101D2B)).testTag("position-map")) {
                val padding = if (near) 0f else 8.dp.toPx()
                fun point(x: Double,y: Double): Offset {
                    val p=viewport.project(x,y,size.width,size.height,padding)
                    return Offset(p.first,p.second)
                }
                val scale=viewport.scale(size.width,size.height,padding)
                val halfX=size.height/2/scale
                val halfY=size.width/2/scale
                val grid=viewport.gridMeters
                // 网格随观察中心和平面比例重新投影，切近景时飞机始终位于中心。
                val startX=floor((viewport.centerX-halfX)/grid).toLong()
                val endX=ceil((viewport.centerX+halfX)/grid).toLong()
                val startY=floor((viewport.centerY-halfY)/grid).toLong()
                val endY=ceil((viewport.centerY+halfY)/grid).toLong()
                for (i in startX..endX) drawLine(Color(0xFF303B47),
                    point(i*grid,viewport.centerY-halfY),point(i*grid,viewport.centerY+halfY))
                for (i in startY..endY) drawLine(Color(0xFF303B47),
                    point(viewport.centerX-halfX,i*grid),point(viewport.centerX+halfX,i*grid))
                if (position != null) drawCircle(TextSecondary, 2.dp.toPx(), point(0.0, 0.0))
                if (validTrail.size > 1) {
                    val path=Path(); validTrail.forEachIndexed { i,p -> val pixel=point(p.first,p.second)
                        if (i == 0) path.moveTo(pixel.x,pixel.y) else path.lineTo(pixel.x,pixel.y) }
                    drawPath(path,AccentBlue,style=Stroke(2.dp.toPx()))
                }
                if (members.isNotEmpty()) markers.forEach { drawFleetMarker(it,point(it.position.x,it.position.y),edge=true) }
                else position?.let { (x, y) ->
                    val p = point(x, y)
                    drawCircle(WarnAmber, 4.dp.toPx(), p)
                    if (yawDeg != null) {
                        val yaw = Math.toRadians(yawDeg)
                        drawLine(WarnAmber, p, p + Offset(-sin(yaw).toFloat(), -cos(yaw).toFloat()) * 8.dp.toPx(), 2.dp.toPx())
                    }
                }
            }
            if (members.isEmpty()) {
                if (coordinates != null && showOwnCoordinates) {
                    Text("X %.2f  Y %.2f".format(Locale.ROOT, coordinates.x, coordinates.y),
                        color=TextPrimary,fontSize=9.sp,lineHeight=10.sp,fontFamily=FontFamily.Monospace)
                }
                Text("${gridLabel}m/格", color=TextPrimary,fontSize=9.sp,lineHeight=10.sp)
            } else members.sortedBy { it.device_id }.forEach { member ->
                val valid = memberFresh(member) && (member.device_id != selfId || position != null)
                val label = if (member.device_id != selfId || !valid) {
                    aircraftCoordinateText(member, selfId, valid)
                } else if (showOwnCoordinates && coordinates != null) {
                    "%s本机 X%.1f Y%.1f".format(Locale.ROOT, shortAircraftId(member.device_id), coordinates.x, coordinates.y)
                } else {
                    "${shortAircraftId(member.device_id)}本机"
                }
                Text(label,fontSize=8.sp,lineHeight=9.sp,maxLines=1,
                    overflow=TextOverflow.Ellipsis,color=if (!valid) Color.Gray else if (member.device_id==selfId) WarnAmber else TextPrimary,
                    modifier=Modifier.testTag("fleet-coordinate-${member.device_id}"))
            }
        }
        if (position == null) InstrumentFlag("本机位置 N/A", "map-invalid-flag", Modifier.padding(6.dp))
    }
}
