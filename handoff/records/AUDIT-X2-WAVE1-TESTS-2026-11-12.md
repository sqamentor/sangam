# Audit X2: Wave 1 tests and guards (retroactive, read-only), 2026-11-12

**Scope:**
- Old tests contradicted by Wave 1 behaviour.
- Ratchet and guard tests recomputed against checkpoint **0e53f91b** and against the **live tree** at about 10:05Z.
- Tests that one lane wrote and another lane later invalidated.

**Method:**
- Static only. No Maven, npm or test runs.
- Every guard was recomputed the way its test computes it:
  - Python ports of the regexes, allow-lists and walk rules, in scratchpad `x2_arch.py`, `x2_guards.py`, `x2_patch.py` and `x2_banned.py`;
  - for the lexer-based `DomainNeutralityGuardTest`, the test body itself, run as a single-file `java` program (`x2dn/DN.java`).
- The checkpoint tree came from `git archive 0e53f91b`.
- Old-test sweeps for item 1 used two helper passes, across all 518 test files under `sangam/`: 273 were touched in 0e53f91b and 245 were not. I spot-verified the findings.

**Rules:** synced per `AGENT-SYNC-RULES.md`. A finding already on `WAVE2-SYNC-BOARD.md` is cited, and so is a Wave 2 entry that explains a change.

## Summary

| ID | Sev | Conf | Test / guard | At 0e53f91b | Live | Owner (lane / module) |
|---|---|---|---|---|---|---|
| X2-01 | HIGH | 98% | `EnginePatchLedgerGuardTest`: unlisted `engine.impl` users | FAILS (5 files) | FAILS (6 files) | sangam-engine PATCHES.md owner (W2-D). Rows come from B24/W1-F (MandatoryAudit*), B8/SL-124 (CommandCaseBindings) and W2-A (EngineWiring) |
| X2-02 | HIGH | 98% | `ConsumerIdsOutOfProductFilesTest`: `IFMS R-102` in product Java | FAILS | FAILS | sangam-packs (B9 text; now the packs owner) |
| X2-03 | HIGH | 97% | `DecisionCitationGuardTest`: citations that do not resolve | FAILS (5) | FAILS (10 product + 58 generated SDK) | B21/W1-C, SL-104 lines, R13 metric line; live: W2-D, W2-N, W2-A, W2-B |
| X2-04 | HIGH | 99% | `ArchitectureGuardTest.mainClassesStayUnderTheSizeLimit` (M-098 ratchet) | passes | FAILS (2 grown + 8 new over 800) | W2-L (OperatonTaskAdapter, OperatonEngineAdapter); W2-B (sdks/java) |
| X2-05 | HIGH | 99% | `BusinessDateGuardTest`: stale allow-list entry | passes | FAILS | W2-L (SL-149 removed the sim wall clock) |
| X2-06 | MEDIUM | 97% | `MoneyGuardTest.noFloatingPointOnTheMoneyPath` | passes | FAILS | W2-B generator / W2-H contract (`AgentUsage`) |
| X2-07 | HIGH | 95% | `RegistryDocumentNumbersH2Test`: no H2 driver | FAILS | fixed (uncommitted pom) | sangam-billing (B20) |
| X2-08 | HIGH | 95% | SDK `GeneratedSurface*` tests (5 SDKs) | n/a (not present) | FAILS until regenerated | W2-B with W2-H; already on the board (68, 161, 169) |
| X2-09 | LOW | 99% | `DomainNeutralityGuardTest:271` asserts a path that no longer exists | passes | passes (vacuous) | sangam-core test; the cause is W2-K M-011 |
| X2-10 | LOW | 20% | PostgreSQL ITs: more connections per cached context | possible | possible | test phase / integrator |
| X2-11 | LOW | 97% | Registry UI `{fy}` pattern (no test fails) | product gap | product gap | duplicate of **X1-02**; sangam-ui |

**Old tests contradicted by Wave 1 behaviour (item 1):** none found among the 245 untouched test files, apart from X2-07, which is a new Wave 1 test. See "Clean" below.

