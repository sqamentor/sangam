# Sangam 0.1.0 release notes

Release of 2026-11-17 under owner decisions 405 and 406 (`docs/records/OWNER-DECISIONS-2026-11-12.md`; decision 406 is
recorded at 4ec73bba on the integration branch and supersedes 405 on scope).

- Release sha: **TO-FILL** (the owner-signed push; announced to IFMS as `[REPORT] SANGAM-GREEN-SHA`).
- Previous published state: `main` at 209d2765. This note covers 209d2765..0c5f5518 (308 commits) plus the lane
  branches marked **merging**, which land before the release sha.
- Test counts on the release sha: unit **TO-FILL**, H2 acceptance **TO-FILL**, UI (vitest) **TO-FILL**,
  `IfmsPacksIT` Oracle 23 **TO-FILL**, SQL Server 2025 **TO-FILL**, full verify ci23 **TO-FILL**.

**Status words.** *In 0.1.0* = merged into the integration branch at the sha named. *Merging* = coded on a lane
branch, being merged for 0.1.0; it is in the release only if it is an ancestor of the release sha. *Known limitation*
= not in 0.1.0, target 0.1.1. *UNCERTAIN* = the claim is below 98% confidence.

## 1. What 0.1.0 contains, by area

Shas are merge or decision commits on the integration branch `claude/workspace-scan-resume-06b524`.

### 1.1 Engine and process runtime

| Item | Evidence |
|---|---|
| Engine write-path guards reconciled with every module that writes engine variables (decisions 381 (4), 385 (2), 387 (1)) | 4d109059 |
| DSC gate through a call: generic prepare task, slot completion bypass closed in two layers, slot consumption on a verified signature (decisions 382, 385, 387, 390 (1)) | c5e237d6, 4f130d6c, eb779e1a; `DscSlotBindingH2Test`, `GateRoundSlotTest` |
| Incident skip/cancel and admin lifecycle routes (round 6 item 2) | 6a59b299, 332e1851 |
| DEC-145: connector and send-event work use the engine tenant only and fail closed without one (decision 402 (5)) | bc6ba27e (merged in 3f5ea4e5); `ConnectorTenantSourceH2Test` 7/7 |
| Job-executor pause waits for dispatched batches (D9); media worker close ordering | eecfe7a0, 0ca28121 |
| M-136 Operaton capture-to-replay on H2 | fa1aec7c |
| ROUTING-INCIDENT v1: decision-144 keys additive after the six v1 keys (decision 357) | 1a45f546; `RoutingIncidentContractTest` |
| **Merging:** PNP-1 `SangamEnginePlugin` (Sangam as one drop-in Operaton plugin, `sangam.engine.*`), PNP-2 exact-name host bean approvals `sangam.engine.deploy-allowed-beans`, PNP-3 `RoutingIncidents.collect`, decision 404 (5) class-B audit of host-approved deployments | feat/round8-pnp: 5c944df4, 72fac64f, eee38a23, 4fd97c61; `DropInHostH2Test`, `HostApprovalEntriesTest`, `SettingsBinderTest` |
| **Merging:** SL-180, finished work held as "outcome unknown" is released at once (decision 402 (3)) | release path of decision 405; no commit at 0c5f5518 (IFMS-FIT-AUDIT §2) |

### 1.2 Security, authorization and tenancy

| Item | Evidence |
|---|---|
| Token scopes bind through defined role rows; platform role rows filled at start (decisions 358, 368, 370) | 74c6ec36, e1fa8691 |
| Production refuses to start without the compliance principal key; trusted proxies named (decisions 371, 372) | c6583065, 48cb179e; `ProductionStartupFailFastTest` |
| Tenant identities refused for shared instances and shared definitions by key (decisions 355, 364) | f342a661, e0080d62 |
| Policy admin API fails closed on effect, principal type, action and condition (sweep S7) | d63b8650 |
| R7UI-S12: module-wide `*` on table-backed types decided by policy (security 188/0) | 498c2832 |
| Hosted extension catalogue security (NG-17, decision 380) | f8b67298, 6a59b299 |
| **Merging (decision 406):** decision 392, one policy id per principal, with a split migration audited per row | lane R7-ui; no commit at 0c5f5518 |
| **Merging (decision 406):** decision 402 (2), plain-Java security bridges module and four-eyes policy-change approval port | lane w2 / R8; no commit at 0c5f5518 |
| **Merging:** team.read permission and the organisation-scoped team task-group report (decisions 399 Q4, 401, 404 (2)) | feat/round8-ux 6ebdd441 |

