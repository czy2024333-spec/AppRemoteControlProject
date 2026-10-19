package com.vrxfusion.remote.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrxfusion.remote.ui.theme.*
import com.vrxfusion.remote.video.*

/** 选中只修改候选源；只有“启用测试画面”才访问摄像头或网络。 */
@Composable
fun VideoSettingsPanel(value: VideoSettings,code: String,active: VideoSource,status: String,
    onChange: (VideoSettings)->Unit,onCodeChange: (String)->Unit,onEnable: ()->Unit,onStop: ()->Unit) {
    var showCode by remember { mutableStateOf(false) }
    Column(verticalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.fillMaxWidth()) {
        Text("画面测试",fontSize=18.sp,fontWeight=FontWeight.SemiBold,color=TextPrimary)
        Text("摄像头画面替代UE占位区域，用于检查全屏与HUD叠加；不生成三维场景，不控制飞机。",
            color=TextSecondary,fontSize=11.sp,lineHeight=17.sp)
        // 短竖屏纵向排列，避免三个长标签挤在一行。
        VideoSource.entries.forEach { source ->
            FilterChip(selected=value.source==source,onClick={ onChange(value.copy(source=source)) },
                label={ Text(source.label) },modifier=Modifier.fillMaxWidth())
        }
        when(value.source) {
            VideoSource.PHONE -> {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text("使用前置摄像头",color=TextPrimary,modifier=Modifier.weight(1f))
                    Switch(checked=value.frontCamera,onCheckedChange={ onChange(value.copy(frontCamera=it)) })
                }
                Text("默认使用后置镜头。开启时申请相机权限；前置预览可能为镜像，飞行方向仍以轨迹图为准。",
                    color=TextSecondary,fontSize=11.sp,lineHeight=17.sp)
            }
            VideoSource.PC -> {
                OutlinedTextField(value=value.pcUrl,onValueChange={ onChange(value.copy(pcUrl=it)) },label={ Text("电脑画面地址") },
                    singleLine=true,enabled=active!=VideoSource.PC,modifier=Modifier.fillMaxWidth().testTag("video-url"),
                    supportingText={ Text("例如 http://电脑IPv4:8768/video.mjpeg",fontSize=11.sp) })
                OutlinedTextField(value=code,onValueChange=onCodeChange,label={ Text("摄像头访问码") },singleLine=true,
                    enabled=active!=VideoSource.PC,visualTransformation=if (showCode) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon={ TextButton(onClick={ showCode=!showCode }) { Text(if (showCode) "隐藏" else "显示") } },
                    modifier=Modifier.fillMaxWidth().testTag("video-code"),
                    supportingText={ Text("填写电脑摄像头窗口给出的访问码，不是飞控来源Token",fontSize=11.sp) })
                videoConnectionIssue(value.pcUrl,code)?.let { Text(it,color=WarnAmber,fontSize=11.sp) }
                Text("电脑和手机需在同一局域网；修改地址或访问码前先关闭当前电脑画面。",color=TextSecondary,fontSize=11.sp,lineHeight=17.sp)
            }
            VideoSource.NONE -> Text("恢复“等待UE视频源接入”的占位画面。",color=TextSecondary,fontSize=11.sp)
        }
        Surface(shape=RoundedCornerShape(12.dp),color=SurfaceCard,modifier=Modifier.fillMaxWidth()) {
            Text("当前：${active.label}\n$status",fontSize=12.sp,lineHeight=19.sp,color=TextPrimary,modifier=Modifier.padding(12.dp))
        }
        Button(onClick=onEnable,enabled=value.source!=VideoSource.PC || videoConnectionIssue(value.pcUrl,code)==null,
            modifier=Modifier.fillMaxWidth().heightIn(min=44.dp)) {
            Text(if (value.source==VideoSource.NONE) "恢复UE占位画面" else "启用测试画面")
        }
        if (active!=VideoSource.NONE) OutlinedButton(onClick=onStop,modifier=Modifier.fillMaxWidth()) { Text("关闭测试画面") }
        Text("不录音、不录像；离开操控页或进入后台会关闭测试画面，回到APP后需手动重新开启。",color=TextSecondary,fontSize=11.sp,lineHeight=17.sp)
    }
}
