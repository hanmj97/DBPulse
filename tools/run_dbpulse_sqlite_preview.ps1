# DBPulse Phase 15: isolated SQLite-backed Windows development preview.
# Runs an existing app-image JAR with private JRE, NOT DBPulse.exe.
# Never points the preview at the verified staging original or PostgreSQL.
# No Docker service is stopped, data deleted or permanent configuration changed.
$ErrorActionPreference = 'Stop'

if ($env:OS -ne 'Windows_NT') {
    throw 'Windows preview requires Windows PowerShell.'
}
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $root

# Strictly prohibit binding to the existing production/development port 18081.
$port = 18082
if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
    throw 'PREVIEW_PORT_18082_ALREADY_IN_USE'
}

# Java preflight happens BEFORE any SQLite staging file is copied.
# jpackage's Windows native EXE can include a working private runtime without
# exposing a command-line java.exe inside runtime\bin. This is not a broken EXE.
# For this development-only preview, use an installed Java 21+ launcher instead.
$buildFolder = Join-Path $root 'backend\target\windows-previews'
if (-not (Test-Path -LiteralPath $buildFolder -PathType Container)) {
    throw 'WINDOWS_APP_IMAGE_NOT_FOUND_BUILD_FIRST'
}
$jarFile = Get-ChildItem -LiteralPath $buildFolder -Filter 'dbpulse-api.jar' -Recurse -File |
    Where-Object { $_.Directory.Name -eq 'app' } |
    Sort-Object LastWriteTimeUtc -Descending |
    Select-Object -First 1
if ($null -eq $jarFile) { throw 'WINDOWS_APP_IMAGE_JAR_NOT_FOUND' }
$appDir = Split-Path (Split-Path $jarFile.FullName -Parent) -Parent
$bundledJava = Join-Path $appDir 'runtime\bin\java.exe'
if (Test-Path -LiteralPath $bundledJava -PathType Leaf) {
    $java = $bundledJava
    Write-Host 'SQLITE_PREVIEW_JAVA_SOURCE: PRIVATE_APP_RUNTIME'
} else {
    # Do not change PATH or the user's JAVA_HOME; only select a launcher.
    $javaCommand = Get-Command java.exe -CommandType Application -ErrorAction SilentlyContinue
    if ($null -eq $javaCommand) {
        throw 'JAVA_21_LAUNCHER_NOT_FOUND_CHECK_JAVA_PATH'
    }
    $java = $javaCommand.Source
    Write-Host 'SQLITE_PREVIEW_JAVA_SOURCE: INSTALLED_LOCAL_JAVA'
}

# DBPulse currently targets Java 21 bytecode; reject a Java 8/17 launcher.
try {
    $javaVersionLines = @(& $java '--version')
} catch {
    throw 'JAVA_VERSION_PROBE_FAILED'
}
if ($LASTEXITCODE -ne 0 -or $javaVersionLines.Count -eq 0) {
    throw 'JAVA_VERSION_PROBE_FAILED'
}
$versionLine = [string]$javaVersionLines[0]
if ($versionLine -notmatch '^(?:openjdk|java)\s+(?<major>\d+)(?:[.\s+]|$)') {
    throw 'JAVA_VERSION_UNRECOGNIZED'
}
if ([int]$Matches['major'] -lt 21) {
    throw 'JAVA_21_OR_NEWER_REQUIRED'
}

# Find ONLY successfully promoted SQLite staging files (exclude partial files).
$stageFolder = Join-Path $env:LOCALAPPDATA 'DBPulse\data\staging'
if (-not (Test-Path -LiteralPath $stageFolder -PathType Container)) {
    throw 'VERIFIED_SQLITE_STAGING_FOLDER_NOT_FOUND'
}
$stage = Get-ChildItem -LiteralPath $stageFolder -Filter 'dbpulse-pg-stage-*.sqlite' -File |
    Where-Object { $_.Name -notlike '*.partial.sqlite' } |
    Sort-Object LastWriteTimeUtc -Descending |
    Select-Object -First 1
