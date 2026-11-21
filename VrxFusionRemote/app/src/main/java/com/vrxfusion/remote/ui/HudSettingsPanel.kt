package com.vrxfusion.remote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrxfusion.remote.ui.hud.instruments.AttitudeDial
import com.vrxfusion.remote.ui.hud.instruments.CenterFlightHud
import com.vrxfusion.remote.ui.hud.instruments.readoutSurface
import com.vrxfusion.remote.ui.hud.HudModel
import com.vrxfusion.remote.rotorpy.AttitudeAngles
import com.vrxfusion.remote.unified.Vec3Value
import com.vrxfusion.remote.ui.theme.*
import kotlin.math.roundToInt

/** 预设只改变四项显示参数，不触及速度、死区、权限或保护策略。 */
enum class HudPreset(val label: String,val preferences: HudPreferences) {
    CLEAR("清晰",HudPreferences(.9f,1f,1f,1f)),
    BALANCED("均衡",HudPreferences()),
    IMMERSIVE("沉浸",HudPreferences(.35f,.6f,.5f,.6f));

    internal fun applyTo(value: HudPreferences): HudPreferences = value.copy(
        joystickOpacity = preferences.joystickOpacity,
        attitudeOpacity = preferences.attitudeOpacity,
        mapOpacity = preferences.mapOpacity,
        positionOpacity = preferences.positionOpacity
    )
}

@Composable
fun HudSettingsPanel(value: HudPreferences,onChange: (HudPreferences)->Unit) {
    HudSettingsPanel(value,onChange,null)
}

@Composable
fun HudSettingsPanel(value: HudPreferences,onChange: (HudPreferences)->Unit,onReset: (() -> Unit)?,showPreview: Boolean=true) {
    val clean=value.normalized()
    @Composable fun option(label: String,checked: Boolean,change: (Boolean)->Unit) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text(label,color=TextPrimary,fontSize=13.sp,modifier=Modifier.weight(1f))
            Switch(checked=checked,onCheckedChange=change,modifier=Modifier.semantics { contentDescription=label })
        }
    }
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
            Text("HUD显示",color=TextPrimary,fontSize=18.sp,lineHeight=23.sp,fontWeight=FontWeight.SemiBold)
            Text("选择显示档位并调整仪表的不透明度。",color=TextSecondary,fontSize=12.sp,lineHeight=17.sp)
        }
        option("显示中央 HUD",clean.showCentralHud) { onChange(clean.copy(showCentralHud=it)) }
        if (clean.showCentralHud) {
            OpacityCard("中央 HUD 不透明度", "实时预览", Icons.Default.Explore, clean.centerHudOpacity,
                { onChange(clean.copy(centerHudOpacity=it)) }, Modifier)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            HudDisplayLevel.entries.forEach { level ->
                FilterChip(selected = clean.level == level, onClick = { onChange(clean.copy(level = level)) },
                    label = { Text(level.label, fontSize = 12.sp, maxLines = 1) }, modifier = Modifier.weight(1f))
            }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
            HudPreset.entries.forEach { preset ->
                val presetValue = preset.applyTo(clean)
                FilterChip(selected=clean==presetValue,onClick={ onChange(presetValue) },
                    label={ Text(preset.label,fontSize=13.sp) },modifier=Modifier.weight(1f),
                    colors=FilterChipDefaults.filterChipColors(containerColor=SurfaceDark,selectedContainerColor=ControlFill,
                        labelColor=TextSecondary,selectedLabelColor=ControlText),border=null)
            }
        }
        @Composable fun joystick(modifier: Modifier=Modifier) = OpacityCard("摇杆不透明度","升降与转向 · 前后与左右",Icons.Default.Gamepad,
            clean.joystickOpacity,{ onChange(clean.copy(joystickOpacity=it)) },modifier)
        @Composable fun attitude(modifier: Modifier=Modifier) = OpacityCard("姿态仪不透明度","横滚与俯仰仪表",Icons.Default.Explore,
            clean.attitudeOpacity,{ onChange(clean.copy(attitudeOpacity=it)) },modifier)
        @Composable fun map(modifier: Modifier=Modifier) = OpacityCard("地图不透明度","轨迹图与飞机标记",Icons.Default.Map,
            clean.mapOpacity,{ onChange(clean.copy(mapOpacity=it)) },modifier)
        @Composable fun position(modifier: Modifier=Modifier) = OpacityCard("位置数据不透明度","X/Y 坐标",Icons.Default.PinDrop,
            clean.positionOpacity,{ onChange(clean.copy(positionOpacity=it)) },modifier)
        BoxWithConstraints {
            if (maxWidth>=420.dp) Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) { joystick(Modifier.weight(1f)); attitude(Modifier.weight(1f)) }
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) { map(Modifier.weight(1f)); position(Modifier.weight(1f)) }
            } else Column(verticalArrangement=Arrangement.spacedBy(10.dp)) { joystick(); attitude(); map(); position() }
        }
        if (showPreview) HudPreview(clean)
        option("显示姿态仪",clean.showAttitude) { onChange(clean.copy(showAttitude=it)) }
        option("显示轨迹图",clean.showMap) { onChange(clean.copy(showMap=it)) }
        option("显示 X/Y 坐标",clean.showPosition) { onChange(clean.copy(showPosition=it)) }
        option("操纵时淡出地图和辅助读数",clean.dimDuringFlight) { onChange(clean.copy(dimDuringFlight=it)) }
        option("交换姿态仪与轨迹图位置",clean.swapInstruments) { onChange(clean.copy(swapInstruments=it)) }
        Text("仪表尺寸 ${(clean.instrumentScale*100).roundToInt()}%",color=TextPrimary,fontSize=12.sp)
        HudSlider(clean.instrumentScale,{ onChange(clean.copy(instrumentScale=it)) },.8f..1f)
        FilledTonalButton(onClick={ onReset?.invoke() ?: onChange(HudPreferences()) },modifier=Modifier.fillMaxWidth().heightIn(min=44.dp)) {
            Text("恢复显示默认值",fontSize=12.sp)
        }
    }
}

