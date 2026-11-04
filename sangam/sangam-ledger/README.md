<!-- SPDX-License-Identifier: Apache-2.0 -->
# sangam-ledger: generic limit / fund / quantity / receivable ledger

Owner: IFMS-IMPLEMENTATION workstream (generic module, ADR-D-20 / ADR-D-22). Base package `org.sangam.ledger`. Government
budgets, bank exposure limits, corporate budgets, insurance reserves, stock and demand ledgers are configurations of the
same ledger (profile section `ledgers:`).

## Files and what they cover

| File | Purpose | Ids |
|---|---|---|
| `LedgerDefinition.java` | Definition from configuration: kind (LIMIT / QUANTITY / RECEIVABLE), mode (AUTHORITATIVE / SHADOW), functional currency, **N-stage chain** with per-stage HARD / SOFT / NONE checks, negative limits, period of availability (ANNUAL / MULTI_YEAR / NO_YEAR, carry-forward, cancel-after), stripes, hold queue, transfer rules, decisions, accounting mapping, issue order | INTL gaps 1, 2, 5, 6; IFC-056..074; IFS-040..043; GA-03 |
| `AccountKey.java` | tenant, ledger, period (incl. extra periods), line, holder, stage, sub (lot, funding source such as a donor agreement, currency) | INTL gaps 6, 7, 13 |
| `Balance.java` | Buckets total / distributed / committed / encumbered / consumed, state (OPEN / FROZEN / EXPIRED / CANCELLED), attributes (conditions, availableUntil, dueDate, expiry, bookedRate) | GP-11 |
| `Movement.java` | One guarded leg of an atomic posting | - |
| `LedgerStore.java` (+ `InMemory`) | Atomic idempotent posting by (tenant, reference) (R41), legs in account order | GP-11, GP-48 |
| `JdbcLedgerStore.java` | Four-dialect store: entry insert-if-absent, then one **guarded single-row UPDATE per leg** (row lock + guard in one statement, no oversell), rollback on any refused leg | ADR-D-24 (≥ 500 ops/s per cell design), A-15 |
| `LedgerSchema.java` | DDL: `LDG_BALANCE`, `LDG_ENTRY`, `LDG_HOLD`, `LDG_FREEZE`, `LDG_ACCOUNTING` | - |
| `LedgerRequest.java` | Operations ALLOCATE, DISTRIBUTE, WITHDRAW (surrender), COMMIT / UNCOMMIT, ENCUMBER / DISENCUMBER (from commitment), CONSUME / REVERSE (from encumbrance), TRANSFER (re-appropriation / virement), ADJUST (supplementary, cut), WRITE_OFF, LAPSE | IFC-058, IFC-061, IFC-069, IFC-070 |
| `LedgerService.java` | Policies (freeze orders, executable conditions, period expiry, transfer constraints by attributes and flags, DMN decisions via port), multi-currency conversion, stripes, hold-for-funds queue, accounting entries (cash / accrual), QUANTITY FIFO / FEFO / LIFO issue across lots, RECEIVABLE arrears, period close (lapse, carry-forward of commitments, expire, cancel), formula interim authority (vote on account / continuing resolution), revaluation of open commitments, SHADOW reconciliation | INTL gaps 1-9; IFC-041 (hold), IFS-041, IFS-057, N02, N10 |
| `LedgerControlStore.java` (+ `JdbcStore`, `InMemory`), `FreezeOrder.java` | Durable holds (pending-for-funds queue) and freeze orders in `LDG_HOLD` / `LDG_FREEZE`; the service keeps no in-memory copy, so nothing is lost on restart | INTL gap 5, IFS-041 |
| `LedgerConnector.java` | In-process connector for the `ledger.*` pack connector ids; posting reference = operationId; lookup from the stored entry (class a) | GA-03, GA-09 |
| `LedgerPorts.java` | Ports: RatePort, ExternalLedgerPort (shadow mode), DecisionPort (DMN), AccountingSink (GL export), AuditLink (R42) | ADR-D-22 |

## Integration notes

- **Liquibase:** add `LedgerSchema.TEMPLATES` to sangam-persistence (amounts `NUMERIC(24,6)` on every dialect).
- **Connector bindings** used by the `gov.ifms.*` packs: `ledger.available`, `ledger.allocate`, `ledger.distribute`,
  `ledger.withdraw`, `ledger.commit`, `ledger.encumber`, `ledger.release`, `ledger.consume`, `ledger.transfer`,
  `ledger.adjust`, `ledger.releaseHolds`, `ledger.closePeriod`: bind all of them to one
  `new LedgerConnector(service, (tenant, date) -> resolver.resolve(tenant, date).locale().fiscal().yearOf(date).label(), clock)`.
  Production wiring: `new LedgerService(new JdbcLedgerStore(ds, d), new LedgerControlStore.JdbcStore(ds, d), clock)`.
- **Ports to wire:** RatePort from `ExchangeRates` (config) or an FX connector; DecisionPort over Operaton DMN
  (`Decisions.evaluate` with tenant override); AccountingSink to the GL connector; AuditLink to the core audit chain.
- **REST endpoints** (WS-REST): `GET /ledger/{ledger}/accounts?period=&line=&holder=&stage=`,
  `GET /ledger/accounts/{accountId}`, `POST /ledger/{ledger}/operations` (LedgerRequest, Idempotency-Key = reference),
  `POST /ledger/{ledger}/freeze-orders`, `DELETE /ledger/{ledger}/freeze-orders/{id}`,
  `POST /ledger/{ledger}/periods/{period}/close`, `GET /ledger/{ledger}/holds`, `POST /ledger/{ledger}/reconcile`.
- Built with `-f sangam-ledger/pom.xml` (see the reactor note in sangam-config).

## Dependencies

sangam-core, sangam-config (Apache-2.0), slf4j-api (MIT); junit-jupiter, h2 (test). No new third-party dependency.

## Tests (fast)

`LedgerServiceTest`: stage distribution, 100 concurrent commitments against a limit of 60 (exactly 60 applied, no
oversell), idempotent repeat, annual lapse and expiry, FEFO issue across lots with an insufficient-stock refusal, transfer
constraint by attribute, freeze order and release.

`LedgerJdbcH2Test` (H2, PostgreSQL mode): 80 concurrent commitments against 500 / 10 (exactly 50 applied), a held
request and a freeze order survive a restart (new service on the same tables), release of the freeze and of the hold;
the connector posts with the operationId as reference (a repeat is a duplicate), refuses an over-encumbrance.

## Known gaps

- Stripe rebalancing when no single stripe can serve a request (the request is refused; a sweep can rebalance).
- Outer-year (MTEF) ceilings are modelled as accounts of later periods with a ceiling parent; a dedicated check is not built.
- The JDBC store is compiled but has not been run on the four databases (testing phase).
