package com.vrxfusion.remote.ui

import androidx.compose.runtime.Composable
import com.vrxfusion.remote.MainViewModel

@Composable
fun RotorPyScreen(vm: MainViewModel, onBack: () -> Unit, onManageConnection: () -> Unit) {
    FlightControlScreen(vm, onBack, onManageConnection)
}
