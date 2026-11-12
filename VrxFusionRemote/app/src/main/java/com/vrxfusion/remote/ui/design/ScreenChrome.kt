package com.vrxfusion.remote.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrxfusion.remote.ui.theme.TextPrimary

@Composable
internal fun LargeTitleHeader(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier, color = TextPrimary, fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold)
}

@Composable
internal fun NavBackButton(label: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onBack, modifier = modifier, contentPadding = PaddingValues(horizontal = 0.dp)) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
        Text(label)
    }
}

/** 调用方提供状态文字及色调，组件只负责圆点和文字的排版。 */
@Composable
internal fun StatusPill(
    dotColor: Color,
    modifier: Modifier = Modifier,
    dotSize: Dp = 8.dp,
    gap: Dp = 8.dp,
    content: @Composable RowScope.() -> Unit
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(dotSize).clip(CircleShape).background(dotColor))
        Spacer(Modifier.width(gap))
        content()
    }
}
