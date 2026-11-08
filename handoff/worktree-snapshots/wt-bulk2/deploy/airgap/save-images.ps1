# SPDX-License-Identifier: Apache-2.0
<#
.SYNOPSIS
  Builds the Sangam air-gapped installation bundle on Windows (same layout as save-images.sh).

.EXAMPLE
  powershell -File deploy\airgap\save-images.ps1 -Version 0.1.0
  powershell -File deploy\airgap\save-images.ps1 -Version 0.1.0 -Registry ghcr.io/sqamentor -WithObservability
  # On the shared build machine, inside the heavy lock:
  python -E -X utf8 "$env:TEMP\heavy_lock.py" run --owner customwf -- powershell -File deploy\airgap\save-images.ps1 -Version 0.1.0

.NOTES
  Needs docker and tar (Windows 10+ ships bsdtar). Optional: syft (image SBOMs), helm (chart package).
  -WithDevDatabases saves Oracle Free / SQL Server Developer / MySQL images, which Sangam does NOT redistribute.
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$Version,
  [string]$Registry = "ghcr.io/sqamentor",
  [string]$Out = (Join-Path $PSScriptRoot "out"),
  [switch]$WithObservability,
  [switch]$WithDevDatabases,
  [string]$Platform = "linux/amd64",
  [switch]$NoPull,
  [string]$SbomDir = "",
  [switch]$AllowMissing
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$deploy = Split-Path -Parent $PSScriptRoot
$name = "sangam-airgap-$Version"
$stage = Join-Path $Out $name

if ($WithDevDatabases) {
  Write-Warning ("-WithDevDatabases saves Oracle Free, SQL Server Developer and MySQL Community images. " +
    "Sangam does NOT redistribute them (SANGAM.md X.3a); use only for your own test environment under your own " +
    "licence acceptance, and do not pass the bundle to third parties.")
}

if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
foreach ($d in "images", "sbom", "chart", "compose", "docs") { New-Item -ItemType Directory -Force (Join-Path $stage $d) | Out-Null }

function Test-Group([string]$g) {
  switch ($g) {
    "product" { return $true }
    "runtime" { return $true }
    "observability" { return [bool]$WithObservability }
    "devdb" { return [bool]$WithDevDatabases }
    default { return $false }
  }
}

