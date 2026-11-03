# 在 VS Code 中运行和调试

请打开整个 `rotorpy_day1` 文件夹，让VS Code读取其中的 `.vscode` 配置。本机已检测到Microsoft Python和Python Debugger扩展目录；若编辑器提示扩展未启用，请在扩展面板检查当前配置文件的启用状态。

本机专用解释器路径已经写入每个调试配置。代码分析解释器若仍显示其他环境，按Ctrl+Shift+P执行 `Python: Select Interpreter`，选择 `C:/Users/24107/.workbuddy-ai/binaries/python/envs/rotorpy-day1/Scripts/python.exe`。迁移到其他电脑时需要修改 `.vscode/settings.json`和 `launch.json`中的解释器路径。

按Ctrl+Shift+D打开“运行和调试”，在顶部下拉列表选择入口，然后按F5：

| 配置 | 作用 | 主要结果 |
|---|---|---|
| 1 官方RotorPy圆轨迹实验 | 官方20s圆轨迹与正弦风仿真基线 | results/official/summary.json、curves.png、basic_usage.csv |
| 2 新核心 | 用官方RotorPy模型验证新增升降/停止/保护接口 | results/core/summary.json、states.jsonl |
| 3 Python单元测试 | 验证核心数值及保护规则 | 终端OK；历史输出检查默认跳过 |
| 4 C++ DLL对照 | 先构建并测试C++，再用相同输入与RotorPy对照 | results/cpp/summary.json、comparison.csv |

配置1运行官方示例，不经过新增的SimulationCore接口。配置2/4的输入场景是项目自写的测试，输出为仿真数据，不是官方实机测量数据。配置4可调试Python包装和对照逻辑，当前没有配置C++源码的原生断点调试。独立C++二进制仍为优化构建。

要观察新核心，请选择配置2，再打开 `simulation_core.py`。搜索 `【输入入口】`、`【超时断点】`和 `【提交状态】`，点击相应可执行语句左侧的行号边栏添加红点。不要把断点放在注释行。按F5后会在断点暂停；F10逐过程，F11进入函数，F5继续，Shift+F5停止。

在变量面板展开self；或者在WATCH添加 `self.time_s`、`self._state['x'][2]`、`self._state['v'][2]`、`self._target[2]`、`self._mode`和 `self._expires_at`。首次输入为+0.3m/s，4s开始下降为-0.3m/s，5.4s进入INPUT_TIMEOUT保护。若每一步都暂停，先取消step内断点，只保留超时分支，可直接看到保护发生。

配置1启用进入第三方代码，便于观察官方模型；配置2默认逐过程跳过第三方代码。需要深入SE3Control或Multirotor时可把该配置的justMyCode改为false。

官方曲线可直接在资源管理器中点击PNG打开。复制命令运行时也可使用项目原有脚本。调试配置使用集成终端，完成后进程退出是正常现象，仿真时间不等于真实等待时间。

配置依据 [VS Code官方Python调试文档](https://code.visualstudio.com/docs/python/debugging)，使用debugpy调试器与显式python解释器配置。
