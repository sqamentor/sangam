# Sangam sector and capability conformance

Status 2026-11-08. Evidence runs on H2 in memory with the connector simulator (`sangam-packs` tests,
`.\mvnw.cmd "-Dsangam.target=target-conf" -pl sangam-packs -am test`). Gaps found but not fixed are in
`TESTING-GAP-REGISTER.md` as SL-nn. "UNVERIFIED" means the claim was not checked against code, tests, or
the primary source.

## A. Sector conformance (by owner priority)

Method: `SectorConformanceTest`. A tenant is configured only by the shipped pack: BPMN, DMN, forms and
seed seats. Each task is answered by `FormAnswers`. The person is the seed holder of the task's seat today,
the decision is the form's affirmative option, and required fields get typed values. No sector Java is
involved.

Every row must show all of the following:
- the end event is reached with 0 incidents;
- every task is completed by the seed holder of its seat;
- each task has a `TASK_COMPLETED` audit event naming the person and the seat;
- `RULE_EVALUATED` is recorded when the pack ships DMN;
- a finished history record exists (the source for reports).

Extra assertions are listed per row.

The columns are:
- **Seats/maker-checker:** seats and maker-checker.
- **DMN:** rules.
- **Connector:** ledger, payment or connector call.
- **Audit:** audit and compliance artefacts.
- **Report source:** the finished history record.

| # | Sector | Pack (main process) | Test | Seats/maker-checker | DMN | Connector | Audit | Report source | Result |
|---|---|---|---|---|---|---|---|---|---|
| 1 | IFMS central | gov.ifms.pao-chain | `ifmsCentralPaoBillChain` (5 levels in order) | yes | yes | sim | yes | yes | PASS |
| 2 | IFMS central | gov.ifms.year-end-closing | `ifmsYearEndClosing` | yes | yes | sim | yes | yes | PASS |
| 3 | IFMS central | gov.ifms.monthly-accounts | `ifmsMonthlyAccounts` | yes | yes | – | yes | yes | PASS |
| 4 | State treasury | gov.p1.bill-payment | `stateTreasuryBillPaymentWithPayment` (CLOSED, payment rail called) | yes | yes | payment | yes | yes | PASS |
| 5 | State DBT | gov.p3.grant-sanction | `stateGrantSanctionDbtInstalments` (CLOSED) | yes | yes | DBT | yes | yes | PASS |
| 6 | State e-Office | gov.p2.file-movement | `stateFileMovementEOffice` | yes, n-level | yes | dispatch | yes | yes | PASS |
| 7 | State RTPS | gov.rtps | `stateRightToPublicServices` | yes | yes | – | yes | yes | PASS |
| 8 | State pension | gov.pension | `statePensionSanction` (HoO power from `pension-powers`, CCS (Pension) Rules 2021 r.59 / r.44(1)) | yes | yes | AG / PAO | yes | yes | PASS (after SL-30) |
| 9 | Banking | sec.bank.payment-dual-auth | `bankPaymentAboveBandNeedsCheckerAndAuthoriser` (2 approvals, 2 persons, 2 seats, class (c) `bank.transfer`) | yes, SoD | yes | payment | yes | yes | PASS (after fix 3) |
| 10 | Banking | sec.bank.loan-origination | `bankLoanOrigination` | yes, borrower party | yes | sim | yes | yes | PASS |
| 11 | Capital markets | sec.cap.investor-onboarding | `capitalMarketInvestorOnboarding` | yes | yes | sim | yes | yes | PASS |
| 12 | Insurance | sec.ins.motor-claim | `insuranceMotorClaim` (CLOSED) | yes | yes | claims.pay | yes | yes | PASS |
| 13 | Insurance | sec.ins.death-claim | `insuranceDeathClaim` (CLOSED) | yes | yes | claims.pay | yes | yes | PASS |
| 14 | E-commerce | sec.ecom.gst-tcs | `ecommerceGstTcsReconciliation` | yes | yes | GST | yes | yes | PASS |
| 15 | E-commerce | sec.ecom.seller-onboarding | `ecommerceSellerOnboarding` | yes | yes | sim | yes | yes | PASS |
| 16 | Manufacturing | sec.mfg.batch-release | `manufacturingBatchRelease` (QC then QA) | yes, SoD | yes | – | yes | yes | PASS |
| 17 | Health (India) | sec.health.lab-order | `healthIndiaLabOrder` | yes | yes | – | yes | yes | PASS |
| 18 | Health (India) | sec.health.admission-discharge | `healthIndiaAdmissionToDischarge` | yes | yes | – | yes | yes | PASS |
| 19 | DPDP (India, all sectors) | sec.legal.dpdp-rights | `dpdpDataPrincipalRequest` | yes | yes | – | yes | yes | PASS |
| 20 | US health (HIPAA) | sec.ushealth.access-request | `usHealthHipaaRightOfAccess` | yes | yes | – | yes | yes | PASS |
| 21 | US health (HIPAA) | sec.ushealth.baa | `usHealthBusinessAssociateAgreement` (stays active for its term) | yes | yes | – | yes | n/a (live) | PASS |
| 22 | All (long cases) | sec.ushealth.baa | `longCaseActorLogBeyondTheEngineStringColumn` (41 rounds) | – | – | – | actor log over 4,000 chars | – | PASS (after fix 1) |