### 1.3 Money and exact numbers

| Item | Evidence |
|---|---|
| Nested decimals typed in JSON variables, strict REST Decimal, typed event matching (G24, G25, G27) | e0080d62 |
| DecimalString accepts the server's canonical exponent form; Java SDK sends negative-scale decimals canonically (decisions 363, 367) | f342a661, 8b71e9b2 |
| Untyped JSON fractions keep their exact text; exact stored fractions; registry JSON reads fractions exactly (decisions 138, 385 (2)) | 43438530, 81e752d5, 4d109059 |

### 1.4 Compliance (CERT-In / DPDP)

| Item | Evidence |
|---|---|
| Signed-in and OTP rights intake, erasure, breach notice, consent per purpose, age and guardian consent, grievance period (decisions 335-350, 360-362, 365, 369) | 10a4b474, 44b09423, b2e43f69, 1e742e62 |
| Compliance pack shipped as `sangam.compliance` 1.1.1 (decision 402 (8), F7-4) | f58fecc9 (merge 95d8956c) |
| Compliance pack form fields named by `id` (13 DPDP rights, breach and incident forms used `key`, so the UI lost their values); guard `PackFormFieldIdsTest` over every pack form | fix/compliance-form-ids |
| Opt-in live eSign connector with the decision 140 verifier | fa4b5102 |

### 1.5 Packs (IFMS, leave, pension and others)

| Item | Evidence |
|---|---|
| Decision 388: PG_CONFIG renamed PG_PROGRAMME_CONFIG without editing applied changesets | 0438ccd9 (merge 1261db4f) |
| Decision 389: BANK_ACCOUNT clear values held through the compliance national-id vault | 57ca10fc (merge 1261db4f) |
| Decision 391: CONTROLLING leave sanction row in `sangam-core-powers.dmn` | 6383c10c (merge 1261db4f); `CorePowersControllingLeaveTest` |
| Payee check reads `partyStatus` (was `status`) | 62ee53db (merge da2ec50e); `PayeeConnectorResultNamesTest` |
| F7-2 "Profile binding missing" hold task; `gov.ifms.bill` 1.0.3 (decision 400 (2)) | f48f6f1a |
| Pack start forms served by `/forms/by-key` (decision 400 (3), UI-DEMO-1) | c9a2f515; `PackStartFormH2Test` 2/2 |
| Exam-cycle `registrationWindow` as an ISO-8601 duration (decision 394) | 6507062f |
| Pack versions at 0c5f5518: `sangam.core` 1.0.2, `gov.ifms.bill` 1.0.3, `sangam.compliance` 1.1.1, other IFMS packs as in `docs/IFMS-INTEGRATION-GUIDE.md` section 7.1 | `pack.json` files |

### 1.6 Persistence and databases

| Item | Evidence |
|---|---|
| MySQL older than 8.4 refused at start (D7) | eecfe7a0 |
| MariaDB `dbms` tag on MySQL changesets | 7f04ae1b |
| Scale modules refuse an unsupported database by name (decision 384) | 9dea6df8 |
| SQL Server now-plus-millis overflow, MySQL quota window deadlock, system-identity list predicate | a4db44ac (merge da2ec50e) |
| `MysqlUtf8mb4PatchIT` derives the 42 Operaton FKs from the scripts | 62ee53db |
| LISTEN only on a real PostgreSQL (R7UI-S11) | 5ba0a7b4 |

### 1.7 REST contract, SDKs and UI

| Item | Evidence |
|---|---|
| Values with `/` as query parameters on the first routes (UI-DEMO-1, UI-DEMO-5) | b84fb884 |
| **Merging (decision 406):** the full decision 393 move of every `/` or `:` identifier to a query parameter, with the contract guard (decision 400 (1)) | lane R7-ui; no commit at 0c5f5518 |
| UX: 7-group menu, plain labels, routes, errors, page frame, status words, first run (decisions 397, 398, 399) | 90a1ae76 (UI 1076/1076 at the branch tip) |
| **Merging:** `/me/capabilities` and hiding modules that are not installed (decision 399 Q3) | feat/round8-ux 55ee67cd |
| M-047 error-state component tests; Retry on failed first load | 1688b9e6 |
| M-103 OCR review screen and knowledge-base search | c02359c9 |
| Saved inbox filters keyed by a hash; configurable idle sign-out (decisions 337, 349) | 02b4df8d |

