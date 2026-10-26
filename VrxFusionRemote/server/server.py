# 统一控制/状态服务 v0.1
# 角色B交付物：FastAPI + WebSocket，实现协议 docs/PROTOCOL_v0.1.md
# 首次联调范围：角色校验、控制权租约、TTL兜底、防乱序、20Hz状态转发、SQLite日志
import asyncio
import json
import sqlite3
import time
import uuid
from pathlib import Path

from fastapi import FastAPI, WebSocket, WebSocketDisconnect

# ---------------- 配置（CTL-006 预留：后续移入配置文件） ----------------
# 端口：8765 已被合伙人A 的 v0.5.1 统一控制服务占用，本自建服务改用 8767
HOST, PORT = "0.0.0.0", 8767
LEASE_TTL_MS = 1000        # 连续输入租约有效期
CMD_ACK_TIMEOUT_MS = 800   # 设备应答超时
HEARTBEAT_TIMEOUT_S = 5    # 心跳超时判定断连
STATE_STALE_MS = 500       # 状态过期阈值（APP 侧同样使用）
MAX_SPEED = 5.0            # m/s 参数范围校验
MAX_YAW_RATE = 1.5         # rad/s
DB_PATH = Path(__file__).parent / "service.db"
LOG_DIR = Path(__file__).parent / "logs"
LOG_DIR.mkdir(exist_ok=True)

ROLE_CAN_CONTROL = {"admin", "operator"}     # 观察员只读（APP-002）
DEVICE_ACTIONS = {"move", "takeoff", "land", "hover", "stop", "return_home"}

app = FastAPI(title="UAV Unified Control Service", version="0.1")


def now_ms() -> int:
    return int(time.time() * 1000)


# ---------------- SQLite 元数据 + 分文件 JSONL 日志（每日必做规范） ----------------
class Logger:
    def __init__(self):
        self.db = sqlite3.connect(DB_PATH, check_same_thread=False)
        self.db.executescript("""
        CREATE TABLE IF NOT EXISTS sessions(
            session TEXT PRIMARY KEY, src TEXT, role TEXT, client TEXT,
            connected_ms INTEGER, closed_ms INTEGER, close_reason TEXT);
        CREATE TABLE IF NOT EXISTS commands(
            cmd_id TEXT PRIMARY KEY, session TEXT, tgt TEXT, action TEXT,
            params TEXT, recv_ms INTEGER, result TEXT, err TEXT, done_ms INTEGER);
        CREATE TABLE IF NOT EXISTS leases(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            device TEXT, holder TEXT, state TEXT, ts_ms INTEGER, reason TEXT);
        CREATE TABLE IF NOT EXISTS states_meta(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            device TEXT, ts_ms INTEGER, mode TEXT, quality TEXT, stale INTEGER);
        """)
        self.db.commit()
        self._files = {}
        self._lock = asyncio.Lock()

    def _jsonl(self, session: str):
        if session not in self._files:
            self._files[session] = open(LOG_DIR / f"{session}.jsonl", "a", encoding="utf-8")
        return self._files[session]

    async def event(self, session: str, kind: str, data: dict):
        async with self._lock:
            rec = {"ts": now_ms(), "kind": kind, **data}
            self._jsonl(session).write(json.dumps(rec, ensure_ascii=False) + "\n")
            self._jsonl(session).flush()

    def sql(self, stmt: str, args: tuple):
        self.db.execute(stmt, args)
        self.db.commit()


log = Logger()


# ---------------- 会话与设备注册表 ----------------
class Client:
    def __init__(self, ws: WebSocket):
        self.ws = ws
        self.session = uuid.uuid4().hex[:12]
        self.src = "unknown"
        self.role = "observer"
        self.client = "app"          # app | device
        self.device_id = None
        self.kind = None
        self.last_seq = 0            # 已接收最大 seq（防乱序）
        self.last_seen = now_ms()
        self.active = True           # 非 ACTIVE 时指令强制归零（W1 安全兜底）

    def header_ok(self, msg: dict) -> bool:
        """序列号校验：旧包/乱序丢弃（E_SEQ）"""
        seq = msg.get("seq", 0)
        if seq <= self.last_seq:
            return False
        self.last_seq = seq
        return True


class DeviceState:
    def __init__(self, device_id: str):
        self.device_id = device_id
        self.holder: str | None = None       # 控制权持有者 src
        self.lease_expire_ms = 0
        self.last_cmd_ms = 0
        self.last_state: dict | None = None
        self.last_state_ms = 0
        self.online = False
        self.capabilities = ["move", "takeoff", "land", "hover", "stop", "return_home"]


