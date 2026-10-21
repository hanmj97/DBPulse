# Run in a SECOND PowerShell while run_dbpulse_sqlite_preview.ps1 is running.
# Only HTTP GET operations. Does not start any collector or modify SQL/SQLite.
$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:18082'

# Verify the isolated service responds as DBPulse, not another app.
$health = Invoke-RestMethod -Uri "$base/api/v1/health" -Method Get -TimeoutSec 10
if ($health.service -ne 'DBPulse' -or $health.status -ne 'UP') {
    throw 'SQLITE_PREVIEW_HEALTH_UNEXPECTED'
}
Write-Host 'SQLITE_PREVIEW_HEALTH_OK'
Write-Host ('APP_VERSION: ' + $health.version)

# Verify the copied local registry is read through Spring/JDBC.
$registered = @(Invoke-RestMethod -Uri "$base/api/v1/local-targets" -Method Get -TimeoutSec 10)
Write-Host ('SQLITE_PREVIEW_REGISTRY_API_OK count=' + $registered.Count)

# Verify production React HTML is served from the same isolated origin.
$page = Invoke-WebRequest -Uri "$base/" -Method Get -UseBasicParsing -TimeoutSec 10
if ($page.StatusCode -ne 200 -or $page.Content -notmatch '<html') {
    throw 'SQLITE_PREVIEW_REACT_ASSET_FAILED'
}
Write-Host 'SQLITE_PREVIEW_REACT_HTML_OK'
Write-Host 'NO_MSSQL_COLLECTOR_ACTIVATED_IN_THIS_TEST'
