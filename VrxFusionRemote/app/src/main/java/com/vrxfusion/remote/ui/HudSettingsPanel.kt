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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrxfusion.remote.ui.theme.*
import kotlin.math.roundToInt

/** 预设只改变四项显示参数，不触及速度、死区、权限或保护策略。 */
enum class HudPreset(val label: String,val preferences: HudPreferences) {
    CLEAR("清晰",HudPreferences(.9f,1f,1f,1f)),
    BALANCED("均衡",HudPreferences()),
    IMMERSIVE("沉浸",HudPreferences(.35f,.6f,.5f,.6f))
}

@Composable
fun HudSettingsPanel(value: HudPreferences,onChange: (HudPreferences)->Unit) {
    val clean=value.normalized()
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
            Text("HUD显示",color=TextPrimary,fontSize=18.sp,lineHeight=23.sp,fontWeight=FontWeight.SemiBold)
            Text("独立调节叠加层，不透明度越低，底层画面越清楚。",color=TextSecondary,fontSize=11.sp,lineHeight=16.sp)
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
            HudPreset.entries.forEach { preset ->
                FilterChip(selected=clean==preset.preferences,onClick={ onChange(preset.preferences) },
                    label={ Text(preset.label,fontSize=12.sp) },modifier=Modifier.weight(1f))
            }
        }
        @Composable fun joystick(modifier: Modifier=Modifier) = OpacityCard("摇杆不透明度","双杆与操作标签",Icons.Default.Gamepad,
            clean.joystickOpacity,{ onChange(clean.copy(joystickOpacity=it)) },modifier)
        @Composable fun attitude(modifier: Modifier=Modifier) = OpacityCard("姿态仪不透明度","横滚与俯仰仪表",Icons.Default.Explore,
            clean.attitudeOpacity,{ onChange(clean.copy(attitudeOpacity=it)) },modifier)
        @Composable fun map(modifier: Modifier=Modifier) = OpacityCard("地图不透明度","轨迹图与飞机标记",Icons.Default.Map,
            clean.mapOpacity,{ onChange(clean.copy(mapOpacity=it)) },modifier)
        @Composable fun position(modifier: Modifier=Modifier) = OpacityCard("位置数据不透明度","坐标、速度与接收频率",Icons.Default.PinDrop,
            clean.positionOpacity,{ onChange(clean.copy(positionOpacity=it)) },modifier)
        BoxWithConstraints {
            if (maxWidth>=420.dp) Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) { joystick(Modifier.weight(1f)); attitude(Modifier.weight(1f)) }
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) { map(Modifier.weight(1f)); position(Modifier.weight(1f)) }
            } else Column(verticalArrangement=Arrangement.spacedBy(10.dp)) { joystick(); attitude(); map(); position() }
        }
        HudPreview(clean)
        @Composable fun option(label: String,checked: Boolean,change: (Boolean)->Unit) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(label,color=TextPrimary,fontSize=12.sp,modifier=Modifier.weight(1f))
                Switch(checked=checked,onCheckedChange=change)
            }
        }
        option("显示姿态仪",clean.showAttitude) { onChange(clean.copy(showAttitude=it)) }
        option("显示轨迹图",clean.showMap) { onChange(clean.copy(showMap=it)) }
        option("显示位置数据行",clean.showPosition) { onChange(clean.copy(showPosition=it)) }
        option("交换姿态仪与轨迹图位置",clean.swapInstruments) { onChange(clean.copy(swapInstruments=it)) }
        Text("仪表尺寸 ${(clean.instrumentScale*100).roundToInt()}%",color=TextPrimary,fontSize=12.sp)
        Slider(value=clean.instrumentScale,onValueChange={ onChange(clean.copy(instrumentScale=it)) },valueRange=.8f..1f)
        Text("停止、保护和关键提示保持醒目；所有显示设置自动保存。",color=TextSecondary,fontSize=11.sp,lineHeight=17.sp)
        OutlinedButton(onClick={ onChange(HudPreferences()) },modifier=Modifier.fillMaxWidth().heightIn(min=44.dp)) {
            Text("恢复显示默认值",fontSize=12.sp)
        }
    }
}

@Composable
private fun OpacityCard(label: String,description: String,icon: ImageVector,amount: Float,onChange: (Float)->Unit,modifier: Modifier) {
    Surface(modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),color=Color(0xFF1A2A3A),
        border=androidx.compose.foundation.BorderStroke(1.dp,Color(0xFF2A3D51))) {
        Column(Modifier.padding(horizontal=12.dp,vertical=10.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Icon(icon,null,tint=AccentBlue,modifier=Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(label.removeSuffix("不透明度"),fontSize=13.sp,lineHeight=17.sp,color=TextPrimary,fontWeight=FontWeight.Medium)
                    Text(description,fontSize=10.sp,lineHeight=14.sp,color=TextSecondary)
                }
                Text("${(amount*100).roundToInt()}%",color=AccentBlue,fontSize=13.sp,fontWeight=FontWeight.SemiBold)
            }
            Slider(value=amount,onValueChange=onChange,valueRange=.2f..1f,
                modifier=Modifier.fillMaxWidth().height(36.dp).semantics { contentDescription=label })
        }
    }
}

/** 只预览显示效果，不是UE实景，不绑定任何运动事件。 */
@Composable
private fun HudPreview(value: HudPreferences) {
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("显示预览",color=TextSecondary,fontSize=11.sp)
        Box(Modifier.fillMaxWidth().height(106.dp).clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF284764),Color(0xFF10283E),Color(0xFF365D63))))) {
            Text("画面底层",color=Color.White.copy(alpha=.45f),fontSize=12.sp,modifier=Modifier.align(Alignment.Center))
            Box(Modifier.align(Alignment.BottomStart).padding(12.dp).size(40.dp).alpha(value.joystickOpacity)
                .background(Color(0xDD172633),CircleShape),contentAlignment=Alignment.Center) {
                Box(Modifier.size(17.dp).background(AccentBlue,CircleShape))
            }
            AttitudeDial(0f,0f,Modifier.align(Alignment.TopStart).padding(10.dp).size(32.dp).alpha(value.attitudeOpacity))
            Canvas(Modifier.align(Alignment.TopEnd).padding(10.dp).size(60.dp,45.dp).alpha(value.mapOpacity)
                .background(Color(0xD9102033),RoundedCornerShape(6.dp))) {
                drawLine(AccentBlue,Offset(size.width*.2f,size.height*.75f),Offset(size.width*.65f,size.height*.35f),2.dp.toPx())
                drawCircle(WarnAmber,3.dp.toPx(),Offset(size.width*.65f,size.height*.35f))
            }
            Text("X 0.00  Y 0.00  Z 1.00 m",color=TextPrimary,fontSize=9.sp,
                modifier=Modifier.align(Alignment.BottomEnd).padding(10.dp).alpha(value.positionOpacity))
        }
    }
}
