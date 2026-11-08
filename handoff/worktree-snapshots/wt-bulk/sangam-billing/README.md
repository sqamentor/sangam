<!-- SPDX-License-Identifier: Apache-2.0 -->
# sangam-billing: tariffs, invoices, collection, receipts, refunds, penalties; payee directory adapter

Owner: IFMS-IMPLEMENTATION workstream (CAPABILITY-COVERAGE N10, GA-12). Base package `org.sangam.billing`. A thin module on
`sangam-ledger` (RECEIVABLE ledgers) and `sangam-config` (tariffs, number series). Water, power, property tax, fees,
rents, licence fees and premiums are configurations.

| File | Purpose |
|---|---|
| `TariffEngine.java` | Tariffs from the profile (`tariffs:`): FIXED, PER_UNIT, SLAB (telescopic or flat), PERCENT of another component, DECISION (DMN); minimum charge; rounding; penalty / interest (grace days, % per month / annum / once, or DMN) |
| `BillingService.java` | Invoice per account and cycle (demand per head in the RECEIVABLE ledger, idempotent per account x cycle x component), collection through `PaymentPort` allocated oldest-due first (excess kept as advance), numbered receipts, refunds, waiver / write-off (never above the outstanding), penalty levy |
| `BillingConnector.java` | In-process connector for the pack connector ids `billing.accounts`, `billing.invoice`, `billing.collect`, `billing.arrears`, `billing.penalty`, `billing.waive` (pack `universal.billing-cycle`); `AccountSource` port for the account register |
| `party/RegistryPayeeDirectory.java` | `PayeeDirectory` (sangam-config) over the sangam-registry party registry: resolve by party or by bank-account identifier, several beneficiaries on one account, register idempotent on the account (GA-12) |

Integration notes: bind the `billing.*` ids to `new BillingConnector(billingService, ledgerService, profileResolver,
accountSource, clock)` with a RECEIVABLE ledger definition `DEMAND` (stage `DEMAND`) in the tenant profile; implement
`PaymentPort` over the ConnectorGateway; bind `registry.party` as described in sangam-config. Dependencies:
sangam-config, sangam-ledger, sangam-registry (optional, Apache-2.0).

Tests (fast): `BillingServiceTest` (slab tariff, percentage, minimum and rounding; invoice, idempotent re-invoice,
partial collection, arrears, penalty, waiver limited to the outstanding).

Gaps: invoice / receipt numbers are drawn again on a retried step (postings stay idempotent; numbering per
operationId is a follow-up); `RegistryPayeeDirectory` is compiled but not yet run against a party-registry database.
