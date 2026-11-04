<!-- SPDX-License-Identifier: Apache-2.0 -->
# Requirement traceability register (Stage C cross-verification, 2026-11-05)

**What this is:** one register of every requirement and item id defined in the Sangam specification and research files, matched against what the code says it covers. Each id has a status, the module, file or pack that covers it, and the tests that will prove it.

**What this is not:** test evidence. The phase rule holds: code first, tests last. Every "implemented" below means *a README coverage map says the code does it*. It does not mean the behaviour has been run.

**Files:**
- this file: summary, UNACCOUNTED list, open items, and the full table for the specification families;
- [`TRACEABILITY-REGISTER.csv`](TRACEABILITY-REGISTER.csv): the full table, one row per id (all 1757 ids).

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
- all 33 `sangam/**/README.md` files (module coverage maps, the packs catalogue in `sangam-packs/README.md` §5–7, and `profiles/README.md`);
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

| Family | Total | impl | data | partial | gap | owner | not-repl | spec-closed | test-pending | **UNACC.** |
|---|---|---|---|---|---|---|---|---|---|---|
| Requirements R1–R84 (SANGAM X.1 + PREMIUM) | 84 | 61 |  | 15 | 2 |  |  |  |  | 6 |
| Amendments A-1…A-16 | 16 | 14 |  | 1 | 1 |  |  |  |  |  |
| Decisions ADR-D-1…D-24 | 24 | 14 |  | 4 |  | 6 |  |  |  |  |
| Acceptance criteria AC-01…AC-11 | 11 | 11 |  |  |  |  |  |  |  |  |
| Government items GOV-1…GOV-12 | 12 | 11 |  | 1 |  |  |  |  |  |  |
| Consistency findings CF-* | 17 | 1 |  |  |  |  |  | 16 |  |  |
| Licence items LIC-1…LIC-11 | 11 | 11 |  |  |  |  |  |  |  |  |
| Tests T01–T89 | 89 |  |  |  |  |  |  |  | 89 |  |
| Parked items PK-01…PK-14 | 14 | 1 |  |  |  | 13 |  |  |  |  |
| Government patterns GP-01…GP-55 | 55 | 50 |  | 5 |  |  |  |  |  |  |
| Universal patterns UP-01…UP-22 | 22 | 20 |  | 2 |  |  |  |  |  |  |
| Premium features PF-01…PF-52 | 52 | 36 |  | 12 | 3 |  | 1 |  |  |  |
| Flowable OSS gap list FC-01…FC-59 | 59 | 43 |  | 6 | 3 |  | 7 |  |  |  |
| Flowable Enterprise EC-01…EC-21 | 21 | 16 |  | 1 | 3 |  | 1 |  |  |  |
| Operaton weaknesses D-01…D-56 | 56 | 45 |  | 4 | 5 | 2 |  |  |  |  |
| Flowable weakness audit FW-01…FW-47 | 47 | 8 |  | 1 |  |  |  |  |  | 38 |
| Weakness scenario tests FWT-001…FWT-052 | 52 |  |  |  |  |  |  |  | 21 | 31 |
| IFMS central IFC-001…IFC-258 | 258 | 160 | 84 | 10 | 4 |  |  |  |  |  |
| IFMS states IFS-* (cross-state + state) | 329 | 135 | 193 |  | 1 |  |  |  |  |  |
| IFMS gap areas GA-01…GA-30 | 30 | 14 |  | 15 | 1 |  |  |  |  |  |
| International PFM PFM-* | 79 |  |  |  |  |  |  |  |  | 79 |
| Compliance CMP-001…CMP-153 | 153 | 118 | 22 |  |  | 13 |  |  |  |  |
| Sector workflows SEC-* | 125 | 125 |  |  |  |  |  |  |  |  |
| US health US-HLT-* | 104 | 77 |  |  |  |  |  |  |  | 27 |
| Capabilities N01–N18 | 18 | 9 |  | 7 | 1 |  |  |  |  | 1 |
| Scale tests T-SCALE-1…11 | 11 |  |  |  |  |  |  |  | 11 |  |
| Integration requests IR-1…IR-8 | 8 | 5 |  | 3 |  |  |  |  |  |  |
| **All** | **1757** | **985** | **299** | **87** | **24** | **34** | **9** | **16** | **121** | **182** |

**Accounted:** 1575 of 1757 ids (89.6 %). **UNACCOUNTED:** 182. They are concentrated in four places:
- the whole international PFM set (79);
- the Flowable weakness register (38 FW, and the 31 FWT scenarios that prove only those FW items);
- 27 US-HLT items;
- 7 others (R2, R5, R7, R10, R46, R52 and N11).

## 3. UNACCOUNTED (defined, but in no README)

### 3.1 Requirements and capabilities

| Id | Title | Nearest evidence / why it matters | Tests named in the spec |
|---|---|---|---|
| **R2** | Per-DB acquisition SQL | nearest: sangam-engine db/EngineDialect (A-15 per-dialect lock SQL) + FC-24 SKIP LOCKED config route; R2 not cited | T10, T24, T31 |
| **R5** | JDK baseline | not in any README; found in: SCALE-ARCHITECTURE.md, sangam-control-plane (code) \| JDK 25 LTS baseline: never built or tested (XI.10.4 'Not yet tested: JDK 25'); no README states the JDK baseline or the certified matrix | T28, T31 |
| **R7** | Native-light profile | no native-image profile anywhere | add test |
| **R10** | FEEL engine and null semantics | not in any README; found in: sangam-control-plane (code) \| FEEL is Operaton's feel-scala; strict-null mode not documented; id appears only in sangam-control-plane code | T18, T19, add test |
| **R46** | Modeler/runtime parity (E17) | no modeler/runtime parity check documented (sangam-ui modeller + engine validator exist) | T20, T32, add test |
| **R52** | Audit-ready release process (GOV-3) | deploy/ has SBOM + licence gate + CI, but no README cites R52 (audit-ready release process, GOV-3) | T81, T49 |
| **N11** | Data-pipeline and batch-job orchestration | no data-pipeline / batch-job orchestration module; GP-08/GP-09 batch patterns exist in packs |  |

### 3.2 International PFM: all 79 ids

`INTERNATIONAL-PFM-REQUIREMENTS.md` is not cited by any README, pack.json or profile. That leaves ADR-D-23 ("first jurisdiction packs: India, USA health, EU GDPR, international PFM") only partly delivered.

Generic building blocks exist, but no coverage map ties them to PFM ids:
- `sangam-ledger`: N-stage chain, commitment and encumbrance, and MTEF modelled as later-period accounts;
- `profiles/intl/INTL.yaml`: the COFOG crosswalk;
- `sangam-config`: classification registry;
- the `gov.ifms.*` chains, which are analogues of PFM-CH-1…7.

