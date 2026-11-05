package com.vrxfusion.remote.unified

import com.vrxfusion.remote.rotorpy.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import okhttp3.Call

/** v1目录/状态仓库：GET和订阅永远只读，不通过观察创建控制会话。 */
class UnifiedRepository {
    val info=MutableStateFlow<UnifiedCapabilities?>(null)
    val directory=MutableStateFlow<UnifiedDirectory?>(null)
    val snapshot=MutableStateFlow<UnifiedSnapshot?>(null)
    val connected=MutableStateFlow(false)
    val running=MutableStateFlow(false)
    val error=MutableStateFlow<String?>(null)
    val receiveHz=MutableStateFlow(0.0)
    var audit: (String,String)->Unit={_,_->}
    private val wire=BackendWire()
    private val rate=StateRate()
    private var job: Job?=null; private var watch: Job?=null; private var catalog: Job?=null
    private var call: Call?=null
    @Volatile private var generation=0L
    @Volatile private var lastAt=0L
    @Volatile private var identities=emptyMap<String,String>()
    @Volatile private var memberAt=emptyMap<String,Long>()
    private var lastIdentity=""
    @Volatile private var catalogError: String?=null
    fun fresh(m: UnifiedDeviceState,now: Long=monoMs()): Boolean {
        val age=m.state_age_ms ?: return false
        return connected.value && m.online && age.isFinite() && age in 0.0..500.0 &&
            memberAt[m.device_id]?.let { now-it<=500 }==true && m.validTelemetry()
    }
    suspend fun start(scope: CoroutineScope,url: String,token: String): Boolean {
        stop(); val gen=++generation
        connectionIssue(url,token)?.let { error.value=it; return false }
        val base=url.trim().trimEnd('/'); val secret=token.trim()
        val allowed={ gen==generation }
        running.value=true; error.value=null; catalogError=null; snapshot.value=null; directory.value=null; info.value=null
        return withContext(Dispatchers.IO) {
            try {
                val capabilities=wire.json.decodeFromJsonElement<UnifiedCapabilities>(wire.get(base,secret,"/api/v1/capabilities",allowed)["data"]!!)
                capabilities.validate(); check(allowed())
                info.value=capabilities
                fun loadDirectory() {
                    val d=wire.json.decodeFromJsonElement<UnifiedDirectory>(wire.get(base,secret,"/api/v1/devices",allowed)["data"]!!)
                    require(d.contract_version=="1.0" && d.cluster_id==capabilities.cluster_id && d.devices.size<=512 &&
                        d.devices.map { it.device_id }.toSet().size==d.devices.size) { "设备目录格式无效" }
                    if (capabilities.independent()) require(d.devices.all {
                        it.is_self==(it.device_id==capabilities.self_device_id) &&
                            it.write_permitted==(it.device_id in capabilities.writable_device_ids)
                    }) { "设备目录与本端权限不一致" }
                    check(allowed()); catalogError=null; directory.value=d
                    audit("DIRECTORY_REFRESHED","revision=${d.target_revision}|members=${d.devices.size}")
                    if (connected.value) error.value=null
                }
                loadDirectory()
                accept(wire.json.decodeFromJsonElement<UnifiedSnapshot>(wire.get(base,secret,"/api/v1/state",allowed)["data"]!!),gen)
                job=scope.launch(Dispatchers.IO) {
                    while (isActive && allowed()) {
                        try {
                            call=wire.stream(base,secret,"/api/v1/events")
                            if (!allowed()) { call?.cancel(); break }
                            wire.readStream(call!!) { accept(wire.json.decodeFromString<UnifiedSnapshot>(it),gen) }
                            if (allowed()) error("统一状态流已关闭")
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { if (allowed()) { connected.value=false; error.value=message(e) }; delay(1500) }
                    }
                }
                watch=scope.launch {
                    while (isActive && allowed()) {
                        if (connected.value && monoMs()-lastAt>500) { connected.value=false; error.value="统一状态超过500ms未更新"; call?.cancel() }
                        receiveHz.value=if (connected.value) rate.hz() else 0.0
                        // 即使单个成员停步而其他成员持续更新，也触发新快照重新检查成员有效性。
                        delay(50)
                    }
                }
                catalog=scope.launch(Dispatchers.IO) {
                    while (isActive && allowed()) {
                        delay(3000)
                        try { loadDirectory() }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { if (allowed()) {
                            val code=if (e is HttpRejection) "HTTP_${e.code}" else e.javaClass.simpleName
                            audit("DIRECTORY_REFRESH_FAILED",code)
                            catalogError="设备能力刷新失败（$code），控制已停用；等待校验恢复后手动申请"
                            directory.value=null; error.value=catalogError
                        } }
                    }
                }
                true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (allowed()) { running.value=false; connected.value=false; error.value=message(e) }; false }
        }
    }
    private fun accept(s: UnifiedSnapshot,gen: Long) {
        if (gen!=generation) return
        require(s.contract_version=="1.0" && s.cluster_id==info.value?.cluster_id && s.members.size<=512 &&
            s.members.map { it.device_id }.toSet().size==s.members.size) { "统一状态契约无效" }
        info.value?.takeIf { it.independent() }?.let { identity ->
            require(s.deployment_mode=="INDEPENDENT" && s.self_device_id==identity.self_device_id &&
                s.viewer_role==identity.authentication.role && s.members.all {
                    it.is_self==(it.device_id==identity.self_device_id) &&
                        it.control.write_permitted==(it.device_id in identity.writable_device_ids)
                }) { "全机状态与绑定身份不一致" }
        }
        val now=monoMs()
        val current=s.members.associate { it.device_id to "${it.boot_id}:${it.sequence}" }
        memberAt=current.mapValues { (id,key)->if (identities[id]==key) memberAt[id] ?: now else now }
        identities=current
        val identity="${s.boot_id}:${s.target_revision}:${s.selection?.selection_id}:"+s.members.joinToString { "${it.device_id}:${it.boot_id}:${it.sequence}:${it.online}" }
        snapshot.value=s
        if (identity==lastIdentity) return // 重复数据不得刷新连接看门狗。
        lastIdentity=identity; lastAt=now; connected.value=true; error.value=catalogError
        rate.record(identity,now); receiveHz.value=rate.hz(now)
    }
    fun stop() {
        ++generation; call?.cancel(); wire.cancelPending(); job?.cancel(); watch?.cancel(); catalog?.cancel()
        running.value=false; connected.value=false; receiveHz.value=0.0
        lastIdentity=""; identities=emptyMap(); memberAt=emptyMap(); rate.reset()
    }
    /** 服务端审计日志：一次性只读请求，观察端同样可读；导出为文件下载，与连接会话无关。 */
    suspend fun queryLogs(url: String,token: String,query: String): JsonObject =
        withContext(Dispatchers.IO) { wire.get(url.trim().trimEnd('/'),token,"/api/v1/logs"+query) }
    suspend fun downloadLogs(url: String,token: String,format: String): ByteArray =
        withContext(Dispatchers.IO) { wire.download(url.trim().trimEnd('/'),token,"/api/v1/logs/export?format="+format) }
    private fun message(e: Exception)=if (e is HttpRejection && e.code==404)
        "接口不匹配：请启动v0.9 --mode independent（旧版可用 --mode unified）" else explainConnection(e)
}