In the Connector column, "–" means the happy path calls no connector. `X12`, `FHIR` and the payment
connectors on these paths are covered by `PilotScenarioTest` (US claim with 835 remittance and ledger
adjustment, utility billing, p1 unknown outcome).

**Earlier evidence still valid:**
- `PilotScenarioTest`: p1 to p4, works tenant override, the US claim, utilities.
- `AllPacksSmokeTest`: every startable process reaches its first wait state.

**Catalogue sweep.** The same configuration-only answerer was run over every main process of every pack
(130 processes):
- 50 reached their end;
- 64 stopped at a wait state that needs scenario data (a message, a timer or a business choice);
- 16 refused.

The refusals break down as follows:
- **Real model defects, now fixed:** pension and death claim, see the table below.
- **Delegated-power gaps in IFMS packs:** SL-31, now fixed by per-pack powers tables (6 of 8 are readiness placeholders until confirmed).
- **Driver artefacts:** one seed person holding two seats, so SoD rightly refuses. These are not defects.
- **Engine-variable size:** fix 1.

Uncovered, and not claimed:
- the other India sectors (telecom, utilities, education, HR, NBFC, SCM): only smoke and pilot evidence;
- worldwide PFM and EU GDPR: no end-to-end scenario (SL-33);
- PostgreSQL acceptance of this suite: not run, because H2 shows the behaviour and the DB matrix belongs to
  another agent.

### Defects found and fixed (register SL-48, SL-49, SL-50 = fixes 1, 2, 3)

