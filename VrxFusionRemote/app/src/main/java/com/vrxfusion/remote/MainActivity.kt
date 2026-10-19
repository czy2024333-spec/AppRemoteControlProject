package com.vrxfusion.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vrxfusion.remote.ui.*
import com.vrxfusion.remote.ui.theme.VrxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { VrxTheme { App() } }
    }
}

@Composable
fun App() {
    val vm: MainViewModel = viewModel()
    var page by rememberSaveable { mutableStateOf("home") }
    val selected by vm.selected.collectAsState()
    val owner = LocalLifecycleOwner.current
    val view = LocalView.current
    DisposableEffect(view, page) {
        val wasAwake = view.keepScreenOn
        if (page == "single") view.keepScreenOn = true
        onDispose { view.keepScreenOn = wasAwake }
    }
    fun navigate(next: String) {
        if (page == "legacy" && next != "legacy") vm.backToList()
        if (page == "single" && next != "single") { vm.pauseRotor(); vm.stopVideo() }
        if (page == "cluster" && next != "cluster") vm.clusterClient.stop()
        page = next
    }
    // 系统返回和进入后台同样停流；恢复前台后等待用户重新连接/申请。
    BackHandler(page != "home") { navigate("home") }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) vm.diagnostics.foreground(true)
            if (event == Lifecycle.Event.ON_STOP) { vm.diagnostics.foreground(false); vm.pauseAll() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    when (page) {
        "single" -> RotorPyScreen(vm,onBack={ navigate("home") })
        "cluster" -> ClusterMonitorScreen(vm,onBack={ navigate("home") })
        "legacy" -> if (selected == null) DeviceListScreen(vm,onSelect={ vm.selectDevice(it) },
            onOpenRotorPy={ navigate("single") },onOpenLandscape={ navigate("single") })
            else ControlScreen(vm,onBack={ vm.backToList() })
        else -> FlightHomeScreen(onSingle={ navigate("single") },onCluster={ navigate("cluster") },onLegacy={ navigate("legacy") })
    }
}
