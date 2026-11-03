# 旋翼遥控与无人机仿真协作仓库

当前协作基线：**Android 前端 v0.9.0 + 三机独立操控后端 v0.9.0**。2026-11-03 整理目录并补齐后端源码；本次没有升级应用功能。

## 按职责进入工程

| 负责内容 | 工程目录 | 首先阅读 | 启动/开发入口 |
| --- | --- | --- | --- |
| 前端 B：手机界面、摇杆、地图和状态展示 | [VrxFusionRemote/](VrxFusionRemote/) | [三机使用说明](VrxFusionRemote/使用与三机联调_v0.9.0.md) | Android Studio 打开此目录 |
| 后端 A：动力学、三机控制权、状态服务和安全校验 | [backend/](backend/) | [后端说明](backend/README.md) | VS Code 打开此目录；run_backend.py |
| UE C：场景画面、坐标转换和渲染接入 | 甲方/UE 端工程另行提供 | [协作分工](docs/仓库目录与分工.md) | backend/core/ue/ 仅是历史桥接源码与骨架 |
| 前后端联调、测试及交接 | [docs/](docs/) | [联调快速开始](docs/联调快速开始.md) | 统一地址 HTTP + SSE |

```text
AppRemoteControlProject/
├── VrxFusionRemote/     Android 前端，保留原工程路径
├── backend/             当前独立操控后端，含三维核心与回归用例
├── docs/                协作目录、接口入口、联调与历史验证摘要
└── CONTRIBUTING.md      提交与协作约定
```

## 当前如何联调

在仓库根目录的终端执行：

```powershell
cd backend
python run_backend.py --doctor
python run_backend.py --mode independent --host 0.0.0.0 --port 8765
```

前端工程用 Android Studio 打开 `VrxFusionRemote`；新环境依照各自说明安装依赖。三台手机和只读端填同一个 `http://后端电脑IPv4:8765`，分别选择操纵端 1/2/3 或只读观察。

每个操纵端只控制自己绑定的飞机，但所有端都能看到三机位置与状态；位置单位 m。只读凭据的写入由服务器拒绝。当前是仿真系统，尚无真实飞机/指环/语音硬件或 UE 视频验收结论。

## 接口与协作入口

- [前后端接口与三机联调](backend/前端对接_三机独立操控.md)
- [当前 OpenAPI](backend/openapi_independent.json)
- [配置样例](backend/cluster_config.json) · [公开开发凭据样例](backend/independent_credentials.json)
- [目录、负责人和历史文件说明](docs/仓库目录与分工.md)
- [提交约定](CONTRIBUTING.md) · [验证记录索引](docs/README.md)

`VrxFusionRemote/server/` 和旧 WebSocket 协议文档属于历史工具；当前三机联调只启动 `backend/` 的 independent 服务。不要同时在同一端口运行历史 server.py。

旧 single/cluster/unified 模式和旧版文档保留供兼容回归。其他分支中的 `vertical_dynamics/` 是旧竖直模块，不代替 main 中的当前三维后端。

## 验证基线

2026-10-20 后端历史验证：94 项后端测试通过，动力学 26 项通过、1 项可选历史输出检查跳过；一分钟三路操纵/四路观察通过。本次 2026-11-03 整理仅验证交付哈希、语法、配置自检和文档链接；未重跑完整功能或手机测试。前端既有验证及限制见 [前端报告](VrxFusionRemote/验证报告_v0.9.0.md)。手机局域网、实际 UI 刷新率、UE/外设和四小时稳定性仍需联合验收。
