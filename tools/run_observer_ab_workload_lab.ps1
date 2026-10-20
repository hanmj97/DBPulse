<#
DBPulseLab local-only synthetic A/B workload.
Runs read-only parameterized queries using Windows integrated authentication.
Execute separately for A=fixed and B=adaptive, with the XE START/END window.
Do not run against production. No credentials or query text are exported.
#>
param(
    [Parameter(Mandatory=$true)]
    [ValidateSet('A','B')]
    [string]$Mode,

    # Keep 10-minute windows identical for both modes.
    [ValidateRange(1,30)]
    [int]$Minutes = 10,

    # Query pacing is fixed and reproducible; not maximum-load benchmarking.
    [ValidateRange(100,5000)]
    [int]$IntervalMs = 500,

    # Local SQL Server and DBPulse API addresses.
    [string]$Server = '127.0.0.1,1433',
    [string]$BaseUrl = 'http://127.0.0.1:18081/api/v1/targets/local-mssql'
)

$ErrorActionPreference = 'Stop'
$connection = $null
$command = $null

# Read the cached JVM status; this endpoint issues no MSSQL request.
$before = Invoke-RestMethod "$BaseUrl/query-performance/overhead"
if ($Mode -eq 'A' -and $before.policy -ne 'FIXED_BASELINE_LAB_ONLY') {
    throw 'A needs DBPULSE_QUERY_ANALYTICS_ADAPTIVE_ENABLED=false and a rebuilt backend.'
}
if ($Mode -eq 'B' -and
    ($before.policy -ne 'ADAPTIVE' -or $before.mode -ne 'THROTTLED')) {
    throw 'B needs ADAPTIVE + THROTTLED before START. Do not force a throttle.'
}
if ($before.baseIntervalMs -ne 60000) {
    throw 'A/B requires a base interval of 60000ms.'
}

$elapsedMs = New-Object 'System.Collections.Generic.List[double]'
$modeViolations = 0
$apiErrors = 0
$lastStatusCheck = [datetime]::MinValue
$startTime = [datetime]::UtcNow
$endTime = $startTime.AddMinutes($Minutes)
$attempted = 0

try {
    # Integrated Security avoids putting a SQL password into scripts or history.
    # TrustServerCertificate is appropriate ONLY for the isolated local SQL lab.
    $cs = "Server=$Server;Database=DBPulseLab;Integrated Security=True;Encrypt=True;TrustServerCertificate=True;Application Name=DBPulseLabWorkload;Connect Timeout=5"
    $connection = New-Object System.Data.SqlClient.SqlConnection($cs)
    $connection.Open()
    $command = $connection.CreateCommand()
    $command.CommandTimeout = 5

    # Uses lab-only dbo.Accounts populated by sql/03_prepare_load_test_data.sql.
    # Parameterized SQL prevents generating different ad-hoc statement texts.
    $command.CommandText = 'SELECT COUNT_BIG(*) FROM dbo.Accounts WHERE account_id BETWEEN @lower AND @upper'
    $lower = $command.Parameters.Add('@lower',[System.Data.SqlDbType]::Int)
    $upper = $command.Parameters.Add('@upper',[System.Data.SqlDbType]::Int)

    while ([datetime]::UtcNow -lt $endTime) {
        $now = [datetime]::UtcNow
        if (($now - $lastStatusCheck).TotalSeconds -ge 15) {
            try {
                # Verify mode continuously, not only at END.
                $current = Invoke-RestMethod "$BaseUrl/query-performance/overhead"
                if (($Mode -eq 'B' -and ($current.policy -ne 'ADAPTIVE' -or $current.mode -ne 'THROTTLED')) -or
                    ($Mode -eq 'A' -and ($current.policy -ne 'FIXED_BASELINE_LAB_ONLY' -or $current.mode -ne 'NORMAL'))) {
                    $modeViolations++
                }
            } catch {
                $apiErrors++
            }
            $lastStatusCheck = $now
        }

        # Each iteration reads an approximately equal indexed range.
        $offset = $attempted % 500
        $lower.Value = [int](1000 + $offset)
        $upper.Value = [int](2000 + $offset)
        $watch = [System.Diagnostics.Stopwatch]::StartNew()
        [void]$command.ExecuteScalar()
        $watch.Stop()
        $elapsedMs.Add($watch.Elapsed.TotalMilliseconds)
        $attempted++

        # Pace from the beginning so the two runs use comparable throughput.
        $next = $startTime.AddMilliseconds($attempted * $IntervalMs)
        $sleepMs = [int]($next - [datetime]::UtcNow).TotalMilliseconds
        if ($sleepMs -gt 0) { Start-Sleep -Milliseconds $sleepMs }
    }
}
finally {
    if ($null -ne $command) { $command.Dispose() }
    if ($null -ne $connection) { $connection.Dispose() }
}

# Re-read process-lifetime counters; invalid if the backend restarted.
$after = Invoke-RestMethod "$BaseUrl/query-performance/overhead"
if ($after.totalCollectorAttempts -lt $before.totalCollectorAttempts -or
    $after.skippedCycles -lt $before.skippedCycles) {
    throw 'Backend counters reset: a JVM restart invalidated the window.'
}

$values = @($elapsedMs | Sort-Object)
$p95Index = [Math]::Max(0,[int][Math]::Ceiling($values.Count * 0.95) - 1)
$meanMs = ($elapsedMs | Measure-Object -Average).Average

$report = [pscustomobject]@{
    Mode = $Mode
    PolicyAtStart = $before.policy
    PolicyAtEnd = $after.policy
    ModeAtStart = $before.mode
    ModeAtEnd = $after.mode
    StartedUtc = $startTime.ToString('o')
    EndedUtc = ([datetime]::UtcNow).ToString('o')
    WorkloadExecutions = $attempted
    MeanClientLatencyMs = [Math]::Round($meanMs, 3)
    P95ClientLatencyMs = [Math]::Round($values[$p95Index], 3)
    SuccessfulCollectorDelta = $after.successfulCollections - $before.successfulCollections
    FailedCollectorDelta = $after.failedCollections - $before.failedCollections
    SkippedCycleDelta = $after.skippedCycles - $before.skippedCycles
    JdbcWallMsDelta = $after.cumulativeJdbcWallMs - $before.cumulativeJdbcWallMs
    ModeViolationSamples = $modeViolations
    ApiStatusErrors = $apiErrors
    ComparisonEligible = ($modeViolations -eq 0 -and $apiErrors -eq 0 -and
        (($Mode -eq 'A' -and $after.policy -eq 'FIXED_BASELINE_LAB_ONLY') -or
         ($Mode -eq 'B' -and $after.policy -eq 'ADAPTIVE' -and $after.mode -eq 'THROTTLED')))
}

# Write aggregate-only evidence OUTSIDE the git checkout.
$out = Join-Path $env:TEMP ("DBPulse_AB_{0}.json" -f $Mode)
$report | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 $out
$report | Format-List
Write-Host ("Aggregate saved to: {0}" -f $out)
