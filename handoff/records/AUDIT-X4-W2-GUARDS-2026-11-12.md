# Audit X4: Wave 2 close-out, guard tests recomputed statically on the live tree, 2026-11-12

**Auditor:** X4 (read-only).
**Tree:** `D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524`. HEAD is still `0e53f91b`, so all Wave 2 work is uncommitted: 246 tracked files are modified, and 2831 files are untracked or new.

**Method:**
- Same method as X2 (`AUDIT-X2-WAVE1-TESTS-2026-11-12.md`). No Maven, npm or test runs, and no edits apart from this file.
- Every guard was recomputed as its test computes it:
  - Python ports of the test's regexes, walk rules and allow-lists, in scratchpad `C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\`:
    - the X2 scripts `x2_arch.py`, `x2_patch.py` and `x2_banned.py`, reused unchanged;
    - `x4_guards.py`, a copy of `x2_guards.py` with the live allow-lists;
    - new ports `x4_more.py`, `x4_outbox.py`, `x4_check.py`, `x4_sdk.py`, `x4_spdx.py` and `x4_syms.py`.
  - For `DomainNeutralityGuardTest`, the live test body itself, run as a single-file Java program (`x4dn/DN.java`). It is generated mechanically from the test: package, JUnit imports and `@Test` are stripped, and the root path is passed in.
- **Guard-test changes since 0e53f91b**, and how each was taken into account:
  - `ArchitectureGuardTest`: adds the M-097 determinism ratchet.
  - `BusinessDateGuardTest`: drops the SimulatorConnectorGateway entry (fixes X2-05).
  - `MoneyGuardTest`: adds `AgentUsage.java` with 2 hits (X2-06, owner OK).
  - `DomainNeutralityGuardTest:271`: now points at `sim/SimulatorConnectorGateway.java` (fixes X2-09).
  - `decision-topics.txt`: adds `ADR:D-27`.
  - New: `OutboxDestinationPublishersTest` and `ModelGuardCardinalityTest`.

## Result: every guard PASSES on the live tree