| # | Defect (evidence) | Root fix | Regression test |
|---|---|---|---|
| 1 | The actor log of a case is an engine **string** variable, and `ACT_RU_VARIABLE.TEXT_` holds 4,000 characters. A long case (BAA, 4,015 characters) crashed with `JdbcBatchUpdateException: Value too long`. Any long case in any sector was affected. | `Vars.setJsonDocument` stores the log as a UTF-8 byte array. `ActorRecorderListener` and `PackStartListener` write it this way; all readers go through `Vars.jsonList`, which already accepted bytes. `Vars.canonical(byte[])` is now `sha256:<hex>` instead of array identity, so the `RULE_EVALUATED` inputs digest is deterministic again. | `longCaseActorLogBeyondTheEngineStringColumn` |
| 2 | `pension`: `candidateGroups="${HOO-P}"` is evaluated as `HOO − P`, giving `PropertyNotFoundException 'HOO'`, so every pension case failed. `sec-ins-death-claim`: `${INVESTIGATOR}` is not a variable, so the investigation path failed. Both write a seat id where a variable is expected. | Data only: the seat variables `verifySeat=HOO-P` and `investigatorSeat=INVESTIGATOR` are set in the packs' own configuration step, and the models and SLA `seatVar` fields use them. | `PackModelLintTest.seatsAreReferencedThroughVariables` (fails on any seat literal in an assignment expression or `seatVar`) |
| 3 | `sec-bank-payment-dual-auth`: the powers table gave the checker 25 lakh "alone". Above the band the process routes checker → authoriser, but the checker's approval was refused (`422 POWER_LIMIT_EXCEEDED`), so the pack's own acceptance SEC-BANK-PY-1 could never pass. | Data only: `sec-bank-pay-powers.dmn` gains the input `authLevels` and a concurrence row. With two levels, the checker may concur up to the authoriser band, and the authoriser's own power is checked again at level 2. | `bankPaymentAboveBandNeedsCheckerAndAuthoriser` |

Notes on the fixes:
- **Fix 3 is now an owner decision (2026-11-08, "हाँ, रखो").** The checker's concurrence is capped at the
  band of the authoriser covering the payment (`authBandMax`); above every band it is refused. Only the
  SYNTHETIC band amounts stay tenant-configured (readiness placeholders). Tests: `BankPaymentConcurrenceTest`
  (register SL-50).
- **The pack's own acceptance blocks never ran.** Pack `acceptance[]` blocks are not executed by any test,
  which is how the problem behind fix 3 went unnoticed (SL-32).

## B. Capability conformance against Flowable and Operaton

This is a re-verification of the documents' rows against code and tests. Rows were counted as follows:
- **Claimed:** the row says a strength is present, or a weakness is absent or fixed.
- **Tested:** a test method asserts the row. Whether that test passes was not re-run here except in
  `sangam-packs`.

| Document | Claimed | Tested | Claimed, no test | Contradicted | Unverified | Out of scope |
|---|---|---|---|---|---|---|
| CAPABILITY-COVERAGE (C01–C16) | 16 | 10 | 3 | 1 (C12) | 1 | 1, plus 18 "missing" N-rows |
| PREMIUM-GAP-RECHECK (present/partial) | 14 | 3 | 11 | 0 | 0 | 33 |
| FLOWABLE-WEAKNESS-AUDIT (verdicts b and c) | 34 | 14 | 17 | 0 | 3 | 13 |
| FLOWABLE-INTO-OPERATON (B-1…24 keep) | 24 | 11 | 6 | 0 | 6 | 1 |
| OPERATON-UI-PARITY (pages and "Built" actions) | 51 | 0 | 51 | 0 | 0 | partial/missing rows |
| TRACEABILITY-REGISTER (1,488 implemented or data-only) | 1,488 | GP-01…55 all have methods (gp21 = SL-12) | not row-checked | 3 | about 1,400 UNVERIFIED | 38 |

### Contradictions

- **C12, SLA and business calendar "Built".** Packs still run on the fixed `WorkingDayCalendar`
  (`sangam-server … PacksWiring.java:142`), so tenant holidays do not reach pack SLAs. The register's own
  §4.2 #8 agrees. Tracked as SL-34.
- **TRACEABILITY-REGISTER and PREMIUM-GAP-RECHECK disagree** on NG-01…47: 34 implemented in one, 31 MISSING
  in the other. NG-23 says there is no CLI, but `tools/sangam-cli` exists. Both documents need re-baselining
  (documentation fix, SL-36).
- **FWT-001…057 are all "test-pending" in the register**, but 17 now have tests: FWT-001…006, 012, 018…022,
  046, 049 and 051. **D-12** says a DMN binding is defaulted to `deployment`, while the code rejects a
  missing binding (`InheritedWeaknessH2Test#businessRuleTaskWithoutBindingIsRejected`), which is safer.
  These are documentation fixes only.

