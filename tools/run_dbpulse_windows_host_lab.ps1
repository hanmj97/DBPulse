# DBPulse local Windows host mode (development only).
# Spring Boot runs as the signed-in Windows user for per-user DPAPI.
# PostgreSQL continues in Docker. Never delete the Postgres volume.
param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') {
    throw "Windows host mode requires Windows, not WSL or Docker."
}

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $root
$envPath = Join-Path $root '.env'
if (-not (Test-Path $envPath)) {
    throw "Missing local .env."
}

function Get-DbpulseEnvValue {
    param([string]$Key,[string]$Default = '')
    # Read whitelisted keys only; never execute .env as a PowerShell script.
    foreach ($line in [System.IO.File]::ReadAllLines($envPath)) {
        if ($line -match ('^\s*' + [regex]::Escape($Key) + '\s*=(.*)$')) {
            $value = $Matches[1].Trim()
            if ($value.Length -ge 2 -and
                (($value.StartsWith('"') -and $value.EndsWith('"')) -or
                 ($value.StartsWith("'") -and $value.EndsWith("'")))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            return $value
        }
    }
    return $Default
}

# Release port 18081 used by the legacy Docker backend, then run only PG.
docker compose stop backend
if ($LASTEXITCODE -ne 0) { throw "Could not stop the old Docker backend." }
docker compose up -d postgres
if ($LASTEXITCODE -ne 0) { throw "Could not start existing PostgreSQL." }

# Keep metadata DB password in this process environment only.
$pgPassword = Get-DbpulseEnvValue 'DBPULSE_POSTGRES_PASSWORD'
if ([string]::IsNullOrWhiteSpace($pgPassword)) {
    throw "DBPULSE_POSTGRES_PASSWORD is required in .env."
}
$env:DBPULSE_META_PASSWORD = $pgPassword
Remove-Variable pgPassword
$env:DBPULSE_META_JDBC_URL = 'jdbc:postgresql://127.0.0.1:5544/dbpulse'
$env:DBPULSE_META_USERNAME = 'dbpulse'
$env:DBPULSE_BIND_ADDRESS = '127.0.0.1'
$env:DBPULSE_API_PORT = '18081'

# Explicitly disable legacy SQL Server credentials; UI selects target.
$env:DBPULSE_MSSQL_ENABLED = 'false'
$env:DBPULSE_MSSQL_USERNAME = ''
$env:DBPULSE_MSSQL_PASSWORD = ''
$env:DBPULSE_LOCAL_TARGETS_ENABLED = 'true'
$env:DBPULSE_LOCAL_ALLOWED_DB_HOSTS = Get-DbpulseEnvValue 'DBPULSE_LOCAL_ALLOWED_DB_HOSTS' 'localhost,127.0.0.1,host.docker.internal'
$env:DBPULSE_LOCAL_TRUST_SELF_SIGNED = Get-DbpulseEnvValue 'DBPULSE_LOCAL_TRUST_SELF_SIGNED' 'false'

# Available ONLY from native Windows signed-in user, never Docker/Linux.
$env:DBPULSE_WINDOWS_DPAPI_ENABLED = 'true'

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw "Java 21 JDK/JRE is required for Windows host mode."
}
$jar = Join-Path $root 'backend\target\dbpulse-api.jar'
if (-not $SkipBuild) {
    if (-not (Get-Command mvn.cmd -ErrorAction SilentlyContinue)) {
        throw "Maven 3.9+ (mvn.cmd) is required for the development launcher."
    }
    # Backend JAR: regular Maven build, with CI tests run separately.
    Push-Location (Join-Path $root 'backend')
    try {
        mvn.cmd -B -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw "Maven backend build failed." }
    } finally {
        Pop-Location
    }
}
if (-not (Test-Path $jar)) { throw "Backend JAR not found: $jar" }

Write-Host "DBPulse Windows host API starting on http://127.0.0.1:18081"
Write-Host "Windows DPAPI belongs to this signed-in Windows user."
Write-Host "Keep this window open. Start npm.cmd run dev in frontend separately."
Write-Host "Never upload .env, encrypted DPAPI blobs, SQL logs or credentials."
# Foreground process; press Ctrl+C to stop. Windows .exe follows later.
java -jar $jar
