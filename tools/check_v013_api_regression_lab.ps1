<#
DBPulse v0.13 local read-only API smoke test.
No changes to SQL Server, containers or monitoring configuration.
Outputs only route names and HTTP statuses. Does not print raw SQL,
customer data, query identifiers, connection strings or credentials.
#>
param(
    [string]$ApiBase = 'http://127.0.0.1:18081',
    [string]$TargetId = 'local-mssql'
)
$ErrorActionPreference = 'Stop'

# Block accidental execution against a remote SQL monitoring service.
$uriBase = [Uri]$ApiBase
if ($uriBase.Scheme -ne 'http' -or $uriBase.Host -notin @('127.0.0.1','localhost')) {
    throw 'Use loopback HTTP backend only. Production API testing is not permitted.'
}

$prefix = $ApiBase.TrimEnd('/') + '/api/v1'
$target = '/targets/' + [Uri]::EscapeDataString($TargetId)
$checks = @(
    @{Name='Health'; Path='/health'},
    @{Name='Targets'; Path='/targets'},
    @{Name='5s DMV metrics'; Path="$target/metrics?limit=5"},
    @{Name='Blocking live'; Path="$target/blocking-chains"},
    @{Name='Blocking history'; Path="$target/blocking-chains/history?limit=5"},
    @{Name='Deadlock status'; Path="$target/deadlocks/status"},
    @{Name='Deadlock history'; Path="$target/deadlocks?limit=5"},
    @{Name='Waits live'; Path="$target/waits"},
    @{Name='Waits history'; Path="$target/waits/history?limit=5"},
    @{Name='Incidents'; Path="$target/incidents?limit=5"},
    @{Name='Query Store state'; Path="$target/query-performance/status"},
    @{Name='Query Store list'; Path="$target/query-performance?limit=5"},
    @{Name='Query tuning advisor'; Path="$target/query-performance/advisor?limit=5"},
    @{Name='Observer overhead'; Path="$target/query-performance/overhead"},
    @{Name='Optional instance CPU'; Path="$target/instance-cpu"}
)

$results = @()
foreach($item in $checks){
    # Read-only GET. Content is discarded, not printed.
    try {
        $response = Invoke-WebRequest -Uri ($prefix + $item.Path) -Method Get -UseBasicParsing -TimeoutSec 20 -ErrorAction Stop
        $results += [pscustomobject]@{Check=$item.Name;HttpCode=[int]$response.StatusCode;Passed=([int]$response.StatusCode -eq 200)}
    } catch {
        $httpCode = $null
        if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
            $httpCode = [int]$_.Exception.Response.StatusCode
        }
        $results += [pscustomobject]@{Check=$item.Name;HttpCode=$httpCode;Passed=$false}
    }
}

# The overhead endpoint reads only an in-memory state snapshot.
$policy = 'UNAVAILABLE'
$mode = 'UNAVAILABLE'
$interval = $null
try {
    $guard = Invoke-RestMethod -Uri ($prefix + $target + '/query-performance/overhead') -Method Get -TimeoutSec 15 -ErrorAction Stop
    $policy = $guard.policy
    $mode = $guard.mode
    $interval = $guard.effectiveIntervalMs
} catch {
    # HTTP endpoint status is already recorded in $results.
}

$results | Format-Table -AutoSize
Write-Host ("Policy: {0} / Mode: {1} / Effective ms: {2}" -f $policy,$mode,$interval)
$ok = @($results | Where-Object { -not $_.Passed }).Count -eq 0
$guardSafe = $policy -eq 'ADAPTIVE'
Write-Host ("HTTP regression passed: {0}" -f $ok)
Write-Host ("Adaptive protection enabled: {0}" -f $guardSafe)
if (-not $ok -or -not $guardSafe) {
    throw 'API regression gate NOT passed. Investigate failures before merging.'
}
Write-Host 'Read-only API smoke gate passed. Continue to UI/scenario regression.'
