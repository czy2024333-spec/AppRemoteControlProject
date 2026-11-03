# 协作文档入口

| 想做什么 | 阅读位置 |
| --- | --- |
| 找前端/后端/UE 负责人和文件 | [仓库目录与分工](仓库目录与分工.md) |
| 第一次三机联调 | [联调快速开始](联调快速开始.md) |
| 修改接口或解决协作冲突 | [提交与协作约定](../CONTRIBUTING.md) |
| 查看当前控制报文和状态字段 | [后端接口说明](../backend/前端对接_三机独立操控.md) |
| 导入当前接口契约 | [openapi_independent.json](../backend/openapi_independent.json) |
| 编译与使用手机 APP | [前端三机使用说明](../VrxFusionRemote/使用与三机联调_v0.9.0.md) |
| 查看已做与待做的验证 | [验证记录说明](validation/README.md) |

当前统一入口是 main 分支的 backend/，协议使用 HTTP + SSE；旧 server/、旧 WebSocket 教程和其他分支的竖直模块均有历史用途，不与当前基线混用。
