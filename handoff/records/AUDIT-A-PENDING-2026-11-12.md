# Audit A: is all Sangam code written? (read-only, 2026-11-12)

Tree: `Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524` (HEAD 1105d9c, plus 1,913 changed paths, 224 of them untracked).
Method: parsed every row of `sangam\TESTING-GAP-REGISTER.md`, read CODE-COMPLETE-PLAN, RESUME-LEDGER §5 and §6r-6w, JOINT-LEDGER §4 and §5, JOINT-PLAN Q12-Q35, HANDOFF §6 and §8, and CHANGES-SINCE-1105d9c §5. Then checked code and tests by file:line.
Nothing was built or run, and no repository file was edited. This file is the only output.

## Verdict

**No. The code is not all written, but what is left is small and named.** 7 register rows still need product or test code (class b), with SL-125 the most important. 5 more rows need only a test run, with no known code gap. Everything else is either coded in the tree (tests written, not run) or waits on the owner or IFMS. No row that claims FIXED turned out to be missing its code or its test.

## Totals

The register has 223 rows: 157 in the main table, 24 in the SL-37 sub-table and 42 in the UI-parity sub-table.

| Class | Main-table rows | Notes |
|---|---|---|
| (a) Code done (in tree or committed); only the test phase is left | 138 | Includes rows whose status text is stale (see "Stale status text") |
| (b) Code not done: OPEN, PARTIAL or being coded | 7 | Full list below |
| (b-T) Open, but the next step is only a test or install run, with no known code gap | 5 | SG-02, SG-04, SG-13, SG-14, SL-111 |
| (c) Owner action or external (IFMS) dependency, no code | 5 | SG-12, SG-18, SL-75 (P2), CI-NVD-OIDC, SL-07 |
| (d) Deferred or closed by an ADR or owner decision | 2 | SL-114 (Db2 dropped from Tier 2), SG-27 (owner exception) |
| Sub-tables (SL-37 and UI parity, 66 rows) | 66 | All PASS, FIXED, DONE or N/A, with no MISSING. Exception: FWT-D11 and FWT-D12 are "single-verified, awaits SD-428 reviewer" (test phase only). |

Confidence in the class counts: about 90%. The status cells are free text, and several rows have more than one appended status (see "Stale status text").

## (b) Code not done: the full list

| Id | What is missing | Code evidence | Conf. |
|---|---|---|---|
| **SL-125** (HIGH) | A missing fiscal year is still silently taken as a calendar year. The owner's decision is to fail closed core-wide. B11 is in the evidence phase, and no code or test exists yet. | `sangam-config\...\config\locale\FiscalCalendar.java:45-47`: `from()` returns `calendarYear(defaultZone)` (1 January, `:40-41`) when the map is null or empty. The only main caller is `LocaleSettings.java:62`. No `FiscalCalendarTest` exists anywhere. The US profile has no `locale.fiscal` (SL-97 row). | 99% |
| **SL-102** (MEDIUM) | Money thresholds are kept twice: as free-text profile amounts and as DMN literals. No code yet. | Free-text amounts are still present: `profilegen\goi.py:116` (`'2CR'`, `'5CR'`), `goi.py:218,224` (`'5L'`), `states.py:196,202`, `delta.py:239`. Row status is OPEN. | 97% |
| **SG-39** (LOW) | No first-class private-ingress switch, no internal load-balancer default, no schema entry. | `deploy\helm\sangam\values.yaml:319-323` has only `enabled`, `className: ""` and `annotations`. `values.schema.json` has no ingress entry (grep finds nothing). The route is an owner question (plan). | 98% |
| **SL-78** (MEDIUM, test code) | `CrossTenantIsolationIT` sends the event-definition and channel set-up calls but never checks their result, so that step proves nothing. | `sangam-server\...\acceptance\isolation\CrossTenantIsolationIT.java:205-209`: two `send(...)` calls whose result is discarded. | 97% |
| **SL-79** (MEDIUM, test code) | The "nobody left waiting" assertion depends on machine load. Its root cause is unproven, and deterministic synchronisation is not coded. | `sangam-server\...\bridge\QuotaPoolDeadlockH2Support.java:150` asserts `getThreadsAwaitingConnection()==0` directly, with no synchronisation. | 95% |
| **SG-15** (MEDIUM) | T-SCALE throughput has never been measured. Next-round round-trip work is uncoded: 74 commits and 43 `SEC_TENANT` reads per lifecycle. The SL-43 deadlock that blocked measurement was fixed in 460c95b. | Register row items (2) to (5). Item (4) is code work; the rest is test runs. | 90% |
| **SL-58** (HIGH, from IFMS) | E-54 T-4 failed at the SQL Server 2025 / JDK 25 smoke test. An id was allocated, but there is no root cause and no fix. The plan lists it as "test phase only", but nobody knows yet whether code is needed. | Row status: "OPEN (2026-11-09, id allocated)". No other text. | 90% |