@Composable
private fun OpacityCard(label: String,description: String,icon: ImageVector,amount: Float,onChange: (Float)->Unit,modifier: Modifier) {
    Surface(modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),color=SurfaceDark) {
        Column(Modifier.padding(horizontal=12.dp,vertical=10.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Icon(icon,null,tint=AccentBlue,modifier=Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(label.removeSuffix("不透明度"),fontSize=14.sp,lineHeight=19.sp,color=TextPrimary,fontWeight=FontWeight.Medium)
                    Text(description,fontSize=11.sp,lineHeight=15.sp,color=TextSecondary)
                }
                Text("${(amount*100).roundToInt()}%",color=AccentBlue,fontSize=13.sp,fontWeight=FontWeight.SemiBold)
            }
            HudSlider(amount,onChange,.2f..1f,Modifier.semantics { contentDescription=label })
        }
    }
}

/** 使用原生滑块的触摸和无障碍逻辑，视觉用细轨与圆形滑块。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HudSlider(value: Float,onChange: (Float)->Unit,range: ClosedFloatingPointRange<Float>,
    modifier: Modifier=Modifier) {
    Slider(value=value,onValueChange=onChange,valueRange=range,modifier=modifier.fillMaxWidth().height(44.dp),
        thumb={ Box(Modifier.size(16.dp).background(AccentBlue,CircleShape)) },
        track={ state ->
            SliderDefaults.Track(state,modifier=Modifier.height(4.dp),thumbTrackGapSize=0.dp,drawStopIndicator=null)
        })
}

/** 只预览显示效果，不是UE实景，不绑定任何运动事件。 */
@Composable
fun HudPreview(value: HudPreferences,modifier: Modifier=Modifier,height: Dp=112.dp) {
    val display=value.normalized()
    val level = if (display.level == HudDisplayLevel.AUTO) HudDisplayLevel.L2 else display.level
    val scale=height/112.dp
    val showPosition = display.showPosition && level == HudDisplayLevel.L3
    Column(modifier.testTag("hud-preview"),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Text("显示预览",color=TextPrimary,fontSize=13.sp,fontWeight=FontWeight.Medium)
            Text("示意画面",color=TextSecondary,fontSize=10.sp)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF43586D),Color(0xFF283746),Color(0xFF54716F))))) {
            val mapWidth = minOf(64.dp,maxWidth*.42f)
            val centerWidth = 176.dp
            val mapSpace = (mapWidth * display.instrumentScale + 12.dp * scale) * 2
            val showMap = display.showMap && level != HudDisplayLevel.L1 &&
                (!display.showCentralHud || maxWidth - mapSpace >= centerWidth)
            for ((alignment,tag) in listOf(Alignment.BottomStart to "preview-stick-left", Alignment.BottomEnd to "preview-stick-right")) {
                Box(Modifier.align(alignment).padding(8.dp*scale).size(34.dp*scale).alpha(display.joystickOpacity)
                    .background(HudSurface,CircleShape).testTag(tag),contentAlignment=Alignment.Center) {
                    Box(Modifier.size(13.dp*scale).background(AccentBlue,CircleShape))
                }
            }
            if (display.showCentralHud) {
                // 在固定预览里缩放同一套仪表，给下方摇杆和上方位置读数留出空间。
                val availableWidth = if (showMap) maxWidth - mapSpace else maxWidth
                val previewTop = if (showPosition) 18.dp else 4.dp
                val availableHeight = height - previewTop - 44.dp * scale
                val hudScale = minOf(1f, availableWidth / centerWidth, availableHeight / 82.dp)
                if (hudScale > 0f) {
                    val density = LocalDensity.current
                    Box(Modifier.align(Alignment.TopCenter).padding(top=previewTop).width(centerWidth * hudScale)
                        .testTag("central-hud-preview")) {
                        CompositionLocalProvider(LocalDensity provides Density(density.density * hudScale, density.fontScale)) {
                            CenterFlightHud(HudModel(Vec3Value(0.0, 0.0, 1.4), 1.05, .24, AttitudeAngles(12.0, 3.0, 72.0)),
                                display.showAttitude, level != HudDisplayLevel.L1, display.centerHudOpacity, Modifier.fillMaxWidth())
                        }
                    }
                }
            } else if (display.showAttitude) AttitudeDial(0f,0f,
                Modifier.align(if (display.swapInstruments) Alignment.TopEnd else Alignment.TopStart)
                    .padding(horizontal=8.dp*scale,vertical=23.dp*scale).size(42.dp*scale*display.instrumentScale)
                    .alpha(display.attitudeOpacity).testTag("preview-attitude"))
            if (showMap) Canvas(
                Modifier.align(if (display.swapInstruments) Alignment.TopStart else Alignment.TopEnd)
                    .padding(horizontal=8.dp*scale,vertical=23.dp*scale)
                    .size(mapWidth*display.instrumentScale,40.dp*scale*display.instrumentScale)
                    .alpha(display.mapOpacity).background(HudSurface,RoundedCornerShape(8.dp)).testTag("preview-map")) {
                drawLine(AccentBlue,Offset(size.width*.2f,size.height*.75f),Offset(size.width*.65f,size.height*.35f),1.5.dp.toPx())
                drawCircle(WarnAmber,2.5.dp.toPx(),Offset(size.width*.65f,size.height*.35f))
            }
            if (showPosition) Text("X 0.0  Y 0.0 m",color=TextPrimary,fontSize=9.sp,lineHeight=13.sp,
                maxLines=1,modifier=Modifier.align(Alignment.TopCenter).padding(top=5.dp)
                    .background(readoutSurface(display.positionOpacity),RoundedCornerShape(6.dp))
                    .padding(horizontal=6.dp).testTag("preview-position"))
        }
    }
}
