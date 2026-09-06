#requires -Version 5.1
<#
.SYNOPSIS
  Scans the repository for committed secrets and forbidden manifest files.

.DESCRIPTION
  Per Implementation_Plan.md 3.1 / 19: verifies that no Gemini API key, release
  keystore, signing password, or private transcript ever lands in tracked source.
  Exits non-zero on any hit so the check can gate release steps.

  Only scanned files are reported (filename + matched pattern family); the matched
  secret content is never printed. The common unprotected local files (.env, *.jks,
  *.keystore, *.key, local.properties, signing.properties) are flagged if present at
  all. Release keystores are intentionally expected to live OUTSIDE the repo, so a hit
  here always means they were mistakenly copied in.

.PARAMETER Root
  Repository root to scan (defaults to this script's parent directory).

.EXAMPLE
  .\scripts\verify-secrets.ps1
#>
[CmdletBinding()]
param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot '..'))
)

$ErrorActionPreference = 'Stop'

# Real Gemini API keys always begin with "AIza".
$keyPatterns = @(
    'AIza[0-9A-Za-z_-]{35,}',
    '(?i)api[_-]?key['"'"':= \t]+AIza[0-9A-Za-z_-]{20,}'
)

# File leaf names that must never be committed regardless of content.
$forbiddenLeaf = @(
    'signing.properties',
    'local.properties',
    '.env',
    'seed.txt',
    'key.txt',
    '*.jks',
    '*.keystore',
    '*.key',
    '*.p12'
)

$hits = 0

function Test-ForbiddenLeaf {
    param([string]$leaf)
    foreach ($pattern in $forbiddenLeaf) {
        if ($leaf -like $pattern) { return $true }
    }
    return $false
}

# Use the tracked file set when this is a Git working tree; otherwise walk the tree.
$tracked = & git -C $Root ls-files 2>$null
if ($LASTEXITCODE -eq 0) {
    $files = $tracked
} else {
    $files = Get-ChildItem -Path $Root -Recurse -File -ErrorAction SilentlyContinue |
        ForEach-Object { $_.FullName }
}

foreach ($file in $files) {
    $full = Join-Path $Root $file
    if (-not (Test-Path -LiteralPath $full)) { continue }

    if (Test-ForbiddenLeaf (Split-Path $full -Leaf)) {
        Write-Host "FORBIDDEN manifest file in repo: $file" -ForegroundColor Red
        $hits++
        continue
    }

    $content = Get-Content -LiteralPath $full -Raw -ErrorAction SilentlyContinue
    if (-not $content) { continue }

    foreach ($pattern in $keyPatterns) {
        if ($content -match $pattern) {
            Write-Host "POSSIBLE SECRET in $file (pattern: $pattern)" -ForegroundColor Yellow
            $hits++
            break
        }
    }
}

if ($hits -gt 0) {
    Write-Host "Secrets audit FAILED: $hits hit(s). Do not build/release from this tree." -ForegroundColor Red
    exit 1
}

Write-Host 'Secrets audit passed: no keys or forbidden manifest files found in tracked source.' -ForegroundColor Green
exit 0