clients: dict[str, Client] = {}              # session -> Client
devices: dict[str, DeviceState] = {}         # device_id -> DeviceState
device_ws: dict[str, Client] = {}            # device_id -> 设备端连接
pending_acks: dict[str, tuple[str, float]] = {}  # cmd_id -> (app_session, deadline_ms)


def envelope(ctype: str, src: str, payload: dict, tgt=None, corr=None, ttl=None, seq=0) -> dict:
    msg = {"v": 1, "type": ctype, "session": "svc", "seq": seq, "ts": now_ms(),
           "src": src, "payload": payload}
    if tgt: msg["tgt"] = tgt
    if corr: msg["corr"] = corr
    if ttl: msg["ttl_ms"] = ttl
    return msg


async def send(c: Client, msg: dict):
    try:
        await c.ws.send_text(json.dumps(msg, ensure_ascii=False))
    except Exception:
        c.active = False


async def broadcast_apps(msg: dict):
    for c in list(clients.values()):
        if c.client == "app" and c.active:
            await send(c, msg)


def roster_payload() -> dict:
    return {"devices": [{
        "device_id": d.device_id, "online": d.online,
        "holder": d.holder, "capabilities": d.capabilities,
        "stale": (now_ms() - d.last_state_ms) > STATE_STALE_MS,
    } for d in devices.values()]}


async def set_lease(dev: DeviceState, holder: str | None, state: str, reason: str):
    dev.holder = holder
    log.sql("INSERT INTO leases(device,holder,state,ts_ms,reason) VALUES(?,?,?,?,?)",
            (dev.device_id, holder, state, now_ms(), reason))
    await broadcast_apps(envelope("lease_state", "svc",
        {"device": dev.device_id, "state": state, "holder": holder, "reason": reason},
        tgt=dev.device_id))
    await broadcast_apps(envelope("roster", "svc", roster_payload()))


async def force_stop(dev: DeviceState, reason: str):
    """安全兜底：状态非 ACTIVE / 租约过期时强制归零（W1 任务3）"""
    dev.last_cmd_ms = now_ms()
    stop = envelope("cmd", "svc", {
        "cmd_id": uuid.uuid4().hex,
        "action": "stop",
        "params": {"vx": 0.0, "vy": 0.0, "vz": 0.0, "yaw_rate": 0.0},
        "reason": reason,
    }, tgt=dev.device_id, ttl=LEASE_TTL_MS)
    c = device_ws.get(dev.device_id)
    if c and c.active:
        await send(c, stop)
    print(f"[SAFE] force stop -> {dev.device_id} ({reason})")


# ---------------- 后台看门狗：租约 TTL / 心跳超时 ----------------
async def watchdog():
    while True:
        await asyncio.sleep(0.2)
        now = now_ms()
        # 心跳超时 → 断连处理
        for c in list(clients.values()):
            if c.active and now - c.last_seen > HEARTBEAT_TIMEOUT_S * 1000:
                c.active = False
                await on_disconnect(c, "heartbeat_timeout")
        # 租约 TTL 到期 → 强制停车 + 释放
        for dev in devices.values():
            if dev.holder and now > dev.lease_expire_ms:
                holder = dev.holder
                await set_lease(dev, None, "EXPIRED", f"ttl_exceeded holder={holder}")
                await force_stop(dev, "lease_ttl_expired")
        # 设备应答超时 → TIMEOUT ack
        for cmd_id, (sess, deadline) in list(pending_acks.items()):
            if now > deadline:
                del pending_acks[cmd_id]
                c = clients.get(sess)
                if c:
                    await send(c, envelope("ack", "svc",
                        {"cmd_id": cmd_id, "status": "TIMEOUT", "err": "E_TIMEOUT"},
                        corr=cmd_id))


