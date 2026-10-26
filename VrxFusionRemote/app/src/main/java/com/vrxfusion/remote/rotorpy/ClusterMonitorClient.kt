package com.vrxfusion.remote.rotorpy

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.Call

@Serializable
data class ClusterState(val cluster_id: String, val boot_id: String = "", val implementation_version: String = "",
    val members: List<RotorState> = emptyList(), val groups: Map<String,List<String>> = emptyMap(),
    val online_count: Int = 0, val selected_count: Int = 0, val active_count: Int = 0,
    val selection: JsonObject? = null)

/** 集群入口只读：不创建来源会话，不改变现有控制权，也不向任何成员下发运动。 */
class ClusterMonitorClient {
    val state = MutableStateFlow<ClusterState?>(null)
    val error = MutableStateFlow<String?>(null)
    val connected = MutableStateFlow(false)
    val running = MutableStateFlow(false)
    val receiveHz = MutableStateFlow(0.0)
    private val wire = BackendWire()
    private var job: Job? = null
    private var watch: Job? = null
    private var call: Call? = null
    private var generation = 0L
    @Volatile private var lastAt = 0L
    fun start(scope: CoroutineScope,urlInput: String,tokenInput: String) {
        if (running.value) return
        connectionIssue(urlInput,tokenInput)?.let { error.value=it; return }
        val url=urlInput.trim().trimEnd('/'); val token=tokenInput.trim(); val gen=++generation
        running.value=true; state.value=null; connected.value=false; receiveHz.value=0.0; error.value=null
        job=scope.launch(Dispatchers.IO) {
            var window=monoMs(); var frames=0; var identity=""
            fun accept(s: ClusterState) {
                if (gen != generation) return
                val id=s.boot_id+s.members.joinToString { "${it.device_id}:${it.sequence}:${it.online}" }
                if (id == identity) return
                identity=id; state.value=s; connected.value=true; lastAt=monoMs(); error.value=null
                frames++
                if (lastAt-window >= 1000) { receiveHz.value=frames*1000.0/(lastAt-window); frames=0; window=lastAt }
            }
            while (isActive && gen == generation) {
                try {
                    accept(wire.json.decodeFromJsonElement<ClusterState>(wire.get(url,token,"/api/cluster/state")["data"]!!))
                    ensureActive()
                    call=wire.stream(url,token,"/api/cluster/events")
                    wire.readStream(call!!) { accept(wire.json.decodeFromString<ClusterState>(it)) }
                    if (isActive) throw IllegalStateException("状态流已关闭")
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (gen == generation) {
                        connected.value=false; receiveHz.value=0.0
                        error.value=if (e is HttpRejection && e.code == 404) "当前端口是单机服务，请启动 --mode cluster" else explainConnection(e)
                    }
                    delay(1500)
                }
            }
        }
        watch=scope.launch {
            while (isActive) {
                if (connected.value && monoMs()-lastAt > 500) {
                    connected.value=false; receiveHz.value=0.0; error.value="集群数据超过500ms未更新，画面为历史状态"
                    call?.cancel()
                }
                delay(50)
            }
        }
    }
    fun stop() { ++generation; job?.cancel(); watch?.cancel(); call?.cancel(); running.value=false; connected.value=false; receiveHz.value=0.0 }
}