---

## 1. Findings

### X2-01 HIGH: EnginePatchLedgerGuardTest fails; PATCHES.md is missing allow-list rows
- **Test:**
  - `sangam/sangam-engine/src/test/java/org/sangam/engine/EnginePatchLedgerGuardTest.java:134` (`assertSame(... listedImpl, impl)`).
  - At line 145, every main source matching `\borg\.operaton\.bpm\.engine\.impl\.` must be a backticked row under "Allow-list A" or "Allow-list B" of `sangam-engine/PATCHES.md`.
- **Unlisted at 0e53f91b (still unlisted live):**
  - `sangam-engine/src/main/java/org/sangam/engine/history/MandatoryAudit.java:8-9` (`impl.history.event.HistoryEventType`, `HistoryEventTypes`). SL-135, B24.
  - `.../history/MandatoryAuditHistoryEventHandler.java:6-7` (`impl.history.event.HistoryEvent`, `impl.history.handler.HistoryEventHandler`).
  - `.../history/MandatoryAuditHistoryEventProducer.java:6` (`impl.history.producer.CacheAwareHistoryEventProducer`).
  - `.../history/MandatoryAuditHistoryLevel.java:6-7` (`impl.history.HistoryLevel`, `impl.history.event.HistoryEventType`).
  - `sangam-server/src/main/java/org/sangam/server/bridge/CommandCaseBindings.java:7-8` (`impl.context.Context`, `impl.interceptor.CommandContext`). SL-124, B8. It is new since 1105d9c, so it is in the checkpoint.
- **Live only:** `sangam-server/src/main/java/org/sangam/server/config/EngineWiring.java:85`, a `org.operaton.bpm.engine.impl.jobexecutor.JobExecutor` bean. This is W2-A's job-executor wiring (board 29 CHANGE, 93 DONE). That entry did not mention the ledger.
- **Lists C, D and the TaskManager count:** all clean. The one TaskManager is `TenantScopedCreation`, and allow-list D matches.
- **Fix:**
  - Add the six files to allow-list A (engine) and B (server) with B-n/I-n entries (I-n pins for the `history.*` internals used; MandatoryAudit* is a B-12 extension).
  - Optionally extend `EngineInternalsPinTest` for the new internals.
  - Owner: the PATCHES.md owner (W2-D last claimed it at FILE-CLAIMS:947 and released it at :967). The rows come from W1-F (B24), SL-124 (B8) and W2-A.

### X2-02 HIGH: ConsumerIdsOutOfProductFilesTest fails on "IFMS R-102"
- **Test:** `sangam/sangam-core/src/test/java/org/sangam/core/licence/ConsumerIdsOutOfProductFilesTest.java:84`. The pattern `IFMS R-\d+` (case-insensitive) runs over product sources, including comments.
- **Hits, the same at 0e53f91b and live:**
  - `sangam/sangam-packs/src/main/java/org/sangam/packs/core/SeatResolutionDelegate.java:134` reads `... gets one instance (IFMS R-102). ...`
  - `sangam/sangam-packs/src/main/java/org/sangam/packs/core/SodGuardListener.java:66` reads `... (IFMS R-102: one seat, or one person, ...`
- **Origin:** B9 (FILE-CLAIMS:466-496, 2026-11-11). This is after SL-106 added the `IFMS R-` ban (FILE-CLAIMS:179), and it is in 0e53f91b. Neither line is at 1105d9c.
- **Fix:** use generic wording, for example "consumer requirement: one seat or one person per joint round" (SG-37). Owner: sangam-packs.

### X2-03 HIGH: DecisionCitationGuardTest fails
- **Test:** `sangam/sangam-server/src/test/java/org/sangam/server/DecisionCitationGuardTest.java:105`. It scans every `.java` under any `/src/main/`, `sdks/` included.
- **Topic keywords:** `sangam-server/src/test/resources/citations/decision-topics.txt` (`ADR:D-20=data|config|generic|constant|jurisdiction`, `ADR:D-21=cell|control plane|cross-cell`, `FIO:D-39=metric|tracing`, `FIO:D-40=long poll|long-poll|cluster`).
- The guard test and the topics file are unchanged since 0e53f91b.

