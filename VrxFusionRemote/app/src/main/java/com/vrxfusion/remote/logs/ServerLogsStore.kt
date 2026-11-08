package com.vrxfusion.remote.logs

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.OutputStream

data class ServerLogsView(val busy: Boolean=false,val page: LogPage?=null,val filter: LogFilter?=null,
    val message: String="日志是落盘历史记录，可能存在索引延迟。",val download: LogDownload?=null,val pickerStarted: Boolean=false)

/** 一次只执行一个日志操作；查询错误独立显示，不清空飞控仓库或续期运动输入。 */
class ServerLogsStore(private val scope: CoroutineScope,private val directory: File,
    private val client: ServerLogsClient=ServerLogsClient()) {
    val view=MutableStateFlow(ServerLogsView())
    private var job: Job?=null
    private var connection: LogConnection?=null
    fun query(c: LogConnection,filter: LogFilter,next: Boolean=false) {
        if (view.value.busy || view.value.download!=null) return
        val old=view.value
        if (next && (connection!=c || old.filter!=filter || old.page?.nextOffset==null)) return
        val offset=if (next) old.page!!.nextOffset!! else 0
        if (offset>100000) { view.value=old.copy(message="分页已达上限，请缩小设备或时间范围。"); return }
        val snapshot=if (next) old.page?.snapshot else null
        connection=c
        // 新查询立即撤下旧页，避免把上个筛选结果当成本次成功结果。
        view.value=old.copy(busy=true,page=null,filter=filter,message="正在查询日志…")
        job=scope.launch {
            try {
                val page=client.query(c,filter,offset,snapshot)
                val warning=buildList {
                    if (!page.caughtUp) add("索引尚未追平历史文件")
                    if (!page.complete) add("当前批次完整性尚未确认")
                    if (page.pendingTail>0) add("${page.pendingTail}处尾行尚未写完")
                    if (page.snapshot==null) add("后端未提供固定快照，已停用连续分页")
                }.joinToString("；")
                view.value=ServerLogsView(page=page,filter=filter,message="共${page.total}条 · 当前${page.offset+1}起，${page.events.size}条"+
                    warning.takeIf { it.isNotEmpty() }?.let { "；$it" }.orEmpty())
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { view.value=ServerLogsView(filter=filter,message=logIssueText(e)) }
        }
    }
    fun export(c: LogConnection,format: String) {
        val state=view.value; val page=state.page ?: return; val filter=state.filter ?: return
        if (state.busy || state.download!=null || connection!=c || page.total==0L) return
        view.value=state.copy(busy=true,message="正在下载并校验，完成后选择保存位置…")
        job=scope.launch {
            var temporary: File?=null
            try {
                val downloaded=withContext(Dispatchers.IO) {
                    directory.mkdirs()
                    val file=File.createTempFile("download-",".part",directory); temporary=file
                    client.download(c,filter,page,format,file)
                }
                view.value=state.copy(download=downloaded,message="已校验${downloaded.rows}条日志，请选择保存位置。"+
                    if (downloaded.pendingTail>0) "另有${downloaded.pendingTail}处未写完尾行未纳入。" else "")
                temporary=null // 完整文件交由保存/取消流程清理。
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { view.value=state.copy(message=logIssueText(e)) }
            finally { temporary?.delete() }
        }
    }
    fun pickerStarted() { view.value=view.value.copy(pickerStarted=true) }
    fun save(open: ()->OutputStream?,onFailed: ()->Unit) {
        val state=view.value; val download=state.download ?: return
        view.value=state.copy(busy=true)
        job=scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    (open() ?: error("无法写入目标文件")).use { output->download.file.inputStream().use { it.copyTo(output,64*1024) } }
                }
                view.value=state.copy(download=null,pickerStarted=false,message="已保存${download.rows}条 ${download.format.uppercase()} 日志（${download.bytes}字节）。")
            } catch(e: CancellationException) { withContext(NonCancellable+Dispatchers.IO) { onFailed() }; throw e }
            catch(e: Exception) {
                withContext(Dispatchers.IO) { onFailed() }
                view.value=state.copy(download=null,pickerStarted=false,message="保存失败，请检查目标位置后重新导出。")
            } finally { download.file.delete(); if (isActive) view.value=view.value.copy(busy=false) }
        }
    }
    fun cancelSave() {
        view.value.download?.file?.delete()
        view.value=view.value.copy(download=null,pickerStarted=false,message="已取消保存。")
    }
    fun clear() {
        job?.cancel(); view.value.download?.file?.delete(); connection=null
        view.value=ServerLogsView()
    }
    fun close() { clear(); client.close() }
}
