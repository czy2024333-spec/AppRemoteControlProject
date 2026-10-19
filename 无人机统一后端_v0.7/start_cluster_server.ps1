param([string]$Python='python',[int]$Port=8765)
Set-Location -LiteralPath $PSScriptRoot
& $Python run_backend.py --mode cluster --host 0.0.0.0 --port $Port
exit $LASTEXITCODE
