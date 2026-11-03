$ErrorActionPreference = 'Stop'
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location -LiteralPath $scriptRoot

python -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --reload