if ($null -eq $stage) {
    throw 'VERIFIED_SQLITE_STAGING_FILE_NOT_FOUND'
}
if ($stage.Attributes -band [IO.FileAttributes]::ReparsePoint) {
    throw 'STAGING_SYMLINK_NOT_ALLOWED'
}
foreach ($suffix in @('-wal','-shm','-journal')) {
    if (Test-Path -LiteralPath ($stage.FullName + $suffix)) {
        throw 'STAGING_SIDECAR_PRESENT_STOP_AND_REVIEW'
    }
}

# Read-only integrity check for original staging; never open it as an app DB.
$checker = Join-Path $PSScriptRoot 'check_dbpulse_sqlite_stage.py'
py -3 $checker --file $stage.FullName
if ($LASTEXITCODE -ne 0) { throw 'ORIGINAL_STAGING_SQLITE_CHECK_FAILED' }

# Never overwrite an existing test DB; use a GUID to create a fresh copy.
$previewFolder = Join-Path $env:LOCALAPPDATA 'DBPulse\data\preview'
New-Item -ItemType Directory -Force -Path $previewFolder | Out-Null
$preview = Join-Path $previewFolder ('dbpulse-preview-' + [guid]::NewGuid().ToString('N') + '.sqlite')
Copy-Item -LiteralPath $stage.FullName -Destination $preview -ErrorAction Stop
$sourceHash = (Get-FileHash -LiteralPath $stage.FullName -Algorithm SHA256).Hash
$previewHash = (Get-FileHash -LiteralPath $preview -Algorithm SHA256).Hash
if ($sourceHash -ne $previewHash) { throw 'STAGING_COPY_HASH_MISMATCH' }

# Verify the copy before application startup.
py -3 $checker --file $preview
if ($LASTEXITCODE -ne 0) { throw 'PREVIEW_SQLITE_CHECK_FAILED' }


# No permanent env writes: preserve and restore all changed variables.
$names = @(
    'DBPULSE_SQLITE_PREVIEW_PATH',
    'DBPULSE_MSSQL_ENABLED',
    'DBPULSE_LOCAL_TARGETS_ENABLED',
    'DBPULSE_WINDOWS_DPAPI_ENABLED'
)
$savedEnv = @{}
foreach ($name in $names) {
    $savedEnv[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
    $env:DBPULSE_SQLITE_PREVIEW_PATH = $preview
    $env:DBPULSE_MSSQL_ENABLED = 'false'
    $env:DBPULSE_LOCAL_TARGETS_ENABLED = 'true'
    $env:DBPULSE_WINDOWS_DPAPI_ENABLED = 'false'
    Write-Host 'SQLITE_PREVIEW_COPY_VERIFIED'
    Write-Host ('PREVIEW_FILE: ' + $preview)
    Write-Host 'PostgreSQL and original staging file are unchanged.'
    Write-Host 'Port: http://127.0.0.1:18082/#targets'
    Write-Host 'Start a SECOND PowerShell to run the API checks while this window stays open.'
    Write-Host 'Do not register/activate monitoring targets in this preview; it is a read-only smoke test.'
    Write-Host 'Press Ctrl+C to stop this preview. Normal DBPulse.exe stays unchanged.'
    # Non-desktop Spring Boot startup. Explicit port, profile, no initialization
    # DDL and no automatic MSSQL target; only the SQLite COPY is used.
    & $java '-Ddbpulse.desktop.enabled=false' '-jar' $jarFile.FullName `
        '--spring.profiles.active=sqlite-preview' `
        '--server.address=127.0.0.1' `
        '--server.port=18082' `
        '--spring.sql.init.mode=never' `
        '--dbpulse.mssql.enabled=false' `
        '--dbpulse.local-targets.enabled=true' `
        '--dbpulse.credentials.windows-dpapi-enabled=false'
    if ($LASTEXITCODE -ne 0) { throw 'SQLITE_PREVIEW_JAVA_PROCESS_FAILED' }
} finally {
    foreach ($name in $names) {
        [Environment]::SetEnvironmentVariable($name, $savedEnv[$name], 'Process')
    }
    Write-Host ('PREVIEW_FILE_PRESERVED_FOR_REVIEW: ' + $preview)
}