**Ids:** PFM-001, PFM-002, PFM-003, PFM-004, PFM-005, PFM-006, PFM-007, PFM-008, PFM-009, PFM-010, PFM-011, PFM-020, PFM-021, PFM-022, PFM-023, PFM-024, PFM-025, PFM-026, PFM-027, PFM-028, PFM-029, PFM-030, PFM-031, PFM-032, PFM-033, PFM-034, PFM-035, PFM-036, PFM-037, PFM-038, PFM-039, PFM-040, PFM-041, PFM-042, PFM-043, PFM-044, PFM-045, PFM-046, PFM-047, PFM-048, PFM-049, PFM-050, PFM-051, PFM-052, PFM-053, PFM-054, PFM-055, PFM-056, PFM-057, PFM-058, PFM-059, PFM-060, PFM-061, PFM-062, PFM-063, PFM-064, PFM-065, PFM-066, PFM-067, PFM-068, PFM-069, PFM-070, PFM-071, PFM-072, PFM-073, PFM-074, PFM-075, PFM-076, PFM-077, PFM-078, PFM-079, PFM-080, PFM-CH-1, PFM-CH-2, PFM-CH-3, PFM-CH-4, PFM-CH-5, PFM-CH-6, PFM-CH-7.

### 3.3 Flowable weakness audit: 38 FW and 31 FWT ids

The `sangam-flowable-ports` README has **no FW coverage map**. Only 9 FW ids appear in any README, all in the `sangam-server` PR-1…PR-8 table: FW-02, 07, 16, 28, 29, 32, 39, 40 and 47. FW-28 is partial (PR-8 records an open poison-message gap). The audit's own verdict matrix (§2) gives the fix and file for each FW. That matrix is quoted per id in the CSV `note` column: (a) shared engine issue, (b) not affected, (c) fixed.

**Fix:** add an "FW-01…FW-47" table to `sangam-flowable-ports/README.md`. The FWT scenarios then become accounted automatically.

- **FW:** FW-01, FW-03, FW-04, FW-05, FW-06, FW-08, FW-09, FW-10, FW-11, FW-12, FW-13, FW-14, FW-15, FW-17, FW-18, FW-19, FW-20, FW-21, FW-22, FW-23, FW-24, FW-25, FW-26, FW-27, FW-30, FW-31, FW-33, FW-34, FW-35, FW-36, FW-37, FW-38, FW-41, FW-42, FW-43, FW-44, FW-45, FW-46.
- **FWT** (each proves only unaccounted FW items): FWT-001, FWT-002, FWT-003, FWT-004, FWT-007, FWT-008, FWT-013, FWT-014, FWT-015, FWT-016, FWT-020, FWT-021, FWT-022, FWT-030, FWT-031, FWT-032, FWT-034, FWT-035, FWT-036, FWT-037, FWT-038, FWT-039, FWT-040, FWT-041, FWT-042, FWT-043, FWT-046, FWT-047, FWT-048, FWT-049, FWT-050.

### 3.4 US health: 27 ids

These are not mapped by `sangam-connectors-health`, the `sec.ushealth.*` packs (`covers`) or `sangam-compliance`. There is no US HIPAA jurisdiction pack: the compliance README says one "is added as another pack". Most are HIPAA Privacy and Security Rule controls, information-blocking rules, the CMS-0057-F APIs from 2027, No Surprises Act timelines and EMTALA.

