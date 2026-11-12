package com.vrxfusion.remote.ui.hud

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import com.vrxfusion.remote.ui.LocalAppMotionEnabled
import com.vrxfusion.remote.ui.design.ProtectionText
import com.vrxfusion.remote.ui.state.ControlStage
import com.vrxfusion.remote.ui.state.ControlUi
import com.vrxfusion.remote.ui.state.FlightNotice
import com.vrxfusion.remote.ui.state.NoticeTone
import com.vrxfusion.remote.ui.theme.HudSurface
import com.vrxfusion.remote.ui.theme.TextPrimary
import com.vrxfusion.remote.ui.theme.VirtualGreen
import com.vrxfusion.remote.ui.theme.WarnAmber
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val ALERT_VISIBLE_MS = 1000L
private const val ALERT_ENTRY_ALPHA = .76f
private const val ALERT_RECOVERY_MS = 5000L

private data class AlertCue(
    val title: String?,
    val detail: String,
    val tone: NoticeTone,
    val transient: Boolean
)

internal fun ControlUi.activeHudNotices(): List<FlightNotice> =
    (listOf(notice) + secondaryNotices).filter { it.tone == NoticeTone.DANGER || it.tone == NoticeTone.WARNING }
        .distinctBy { it.hudAlertKey() }.sortedBy { if (it.tone == NoticeTone.DANGER) 0 else 1 }

internal fun FlightNotice.hudAlertKey(): ControlStage = when (stage) {
    ControlStage.RELEASE, ControlStage.RECOVERING -> ControlStage.PROTECTED
    ControlStage.CAPABILITY_UNAVAILABLE -> ControlStage.READ_ONLY
    else -> stage
}

private fun FlightNotice.cue() = AlertCue(title, detail, tone, stage == ControlStage.REJECTED)

@Composable
private fun rememberRecoveries(notices: List<FlightNotice>, canConfirm: Boolean): List<AlertCue> {
    val warnings = notices.filter { it.tone == NoticeTone.DANGER }.associateBy { it.hudAlertKey() }
    var previous by remember { mutableStateOf(emptyMap<ControlStage, FlightNotice>()) }
    val recoveries = remember { mutableStateMapOf<ControlStage, AlertCue>() }
    val expiries = remember { mutableMapOf<ControlStage, Job>() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(warnings, canConfirm) {
        if (canConfirm) {
            for ((key, old) in previous) {
                if (key !in warnings) {
                    val detail = when (key) {
                        ControlStage.PROTECTED -> "保护已解除"
                        ControlStage.STALE -> "实时状态已恢复"
                        else -> old.title
                    }
                    recoveries[key] = AlertCue("已恢复", detail, NoticeTone.GOOD, false)
                    expiries.remove(key)?.cancel()
                    expiries[key] = scope.launch {
                        delay(ALERT_RECOVERY_MS)
                        recoveries.remove(key)
                        expiries.remove(key)
                    }
                }
            }
        }
        for (key in warnings.keys) {
            expiries.remove(key)?.cancel()
            recoveries.remove(key)
        }
        // 断流时无法确认上一次的保护是否解除，等实时状态回来再生成恢复记录。
        previous = if (canConfirm) warnings else previous + warnings
    }
    return recoveries.values.toList()
}

/** 固定提示行高度，告警切换时仪表、摇杆与视频位置保持稳定。 */
@Composable
internal fun FlightAlert(control: ControlUi, receipt: State<String?>, onVisible: (Boolean) -> Unit) {
    val notices = remember(control.notice, control.secondaryNotices) { control.activeHudNotices() }
    val recoveries = rememberRecoveries(notices, control.stage != ControlStage.OFFLINE && control.stage != ControlStage.STALE)
    val cue = notices.firstOrNull()?.cue()
        ?: receipt.value?.takeIf { it.isNotBlank() }?.let { AlertCue(null, it, NoticeTone.NORMAL, true) }
    val recoveryOnBar = if (cue == null) recoveries.firstOrNull() else null
    val others = notices.drop(1).map { it.cue() } + if (recoveryOnBar != null) recoveries.drop(1) else recoveries
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(others.isEmpty()) { if (others.isEmpty()) expanded = false }
    val textLine = with(LocalDensity.current) { 16.sp.toDp() }
    BoxWithConstraints(Modifier.fillMaxWidth().height(maxOf(38.dp, textLine * 2 + 6.dp))) {
        val showList = expanded && others.isNotEmpty()
        val cardWidth = minOf(420.dp, if (showList) maxWidth * .48f else maxWidth)
        val badge = if (!showList && others.isNotEmpty()) "+${others.size}" else null
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            // 红色告警保持在左侧，展开后的滚动区仍使用同一行。
            if (cue?.tone == NoticeTone.DANGER) {
                SideEffect { onVisible(true) }
                AlertCard(cue, cardWidth, badge = badge, onExpand = { expanded = true })
            } else {
                BufferedAlert(cue, LocalAppMotionEnabled.current, cardWidth, badge, { expanded = true }, recoveryOnBar, onVisible)
            }
            if (showList) {
                Row(Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState()).testTag("flight-alert-list"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    others.forEach { item ->
                        AlertCard(item, 210.dp, tag = if (item.tone == NoticeTone.GOOD) "flight-alert-recovered" else "flight-alert-detail")
                    }
                }
                AlertToggle("收起", "flight-alert-collapse") { expanded = false }
            }
        }
    }
}

