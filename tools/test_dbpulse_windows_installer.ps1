# CI-ONLY: installer smoke; never use on personal PC.
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$InstallerPath)
$ErrorActionPreference='Stop'
if ($env:CI -ne 'true' -or -not $env:RUNNER_TEMP) { throw 'CI_ONLY_INSTALL_UNINSTALL_SMOKE_TEST' }
$installer = (Resolve-Path -LiteralPath $InstallerPath).Path
$dir = Join-Path $env:RUNNER_TEMP ('dbpulse-installer-smoke-' + [guid]::NewGuid().ToString('N'))
$sentinelDir = Join-Path $env:LOCALAPPDATA 'DBPulse\data'
$sentinel = Join-Path $sentinelDir ('phase18-preservation-' + [guid]::NewGuid().ToString('N') + '.txt')
New-Item -ItemType Directory -Path $sentinelDir -Force | Out-Null
'DO_NOT_DELETE_USER_DATA' | Set-Content -LiteralPath $sentinel -NoNewline
$originalHash = (Get-FileHash -LiteralPath $sentinel -Algorithm SHA256).Hash
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
    & $hostExe --self-test
    if ($LASTEXITCODE -ne 0) { throw 'INSTALLED_WINDOW_SELF_TEST_FAILED' }
    Write-Host 'DBPULSE_INSTALLER_SILENT_INSTALL_OK'
    $uninstaller = Join-Path $dir 'unins000.exe'
    if (-not (Test-Path -LiteralPath $uninstaller -PathType Leaf)) { throw 'UNINSTALLER_MISSING' }
    $u = Start-Process -FilePath $uninstaller -ArgumentList @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART') -PassThru -Wait
    if ($u.ExitCode -ne 0) { throw "UNINSTALL_EXIT_$($u.ExitCode)" }
    if (Test-Path -LiteralPath $hostExe) { throw 'APP_BINARY_SURVIVED_UNINSTALL' }
    Write-Host 'DBPULSE_INSTALLER_SILENT_UNINSTALL_OK'
    if (-not (Test-Path -LiteralPath $sentinel -PathType Leaf) -or
        (Get-FileHash -LiteralPath $sentinel -Algorithm SHA256).Hash -ne $originalHash) {
        throw 'USER_DATA_MODIFIED_BY_UNINSTALL'
    }
    Write-Host 'DBPULSE_INSTALLER_USER_DATA_PRESERVED_OK'
}
finally {
    if (Test-Path -LiteralPath $sentinel) { Remove-Item -LiteralPath $sentinel -Force }
}
