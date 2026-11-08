<!-- SPDX-License-Identifier: Apache-2.0 -->
# Requirement traceability register (Stage C cross-verification, 2026-11-05)

**What this is:** one register of every requirement and item id defined in the Sangam specification and research files, matched against what the code says it covers. Each id has a status, the module, file or pack that covers it, and the tests that will prove it.

**What this is not:** test evidence. The phase rule holds: code first, tests last. Every "implemented" below means *a README coverage map says the code does it*. It does not mean the behaviour has been run.

**Files:**
- this file: summary, UNACCOUNTED list, open items, and the full table for the specification families;
- [`TRACEABILITY-REGISTER.csv`](TRACEABILITY-REGISTER.csv): the full table, one row per id (all 1858 ids).

## 1. Method

**Defined ids** were extracted by script (`python -E -X utf8`) from these files:
- `SANGAM.md`:
  - R1–R62 (X.1 table, with its Tests column);
  - A-1…A-16, ADR-D-1…D-24 (decision register), AC-01…AC-11, GOV-1…12;
  - CF-1…CF-16 and CF-9a, LIC-1…11, T01–T89, PK-01…14;
  - the R50–R62 tests come from the CF-10 resolution (X.5.1).
- `spike/GOV-PATTERNS.md` (GP);
- `spike/results/`:
  - `PREMIUM-AND-UNIVERSAL-SCOPE.md` (R63–R84, UP, PF);
  - `FLOWABLE-INTO-OPERATON.md` (FC, EC, D);
  - `FLOWABLE-WEAKNESS-AUDIT.md` (FW, FWT);
  - `IFMS-CENTRAL-REQUIREMENTS.md` (IFC), `IFMS-STATES-REQUIREMENTS.md` (IFS, GA);
  - `INTERNATIONAL-PFM-REQUIREMENTS.md` (PFM-nnn and the seven PFM-CH chains);
  - `COMPLIANCE-REQUIREMENTS.md` (CMP), `SECTOR-WORKFLOWS.md` (SEC), `US-HEALTH-WORKFLOWS.md` (US-HLT), `CAPABILITY-COVERAGE.md` (N);
- `sangam/SCALE-ARCHITECTURE.md` (T-SCALE) and `sangam/sangam-cell/README.md` (IR-1…IR-8, defined there).

**Coverage evidence** came from these sources:
- all 42 `sangam/**/README.md` files (module coverage maps, the packs catalogue in `sangam-packs/README.md` §5–7, and `profiles/README.md`);
- the `covers` / `acceptance` fields of the 130 `pack.json` files;
- the compliance obligation catalogue `IN/obligations.tsv`. The compliance README cites it as covering CMP-001…153, and it gives the bearer of each obligation.

**Matching rules:**
- Id ranges such as `FC-55..59`, `T06–T08` and `CMP-032-034`, and lists such as `PF-01…06, 09, 10`, are expanded.
- Ranges wider than 15 ids (8 in headings), such as `GP-01 ... GP-55` in a section title, are **not** counted as coverage.
- Tables whose first column is a bare number under a `PF`/`EC`/`FC`/`D` header are read as ids.
- "FHIR R4/R5" is not read as requirement R4/R5.
- A bare `D-nn` in a README is the Flowable-into-Operaton D list. A decision is `ADR-D-n`.