# ---------------- 指令校验与路由（CTL-001/002/004） ----------------
async def handle_cmd(c: Client, msg: dict):
    p = msg.get("payload", {})
    tgt = msg.get("tgt") or p.get("device")
    cmd_id = p.get("cmd_id", uuid.uuid4().hex)
    action = p.get("action", "")
    params = p.get("params", {})

    async def reject(err: str, detail: str):
        log.sql("INSERT OR REPLACE INTO commands VALUES(?,?,?,?,?,?,?,?,?)",
                (cmd_id, c.session, tgt, action, json.dumps(params), now_ms(), "REJECTED", err, now_ms()))
        await send(c, envelope("ack", "svc",
            {"cmd_id": cmd_id, "status": "REJECTED", "err": err, "detail": detail}, corr=cmd_id))

    # 1) 会话状态：非 ACTIVE 一律拒（重连不恢复旧输入）
    if not c.active:
        return await reject("E_STATE", "session not active")
    # 2) 角色：观察员不能控制（首周验收用例）
    if c.role not in ROLE_CAN_CONTROL:
        return await reject("E_ROLE", f"role {c.role} cannot control")
    # 3) 消息有效期
    if now_ms() - msg.get("ts", 0) > msg.get("ttl_ms", 1000):
        return await reject("E_EXPIRED", "message expired")
    # 4) 目标与动作
    dev = devices.get(tgt)
    if not dev or not dev.online:
        return await reject("E_STATE", f"device {tgt} offline")
    if action not in DEVICE_ACTIONS:
        return await reject("E_CAP", f"unknown action {action}")
    if action not in dev.capabilities:
        return await reject("E_CAP", f"device lacks capability {action}")
    # 5) 参数范围
    if action == "move":
        if any(abs(params.get(k, 0.0)) > MAX_SPEED for k in ("vx", "vy", "vz")):
            return await reject("E_RANGE", "speed out of range")
        if abs(params.get("yaw_rate", 0.0)) > MAX_YAW_RATE:
            return await reject("E_RANGE", "yaw_rate out of range")
    # 6) 控制权：stop 保护指令优先，其余须持有租约（CTL-002 停止优先）
    if action != "stop" and dev.holder != c.src:
        return await reject("E_LEASE", f"control held by {dev.holder}")
    # 7) 防乱序（连续指令）
    if not c.header_ok(msg):
        return await reject("E_SEQ", "out-of-order seq dropped")

    # 通过 → 回 RECEIVED，转发设备，续租
    log.sql("INSERT OR REPLACE INTO commands VALUES(?,?,?,?,?,?,?,?,?)",
            (cmd_id, c.session, tgt, action, json.dumps(params), now_ms(), "RECEIVED", None, None))
    await send(c, envelope("ack", "svc", {"cmd_id": cmd_id, "status": "RECEIVED"}, corr=cmd_id))
    dev.last_cmd_ms = now_ms()
    dev.lease_expire_ms = now_ms() + LEASE_TTL_MS
    pending_acks[cmd_id] = (c.session, now_ms() + CMD_ACK_TIMEOUT_MS)
    fwd = dict(msg)
    fwd["src"] = c.src
    dc = device_ws.get(tgt)
    if dc and dc.active:
        await send(dc, fwd)
    else:
        pending_acks.pop(cmd_id, None)
        await send(c, envelope("ack", "svc",
            {"cmd_id": cmd_id, "status": "FAILED", "err": "E_STATE", "detail": "device link down"}, corr=cmd_id))


async def handle_lease(c: Client, msg: dict, acquire: bool):
    tgt = msg.get("tgt") or msg.get("payload", {}).get("device")
    dev = devices.get(tgt)
    if not dev:
        return
    if c.role not in ROLE_CAN_CONTROL:
        return await send(c, envelope("error", "svc", {"code": "E_ROLE", "msg": "role cannot lease"}))
    if acquire:
        if dev.holder and dev.holder != c.src:
            # 冲突：管理员可强制接管（先清残留再中立生效，CTL-003）
            if c.role == "admin":
                await set_lease(dev, None, "RELEASED", f"force_takeover_by {c.src}")
                await force_stop(dev, "clear_residual_before_takeover")
            else:
                return await send(c, envelope("lease_state", "svc",
                    {"device": tgt, "state": "DENIED", "holder": dev.holder, "reason": "held_by_other"}, tgt=tgt))
        await set_lease(dev, c.src, "GRANTED", "acquire")
        dev.lease_expire_ms = now_ms() + LEASE_TTL_MS
    else:
        if dev.holder == c.src:
            await set_lease(dev, None, "RELEASED", "user_release")
            await force_stop(dev, "lease_released")


