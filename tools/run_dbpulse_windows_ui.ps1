# DBPulse Windows integrated UI DEVELOPMENT launcher.
# Frontend is built once and embedded into the Spring Boot JAR.
# Runtime uses 127.0.0.1:18081 only; Vite/5174 is not required.
# Existing Docker PostgreSQL volume is NEVER reset or deleted.
param([switch]$SkipTests)

$ErrorActionPreference='Stop'
if ($env:OS -ne 'Windows_NT') { throw 'Windows is required.' }
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $root

# Node/npm are needed to BUILD React, not to run the integrated JAR.
if (-not (Get-Command npm.cmd -ErrorAction SilentlyContinue)) {
    throw 'Node.js/npm are required to build the React dashboard.'
}
if (-not (Get-Command jar.exe -ErrorAction SilentlyContinue)) {
    throw 'JDK 21 (jar.exe) is required to verify the complete Spring Boot bundle.'
}

$frontend=Join-Path $root 'frontend'
$dist=Join-Path $frontend 'dist'
# Runtime JAR path is assigned after building to a unique versioned file.

Push-Location $frontend
try {
    # This project currently has no committed package-lock.json.
    # Do not write a new lockfile or any secrets into the repository.
    npm.cmd install --no-package-lock --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) { throw 'React dependency install failed.' }
    if (-not $SkipTests) {
        npm.cmd run test
        if ($LASTEXITCODE -ne 0) { throw 'React regression tests failed.' }
    }
    # Vite rebuilds dist from source. Never ship the Vite dev server.
    npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw 'React production build failed.' }
} finally { Pop-Location }
if (-not (Test-Path (Join-Path $dist 'index.html'))) {
    throw 'Vite dist/index.html is missing.'
}

# Detect only a known DBPulse Windows JVM. Never terminate other Java processes.
# A running local DBPulse instance has an active database connection and may
# still be polling; the operator should close its original window with Ctrl+C.
$listenerPids=@(Get-NetTCPConnection -LocalPort 18081 -State Listen -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -Unique)
foreach ($listenerPid in $listenerPids) {
    $proc=Get-CimInstance Win32_Process -Filter "ProcessId = $listenerPid" -ErrorAction SilentlyContinue
    if ($null -eq $proc) { continue }
    $cmdLine=[string]$proc.CommandLine
    if ($proc.Name -match '^javaw?\\.exe$' -and
        ($cmdLine.IndexOf('dbpulse-api.jar',[System.StringComparison]::OrdinalIgnoreCase) -ge 0 -or
         $cmdLine.IndexOf('dbpulse-desktop-',[System.StringComparison]::OrdinalIgnoreCase) -ge 0)) {
        throw "An existing Windows DBPulse backend (PID $listenerPid) is using port 18081. Press Ctrl+C in its original PowerShell window, wait for exit, then rerun this launcher. No other Java process was stopped."
    }
}

# Build into a UNIQUE VERSIONED FILE instead of replacing dbpulse-api.jar.
# Windows keeps the JAR locked while the old Spring Boot Java process runs.
# Docker is already a prerequisite for the existing PostgreSQL container,
# so the isolated Maven builder is used consistently (even if Maven exists).
# Root .dockerignore permits sources + React dist ONLY, never the .env file.
$bundleDir=Join-Path $root 'backend\\target\\desktop-bundles'
if (-not (Test-Path $bundleDir)) {
    New-Item -ItemType Directory -Force -Path $bundleDir | Out-Null
}
$jar=Join-Path $bundleDir ('dbpulse-desktop-' + [guid]::NewGuid().ToString('N') + '.jar')
docker build -f tools/Dockerfile.windows-ui-bundle --tag dbpulse-windows-ui-builder .
if ($LASTEXITCODE -ne 0) { throw 'Docker desktop-bundle build failed.' }
$cid=(docker create dbpulse-windows-ui-builder).Trim()
if (-not $cid) { throw 'Could not create temporary build container.' }
try {
    docker cp ("${cid}:/workspace/backend/target/dbpulse-api.jar") $jar
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not copy desktop-bundle JAR into a new versioned file.'
    }
} finally {
    docker rm -f $cid | Out-Null
}

if (-not (Test-Path $jar)) { throw 'Bundled JAR is missing.' }
$entries=jar.exe tf $jar
if ($LASTEXITCODE -ne 0 -or
    -not ($entries | Where-Object { $_ -eq 'BOOT-INF/classes/static/index.html' }) -or
    -not ($entries | Where-Object { $_ -match '^BOOT-INF/classes/static/assets/.*\.js$' })) {
    throw 'JAR does not contain a complete React production build.'
}
Write-Host 'One-process DBPulse Java + React bundle verified.'
Write-Host 'Stopping only the Docker backend; existing PostgreSQL remains intact.'

# Reuse proven Windows-native DPAPI and metadata configuration.
# This launcher opens the browser automatically AFTER the UI responds.
& (Join-Path $PSScriptRoot 'run_dbpulse_windows_host_lab.ps1') -SkipBuild -BundledUi -JarPath $jar
