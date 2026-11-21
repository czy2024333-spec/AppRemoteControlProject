package com.vrxfusion.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrxfusion.remote.ui.design.GroupedList
import com.vrxfusion.remote.ui.design.LargeTitleHeader
import com.vrxfusion.remote.ui.design.ListRow
import com.vrxfusion.remote.ui.design.SectionLabel
import com.vrxfusion.remote.ui.theme.*

/**
 * 首页采用 iOS「设置」式布局：大标题 + 分组圆角列表 + 右侧箭头。
 * 所有入口共用统一后端；设备管理只观察，操纵必须主动申请控制。
 */
@Composable
fun FlightHomeScreen(onSingle: ()->Unit,onCluster: ()->Unit,onManageConnections: ()->Unit) {
    Column(Modifier.fillMaxSize().background(BgDark).safeDrawingPadding().verticalScroll(rememberScrollState())
        .padding(horizontal=20.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("v0.9.3",color=TextSecondary,fontSize=13.sp)
        LargeTitleHeader("旋翼飞行控制台")
        Text("连接电脑上的动力学后端，查看真实仿真状态。所有位置以米显示。",color=TextSecondary,fontSize=15.sp,lineHeight=20.sp)
        Spacer(Modifier.height(10.dp))
        SectionLabel("工作台")
        GroupedList {
            ListRow(Icons.Default.Flight,AccentBlue,"01  独立操纵","操纵端1 / 2 / 3 · 本机控制 · 全机位置",
                "对应Token绑定一架 · 另两架只观察 · 主动申请控制",onSingle)
            HorizontalDivider(color=Separator,modifier=Modifier.padding(start=70.dp))
            ListRow(Icons.Default.Visibility,VirtualGreen,"02  三机只读总览","三机位置 · 模式 · 在线 / 告警 · 小组筛选",
                "与单机同一地址（只读，不接管）",onCluster)
        }
        Spacer(Modifier.height(10.dp))
        SectionLabel("连接")
        GroupedList {
            ListRow(Icons.Default.Link,BoundPurple,"连接与设备管理","后端地址 · 本端身份 · 设备目录",
                "连接只用于观察状态；操纵需在操纵页主动申请控制",onManageConnections)
        }
        Spacer(Modifier.height(10.dp))
        SectionLabel("首次使用")
        GroupedList {
            val steps=listOf("v0.9后端启动 --mode independent","手机与电脑连接同一局域网","地址填 http://电脑IPv4:8765",
                "选择操纵端1、2、3或观察端预设","确认本端绑定飞机，再主动申请控制")
            steps.forEachIndexed { index,text ->
                if (index>0) HorizontalDivider(color=Separator,modifier=Modifier.padding(start=52.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(TertiaryFill),contentAlignment=Alignment.Center) {
                        Text("${index+1}",color=TextPrimary,fontSize=13.sp,fontWeight=FontWeight.SemiBold)
                    }
                    Text(text,color=TextPrimary,fontSize=15.sp,lineHeight=20.sp)
                }
            }
        }
        Text("三台手机各选不同操纵端，可同时控制各自飞机。",color=TextSecondary,fontSize=13.sp,lineHeight=18.sp,
            modifier=Modifier.padding(horizontal=16.dp))
        Text("UE画面尚待UE端提供，可先在单机设置中启用手机或电脑摄像头测试叠加效果。地图仍为遥测位置示意。",
            color=WarnAmber,fontSize=12.sp,lineHeight=17.sp,modifier=Modifier.padding(horizontal=16.dp))
    }
}
