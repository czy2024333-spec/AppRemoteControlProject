package com.vrxfusion.remote.ui

import android.app.Activity
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** 操纵页顶部按应用间距布局，状态栏显隐时只覆盖画面。导航栏和屏幕缺口保留安全边距。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun flightSafeInsets(): WindowInsets =
    WindowInsets.navigationBarsIgnoringVisibility.union(WindowInsets.displayCutout)

/** 页面、窗口焦点和前后台恢复时保持全屏；不申请系统悬浮窗权限。 */
@Composable
fun FlightImmersiveMode(activity: Activity?) {
    val owner=LocalLifecycleOwner.current
    val imeVisible=WindowInsets.ime.getBottom(LocalDensity.current)>0
    // 输入法收起后补回沉浸状态；不会因设置框出现而主动弹出输入法。
    LaunchedEffect(activity,imeVisible) {
        if (!imeVisible) activity?.window?.let {
            WindowCompat.getInsetsController(it,it.decorView).hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(activity,owner) {
        val view=activity?.window?.decorView
        val controller=activity?.window?.let { WindowCompat.getInsetsController(it,it.decorView) }
        val insets=view?.let(ViewCompat::getRootWindowInsets)
        val hadStatus=insets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
        val hadNavigation=insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true
        val behavior=controller?.systemBarsBehavior
        val darkStatusIcons=controller?.isAppearanceLightStatusBars
        val darkNavigationIcons=controller?.isAppearanceLightNavigationBars
        // 操纵页是深色界面，临时唤出系统栏时使用浅色系统图标。
        controller?.isAppearanceLightStatusBars=false
        controller?.isAppearanceLightNavigationBars=false
        var disposed=false
        val hide=Runnable {
            if (!disposed) {
                // 只隐藏系统状态/导航栏；输入法仍由用户点击输入框正常唤起。
                controller?.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller?.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        val focus=ViewTreeObserver.OnWindowFocusChangeListener { hasFocus -> if (hasFocus) view?.post(hide) }
        val resume=LifecycleEventObserver { _,event -> if (event==Lifecycle.Event.ON_RESUME) view?.post(hide) }
        view?.viewTreeObserver?.addOnWindowFocusChangeListener(focus)
        owner.lifecycle.addObserver(resume)
        view?.post(hide)
        onDispose {
            disposed=true
            view?.removeCallbacks(hide)
            if (view?.viewTreeObserver?.isAlive==true) view.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
            owner.lifecycle.removeObserver(resume)
            if (hadStatus) controller?.show(WindowInsetsCompat.Type.statusBars())
            if (hadNavigation) controller?.show(WindowInsetsCompat.Type.navigationBars())
            darkStatusIcons?.let { controller?.isAppearanceLightStatusBars=it }
            darkNavigationIcons?.let { controller?.isAppearanceLightNavigationBars=it }
            behavior?.let { controller?.systemBarsBehavior=it }
        }
    }
}
