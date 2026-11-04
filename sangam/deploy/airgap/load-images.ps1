# SPDX-License-Identifier: Apache-2.0
<#
.SYNOPSIS
  Verifies and loads a Sangam air-gapped bundle on Windows; optionally retags and pushes to an internal registry.
.EXAMPLE
  powershell -File load-images.ps1 -VerifyOnly
  powershell -File load-images.ps1 -Registry registry.sdc.local
#>
[CmdletBinding()]
param([string]$Registry = "", [switch]$KeepPath, [switch]$VerifyOnly)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$bad = 0
foreach ($line in Get-Content SHA256SUMS) {
  if (-not $line.Trim()) { continue }
  $hash, $rel = $line -split "\s+", 2
  $p = Join-Path $PSScriptRoot ($rel.TrimStart('*').Substring(2))
  $h = (Get-FileHash -Algorithm SHA256 -LiteralPath $p).Hash.ToLowerInvariant()
  if ($h -ne $hash) { Write-Error "checksum mismatch: $rel" -ErrorAction Continue; $bad++ }
}
if ($bad -gt 0) { throw "$bad file(s) failed verification - refusing to load" }
Write-Host "checksums OK"
if ($VerifyOnly) { return }

foreach ($line in Get-Content images.lock) {
  if ($line.StartsWith("#") -or -not $line.Trim()) { continue }
  $group, $ref, $digest, $file = $line -split "\s+"
  Write-Host ">> load $ref ($group)"
  $gz = Join-Path $PSScriptRoot $file
  $tar = [IO.Path]::ChangeExtension($gz, $null).TrimEnd('.')
  $in = [IO.File]::OpenRead($gz); $z = New-Object IO.Compression.GZipStream($in, [IO.Compression.CompressionMode]::Decompress)
  $o = [IO.File]::Create($tar)
  try { $z.CopyTo($o) } finally { $o.Dispose(); $z.Dispose(); $in.Dispose() }
  docker load -i $tar; if ($LASTEXITCODE -ne 0) { throw "docker load failed: $ref" }
  Remove-Item $tar
  if ($Registry) {
    $path = if ($ref.Contains("/")) { $ref.Substring($ref.IndexOf("/") + 1) } else { $ref }
    if (-not $KeepPath) { $path = "sangam/" + ($path.Split("/")[-1]) }
    $target = "$Registry/$path"
    docker tag $ref $target; docker push $target
    if ($LASTEXITCODE -ne 0) { throw "push failed: $target" }
    Write-Host "   pushed $target"
  }
}
Write-Host "done. Helm: set global.imageRegistry=$Registry (chart/examples/values-airgap.yaml)"
