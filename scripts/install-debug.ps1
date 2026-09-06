#requires -Version 5.1
<#
.SYNOPSIS
  Builds the debug APK, installs it on a single target device, and optionally launches it.

.DESCRIPTION
  Resolves the target device through scripts\adb-preflight.ps1 (never defaults to "every
  connected device"), builds the debug APK, and installs it with `adb install -r -t` so
  app data is preserved. Per Implementation_Plan.md 22.7 / 22.14:
    - No uninstall, data wipe, or clear is ever performed without an explicit -ForceReinstall
      switch AND typed confirmation.
    - The exact artifact and target device are printed before installation.
    - Exits non-zero on build, uninstall, install, or launch failure.

.PARAMETER Device
  Optional explicit adb serial; otherwise a sole authorized device is auto-selected.

.PARAMETER Launch
  Launch the app after install via monkey.

.PARAMETER RunChecks
  Also run :app:testDebugUnitTest and :app:lintDebug before assembleDebug.

.PARAMETER ForceReinstall
  Uninstall (removes app data/permissions!) only after a typed confirmation, then reinstall.

.EXAMPLE
  .\scripts\install-debug.ps1 -Launch
  .\scripts\install-debug.ps1 -Device R58M12345 -ForceReinstall
#>
[CmdletBinding()]
param(
    [string]$Device = '',
    [switch]$Launch,
    [switch]$RunChecks,
    [switch]$ForceReinstall
)
$ErrorActionPreference = 'Stop'

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ScriptsDir = $PSScriptRoot
$AppId = 'com.whispertype.android'
$DebugApk = Join-Path $ProjectRoot 'app\build\outputs\apk\debug\app-debug.apk'
$GradleWrapper = Join-Path $ProjectRoot 'gradlew.bat'

function Get-AdbPath {
    $candidates = @()
    if ($env:ANDROID_HOME) { $candidates += (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') }
    if ($env:ANDROID_SDK_ROOT) { $candidates += (Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe') }
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbCommand) { return $adbCommand.Source }
    return ''
}

# Reuses adb-preflight.ps1; its last line of stdout is the resolved device serial.
function Resolve-DeviceSerial {
    param([string]$RequestedSerial)
    $preflight = Join-Path $ScriptsDir 'adb-preflight.ps1'
    if (-not (Test-Path -LiteralPath $preflight)) {
        Write-Host ("Missing preflight script: {0}" -f $preflight) -ForegroundColor Red
        exit 2
    }
    $resolved = (& $preflight -Device $RequestedSerial)
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    return $resolved
}

$adb = Get-AdbPath
if (-not $adb) {
    Write-Host 'ERROR: adb.exe not found. Set ANDROID_HOME/ANDROID_SDK_ROOT or add platform-tools to PATH.' -ForegroundColor Red
    exit 2
}
$serial = Resolve-DeviceSerial -RequestedSerial $Device
Write-Host ("Resolved target device serial: {0}" -f $serial)

Push-Location $ProjectRoot
try {
    if ($RunChecks) {
        Write-Host 'Running unit tests, lint, and debug assemble...'
        & $GradleWrapper :app:testDebugUnitTest :app:lintDebug :app:assembleDebug 2>&1 | ForEach-Object { Write-Host $_ }
    } else {
        Write-Host 'Assembling debug APK...'
        & $GradleWrapper :app:assembleDebug 2>&1 | ForEach-Object { Write-Host $_ }
    }
} finally {
    Pop-Location
}
if ($LASTEXITCODE -ne 0) {
    Write-Host 'Gradle build failed.' -ForegroundColor Red
    exit 1
}
if (-not (Test-Path -LiteralPath $DebugApk)) {
    Write-Host 'ERROR: the debug APK was not produced at the expected path.' -ForegroundColor Red
    Write-Host "Expected: $DebugApk"
    exit 1
}

if ($ForceReinstall) {
    Write-Host 'WARNING: -ForceReinstall uninstalls WhisperType, which deletes its app data and permissions.' -ForegroundColor Yellow
    $confirmation = Read-Host 'Type "uninstall" to confirm this destructive step'
    if ($confirmation -cne 'uninstall') {
        Write-Host 'Aborting: nothing was uninstalled.' -ForegroundColor Yellow
        exit 1
    }
    Write-Host ("Uninstalling {0} from {1}..." -f $AppId, $serial)
    $uninstallOutput = @(& $adb -s $serial uninstall $AppId 2>&1)
    $uninstallOutput | ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -ne 0) {
        Write-Host 'adb uninstall failed.' -ForegroundColor Red
        exit 1
    }
    Write-Host 'Uninstall complete. Proceeding to install.'
}

Write-Host ''
Write-Host 'INSTALL SUMMARY'
Write-Host ("  artifact : {0}" -f $DebugApk)
Write-Host ("  device   : {0}" -f $serial)
Write-Host ''
Write-Host 'Installing debug APK with -r -t (app data is preserved)...'
$installOutput = @(& $adb -s $serial install -r -t $DebugApk 2>&1)
$installText = $installOutput -join "`n"
$installOutput | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0 -or $installText -notmatch 'Success') {
    if ($installText -match 'UPDATE_INCOMPATIBLE|signature|INVALID') {
        Write-Host 'The installer reported a signature or update conflict. Run with -ForceReinstall (this uninstalls and clears app data; you will be asked to confirm).' -ForegroundColor Yellow
    }
    Write-Host 'APK installation failed.' -ForegroundColor Red
    exit 1
}

$pmPath = (& $adb -s $serial shell pm path $AppId 2>&1 | Out-String).Trim()
if (-not $pmPath) {
    Write-Host 'pm path returned nothing; install verification failed.' -ForegroundColor Red
    exit 1
}
Write-Host ("Verified package: {0}" -f $pmPath)

if ($Launch) {
    Write-Host 'Launching WhisperType via monkey...'
    & $adb -s $serial shell monkey -p $AppId -c android.intent.category.LAUNCHER 1 2>&1 | ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -ne 0) {
        Write-Host 'Launch failed.' -ForegroundColor Red
        exit 1
    }
}

Write-Host 'Debug deployment finished.' -ForegroundColor Green
exit 0