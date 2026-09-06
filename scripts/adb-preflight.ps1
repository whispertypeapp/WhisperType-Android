#requires -Version 5.1
<#
.SYNOPSIS
  Locates adb, lists connected devices, resolves a single target device, and prints
  non-sensitive device/build information. Writes the chosen serial as the last line.

.DESCRIPTION
  Rules enforced here (Implementation_Plan.md 22.14):
    - Never defaults to "every connected device". With zero or multiple devices the
      script fails instead of guessing.
    - Never prints secrets or personal data: only model/brand/OS properties are shown.
    - An explicit -Device serial must exist and be authorized ("device" state) or the
      script fails with instructions.

.PARAMETER Device
  Explicit device serial to target. Optional; when omitted a sole authorized device
  is auto-selected.

.EXAMPLE
  .\scripts\adb-preflight.ps1
  .\scripts\adb-preflight.ps1 -Device R58M12345
#>
[CmdletBinding()]
param(
    [string]$Device = ''
)
$ErrorActionPreference = 'Stop'

# PowerShell native tools never get auto-stopped; explicit exit codes are used below
# so every failure path is deterministic. Exit codes: 0 ok, 2 adb not found, 3 device.
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

# Returns the device table from `adb devices -l` as pscustomobjects (Serial/State).
function Get-ConnectedDeviceList {
    param([string]$AdbPath)
    $rawLines = @(& $AdbPath devices -l)
    $result = @()
    foreach ($raw in $rawLines) {
        $line = ($raw -as [string]).Trim()
        if ($line.Length -eq 0 -or $line -like 'List of devices attached*') { continue }
        $parts = @($line -split '\s+')
        if ($parts.Count -lt 2) { continue }
        $state = $parts[1]
        if ($parts[1] -eq 'no' -and $parts.Count -ge 3 -and $parts[2] -eq 'permissions') {
            $state = 'no permissions'
        }
        $result += [pscustomobject]@{
            Serial = $parts[0]
            State  = $state
        }
    }
    return ,$result
}

$adb = Get-AdbPath
if (-not $adb) {
    Write-Host 'ERROR: adb was not found. Set ANDROID_HOME or ANDROID_SDK_ROOT, or add platform-tools to PATH.' -ForegroundColor Red
    exit 2
}
Write-Host "Using adb: $adb"

$devices = @(Get-ConnectedDeviceList -AdbPath $adb)
if ($devices.Count -eq 0) {
    Write-Host 'No Android devices are currently connected to adb.' -ForegroundColor Yellow
    Write-Host 'Connect, unlock, and authorize a phone, then re-run.'
    exit 3
}

Write-Host ''
Write-Host 'Connected devices:'
foreach ($d in $devices) {
    Write-Host ('  {0}    {1}' -f $d.Serial, $d.State)
}
Write-Host ''

function Assert-UsableDevice {
    param([string]$TargetSerial, [string]$State)
    if ($State -eq 'device') { return }
    if ($State -like 'no permissions') {
        Write-Host 'adb reports "no permissions" for the target. On Linux run adb as an authorized user or fix udev rules; on Windows restart the adb server and accept the USB debugging prompt.' -ForegroundColor Yellow
    } elseif ($State -like 'unauthorized*') {
        Write-Host 'Target device is "unauthorized". Check the phone and accept the "Allow USB debugging" dialog, then re-run.' -ForegroundColor Yellow
    } else {
        Write-Host ("Target device '{0}' is '{1}'. Reconnect and authorize it before deploying." -f $TargetSerial, $State) -ForegroundColor Yellow
    }
    exit 3
}

$serial = ''
if (-not [string]::IsNullOrWhiteSpace($Device)) {
    $matched = @($devices | Where-Object { $_.Serial -eq $Device })
    if ($matched.Count -eq 0) {
        Write-Host "Requested device '$Device' was not seen by adb. Check the serial and re-run." -ForegroundColor Red
        exit 3
    }
    Assert-UsableDevice -TargetSerial $matched[0].Serial -State $matched[0].State
    $serial = $matched[0].Serial
} elseif ($devices.Count -eq 1) {
    Assert-UsableDevice -TargetSerial $devices[0].Serial -State $devices[0].State
    $serial = $devices[0].Serial
} else {
    Write-Host 'Multiple devices are connected. Pass -Device <serial> to select exactly one.' -ForegroundColor Yellow
    foreach ($d in $devices) {
        Write-Host ('  {0}  ({1})' -f $d.Serial, $d.State)
    }
    exit 3
}

Write-Host ''
Write-Host ("Target device: {0}" -f $serial)
foreach ($prop in @('ro.product.model', 'ro.product.brand', 'ro.build.version.release', 'ro.build.version.sdk')) {
    $value = (& $adb -s $serial shell getprop $prop 2>$null | Out-String).Trim()
    Write-Host ('  {0} = {1}' -f $prop, $value)
}

# Last line of stdout is the target serial; callers capture only this value.
Write-Output $serial
exit 0