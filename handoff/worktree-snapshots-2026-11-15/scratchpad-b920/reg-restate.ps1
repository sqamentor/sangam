$p = "D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam\TESTING-GAP-REGISTER.md"
$b = [IO.File]::ReadAllText($p); $nl = if ($b.Contains("`r`n")) {"`r`n"} else {"`n"}
$lines = [Collections.Generic.List[string]]($b -split "`r?`n")
function SetLast($id, $s, [switch]$append) {
  $k = $lines.FindIndex({ param($x) $x.StartsWith("| $id ") }); if ($k -lt 0) { "MISSING $id"; return }
  $c = $lines[$k].Split('|'); $i = $c.Count-2
  if ($append) { $c[$i] = $c[$i].TrimEnd() + '; ' + $s + ' ' } else { $c[$i] = ' ' + $s + ' ' }
  $lines[$k] = ($c -join '|')
}
$note = 'restated 2026-11-12 on evidence: committed in 15e4135, an ancestor of HEAD; the commit message lists this item'
foreach ($id in 'SG-09','SG-10','SG-11','SG-26','SG-28','SG-29') { SetLast $id ("FIXED in commit 15e4135 (" + $note + ")") }
SetLast 'SG-08' ('(a)(b)(c) FIXED in commit 15e4135 (' + $note + '). Strict XOR is product policy under ADR-D-25. The SG-35 audit of routing incidents is in 0d04fea; RoutingIncidentContractTest and Sg35BillBandRegressionH2Test were added 2026-11-12, and their tests are pending')
SetLast 'SG-21' 'CLOSED: committed in 15e4135 (.gitignore fix plus guard script and the CI step "No source file hidden by .gitignore", ci-build.yml:73); IFMS E-54 closed it. A recurrence was found and fixed as SL-63.'
SetLast 'SG-06' 'CI lane EXISTS: ci-build.yml:38 runs java ["25","21","27"] (restated 2026-11-12). The local unit PASS on JDK 27 was recorded 2026-11-08. A fresh CI run is due after the push.' -append
SetLast 'SG-12' 'SECURITY.md exists at the repo root (restated 2026-11-12). Still open as an owner action: enabling GitHub private vulnerability reporting and secret-scanning validity checks (Q17).'
SetLast 'SL-16' 'FIXED in commit 15e4135; T35 passed in run 5 (JOINT-LEDGER). The "rerun pending (401)" note is superseded (restated 2026-11-12).'
SetLast 'SL-19' 'Signing is active: the owner public key was added in 3cf7841 (git cat-file confirms the commit), so owner-signed push approvals are in force (restated 2026-11-12).' -append
[IO.File]::WriteAllText($p, ($lines -join $nl)); "ok"
