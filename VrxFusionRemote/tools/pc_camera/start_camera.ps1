param([int]$Camera=0,[int]$Port=8768,[switch]$TestPattern,[switch]$InstallDependencies,
    [string]$PackageIndex='https://mirrors.aliyun.com/pypi/simple/')
$ErrorActionPreference='Stop'
$cameraPython=Join-Path $env:USERPROFILE '.workbuddy-ai\binaries\python\envs\rotorpy-day1\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $cameraPython)) { $cameraPython=(Get-Command python -ErrorAction Stop).Source }
$cameraDeps=Join-Path $PSScriptRoot '.deps'
& $cameraPython (Join-Path $PSScriptRoot 'check_dependencies.py')
if ($LASTEXITCODE -ne 0) {
    if (-not $InstallDependencies) { throw '首次使用请加 -InstallDependencies，或双击 启动电脑摄像头.bat。依赖只安装到本组件.deps目录。' }
    & $cameraPython -m pip install --index-url $PackageIndex --target $cameraDeps -r (Join-Path $PSScriptRoot 'requirements.txt')
    if ($LASTEXITCODE -ne 0) { throw '摄像头依赖安装失败，请检查网络后重试。' }
}
$cameraArgs=@((Join-Path $PSScriptRoot 'camera_server.py'),'--host','0.0.0.0','--port',"$Port",'--camera',"$Camera")
if ($TestPattern) { $cameraArgs+='--test-pattern' }
& $cameraPython @cameraArgs
exit $LASTEXITCODE
