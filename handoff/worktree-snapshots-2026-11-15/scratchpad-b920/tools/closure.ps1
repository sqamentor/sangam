param([string[]]$TreeFiles, [string]$OutTsv)
# Union of dependency:tree outputs -> g:a:v, scope(s), declared licence (parent chain), policy class, pom write time.
$m2 = "$env:USERPROFILE\.m2\repository"
function PomPath($g, $a, $v) { Join-Path $m2 (($g -replace '\.', '\') + "\$a\$v\$a-$v.pom") }
function Lic($g, $a, $v, $depth) {
  if ($depth -gt 10) { return "?" }
  $p = PomPath $g $a $v
  if (-not (Test-Path $p)) { return "(pom not cached)" }
  [xml]$x = Get-Content $p -Raw
  $pr = $x.project
  if ($pr.licenses -and $pr.licenses.license) { return (@($pr.licenses.license) | % { ($_.name -replace '\s+', ' ').Trim() }) -join ' / ' }
  if ($pr.parent) { return Lic $pr.parent.groupId $pr.parent.artifactId $pr.parent.version ($depth + 1) }
  return "(none declared)"
}
function Classify($l) {
  $t = $l.ToLower()
  if ($t -match 'lesser|lgpl') { return 'LGPL: NOT ALLOWED (only MariaDB LIC-5)' }
  if ($t -match 'affero|agpl') { return 'AGPL: FORBIDDEN' }
  if ($t -match 'apache') { return 'allowed (Apache-2.0)' }
  if ($t -match 'mit') { return 'allowed (MIT/MIT-0)' }
  if ($t -match 'bsd') { return 'allowed (BSD)' }
  if ($t -match 'epl|eclipse public|eclipse distribution') { return 'allowed (EPL/EDL; GPL alternative is a dual-licence option)' }
  if ($t -match 'mpl') { return 'allowed (MPL-2.0)' }
  if ($t -match 'universal permissive|upl') { return 'allowed (UPL-1.0)' }
  if ($t -match 'cddl') { return 'allowed (CDDL, dual with GPL+CPE)' }
  if ($t -match 'public domain') { return 'allowed (LicenseRef-Public-Domain)' }
  if ($t -match 'classpath exception') { return 'allowed (GPL-2.0-with-classpath-exception)' }
  if ($t -match 'gpl') { return 'GPL: FORBIDDEN' }
  return 'UNKNOWN: check by hand'
}
$rows = @{}
foreach ($f in $TreeFiles) {
  Get-Content $f | Select -Skip 1 | % {
    $line = $_ -replace '^[\s\|\+\\-]+', ''
    $p = $line.Split(':')
    if ($p.Count -ge 5) {
      $g = $p[0]; $a = $p[1]
      if ($p.Count -ge 6) { $v = $p[4]; $sc = $p[5] } else { $v = $p[3]; $sc = $p[4] }
      $sc = ($sc -split ' ')[0]
      $k = "${g}:${a}:${v}"
      if (-not $rows[$k]) { $rows[$k] = @{ scopes = @{}; } }
      $rows[$k].scopes[$sc] = 1
    }
  }
}
$out = foreach ($k in ($rows.Keys | Sort-Object)) {
  $g, $a, $v = $k.Split(':')
  $l = Lic $g $a $v 0
  $pp = PomPath $g $a $v
  $t = if (Test-Path $pp) { (Get-Item $pp).LastWriteTimeUtc.ToString('yyyy-MM-ddTHH:mm:ssZ') } else { '' }
  [pscustomobject]@{ gav = $k; scope = ($rows[$k].scopes.Keys -join ','); licence = $l; class = (Classify $l); pomTime = $t }
}
$out | Export-Csv -NoTypeInformation -Delimiter "`t" -Path $OutTsv -Encoding utf8
$out
