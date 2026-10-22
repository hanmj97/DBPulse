# Phase 18: compile an unsigned Inno Setup preview from an existing NEW app-image.
# Source must be inside backend\target; do NOT package live SQLite/DPAPI data.
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$AppImagePath,
    [string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'WINDOWS_BUILD_HOST_REQUIRED' }
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$target = [IO.Path]::GetFullPath((Join-Path $root 'backend\target')).TrimEnd('\')
$source = (Resolve-Path -LiteralPath $AppImagePath -ErrorAction Stop).Path.TrimEnd('\')
if (-not $source.StartsWith($target + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'APP_IMAGE_MUST_BE_INSIDE_BACKEND_TARGET'
}
if ((Get-Item -LiteralPath $source).Attributes -band [IO.FileAttributes]::ReparsePoint) {
    throw 'APP_IMAGE_REPARSE_POINT_NOT_ALLOWED'
}
$expected = @(
    'DBPulse.exe',
    'runtime',
    'app',
    'window\DBPulse.WindowHost.exe'
)
foreach ($relative in $expected) {
    if (-not (Test-Path -LiteralPath (Join-Path $source $relative))) {
        throw "INCOMPLETE_APP_IMAGE_$($relative.Replace('\','_'))"
    }
}
$exposed = @(Get-ChildItem -LiteralPath $source -Recurse -File -Force |
    Where-Object {
        $_.Attributes -band [IO.FileAttributes]::ReparsePoint -or
        $_.Name -match '(?i)^\.env($|\.)|\.(sqlite|sqlite3|db|dump|dpapi|pfx|pem|key)$|\.sqlite-(wal|shm)$'
    })
if ($exposed.Count -ne 0) {
    throw 'APP_IMAGE_CONTAINS_DATA_OR_CREDENTIAL_FILE'
}

$inno = Get-Command ISCC.exe -ErrorAction SilentlyContinue
if ($null -ne $inno) {
    $compiler = $inno.Source
}
else {
    $pf86 = [Environment]::GetEnvironmentVariable('ProgramFiles(x86)')
    $candidates = @(
        (Join-Path $pf86 'Inno Setup 6\ISCC.exe'),
        (Join-Path $env:ProgramFiles 'Inno Setup 6\ISCC.exe')
    )
    $compiler = @($candidates | Where-Object { Test-Path -LiteralPath $_ } |
        Select-Object -First 1)[0]
}
if ([string]::IsNullOrWhiteSpace($compiler) -or
    -not (Test-Path -LiteralPath $compiler)) {
    throw 'INNO_SETUP_6_COMPILER_NOT_FOUND'
}
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $target ('installer-preview-' + [guid]::NewGuid().ToString('N'))
}
$destination = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$filename = 'DBPulse-Setup-v0.21.0-preview.exe'
$output = Join-Path $destination $filename
if (Test-Path -LiteralPath $output) {
    throw 'INSTALLER_ALREADY_EXISTS_NO_OVERWRITE'
}
$definition = Join-Path $root 'installer\dbpulse.iss'
if (-not (Test-Path -LiteralPath $definition)) {
    throw 'INSTALLER_DEFINITION_MISSING'
}
# ISCC /D defines the known-good absolute source directory in the .iss file.
& $compiler ("/DAppImage=" + $source) ("/O" + $destination) ("/F" + 'DBPulse-Setup-v0.21.0-preview') $definition
if ($LASTEXITCODE -ne 0) { throw 'INNO_SETUP_COMPILATION_FAILED' }
if (-not (Test-Path -LiteralPath $output -PathType Leaf) -or
    (Get-Item -LiteralPath $output).Length -lt 1000000) {
    throw 'INSTALLER_OUTPUT_INVALID'
}
Write-Host 'DBPULSE_UNSIGNED_WINDOWS_SETUP_PREVIEW_READY'
Write-Host ('INSTALLER: ' + $output)
Write-Host 'No user data or DPAPI credentials were packaged or modified.'