**Failing at 0e53f91b (all still failing live):**

| Location | Problem | Fix |
|---|---|---|
| `sangam-rest/src/main/java/org/sangam/rest/support/WorkLongPoll.java:29` `(M-053; R9, CF-3, D-40, R22)` | bare `D-40` (B21, W1-C) | write `FLOWABLE-INTO-OPERATON D-40` (the context already says long-poll) |
| `sangam-case/src/main/java/org/sangam/casefile/CaseModule.java:53` `SL-104 (ADR-D-20)` | no D-20 keyword in lines 50-56 | say "configuration" or "generic" in the sentence |
| `sangam-packs/src/main/java/org/sangam/packs/core/Vars.java:154` `SL-104 (ADR-D-20)` | same | same |
| `sangam-server/src/main/java/org/sangam/server/bridge/RegistryPortBridges.java:212` `SL-104 (ADR-D-20)` | same | same |
| `sangam-engine/src/main/java/org/sangam/engine/job/CountingFailedJobCommandFactory.java:14` `(FLOWABLE-INTO-OPERATON D-39, R13)` | no "metric" or "tracing" in lines 11-17 | add the word "metric" |

**New in the live tree (Wave 2):**

| Location | Problem | Lane |
|---|---|---|
| `sangam-integration/src/main/java/org/sangam/integration/relay/TenantAdmission.java:7` `ADR-D-21` | no cell, control-plane or cross-cell keyword in lines 4-10 | W2-N (B41; the B21 original) |
| `sangam-engine/src/main/java/org/sangam/engine/deploy/ModelCodeAllowList.java:142` `(FC-33,` | bare id | W2-D (SL-160) |
| `.../ModelCodeAllowList.java:291` `// D-51 / R24` | bare id | W2-D |
| `sangam-engine/src/main/java/org/sangam/engine/job/PartitionedAcquireJobsCmd.java:25` `M-106 FC-24 evidence` | bare id | W2-D (M-056) |
| `sangam-spring-boot-starter/src/main/java/org/sangam/spring/boot/autoconfigure/SangamDefaultsEnvironmentPostProcessor.java:32` `ADR-D-27` | D-27 is in the register, but `decision-topics.txt` has no `ADR:D-27=` line | W2-A (M-014 starter); add the topic line (server test resource owner) |
| `sdks/java/src/main/java/org/sangam/sdk/generated/**` | 58 bare ids (FC-01..FC-59, D-0, D-32, D-34), e.g. `generated/model/Task.java:7` `(FC-52 lifecycle fields included)`, copied from contract descriptions | W2-B: the generator must qualify the ids or strip them from javadoc, or, with owner decision, the guard scope excludes generated SDK trees |

### X2-04 HIGH (live): M-098 class-size ratchet fails
- **Test:** `sangam/sangam-core/src/test/java/org/sangam/core/arch/ArchitectureGuardTest.java:409`. Ceilings are listed at `:84-96` and the walk covers the whole `sangam/` folder, `sdks/` included.
- **0e53f91b:** all 13 counts match exactly, and there is no new file over 800 lines.
- **Live failures:**
  - `sangam-rest/.../operaton/OperatonTaskAdapter.java`: ceiling 981, now **1101** (+120). W2-L SL-132/SG-15 denials and ReadTransactions (FILE-CLAIMS:740/816).
  - `sangam-engine/.../OperatonEngineAdapter.java`: ceiling 1195, now **1244** (+49). W2-L SL-157 add-only overloads (board 61 CHANGE, 76 DONE). The board entries do not mention the ratchet.
  - New files over 800 lines, all untracked W2-B output (FILE-CLAIMS:736/920-921, board 86 DONE):
    - `sdks/java/.../generated/api/RegistryApi.java` 2907
    - `CaseFilesApi.java` 1736
    - `AdminApi.java` 1142
    - `ResourcesApi.java` 971
    - `ProgrammesApi.java` 944
    - `FieldApi.java` 926
    - `OpsApi.java` 872
    - the hand-written `sdks/java/src/main/java/org/sangam/sdk/testing/SangamTestEngine.java` 997 (M-021)
