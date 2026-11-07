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
| `FinancingAgreements.java` | Donor / lender agreements as limit sources: eligibility (category, % financed, retroactive window, procurement method), agreement-category limit accounts | PFM-059..062 |
| `FiscalRuleEvaluator.java` | Fiscal-rule checks over aggregated ledger data (ratios, DMN) | PFM-078 |
| `TsaTopology.java` | TSA topology as data: main, ledger-only, real, zero-balance, collection and exception accounts; sweep plan; lint | PFM-045, IFC-158 |
| `LedgerService` additions | `commitMultiYear` (outer-year ceilings), `payablesAgeing`, `cashForecast`, breach events (`BreachSink`), accounting-basis filter | PFM-021, 036, 038, 039, 047, 054 |
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

`PfmGapsTest`: outer-year ceiling held atomically across periods, breach event on a SOFT overspend, financing-agreement
eligibility, a fiscal-rule ratio, TSA sweep plan.

## Known gaps

- Stripe rebalancing when no single stripe can serve a request (the request is refused; a sweep can rebalance).
- Outer-year (MTEF) ceilings are modelled as accounts of later periods with a ceiling parent; a dedicated check is not built.
- The JDBC store is compiled but has not been run on the four databases (testing phase).

## International PFM coverage (spike/results/INTERNATIONAL-PFM-REQUIREMENTS.md)

Status: C implemented as a generic capability (with data where needed), D data only, P partial, G gap. Counts over PFM-001..080 (72 ids): C 39, D 2, P 27, G 4; PFM-CH-1..7: 7 D. The same map is in the sangam-config and sangam-ledger READMEs. Country profiles (ISO 3166 alpha-3 ids, so they never collide with Indian state codes): USA, GBR, BRA, KEN, IDN, PHL, BGD under profiles/intl/countries/, on the regime presets PRESIDENTIAL-APPORTIONMENT, WESTMINSTER-SUPPLY, CIVIL-LAW-EMPENHO, DIPA-TREASURY-OFFICE, ALLOTMENT-NCA, indexed by profiles/intl.idx (load it with ProfileLoader.loadIndex next to profiles.idx). KEN, IDN, PHL, BGD are [U] throughout.

