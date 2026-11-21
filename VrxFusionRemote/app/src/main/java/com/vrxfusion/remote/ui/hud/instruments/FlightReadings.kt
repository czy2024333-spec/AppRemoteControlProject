package com.vrxfusion.remote.ui.hud.instruments

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrxfusion.remote.ui.hud.HudModel
import com.vrxfusion.remote.ui.theme.AccentBlue
import com.vrxfusion.remote.ui.theme.TextPrimary
import com.vrxfusion.remote.ui.theme.TextSecondary
import java.util.Locale
import kotlin.math.floor

@Composable
internal fun HudHeading(heading: Int?, banded: Boolean = false, opacity: Float = 1f) {
    if (heading == null) {
        InstrumentFlag("HDG N/A", "heading-invalid-flag")
        return
    }
    val label = "HDG %03d°".format(Locale.ROOT, heading)
    val fill = readoutSurface(.65f)
    if (banded) {
        Box(Modifier.width(124.dp).height(24.dp).background(fill, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                val spacing = size.width / 12f
                val shift = heading % 5 / 5f * spacing
                for (tick in 0..12) {
                    val x = tick * spacing - shift
                    if (x < size.width * .2f || x > size.width * .8f) {
                        drawLine(TextSecondary, Offset(x, size.height - (if (tick % 2 == 0) 7.dp else 4.dp).toPx()),
                            Offset(x, size.height - 2.dp.toPx()), 1.dp.toPx())
                    }
                }
            }
            HudNumber(label, Color.Transparent, Modifier.testTag("heading-readout"))
        }
    } else {
        HudNumber(label, fill, Modifier.alpha(opacity).testTag("heading-readout"))
    }
}

@Composable
internal fun HudAltitudeNumber(value: Double?) {
    if (value == null) InstrumentFlag("Z N/A", "altitude-invalid-flag")
    else HudNumber("Z %.2f m".format(Locale.ROOT, value), readoutSurface(.65f),
        Modifier.testTag("altitude-readout"))
}

@Composable
internal fun HudGroundSpeedNumber(value: Double?) {
    if (value == null) InstrumentFlag("GS N/A", "ground-speed-invalid-flag")
    else HudNumber("GS %.2f m/s".format(Locale.ROOT, value), readoutSurface(.65f),
        Modifier.testTag("ground-speed-readout"))
}

@Composable
internal fun HudVerticalSpeedNumber(value: Double?) {
    if (value == null) InstrumentFlag("VS N/A", "vertical-speed-invalid-flag")
    else HudNumber("VS %+.2f m/s".format(Locale.ROOT, value), readoutSurface(.65f),
        Modifier.testTag("vertical-speed-readout"))
}

@Composable
internal fun HudSpeedBand(model: State<HudModel>, modifier: Modifier) {
    val groundSpeed = model.value.groundSpeed
    val verticalSpeed = model.value.verticalSpeed
    Column(modifier.background(readoutSurface(.65f), RoundedCornerShape(10.dp)).padding(vertical = 4.dp)
        .testTag("speed-band"), horizontalAlignment = Alignment.CenterHorizontally) {
        TapeReading("GS", groundSpeed, "m/s", "ground-speed")
        Canvas(Modifier.weight(1f).fillMaxWidth()) {
            val left = size.width * .42f
            for (tick in 0..4) {
                val y = size.height * (.12f + tick * .19f)
                drawLine(TextSecondary, Offset(left, y), Offset(left + (if (tick % 2 == 0) 8.dp else 4.dp).toPx(), y), 1.dp.toPx())
            }
            if (verticalSpeed != null) {
                // 垂直速度刻度为 ±3 m/s，正值向上。
                val y = size.height * (.5f - .38f * (verticalSpeed / 3.0).coerceIn(-1.0, 1.0).toFloat())
                drawLine(AccentBlue, Offset(left - 6.dp.toPx(), y), Offset(left + 10.dp.toPx(), y), 2.dp.toPx())
            }
        }
        TapeReading("VS", verticalSpeed, "m/s", "vertical-speed", signed = true)
    }
}

@Composable
internal fun HudAltitudeBand(model: State<HudModel>, modifier: Modifier) {
    val altitude = model.value.position?.z
    Column(modifier.background(readoutSurface(.65f), RoundedCornerShape(10.dp)).padding(vertical = 4.dp)
        .testTag("altitude-band"), horizontalAlignment = Alignment.CenterHorizontally) {
        TapeReading("Z", altitude, "m", "altitude")
        Canvas(Modifier.weight(1f).fillMaxWidth()) {
            if (altitude != null) {
                // 以当前仿真 Z 附近的整米为刻度。
                val first = floor(altitude) - 2
                for (tick in 0..5) {
                    val y = (size.height * (.5 - (first + tick - altitude) / 6)).toFloat()
                    drawLine(TextSecondary, Offset(size.width * .35f, y),
                        Offset(size.width * (if (tick % 2 == 0) .7f else .58f), y), 1.dp.toPx())
                }
                drawLine(AccentBlue, Offset(size.width * .2f, size.height / 2), Offset(size.width * .72f, size.height / 2), 2.dp.toPx())
            }
        }
    }
}

@Composable
private fun TapeReading(label: String, value: Double?, unit: String, tag: String, signed: Boolean = false) {
    Text(label, color = TextSecondary, fontSize = 9.sp, lineHeight = 10.sp, maxLines = 1)
    if (value == null) {
        InstrumentFlag("N/A", "$tag-invalid-flag")
    } else {
        val text = (if (signed) "%+.2f" else "%.2f").format(Locale.ROOT, value)
        Text(text, color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 11.sp,
            maxLines = 1, modifier = Modifier.testTag("$tag-readout").semantics { contentDescription = "$label $text $unit" })
        Text(unit, color = TextSecondary, fontSize = 8.sp, lineHeight = 9.sp, maxLines = 1)
    }
}