- **Near the limit, not failing:** `OperatonWorkspaceAdapter.java` 765 (W2-L) and `generated/api/RegistriesApi.java` 775.
- **Fix:**
  - The ratchet forbids raising a ceiling, so split OperatonTaskAdapter (for example SoD/denial into a collaborator) and OperatonEngineAdapter (for example lease/fetch helpers) back to at most their ceilings. Owner: W2-L.
  - For sdks/java, either have the generator split API classes per tag or sub-tag below 800 lines and split SangamTestEngine (W2-B), or let the owner decide that generated SDK trees are out of the D4 scope (an explicit guard exclusion, since the allow-list may not grow).

### X2-05 HIGH (live): BusinessDateGuardTest has a stale allow-list entry
- **Test:** `sangam/sangam-core/src/test/java/org/sangam/core/time/BusinessDateGuardTest.java:57` (`allow("sangam-packs/.../sim/SimulatorConnectorGateway.java", 1)`), checked at `:114`. Exact counts are required.
- **Live:** 0 hits. W2-L SL-149 (simulator wall clock, FILE-CLAIMS:761/818) removed the `LocalDate.now(...)`, which is a correct change. The guard now fails on the stale entry ("tighten them").
- **0e53f91b:** passes.
- **Fix:** delete line 56-57 (comment and entry). Owner: W2-L, with the sangam-core test file claim. There is no board entry yet.

### X2-06 MEDIUM (live): MoneyGuardTest flags generated SDK doubles
- **Test:** `sangam/sangam-core/src/test/java/org/sangam/core/money/MoneyGuardTest.java:120`. It walks every `/src/main/` Java file, sdks included.
- **Hit:** `sdks/java/src/main/java/org/sangam/sdk/generated/model/AgentUsage.java:24-25`, `Double costUnits` and `Double budget` (two hits; no allow-list entry). This mirrors `rest/port/InsightsPort.java`, which is already allow-listed for AI token cost units.
- **Fix:** either the generator emits a non-money name or type for these, or `FLOAT_ALLOWED` gets the generated path with the same "AI token cost units, not money" reason (owner OK needed; this list is not shrink-only). Owner: W2-B with W2-H.

### X2-07 HIGH (0e53f91b only): RegistryDocumentNumbersH2Test had no H2 driver
- **Test:** `sangam/sangam-billing/src/test/java/org/sangam/billing/numbering/RegistryDocumentNumbersH2Test.java:53-57` (`DriverManager.getConnection("jdbc:h2:mem:...")`).
- **Cause:** at 0e53f91b, `sangam-billing/pom.xml` has no `com.h2database:h2`, and the test-scope h2 of the modules it depends on is not transitive. All 5 tests fail with "No suitable driver".
- **Live:** fixed by an uncommitted test-scope h2 dependency in `sangam-billing/pom.xml` (SL-130/131 comment). Only needs committing. Owner: B20 / sangam-billing.

### X2-08 HIGH (live, known): SDK generated-surface tests drift from the contract
- **Tests:**
  - `sangam/sdks/python/tests/test_generated_surface.py:24-27` (`assert contract == generated`)
  - `sdks/java/src/test/java/org/sangam/sdk/generated/GeneratedSurfaceTest.java`
  - `sdks/dotnet/tests/Sangam.Sdk.Tests/GeneratedSurfaceTests.cs`
  - `sdks/php/tests/GeneratedSurfaceTest.php`
  - `sdks/typescript/test/generated-surface.test.ts`