| Id | St | Where |
|---|---|---|
| PFM-001 | C | sangam-config: master/ClassificationScheme.java (N segments, widths, patterns, owners); data: profiles/intl/countries/*.yaml#classification |
| PFM-002 | P | data: profiles/intl/seeds/gfsm-2014-economic.csv, intl/INTL-PFM.yaml (GFS scheme): two levels only [U] |
| PFM-003 | P | data: profiles/intl/INTL.yaml, intl/seeds/cofog-divisions.csv: divisions only (groups / classes to load) |
| PFM-004 | C | sangam-config: master/Crosswalk.java (effective-dated, %-split, version to version); data: profiles/in/IN.yaml#crosswalks, intl/INTL-PFM.yaml#crosswalks |
| PFM-005 | C | sangam-config: master/Crosswalk.java#checkCombinations; api/ConfigConnector (classification.validate) |
| PFM-006 | D | data: profiles/intl/INTL-PFM.yaml#classification.attributes.counterpartySector |
| PFM-007 | D | data: profiles/intl/INTL-PFM.yaml#classification.attributes.institutionalSector |
| PFM-008 | C | sangam-config: admin/ConfigAdminService.java, master/ClassificationRegistry.java (correction slips), ClassificationScheme owner |
| PFM-009 | P | programme segment in country schemes; KPIs / targets on programmes not modelled |
| PFM-010 | C | sangam-config: master/Crosswalk.java#checkSplitTags; data: profiles/intl/INTL-PFM.yaml#classification.tags |
| PFM-011 | P | data: profiles/institutionalSector attribute; extrabudgetary-unit registry as tenants (no reporting-obligation tracker) |
| PFM-020 | P | pack gov.ifms.budget-formulation (generic flow); intl.pfm.budget-preparation not built |
| PFM-021 | C | sangam-ledger: LedgerService#commitMultiYear (outer-year ceilings as later-period accounts) |
| PFM-022 | P | core DocumentDelegate outputs; budget-document bundle not built |
| PFM-023 | C | sangam-ledger: ALLOCATE at the top stage with auditRef = legal reference; level of control = stages / line in the profile |
| PFM-024 | P | pack gov.ifms.scheme-appraisal (generic ladder DMN) |
| PFM-025 | C | sangam-ledger: ADJUST; pack gov.ifms.supplementary; data: profiles/intl/countries/BRA.yaml#creditosAdicionais |
| PFM-026 | P | pack gov.ifms.supplementary (contingency route); repayment tracking as ledger data |
| PFM-027 | C | sangam-ledger: LedgerService transfer rules (sameAttributes, blocked flags, DecisionPort); DMN ifms-reapp-rules; data: profiles/intl/countries/GBR.yaml#ledgers.transfer |
| PFM-028 | C | sangam-ledger: LedgerService#allocateFormula; data: profiles/USA apportionment.interimAuthority, GBR estimates.voteOnAccount |
| PFM-029 | C | sangam-ledger: LedgerDefinition allowNegative + one account per limit type; data: profiles/intl/countries/GBR.yaml#estimates |
| PFM-030 | C | sangam-ledger: LedgerDefinition stages / checks (N-stage chain); data: profiles/regime/*.yaml, intl/countries/*.yaml#ledgers |
| PFM-031 | P | sangam-ledger: period / sub dimension for quarterly cotas and Category A; release-above-plan approval not specific |
| PFM-032 | P | sangam-ledger: line / sub dimension for Category B purpose phasing; Category C as later periods |
| PFM-033 | C | sangam-ledger: account attribute conditions + LedgerPorts.DecisionPort (executable conditions) |
| PFM-034 | C | sangam-ledger: COMMIT guarded under the row lock (JdbcLedgerStore) |
| PFM-035 | C | sangam-ledger: COMMIT / UNCOMMIT adjustments, ENCUMBER from commitment; data: profiles/intl/INTL-PFM.yaml COMMITMENT subtypes |
| PFM-036 | C | sangam-ledger: LedgerService#commitMultiYear |
| PFM-037 | C | sangam-ledger: FreezeOrder.java + LedgerControlStore (durable, LDG_FREEZE) |
| PFM-038 | C | sangam-ledger: LedgerService#payablesAgeing (buckets from config); RECEIVABLE arrears |
| PFM-039 | P | sangam-ledger: LedgerPorts.BreachSink event; data: profiles/INTL-PFM chain PFM-CH-7; breach case pack not built |
| PFM-040 | P | sangam-ledger: LedgerStore#find by segment / stage / period; standard reports not built |
| PFM-041 | C | core SoD guard; data: profiles/intl/INTL-PFM.yaml PFM-CH-3 sod pairs |
| PFM-042 | P | release notice date as document field (RELEASE_ORDER.noticeDate); no SLA report |
| PFM-043 | C | sangam-ledger: LedgerService#closePeriod / cancelExpired, availability ANNUAL / MULTI_YEAR / NO_YEAR, carry-forward |
| PFM-044 | C | sangam-ledger: LedgerDefinition mode SHADOW + ExternalLedgerPort + LedgerService#reconcile |
| PFM-045 | C | sangam-ledger: TsaTopology.java (main, ledger-only, real, zero-balance, collection, exception; sweeps; lint) |
| PFM-046 | P | pack gov.ifms.receipts (scroll reconciliation); transfer-deadline monitor not built |
| PFM-047 | P | sangam-ledger: LedgerService#cashForecast (input from commitments); cash-plan process not built |
| PFM-048 | C | sangam-config: catalogue/DocumentTypeCatalogue.ChainTemplate#liableRoles; data: profiles/PFM-CH-3 certifying officer |
| PFM-049 | P | sangam-config: api/PayeeDirectory (party registry) + duplicate check; exclusion-list (Do Not Pay) connector not built |
| PFM-050 | C | rail catalogue (profile paymentRails) + core execution with UNKNOWN_OUTCOME |
| PFM-051 | P | sangam-ledger: RECEIVABLE ledger for advances (dueDate); suspense ageing workflow not built |
| PFM-052 | P | sangam-ledger: closePeriod + config period lock; pack gov.ifms.monthly-accounts |
| PFM-053 | C | sangam-config: catalogue/TwoSidedDocument.java; data: profiles/IG_AGREEMENT (parties BUYER / SELLER) |
| PFM-054 | C | sangam-ledger: accounting mapping filtered by LedgerDefinition bases; data: profiles/accountingBasis (effective-dated) per country |
| PFM-055 | P | sangam-ledger: AccountingEntry (cash / accrual) to AccountingSink; budget-to-accounting reconciliation report not built |
| PFM-056 | G | fiscal-transparency outputs and open data: not built |
| PFM-057 | P | sangam-security read roles + audit export; SAI cross-tenant role not configured |
| PFM-058 | P | connector accounts.render (gov.ifms.monthly-accounts); consolidation-return tracking not built |
| PFM-059 | C | sangam-ledger: AccountKey.sub = funding source / agreement; data: profiles/FONTE scheme (BRA) |
| PFM-060 | C | sangam-ledger: FinancingAgreements.java (agreement-level limits, currency, closing date) |
| PFM-061 | C | sangam-ledger: FinancingAgreements#check (category, % financed, retroactive window, procurement method) |
| PFM-062 | P | sangam-ledger: TsaTopology EXCEPTION account + WITHDRAWAL_APPLICATION / PFM-CH-6 data; replenishment pack not built |
| PFM-063 | P | sangam-config: n-level scopes + core referral; data: profiles/KEN regime.transferFormula; formula DMN not written |
| PFM-064 | P | pack gov.ifms.salary-monthly |
| PFM-065 | G | procurement complaints: not built |
| PFM-066 | C | data: profiles/in/dmn/<GOV>/ifms-procurement-mode.dmn (per government) |
| PFM-067 | P | existing pack gov.assets |
| PFM-068 | P | sangam-ledger: a guarantee register is a LIMIT ledger by configuration; no register definition shipped |
| PFM-069 | G | debt-operations approval chain: not built |
| PFM-070 | C | sangam-ledger: transaction vs functional currency, AccountingEntry.functionalAmount |
| PFM-071 | C | sangam-ledger: LedgerPorts.RatePort (rate types); sangam-config: locale/ExchangeRates.java |
| PFM-072 | C | sangam-ledger: LedgerService#revalue |
| PFM-073 | C | sangam-ledger: account currency (limits in a foreign currency) |
| PFM-074 | C | sangam-config: locale/FiscalCalendar.java (any start month, adjustmentPeriods P13 / P14) |
| PFM-075 | C | sangam-config: locale/LocaleSettings.java (languages, scripts, direction); data: profiles/intl/countries/*.yaml#locale |
| PFM-076 | C | sangam-config: ChainTemplate#externalSteps; data: profiles/intl/countries/KEN.yaml KEN-EXCHEQUER (Controller of Budget) |
| PFM-077 | P | data: profiles/intl/countries/GBR.yaml#accountability.ministerialDirection; no pack |
| PFM-078 | C | sangam-ledger: FiscalRuleEvaluator.java (aggregates + ratio rules + DMN) |
| PFM-079 | C | sangam-config: profile versioning, effective dating, ruleRef on findings |
| PFM-080 | G | PEFA evidence pack: not built |
| PFM-CH-1 | D | Release: template in data: profiles/intl/INTL-PFM.yaml#approvalChains (generic chain mechanics: config ChainTemplate; no intl.pfm pack yet) |
| PFM-CH-2 | D | Commitment: template in data: profiles/intl/INTL-PFM.yaml#approvalChains (generic chain mechanics: config ChainTemplate; no intl.pfm pack yet) |
| PFM-CH-3 | D | Verification and payment: template in data: profiles/intl/INTL-PFM.yaml#approvalChains (generic chain mechanics: config ChainTemplate; no intl.pfm pack yet) |
| PFM-CH-4 | D | Virement: template in data: profiles/intl/INTL-PFM.yaml#approvalChains (generic chain mechanics: config ChainTemplate; no intl.pfm pack yet) |
| PFM-CH-5 | D | Year-end: template in data: profiles/intl/INTL-PFM.yaml#approvalChains (generic chain mechanics: config ChainTemplate; no intl.pfm pack yet) |
| PFM-CH-6 | D | Donor replenishment: template in data: profiles/intl/INTL-PFM.yaml#approvalChains (generic chain mechanics: config ChainTemplate; no intl.pfm pack yet) |
| PFM-CH-7 | D | Breach: template in data: profiles/intl/INTL-PFM.yaml#approvalChains (generic chain mechanics: config ChainTemplate; no intl.pfm pack yet) |
