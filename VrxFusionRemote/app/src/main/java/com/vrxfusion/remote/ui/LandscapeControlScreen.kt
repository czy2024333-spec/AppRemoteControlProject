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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vrxfusion.remote.MainViewModel
import com.vrxfusion.remote.rotorpy.RotorState
import com.vrxfusion.remote.rotorpy.StateRate
import com.vrxfusion.remote.rotorpy.StickSnapshot
import com.vrxfusion.remote.ui.design.SafetyDock
import com.vrxfusion.remote.ui.design.StickPad
import com.vrxfusion.remote.ui.hud.FlightAlert
import com.vrxfusion.remote.ui.hud.HudControlSignals
import com.vrxfusion.remote.ui.hud.HudSnapshot
import com.vrxfusion.remote.ui.hud.HudTelemetry
import com.vrxfusion.remote.ui.hud.activeHudNotices
import com.vrxfusion.remote.ui.hud.deriveHud
import com.vrxfusion.remote.ui.hud.hudControlSignals
import com.vrxfusion.remote.ui.hud.hudInstrumentSpacing
import com.vrxfusion.remote.ui.hud.hudLayout
import com.vrxfusion.remote.ui.hud.instruments.CenterFlightHud
import com.vrxfusion.remote.ui.hud.instruments.HudAttitude
import com.vrxfusion.remote.ui.hud.instruments.HudAltitudeBand
import com.vrxfusion.remote.ui.hud.instruments.HudMap
import com.vrxfusion.remote.ui.hud.instruments.HudReadouts
import com.vrxfusion.remote.ui.hud.instruments.HudSpeedBand
import com.vrxfusion.remote.ui.hud.instruments.hudReceiveRateLabel
import com.vrxfusion.remote.ui.hud.rememberHudControlSignals
import com.vrxfusion.remote.ui.hud.rememberHudDisplay
import com.vrxfusion.remote.ui.screens.control.CockpitActionBar
import com.vrxfusion.remote.ui.state.ControlSnapshot
import com.vrxfusion.remote.ui.state.ControlUi
import com.vrxfusion.remote.ui.state.deriveControlUi
import com.vrxfusion.remote.ui.state.shortLabel
import com.vrxfusion.remote.ui.theme.BgDark
import com.vrxfusion.remote.unified.UnifiedDeviceState
import com.vrxfusion.remote.video.VideoSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

