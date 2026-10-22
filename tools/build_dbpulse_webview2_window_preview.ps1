# Phase 17: Build a NEW WebView2 standalone-window preview beside a NEW Java app-image.
# Requires .NET 10 SDK, WebView2 NuGet restore, and the Phase 15 Java build tool.
# Does not overwrite an existing EXE, SQLite DB, DPAPI credential or PostgreSQL volume.
param([switch]$SkipBackendBuild)

$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $root

# A Windows self-contained .NET publish requires the SDK only on the developer PC.
if ($env:OS -ne 'Windows_NT') { throw 'PHASE17_WINDOWS_REQUIRED' }
if (-not (Get-Command dotnet -ErrorAction SilentlyContinue)) {
    throw 'DOTNET_10_SDK_NOT_FOUND'
}
$sdk=@(dotnet --list-sdks | Where-Object { $_ -match '^10[.]' })
if ($LASTEXITCODE -ne 0 -or $sdk.Count -eq 0) {
    throw 'DOTNET_10_SDK_NOT_FOUND'
}

# Default: always build a NEW patched Java app-image first.
# SkipBackendBuild is developer-only for an already verified NEW jar.
if (-not $SkipBackendBuild) {
    & (Join-Path $PSScriptRoot 'build_dbpulse_windows_app_image.ps1')
    if (-not $?) { throw 'JAVA_APP_IMAGE_BUILD_FAILED' }
}

$previewRoot=Join-Path $root 'backend\target\windows-previews'
$javaExe=Get-ChildItem $previewRoot -Filter DBPulse.exe -Recurse -File |
    Where-Object { $_.Directory.Name -eq 'DBPulse' } |
    Sort-Object LastWriteTimeUtc -Descending |
    Select-Object -First 1
if ($null -eq $javaExe) { throw 'NEW_JAVA_APP_IMAGE_NOT_FOUND' }
$appDir=$javaExe.Directory.FullName
if (-not (Test-Path (Join-Path $appDir 'runtime'))) {
    throw 'BUNDLED_JAVA_RUNTIME_MISSING'
}
$windowDir=Join-Path $appDir 'window'
if (Test-Path -LiteralPath $windowDir) {
    throw 'WINDOW_PREVIEW_ALREADY_EXISTS_NO_OVERWRITE'
}

# Output inside the versioned app-image, separate from the historical DBPulse.exe.
$project=Join-Path $root 'desktop\DBPulse.WindowHost\DBPulse.WindowHost.csproj'
& dotnet publish $project -c Release -r win-x64 --self-contained true -o $windowDir -p:PublishSingleFile=false
if ($LASTEXITCODE -ne 0) { throw 'WINDOW_WEBVIEW2_PUBLISH_FAILED' }

$hostExe=Join-Path $windowDir 'DBPulse.WindowHost.exe'
if (-not (Test-Path -LiteralPath $hostExe -PathType Leaf)) {
    throw 'WINDOW_WEBVIEW2_EXE_NOT_FOUND'
}
$check = Start-Process -FilePath $hostExe -ArgumentList '--self-test' -PassThru -Wait
if ($check.ExitCode -ne 0) { throw 'WINDOW_HOST_POLICY_TEST_FAILED' }

Write-Host 'DBPULSE_WEBVIEW2_WINDOW_PREVIEW_READY'
Write-Host ('NEW_WINDOW_EXE: ' + $hostExe)
Write-Host ('BUNDLED_JAVA_EXE: ' + $javaExe.FullName)
Write-Host 'NOTE: Run DBPulse.WindowHost.exe in the window folder; keep entire DBPulse folder.'
Write-Host 'NOTE: WebView2 Runtime must be installed. No Docker/.NET SDK needed at runtime.'