**Being coded right now:** only SL-125 (B11, evidence phase). Nothing else is listed as running in RESUME-LEDGER §6w.

## Known-open items the caller listed: confirmation

| Item | Finding | Conf. |
|---|---|---|
| SL-125 | **Confirmed open.** See (b) above. | 99% |
| SL-117 | **Not open in code. This contradicts the brief.** The register row says FIXED in tree (B7), and the code is there: `ScheduleTenants.java:47` (`resolve`) and `:61` (WARN on a directory failure); `ComplianceWiring.java:165` uses it; `TenantAdminService.java:237` (`listForSystem`). `ScheduleTenantsTest` has 13 `@Test` methods. `ScheduleTenantsMonitorTest`, `ComplianceSchedulerEmptyTest` and `TenantAdminServiceSystemListingH2Test` exist. Only the README (docs wave) and the test run are left. | 95% |
| SL-44 | **Confirmed.** The API is CLOSED: BulkIngest, the REST endpoints and tests. The row itself says "Open: no UI; only the ops.variable sink; Oracle/SQL Server/MySQL not run". Whether a UI is wanted is an owner question; no code until then. | 97% |
| Q12, Q13, Q14, Q17, Q19, Q21, Q23, Q24, Q26, Q34 | **Confirmed unanswered.** None appears in the JOINT-LEDGER §4 decisions (JL:103-140). RESUME-LEDGER §6v and HANDOFF §6.0 list them as still open. Definitions are in JOINT-PLAN:579-601. | 97% |

Effect of each open question on code:
- **Q12:** downloads (.NET 8 and PHP for AC-08; kind and GraalVM for SG-14). This blocks test runs, not code.
- **Q13:** who signs the dependency-check suppressions.
- **Q14:** required checks on the ruleset.
- **Q17:** private vulnerability reporting (SG-12).
- **Q19:** governance (SG-18).
- **Q21:** dismissing alerts.
- **Q23:** where IFMS pack data lives. This may change the gov.ifms packs later.
- **Q24:** deleting branches.
- **Q26:** removing worktrees.
- **Q34:** the SL-53a date (UTC storage migration). This is **code that will be needed later**: a data migration for 5 dialects. There is no SL-53a register row; it is mentioned only inside SL-53.

Owner questions also still unanswered but not in the brief's list:
- SG-39 route.
- SL-07 semantics (plan "Owner decisions that block code").
- Store the NVD SSM parameter value (CI-NVD-OIDC; old Q18).
- SL-75 P2.
- US `locale.fiscal` (now SL-125).
- Q15, Q25, Q27, Q28: no answer recorded. Q16 is answered (JL:140).

## (c) and (d) rows

- **(c) SG-12:** SECURITY.md exists. Enabling private vulnerability reporting and validity checks is the owner's action (Q17).
- **(c) SG-18:** governance (Q19). Status DECISION.
- **(c) SL-75:** P1 is coded (`.github\dependabot.yml:11-12`: limit 0, `exclude-paths: ["handoff/**"]`). P2, removing the snapshots, is the owner's call.
- **(c) CI-NVD-OIDC:** coded (`security.yml:85,114,119`, OIDC to SSM). The owner must store the parameter value.
- **(c) SL-07:** lint and documentation are in 15e4135, and ADR-D-26 / X.0g decided the semantics. Still "being checked against contract C-7", and the plan lists the semantics as an owner question.
- **(d) SL-114:** Db2 dropped from Tier 2 (`SUPPORT-MATRIX.md:44`).
- **(d) SG-27:** owner exception for feel-engine. Its follow-ups (a) to (c) are SL-05, SL-06 and SL-07.

