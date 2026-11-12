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
| `BillingConnector.java` | In-process connector for the pack connector ids `billing.accounts`, `billing.invoice`, `billing.collect`, `billing.arrears`, `billing.penalty`, `billing.waive` (pack `universal.billing-cycle`); `AccountSource` port for the account register |
| `party/RegistryPayeeDirectory.java` | `PayeeDirectory` (sangam-config) over the sangam-registry party registry: resolve by party or by bank-account identifier, several beneficiaries on one account, register idempotent on the account (GA-12) |

Integration notes: build `new BillingService(ledger, tariffs, documentNumbers, payments, businessClock)` with the
registry-backed `RegistryDocumentNumbers` in production (the `NumberSeries` constructors are the single-node form for
tests and tools), and bind the `billing.*` ids to `new BillingConnector(billingService, ledgerService, profileResolver,
accountSource, periodResolver, clock)`, where `periodResolver` gives the tenant's current fiscal period label and
throws `FiscalYearNotConfiguredException` when no fiscal year is set (null: a step without an explicit `period` is
refused; SL-127). Both are wired so in `sangam-server` `BillingWiring`. Use a RECEIVABLE ledger definition `DEMAND` (stage `DEMAND`) in the tenant profile; implement
`PaymentPort` over the ConnectorGateway; bind `registry.party` as described in sangam-config. Dependencies:
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
