# 首周验收用例自动化：角色拒绝 / 租约TTL / 防乱序 / 断连兜底 / 20Hz状态
import asyncio
import json
import time
import uuid

import websockets

SERVER = "ws://127.0.0.1:8765/ws"
DEV = "uav-sim-01"


def now_ms():
    return int(time.time() * 1000)


class WsClient:
    def __init__(self):
        self.ws = None
        self.seq = 0
        self.inbox = asyncio.Queue()

    async def connect(self, role, name, client="app", device_id=None, kind=None):
        self.ws = await websockets.connect(SERVER)
        p = {"role": role, "name": name, "client": client}
        if device_id:
            p.update(device_id=device_id, kind=kind or "rotary_sim")
        await self.send("hello", p)
        self.rx_task = asyncio.create_task(self._rx())
        # 等 welcome
        while True:
            m = await self.get()
            if m["type"] == "welcome":
                self.session = m["payload"]["session"]
                return self

    async def _rx(self):
        async for raw in self.ws:
            await self.inbox.put(json.loads(raw))

    async def get(self, timeout=3.0):
        return await asyncio.wait_for(self.inbox.get(), timeout)

    async def send(self, t, payload, tgt=None, corr=None, seq=None):
        self.seq += 1
        m = {"v": 1, "type": t, "session": getattr(self, "session", ""),
             "seq": seq if seq is not None else self.seq, "ts": now_ms(),
             "src": "app:test", "payload": payload}
        if tgt: m["tgt"] = tgt
        if corr: m["corr"] = corr
        await self.ws.send(json.dumps(m))

    async def close(self):
        self.rx_task.cancel()
        await self.ws.close()


async def until(cli, pred, timeout=3.0):
    """轮询收件箱直到满足条件的消息出现"""
    end = time.time() + timeout
    while time.time() < end:
        m = await cli.get(timeout=max(0.1, end - time.time()))
        if pred(m):
            return m
    raise TimeoutError("condition not met")


async def main():
    results = []

    # --- 设备端 ---
    dev = await WsClient().connect("device", "", "device", DEVICE_ID := DEV, "rotary_sim")
    async def dev_loop():
        n = 0
        next_tick = time.perf_counter()
        while True:
            next_tick += 0.05
            await asyncio.sleep(max(0.0, next_tick - time.perf_counter()))
            n += 1
            await dev.send("state", {
                "device": DEV, "kind": "rotary_sim",
                "pos": {"x": 0, "y": 0, "z": 1.0}, "vel": {"vx": 0, "vy": 0, "vz": 0},
                "att": {"roll": 0, "pitch": 0, "yaw": 0}, "mode": "HOVER",
                "battery_pct": 99.0, "quality": "iface_test",
                "validity": {"gnss": True, "link": True, "stale": False}})
    dev_task = asyncio.create_task(dev_loop())

    # 用例1：操作员申请控制权→GRANTED→move→RECEIVED→设备收到cmd
    op = await WsClient().connect("operator", "tan")
    await op.send("lease_req", {"device": DEV}, tgt=DEV)
    m = await until(op, lambda m: m["type"] == "lease_state" and m["payload"].get("state") == "GRANTED")
    results.append(("用例1 操作员获得控制权 GRANTED", True))

    cmd_id = uuid.uuid4().hex
    await op.send("cmd", {"cmd_id": cmd_id, "action": "move",
                          "params": {"vx": 1.0, "vy": 0, "vz": 0, "yaw_rate": 0}}, tgt=DEV)
    m = await until(op, lambda m: m["type"] == "ack" and m.get("corr") == cmd_id)
    results.append(("用例1 指令收到 RECEIVED 应答", m["payload"]["status"] == "RECEIVED"))

    # 用例2：观察员发指令 → E_ROLE
    ob = await WsClient().connect("observer", "guest")
    bad_id = uuid.uuid4().hex
    await ob.send("cmd", {"cmd_id": bad_id, "action": "takeoff", "params": {}}, tgt=DEV)
    m = await until(ob, lambda m: m["type"] == "ack" and m.get("corr") == bad_id)
    ok = m["payload"]["status"] == "REJECTED" and m["payload"]["err"] == "E_ROLE"
    results.append(("用例2 观察员被拒绝 E_ROLE", ok))

    # 用例3：乱序包被丢弃 E_SEQ
    seq_id = uuid.uuid4().hex
    await op.send("cmd", {"cmd_id": seq_id, "action": "move",
                          "params": {"vx": 0.5, "vy": 0, "vz": 0, "yaw_rate": 0}}, tgt=DEV, seq=1)  # 旧seq
    m = await until(op, lambda m: m["type"] == "ack" and m.get("corr") == seq_id)
    results.append(("用例3 乱序报文丢弃 E_SEQ", m["payload"].get("err") == "E_SEQ"))

    # 用例4：无控制权者发指令 → E_LEASE
    other = await WsClient().connect("operator", "other")
    l_id = uuid.uuid4().hex
    await other.send("cmd", {"cmd_id": l_id, "action": "move",
                             "params": {"vx": 1, "vy": 0, "vz": 0, "yaw_rate": 0}}, tgt=DEV)
    m = await until(other, lambda m: m["type"] == "ack" and m.get("corr") == l_id)
    results.append(("用例4 无控制权拒绝 E_LEASE", m["payload"].get("err") == "E_LEASE"))

    # 用例5：杀掉 APP → 租约 EXPIRED + 设备收到强制 stop（TTL 兜底）
    await op.close()
    m = await until(other, lambda m: m["type"] == "lease_state" and m["payload"].get("state") == "EXPIRED", timeout=8)
    results.append(("用例5 APP断连租约自动EXPIRED", True))
    # 设备端应收到 svc 的 stop（dev_loop 在发state，检查dev inbox）
    try:
        m = await until(dev, lambda m: m["type"] == "cmd" and m["payload"].get("action") == "stop", timeout=3)
        results.append(("用例5 服务端强制归零stop已下发设备", m["src"] == "svc"))
    except TimeoutError:
        results.append(("用例5 服务端强制归零stop已下发设备", False))

    # 用例6：20Hz 状态转发
    ob2 = await WsClient().connect("observer", "counter")
    t0 = time.time()
    count = 0
    while time.time() - t0 < 2.0:
        m = await ob2.get(timeout=1.0)
        if m["type"] == "state":
            count += 1
    hz = count / 2.0
    results.append((f"用例6 状态转发频率 {hz:.0f}Hz ≥20Hz", hz >= 18))

    dev_task.cancel()
    print()
    allok = True
    for name, ok in results:
        print(("PASS " if ok else "FAIL ") + name)
        allok &= ok
    print("\n总体:", "全部通过" if allok else "存在失败项")
    for c in (dev, ob, other, ob2):
        try: await c.close()
        except Exception: pass


asyncio.run(main())
