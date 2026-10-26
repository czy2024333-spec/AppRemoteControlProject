package com.vrxfusion.remote.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrxfusion.remote.MainViewModel
import com.vrxfusion.remote.protocol.Role
import com.vrxfusion.remote.protocol.WsTransport
import com.vrxfusion.remote.ui.theme.AccentBlue
import com.vrxfusion.remote.ui.theme.BgDark
import com.vrxfusion.remote.ui.theme.BoundPurple
import com.vrxfusion.remote.ui.theme.SurfaceCard
import com.vrxfusion.remote.ui.theme.TextPrimary
import com.vrxfusion.remote.ui.theme.TextSecondary
import com.vrxfusion.remote.ui.theme.WarnAmber

/**
 * 封面 + 登录页 —— 未连接服务时全屏展示。
 * 顶部品牌区（渐变 + 四旋翼图标 + 系统全称 + 版本徽章），中部登录卡片。
 */
@Composable
fun LoginScreen(
    vm: MainViewModel,
    onOpenRotorPy: () -> Unit = {},
    onOpenLandscape: () -> Unit = {}
) {
    val conn by vm.connState.collectAsState()
    val role by vm.role.collectAsState()
    val name by vm.userName.collectAsState()
    val url by vm.serverUrl.collectAsState()

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0B1E33), Color(0xFF10202F), BgDark)
                )
            )
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(44.dp))
            DroneEmblem()
            Spacer(Modifier.height(20.dp))
            Text(
                "虚实融合遥控",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "旋翼式无人机仿真与多通路遥控系统",
                fontSize = 13.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
            VersionBadge()
            Spacer(Modifier.height(28.dp))

            // ---- 登录卡片 ----
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(SurfaceCard.copy(alpha = 0.92f))
                    .padding(20.dp)
            ) {
                Text("连接统一控制服务", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text("旧版联调入口：角色由客户端选择，尚未接入正式账号认证", fontSize = 11.sp, color = WarnAmber)
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = url,
                    onValueChange = { vm.setServerUrl(it) },
                    label = { Text("服务地址") },
                    placeholder = { Text("ws://<服务IP>:8767/ws") },
                    leadingIcon = { Icon(Icons.Default.Link, contentDescription = null, tint = AccentBlue) },
                    singleLine = true,
                    enabled = conn == WsTransport.ConnState.DISCONNECTED,
                    colors = loginFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { vm.setUserName(it) },
                    label = { Text("用户名") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = AccentBlue) },
                    singleLine = true,
                    enabled = conn == WsTransport.ConnState.DISCONNECTED,
                    colors = loginFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))

                Text("演示角色（不代表已验证身份）", fontSize = 12.sp, color = TextSecondary)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Role.entries.forEach { r ->
                        RoleSegment(
                            role = r,
                            selected = role == r,
                            enabled = conn == WsTransport.ConnState.DISCONNECTED,
                            onClick = { vm.setRole(r) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (role == Role.OBSERVER) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "观察员为只读角色：可查看状态，不能控制（服务端强制拒绝）",
                        fontSize = 11.sp, color = WarnAmber
                    )
                }
                Spacer(Modifier.height(18.dp))

                Button(
                    onClick = { vm.connect() },
                    enabled = conn == WsTransport.ConnState.DISCONNECTED,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentBlue,
                        disabledContainerColor = AccentBlue.copy(alpha = 0.5f)
                    )
                ) {
                    if (conn == WsTransport.ConnState.CONNECTING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = TextPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("连接中…", fontSize = 15.sp)
                    } else {
                        Text("连 接 服 务", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "V2.3 需求基线 · 协议 v0.1 · 角色B（APP / 控制与状态服务）",
                fontSize = 10.sp,
                color = TextSecondary.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(14.dp))
            // RotorPy 联调直联入口（不依赖统一控制服务）
            Text(
                "飞行操控：三维遥测 / 双摇杆 / 横竖屏自适应 ›",
                fontSize = 12.sp,
                color = AccentBlue,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenRotorPy() }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 四旋翼无人机徽章：Canvas 绘制，带呼吸光环动画 */
@Composable
private fun DroneEmblem() {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.85f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
        label = "pulseAnim"
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(110.dp)) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = size.minDimension / 2f
            // 外圈光环
            drawCircle(
                color = AccentBlue.copy(alpha = 0.18f * pulse),
                radius = r * pulse
            )
            drawCircle(
                color = AccentBlue.copy(alpha = 0.5f),
                radius = r * 0.78f,
                style = Stroke(width = 2.dp.toPx())
            )
            // 机臂（X 形）
            val arm = r * 0.46f
            val w = 5.dp.toPx()
            drawLine(Color(0xFF8FC4F5), Offset(c.x - arm, c.y - arm), Offset(c.x + arm, c.y + arm), strokeWidth = w)
            drawLine(Color(0xFF8FC4F5), Offset(c.x + arm, c.y - arm), Offset(c.x - arm, c.y + arm), strokeWidth = w)
            // 四个旋翼
            val rotor = r * 0.17f
            listOf(
                Offset(c.x - arm, c.y - arm), Offset(c.x + arm, c.y - arm),
                Offset(c.x - arm, c.y + arm), Offset(c.x + arm, c.y + arm)
            ).forEach { p ->
                drawCircle(AccentBlue, radius = rotor, center = p, style = Stroke(width = 3.dp.toPx()))
                drawCircle(Color(0xFF0B1E33), radius = rotor * 0.35f, center = p)
            }
            // 机身
            drawCircle(Color(0xFFE6EDF3), radius = r * 0.16f, center = c)
            drawCircle(AccentBlue, radius = r * 0.07f, center = c)
        }
    }
}

/** 版本徽章 */
@Composable
private fun VersionBadge() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(AccentBlue.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(AccentBlue))
        Spacer(Modifier.width(6.dp))
        Text("v0.8.0", fontSize = 11.sp, color = AccentBlue, fontWeight = FontWeight.Medium)
    }
}

/** 分段式角色选择器 */
@Composable
private fun RoleSegment(
    role: Role,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (selected) AccentBlue else Color.Transparent
    val border = if (selected) AccentBlue else TextSecondary.copy(alpha = 0.4f)
    val fg = if (selected) Color.White else TextSecondary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .alpha(if (enabled) 1f else 0.5f)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 10.dp)
    ) {
        Text(role.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = fg)
        Text(
            when (role) {
                Role.ADMIN -> "全部权限"
                Role.OPERATOR -> "可控制"
                Role.OBSERVER -> "只读"
            },
            fontSize = 10.sp,
            color = fg.copy(alpha = 0.8f)
        )
    }
}

@Composable
private fun loginFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AccentBlue,
    unfocusedBorderColor = TextSecondary.copy(alpha = 0.4f),
    focusedLabelColor = AccentBlue,
    unfocusedLabelColor = TextSecondary,
    cursorColor = AccentBlue,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary
)