| # | Guard (module) | Result | Recomputed evidence |
|---|---|---|---|
| 1 | `ArchitectureGuardTest.mainClassesStayUnderTheSizeLimit` (sangam-core) | PASS | 2360 main Java files (933 under `sdks/`, generated included). All 13 `CLASS_SIZE_ALLOWED` files are exactly at their ceiling: OperatonEngineAdapter 1195, OperatonTaskAdapter 981, and so on. No file outside the list is over 800. The largest unlisted files are `sdks/java/.../generated/api/RegistriesApi.java` (775) and `OperatonWorkspaceAdapter.java` (774). The generated API classes are now split (manifest `lineLimit` 800, 67 api classes), and `SangamTestEngine.java` is 734. |
| 2 | `ArchitectureGuardTest.coreIsDeterministic` (M-097 ratchet) | PASS | 8 matching code lines. They equal the providers exactly (SangamClock 1, BusinessClock 1, IdSource 4) plus the debt (JobIdentityBinder 1, LockScope 1). Nothing is new, grown or shrunk. `DETERMINISM_ALLOWED` size is 2, within the limit of 3. |
| 3 | `ArchitectureGuardTest.noDisabledTests` | PASS | No `@Disabled`/`@Ignore` in any test source, sdks included. `DISABLED_ALLOWED` is empty. |
| 4 | `ArchitectureGuardTest.everyJavaSourceCarriesTheSpdxHeader` | PASS | 0 Java files are missing the header. No file is non-UTF-8. |
| 5 | `ArchitectureGuardTest.coreNeverDependsOnOperaton` | PASS | No `org.operaton` in sangam-core main or in `sangam-core/pom.xml`. |
| 6 | `ArchitectureGuardTest.noGrpcInProductSources` | PASS | No `io.grpc`/`com.google.protobuf` and no `.proto` under `/src/main/`. |
| 7 | `EnginePatchLedgerGuardTest` (sangam-engine, PATCHES.md) | PASS | Allow-lists A and B: no unlisted and no stale entries. The six X2-01 files are now listed. C (session factories) and D (FailedJobCommandFactory) are clean. The single TaskManager is `deploy/TenantScopedCreation.java`, which list D expects. |
| 8 | `DecisionCitationGuardTest` (sangam-server) | PASS | 696 citations checked across every `/src/main/` Java file, the generated Java SDK included, with 0 errors. Every bare id from X2-03 (product and the 58 SDK ids) is gone. `ADR:D-27` resolves through the new topic line. |
| 9 | `ConsumerIdsOutOfProductFilesTest` (sangam-core) | PASS | No hits. X2-02 (`IFMS R-102` in SeatResolutionDelegate and SodGuardListener) is fixed. |
| 10 | `BusinessDateGuardTest` (sangam-core) | PASS | Every remaining allow-list entry matches its exact count, and there are no violations. |
| 11 | `MoneyGuardTest` (sangam-core) | PASS | Rule 1: the 10 `FLOAT_ALLOWED` entries match exactly, including AgentUsage = 2, with no extras. Rule 2: no undeclared decimal money column; `REG_METER_READING.delta` is still present, so its entry is not stale. Rule 3: no billing rounding outside the allowed form. |
| 12 | `DomainNeutralityGuardTest` (sangam-core) | PASS | The test's own lexer, run on the live tree, reports no FAIL in `productJavaCarriesNoCountryDefault` or `theScanCatchesEachCategory`. The path at `:271` exists. |
| 13 | `ReactorBannedDependenciesTest` (sangam-core, pom-level) | PASS | The `sangam-banned-dependencies` execution is present after pluginManagement. All 16 licence-policy purls are enforced. LIC-7 (`liquibase-core:[5,)`), the gRPC ban and the exact FEEL include are present. No driver ban has a test or `*` scope. |
| 14 | `ProductionPredicateGuardTest` (sangam-core) | PASS | No hits outside `ProductionMode.java`. |
| 15 | `JacksonModuleDiscoveryGuardTest` (sangam-core) | PASS | No discovery calls in main or test Java. |
| 16 | `TestResourcesNotAutoDeployedTest` (sangam-server) | PASS | No bpmn, dmn or cmmn file under `sangam-server/src/test/resources`. The E46 models are `*.bpmn.model`. |
| 17 | `ModelCodeAllowListGuardTest.theOwnClassListIsExactlyTheReviewedSangamDelegates` (sangam-engine) | PASS | 69 classes listed, 69 found, none missing, none stale. |
| 17b | `ModelCodeAllowListGuardTest`, its two model tests | preconditions PASS | 208 shipped models (at least 200 required) and 10 frozen E46 models (at least 8 required). The `check()` semantics are not ported, so the test phase decides. |
| 18 | `OutboxDestinationPublishersTest` (sangam-server, new) | PASS | Full port. 0 unresolved, and the 5 `CONFIGURED` entries all match the source with no stale ones. All 13 destinations are covered by the 15 publishers. Every expected destination and publisher is found, and `notifications`/`sangam.registry.events` are absent. |
| 19 | `FeelBoundaryGuardTest` (sangam-engine), import and qualified-name scans | PASS | 2360 main sources scanned. No `org.camunda`/`scala` reference outside `engine/feel/`. |
| 20 | `CheckResultGuardTest` (sangam-packs), all 3 scans | PASS | T1: 29 pre-fills, all reviewed, with no stale, duplicate or reason-less entries. T2: 54 clear steps, none bad. T3: no missing not-confirmed conditions. |
| 21 | `PackMoneyGuardTest` (sangam-packs) | PASS (inferred) | Not fully ported. Since 0e53f91b the only pack-resource change is `p3-grant-sanction/sangam-p3-grant-application.bpmn`: the default `duplicateSeat=DOCVERIFIER-A2` (not money under the `money.json` convention) and `candidateGroups`. The guard's input is therefore unchanged in money terms; packgen `--check` was green at the checkpoint. |
| 22 | `ModelGuardCardinalityTest` (sangam-packs, new; behaviour test) | precondition PASS | `ModelGuard.MAX_MULTI_INSTANCE` = 50, matching the test's "1".." 50 " / "51" cases. The messages carry "without a value", "not a whole number", "< 1", "has no sangam.maxCardinality" and "GP-09". Both fuzz seeds exist. |
| 23 | SDK surface guards (`test_generated_surface.py` and the Java, .NET, PHP and TS equivalents; X2-08) | PASS (static part) | Contract operationIds 1074 = manifest 1074, none missing, none extra. Every manifest `java` entry has its generated method in its `*Api.java`. |

