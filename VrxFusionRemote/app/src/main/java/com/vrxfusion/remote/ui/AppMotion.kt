package com.vrxfusion.remote.ui

import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

val LocalAppMotionEnabled=staticCompositionLocalOf { true }
val LocalMotionInputBlocked=staticCompositionLocalOf { false }

/** 动画只作用于界面层；时长由本APP设计，不冒充苹果系统的内部参数。 */
object AppMotion {
    const val PAGE_MS=320
    const val SECTION_MS=180
    const val SHEET_CLOSE_MS=250
    val ease=CubicBezierEasing(.22f,.80f,.24f,1f)
    val sheetCloseEase=CubicBezierEasing(.32f,0f,.45f,1f)
}

/** 跟随Android系统关闭动画设置，运行中切换也会立即结束当前过渡。 */
@Composable
fun rememberSystemMotionEnabled(): Boolean {
    val resolver=LocalContext.current.contentResolver
    fun read()=runCatching {
        Settings.Global.getFloat(resolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0 &&
            Settings.Global.getFloat(resolver,Settings.Global.TRANSITION_ANIMATION_SCALE,1f)>0
    }.getOrDefault(true)
    var enabled by remember(resolver) { mutableStateOf(read()) }
    DisposableEffect(resolver) {
        val observer=object: ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { enabled=read() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),false,observer)
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE),false,observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled
}

/** 拦截过渡期间的新触摸；原操纵手势还由操纵页的禁用/实际松手逻辑负责清除。 */
@Composable
fun MotionInputShield(modifier: Modifier=Modifier) {
    Box(modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }.testTag("motion-input-shield"))
}

/** 只在导航时取一张内存预览，长边至多1280像素；不落盘、不采集新摄像头画面。 */
fun captureMotionPreview(view: View): ImageBitmap? {
    if (view.width<=0 || view.height<=0) return null
    var bitmap: Bitmap?=null
    return try {
        val scale=min(1f,1280f/max(view.width,view.height))
        bitmap=Bitmap.createBitmap(max(1,(view.width*scale).toInt()),max(1,(view.height*scale).toInt()),Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.scale(scale,scale);view.draw(canvas)
        bitmap.asImageBitmap()
    } catch(e: RuntimeException) {bitmap?.recycle();null}
    catch(e: OutOfMemoryError) {bitmap?.recycle();null} // 低内存时放弃可选预览，不能影响页面操作。
}

/** 同时只装载目标页面；旧画面仅作短暂位图预览，避免双操纵页和先淡空再进入的黑场。 */
@Composable
fun <T> MotionContentHost(target: T,forward: Boolean=true,section: Boolean=false,
    modifier: Modifier=Modifier,preview: ImageBitmap?=null,onFinished: ()->Unit={},content: @Composable (T)->Unit) {
    val enabled=LocalAppMotionEnabled.current
    val parentBlocked=LocalMotionInputBlocked.current
    var observed by remember { mutableStateOf(target) }
    var moving by remember { mutableStateOf(false) }
    val progress=remember { Animatable(1f) }
    LaunchedEffect(target,forward,enabled) {
        if (observed==target && progress.value==1f && !moving) return@LaunchedEffect
        if (!enabled) {
            observed=target;progress.snapTo(1f);moving=false;onFinished()
        } else {
            moving=true
            try {
                progress.snapTo(0f);observed=target
                progress.animateTo(1f,tween(if (section) AppMotion.SECTION_MS else AppMotion.PAGE_MS,easing=AppMotion.ease))
                onFinished()
            } finally { moving=false }
        }
    }
    val blocked=enabled && (observed!=target || moving)
    val position=if (!enabled) 1f else if (observed!=target) 0f else progress.value.coerceIn(0f,1f)
    val hidden=if (blocked) Modifier.clearAndSetSemantics { } else Modifier
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds().testTag(if (section) "motion-section-host" else "motion-page-host")) {
        val width=with(LocalDensity.current) { maxWidth.toPx() }
        val image=preview.takeIf { enabled && position<1f }
        if (image!=null && forward) Image(image,null,contentScale=ContentScale.FillBounds,
            modifier=Modifier.fillMaxSize().graphicsLayer {translationX=-width*.22f*position})
        CompositionLocalProvider(LocalMotionInputBlocked provides (parentBlocked || blocked)) {
            Box(Modifier.fillMaxSize().then(hidden).graphicsLayer {
                alpha=if (section) .65f+.35f*position else 1f
                translationX=when {
                    section -> (if (forward) 1f else -1f)*width*.045f*(1f-position)
                    image!=null && forward -> width*(1f-position)
                    image!=null -> -width*.22f*(1f-position)
                    else -> (if (forward) 1f else -1f)*width*.10f*(1f-position)
                }
            }) {
                key(target) { content(target) }
            }
        }
        if (image!=null && !forward) Image(image,null,contentScale=ContentScale.FillBounds,
            modifier=Modifier.fillMaxSize().graphicsLayer {translationX=width*position})
        if (blocked) MotionInputShield(Modifier.matchParentSize())
    }
}

data class MotionSheetState(val visible: Boolean,val progress: Float,val ready: Boolean)

/** 收起动画结束前仍保留模态层，不能先启用底下的摇杆。 */
@Composable
fun rememberMotionSheet(open: Boolean): MotionSheetState {
    val enabled=LocalAppMotionEnabled.current
    val progress=remember { Animatable(0f) }
    var retained by remember { mutableStateOf(false) }
    var settling by remember { mutableStateOf(false) }
    LaunchedEffect(open,enabled) {
        if (!enabled) {progress.snapTo(if (open) 1f else 0f);retained=open;settling=false}
        else if (open) {
            retained=true;settling=true
            try { progress.animateTo(1f,spring(dampingRatio=.88f,stiffness=520f)) }
            finally {settling=false}
        } else {
            settling=true
            try {
                progress.animateTo(0f,tween(AppMotion.SHEET_CLOSE_MS,easing=AppMotion.sheetCloseEase))
                retained=false
            } finally {settling=false}
        }
    }
    if (!enabled) return MotionSheetState(open,if (open) 1f else 0f,open)
    return MotionSheetState(open || retained,progress.value.coerceIn(0f,1f),open && !settling && progress.value>=.999f)
}

/** 按下即缩放，回调仍由原clickable即时执行；不延迟、排队或重复发送指令。 */
@Composable
fun Modifier.motionPress(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val enabled=LocalAppMotionEnabled.current
    val scale by animateFloatAsState(if (pressed && enabled) .975f else 1f,
        animationSpec=if (enabled) spring(dampingRatio=.85f,stiffness=650f) else snap(),label="press-scale")
    return graphicsLayer {scaleX=scale;scaleY=scale}
}