- **Drift:**
  - First pass: manifest 1046 vs contract 1074 operationIds. Missing were the 13 scim* operations, the env-binding, package-bundle and work-step operations, listInstanceHandlers, queryProcessInstance and updateProcessInstance.
  - Recount at about 10:05Z: manifest 1074 vs contract 1079, still drifting.
- **Already on the board:** W2-H REQUEST (68), integrator REQUEST (161), W2-B ACK "regenerating" (169). Recheck at Wave 2 close-out after the contract freeze.

### X2-09 LOW: DomainNeutralityGuardTest asserts a moved path
- **Where:** `sangam/sangam-core/src/test/java/org/sangam/core/generic/DomainNeutralityGuardTest.java:271` asserts `allowListed(".../packs/sim/WorkingDayCalendar.java")`.
- **Why it matters:** the file moved to `packs/calendar/` (W2-K M-011, FILE-CLAIMS:734/781). The assertion still passes, because only the substring `/sim/` is tested, but it now names a file that does not exist.
- **Main scan:** recomputed with the test's own lexer, it is clean at both 0e53f91b and live. The moved file carries no zone, currency or country literal.
- **Fix:** point it at `sim/SimulatorConnectorGateway.java`.

### X2-10 LOW: PostgreSQL ITs hold more connections per cached Spring context
- **Cause:** each context now opens:
  - a non-pooled LISTEN connection (SL-140, `IntegrationWiring.sangamWakeupListener`);
  - the compliance leaf pool `sangam-compliance-audit` (max 2, SL-137);
  - live, also the security denial pool (max 2, board 28) and the quota pool via `ComplianceWiring.leafPool` (board 144).
- **Risk:** a suite with many cached contexts on one container may hit `too many clients`. No test asserts connection counts.
- **Fix, only if it happens:** raise the container's max_connections or cap the context cache.

### X2-11 LOW: registry UI `{fy}` pattern
This duplicates **X1-02**; it is listed only for traceability.
- `sangam-ui/src/modules/registryNumbering.ts:17-18` offers fiscalStartMonth only for reset FISCAL_YEAR.
- But `NumberSeriesDefinition.java:40-42` also requires it for `{fy}`/`{fyStart}`.
- The hint `en.json:3006` suggests `{prefix}/{fy}/{seq:6}`.

No test fails.

---

## 2. Guards recomputed and found clean

| Guard | 0e53f91b | Live | Notes |
|---|---|---|---|
| `ArchitectureGuardTest.coreNeverDependsOnOperaton` | clean | clean | |
| `ArchitectureGuardTest.noGrpcInProductSources` | clean | clean | |
| `ArchitectureGuardTest.noDisabledTests` (`@Disabled`/`@Ignore` ban) | clean | clean | DISABLED_ALLOWED is empty; this includes sdks and `sangam-packs/src/test/m095-pending` |
| `ArchitectureGuardTest.everyJavaSourceCarriesTheSpdxHeader` | clean | clean | every new Wave 1/2 file, including the generated SDK, has the header |
| `ArchitectureGuardTest.coreIsDeterministic` (M-097, live only) | n/a | clean | the 8 matching lines equal providers SangamClock 1, BusinessClock 1, IdSource 4 and debt JobIdentityBinder 1, LockScope 1. The ThreadLocals are carried for W2-D (board / FILE-CLAIMS:730-836) |
| `DomainNeutralityGuardTest.productJavaCarriesNoCountryDefault` | clean | clean | run with the test's own lexer and the JDK currency and country lists |
| `ReactorBannedDependenciesTest` (4 tests) | clean | clean | 16 policy bans enforced; LIC-7, gRPC and the FEEL exact include are present; driver scopes OK |
| `ProductionPredicateGuardTest` | clean | clean | |
| `JacksonModuleDiscoveryGuardTest` | clean | clean | |
| `MoneyGuardTest` rule 2 (decimal money columns) and rule 3 (billing rounding) | clean | clean | rule 1 is X2-06 (live) |
| `BusinessDateGuardTest` | clean | X2-05 | |
| `ConsumerIdsOutOfProductFilesTest` | X2-02 | X2-02 | |
| `ModelCodeAllowListGuardTest.theOwnClassListIsExactlyTheReviewedSangamDelegates` (W2-D, live) | n/a | clean | 69 listed = 69 found |
| `FeelBoundaryGuardTest` (import part) | clean | clean | no `org.camunda`/`scala` import outside `engine/feel` |
| `TestResourcesNotAutoDeployedTest` | clean | clean | no bpmn/dmn/cmmn under sangam-server test resources |
| `EnginePatchLedgerGuardTest` lists C and D and the single TaskManager | clean | clean | list A/B is X2-01 |

