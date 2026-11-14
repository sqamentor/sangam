$c = Get-Content "D:\GITHUB  Project\sangam-handoff\FILE-CLAIMS.md"
$re = '^(\S+)\s+(B29|B30|B31|B32|B33|B34|B35|B36|B37|B38|B39|B40|B41|X1|X2|X3|integrator|INTEGRATOR)\s+(CLAIM|RELEASE|STANDING-CLAIM)\s+(.*)$'
$entries = @()
for ($i = 0; $i -lt $c.Count; $i++) {
  if ($c[$i] -match $re) {
    $w = $matches[2].ToLower()
    $entries += [pscustomobject]@{ L = $i + 1; W = $w; A = $matches[3]; T = $matches[4] }
  }
}
function Get-Names($t) {
  # strip parenthetical notes
  $s = $t
  $names = [regex]::Matches($s, '[A-Za-z0-9_\-]+\.(java|py|yaml|yml|md|json|sql|ts|tsx|xml|bpmn|cs|txt|mjs|csproj|toml|kts|gradle|php|go|rs|properties|Dockerfile|feature|gitattributes)\b') | ForEach-Object { $_.Value } | Select-Object -Unique
  return $names
}
$report = @()
foreach ($e in $entries | Where-Object { $_.A -eq 'CLAIM' }) {
  $pathPart = ($e.T -split ' \(')[0]
  $names = @(Get-Names $pathPart)
  if ($names.Count -eq 0) { $names = @($pathPart.Trim()) }
  $rels = @($entries | Where-Object { $_.W -eq $e.W -and $_.A -eq 'RELEASE' -and $_.L -gt $e.L })
  $missing = @()
  foreach ($n in $names) {
    $hit = $rels | Where-Object { $_.T.Contains($n) }
    if (-not $hit) { $missing += $n }
  }
  if ($missing.Count -gt 0) {
    $report += [pscustomobject]@{ L = $e.L; W = $e.W; Missing = ($missing -join ', '); Claim = $e.T.Substring(0, [Math]::Min(230, $e.T.Length)) }
  }
}
"claims: " + ($entries | Where-Object { $_.A -eq 'CLAIM' }).Count + "  releases: " + ($entries | Where-Object { $_.A -eq 'RELEASE' }).Count
$entries | Group-Object W | ForEach-Object { "$($_.Name): C=" + ($_.Group | Where-Object A -eq 'CLAIM').Count + " R=" + ($_.Group | Where-Object A -eq 'RELEASE').Count }
"---- unmatched ----"
$report | Format-List | Out-String -Width 300
