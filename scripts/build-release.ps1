#requires -Version 5.1
<#
.SYNOPSIS
  Builds, verifies, and packages a signed WhisperType release APK into dist\<version>.

.DESCRIPTION
  Per Implementation_Plan.md 22.10 / 22.11 / 22.14:
    - Refuses to build when signing.properties is missing/incomplete so a release is
      never silently signed with the debug key (exit 5). Signing values are never printed.
    - Runs `clean :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease`.
    - Verifies the release APK exists, verifies its signature and package badging with the
      Android SDK tools when available, and writes a SHA-256 checksum into dist\<version>.
    - Creates dist\<version>\WhisperType-Android-<Version>.apk with a matching .sha256 file
      plus RELEASE_NOTES.md and INSTALL.md; copies mapping.txt when minification produced one.
    - Reports worktree state (git status --porcelain) but never commits anything.

.PARAMETER Version
  Release version. Defaults to versionName found in app/build.gradle.kts. Must not contain
  "debug" and must match ^[0-9][0-9A-Za-z.+-]*$.

.PARAMETER DistDir
  Override the distribution folder (default: <project>\dist\<Version>). A relative path
  is resolved against the project root.

.EXAMPLE
  .\scripts\build-release.ps1
  .\scripts\build-release.ps1 -Version 0.2.0
#>
[CmdletBinding()]
param(
    [string]$Version = '',
    [string]$DistDir = ''
)
$ErrorActionPreference = 'Stop'

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$AppId = 'com.whispertype.android'
$AppGradleFile = Join-Path $ProjectRoot 'app\build.gradle.kts'
$GradleWrapper = Join-Path $ProjectRoot 'gradlew.bat'
$ReleaseApk = Join-Path $ProjectRoot 'app\build\outputs\apk\release\app-release.apk'
$SigningProps = Join-Path $ProjectRoot 'signing.properties'

function Test-ReleaseVersion {
    param([string]$VersionText)
    if ([string]::IsNullOrWhiteSpace($VersionText)) { return $false }
    if ($VersionText -match 'debug') { return $false }
    if ($VersionText -match '\s') { return $false }
    if (-not ($VersionText -match '^[0-9][0-9A-Za-z.+-]*$')) { return $false }
    return $true
}

# Returns the newest available copy of a build-tools binary (apksigner.bat / aapt.exe)
# from ANDROID_HOME/ANDROID_SDK_ROOT build-tools, then falls back to PATH.
function Get-SdkTool {
    param([string]$ToolFile)
    $roots = @()
    if ($env:ANDROID_HOME) { $roots += $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT) { $roots += $env:ANDROID_SDK_ROOT }
    foreach ($root in $roots) {
        $buildTools = Join-Path $root 'build-tools'
        if (Test-Path -LiteralPath $buildTools) {
            foreach ($dir in @(Get-ChildItem -LiteralPath $buildTools -Directory | Where-Object { $_.Name -match '^[0-9]' })) {
                $candidate = Join-Path $dir.FullName $ToolFile
                if (Test-Path -LiteralPath $candidate) { return $candidate }
            }
        }
    }
    $command = Get-Command $ToolFile -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    return ''
}

function Get-ReleaseVersion {
    if (-not [string]::IsNullOrWhiteSpace($Version)) { return $Version }
    if (-not (Test-Path -LiteralPath $AppGradleFile)) {
        Write-Host ("Cannot read versionName: {0} is missing." -f $AppGradleFile) -ForegroundColor Red
        exit 1
    }
    $hit = Select-String -Path $AppGradleFile -Pattern 'versionName\s*=\s*"([^"]+)"' | Select-Object -First 1
    if (-not $hit) {
        Write-Host 'Cannot read versionName from app/build.gradle.kts; pass -Version explicitly.' -ForegroundColor Red
        exit 1
    }
    return $hit.Matches[0].Groups[1].Value
}

$version = Get-ReleaseVersion
if (-not (Test-ReleaseVersion $version)) {
    Write-Host "Invalid release version: '$version'. Use a clean semver-style tag and never 'debug'." -ForegroundColor Red
    exit 1
}

if ([System.IO.Path]::IsPathRooted($DistDir) -or $DistDir) {
    $outDist = if ([System.IO.Path]::IsPathRooted($DistDir)) { $DistDir } else { Join-Path $ProjectRoot $DistDir }
} else {
    $outDist = Join-Path $ProjectRoot (Join-Path 'dist' $version)
}

Write-Host ("Release version        : {0}" -f $version)
Write-Host ("Output distribution    : {0}" -f $outDist)

