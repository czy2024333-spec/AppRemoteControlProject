# 电脑摄像头局域网测试组件

本工具独立于飞控后端。双击 `启动电脑摄像头.bat`，首次会在本目录`.deps`安装依赖，成功后复制窗口中的访问码到APP。

- 画面URL：`http://电脑局域网IPv4:8768/video.mjpeg`
- 访问码由每次启动随机生成，使用Authorization请求头，不放在URL中。
- 默认镜头0、640×480、15fps；按Ctrl+C结束。
- 只预览传输，不录音录像，不调用飞控接口。
- APP中的来源Token仍是原飞控Token，不能拿来填摄像头访问码。
- 首次安装使用公共PyPI镜像；可用`-PackageIndex https://pypi.org/simple/`切换源。

PowerShell启动：`powershell -NoProfile -ExecutionPolicy Bypass -File .\start_camera.ps1 -InstallDependencies`

没有摄像头时加`-TestPattern`，画面会明确标记合成测试图，不算硬件验收。
更换镜头编号用`-Camera 1`。如果打不开，请确认Windows“隐私和安全性→相机→允许桌面应用访问相机”，并关闭其他占用镜头的程序。

底层也可直接运行：`python camera_server.py --host 0.0.0.0 --port 8768`。相机依赖需先装到`.deps`或当前Python环境。
本机协议自测：`python test_camera_server.py`；测试使用合成图与模拟相机，不读取真实摄像头。
