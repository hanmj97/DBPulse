# Diagnose native Windows DBPulse.exe without printing or saving secrets.
# Read-only checks; never resets Postgres volumes or DPAPI credentials.
param([switch]$RunExe)

$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $root
$preview=Join-Path (Join-Path (Join-Path $root 'backend') 'target') 'windows-previews'
$exe=Get-ChildItem -Path $preview -Filter DBPulse.exe -Recurse -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
if($null -eq $exe){throw 'No DBPulse.exe preview found; build the preview app-image first.'}
Write-Host 'DBPulse executable:' $exe.FullName

# Only print whether a DPAPI blob exists. Do not decrypt its contents here.
if (-not $env:LOCALAPPDATA) {throw 'LOCALAPPDATA unavailable: use normal signed-in Windows account.'}
$credential=Join-Path (Join-Path (Join-Path $env:LOCALAPPDATA 'DBPulse') 'desktop') 'metadata.dpapi'
Write-Host 'Windows metadata DPAPI file exists:' (Test-Path -LiteralPath $credential)
Write-Host 'Backend localhost:18081 listener process IDs:'
Get-NetTCPConnection -LocalPort 18081 -State Listen -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -Unique

# Network reachability does not establish the Postgres password is correct.
$tcp=Test-NetConnection 127.0.0.1 -Port 5544 -WarningAction SilentlyContinue
Write-Host 'Existing PostgreSQL 127.0.0.1:5544 TCP reachable:' $tcp.TcpTestSucceeded

# Checks Windows current-user DPAPI can decrypt metadata; never prints secret.
if (Test-Path -LiteralPath $credential) {
    & $exe.FullName --desktop-check
    Write-Host 'DPAPI metadata check exit code:' $LASTEXITCODE
}

# This option keeps original Java exception visible in the current shell.
if($RunExe) {
    Write-Host 'Launching DBPulse.exe with console diagnostics (Ctrl+C to stop).'
    & $exe.FullName
    Write-Host 'DBPulse.exe exit code:' $LASTEXITCODE
}
$log=Join-Path (Join-Path (Join-Path $env:LOCALAPPDATA 'DBPulse') 'logs') 'desktop-startup-status.txt'
if(Test-Path -LiteralPath $log) {
    Write-Host 'Sanitized desktop startup status:'
    Get-Content -LiteralPath $log -Tail 3
}else{
    Write-Host 'Startup status log not yet created (older app-image or early JVM failure).'
}
