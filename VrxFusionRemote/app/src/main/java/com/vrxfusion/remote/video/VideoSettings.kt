package com.vrxfusion.remote.video

import android.content.Context
import java.net.URI

enum class VideoSource(val label: String) { NONE("UE占位"), PHONE("手机摄像头"), PC("电脑摄像头") }

data class VideoSettings(val source: VideoSource=VideoSource.PHONE,
    val pcUrl: String="http://192.168.1.100:8768/video.mjpeg",val frontCamera: Boolean=false)

/** 只保存候选画面源和地址；不保存访问码，也不保存“正在开启”的状态。 */
class VideoSettingsStore(context: Context) {
    private val store=context.applicationContext.getSharedPreferences("test_video",Context.MODE_PRIVATE)
    fun load()=VideoSettings(
        runCatching { VideoSource.valueOf(store.getString("source","PHONE")!!) }.getOrDefault(VideoSource.PHONE),
        store.getString("url",VideoSettings().pcUrl) ?: VideoSettings().pcUrl,store.getBoolean("front",false))
    fun save(value: VideoSettings) {
        store.edit().putString("source",value.source.name).putString("url",value.pcUrl.trim()).putBoolean("front",value.frontCamera).apply()
    }
}

fun videoConnectionIssue(url: String,code: String): String? {
    val uri=runCatching { URI(url.trim()) }.getOrNull() ?: return "请输入完整电脑画面地址"
    if (uri.scheme !in listOf("http","https") || uri.host.isNullOrBlank() || !uri.rawUserInfo.isNullOrEmpty() ||
        !uri.rawQuery.isNullOrEmpty() || !uri.rawFragment.isNullOrEmpty() || uri.path.isNullOrBlank() ||
        uri.port !in -1..65535 || uri.port==0) return "地址格式应为 http://电脑IPv4:8768/video.mjpeg，不要带访问码或查询参数"
    if (code.length<16 || code.any { it.code !in 33..126 }) return "请填写电脑摄像头窗口给出的访问码（至少16位），不是后端Token"
    return null
}
