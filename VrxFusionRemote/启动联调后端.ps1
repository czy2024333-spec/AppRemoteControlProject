param(
    [ValidateSet('independent','unified','single','cluster')][string]$Mode = 'independent',
    [ValidateRange(1,65535)][int]$Port = 8765,
    [string]$BackendRoot = (Join-Path $PSScriptRoot '..\无人机独立操控后端_v0.9'),
    [string]$Python = 'C:\Users\24107\.workbuddy-ai\binaries\python\envs\rotorpy-day1\Scripts\python.exe'
)
$ErrorActionPreference = 'Stop'
# 使用完整路径，避免在用户主目录执行时找不到入口；不会关闭占用端口的其他进程。
$entry = Join-Path $BackendRoot 'run_backend.py'
if (!(Test-Path -LiteralPath $entry)) { throw '找不到后端。请将v0.9后端解压到APP源码目录的同级，或通过 -BackendRoot 指定含run_backend.py的目录。' }
if (!(Test-Path -LiteralPath $Python)) { throw '找不到RotorPy解释器，请通过 -Python 指定已安装RotorPy的Python路径。' }
# 默认三机独立模式，只调用用户原后端入口，不修改后端配置或强行结束已有服务。
Write-Host "启动 $Mode 服务，端口 $Port；三机独立控制应使用independent模式。手机地址请使用电脑IPv4。Ctrl+C停止。"
& $Python $entry --mode $Mode --host 0.0.0.0 --port $Port
if ($LASTEXITCODE -ne 0) { throw '后端启动失败，请查看上方错误（例如端口占用或缺少依赖）。' }
