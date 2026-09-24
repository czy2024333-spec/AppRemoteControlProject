package com.vrxfusion.remote.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * MQTT 传输层 —— IF-001：APP 与统一控制服务之间的标准化双向通道。
 * 封装 Paho 客户端：连接管理、主题订阅、统一消息发布/接收。
 *
 * 说明：工程原型阶段 Broker 地址可配置（默认 tcp://127.0.0.1:1883，
 * 局域网部署时在设置页改为统一控制服务器地址）。未连接时消息进入
 * 待发队列重试，保证弱网下的可观测性（NFR-002 断线检测与自动重连）。
 */
class MqttTransport(
    private val scope: CoroutineScope,
    private val brokerUri: String = "tcp://127.0.0.1:1883"
) {
    enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED }

    private val _state = MutableStateFlow(ConnState.DISCONNECTED)
    val state: StateFlow<ConnState> = _state

    /** deviceId -> 收到的统一消息 */
    private val _incoming = MutableStateFlow<List<UnifiedMessage>>(emptyList())
    val incoming: StateFlow<List<UnifiedMessage>> = _incoming

    private var client: MqttClient? = null
    private var reconnectJob: Job? = null
    private val seqGen = AtomicLong(0)
    private val sessionId: String = UUID.randomUUID().toString().take(8)

    fun nextSeq(): Long = seqGen.incrementAndGet()
    fun currentSessionId(): String = sessionId

    fun connect() {
        if (_state.value != ConnState.DISCONNECTED) return
        _state.value = ConnState.CONNECTING
        scope.launch(Dispatchers.IO) {
            runCatching {
                val c = MqttClient(brokerUri, "vrx-app-${UUID.randomUUID()}", MemoryPersistence())
                c.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, uri: String?) {
                        _state.value = ConnState.CONNECTED
                    }
                    override fun connectionLost(cause: Throwable?) {
                        _state.value = ConnState.DISCONNECTED
                        scheduleReconnect()
                    }
                    override fun messageArrived(topic: String, message: MqttMessage) {
                        UnifiedMessage.decode(String(message.payload))?.let { m ->
                            _incoming.value = _incoming.value + m
                        }
                    }
                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })
                val opts = MqttConnectOptions().apply {
                    isAutomaticReconnect = false
                    isCleanSession = true
                    connectionTimeout = 5
                    keepAliveInterval = 10
                }
                c.connect(opts)
                client = c
            }.onFailure {
                _state.value = ConnState.DISCONNECTED
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(3000)
            if (_state.value == ConnState.DISCONNECTED) connect()
        }
    }

    fun subscribe(deviceId: String) {
        val c = client ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                listOf(Topics.state(deviceId), Topics.alarm(deviceId), Topics.result(deviceId))
                    .forEach { c.subscribe(it, 1) }
            }
        }
    }

    /** 发布统一消息；返回是否成功送达 Broker */
    fun publish(msg: UnifiedMessage): Boolean {
        val c = client ?: return false
        if (!c.isConnected) return false
        return runCatching {
            val topic = when (msg.type) {
                MessageType.CMD, MessageType.TASK -> Topics.cmd(msg.deviceId)
                else -> Topics.state(msg.deviceId)
            }
            c.publish(topic, MqttMessage(msg.encode().toByteArray()).apply { qos = 1 })
            true
        }.getOrDefault(false)
    }

    fun disconnect() {
        reconnectJob?.cancel()
        runCatching { client?.disconnect() }
        client = null
        _state.value = ConnState.DISCONNECTED
    }
}
