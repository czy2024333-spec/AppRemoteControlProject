package com.vrxfusion.remote.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrxfusion.remote.ui.theme.SurfaceDark
import com.vrxfusion.remote.ui.theme.TertiaryFill
import com.vrxfusion.remote.ui.theme.TextPrimary
import com.vrxfusion.remote.ui.theme.TextSecondary

@Composable
internal fun GroupedList(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SurfaceDark), content = content)
}

@Composable
internal fun GroupedCard(
    modifier: Modifier = Modifier,
    radius: Dp = 18.dp,
    spacing: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        Modifier.fillMaxWidth().background(SurfaceDark, RoundedCornerShape(radius)).padding(16.dp).then(modifier),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content
    )
}

@Composable
internal fun SectionLabel(text: String) {
    Text(text, color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp))
}

/** 按下时只改变行的背景，点击由原回调立即处理。 */
@Composable
internal fun ListRow(icon: ImageVector, tint: Color, title: String, subtitle: String, note: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier.fillMaxWidth().background(if (pressed) TertiaryFill else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(tint), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = TextPrimary, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
            Text(note, color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = TextSecondary.copy(alpha = .6f))
    }
}
