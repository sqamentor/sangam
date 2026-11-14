$ErrorActionPreference = "Stop"
$h = "D:\GITHUB  Project\sangam-handoff"
$root = "D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
$sp = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad"
$a = @(
  "sangam-compliance/src/main/java/org/sangam/compliance/ComplianceAudit.java",
  "sangam-compliance/src/main/java/org/sangam/compliance/breach/IncidentReportingService.java",
  "sangam-compliance/src/main/java/org/sangam/compliance/breach/BreachIncidentService.java",
  "sangam-compliance/src/test/java/org/sangam/compliance/breach/SecurityIncidentCatalogueEventsH2Test.java"
)
$b = @(
  "sangam-server/src/test/java/org/sangam/server/config/BillingPaymentPolicyWiringTest.java",
  "sangam-server/src/test/java/org/sangam/server/config/LedgerWiringGlGuardTest.java"
)
function HashList($list) {
  $o = @()
  foreach ($f in $list) { $o += "sangam/$f " + (Get-FileHash -Algorithm SHA256 (Join-Path $root $f)).Hash }
  return $o
}
$ha = HashList $a
$hb = HashList $b
($ha + $hb) | Set-Content -Encoding UTF8 "$sp\w4a_299_hashes.txt"
$ts = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
$done = "$ts | W4-A/B61 | DONE | Decision 299 W4-A names: 4 CODED, 1 BLOCKED, 3 handed to W4-K; tests written NOT_RUN. CODED in sangam-compliance IncidentReportingService on chain cmp:<tenant>, same transaction as the incident change, next to the specific CMP_INCIDENT_* event: SEC_INCIDENT_OPENED and SEC_INCIDENT_CLASSIFIED in register (noticedAt and dueAt; Annexure I type code, label, personalDataLikely, personalData = linked to a breach), SEC_REPORT_SUBMITTED in submit (references, submittedAt, late), SEC_INCIDENT_CLOSED in close only when the update moved REPORTED to CLOSED. BLOCKED: DPDP_BREACH_REPORTED waits on my INFO $('2026-11-13T07:22:57Z') (options A, B, C); BreachIncidentService unchanged (hash equals its before hash). HANDED: RECORD_REVIEWED, RECORD_TRANSFERRED_NAI, RTI_REQUEST_LINKED, work exists only in sangam-packs (REQUEST to W4-K 07:22:57Z, open). NEW SecurityIncidentCatalogueEventsH2Test (5 tests: open and classify payloads with rollback, breach link marks personal data, unknown type writes nothing, submit references and close once with repeated and early close writing nothing, refused submit writes nothing). Also W4-B REQUEST 07:29:27Z served: decision 143 switch false added to the development fixtures of BillingPaymentPolicyWiringTest (3 tests) and LedgerWiringGlGuardTest, assertions unchanged. Gate mvnw -q -B -o clean test-compile -pl sangam-compliance -am (target-w4a) EXIT 0; the two sangam-server tests by javac against the server classpath EXIT 0 (full server gate not run: sangam-rest is mid-move by W4-F decision 285). SHA-256: " + (($ha + $hb) -join " ; ") + " | coordinator, W4-M, W4-K, W4-B"
Add-Content -Encoding UTF8 -Path "$h\WAVE4-SYNC-BOARD.md" -Value $done
$req = "$ts | W4-A/B61 | REQUEST | To W4-M (decision 299, AuditCatalogueEmittedTest.NOT_EMITTED_YET): please drop SEC_INCIDENT_OPENED, SEC_INCIDENT_CLASSIFIED, SEC_REPORT_SUBMITTED and SEC_INCIDENT_CLOSED, now string literals in sangam-compliance org.sangam.compliance.ComplianceAudit (written by breach.IncidentReportingService). Keep DPDP_BREACH_REPORTED (BLOCKED on my INFO 07:22:57Z) and RECORD_REVIEWED, RECORD_TRANSFERRED_NAI, RTI_REQUEST_LINKED (REQUEST to W4-K 07:22:57Z); for RTI_REQUEST_LINKED the pointer 'none found in the code' is not right: the feature is the p2-file-movement event subprocess p2_rti on message sangam.rtiLinked, only the event is missing. | W4-M"
Add-Content -Encoding UTF8 -Path "$h\WAVE4-SYNC-BOARD.md" -Value $req
$rel = "$ts B61 RELEASE W4-A decision 299, every claim of 2026-11-13T07:22:57Z and 2026-11-13T07:31:35Z (SHA-256 after): " + (($ha + $hb) -join " ; ")
Add-Content -Encoding UTF8 -Path "$h\FILE-CLAIMS.md" -Value $rel
"posted $ts"
$ha + $hb
