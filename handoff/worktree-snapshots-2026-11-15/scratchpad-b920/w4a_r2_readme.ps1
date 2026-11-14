$p = "D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam\sangam-billing\README.md"
$raw = [IO.File]::ReadAllText($p)
$crlf = $raw.Contains("`r`n")
$s = $raw.Replace("`r`n", "`n")
$lines = $s.Split("`n")
$out = New-Object System.Collections.Generic.List[string]
$done = 0
foreach ($l in $lines) {
    if ($l.StartsWith('| `UnverifiedPayments.java` |')) {
        $out.Add('| `UnverifiedPayments.java` | RM-03 / owner decisions 141 and 193-201: the only way a trusted-channel payment is recorded. Plain-Java application service: `accept` (state `PAYMENT_ACCEPTED_UNVERIFIED`, nothing posted, audit event of the same name), `resolve` (by hand: VERIFIED needs the channel''s reference and an `evidenceRef` and writes `PAYMENT_UNVERIFIED_MANUALLY_VERIFIED`; REJECTED and REVERSED write `PAYMENT_UNVERIFIED_RESOLVED`; maker-checker always, the resolver is never the acceptor; state and authorisation rechecked on the locked row), `reconcile` (the normal path: an exact rail confirmation resolves VERIFIED as `system:reconciliation`; interrupted allocations and resumptions completed), `ageing`, `list` (a `Query` filtered in SQL before paging, with the total). Only signed-in staff act (`Caller.actorType` STAFF; API keys, service and system identities refused, decision 201); the scoped actions `ACCEPT_UNVERIFIED` / `RESOLVE_UNVERIFIED` on (tenant, office, channel) come from the host''s `UnverifiedPaymentAuthority` (none bound: all refused). VERIFIED allocates through `BillingService.allocateVerified` (package-private) |')
        $done++
    } elseif ($l.StartsWith('| `UnverifiedPaymentPolicy.java` |')) {
        $out.Add('| `UnverifiedPaymentPolicy.java` | Per-channel operating policy `sangam.billing.unverified.channels.<channel>.{policy-version,currency,max-amount (decimal or NONE),maker-checker,ageing-alert-after,downstream-permitted}`; any required key missing or malformed disables the channel, and `maker-checker` must be `true` (decision 194: `false` disables the channel) |')
        $done++
    } elseif ($l.StartsWith('| `JdbcUnverifiedPaymentStore.java` |')) {
        $out.Add('| `JdbcUnverifiedPaymentStore.java` | `BILL_UNVERIFIED_PAYMENT` and `BILL_UNVERIFIED_ACCRUAL_PAUSE` (in sangam-ledger `LedgerSchema`): unique (tenant, channel, payment_ref); transitions under a row lock guarded by version; audit through `AUDIT_PENDING` in the same transaction; pauses written under the payments'' row locks only while they are pending; lists paged in SQL (LIMIT/OFFSET, OFFSET ... FETCH on Oracle and SQL Server) |')
        $out.Add('| `AccrualPauses.java` | Owner decision 195: while a payment is pending, `BillingService.levyPenalties` stops penalty and interest on the part of the arrears it covers (oldest due first) from the business date of its acceptance and records what it did not levy per payment; VERIFIED forgives it (`accrual_forgiven` on the VERIFIED event), REJECTED and REVERSED levy it (`pen-resume:<payment>:<account>:<date>`, audit `PAYMENT_UNVERIFIED_ACCRUAL_RESUMED`). The first levy of a date decides an account. The store is the billing service''s `AccrualPauses`; `UnverifiedPayments` refuses a billing service wired to another |')
        $done++
    } else {
        $out.Add($l)
    }
}
if ($done -ne 3) { throw "rows replaced: $done" }
$t = [string]::Join("`n", $out)
if ($crlf) { $t = $t.Replace("`n", "`r`n") }
[IO.File]::WriteAllText($p, $t)
"OK"
