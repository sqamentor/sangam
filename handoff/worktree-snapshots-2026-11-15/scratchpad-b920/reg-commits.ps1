$p = "D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam\TESTING-GAP-REGISTER.md"
$b = [IO.File]::ReadAllText($p); $nl = if ($b.Contains("`r`n")) {"`r`n"} else {"`n"}
$lines = [Collections.Generic.List[string]]($b -split "`r?`n")
$map = [ordered]@{
 'SL-01'='15e4135'; 'SL-02'='15e4135'; 'SL-17'='2aaf44f'; 'SL-39'='2aaf44f'; 'SL-43'='2aaf44f + 460c95b (deadlock fix)';
 'SL-48'='2aaf44f'; 'SL-49'='2aaf44f'; 'SL-50'='2aaf44f'; 'SL-51'='2aaf44f'; 'SL-52'='2aaf44f'
}
foreach ($id in $map.Keys) {
  $k = $lines.FindIndex({ param($x) $x.StartsWith("| $id ") }); if ($k -lt 0) { "MISSING $id"; continue }
  $c = $lines[$k].Split('|'); $i = $c.Count-2
  $c[$i] = $c[$i].Replace('FIXED in working tree','FIXED in commit ' + $map[$id] + ' (on main; restated 2026-11-12 from git log -S evidence)')
  $lines[$k] = ($c -join '|')
}
$k = $lines.FindIndex({ param($x) $x.StartsWith("| SL-60 ") }); $c = $lines[$k].Split('|'); $i=$c.Count-2
$c[$i] = $c[$i].TrimEnd() + '; git evidence (2026-11-12): the fix is UNCOMMITTED (SecurityDialectTest is untracked and the HEAD SecurityDialect still has the old BITAND). It lands with the next push. '
$lines[$k] = ($c -join '|')
[IO.File]::WriteAllText($p, ($lines -join $nl)); "ok"