### Claimed with no test, highest risk first

Recorded as SL-37 with exact scope:
- **Money and correctness:**
  - FW-07: fenced completion returns 409.
  - FW-31 and FW-32: no-correlation buffering, no `"null"` unique start.
  - FW-35: sequential ad-hoc race.
  - FW-36: second exclusive job.
  - FW-34: injection caps.
  - FW-37: hand-off errors.
  - FW-28: poison acknowledgement.
  - C03: server-side form validation.
  - C11: document versions.
- **Security:**
  - FW-30: reserved skip variables.
  - FW-33: `${}` in injected specs.
  - FW-38: debugger `NOT_SANDBOX`.
  - FW-29 and FW-40: tenant mismatch, content tenant.
  - FW-46 and FW-39: upload limit.
  - UI `/engine-rest` role gate.
- **UI parity:** none of the 51 claimed UI rows has a test. The only UI test is `src/lib/logic.test.ts`.

### Weaknesses verified absent here, with a test

**In-flight version binding of rules.** Pack DMN runs through `DecisionDelegate`, which uses the
`deployment` binding by default. `ChangeAdoptionTest.newRuleVersionAndApprovalLevelApplyToNewCasesOnly`
starts a case under v1, deploys v2 of the pack, and shows the v1 case keeps rule version 1 and one approval
level while the new case gets version 2 and two levels.

Competent-authority routing (`sangam-core-sanction`, `binding=latest`) and the delegated-powers check
(`PowerLimitGuardListener`) use the table in force on the action date **by design**. The power is the
power held when the officer acts. This is documented in the classes and is not a defect.

## C. Change adoption (configuration only, effective-dated, in-flight safe)

| Change | Test | Result |
|---|---|---|
| New DMN rule version | `ChangeAdoptionTest.newRuleVersionAndApprovalLevelApplyToNewCasesOnly` | PASS |
| New approval level (band lowered from 25 to 10 lakh) | same test: new case has 2 sanctions, in-flight case has 1 | PASS |
| Delegation with an effective date (seat holding) | `delegationTakesEffectOnItsDateWithoutRedeploy` | PASS |
| Locale, fiscal calendar, document types, connector bindings: profile version pinned per case | `runningCaseKeepsItsProfileBinding` (packs side); effective-dated resolution in `sangam-config` `ProfileDataTest` and `ProfileBusinessClockTest` | PASS (packs side); config side existing tests, not re-run |
| New document type, end to end through a running pack | none | gap SL-35 (open) |
| Tenant-specific rule override | `PilotScenarioTest.worksOrderOfSanctionsIsPackConfiguration` | PASS (existing) |

## D. CRM-grade platform qualities

Reference platforms are Salesforce Platform, Dynamics 365 / Power Platform, HubSpot, Zoho and ServiceNow.
Their official documentation pages are named per row, but **none was fetched in this pass**, so every
reference is UNVERIFIED. The Sangam mapping is from code search: "no test" means no test class names it.