### 1.8 Hosts, deployment and operations

| Item | Evidence |
|---|---|
| Quarkus test start-up uses pinned versions offline (decision 402 (1)); host plan (decision 396) | 55902a58, 3f5ea4e5 |
| RM-52 archive gate, archive rewrite after erasure; DEC-225-4 per-tenant delegation tables; RM-30 inbox port | 29c24700, 156b7053 |
| OperatonProcessAdapter split, size gate green (R7UI-SIZE-1) | 50e3a36e |
| Licence: LGPL MariaDB driver optional (RM-38); Jersey EPL-2.0 election (decisions 383, 385 (4)) | 75992108, c5e237d6 |

## 2. Known limitations (target 0.1.1)

Decision 406 moved decision 392, the full decision 393 change and decision 402 (2) **into** 0.1.0 (section 1, merging).
If any of them is not an ancestor of the release sha, it is listed here before the release with target 0.1.1. The
limitations below come from `D:\GITHUB  Project\sangam-handoff\2026-11-17\IFMS-FIT-AUDIT.md` sections 2 and 4.

| Tracking id | Limitation | Target |
|---|---|---|
| E2, C1, C2 | Oracle and SQL Server evidence: `IfmsPacksIT`, `E2AuthorizationIT`, `TenantQuotaIT`, delegation, archive and exact-fraction runs are FAIL or NOT_RUN in the last recorded runs (joint ledger §(1)); results on the release sha are **TO-FILL** | 0.1.1 if not green on the release sha |
| G9 | No dedicated return-and-rework test on IFMS packs | 0.1.1 |
| G12 | No year-end carry-forward test | 0.1.1 |
| G14 | No cancellation test on IFMS packs | 0.1.1 |
| IFMS-PACK-IT | 12 IFMS packs have no end-to-end IT (`IfmsPacksIT` covers 9) | 0.1.1 |
| B30 (DEC-236-2) | DSC return without signing; motor-claim re-sanction | 0.1.1 |
| M-108 | YugabyteDB experimental, NOT_RUN, no support claim | 0.1.1 |
| M-126 | No DMN 1.6 conformance claim | 0.1.1 |
| M-140 | Host-migration procedure has no tests | 0.1.1 |
| M-136 | Capture-to-replay on Oracle and SQL Server not run (H2 only) | 0.1.1 |
| M-015, M-142, M-143, M-150 (B11) | Quarkus host phases partial; DSC gates fail closed on Quarkus (decision 390 (3)) | 0.1.1 |
| M-048, C5 | Playwright first run, including the 10 CERT-In/DPDP browser specs | 0.1.1 |
| C11 (SG-15) | March-peak performance run not done | 0.1.1 |
| C7 | Helm / Kubernetes cluster install not run | 0.1.1 |
| E8, SL-45 | DST timers and `CrossTenantIsolationIT` have PostgreSQL-only evidence | 0.1.1 |
| D11 | Live eSign against the ESP not run (decision 387 (6)) | 0.1.1 |
| R8-UX-404 | Scoped shared saved filters (decision 404 (1)) not coded at 0c5f5518 (feat/round8-ux-404 has no own commits) | 0.1.1 unless merged |
| PACK-CORE-391 | **UNCERTAIN:** 6383c10c changed `sangam-core-powers.dmn` under the existing `sangam.core` 1.0.2, so a database that already holds older 1.0.2 content refuses it (`PACK_VERSION_IMMUTABLE`) | integrator ruling |

## 3. Contract changes since 209d2765

Source: `sangam-rest/api/sangam-api.yaml`, `sangam-rest/api/sangam-api-deprecations.yaml` and the commits named.

