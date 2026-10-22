# DBPulse Windows React + Spring Boot single-process development launcher.
# PostgreSQL continues in Docker, and existing metadata/data are not deleted.
# Each bundled JAR gets a unique filename to avoid Windows file-lock conflicts.
param([switch]$SkipTests)

$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'Windows is required.' }

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $root
$frontend = Join-Path $root 'frontend'

if (-not (Get-Command npm.cmd -ErrorAction SilentlyContinue)) {
    throw 'Node.js/npm is required to build the React dashboard.'
}
if (-not (Get-Command jar.exe -ErrorAction SilentlyContinue)) {
    throw 'Java 21 JDK (jar.exe) is required to verify the bundled dashboard.'
}

# An older Windows Java backend may still hold port 18081. Require the
# operator to close that DBPulse window first; never stop unrelated Java.
$owners = @(Get-NetTCPConnection -LocalPort 18081 -State Listen -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -Unique)
foreach ($listenerPid in $owners) {
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $listenerPid" -ErrorAction SilentlyContinue
    if ($null -eq $processInfo) { continue }
    $commandLine = [string]$processInfo.CommandLine
    $javaProcess = @('java.exe','javaw.exe') -contains $processInfo.Name.ToLowerInvariant()
    $dbpulseProcess = $commandLine.Contains('dbpulse-api.jar') -or
        $commandLine.Contains('dbpulse-desktop-')
    if ($javaProcess -and $dbpulseProcess) {
        throw "Windows DBPulse backend PID $listenerPid is still running. Press Ctrl+C in its original window, wait until it exits, and retry."
    }
}

# Build the React production assets. The Vite dev server is NOT launched.
Push-Location $frontend
try {
    npm.cmd install --no-package-lock --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) { throw 'React dependency install failed.' }
    if (-not $SkipTests) {
        npm.cmd run test
        if ($LASTEXITCODE -ne 0) { throw 'React regression tests failed.' }
    }
    npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw 'React production build failed.' }
} finally {
    Pop-Location
}
$dist = Join-Path $frontend 'dist'
if (-not (Test-Path (Join-Path $dist 'index.html'))) {
    throw 'React build did not generate index.html.'
}

# Docker is already needed for the existing PostgreSQL metadata container.
# .dockerignore restricts the build context to backend sources + React dist.
# Build a bundled JAR in a temporary builder container; never overwrite a
# JAR that might still be locked by a running native Windows Java process.
$bundleDir = Join-Path (Join-Path (Join-Path $root 'backend') 'target') 'desktop-bundles'
if (-not (Test-Path $bundleDir)) {
    New-Item -ItemType Directory -Force -Path $bundleDir | Out-Null
}
$jar = Join-Path $bundleDir ('dbpulse-desktop-' + [guid]::NewGuid().ToString('N') + '.jar')

docker build -f tools/Dockerfile.windows-ui-bundle --tag dbpulse-windows-ui-builder .
if ($LASTEXITCODE -ne 0) { throw 'Docker desktop-bundle build failed.' }
$cid = (docker create dbpulse-windows-ui-builder).Trim()
if (-not $cid) { throw 'Could not create the temporary builder container.' }
try {
    docker cp ("${cid}:/workspace/backend/target/dbpulse-api.jar") $jar
    if ($LASTEXITCODE -ne 0) { throw 'Could not copy the new JAR into its unique bundle path.' }
} finally {
    docker rm -f $cid | Out-Null
}

# Verify that this version contains both the React HTML and JS bundle.
if (-not (Test-Path $jar)) { throw 'New bundled JAR is missing.' }
$entries = jar.exe tf $jar
if ($LASTEXITCODE -ne 0 -or
    -not ($entries | Where-Object { $_ -eq 'BOOT-INF/classes/static/index.html' }) -or
    -not ($entries | Where-Object { $_ -match '^BOOT-INF/classes/static/assets/.+[.]js$' })) {
    throw 'New JAR does not contain a complete production React dashboard.'
}

Write-Host 'One-process Windows DBPulse React + Java JAR is ready.'
Write-Host 'The original JAR and PostgreSQL data have not been replaced or removed.'

# Run this JAR as the current Windows user to retain DPAPI access.
# The host script stops only the legacy Docker backend and launches browser
# after the new UI answers on 127.0.0.1:18081.
& (Join-Path $PSScriptRoot 'run_dbpulse_windows_host_lab.ps1') -SkipBuild -BundledUi -JarPath $jar