/** 所有布局使用同一会话、同一摇杆状态和同一提示决策，横竖屏不会再导航到另一个操控窗口。 */
@Composable
fun FlightControlScreen(vm: MainViewModel, onBack: () -> Unit, onManageConnection: () -> Unit) {
    val client=vm.flightGateway; val scope=vm.rotorScope
    val aircraft = client.state.collectAsState()
    val signals by rememberHudControlSignals(aircraft)
    val connected by client.connected.collectAsState()
    val running by client.running.collectAsState()
    val stale by client.stale.collectAsState()
    val writable by client.writable.collectAsState()
    val phase by client.phase.collectAsState()
    val busy by client.busy.collectAsState()
    val safetyBusy by client.safetyBusy.collectAsState()
    val holding by client.holding.collectAsState()
    val error by client.lastError.collectAsState()
    val feedback = client.commandFeedback.collectAsState()
    val commandReceipt = remember(feedback) {
        derivedStateOf(structuralEqualityPolicy()) { feedback.value?.text() }
    }
    val hz = client.receiveHz.collectAsState()
    val trail = client.trail.collectAsState()
    val hud by vm.hudPreferences.collectAsState()
    val videoSettings by vm.videoSettings.collectAsState()
    val videoCode by vm.videoCode.collectAsState()
    val videoSource by vm.videoSource.collectAsState()
    val videoGeneration by vm.videoGeneration.collectAsState()
    val videoStatus by vm.videoStatus.collectAsState()
    var viewportMessage by remember(videoSource, videoGeneration, videoSettings, videoCode) {
        mutableStateOf<String?>(if (videoSource==VideoSource.NONE) "UE 画面未接入" else "正在开启${videoSource.label}…")
    }
    val unifiedSnapshot = client.repository.snapshot.collectAsState()
    val unifiedInfo by client.repository.info.collectAsState()
    val directory by client.repository.directory.collectAsState()
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
    val neutral by remember {
        derivedStateOf(structuralEqualityPolicy()) {
            left == Offset.Zero && right == Offset.Zero && !leftTouch && !rightTouch
        }
    }
    val targetDeviceId=unifiedInfo?.controlDeviceId()
    val members = remember(unifiedSnapshot) {
        derivedStateOf(structuralEqualityPolicy()) { unifiedSnapshot.value?.members.orEmpty() }
    }
    val qualityLabel = remember(members, targetDeviceId) {
        derivedStateOf(structuralEqualityPolicy()) {
            members.value.firstOrNull { it.device_id == targetDeviceId }?.qualityText()
        }
    }
    val telemetry = remember(aircraft, trail, hz, members, qualityLabel) {
        HudTelemetry(aircraft, trail, hz, members, qualityLabel)
    }
    val memberFresh: (UnifiedDeviceState) -> Boolean = remember(client) { { client.repository.fresh(it) } }
    val safetyActions=directory?.devices?.firstOrNull { it.device_id==targetDeviceId }?.capabilities?.actions.orEmpty()
    val control=deriveControlUi(ControlSnapshot(
        connected=connected, running=running, stale=stale, writable=writable, phase=phase,
        busy=busy, safetyBusy=safetyBusy, holding=holding, mode=signals.mode, neutral=neutral, error=error,
        sheetBlocking=settingsMotion.visible, routeMoving=routeMoving, safetyAllowed=client.canUseSafety(),
        boundaryRecovery=signals.boundary, protectionReason=signals.protectionReason,
        stopAvailable="STOP" in safetyActions, protectAvailable="PROTECT" in safetyActions,
        resumeAvailable="RESUME" in safetyActions
    ))
    val onStop: () -> Unit = remember(client, scope) { { scope.launch { client.stop() } } }
    val onProtect: () -> Unit = remember(client, scope) { { scope.launch { client.protect() } } }
    fun report()=client.onStick(StickSnapshot(right.x,right.y,left.x,left.y),scope)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide=maxWidth > maxHeight
        HudHost(
            telemetry=telemetry, signals=signals, control=control, fresh=connected && !stale, wide=wide,
            renderedRate=client.renderedRate, countFrames=!settingsMotion.visible && !routeMoving,
            onBack=onBack, onSettings={ openSettings(1) },hud=hud,
            videoLabel=videoSource.takeUnless { it==VideoSource.NONE }?.label,
            commandReceipt=commandReceipt, viewportStatus=viewportMessage,
            selfDeviceId=targetDeviceId, memberFresh=memberFresh,
            ueContent={ key(videoSource,videoGeneration) {
                val sessionGeneration=videoGeneration
                TestVideoViewport(videoSource,videoSettings,videoCode) { status,failed ->
                    if (vm.videoGeneration.value==sessionGeneration) {
                        vm.videoStatus.value=status
                        viewportMessage=status.takeIf { failed }
                    }
                }
            } },
            onPrimary={ when (control.notice.action) {
                FlightAction.CONNECTION -> onManageConnection()
                FlightAction.REQUEST -> client.retryHandshake(scope)
                FlightAction.RESUME -> scope.launch { client.resume(scope) }
                FlightAction.NONE -> Unit
            } },
            onStop=onStop, onProtect=onProtect,
            onLeft={ left=it; report() }, onRight={ right=it; report() },
            onLeftTouch={ leftTouch=it }, onRightTouch={ rightTouch=it })
        if (settingsMotion.visible) FlightSettingsPanel(
            selectedTab=settingsTab,onTabChange={ settingsTab=it },hud=hud,onHudChange=vm::setHudPreferences,
            motionProgress=settingsMotion.progress,motionInputBlocked=!settingsMotion.ready,
            onClose={ settingsOpen=false },
            configContent={ HudConfigPanel(vm) },onResetHud=vm::resetHudDefaults,
            safetyContent={ SafetyDock(control.stopEnabled, control.protectEnabled, onStop, onProtect) },
            linkLabel=when { stale->"数据已失效"; connected->"已连接 · ${hudReceiveRateLabel(hz.value)}"; running->"正在连接"; else->"未连接" },
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

/**
 * UE始终铺满整页，HUD只叠加在其上，不占用或缩小视频容器。
 * 透明度分别作用于非关键显示层，控制按钮与顶部告警保持独立。
 */
@Composable
fun FlightCockpit(state: RotorState?, trail: List<Pair<Double,Double>>, control: ControlUi,
    fresh: Boolean, wide: Boolean, receiveHz: Double,
    onBack: ()->Unit={}, onSettings: ()->Unit={}, onPrimary: ()->Unit={},
    onStop: ()->Unit={}, onProtect: ()->Unit={},
    onLeft: (Offset)->Unit={}, onRight: (Offset)->Unit={}, onLeftTouch: (Boolean)->Unit={}, onRightTouch: (Boolean)->Unit={},
    hud: HudPreferences=HudPreferences(), videoLabel: String?=null,qualityLabel: String?=null,
    fleetMembers: List<UnifiedDeviceState> = emptyList(),selfDeviceId: String?=null,
    memberFresh: (UnifiedDeviceState)->Boolean={false},commandReceipt: String?=null,
    viewportStatus: String?=null,ueContent: (@Composable ()->Unit)?=null) {
    val telemetry = HudTelemetry(
        rememberUpdatedState(state), rememberUpdatedState(trail), rememberUpdatedState(receiveHz),
        rememberUpdatedState(fleetMembers), rememberUpdatedState(qualityLabel)
    )
    HudHost(telemetry, state.hudControlSignals(), control, fresh, wide,
        onBack, onSettings, onPrimary, onStop, onProtect, onLeft, onRight, onLeftTouch, onRightTouch,
        hud, videoLabel, selfDeviceId, memberFresh, rememberUpdatedState(commandReceipt), viewportStatus, ueContent)
}

@Composable
internal fun HudHost(
    telemetry: HudTelemetry, signals: HudControlSignals, control: ControlUi, fresh: Boolean, wide: Boolean,
    onBack: () -> Unit = {}, onSettings: () -> Unit = {}, onPrimary: () -> Unit = {},
    onStop: () -> Unit = {}, onProtect: () -> Unit = {},
    onLeft: (Offset) -> Unit = {}, onRight: (Offset) -> Unit = {},
    onLeftTouch: (Boolean) -> Unit = {}, onRightTouch: (Boolean) -> Unit = {},
    hud: HudPreferences = HudPreferences(), videoLabel: String? = null, selfDeviceId: String? = null,
    memberFresh: (UnifiedDeviceState) -> Boolean = { false },
    commandReceipt: State<String?>, viewportStatus: String? = null,
    ueContent: (@Composable () -> Unit)? = null, renderedRate: StateRate? = null, countFrames: Boolean = false
) {
    val display=hud.normalized()
    val appearance = rememberHudDisplay(display, control)
    val noticeVisible = remember { mutableStateOf(false) }
    val hasActiveNotice = remember(control.notice, control.secondaryNotices) { control.activeHudNotices().isNotEmpty() }
    val model = remember(telemetry.aircraft, fresh) {
        derivedStateOf(structuralEqualityPolicy()) { deriveHud(HudSnapshot(telemetry.aircraft.value, fresh)) }
    }
    Box(Modifier.fillMaxSize().testTag("cockpit")) {
        // 后续UE视频组件只替换此内容，保持Texture/View层在HUD之下。
        Box(Modifier.matchParentSize().background(BgDark).testTag("ue-viewport")) { ueContent?.invoke() }
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(flightSafeInsets()).padding(8.dp)) {
            // 画面源只负责底层视频，横屏 HUD 布局由用户设置决定。
            val centered = wide && display.showCentralHud
            val cockpitHeight = maxHeight
            val narrowHeader = wide && maxWidth < 500.dp
            val layout = hudLayout(maxWidth, wide, display.instrumentScale, display.showAttitude,
                display.showMap && appearance.showFlightMetrics, appearance.showFlightMetrics, centered)
            Column(Modifier.fillMaxSize(), verticalArrangement=Arrangement.spacedBy(6.dp)) {
                val deviceLabel=listOfNotNull(signals.deviceId, videoLabel)
                    .joinToString(" · ").takeIf { it.isNotEmpty() }
                CockpitActionBar(
                    control, signals.simulated, deviceLabel, wide, narrowHeader,
                    onBack, onSettings, onPrimary, onStop, onProtect
                )
                HudReadouts(telemetry, model, fresh, display, wide, viewportStatus ?: if (ueContent==null) "UE 画面未接入" else null,
                    renderedRate, countFrames, appearance, layout, centered)
                FlightAlert(control, commandReceipt) { noticeVisible.value = it }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().testTag("hud-field")) {
                    if (centered && layout.centralHudWidth > 0.dp) {
                        val reservedTop = cockpitHeight - maxHeight
                        // 没有告警时用到提示行的空位，让仪表贴近视频中心。
                        val earliest = if (hasActiveNotice || noticeVisible.value || !commandReceipt.value.isNullOrBlank()) 0.dp else (-44).dp
                        val verticalOffset = (cockpitHeight / 2 - reservedTop - 41.dp)
                            .coerceIn(earliest, (maxHeight - 82.dp).coerceAtLeast(0.dp))
                        Box(Modifier.align(Alignment.TopCenter).offset(y = verticalOffset).width(layout.centralHudWidth)) {
                            val instruments by model
                            CenterFlightHud(instruments, display.showAttitude, appearance.showFlightMetrics,
                                display.centerHudOpacity, Modifier.fillMaxWidth())
                        }
                    }
                    val instrumentHeight=minOf((if (wide) 128.dp else 148.dp)*display.instrumentScale,maxHeight)
                    val stickHeight=minOf(152.dp,maxHeight)
                    val attitudeOnLeft = !display.swapInstruments
                    fun side(onLeft: Boolean, width: Dp, inset: Dp) =
                        Modifier.align(if (onLeft) Alignment.TopStart else Alignment.TopEnd)
                            .offset(x = if (onLeft) inset else -inset).width(width).height(instrumentHeight)
                    if (layout.attitudeWidth > 0.dp) key(selfDeviceId) {
                        HudAttitude(model, display.attitudeOpacity, side(attitudeOnLeft, layout.attitudeWidth, layout.sideInset))
                    }
                    if (layout.mapWidth > 0.dp) {
                        val mapOpacity = if (appearance.auxiliaryDimmed) minOf(display.mapOpacity, .4f) else display.mapOpacity
                        HudMap(telemetry, model, mapOpacity, side(!attitudeOnLeft, layout.mapWidth, layout.sideInset),
                            selfDeviceId, memberFresh, showTrail = appearance.showDiagnostics,
                            showOwnCoordinates = !(appearance.showDiagnostics && display.showPosition))
                    }
                    if (layout.bandWidth > 0.dp) {
                        val speedInset = layout.sideInset + layout.attitudeWidth +
                            if (layout.attitudeWidth > 0.dp) hudInstrumentSpacing else 0.dp
                        val altitudeInset = layout.sideInset + layout.mapWidth +
                            if (layout.mapWidth > 0.dp) hudInstrumentSpacing else 0.dp
                        HudSpeedBand(model, side(attitudeOnLeft, layout.bandWidth, speedInset))
                        HudAltitudeBand(model, side(!attitudeOnLeft, layout.bandWidth, altitudeInset))
                    }
                    val blockLabel=control.blockReason?.shortLabel()
                    StickPad("升降 / 转向",control.inputEnabled,blockLabel,display.joystickOpacity,onLeft,onLeftTouch,
                        Modifier.align(Alignment.BottomStart).width(layout.stickWidth).height(stickHeight).testTag("left-stick-panel"))
                    StickPad("前后 / 左右",control.inputEnabled,blockLabel,display.joystickOpacity,onRight,onRightTouch,
                        Modifier.align(Alignment.BottomEnd).width(layout.stickWidth).height(stickHeight).testTag("right-stick-panel"))
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when(this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
