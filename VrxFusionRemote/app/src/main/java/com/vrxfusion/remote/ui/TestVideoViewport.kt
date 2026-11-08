package com.vrxfusion.remote.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.widget.ImageView
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vrxfusion.remote.video.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 两种摄像头只替代视频底层，不产生飞行状态，也不访问控制接口。 */
@Composable
fun TestVideoViewport(source: VideoSource,settings: VideoSettings,code: String,onStatus: (String,Boolean)->Unit) {
    val currentStatus by rememberUpdatedState(onStatus)
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF141B24),Color(0xFF0D131B))))) {
        when(source) {
            VideoSource.NONE -> Unit
            VideoSource.PHONE -> PhoneVideoPreview(settings.frontCamera,
                { currentStatus("手机摄像头预览中 · 测试画面",false) },{ currentStatus(it,true) })
            VideoSource.PC -> PcVideoPreview(settings.pcUrl,code,
                { currentStatus("电脑摄像头画面播放中 · 测试画面",false) },{ currentStatus(it,true) })
        }
    }
}

@Composable
private fun PhoneVideoPreview(front: Boolean,onStreaming: ()->Unit,onError: (String)->Unit) {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    val ready by rememberUpdatedState(onStreaming); val failed by rememberUpdatedState(onError)
    val view=remember(context) { PreviewView(context).apply {
        // COMPATIBLE使用TextureView，可与Compose透明HUD正确叠加，避免Surface置顶遮住摇杆。
        implementationMode=PreviewView.ImplementationMode.COMPATIBLE
        scaleType=PreviewView.ScaleType.FILL_CENTER
        isFocusable=false; importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
    } }
    AndroidView(factory={ view },modifier=Modifier.fillMaxSize())
    DisposableEffect(view,owner,front) {
        var disposed=false
        var provider: ProcessCameraProvider?=null
        var camera: androidx.camera.core.Camera?=null
        val cameraObserver=Observer<CameraState> { if (!disposed && it.error!=null)
            failed("手机摄像头暂不可用，请检查镜头、系统权限及其他应用占用") }
        val preview=Preview.Builder().build()
        val main=ContextCompat.getMainExecutor(context)
        val manager=context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val rotation=object: DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) {}
            override fun onDisplayRemoved(id: Int) {}
            override fun onDisplayChanged(id: Int) { preview.targetRotation=view.display?.rotation ?: Surface.ROTATION_0 }
        }
        var hasStreamed=false
        val streamObserver=Observer<PreviewView.StreamState> {
            if (!disposed && it==PreviewView.StreamState.STREAMING) { hasStreamed=true; ready() }
            else if (!disposed && hasStreamed) failed("手机画面已中断，请重新启用摄像头")
        }
        view.previewStreamState.observe(owner,streamObserver)
        manager.registerDisplayListener(rotation,Handler(Looper.getMainLooper()))
        if (ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) {
            failed("手机相机未授权，请在画面测试中重新启用并允许相机权限")
        } else {
            val future=ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (!disposed) try {
                    val cameraProvider=future.get(); provider=cameraProvider
                    val selector=if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    require(cameraProvider.hasCamera(selector)) { "没有所选摄像头，请切换前置或后置镜头" }
                    preview.targetRotation=view.display?.rotation ?: Surface.ROTATION_0
                    preview.setSurfaceProvider(view.surfaceProvider)
                    camera=cameraProvider.bindToLifecycle(owner,selector,preview)
                    camera?.cameraInfo?.cameraState?.observe(owner,cameraObserver)
                } catch (_: Exception) { failed("手机摄像头无法开启，请检查所选镜头、系统权限及其他应用占用") }
            },main)
        }
        onDispose {
            disposed=true
            manager.unregisterDisplayListener(rotation)
            view.previewStreamState.removeObserver(streamObserver)
            camera?.cameraInfo?.cameraState?.removeObserver(cameraObserver)
            // 仅解绑本组件的预览，退出页面、切换画面源都释放本次相机占用。
            provider?.unbind(preview)
        }
    }
}

@Composable
private fun PcVideoPreview(url: String,code: String,onStreaming: ()->Unit,onError: (String)->Unit) {
    val context=LocalContext.current; val owner=LocalLifecycleOwner.current
    val ready by rememberUpdatedState(onStreaming); val failed by rememberUpdatedState(onError)
    val view=remember(context) { ImageView(context).apply { scaleType=ImageView.ScaleType.CENTER_CROP; isFocusable=false } }
    AndroidView(factory={ view },modifier=Modifier.fillMaxSize())
    DisposableEffect(view,url,code,owner) {
        val disposed=AtomicBoolean(false); val posted=AtomicBoolean(false); val latest=AtomicReference<Bitmap?>(null)
        val stream=PcVideoStream(url,code)
        var lastDecode=0L
        var lastFrameAt=android.os.SystemClock.elapsedRealtime()
        val handler=Handler(Looper.getMainLooper())
        // 按完整可显示帧计时，不能被网络零碎字节或缓存画面伪装成持续播放。
        val watchdog=object: Runnable {
            override fun run() {
                if (disposed.get()) return
                if (android.os.SystemClock.elapsedRealtime()-lastFrameAt>3000) {
                    disposed.set(true); stream.close(); latest.set(null); view.setImageDrawable(null)
                    failed("电脑画面超过3秒未更新，请检查视频源后重新启用")
                } else handler.postDelayed(this,250)
            }
        }
        handler.postDelayed(watchdog,250)
        val observer=LifecycleEventObserver { _,event -> if (event==Lifecycle.Event.ON_STOP) {
            disposed.set(true); stream.close(); latest.set(null); view.setImageDrawable(null)
        } }
        owner.lifecycle.addObserver(observer)
        try { stream.start({ bytes ->
            val now=System.nanoTime()
            // 只解码最新画面，最高15fps，防止视频积压拖慢操纵界面。
            if (now-lastDecode>=66_000_000L && !disposed.get()) {
                lastDecode=now
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192 &&
                    bounds.outWidth.toLong()*bounds.outHeight<=32_000_000L) { "视频分辨率超出测试范围" }
                val options=BitmapFactory.Options().apply {
                    inPreferredConfig=Bitmap.Config.RGB_565
                    inSampleSize=1
                    while (bounds.outWidth/inSampleSize.coerceAtLeast(1)>1920 || bounds.outHeight/inSampleSize.coerceAtLeast(1)>1080)
                        inSampleSize=inSampleSize.coerceAtLeast(1)*2
                }
                val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,options) ?: error("视频帧解码失败")
                latest.set(bitmap)
                if (posted.compareAndSet(false,true)) view.post {
                    posted.set(false)
                    if (!disposed.get()) latest.getAndSet(null)?.let {
                        lastFrameAt=android.os.SystemClock.elapsedRealtime(); view.setImageBitmap(it); ready()
                    }
                }
            }
        },{ error -> view.post { if (!disposed.get()) {
            disposed.set(true); latest.set(null); handler.removeCallbacks(watchdog)
            view.setImageDrawable(null); failed(error)
        } } }) }
        catch (_: Exception) { failed("电脑视频地址或访问码无效") }
        onDispose {
            handler.removeCallbacks(watchdog)
            disposed.set(true); owner.lifecycle.removeObserver(observer); stream.close()
            latest.set(null); view.setImageDrawable(null)
        }
    }
}
