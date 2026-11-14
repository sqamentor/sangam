$root = "D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524"
$h = "D:\GITHUB  Project\sangam-handoff"
$sp = "C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad"
$b = "sangam/sangam-billing/src/main/java/org/sangam/billing"
$files = @(
  @("$b/BillingService.java", ""),
  @("$b/PaymentConfirmationPolicy.java", ""),
  @("sangam/sangam-billing/src/test/java/org/sangam/billing/PaymentConfirmationPolicyTest.java", ""),
  @("sangam/sangam-billing/README.md", ""),
  @("sangam/sangam-ledger/src/main/java/org/sangam/ledger/LedgerSchema.java", ""),
  @("sangam/sangam-server/src/main/java/org/sangam/server/config/BillingWiring.java", ""),
  @("sangam/sangam-server/src/test/java/org/sangam/server/config/BillingPaymentPolicyWiringTest.java", ""),
  @("sangam/sangam-rest/src/main/java/org/sangam/rest/modules/billing/BillingRestConfiguration.java", ""),
  @("$b/UnverifiedPayments.java", "(new)"),
  @("$b/UnverifiedPayment.java", "(new)"),
  @("$b/UnverifiedPaymentStore.java", "(new)"),
  @("$b/JdbcUnverifiedPaymentStore.java", "(new)"),
  @("$b/UnverifiedPaymentPolicy.java", "(new)"),
  @("$b/UnverifiedPaymentAuthority.java", "(new)"),
  @("$b/UnverifiedPaymentException.java", "(new)"),
  @("sangam/sangam-billing/src/test/java/org/sangam/billing/UnverifiedPaymentsH2Test.java", "(new)"),
  @("sangam/sangam-billing/src/test/java/org/sangam/billing/UnverifiedPaymentPolicyTest.java", "(new)"),
  @("sangam/sangam-rest/src/main/java/org/sangam/rest/modules/billing/UnverifiedPaymentController.java", "(new)"),
  @("sangam/sangam-server/src/test/java/org/sangam/server/acceptance/contracts/UnverifiedPaymentVendorIT.java", "(new)")
)
$parts = @()
foreach ($f in $files) {
  $p = Join-Path $root ($f[0] -replace "/", "\")
  if (-not (Test-Path $p)) { throw "missing $p" }
  $parts += "$($f[0])$($f[1]) $((Get-FileHash $p).Hash)"
}
$list = $parts -join " ; "
Set-Content -Path "$sp\w4a141_hashes.txt" -Value ($parts -join "`n")
$ts = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
$gate = "Gate: offline clean compile of -pl sangam-ledger,sangam-billing,sangam-rest,sangam-server -am on target-w4a EXIT 0 (main sources of all 29 reactor modules); test-compile through Maven stops in other lanes' in-flight files (W4-H ArazzoOperationStepTest), so billing tests (9 files) and sangam-server tests were compiled with standalone javac on the Maven test classpath: billing 0 errors, server 1 error only in W4-B ExternalPolicyWiringTest line 81 SecurityProperties (likely my classpath, not reported as BROKEN), 0 in my files."
$done = "$ts | W4-A/B61 | DONE | Decision 141 RM-03 CODED, status PARTIAL: tests written NOT_RUN (H2 and unit), Oracle and SQL Server and every vendor IT NOT_RUN; not closed on compilation. PAYMENT_ACCEPTED_UNVERIFIED is now a pending record in BILL_UNVERIFIED_PAYMENT owned by the new framework-free service UnverifiedPayments (accept, resolve VERIFIED or REJECTED or REVERSED, reconcile against the rail, ageing); collect refuses every trusted channel; posting happens only on VERIFIED through package-private allocateVerified, once, under a row lock with version check; duplicate prevention by unique (tenant, channel, payment_ref); the old UnverifiedPaymentGate (actor from the payload) is removed and replaced by the UnverifiedPaymentAuthority port scoped to tenant, office and channel, enforced in the service, with REST permissions as an extra layer. Open: authority bean (W4-B), table import (W4-G), audit catalogue (integrator), contract alignment (W4-M), three owner questions. $gate Files: $list | all"
Add-Content -Path "$h\WAVE4-SYNC-BOARD.md" -Value $done -Encoding UTF8
foreach ($l in (Get-Content "$sp\w4a141_requests.txt")) { if ($l.Trim()) { Add-Content -Path "$h\WAVE4-SYNC-BOARD.md" -Value "$ts | $l" -Encoding UTF8 } }
Add-Content -Path "$h\FILE-CLAIMS.md" -Value "$ts B61 RELEASE W4-A Wave 4b decision 141, every claim of 2026-11-12T19:04:13Z: $list" -Encoding UTF8
"posted $ts"