**Not recomputed (needs the engine parser or pack DMN/BPMN semantics):**
- `ModelCodeAllowListGuardTest` model checks (W2-D's own new test);
- `PackMoneyGuardTest`, `CheckResultGuardTest`, `ReasonCodeCatalogueTest`, `RejectionReasonCoverageTest`, `CrossPackCallBindingTest`, `DmnModelLintTest`;
- `OutboxDestinationPublishersTest` (new, W2-N).

These are exercised in the test phase. Packgen `--check` was green at the checkpoint (ledger 6w/6z).

## 3. Item 1: behaviour changes vs old tests (all clean apart from X2-07)

**Fiscal year fails closed / `FISCAL_YEAR_NOT_CONFIGURED` / `fiscalStartMonth` required / billing period from the fiscal year (SL-125/126/127):**
- The removed calendar-year factory, `FiscalCalendar.from` returning Optional, the new `LocaleSettings` signature and the extra `BillingConnector` argument are called only from updated or new tests.
- All 8 shipped `locale.fiscal` maps (IN, BGD, BRA, GBR, IDN, KEN, PHL, USA) are complete.
- `ProfileDataTest` expects 54 profiles, which matches the files.
- `FISCAL_NOT_CONFIGURED` is a warning, and no test counts warnings.
- AT-5.json fixtures carry a complete fiscal map.
- `BillingServiceTest` and `BillingBusinessDateTest` always pass explicit periods.
- No REST or server test calls billing without a period.
- No UI test uses `resetPolicy`.
- No test outside the new `FiscalSemanticsRequireStartMonthTest` uses FISCAL_YEAR, `{fy}` or `fiscalStartMonth`.

**Receipt `RCP/{channel}/{date}/{n}`:**
- `BillingBusinessDateTest`'s `"RCP/1"` still matches the series path it uses.
- No server test asserts a document number.
- Live `BillingWiring` now wires `RegistryDocumentNumbers` (X1-04 resolved).

**Registry `reset` field name:** no old test uses either name.

**History level none/auto refused; mandatory audit at every level (SL-135):**
- Only the new tests set none, auto or variable (`GlobalHistoryNoneRefusedTest`, `MandatoryAudit*Test`, `SangamEngineSettingsFreezeTest`), and they set none per definition, which is still allowed.
- No test resource sets `history-level`.
- No test counts ACT_HI_* at none or activity.

**Exclusive jobs over hierarchies on by default (SL-136):**
- Old tests set the flag explicitly or use raw Operaton configurations.
- The Helm trace test matches the configmap.

**check-i18n:**
- `hindi-locale.test.ts` is at parity at 0e53f91b (2989/2989 keys).
- Live, en.json has 503 keys more than hi. The owning lane already relaxed the test to match the Q-06 English-only decision (SL-147).

**UI route guards (SL-143):** parity tests render modules through MemoryRouter, so the guards are not in their path. Only `access.test.ts` uses `NAV` and `canAccess`.

**Long-poll `DeferredResult` (SL-141):**
- The only MockMvc tests (`RequestStatusContractH2Test`, `ServerWebProblemHandlerTest`) do not touch `/work`.
- The async re-dispatch keeps authentication (`DevTokenFilter:77`, `TokenAuthenticationFilters:44`).
- `fetch-and-lock` is `x-sangam-idempotent: false`, so `IdempotencyFilter` is not involved.
- The E3Lease, T35Restore and TScaleLoad ITs go over real HTTP.

**LISTEN listener (SL-140):** no bean or connection count is asserted (`WebhookRelayIT` uses `WakeupNotifier.NONE`). See X2-10.

**Broker tenant admission (SL-142):** the old Kafka/JMS constructors remain as admit-all overloads (`KafkaInboxConsumer:83`).

**Idempotency encryption marker (SL-139):** `E4IdempotencyIT` and `IdempotencyStoreIT` read only `state`. No old test reads `response_body` as plain text.

**Denial audits on `cmp:d:` chains (SL-137):**
- No old compliance test asserts denials on the principal chain.
- No server test defines a non-Hikari DataSource. The `StatementCounter` proxy unwraps to Hikari.

**CDS `/cds-services` auth (SL-138):** only the new CDS tests call it.

**ModelGuard (SL-148, live):** every pack `sangam.maxCardinality` is a positive integer, so `PackDeploymentTest` stays green.

**Money minor units / profile `money()`:**
- LedgerJdbcH2, RemittanceAndSeeds, MixedRemittance, PilotScenario and Remittance835Fields were all updated.
- The untouched `Remittance835MoneyTest` checks only flags and invalid input.
- `PackLedgersH2Test` uses the unchanged pack ledger store.
- Every test ledger has the newly required currency.

**DMN thresholds ×100 (versionTag 1.0.1):** untouched DMN tests (E1, E16, E17, `PowerBandBoundaryTest`, `FeelBehaviourH2Test`) use their own inline or corpus tables.

**Packgen compliance BPMN (SL-134):**
- The 12 compliance BPMN files are unchanged at 0e53f91b. The packgen version is Wave 2.
- No test hashes them or counts 1123/186.
- The readers (`ComplianceSeatModelTest`, `ComplianceSeatRoutingH2Test`, `P3DuplicateSeatTest`) are new.

**`CrossTenantIsolationIT` seed asserts:** the lane's rewritten seed (persons in the seed, B's own REST start, event definition and channel) conflicts with no other Wave 1 change.

