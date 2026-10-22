# Phase 17 Windows WebView2 localhost diagnostic. Read-only; does not kill programs.
# Prints no DB credentials, SQL statements, .env, process command line or DB data.
$ErrorActionPreference = 'Stop'
$port = 18081
$base = 'http://127.0.0.1:18081'
$listeners = @(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)

if ($listeners.Count -eq 0) {
    Write-Host 'PORT_18081_FREE'
    Write-Host 'NEXT: Close the old error window and open the NEW DBPulse.WindowHost.exe.'
    return
}

Write-Host 'PORT_18081_LISTENING'
foreach ($pidValue in @($listeners | Select-Object -ExpandProperty OwningProcess -Unique)) {
    $proc = Get-Process -Id $pidValue -ErrorAction SilentlyContinue
    if ($proc) {
        Write-Host ('LISTENING_PROCESS_ID: ' + $proc.Id)
        Write-Host ('LISTENING_PROCESS_NAME: ' + $proc.ProcessName)
    }
    else {
        Write-Host 'LISTENING_PROCESS_NOT_ACCESSIBLE'
    }
}

# Do not use a system proxy to test localhost; do not follow redirects.
Add-Type -AssemblyName System.Net.Http
$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect = $false
$handler.UseProxy = $false
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(4)
try {
    try {
        $health = $client.GetAsync("$base/api/v1/health").GetAwaiter().GetResult()
        Write-Host ('HEALTH_HTTP_STATUS: ' + [int]$health.StatusCode)
        $contentType = if ($health.Content.Headers.ContentType) {
            $health.Content.Headers.ContentType.MediaType
        } else { '(missing)' }
        Write-Host ('HEALTH_CONTENT_TYPE: ' + $contentType)
        if ($health.IsSuccessStatusCode -and $contentType -eq 'application/json') {
            $raw = $health.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            if ($raw.Length -le 4096) {
                $healthJson = $raw | ConvertFrom-Json
                # Display only fixed identity fields, not arbitrary metadata.
                Write-Host ('HEALTH_SERVICE_MATCH: ' + ($healthJson.service -ceq 'DBPulse'))
                Write-Host ('HEALTH_STATUS_UP: ' + ($healthJson.status -ceq 'UP'))
                Write-Host ('HEALTH_PHASE_14: ' + ($healthJson.phase -eq 14))
            }
            else {
                Write-Host 'HEALTH_RESPONSE_TOO_LARGE'
            }
        }
    }
    catch {
        Write-Host 'HEALTH_REQUEST_FAILED'
    }
    try {
        $page = $client.GetAsync($base).GetAwaiter().GetResult()
        Write-Host ('PAGE_HTTP_STATUS: ' + [int]$page.StatusCode)
        $type = if ($page.Content.Headers.ContentType) {
            $page.Content.Headers.ContentType.MediaType
        } else { '(missing)' }
        Write-Host ('PAGE_CONTENT_TYPE: ' + $type)
        if ($page.IsSuccessStatusCode -and $type -eq 'text/html') {
            $html = $page.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            Write-Host ('PAGE_DBPULSE_TITLE_MATCH: ' + (
                $html.Length -le 8192 -and $html.Contains('<title>DBPulse')
            ))
        }
    }
    catch {
        Write-Host 'PAGE_REQUEST_FAILED'
    }
}
finally {
    $client.Dispose()
    $handler.Dispose()
}
Write-Host 'NOTE: If this is a known old DBPulse console, close it normally.'
Write-Host 'NOTE: Never kill an unidentified port owner or delete SQLite data.'
