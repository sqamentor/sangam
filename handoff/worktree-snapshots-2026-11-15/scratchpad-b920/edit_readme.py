import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
old = ("a non-empty list is refused in production unless `sangam.billing.trusted-channels-acknowledged=true`; each "
       "unverified acceptance passes the host's `UnverifiedPaymentGate` (channel permission, `PAYMENT_ACCEPTED_UNVERIFIED`) "
       "and is refused without one |")
assert s.count(old) == 1
new = ("a non-empty list is refused in production unless `sangam.billing.trusted-channels-acknowledged=true`; a trusted "
       "channel is refused by `BillingService.collect` on every path (owner decision 141) |\n"
       "| `UnverifiedPayments.java` | RM-03 / owner decision 141: the only way a trusted-channel payment is recorded. "
       "Plain-Java application service: `accept` (state `PAYMENT_ACCEPTED_UNVERIFIED`, nothing posted, audit event of the "
       "same name), `resolve` (VERIFIED with the channel's reference, REJECTED, REVERSED; maker-checker per policy; state "
       "and authorisation rechecked on the locked row), `reconcile` (exact rail confirmation resolves VERIFIED; "
       "interrupted allocations completed), `ageing`. The caller (`UnverifiedPaymentAuthority.Caller`) comes only from a "
       "server-authenticated identity; the scoped actions `ACCEPT_UNVERIFIED` / `RESOLVE_UNVERIFIED` on (tenant, office, "
       "channel) come from the host's `UnverifiedPaymentAuthority` (none bound: all refused). VERIFIED allocates through "
       "`BillingService.allocateVerified` (package-private) |\n"
       "| `UnverifiedPaymentPolicy.java` | Per-channel operating policy `sangam.billing.unverified.channels.<channel>."
       "{policy-version,currency,max-amount (decimal or NONE),maker-checker,ageing-alert-after,downstream-permitted}`; "
       "any required key missing or malformed disables the channel |\n"
       "| `JdbcUnverifiedPaymentStore.java` | `BILL_UNVERIFIED_PAYMENT` (in sangam-ledger `LedgerSchema`): unique "
       "(tenant, channel, payment_ref); transitions under a row lock guarded by version; audit through `AUDIT_PENDING` "
       "in the same transaction |")
s = s.replace(old, new)
open(p, "w", encoding="utf-8", newline="\n").write(s)
print("ok")
