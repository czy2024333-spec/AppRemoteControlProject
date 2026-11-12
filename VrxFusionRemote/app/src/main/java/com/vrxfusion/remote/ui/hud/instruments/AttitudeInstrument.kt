package com.vrxfusion.remote.ui.hud.instruments

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import com.vrxfusion.remote.rotorpy.AttitudeAngles
import com.vrxfusion.remote.rotorpy.AttitudeMath
import com.vrxfusion.remote.ui.LocalAppMotionEnabled
import com.vrxfusion.remote.ui.hud.HudModel
import com.vrxfusion.remote.ui.theme.HudSurface
import com.vrxfusion.remote.ui.theme.SurfaceDark
import com.vrxfusion.remote.ui.theme.TextPrimary
import com.vrxfusion.remote.ui.theme.TextSecondary
import com.vrxfusion.remote.ui.theme.WarnAmber
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal fun wrapBank(angle: Float): Float = ((angle + 180f) % 360f + 360f) % 360f - 180f

@Composable
internal fun HudAttitude(model: State<HudModel>, opacity: Float, modifier: Modifier) {
    val attitude by remember(model) { derivedStateOf(structuralEqualityPolicy()) { model.value.attitude } }
    AttitudePanel(attitude, opacity, modifier)
}

/** 失效会取消当前动画并冻结已绘制角度。首个有效样本直接定位。 */
@Composable
private fun AttitudePanel(angles: AttitudeAngles?, opacity: Float, modifier: Modifier) {
    val animation = remember {
        val start = angles?.let { Offset(it.rollDeg.toFloat(), it.pitchDeg.toFloat()) } ?: Offset.Zero
        Animatable(start, Offset.VectorConverter)
    }
    var hasSample by remember { mutableStateOf(angles != null) }
    val motion = LocalAppMotionEnabled.current
    LaunchedEffect(angles?.rollDeg, angles?.pitchDeg, motion) {
        if (angles != null) {
            val current = animation.value
            val nearestRoll = current.x + wrapBank(angles.rollDeg.toFloat() - current.x)
            val target = Offset(
                AttitudeMath.stableAngle(current.x, nearestRoll.toDouble()),
                AttitudeMath.stableAngle(current.y, angles.pitchDeg)
            )
            if (motion && hasSample) animation.animateTo(target, tween(120, easing = LinearEasing)) else animation.snapTo(target)
            hasSample = true
        }
    }
    Column(
        modifier.testTag("attitude-panel").clip(RoundedCornerShape(12.dp))
            .background(HudSurface.copy(alpha = HudSurface.alpha * opacity)).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val diameter = minOf(maxWidth, maxHeight, 104.dp)
            AttitudeFace(animation.asState(), hasSample, Modifier.size(diameter)
                .alpha(if (angles == null) opacity * .35f else opacity))
            if (angles == null) InstrumentFlag("姿态 N/A", "attitude-invalid-flag")
        }
        if (angles == null) {
            Spacer(Modifier.height(30.dp))
        } else {
            Column(Modifier.height(30.dp).background(readoutSurface(opacity), RoundedCornerShape(5.dp))
                .padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("横滚 %.1f°".format(Locale.ROOT, angles.rollDeg), color = TextPrimary, fontSize = 11.sp,
                    lineHeight = 15.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                Text("俯仰 %.1f°".format(Locale.ROOT, angles.pitchDeg), color = TextPrimary, fontSize = 11.sp,
                    lineHeight = 15.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
            }
        }
    }
}

/** 设置页示意仪表与飞行仪表共用刻度和地平线。 */
@Composable
internal fun AttitudeDial(roll: Float, pitch: Float, modifier: Modifier = Modifier) {
    AttitudeFace(rememberUpdatedState(Offset(roll, pitch)), true, modifier)
}

@Composable
private fun AttitudeFace(angles: State<Offset>, hasSample: Boolean, modifier: Modifier) {
    Box(modifier.clip(CircleShape).background(SurfaceDark).testTag("attitude-dial").drawWithCache {
        val width = size.width
        val height = size.height
        val center = Offset(width / 2f, height / 2f)
        val radius = size.minDimension / 2f
        val bankTicks = listOf(-60, -30, 0, 30, 60).map { degrees ->
            val radians = (degrees - 90) * PI / 180
            val direction = Offset(cos(radians).toFloat(), sin(radians).toFloat())
            center + direction * (radius - 2.dp.toPx()) to center + direction * (radius - 7.dp.toPx())
        }
        val bankPointer = Path().apply {
            moveTo(center.x, center.y - radius + 8.dp.toPx())
            lineTo(center.x - 3.dp.toPx(), center.y - radius + 13.dp.toPx())
            lineTo(center.x + 3.dp.toPx(), center.y - radius + 13.dp.toPx())
            close()
        }
        val ladder = (-20..20 step 5).filter { it != 0 }.map { degrees ->
            val y = -degrees * height / 110
            val half = width * if (degrees % 10 == 0) .21f else .12f
            Offset(center.x - half, y) to Offset(center.x + half, y)
        }
        onDrawBehind {
            if (hasSample) {
                val value = angles.value
                rotate(-value.x, pivot = center) {
                    translate(top = AttitudeMath.horizonY(height, value.y.toDouble())) {
                        drawRect(Color(0xFF1D4E89), Offset(-width, -2 * height), Size(3 * width, 2 * height))
                        drawRect(Color(0xFF5A4326), Offset(-width, 0f), Size(3 * width, 2 * height))
                        drawLine(Color.White, Offset(-width, 0f), Offset(2 * width, 0f), 2.dp.toPx())
                        ladder.forEach { (start, end) -> drawLine(Color.White.copy(alpha = .72f), start, end, 1.dp.toPx()) }
                    }
                    drawPath(bankPointer, WarnAmber)
                }
            }
            bankTicks.forEach { (start, end) -> drawLine(TextSecondary, start, end, 1.dp.toPx()) }
            drawLine(WarnAmber, center - Offset(width * .28f, 0f), center - Offset(width * .08f, 0f), 2.dp.toPx())
            drawLine(WarnAmber, center + Offset(width * .08f, 0f), center + Offset(width * .28f, 0f), 2.dp.toPx())
            drawCircle(WarnAmber, 2.dp.toPx(), center)
            drawCircle(TextSecondary, (radius - 1.dp.toPx()).coerceAtLeast(0f), center, style = Stroke(1.dp.toPx()))
        }
    })
}
