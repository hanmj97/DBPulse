# Phase 15: read-only health/UI/registry checks for the opted-in Windows EXE.
# No MSSQL queries, database changes, Docker operations or secret output.
$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:18081'
$data = Join-Path $env:LOCALAPPDATA 'DBPulse\data\dbpulse.sqlite'
$marker = Join-Path $env:LOCALAPPDATA 'DBPulse\desktop\sqlite-mode.enabled'
if (-not (Test-Path -LiteralPath $data -PathType Leaf)) { throw 'SQLITE_DESKTOP_DATA_FILE_MISSING' }
if (-not (Test-Path -LiteralPath $marker -PathType Leaf)) { throw 'SQLITE_DESKTOP_MODE_NOT_SELECTED' }

# The built-in application health page must reply on the regular EXE port.
$health = Invoke-RestMethod -Uri "$base/api/v1/health" -TimeoutSec 10
if ($health.service -ne 'DBPulse' -or $health.status -ne 'UP') {
    throw 'SQLITE_DESKTOP_HEALTH_BAD_RESPONSE'
}
Write-Host 'DBPULSE_NATIVE_EXE_HEALTH_OK'

# Avoid echoing SQL Server login names or other metadata to the console.
$registry = @(Invoke-RestMethod -Uri "$base/api/v1/local-targets" -TimeoutSec 10)
if ($registry.Count -lt 1) { throw 'SQLITE_DESKTOP_REGISTERED_TARGET_MISSING' }
Write-Host ('DBPULSE_NATIVE_EXE_REGISTRY_OK: ' + $registry.Count)

# The React production assets must also be present on the regular app port.
$page = Invoke-WebRequest -Uri "$base/" -UseBasicParsing -TimeoutSec 10
if ($page.StatusCode -ne 200 -or $page.Content -notmatch '<title>DBPulse') {
    throw 'SQLITE_DESKTOP_REACT_HTML_MISSING'
}
Write-Host 'DBPULSE_NATIVE_EXE_REACT_OK'
Write-Host 'PASS: SQLITE_DESKTOP_API_SMOKE'
Write-Host 'NOTE: Verify PostgreSQL is stopped separately; health checks cannot prove backing storage.'
