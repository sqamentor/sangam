# SPDX-License-Identifier: Apache-2.0
<#
.SYNOPSIS
  Windows variant of generate-sbom.sh: CycloneDX SBOMs (Maven aggregate, sangam-ui, TypeScript SDK) + SHA256SUMS
  + licence policy check.
.EXAMPLE
  python -E -X utf8 "$env:TEMP\heavy_lock.py" run --owner customwf -- powershell -File deploy\airgap\generate-sbom.ps1
#>
[CmdletBinding()]
param([string]$Out = (Join-Path $PSScriptRoot "out\sbom"), [switch]$Offline)
$ErrorActionPreference = "Stop"
$sangam = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
New-Item -ItemType Directory -Force $Out | Out-Null
$mvnOff = @(); $npmOff = @()
if ($Offline) { $mvnOff = @("-o"); $npmOff = @("--offline") }

Push-Location $sangam
try {
  & .\mvnw.cmd -B -ntp @mvnOff -DskipTests "org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom" `
    "-DoutputFormat=json" "-DoutputName=sangam-maven.cdx" "-DschemaVersion=1.6" `
    "-DincludeTestScope=false" "-DincludeProvidedScope=false" "-DincludeSystemScope=false"
  if ($LASTEXITCODE -ne 0) { throw "maven SBOM failed" }
  Copy-Item target\sangam-maven.cdx.json $Out -Force
} finally { Pop-Location }

foreach ($pair in @(@("sangam-ui", "sangam-ui"), @("sdks\typescript", "sdk-typescript"))) {
  $dir = Join-Path $sangam $pair[0]
  if (-not (Test-Path (Join-Path $dir "package.json"))) { Write-Host "skip $($pair[1])"; continue }
  Push-Location $dir
  try {
    if (-not (Test-Path node_modules)) { npm ci --no-audit --no-fund @npmOff }
    npx --yes @npmOff "@cyclonedx/cyclonedx-npm@2.1.0" --omit dev --spec-version 1.6 --output-format JSON --output-file (Join-Path $Out "$($pair[1]).cdx.json")
    if ($LASTEXITCODE -ne 0) { throw "npm SBOM failed: $($pair[1])" }
  } finally { Pop-Location }
}

# Licence policy before the checksums: --annotate records the SG-27 / SD-301 owner exception (reason, verified SPDX,
# evidence file) as CycloneDX properties on the exact excepted components and in metadata.properties.
$pyArgs = @("-I", (Join-Path $sangam "deploy\licence\check_licences.py"), "--policy", (Join-Path $sangam "deploy\licence\licence-policy.json"),
          "--annotate", "--sbom", (Join-Path $Out "sangam-maven.cdx.json"), "--report", (Join-Path $Out "licence-report.md"))
$ui = Join-Path $Out "sangam-ui.cdx.json"
if (Test-Path $ui) { $pyArgs += @("--sbom", $ui) }
python @pyArgs
if ($LASTEXITCODE -ne 0) { throw "licence policy check failed (see $Out\licence-report.md)" }
# Attribution the exception carries (Apache-2.0 section 4(d) NOTICE of scala-library; MIT notices of com.lihaoyi)
$notice = Join-Path (Split-Path -Parent $sangam) "NOTICE"
if (Test-Path $notice) { Copy-Item $notice (Join-Path $Out "NOTICE") -Force }

$sums = Get-ChildItem $Out -Filter *.json | Sort-Object Name | ForEach-Object { "$((Get-FileHash -Algorithm SHA256 $_.FullName).Hash.ToLowerInvariant())  $($_.Name)" }
[IO.File]::WriteAllLines((Join-Path $Out "SHA256SUMS"), $sums)
Write-Host "SBOMs in $Out"
