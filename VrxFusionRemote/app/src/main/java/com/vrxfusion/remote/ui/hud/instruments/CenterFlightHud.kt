package com.vrxfusion.remote.ui.hud.instruments

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrxfusion.remote.rotorpy.AttitudeAngles
import com.vrxfusion.remote.ui.hud.HudModel
import com.vrxfusion.remote.ui.theme.TextPrimary
import com.vrxfusion.remote.ui.theme.TextSecondary
import com.vrxfusion.remote.ui.theme.WarnAmber
import java.util.Locale

/** 紧凑姿态刻度使用机体遥测，画布范围独立于视频相机。 */
@Composable
internal fun CenterFlightHud(
    model: HudModel,
    showAttitude: Boolean,
    showMetrics: Boolean,
    opacity: Float,
    modifier: Modifier = Modifier
) {
    Column(modifier.testTag("central-hud"), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        HudHeading(model.headingDeg, opacity = opacity)
        if (showAttitude || showMetrics) Row(
            Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showMetrics) Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                CentralReading("GS", model.groundSpeed, "m/s", "ground-speed", opacity)
                CentralReading("VS", model.verticalSpeed, "m/s", "vertical-speed", opacity, signed = true)
            }
            if (showAttitude) CenterAttitude(model.attitude, opacity)
            if (showMetrics) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                CentralReading("Z", model.position?.z, "m", "altitude", opacity)
            }
        }
    }
}

@Composable
private fun CentralReading(label: String, value: Double?, unit: String, tag: String, opacity: Float, signed: Boolean = false) {
    if (value == null) {
        InstrumentFlag("$label N/A", "$tag-invalid-flag")
    } else {
        val reading = (if (signed) "%+.2f" else "%.2f").format(Locale.ROOT, value)
        Column(Modifier.alpha(opacity).background(readoutSurface(.6f), RoundedCornerShape(5.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp).testTag("$tag-readout")
            .semantics { contentDescription = "$label $reading $unit" }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$label $unit", color = TextSecondary, fontSize = 8.sp, lineHeight = 9.sp, maxLines = 1)
            Text(reading, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                lineHeight = 12.sp, maxLines = 1)
        }
    }
}

@Composable
private fun CenterAttitude(angles: AttitudeAngles?, opacity: Float) {
    if (angles == null) {
        InstrumentFlag("姿态 N/A", "attitude-invalid-flag")
        return
    }
    val stroke = Color(0xFFD3F5E7)
    Canvas(Modifier.size(width = 60.dp, height = 48.dp).clipToBounds().alpha(opacity)
        .testTag("center-attitude-scale")
        .semantics { contentDescription = "横滚 %.1f°，俯仰 %.1f°".format(Locale.ROOT, angles.rollDeg, angles.pitchDeg) }) {
        val lineSpacing = size.height / 7
        val offset = (angles.pitchDeg / 5).coerceIn(-4.0, 4.0).toFloat() * lineSpacing
        rotate(-angles.rollDeg.toFloat()) {
            for (tick in -4..4) {
                val y = center.y + tick * lineSpacing + offset
                val length = if (tick % 2 == 0) size.width * .30f else size.width * .18f
                val start = Offset(center.x - length, y)
                val end = Offset(center.x + length, y)
                drawLine(Color.Black.copy(alpha = .72f), start, end, 3.dp.toPx())
                drawLine(stroke, start, end, 1.dp.toPx())
            }
        }
        val inner = 5.dp.toPx()
        val outer = 13.dp.toPx()
        drawLine(WarnAmber, Offset(center.x - outer, center.y), Offset(center.x - inner, center.y), 2.dp.toPx())
        drawLine(WarnAmber, Offset(center.x + inner, center.y), Offset(center.x + outer, center.y), 2.dp.toPx())
    }
}