async def handle_device_msg(c: Client, msg: dict):
    """设备端消息：state / ack"""
    t = msg.get("type")
    dev = devices.get(c.device_id)
    if t == "state" and dev:
        dev.last_state = msg.get("payload", {})
        dev.last_state_ms = now_ms()
        log.sql("INSERT INTO states_meta(device,ts_ms,mode,quality,stale) VALUES(?,?,?,?,0)",
                (dev.device_id, now_ms(),
                 dev.last_state.get("mode", ""), dev.last_state.get("quality", "")))
        # 转发全体 APP（≥20Hz 由设备端保证，服务零缓冲透传）
        fwd = dict(msg)
        await broadcast_apps(fwd)
    elif t == "ack":
        corr = msg.get("corr") or msg.get("payload", {}).get("cmd_id", "")
        pend = pending_acks.pop(corr, None)
        log.sql("UPDATE commands SET result=?, done_ms=? WHERE cmd_id=?",
                (msg.get("payload", {}).get("status", ""), now_ms(), corr))
        if pend:
            app_c = clients.get(pend[0])
            if app_c:
                await send(app_c, msg)


async def on_disconnect(c: Client, reason: str):
    if not c.active and reason != "ws_close":
        pass
    c.active = False
    clients.pop(c.session, None)
    log.sql("UPDATE sessions SET closed_ms=?, close_reason=? WHERE session=?",
            (now_ms(), reason, c.session))
    await log.event(c.session, "disconnect", {"reason": reason})
    # 设备下线
    if c.client == "device" and c.device_id in devices:
        dev = devices[c.device_id]
        dev.online = False
        device_ws.pop(c.device_id, None)
        await set_lease(dev, None, "RELEASED", "device_offline")
    # APP 断连 → 释放其控制权 + 强制停车（CTL-005：重连不恢复旧输入）
    for dev in devices.values():
        if dev.holder == c.src:
            await set_lease(dev, None, "EXPIRED", f"app_disconnect:{reason}")
            await force_stop(dev, "app_disconnect")
    try:
        await c.ws.close()
    except Exception:
        pass


# ---------------- WebSocket 入口 ----------------
@app.websocket("/ws")
async def ws_endpoint(ws: WebSocket):
    await ws.accept()
    c = Client(ws)
    clients[c.session] = c
    try:
        # 首帧必须 hello
        raw = await asyncio.wait_for(ws.receive_text(), timeout=10)
        hello = json.loads(raw)
        if hello.get("type") != "hello":
            await send(c, envelope("error", "svc", {"code": "E_STATE", "msg": "first frame must be hello"}))
            return await on_disconnect(c, "no_hello")
        p = hello.get("payload", {})
        c.role = p.get("role", "observer")
        c.client = p.get("client", "app")
        c.src = f"app:{p.get('name','anon')}" if c.client == "app" else f"dev:{p.get('device_id','?')}"
        c.last_seen = now_ms()
        log.sql("INSERT INTO sessions VALUES(?,?,?,?,?,NULL,NULL)",
                (c.session, c.src, c.role, c.client, now_ms()))
        await log.event(c.session, "hello", p)

        if c.client == "device":
            c.device_id = p.get("device_id")
            c.kind = p.get("kind", "unknown")
            dev = devices.setdefault(c.device_id, DeviceState(c.device_id))
            dev.online = True
            if "capabilities" in p:
                dev.capabilities = p["capabilities"]
            device_ws[c.device_id] = c
        await send(c, envelope("welcome", "svc",
            {"session": c.session, "role_granted": c.role}))
        await broadcast_apps(envelope("roster", "svc", roster_payload()))
        await send(c, envelope("roster", "svc", roster_payload()))
        print(f"[+] {c.src} ({c.role}/{c.client}) session={c.session}")

        while True:
            raw = await ws.receive_text()
            c.last_seen = now_ms()
            msg = json.loads(raw)
            t = msg.get("type")
            await log.event(c.session, "rx", {"type": t, "tgt": msg.get("tgt")})
            if t == "heartbeat":
                await send(c, envelope("heartbeat", "svc", {"echo": msg.get("payload", {}).get("n", 0)}))
            elif t == "cmd":
                await handle_cmd(c, msg)
            elif t == "lease_req":
                await handle_lease(c, msg, True)
            elif t == "lease_rel":
                await handle_lease(c, msg, False)
            elif c.client == "device":
                await handle_device_msg(c, msg)
    except WebSocketDisconnect:
        await on_disconnect(c, "ws_close")
    except Exception as e:
        print(f"[!] {c.src} error: {e}")
        await on_disconnect(c, f"error:{type(e).__name__}")


@app.on_event("startup")
async def startup():
    asyncio.create_task(watchdog())
    print(f"统一控制服务 v0.1 启动: ws://{HOST}:{PORT}/ws  DB={DB_PATH}  LOGS={LOG_DIR}")


@app.get("/health")
async def health():
    return {"ok": True, "devices": roster_payload()["devices"],
            "sessions": len(clients), "ts": now_ms()}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host=HOST, port=PORT, log_level="warning")
