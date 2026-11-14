$p = "D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam\TESTING-GAP-REGISTER.md"
$b = [IO.File]::ReadAllText($p); $nl = if ($b.Contains("`r`n")) {"`r`n"} else {"`n"}
$lines = [Collections.Generic.List[string]]($b -split "`r?`n")
foreach ($id in 'SL-32','SL-40') {
  $k = $lines.FindIndex({ param($x) $x.StartsWith("| $id ") })
  $c = $lines[$k].Split('|')
  $status = ($c[5..($c.Count-2)] -join '/')
  $new = @($c[0],$c[1],$c[2],$c[3],$c[4],$status,'')
  $lines[$k] = ($new -join '|')
  "$id cells=" + $lines[$k].Split('|').Count
}
[IO.File]::WriteAllText($p, ($lines -join $nl))
