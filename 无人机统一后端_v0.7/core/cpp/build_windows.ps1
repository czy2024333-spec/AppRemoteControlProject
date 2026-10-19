param([string]$OutDir = (Join-Path $PSScriptRoot 'build'))
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$buildPath = [IO.Path]::GetFullPath($OutDir)
New-Item -ItemType Directory -Path $buildPath -Force | Out-Null
if (-not (Get-Command cl.exe -ErrorAction SilentlyContinue)) {
    $locator = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
    if (-not (Test-Path -LiteralPath $locator)) { throw 'Install Visual Studio C++ Build Tools first.' }
    $installation = & $locator -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    if (-not $installation) { throw 'Visual Studio x64 C++ toolchain not found.' }
    $devShell = Join-Path $installation 'Common7/Tools/Launch-VsDevShell.ps1'
    & $devShell -VsInstallationPath $installation -Arch amd64 -HostArch amd64 -SkipAutomaticLocation
}
if ($env:VSCMD_ARG_TGT_ARCH -ne 'x64') { throw 'Use an x64 Developer PowerShell, or a normal PowerShell with no compiler environment.' }
$common = @('/nologo', '/std:c++17', '/EHsc', '/W4', '/WX', '/O2', '/MD', '/utf-8', "/I$projectRoot/include")
Push-Location -LiteralPath $buildPath
try {
    $compilerLog = & cl.exe @common /c "$projectRoot/src/drone_core.cpp" /Fodrone_core.obj 2>&1
    $compilerLog | Out-File -LiteralPath 'compile.log' -Encoding utf8
    if ($LASTEXITCODE -ne 0) { throw ($compilerLog -join "`n") }
    $threeLog = & cl.exe @common /c "$projectRoot/src/drone_core_3d.cpp" /Fodrone_core_3d.obj 2>&1
    $threeLog | Out-File -LiteralPath 'compile.log' -Append -Encoding utf8
    if ($LASTEXITCODE -ne 0) { throw ($threeLog -join "`n") }
    $libraryLog = & lib.exe /nologo /OUT:drone_core.lib drone_core.obj drone_core_3d.obj 2>&1
    $libraryLog | Out-File -LiteralPath 'compile.log' -Append -Encoding utf8
    if ($LASTEXITCODE -ne 0) { throw ($libraryLog -join "`n") }
    $dllLog = & cl.exe @common /LD /DDC_BUILD_DLL "$projectRoot/src/drone_core_c.cpp" "$projectRoot/src/drone_core_3d_c.cpp" drone_core.lib /Fedrone_core_c.dll 2>&1
    $dllLog | Out-File -LiteralPath 'compile.log' -Append -Encoding utf8
    if ($LASTEXITCODE -ne 0) { throw ($dllLog -join "`n") }
    $testsLog = & cl.exe @common "$projectRoot/tests/core_tests.cpp" "$projectRoot/src/drone_core_c.cpp" drone_core.lib /Fecore_tests.exe 2>&1
    $testsLog | Out-File -LiteralPath 'compile.log' -Append -Encoding utf8
    if ($LASTEXITCODE -ne 0) { throw ($testsLog -join "`n") }
    $schedulerLog = & cl.exe @common "$projectRoot/tests/scheduler_tests.cpp" drone_core.lib /Fescheduler_tests.exe 2>&1
    $schedulerLog | Out-File -LiteralPath 'compile.log' -Append -Encoding utf8
    if ($LASTEXITCODE -ne 0) { throw ($schedulerLog -join "`n") }
    $threeTests = & cl.exe @common "$projectRoot/tests/core_3d_tests.cpp" "$projectRoot/src/drone_core_3d_c.cpp" drone_core.lib /Fecore_3d_tests.exe 2>&1
    $threeTests | Out-File -LiteralPath 'compile.log' -Append -Encoding utf8
    if ($LASTEXITCODE -ne 0) { throw ($threeTests -join "`n") }
    $environmentCompile = & cl.exe @common "$projectRoot/tests/environment_tests.cpp" "$projectRoot/src/drone_core_3d_c.cpp" drone_core.lib /Feenvironment_tests.exe 2>&1
    $environmentCompile | Out-File -LiteralPath 'compile.log' -Append -Encoding utf8
    if ($LASTEXITCODE -ne 0) { throw ($environmentCompile -join "`n") }
    $demoLog = & cl.exe @common "$projectRoot/examples/core_demo.cpp" drone_core.lib /Fecore_demo.exe 2>&1
    $demoLog | Out-File -LiteralPath 'compile.log' -Append -Encoding utf8
    if ($LASTEXITCODE -ne 0) { throw ($demoLog -join "`n") }
    $cLog = & cl.exe /nologo /DDC_USE_DLL /W4 /WX /O2 /MD /utf-8 "/I$projectRoot/include" "/Tc$projectRoot/examples/c_api_smoke.c" /Fec_api_smoke.exe /link drone_core_c.lib 2>&1
    $cLog | Out-File -LiteralPath 'compile.log' -Append -Encoding utf8
    if ($LASTEXITCODE -ne 0) { throw ($cLog -join "`n") }
    $testOutput = & './core_tests.exe' 2>&1
    $testOutput | Out-File -LiteralPath 'test.log' -Encoding utf8
    $testOutput | Write-Output
    if ($LASTEXITCODE -ne 0) { throw 'C++ tests failed. See test.log.' }
    $schedulerOutput = & './scheduler_tests.exe' 2>&1
    $schedulerOutput | Out-File -LiteralPath 'scheduler_test.log' -Encoding utf8
    $schedulerOutput | Write-Output
    if ($LASTEXITCODE -ne 0) { throw 'Scheduler tests failed.' }
    $threeOutput = & './core_3d_tests.exe' 2>&1
    $threeOutput | Out-File -LiteralPath '3d_test.log' -Encoding utf8
    $threeOutput | Write-Output
    if ($LASTEXITCODE -ne 0) { throw '3D core tests failed.' }
    $environmentOutput = & './environment_tests.exe' 2>&1
    $environmentOutput | Out-File -LiteralPath 'environment_test.log' -Encoding utf8
    $environmentOutput | Write-Output
    if ($LASTEXITCODE -ne 0) { throw 'Environment tests failed.' }
    $demoOutput = & './core_demo.exe' 'core_demo.csv' 2>&1
    $demoOutput | Out-File -LiteralPath 'demo.log' -Encoding utf8
    $demoOutput | Write-Output
    if ($LASTEXITCODE -ne 0) { throw 'C++ demo failed. See demo.log.' }
    $cOutput = & './c_api_smoke.exe' 2>&1
    $cOutput | Out-File -LiteralPath 'c_api_smoke.log' -Encoding utf8
    $cOutput | Write-Output
    if ($LASTEXITCODE -ne 0) { throw 'Native C DLL consumer failed.' }
    Write-Output "Built and verified x64 library, DLL, tests and demo in $buildPath"
} finally { Pop-Location }
