package com.vrxfusion.remote

import com.vrxfusion.remote.adapters.AdapterKind
import com.vrxfusion.remote.adapters.DeviceAdapterRegistry
import com.vrxfusion.remote.adapters.LocalSimAdapter
import com.vrxfusion.remote.adapters.ReservedAdapter
import com.vrxfusion.remote.adapters.ServiceAdapter
import com.vrxfusion.remote.device.Capability
import com.vrxfusion.remote.device.CommandStatus
import com.vrxfusion.remote.device.DeviceCommand
import com.vrxfusion.remote.device.DeviceDescriptor
import com.vrxfusion.remote.device.DeviceKind
import com.vrxfusion.remote.device.DeviceRepository
import com.vrxfusion.remote.device.GaitPosture
import com.vrxfusion.remote.device.RealityType
import com.vrxfusion.remote.device.SimAction
import com.vrxfusion.remote.protocol.MsgType
import com.vrxfusion.remote.protocol.Role
import com.vrxfusion.remote.protocol.WsMessage
import com.vrxfusion.remote.protocol.WsTransport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 11/26 模拟适配器框架：注册、能力边界、异常应答和设备类型隔离。 */
class DeviceAdapterFrameworkTest {
    private fun descriptor(
        id: String = "sim-drone-1",
        kind: DeviceKind = DeviceKind.DRONE,
        reality: RealityType = RealityType.VIRTUAL,
        capabilities: Set<Capability> = setOf(
            Capability.TELEMETRY, Capability.MOTION, Capability.TAKEOFF_LAND,
            Capability.HOVER, Capability.RTL, Capability.SIM_MANAGE
        ),
        reserved: Boolean = false
    ) = DeviceDescriptor(
        id = id, name = id, kind = kind, reality = reality, model = "adapter-test",
        capabilities = capabilities, reserved = reserved
    )

    @Test
    fun registryRejectsDuplicateIdentityAndRealSimulationManagement() = runBlocking {
        val transport = WsTransport(this)
        val registry = DeviceAdapterRegistry(this, transport)
        registry.create(descriptor(), AdapterKind.LOCAL_SIM)
        assertTrue(runCatching {
            registry.create(descriptor(), AdapterKind.LOCAL_SIM)
        }.isFailure)
        assertTrue(runCatching {
            registry.create(descriptor(
                id = "real-drone-1", reality = RealityType.REAL
            ), AdapterKind.SERVICE)
        }.isFailure)
        assertTrue(runCatching {
            registry.create(descriptor(id = "bad id"), AdapterKind.LOCAL_SIM)
        }.isFailure)
        assertEquals(1, registry.values.size)
        transport.disconnect()
    }

    @Test
    fun droneAndQuadrupedSimulationEnforceCapabilitiesBeforeExecution() = runBlocking {
        val drone = LocalSimAdapter(this, descriptor())
        val dog = LocalSimAdapter(this, descriptor(
            id = "sim-dog-1", kind = DeviceKind.QUADRUPED,
            capabilities = setOf(Capability.TELEMETRY, Capability.MOTION, Capability.GAIT)
        ))
        drone.start()
        dog.start()
        try {
            assertEquals(CommandStatus.SUCCESS, drone.send(DeviceCommand.Takeoff).status)
            val droneGait = drone.send(DeviceCommand.Gait(GaitPosture.STAND_UP))
            assertEquals(CommandStatus.FAILED, droneGait.status)
            assertTrue(droneGait.detail.orEmpty().contains("不支持"))
            assertEquals(CommandStatus.SUCCESS,
                dog.send(DeviceCommand.Gait(GaitPosture.LIE_DOWN)).status)
            val dogTakeoff = dog.send(DeviceCommand.Takeoff)
            assertEquals(CommandStatus.FAILED, dogTakeoff.status)
            assertTrue(dogTakeoff.detail.orEmpty().contains("不支持"))
            val stopped = drone.send(DeviceCommand.SimManage(SimAction.PAUSE))
            assertEquals(CommandStatus.SUCCESS, stopped.status)
        } finally {
            drone.stop()
            dog.stop()
            }
    }

