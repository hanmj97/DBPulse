# Read-only API smoke for isolated DBPulse SQLite LAB (port 18082).
# Must be run AFTER manually starting a local SQL Server target in the UI.
$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:18082'

# Verify the server identity before asking for any target-specific data.
$health = Invoke-RestMethod -Uri "$base/api/v1/health" -Method Get -TimeoutSec 10
if ($health.service -ne 'DBPulse' -or $health.status -ne 'UP') {
    throw 'LIVE_LAB_SERVER_IDENTITY_MISMATCH'
}
Write-Host 'SQLITE_LIVE_HEALTH_OK'

$targets = @(Invoke-RestMethod -Uri "$base/api/v1/targets" -Method Get -TimeoutSec 10)
$active = $targets | Where-Object { $_.enabled -eq $true -and $_.collectorStatus -eq 'ONLINE' } | Select-Object -First 1
if ($null -eq $active) {
    throw 'SQLITE_LIVE_TARGET_NOT_ONLINE_WAIT_OR_CHECK_DB_PERMISSIONS'
}
$id = [uri]::EscapeDataString([string]$active.id)
$prefix = "$base/api/v1/targets/$id"
Write-Host 'SQLITE_LIVE_MSSQL_COLLECTOR_ONLINE'

# New DMV samples should exist after at least 20-40 seconds of collection.
$metrics = @(Invoke-RestMethod -Uri "$prefix/metrics?limit=60" -Method Get -TimeoutSec 10)
if ($metrics.Count -eq 0) { throw 'SQLITE_LIVE_METRIC_HISTORY_EMPTY' }
Write-Host ('SQLITE_LIVE_METRIC_API_OK count=' + $metrics.Count)

# These are historical GET requests. No new DB monitoring actions are issued.
$blocking = @(Invoke-RestMethod -Uri "$prefix/blocking-chains/history?limit=20" -Method Get -TimeoutSec 10)
Write-Host ('SQLITE_LIVE_BLOCKING_API_OK count=' + $blocking.Count)
$waits = @(Invoke-RestMethod -Uri "$prefix/waits/history?limit=20" -Method Get -TimeoutSec 10)
Write-Host ('SQLITE_LIVE_WAIT_API_OK count=' + $waits.Count)
$deadlocks = @(Invoke-RestMethod -Uri "$prefix/deadlocks?limit=20" -Method Get -TimeoutSec 10)
Write-Host ('SQLITE_LIVE_DEADLOCK_API_OK count=' + $deadlocks.Count)
$incidents = @(Invoke-RestMethod -Uri "$prefix/incidents?limit=30" -Method Get -TimeoutSec 10)
Write-Host ('SQLITE_LIVE_INCIDENT_API_OK count=' + $incidents.Count)
Write-Host 'SQLITE_LIVE_READ_ONLY_API_SMOKE_PASSED'
