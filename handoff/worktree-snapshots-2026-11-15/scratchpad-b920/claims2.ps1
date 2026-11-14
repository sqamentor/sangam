$c = Get-Content "D:\GITHUB  Project\sangam-handoff\FILE-CLAIMS.md"
$re = '^(\S+)\s+(B29|B30|B31|B32|B33|B34|B35|B36|B37|B38|B39|B40|B41|X1|X2|X3|integrator|INTEGRATOR)\s+(CLAIM|RELEASE|STANDING-CLAIM)\s+(.*)$'
$entries = @()
for ($i = 0; $i -lt $c.Count; $i++) {
  if ($c[$i] -match $re) { $entries += [pscustomobject]@{ L = $i + 1; W = $matches[2].ToLower(); A = $matches[3]; T = $matches[4] } }
}
$stop = @('W2','M','SL','SG','X1','X2','X3','B','CHANGE','DONE','URGENT','SANGAM','PR','Q','OK','A','B','IN','EU','US','H2','DDL','UI','AAD','SDK','SDKs','JSON','REST','API','NEW','ADR','D','FC','TS','DOCVERIFIER','GOI','RJ','IFMS','CMP','EXEC','JOB','PG','LF','CRLF')
$res = @()
foreach ($e in $entries | Where-Object { $_.A -eq 'CLAIM' }) {
  # take path part: drop parenthetical notes
  $t = [regex]::Replace($e.T, '\([^)]*\)', ' ')
  $toks = [regex]::Matches($t, '[A-Za-z][A-Za-z0-9_\-]*(\.[a-z]+)?') | ForEach-Object { $_.Value } | Where-Object { ($_ -cmatch '^[A-Z][a-z]+[A-Z]' -or $_ -match '\.[a-z]+$') -and $_.Length -gt 4 } | Select-Object -Unique
  $relText = ($entries | Where-Object { $_.W -eq $e.W -and $_.A -eq 'RELEASE' -and $_.L -gt $e.L } | ForEach-Object { $_.T }) -join ' '
  $miss = @($toks | Where-Object { -not $relText.Contains(($_ -replace '\.[a-z]+$', '')) })
  if ($miss.Count -gt 0) { $res += "$($e.L) $($e.W): missing [" + ($miss -join ', ') + "]" }
}
$res