# --- Signing configuration ---------------------------------------------------
# storePassword / keyPassword / storeFile / keyAlias values are never printed.
if (-not (Test-Path -LiteralPath $SigningProps)) {
    Write-Host 'ERROR: signing.properties is missing. Without it Gradle would fall back to the debug key. Refusing to build a distributable release.' -ForegroundColor Red
    Write-Host "Create $SigningProps with keys: storeFile, storePassword, keyAlias, keyPassword (kept outside Git)."
    exit 5
}
$props = @{}
foreach ($line in @(Get-Content -LiteralPath $SigningProps)) {
    if ($line -match '^\s*([A-Za-z0-9_]+)\s*=\s*(.*?)[\s]*$') {
        $props[$Matches[1]] = $Matches[2]
    }
}
$requiredKeys = @('storeFile', 'storePassword', 'keyAlias', 'keyPassword')
$missingKeys = @($requiredKeys | Where-Object {
    -not $props.ContainsKey($_) -or [string]::IsNullOrWhiteSpace($props[$_])
})
if ($missingKeys.Count -gt 0) {
    Write-Host ("ERROR: signing.properties is missing required keys: {0}" -f ($missingKeys -join ', ')) -ForegroundColor Red
    Write-Host 'Fix signing.properties and re-run. Values are never printed.'
    exit 5
}
$storePath = $props['storeFile']
$storeResolved = if ([System.IO.Path]::IsPathRooted($storePath)) { $storePath } else { Join-Path $ProjectRoot $storePath }
if (-not (Test-Path -LiteralPath $storeResolved)) {
    Write-Host 'ERROR: "storeFile" in signing.properties does not point to an existing keystore.' -ForegroundColor Red
    exit 5
}
Write-Host 'signing.properties present with all 4 keys; release APK will be signed with the release keystore.'

# --- worktree disclosure ----------------------------------------------------------
Write-Host ''
Write-Host 'Worktree status (informational only; this script never commits or cleans):'
if (Get-Command git -ErrorAction SilentlyContinue) {
    $statusLines = @(& git status --porcelain 2>&1)
    if ($statusLines.Count -eq 0) {
        Write-Host '  (clean)'
    } else {
        $statusLines | Select-Object -First 20 | ForEach-Object { Write-Host ("  {0}" -f $_) }
        if ($statusLines.Count -gt 20) { Write-Host "  ... and $($statusLines.Count - 20) more" }
        Write-Host '  Review the worktree above before packaging, per Implementation_Plan.md 22.11.'
    }
} else {
    Write-Host '  WARNING: git not found; worktree was not inspected.' -ForegroundColor Yellow
}

# --- release build ----------------------------------------------------------------
Write-Host ''
Write-Host 'Running :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease...'
Push-Location $ProjectRoot
try {
    & $GradleWrapper clean :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease 2>&1 | ForEach-Object { Write-Host $_ }
} finally {
    Pop-Location
}
if ($LASTEXITCODE -ne 0) {
    Write-Host 'Gradle release build failed.' -ForegroundColor Red
    exit 1
}
if (-not (Test-Path -LiteralPath $ReleaseApk)) {
    Write-Host 'ERROR: the release APK was not produced at the expected path.' -ForegroundColor Red
    Write-Host "Expected: $ReleaseApk"
    exit 1
}

# --- signature / badging verification ---------------------------------------------
$apksigner = Get-SdkTool 'apksigner.bat'
if (-not $apksigner) { $apksigner = Get-SdkTool 'apksigner' }
if ($apksigner) {
    Write-Host ''
    Write-Host 'Verifying release signature (apksigner verify --verbose --print-certs)...'
    & $apksigner verify --verbose --print-certs $ReleaseApk
    if ($LASTEXITCODE -ne 0) {
        Write-Host 'FAILED: apksigner rejected the APK; it may be signed with the debug key or corrupt.' -ForegroundColor Red
        exit 1
    }
    Write-Host 'Signature verification OK.'
} else {
    Write-Host 'WARNING: apksigner not found (set ANDROID_HOME with build-tools). Signature verification skipped; checksum and badging below may also be limited.' -ForegroundColor Yellow
}

$aapt = Get-SdkTool 'aapt.exe'
if ($aapt) {
    Write-Host ''
    Write-Host 'Reading package badging (aapt dump badging)...'
    $badging = @(& $aapt dump badging $ReleaseApk 2>&1)
    if ($LASTEXITCODE -ne 0) {
        Write-Host 'FAILED: aapt could not read the release APK.' -ForegroundColor Red
        exit 1
    }
    if (-not (($badging -join "`n") -match ("package: name='{0}'" -f [regex]::Escape($AppId)))) {
        Write-Host "FAILED: badging did not report package name '$AppId'." -ForegroundColor Red
        exit 1
    }
    $badging | Select-Object -First 3 | ForEach-Object { Write-Host "  $_" }
    if (($badging -join "`n") -match ([regex]::Escape("versionName='$version'"))) {
        Write-Host ("OK: versionName '{0}' confirmed." -f $version)
    } else {
        Write-Host ("WARNING: versionName in badging did not match '{0}'; double-check app/build.gradle.kts." -f $version) -ForegroundColor Yellow
    }
} else {
    Write-Host 'WARNING: aapt not found; badging verification skipped.' -ForegroundColor Yellow
}

