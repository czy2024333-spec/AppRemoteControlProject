package com.vrxfusion.remote.ui

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrxfusion.remote.MainViewModel
import com.vrxfusion.remote.rotorpy.*
import com.vrxfusion.remote.unified.*
import com.vrxfusion.remote.ui.theme.*
import com.vrxfusion.remote.video.VideoSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlin.math.*

/** 所有布局使用同一会话、同一摇杆状态和同一提示决策，横竖屏不会再导航到另一个操控窗口。 */
@Composable
fun FlightControlScreen(vm: MainViewModel, onBack: () -> Unit, onManageConnection: () -> Unit) {
    val client=vm.flightGateway; val scope=vm.rotorScope
    val state by client.state.collectAsState()
    val connected by client.connected.collectAsState()
    val running by client.running.collectAsState()
    val stale by client.stale.collectAsState()
    val writable by client.writable.collectAsState()
    val phase by client.phase.collectAsState()
    val busy by client.busy.collectAsState()
    val safetyBusy by client.safetyBusy.collectAsState()
    val holding by client.holding.collectAsState()
    val error by client.lastError.collectAsState()
    val feedback by client.commandFeedback.collectAsState()
    val hz by client.receiveHz.collectAsState()
    val trail by client.trail.collectAsState()
    val hud by vm.hudPreferences.collectAsState()
    val videoSettings by vm.videoSettings.collectAsState()
    val videoCode by vm.videoCode.collectAsState()
    val videoSource by vm.videoSource.collectAsState()
    val videoGeneration by vm.videoGeneration.collectAsState()
    val videoStatus by vm.videoStatus.collectAsState()
    var viewportMessage by remember(videoSource,videoGeneration) {
        mutableStateOf<String?>(if (videoSource==VideoSource.NONE) "UE 画面未接入" else "正在开启${videoSource.label}…")
    }
    val unifiedSnapshot by client.repository.snapshot.collectAsState()
    val unifiedInfo by client.repository.info.collectAsState()
    var left by remember { mutableStateOf(Offset.Zero) }
    var right by remember { mutableStateOf(Offset.Zero) }
    var leftTouch by remember { mutableStateOf(false) }
    var rightTouch by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var settingsTab by remember { mutableIntStateOf(0) }
    val settingsMotion=rememberMotionSheet(settingsOpen)
    val routeMoving=LocalMotionInputBlocked.current
    val activity=LocalContext.current.findActivity()
    val context=LocalContext.current
    val cameraPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && vm.videoSettings.value.source==VideoSource.PHONE) vm.startVideo()
        else if (!granted) vm.stopVideo("相机权限未允许，请在系统应用权限中允许相机后重新启用")
    }
    FlightImmersiveMode(activity)
    DisposableEffect(client) { client.attachInputUi(); onDispose { client.detachInputUi() } }
    LaunchedEffect(client) { while (isActive) withFrameNanos { client.pulseInputUi() } }
    DisposableEffect(activity) {
        val original=activity?.requestedOrientation
        // 独立操纵只使用横屏，允许手机左右翻转；离开操纵页恢复原方向策略。
        activity?.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            original?.let { activity?.requestedOrientation=it }
            client.onStick(StickSnapshot(),scope)
        }
    }
    fun openSettings(tab: Int) {
        // 打开设置先停发并清掉两个杆值，不能让设置层遮住之后仍持续飞行。
        client.onStick(StickSnapshot(),scope)
        left=Offset.Zero; right=Offset.Zero
        settingsTab=tab; settingsOpen=true
    }
    val neutral=left == Offset.Zero && right == Offset.Zero && !leftTouch && !rightTouch
    val notice=flightNotice(state,connected,running,stale,phase,busy,holding,neutral,error,writable)
    val canInput=connected && writable && !stale && !busy && state?.mode != "PROTECTED" &&
        !safetyBusy && phase == LinkPhase.READY && !settingsMotion.visible && !routeMoving
    // 按已提交到Compose的不同步号计数，不能拿网络接收频率冒充APP刷新率。
    SideEffect { if (connected && !stale && !settingsMotion.visible && !routeMoving && state!=null)
        client.renderedRate.record("${state!!.boot_id}:${state!!.sequence}") }
    fun report()=client.onStick(StickSnapshot(right.x,right.y,left.x,left.y),scope)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide=maxWidth > maxHeight
        FlightCockpit(
            state=state, trail=trail, notice=notice, fresh=connected && !stale, wide=wide, receiveHz=hz,
            inputEnabled=canInput, safetyEnabled=running && client.canUseSafety(),
            onBack=onBack, onSettings={ openSettings(1) },hud=hud,
            videoLabel=videoSource.takeUnless { it==VideoSource.NONE }?.label,
            qualityLabel=client.primaryState()?.qualityText(),
            commandReceipt=feedback?.text(),
            viewportStatus=viewportMessage,
            fleetMembers=unifiedSnapshot?.members.orEmpty(),selfDeviceId=unifiedInfo?.controlDeviceId(),
            memberFresh={ client.repository.fresh(it) },
            ueContent={ key(videoSource,videoGeneration) {
                val sessionGeneration=videoGeneration
                TestVideoViewport(videoSource,videoSettings,videoCode) { status,failed ->
                    if (vm.videoGeneration.value==sessionGeneration) {
                        vm.videoStatus.value=status
                        viewportMessage=status.takeIf { failed }
                    }
                }
            } },
            onPrimary={ when (notice.action) {
                FlightAction.CONNECTION -> onManageConnection()
                FlightAction.REQUEST -> client.retryHandshake(scope)
                FlightAction.RESUME -> scope.launch { client.resume(scope) }
                FlightAction.NONE -> Unit
            } },
            onStop={ scope.launch { client.stop() } }, onProtect={ scope.launch { client.protect() } },
            onLeft={ left=it; report() }, onRight={ right=it; report() },
            onLeftTouch={ leftTouch=it }, onRightTouch={ rightTouch=it })
        if (settingsMotion.visible) FlightSettingsPanel(
            selectedTab=settingsTab,onTabChange={ settingsTab=it },hud=hud,onHudChange=vm::setHudPreferences,
            motionProgress=settingsMotion.progress,motionInputBlocked=!settingsMotion.ready,
            onClose={ settingsOpen=false },
            configContent={ HudConfigPanel(vm) },onResetHud=vm::resetHudDefaults,
            safetyContent={ FlightSafetyButtons(running && client.canUseSafety(),{ scope.launch { client.stop() } },{ scope.launch { client.protect() } }) },
            linkLabel=when { stale->"数据已失效"; connected->"已连接 · %.1f Hz".format(hz); running->"正在连接"; else->"未连接" },
            linkHealthy=connected && !stale,
            videoContent={ VideoSettingsPanel(videoSettings,videoCode,videoSource,videoStatus,vm::setVideoSettings,
                { vm.videoCode.value=it },onEnable={
                    if (videoSettings.source==VideoSource.PHONE &&
                        ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    else vm.startVideo()
                },onStop={ vm.stopVideo() }) }) {
            BackendConnectionSummary(vm) { settingsOpen=false; onManageConnection() }
            CommandReceiptPanel(vm)
            DiagnosticsPanel(vm)
        }
    }

}

