package com.vrxfusion.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vrxfusion.remote.ui.ControlScreen
import com.vrxfusion.remote.ui.DeviceListScreen
import com.vrxfusion.remote.ui.theme.VrxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VrxTheme {
                App()
            }
        }
    }
}

@Composable
fun App() {
    val vm: MainViewModel = viewModel()
    val selected by vm.selected.collectAsState()

    if (selected == null) {
        DeviceListScreen(vm) { vm.selectDevice(it) }
    } else {
        ControlScreen(vm, onBack = { vm.backToList() })
    }
}
