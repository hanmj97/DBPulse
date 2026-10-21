# DBPulse local Windows host mode (development only).
# Spring Boot runs as the signed-in Windows user for per-user DPAPI.
# PostgreSQL continues in Docker. Never delete the Postgres volume.
param([switch]$SkipBuild, [switch]$BundledUi, [string]$JarPath)

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

# Check prerequisites and secrets BEFORE stopping the existing API.
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw "Java 21 JDK/JRE is required for Windows host mode."
}
$pgPassword = Get-DbpulseEnvValue 'DBPULSE_POSTGRES_PASSWORD'
if ([string]::IsNullOrWhiteSpace($pgPassword)) {
    throw "DBPULSE_POSTGRES_PASSWORD is required in .env."
}
$jar = Join-Path $root 'backend\target\dbpulse-api.jar'
if (-not [string]::IsNullOrWhiteSpace($JarPath)) {
    if (-not $BundledUi -or -not $SkipBuild) {
        throw 'An explicit bundle JarPath requires -BundledUi and -SkipBuild.'
    }
    # Only execute a JAR already staged beneath this repository target folder.
    $jar = (Resolve-Path -LiteralPath $JarPath -ErrorAction Stop).Path
    $allowedRoot = (Join-Path (Join-Path $root 'backend') 'target') + [IO.Path]::DirectorySeparatorChar
    if (-not $jar.StartsWith($allowedRoot,[System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'Bundle JAR must be under this project backend target directory.'
    }
}
if (-not $SkipBuild) {
    if (Get-Command mvn.cmd -ErrorAction SilentlyContinue) {
        # Use locally installed Maven when available.
        Push-Location (Join-Path $root 'backend')
        try {
            mvn.cmd -B -DskipTests package
            if ($LASTEXITCODE -ne 0) { throw "Maven backend build failed." }
        } finally { Pop-Location }
    } else {
        # Maven is optional; extract the built JAR from Docker's existing
        # Maven builder stage WITHOUT starting a second backend service.
        docker build --target builder --tag dbpulse-native-builder ./backend
        if ($LASTEXITCODE -ne 0) { throw "Backend builder image failed." }
        $cid = (docker create dbpulse-native-builder).Trim()
        if (-not $cid) { throw "Could not create temporary backend build container." }
        try {
            if (-not (Test-Path (Split-Path $jar -Parent))) {
                New-Item -ItemType Directory -Force (Split-Path $jar -Parent) | Out-Null
            }
            docker cp ("${cid}:/workspace/target/dbpulse-api.jar") $jar
            if ($LASTEXITCODE -ne 0) { throw "Could not extract built backend JAR." }
        } finally { docker rm -f $cid | Out-Null }
    }
}
if (-not (Test-Path $jar)) { throw "Backend JAR not found: $jar" }

# The desktop distribution must contain the production React index, not
# accidentally point users at an API-only JAR.
if ($BundledUi) {
    if (-not (Get-Command jar.exe -ErrorAction SilentlyContinue)) {
        throw "Java 21 JDK jar.exe is required to verify the bundled UI."
    }
    $jarEntries = jar.exe tf $jar
    if ($LASTEXITCODE -ne 0 -or
        -not ($jarEntries | Where-Object { $_ -eq 'BOOT-INF/classes/static/index.html' })) {
        throw "The JAR has no bundled React UI. Run tools/run_dbpulse_windows_ui.ps1 to rebuild it."
    }
}


# Only NOW release port 18081 and keep existing PostgreSQL data running.
docker compose stop backend
if ($LASTEXITCODE -ne 0) { throw "Could not stop the old Docker backend." }
docker compose up -d postgres
if ($LASTEXITCODE -ne 0) { throw "Could not start existing PostgreSQL." }

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

Write-Host "DBPulse Windows host API starting on http://127.0.0.1:18081"
Write-Host "Windows DPAPI belongs to this signed-in Windows user."
if ($BundledUi) {
    Write-Host "React UI and API share http://127.0.0.1:18081/#targets"
    Write-Host "Vite/npm development server is NOT needed."
} else {
    Write-Host "Keep this window open. Start npm.cmd run dev in frontend separately."
}
Write-Host "Never upload .env, encrypted DPAPI blobs, SQL logs or credentials."
# Foreground process; press Ctrl+C to stop. Windows .exe follows later.
try {
    if ($BundledUi) {
        # Java and the browser both use localhost. Wait for the backend
        # response before opening the browser so users never see a false 404.
        $javaBinary = (Get-Command java).Source
        $backendProcess = Start-Process -FilePath $javaBinary -ArgumentList @('-jar', ('"' + $jar + '"')) -PassThru -NoNewWindow
        $ready = $false
        for ($attempt=0; $attempt -lt 45; $attempt++) {
            if ($backendProcess.HasExited) { break }
            try {
                $response = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:18081/' -TimeoutSec 2
                if ($response.StatusCode -eq 200 -and
                    $response.Content -match 'DBPulse') {
                    $ready = $true
                    break
                }
            } catch {
                # Server may still be starting; retry without exposing errors.
            }
            Start-Sleep -Seconds 1
        }
        if (-not $ready) {
            throw "Bundled DBPulse UI was not reachable on localhost:18081. Inspect the Java startup logs above."
        }
        Write-Host "DBPulse UI ready. Opening local browser..."
        Start-Process 'http://127.0.0.1:18081/#targets'
        Wait-Process -Id $backendProcess.Id
    } else {
        java -jar $jar
    }
} finally {
    # Avoid leaving a second Java server running after Ctrl+C or any failure.
    if ($BundledUi -and $null -ne $backendProcess -and -not $backendProcess.HasExited) {
        Stop-Process -Id $backendProcess.Id -Force -ErrorAction SilentlyContinue
    }
    # Do not leave the PostgreSQL password in this PowerShell session's env.
    Remove-Item Env:DBPULSE_META_PASSWORD -ErrorAction SilentlyContinue
}
