$p = "D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam\TESTING-GAP-REGISTER.md"
$b = [IO.File]::ReadAllText($p); $nl = if ($b.Contains("`r`n")) {"`r`n"} else {"`n"}
$lines = [Collections.Generic.List[string]]($b -split "`r?`n")
$k = $lines.FindIndex({ param($x) $x.StartsWith("| SL-86 ") }); $c = $lines[$k].Split('|'); $i = $c.Count-2
$c[$i] = $c[$i].TrimEnd() + '; Upgrade ordering fixed in code 2026-11-12: Relay.claim holds back an ordered ''-'' delivery while any '''' delivery of the same root and destination is still pending, claimed or parked, so the legacy stream drains first. It uses the existing OUTBOX_DELIVERY_LINE index, and the check is a no-op on Oracle. RelayLegacySharedStreamH2Test written; it is red without the fix. '
$lines[$k] = ($c -join '|')
$k = $lines.FindIndex({ param($x) $x.StartsWith("| SL-117 ") }); $c = $lines[$k].Split('|')
$c[2] = ' MEDIUM compliance (fail-silent) '
$c[3] = ' The compliance tenant list (SANGAM_COMPLIANCE_TENANTS, falling back to sangam.tenant.default, then dev) resolves to empty because the property is defined as empty, so the dev fallback never applies. The scheduler also reads the tenant directory, but a failed directory lookup is swallowed at DEBUG (ComplianceWiring.java:172-174). By default nothing is then scheduled and nothing is logged: no retention or erasure (DPDP), no breach or rights escalation, no log sealing or audit verification. The system-context home tenant (:165) is also empty by default. '
$c[$c.Count-2] = ' OPEN (2026-11-12; B7). Root fix: log a directory failure at WARN/ERROR and retry it; warn and emit a metric when the schedule is empty while compliance is enabled; remove the misleading yaml fallback; take the home tenant from AttributionTenant. '
$lines[$k] = ($c -join '|')
[IO.File]::WriteAllText($p, ($lines -join $nl)); "ok"
