<!-- SPDX-License-Identifier: Apache-2.0 -->
# sangam-billing: tariffs, invoices, collection, receipts, refunds, penalties; payee directory adapter

Owner: IFMS-IMPLEMENTATION workstream (CAPABILITY-COVERAGE N10, GA-12). Base package `org.sangam.billing`. A thin module on
`sangam-ledger` (RECEIVABLE ledgers) and `sangam-config` (tariffs, number series). Water, power, property tax, fees,
rents, licence fees and premiums are configurations.

| File | Purpose |
|---|---|
| `TariffEngine.java` | Tariffs from the profile (`tariffs:`): FIXED, PER_UNIT, SLAB (telescopic or flat), PERCENT of another component, DECISION (DMN); minimum charge; rounding; penalty / interest (grace days, % per month / annum / once, or DMN) |
| `BillingService.java` | Invoice per account and cycle (demand per head in the RECEIVABLE ledger, idempotent per account x cycle x component), collection through `PaymentPort` allocated oldest-due first (excess kept as advance), numbered receipts, refunds, waiver / write-off (never above the outstanding), penalty levy |
| `DocumentNumbers.java` | Invoice and receipt numbers keyed by document identity (invoice: tenant + ledger + consumer + cycle; receipt: tenant + paymentRef): the first issue draws the number and stores the plan, every retry returns them and draws nothing (SL-130, SL-131); `overSeries` is the single-node adapter over a config `NumberSeries` (tests, tools) |
| `numbering/RegistryDocumentNumbers.java` | Production `DocumentNumbers` over the registry `NumberingService`: GAP_FREE series `INVOICE` (`INV/{scope}/{seq}`, scope = cycle) and `RECEIPT` (`RCP/{scope}/{period}/{seq}`, scope = channel, daily), number recorded with `ref_type` `billing.invoice` / `billing.receipt` and `ref_id` = identity; `BIL_DOCUMENT` row claimed, numbered and planned in one transaction; a tenant-defined series is used as defined |
| `BillingConnector.java` | In-process connector for the pack connector ids `billing.accounts`, `billing.invoice`, `billing.collect`, `billing.arrears`, `billing.penalty`, `billing.waive` (pack `universal.billing-cycle`); `AccountSource` port for the account register; `ActorSource` port for the authenticated actor (RM-37): the actor recorded with an invoice, receipt, penalty or waiver is the host's authenticated identity, never the step input's `actor` (an input naming somebody else is refused `ACTOR_MISMATCH`; with no authenticated actor the posting is recorded as `system:billing-connector`); `paymentRef`, `channel` and `orderRef` are required |
| `PaymentConfirmationPolicy.java` | The one payment-confirmation rule (RM-02, RM-03, owner decision 136): a rail result confirms only with a success status and a parseable amount; no channel is trusted by default (`sangam.billing.trusted-channels`, empty); a non-empty list is refused in production unless `sangam.billing.trusted-channels-acknowledged=true`; a trusted channel is refused by `BillingService.collect` on every path (owner decision 141) |
| `UnverifiedPayments.java` | RM-03 / owner decisions 141 and 193-201: the only way a trusted-channel payment is recorded. Plain-Java application service: `accept` (state `PAYMENT_ACCEPTED_UNVERIFIED`, nothing posted, audit event of the same name), `resolve` (by hand: VERIFIED needs the channel's reference and an `evidenceRef` and writes `PAYMENT_UNVERIFIED_MANUALLY_VERIFIED`; REJECTED and REVERSED write `PAYMENT_UNVERIFIED_RESOLVED`; maker-checker always, the resolver is never the acceptor; state and authorisation rechecked on the locked row), `reconcile` (the normal path: an exact rail confirmation resolves VERIFIED as `system:reconciliation`; interrupted allocations and resumptions completed), `ageing`, `list` (a `Query` filtered in SQL before paging, with the total). Only signed-in staff act (`Caller.actorType` STAFF; API keys, service and system identities refused, decision 201); the scoped actions `ACCEPT_UNVERIFIED` / `RESOLVE_UNVERIFIED` on (tenant, office, channel) come from the host's `UnverifiedPaymentAuthority` (none bound: all refused). VERIFIED allocates through `BillingService.allocateVerified` (package-private) |
| `UnverifiedPaymentPolicy.java` | Per-channel operating policy `sangam.billing.unverified.channels.<channel>.{policy-version,currency,max-amount (decimal or NONE),maker-checker,ageing-alert-after,downstream-permitted}`; any required key missing or malformed disables the channel, and `maker-checker` must be `true` (decision 194: `false` disables the channel) |
| `JdbcUnverifiedPaymentStore.java` | `BILL_UNVERIFIED_PAYMENT` and `BILL_UNVERIFIED_ACCRUAL_PAUSE` (in sangam-ledger `LedgerSchema`): unique (tenant, channel, payment_ref); transitions under a row lock guarded by version; audit through `AUDIT_PENDING` in the same transaction; pauses written under the payments' row locks only while they are pending; lists paged in SQL (LIMIT/OFFSET, OFFSET ... FETCH on Oracle and SQL Server) |
| `AccrualPauses.java` | Owner decision 195: while a payment is pending, `BillingService.levyPenalties` stops penalty and interest on the part of the arrears it covers (oldest due first) from the business date of its acceptance and records what it did not levy per payment; VERIFIED forgives it (`accrual_forgiven` on the VERIFIED event), REJECTED and REVERSED levy it (`pen-resume:<payment>:<account>:<date>`, audit `PAYMENT_UNVERIFIED_ACCRUAL_RESUMED`). The first levy of a date decides an account. The store is the billing service's `AccrualPauses`; `UnverifiedPayments` refuses a billing service wired to another |
| `party/RegistryPayeeDirectory.java` | `PayeeDirectory` (sangam-config) over the sangam-registry party registry: resolve by party or by bank-account identifier, several beneficiaries on one account, register idempotent on the account (GA-12) |

Integration notes: build `new BillingService(ledger, tariffs, documentNumbers, payments, confirmationPolicy, businessClock)`
(the overload without a policy trusts no channel) with the
registry-backed `RegistryDocumentNumbers` in production (the `NumberSeries` constructors are the single-node form for
tests and tools), and bind the `billing.*` ids to `new BillingConnector(billingService, ledgerService, profileResolver,
accountSource, periodResolver, clock, actorSource)`, where `periodResolver` gives the tenant's current fiscal period label and
throws `FiscalYearNotConfiguredException` when no fiscal year is set (null: a step without an explicit `period` is
refused; SL-127), and `actorSource` answers the authenticated principal of the call from the host's own authentication
state (null when no person is authenticated; RM-37). The overload without `actorSource` records every posting as
`system:billing-connector`. `sangam-server` `BillingWiring` builds both (it uses the overload without `actorSource`
until the host passes its authenticated principal). Use a RECEIVABLE ledger definition `DEMAND` (stage `DEMAND`) in the tenant profile; implement
`PaymentPort` over the ConnectorGateway as configuration binding only (it hands the rail's status and amount to
`PaymentConfirmationPolicy.confirmedByRail`); without a bound `PaymentPort` every collection is refused (RM-02); bind `registry.party` as described in sangam-config. Dependencies:
sangam-config, sangam-ledger, sangam-registry (optional, Apache-2.0).

Tests (fast): `BillingServiceTest` (slab tariff, percentage, minimum and rounding; invoice, idempotent re-invoice,
partial collection, arrears, penalty, waiver limited to the outstanding); `BillingRetryTest` (SL-130: a re-invoice and a
retried collection keep their number and draw none; SL-131: a retried collection replays its stored plan, so demands
A = 100 and B = 100 with one payment of 100 leave B untouched; another amount or consumer for a receipted payment is
refused). `numbering/RegistryDocumentNumbersH2Test` (registry numbering on H2: one USED allocation per document, gap
report without gaps, a refused first issue leaves no row and burns no number) needs the H2 driver as a test dependency.

Retries (SL-130, SL-131): the receipt's allocation plan is stored with its number; a retry replays it (each leg's
posting reference `rcp:<paymentRef>:<account id>` is then a ledger duplicate) instead of re-planning over the arrears
the first attempt already reduced. Production wiring must pass `RegistryDocumentNumbers` (the `NumberSeries`
constructors keep a node-local document map).

Gaps: `RegistryPayeeDirectory` is compiled but not yet run against a party-registry database.