| Change | Kind | Evidence |
|---|---|---|
| `GET /me/capabilities` (installed modules) | Additive | feat/round8-ux 55ee67cd (merging) |
| `GET /team/task-groups` behind `team.read` | Additive | feat/round8-ux 6ebdd441 (merging) |
| Scoped saved-filter create and list endpoints (decision 404 (1)) | Additive (coming) | not coded at 0c5f5518 |
| `GET /forms/by-key?key=&version=` serves pack start forms; `422 FORM_AMBIGUOUS` | Additive behaviour; the route move itself is breaking (old path route removed, `sangam-api-deprecations.yaml:805,813`) | c9a2f515, b84fb884 |
| Incident skip/cancel, tenant lifecycle, users and groups routes | Additive | 397455b5 |
| Hosted catalogue routes (NG-17) | Additive | 83ff6bd9 |
| ROUTING-INCIDENT v1 additive keys | Additive | 1a45f546 |
| DEC-145 engine-tenant fail-closed: `TENANT_CONTEXT_MISSING`, `TENANT_CONTEXT_CONFLICT` | Breaking for callers that relied on a default tenant | bc6ba27e |
| REST start refuses a process that is not startable in the tasklist (decision 402 (10)) | Breaking (behaviour) | **UNCERTAIN:** only the UI start catalogue filter is traced (b82faa85, UI-DEMO-7); no REST-side commit found |
| Named refusal for a manifest using a case-type table without `ruleCaseType` (decision 402 (9)) | Breaking (behaviour) | **UNCERTAIN:** packgen enforcement traced (3c2e978b); the run-time refusal is not traced |
| Payee answer key `status` renamed `partyStatus` | Breaking for readers of the old key (none found, 62ee53db) | 62ee53db |
| Decision 393: every `/` or `:` identifier moves from path to query | Breaking | merging (decision 406) |
| Decision 393 (a): SCIM user ids are opaque and server-assigned (RFC 7643 §3.1), no longer the Sangam person id; `SEC_SCIM_RESOURCE.person_id` holds the person or group (changesets `0046-02`, `0046-03`, all five dialects); list `filter` accepts `id`. **Upgrade:** users provisioned before keep their SCIM id (backfilled), but an identity provider that already synced must run one full re-sync, so that users whose person id holds `/` or `:` (unreachable before) are re-created with an opaque id. **Operator note:** an IdP that PATCHes the old id instead of POSTing never triggers that re-key; a user whose old SCIM id contains `/` or `:` must then be removed from SCIM provisioning in the IdP and provisioned again, or the operator deletes its `SEC_SCIM_RESOURCE` row (the person, memberships and postings stay) so the next sync re-creates it | Breaking for IdPs that assumed SCIM id = person id | feat/round8-393mc (this commit) |
| Strict REST Decimal; policy admin API fails closed | Breaking for loose clients | e0080d62, d63b8650 |
| PG_CONFIG table renamed PG_PROGRAMME_CONFIG | Breaking only for direct table readers | 0438ccd9 |

## 4. Supported databases and evidence

Tiers are from `deploy/SUPPORT-MATRIX.md` section 3; "No row is certified yet" stands. Evidence is from the joint
ledger §(1) (r7-it run logs, not a reproducible sha) until the release-sha runs are filled in.

| Database | Tier | Evidence status |
|---|---|---|
| PostgreSQL 17 | 1 | `CrossTenantIsolationIT` 5/5, `IfmsPacksIT` (APPROVE-401 run); release sha **TO-FILL** |
| Oracle Free 23 | 1 | `DecimalVariableIT`, `UnverifiedPaymentVendorIT`, `TenantQuotaIT` PASS; `IfmsPacksIT`, `E2AuthorizationIT` FAIL (before fixes in da2ec50e); release sha **TO-FILL** |
| SQL Server 2022 | 1 | no recorded run in this cycle; **TO-FILL** |
| SQL Server 2025 | 1 candidate (being validated, SG-05) | `DecimalVariableIT`, `UnverifiedPaymentVendorIT` PASS; `IfmsPacksIT`, `E2AuthorizationIT`, `TenantQuotaIT` FAIL (before a4db44ac); release sha **TO-FILL** |
| MySQL 8.4 | 1 | `MysqlUtf8mb4PatchIT` corrected (62ee53db); release sha **TO-FILL** |
| MariaDB 11.4 | 2 | coded, first run in the test phase (NOT_RUN) |
| CockroachDB, YugabyteDB | Experimental | NOT_RUN, no support claim |
| Db2 | Unsupported | none |
| H2 | Development and tests only | H2 suites; counts **TO-FILL** |
