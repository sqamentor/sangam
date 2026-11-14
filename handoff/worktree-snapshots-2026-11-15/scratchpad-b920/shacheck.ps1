$root = "D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524"
$hand = "D:\GITHUB  Project\sangam-handoff"
$scr = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad"
$files = Get-Content "$scr\files.txt" | Where-Object { $_ -notlike 'handoff/*' -and $_ -notlike 'spike/*' }
$board = Get-Content "$hand\WAVE2-SYNC-BOARD.md"
$claimsRaw = Get-Content "$hand\FILE-CLAIMS.md" -Raw
$cache = @{}
function Get-Sha($fp) { if (-not $cache.ContainsKey($fp)) { if (Test-Path -LiteralPath $fp) { $cache[$fp] = (Get-FileHash -LiteralPath $fp -Algorithm SHA256).Hash } else { $cache[$fp] = $null } }; return $cache[$fp] }
$pairs = @()
for ($i = 0; $i -lt $board.Count; $i++) {
  $line = $board[$i]; $ln = $i + 1
  if ($line -notmatch '\| (DONE|CHANGE\+DONE) \|') { continue }
  foreach ($m in [regex]::Matches($line, '([A-Za-z0-9_./\-]+\.(?:java|py|yaml|yml|md|json|sql|ts|tsx|xml|bpmn|cs|txt))(?:\s*\((?:new)\))?\s+([0-9A-F]{16,64})\b')) {
    $pairs += [pscustomobject]@{ L = $ln; Name = $m.Groups[1].Value; Sha = $m.Groups[2].Value }
  }
}
$out = @()
foreach ($p in $pairs) {
  $n = $p.Name.TrimStart('/')
  if ($n -like 'W2-*' -or $n -like 'sangam-handoff/*') { $cands = @("HANDOFF::" + ($n -replace '^sangam-handoff/', '')) }
  else { $cands = @($files | Where-Object { $_ -eq $n -or $_.EndsWith('/' + $n) }) }
  $hashes = @()
  foreach ($c in $cands) {
    if ($c -like 'HANDOFF::*') { $fp = Join-Path $hand ($c.Substring(9)) } else { $fp = Join-Path $root $c }
    $hashes += [pscustomobject]@{ Path = $c; Hash = (Get-Sha $fp) }
  }
  $match = @($hashes | Where-Object { $_.Hash -and $_.Hash.StartsWith($p.Sha) })
  $later = @($pairs | Where-Object { $_.Name -eq $p.Name -and $_.L -gt $p.L })
  $status = if ($cands.Count -eq 0) { 'NOFILE' } elseif ($match.Count -gt 0) { 'MATCH' } elseif ($later.Count -gt 0) { 'SUPERSEDED-BY-BOARD' } else { 'MISMATCH' }
  $inClaims = $false
  foreach ($hh in $hashes) { if ($hh.Hash -and $claimsRaw.Contains($hh.Hash)) { $inClaims = $true } }
  $liveShort = ($hashes | Where-Object { $_.Hash } | ForEach-Object { $_.Hash.Substring(0, 16) }) -join ','
  $out += [pscustomobject]@{ L = $p.L; Name = $p.Name; Board = $p.Sha.Substring(0, 16); Status = $status; NCand = $cands.Count; Live = $liveShort; LiveInClaims = $inClaims; Paths = ($cands -join ';') }
}
$out | Export-Csv "$scr\sha.csv" -NoTypeInformation -Encoding utf8
"pairs: $($pairs.Count)"
$out | Group-Object Status | ForEach-Object { "$($_.Name): $($_.Count)" }
$out | Where-Object { $_.Status -ne 'MATCH' } | Format-Table L, Name, Board, Status, NCand, Live, LiveInClaims -AutoSize | Out-String -Width 300
