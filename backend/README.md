# 后端工程 · v0.9.0

负责人 A 的当前 Python 后端。对应仓库另一侧的 [Android 前端](../VrxFusionRemote/)；协作说明见 [docs](../docs/README.md)。

## 先运行这一个入口

VS Code 打开本目录，选择已安装依赖的 Python 解释器。新环境先执行 `python -m pip install -r core/requirements.txt`，固定 RotorPy 3.0.0；完整依赖锁见 core/requirements-lock.txt。

```powershell
python run_backend.py --doctor
python run_backend.py --mode independent --host 0.0.0.0 --port 8765
```

局域网示例 `http://192.168.1.10:8765` 的地址必须换成后端电脑 IPv4。原生手机 APP 不靠 CORS 授予权限；网页开发才配置确切的 `--allow-origin`。

## 代码导航

| 文件/目录 | 作用 |
| --- | --- |
| run_backend.py、start_backend.ps1 | 启动、模式选择和配置自检 |
| independent_server.py | 当前 HTTP/SSE 路由 |
| independent_fleet.py | 逐机身份、写权限和全机状态视图 |
| app_server.py、cluster_member.py | 每架独立执行器、会话、TTL、epoch 与保护 |
| async_audit.py | 有界异步审计及失败处理 |
| core/simulation_core.py | 三维动力学、运动与边界保护 |
| cluster_config.json | 三实例位置、控制、传感器配置 |
| independent_credentials.json | 公开的逐设备开发凭据样例 |
| test_*.py、run_checks.py | 接口与安全回归 |
| run_independent_acceptance.py | 随机临时端口的三控四观察联调测试 |
| core/cpp/、core/ue/ | C++ 核心与历史 UE 桥接源码；无预编译 DLL |
| core/vendor/ | 固定官方阅读源码与许可证 |

## 日志修复（2026-11-05）

当前分支的日志接口使用后台分批索引、快照分页和有界文件导出，详见 [日志接口与修复说明](日志接口与修复说明.md)。首次历史索引或繁忙时返回明确503；损坏行可定位，默认拒绝不完整导出。

## 对接文档

- [当前后端架构逻辑与复测](后端架构逻辑说明.md) · [HUD 优化实施记录](后端HUD优化实施记录.md)
- [当前三机接口](前端对接_三机独立操控.md) · [OpenAPI](openapi_independent.json)
- [完整运行、Token 与测试说明](README_v0.9.md)
- [原交付验证报告](验证报告_v0.9.md) · [协作仓库验证摘要](../docs/validation/README.md)

原交付报告中引用的 `results/` 是本地运行证据目录，GitHub 不提交运行日志；历史精简摘要在 docs/validation。`契约样例_v0.9/` 保留来自实际联调的状态数据，不是新的验收结果。

## 配置与测试

开发样例 Token 是公开值，不作正式用户登录凭据。需要独立本机值时，复制为 `independent_credentials.local.json`，通过 `--credentials independent_credentials.local.json` 启动；该文件已被仓库忽略。

```powershell
python run_checks.py
python run_independent_acceptance.py --seconds 60 --out results/independent_acceptance_new
```

每轮使用未存在的输出目录保留证据。测试工具使用开发凭据配置列表的前三项作三路操纵、第四项作观察；沿用样例顺序。

当前每机独立控制；输入 TTL 500 ms、心跳不续期运动、保护后不自动恢复旧指令。只读和跨设备写入由服务端拒绝。旧模式与旧文档仅供兼容回归，不作为当前启动教程。