**Other `*Guard*Test` classes are runtime or behaviour tests, not source scans.** Static recompute does not apply to them; their imports and symbols resolve (see below):
- `LiquibaseLicenceGuardTest`
- `JacksonCompatibilityGuardTest` (`theBuildItselfPasses`: the reactor pins `version.jackson` 2.22.3, and sangam-bom imports `jackson-bom` and `jackson-module-scala_2.13` 2.22.3, which the 2.22 line accepts)
- `DefaultTenantIdGuardTest`
- `H2ModeGuardTest`
- `BreachPlanStartGuardH2Test`
- `OperatonWorkTopicGuardH2Test`
- `WorkerTopicGuardWiringTest` (new)
- `JdbcReplayGuardH2Test` (new)
- `ProductionGuardsTest`
- `RollbackGuardSpringTxTest`
- `Fwt{Xml,Input,Channel}GuardsTest`
- `GuardedBatchJobHandlerTest`
- `TimerFollowUpAndMultiInstanceGuardH2Test`
- `ModelCodeAllowListTest`
- the SDK `ContractGuard*` tests (.cs, .php, .py, .ts) and `ModelContractTest`

## Extra checks

**SPDX on new files since 0e53f91b** (`git diff --name-only --diff-filter=A 0e53f91b` plus `git ls-files --others --exclude-standard`, 2831 files):
- **Java: 995 new files, all with the header (PASS).** The same is true for all new .cs (866), .php (862), .py (31), .ts/.tsx (32), .go, .kt and .rs files.
- **No header (LOW; no guard enforces these).**
  - **Breaks the repo convention.** All 204 tracked .bpmn and all 245 tracked .dmn files carry SPDX, but these new fuzz-seed inputs do not (sangam-packs tests, B37/M-095):
    - `sangam/sangam-packs/src/test/resources/org/sangam/packs/PackParserFuzzTestInputs/bpmnModel/minimal.bpmn:1`
    - `.../bpmnModel/maxcardinality-without-value.bpmn:1`
    - `.../bpmnModel/maxcardinality-not-a-number.bpmn:1`
    - `.../dmnModel/minimal.dmn:1`
    - They are fuzz corpus seeds, so a header may be intentionally absent. The owner decides.
  - **No existing convention** (tracked files of these types also lack SPDX):
    - `sangam/sangam-spring-boot-starter/src/main/resources/META-INF/spring.factories`
    - `.../META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
    - `sangam/sdks/rust/sangam-worker/.gitignore`
    - `sangam/sdks/tier2/.gitignore`
    - 5 new .md files
  - **Cannot carry a comment:** 10 new .json files.

**Stale symbols in tests (light check):** all 515 Java test sources were checked, sdks included. Every `org.sangam` import, normal, static and wildcard, resolves to a declared type and, for static imports, to a member identifier in that type. There are 0 unresolved.

## Status of X2 findings at Wave 2 close-out

| X2 | Status (live) |
|---|---|
| X2-01 PATCHES allow-lists | fixed |
| X2-02 `IFMS R-102` | fixed |
| X2-03 citations (product and SDK) | fixed |
| X2-04 class-size ratchet | fixed: the adapters are back at their ceilings, and the SDK is split under 800 |
| X2-05 BusinessDate stale entry | fixed |
| X2-06 Money AgentUsage | fixed (allow-listed with owner OK) |
| X2-07 billing H2 dependency | still uncommitted, as is all of Wave 2 |
| X2-08 SDK surface drift | fixed statically (1074 = 1074) |
| X2-09 DomainNeutrality path | fixed |

**Caveats:**
- The ports use Python `re`. Java `\w`/`\b` are ASCII by default and Python's are Unicode, which only matters for non-ASCII identifiers; none are hit.
- The behaviour tests and the parser-level model checks (17b, 21, 22) are confirmed only in the test phase.
