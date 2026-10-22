# Windows preview .exe with private Java 21 runtime (app-image only).
# Docker PostgreSQL metadata remains required. Existing data/DPAPI files intact.
param([switch]$SkipTests)

$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'Windows JDK 21 required.' }
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $root

# Build prerequisites; end users will not need Node/JDK to run the preview.
foreach ($toolName in @('npm.cmd','jar.exe','jpackage.exe','docker.exe')) {
    if (-not (Get-Command $toolName -ErrorAction SilentlyContinue)) {
        throw "Required build tool missing: $toolName"
    }
}

# Production React bundle, same source as the validated unified Windows UI.
Push-Location (Join-Path $root 'frontend')
try {
    npm.cmd install --no-package-lock --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency install failed.' }
    if (-not $SkipTests) {
        npm.cmd run test
        if ($LASTEXITCODE -ne 0) { throw 'Frontend regression tests failed.' }
    }
    npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw 'Frontend production build failed.' }
} finally { Pop-Location }

# Docker builder is compile-only; root .dockerignore excludes .env/secrets.
docker build -f tools/Dockerfile.windows-ui-bundle --tag dbpulse-windows-image-builder .
if ($LASTEXITCODE -ne 0) { throw 'Java bundle compilation failed.' }
$cid=(docker create dbpulse-windows-image-builder).Trim()
if ([string]::IsNullOrWhiteSpace($cid)) { throw 'Cannot create Java build container.' }

# Versioned output prevents overwriting any currently running Windows JAR.
$previewRoot=Join-Path (Join-Path (Join-Path $root 'backend') 'target') 'windows-previews'
$buildDir=Join-Path $previewRoot ([guid]::NewGuid().ToString('N'))
$inputDir=Join-Path $buildDir 'input'
New-Item -ItemType Directory -Force -Path $inputDir | Out-Null
$jar=Join-Path $inputDir 'dbpulse-api.jar'
try {
    docker cp ("${cid}:/workspace/backend/target/dbpulse-api.jar") $jar
    if ($LASTEXITCODE -ne 0) { throw 'Could not copy bundled Java JAR.' }
} finally {
    docker rm -f $cid | Out-Null
}

# Verify that real React assets are embedded, not just the backend API.
$entries=jar.exe tf $jar
if ($LASTEXITCODE -ne 0 -or
    -not ($entries | Where-Object { $_ -eq 'BOOT-INF/classes/static/index.html' }) -or
    -not ($entries | Where-Object { $_ -match '^BOOT-INF/classes/static/assets/.+[.]js$' })) {
    throw 'Java JAR does not contain the React production dashboard.'
}

# Nonmodular Spring Boot nested JARs are not fully analyzable by jdeps.
# Include the complete JDK module set for this first preview for safety.
$jpackageArgs=@(
    '--type','app-image',
    '--name','DBPulse',
    '--app-version','0.21.1',
    '--vendor','DBPulse',
    '--description','Local SQL Server monitoring dashboard',
    '--input',$inputDir,
    '--dest',$buildDir,
    '--main-jar','dbpulse-api.jar',
    '--main-class','org.springframework.boot.loader.launch.JarLauncher',
    '--java-options','-Ddbpulse.desktop.enabled=true',
    '--add-modules','ALL-MODULE-PATH',
    '--win-console'
)
& jpackage.exe @jpackageArgs
if ($LASTEXITCODE -ne 0) { throw 'jpackage Windows preview build failed.' }
$appExe=Join-Path (Join-Path $buildDir 'DBPulse') 'DBPulse.exe'
if (-not (Test-Path $appExe)) { throw 'DBPulse.exe missing from app-image.' }

Write-Host ''
Write-Host 'Windows DBPulse app-image preview created (not a setup installer).'
Write-Host ('Executable: ' + $appExe)
Write-Host 'Includes private Java runtime; no developer PowerShell or Node needed at app runtime.'
Write-Host 'PostgreSQL must still be running via Docker at localhost:5544.'
Write-Host 'Before first launch, perform one-time encrypted metadata setup.'