| Id | Title |
|---|---|
| US-HLT-001 | Uses and disclosures of PHI only as permitted (treatment, payment, health-care operations, or with authorisat… |
| US-HLT-007 | Right to amend: act within 60 days, one extension of 30 days |
| US-HLT-008 | Accounting of disclosures: within 60 days, one extension of 30 days; six-year look-back |
| US-HLT-010 | Reproductive-health PHI attestation (2024 rule) |
| US-HLT-011 | Risk analysis and risk management; information-system activity review (audit logs) |
| US-HLT-012 | Unique user identification, emergency access procedure, automatic logoff, encryption (addressable) |
| US-HLT-013 | Audit controls on systems that hold ePHI |
| US-HLT-015 | Security incident procedures: identify, respond, mitigate, document |
| US-HLT-016 | 2025 Security Rule NPRM (mandatory encryption, MFA, 72-hour restoration, annual audit) |
| US-HLT-042 | Tiered civil money penalties by culpability (no knowledge, reasonable cause, wilful neglect corrected within … |
| US-HLT-043 | Restriction of disclosure to a health plan when the individual paid out of pocket in full |
| US-HLT-045 | A practice likely to interfere with access, exchange or use of EHI is information blocking unless it meets an… |
| US-HLT-046 | EHI = ePHI in a designated record set (since 6 Oct 2022, no longer limited to USCDI) |
| US-HLT-049 | Exceptions for how to fulfil: manner (formerly content and manner), fees, licensing; TEFCA manner exception (… |
| US-HLT-050 | Disincentives for providers found by OIG to have committed information blocking (Promoting Interoperability, … |
| US-HLT-061 | Patient Access API gains PA information (excluding drugs) from 1 Jan 2027; usage metrics reported from 1 Jan … |
| US-HLT-062 | Provider Access API from 1 Jan 2027: claims, encounter, USCDI and PA data to in-network providers with a trea… |
| US-HLT-063 | Payer-to-Payer API from 1 Jan 2027: data exchange when a patient changes payer, with patient opt-in; five yea… |
| US-HLT-064 | Response time for Provider Access and Payer-to-Payer requests: within 1 business day of the request (verify: … |
| US-HLT-066 | MIPS / Promoting Interoperability Electronic Prior Authorization attestation measure from CY 2027 |
| US-HLT-067 | API standards: FHIR R4.0.1, US Core, SMART App Launch, Bulk Data, OpenID Connect |
| US-HLT-114 | Plan initial payment or notice of denial within 30 calendar days of the clean bill (out-of-network emergency … |
| US-HLT-115 | Federal IDR: 30-business-day open negotiation; IDR initiation within 4 business days after it ends; certified… |
| US-HLT-116 | Advanced EOB for insured patients (enforcement deferred pending rulemaking) |
| US-HLT-122 | Stabilising treatment or appropriate transfer (receiving hospital agrees, records sent, qualified transport, … |
| US-HLT-123 | Central log of every individual who seeks emergency care; on-call physician list; transfer records kept 5 yea… |
| US-HLT-124 | Receiving hospital reports a suspected inappropriate transfer within 72 hours |

### 3.5 Orphan references (used in a README, defined nowhere)

| Reference | Where | Finding |
|---|---|---|
| `A-17` | `sangam-security/README.md` L58, L69, L152 | **Id collision.** `FLOWABLE-INTO-OPERATON.md` has its own lists A-1…A-18 ("common to both engines") and B-1…B-24 ("Operaton only: what we keep"). A-17 there is the LDAP identity plugin. SANGAM.md amendments are also A-1…A-16, so a bare `A-n` in a README is ambiguous. `B-n` references (for example B-1, B-4, B-11, B-16, B-24) are FIO list B. These two lists are keep-lists, not requirements to build, and are outside the requested families. Proposal: write them as `FIO-A-n` / `FIO-B-n` |
| `IFS-034`, `IFS-037` | `profiles/README.md` (status D) | Not defined in `IFMS-STATES-REQUIREMENTS.md` (the cross-state list skips 034 and 037) or in `IFMS-RESEARCH-DELTA.md`. The profiles summary counts 78 cross-state ids; the source defines 76 |
| `D-0` | `sangam-rest/README.md` (audit) | This is X.2.3 **Rule D-0** (no replay or time-travel claims from class A/C data), not an ADR. No action needed |

### 3.6 Items accounted for only by their nature (no code home)

These are recorded so that nothing is silently dropped.

**CF-1…CF-16, CF-9a** are specification resolutions (status spec-closed). Each row names the id that realises it. Only CF-11 (PR-gate policy) is cited in a README (`deploy`).

**PK-01…PK-14** are parked items (ADR-D-13), status owner-side. The CSV lists the absorbing requirement for each. Only PK-11 (the bpmn.io watermark) is cited in a README (`sangam-ui`).

**ADR decisions without a README citation:**
- realised in the build: D-1 (Operaton 2.2.0 dependency, cited as ADR-D-16), D-6 (= D-8), D-17 (repository LICENSE + SPDX headers), D-19 (parallel workstreams);
- owner-side: D-2, D-3, D-9, D-10, D-11, D-18;
- partial: D-23. India and USA health exist; there is no EU GDPR pack and no PFM coverage.

## 4. Consolidated open items (de-duplicated, ranked)

**Source:** every README section headed Gaps, Known gaps, Not done, Not run yet, Still open or Notes and known gaps; the bold "Still open" and "Gaps" blocks in `sangam-server`; the `sangam-rest` defaults table; the engine and persistence requested core changes; and this register's UNACCOUNTED findings. The raw extraction found 118 lines. After merging duplicates and removing items already fixed (§4.4), **43 items** remain.

**Ranks:**
- **Blocker:** it stops the testing phase, or makes a Tier-1 run fail or give a false result.
- **Must-fix before go-live:** it must close before Stage D sign-off or any production use.
- **Later stage:** Stage D/E by the plan, or an accepted limitation.

### 4.1 Blocker (6)

| # | Item | Detail | Source | Ids |
|---|---|---|---|---|
| 1 | **No behaviour test has run; only H2 compile / fast tests** | Every module states this (phase rule). Multi-DB runs on PostgreSQL, Oracle, SQL Server and MySQL have never been executed for the product modules: engine, persistence, config, ledger, registry, billing JDBC stores. This is the Stage C exit (AC-01…AC-11 on three Tier-1 DBs) | all READMEs; sangam-engine L221; sangam-config L74; sangam-ledger L59; sangam-billing L23 | T01–T89, AC-01…AC-11 |
| 2 | **Liquibase changelogs missing for the `PortsSchema` tables and `PACK_OPERATION`** | Only the dev `PortsSchema.create` builds them, so the ports (event registry, read model, batches, SLA, content) and pack operations have no tables on a non-H2 profile. The 2026-11 schema follow-up columns (EVT_* `state`, PORT_BATCH four-eyes, SLA_INSTANCE `definition_key`, HRM_* indexes) are also pending | sangam-server L264; sangam-flowable-ports L242-252 | FC-02…04, FC-20, D-34, R12 |
| 3 | **Core `AuthorizationPolicy` writes `AUTH_EFFECTIVE` without `cond_key` (NOT NULL in the adopted shape)** | Any path that still uses the core policy fails on insert against the Liquibase schema. Align sangam-core with sangam-security | sangam-persistence L181; sangam-engine L145 | R19, A-3, A-13 |
| 4 | **Operation identity not carried end to end in sangam-core** | `EngineAdapter.ExternalWork` lacks `operationId` / attemptNo / businessKey (today only `LeasedWork`). `OperationLedger.recordIntent` has no overload taking the adapter's T35 operationId, so the ledger can mint a different id. This is the double-payment guard (E3, A-10, A-14) | sangam-engine L141-145 | R36, A-2, A-10, A-14, T35, AC-03 |
| 5 | **Oracle claim path: relay and inbox claims inherit the core `ROWNUM` skip-locked caveat** | Oracle is Tier 1. Concurrent relays can skip or over-claim. Fix before the Oracle runs of E5/E6 | sangam-integration L346 | R2, R4, R38, T06–T09 |
| 6 | **Poison inbound handling is incomplete** | Malformed JSON in a mappable Kafka/JMS message is treated as an infrastructure error and **redelivered forever** (PR-8). Core `ReconciliationQueue.Kind` has no `POISON_INBOUND`, so PR-7 parks with a dead letter but no reconciliation item, and the REST resolve endpoint answers 422 for such rows | sangam-server L281, L293; sangam-integration L336 | R38, T09, T35, FW-28 |

### 4.2 Must-fix before go-live (25)

| # | Item | Detail | Source | Ids |
|---|---|---|---|---|
| 7 | **UNACCOUNTED requirement families (traceability)** | PFM-* (79) has no coverage map (ADR-D-23). The 38 FW ids are not in the ports README. 27 US-HLT items are unmapped. R2, R5 (JDK 25 baseline, never tested: XI.10.4), R7, R10, R46, R52 and N11 have no README entry. Each needs a coverage line, or an explicit gap or decision | this register §3 | see §3 |
| 8 | **R41 idempotency store gaps** | sangam-rest's `IdempotencyFilter` owns R41 (the integration filter is not registered). Its JDBC adapter does **not encrypt the stored response body** (X.2.2.3) and has **no scheduled `cleanup()`** | sangam-rest L104; sangam-server L164 | R41, T55–T60 |
| 9 | **No signed / Merkle audit checkpoints or external export of the audit chain (X.2.3.4 rule 4)** | Recorded by three modules. CERT-In-style audit evidence and R42 tamper evidence depend on it | sangam-security L247; sangam-integration L349; sangam-rest L105 | R42, R51, R52, GOV-3 |
| 10 | **Audit events lost on two paths** | The JavaDelegate connector path runs without the audit chain (ROOT_SEQ self-deadlock avoidance). Compliance denial audits are written in the caller's transaction and vanish on rollback | sangam-integration L343; sangam-compliance L154 | R42, CMP-012 |
| 11 | **Compliance API cannot be authorised by policy** | Resource type `COMPLIANCE` is missing from `ResourceRegistry` | sangam-server L260 | R19, CMP-* |
| 12 | **Compliance ports left at "not configured" defaults** | Principal directory, principal authenticator, personal-data port, adult-identity verifier (no DigiLocker pull/eKYC connector, DPDP r.10), guardianship verifier, processor notifier, geo resolver, national-ID vault and security evidence. No registered Consent Manager integration | sangam-server L207, L261; sangam-compliance L164 | CMP-029, CMP-068…070, CMP-010/011 |
| 13 | **Compliance BPMN: no diagram (DI) layout, and user tasks use candidate groups, not seat routing** | Seat routing is the government core rule (IX.5) | sangam-compliance L163 | IX.5, R82 |
| 14 | **Languages: only English and Hindi ship** | The other 21 Eighth-Schedule notice templates and the form label packs (GA-22) are not generated. The Hindi UI and notice text need Rajbhasha / native review | sangam-compliance L151; sangam-config L77; sangam-ui L749 | GOV-1, R54, GA-22, AC-10 |
| 15 | **Accessibility not verified** | WCAG 2.1 AA / GIGW 3.0 is designed in, but no audit-tool or assistive-technology test has been run | sangam-ui L749 | GOV-2, R55, T32, T88 |
| 16 | **Synthetic pilot values in DMN tables and seeds** | Thresholds, fee tables, power limits, timelines, roles, and sector credit / underwriting grids are synthetic. Sector timelines marked "verify" must be checked against the regulator's text. The departments' delegation-of-powers orders replace them through tenant DMN overrides | sangam-packs L926, L932 | GP-01, GP-06, SEC-*, PK-09 |
| 17 | **Real government wire formats are on request (RQ-1/RQ-3, PK-05)** | IFMS, PFMS, NIC SMS and payment-gateway schemas. Connectors follow the simulator formats. Real adapters must also return the data keys the pack simulators return | sangam-integration L348; sangam-packs L930 | R58, PK-05, PK-08, T89 |
| 18 | **Statutory day arithmetic approximated** | Durations that JUEL cannot express (for example "decide within 40 days of receipt" after a third-party window) are approximated by resetting the case clock. Literal event-subprocess timers are used where no variable exists yet (UP-16 PT4H, UP-19 R/P30D) | sangam-packs L928, L931 | R31, R79, UP-16, UP-19 |
| 19 | **Packs use `WorkingDayCalendar`, not a product-table `BusinessCalendar`** | Tenant holidays and notified calendars do not reach pack SLAs until this is built | sangam-server L265 | R31, GP-06, AC-06 |
| 20 | **REST ports still on defaults** | History read-model query API (`HistoryPort`), SLA aggregate (`InsightsPort`) and pause / resume / four-eyes on `ProductBatchService` (`OpsPort`) stay on their defaults. EventRegistry delete/retire deploys an inactive version, and event-definition delete returns 422. `PackagePort` default has no package signatures or environment promotion (X.2.4.5), and deferred `activateAt` needs a scheduler | sangam-server L263; sangam-rest L106, L113 | R43, R63, R65, FC-02…04, FC-35 |
| 21 | **Worker API long-poll holds a servlet thread for up to 30 s** | At ADR-D-24 load (about 30,000 ops/s) this exhausts the thread pool. Also confirm on `EngineAdapterWorkAdapter` that the implicit `attemptId:complete` replay key (X.2.2.1) is honoured: the default adapter answers `STALE_LEASE` | sangam-rest L101 | R9, R22, ADR-D-24, T-SCALE-2 |
| 22 | **Document malware scan** | The default `DocumentPort` marks content `NOT_SCANNED`. Verify that the scan hook (R74) is active on the server's `ContentService` bridge. Uploads through the REST controllers are still buffered (`MultipartFile.getBytes()`) | sangam-rest L108; sangam-server L292 | R74, FW-39, FW-40 |
| 23 | **SCALE cell items still open** | IR-6: the replica router exists, but no adapter uses it. IR-4: Kafka/JMS consumers are not paused on tenant freeze, and content-store and audit-export files are not moved with the rows. IR-3/IR-4: `XCELL` / `CELL_ADMIN` service principals need a tenant claim (401 today) | sangam-server L253, L266-268 | IR-3, IR-4, IR-6, T-SCALE-8 |
| 24 | **SDKs: no in-memory test engine (R18 Tier-1 requirement) and no contract tests T01–T04 / T18** | All five SDKs. The .NET SDK is compile-checked on net7.0 only (out of support); build on net8 | sdks/*/README Gaps | R18, AC-08, T01–T04, T18 |
| 25 | **Field offline queue** | The PWA does not implement the encrypted offline queue contract (`LocalQueueContract`). It uses the online `record` call, so offline capture (N05, GA-27 offline units) is not yet real | sangam-field L109 | N05, GA-27 |
| 26 | **Billing numbering on retry** | Invoice and receipt numbers are drawn again on a retried step: gaps in a government receipt series. Postings stay idempotent. Numbering per operationId is the fix | sangam-billing L23 | N09, N10, R36 |
| 27 | **Break-glass accounts not a separate mechanism (AJ-05 step 7)** | Proposed: a time-limited API token plus an alert on use | sangam-security L245 | AJ-05, R50 |
| 28 | **US health connector safety items** | CDS Hooks caller JWT verification is left to the REST layer and not written. There is no FHIR profile validation (US Core, PAS). The SMART client JWKS publishing endpoint is operational setup (owner-side) | sangam-connectors-health L78, L83-84 | US-HLT-074, US-HLT-077 |
| 29 | **Compliance pack data marked [U]/[S]** | UIDAI circulars, CERT-In audit and BOM guidelines, GIGW language clauses, NISPG and state mandates. State obligations are configuration hooks only | sangam-compliance L157 | CMP-*, PK-10 |
| 30 | **IFMS profile placeholders** | 4 IFC gaps (IFC-001 registry seed of 94 units, IFC-189, IFC-238, IFC-244) and IFS-077. 15 GA partials: State values in [U] skeletons, LMMH seed partial, pension and procurement rules not loaded, sub-state entities. The "Still placeholders" list covers GJ/WB/TG/OD/ML/JK/LA code structures, works manuals and SNA-SPARSH dates | profiles/README.md L675 | IFC-*, IFS-*, GA-* |
| 31 | **DNS pinning leaves a short rebinding window** | The JDK `HttpClient` cannot connect to a pre-resolved IP with SNI (SSRF hardening, R27) | sangam-integration L344 | R27, T23, T47 |

### 4.3 Later stage (12)

| # | Item | Detail | Source | Ids |
|---|---|---|---|---|
| 32 | **Engine source patches (Stage D, with upstream PRs)** | D-19 `ExternalTask.xml` SKIP LOCKED mapping, D-10 per-engine clock SPI (one engine per JVM until then; also blocks FC-34), FC-24 job hand-off and timer acquisition, D-27, D-52, D-23 MI profiling, D-48. Interim extension-point routes are in place | sangam-engine L216; sangam-flowable-ports L285 | D-10, D-19, D-23, D-27, D-48, D-52, FC-24, FC-34 |
| 33 | **R39 partition epochs blocked by A-6** | Partitioned executors are off by default | sangam-engine L220 | R39, A-6, T41 |
| 34 | **OFFERED / ack lease state (VI.3.1 stream offers)** | REST-first (ADR-D-5): a fetch returns LOCKED work. It applies only when gRPC streaming is added | sangam-engine L218 | R9, CF-3 |
| 35 | **Flowable-port remainders** | FC-56 state-machine extensions and FC-57 (P-4), FC-11/FC-19 native CMMN (P-5): Stage E. FC-18 historic-instance migration (Operaton history is bound to the definition). FC-51 CockroachDB and EC-06 data objects: Stage D/E. EC-03 Pages, EC-14 Agents, EC-15 Engage: Stage E | sangam-flowable-ports L283-286 | FC-11, FC-18, FC-19, FC-51, FC-56, FC-57, EC-03, EC-06, EC-14, EC-15 |
| 36 | **Premium screens waiting for server endpoints ("UI ready; server [P]")** | PF-13 metrics, PF-22 Git sync, PF-27/39 connectors/registry, PF-28/46 agentic AI, PF-35 pages, PF-38 data objects, PF-40 SLA models, PF-45 inspect, PF-47 engage. PF-25/26 console and backups (R70, Stage D) and PF-31 IDP (R75, Stage E) have no screen | sangam-ui L247-292, L744-746 | PF-*, R69, R70, R72, R75, R77, R78, R81 |
| 37 | **Identity breadth** | SCIM 2.0 provisioning (R67, Stage D). SAML 2.0 / Parichay is IdP (Keycloak) configuration only; `IDP_CONFIGURED` metadata management is not implemented | sangam-security L242-243 | R67, AJ-05, PK-04 |
| 38 | **R82 assignment strategies (round-robin, least-loaded, skills)** | Stage D. Seat resolution and candidate exclusion exist; packs carry their own `AssignmentStrategyDelegate` | sangam-security L251; sangam-packs §7 | R82, UP-04 |
| 39 | **Connector breadth** | FC-08 Camel. GraphQL, JDBC, S3 and chat connectors (R71 breadth, Stage E). X12 TR3 situational rules, the 277CA decoder, 999 IK3/IK4 detail and the CAQH CORE SOAP/MIME envelope. Bulk Data `$export` polling | sangam-integration L349; sangam-connectors-health L78-80 | FC-08, R71 |
| 40 | **Performance refinements** | No native spatial indexes on Oracle, SQL Server or MySQL. Field-mask checks cost one object check per row. Capacity sampling is per node. Ledger stripe rebalancing. Roll-up micro-batch pre-aggregation and the state-level tier | sangam-geo L93; sangam-security L248; sangam-integration L345; sangam-ledger L57; sangam-rollup | R22, R19, ADR-D-24 |
| 41 | **Functional limits accepted for now** | ETA and nearest-N by straight line (road routing is out of scope). EXIF read from JPEG only. Antimeridian geometries must be pre-split. Overlap is a boolean intersect. No fuzzy duplicate matching for references. Programme: no ISO weeks, allocation one level at a time, alert de-dup keys permanent. Ledger has no dedicated MTEF check. `ProfileResolver.remove` has no per-field removal. No field re-encryption job. Python long-poll cannot be aborted. PHP inline mode cannot pre-empt a handler. SDK admin bodies are untyped. Java `ExternalWork.effect` is not in the contract. CMMN editing is not graphical. There is no collaborative modeller presence (R69). `processEngineName` is overridden by the starter | sangam-field, sangam-geo, sangam-case, sangam-programme, sangam-ledger, sangam-config, sangam-security, sdks/*, sangam-ui, sangam-server L262 | N13, N16, N17, R69 |
| 42 | **EU GDPR jurisdiction pack** | ADR-D-23 lists it, but there is no pack. The compliance framework supports adding one as data | sangam-compliance L13 | ADR-D-23 |
| 43 | **Unfenced Flowable-compat opt-in reads core `LeaseTable` (`spike_lease`), not `SANGAM_LEASE`** | Fails closed (`STALE_LEASE`), so it is safe, but the opt-in path is unusable with the sangam-engine adapter | sangam-server L294 | FW-07, D-17 |

### 4.4 README statements already superseded (do not count as open)

| Older statement | Superseded by |
|---|---|
| sangam-compliance L161-162: "Purpose-binding and retention hooks into engine execution are not wired yet" | sangam-server L216-219: `bridge/CompliancePurposePlugin` wires the purpose binding, `retention.track` and `onPurposeCompleted` |
| sangam-server L291: "Downloads are still buffered" | sangam-server L336 (final integration wave): `Download` carries a body supplier and streams; content is cut off above `max-bytes` |
| sangam-case L107: venue booking and the party registry are pass-through until N01/N14 exist | sangam-server L323: `CaseModule` uses venue booking via sangam-resources and parties via sangam-registry |
| sangam-rest L95: `DynamicPort` / `EventPort` / `LinkPort` / `DocumentPort` default gaps (501, in-memory links, no channels) | sangam-server L169-181: bridged to sangam-flowable-ports (`AdHocSubProcessService`, `EventRegistry`, `InstanceLinkService`, `ContentService`); "no more 501" |
| sangam-rest L102-103: `PermissionPort` / `AdminPort` defaults (no field masks, in-memory admin) | sangam-server L147-153: replaced by sangam-security bridges |
| sangam-engine L141 job identity and job counters | sangam-server L262: "Done: job identity binding … job counters" |
| sangam-rest L86: "Nothing is kept in memory" vs the table row "`OpsPort` batches are held in memory" and "`PackagePort` `InMemoryPackageAdapter`" | Contradiction inside one README: the prose says packages persist in `PACKAGE_VERSION` and batches in `PORT_BATCH`. Correct the table (open item: README accuracy) |

## 5. Full table: specification families (SANGAM.md)

Every R, A, ADR-D, AC, GOV, CF, LIC, PK, N, IR and T-SCALE id is listed here. The bulk families (GP, UP, PF, FC, EC, D, FW, FWT, IFC, IFS, GA, PFM, CMP, SEC, US-HLT, T) are in §6 by exception and in full in the CSV.

### Requirements R1–R84 (SANGAM X.1 + PREMIUM)

| Id | Title | Stage | Status | Where (module:file / pack) | Tests | Note |
|---|---|---|---|---|---|---|
| R1 | Relay-driven wake-up | C | implemented | sangam-integration:OutboxRelayService.java; sangam-integration:WakeupNotifier.java | T06, T07, T08, T25, T31, T45 |  |
| R2 | Per-DB acquisition SQL | C | **UNACCOUNTED** |  | T10, T24, T31 | nearest: sangam-engine db/EngineDialect (A-15 per-dialect lock SQL) + FC-24 SKIP LOCKED config route; R2 not cited |
| R3 | Lock and transaction specification | C | implemented | sangam-engine:OperatonEngineAdapter.java; sangam-integration:RetryPolicy.java | T10, T33, T34 |  |
| R4 | Outbox algorithm | C | implemented | sangam-cell:RollupPublisher.java; sangam-flowable-ports:OutboundEventService.java; sangam-integration:OutboxRelayService.java; sangam-persistence:sangam-0001-ba | T06, T07, T08, T25, T35 |  |
| R5 | JDK baseline | B (minimal) | **UNACCOUNTED** |  | T28, T31 | not in any README; found in: SCALE-ARCHITECTURE.md, sangam-control-plane (code) \| JDK 25 LTS baseline: never built or tested (XI.10.4 'Not yet tested: JDK 25'); |
| R6 | Script isolation | C (secure defaults) → D (full) | implemented | sangam-engine:SangamBpmnParseListener.java; sangam-flowable-ports | T23, add test |  |
| R7 | Native-light profile | B (Phase 0 item) | **UNACCOUNTED** |  | add test | no native-image profile anywhere |
| R8 | DMN engine scope | C | implemented | pack universal.up14-three-way-match | T18, T19, T20 |  |
| R9 | Worker task lease lifecycle | B (proof) → C | implemented | sangam-engine:OperatonEngineAdapter.java; sangam-engine:SangamLeaseTable.java; sangam-persistence:sangam-0001-baseline.sql; sdks/dotnet:SangamWorker.cs; sdks/ja | T02, T03, T04, T36 |  |
| R10 | FEEL engine and null semantics | C | **UNACCOUNTED** |  | T18, T19, add test | not in any README; found in: sangam-control-plane (code) \| FEEL is Operaton's feel-scala; strict-null mode not documented; id appears only in sangam-control-pla |
| R11 | JSON limits | C → D | implemented | sangam-cell:MiniJson.java; sangam-engine:JsonValueSerializer.java | T18, T23 |  |
| R12 | Schema migration | B (skeleton) → C | implemented | deploy; sangam-control-plane:SchemaConfig.java; sangam-engine:SangamEngineConfigurer.java; sangam-flowable-ports:PortsSchema.java; sangam-persistence; sangam-pe | T28, T29 |  |
| R13 | Telemetry | B (skeleton) → C | implemented | deploy; sangam-engine:JobMetricsInterceptor.java; sangam-engine:CountingFailedJobCommandFactory.java; sangam-rest; sangam-server:ObservabilityConfig.java; sdks/ | T24, T31, T49 |  |
| R14 | AI / MCP tools | E | implemented | pack universal.up06-hitl-ai-review | T12, T13, T14, T15, T16, T17, T23, add test |  |
| R15 | Process-mining export | E | implemented | sangam-rest | add test |  |
| R16 | Contract-driven schemas | C | implemented | sdks/dotnet:Models.cs; sdks/php:CloudEvent.php; sdks/php:AdminApi.php; sdks/python:models.py | T48 |  |
| R17 | Database tiers | B (skeleton) → C | implemented | deploy; sangam-server:application-*.yaml | T10, T26, T28, T29, T31 |  |
| R18 | SDK tiers | C | partial | sdks/dotnet; sdks/java; sdks/php; sdks/php:SangamClient.php; sdks/php:SangamWorker.php; sdks/python; sdks/python:client.py; sdks/typescript; +1 more | T01, T02, T03, T04, T18, T28 | covered, with an open item \| sdks/dotnet L216: - No in-memory test engine yet (R18 Tier-1 "test engine"); contract tests T01–T04/T18 come in the testing phase. |
| R19 | Authorization | B (spike on both engines) → C | implemented | sangam-case:CaseFileService.java; sangam-case:CaseRestAdapter.java; sangam-flowable-ports:TenantDefinitionResolver.java; sangam-gateway:TenantResolver.java; san | T13, T14, T15, T16, T17, T37, T38 | explicit |
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
| R39 | Partition epochs (E09) | E (blocked by A-6) | gap | sangam-engine | T41, T24 | sangam-engine L220: - Partition epochs (R39): blocked by A-6. |
| R40 | Outbox capacity (E10) | C | implemented | sangam-control-plane:PlacementService.java; sangam-integration:OutboxCapacityMonitor.java; sangam-integration:InboxService.java; pack universal.up01-stp-order;  | T25, add test, T09, T35 |  |
| R41 | API idempotency (E12) | B (proof) → C | partial | sangam-cell:CrossCellPublisher.java; sangam-field:SyncService.java; sangam-field:FieldRestPort.java; sangam-integration:IdempotencyService.java; sangam-ledger:L | T01, add test | sangam-rest L105: idempotency response body not encrypted (X.2.2.3), no scheduled cleanup(); sangam-rest owns R41 (server L164) |
| R42 | Audit vs replay (E13) | C (audit part); replay E | partial | sangam-control-plane:ControlPlaneAudit.java; sangam-engine:SangamHistoryEventHandler.java; sangam-field:FieldAudit.java; sangam-flowable-ports:TaskLifecycleServ | T42, T19, T30, add test | Merkle/signed audit checkpoints (X.2.3.4 rule 4) missing: sangam-security L81, sangam-integration L61, sangam-rest L106 |
| R43 | Executable-package versioning (E14) | C | partial | sangam-case:CaseConfigRegistry.java; sangam-flowable-ports:EventRegistry.java; sangam-flowable-ports:DynamicInjectionService.java; sangam-flowable-ports:AppBund | T19, T21, T28 | sangam-rest L113: PackagePort default has no signatures or environment promotion (X.2.4.5) \| covered, with an open item \| sangam-flowable-ports L231: \| Runtime  |
| R44 | Standards profile (E15) | Not mapped in VIII.9 (finding 9) | implemented | sangam-engine:SangamBpmnParseListener.java; sangam-flowable-ports:SangamModelValidator.java; sangam-rest | T20 |  |
| R45 | Bounded CMMN conversion (E16) | E (isolation in B/C) | implemented | pack universal.up09-dynamic-case; pack universal.up18-capa | T43, T21 |  |
| R46 | Modeler/runtime parity (E17) | E | **UNACCOUNTED** |  | T20, T32, add test | no modeler/runtime parity check documented (sangam-ui modeller + engine validator exist) |
| R47 | Forms contract (E19) | C | implemented | sangam-flowable-ports:FormDefinition.java; sangam-packs:PackDeployer.java; sangam-persistence; sangam-persistence:sangam-0001-baseline.sql; sangam-rest:sangam-a | T16, T19, T32, add test |  |
| R48 | Migration contracts (E20) | D (pilot subset may move to C); importers E | implemented | sangam-flowable-ports:MigrationDocument.java; sangam-rest:sangam-api.yaml; sangam-rest; sangam-ui | T21, T27, T28, add test |  |
| R49 | Continue-as-new and modification limits (E21) | D | implemented | sangam-flowable-ports:DynamicInjectionService.java; sangam-flowable-ports:CrossInstanceStateChangeService.java; sangam-rest:sangam-api.yaml; sangam-rest; pack u | T44 |  |
| R50 | Identity / SSO (GOV-6) | C | implemented | deploy; sangam-registry:RegistryPorts.java; sangam-rest; sangam-security; sangam-server:SecurityConfig.java; sangam-ui:AuthProvider.tsx | T87 |  |
| R51 | Log retention and incident readiness (GOV-3) | C (Stage D gate item) | implemented | deploy | T81, T49 |  |
| R52 | Audit-ready release process (GOV-3) | D | **UNACCOUNTED** |  | T81, T49 | deploy/ has SBOM + licence gate + CI, but no README cites R52 (audit-ready release process, GOV-3) |
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
| R65 | Process analytics and BI | D (core) → E (advanced) | implemented | sangam-flowable-ports:TaskLifecycleService.java; sangam-flowable-ports; sangam-rest:sangam-api.yaml; sangam-rest; sangam-security:AuthorizedQueryBuilder.java; s | PackDeploymentTest+AllPacksSmokeTest (deploy/first wait) |  |
| R66 | In-product process mining | E | implemented | sangam-rest:sangam-api.yaml; sangam-rest; sangam-ui; pack universal.up07-improvement-loop | PackDeploymentTest+AllPacksSmokeTest (deploy/first wait) |  |
| R67 | Identity federation | C (OIDC, LDAP) → D (SCIM) | partial | sangam-rest; sangam-security; sangam-security:KeycloakClaimsMapper.java; sangam-security:PrincipalResolver.java; sangam-security:LdapDirectorySync.java; sangam- | PackDeploymentTest+AllPacksSmokeTest (deploy/first wait) | covered, with an open item \| sangam-security L69: \| identity/LdapDirectorySync.java \| R67 / A-17: LDAP/AD read and scheduled sync (JDK JNDI, no new dependency). |
| R68 | Published support lifecycle | D | implemented | sangam-ui |  |  |
| R69 | Collaborative web modeller | C (BPMN/DMN/forms, single-use… | partial | sangam-rest; sangam-ui |  | covered, with an open item \| sangam-rest L95: Without the owning module, these return 501 CAPABILITY_NOT_INSTALLED: directory sync (sangam-security), Git pull/p |
| R70 | Platform console and lifecycle | D | gap | sangam-ui |  | sangam-ui L271: \| PF-25, PF-26 \| Console, backups \| Not in WS-UI scope (R70, Stage D) \| Gap \| |
| R71 | Connector catalogue and service registry | C (REST, e-mail, SFTP, webhoo… | partial | sangam-connectors-health; sangam-flowable-ports:ChannelAdapters.java; sangam-flowable-ports; sangam-geo:WfsConnector.java; sangam-integration:SangamConnector.ja | PackDeploymentTest+AllPacksSmokeTest (deploy/first wait) | covered, with an open item \| sangam-integration L349: - Not implemented: FC-08 Camel (stage E); GraphQL, JDBC, S3 and chat connectors from R71 (stage E breadth) |
| R72 | AI-agent orchestration | E | partial | sangam-flowable-ports:SangamBpmnParse.java; sangam-flowable-ports; sangam-rest; sangam-ui; pack universal.up06-hitl-ai-review | PackDeploymentTest+AllPacksSmokeTest (deploy/first wait) | covered, with an open item \| sangam-flowable-ports L184: \| EC-14 \| Agents / AI \| Stage E (R72); prerequisite FC-01 Done; owner proposed WS-PACKS/WS-INTEGRATION  |
| R73 | RPA worker | E | implemented | sangam-integration:RetryPolicy.java; sangam-packs:EmbeddedWorkerRunner.java; pack universal.up20-rpa | PackDeploymentTest+AllPacksSmokeTest (deploy/first wait) |  |
| R74 | Document service | C (store + references) → D (g… | implemented | sangam-flowable-ports:FileSystemDocumentStore.java; sangam-integration:LifecyclePorts.java; sangam-integration; sangam-rest:sangam-api.yaml; sangam-rest; sangam | PackDeploymentTest+AllPacksSmokeTest (deploy/first wait) |  |
| R75 | Intelligent document processing | E | partial | sangam-ui; pack universal.up06-hitl-ai-review | PackDeploymentTest+AllPacksSmokeTest (deploy/first wait) | covered, with an open item \| sangam-ui L276: \| PF-31 \| IDP (document processing) \| — \| Gap (R75, Stage E) \| |
| R76 | Universal work UI | C (inbox and task) → E (case … | implemented | sangam-rest; sangam-ui; pack universal.up09-dynamic-case; pack universal.up22-external-collaboration | PackDeploymentTest+AllPacksSmokeTest (deploy/first wait) |  |
| R77 | Low-code pages | E | partial | sangam-flowable-ports; sangam-rest; sangam-ui |  | covered, with an open item \| sangam-flowable-ports L173: \| EC-03 \| Pages (low code) \| Owned: WS-UI (R77), stage E \| |
| R78 | Data objects and data dictionary | D → E | partial | sangam-flowable-ports; sangam-registry; sangam-resources:ResourceType.java; sangam-rest; sangam-ui |  | covered, with an open item \| sangam-flowable-ports L176: \| EC-06 \| Data objects / ERD (R78) \| Gap for stage C: stage D/E, not routed to WS-PORTS; integrator to  |
| R79 | SLA model | C (subset for AC-06) → D | implemented | sangam-case:DeadlineService.java; sangam-engine:TaggedDurationBusinessCalendar.java; sangam-flowable-ports:SlaService.java; sangam-packs:SlaDelegate.java; sanga | PackDeploymentTest+AllPacksSmokeTest (deploy/first wait) |  |
| R80 | Model test harness and debugger | D | implemented | sangam-flowable-ports:DebuggerService.java; sangam-rest; sangam-ui |  |  |
| R81 | Conversational and messaging channels | E | partial | sangam-flowable-ports; sangam-rest; sangam-ui |  | covered, with an open item \| sangam-flowable-ports L185: \| EC-15 \| Engage \| Owned: WS-INTEGRATION (R81), stage E \| |
| R82 | Routing and assignment strategies | D | partial | sangam-field:DispatchService.java; sangam-geo:Nearest.java; sangam-packs:AssignmentStrategyDelegate.java; sangam-rest; sangam-security; sangam-ui; pack universa | PackDeploymentTest+AllPacksSmokeTest (deploy/first wait) | covered, with an open item \| sangam-security L251: - R82 strategies (round-robin, least-loaded, skills) are not implemented. Only seat resolution and candidate  |
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
| A-6 | Contrast 4.5:1 for text and 3:1 for UI components; reflow at 320 CSS px; text resizable to |  | gap | sangam-engine |  | sangam-engine L220: - Partition epochs (R39): blocked by A-6. |
| A-7 | : L1 = any row in EXCLUSIVE_LEASE, for both execution and resource: modes (VI.6). The lock |  | implemented | sangam-engine:OperatonEngineAdapter.java; sangam-engine:ExclusiveLeaseTable.java; sangam-persistence; sangam-persistence:sangam-0001-baseline.sql |  |  |
| A-8 | : the default capability class is (c) "neither": no automatic repeat after lease expiry; t |  | implemented | sangam-engine:CapabilityClassExpiryPolicy.java; sangam-engine; sangam-integration:SangamConnector.java; sangam-integration:ConnectorInvoker.java; sangam-integra |  |  |
| A-9 | : for government records, R61 legal retention takes precedence over erasure (DPDP s.17(4)  |  | implemented | sangam-flowable-ports:TaskEnhancementService.java |  |  |
| A-10 | Lease-expiry hook. Engine-native lease expiry (inside fetch, job execution or the engine's |  | partial | sangam-engine:OperatonEngineAdapter.java; sangam-engine:CapabilityClassExpiryPolicy.java; sangam-engine; sangam-integration; sangam-integration:LedgerExpiryPoli |  | covered, with an open item \| sangam-rest L101: \| WorkPort \| OperatonWorkAdapter: Operaton external tasks + core LeaseTable fencing. Switches to EngineAdapterWor |
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
| ADR-D-5 | REST first (X.0) |  | partial | sangam-engine; sangam-rest:sangam-api.yaml; sangam-server:pom.xml; sangam-server:OpenApiController.java |  | covered, with an open item \| sangam-engine L218: - OFFERED/ack state (VI.3.1 stream offers): REST-first (ADR-D-5) — a fetch returns LOCKED work; OFFERED belongs |
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
| ADR-D-21 | Cell-based deployment with a central control plane (X.0e) |  | implemented | sangam-cell:CellId.java |  |  |
| ADR-D-22 | Boundary: workflow + rules + generic ledgers/master data; integrate with IFMS/EHR/core ban |  | partial | sangam-connectors-health; sangam-ledger; sangam-ledger:LedgerPorts.java |  | covered, with an open item \| sangam-ledger L4: Owner: IFMS-IMPLEMENTATION workstream (generic module, ADR-D-20 / ADR-D-22). Base package org.sangam.ledger. Gove |
| ADR-D-23 | First jurisdiction packs: India, USA health, EU GDPR, international PFM (X.0e) |  | partial | IN: profiles/ + sangam-compliance IN pack; USA health: sec.ushealth.* packs + sangam-connectors-health; EU GDPR: no pack; international PFM: no coverage map |  | decision item: GDPR pack and PFM coverage missing |
| ADR-D-24 | Load target: India national (~3,000 starts/s, ~30,000 ops/s, ~1M users) (X.0e) |  | implemented | perf; sangam-ledger:JdbcLedgerStore.java |  |  |

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
| GOV-3 | Security audit |  | partial | sangam-packs | T81, T49 | realised through R51=implemented, R52=UNACCOUNTED |
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
| CF-10 | R50–R62 have no tests; IX.3 "Hindi/English UI" has no spec reference |  | spec-closed | SANGAM.md X.1a / X.5.1; realised by R50=implemented, R51=implemented, R52=UNACCOUNTED, R53=implemented, R54=partial, R55=implemented, R56=implemented, R57=imple | T87, T81, T49, T86, T32, T88, T50, T80, T02, T27 | consistency finding resolved in the specification |
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
| N11 | Data-pipeline and batch-job orchestration |  | **UNACCOUNTED** |  |  | no data-pipeline / batch-job orchestration module; GP-08/GP-09 batch patterns exist in packs |
| N12 | IoT / telemetry-triggered processes |  | implemented | sangam-field |  |  |
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
| IR-3 | Roll-up and cross-cell publishers (sangam-server IntegrationWiring, WS-INTEGRATION). |  | partial | sangam-cell; sangam-server; sangam-server:XCELL |  | sangam-server L267: XCELL / CELL_ADMIN service principals need a tenant claim (401 today) |
| IR-4 | Tenant-admin API on the cell (WS-REST + WS-INTEGRATION + WS-ENGINE). |  | partial | sangam-cell; sangam-control-plane; sangam-server; sangam-server:XCELL; sangam-server:ContentService |  | sangam-server L268: Kafka/JMS consumers not paused on freeze; content-store and audit export files not moved |
| IR-5 | History "outbox-only" per definition (WS-ENGINE/WS-INTEGRATION). |  | implemented | sangam-cell; sangam-engine:OutboxOnlyDbHistoryEventHandler.java; sangam-engine |  |  |
| IR-6 | Read-replica routing (sangam-server/WS-REST). |  | partial | sangam-cell; sangam-server; sangam-server:CellReadReplicaRouter.routing() |  | sangam-server L253: router built, 'Still open: no adapter uses it yet' |
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
| FC-24 | FC | partial | sangam-engine:SangamProcessEnginePlugin.java; sangam-engine; sangam-flowable-ports:FilteredAcquireJobsCmd.java; sangam-f | explicit(mixed) \| sangam-engine L120: \| FC-24 job executor \| config route done (SKIP LOCKED, timers first, priorities); hand-off / separate timer acquisition  |
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
| D-10 | D | gap | sangam-control-plane; sangam-engine:SangamEngineConfigurer.java; sangam-engine:OperatonClock.java; sangam-engine; sangam | explicit \| sangam-engine L92: \| D-10 global ClockUtil \| contained: OperatonClock, one engine per JVM (per-engine clock SPI = Stage D patch) \| |
| D-16 | D | owner-side | sangam-engine; sangam-flowable-ports | explicit \| sangam-flowable-ports L204: \| D-16 \| Organisational (integrator: patch ledgers + upstream offers, both ledgers) \| |
| D-19 | D | partial | sangam-engine:OperatonEngineAdapter.java; sangam-engine:ExternalTask.xml; sangam-engine; sangam-flowable-ports; sangam-i | explicit \| sangam-engine L97: \| D-19 concurrent fetch \| interim W-9 route (fetch lock + OLE listener); the ExternalTask.xml SKIP LOCKED patch is not d |
| D-23 | D | gap | sangam-engine:SangamBpmnParseListener.java; sangam-engine; sangam-flowable-ports:MultiInstanceAggregation.java; sangam-f | explicit \| sangam-engine L99: \| D-23 large MI \| deploy + runtime guard (engine profiling = Stage D) \| |
| D-27 | D | gap | sangam-engine; sangam-flowable-ports | explicit \| sangam-flowable-ports L207: \| D-27 \| Owned: WS-ENGINE (stage D engine patch) \| |
| D-40 | D | partial | sangam-engine; sangam-flowable-ports; sangam-integration:WakeupNotifier.java; sangam-integration:WakeupNotifier; sdks/do | covered, with an open item \| sdks/python L126: Flowable gap ids: FC-36, FC-37, D-17, D-18, D-40, D-44. The structure follows the Flowable external-worker |
| D-44 | D | partial | sangam-engine; sangam-flowable-ports; sdks/dotnet; sdks/java:SangamWorker.java; sdks/java; sdks/php:SangamClient.php; sd | covered, with an open item \| sdks/python L126: Flowable gap ids: FC-36, FC-37, D-17, D-18, D-40, D-44. The structure follows the Flowable external-worker |
| D-48 | D | gap | sangam-engine; sangam-flowable-ports | explicit \| sangam-flowable-ports L220: \| D-48, D-49 \| Owned: WS-ENGINE (D-48 stage D profiling) \| |
| D-49 | D | partial | sangam-engine:PerDefinitionHistoryLevel.java; sangam-engine:PerDefinitionHistoryLevel; sangam-flowable-ports | explicit(mixed) \| sangam-flowable-ports L220: \| D-48, D-49 \| Owned: WS-ENGINE (D-48 stage D profiling) \| |
| D-50 | D | owner-side | sangam-engine; sangam-flowable-ports | explicit \| sangam-flowable-ports L221: \| D-50 \| Organisational / WS-DEPLOY \| |
| D-52 | D | gap | sangam-engine; sangam-flowable-ports:SangamModelValidator.java; sangam-flowable-ports | explicit \| sangam-flowable-ports L223: \| D-52 \| Validator rejects (here); runtime support = stage E engine patches (WS-ENGINE) \| |
| FW-28 | FW | partial | sangam-server:PortsEventBridge; sangam-server:sangam-integration; sangam-server:KafkaInboxConsumer | covered, with an open item \| sangam-server L281: \| PR-8 (FW-28) \| inbox/KafkaInboxConsumer, inbox/JmsInboxListener \| Reviewed, not changed. Both commit/ack the broker on  |
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
| GA-02 | GA | partial | sangam-config:ClassificationScheme.java; sangam-packs/src/main/resources/profiles | explicit \| sangam-packs/src/main/resources/profiles L643: \| GA-02 \| classification registry + per-state schemes + seeds \| - \| P \| LMMH seed partial; per-state master code |
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

## 7. Regenerating

The register was produced by two scratch scripts, kept outside the repository and run read-only over it: an extraction/matching script and a writer. To refresh it after README changes, re-run the extraction against the same sources. The hand-set statuses (IR-3/4/6, R41/42/43) and the decision-item classifications in §3.6 must be re-checked by reading the cited README lines.
