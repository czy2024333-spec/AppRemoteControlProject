package com.vrxfusion.remote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrxfusion.remote.ui.theme.*

/** 同一Activity内的设置层，不使用Dialog/Popup，避免独立窗口唤起系统栏。 */
@Composable
fun FlightSettingsPanel(selectedTab: Int,onTabChange: (Int)->Unit,hud: HudPreferences,onHudChange: (HudPreferences)->Unit,
    onClose: ()->Unit,linkLabel: String="未连接",linkHealthy: Boolean=false,videoContent: (@Composable ()->Unit)?=null,
    configContent: (@Composable ()->Unit)?=null,safetyContent: (@Composable ()->Unit)?=null,
    onResetHud: (() -> Unit)?=null,motionProgress: Float=1f,motionInputBlocked: Boolean=false,
    connectionContent: @Composable ()->Unit) {
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    fun dismiss() { focus.clearFocus(); keyboard?.hide(); onClose() }
    var tabForward by remember { mutableStateOf(true) }
    fun select(index: Int) {
        focus.clearFocus(); keyboard?.hide()
        tabForward=listOf(1,0,2).indexOf(index)>=listOf(1,0,2).indexOf(selectedTab)
        onTabChange(index)
    }
    val progress=motionProgress.coerceIn(0f,1f)
    BackHandler { dismiss() }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("flight-settings-overlay")) {
        val wide=maxWidth>maxHeight
        val tallMenu=maxHeight>=360.dp
        // 蒙层截住底层点击；关闭不会恢复旧按压，操控层仍要求实际松手后重新拨杆。
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.4f*progress)).clickable(
            interactionSource=remember { MutableInteractionSource() },indication=null,onClickLabel="关闭设置",
            onClick={ dismiss() }).testTag("settings-scrim"))
        Box(Modifier.fillMaxSize().windowInsetsPadding(flightSafeInsets()).imePadding().padding(if (wide) 12.dp else 8.dp),contentAlignment=Alignment.Center) {
            Column(Modifier.fillMaxHeight().widthIn(max=780.dp).fillMaxWidth().testTag("settings-panel"),
                verticalArrangement=Arrangement.spacedBy(8.dp)) {
                // 面板保持实底，滑出裁切区时逐步露出操纵画面，避免两层文字透叠。
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                    Surface(Modifier.fillMaxSize().graphicsLayer {
                            translationY=size.height*(1f-progress)
                        }.testTag("settings-card"),
                        shape=RoundedCornerShape(22.dp),color=SurfaceDark,shadowElevation=4.dp) {
                        Column {
                            Row(Modifier.fillMaxWidth().heightIn(min=64.dp)
                                .padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,
                                horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Default.Settings,null,tint=TextSecondary,modifier=Modifier.size(22.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("飞行设置",fontSize=19.sp,lineHeight=24.sp,fontWeight=FontWeight.Bold,color=TextPrimary)
                                    Text("操控暂时停用",fontSize=12.sp,lineHeight=16.sp,color=TextSecondary)
                                }
                                FilledTonalButton(onClick={ dismiss() },contentPadding=PaddingValues(horizontal=16.dp),
                                    modifier=Modifier.height(44.dp).testTag("settings-done")) { Text("完成") }
                            }
                            if (!wide) Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                SettingsCategory("显示布局",selectedTab==1,{ select(1) },1,Modifier.weight(1f))
                                SettingsCategory("连接状态",selectedTab==0,{ select(0) },0,Modifier.weight(1f))
                                if (videoContent!=null) SettingsCategory("画面测试",selectedTab==2,{ select(2) },2,Modifier.weight(1f))
                            }
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                CompositionLocalProvider(LocalMotionInputBlocked provides (LocalMotionInputBlocked.current || motionInputBlocked)) {
                                    Row(Modifier.fillMaxSize().then(if (motionInputBlocked) Modifier.clearAndSetSemantics { } else Modifier)) {
                                        if (wide) Column(Modifier.width(124.dp).fillMaxHeight().background(BgDark).padding(8.dp),
                                            verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                                if (tallMenu) Text("设置分类",fontSize=10.sp,lineHeight=14.sp,color=TextSecondary,modifier=Modifier.padding(6.dp))
                                                SettingsCategory("显示布局",selectedTab==1,{ select(1) },1)
                                                SettingsCategory("连接状态",selectedTab==0,{ select(0) },0)
                                                if (videoContent!=null) SettingsCategory("画面测试",selectedTab==2,{ select(2) },2)
                                            }
                                            if (tallMenu || videoContent==null) Text(linkLabel,color=if (linkHealthy) VirtualGreen else TextSecondary,
                                                fontSize=10.sp,lineHeight=14.sp,modifier=Modifier.padding(6.dp))
                                        }
                                        MotionContentHost(selectedTab,tabForward,section=true,modifier=Modifier.weight(1f).fillMaxHeight()) { tab ->
                                            if (tab==1) {
                                                DisplayLayoutSettings(hud,onHudChange,onResetHud,configContent)
                                            } else {
                                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)
                                                    .testTag("settings-scroll"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                                    if (tab==2 && videoContent!=null) videoContent() else connectionContent()
                                                }
                                            }
                                        }
                                    }
                                }
                                if (motionInputBlocked) MotionInputShield(Modifier.matchParentSize())
                            }
                        }
                    }
                }
                // 安全操作固定在同一位置，只随蒙层淡入淡出，整个过渡期间可立即响应。
                safetyContent?.let { safety ->
                    Surface(Modifier.fillMaxWidth().testTag("settings-safety-dock").graphicsLayer { alpha=progress },
                        shape=RoundedCornerShape(16.dp),color=SurfaceDark) { safety() }
                }
            }
        }
    }
}