**Signature changes:** every changed constructor checked at 0e53f91b keeps an overload, and its callers are updated: KafkaInboxConsumer, JmsInboxListener, IdempotencyStore, JdbcIdempotencyAdapter, ComplianceAudit, SangamCompliance, WorkController, ComplianceWiring.tenants, SlaService and OutboxRelayService. The full reactor test-compile was green at the checkpoint (ledger 6z).

## 4. Item 3: one lane's test invalidated by another lane

| Finding | What happened |
|---|---|
| X2-04 | W2-L growth against the W1-H/B26 class-size ratchet; W2-B generated SDK against the same ratchet |
| X2-05 | W2-L (SL-149) against the BusinessDateGuardTest exact allow-list |
| X2-01 (live part) | W2-A EngineWiring against the SL-105 PATCHES ledger guard; at the checkpoint, W1-F (B24) and B8 against the same guard |
| X2-03 (live part) | W2-D, W2-N, W2-A and W2-B citations against DecisionCitationGuardTest |
| X2-06 | W2-B/W2-H generated `AgentUsage` against MoneyGuardTest |
| X2-08 | W2-H contract growth against the W2-B surface tests (on the board) |
| X2-09 | W2-K file move against the DomainNeutralityGuardTest path (cosmetic) |

## 5. Note for the integrator

X2-01, X2-02, X2-03 (checkpoint part) and X2-07 mean that **0e53f91b itself would not pass the unit-test stage**. The class-size gate passed there.

The ledger 6z gates were compile plus generators only, with no guard tests run. Add `ArchitectureGuardTest`, `EnginePatchLedgerGuardTest`, `DecisionCitationGuardTest`, `ConsumerIdsOutOfProductFilesTest`, `BusinessDateGuardTest` and `MoneyGuardTest` to the Wave 2 close-out gate. They are pure source scans, with no Testcontainers and no heavy lock.