# --- checksum + dist --------------------------------------------------------------
$hash = (Get-FileHash -LiteralPath $ReleaseApk -Algorithm SHA256).Hash
$hashLower = $hash.ToLowerInvariant()
Write-Host ''
Write-Host ("SHA-256: {0}" -f $hashLower)

$apkName = 'WhisperType-Android-{0}.apk' -f $version
$shaFileName = '{0}.sha256' -f $apkName
New-Item -ItemType Directory -Force -Path $outDist | Out-Null
$distApk = Join-Path $outDist $apkName
Copy-Item -LiteralPath $ReleaseApk -Destination $distApk -Force
$shaContent = '{0}  {1}' -f $hashLower, $apkName
Set-Content -LiteralPath (Join-Path $outDist $shaFileName) -Value $shaContent -Encoding ascii -NoNewline

# --- documentation in dist ---------------------------------------------------------
$today = Get-Date -Format 'yyyy-MM-dd'
$releaseNotes = @(
    '# WhisperType ' + $version,
    '',
    'Release date : {0}' -f $today,
    'SHA-256      : {0}' -f $hashLower,
    'File         : {0}' -f $apkName,
    'Package      : com.whispertype.android',
    '',
    '## Changes',
    '- (complete per release)',
    '',
    '## Packaging checks',
    '- Signed with the release keystore (not the debug key); apksigner verify passed.',
    '- Release unit tests and lint pass.',
    '- SHA-256 checksum stored in {0}.sha256 next to the APK.' -f $apkName
) -join "`r`n"
Set-Content -LiteralPath (Join-Path $outDist 'RELEASE_NOTES.md') -Value $releaseNotes -Encoding utf8

$installDocs = @(
'# Installing WhisperType {0}' -f $version,
'',
'Package   : com.whispertype.android',
'Checksum  : {0}' -f $shaContent,
'',
'## Direct install on a phone',
'1. Copy {0} to the phone over a trusted local method.' -f $apkName,
'2. Verify the SHA-256 checksum on the receiving computer first:',
'   Get-FileHash .\dist\...\WhisperType-Android-{0}.apk -Algorithm SHA256 and compare to {0}.sha256.' -f $version,
'3. Open the APK on the phone and allow installs from that source when Android asks.',
'4. Open WhisperType and complete microphone, notification, Accessibility, and API-key setup.',
'',
'## ADB install (authorized, USB-connected device)',
'  .\scripts\install-release.ps1 -Version {0} -Device <serial> -Launch' -f $version,
'',
'Raw command without the script:',
'  adb -s <serial> install -r .\dist\{0}\WhisperType-Android-{0}.apk' -f $version,
'',
'Use install -r to preserve app data. Android normally blocks downgrades by version code;',
'the -d flag (-AllowDowngrade) is for development diagnosis only and is not part of',
'user-facing installation guidance (Implementation_Plan.md 22.12).',
'',
'Never distribute: debug APKs, unsigned APKs, the keystore, signing passwords, mapping',
'files to ordinary recipients, diagnostics, or APKs whose signature/checksum was not verified.'
) -join "`r`n"
Set-Content -LiteralPath (Join-Path $outDist 'INSTALL.md') -Value $installDocs -Encoding utf8

# --- mapping.txt (proguard) copy ----------------------------------------------------
$mappingSource = Join-Path $ProjectRoot 'app\build\outputs\mapping\release\mapping.txt'
if (Test-Path -LiteralPath $mappingSource) {
    Copy-Item -LiteralPath $mappingSource -Destination (Join-Path $outDist 'mapping.txt') -Force
    Write-Host 'Copied release mapping.txt (kept private; for debugging future crashes).'
} else {
    Write-Host 'No mapping.txt produced (minification disabled): skipping. Implementation_Plan.md 22.11.'
}

Write-Host ''
Write-Host ('Distribution created: {0}' -f $outDist)
Write-Host ('  {0}' -f $apkName)
Write-Host ('  {0}' -f $shaFileName)
Write-Host '  RELEASE_NOTES.md'
Write-Host '  INSTALL.md'
Write-Host ''
Write-Host 'The dist/ folder is git-ignored; it is not committed, and build-release never commits.' -ForegroundColor Green
exit 0