/** 调节区可滚动，预览直接读取同一份HUD状态并固定在可见区域。 */
@Composable
private fun DisplayLayoutSettings(hud: HudPreferences,onChange: (HudPreferences)->Unit,
    onReset: (() -> Unit)?,configContent: (@Composable ()->Unit)?) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        @Composable fun controls(modifier: Modifier,inlinePreview: Boolean=false) {
            Column(modifier.verticalScroll(rememberScrollState()).padding(14.dp).testTag("settings-scroll"),
                verticalArrangement=Arrangement.spacedBy(12.dp)) {
                HudSettingsPanel(hud,onChange,onReset,showPreview=inlinePreview)
                configContent?.invoke()
            }
        }
        if (maxWidth>=350.dp && maxHeight>=96.dp) {
            val previewWidth=minOf(166.dp,maxWidth*.32f)
            val previewHeight=minOf(112.dp,maxHeight-32.dp)
            Row(Modifier.fillMaxSize()) {
                controls(Modifier.weight(1f).fillMaxHeight())
                HudPreview(hud,Modifier.width(previewWidth).padding(top=8.dp,end=12.dp),previewHeight)
            }
        } else if (maxHeight>=200.dp) {
            val previewHeight=minOf(112.dp,maxHeight*.4f)
            Column(Modifier.fillMaxSize()) {
                HudPreview(hud,Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=8.dp),previewHeight)
                controls(Modifier.fillMaxWidth().weight(1f))
            }
        } else {
            controls(Modifier.fillMaxSize(),inlinePreview=true)
        }
    }
}

@Composable
private fun SettingsCategory(label: String,selected: Boolean,onClick: ()->Unit,category: Int,modifier: Modifier=Modifier) {
    val interaction=remember { MutableInteractionSource() }
    val color by animateColorAsState(if (selected) ControlFill else Color.Transparent,
        animationSpec=if (LocalAppMotionEnabled.current) tween(150) else snap(),label="category-color")
    Surface(onClick=onClick,color=color,interactionSource=interaction,
        shape=RoundedCornerShape(12.dp),modifier=modifier.motionPress(interaction).fillMaxWidth().heightIn(min=44.dp)) {
        Row(Modifier.padding(horizontal=10.dp,vertical=11.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
            Icon(when(category) { 1->Icons.Default.Tune; 2->Icons.Default.Videocam; else->Icons.Default.Link },null,tint=if (selected) AccentBlue else TextSecondary,modifier=Modifier.size(18.dp))
            Text(label,color=if (selected) TextPrimary else TextSecondary,fontSize=12.sp,lineHeight=17.sp,
                fontWeight=if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}
