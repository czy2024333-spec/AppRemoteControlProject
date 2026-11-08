package com.vrxfusion.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vrxfusion.remote.ui.*
import com.vrxfusion.remote.ui.theme.VrxTheme
import com.vrxfusion.remote.ui.theme.BgDark

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { VrxTheme { App() } }
    }
}

@Composable
fun App(initialPage: String = "home", motionEnabled: Boolean? = null) {
    val vm: MainViewModel = viewModel()
    var page by rememberSaveable { mutableStateOf(initialPage) }
    var managementReturn by rememberSaveable { mutableStateOf("home") }
    var forward by rememberSaveable { mutableStateOf(true) }
    val animate=motionEnabled ?: rememberSystemMotionEnabled()
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    val owner = LocalLifecycleOwner.current
    val view = LocalView.current
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    DisposableEffect(view, page) {
        val wasAwake = view.keepScreenOn
        if (page == "single") view.keepScreenOn = true
        onDispose { view.keepScreenOn = wasAwake }
    }
    fun navigate(next: String,back: Boolean=next=="home") {
        if (next==page) return
        if (page == "single" && next != "single") { vm.pauseRotor(); vm.stopVideo(); vm.serverLogs.clear() }
        if (page == "cluster" && next != "cluster") vm.pauseRotor()
        focus.clearFocus();keyboard?.hide()
        // 必须先停流，再取内存预览；图片不包含控件或会话，动画结束即可释放引用。
        preview=if (animate) captureMotionPreview(view) else null
        // 管理页只建立观察连接，回首页时可复用；离开操纵页仍按原规则停流并释放控制。
        forward=!back
        page = next
    }
    fun openManagement() {
        managementReturn = page.takeIf { it == "single" || it == "cluster" } ?: "home"
        vm.serverLogs.clear()
        // 离开操纵页继续执行停流与释放；连接页只观察，返回后不能恢复旧控制。
        navigate("devices")
    }
    fun closeManagement() {
        val destination = managementReturn.takeIf { it == "single" || it == "cluster" } ?: "home"
        managementReturn = "home"
        navigate(destination,back=true)
    }
    // 系统返回与页面按钮走同一路径，避免从连接页返回时丢失原入口。
    BackHandler(page != "home") {
        if (page == "devices" || page == "legacy") closeManagement() else navigate("home")
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) vm.diagnostics.foreground(true)
            if (event == Lifecycle.Event.ON_STOP) { vm.diagnostics.foreground(false); vm.pauseAll() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    CompositionLocalProvider(LocalAppMotionEnabled provides animate) {
    MotionContentHost(page,forward,modifier=Modifier.background(BgDark),preview=preview,onFinished={preview=null}) { shown -> when (shown) {
        "single" -> RotorPyScreen(vm,onBack={ navigate("home") },onManageConnection={ openManagement() })
        "cluster" -> ClusterMonitorScreen(vm,onBack={ navigate("home") },onManageConnection={ openManagement() })
        // 兼容覆盖升级时恢复出的旧路由，但不再启动旧WebSocket目录或本地演示机。
        "devices", "legacy" -> {
            // 保留退出画面的原返回文案，避免返回状态先被重置导致动画中途闪字。
            val returnForThisPage=remember(shown) { managementReturn }
            BackendManagementScreen(vm,onBack={ closeManagement() },returnLabel=when(returnForThisPage) {
                "single" -> "返回操纵"; "cluster" -> "返回总览"; else -> "返回首页"
            })
        }
        else -> FlightHomeScreen(onSingle={ navigate("single") },onCluster={ navigate("cluster") },onManageConnections={ openManagement() })
    } }
    }
}
