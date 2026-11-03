# 无人机统一后端 v0.8

本次只升级后端。一个地址、一个8765端口同时服务原单机与集群，新增设备目录、能力发现、统一状态及回执。前端v0.7.8原包继续使用，无需安装本聊天产生的新APP。

先阅读《接口统一对接说明_v1.0.md》。前端评审稿是设计参考，里面“本聊天负责前端”等背景说明不改变本轮后端范围；原稿JSON不是当前写控制报文。

## 在这台电脑启动

先关闭占用8765的旧后端窗口，再在PowerShell执行：

```powershell
cd "C:\Users\24107\Documents\Codex\2026-10-11\new-chat\outputs\无人机统一后端_v0.8_接口统一"
& "C:\Users\24107\.workbuddy-ai\binaries\python\envs\rotorpy-day1\Scripts\python.exe" run_backend.py --mode unified --host 0.0.0.0 --port 8765
```

默认启动3架开发实例，主设备sim-drone-1。手机与电脑同一局域网，APP连接地址填`http://电脑当前IPv4:8765`；单机和集群监控都填同一地址。APP Token仍为`demo-app-token-2026`。0.0.0.0是监听地址，不能填进手机。

如从VSCode启动：打开本目录，选择已有rotorpy-day1解释器，在运行和调试中选择“v0.8 统一后端（单机+集群同地址）”。

单机原始部署仍可`--mode single`，原集群部署仍可`--mode cluster`；这两个兼容模式分别只有原协议。需要全部统一路径时使用默认unified模式。不要同时启动多个服务占用同一端口。

## 只读检查

```powershell
$headers = @{Authorization='Bearer demo-app-token-2026'}
Invoke-RestMethod http://127.0.0.1:8765/api/v1/capabilities -Headers $headers
Invoke-RestMethod http://127.0.0.1:8765/api/v1/devices -Headers $headers
Invoke-RestMethod http://127.0.0.1:8765/api/v1/state -Headers $headers
```

这些检查不会申请控制。申请单机后再选择集群会按控制对象冲突规则拒绝，不能靠换页面自动抢占；详见对接说明。

网页调试时明确添加来源，例如`--allow-origin http://localhost:5173`；手机原生APP不需要这个参数，不使用通配符。

## 后端自检与回归

用同一个解释器运行：

```powershell
python run_backend.py --doctor
python -m unittest test_unified -v
python run_checks.py
```

以上`python`须为已经激活的rotorpy-day1解释器；未激活时继续使用上面的完整解释器路径。测试使用临时端口，不接管你的8765服务。

交付含源码、统一接口说明、OpenAPI、实际响应样例与验证报告。来源Token仍是开发认证，正式用户角色尚未接入；电量/GNSS缺失值、未接入UE/硬件、未实现任务均明确标识。4小时、实体硬件和手机局域网性能仍需联合验收。
