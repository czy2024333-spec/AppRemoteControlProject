package com.vrxfusion.remote.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * WebSocket 传输层 —— 协议 v0.1（IF-002：连接检测、断线提示、重连）。
 * 连接后以 hello 注册角色/名称，2s 心跳保活，断线 3s 后自动重连。
 * 重连为全新会话：服务端不恢复失效前的运动指令（CTL-005）。
 */
class WsTransport(
    private val scope: CoroutineScope
) {
    enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED }

    private val _state = MutableStateFlow(ConnState.DISCONNECTED)
    val state: StateFlow<ConnState> = _state

    /** 收到的协议消息流（每条发射一次，适配器按 type 过滤消费） */
    private val _incoming = kotlinx.coroutines.flow.MutableSharedFlow<WsMessage>(
        extraBufferCapacity = 1000,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val incoming: kotlinx.coroutines.flow.SharedFlow<WsMessage> = _incoming

    private val _sessionId = MutableStateFlow("")
    val sessionId: StateFlow<String> = _sessionId

    /** 当前登录身份 */
    var role: Role = Role.OBSERVER
        private set
    var userName: String = ""
        private set
    var serverUrl: String = ""
        private set

    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .build()
    private var ws: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private val seqGen = AtomicLong(0)

    val mySrc: String get() = "app:$userName"

    fun connect(url: String, role: Role, name: String) {
        disconnect(silent = true)
        this.serverUrl = url
        this.role = role
        this.userName = name
        _state.value = ConnState.CONNECTING
        openSocket()
    }

    private fun openSocket() {
        val req = Request.Builder().url(serverUrl).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // 首帧必须 hello（协议第 9 节）
                sendRaw(
                    WsMessage(
                        type = MsgType.HELLO,
                        src = mySrc,
                        payload = buildJsonObject {
                            put("role", role.wire)
                            put("name", userName)
                            put("client", "app")
                        }
                    )
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = WsMessage.decode(text) ?: return
                if (m.type == MsgType.WELCOME) {
                    _sessionId.value = m.str("session") ?: ""
                    _state.value = ConnState.CONNECTED
                    startHeartbeat()
                }
                _incoming.tryEmit(m)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onDisconnected()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onDisconnected()
            }
        })
    }

    private fun onDisconnected() {
        stopHeartbeat()
        ws = null
        _sessionId.value = ""
        if (_state.value != ConnState.DISCONNECTED) {
            _state.value = ConnState.DISCONNECTED
            scheduleReconnect()
        } else if (serverUrl.isNotEmpty()) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch(Dispatchers.IO) {
            delay(3000)
            if (_state.value == ConnState.DISCONNECTED && serverUrl.isNotEmpty()) {
                _state.value = ConnState.CONNECTING
                openSocket()
            }
        }
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            var n = 0
            while (isActive) {
                send(MsgType.HEARTBEAT, buildJsonObject { put("n", n++) })
                delay(Protocol.HEARTBEAT_MS)
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /** 发送协议消息，自动补齐 seq/ts/src/session；返回是否已入发送队列 */
    fun send(
        type: String,
        payload: kotlinx.serialization.json.JsonObject,
        tgt: String? = null,
        corr: String? = null,
        ttlMs: Long? = null
    ): Boolean = sendRaw(
        WsMessage(
            type = type,
            session = _sessionId.value,
            seq = seqGen.incrementAndGet(),
            src = mySrc,
            tgt = tgt,
            corr = corr,
            ttlMs = ttlMs,
            payload = payload
        )
    )

    private fun sendRaw(m: WsMessage): Boolean {
        val sock = ws ?: return false
        return runCatching { sock.send(m.encode()) }.getOrDefault(false)
    }

    fun disconnect(silent: Boolean = false) {
        reconnectJob?.cancel()
        stopHeartbeat()
        runCatching { ws?.close(1000, "bye") }
        ws = null
        _sessionId.value = ""
        _state.value = ConnState.DISCONNECTED
        if (!silent) serverUrl = ""
    }
}
