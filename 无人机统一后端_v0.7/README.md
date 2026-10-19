# 无人机统一后端 v0.7

本包提供当前完整Python后端，包含三维动力学、APP/指环/语音输入、边界迟滞、多机集群、独立模拟测量、HTTP与SSE接口、跨域配置、自检和回归工具。

先读升级说明_v0.7.md。实现版本0.7.0；单机控制协议0.5、集群协议0.6保持兼容。APP与UE需要各自接入，本包没有重新编译手机APK或UE运行程序。

## 第一次运行

VSCode打开本目录，选择已有rotorpy-day1解释器。新电脑先建立Python环境并安装core/requirements.txt；本包不含Python、离线依赖或供应商SDK。

```powershell
python run_backend.py --doctor
python run_backend.py --mode cluster --host 0.0.0.0 --port 8765
```

单机使用 --mode single。同一端口只能启动一种服务。VSCode也可F5选择“v0.7 集群局域网服务”或“v0.7 单机局域网服务”。健康接口GET /api/health需要对应Bearer Token，响应implementation_version应为0.7.0。

```powershell
Invoke-RestMethod -Uri 'http://127.0.0.1:8765/api/health' -Headers @{ Authorization = 'Bearer demo-app-token-2026' } | ConvertTo-Json -Depth 12
```

手机使用电脑局域网IPv4。浏览器跨域白名单配置及状态示例见升级说明；默认不开跨域，不使用无鉴权接口。

## 运行验证

```powershell
python run_checks.py
python run_cluster_demo.py --seconds 5
python run_handover_demo.py
```

前一个脚本执行接口和动力学两组回归，任意失败返回非零退出码。后两个演示自启临时本机服务，分别验证多机对象切换和三来源切换，不会直接操纵另一个8765服务。

三机演示结果在results/cluster_demo_v07，单机演示在results/handover。验证报告_v0.7.md记录本版实际测试和性能，旧版报告只能作历史参考。

## 当前配置与能力

三机开发样例为3在线/3受控/3模拟测量，每架动力学50Hz、测量20Hz、集群状态流目标25Hz；单机默认100Hz。更高数量和频率需按硬件验证。边界迟滞和调度保护阈值保持生效。

接口说明：集群接口与前端对接.md、IN接口补充说明.md、前端对接说明.md。机器可读索引：openapi_cluster.json、openapi.json；共享变更见升级说明。

历史C++与UE源码保留供参考，不是本版已编译验证组件。实物SDK、起降返航任务、GNSS、航点、自主协同及ADP可配置控件系统仍未完成，详见升级说明和验证报告。
