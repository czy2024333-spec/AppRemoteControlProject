package com.vrxfusion.remote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrxfusion.remote.ui.theme.*

/** 同一Activity内的设置层，不使用Dialog/Popup，避免独立窗口唤起系统栏。 */
@Composable
fun FlightSettingsPanel(selectedTab: Int,onTabChange: (Int)->Unit,hud: HudPreferences,onHudChange: (HudPreferences)->Unit,
    onClose: ()->Unit,linkLabel: String="未连接",linkHealthy: Boolean=false,videoContent: (@Composable ()->Unit)?=null,
    configContent: (@Composable ()->Unit)?=null,safetyContent: (@Composable ()->Unit)?=null,connectionContent: @Composable ()->Unit) {
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    fun dismiss() { focus.clearFocus(); keyboard?.hide(); onClose() }
    fun select(index: Int) { focus.clearFocus(); keyboard?.hide(); onTabChange(index) }
    BackHandler { dismiss() }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("flight-settings-overlay")) {
        val wide=maxWidth>maxHeight
        val tallMenu=maxHeight>=360.dp
        // 蒙层截住底层点击；关闭不会恢复旧按压，操控层仍要求实际松手后重新拨杆。
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.5f)).clickable(
            interactionSource=remember { MutableInteractionSource() },indication=null,onClickLabel="关闭设置",
            onClick={ dismiss() }).testTag("settings-scrim"))
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(if (wide) 12.dp else 8.dp),contentAlignment=Alignment.Center) {
            Surface(Modifier.fillMaxHeight().widthIn(max=780.dp).fillMaxWidth().testTag("settings-panel"),
                shape=RoundedCornerShape(22.dp),color=Color(0xFF111D2B),shadowElevation=20.dp,
                border=androidx.compose.foundation.BorderStroke(1.dp,Color(0xFF2A3B50))) {
                Column {
                    Row(Modifier.fillMaxWidth().heightIn(min=64.dp)
                        .background(Brush.horizontalGradient(listOf(Color(0xFF192E47),Color(0xFF142234))))
                        .padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.Settings,null,tint=AccentBlue,modifier=Modifier.size(24.dp))
                        Column(Modifier.weight(1f)) {
                            Text("飞行设置",fontSize=19.sp,lineHeight=24.sp,fontWeight=FontWeight.Bold,color=TextPrimary)
                            Text("显示即时保存 · 操控暂时停用",fontSize=10.sp,lineHeight=14.sp,color=TextSecondary)
                        }
                        Button(onClick={ dismiss() },contentPadding=PaddingValues(horizontal=16.dp),
                            modifier=Modifier.height(44.dp).testTag("settings-done")) { Text("完成") }
                    }
                    if (!wide) Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        SettingsCategory("显示布局",selectedTab==1,{ select(1) },1,Modifier.weight(1f))
                        SettingsCategory("连接服务",selectedTab==0,{ select(0) },0,Modifier.weight(1f))
                        if (videoContent!=null) SettingsCategory("画面测试",selectedTab==2,{ select(2) },2,Modifier.weight(1f))
                    }
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        if (wide) Column(Modifier.width(124.dp).fillMaxHeight().background(Color(0xFF101A27)).padding(10.dp),
                            verticalArrangement=Arrangement.spacedBy(5.dp)) {
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                            if (tallMenu) Text("设置分类",fontSize=10.sp,lineHeight=14.sp,color=TextSecondary,modifier=Modifier.padding(6.dp))
                            SettingsCategory("显示布局",selectedTab==1,{ select(1) },1)
                            SettingsCategory("连接服务",selectedTab==0,{ select(0) },0)
                            if (videoContent!=null) SettingsCategory("画面测试",selectedTab==2,{ select(2) },2)
                            }
                            if (tallMenu || videoContent==null) Text(linkLabel,color=if (linkHealthy) VirtualGreen else TextSecondary,
                                fontSize=10.sp,lineHeight=14.sp,modifier=Modifier.padding(6.dp))
                        }
                        key(selectedTab) {
                            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(14.dp)
                                .testTag("settings-scroll"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                if (selectedTab==1) { HudSettingsPanel(hud,onHudChange); configContent?.invoke() }
                                else if (selectedTab==2 && videoContent!=null) videoContent()
                                else {
                                    Text("后端连接",fontSize=18.sp,fontWeight=FontWeight.SemiBold,color=TextPrimary)
                                    Surface(shape=RoundedCornerShape(14.dp),color=Color(0xFF1B2B3A),modifier=Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                            Text(linkLabel,color=if (linkHealthy) VirtualGreen else WarnAmber,fontWeight=FontWeight.SemiBold)
                                            Text("手机与电脑连接同一局域网。连接后先观察状态，再主动申请控制。",color=TextSecondary,fontSize=11.sp,lineHeight=17.sp)
                                        }
                                    }
                                    connectionContent()
                                }
                            }
                        }
                    }
                    HorizontalDivider(color=Color(0xFF26364A))
                    safetyContent?.invoke()
                    Text("关闭设置后重新拨杆操控；停止与保护规则始终生效。",fontSize=10.sp,lineHeight=15.sp,
                        color=TextSecondary,modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp))
                }
            }
        }
    }
}

@Composable
private fun SettingsCategory(label: String,selected: Boolean,onClick: ()->Unit,category: Int,modifier: Modifier=Modifier) {
    Surface(onClick=onClick,color=if (selected) Color(0xFF203D5C) else Color.Transparent,
        shape=RoundedCornerShape(12.dp),modifier=modifier.fillMaxWidth().heightIn(min=44.dp)) {
        Row(Modifier.padding(horizontal=10.dp,vertical=11.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
            Icon(when(category) { 1->Icons.Default.Tune; 2->Icons.Default.Videocam; else->Icons.Default.Link },null,tint=if (selected) AccentBlue else TextSecondary,modifier=Modifier.size(18.dp))
            Text(label,color=if (selected) TextPrimary else TextSecondary,fontSize=12.sp,lineHeight=17.sp,
                fontWeight=if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}
