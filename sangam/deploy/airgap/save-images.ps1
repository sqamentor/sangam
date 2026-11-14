# SPDX-License-Identifier: Apache-2.0
<#
.SYNOPSIS
  Builds the Sangam air-gapped installation bundle on Windows (same layout and the same strict rules as save-images.sh).

.EXAMPLE
  powershell -File deploy\airgap\save-images.ps1 -Version 0.1.0 -SbomDir dist\sbom
  powershell -File deploy\airgap\save-images.ps1 -Version 0.1.0 -SbomDir dist\sbom -NoticesDir dist\notices -Registry ghcr.io/sqamentor -WithObservability
  # On the shared build machine, inside the heavy lock:
  python -E -X utf8 "$env:TEMP\heavy_lock.py" run --owner customwf -- powershell -File deploy\airgap\save-images.ps1 -Version 0.1.0 -SbomDir dist\sbom

.NOTES
  Needs docker, python, syft and tar (Windows 10+ ships bsdtar). Optional: helm (chart package).
  Every step is strict (RM-12): an image that cannot be pulled, an image SBOM syft cannot write, a missing LICENSE,
  NOTICE or source offer, or an empty -SbomDir stops the bundle. The image list first passes the bundle licence gate
  (bundle_licence_gate.py, RM-13). -WithDevDatabases saves Oracle Free / SQL Server Developer / MySQL images, which
  Sangam does NOT redistribute.
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$Version,
  [Parameter(Mandatory = $true)][string]$SbomDir,
  [string]$NoticesDir = "",
  [string]$Registry = "ghcr.io/sqamentor",
  [string]$Out = (Join-Path $PSScriptRoot "out"),
  [switch]$WithObservability,
  [switch]$WithDevDatabases,
  [string]$Platform = "linux/amd64",
  [switch]$NoPull
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$deploy = Split-Path -Parent $PSScriptRoot
$root = Split-Path -Parent (Split-Path -Parent $deploy)
$name = "sangam-airgap-$Version"
$stage = Join-Path $Out $name

if (-not (Get-ChildItem -Path $SbomDir -Filter *.json -File -ErrorAction Stop)) { throw "no SBOM (*.json) in $SbomDir" }
if ($NoticesDir -and -not (Test-Path -PathType Container $NoticesDir)) { throw "$NoticesDir is not a directory" }
foreach ($tool in "docker", "python", "syft", "tar") {
  if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw "$tool is required" }
}
$required = @((Join-Path $root "LICENSE"), (Join-Path $root "NOTICE"), (Join-Path $deploy "docker\server\SOURCE-OFFER.txt"),
  (Join-Path $deploy "licence\OS-PACKAGES-SOURCE-OFFER.txt"))
foreach ($f in $required) { if (-not (Test-Path -PathType Leaf $f)) { throw "$f is missing; the bundle cannot ship without it" } }

if ($WithDevDatabases) {
  Write-Warning ("-WithDevDatabases saves Oracle Free, SQL Server Developer and MySQL Community images. " +
    "Sangam does NOT redistribute them (SANGAM.md X.3a); use only for your own test environment under your own " +
    "licence acceptance, and do not pass the bundle to third parties.")
}

# RM-13, CR-06: the image list passes the licence policy before anything is saved.
python -I (Join-Path $PSScriptRoot "bundle_licence_gate.py") (Join-Path $PSScriptRoot "images.txt") (Join-Path $deploy "licence\licence-policy.json")
if ($LASTEXITCODE -ne 0) { throw "bundle licence gate failed (exit $LASTEXITCODE)" }

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

$lock = @("# group image digest file")

