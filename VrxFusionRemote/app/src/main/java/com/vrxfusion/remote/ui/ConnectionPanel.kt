package com.vrxfusion.remote.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrxfusion.remote.MainViewModel
import com.vrxfusion.remote.rotorpy.connectionIssue
import com.vrxfusion.remote.ui.theme.*

/** 连接配置复用：连接期间锁定凭据；需要修改时先断开，防止会话被发到另一台服务。 */
@Composable
fun ConnectionPanel(vm: MainViewModel,running: Boolean,onConnect: ()->Unit,onDisconnect: ()->Unit) {
    val url by vm.rotorBaseUrl.collectAsState()
    val token by vm.rotorToken.collectAsState()
    ConnectionFields(url,token,running,vm::setRotorBaseUrl,vm::setRotorToken,onConnect,onDisconnect)
}

/** 只在用户点击输入框时唤起键盘；切换连接或完成输入时清焦点并收起键盘。 */
@Composable
fun ConnectionFields(url: String,token: String,running: Boolean,onUrlChange: (String)->Unit,onTokenChange: (String)->Unit,
    onConnect: ()->Unit,onDisconnect: ()->Unit) {
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    fun finish() { focus.clearFocus(); keyboard?.hide() }
    var showToken by rememberSaveable { mutableStateOf(false) }
    val issue=connectionIssue(url,token)
    OutlinedTextField(value=url,onValueChange=onUrlChange,label={ Text("后端地址") },
        placeholder={ Text("http://电脑IPv4:8765") },singleLine=true,enabled=!running,
        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri,imeAction=ImeAction.Next),
        supportingText={ Text("填写电脑IPv4，例如 http://192.168.1.100:8765",fontSize=11.sp) },
        modifier=Modifier.fillMaxWidth().testTag("backend-address"))
    OutlinedTextField(value=token,onValueChange=onTokenChange,label={ Text("APP来源Token") },singleLine=true,
        enabled=!running,visualTransformation=if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon={ TextButton(onClick={ showToken=!showToken }) { Text(if (showToken) "隐藏" else "显示") } },
        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,imeAction=ImeAction.Done),
        keyboardActions=KeyboardActions(onDone={ finish() }),
        supportingText={ Text("仅填Token本身，不加Bearer；不保存到磁盘",fontSize=11.sp) },
        modifier=Modifier.fillMaxWidth().testTag("backend-token"))
    if (issue != null && !running) Text(issue,color=WarnAmber,fontSize=12.sp)
    Button(onClick={ finish(); if (running) onDisconnect() else onConnect() },enabled=running || issue == null,
        modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
        Text(if (running) "断开连接 / 修改地址" else "连接并查看状态")
    }
}
