# Phase20 - temporary opt-in to view SQL text, can contain sensitive data.
param([string]$WindowHostPath = (Join-Path $env:LOCALAPPDATA 'Programs\DBPulse\window\DBPulse.WindowHost.exe'))
$ErrorActionPreference='Stop'
if (-not (Test-Path -LiteralPath $WindowHostPath -PathType Leaf)) { throw 'WINDOW_HOST_NOT_FOUND' }
# Stop existing DBPulse first; never terminate unknown processes.
if (Get-NetTCPConnection -LocalPort 18081 -State Listen -ErrorAction SilentlyContinue) {
    throw 'CLOSE_EXISTING_DBPULSE_FIRST_PORT_18081_BUSY'
}
$previous=[Environment]::GetEnvironmentVariable('DBPULSE_QUERY_TEXT_ENABLED','Process')
try {
    # This environment variable is scoped to this PowerShell process.
    $env:DBPULSE_QUERY_TEXT_ENABLED='true'
    $proc=Start-Process -FilePath (Resolve-Path -LiteralPath $WindowHostPath).Path -Wait -PassThru
    Write-Host ('DBPULSE_SQL_TEXT_SESSION_ENDED_' + $proc.ExitCode)
}
finally {
    # Never leave an unintended system or user-level opt-in enabled.
    if ($null -eq $previous) { Remove-Item Env:\DBPULSE_QUERY_TEXT_ENABLED -ErrorAction SilentlyContinue }
    else { $env:DBPULSE_QUERY_TEXT_ENABLED=$previous }
}