foreach ($line in Get-Content (Join-Path $PSScriptRoot "images.txt")) {
  $t = ($line -replace '#.*$', '').Trim()   # drop the "# licence: ..." annotation and comment lines
  if ($t -eq "") { continue }
  $parts = $t -split "\s+"
  if ($parts.Count -ne 2) { throw "images.txt: bad line: $line" }
  $group = $parts[0]; $ref = $parts[1]
  if (-not (Test-Group $group)) { continue }
  $ref = $ref.Replace('${SANGAM_VERSION}', $Version).Replace('${SANGAM_REGISTRY}', $Registry)
  Write-Host ">> [$group] $ref"
  if ($ref.Contains("@sha256:")) {
    # Decision 261: <name>:<tag>@sha256:<index digest>. Pull by the pinned digest (the tag alone may have moved), check
    # that exactly that digest is in the local store, then tag it <name>:<tag>, so the archive, the lock, the SBOM and
    # load-images.ps1 all use the human tag. Never saved by digest alone: docker load would restore an untagged image.
    $tagRef = $ref.Substring(0, $ref.IndexOf("@"))
    $pinned = $ref.Substring($ref.IndexOf("@") + 1)
    $lastPart = $tagRef.Split("/")[-1]
    if (-not $lastPart.Contains(":")) { throw "images.txt: $ref pins a digest but names no tag" }
    $repo = $tagRef.Substring(0, $tagRef.LastIndexOf(":"))
    if (-not $NoPull) {
      docker pull --platform $Platform "$repo@$pinned"
      if ($LASTEXITCODE -ne 0) { throw "docker pull failed: $repo@$pinned" }
    }
    docker image inspect "$repo@$pinned" *> $null
    if ($LASTEXITCODE -ne 0) { throw "$repo@$pinned is not in the local image store" }
    docker tag "$repo@$pinned" $tagRef
    if ($LASTEXITCODE -ne 0) { throw "docker tag failed: $repo@$pinned -> $tagRef" }
    $digest = "$repo@$pinned"
  } else {
    $tagRef = $ref
    if (-not $NoPull) {
      docker pull --platform $Platform $ref
      if ($LASTEXITCODE -ne 0) { throw "docker pull failed: $ref" }
    }
    $digest = (docker image inspect --format '{{if .RepoDigests}}{{index .RepoDigests 0}}{{end}}' $ref)
    if ($LASTEXITCODE -ne 0) { throw "docker image inspect failed: $ref" }
    if (-not $digest) { $digest = "none(local-build)" }
  }
  $ref = $tagRef
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
  syft "docker:$ref" -o "cyclonedx-json=$(Join-Path $stage "sbom\$safe.cdx.json")" -q
  if ($LASTEXITCODE -ne 0) { throw "syft failed for $ref" }
}
Set-Content -Encoding utf8 -Path (Join-Path $stage "images.lock") -Value $lock

if (Get-Command helm -ErrorAction SilentlyContinue) {
  helm package (Join-Path $deploy "helm\sangam") --version $Version --app-version $Version -d (Join-Path $stage "chart")
  if ($LASTEXITCODE -ne 0) { throw "helm package failed" }
} else {
  tar -C (Join-Path $deploy "helm") -czf (Join-Path $stage "chart\sangam-$Version.tgz") sangam
  if ($LASTEXITCODE -ne 0) { throw "tar of the chart failed" }
}

# compose directory without local secrets / data / vendor drivers
Copy-Item -Recurse -Force (Join-Path $deploy "compose\*") (Join-Path $stage "compose")
foreach ($p in ".env", "data") { $x = Join-Path $stage "compose\$p"; if (Test-Path $x) { Remove-Item -Recurse -Force $x } }
$drivers = Join-Path $stage "compose\drivers"
if (Test-Path $drivers) { Get-ChildItem $drivers -Filter *.jar | Remove-Item -Force }

Copy-Item (Join-Path $deploy "README.md") (Join-Path $stage "docs\OPERATIONS.md")
Copy-Item (Join-Path $deploy "docker\server\SOURCE-OFFER.txt") (Join-Path $stage "docs\SOURCE-OFFER-mariadb.txt")
Copy-Item (Join-Path $deploy "licence\OS-PACKAGES-SOURCE-OFFER.txt") (Join-Path $stage "docs")
foreach ($f in "load-images.sh", "load-images.ps1", "verify-bundle.sh") { Copy-Item (Join-Path $PSScriptRoot $f) $stage }
foreach ($f in "LICENSE", "NOTICE") { Copy-Item (Join-Path $root $f) (Join-Path $stage "docs") }
if ($NoticesDir) {
  $n = Join-Path $stage "docs\notices"
  New-Item -ItemType Directory -Force $n | Out-Null
  Copy-Item -Recurse -Force (Join-Path $NoticesDir "*") $n
}
Copy-Item (Join-Path $SbomDir "*.json") (Join-Path $stage "sbom")

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
Docs             : docs/OPERATIONS.md ("Air-gapped install"); licence notices in docs/ and docs/notices/
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
