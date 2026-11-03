# 三机独立操控后端 v0.9

2026-10-20。三个操纵端各控制一架仿真飞机，所有操纵端和只读端均可查看三架飞机的位置、姿态、速度、在线状态和保护告警。本包只更新后端，APP 需要依据《前端对接_三机独立操控.md》接入。

## 如何运行

VS Code 打开解压后的“无人机独立操控后端_v0.9”文件夹，选择已有 rotorpy-day1 解释器。在此文件夹的终端执行：

```powershell
python run_backend.py --doctor
python run_backend.py --mode independent --host 0.0.0.0 --port 8765
```

也可 F5 选择“v0.9 三端独立操控与全机观察”。启动后持续运行属于正常现象；Ctrl+C 停止。端口已占用时先确认原服务，或使用 `--port 8766`，相应客户端也改为 8766。

新电脑需要安装 core/requirements.txt。代码包不含 Python 解释器或离线依赖。

## 四种端如何连接

都连同一个后端地址，例如 `http://192.168.1.10:8765`，IP 替换为后端电脑的局域网 IPv4。手机与电脑在同一局域网，电脑防火墙允许所用端口。手机上的 localhost 指手机本身。

| 端 | 开发联调 Token | 可以操纵 | 可以观察 |
| --- | --- | --- | --- |
| 操纵端 1 | demo-pilot-1-token-2026 | sim-drone-1 | 三架全部 |
| 操纵端 2 | demo-pilot-2-token-2026 | sim-drone-2 | 三架全部 |
| 操纵端 3 | demo-pilot-3-token-2026 | sim-drone-3 | 三架全部 |
| 只读端 | demo-observer-token-2026 | 无 | 三架全部 |

Token 填入 HTTP `Authorization: Bearer <token>`。逐设备权限由后端强制检查；把只读按钮隐藏不能代替后端拒绝写入。以上为公开的开发样例凭据，不是用户登录系统；实际联调可修改 independent_credentials.json，重启加载。

浏览器网页联调时增加确切来源，例如：

```powershell
python run_backend.py --host 0.0.0.0 --allow-origin http://localhost:5173
```

原生手机 APP 不需要通过 CORS 配置赋予控制权。浏览器原生 EventSource 无法直接设置 Bearer 请求头时，应使用可携带 Authorization 的 SSE 客户端；不要把 Token 放在 URL。

## 查看与操纵

先用本端 Token GET `/api/v1/capabilities` 确认 deployment_mode 为 INDEPENDENT。GET `/api/v1/state` 获取三机快照；订阅 `/api/v1/events` 获取同结构的三机状态流，目标 25 Hz。每条成员状态携带自己的步号、时刻和新鲜度，不是三个实例的严格同步采样。

`self_device_id` 与 `members[].is_self` 用于地图突出本机。坐标共用 SIM_WORLD，X 前、Y 左、Z 上；位置单位 m，速度 m/s。初始位置分别为 [0,0,1]、[2,0,1]、[0,2,1.5]。这些是仿真场景位置，不能直接当作经纬度。

用户主动申请本机 session → 切换 APP 来源 → 实际摇杆归中 → 新一次触摸开始发送 input。观察连接不自动申请控制；自己的 Token 也不能写另一架飞机。完整报文见《前端对接_三机独立操控.md》。

## 本机验证

```powershell
python run_checks.py
python run_independent_acceptance.py --seconds 60 --out results/independent_acceptance_new
```

第二个命令在随机临时端口创建独立的三机服务，使用三路操纵与四路观察进行实测，不操纵已运行的 8765 服务。输出目录必须尚不存在，以保留每一轮证据。它使用当前独立凭据样例的前三项作为三路 APP、第四项作为只读端。

## 安全与版本边界

每架飞机有自己的会话、epoch、序号、来源切换、输入 TTL 和保护。单机退出或超时不会撤销另外两架；同一架重建会话会停止并撤销该来源旧会话，必须由用户主动发起，重新切换和归中。连续输入有效期仍为 500 ms；心跳不续期运动。

新模式把命令审计和控制事件交给有界异步队列，不等待文件写完才返回运动应答。日志为缓冲写入，约 100 ms 刷新，无 fsync 保证。队列满或文件写失败会公开失败计数、保留最近未写事件于内存，停用普通操纵并让实例进入 AUDIT_UNAVAILABLE 保护；关机排空最长等待 5 s。进程异常退出仍可能丢失未落盘日志，不能据此声称强持久审计。

旧 single、cluster、unified 模式仍可显式启动，用于旧协议回归；它们不能与 independent 模式共用同一端口或同一仿真实例。新模式不提供群控广播，不包含自主编队、避障或防碰撞算法。电量、GNSS、UE 视频和硬件验收保持未接入状态。

一分钟本机通过只证明当前开发基线，手机局域网、实际 APP 刷新率和长期稳定性仍需联合验收。详见《验证报告_v0.9.md》。
