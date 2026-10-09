# 【临时测试适配】模拟无人机设备端 —— 仅用于首次联调验证链路
# 明确标识：输出 quality=iface_test，不代表 A 的动力学模型或 C 的 UE 场景
# A 交付仿真核心接口、C 完成 UE 接入后，本脚本由真实设备端替换，服务端与 APP 无需改动
import asyncio
import json
import math
import time
import uuid

import websockets

SERVER = "ws://127.0.0.1:8765/ws"
DEVICE_ID = "uav-sim-01"
HZ = 20
DT = 1.0 / HZ


def now_ms():
    return int(time.time() * 1000)


class MockUav:
    """最小竖直模型：一阶响应逼近目标速度，仅支持起飞/降落/悬停/停止/移动"""

    def __init__(self):
        self.x = self.y = 0.0
        self.z = 0.0
        self.vx = self.vy = self.vz = 0.0
        self.yaw = 0.0
        self.tgt = {"vx": 0.0, "vy": 0.0, "vz": 0.0, "yaw_rate": 0.0}
        self.mode = "IDLE"          # IDLE|FLYING|HOVER|LANDING
        self.battery = 100.0
        self.seq = 0
        self.last_cmd_ms = 0

    def on_cmd(self, p: dict) -> str:
        action = p.get("action")
        if action == "takeoff" and self.mode == "IDLE":
            self.mode = "FLYING"
            self.tgt.update(vz=1.0)
            self.last_cmd_ms = now_ms()
            return "EXECUTING"
        if action == "land" and self.mode in ("FLYING", "HOVER"):
            self.mode = "LANDING"
            self.tgt.update(vx=0.0, vy=0.0, vz=-0.8, yaw_rate=0.0)
            self.last_cmd_ms = now_ms()
            return "EXECUTING"
        if action in ("hover", "stop"):
            self.tgt.update(vx=0.0, vy=0.0, vz=0.0, yaw_rate=0.0)
            if self.mode in ("FLYING", "LANDING") and self.z > 0.05:
                self.mode = "HOVER"
            self.last_cmd_ms = now_ms()
            return "DONE"
        if action == "move":
            self.tgt.update({k: float(p.get("params", {}).get(k, 0.0))
                             for k in ("vx", "vy", "vz", "yaw_rate")})
            self.last_cmd_ms = now_ms()
            return "DONE"
        if action == "return_home":
            self.tgt.update(vx=0.0, vy=0.0, vz=0.0, yaw_rate=0.0)
            self.x = self.y = 0.0
            self.last_cmd_ms = now_ms()
            return "EXECUTING"
        return "FAILED"

    def step(self):
        # 输入失效保护：500ms 无新连续指令自动悬停（与服务端 TTL 兜底形成双保险）
        if self.mode == "FLYING" and now_ms() - self.last_cmd_ms > 500:
            self.tgt.update(vx=0.0, vy=0.0, vz=0.0, yaw_rate=0.0)
        k = min(1.0, 4.0 * DT)   # 一阶响应
        self.vx += (self.tgt["vx"] - self.vx) * k
        self.vy += (self.tgt["vy"] - self.vy) * k
        self.vz += (self.tgt["vz"] - self.vz) * k
        self.x += self.vx * DT
        self.y += self.vy * DT
        self.z = max(0.0, self.z + self.vz * DT)
        self.yaw = (self.yaw + self.tgt["yaw_rate"] * DT) % (2 * math.pi)
        if self.mode == "LANDING" and self.z <= 0.0:
            self.mode = "IDLE"
            self.tgt.update(vz=0.0)
        if self.mode == "FLYING" and abs(self.vz) < 0.01 and self.tgt["vz"] == 0 and self.z >= 1.0:
            self.mode = "HOVER"
        if self.mode != "IDLE":
            self.battery = max(0.0, self.battery - 0.001)

    def state(self) -> dict:
        self.seq += 1
        return {
            "v": 1, "type": "state", "session": "mock", "seq": self.seq, "ts": now_ms(),
            "src": f"dev:{DEVICE_ID}",
            "payload": {
                "device": DEVICE_ID, "kind": "rotary_sim",
                "pos": {"x": round(self.x, 3), "y": round(self.y, 3), "z": round(self.z, 3)},
                "vel": {"vx": round(self.vx, 3), "vy": round(self.vy, 3), "vz": round(self.vz, 3)},
                "att": {"roll": 0.0, "pitch": 0.0, "yaw": round(self.yaw, 3)},
                "mode": self.mode,
                "battery_pct": round(self.battery, 1),
                "quality": "iface_test",   # 明确标识：接口测试数据（DAT-002）
                "validity": {"gnss": True, "link": True, "stale": False},
            },
        }


async def main():
    uav = MockUav()
    async with websockets.connect(SERVER) as ws:
        await ws.send(json.dumps({
            "v": 1, "type": "hello", "session": "", "seq": 1, "ts": now_ms(),
            "src": f"dev:{DEVICE_ID}",
            "payload": {"role": "device", "client": "device",
                        "device_id": DEVICE_ID, "kind": "rotary_sim",
                        "capabilities": ["move", "takeoff", "land", "hover", "stop", "return_home"]},
        }))
        print(f"模拟设备 {DEVICE_ID} 已接入 {SERVER}（quality=iface_test，临时适配）")

        async def sender():
            next_tick = time.perf_counter()
            while True:
                next_tick += DT
                await asyncio.sleep(max(0.0, next_tick - time.perf_counter()))
                uav.step()
                await ws.send(json.dumps(uav.state()))

        async def receiver():
            async for raw in ws:
                msg = json.loads(raw)
                if msg.get("type") == "cmd":
                    p = msg.get("payload", {})
                    status = uav.on_cmd(p)
                    ack = {
                        "v": 1, "type": "ack", "session": "mock", "seq": 0, "ts": now_ms(),
                        "src": f"dev:{DEVICE_ID}", "corr": p.get("cmd_id"),
                        "payload": {"cmd_id": p.get("cmd_id"), "status": status},
                    }
                    await ws.send(json.dumps(ack))

        await asyncio.gather(sender(), receiver())


if __name__ == "__main__":
    asyncio.run(main())
