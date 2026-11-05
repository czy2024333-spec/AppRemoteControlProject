package com.vrxfusion.remote.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrxfusion.remote.MainViewModel
import com.vrxfusion.remote.ui.theme.*

/** 使用系统文件选择器，不申请全盘存储权限；单次只读取最多8KB。 */
@Composable
fun HudConfigPanel(vm: MainViewModel) {
    val context=LocalContext.current
    val revision by vm.hudRevision.collectAsState()
    val canRollback by vm.hudCheckpoint.collectAsState()
    val message by vm.hudConfigMessage.collectAsState()
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri!=null) runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes=java.io.ByteArrayOutputStream()
                val buffer=ByteArray(1024)
                while (bytes.size()<=8192) {
                    val n=input.read(buffer,0,minOf(buffer.size,8193-bytes.size()))
                    if (n<0) break
                    bytes.write(buffer,0,n)
                }
                require(bytes.size()<=8192) { "文件过大" }
                bytes.toString("UTF-8")
            }
                ?: error("无法读取")
        }.onSuccess(vm::importHud).onFailure { vm.hudConfigMessage.value="无法读取配置文件" }
    }
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri!=null) runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(HudConfigCodec.encode(vm.hudPreferences.value).toByteArray(Charsets.UTF_8)) }
                ?: error("无法写入")
        }.onSuccess { vm.hudConfigMessage.value="显示配置已导出" }.onFailure { vm.hudConfigMessage.value="导出失败，请检查目标位置" }
    }
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        HorizontalDivider()
        Text("配置管理 · 格式1 / 修订${maxOf(revision,vm.currentHudRevision())}",color=TextPrimary,fontSize=13.sp)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick=vm::checkpointHud,modifier=Modifier.weight(1f)) { Text("保存回退点") }
            OutlinedButton(onClick=vm::rollbackHud,enabled=canRollback,modifier=Modifier.weight(1f)) { Text("回退配置") }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={ importer.launch(arrayOf("application/json","text/plain","application/octet-stream")) },modifier=Modifier.weight(1f)) { Text("导入配置") }
            OutlinedButton(onClick={ exporter.launch("flight-display.json") },modifier=Modifier.weight(1f)) { Text("导出配置") }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            // 恢复默认前自动保存回退点，误触可立即回退。
            OutlinedButton(onClick=vm::resetHudDefaults,modifier=Modifier.weight(1f)) { Text("恢复默认") }
        }
        Text(message.ifEmpty { "仅包含显示字段；停止、保护和告警始终保留。" },color=TextSecondary,fontSize=11.sp)
    }
}
