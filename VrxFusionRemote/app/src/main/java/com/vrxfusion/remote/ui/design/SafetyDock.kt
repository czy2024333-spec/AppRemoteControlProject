package com.vrxfusion.remote.ui.design

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrxfusion.remote.ui.theme.EstopRed
import com.vrxfusion.remote.ui.theme.TextSecondary

private val ProtectionFill = Color(0xFF4A2220)
internal val ProtectionText = Color(0xFFFF8A80)

/** 顶栏和设置共用安全按钮，权限和命令由调用方提供。 */
@Composable
internal fun SafetyDock(
    stopEnabled: Boolean,
    protectEnabled: Boolean,
    onStop: () -> Unit,
    onProtect: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    pressEffect: @Composable (MutableInteractionSource) -> Modifier = { Modifier },
    leading: @Composable RowScope.() -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    val layout = if (compact) modifier else modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)
    Row(layout, horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
        leading()
        val buttonSize = if (compact) Modifier.width(72.dp).height(48.dp) else Modifier.weight(1f).heightIn(min = 48.dp)
        SafetyAction("停止", "flight-stop", stopEnabled, EstopRed, Color.White, buttonSize, compact, pressEffect) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onStop()
        }
        SafetyAction("保护", "flight-protect", protectEnabled, ProtectionFill, ProtectionText, buttonSize, compact, pressEffect) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onProtect()
        }
    }
}

@Composable
private fun SafetyAction(
    label: String,
    tag: String,
    enabled: Boolean,
    container: Color,
    text: Color,
    modifier: Modifier,
    compact: Boolean,
    pressEffect: @Composable (MutableInteractionSource) -> Modifier,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val colors = if (compact) {
        ButtonDefaults.buttonColors(containerColor = container, contentColor = text,
            disabledContainerColor = Color.Transparent, disabledContentColor = TextSecondary)
    } else {
        ButtonDefaults.buttonColors(containerColor = container, contentColor = text)
    }
    Button(
        onClick = onClick, enabled = enabled, interactionSource = interaction,
        modifier = modifier.then(pressEffect(interaction)).testTag(tag),
        shape = if (compact) RoundedCornerShape(24.dp) else ButtonDefaults.shape,
        colors = colors,
        contentPadding = if (compact) PaddingValues(4.dp) else ButtonDefaults.ContentPadding
    ) {
        Text(label, fontSize = if (compact) 13.sp else TextUnit.Unspecified)
    }
}
