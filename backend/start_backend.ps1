param([string]$Python = 'python', [ValidateSet('single','cluster','unified','independent')][string]$Mode = 'independent', [int]$Port = 8765, [string[]]$AllowOrigin = @(), [string]$Credentials = '')
# 统一入口先自检依赖与配置，再启动；解释器通过参数或VSCode选择，不写死其他人的本机路径。
Set-Location -LiteralPath $PSScriptRoot
$backendArgs = @('run_backend.py','--mode',$Mode,'--host','0.0.0.0','--port',"$Port")
foreach ($origin in $AllowOrigin) { $backendArgs += @('--allow-origin',$origin) }
if ($Credentials) { $backendArgs += @('--credentials',$Credentials) }
& $Python @backendArgs
exit $LASTEXITCODE