| Quality | Reference (official docs, UNVERIFIED) | Sangam equivalent | Test | Status |
|---|---|---|---|---|
| Versioned REST API | Salesforce API end-of-life policy; Dataverse Web API versions | `/api/v1`, `sangam-rest/api/sangam-api.yaml` | none | present, no test |
| API deprecation policy (Deprecation/Sunset headers) | Salesforce API end-of-life policy | none | – | **ABSENT** (SL-38) |
| Events, webhooks, CDC | Salesforce Platform Events and Change Data Capture; HubSpot webhooks | Outbox, `OutboxRelayService`, `HttpWebhookPublisher`, `WebhookInboxReceiver` | `E5OutboxInboxIT`, `OutboxRelayIT`, `InboxIT`; publisher none | partial (SL-39) |
| Idempotency | Salesforce upsert by external id | `IdempotencyService`, `JdbcIdempotencyAdapter` | `E4IdempotencyIT`, `IdempotencyStoreIT` | PRESENT + TESTED |
| Metadata-driven forms and profiles | Salesforce custom objects; Dataverse tables | forms JSON, `FormSchemaValidator`, profiles | `ProfileDataTest` | partial; tenant custom objects and fields **ABSENT** (SL-40) |
| Validation rules and formulas | Salesforce validation rules and formula fields | `ValidationEngine`, `FormulaEvaluator`, DMN/FEEL | FEEL under SL-05/06/07 | present, no test (SL-40) |
| Declarative automation | Salesforce Flow; Power Automate | BPMN/CMMN/DMN packs, modeller | `E16*PatternsIT`, this suite | PRESENT + TESTED |
| Versioned packages: dependencies and upgrade | Salesforce 2GP; Dataverse solutions | `PackDeployer`, `PackRegistry`, bundles | `PackDeploymentTest`, `DependencyResolutionTest`, `ChangeAdoptionTest` | PRESENT + TESTED |
| Package uninstall and rollback | Salesforce 2GP uninstall | none | – | **ABSENT** (SL-41) |
| Package signing | AppExchange security review | `BundleSigner`, `Ed25519Signer` | `PlugAndPlayAcceptanceTest` | PRESENT + TESTED; marketplace ABSENT |
| Sandboxes and promotion between environments | Salesforce sandboxes; Power Platform environments | none (`TenantMoveService` is not promotion) | – | **ABSENT** (SL-42) |
| Per-tenant limits and quotas with clear errors | Salesforce governor limits; HubSpot API limits | `BulkOperationGuard`, `OutboxCapacityMonitor`, public-registry rate limiter only | – | **ABSENT** for tenant API quotas (SL-43) |
| OAuth2 client credentials | Salesforce client credentials flow | SDK `ClientCredentialsTokenProvider` | none | present, no test |
| Named credentials and secret refs | Salesforce Named Credentials | secret references, `SecretResolver` | `FwtChannelGuardsTest` | partial |
| Connectors, retries, DLQ | ServiceNow IntegrationHub | `ConnectorResult`, `DeadLetterStore`, `OutboxRepair` | `T08PoisonOutboxIT`, `OutboxRepairDeadLetterTest` | PRESENT + TESTED; live HTTP connector SL-17 (other agent) |
| Bulk data API | Salesforce Bulk API 2.0 | none for ingest | – | **ABSENT** (SL-44) |
| SDKs | HubSpot client libraries | `sdks/java`, `dotnet`, `php`, `python`, `typescript` | not confirmed in CI | present, UNVERIFIED |
| Multi-tenant isolation | Salesforce multitenant architecture | `TenantGuard`, `TenantContext` | no cross-tenant negative test found | present, no test (SL-45, security, first) |
| Audit and field history | Salesforce field history; Dataverse auditing | `AuditChain`, `FieldAudit`, actor log | `AuditChainIT`, this suite | PRESENT + TESTED (chain) |
| i18n (Hindi and English) | Salesforce Translation Workbench | bilingual pack and form labels (`en`/`hi`), `LocaleSettings` | label presence not asserted | partial |
| Schema and instance migration with in-flight safety | Salesforce Flow versions | deployment binding, profile binding, Liquibase | `ChangeAdoptionTest`; instance migration none | partial |
| LTS upgrade path | Salesforce and ServiceNow release cadence | none | – | **ABSENT**, documentation (SL-46) |

Most important first (security, correctness, money):
1. SL-45 tenant isolation negative tests.
2. SL-43 per-tenant quotas returning 429 with a problem body.
3. SL-39 webhook publisher (signing, retry, DLQ, replay).
4. SL-40 validation and formula evaluator tests.
5. SL-41 uninstall and rollback.
6. SL-38 deprecation policy.
7. SL-44 bulk API.
8. SL-42 sandbox promotion.
9. SL-46 LTS policy.

None of these was fixed in this pass. They are recorded with scope in the gap register.
