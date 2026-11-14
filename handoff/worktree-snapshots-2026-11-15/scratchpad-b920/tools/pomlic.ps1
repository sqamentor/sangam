param([string]$TreeFile)
# Prints group:artifact:version and the licence names declared in its pom (following the parent chain).
$m2 = "$env:USERPROFILE\.m2\repository"
function PomPath($g, $a, $v) { Join-Path $m2 ((($g -replace '\.', '\') + "\$a\$v\$a-$v.pom")) }
function Lic($g, $a, $v, $depth) {
  if ($depth -gt 8) { return "?" }
  $p = PomPath $g $a $v
  if (-not (Test-Path $p)) { return "(pom not cached)" }
  [xml]$x = Get-Content $p -Raw
  $ns = $x.project
  if ($ns.licenses -and $ns.licenses.license) {
    return (@($ns.licenses.license) | % { $_.name }) -join ' / '
  }
  if ($ns.parent) { return Lic $ns.parent.groupId $ns.parent.artifactId $ns.parent.version ($depth + 1) }
  return "(none declared)"
}
$seen = @{}
Get-Content $TreeFile | Select -Skip 1 | % {
  if ($_ -match '([\w\.\-]+):([\w\.\-]+):(jar|pom|maven-plugin):([\w\.\-]+):(\w+)') {
    $k = "$($matches[1]):$($matches[2]):$($matches[4])"
    if (-not $seen[$k]) { $seen[$k] = 1; "{0} | {1} | {2}" -f $k, $matches[5], (Lic $matches[1] $matches[2] $matches[4] 0) }
  }
}
