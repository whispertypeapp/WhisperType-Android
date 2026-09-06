#requires -Version 5.1
<#
.SYNOPSIS
  Installs a verified, checksum-matched release APK from dist\<Version> on one authorized
  device and optionally launches it.

.DESCRIPTION
  Per Implementation_Plan.md 22.12 / 22.13 / 22.14:
    - Verifies that dist\<Version>\WhisperType-Android-<Version>.apk and its .sha256 file
      exist and that the recomputed local SHA-256 matches (exit 4 on any mismatch).
    - Resolves exactly one authorized device through scripts\adb-preflight.ps1.
    - Prints the exact artifact and target device before installing (install -r preserves app data).
    - Never uninstalls or clears data silently. A downgrade requires the explicit
      -AllowDowngrade switch, which adds -d (documented as development diagnosis only).

.PARAMETER Version
  The release version whose dist\<Version> folder holds the APK and checksum. Required.

.PARAMETER Device
  Optional explicit adb serial; otherwise a sole authorized device is auto-selected.

.PARAMETER Launch
  Launch the app after a successful install via monkey.

.PARAMETER AllowDowngrade
  Install with -d to allow a version-code downgrade. Android normally blocks downgrades;
  the -d flag is for development diagnosis only and is not part of user-facing guidance.

.EXAMPLE
  .\scripts\install-release.ps1 -Version 0.1.0 -Launch
  .\scripts\install-release.ps1 -Version 0.1.0 -Device R58M12345 -AllowDowngrade
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Version,
    [string]$Device = '',
    [switch]$Launch,
    [switch]$AllowDowngrade
)
$ErrorActionPreference = 'Stop'

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ScriptsDir = $PSScriptRoot
$AppId = 'com.whispertype.android'
$ApkName = 'WhisperType-Android-{0}.apk' -f $Version
$DistDir = Join-Path $ProjectRoot (Join-Path 'dist' $Version)
$ApkPath = Join-Path $DistDir $ApkName
$ShaPath = Join-Path $DistDir ('{0}.sha256' -f $ApkName)

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

# --- checksum verification (exit 4) -------------
if (-not (Test-Path -LiteralPath $ApkPath)) {
    Write-Host "ERROR: APK not found: $ApkPath" -ForegroundColor Red
    Write-Host 'Run scripts\build-release.ps1 first to produce the distribution directory.'
    exit 4
}
if (-not (Test-Path -LiteralPath $ShaPath)) {
    Write-Host "ERROR: checksum file not found: $ShaPath" -ForegroundColor Red
    exit 4
}

$shaRaw = (Get-Content -LiteralPath $ShaPath -Raw).Trim()
$shaFields = @($shaRaw -split '\s+')
if ($shaFields.Count -lt 2) {
    Write-Host 'ERROR: checksum file has an unexpected format (expected "<sha256>  <filename>").' -ForegroundColor Red
    exit 4
}
$storedHash = $shaFields[0].Trim()
$storedName = $shaFields[$shaFields.Count - 1].Trim()
$computedHash = (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash.Trim()

if ($storedName -cne $ApkName) {
    Write-Host ("ERROR: checksum file names '{0}' but the expected artifact is '{1}'." -f $storedName, $ApkName) -ForegroundColor Red
    exit 4
}
if ($computedHash -ine $storedHash) {
    Write-Host 'ERROR: SHA-256 checksum mismatch.' -ForegroundColor Red
    Write-Host ("  stored   : {0}" -f $storedHash)
    Write-Host ("  computed : {0}" -f $computedHash)
    Write-Host 'The APK may have been corrupted in transit. Do not install it.'
    exit 4
}
Write-Host 'Checksum verified OK.'
Write-Host ("  SHA-256 : {0}" -f $computedHash)
Write-Host ("  file    : {0}" -f $storedName)
Write-Host ''

# --- single authorized device ---------------------------------------------
$adb = Get-AdbPath
if (-not $adb) {
    Write-Host 'ERROR: adb.exe not found. Set ANDROID_HOME/ANDROID_SDK_ROOT or add platform-tools to PATH.' -ForegroundColor Red
    exit 2
}
$serial = Resolve-DeviceSerial -RequestedSerial $Device

# --- show exact artifact + target before installing --------------------------
$model = (& $adb -s $serial shell getprop ro.product.model 2>$null | Out-String).Trim()
Write-Host ''
Write-Host 'INSTALL SUMMARY'
Write-Host ("  artifact : {0}" -f $ApkPath)
Write-Host ("  sha256   : {0}" -f $computedHash)
Write-Host ("  device   : {0}  ({1})" -f $serial, $model)
Write-Host ''

# --- install ---------------------------------------------------------------
if ($AllowDowngrade) {
    Write-Host 'Installing with -r -d (-AllowDowngrade): allowing a version-code downgrade.'
    Write-Host 'Note: -d is a development-diagnosis fallback; Android normally blocks downgrades and user-facing installs never use it.'
    $installOutput = @(& $adb -s $serial install -r -d $ApkPath 2>&1)
} else {
    Write-Host 'Installing with install -r (app data is preserved; no uninstall is performed).'
    $installOutput = @(& $adb -s $serial install -r $ApkPath 2>&1)
}
$installText = $installOutput -join "`n"
$installOutput | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0 -or $installText -match 'Failure') {
    Write-Host 'Release APK installation failed.' -ForegroundColor Red
    if ($installText -match 'VERSION_DOWNGRADE|UPDATETIN') {
        Write-Host 'If this is an intentional downgrade during development, re-run with -AllowDowngrade.' -ForegroundColor Yellow
    }
    exit 1
}

# --- post-install verification ---------------------------------------------
$pmPath = (& $adb -s $serial shell pm path $AppId 2>&1 | Out-String).Trim()
if (-not $pmPath) {
    Write-Host 'pm path returned nothing; install verification failed.' -ForegroundColor Red
    exit 1
}
Write-Host ("Verified install: {0}" -f $pmPath)

if ($Launch) {
    Write-Host 'Launching WhisperType via monkey...'
    & $adb -s $serial shell monkey -p $AppId -c android.intent.category.LAUNCHER 1 2>&1 | ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -ne 0) {
        Write-Host 'Launch failed.' -ForegroundColor Red
        exit 1
    }
}

Write-Host 'Release install finished.' -ForegroundColor Green
exit 0