**Status rules:**
- An explicit status column wins: `St` C/D/P/G in `profiles/README.md`; `Status` in `sangam-flowable-ports` and the `sangam-ui` PF table; `Status here` in `sangam-engine`.
- Otherwise the status follows the README wording: a mention in a coverage table counts as coverage, and a mention in a "Gaps", "Known gaps", "Not done" or "Still open" section counts as an open item.
- An id that is covered and also listed as open is **partial**.
- **Third pass (2026-11-08):** regenerated over the current READMEs, the pack.json files and the code. New families: NG-01…NG-47 (remapped from `PREMIUM-GAP-RECHECK.md`) and AT-1…AT-7 (`ACCEPTANCE-PLUG-AND-PLAY.md`). The hand-set statuses and every open item in §4 were re-checked in the source; the "Code check" column gives the file and line. The contract `sangam-rest/api/sangam-api.yaml` (862 paths) was checked against the `@*Mapping` annotations for the routes the coordinator named.
- **Revision (2026-11-05):** every open item in §4 was re-checked against the code, not only the READMEs. The "Code check" column records the file and line. Four first-edition blockers (#2, #3, #4 and #6) and must-fix #11 turned out to be fixed in code. They are listed in §4.4; #4 continues as a smaller adoption item.
- **Rename (2026-11-05):** `FLOWABLE-INTO-OPERATON.md` lists A (common to both engines) and B (Operaton only, kept) appear here as **FA-01…FA-18** and **FB-01…FB-24**. This keeps them apart from the SANGAM.md amendments A-1…A-16. Their status is implemented (native Operaton 2.2.0 capability, kept by ADR-D-1/D-16), with README citations where they exist.
- Six statuses were set by hand after reading `sangam-server/README.md` and `sangam-rest/README.md`, because their open items sit in prose the scanner cannot attach to an id: IR-3, IR-4, IR-6, R41, R42 and R43.

**Spot checks (files read):**
- READMEs:
  - `sangam-engine`, `sangam-server` (integration notes, still open, gaps), `sangam-rest` (defaults table) and `sangam-cell` (IR-1…8);
  - `sangam-flowable-ports` (D table and "not replicated by decision") and the `sangam-ui` PF table;
  - `sangam-packs` §6–11, `profiles/README.md` (summary, GA table) and `sangam-compliance` (header and gaps);
  - `sangam-billing`, `sangam-rollup`, `sangam-control-plane`, `sangam-registry`, `sangam-gateway` and `perf`;
- data and source files: `obligations.tsv`, the `pack.json` schema, X.1, X.1a, X.5.1, X.6 and XI.4.

**Check against the profiles README's own summary:**
- IFC 258 = 160 C / 84 D / 10 P / 4 G; here: 160 / 84 / 10 / 4.
- GA 30 = 14 / 0 / 15 / 1; here: 14 / 0 / 15 / 1.

**Limits:**
- The status is read from documentation. Where a README is out of date (see §4.4), the register is too.
- "implemented" is a coverage claim, not a verified behaviour.

**Status values:**

| Status | Meaning |
|---|---|
| implemented | A README maps the id to code, a pack (BPMN/DMN) or a module file, with no open item recorded against it |
| data-only | Covered as configuration data on a generic capability (profile, seed, obligation catalogue row) and no code specific to the id |
| partial | Covered, but a README also records an open item, a placeholder, `[P]`, "UI ready; server [P]" or a Stage D/E remainder |
| gap | Only recorded as not done / Stage D or E / G |
| owner-side | Needs an owner, deployer or external party: a parked item, a business decision, or a DPDP obligation borne by the data fiduciary (bearer DF/SDF) |
| not-replicated-by-decision | Deliberately not built, with the reason recorded (for example in-engine scripts, runtime model patching, SaaS) |
| spec-closed | A consistency finding closed inside the specification (CF-*). The row names the id that realises it |
| test-pending | A test id. It is accounted for when the test or a requirement it proves appears in a README. Nothing has run |
| **UNACCOUNTED** | Defined in a source document, but neither the id nor (for tests) any requirement it proves appears in any README, pack.json or the obligation catalogue |

## 2. Summary counts

| Family | Total | impl | data | partial | gap | owner | not-repl | spec-closed | test-pending |
|---|---|---|---|---|---|---|---|---|---|
| Requirements R1–R84 (SANGAM X.1 + PREMIUM) | 84 | 65 |  | 17 | 2 |  |  |  |  |
| Amendments A-1…A-16 | 16 | 14 |  | 1 | 1 |  |  |  |  |
| Decisions ADR-D-1…D-24 | 24 | 15 |  | 3 |  | 6 |  |  |  |
| Acceptance criteria AC-01…AC-11 | 11 | 11 |  |  |  |  |  |  |  |
| Government items GOV-1…GOV-12 | 12 | 11 |  | 1 |  |  |  |  |  |
| Consistency findings CF-* | 17 | 1 |  |  |  |  |  | 16 |  |
| Licence items LIC-1…LIC-11 | 11 | 11 |  |  |  |  |  |  |  |
| Tests T01–T89 | 89 |  |  |  |  |  |  |  | 89 |
| Parked items PK-01…PK-14 | 14 | 1 |  |  |  | 13 |  |  |  |
| Government patterns GP-01…GP-55 | 55 | 50 |  | 5 |  |  |  |  |  |
| Universal patterns UP-01…UP-22 | 22 | 20 |  | 2 |  |  |  |  |  |
| Premium features PF-01…PF-52 | 52 | 36 |  | 12 | 3 |  | 1 |  |  |
| Flowable OSS gap list FC-01…FC-59 | 59 | 43 |  | 6 | 3 |  | 7 |  |  |
| Flowable Enterprise EC-01…EC-21 | 21 | 16 |  | 1 | 3 |  | 1 |  |  |
| Operaton weaknesses D-01…D-56 | 56 | 45 |  | 4 | 5 | 2 |  |  |  |
| Flowable-into-Operaton list A (common), FA-01…FA-18 | 18 | 18 |  |  |  |  |  |  |  |
| Flowable-into-Operaton list B (Operaton only, kept), FB-01…FB-24 | 24 | 24 |  |  |  |  |  |  |  |
| Premium re-check NG-01…NG-47 | 47 | 12 |  | 33 |  | 1 | 1 |  |  |
| Onboarding acceptance AT-1…AT-7 | 7 | 7 |  |  |  |  |  |  |  |
| Flowable weakness audit FW-01…FW-47 | 47 | 47 |  |  |  |  |  |  |  |
| Weakness scenario tests FWT-001…FWT-057 | 57 | 49 |  | 8 |  |  |  |  |  |
| IFMS central IFC-001…IFC-258 | 258 | 160 | 84 | 10 | 4 |  |  |  |  |
| IFMS states IFS-* (cross-state + state) | 329 | 135 | 193 |  | 1 |  |  |  |  |
| IFMS gap areas GA-01…GA-30 | 30 | 14 |  | 15 | 1 |  |  |  |  |
| International PFM PFM-* | 79 | 39 | 9 | 27 | 4 |  |  |  |  |
| Compliance CMP-001…CMP-153 | 153 | 118 | 22 |  |  | 13 |  |  |  |
| Sector workflows SEC-* | 125 | 125 |  |  |  |  |  |  |  |
| US health US-HLT-* | 104 | 104 |  |  |  |  |  |  |  |
| Capabilities N01–N18 | 18 | 10 |  | 7 | 1 |  |  |  |  |
| Scale tests T-SCALE-1…11 | 11 |  |  |  |  |  |  |  | 11 |
| Integration requests IR-1…IR-8 | 8 | 6 |  | 2 |  |  |  |  |  |
| **All** | **1858** | **1207** | **308** | **154** | **28** | **35** | **10** | **16** | **100** |

**Accounted:** 1858 of 1858 ids (100.0 %). **UNACCOUNTED:** 0.

## 3. UNACCOUNTED (defined, but in no README)

**Third pass (2026-11-08): no id is UNACCOUNTED.** Since the 2026-11-05 edition:
- **PFM-*** (79) is covered: `PFM` coverage maps in the READMEs. 39 implemented, 9 data-only, 27 partial, 4 gap.
- **US-HLT-*** (104) is fully covered, together with the new `US-HIPAA` and `EU-GDPR` compliance jurisdiction packs.
- **R2, R5, R7, R10, R46, R52 and N11** each have a README entry.
- **NG-01…NG-47**, the premium re-check in `PREMIUM-GAP-RECHECK.md`, were re-verified on 2026-11-08 (SL-36) against code, module READMEs and test sources; both documents now carry the same statement per id (§6a). "partial" is used whenever the module README states a gap or missing wiring (often the screen), which is why the implemented count fell from 34 to 12.
  - Nine NG ids are cited in no README and were classified from the code instead: NG-42 to NG-45 present in code; NG-33, 37 and 39 partial; NG-46 not replicated (SaaS); NG-47 owner-side (services).
- **FWT-001…FWT-057** are no longer test-pending: every id has a named test method, and each test class has a local surefire report dated 2026-11-08 (§6a). Eight are partial: FWT-009/010/011/031 (last `FwtWorkerCompatIT` run failed), FWT-012 (REST part failed), FWT-014 (Oracle/MSSQL/MySQL not run), FWT-017 (p95 not asserted), FWT-052 (no real broker, no JMS).
- **D-12** text in `FLOWABLE-INTO-OPERATON.md` says a default `decisionRefBinding` is applied; the code rejects a missing decision binding instead (§6a).
- **AT-1…AT-7**, the onboarding acceptance tests in `sangam/ACCEPTANCE-PLUG-AND-PLAY.md`, are cited in no README, but exist in code as `sangam-onboarding/.../acceptance/PlugAndPlayAcceptanceTest.java`. They are listed here as implemented; the tests have not been run in this pass.

Remaining README hygiene, which is not an UNACCOUNTED finding: add AT-1…AT-7 and NG-33…NG-47 to the onboarding and deploy READMEs.

### 3.5 Orphan references (used in a README, defined nowhere)

| Reference | Where | Finding |
|---|---|---|
| `A-17` (resolved) | `sangam-security/README.md` L58, L69, L152 | **Renamed in this register to FA-17** (LDAP identity plugin, Spring Security, CDI; status implemented, `LdapDirectorySync`). **Id collision.** `FLOWABLE-INTO-OPERATON.md` has its own lists A-1…A-18 ("common to both engines") and B-1…B-24 ("Operaton only: what we keep"). A-17 there is the LDAP identity plugin. SANGAM.md amendments are also A-1…A-16, so a bare `A-n` in a README is ambiguous. `B-n` references (for example B-1, B-4, B-11, B-16, B-24) are FIO list B. These two lists are keep-lists, not requirements to build, and are outside the requested families. **Resolved here:** list A is renamed FA-01…FA-18 and list B FB-01…FB-24 (§2 and the CSV). In READMEs, `A-1`…`A-16` are read as SANGAM amendments, and `A-17`/`A-18` and every `B-n` as FA/FB. The two exceptions are sangam-engine L85/L98, where B-1/B-4/B-5 are extension-point labels |
| `IFS-034`, `IFS-037` | `profiles/README.md` (status D) | Not defined in `IFMS-STATES-REQUIREMENTS.md` (the cross-state list skips 034 and 037) or in `IFMS-RESEARCH-DELTA.md`. The profiles summary counts 78 cross-state ids; the source defines 76 |
| `D-0` | `sangam-rest/README.md` (audit) | This is X.2.3 **Rule D-0** (no replay or time-travel claims from class A/C data), not an ADR. No action needed |

### 3.6 Items accounted for only by their nature (no code home)

These are recorded so that nothing is silently dropped.

**CF-1…CF-16, CF-9a** are specification resolutions (status spec-closed). Each row names the id that realises it. Only CF-11 (PR-gate policy) is cited in a README (`deploy`).

**PK-01…PK-14** are parked items (ADR-D-13), status owner-side. The CSV lists the absorbing requirement for each. Only PK-11 (the bpmn.io watermark) is cited in a README (`sangam-ui`).

**ADR decisions without a README citation:**
- realised in the build: D-1 (Operaton 2.2.0 dependency, cited as ADR-D-16), D-6 (= D-8), D-17 (repository LICENSE + SPDX headers), D-19 (parallel workstreams);
- owner-side: D-2, D-3, D-9, D-10, D-11, D-18;
- D-23 is now implemented: the IN, US-HIPAA and EU-GDPR compliance packs exist, and PFM is covered (it is cited in sangam-compliance and sangam-onboarding).

## 4. Consolidated open items (de-duplicated, ranked)

**Source:** every README section headed Gaps, Known gaps, Not done, Not run yet, Still open or Notes and known gaps; the bold "Still open" and "Gaps" blocks in `sangam-server`; the `sangam-rest` defaults table; the engine and persistence requested core changes; and this register's UNACCOUNTED findings. The raw extraction found 126 lines. After merging duplicates and removing items already fixed (§4.4), **27 items** remain.

**Ranks:**
- **Blocker:** it stops the testing phase, or makes a Tier-1 run fail or give a false result.
- **Must-fix before go-live:** it must close before Stage D sign-off or any production use.
- **Later stage:** Stage D/E by the plan, or an accepted limitation.

### 4.1 Blocker (1)

| # | Item | Detail | Source | Ids | Code check (2026-11-05) |
|---|---|---|---|---|---|
| 1 | **No behaviour test has run on the Tier-1 databases** | The full clean build passed (2026-11-08), but the AC-01…AC-11 runs on PostgreSQL, Oracle and SQL Server, the Stage B re-runs (XII.5) and the AT-1…AT-7 acceptance tests are still to be run. This is the Stage C exit | all READMEs | T01–T89, AC-01…AC-11, AT-1…AT-7 | Build green; test execution pending |

### 4.2 Must-fix before go-live (18)

| # | Item | Detail | Source | Ids | Code check (2026-11-05) |
|---|---|---|---|---|---|
| 2 | **Idempotency response body not encrypted (X.2.2.3)** | Cleanup is now scheduled; only the encryption remains | sangam-rest L104 | R41, T55–T60 | `PackGapWiring.java` L80 schedules cleanup (fixed). No encryption in `JdbcIdempotencyAdapter` or core `IdempotencyStore` |
| 3 | **No signed / Merkle audit checkpoints or external export (X.2.3.4 rule 4)** | CERT-In-style evidence and R42 tamper evidence | sangam-security; sangam-integration; sangam-rest | R42, R51, R52, GOV-3 | `AuditChain.java` L26 still "not implemented" |
| 4 | **Compliance denial audits roll back with the caller** | `PurposeBindingGuard.deny` writes on the caller's connection | sangam-compliance | R42, CMP-012 | `PurposeBindingGuard.java` L54-72 call `deny(c, …)` |
| 5 | **Compliance adult-identity verifier not configured (DPDP r.10)** | There is no DigiLocker identity-verification connector | sangam-server | CMP-029, CMP-068…070 | `ComplianceWiring.java` L130 `null /* adultVerifier */` |
| 6 | **Compliance BPMN: no diagram layout and no seat routing** | 12 compliance BPMN files, all without `bpmndi` and without seat-based assignment (IX.5) | sangam-compliance | IX.5, R82 | Counted in `sangam-compliance/src/main/resources` |
| 7 | **Languages: only en/hi; accessibility not verified** | IN ships `notice_en` and `notice_hi`; EU-GDPR and US-HIPAA ship English only. UI locales are `en.json` and `hi.json`. No WCAG/GIGW audit has been done | sangam-compliance; sangam-ui | GOV-1, GOV-2, R54, R55, GA-22 | Notice files and locales counted |
| 8 | **Packs still run on `WorkingDayCalendar`** | Tenant holidays and notified calendars do not reach pack SLAs. `sangam-config` has a `BusinessCalendar` class, but it is not bound to the packs port | sangam-server | R31, GP-06, AC-06 | `PacksWiring.java` L135 logs "calendar=packs WorkingDayCalendar" |
| 9 | **`PackagePort`: no package signatures or environment promotion (X.2.4.5)** |  | sangam-rest L113 | R43 | `JdbcPackageAdapter.java` L41 |
| 10 | **Worker long-poll blocks a servlet thread** | This exhausts threads at ADR-D-24 load | sangam-rest | R9, R22, T-SCALE-2 | `Thread.sleep` loop in `EngineAdapterWorkAdapter.java` L82 and `OperatonWorkAdapter.java` L87 |
| 11 | **No malware scanner bound** | Content stays `NOT_SCANNED` | sangam-server | R74 | No `ContentScanner` in sangam-server |
| 12 | **SCALE: read replica unused; broker consumers ignore tenant freeze** | IR-6: the router has no caller. IR-4: Kafka/JMS are now wired (`BrokerInboxWiring`), but they do not check the FROZEN state, and files are not moved | sangam-server | IR-4, IR-6, T-SCALE-8 | No call to `CellReadReplicaRouter.routing()`; no freeze check in the broker consumers |
| 13 | **SDKs have no in-memory test engine (R18 Tier 1)** | Contract tests T01–T04 / T18 still need to be run | sdks/* | R18, AC-08 | No test-engine class in `sdks/` |
| 14 | **PWA offline queue not implemented** | Offline field capture (N05, GA-27) | sangam-field; sangam-ui | N05, GA-27 | No `LocalQueueContract` or IndexedDB in `sangam-ui/src` |
| 15 | **Billing numbers redrawn on a retried step** | Gaps in the receipt series | sangam-billing | N09, N10 | `BillingService.java` L58, L96 `series.next` without an operation key |
| 16 | **Break-glass accounts (AJ-05 step 7)** |  | sangam-security | AJ-05, R50 | No code |
| 17 | **CDS Hooks client JWT (`iss`/`jku`) not verified** | The route requires a Sangam token only | sangam-connectors-health | US-HLT-074 | No `jku` / client-JWT verification code |
| 18 | **Synthetic DMN/seed values; real government wire formats on request (PK-05)** | Owner and department input. The official LMMH list has now been loaded (fixed) | sangam-packs; sangam-integration | GP-01, R58, PK-05 | Data item |
| 19 | **Contract coverage of the separate apps** | `/cells`, `/routes` (sangam-control-plane `ControlPlaneController`) and `/rollup` (sangam-rollup `RollupController`) exist in code but are not in `sangam-api.yaml`, and no other OpenAPI file covers them. `/field/assignments/*` is mapped (`FieldWorkController` L86-145). `/plugins/ui/{id}/assets/{path}` maps to `/{id}/assets/**`; the contract notes that `path` may contain slashes, which OpenAPI path parameters do not allow (generated clients will encode them) | sangam-rest/api/sangam-api.yaml (862 paths) | R16, D-5 | Mapping search of every `@*Mapping` |

### 4.3 Later stage (8)

| # | Item | Detail | Source | Ids | Code check (2026-11-05) |
|---|---|---|---|---|---|
| 20 | **operationId not on `ExternalWork` built by the engine adapter** | `ConnectorInvoker` now records with the explicit id (L153, fixed). `OperatonEngineAdapter` L572 still builds `ExternalWork` without it; `LeasedWork` carries it. Confirm in T35 | sangam-engine | R36, T35 | Partly fixed |
| 21 | **Engine source patches (Stage D)** | D-19, D-10, FC-24, D-27, D-52, D-23, D-48. Interim routes are in place | sangam-engine | D-10, D-19, FC-24 | README plus PATCHES.md |
| 22 | **R39 partition epochs (A-6)** | Off by default | sangam-engine | R39, A-6 | No epoch code |
| 23 | **Flowable-port remainders and premium partials** | FC-56/57, FC-11/19, FC-18 historic migration, FC-51, EC-03/06/14/15; PF "UI ready; server [P]"; NG partials (NG-33 mobile app, NG-37 backup beyond PostgreSQL, NG-39 active-active) | sangam-flowable-ports; sangam-ui; PREMIUM-GAP-RECHECK | FC-*, EC-*, PF-*, NG-* | README / recheck |
| 24 | **Identity breadth (SCIM, R67 Stage D); R82 strategies in sangam-security** |  | sangam-security | R67, R82 | None in code |
| 25 | **Connector breadth and FHIR profile validation depth** | X12 TR3 and 277CA. The Teams, WhatsApp, WeChat, ServiceNow, SAP, Azure Service Bus, MCP and A2A connectors were checked against the vendor APIs in `CONNECTOR-API-VERIFICATION.md`; its "Integrator actions" remain | sangam-integration; connectors-health | R71 | Doc review |
| 26 | **Performance refinements and accepted functional limits** | Native spatial indexes, field-mask cost, stripe rebalancing, roll-up tiers, straight-line ETA, … | module READMEs | R22 | README-based |
| 27 | **Unfenced Flowable-compat opt-in reads core `LeaseTable`** | It fails closed | sangam-server | FW-07 | `FlowableWorkerCompatController.java` L153 |

### 4.4 Fixed in code or superseded (do not count as open)

The integrator reported, on 2026-11-05, that several README notes were already fixed in code. Each was checked against the source; the result is below.

| Older statement | Superseded by |
|---|---|
| 2026-11-05 blocker: Oracle `ROWNUM` before `SKIP LOCKED` | **Fixed.** `Dialect.java` L138-144 uses an ordered `ROWNUM` candidate window, then `FOR UPDATE SKIP LOCKED`. `ResourcesDialect` no longer has `ROWNUM` |
| Kafka/JMS consumers not wired in the server | **Fixed.** `BrokerInboxWiring.java` L98, L115, L141 build `PoisonInbound`, `KafkaInboxConsumer` and `JmsInboxListener` |
| `ConnectorInvoker` uses the derived operationId | **Fixed.** `ConnectorInvoker.java` L153 calls `recordIntent(c, op, …)` |
| Idempotency `cleanup()` never scheduled | **Fixed.** `PackGapWiring.java` L80 (leader-elected) |
| UNACCOUNTED PFM, US-HLT, R2/R5/R7/R10/R46/R52, N11 | **Fixed.** All are now covered in the READMEs (§3) |
| No EU GDPR pack (ADR-D-23) | **Fixed.** The `EU-GDPR` and `US-HIPAA` jurisdiction packs exist |
| 835 remittance posting missing | **Present.** `PackGapConnectors` / `PackGapWiring` in sangam-server |
| Earlier fixes (2026-11-05): ports and `PACK_OPERATION` Liquibase, `cond_key`, poison inbound, `COMPLIANCE` resource type, History/Insights/Ops bridges | Still fixed. The product schema now has 286 `CREATE TABLE` statements in the PostgreSQL changelogs |

## 5. Full table: specification families (SANGAM.md)

Every R, A, ADR-D, AC, GOV, CF, LIC, PK, N, IR and T-SCALE id is listed here. The bulk families (GP, UP, PF, FC, EC, D, FW, FWT, IFC, IFS, GA, PFM, CMP, SEC, US-HLT, T) are in §6 by exception and in full in the CSV.

### Requirements R1–R84 (SANGAM X.1 + PREMIUM)

| Id | Title | Stage | Status | Where (module:file / pack) | Tests | Note |
|---|---|---|---|---|---|---|
| R1 | Relay-driven wake-up | C | implemented | sangam-integration:OutboxRelayService.java; sangam-integration:WakeupNotifier.java | T06, T07, T08, T25, T31, T45 |  |
| R2 | Per-DB acquisition SQL | C | implemented | sangam-engine:SangamLeaseTable.java; sangam-engine:CommandState.java; sangam-engine | T10, T24, T31 |  |
| R3 | Lock and transaction specification | C | implemented | sangam-engine:OperatonEngineAdapter.java; sangam-integration:RetryPolicy.java | T10, T33, T34 |  |
| R4 | Outbox algorithm | C | implemented | sangam-cell:RollupPublisher.java; sangam-flowable-ports:OutboundEventService.java; sangam-integration:OutboxRelayService.java; sangam-persistence:sangam-0001-ba | T06, T07, T08, T25, T35 |  |
| R5 | JDK baseline | B (minimal) | implemented | deploy | T28, T31 |  |
| R6 | Script isolation | C (secure defaults) → D (full) | implemented | sangam-engine:SangamBpmnParseListener.java; sangam-flowable-ports | T23, add test |  |
| R7 | Native-light profile | B (Phase 0 item) | implemented | deploy | add test |  |
| R8 | DMN engine scope | C | implemented | pack universal.up14-three-way-match | T18, T19, T20 |  |
| R9 | Worker task lease lifecycle | B (proof) → C | implemented | sangam-engine:OperatonEngineAdapter.java; sangam-engine:SangamLeaseTable.java; sangam-persistence:sangam-0001-baseline.sql; sdks/dotnet:SangamWorker.cs; sdks/ja | T02, T03, T04, T36 |  |
| R10 | FEEL engine and null semantics | C | implemented | sangam-engine:FeelEngineIdentity.java; sangam-engine:StrictNullResultGuard.java; sangam-engine; sangam-persistence | T18, T19, add test |  |
| R11 | JSON limits | C → D | implemented | sangam-cell:MiniJson.java; sangam-engine:JsonValueSerializer.java | T18, T23 |  |
| R12 | Schema migration | B (skeleton) → C | implemented | deploy; sangam-control-plane:SchemaConfig.java; sangam-engine:SangamEngineConfigurer.java; sangam-flowable-ports:PortsSchema.java; sangam-persistence; sangam-pe | T28, T29 |  |
| R13 | Telemetry | B (skeleton) → C | implemented | deploy; sangam-engine:JobMetricsInterceptor.java; sangam-engine:CountingFailedJobCommandFactory.java; sangam-rest; sangam-server:ObservabilityConfig.java; sdks/ | T24, T31, T49 |  |
| R14 | AI / MCP tools | E | implemented | pack universal.up06-hitl-ai-review | T12, T13, T14, T15, T16, T17, T23, add test |  |
| R15 | Process-mining export | E | implemented | sangam-rest | add test |  |
| R16 | Contract-driven schemas | C | implemented | sdks/dotnet:Models.cs; sdks/php:CloudEvent.php; sdks/php:AdminApi.php; sdks/python:models.py | T48 |  |
| R17 | Database tiers | B (skeleton) → C | implemented | deploy; sangam-server:application-*.yaml | T10, T26, T28, T29, T31 |  |
| R18 | SDK tiers | C | partial | sdks/dotnet; sdks/java; sdks/php; sdks/php:SangamClient.php; sdks/php:SangamWorker.php; sdks/python; sdks/python:client.py; sdks/typescript; +1 more | T01, T02, T03, T04, T18, T28 | covered, with an open item \| sdks/dotnet L216: - No in-memory test engine yet (R18 Tier-1 "test engine"); contract tests T01–T04/T18 come in the testing phase. |
| R19 | Authorization | B (spike on both engines) → C | implemented | sangam-case:CaseFileService.java; sangam-case:CaseRestAdapter.java; sangam-channels; sangam-flowable-ports:TenantDefinitionResolver.java; sangam-gateway:TenantR | T13, T14, T15, T16, T17, T37, T38 | explicit |
| R20 | Fencing and heartbeats | B → C | implemented | sangam-engine:OperatonEngineAdapter.java; sangam-engine:SangamLeaseTable.java; sangam-persistence:sangam-0001-baseline.sql; sdks/dotnet:SangamWorker.cs; sdks/ja | T02, T03, T04, T05, T27 |  |
| R21 | Expired-lease sweeper | B → C (also D, with R48/R49) | implemented | sangam-engine:SangamLeaseTable.java; sdks/java:SangamWorker.java | T03, T04, add test |  |
| R22 | Benchmarks | D | implemented | perf; pack universal.up01-stp-order | T24, T25, T31 |  |
| R23 | Cancel / delete / erase | C | implemented | sangam-engine:OperatonEngineAdapter.java; sangam-integration:InstanceLifecycleService.java; sangam-persistence:sangam-0001-baseline.sql; sdks/java; sdks/php; sd | T10, T11, T27, T39 |  |
| R24 | XML hardening | C → D | implemented | sangam-engine:OperatonEngineAdapter.java; sangam-flowable-ports:SecureXml.java | T23, add test |  |
| R25 | No expressions in queries | C → D | implemented | sangam-rest | T13, T14, T23, add test |  |
| R26 | Worker-bound operations | B → C | implemented | sangam-persistence; sangam-rest; sangam-security; sangam-security:WorkerBindingService.java | T03, T13, T46 |  |
| R27 | Connector egress | C → D (breadth in E) | implemented | sangam-connectors-health; sangam-geo:WfsConnector.java; sangam-integration:SangamConnector.java; sangam-integration:EgressPolicy.java; sangam-integration:HttpTr | T23, T47, add test |  |
| R28 | Safe defaults | C | partial | sangam-case:CaseFileException.java; sangam-integration:ConnectorInvoker.java; sangam-integration:HttpTransport.java; sangam-integration:RetryPolicy.java; sangam | T01, T13, T23, T24, T25 | covered, with an open item \| sangam-ui L56: \| State contract (X.4.7.2) \| Covers: loading after 10 s ("Still loading"); empty states that say why; 403 and 404 sh |
| R29 | Retention and variable limits | C | implemented | sangam-cell; sangam-engine:SangamBpmnParseListener.java; sangam-integration:LeaderElection.java; sangam-integration:LegalHoldStore.java; sangam-integration:Inte | T25, T30 |  |
| R30 | Bounded caches | D | implemented | deploy | T19, T24, T31, T49 |  |
| R31 | Timer policy | D (see finding 12) | implemented | sangam-engine:CycleExpression.java; sangam-engine:TimerGuard.java; sangam-engine:TimerPolicies.java; sangam-engine:SangamBpmnParseListener.java; sangam-engine;  | T22 |  |
| R32 | Upgrade matrix | D | implemented | deploy | T19, T28, T29 |  |
| R33 | FEEL type contract | C | implemented | sangam-config:ProfileLoader.java; sangam-engine:JsonValueSerializer.java; sangam-engine:JdbcLedgerStore.java; sangam-integration:ConnectorRequest.java; sangam-i | T18, T19 |  |
| R34 | BPMN gaps of the base | C (pilot subset) | implemented | sangam-flowable-ports:SangamBpmnParse.java | T20, T21, add test |  |
| R35 | Exclusivity | C | implemented | sangam-engine:OperatonEngineAdapter.java; sangam-engine:ExclusiveLeaseTable.java; sangam-persistence:sangam-0001-baseline.sql; sangam-resources | T05, T40 |  |
| R36 | Business-operation identity (E01) | B (proof) → C | implemented | sangam-cell:CrossCellPublisher.java; sangam-connectors-health; sangam-engine:OperatonEngineAdapter.java; sangam-flowable-ports:OutboundEventService.java; sangam | T02, T03, T27, T36, T35 |  |
| R37 | Durable call intent / journal (E02) | E | implemented | sangam-integration; sangam-integration:ConnectorInvoker.java; sangam-integration:ConnectorCallJournal.java; sangam-integration:OperationLedgerService.java; sang | T12, add test |  |
| R38 | Inbound inbox (E03) | C | implemented | sangam-cell:CrossCellPublisher.java; sangam-field:SyncService.java; sangam-flowable-ports:EventDefinition.java; sangam-flowable-ports:InboundEventProcessor.java | T09, T35, add test |  |
| R39 | Partition epochs (E09) | E (blocked by A-6) | gap | sangam-engine | T41, T24 | sangam-engine L239: - Partition epochs (R39): blocked by A-6. |
| R40 | Outbox capacity (E10) | C | implemented | sangam-control-plane:PlacementService.java; sangam-integration:OutboxCapacityMonitor.java; sangam-integration:InboxService.java; pack universal.up01-stp-order;  | T25, add test, T09, T35 |  |
| R41 | API idempotency (E12) | B (proof) → C | partial | sangam-cell:CrossCellPublisher.java; sangam-field:SyncService.java; sangam-field:FieldRestPort.java; sangam-integration:IdempotencyService.java; sangam-ledger:L | T01, add test | code: idempotency cleanup now scheduled (PackGapWiring L80); stored response body still not encrypted (X.2.2.3) |
| R42 | Audit vs replay (E13) | C (audit part); replay E | partial | sangam-control-plane:ControlPlaneAudit.java; sangam-engine:SangamHistoryEventHandler.java; sangam-field:FieldAudit.java; sangam-flowable-ports:TaskLifecycleServ | T42, T19, T30, add test | code: AuditChain.java L26 signed Merkle checkpoints not implemented |
| R43 | Executable-package versioning (E14) | C | partial | sangam-case:CaseConfigRegistry.java; sangam-flowable-ports:EventRegistry.java; sangam-flowable-ports:DynamicInjectionService.java; sangam-flowable-ports:AppBund | T19, T21, T28 | code: JdbcPackageAdapter.java L41 no package signatures / environment promotion \| covered, with an open item \| sangam-flowable-ports L290: \| Runtime model patch |
| R44 | Standards profile (E15) | Not mapped in VIII.9 (finding 9) | implemented | sangam-engine:SangamBpmnParseListener.java; sangam-flowable-ports:SangamModelValidator.java; sangam-rest | T20 |  |
| R45 | Bounded CMMN conversion (E16) | E (isolation in B/C) | implemented | pack universal.up09-dynamic-case; pack universal.up18-capa | T43, T21 |  |
| R46 | Modeler/runtime parity (E17) | E | implemented | sangam-ui | T20, T32, add test |  |
| R47 | Forms contract (E19) | C | implemented | sangam-flowable-ports:FormDefinition.java; sangam-packs:PackDeployer.java; sangam-persistence; sangam-persistence:sangam-0001-baseline.sql; sangam-rest:sangam-a | T16, T19, T32, add test |  |
| R48 | Migration contracts (E20) | D (pilot subset may move to C); importers E | implemented | sangam-flowable-ports:MigrationDocument.java; sangam-rest:sangam-api.yaml; sangam-rest; sangam-ui | T21, T27, T28, add test |  |
| R49 | Continue-as-new and modification limits (E21) | D | implemented | sangam-flowable-ports:DynamicInjectionService.java; sangam-flowable-ports:CrossInstanceStateChangeService.java; sangam-rest:sangam-api.yaml; sangam-rest; pack u | T44 |  |
| R50 | Identity / SSO (GOV-6) | C | implemented | deploy; sangam-registry:RegistryPorts.java; sangam-rest; sangam-security; sangam-server:SecurityConfig.java; sangam-ui:AuthProvider.tsx | T87 |  |
| R51 | Log retention and incident readiness (GOV-3) | C (Stage D gate item) | implemented | deploy | T81, T49 |  |
| R52 | Audit-ready release process (GOV-3) | D | partial | deploy; deploy:release.yml | T81, T49 | covered, with an open item \| deploy L570: \| release.yml \| tag vX.Y.Z \| also builds the R52 evidence pack (evidence/: SBOMs, licence, dependency-check, CodeQL su |
| R53 | DPDP compliance (GOV-4) | D | implemented | sangam-registry:RegistryPorts.java; sangam-rest; pack gov.p3.grant-sanction; pack universal.up17-prior-authorisation | T86 |  |
| R54 | Languages (GOV-1) | C | partial | sangam-packs; sangam-packs:DocumentDelegate.java; sangam-persistence:sangam-0001-baseline.sql; sangam-ui:{en,hi}.json; pack sangam.core | T32, T88 | covered, with an open item \| sangam-ui L52: \| i18n Hindi + English (R54, T32) \| i18next. All strings live in src/i18n/locales/{en,hi}.json. There is a language  |
| R55 | Accessibility / GIGW (GOV-2) | D | implemented | sangam-ui:ui.tsx | T32, T88 |  |
| R56 | Hosting and localisation (GOV-5) | C | implemented | deploy; sangam-flowable-ports; sangam-packs:PackRuntime.java; sangam-ui | T50 |  |
| R57 | Signing service (GOV-7) | C | implemented | deploy; sangam-integration:EsignConnector.java; sangam-packs:SignatureDelegate.java; pack sangam.core; pack gov.p2.file-movement; pack gov.p4.licence; pack univ | T80 |  |
| R58 | PFMS / IFMIS and payment connectors (GOV-8) | C | implemented | sangam-integration:FileTransport.java; sangam-integration:SftpTransport.java; sangam-integration:IfmsJitConnector.java; sangam-integration:PfmsFileConnector.jav | T02, T27, T47, T89 |  |
| R59 | DigiLocker / API Setu (GOV-9) | D | implemented | sangam-integration:DigiLockerPushConnector.java; pack gov.p3.grant-sanction; pack gov.p4.licence | T84 |  |
| R60 | Notifications / DLT (GOV-10) | C | implemented | sangam-integration:SmtpEmailConnector.java; sangam-integration:DltSmsConnector.java; sangam-packs; sangam-packs:NotifyDelegate.java; sangam-rest | T82 |  |
| R61 | Records retention and RTI (GOV-11) | C | implemented | sangam-cell; sangam-engine:SangamBpmnParseListener.java; sangam-engine; sangam-integration:LegalHoldStore.java; sangam-packs; sangam-packs:CaseRecordDelegate.ja | T85, T30 | explicit |
| R62 | Procurement readiness (GOV-12) | D | implemented | pack gov.gem-purchase | release evidence (local-content dossier) |  |
| R63 | Operations console (Cockpit/Operate/Control/Hub parity) | C (O01–O09 + history + migrat… | implemented | sangam-rest:sangam-api.yaml; sangam-rest; sangam-security:BulkOperationGuard.java; sangam-server:ObservabilityConfig.java; sangam-ui:ReasonDialog; sangam-ui; sd |  |  |
| R64 | Bulk / batch operations | C | implemented | sangam-rest:sangam-api.yaml; sangam-rest; sangam-security; sangam-security:BulkOperationGuard.java; sangam-ui; pack universal.up15-recertification | PackDeploymentTest+AllPacksSmokeTest (deploy/first wait) |  |
| R65 | Process analytics and BI | D (core) → E (advanced) | implemented | sangam-flowable-ports:TaskLifecycleService.java; sangam-flowable-ports; sangam-ops; sangam-rest:sangam-api.yaml; sangam-rest; sangam-security:AuthorizedQueryBui | PackDeploymentTest+AllPacksSmokeTest (deploy/first wait) |  |
| R66 | In-product process mining | E | implemented | sangam-rest:sangam-api.yaml; sangam-rest; sangam-ui; pack universal.up07-improvement-loop | PackDeploymentTest+AllPacksSmokeTest (deploy/first wait) |  |
| R67 | Identity federation | C (OIDC, LDAP) → D (SCIM) | partial | sangam-rest; sangam-security; sangam-security:KeycloakClaimsMapper.java; sangam-security:PrincipalResolver.java; sangam-security:LdapDirectorySync.java; sangam- | PackDeploymentTest+AllPacksSmokeTest (deploy/first wait) | covered, with an open item \| sangam-security L69: \| identity/LdapDirectorySync.java \| R67 / A-17: LDAP/AD read and scheduled sync (JDK JNDI, no new dependency). |
| R68 | Published support lifecycle | D | implemented | sangam-ui |  |  |
| R69 | Collaborative web modeller | C (BPMN/DMN/forms, single-use… | partial | sangam-rest; sangam-ui |  | covered, with an open item \| sangam-rest L95: Without the owning module, these return 501 CAPABILITY_NOT_INSTALLED: directory sync (sangam-security), Git pull/p |
| R70 | Platform console and lifecycle | D | gap | sangam-ui |  | sangam-ui L271: \| PF-25, PF-26 \| Console, backups \| Not in WS-UI scope (R70, Stage D) \| Gap \| |
| R71 | Connector catalogue and service registry | C (REST, e-mail, SFTP, webhoo… | partial | sangam-connectors-health; sangam-flowable-ports:ChannelAdapters.java; sangam-flowable-ports; sangam-geo:WfsConnector.java; sangam-integration:SangamConnector.ja | PackDeploymentTest+AllPacksSmokeTest (deploy/first wait) | covered, with an open item \| sangam-integration L360: - Not implemented: FC-08 Camel (stage E); GraphQL, JDBC, S3 and chat connectors from R71 (stage E breadth) |
| R72 | AI-agent orchestration | E | partial | sangam-ai; sangam-flowable-ports:SangamBpmnParse.java; sangam-flowable-ports; sangam-rest; sangam-ui; pack universal.up06-hitl-ai-review | PackDeploymentTest+AllPacksSmokeTest (deploy/first wait) | covered, with an open item \| sangam-flowable-ports L184: \| EC-14 \| Agents / AI \| Stage E (R72); prerequisite FC-01 Done; owner proposed WS-PACKS/WS-INTEGRATION  |
| R73 | RPA worker | E | implemented | sangam-integration:RetryPolicy.java; sangam-packs:EmbeddedWorkerRunner.java; pack universal.up20-rpa | PackDeploymentTest+AllPacksSmokeTest (deploy/first wait) |  |
| R74 | Document service | C (store + references) → D (g… | partial | sangam-channels; sangam-flowable-ports:FileSystemDocumentStore.java; sangam-integration:LifecyclePorts.java; sangam-integration; sangam-rest:sangam-api.yaml; sa | PackDeploymentTest+AllPacksSmokeTest (deploy/first wait) | covered, with an open item \| sangam-channels L17: \| NG-27 \| mail/InboundMail \| Done, untested live \| Message-ID idempotency, attachments to R74 with a scan hook |
| R75 | Intelligent document processing | E | partial | sangam-ui; pack universal.up06-hitl-ai-review | PackDeploymentTest+AllPacksSmokeTest (deploy/first wait) | covered, with an open item \| sangam-ui L276: \| PF-31 \| IDP (document processing) \| — \| Gap (R75, Stage E) \| |
| R76 | Universal work UI | C (inbox and task) → E (case … | implemented | sangam-rest; sangam-ui; pack universal.up09-dynamic-case; pack universal.up22-external-collaboration | PackDeploymentTest+AllPacksSmokeTest (deploy/first wait) |  |
| R77 | Low-code pages | E | partial | sangam-flowable-ports; sangam-rest; sangam-ui |  | covered, with an open item \| sangam-flowable-ports L173: \| EC-03 \| Pages (low code) \| Owned: WS-UI (R77), stage E \| |
| R78 | Data objects and data dictionary | D → E | partial | sangam-flowable-ports; sangam-registry; sangam-resources:ResourceType.java; sangam-rest; sangam-ui |  | covered, with an open item \| sangam-flowable-ports L176: \| EC-06 \| Data objects / ERD (R78) \| Gap for stage C: stage D/E, not routed to WS-PORTS; integrator to  |
| R79 | SLA model | C (subset for AC-06) → D | implemented | sangam-case:DeadlineService.java; sangam-engine:TaggedDurationBusinessCalendar.java; sangam-flowable-ports:SlaService.java; sangam-packs:SlaDelegate.java; sanga | PackDeploymentTest+AllPacksSmokeTest (deploy/first wait) |  |
| R80 | Model test harness and debugger | D | implemented | sangam-flowable-ports:DebuggerService.java; sangam-modeller; sangam-rest; sangam-ui |  |  |
| R81 | Conversational and messaging channels | E | partial | sangam-flowable-ports; sangam-rest; sangam-ui |  | covered, with an open item \| sangam-flowable-ports L185: \| EC-15 \| Engage \| Owned: WS-INTEGRATION (R81), stage E \| |
| R82 | Routing and assignment strategies | D | partial | sangam-field:DispatchService.java; sangam-geo:Nearest.java; sangam-packs:AssignmentStrategyDelegate.java; sangam-rest; sangam-security; sangam-ui; pack universa | PackDeploymentTest+AllPacksSmokeTest (deploy/first wait) | covered, with an open item \| sangam-security L259: - R82 strategies (round-robin, least-loaded, skills) are not implemented. Only seat resolution and candidate  |
| R83 | Event-stream ingestion and correlation | E | implemented | sangam-flowable-ports:ChannelAdapters.java; sangam-packs:EventWindowDelegate.java; sangam-ui; pack universal.up03-event-correlation; pack universal.up21-iot-sto | PackDeploymentTest+AllPacksSmokeTest (deploy/first wait) |  |
| R84 | MySQL Tier-1 conditions (amends R17 per ADR-D-15) | B (finding) → C | implemented | sangam-engine:SangamEngineConfigurer.java; sangam-engine:IsolationGuardDataSource.java; sangam-persistence:sangam-0001-baseline.sql; sangam-server; sangam-serve |  |  |

### Amendments A-1…A-16

| Id | Title | Stage | Status | Where (module:file / pack) | Tests | Note |
|---|---|---|---|---|---|---|
| A-1 | Every input has a programmatic label in the current language. Help text is linked with ari |  | implemented | sangam-case:Db.java; sangam-programme; sangam-registry; sangam-resources; sangam-resources:ResourcesDialect.java; sangam-security:Sql.java; sdks/dotnet; sdks/do |  |  |
| A-2 | Errors: an error summary at the top with links to the fields; each field's error linked wi |  | implemented | sangam-connectors-health; sangam-engine:OperatonEngineAdapter.java; sangam-geo:WfsConnector.java; sangam-integration; sangam-integration:SangamConnector.java; s | T35 |  |
| A-3 | Keyboard: all controls can be operated; the tab order follows the visual order; there are  |  | implemented | sangam-persistence:sangam-0001-baseline.sql; sangam-programme:ProgrammeRestAdapter.java; sangam-resources; sangam-security; sangam-security:PolicyCompiler.java; |  |  |
| A-4 | Conditional reveal: newly shown fields are announced, focus is never lost, and removed fie |  | implemented | sangam-integration:InstanceLifecycleService.java |  |  |
| A-5 | Time limits: a session-expiry warning with an extend option at least 2 minutes before expi |  | implemented | sangam-integration:OutboxRelayService.java; sangam-integration:OutboxCapacityMonitor.java |  |  |
| A-6 | Contrast 4.5:1 for text and 3:1 for UI components; reflow at 320 CSS px; text resizable to |  | gap | sangam-engine |  | sangam-engine L239: - Partition epochs (R39): blocked by A-6. |
| A-7 | : L1 = any row in EXCLUSIVE_LEASE, for both execution and resource: modes (VI.6). The lock |  | implemented | sangam-engine:OperatonEngineAdapter.java; sangam-engine:ExclusiveLeaseTable.java; sangam-persistence; sangam-persistence:sangam-0001-baseline.sql |  |  |
| A-8 | : the default capability class is (c) "neither": no automatic repeat after lease expiry; t |  | implemented | sangam-engine:CapabilityClassExpiryPolicy.java; sangam-engine; sangam-integration:SangamConnector.java; sangam-integration:ConnectorInvoker.java; sangam-integra |  |  |
| A-9 | : for government records, R61 legal retention takes precedence over erasure (DPDP s.17(4)  |  | implemented | sangam-flowable-ports:TaskEnhancementService.java |  |  |
| A-10 | Lease-expiry hook. Engine-native lease expiry (inside fetch, job execution or the engine's |  | partial | sangam-engine:OperatonEngineAdapter.java; sangam-engine:CapabilityClassExpiryPolicy.java; sangam-engine; sangam-flowable-ports; sangam-integration; sangam-integ | FWT-013 | covered, with an open item \| sangam-rest L101: \| WorkPort \| OperatonWorkAdapter: Operaton external tasks + core LeaseTable fencing. Switches to EngineAdapterWor |
| A-11 | Root lock L2 is mandatory, not optional. Both engines rely on optimistic locking only; wit |  | implemented | sangam-engine:OperatonEngineAdapter.java; sangam-engine:RootLock.java; sangam-engine:RootLockFlushListener.java; sangam-persistence |  |  |
| A-12 | DST timer guard (R31) is mandatory on the base engine: UTC due dates, lastFiredInterval, c |  | implemented | deploy; sangam-engine:OperatonEngineAdapter.java; sangam-engine:CycleExpression.java; sangam-engine:DstSafeCycleBusinessCalendar.java; sangam-engine:TimerGuard. |  |  |
| A-13 | A-3 wording: any matching deny wins, at any scope (as compiled into NOT EXISTS) |  | implemented | sangam-persistence:sangam-0001-baseline.sql; sangam-rest; sangam-security; sangam-security:ResourceDescriptor.java; sangam-security:PolicyCompiler.java; sangam- |  |  |
| A-14 | OPERATION_LEDGER carries the engine work id (task / external-task id) for expiry and delet |  | implemented | sangam-engine:OperatonEngineAdapter.java; sangam-engine:JdbcIncidentSink.java; sangam-persistence; sangam-persistence:sangam-0001-baseline.sql |  |  |
| A-15 | The lease-table SQL is per dialect (PostgreSQL/Oracle/SQL Server); adapter constructors mu |  | implemented | sangam-case:CaseFileSchema.java; sangam-config:JdbcConfigStore.java; sangam-engine:EngineDialect.java; sangam-field:FieldSchema.java; sangam-flowable-ports:Json |  |  |
| A-16 | Count queries under authorization need optimisation (count p95 386–550 ms at 10k × 300 gro |  | implemented | sangam-case:CaseFileService.java; sangam-engine:OperatonEngineAdapter.java; sangam-flowable-ports:HistoryQueryService.java; sangam-flowable-ports:Paging.java; s |  |  |

### Decisions ADR-D-1…D-24

| Id | Title | Stage | Status | Where (module:file / pack) | Tests | Note |
|---|---|---|---|---|---|---|
| ADR-D-1 | Base engine: Operaton 2.2.x (score 85.2 vs Flowable 72.1; XI.10). Flowable strengths carri |  | implemented | sangam-engine (Operaton 2.2.0 as a Maven dependency; README cites ADR-D-16) |  | decision item: decision realised; README does not cite D-1 by id |
| ADR-D-2 | Stages A–E (VIII.9) |  | owner-side | process (VIII.9 stages; this register + SANGAM.md XII) |  | decision item: delivery decision, no code home |
| ADR-D-3 | Government / regulated integrator (VI.7) |  | owner-side | business positioning |  | decision item: decision, no code home |
| ADR-D-4 | SDK tiers (IX.6) |  | implemented | sdks/dotnet; sdks/java; sdks/php; sdks/python; sdks/python:client.py; sdks/typescript; sdks/typescript:client.ts |  |  |
| ADR-D-5 | REST first (X.0) |  | partial | sangam-engine; sangam-rest:sangam-api.yaml; sangam-server:pom.xml; sangam-server:OpenApiController.java |  | covered, with an open item \| sangam-engine L237: - OFFERED/ack state (VI.3.1 stream offers): REST-first (ADR-D-5) — a fetch returns LOCKED work; OFFERED belongs |
| ADR-D-6 | Scripts (by D-8) |  | implemented | = ADR-D-8 (script tasks rejected at deploy, sangam-engine SangamBpmnParseListener) |  | decision item: closed by D-8 |
| ADR-D-7 | Liquibase 4.33.0 + SQL changelogs; 5.x after legal review (VI.7, LIC-7) |  | implemented | sangam-control-plane:SchemaConfig.java; sangam-persistence; sangam-persistence:sangam-master.xml; sangam-server:pom.xml; sangam-server:SchemaBootstrap.java |  |  |
| ADR-D-8 | Out-of-process script worker; no GraalJS (VI.7, LIC-1) |  | implemented | sangam-engine:SangamBpmnParseListener.java; sangam-flowable-ports; sangam-programme:FormulaEvaluator.java; sangam-rest |  |  |
| ADR-D-9 | Market claim is a hypothesis (VIII.2) |  | owner-side | go-to-market |  | decision item: hypothesis, no code home |
| ADR-D-10 | Release approval through gates G1–G8 (VIII.2) |  | owner-side | release process (gates G1-G8) |  | decision item: gate evidence comes from the testing phase |
| ADR-D-11 | Revenue model (X.0) |  | owner-side | commercial |  | decision item: decision, no code home |
| ADR-D-12 | HA/DR targets (X.0) |  | implemented | deploy |  |  |
| ADR-D-13 | Self-reliant development, no government dependency (X.0b) |  | implemented | sangam-packs:PackRuntime.java; sangam-packs |  |  |
| ADR-D-14 | Universal open-source product; all premium features free (X.0c) |  | implemented | sangam-ui |  |  |
| ADR-D-15 | Tier-1 databases PostgreSQL, Oracle, SQL Server, MySQL (X.0c) |  | implemented | sangam-persistence:sangam-0001-baseline.sql |  |  |
| ADR-D-16 | Operaton as a dependency + Sangam plugins; engine patches only where needed, offered upstr |  | implemented | sangam-case:CaseFileEnginePlugin.java; sangam-engine; sangam-engine:SangamProcessEnginePlugin.java; sangam-flowable-ports; sangam-flowable-ports:SangamPortsPlug |  |  |
| ADR-D-17 | Licence Apache 2.0 (X.0d) |  | implemented | repo LICENSE + SPDX-License-Identifier: Apache-2.0 headers |  | decision item: not cited by id in any README |
| ADR-D-18 | Public GitHub repository sqamentor/sangam (X.0d) |  | owner-side | repository publication (no commit in this phase) |  | decision item: owner action |
| ADR-D-19 | Stage C: all workstreams run in parallel (X.0d) |  | implemented | Stage C ran as parallel workstreams (WS-* owners in each README) |  | decision item: process decision, realised |
| ADR-D-20 | Fully generic, plug-and-play platform; domain knowledge only as data (X.0e) |  | partial | sangam-config; sangam-config:Tree.java; sangam-config:NumberGrouping.java; sangam-connectors-health; sangam-control-plane:TenantRegistry.java; sangam-ledger; sa |  | covered, with an open item \| sangam-ledger L4: Owner: IFMS-IMPLEMENTATION workstream (generic module, ADR-D-20 / ADR-D-22). Base package org.sangam.ledger. Gove |
| ADR-D-21 | Cell-based deployment with a central control plane (X.0e) |  | implemented | sangam-cell:CellId.java; sangam-onboarding |  |  |
| ADR-D-22 | Boundary: workflow + rules + generic ledgers/master data; integrate with IFMS/EHR/core ban |  | partial | sangam-connectors-health; sangam-ledger; sangam-ledger:LedgerPorts.java; sangam-onboarding |  | covered, with an open item \| sangam-ledger L4: Owner: IFMS-IMPLEMENTATION workstream (generic module, ADR-D-20 / ADR-D-22). Base package org.sangam.ledger. Gove |
| ADR-D-23 | First jurisdiction packs: India, USA health, EU GDPR, international PFM (X.0e) |  | implemented | sangam-compliance; sangam-onboarding |  |  |
| ADR-D-24 | Load target: India national (~3,000 starts/s, ~30,000 ops/s, ~1M users) (X.0e) |  | implemented | perf; sangam-ledger:JdbcLedgerStore.java; sangam-onboarding |  |  |

### Acceptance criteria AC-01…AC-11

| Id | Title | Stage | Status | Where (module:file / pack) | Tests | Note |
|---|---|---|---|---|---|---|
| AC-01 | A bill passes initiator → selected checker → approver chosen by amount band; payment ackno |  | implemented | sangam-packs [gov.p1.bill-payment]; sangam-packs; sangam-packs:gov.p1.bill-payment [gov.p1.bill-payment]; sdks/java:BillPaymentWorker.java; sdks/php:AuditApi.ph | T16, T17, T19, T27, PilotScenarioTest |  |
| AC-02 | Amount or beneficiary changed after approval invalidates the approval and forces re-approv |  | implemented | sangam-packs [gov.p1.bill-payment]; sangam-packs [sec.bank.payment-dual-auth]; sangam-packs:gov.p1.bill-payment [gov.p1.bill-payment,sec.bank.payment-dual-auth] | T16 |  |
| AC-03 | Payment succeeds but the response is lost → UNKNOWN_OUTCOME → reconciliation resolves it w |  | implemented | sangam-packs; sangam-packs [gov.p1.bill-payment]; sangam-packs:gov.p1.bill-payment [gov.p1.bill-payment]; sangam-packs:ExternalOperationDelegate.java; sdks/dotn | T02, T27, T35, PilotScenarioTest |  |
| AC-04 | A file moves up three levels, is returned two levels, is resubmitted and approved; officer |  | implemented | sangam-packs [gov.p2.file-movement]; sangam-packs:gov.p2.file-movement [gov.p2.file-movement]; pack gov.p2.file-movement | T15, T17, PilotScenarioTest |  |
| AC-05 | Grant application fails eligibility after verification → rejection with reasons; partial d |  | implemented | sangam-packs [gov.p3.grant-sanction]; sangam-packs:gov.p3.grant-sanction [gov.p3.grant-sanction]; pack gov.p3.grant-sanction | T11, T27, PilotScenarioTest |  |
| AC-06 | Licence SLA breach triggers escalation; fee paid but callback lost is reconciled; a signed |  | implemented | sangam-packs [gov.p4.licence]; sangam-packs:gov.p4.licence [gov.p4.licence]; pack gov.p4.licence | T22, T47, PilotScenarioTest |  |
| AC-07 | The same pilot runs on Oracle, SQL Server and PostgreSQL with identical results |  | implemented | sangam-packs [sangam.core]; sangam-packs:sangam.core [sangam.core]; pack sangam.core | T18, T31 |  |
| AC-08 | Workers in Java, .NET, PHP, Python and Node complete tasks with the same lease, idempotenc |  | implemented | sangam-packs [sangam.core]; sangam-packs:sangam.core [sangam.core]; sangam-packs:EmbeddedWorkerRunner.java; pack sangam.core | T01, T02, T03, T04, T18 |  |
| AC-09 | A tenant/department cannot see another's data through UI, API, list, count or export |  | implemented | sangam-packs; sangam-packs [sangam.core]; sangam-packs:sangam.core [sangam.core]; sangam-packs:PackDeployer.java; pack sangam.core | T13, T14 |  |
| AC-10 | Hindi and English UI; a first-time officer completes a task without help (usability sessio |  | implemented | sangam-packs [sangam.core]; sangam-packs:sangam.core [sangam.core]; pack sangam.core | T32 |  |
| AC-11 | Air-gapped install from offline bundle on a State-DC-like VM |  | implemented | deploy; sangam-packs [sangam.core]; sangam-packs:sangam.core [sangam.core]; sangam-packs; sangam-server; sangam-server:ObservabilityConfig.java; sangam-server:O | T50 |  |

### Government items GOV-1…GOV-12

| Id | Title | Stage | Status | Where (module:file / pack) | Tests | Note |
|---|---|---|---|---|---|---|
| GOV-1 | Languages |  | implemented | sangam-packs | T32, T88 | realised through R54=partial |
| GOV-2 | Accessibility / web guidelines |  | implemented | sangam-packs | T32, T88 | realised through R55=implemented |
| GOV-3 | Security audit |  | partial | sangam-packs | T81, T49 | realised through R51=implemented, R52=partial |
| GOV-4 | Data protection |  | implemented | sangam-integration:ErasureService.java; sangam-packs; sangam-rest | T86 | realised through R53=implemented |
| GOV-5 | Data localisation / hosting |  | implemented | sangam-packs | T50 | realised through R56=implemented |
| GOV-6 | Identity / SSO |  | implemented | sangam-packs | T87 | realised through R50=implemented |
| GOV-7 | Digital signature |  | implemented | sangam-integration:EsignConnector.java; sangam-integration; sangam-packs | T80 | realised through R57=implemented |
| GOV-8 | Payments |  | implemented | sangam-integration:IfmsJitConnector.java; sangam-integration; sangam-packs | T02, T27, T47, T89 | realised through R58=implemented |
| GOV-9 | Documents |  | implemented | sangam-integration:DigiLockerPushConnector.java; sangam-integration; sangam-packs | T84 | realised through R59=implemented |
| GOV-10 | Notifications |  | implemented | sangam-integration:DltSmsConnector.java; sangam-integration; sangam-packs | T82 | realised through R60=implemented |
| GOV-11 | Records retention |  | implemented | sangam-packs; pack gov.records; pack gov.rti | PackDeploymentTest+AllPacksSmokeTest (deploy/first wait), T85, T30 | realised through R61=implemented |
| GOV-12 | Procurement |  | implemented | sangam-packs | release evidence (local-content dossier) | realised through R62=implemented |

### Consistency findings CF-*

| Id | Title | Stage | Status | Where (module:file / pack) | Tests | Note |
|---|---|---|---|---|---|---|
| CF-1 | Stages A–E are defined differently in III §15.2, VI.7 D-2 and VIII.9 |  | spec-closed | SANGAM.md X.1a / X.5.1; realised by ADR-D-2=owner-side |  | consistency finding resolved in the specification |
| CF-2 | Three versions of the SDK tiers; the VIII.6 index still shows D-4 OPEN |  | spec-closed | SANGAM.md X.1a / X.5.1; realised by ADR-D-4=implemented |  | consistency finding resolved in the specification |
| CF-3 | R9 assumes gRPC streaming while D-5 was open |  | spec-closed | SANGAM.md X.1a / X.5.1; realised by ADR-D-5=partial |  | consistency finding resolved in the specification |
| CF-4 | Lock level L1 covers only resource: mode, but the execution mode also takes a lease |  | spec-closed | SANGAM.md X.1a / X.5.1; realised by A-7=implemented |  | consistency finding resolved in the specification |
| CF-5 | No default for topics declaring neither effect=irreversible nor a capability class |  | spec-closed | SANGAM.md X.1a / X.5.1; realised by A-8=implemented |  | consistency finding resolved in the specification |
| CF-6 | Erasure (VI.5) vs records retention (R61, Public Records Act) |  | spec-closed | SANGAM.md X.1a / X.5.1; realised by A-9=implemented |  | consistency finding resolved in the specification |
| CF-7 | Test catalogue counts differ (T01–T32, T01–T49, T50) |  | spec-closed | SANGAM.md X.1a / X.5.1 | T01, T50, T51 | consistency finding resolved in the specification |
| CF-8 | The VIII.6 index omits R50–R62; R44 has no stage |  | spec-closed | SANGAM.md X.1a / X.5.1; realised by R44=implemented |  | consistency finding resolved in the specification |
| CF-9 | R31 (timers/DST) is in Stage D, but the pilot core and AC-06 need it |  | spec-closed | SANGAM.md X.1a / X.5.1; realised by R31=implemented | T22 | consistency finding resolved in the specification |
| CF-9a | Requirements placed in more than one stage in VIII.9 |  | spec-closed | SANGAM.md X.1a / X.5.1; realised by R19=implemented, R21=implemented, R26=implemented, R42=partial, R48=implemented |  | consistency finding resolved in the specification |
| CF-10 | R50–R62 have no tests; IX.3 "Hindi/English UI" has no spec reference |  | spec-closed | SANGAM.md X.1a / X.5.1; realised by R50=implemented, R51=implemented, R52=partial, R53=implemented, R54=partial, R55=implemented, R56=implemented, R57=implement | T87, T81, T49, T86, T32, T88, T50, T80, T02, T27 | consistency finding resolved in the specification |
| CF-11 | R17 PR-gate policy unresolved |  | implemented | deploy; deploy:db-matrix.yml |  | realised by R17=implemented |
| CF-12 | R31 stage vs the pilot core |  | spec-closed | SANGAM.md X.1a / X.5.1; realised by R31=implemented |  | consistency finding resolved in the specification |
| CF-13 | R34 MoSCoW status |  | spec-closed | SANGAM.md X.1a / X.5.1; realised by R34=implemented |  | consistency finding resolved in the specification |
| CF-14 | The index points to specs that did not exist |  | spec-closed | SANGAM.md X.1a / X.5.1; realised by R39=gap, R41=partial, R42=partial, R43=partial, R47=implemented |  | consistency finding resolved in the specification |
| CF-15 | A-6 status contradictory |  | spec-closed | SANGAM.md X.1a / X.5.1; realised by A-6=gap |  | consistency finding resolved in the specification |
| CF-16 | Stale summaries in Parts I–II |  | spec-closed | SANGAM.md X.1a / X.5.1 |  | consistency finding resolved in the specification |

### Licence items LIC-1…LIC-11

| Id | Title | Stage | Status | Where (module:file / pack) | Tests | Note |
|---|---|---|---|---|---|---|
| LIC-1 | GraalJS in the Operaton distros (org.graalvm.polyglot:js 25.4.4.1.1, GFTC licence: redistr |  | implemented | deploy; deploy:check_licences.py; sangam-engine:pom.xml; sangam-engine |  |  |
| LIC-2 | Oracle JDBC (ojdbc11/ojdbc17, Oracle Free Use Terms) |  | implemented | deploy; deploy:--jar |  |  |
| LIC-3 | IBM Db2 JCC (IBM IPLA, no redistribution right found) |  | implemented | deploy; deploy:--jar |  |  |
| LIC-4 | MySQL Connector/J (GPL-2.0 + Universal FOSS Exception) |  | implemented | deploy; deploy:--jar; sangam-control-plane; sangam-server; sangam-server:pom.xml |  |  |
| LIC-5 | MariaDB driver (LGPL-2.1) |  | implemented | deploy; deploy:mariadb-java-client-<v>.jar; sangam-server; sangam-server:pom.xml |  |  |
| LIC-6 | Legacy AngularJS webapps (EOL since Dec 2021) |  | implemented | deploy |  |  |
| LIC-7 | Liquibase 5.x FSL risk of an accidental upgrade |  | implemented | deploy; sangam-server |  |  |
| LIC-8 | bpmn.io licence (bpmn-js, dmn-js, form-js, cmmn-js: not OSI; the watermark must stay visib |  | implemented | deploy; deploy:ui.yml; sangam-ui:.bjs-powered-by; sangam-ui |  |  |
| LIC-9 | Trademarks |  | implemented | deploy |  |  |
| LIC-10 | Rhino, Nashorn, Jython, JRuby (shippable licences) |  | implemented | deploy; deploy:check_licences.py |  |  |
| LIC-11 | Inventory regenerated at release tag |  | implemented | deploy; deploy:release.yml |  |  |

### Parked items PK-01…PK-14

| Id | Title | Stage | Status | Where (module:file / pack) | Tests | Note |
|---|---|---|---|---|---|---|
| PK-01 | G.S.R. number and IS 17802 wording of the RPwD (Amendment) Rules, 10 May 2023 |  | owner-side | parked (X.6); absorbed by R55 conformance matrix (citation only) |  | external source at adoption; DEPwD site / e-Gazette (retry download) |
| PK-02 | Data Protection Board member appointments |  | owner-side | parked (X.6); absorbed by None needed |  | external source at adoption; dpb.gov.in / e-Gazette |
| PK-03 | NIC rule on hosting location / DR for applications |  | owner-side | parked (X.6); absorbed by R56, ADR-D-12 |  | external source at adoption; NIC hosting division |
| PK-04 | Parichay application onboarding metadata (SAML metadata / OAuth client registration) |  | owner-side | parked (X.6); absorbed by R50 identity adapter configuration |  | external source at adoption; NIC service desk, at adoption |
| PK-05 | Exact PFMS file/API schemas; other states' IFMS specs |  | owner-side | parked (X.6); absorbed by R58 adapter mapping files + simulator |  | external source at adoption; PFMS / state treasury, at adoption |
| PK-06 | NIC SMS console onboarding (behind login) |  | owner-side | parked (X.6); absorbed by R60 notification adapter |  | external source at adoption; NIC / Mobile Seva, at adoption |
| PK-07 | MeitY method for computing local content of general software |  | owner-side | parked (X.6); absorbed by R62 local-content dossier | DPIIT | external source at adoption; MeitY / DPIIT |
| PK-08 | Real connector capability (duplicate rejection / status lookup) for PFMS, gateway, eSign,  |  | owner-side | parked (X.6); absorbed by Adapter capability declaration |  | external source at adoption; Provider documentation at adoption |
| PK-09 | State-specific deemed-approval / SLA-pause rules (time-bound service laws) for P4 |  | owner-side | parked (X.6); absorbed by P4 per-state DMN tables |  | external source at adoption; State laws (public; to be collected per state) |
| PK-10 | CERT-In incident report format and submission channel |  | owner-side | parked (X.6); absorbed by Incident-report template |  | external source at adoption; CERT-In site / empanelled auditor |
| PK-11 | Whether citizen-facing forms may carry the bpmn.io watermark |  | implemented | sangam-ui:.bjs-powered-by |  | parked; absorbed by Stage C UI decision (form renderer choice) |
| PK-12 | FSL legal review for Liquibase 5.x |  | owner-side | parked (X.6); absorbed by D-7, enforcer ban |  | external source at adoption; Legal counsel |
| PK-13 | Licence of angular-data-depend (legacy webapps); latest versions of Flyway, Chicory and jD |  | owner-side | parked (X.6); absorbed by X.3 regeneration at the D-1 release tag |  | external source at adoption; Package registries |
| PK-14 | RQ-1…RQ-6 official requests (IX.7c) |  | owner-side | parked (X.6); absorbed by — |  | external source at adoption; Used only at go-to-market |

### Capabilities N01–N18

| Id | Title | Stage | Status | Where (module:file / pack) | Tests | Note |
|---|---|---|---|---|---|---|
| N01 | Resource booking, allotment and scheduling |  | partial | sangam-case:HearingService.java; sangam-case:MeetingService.java; sangam-case:CaseFilePorts.java; sangam-case; sangam-resources; sangam-resources:QueueModel.jav |  | covered, with an open item \| sangam-case L107: - Venue booking and the external party registry are ports with pass-through defaults until N01 and N14 exist. |
| N02 | Inventory, stock and warehouse (quantity ledger) |  | partial | sangam-ledger:LedgerService.java |  | sangam-ledger L20: \| LedgerService.java \| Policies (freeze orders, executable conditions, period expiry, transfer constraints by attributes |
| N03 | Asset registry and maintenance |  | implemented | sangam-registry |  |  |
| N04 | Case file and party model (generic case management) |  | implemented | sangam-case; sangam-case:CaseConfig.java; sangam-case:CaseFileService.java; sangam-case:PartyService.java; sangam-case:HearingService.java; sangam-case:CaseFile |  |  |
| N05 | Field inspection, mobile/offline capture, geo-tagged evidence |  | implemented | sangam-field; sangam-field:FieldTemplate.java; sangam-field:ScoringService.java; sangam-field:GeoServicePort.java; sangam-field:Assignment.java; sangam-field:Ra |  |  |
| N06 | Issued-entitlement registry (licences, permits, certificates, registrations) |  | implemented | sangam-registry |  |  |
| N07 | Queue and token management |  | implemented | sangam-resources; sangam-resources:ResourcesPorts.java |  |  |
| N08 | Rostering, shifts and duty deployment |  | implemented | sangam-resources; sangam-resources:ResourcesPorts.java |  |  |
| N09 | Statutory registers and numbering |  | partial | sangam-case:NumberService.java; sangam-registry |  | covered, with an open item \| sangam-registry L22: \| numbering/ \| N09 numbering. Gap-free series: one counter row per series, scope and period, locked per dialec |
| N10 | Billing, demand, tariff and collection |  | partial | sangam-billing; sangam-ledger:LedgerService.java; sangam-resources:ResourcesPorts.java; sangam-resources; pack universal.billing-cycle | PackDeploymentTest+AllPacksSmokeTest (deploy/first wait) | covered, with an open item \| sangam-ledger L20: \| LedgerService.java \| Policies (freeze orders, executable conditions, period expiry, transfer constraints by at |
| N11 | Data-pipeline and batch-job orchestration |  | implemented | sangam-integration |  |  |
| N12 | IoT / telemetry-triggered processes |  | implemented | sangam-field; sangam-integration |  |  |
| N13 | GIS and spatial data |  | partial | sangam-field:GeoServicePort.java; sangam-field:DispatchService.java; sangam-geo; sangam-geo:Geometry.java; sangam-geo:GeoMath.java; sangam-geo:JurisdictionLayer |  | covered, with an open item \| sangam-geo L94: - Overlap is a boolean intersect. Intersection area, buffers and unions are external-GIS work, as the N13 boundary  |
| N14 | Party / beneficiary registry with de-duplication |  | partial | sangam-case:PartyService.java; sangam-case:CaseFilePorts.java; sangam-case; sangam-registry |  | covered, with an open item \| sangam-case L107: - Venue booking and the external party registry are ports with pass-through defaults until N01 and N14 exist. |
| N15 | Targets, indicators, milestones and periodic returns |  | implemented | sangam-programme; sangam-programme:IndicatorDefinition.java; sangam-programme:ReturnDefinition.java; sangam-programme:PeriodService.java; sangam-programme:Targe |  |  |
| N16 | Meetings, committees, legislative questions and references |  | partial | sangam-case; sangam-case:CaseConfig.java; sangam-case:CommitteeService.java; sangam-case:MeetingService.java; sangam-case:ActionItemService.java; sangam-case:Re |  | covered, with an open item \| sangam-case L50: \| reference/ReferenceService.java \| N16 references with a deadline: register (kind fields, source category, duplic |
| N17 | Dispatch, trips and field-unit tracking |  | implemented | sangam-field; sangam-field:DispatchModel.java; sangam-geo:Nearest.java |  |  |
| N18 | Physical item custody and tracking (chain of custody) |  | gap | sangam-registry |  | sangam-registry L28: \| custody/ \| N18: tracked items; per-item hash-chained custody events plus AuditChain; two-step transfer and handover wi |

### Integration requests IR-1…IR-8

| Id | Title | Stage | Status | Where (module:file / pack) | Tests | Note |
|---|---|---|---|---|---|---|
| IR-1 | Cell id generator (sangam-server EngineWiring, WS-ENGINE). |  | implemented | sangam-cell; sangam-engine |  |  |
| IR-2 | Cell registration (sangam-server, new config/CellWiring). |  | implemented | sangam-cell; sangam-server |  |  |
| IR-3 | Roll-up and cross-cell publishers (sangam-server IntegrationWiring, WS-INTEGRATION). |  | implemented | sangam-cell; sangam-server; sangam-server:XCELL |  |  |
| IR-4 | Tenant-admin API on the cell (WS-REST + WS-INTEGRATION + WS-ENGINE). |  | partial | sangam-cell; sangam-control-plane; sangam-server; sangam-server:XCELL; sangam-server:ContentService |  | code: broker consumers (BrokerInboxWiring) do not check the tenant FROZEN state; files not moved (sangam-server README L268) |
| IR-5 | History "outbox-only" per definition (WS-ENGINE/WS-INTEGRATION). |  | implemented | sangam-cell; sangam-engine:OutboxOnlyDbHistoryEventHandler.java; sangam-engine |  |  |
| IR-6 | Read-replica routing (sangam-server/WS-REST). |  | partial | sangam-cell; sangam-server; sangam-server:CellReadReplicaRouter.routing() |  | code: CellReadReplicaRouter.routing()/onReplica has no caller |
| IR-7 | Job executor settings (sangam-server application.yaml). |  | implemented | sangam-cell; sangam-engine; sangam-server |  |  |
| IR-8 | History partitioning (sangam-persistence/WS-ENGINE). |  | implemented | sangam-cell; sangam-engine; sangam-persistence; sangam-persistence:sangam-history-partitioning.xml |  |  |

### Scale tests T-SCALE-1…11

| Id | Title | Stage | Status | Where (module:file / pack) | Tests | Note |
|---|---|---|---|---|---|---|
| T-SCALE-1 | Cell floor |  | test-pending | perf | T-SCALE-1 | named in a README; not run (testing phase) |
| T-SCALE-2 | Latency |  | test-pending | perf | T-SCALE-2 | named in a README; not run (testing phase) |
| T-SCALE-3 | Correctness under load |  | test-pending | perf | T-SCALE-3 | named in a README; not run (testing phase) |
| T-SCALE-4 | DB headroom |  | test-pending | perf | T-SCALE-4 | named in a README; not run (testing phase) |
| T-SCALE-5 | Linear scale-out |  | test-pending | perf | T-SCALE-5 | named in a README; not run (testing phase) |
| T-SCALE-6 | Back-pressure |  | test-pending | perf | T-SCALE-6 | named in a README; not run (testing phase) |
| T-SCALE-7 | Soak (R30) |  | test-pending | perf | T-SCALE-7 | named in a README; not run (testing phase) |
| T-SCALE-8 | Tenant move under load |  | test-pending | perf | T-SCALE-8 | named in a README; not run (testing phase) |
| T-SCALE-9 | Failure drills |  | test-pending | perf | T-SCALE-9 | named in a README; not run (testing phase) |
| T-SCALE-10 | Roll-up exactness |  | test-pending | perf | T-SCALE-10 | named in a README; not run (testing phase) |
| T-SCALE-11 | E9 defect fixed |  | test-pending | perf | T-SCALE-11 | named in a README; not run (testing phase) |

### Tests T01–T89

| Test | Title | Proves | Status |
|---|---|---|---|
| T01 | Duplicate process-start requests, simultaneous and after timeout | R18, R28, R41, AC-08, CF-7, CF-10 | test-pending |
| T02 | Crash after downstream payment success, before completion | R9, R18, R20, R36, R58, AC-03, AC-08, CF-10 | test-pending |
| T03 | Old worker resumes after lease expiry | R9, R18, R20, R21, R26, R36, AC-08 | test-pending |
| T04 | gRPC stream breaks while worker still runs | R9, R18, R20, R21, AC-08 | test-pending |
| T05 | Two external tasks marked exclusive | R20, R35 | test-pending |
| T06 | Late commit with lower outbox ID | R1, R4 | test-pending |
| T07 | Relay crash after publish/before mark | R1, R4 | test-pending |
| T08 | Poison outbox event and later same-instance events | R1, R4 | test-pending |
| T09 | Incoming event before subscription, and duplicate after commit | R38 | test-pending |
| T10 | Parent/child modification, timer and concurrent cancellation | R2, R3, R17, R23 | test-pending |
| T11 | Delete instance during external action | R23, AC-05 | test-pending |
| T12 | Crash after agent tool response before journal commit | R14, R37 | test-pending |
| T13 | Tenant spoofing in API, worker, broker and file reference | R14, R19, R25, R26, R28, AC-09 | test-pending |
| T14 | ABAC direct read versus list/count/export | R14, R19, R25, AC-09 | test-pending |
| T15 | Approver permission revoked after form load | R14, R19, AC-04 | test-pending |
| T16 | Amount/document/beneficiary changed after approval | R14, R19, R47, AC-01, AC-02 | test-pending |
| T17 | Initiator also has checker/approver assignment | R14, R19, AC-01, AC-04 | test-pending |
| T18 | Decimal/large integer/date/null through all supported SDKs | R8, R10, R11, R18, R33, AC-07, AC-08 | test-pending |
| T19 | DMN/rule/form updated while process remains active | R8, R10, R30, R32, R33, R42, R43, R47, AC-01 | test-pending |
| T20 | Unsupported executable BPMN/CMMN construct | R8, R34, R44, R46 | test-pending |
| T21 | Model modification and migration with timers/MI/compensation | R34, R43, R45, R48 | test-pending |
| T22 | DST gap/fold, holiday calendar, long downtime | R31, AC-06, CF-9 | test-pending |
| T23 | Hostile XML/JSON/script/connector destination | R6, R11, R14, R24, R25, R27, R28 | test-pending |
| T24 | Large tenant plus many small tenants | R2, R13, R22, R28, R30, R39 | test-pending |
| T25 | Broker outage beyond designed buffer window | R1, R4, R22, R28, R29, R40 | test-pending |
| T26 | DB failover under acknowledged writes | R17 | test-pending |
| T27 | Restore after real external effects have already completed | R20, R23, R36, R48, R58, AC-01, AC-03, AC-05, CF-10 | test-pending |
| T28 | Rolling N/N-1 upgrade with active processes | R5, R12, R17, R18, R32, R43, R48 | test-pending |
| T29 | Tenant provisioning/migration failure mid-batch | R12, R17, R32 | test-pending |
| T30 | Retention/erasure with hold and referenced objects | R29, R42, R61, CF-10 | test-pending |
| T31 | Stable-load benchmark with audit/auth enabled | R1, R2, R5, R13, R17, R22, R30, AC-07 | test-pending |
| T32 | First-time user completes real task flow | R46, R47, R54, R55, AC-10, GOV-2, CF-10 | test-pending |
| T33 | \| Tests \| T01–T89: T01–T32 Part III §16; T33–T40 Part VI; T41–T49 VIII.4; T50 IX.8; T51–T7 | R3 | test-pending |
| T34 | \| Tests \| T01–T89: T01–T32 Part III §16; T33–T40 Part VI; T41–T49 VIII.4; T50 IX.8; T51–T7 | R3 | test-pending |
| T35 | \| Tests \| T01–T89: T01–T32 Part III §16; T33–T40 Part VI; T41–T49 VIII.4; T50 IX.8; T51–T7 | R4, R38 | test-pending |
| T36 | CX-5, OJ-02 5b, IJ-01 | R9, R36 | test-pending |
| T37 | \| Tests \| T01–T89: T01–T32 Part III §16; T33–T40 Part VI; T41–T49 VIII.4; T50 IX.8; T51–T7 | R19 | test-pending |
| T38 | \| Tests \| T01–T89: T01–T32 Part III §16; T33–T40 Part VI; T41–T49 VIII.4; T50 IX.8; T51–T7 | R19 | test-pending |
| T39 | J-P1-E13, OJ-03 | R23 | test-pending |
| T40 | \| Tests \| T01–T89: T01–T32 Part III §16; T33–T40 Part VI; T41–T49 VIII.4; T50 IX.8; T51–T7 | R35 | test-pending |
| T41 | Partition rebalance / epochs (R39) | R39 | test-pending |
| T42 | Completeness of deterministic replay inputs (R42) | R42 | test-pending |
| T43 | Supported and rejected CMMN conversion corpus (R45) | R45 | test-pending |
| T44 | Continue-as-new obligations (R49) | R49 | test-pending |
| T45 | Lost wake-up (R1) | R1 | test-pending |
| T46 | Worker principal / lease binding (R26) | R26 | test-pending |
| T47 | Connector callback replay (R27, UC12) | R27, R58, AC-06, CF-10 | test-pending |
| T48 | Protocol and schema compatibility (R16) | R16 | test-pending |
| T49 | Cache and telemetry exporter failure (R13, R30) | R13, R30, R51, R52, CF-10 | test-pending |
| T50 | OJ-08 | R56, AC-11, CF-7, CF-10 | test-pending |
| T51 | A paused stale partition owner resumes after takeover: zero stale-epoch writes commit, and | CF-7, R39 | test-pending |
| T52 | Partition count changes 8 → 12 → 4 → off → 8 under load on every Tier-1 DB: no stranded jo | R39 | test-pending |
| T53 | Node loss with in-flight jobs and a poison job: bounded recovery, retries preserved, JOB_R | R39 | test-pending |
| T54 | Benefit gate: partitioned against default acquisition on the same hardware; partitioning m | R39 | test-pending |
| T55 | 50 identical concurrent idempotent requests: one execution, others 409 IN_PROGRESS or byte | R41 | test-pending |
| T56 | Key reuse with a different payload or a different principal: 422, no state change, no disc | R41 | test-pending |
| T57 | Crash just before or just after the command commit: exactly one execution, or replay of th | R41 | test-pending |
| T58 | Retention: full replay, then compact replay, then new execution stopped by the business ke | R41 | test-pending |
| T59 | Replay after revocation or tenant switch: 403 with no body; current masks applied to repla | R41 | test-pending |
| T60 | Outcome classification: business errors replayed; transient errors and 401/403 not stored | R41 | test-pending |
| T61 | Audit hash chain: edits, deletes, reorders, inserts and truncation detected by verificatio | R42 | test-pending |
| T62 | Audit present and ordered with history none, suppression, external mode and a 1-hour outbo | R42 | test-pending |
| T63 | Erasure scrubs every copy while the chain still verifies; refused and audited under a hold | R42 | test-pending |
| T64 | GOV-11 lifecycle: C review instead of deletion, two-person destroy, 25-year appraisal bloc | GOV-11, R42 | test-pending |
| T65 | Audit schema evolution: v1 events upcast to v2, chain verifies over stored bytes | R42 | test-pending |
| T66 | Diagnostics and fixture privacy: no personal fields by default, pseudonymisation or DPO ap | R42 | test-pending |
| T67 | Package closure: unresolved, forbidden-latest or unsupported references rejected; frozen i | R43 | test-pending |
| T68 | Live versus frozen in mid-flow: grant revocation, effective-dated delegation change, asOf  | R43 | test-pending |
| T69 | Lifecycle SoD by person and seat; production refuses sod=relaxed | R43 | test-pending |
| T70 | Promotion integrity: hash or signature mismatch rejected, ENV_BINDING gate, air-gapped bun | R43 | test-pending |
| T71 | Rollback and run-off: new starts on v2, v3 runs off, survives an engine upgrade, archived  | R43 | test-pending |
| T72 | Server-side form validation cannot be bypassed; FEEL condition parity between browser and  | R47 | test-pending |
| T73 | Field-level authorization and masking across every read path | R47 | test-pending |
| T74 | Attachments: tenant, root and field binding, AV quarantine, MIME spoofing, active content, | R47 | test-pending |
| T75 | In-flight form versions: bound task versions, historic rendering, outdated start forms, mi | R47 | test-pending |
| T76 | Accessibility (axe, NVDA/JAWS, GIGW Annexure II) and languages (en, hi, state language, di | R47 | test-pending |
| T77 | Drafts: revision conflicts, seat handover, never treated as state, expiry, erasure, hold,  | R47 | test-pending |
| T78 | \| Tests \| T01–T89: T01–T32 Part III §16; T33–T40 Part VI; T41–T49 VIII.4; T50 IX.8; T51–T7 | CF-10 | test-pending |
| T79 | \| Tests \| T01–T89: T01–T32 Part III §16; T33–T40 Part VI; T41–T49 VIII.4; T50 IX.8; T51–T7 | CF-10 | test-pending |
| T80 | \| Tests \| T01–T89: T01–T32 Part III §16; T33–T40 Part VI; T41–T49 VIII.4; T50 IX.8; T51–T7 | R57, CF-10 | test-pending |
| T81 | \| Tests \| T01–T89: T01–T32 Part III §16; T33–T40 Part VI; T41–T49 VIII.4; T50 IX.8; T51–T7 | R51, R52, CF-10 | test-pending |
| T82 | \| Tests \| T01–T89: T01–T32 Part III §16; T33–T40 Part VI; T41–T49 VIII.4; T50 IX.8; T51–T7 | R60, CF-10 | test-pending |
| T83 | \| Tests \| T01–T89: T01–T32 Part III §16; T33–T40 Part VI; T41–T49 VIII.4; T50 IX.8; T51–T7 | CF-10 | test-pending |
| T84 | \| Tests \| T01–T89: T01–T32 Part III §16; T33–T40 Part VI; T41–T49 VIII.4; T50 IX.8; T51–T7 | R59, CF-10 | test-pending |
| T85 | \| Tests \| T01–T89: T01–T32 Part III §16; T33–T40 Part VI; T41–T49 VIII.4; T50 IX.8; T51–T7 | R61, CF-10 | test-pending |
| T86 | \| Tests \| T01–T89: T01–T32 Part III §16; T33–T40 Part VI; T41–T49 VIII.4; T50 IX.8; T51–T7 | R53, CF-10 | test-pending |
| T87 | \| Tests \| T01–T89: T01–T32 Part III §16; T33–T40 Part VI; T41–T49 VIII.4; T50 IX.8; T51–T7 | R50, CF-10 | test-pending |
| T88 | \| Tests \| T01–T89: T01–T32 Part III §16; T33–T40 Part VI; T41–T49 VIII.4; T50 IX.8; T51–T7 | R54, R55, CF-10 | test-pending |
| T89 | \| Tests \| T01–T89: T01–T32 Part III §16; T33–T40 Part VI; T41–T49 VIII.4; T50 IX.8; T51–T7 | R58, CF-10 | test-pending |

## 6. Bulk families: every id not "implemented" / "data-only"

The UNACCOUNTED ids of these families are in §3 and are not repeated here. The full rows, implemented ones included, are in the CSV.

| Id | Family | Status | Where | Note |
|---|---|---|---|---|
| GP-09 | GP | partial | sangam-case:ActionItemService.java; sangam-case:CaseFilePorts.java; sangam-engine:SangamBpmnParseListener.java; sangam-e | covered, with an open item \| sangam-programme L62: \| delegate/ReturnCampaignDelegate.java \| Timer-driven campaign start (GP-30/GP-09); generates missing periods \| |
| GP-11 | GP | partial | sangam-engine:JdbcLedgerStore.java; sangam-ledger:Balance.java; sangam-ledger:LedgerStore.java; sangam-packs; sangam-pac | covered, with an open item \| sangam-packs L386: Journey details per pack: P1 bill (p1-bill-payment): duplicate bill review (E03), budget shortfall return (E11), encumbr |
| GP-30 | GP | partial | sangam-packs [gov.ifms.challan-reconciliation]; sangam-packs [gov.ifms.salary-monthly]; sangam-packs [gov.pension]; sang | covered, with an open item \| sangam-programme L62: \| delegate/ReturnCampaignDelegate.java \| Timer-driven campaign start (GP-30/GP-09); generates missing periods \| |
| GP-53 | GP | partial | sangam-packs [gov.p2.file-movement]; sangam-packs [gov.security-instrument]; sangam-packs [gov.work-order]; sangam-packs | covered, with an open item \| sangam-packs L386: Journey details per pack: P1 bill (p1-bill-payment): duplicate bill review (E03), budget shortfall return (E11), encumbr |
| GP-54 | GP | partial | sangam-case:CaseLinkService.java; sangam-packs [gov.p2.file-movement]; sangam-packs [gov.court-case]; sangam-packs:gov.p | covered, with an open item \| sangam-packs L386: Journey details per pack: P1 bill (p1-bill-payment): duplicate bill review (E03), budget shortfall return (E11), encumbr |
| UP-16 | UP | partial | sangam-packs:sangam-up16-provisioning.bpmn [universal.up16-provisioning]; sangam-packs [sec.hr.employee-exit]; sangam-pa | covered, with an open item \| sangam-packs L928: - Event-subprocess timers that are armed at scope start use literal durations (UP-16 leaver deadline PT4H, UP-19 obligat |
| UP-19 | UP | partial | sangam-packs:sangam-up19-clm.bpmn [universal.up19-contract-lifecycle]; sangam-packs:universal.up19-contract-lifecycle [u | covered, with an open item \| sangam-packs L928: - Event-subprocess timers that are armed at scope start use literal durations (UP-16 leaver deadline PT4H, UP-19 obligat |
| PF-13 | PF | partial | sangam-ui | explicit \| sangam-ui L260: \| PF-13 \| Metrics dashboard \| OpsDashboard \| UI ready; server [P] \| |
| PF-22 | PF | partial | sangam-ui | explicit \| sangam-ui L268: \| PF-22 \| Git sync / CI \| PackageWorkspace → Git \| UI ready; server [P] \| |
| PF-25 | PF | gap | sangam-ui | explicit \| sangam-ui L271: \| PF-25, PF-26 \| Console, backups \| Not in WS-UI scope (R70, Stage D) \| Gap \| |
| PF-26 | PF | gap | sangam-ui | explicit \| sangam-ui L271: \| PF-25, PF-26 \| Console, backups \| Not in WS-UI scope (R70, Stage D) \| Gap \| |
| PF-27 | PF | partial | sangam-ui | explicit \| sangam-ui L272: \| PF-27, PF-39 \| Connectors, service registry \| ServiceRegistry \| UI ready; server [P] \| |
| PF-28 | PF | partial | sangam-ui | explicit \| sangam-ui L273: \| PF-28, PF-46 \| Agentic AI \| AgentDefinitions, AgentUsage \| UI ready; server [P] \| |
| PF-29 | PF | partial | sangam-ui:RPA | explicit \| sangam-ui L274: \| PF-29 \| RPA \| ServiceRegistry connector RPA \| Partial (worker side E) \| |
| PF-31 | PF | gap | sangam-ui | explicit \| sangam-ui L276: \| PF-31 \| IDP (document processing) \| — \| Gap (R75, Stage E) \| |
| PF-35 | PF | partial | sangam-ui | explicit \| sangam-ui L280: \| PF-35 \| Pages \| PageBuilder, PageRuntime \| UI ready; server [P] \| |
| PF-38 | PF | partial | sangam-ui | explicit \| sangam-ui L283: \| PF-38 \| Data objects \| DataObjects + ERD \| UI ready; server [P] \| |
| PF-39 | PF | partial | sangam-integration:ConnectorRegistry.java; sangam-ui | explicit \| sangam-ui L272: \| PF-27, PF-39 \| Connectors, service registry \| ServiceRegistry \| UI ready; server [P] \| |
| PF-40 | PF | partial | sangam-ui | explicit \| sangam-ui L284: \| PF-40 \| SLA models \| SlaModels; SLA columns in the inbox and analytics \| UI ready; server [P] \| |
| PF-45 | PF | partial | sangam-ui | explicit \| sangam-ui L288: \| PF-45 \| Inspect \| Debugger \| UI ready; server [P] \| |
| PF-46 | PF | partial | sangam-ui | explicit \| sangam-ui L273: \| PF-28, PF-46 \| Agentic AI \| AgentDefinitions, AgentUsage \| UI ready; server [P] \| |
| PF-47 | PF | partial | sangam-ui | explicit \| sangam-ui L289: \| PF-47 \| Engage \| ConversationalChannels \| UI ready; server [P] \| |
| PF-51 | PF | not-replicated-by-decision | sangam-ui | explicit \| sangam-ui L292: \| PF-51 \| SaaS \| — (deliberately not replicated) \| X \| |
| FC-08 | FC | gap | sangam-flowable-ports; sangam-integration | explicit \| sangam-flowable-ports L96: \| FC-08 \| Camel task \| Owned: WS-INTEGRATION (stage E) \| — \| |
| FC-10 | FC | not-replicated-by-decision | sangam-flowable-ports | explicit \| sangam-flowable-ports L98: \| FC-10 \| Shell task \| X in engine (R6/D-8); out-of-process command worker: WS-INTEGRATION \| importer flags it \| |
| FC-11 | FC | partial | sangam-flowable-ports:CmmnTaskPatterns.java; sangam-flowable-ports:CmmnTaskPatterns; sangam-flowable-ports; sangam-rest; | explicit \| sangam-flowable-ports L99: \| FC-11 \| External worker for CMMN \| Done (pattern); native = Gap P-5 (stage E) \| cmmn/CmmnTaskPatterns \| |
| FC-14 | FC | not-replicated-by-decision | sangam-flowable-ports | explicit \| sangam-flowable-ports L102: \| FC-14 \| Future/async Java delegates \| X (would bypass L2/R36; external work instead) \| — \| |
| FC-16 | FC | not-replicated-by-decision | sangam-flowable-ports | explicit \| sangam-flowable-ports L104: \| FC-16 \| Runtime definition patching \| X (conflicts with R43; redeploy + migration = FC-15/FC-18) \| — \| |
| FC-18 | FC | partial | sangam-flowable-ports:MigrationDocument.java; sangam-flowable-ports; sangam-flowable-ports:*; sangam-persistence; sangam | explicit \| sangam-flowable-ports L106: \| FC-18 \| Migration builder variants, JSON document \| Done (historic-instance migration: Gap, Operaton history bound to  |
| FC-19 | FC | partial | sangam-flowable-ports:CaseLifecycleService.java; sangam-flowable-ports:CaseLifecycleService; sangam-flowable-ports | explicit \| sangam-flowable-ports L107: \| FC-19 \| CMMN case migration \| Done as run-off/cut-over; native = Gap P-5 \| cmmn/CaseLifecycleService \| |
| FC-24 | FC | partial | sangam-engine:SangamProcessEnginePlugin.java; sangam-engine; sangam-flowable-ports:FilteredAcquireJobsCmd.java; sangam-f | explicit(mixed) \| sangam-engine L124: \| FC-24 job executor \| config route done (SKIP LOCKED, timers first, priorities); hand-off / separate timer acquisition  |
| FC-27 | FC | not-replicated-by-decision | sangam-flowable-ports | explicit \| sangam-flowable-ports L120: \| FC-27 \| Secure JS sandbox \| X (Rhino/GraalJS not ported); script worker: WS-INTEGRATION \| — \| |
| FC-37 | FC | partial | sangam-flowable-ports; sangam-ui; sdks/dotnet; sdks/java:SangamWorker.java; sdks/java; sdks/php:SangamClient.php; sdks/p | covered, with an open item \| sdks/php L191: Flowable gap ids: FC-36 (consumer of the Sangam worker API), FC-37 (worker clients in several languages: |
| FC-47 | FC | not-replicated-by-decision | sangam-flowable-ports | explicit \| sangam-flowable-ports L145: \| FC-47 \| Groovy static engine \| X in engine (D-8); script worker: WS-INTEGRATION \| — \| |
| FC-48 | FC | not-replicated-by-decision | sangam-flowable-ports:EngineMetrics; sangam-flowable-ports | explicit \| sangam-flowable-ports L146: \| FC-48 \| JMX \| X, replaced by OTel/Micrometer (WS-ENGINE EngineMetrics, WS-REST binding) \| — \| |
| FC-49 | FC | not-replicated-by-decision | sangam-flowable-ports | explicit \| sangam-flowable-ports L147: \| FC-49 \| OSGi \| X (outside the product envelope) \| — \| |
| FC-51 | FC | gap | sangam-flowable-ports | explicit \| sangam-flowable-ports L149: \| FC-51 \| CockroachDB \| Gap: stage E dialect work (WS-ENGINE / persistence) \| — \| |
| FC-56 | FC | partial | sangam-engine; sangam-flowable-ports:PortsCmmnTransformListener.java; sangam-flowable-ports; sangam-flowable-ports:Ports | explicit \| sangam-flowable-ports L159: \| FC-56 \| Plan-item lifecycle extensions \| Partial: lifecycle listeners Done; state-machine extensions = Gap P-4 (stage  |
| FC-57 | FC | gap | sangam-engine; sangam-flowable-ports; sangam-rest:sangam-api.yaml; sangam-rest | explicit \| sangam-flowable-ports L160: \| FC-57 \| Repetition with collections, max count \| Gap P-4 (stage E, engine state-machine patch) \| — \| |
| EC-03 | EC | gap | sangam-flowable-ports; sangam-rest; sangam-ui | explicit \| sangam-flowable-ports L173: \| EC-03 \| Pages (low code) \| Owned: WS-UI (R77), stage E \| |
| EC-06 | EC | gap | sangam-flowable-ports; sangam-rest; sangam-ui | explicit \| sangam-flowable-ports L176: \| EC-06 \| Data objects / ERD (R78) \| Gap for stage C: stage D/E, not routed to WS-PORTS; integrator to assign (proposed  |
| EC-14 | EC | partial | sangam-flowable-ports; sangam-ui | explicit \| sangam-flowable-ports L184: \| EC-14 \| Agents / AI \| Stage E (R72); prerequisite FC-01 Done; owner proposed WS-PACKS/WS-INTEGRATION \| |
| EC-15 | EC | gap | sangam-flowable-ports; sangam-ui | explicit \| sangam-flowable-ports L185: \| EC-15 \| Engage \| Owned: WS-INTEGRATION (R81), stage E \| |
| EC-19 | EC | not-replicated-by-decision | sangam-flowable-ports; sangam-ui | explicit \| sangam-flowable-ports L189: \| EC-19 \| Enterprise Cloud \| X (self-hosted, R56) \| |
| D-10 | D | gap | sangam-control-plane; sangam-engine:SangamEngineConfigurer.java; sangam-engine:OperatonClock.java; sangam-engine; sangam | explicit \| sangam-engine L96: \| D-10 global ClockUtil \| contained: OperatonClock, one engine per JVM (per-engine clock SPI = Stage D patch) \| |
| D-16 | D | owner-side | sangam-engine; sangam-flowable-ports | explicit \| sangam-flowable-ports L204: \| D-16 \| Organisational (integrator: patch ledgers + upstream offers, both ledgers) \| |
| D-19 | D | partial | sangam-engine:OperatonEngineAdapter.java; sangam-engine:ExternalTask.xml; sangam-engine; sangam-flowable-ports; sangam-i | explicit \| sangam-engine L101: \| D-19 concurrent fetch \| interim W-9 route (fetch lock + OLE listener); the ExternalTask.xml SKIP LOCKED patch is not d |
| D-23 | D | gap | sangam-engine:SangamBpmnParseListener.java; sangam-engine; sangam-flowable-ports:MultiInstanceAggregation.java; sangam-f | explicit \| sangam-engine L103: \| D-23 large MI \| deploy + runtime guard (engine profiling = Stage D) \| |
| D-27 | D | gap | sangam-engine; sangam-flowable-ports | explicit \| sangam-flowable-ports L207: \| D-27 \| Owned: WS-ENGINE (stage D engine patch) \| |
| D-40 | D | partial | sangam-engine; sangam-flowable-ports; sangam-integration:WakeupNotifier.java; sangam-integration:WakeupNotifier; sdks/do | covered, with an open item \| sdks/python L126: Flowable gap ids: FC-36, FC-37, D-17, D-18, D-40, D-44. The structure follows the Flowable external-worker |
| D-44 | D | partial | sangam-engine; sangam-flowable-ports; sdks/dotnet; sdks/java:SangamWorker.java; sdks/java; sdks/php:SangamClient.php; sd | covered, with an open item \| sdks/python L126: Flowable gap ids: FC-36, FC-37, D-17, D-18, D-40, D-44. The structure follows the Flowable external-worker |
| D-48 | D | gap | sangam-engine; sangam-flowable-ports | explicit \| sangam-flowable-ports L220: \| D-48, D-49 \| Owned: WS-ENGINE (D-48 stage D profiling) \| |
| D-49 | D | partial | sangam-engine:PerDefinitionHistoryLevel.java; sangam-engine:PerDefinitionHistoryLevel; sangam-flowable-ports | explicit(mixed) \| sangam-flowable-ports L220: \| D-48, D-49 \| Owned: WS-ENGINE (D-48 stage D profiling) \| |
| D-50 | D | owner-side | sangam-engine; sangam-flowable-ports | explicit \| sangam-flowable-ports L221: \| D-50 \| Organisational / WS-DEPLOY \| |
| D-52 | D | gap | sangam-engine; sangam-flowable-ports:SangamModelValidator.java; sangam-flowable-ports | explicit \| sangam-flowable-ports L223: \| D-52 \| Validator rejects (here); runtime support = stage E engine patches (WS-ENGINE) \| |
| IFC-001 | IFC | gap | sangam-packs/src/main/resources/profiles; sangam-packs/src/main/resources/profiles:orgModel | explicit \| sangam-packs/src/main/resources/profiles L38: \| IFC-001 \| config: profile orgModel (seat types, office tiers, registry events) + sangam-security seats / postin |
| IFC-043 | IFC | partial | sangam-packs [gov.ifms.budget-formulation]; sangam-packs:gov.ifms.budget-formulation [gov.ifms.budget-formulation]; sang | explicit \| sangam-packs/src/main/resources/profiles L80: \| IFC-043 \| ledger (APPROPRIATION stage) + budget-formulation / supplementary packs \| in/GOI.yaml#budgetControl \| |
| IFC-044 | IFC | partial | sangam-packs [gov.ifms.budget-formulation]; sangam-packs:gov.ifms.budget-formulation [gov.ifms.budget-formulation]; sang | explicit \| sangam-packs/src/main/resources/profiles L81: \| IFC-044 \| ledger (APPROPRIATION stage) + budget-formulation / supplementary packs \| in/GOI.yaml#budgetControl \| |
| IFC-052 | IFC | partial | sangam-packs [gov.ifms.budget-formulation]; sangam-packs:gov.ifms.budget-formulation [gov.ifms.budget-formulation]; sang | explicit \| sangam-packs/src/main/resources/profiles L89: \| IFC-052 \| ledger (APPROPRIATION stage) + budget-formulation / supplementary packs \| in/GOI.yaml#budgetControl \| |
| IFC-055 | IFC | partial | sangam-packs [gov.ifms.budget-formulation]; sangam-packs:gov.ifms.budget-formulation [gov.ifms.budget-formulation]; sang | explicit \| sangam-packs/src/main/resources/profiles L92: \| IFC-055 \| ledger (APPROPRIATION stage) + budget-formulation / supplementary packs \| in/GOI.yaml#budgetControl \| |
| IFC-123 | IFC | partial | sangam-packs [gov.ifms.bill]; sangam-packs:gov.ifms.bill [gov.ifms.bill]; sangam-packs/src/main/resources/profiles:GOI.y | explicit \| sangam-packs/src/main/resources/profiles L160: \| IFC-123 \| config: document-type catalogue + approval-chain templates + number series; ledger encumber / consum |
| IFC-189 | IFC | gap | sangam-packs/src/main/resources/profiles:GOI.yaml#procurement | explicit \| sangam-packs/src/main/resources/profiles L226: \| IFC-189 \| existing procurement / works packs; thresholds as profile data \| in/GOI.yaml#procurement \| - \| G \| s |
| IFC-202 | IFC | partial | sangam-packs [gov.ifms.year-end-closing]; sangam-packs:gov.ifms.year-end-closing [gov.ifms.year-end-closing]; sangam-pac | explicit \| sangam-packs/src/main/resources/profiles L239: \| IFC-202 \| monthly-accounts / year-end packs; ledger period lock and accounting-entry mapping \| in/GOI.yaml#acc |
| IFC-214 | IFC | partial | sangam-packs [gov.ifms.monthly-accounts]; sangam-packs:gov.ifms.monthly-accounts [gov.ifms.monthly-accounts]; sangam-pac | explicit \| sangam-packs/src/main/resources/profiles L251: \| IFC-214 \| monthly-accounts / year-end packs; ledger period lock and accounting-entry mapping \| in/GOI.yaml#acc |
| IFC-217 | IFC | partial | sangam-packs [gov.ifms.audit-para-settlement]; sangam-packs:gov.ifms.audit-para-settlement [gov.ifms.audit-para-settleme | explicit \| sangam-packs/src/main/resources/profiles L254: \| IFC-217 \| audit-para settlement pack; core audit trail \| in/GOI.yaml#audit \| gov.ifms.audit-para-settlement \|  |
| IFC-219 | IFC | partial | sangam-packs [gov.ifms.audit-para-settlement]; sangam-packs:gov.ifms.audit-para-settlement [gov.ifms.audit-para-settleme | explicit \| sangam-packs/src/main/resources/profiles L256: \| IFC-219 \| audit-para settlement pack; core audit trail \| in/GOI.yaml#audit \| gov.ifms.audit-para-settlement \|  |
| IFC-238 | IFC | gap | sangam-packs/src/main/resources/profiles:GOI.*.yaml | explicit \| sangam-packs/src/main/resources/profiles L275: \| IFC-238 \| department profiles (department layer) over GOI \| in/goi/GOI..yaml \| - \| G \| CPWD account-code depar |
| IFC-244 | IFC | gap | sangam-packs/src/main/resources/profiles:GOI.*.yaml | explicit \| sangam-packs/src/main/resources/profiles L281: \| IFC-244 \| department profiles (department layer) over GOI \| in/goi/GOI..yaml \| - \| G \| field-heavy CDDO patter |
| IFC-254 | IFC | partial | sangam-packs [gov.ifms.gpf]; sangam-packs:gov.ifms.gpf [gov.ifms.gpf]; sangam-packs/src/main/resources/profiles:- [gov.i | explicit \| sangam-packs/src/main/resources/profiles L291: \| IFC-254 \| platform core (forms, eSign, idempotency, SoD, tenancy, adapters) \| - \| gov.ifms.gpf \| P \| [U] value |
| IFS-077 | IFS | gap | sangam-packs/src/main/resources/profiles:*.yaml#operations | explicit \| sangam-packs/src/main/resources/profiles L377: \| IFS-077 \| profile offlineUnits flags only \| in/states/.yaml#operations \| - \| G \| store-and-forward: field work |
| GA-02 | GA | partial | sangam-config:ClassificationScheme.java; sangam-packs/src/main/resources/profiles | explicit \| sangam-packs/src/main/resources/profiles L643: \| GA-02 \| classification registry + per-state schemes + seeds \| - \| P \| official LMMH seed to CS 1127 (slips 112 |
| GA-04 | GA | partial | sangam-packs [gov.ifms.bill]; sangam-packs:gov.ifms.bill [gov.ifms.bill]; sangam-packs/src/main/resources/profiles:gov.i | explicit \| sangam-packs/src/main/resources/profiles L645: \| GA-04 \| bill pack: sanction reference checked by the catalogue \| gov.ifms.bill \| P \| sanction as linked case:  |
| GA-06 | GA | partial | sangam-packs [gov.ifms.bill]; sangam-packs:gov.ifms.bill [gov.ifms.bill]; sangam-packs/src/main/resources/profiles:gov.i | explicit \| sangam-packs/src/main/resources/profiles L647: \| GA-06 \| bill intake from external sources (integrations.sourceSystem) \| gov.ifms.bill \| P \| inbound start cont |
| GA-08 | GA | partial | sangam-packs [gov.ifms.gia-uc]; sangam-packs:gov.ifms.gia-uc [gov.ifms.gia-uc]; sangam-packs/src/main/resources/profiles | explicit \| sangam-packs/src/main/resources/profiles L649: \| GA-08 \| GIA-UC pack; AC/DC flags in profile \| gov.ifms.gia-uc \| P \| AC/DC pairing pack not separate (contingen |
| GA-13 | GA | partial | sangam-packs/src/main/resources/profiles | explicit \| sangam-packs/src/main/resources/profiles L654: \| GA-13 \| ledger sub-ledger types (profile publicAccounts) \| - \| P \| PD/PLA policies as data; no dedicated PD pa |
| GA-14 | GA | partial | sangam-packs/src/main/resources/profiles | explicit \| sangam-packs/src/main/resources/profiles L655: \| GA-14 \| profile orgTopology (typed units, servedGovernments, rendition parent) \| - \| P \| unit registers per st |
| GA-15 | GA | partial | sangam-packs/src/main/resources/profiles | explicit \| sangam-packs/src/main/resources/profiles L656: \| GA-15 \| profile subStateEntities + n-level scopes \| - \| P \| share-formula DMNs per entity to write \| |
| GA-16 | GA | partial | sangam-packs [gov.ifms.reappropriation]; sangam-packs:gov.ifms.reappropriation [gov.ifms.reappropriation]; sangam-packs/ | explicit \| sangam-packs/src/main/resources/profiles L657: \| GA-16 \| DMN delegation tables (reapp, appraisal, GIA, rail) + per-government financial-powers table skeleton \| |
| GA-18 | GA | partial | sangam-packs/src/main/resources/profiles | explicit \| sangam-packs/src/main/resources/profiles L659: \| GA-18 \| existing works packs + profile works mode \| - \| P \| CCL/DCL / LoC as rail; e-MB attachments data only  |
| GA-19 | GA | partial | sangam-packs/src/main/resources/profiles | explicit \| sangam-packs/src/main/resources/profiles L660: \| GA-19 \| profile procurement family + per-government procurement-mode DMN skeleton \| - \| P \| State thresholds a |
| GA-20 | GA | partial | sangam-packs/src/main/resources/profiles | explicit \| sangam-packs/src/main/resources/profiles L661: \| GA-20 \| pension DMN CCS 2021 + profile payer classes \| - \| P \| state pension rules not loaded \| |
| GA-21 | GA | partial | sangam-packs/src/main/resources/profiles | explicit \| sangam-packs/src/main/resources/profiles L662: \| GA-21 \| salary pack + profile payroll binding \| - \| P \| \| |
| GA-22 | GA | partial | sangam-config:LocaleSettings.java; sangam-config; sangam-packs/src/main/resources/profiles | explicit \| sangam-packs/src/main/resources/profiles L663: \| GA-22 \| locale languages per profile \| - \| P \| forms still en / hi; language packs for forms not generated \| |
| GA-26 | GA | partial | sangam-packs/src/main/resources/profiles | explicit \| sangam-packs/src/main/resources/profiles L667: \| GA-26 \| ledger SHADOW mode + connector bindings \| - \| P \| SAP / NIC adapters are WS-INTEGRATION \| |
| GA-27 | GA | gap | sangam-packs/src/main/resources/profiles | explicit \| sangam-packs/src/main/resources/profiles L668: \| GA-27 \| offline units (profile flags); store-and-forward belongs to the field workstream \| - \| G \| out of this |
| GA-28 | GA | partial | sangam-packs/src/main/resources/profiles | explicit \| sangam-packs/src/main/resources/profiles L669: \| GA-28 \| DBT pack + ledger striping \| - \| P \| throughput not measured (testing phase) \| |
| CMP-044 | CMP | owner-side | sangam-compliance:obligations.tsv | sangam-compliance L0: catalogue row: bearer=DF check=none source-status=[V] |
| CMP-045 | CMP | owner-side | sangam-compliance:obligations.tsv | sangam-compliance L0: catalogue row: bearer=DF check=none source-status=[V] |
| CMP-083 | CMP | owner-side | sangam-compliance:obligations.tsv | sangam-compliance L0: catalogue row: bearer=DF check=none source-status=[V] |
| CMP-094 | CMP | owner-side | sangam-compliance:obligations.tsv | sangam-compliance L0: catalogue row: bearer=DF check=none source-status=[V] |
| CMP-095 | CMP | owner-side | sangam-compliance:obligations.tsv | sangam-compliance L0: catalogue row: bearer=DF check=none source-status=[V] |
| CMP-096 | CMP | owner-side | sangam-compliance:obligations.tsv | sangam-compliance L0: catalogue row: bearer=DF check=none source-status=[V] |
| CMP-111 | CMP | owner-side | sangam-compliance:obligations.tsv | sangam-compliance L0: catalogue row: bearer=DF check=security source-status=[S] |
| CMP-112 | CMP | owner-side | sangam-compliance:obligations.tsv | sangam-compliance L0: catalogue row: bearer=DF check=logs source-status=[V] |
| CMP-113 | CMP | owner-side | sangam-compliance:obligations.tsv | sangam-compliance L0: catalogue row: bearer=DF check=none source-status=[V] |
| CMP-115 | CMP | owner-side | sangam-compliance:obligations.tsv | sangam-compliance L0: catalogue row: bearer=DF check=logs source-status=[V] (penalty) / [S] (duty) |
| CMP-117 | CMP | owner-side | sangam-compliance:obligations.tsv | sangam-compliance L0: catalogue row: bearer=DF check=security source-status=[U] |
| CMP-118 | CMP | owner-side | sangam-compliance:obligations.tsv | sangam-compliance L0: catalogue row: bearer=DF check=none source-status=[V] (fine) |
| CMP-144 | CMP | owner-side | sangam-compliance:obligations.tsv | sangam-compliance L0: catalogue row: bearer=DF check=none source-status=[V] |

## 6a. NG, FWT and D-12, verified 2026-11-08 (SL-36)

One statement per id, identical to the Findings column of `PREMIUM-GAP-RECHECK.md` (NG) and to the CSV. Test = class#method found in the test sources that exercises the capability, or "no test". Run evidence = local surefire reports (`target-*/surefire-reports`) dated 2026-11-08; no CI run was checked. FWT "implemented" means the scenario test exists and its last recorded run passed.

| Id | Family | Status | Where | Test | Note |
|---|---|---|---|---|---|
| NG-01 | NG | implemented | sangam-ai:draft/DraftService.java, draft/DraftValidators.java | AiRuntimeTest#draftNeedsValidationAndAnotherReviewerAndNeverDeploys | back end only; drafts never deploy; REST package validator is a further gate via Sink |
| NG-02 | NG | implemented | sangam-ai:expr/ExpressionPlayground.java | AiRuntimeTest#playgroundEvaluatesFeel | FEEL only (JUEL refused by design); runaway evaluation abandoned on timeout |
| NG-03 | NG | partial | sangam-ai:rag/KnowledgeBaseService.java | AiRuntimeTest#retrievalFiltersBeforeRankingWithThePolicyCompiler; AiRuntimeTest#typeWildcardDenyBlocksRetrieval | gap: brute-force cosine in the JVM, no pgvector index; text input only (no PDF/image extraction) |
| NG-04 | NG | partial | sangam-ai:a2a/A2a.java | ProtocolLoopbackTest#a2aClientTalksToOurServerAndUnknownOutcomeParks; ProtocolLoopbackTest#a2aFallsBackTo03WhenThePeerRefuses10 | gap: no streaming or push; protocol revision not conformance-tested |
| NG-05 | NG | partial | sangam-ai:mcp/McpClient.java | ProtocolLoopbackTest#mcpClientTalksToOurServerAndFiltersTools; ProtocolLoopbackTest#mcpClientFallsBackToLegacyAndReadsSseWithNotifications | gap: no server-initiated requests |
| NG-06 | NG | partial | sangam-ai:mcp/McpServer.java | AiRuntimeTest#mcpServerHidesWritesUntilEnabledAndUsesCallerToken; ProtocolLoopbackTest#mcpServerAnswersEachEra | gap: transport-neutral only; no HTTP endpoint / RestGateway wiring in the server |
| NG-07 | NG | implemented | sangam-ai:agent/AgentDefinition.java, agent/AgentRuntime.java, agent/AgentTools.java (subAgent) | no test | sub-agents run as tools inside the run (not a BPMN ad-hoc sub-process); no test exercises the orchestrator/external types |
| NG-08 | NG | partial | sangam-ai:classify/DocumentClassifier.java | AiRuntimeTest#lowConfidenceGoesToHuman | gap: text only, no OCR |
| NG-09 | NG | implemented | sangam-ai:draft/DraftService.java (kinds DASHBOARD, TRANSLATION, AGENT_PROMPT) | AiRuntimeTest#translationKeepsPlaceholders | only the translation kind has a test; nothing activates without approval |
| NG-10 | NG | implemented | sangam-ai:judge/LlmJudge.java | AiRuntimeTest#judgeNeverPassesWhenDisabled | verdict marked non-deterministic |
| NG-11 | NG | partial | sangam-channels:messenger/MessengerConnectors.java (teams), WebhookVerifiers.java (botFramework), InboundMessageParsers.java | ChannelsTest#messengersNeedOptInAndMapResults; ChannelsTest#webhookSignatures | gap: no Teams app package/manifest, no tab task app, no live task-list updates, no IdP token acquisition |
| NG-12 | NG | partial | sangam-channels:conversation/ConversationService.java | ChannelsTest#conversationAccessIdempotencySearchAndLiveDelivery | gap: no WebSocket endpoint or UI; search is a substring scan; retention on request only |
| NG-13 | NG | partial | sangam-channels:bot/BotBridge.java, resources/bots/sample-bot.bpmn | ChannelsTest#sampleBotParses | gap: ProcessGateway adapter and bot.prompt worker not written; dialogue never run on the engine |
| NG-14 | NG | partial | sangam-channels:messenger/MessengerConnectors.java (whatsApp, weChat), WebhookVerifiers.java, OptInRegistry.java | ChannelsTest#messengersNeedOptInAndMapResults; ChannelsTest#webhookSignatures | gap: WeChat encrypted mode, media download, provider token refresh, template approval rules |
| NG-15 | NG | partial | sangam-channels:enterprise/EnterpriseConnectors.java (sapOData) | ChannelsTest#odataRetriesOnceWhenTheTokenExpired; ChannelsTest#serviceNowAndSapThroughSimulator | gap: no RFC, no update/delete, licence review not done |
| NG-16 | NG | implemented | sangam-channels:enterprise/EnterpriseConnectors.java (serviceNow, serviceNowWebhookValid) | ChannelsTest#serviceNowAndSapThroughSimulator | simulator only; webhook HMAC is a Sangam convention |
| NG-17 | NG | partial | sangam-modeller:catalogue/ExtensionCatalogue.java | ModellerTest#catalogueNeedsSignatureLicenceAndChecksum | gap: no hosted catalogue, publishing flow or install step; vendor feature [U] |
| NG-18 | NG | partial | sangam-modeller:simulation/TokenWalk.java | ModellerTest#tokenWalkAsksForChoiceForksAndJoins | gap: no diagram animation in the UI; boundary events, event sub-processes and compensation not walked |
| NG-19 | NG | partial | sangam-modeller:template/ElementTemplates.java | ModellerTest#templatesVersionAndValidate | gap: no properties panel, palette or form-editor integration |
| NG-20 | NG | partial | sangam-modeller:landscape/ProcessLandscape.java | ModellerTest#landscapeFindsMissingDynamicAndCycles | gap: no graph screen |
| NG-21 | NG | partial | sangam-modeller:lease/EditLeases.java | ModellerTest#leaseGivesOneEditorAndFencesLateSave | gap: package save must call checkFence; no read-only mode or takeover dialog |
| NG-22 | NG | partial | sangam-modeller:mock/ServiceMocks.java, mock/ServiceMockStore.java | ModellerTest#mocksNeverOnProductionAndNeverFallThrough; ModellerTest#mockRulesPersistWithToggle | gap: no mock editor |
| NG-23 | NG | partial | sangam/tools/sangam-cli:src/main/java/org/sangam/cli/Main.java (module in sangam/pom.xml) | MainTest#exitCodesAreStable; MainTest#testRunFailsOnFailedCases; MainTest#startSendsIdempotencyKey; MainTest#deployIsMultipart; MainTest#servicePrincipalLoginStoresTokenAndRefusesPlainHttp | CLI exists (the recheck's MISSING was wrong); fake HTTP layer only; gap: no token refresh or OS keychain; device-flow polling untested |
| NG-24 | NG | partial | sangam-ops:metrics/UsageMetrics.java | OpsTest#usageRollupReplacesTheDay | gap: no daily schedule wired |
| NG-25 | NG | partial | sangam-ops:migrate/LegacyHistoryMigrator.java | OpsTest#migratorResumesNeverCopiesPasswordsAndReconciles | gap: rows copied as JSON to OPS_MIG_ROW; no mapping into the audit chain or product views |
| NG-26 | NG | partial | sangam-modeller:auth/TokenExchange.java | ModellerTest#tokenExchangeRefusesNoUserAndCachesPerUser | gap: no call site in the connector auth layer; loopback endpoint only |
| NG-27 | NG | partial | sangam-channels:mail/InboundMail.java | ChannelsTest#mailIsIdempotentAndPoisonIsRecorded | gap: Simulated source only, no Microsoft Graph source; Poison port not bound to PoisonInbound |
| NG-28 | NG | partial | sangam-modeller:action/ActionModels.java | ModellerTest#actionsCheckPermissionOnServerAndAreIdempotent | gap: Dispatcher binding not written; no action menu in the UI |
| NG-29 | NG | implemented | sangam-ops:search/SearchIndexMaintenance.java | OpsTest#reindexSwitchesAliasAndDetectsDrift | index is a relational table (R65 has no external index) |
| NG-30 | NG | partial | sangam-ops:search/GlobalSearch.java | OpsTest#searchCapsAndHiddenRowsDoNotCount; OpsTest#typeWildcardDenyBlocksSearchAndOutliersThroughThePolicyCompiler | gap: no Ctrl+K dialog; substring scan |
| NG-31 | NG | partial | sangam-ops:outlier/OutlierAnalysis.java | OpsTest#outliersRespectMinSampleAndFindValue | gap: no screen; no multiple-comparison correction; readSamples untested |
| NG-32 | NG | partial | sangam-modeller:test/BulkModelTests.java | ModellerTest#bulkRunContinuesAfterErrorsAndBundleIsSealed | gap: Executor not bound to the REST test endpoint; no report screen |
| NG-33 | NG | partial | sangam-ui:src/app/App.tsx, src/areas/tasklist/MyWork.tsx (responsive shell, FieldVisits) | no test | gap: no native app, no camera upload, no connect-to-another-server |
| NG-34 | NG | partial | sangam-ops:vars/ScopedVariables.java | OpsTest#variablesMergeTenantOverCluster | gap: no edit UI; expressions need the bean registered |
| NG-35 | NG | implemented | sangam-ops:listener/GlobalTaskListeners.java | OpsTest#globalListenerAttachesOnlyToMatchingKeysAndValidatesHandler | test covers configuration; attachment to a running engine not run |
| NG-36 | NG | partial | sangam-ops:retention/HierarchyRetention.java | OpsTest#retentionTreatsTreeAsOneUnit | gap: Purger not bound to the engine history service; no legal-hold check |
| NG-37 | NG | partial | sangam/deploy/helm/sangam/templates/backup-cronjobs.yaml | no test | continuous WAL receiver for PostgreSQL only; Oracle archivelog and MySQL binlog are scheduled jobs, not continuous; design/Helm only, not run |
| NG-38 | NG | partial | sangam-ops:secrets/SecretAdmin.java | OpsTest#secretsRoundTripThroughBundleAndListingHasNoValues | gap: no UI; tests use a fake encryptor |
| NG-39 | NG | partial | sangam/deploy/DUAL-REGION.md | no test | design only (file says nothing deployed or tested); active-passive per cell |
| NG-40 | NG | partial | sangam-channels:inbound/PolledChannels.java | ChannelsTest#fileChannelMovesAfterHandover; ChannelsTest#sqlChannelResumesFromCursor; ChannelsTest#serviceBusCompletesOnlyAfterHandover | gap: not wired into InboundChannelManager; SFTP tested with local transport only |
| NG-41 | NG | partial | sangam-ops:sequence/SequenceAdmin.java | OpsTest#sequenceMovesForwardOnly | gap: no admin screen |
| NG-42 | NG | implemented | sangam-integration:inbox/UniqueStartGuard.java | no test | no direct test of UniqueStartGuard found (used in MixedRemittanceTest set-up only) |
| NG-43 | NG | implemented | sangam/deploy/helm/sangam/templates/backup-cronjobs.yaml | no test | Helm templates; not run in a cluster |
| NG-44 | NG | implemented | sangam-ui:src/api/endpoints.ts:312 (/packages/{id}/diff) | no test | visual comparison not checked |
| NG-45 | NG | implemented | sangam-ui:src/auth/AuthProvider.tsx:177 (signinRedirect state = path+query) | no test |  |
| NG-46 | NG | not-replicated-by-decision | - | no test | vendor SaaS items; Sangam is self-hosted (R56) |
| NG-47 | NG | owner-side | - | no test | services, not software (R68) |
| FWT-001 | FWT | implemented | sangam-flowable-ports | FwtXmlGuardsTest#fwt001_doctypeWithExternalEntitiesRejectedUtf8AndUtf16_noFileOrNetworkAccess | passed (target-fwt, 2026-11-08) |
| FWT-002 | FWT | implemented | sangam-flowable-ports | FwtXmlGuardsTest#fwt002_billionLaughsAfter4KiBCommentPaddingRejectedByParserFeature | passed (target-fwt, 2026-11-08) |
| FWT-003 | FWT | implemented | sangam-flowable-ports | FwtXmlGuardsTest#fwt003_elevenMiBModelIsModelTooLarge413 | passed (target-fwt, 2026-11-08) |
| FWT-004 | FWT | implemented | sangam-flowable-ports | FwtXmlGuardsTest#fwt004_bpmnNested300DeepRejectedByDepthLimit | passed (target-fwt, 2026-11-08) |
| FWT-005 | FWT | implemented | sangam-flowable-ports | FwtChannelGuardsTest#fwt005_deepNestingHugeStringLongNumberRejectedInvalidInputWithoutThrowing; FwtEventRegistryTest#fwt005_hostilePayloadsAreRejectedAsInvalidInputAndAcknowledged | passed (target-fwt, 2026-11-08) |
| FWT-006 | FWT | implemented | sangam-flowable-ports | FwtChannelGuardsTest#fwt006_twoMiBWebhookBodyIs413BeforeHmac | passed (target-fwt, 2026-11-08) |
| FWT-007 | FWT | implemented | sangam-flowable-ports | FwtEventRegistryTest#fwt007_messageCatchInParallelBranchIsDeliveredToThatInstanceOnly | passed (target-fwt, 2026-11-08) |
| FWT-008 | FWT | implemented | sangam-flowable-ports | FwtJobsTest#fwt008_externalCompletionWhoseContinuationThrowsRollsBack | passed (target-fwt, 2026-11-08) |
| FWT-009 | FWT | partial | sangam-server | FwtWorkerCompatIT#fwt009_completeWithoutLockVersionIsFencingTokenRequired | gap: last recorded run FAILED (target-fwt 2026-11-08 13:29: 403 action-not-permitted); cause unverified |
| FWT-010 | FWT | partial | sangam-server | FwtWorkerCompatIT#fwt010_staleCallAfterReacquireIsStaleLease | gap: last recorded run FAILED (403 action-not-permitted); cause unverified |
| FWT-011 | FWT | partial | sangam-server | FwtWorkerCompatIT#fwt011_allowUnfencedRightAttemptAcceptedWrongAttemptRejected | gap: last recorded run FAILED (IllegalStateException instead of ApiProblem); cause unverified |
| FWT-012 | FWT | partial | sangam-flowable-ports; sangam-server | FwtInputGuardsTest#fwt012_malformedDurationsAndMissingWorkerIdAreInvalidInput_numberOfTasksClampedTo100; FwtWorkerCompatIT#fwt012_malformedInputIs422InvalidInput | module part passed; gap: REST part last run FAILED (403); cause unverified |
| FWT-013 | FWT | implemented | sangam-flowable-ports | FwtJobsTest#fwt013_expiredJobLockReacquiredExternalLeaseUntouched | passed (target-fwt, 2026-11-08) |
| FWT-014 | FWT | partial | sangam-flowable-ports; sangam-server | FwtAdHocImportTest#fwt014_adHocUserTaskCompensationHandlerCreated; FwtWorkerCompatIT#fwt014_adHocCompensationHandlerTaskOnPostgres | H2 and PostgreSQL passed; gap: Oracle, MSSQL, MySQL not run |
| FWT-015 | FWT | implemented | sangam-flowable-ports | FwtAdHocImportTest#fwt015_cancelRemainingCompensatesOnlyCompleted | passed (target-fwt, 2026-11-08) |
| FWT-016 | FWT | implemented | sangam-flowable-ports | FwtAdHocImportTest#fwt016_importedVariableListenerFiresOnChange | passed (target-fwt, 2026-11-08) |
| FWT-017 | FWT | partial | sangam-flowable-ports | FwtHistoryTest#fwt017_unfilteredVariablesListNeverCounts | passed on H2; gap: p95 is printed, not asserted against R19 |
| FWT-018 | FWT | implemented | sangam-flowable-ports | FwtInputGuardsTest#fwt018_page300Size500IsPageTooDeep422 | passed (target-fwt, 2026-11-08) |
| FWT-019 | FWT | implemented | sangam-flowable-ports | FwtInputGuardsTest#fwt019_historyQueryWithNullPredicateIsIllegalArgument | passed (target-fwt, 2026-11-08) |
| FWT-020 | FWT | implemented | sangam-flowable-ports | FwtModelImporterTest#fwt020_activitiNamespaceMappedLikeFlowable | passed (target-fwt, 2026-11-08) |
| FWT-021 | FWT | implemented | sangam-flowable-ports | FwtModelImporterTest#fwt021_dmnServiceTaskWithKeyBecomesBusinessRuleTaskWithDeploymentBinding_withoutKeyUnsupported | passed (target-fwt, 2026-11-08) |
| FWT-022 | FWT | implemented | sangam-flowable-ports | FwtModelImporterTest#fwt022_scriptTaskIsUnsupportedD46 | passed (target-fwt, 2026-11-08) |
| FWT-023 | FWT | implemented | sangam-flowable-ports | FwtKafkaInboundTest#fwt023_poisonRecordsAreRejectedCommittedAndTheNextRecordIsProcessed | passed (target-fwt, 2026-11-08) |
| FWT-024 | FWT | implemented | sangam-flowable-ports | FwtKafkaInboundTest#fwt024_databaseDownDuringReceiveThrowsAndKafkaSeeksBackAndRetries | passed (target-fwt, 2026-11-08) |
| FWT-025 | FWT | implemented | sangam-flowable-ports | FwtEventRegistryTest#fwt025_messageIdOf300CharsIsRejectedAsInvalidInput | passed (target-fwt, 2026-11-08) |
| FWT-026 | FWT | implemented | sangam-flowable-ports | FwtEventRegistryTest#fwt026_payloadTenantDifferentFromChannelTenantIsTenantMismatch | passed (target-fwt, 2026-11-08) |
| FWT-027 | FWT | implemented | sangam-flowable-ports | FwtEventRegistryTest#fwt027_allowPayloadTenantWithoutAllowedTenantsIsRefusedAtDeploy | passed (target-fwt, 2026-11-08) |
| FWT-028 | FWT | implemented | sangam-flowable-ports | FwtEventRegistryTest#fwt028_allowedTenantsListDecidesAndDeliveryStaysInTheNamedTenant | passed (target-fwt, 2026-11-08) |
| FWT-029 | FWT | implemented | sangam-flowable-ports | FwtEventRegistryTest#fwt029_defaultTenantChannelCorrelatesAndStartsWithoutTenant | passed (target-fwt, 2026-11-08) |
| FWT-030 | FWT | implemented | sangam-flowable-ports | FwtEventRegistryTest#fwt030_reservedPayloadFieldIsRefusedAtDeploy | passed (target-fwt, 2026-11-08) |
| FWT-031 | FWT | partial | sangam-server | FwtWorkerCompatIT#fwt031_reservedVariableFromWorkerIs422AndNoSkip | gap: last recorded run FAILED (403 action-not-permitted); cause unverified |
| FWT-032 | FWT | implemented | sangam-flowable-ports | FwtEventRegistryTest#fwt032_eventWithoutCorrelationValuesIsNotBroadcastAndExpires | passed (target-fwt, 2026-11-08) |
| FWT-033 | FWT | implemented | sangam-flowable-ports | FwtEventRegistryTest#fwt033_uniqueStartWithoutReferenceIsRejectedAtIntake | passed (target-fwt, 2026-11-08) |
| FWT-034 | FWT | implemented | sangam-flowable-ports | FwtInjectionTest#fwt034_expressionInAssigneeRefusedNothingDeployed | passed (target-fwt, 2026-11-08) |
| FWT-035 | FWT | implemented | sangam-flowable-ports | FwtInjectionTest#fwt035_fragmentCompensationStaysInsideFragment | passed (target-fwt, 2026-11-08) |
| FWT-036 | FWT | implemented | sangam-flowable-ports | FwtInjectionTest#fwt036_invalidFragmentsRefused | passed (target-fwt, 2026-11-08) |
| FWT-037 | FWT | implemented | sangam-flowable-ports | FwtInjectionTest#fwt037_twentyFirstInjectionAndSuspendedInstanceRefused | passed (target-fwt, 2026-11-08) |
| FWT-038 | FWT | implemented | sangam-flowable-ports | FwtInjectionTest#fwt038_injectionWithEventScopeExecutionThenCompensate | passed (target-fwt, 2026-11-08) |
| FWT-039 | FWT | implemented | sangam-flowable-ports | FwtAdHocImportTest#fwt039_sequentialAdHocRaceExactlyOneStarts | passed (target-fwt, 2026-11-08) |
| FWT-040 | FWT | implemented | sangam-flowable-ports | FwtJobsTest#fwt040_exclusiveJobsOfOneInstanceNeverLockedOnTwoNodes | passed (target-fwt, 2026-11-08); two engines on one H2 database |
| FWT-041 | FWT | implemented | sangam-flowable-ports | FwtJobsTest#fwt041_handOffSaturatedPoolAndDeploymentAware | passed (target-fwt, 2026-11-08) |
| FWT-042 | FWT | implemented | sangam-flowable-ports | FwtServicesTest#fwt042_debuggerRefusesNonSandboxTenant | passed (target-fwt, 2026-11-08) |
| FWT-043 | FWT | implemented | sangam-flowable-ports | FwtServicesTest#fwt043_resumeStopsAtThousandSteps | passed (target-fwt, 2026-11-08) |
| FWT-044 | FWT | implemented | sangam-flowable-ports | FwtServicesTest#fwt044_uploadOverDefaultLimitRefusedNothingStored | passed (target-fwt, 2026-11-08) |
| FWT-045 | FWT | implemented | sangam-flowable-ports | FwtServicesTest#fwt045_openOtherTenantsItemIsNotFound | passed (target-fwt, 2026-11-08) |
| FWT-046 | FWT | implemented | sangam-flowable-ports | FwtChannelGuardsTest#fwt046_outboundTimeoutZeroIsOneSecond; #fwt046_outboundTimeoutNotANumberIsADeployError; #fwt046_outboundOneGiBResponseBodyIsDiscardedNotBuffered; #fwt046_outboundRedirectIsNotFollowed | passed (target-fwt, 2026-11-08) |
| FWT-047 | FWT | implemented | sangam-flowable-ports | FwtEventRegistryTest#fwt047_xmlDeserializerChannelIsRefused | passed (target-fwt, 2026-11-08) |
| FWT-048 | FWT | implemented | sangam-flowable-ports | FwtEventRegistryTest#fwt048_earlyEventIsBufferedAndDeliveredWhenTheInstanceReachesTheCatch | passed (target-fwt, 2026-11-08) |
| FWT-049 | FWT | implemented | sangam-flowable-ports | FwtAppBundleTest#fwt049_zipSlipRefused; #fwt049_nestedZipRefused; #fwt049_duplicateEntryRefused; #fwt049_sixHundredEntriesRefused; #fwt049_seventyMBUncompressedRefused | passed (target-fwt, 2026-11-08) |
| FWT-050 | FWT | implemented | sangam-flowable-ports | FwtServicesTest#fwt050_changeTenantItemWithoutTargetTenant | passed (target-fwt, 2026-11-08) |
| FWT-051 | FWT | implemented | sangam-flowable-ports | FwtChannelGuardsTest#fwt051_webhookSignatureMissingWrongRight | passed (target-fwt, 2026-11-08) |
| FWT-052 | FWT | partial | sangam-flowable-ports | FwtKafkaInboundTest#fwt052_soakOf10kMixedEventsLeavesNoStuckPartitionAndOneRejectedPerPoison | passed in-process; gap: MockConsumer + webhook only, no real Kafka broker and no JMS |
| FWT-053 | FWT | implemented | sangam-flowable-ports | FwtAdHocImportTest#fwt053_importedParallelMiExternalWorkerAllFetched | passed (target-fwt, 2026-11-08) |
| FWT-054 | FWT | implemented | sangam-flowable-ports | FwtInjectionTest#fwt054_claimedTaskKeepsIdentityDuringInjection; FwtInjectionTest#fwt054_claimedTaskKeepsIdentityDuringCrossInstanceMove | passed (target-fwt, 2026-11-08) |
| FWT-055 | FWT | implemented | sangam-flowable-ports | FwtTimerCyclesTest#fwt055_thirtySixTimerCyclesKeepRuntimeTablesFlat | passed (target-fwt, 2026-11-08); runtime part only |
| FWT-056 | FWT | implemented | sangam-flowable-ports | FwtServicesTest#fwt056_productRowRolledBackWhenEngineCommandFails | passed (target-fwt, 2026-11-08) |
| FWT-057 | FWT | implemented | sangam-flowable-ports; sangam-engine | FwtModelImporterTest#fwt057_shellTypeAndScriptTaskBothUnsupported; FwtScriptTaskDeployH2Test#fwt057_scriptTaskDeployedDirectlyIsRejected | both passed (target-fwt, 2026-11-08) |
| D-12 | D | implemented | sangam-engine:deploy/SangamBpmnParseListener.java:226-227 (call activity: binding set to DEPLOYMENT when absent), :253-258 (business rule task without decisionRefBinding is REJECTED at deploy, not defaulted) | InheritedWeaknessH2Test#businessRuleTaskWithoutBindingIsRejected; InheritedWeaknessH2Test#callActivityWithoutBindingCallsTheChildOfItsOwnDeployment; InheritedWeaknessH2Test#explicitLatestBindingIsHonoured | corrected: decision binding is not defaulted; a missing decisionRefBinding is a deploy error (setting isDefaultBindingDeployment); passed (target-unit/target-docs 2026-11-08) |

## 7. Regenerating

The register was produced by two scratch scripts, kept outside the repository and run read-only over it: an extraction/matching script and a writer. To refresh it after README changes, re-run the extraction against the same sources. The hand-set statuses (IR-3/4/6, R41/42/43) and the decision-item classifications in §3.6 must be re-checked by reading the cited README lines.