Residual owner or data items inside rows marked (a):
- SL-15: the official GA-55 form must come from the owner.
- SL-31: 6 placeholder power tables need owner or rule confirmation.
- SL-97: US fiscal year (became SL-125).

## (a) Spot-check: fixes and tests found (sample of 40 or more)

All of these exist in the tree. Paths are relative to `sangam\`.

- **SL-53:** `EngineTimeZone.java:58` (`requireUtcJvm`).
- **SL-59:** `JdbcWaitBounds.java:33`.
- **SL-80:** `CorrelationNames.java:32-38`, with `CorrelationNamesTest` and `CorrelationNamingH2Test`.
- **SL-91:** `SangamEngineSettings.java:178-180`, with `DefaultTenantIdGuardTest`.
- **SL-82:** `OutboxCapacityMonitor.java:85-88` (explicit if/else), with `OutboxCapacityMonitorTest`.
- **SL-98:** `LedgerDefinition.java:77`, with `LedgerDefinitionCurrencyTest` (4 tests).
- **SG-01:** `LedgerMoneyTest.java:117` (`ifmsBandEdgesInPaise`). SG-22 and SG-23 are at `:70` and `:81`.
- **SL-101:** `InheritedWeaknessH2Test.java:237`.
- **SL-04:** `BranchCorrelationH2Test` has 5 `@Test` methods, as claimed.
- **SL-103:** `ProductionDatabaseCredentials` plus its test (8 cases, as claimed).
- **SL-109:** `ProductionMode.java:18`, with `ProductionGuardsTest`, `ProductionModeTest` and `ProductionPredicateGuardTest`.
- **SL-123:** `ConfigWiring.java:67-68` (`config.crosswalk`), with `ConfigConnectorRegistrationTest`.
- **SL-120:** `JdbcConfigStore.java:196` (CFG_CHANGE marker).
- **SL-86:** `TenantKey.java:23` (`SHARED = AbsentKey.NONE`).
- **SL-60:** `SecurityDialect.java:71-75` (H2 uses CAST BIGINT), with `SecurityDialectTest`. The test is untracked, as the row says.
- **SL-104:** `DomainNeutralityGuardTest.java:56`: `RATCHET = Map.of()` (empty), and `governmentDefaults` is gone from all Java.
- **SG-03(c):** `TenantScopedCreation.java:170-189` overrides the `updateTaskSuspensionStateBy*` methods, with `TaskSuspensionEventsH2Test` and `TaskSuspensionDispatchTest`.
- **SL-55:** `packgen\common.py:15` and `reason_codes.py:14`, with `RejectionReasonCoverageTest` and `ReasonCodeCatalogueTest`.
- **SL-113 and SL-115:** no `999999999999` remains in packgen or the pack resources. `FundsCheckFailClosedTest`, `CheckFailClosedTest` and `CheckResultGuardTest` exist.
- **SL-116:** `PackOperationLock.java:26`, plus 3 lock tests.
- **SL-77:** `TaskFormRequirements.java:22-29`, with both tests.
- **SL-83 and SL-108:** 2 `Instant.now()` calls remain in `SecurityAdminBridge.java:246,331`. The row says these 2 response-metadata values are deliberate.
- **SL-110:** no `-Psangam-oracle-driver` or `-Psangam-db2-driver` left in `db-matrix.yml`.
- **SG-05:** `db-matrix.yml:10,102`.
- **SL-112:** mariadb lane at `db-matrix.yml:6,27`.
- **Q22:** `db-matrix.yml:13,194`.
- **SG-16:** `sangam\tools\provenance\run_provenance.py` and `test_run_provenance.py`, called at `ci-build.yml:89,98` (working directory `sangam`).
- **SL-56:** `.gitattributes:1-5` (`eol=lf`).
- **CODESCAN-SDK-TS-REDOS:** `sdks\typescript\src\http.ts:105`.
- **SDK-UNIT-TESTS (PHP seam):** `sdks\php\src\Http\HttpSender.php` and `CurlSender.php`.
- **AT-6 automation:** `SignedExamplePlugin.java`, `SignedExamplePluginTest.java`, and `PlugAndPlayIT.java:41`.
- **SL-74:** `git status` shows the 3 root files deleted.
- **SL-125 negative control:** `FiscalCalendarTest` was the only searched class that is missing.

**False-FIXED list:** none. All 84 class names searched were found, except `FiscalCalendarTest`, which belongs to the open SL-125. Confidence 95%. This confirms that the code and tests exist, not that they pass: nothing was run.

Claims marked FIXED but coded below the owner's 97% rule. The register says so openly; listed here so nothing is hidden:
- SL-87 (95%, MODIFY NULL on a CLOB).
- SL-88 (95%, `sp_describe_undeclared_parameters`).
- SL-94 (95% and 90%).
- SG-35-SANGAM (95%).
- SL-99 (restore on real SQL Server and MySQL drivers).
- J-P1-E09 in SL-32 (basis IFMS R-102 v0.1, INFERRED; matches IFMS's recommendation at JL:295).

## Stale status text (rows are correct in substance, but the first word misleads)

- **SL-106 and SL-107:** marked "OPEN", but the appended text says every part is coded or written.
- **SG-37 and SG-38:** marked "PARTLY CODED", but the appended text says the code is complete and the tests are written.
  - SG-37 caveat: the file name `SG-27-SD-301-exception.md` (an IFMS decision id) still appears in `licence-policy.json:61-62`, `check_licences.py:266`, `pom.xml:74` and `sangam-bom\pom.xml:63`. This is a documented exemption.
- **SL-03:** "IN REVIEW (compile + guard before commit)". The guard `DecisionCitationGuardTest` is committed (15e4135), and SL-68 fixed the bare citations. This is very likely done (85%).
- **SL-37 parent row:** still says "Open: the TaskDetail embedded form, the 51 UI-parity rows". Its own sub-tables mark both FIXED or DONE. Only the `FwtWorkerCompatIT` rerun and the FWT-D11/D12 SD-428 check are left.
- **SL-43:** "FIXED … (single-verified, OPEN)". This is a test-phase item.
- **HANDOFF.md:**
  - §6.0 points to `CHANGES-SINCE-1105d9c.md` §4, but the id list is §5.
  - §6c "Not started / open" still lists SL-35, the S3DocumentStore question, the `_shared` key question, the `task.suspend` events and the SDK gaps. All of these are now coded or decided.

## CODE-COMPLETE plan: every item and its status

| Wave / item | Status | Evidence |
|---|---|---|
| A: SL-84, SL-86, SL-90, SL-91, SL-87 | Done in tree | Register rows; `TenantKey.java:23`; `SangamEngineSettings.java:178` |
| A: SL-88, SL-89 | Done in tree | Rows; RESUME-LEDGER §6t |
| A: SL-94; SL-93, SL-96 | Done in tree | `MysqlUtf8mb4PlanTest`, `MysqlUtf8mb4PatchIT`, `IsolationPlanTest`, `DialectSchemaIT` |
| A: SL-57 set-up NPE | Done (SL-97 and SL-98) | `ProfileDataTest`, `LedgerDefinition.java:77` |
| A: security SL-70, 72, 73, 95, 71, 83, JIT race | Done (JIT race = SL-100) | `PlatformIssuers`, `EngineRestNotFound`, `ProblemErrorController`, `JitProvisioningRaceH2Test` |
| B1: SG-20, SG-01, SG-22 to SG-25, SL-50, PACKS4 | Done | `LedgerMoneyTest:70,81,117`; PACKS4 row |
| B2: SL-04, 05, 06, SG-19 test, ROUTING-INCIDENT-CT, SG-35-SANGAM, GP-13 (SL-101), SL-54, SL-55, SL-59 | Done | Tests listed above |
| B2: SL-07 | **Not closed**: owner semantics and C-7 check | Row L52 |
| B3: SL-77, 80, 40, 41, 42, 32, 33, 35, APPROVE-401 2b | Done | Rows; tests exist |
| B3: SL-44 server side | Done: the BulkQuota port was replaced by `TenantQuotas.checkSize` | No `BulkQuota*.java` in tree; SL-44 row |
| B3: AT-6 signed-plugin automation | Done | `SignedExamplePlugin.java`, `PlugAndPlayIT.java:41` |
| B4: CI-NVD-OIDC, SG-05, SG-16, SL-56, SL-75 P1, SG-38, SG-37, ADR-D-27, Q22 | Done | `security.yml:85`; `db-matrix.yml:13,102`; `SANGAM.md:4319` |
| B5: SDKs | Done | PHP tests written but never run (no PHP installed); CI is the first run |
| C: docs wave | **Partly done** | Done: ADR-D-27 (`SANGAM.md:4319`), ADR-D-28 (`:4339`), Db2 in SUPPORT-MATRIX, `docs\IFMS-KNOWLEDGE-LINKS.md`, CHANGES file, 12 register restatements. Open: HANDOFF §6c is stale; the SL-117 and SL-116 READMEs; the C-19 [CONTRACT] notice for SL-71/72/73 (HANDOFF §8 step 1). No Sangam→IFMS entry exists in JL §5 after 2026-11-11 09:58, although IFMS mentions a "C-19 bundle" (JL:294). It may have been sent without being logged. |
| D: stray-work centralisation | **Partly done** | wt-packs4 and wt-comp are merged (PACKS4, SL-33, SL-101). Not done: the "D:\wt-scale T-SCALE text evidence" merge (RESUME-LEDGER:349; no later record). The BulkQuota decision is not recorded, though it is superseded in code. Folder deletions wait for the owner, after the push. |
| Pre-push gates | Partly done | JDK 25 clean test-compile is green, and every generator `--check` is green (§6w). JDK 21 is not installed locally (CI covers it). The "every CLAIM has its RELEASE" check in FILE-CLAIMS cannot be confirmed mechanically: 270 CLAIM lines against 241 RELEASE lines, in mixed formats. Commit and signed push are not done. |
| Test-phase-only list | Not started (by design) | SG-04, SG-07, SG-13, SL-01, SL-12, SL-34, SL-37 residuals, SD-428, SL-78, SL-79, DB proofs, IX8/AC-07, T-SCALE, SL-58 |

The plan's test-phase-only list includes SL-78 and SL-79, but both need **test-code** changes first (see (b)). Running them again will not close them.

## Planned or decided items that have no register row

1. **SL-53a / Q34:** UTC timestamp storage migration (5 dialects). This is future code, mentioned only inside the SL-53 row.
2. **AT-6 signed-plugin automation:** coded, but has no row (it appears only in ACCEPTANCE-PLUG-AND-PLAY).
3. **Q22 / CF-11:** DB legs skip the unit tests (coded in `db-matrix.yml`). No row.
4. **C-19 [CONTRACT] notice** for SL-71/72/73: tracked only in HANDOFF §8.
5. **Docs-wave items:** stale HANDOFF §6c; the SL-116 and SL-117 READMEs.
6. **Stray-work merges:** wt-scale T-SCALE evidence; BulkQuota disposition.
7. **IX.8 follow-ups:** AC-01 to AC-06 on PostgreSQL, AC-07 on the other databases, the AC-08 cross-language run (needs Q12), and AC-10 usability (Q28). No register rows.
8. **UI-UPGRADE-1** (Q20 accepted after the SD-428 review) and **Dependabot npm branch 77f51234** (review after the push). No rows.
9. **ADR-D-28** (module scope): a decision, recorded in SANGAM.md, with no row. Acceptable.
10. "`Jdbc.one` diagnosable" and "IfmsPacksIT executor race": each is mentioned once in the register inside another row. There is no row of its own.

## Bottom line for the owner

- **Code still to write:**
  - SL-125 (in progress).
  - SL-102.
  - SG-39 (after your route decision).
  - The SL-78 and SL-79 test fixes.
  - SG-15 round-trip work.
  - SL-58 root cause (unknown; may need code).
  - Later, SL-53a (after Q34).
- **Owner or IFMS actions:** Q12, Q13, Q14, Q17, Q19, Q21, Q23, Q24, Q26, Q34; the SG-39 route; the SL-07 semantics; the SL-44 UI; storing the NVD parameter; SL-75 P2; the GA-55 form; the SL-31 power tables.
- **Everything else is coded with tests written, and none of it has been run.** About 140 rows are "done" only in the sense that they are coded and compile. None is proven until the test phase.