    @Test
    fun stoppedAndReservedAdaptersReturnExplicitFailureInsteadOfSuccess() = runBlocking {
        val local = LocalSimAdapter(this, descriptor())
        val stopped = local.send(DeviceCommand.Stop)
        assertEquals(CommandStatus.FAILED, stopped.status)
        assertTrue(stopped.detail.orEmpty().contains("未启动"))

        val reserved = ReservedAdapter(descriptor(
            id = "dog-real-1", kind = DeviceKind.QUADRUPED, reality = RealityType.REAL,
            capabilities = setOf(Capability.TELEMETRY), reserved = true
        ))
        reserved.start()
        val record = reserved.send(DeviceCommand.Stop)
        assertEquals(CommandStatus.FAILED, record.status)
        assertTrue(record.detail.orEmpty().contains("预留"))
        assertFalse(reserved.state.value.online)
    }

    @Test
    fun serviceAdapterCannotRouteSimulationManagementOrUnsupportedCommand() = runBlocking {
        val transport = WsTransport(this)
        val adapter = ServiceAdapter(this, descriptor(), transport)
        adapter.start()
        val simulation = adapter.send(DeviceCommand.SimManage(SimAction.RESET))
        assertEquals(CommandStatus.FAILED, simulation.status)
        // 未连接时在传输边界提前失败；已连接时仍有 SimManage 分支拒绝，均不得发送。
        val disconnected = adapter.send(DeviceCommand.Stop)
        assertEquals(CommandStatus.FAILED, disconnected.status)
        assertTrue(disconnected.detail.orEmpty().contains("未连接"))
        adapter.stop()
        transport.disconnect()
    }

    @Test
    fun connectedServiceRejectsSimulationManagementWithoutSendingCommand() = runBlocking {
        val received = ConcurrentLinkedQueue<WsMessage>()
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(WsMessage(
                    type = MsgType.WELCOME,
                    payload = buildJsonObject { put("session", "adapter-test") }
                ).encode())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                WsMessage.decode(text)?.let(received::add)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }
        }))
        server.start()
        val transport = WsTransport(this)
        val adapter = ServiceAdapter(this, descriptor(), transport)
        adapter.start()
        try {
            transport.connect(server.url("/ws").toString(), Role.OPERATOR, "tester")
            withTimeout(3000) {
                transport.state.first { it == WsTransport.ConnState.CONNECTED }
            }
            val record = adapter.send(DeviceCommand.SimManage(SimAction.RESET))
            assertEquals(CommandStatus.FAILED, record.status)
            assertTrue(record.detail.orEmpty().contains("仅本地虚拟设备"))
            assertTrue(received.none { it.type == MsgType.CMD })
            adapter.stop()
            assertFalse(adapter.state.value.online)
            val stopped = adapter.send(DeviceCommand.Stop)
            assertEquals(CommandStatus.FAILED, stopped.status)
            assertTrue(stopped.detail.orEmpty().contains("已停止"))
            adapter.setController("tester")
            assertTrue(received.none { it.type == MsgType.CMD || it.type == MsgType.LEASE_REQ })
            adapter.start()
            assertEquals(CommandStatus.SENT, adapter.send(DeviceCommand.Stop).status)
        } finally {
            adapter.stop()
            transport.disconnect()
            server.shutdown()
        }
    }

    @Test
    fun repositoryCanStopAndRestartWithoutDuplicateRegistration() = runBlocking {
        val transport = WsTransport(this)
        val repository = DeviceRepository(this, transport)
        repository.start()
        repository.start()
        assertEquals(4, repository.descriptors.value.size)
        repository.stop()
        assertTrue(repository.states.value.values.none { it.online })
        repository.start()
        assertEquals(4, repository.descriptors.value.size)
        repository.stop()
    }

    @Test
    fun unstartedRepositoryStillDisconnectsIndependentlyConnectedTransport() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(WsMessage(type = MsgType.WELCOME).encode())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }
        }))
        server.start()
        val transport = WsTransport(this)
        val repository = DeviceRepository(this, transport)
        try {
            transport.connect(server.url("/ws").toString(), Role.OPERATOR, "tester")
            withTimeout(3000) {
                transport.state.first { it == WsTransport.ConnState.CONNECTED }
            }
            repository.stop()
            assertEquals(WsTransport.ConnState.DISCONNECTED, transport.state.value)
        } finally {
            repository.stop()
            server.shutdown()
        }
    }
}
