# CI-ONLY: installer smoke; never use on personal PC.
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$InstallerPath)
$ErrorActionPreference='Stop'
if ($env:CI -ne 'true' -or -not $env:RUNNER_TEMP) { throw 'CI_ONLY_INSTALL_UNINSTALL_SMOKE_TEST' }
$installer = (Resolve-Path -LiteralPath $InstallerPath).Path
$dir = Join-Path $env:RUNNER_TEMP ('dbpulse-installer-smoke-' + [guid]::NewGuid().ToString('N'))
# Check that uninstall preserves ALL independently managed user data folders.
# Each sentinel has a unique name and only that known test file is removed.
$sentinels = @()
foreach ($name in @('data', 'desktop', 'credentials', 'backups', 'logs')) {
    $dirPath = Join-Path (Join-Path $env:LOCALAPPDATA 'DBPulse') $name
    New-Item -ItemType Directory -Path $dirPath -Force | Out-Null
    $sentinel = Join-Path $dirPath ('phase21-uninstall-preserve-' + [guid]::NewGuid().ToString('N') + '.txt')
    'DO_NOT_DELETE_USER_DATA' | Set-Content -LiteralPath $sentinel -NoNewline
    $sentinels += [PSCustomObject]@{
        Path = $sentinel
        Hash = (Get-FileHash -LiteralPath $sentinel -Algorithm SHA256).Hash
    }
}
try {
    $args = @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART',('/DIR="' + $dir + '"'))
    $p = Start-Process -FilePath $installer -ArgumentList $args -PassThru -Wait
    if ($p.ExitCode -ne 0) { throw "SETUP_INSTALL_EXIT_$($p.ExitCode)" }
    $hostExe = Join-Path $dir 'window\DBPulse.WindowHost.exe'
    $backendExe = Join-Path $dir 'DBPulse.exe'
    $runtime = Join-Path $dir 'runtime'
    if (-not (Test-Path -LiteralPath $hostExe -PathType Leaf) -or
        -not (Test-Path -LiteralPath $backendExe -PathType Leaf) -or
        -not (Test-Path -LiteralPath $runtime -PathType Container)) {
        throw 'INSTALLED_BUNDLE_INCOMPLETE'
    }
    # Seven-size branded icon is packaged and remains usable by shortcuts.
    $icon = Join-Path $dir 'window\DBPulse.ico'
    if (-not (Test-Path -LiteralPath $icon -PathType Leaf)) {
        throw 'INSTALLED_DBPULSE_ICON_MISSING'
    }
    $sourceIcon = Join-Path (Get-Location).Path 'assets/DBPulse.ico'
    if ((Get-FileHash -LiteralPath $icon -Algorithm SHA256).Hash -ne
        (Get-FileHash -LiteralPath $sourceIcon -Algorithm SHA256).Hash) {
        throw 'INSTALLED_DBPULSE_ICON_MISMATCH'
    }
    Write-Host 'DBPULSE_INSTALLED_APP_ICON_VERIFIED'
    # Ensure installation preserved the actual EXE, not a truncated / different binary.
    $originalHost = Join-Path (Get-Location).Path 'backend/target/windows-ci/DBPulse/window/DBPulse.WindowHost.exe'
    $installedHash = (Get-FileHash -LiteralPath $hostExe -Algorithm SHA256).Hash
    $sourceHash = (Get-FileHash -LiteralPath $originalHost -Algorithm SHA256).Hash
    if ($installedHash -ne $sourceHash) { throw 'INSTALLED_EXE_HASH_DIFFERS_FROM_SOURCE' }
    Write-Host 'DBPULSE_INSTALLED_EXE_SHA256_VERIFIED'
    # WinExe is a GUI subsystem executable. Wait explicitly for exit code.
    $check = Start-Process -FilePath $hostExe -ArgumentList '--self-test' -PassThru -Wait
    $selfTestExit = $check.ExitCode
    if ($selfTestExit -ne 0) { throw "INSTALLED_WINDOW_SELF_TEST_EXIT_$selfTestExit" }
    Write-Host 'DBPULSE_INSTALLER_SILENT_INSTALL_OK'
    $uninstaller = Join-Path $dir 'unins000.exe'
    if (-not (Test-Path -LiteralPath $uninstaller -PathType Leaf)) { throw 'UNINSTALLER_MISSING' }
    $u = Start-Process -FilePath $uninstaller -ArgumentList @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART') -PassThru -Wait
    if ($u.ExitCode -ne 0) { throw "UNINSTALL_EXIT_$($u.ExitCode)" }
    if (Test-Path -LiteralPath $hostExe) { throw 'APP_BINARY_SURVIVED_UNINSTALL' }
    Write-Host 'DBPULSE_INSTALLER_SILENT_UNINSTALL_OK'
    foreach ($item in $sentinels) {
        if (-not (Test-Path -LiteralPath $item.Path -PathType Leaf) -or
            (Get-FileHash -LiteralPath $item.Path -Algorithm SHA256).Hash -ne $item.Hash) {
            throw 'USER_DATA_MODIFIED_BY_UNINSTALL'
        }
    }
    Write-Host 'DBPULSE_INSTALLER_USER_DATA_PRESERVED_OK'
}
finally {
    foreach ($item in $sentinels) {
        if (Test-Path -LiteralPath $item.Path -PathType Leaf) {
            Remove-Item -LiteralPath $item.Path -Force
        }
    }
}