function Get-Sha256([string]$path) { (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLowerInvariant() }

$haveSyft = [bool](Get-Command syft -ErrorAction SilentlyContinue)
$lock = @("# group image digest file")

foreach ($line in Get-Content (Join-Path $PSScriptRoot "images.txt")) {
  $t = $line.Trim()
  if ($t -eq "" -or $t.StartsWith("#")) { continue }
  $parts = $t -split "\s+", 2
  $group = $parts[0]; $ref = $parts[1]
  if (-not (Test-Group $group)) { continue }
  $ref = $ref.Replace('${SANGAM_VERSION}', $Version).Replace('${SANGAM_REGISTRY}', $Registry)
  Write-Host ">> [$group] $ref"
  if (-not $NoPull) {
    docker pull --platform $Platform $ref
    if ($LASTEXITCODE -ne 0) {
      if ($AllowMissing) { Write-Warning "skipping $ref (pull failed)"; $lock += "# missing $group $ref"; continue }
      throw "docker pull failed: $ref"
    }
  }
  $digest = (docker image inspect --format '{{if .RepoDigests}}{{index .RepoDigests 0}}{{end}}' $ref 2>$null)
  if (-not $digest) { $digest = "none" }
  $safe = ($ref -replace '[/:@]', '_')
  $tar = Join-Path $stage "images\$safe.tar"
  docker save -o $tar $ref
  if ($LASTEXITCODE -ne 0) { throw "docker save failed: $ref" }
  # gzip with .NET (no external gzip needed)
  $gz = "$tar.gz"
  $in = [IO.File]::OpenRead($tar); $outS = [IO.File]::Create($gz)
  $z = New-Object IO.Compression.GZipStream($outS, [IO.Compression.CompressionLevel]::Optimal)
  try { $in.CopyTo($z) } finally { $z.Dispose(); $outS.Dispose(); $in.Dispose() }
  Remove-Item $tar
  $lock += "$group $ref $digest images/$safe.tar.gz"
  if ($haveSyft) {
    syft "docker:$ref" -o "cyclonedx-json=$(Join-Path $stage "sbom\$safe.cdx.json")" -q
    if ($LASTEXITCODE -ne 0) { Write-Warning "syft failed for $ref" }
  }
}
if (-not $haveSyft) { Write-Host "NOTE: syft not found - image SBOMs skipped (release CI produces them)" }
Set-Content -Encoding utf8 -Path (Join-Path $stage "images.lock") -Value $lock

if (Get-Command helm -ErrorAction SilentlyContinue) {
  helm package (Join-Path $deploy "helm\sangam") --version $Version --app-version $Version -d (Join-Path $stage "chart")
} else {
  tar -C (Join-Path $deploy "helm") -czf (Join-Path $stage "chart\sangam-$Version.tgz") sangam
}

# compose directory without local secrets / data / vendor drivers
Copy-Item -Recurse -Force (Join-Path $deploy "compose\*") (Join-Path $stage "compose")
foreach ($p in ".env", "data") { $x = Join-Path $stage "compose\$p"; if (Test-Path $x) { Remove-Item -Recurse -Force $x } }
Get-ChildItem (Join-Path $stage "compose\drivers") -Filter *.jar -ErrorAction SilentlyContinue | Remove-Item -Force

Copy-Item (Join-Path $deploy "README.md") (Join-Path $stage "docs\OPERATIONS.md")
Copy-Item (Join-Path $deploy "docker\server\SOURCE-OFFER.txt") (Join-Path $stage "docs\SOURCE-OFFER-mariadb.txt")
foreach ($f in "load-images.sh", "load-images.ps1", "verify-bundle.sh") { Copy-Item (Join-Path $PSScriptRoot $f) $stage }
$root = Split-Path -Parent (Split-Path -Parent $deploy)
foreach ($f in "LICENSE", "NOTICE") { $p = Join-Path $root $f; if (Test-Path $p) { Copy-Item $p (Join-Path $stage "docs") } }
if ($SbomDir -and (Test-Path $SbomDir)) { Copy-Item (Join-Path $SbomDir "*.json") (Join-Path $stage "sbom") -ErrorAction SilentlyContinue }

@"
Sangam air-gapped bundle
version          : $Version
source registry  : $Registry
platform         : $Platform
created (UTC)    : $((Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ"))
observability    : $(if ($WithObservability) { "included" } else { "not included" })
dev databases    : $(if ($WithDevDatabases) { "included (NOT redistributable)" } else { "not included" })
Verify           : ./verify-bundle.sh  or  load-images.ps1 -VerifyOnly
Load             : ./load-images.sh --registry <internal-registry>
Docs             : docs/OPERATIONS.md ("Air-gapped install")
"@ | Set-Content -Encoding utf8 (Join-Path $stage "BUNDLE-INFO.txt")

# SHA256SUMS in GNU coreutils format ("<hash>  ./relative/path") so sha256sum -c works on Linux.
$sums = Get-ChildItem -Recurse -File $stage | Where-Object { $_.Name -ne "SHA256SUMS" } | Sort-Object FullName | ForEach-Object {
  $rel = "./" + $_.FullName.Substring($stage.Length + 1).Replace('\', '/')
  "$(Get-Sha256 $_.FullName)  $rel"
}
[IO.File]::WriteAllLines((Join-Path $stage "SHA256SUMS"), $sums)

$bundle = Join-Path $Out "$name.tar"
tar -C $Out -cf $bundle $name
if ($LASTEXITCODE -ne 0) { throw "tar failed" }
"$(Get-Sha256 $bundle)  $name.tar" | Set-Content -Encoding ascii "$bundle.sha256"
Write-Host "bundle: $bundle"
Get-Content "$bundle.sha256"
