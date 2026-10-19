param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$sourceRoot = Split-Path -Parent $PSScriptRoot
if (-not $SkipBuild) { & (Join-Path $sourceRoot 'cpp/build_windows.ps1') }
$pluginPath = Join-Path $PSScriptRoot 'DroneCoreBridge'
$includePath = Join-Path $pluginPath 'ThirdParty/Include'
$binaryPath = Join-Path $pluginPath 'ThirdParty/Win64'
New-Item -ItemType Directory -Path $includePath,$binaryPath -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $sourceRoot 'cpp/include/drone_core_c.h') -Destination $includePath -Force
Copy-Item -LiteralPath (Join-Path $sourceRoot 'cpp/include/frame_scheduler.hpp') -Destination $includePath -Force
$coreDll = Join-Path $sourceRoot 'cpp/build/drone_core_c.dll'
Copy-Item -LiteralPath $coreDll -Destination $binaryPath -Force
$manifest = [ordered]@{
    plugin = 'DroneCoreBridge'
    interface_version = 1
    platform = 'Win64'
    source_target = 'UE5'
    ue_build_validation = 'not_run_no_engine_installed'
    standalone_test_log = 'cpp/build/scheduler_test.log'
    dll_sha256 = (Get-FileHash -LiteralPath (Join-Path $binaryPath 'drone_core_c.dll') -Algorithm SHA256).Hash.ToLowerInvariant()
}
$manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $pluginPath 'prepared_manifest.json') -Encoding utf8
$sandboxPluginPath = Join-Path $PSScriptRoot 'DroneCoreSandbox/Plugins/DroneCoreBridge'
New-Item -ItemType Directory -Path $sandboxPluginPath -Force | Out-Null
foreach ($itemName in @('Source','ThirdParty','DroneCoreBridge.uplugin','prepared_manifest.json')) {
    Copy-Item -LiteralPath (Join-Path $pluginPath $itemName) -Destination $sandboxPluginPath -Recurse -Force
}
Write-Output "Prepared plugin sources + headers + x64 DLL: $pluginPath"
Write-Output "Prepared standalone project skeleton: $(Join-Path $PSScriptRoot 'DroneCoreSandbox/DroneCoreSandbox.uproject')"
Write-Output 'UE compilation and packaged-game validation have NOT been performed.'
