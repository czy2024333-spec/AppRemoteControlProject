package com.vrxfusion.remote.ui.hud.instruments

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrxfusion.remote.ui.design.ProtectionText
import com.vrxfusion.remote.ui.theme.HudSurface

/** 滑块调暗底的透明度，保留数值文字的亮度。 */
internal fun readoutSurface(opacity: Float): Color =
    HudSurface.copy(alpha = .70f + .25f * ((opacity - .2f) / .8f).coerceIn(0f, 1f))

@Composable
internal fun InstrumentFlag(label: String, tag: String, modifier: Modifier = Modifier) {
    Text(
        label, color = ProtectionText, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold,
        maxLines = 1, modifier = modifier.background(Color(0xFF4A2220), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp).testTag(tag)
    )
}
