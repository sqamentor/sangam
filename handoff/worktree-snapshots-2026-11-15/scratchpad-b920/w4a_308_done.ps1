$ErrorActionPreference = "Stop"
$h = "D:\GITHUB  Project\sangam-handoff"
$root = "D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam"
$sp = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad"
$m = "sangam-compliance/src/main/java/org/sangam/compliance"
$t = "sangam-compliance/src/test/java/org/sangam/compliance"
$files = @(
  "$m/ComplianceAudit.java", "$m/ComplianceException.java", "$m/ComplianceSchema.java",
  "$m/breach/IncidentReportingService.java", "$m/breach/BreachIncidentService.java", "$m/bpmn/ComplianceSteps.java",
  "$m/bpmn/IncidentClosureGuardListener.java",
  "sangam-compliance/src/main/resources/packs/compliance/forms/incident-follow-up.form.json",
  "$t/breach/SecurityIncidentCatalogueEventsH2Test.java", "$t/breach/DpdpBreachReportedH2Test.java",
  "$t/bpmn/IncidentClosureGuardListenerTest.java"
)
$hs = @()
foreach ($f in $files) { $hs += "sangam/$f " + (Get-FileHash -Algorithm SHA256 (Join-Path $root $f)).Hash }
$hs | Set-Content -Encoding UTF8 "$sp\w4a_308_hashes.txt"
$ts = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
$lines = @(
"$ts | W4-A/B61 | DONE | Decision 308 CODED and the OJ-06 step 8 closure rule PARTIAL (W4-A side CODED; waits on W4-G changelog and W4-K BPMN listener), tests written NOT_RUN. 308: BreachIncidentService.submitDetailedReport writes DPDP_BREACH_REPORTED after CMP_BREACH_DETAILED_REPORT in the same transaction only when the pack code is IN (DPDP_PACK), payload breach, authority, ref, late, principalsAffected, principalsNotified, contentHash, actor; NEW DpdpBreachReportedH2Test (IN once and rolls back, a second report refused adds none, EU-GDPR and US-HIPAA only the generic event, a refused report before notification or with a missing r.7(2)(b) item writes neither). Closure: ComplianceException.Code INCIDENT_CLOSURE_INCOMPLETE (422); IncidentReportingService.close(c, tenant, incident, rootCause, lessonsLearned, actor) refuses before any write when either is missing or blank (one rule, missingClosureFields), stores root_cause, lessons_learned, closed_at, closed_by and puts rootCause, lessonsLearned, closedAt in SEC_INCIDENT_CLOSED; step incident.close passes the task variables; NEW IncidentClosureGuardListener refuses completeTask of u_follow with 422 INCIDENT_CLOSURE_INCOMPLETE when closing without both (W4-M finding 07:51:04Z); form compliance/incident-follow-up gains rootCause and lessonsLearned; ComplianceSchema CMP_INCIDENT gains the four columns (H2 and tests only until W4-G's changeset). Tests: SecurityIncidentCatalogueEventsH2Test gains the refusal (5 incomplete pairs, nothing written, still REPORTED) and the success columns and payload; NEW IncidentClosureGuardListenerTest (4 tests). Gate: mvnw -o clean test-compile -pl sangam-compliance -am EXIT 0 after 308 (07:4x); after the closure rule the same gate EXIT 1 only in sangam-integration DltDeliveryReports (W4-H work in progress, not mine), so sangam-compliance main and tests (64 files) compiled by javac against the module classpath with sangam-integration classes of target-inth2ig: EXIT 0. Open: W4-G REQUEST 07:39:37Z (no reply yet), W4-K REQUEST 07:56:35Z. SHA-256: " + ($hs -join " ; ") + " | coordinator, W4-M, W4-G, W4-K",
"$ts | W4-A/B61 | REQUEST | To W4-M (decision 308, AuditCatalogueEmittedTest.NOT_EMITTED_YET): please drop DPDP_BREACH_REPORTED, now written by sangam-compliance breach.BreachIncidentService (literal in ComplianceAudit.DPDP_BREACH_REPORTED). | W4-M",
"$ts | W4-A/B61 | REQUEST | To W4-G, reminder of REQUEST 07:39:37Z, now coded on the module side: ComplianceSchema CMP_INCIDENT create has root_cause {text}, lessons_learned {text}, closed_at {ts}, closed_by {str} (all nullable, after process_instance_id); please add the alter changeset for all dialects and the timestamp inventory row for closed_at, and tell me the changeset id. Until then a database migrated by Liquibase lacks the columns and IncidentReportingService.close fails there. | W4-G"
)
foreach ($l in $lines) { Add-Content -Encoding UTF8 -Path "$h\WAVE4-SYNC-BOARD.md" -Value $l }
Add-Content -Encoding UTF8 -Path "$h\FILE-CLAIMS.md" -Value ("$ts B61 RELEASE W4-A decision 308 and closure rule, every claim of 2026-11-13T07:39:37Z and 2026-11-13T07:56:35Z (SHA-256 after): " + ($hs -join " ; "))
"posted $ts"
$hs
