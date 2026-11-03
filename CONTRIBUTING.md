# 协作与提交约定

## 在对应工程修改

前端改 VrxFusionRemote/，后端改 backend/，跨端接口和联调说明改 docs/。新协作者先读 [根目录导航](README.md) 和 [分工](docs/仓库目录与分工.md)。保留已有前端工程路径，避免 Android Studio 和协作脚本失效。

从最新 main 创建自己的短期分支，建议 frontend/功能名、backend/功能名、integration/联调主题。完成后发 PR，描述具体行为、涉及端、验证结果和待验收项。跨端改动在同一 PR 说明对应接口版本与接入顺序，不把未接入字段写成已完成。

## 修改接口时

同时更新 backend/openapi_independent.json、backend/前端对接_三机独立操控.md 和必要样例；说明路径、字段、单位、坐标、权限、错误码与兼容影响。三维位置单位固定为 m，世界轴为 X 前/Y 左/Z 上；UE 显示换算在 UE 适配边界处理。

后端仍执行来源/设备/能力、会话、epoch、序号、TTL 和保护校验；前端主动切换、实际归中、显示当前来源和数据新鲜度。禁止靠重放旧运动、心跳续期运动或自动解除保护掩盖问题。

## 验证与记录

后端在 backend/ 运行 python run_checks.py；按改动范围补充三机、权限或安全验证。前端使用工程已有测试与编译入口；不将后端 SSE 频率直接当成手机 UI 刷新率。PR 中区分本次结果与历史报告，注明跳过和未验收条件。

## 文件提交范围

提交源码、依赖说明、配置样例、接口、必要的脱敏验证摘要和第三方许可证。运行日志、results/、构建输出、虚拟环境、本机路径配置、真实凭据、签名私钥及安装包不作为源码提交。

independent_credentials.json 内 demo 值为公开开发样例；实际联调凭据另存 independent_credentials.local.json 并通过 --credentials 指定。不要把真实 Token 写进 README、截图、URL、日志或 PR。APK/UE 二进制应按发布流程交付；当前仓库的历史 UE 源码不含预编译 DLL。
