package com.vrxfusion.remote.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrxfusion.remote.ui.theme.TextSecondary

@Composable
internal fun ControlStageStrip(
    stepIndex: Int?,
    stepCount: Int,
    label: String,
    tone: Color,
    modifier: Modifier = Modifier
) {
    val description = if (stepIndex == null) label else "第 ${stepIndex + 1} 步，共 ${stepCount} 步，$label"
    val shape = RoundedCornerShape(6.dp)
    val badge = if (stepIndex == null) Modifier.background(tone.copy(alpha = .14f), shape).padding(horizontal = 5.dp) else Modifier
    Row(
        modifier.then(badge).semantics(mergeDescendants = true) { stateDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(if (stepIndex == null) 1 else stepCount) { index ->
                val color = when {
                    stepIndex == null || index == stepIndex -> tone
                    index < stepIndex -> tone.copy(alpha = .55f)
                    else -> TextSecondary.copy(alpha = .34f)
                }
                Box(Modifier.size(5.dp).clip(CircleShape).background(color))
            }
        }
        Text(label, color = tone, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
