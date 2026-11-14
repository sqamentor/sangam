$ErrorActionPreference = "Stop"
$h = "D:\GITHUB  Project\sangam-handoff"
$root = "D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
$m = "sangam-compliance/src/main/java/org/sangam/compliance"
$files = @(
  "$m/ComplianceAudit.java", "$m/ComplianceException.java", "$m/ComplianceSchema.java",
  "$m/breach/IncidentReportingService.java", "$m/breach/BreachIncidentService.java", "$m/bpmn/ComplianceSteps.java",
  "sangam-compliance/src/main/resources/packs/compliance/forms/incident-follow-up.form.json",
  "sangam-compliance/src/test/java/org/sangam/compliance/breach/SecurityIncidentCatalogueEventsH2Test.java"
)
$parts = @()
foreach ($f in $files) { $parts += "sangam/$f (before " + (Get-FileHash -Algorithm SHA256 (Join-Path $root $f)).Hash.Substring(0,16) + ")" }
$parts += "NEW sangam/sangam-compliance/src/test/java/org/sangam/compliance/breach/DpdpBreachReportedH2Test.java"
$ts = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
Add-Content -Encoding UTF8 -Path "$h\FILE-CLAIMS.md" -Value ("$ts B61 CLAIM W4-A owner decision 308 and the coordinator ruling on the incident closure rule (OJ-06 step 8): " + ($parts -join " ; "))
$lines = @(
"$ts | W4-A/B61 | CHANGE | Decision 308 under claim ${ts}: ComplianceAudit gains DPDP_BREACH_REPORTED; BreachIncidentService.submitDetailedReport writes it in the same transaction, after CMP_BREACH_DETAILED_REPORT, only when the tenant's jurisdiction pack code is IN (payload breach, authority, ref, late, principalsAffected, principalsNotified, contentHash, actor); EU-GDPR and US-HIPAA keep only CMP_BREACH_DETAILED_REPORT. NEW DpdpBreachReportedH2Test (IN exactly once, EU-GDPR and US-HIPAA none, a refused report none). Closure rule: coding waits on the W4-G and W4-M ACKs below (contract-first). Tests NOT_RUN. | all",
"$ts | W4-A/B61 | REQUEST | To W4-G (coordinator ruling: OJ-06 step 8, closure needs root cause and lessons): CMP_INCIDENT gains four nullable columns, root_cause {text}, lessons_learned {text}, closed_at {ts}, closed_by {str}; no index, no backfill (existing CLOSED rows keep NULL). Proposal, as with plan_process_instance_id and LedgerSchema in round 2: W4-A adds them to the CMP_INCIDENT create in sangam-compliance ComplianceSchema (module source, H2 and tests), W4-G adds one alter changeset through the generator for all dialects and the timestamp inventory row for closed_at. Please ACK (with the changeset id) or give other names. | W4-G",
"$ts | W4-A/B61 | REQUEST | To W4-M (same ruling, contract): (1) problem code INCIDENT_CLOSURE_INCOMPLETE, HTTP 422, new ComplianceException.Code; detail names the missing field(s) and cites OJ-06 step 8; refused before anything is written. (2) request fields rootCause and lessonsLearned, both required non-blank text. There is no REST close route today: close runs only as step incident.close of process sangam-incident-report, so the fields arrive as variables of the task compliance/incident-follow-up (form gains two text fields rootCause and lessonsLearned) and the 422 surfaces as the step's refusal; I add no REST route unless you want one. (3) SEC_INCIDENT_CLOSED payload becomes incident, rootCause, lessonsLearned, closedAt, actor (AsyncAPI payload, if you list one). Please ACK or change names. | W4-M"
)
foreach ($l in $lines) { Add-Content -Encoding UTF8 -Path "$h\WAVE4-SYNC-BOARD.md" -Value $l }
"posted $ts"
