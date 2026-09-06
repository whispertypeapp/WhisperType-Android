#requires -Version 5.1
<#
.SYNOPSIS
  Collects privacy-safe scoped logs and system dumps for the WhisperType app on one target
  device and writes them into an ignored, timestamped diagnostics directory.

.DESCRIPTION
  Per Implementation_Plan.md 22.8 / 22.14:
    - Clears only the log buffer (`logcat -c`); never touches app data.
    - Only collects logcat scoped to the app PID, plus dumpsys window / accessibility /
      activity services / package. No transcript, audio, API key, or clipboard content
      is intentionally captured, and no adb output is echoed to the console.
    - Results are written to files in <OutDir>\<yyyyMMdd-HHmmss> under .\diagnostics\
      (git-ignored). The final line of stdout is the output directory path.

.PARAMETER Device
  Optional explicit adb serial; otherwise a sole authorized device is auto-selected.

.PARAMETER OutDir
  Base directory for the timestamped capture folder. Relative paths resolve against the
  project root. Default: .\diagnostics

.PARAMETER Verbose
  Print additional non-sensitive detail (device model, file sizes). Never prints content.

.EXAMPLE
  .\scripts\collect-diagnostics.ps1
  .\scripts\collect-diagnostics.ps1 -Device R58M12345 -Verbose
#>
[CmdletBinding()]
param(
    [string]$Device = '',
    [string]$OutDir = '.\diagnostics',
    [switch]$Verbose
)
$ErrorActionPreference = 'Stop'

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ScriptsDir = $PSScriptRoot
$AppId = 'com.whispertype.android'

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

# Runs an adb command and redirects stdout+stderr into a file. Content never goes to the console.
function Invoke-AdbCapture {
    param([string]$AdbPath, [string]$Serial, [string[]]$Arguments, [string]$OutputFile, [string]$Label)
    & $AdbPath -s $Serial $Arguments 2>&1 | Out-File -FilePath $OutputFile -Encoding utf8
    if ($LASTEXITCODE -ne 0) {
        Write-Host ("WARNING: '{0}' capture returned exit code {1}." -f $Label, $LASTEXITCODE) -ForegroundColor Yellow
    }
}

$adb = Get-AdbPath
if (-not $adb) {
    Write-Host 'ERROR: adb.exe not found. Set ANDROID_HOME/ANDROID_SDK_ROOT or add platform-tools to PATH.' -ForegroundColor Red
    exit 2
}
$serial = Resolve-DeviceSerial -RequestedSerial $Device

if ([System.IO.Path]::IsPathRooted($OutDir)) {
    $resolvedOutDir = $OutDir
} else {
    $resolvedOutDir = Join-Path $ProjectRoot $OutDir
}
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$diagDir = Join-Path $resolvedOutDir $stamp
New-Item -ItemType Directory -Force -Path $diagDir | Out-Null

if ($Verbose) {
    $model = (& $adb -s $serial shell getprop ro.product.model 2>$null | Out-String).Trim()
    Write-Host ("Target device model : {0}" -f $model)
}
Write-Host 'Clearing logcat buffer (log buffer only; app data is untouched)...'
& $adb -s $serial logcat -c

Write-Host 'Resolving WhisperType process id...'
$processId = (& $adb -s $serial shell pidof -s $AppId 2>&1 | Out-String).Trim()
if ($processId) {
    Write-Host ("Capturing scoped logcat for pid {0}..." -f $processId)
    # NOTE: `$processId` instead of the reserved automatic variable `$pid` (current shell PID).
    & $adb -s $serial logcat "--pid=$processId" 2>&1 | Out-File -FilePath (Join-Path $diagDir 'logcat-app.log') -Encoding utf8
} else {
    Write-Host 'WhisperType is not currently running on this device; skipping app-PID logcat capture.' -ForegroundColor Yellow
    Set-Content -LiteralPath (Join-Path $diagDir 'logcat-app.log') -Value '(WhisperType process was not running when diagnostics were collected.)' -Encoding utf8
}

Write-Host 'Capturing system dumps (window / accessibility / activity services / package)...'
Invoke-AdbCapture -AdbPath $adb -Serial $serial -Arguments @('shell', 'dumpsys', 'window') -OutputFile (Join-Path $diagDir 'dumpsys-window.txt') -Label 'dumpsys window'
Invoke-AdbCapture -AdbPath $adb -Serial $serial -Arguments @('shell', 'dumpsys', 'accessibility') -OutputFile (Join-Path $diagDir 'dumpsys-accessibility.txt') -Label 'dumpsys accessibility'
Invoke-AdbCapture -AdbPath $adb -Serial $serial -Arguments @('shell', 'dumpsys', 'activity', 'services', $AppId) -OutputFile (Join-Path $diagDir 'dumpsys-activity-services.txt') -Label 'dumpsys activity services'
Invoke-AdbCapture -AdbPath $adb -Serial $serial -Arguments @('shell', 'dumpsys', 'package', $AppId) -OutputFile (Join-Path $diagDir 'dumpsys-package.txt') -Label 'dumpsys package'

$privacyNote = @(
    'Collection info',
    '---------------',
    "Captured      : $stamp",
    'App package   : com.whispertype.android',
    '',
    'PRIVACY: This directory may contain identifiers and app-scoped values. It is stored',
    'under a git-ignored path. Do not attach it to bug reports without reviewing its contents,',
    'and never copy transcripts, audio, API keys, or clipboard data out of the device.'
) -join "`r`n"
Set-Content -LiteralPath (Join-Path $diagDir 'README.txt') -Value $privacyNote -Encoding utf8

Write-Host ''
Write-Host ("Diagnostics saved to: {0}" -f $diagDir)
Write-Host 'Files written:'
Get-ChildItem -LiteralPath $diagDir -File | Sort-Object Name | ForEach-Object {
    Write-Host ('  {0}  ({1} bytes)' -f $_.Name, $_.Length)
}

Write-Output $diagDir
exit 0