/** 设置遮住操控页时，安全操作依然固定可达；忙碌不会禁用它们。 */
@Composable
fun FlightSafetyButtons(enabled: Boolean,onStop: ()->Unit,onProtect: ()->Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=2.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Button(onClick=onStop,enabled=enabled,modifier=Modifier.weight(1f),
            colors=ButtonDefaults.buttonColors(containerColor=SurfaceCard,contentColor=TextPrimary)) { Text("停止") }
        Button(onClick=onProtect,enabled=enabled,modifier=Modifier.weight(1f),
            colors=ButtonDefaults.buttonColors(containerColor=ProtectionFill,contentColor=ProtectionText)) { Text("保护") }
    }
}

private val ProtectionFill=Color(0xFF713936)
private val ProtectionText=Color(0xFFFFE5E1)

/**
 * UE始终铺满整页，HUD只叠加在其上，不占用或缩小视频容器。
 * 透明度分别作用于非关键显示层，控制按钮与顶部告警保持独立。
 */
@Composable
fun FlightCockpit(state: RotorState?, trail: List<Pair<Double,Double>>, notice: FlightNotice,
    fresh: Boolean, wide: Boolean, receiveHz: Double, inputEnabled: Boolean, safetyEnabled: Boolean,
    onBack: ()->Unit={}, onSettings: ()->Unit={}, onPrimary: ()->Unit={},
    onStop: ()->Unit={}, onProtect: ()->Unit={},
    onLeft: (Offset)->Unit={}, onRight: (Offset)->Unit={}, onLeftTouch: (Boolean)->Unit={}, onRightTouch: (Boolean)->Unit={},
    hud: HudPreferences=HudPreferences(), videoLabel: String?=null,qualityLabel: String?=null,
    fleetMembers: List<UnifiedDeviceState> = emptyList(),selfDeviceId: String?=null,
    memberFresh: (UnifiedDeviceState)->Boolean={false},commandReceipt: String?=null,
    viewportStatus: String?=null,ueContent: (@Composable ()->Unit)?=null) {
    val display=hud.normalized()
    Box(Modifier.fillMaxSize().testTag("cockpit")) {
        // 后续UE视频组件只替换此内容，保持Texture/View层在HUD之下。
        Box(Modifier.matchParentSize().background(BgDark).testTag("ue-viewport")) { ueContent?.invoke() }
        Column(Modifier.fillMaxSize().windowInsetsPadding(flightSafeInsets()).padding(8.dp),
            verticalArrangement=Arrangement.spacedBy(6.dp)) {
            @Composable fun buttons(modifier: Modifier=Modifier) {
                Row(modifier.testTag("critical-actions"),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    Button(onClick=onPrimary,enabled=notice.actionEnabled,modifier=Modifier.weight(1f).height(48.dp),
                        shape=RoundedCornerShape(14.dp),
                        colors=ButtonDefaults.buttonColors(containerColor=ControlFill,contentColor=ControlText,
                            disabledContainerColor=Color.Transparent,disabledContentColor=TextSecondary),
                        contentPadding=PaddingValues(horizontal=8.dp)) { Text(notice.actionLabel,maxLines=1,fontSize=12.sp) }
                    Button(onClick=onStop,enabled=safetyEnabled,modifier=Modifier.width(72.dp).height(48.dp),
                        shape=RoundedCornerShape(14.dp),
                        colors=ButtonDefaults.buttonColors(containerColor=SurfaceCard,contentColor=TextPrimary,
                            disabledContainerColor=Color.Transparent,disabledContentColor=TextSecondary),
                        contentPadding=PaddingValues(4.dp)) { Text("停止",fontSize=12.sp) }
                    Button(onClick=onProtect,enabled=safetyEnabled,modifier=Modifier.width(72.dp).height(48.dp),
                        shape=RoundedCornerShape(14.dp),
                        contentPadding=PaddingValues(4.dp),
                        colors=ButtonDefaults.buttonColors(containerColor=ProtectionFill,contentColor=ProtectionText,
                            disabledContainerColor=Color.Transparent,disabledContentColor=TextSecondary)) { Text("保护",fontSize=12.sp) }
                }
            }
            Row(Modifier.fillMaxWidth().height(48.dp).background(
                Brush.horizontalGradient(listOf(HudSurface,HudSurface.copy(alpha=.72f))),RoundedCornerShape(14.dp)),
                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                IconButton(onClick=onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack,contentDescription="返回首页",tint=TextPrimary)
                }
                Column(Modifier.weight(1f)) {
                    Text(if (state?.data_source=="simulation_truth") "飞行操控 · 仿真" else "飞行操控 · 来源未确认",
                        color=TextPrimary,fontWeight=FontWeight.SemiBold,fontSize=15.sp,lineHeight=19.sp,
                        maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text((state?.device_id ?: "单机 · 等待连接")+(videoLabel?.let { " · $it" } ?: ""),
                        color=TextSecondary,fontSize=11.sp,lineHeight=15.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                if (wide) buttons(Modifier.width(274.dp))
                IconButton(onClick=onSettings) {
                    Icon(Icons.Default.Settings,contentDescription="飞行设置",tint=TextPrimary)
                }
            }
            if (!wide) buttons(Modifier.fillMaxWidth())
            if (display.showPosition) Row(Modifier.fillMaxWidth().height(26.dp).alpha(display.positionOpacity)
                .testTag("position-readout"),
                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                Text(if (state == null || !fresh) "X —  Y —  Z — m" else "X %.2f  Y %.2f  Z %.2f m".format(
                    state.position_m.getOrElse(0){0.0},state.position_m.getOrElse(1){0.0},state.position_m.getOrElse(2){0.0}),
                    color=TextPrimary,fontSize=11.sp,fontFamily=FontFamily.Monospace,
                    modifier=Modifier.background(HudSurface.copy(alpha=.65f),RoundedCornerShape(6.dp))
                        .padding(horizontal=8.dp,vertical=3.dp))
                if (fresh) Text("%.1fHz · %.2fm/s".format(receiveHz,state?.speed() ?: 0.0),color=TextSecondary,fontSize=10.sp,
                    modifier=Modifier.background(HudSurface.copy(alpha=.65f),RoundedCornerShape(6.dp))
                        .padding(horizontal=8.dp,vertical=3.dp))
            }
            // 运行模式和来源与模拟/缺失标记始终可见，显示配置不能隐藏数据质量提示。
            Row(Modifier.fillMaxWidth().heightIn(min=24.dp),verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.SpaceBetween) {
                Text(if (!fresh) "状态未更新 · 仪表仅供历史参考" else
                    "${state?.mode ?: "—"} · 通路 ${state?.selected_source ?: "未选择"} · ${qualityLabel ?: "任务未接入 · GNSS/电量未接入"}",
                    color=if (fresh) TextSecondary else WarnAmber,fontSize=10.sp,lineHeight=14.sp,
                    maxLines=1,overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.weight(1f,fill=false).background(HudSurface,RoundedCornerShape(8.dp))
                        .padding(horizontal=8.dp,vertical=4.dp).testTag("data-quality"))
                val videoMessage=viewportStatus ?: if (ueContent==null) "UE 画面未接入" else null
                if (videoMessage!=null) {
                    Text(videoMessage,color=TextSecondary,fontSize=10.sp,lineHeight=14.sp,maxLines=1,
                        overflow=TextOverflow.Ellipsis,
                        modifier=Modifier.padding(start=8.dp).widthIn(max=if (wide) 240.dp else 120.dp)
                            .background(HudSurface,RoundedCornerShape(8.dp))
                            .padding(horizontal=8.dp,vertical=4.dp).testTag("video-viewport-status"))
                }
            }
            FlightAlert(notice,commandReceipt)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().testTag("hud-field")) {
                val stickWidth=minOf(if (wide) 128.dp else 144.dp,(maxWidth-16.dp)/2)
                val inset=if (wide) stickWidth+8.dp else 0.dp
                val attitudeWidth=(if (wide) 96.dp else 104.dp)*display.instrumentScale
                val mapWidth=(if (wide) 128.dp else 140.dp)*display.instrumentScale
                val instrumentHeight=minOf((if (wide) 128.dp else 148.dp)*display.instrumentScale,maxHeight)
                val stickHeight=minOf(152.dp,maxHeight)
                if (display.showAttitude) AttitudePanel(state?.quaternion_xyzw,fresh,
                    Modifier.align(if (display.swapInstruments) Alignment.TopEnd else Alignment.TopStart)
                        .offset(x=if (display.swapInstruments) -inset else inset).width(attitudeWidth).height(instrumentHeight)
                        .alpha(display.attitudeOpacity),compact=true)
                if (display.showMap) PositionMap(state,trail,fresh,
                    Modifier.align(if (display.swapInstruments) Alignment.TopStart else Alignment.TopEnd)
                        .offset(x=if (display.swapInstruments) inset else -inset).width(mapWidth).height(instrumentHeight)
                        .alpha(display.mapOpacity),fleetMembers,selfDeviceId,memberFresh)
                StickPanel("升降 / 转向",inputEnabled,onLeft,onLeftTouch,
                    Modifier.align(Alignment.BottomStart).width(stickWidth).height(stickHeight).alpha(display.joystickOpacity).testTag("left-stick-panel"))
                StickPanel("前后 / 左右",inputEnabled,onRight,onRightTouch,
                    Modifier.align(Alignment.BottomEnd).width(stickWidth).height(stickHeight).alpha(display.joystickOpacity).testTag("right-stick-panel"))
            }
        }
    }
}

@Composable
private fun StickPanel(label: String,enabled: Boolean,onValue: (Offset)->Unit,onTouch: (Boolean)->Unit,modifier: Modifier) {
    // 底部保留握持留白和原有摇杆位置，轴向说明交给无障碍语义。
    BoxWithConstraints(modifier.padding(bottom=24.dp).semantics { contentDescription=label },
        contentAlignment=Alignment.Center) {
        val diameter=minOf(128.dp,maxWidth,maxHeight.coerceAtLeast(24.dp))
        VirtualJoystick(enabled,onValue,onTouch,diameter)
    }
}

/** 提示卡靠左，保留背景画面；固定区域避免切换提示时操纵控件位移。 */
@Composable
private fun FlightAlert(notice: FlightNotice,commandReceipt: String?) {
    val alert = notice.tone == NoticeTone.DANGER || notice.tone == NoticeTone.WARNING
    val textLine=with(LocalDensity.current) { 16.sp.toDp() }
    Box(Modifier.fillMaxWidth().height(maxOf(38.dp,textLine*2+6.dp))) {
        if (alert || commandReceipt != null) {
            Column(Modifier.widthIn(max=420.dp).fillMaxHeight().background(HudSurface,RoundedCornerShape(10.dp))
                .padding(horizontal=8.dp,vertical=3.dp).testTag("flight-notice"),verticalArrangement=Arrangement.Center) {
                if (alert) {
                    Text(notice.title,color=if (notice.tone==NoticeTone.DANGER) ProtectionText else WarnAmber,
                        fontSize=11.sp,lineHeight=14.sp,fontWeight=FontWeight.SemiBold,
                        maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                Text(if (alert) notice.detail else commandReceipt.orEmpty(),color=TextPrimary,fontSize=11.sp,lineHeight=14.sp,
                    maxLines=1,overflow=TextOverflow.Ellipsis)
            }
        }
    }
}

/** 参数只包含四元数和数据有效性，位置更新不会重置或驱动姿态仪动画。 */
@Composable
fun AttitudePanel(quaternion: List<Double>?,fresh: Boolean,modifier: Modifier=Modifier,compact: Boolean=false) {
    val angles=remember(quaternion) { quaternion?.let(AttitudeMath::fromQuaternion) }
    var rollTarget by remember { mutableFloatStateOf(angles?.rollDeg?.toFloat() ?: 0f) }
    var pitchTarget by remember { mutableFloatStateOf(angles?.pitchDeg?.toFloat() ?: 0f) }
    LaunchedEffect(angles?.rollDeg,angles?.pitchDeg) {
        angles?.let { rollTarget=AttitudeMath.stableAngle(rollTarget,it.rollDeg); pitchTarget=AttitudeMath.stableAngle(pitchTarget,it.pitchDeg) }
    }
    val roll by animateFloatAsState(rollTarget,tween(120),label="仅姿态横滚")
    val pitch by animateFloatAsState(pitchTarget,tween(120),label="仅姿态俯仰")
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(HudSurface).padding(if (compact) 4.dp else 8.dp).testTag("attitude-panel"),
        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(if (compact) 2.dp else 4.dp)) {
        if (!compact) Text("姿态",color=TextPrimary,fontSize=12.sp)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {
            val diameter=minOf(maxWidth,maxHeight,104.dp)
            AttitudeDial(roll,pitch,Modifier.size(diameter))
        }
        Text(if (angles != null && fresh) "横滚 %.1f°".format(roll) else "姿态未更新",color=TextSecondary,fontSize=10.sp,maxLines=1)
        Text(if (angles != null && fresh) "俯仰 %.1f°".format(pitch) else "—",color=TextSecondary,fontSize=10.sp,maxLines=1)
    }
}

@Composable
fun AttitudeDial(roll: Float,pitch: Float,modifier: Modifier=Modifier) {
    Canvas(modifier.clip(CircleShape).background(SurfaceDark).testTag("attitude-dial")) {
        val w=size.width; val h=size.height; val cx=center.x; val cy=center.y
        rotate(-roll,pivot=center) {
            translate(top=AttitudeMath.horizonY(h,pitch.toDouble())) {
                drawRect(Color(0xFF1D4E89),Offset(-w,-2*h),Size(3*w,2*h))
                drawRect(Color(0xFF5A4326),Offset(-w,0f),Size(3*w,2*h))
                drawLine(Color.White,Offset(-w,0f),Offset(2*w,0f),2.dp.toPx())
                for (p in -60..60 step 10) if (p != 0) {
                    val y=-p*h/110; val half=if (p%20 == 0) w*.22f else w*.12f
                    drawLine(Color.White.copy(alpha=.65f),Offset(cx-half,y),Offset(cx+half,y),1.dp.toPx())
                }
            }
        }
        drawLine(WarnAmber,Offset(cx-w*.28f,cy),Offset(cx-w*.08f,cy),2.dp.toPx())
        drawLine(WarnAmber,Offset(cx+w*.08f,cy),Offset(cx+w*.28f,cy),2.dp.toPx())
        drawCircle(WarnAmber,2.dp.toPx(),Offset(cx,cy))
        drawCircle(TextSecondary,size.minDimension/2-1,style=Stroke(1.dp.toPx()))
    }
}

/** 单击只切换地图观察比例，框的尺寸和位置始终不变；不触发申请控制或运动输入。 */
@Composable
private fun PositionMap(state: RotorState?,trail: List<Pair<Double,Double>>,fresh: Boolean,modifier: Modifier,
    members: List<UnifiedDeviceState> = emptyList(),selfId: String?=null,memberFresh: (UnifiedDeviceState)->Boolean={false}) {
    var focused by rememberSaveable { mutableStateOf(false) }
    val validTrail=remember(trail) { trail.filter { it.first.isFinite() && it.second.isFinite() } }
    val position=state?.position_m?.let { p ->
        if (p.size >= 2 && p[0].isFinite() && p[1].isFinite()) p[0] to p[1] else null
    }
    val center=position ?: validTrail.lastOrNull() ?: (0.0 to 0.0)
    val markers=fleetMarkers(members,selfId,memberFresh)
    val mapPoints=validTrail+listOfNotNull(position,0.0 to 0.0)+markers.map { it.position.x to it.position.y }
    val viewport=if (focused) TrajectoryViewport.focus(center)
        else if (members.isNotEmpty()) fleetViewport(mapPoints) else TrajectoryViewport.fit(mapPoints)
    fun coordinate(index: Int)=state?.position_m?.getOrNull(index)?.takeIf { fresh && it.isFinite() }
        ?.let { "%.2f".format(java.util.Locale.ROOT,it) } ?: "—"
    val mode=if (focused) "当前位置近景" else "完整轨迹总览"
    val gridLabel="%.2f".format(java.util.Locale.ROOT,viewport.gridMeters).trimEnd('0').trimEnd('.')
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(HudSurface)
        .clickable(role=Role.Button,onClickLabel=if (focused) "恢复轨迹总览" else "放大当前位置") { focused=!focused }
        .semantics { stateDescription=mode }.padding(6.dp).testTag("position-panel"),
        verticalArrangement=Arrangement.spacedBy(3.dp)) {
        // 不绘制标题或模式文字，把空间留给轨迹；无障碍仍可读取当前观察模式。
        Canvas(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(Color(0x40101D2B)).testTag("position-map")) {
            val padding=if (focused) 0f else 8.dp.toPx()
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
            drawCircle(TextSecondary,2.dp.toPx(),point(0.0,0.0))
            if (validTrail.size > 1) {
                val path=Path(); validTrail.forEachIndexed { i,p -> val pixel=point(p.first,p.second)
                    if (i == 0) path.moveTo(pixel.x,pixel.y) else path.lineTo(pixel.x,pixel.y) }
                drawPath(path,AccentBlue,style=Stroke(2.dp.toPx()))
            }
            if (members.isNotEmpty()) markers.forEach { drawFleetMarker(it,point(it.position.x,it.position.y),edge=true) }
            else position?.let { xy ->
                val p=point(xy.first,xy.second)
                val yaw=state?.yawRad() ?: 0.0; val color=if (fresh) WarnAmber else TextSecondary
                drawCircle(color,4.dp.toPx(),p)
                drawLine(color,p,p+Offset(-sin(yaw).toFloat()*8.dp.toPx(),-cos(yaw).toFloat()*8.dp.toPx()),2.dp.toPx())
            }
        }
        // 三维坐标在原地图位置直接可读，Z仍以米显示，不需要再打开详情。
        if (members.isEmpty()) {
            Text("X ${coordinate(0)}  Y ${coordinate(1)}",color=TextPrimary,fontSize=9.sp,lineHeight=10.sp,fontFamily=FontFamily.Monospace)
            Text("Z ${coordinate(2)} m · ${gridLabel}m/格",color=TextPrimary,fontSize=9.sp,lineHeight=10.sp)
        } else members.sortedBy { it.device_id }.forEach { m ->
            Text(aircraftCoordinateText(m,selfId,memberFresh(m)),fontSize=8.sp,lineHeight=9.sp,maxLines=1,
                overflow=TextOverflow.Ellipsis,color=if (!memberFresh(m)) Color.Gray else if (m.device_id==selfId) WarnAmber else TextPrimary,
                modifier=Modifier.testTag("fleet-coordinate-${m.device_id}"))
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when(this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