@Composable
private fun BufferedAlert(
    cue: AlertCue?, motion: Boolean, width: Dp, badge: String?, onExpand: () -> Unit,
    recovery: AlertCue?, onVisible: (Boolean) -> Unit
) {
    var previous by remember { mutableStateOf<AlertCue?>(null) }
    var shownAt by remember { mutableStateOf<Long?>(null) }
    val opacity = remember { Animatable(ALERT_ENTRY_ALPHA) }
    LaunchedEffect(cue, motion) {
        if (cue != null) {
            if (cue != previous) {
                previous = cue
                shownAt = null
                opacity.snapTo(ALERT_ENTRY_ALPHA)
            }
            if (shownAt == null) shownAt = withFrameMillis { it }
            if (motion) opacity.animateTo(1f, tween(140)) else opacity.snapTo(1f)
        } else if (previous?.transient == true) {
            val now = withFrameMillis { it }
            val elapsed = shownAt?.let { now - it } ?: 0L
            delay((ALERT_VISIBLE_MS - elapsed).coerceIn(0L, ALERT_VISIBLE_MS))
            // 关闭动画仍保留阅读时间，到时直接收起。
            if (motion) opacity.animateTo(0f, tween(180))
            previous = null
        } else {
            previous = null
        }
    }
    // 断连、只读、归中等提示描述当前条件，条件恢复后立即让位给当前状态。
    val shown = cue ?: previous?.takeIf { it.transient }
    SideEffect { onVisible(shown != null || recovery != null) }
    if (shown != null) {
        val fade = if (motion) Modifier.graphicsLayer {
            alpha = when {
                cue == null -> opacity.value
                cue != previous -> ALERT_ENTRY_ALPHA
                else -> opacity.value.coerceAtLeast(ALERT_ENTRY_ALPHA)
            }
        } else Modifier
        AlertCard(shown, width, fade, badge, onExpand)
    } else if (recovery != null) {
        AlertCard(recovery, width, badge = badge, onExpand = onExpand, tag = "flight-alert-recovered")
    }
}

@Composable
private fun AlertCard(
    cue: AlertCue,
    width: Dp,
    modifier: Modifier = Modifier,
    badge: String? = null,
    onExpand: () -> Unit = {},
    tag: String = "flight-notice"
) {
    Row(
        modifier.widthIn(max = width).fillMaxHeight()
            .background(HudSurface, RoundedCornerShape(10.dp)).padding(start = 8.dp, end = if (badge == null) 8.dp else 2.dp)
            .testTag(tag).semantics {
                liveRegion = if (cue.tone == NoticeTone.DANGER) LiveRegionMode.Assertive else LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f, fill = false).padding(vertical = 3.dp), verticalArrangement = Arrangement.Center) {
            if (cue.title != null) {
                val titleColor = when (cue.tone) {
                    NoticeTone.DANGER -> ProtectionText
                    NoticeTone.GOOD -> VirtualGreen
                    else -> WarnAmber
                }
                Text(cue.title, color = titleColor, fontSize = 11.sp, lineHeight = 14.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(cue.detail, color = TextPrimary, fontSize = 11.sp, lineHeight = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (badge != null) AlertToggle(badge, "flight-alert-expand", onExpand)
    }
}

@Composable
private fun AlertToggle(label: String, tag: String, onClick: () -> Unit) {
    Box(Modifier.widthIn(min = 44.dp).fillMaxHeight()
        .clickable(role = Role.Button, onClickLabel = if (tag == "flight-alert-expand") "展开其他告警" else "收起告警列表", onClick = onClick)
        .testTag(tag), contentAlignment = Alignment.Center) {
        Text(label, color = TextPrimary, fontSize = 11.sp, maxLines = 1)
    }
}
