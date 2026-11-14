I have everything needed. Here is the extraction.

# Sangam dependency extraction for the joint-plan revision (read-only)

I edited nothing and sent no messages; I only read git. Where code could settle a claim in a document, I ran read-only git checks in Sangam (`1105d9c`, `15e4135`, and `HANDOFF.md` at `1105d9c`) and in oneifms (main `80dc4184`). Those results are marked **[git]**.

## 0. Sources and version traps

| Alias | Path | Note |
|---|---|---|
| E56 | `D:\GITHUB  Project\IFMS\DOCS\4_ENGINEERING\E-56_Code_Complete_Work_Breakdown_v0.3.md` | v0.3, 945 lines; identical copy in the IFMS worktree |
| E56a | `D:\GITHUB  Project\IFMS\DOCS\4_ENGINEERING\E-56a_Builder_Brief_v0.1.md` | the two copies differ only in line endings |
| REC05 | `D:\GITHUB  Project\IFMS\.claude\worktrees\resets-display-expiry-eeb1fe\DOCS\0_RECORDS\REC-05_Session_Handover_and_Resume.md` | **v1.3 exists only in this worktree.** The copy in the master checkout is v1.2. |
| REC06 | `D:\GITHUB  Project\IFMS\.claude\worktrees\resets-display-expiry-eeb1fe\DOCS\0_RECORDS\REC-06_Session_Hierarchy_and_Lineage_v1.0.md` (REC-06a beside it) | **Not present in the master checkout** |
| E53, E54 | `D:\GITHUB  Project\IFMS\DOCS\4_ENGINEERING\E-53_Sangam_Trial_Test_Plan_v0.3.md`, `D:\GITHUB  Project\IFMS\DOCS\4_ENGINEERING\E-54_Sangam_Trial_Results_v0.1.md` | identical in both places |
| REG | `D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524\sangam\TESTING-GAP-REGISTER.md` | Working copy. **SL-54, SL-55 and SL-56 exist only in the uncommitted diff, not at `1105d9c`** [git]. |
| HS | Sangam `HANDOFF.md` at `1105d9c` | supplementary |

---

## A. Work packages WP-01..WP-15

Planned order (E56 §3, l.434-439) runs in five waves:
1. WP-01 and WP-02.
2. WP-03, WP-04 and WP-13.
3. WP-05..WP-12.
4. WP-14.
5. WP-15.

All code is **UNVERIFIED** (REC05 §4 heading, l.67). Every tip is on GitHub, the local tip equals the remote tip, and every tip is an ancestor of main `80dc4184` (l.69).

| WP | Builds (E56 §3 table, l.456-470) | Sangam dependency | REC05 §4 (l.73-87): tip; state; open | Wave |
|---|---|---|---|---|
| 01 Contracts | All new and revised OpenAPI/AsyncAPI (sanction lifecycle, event catalogues, driver ports, workflow-rules v3 candidate), registry, error codes, common v1/v2, manifests, pack schemas 01..12, generated interfaces. Only package that edits `contracts/**`. | **Indirect.** Owns the WFR contract that Sangam must sit behind (§1.5 l.23): CR-workflow-rules 2.1.0 (#128 l.213); NC-17 ops X/M/S/T/E/R/C (#154 l.239); CF-wfr-v3 "3.0.0 after the trial" (#131 l.216). The GA-55 fix is due "before WP-07 starts" (§6 l.911); it is the same data as Sangam's AT-1 (E54 §2b). | `cc/wp01-contracts` f7c8545. Compiles; WP-07 list delivered; BillPassed in integer paise (d74a452); commitmentId (SD-439). Open: R-WP01-4, R-08 candidates, OQ-215..217. | 1 |
| 02 Kernel | lib-kernel, lib-money (paise), lib-persistence-spi, lib-reliable plus the outbox template, audit-chain, policy-client, service-starter, RabbitMQ adapter, persistence for Oracle/MSSQL/parity, root and parent poms, Jackson 2.22 pin, `security/**`. | **Direct (build).** LIB-JACKSON "Jackson 2.22.x pin … (SG-33)" (#161 l.246). F-04: only WP-02 may add the Sangam dependencyManagement entry or an SCA exception for it (l.445). [git] The parent now manages `jackson-2-bom 2.22.4` (`build/oneifms-parent/pom.xml:24`) and inherits `spring-boot-starter-parent` (l.8). | `cc/wp02-kernel` 3fd91e2. Done; root-pom profiles per WP; 82-module probe BUILD SUCCESS. Open: S-32 real-DB run. | 1 |
| 03 Platform | identity-svc (demo, Keycloak), mdm, config-registry, document-dsc, audit-trail, notify, helpdesk, reporting, BFF, gateway, hosting. | **Indirect.** The PDP decides every workflow action: E-45 B-4 and P-1..P-8 map to WP-03 + WP-04 (§4 l.522, 528-535). CAL-SRC supplies the working-day calendar (#164 l.249; I-3 l.557). DEP-HOST (#169) holds Hosting's SL-18 confirmation against `1105d9c` (P-450; REC05 §8.3 l.136). | `cc/wp03-platform` 07ffae0. mdm 19/19 ops; bff rc.3 27/27; realm claim mappers. Open: gate tests not run. | 2 |
| 04 Workflow | workflow-rules-svc behind the engine-neutral port; Operaton, Flowable exit-path and **Sangam** adapters; lib-workflow-cs/pack (route-pack validator, working-day calculator); kit W-04; threats W-05. Owns `adapters/workflow-sangam/**` and `tests/workflow-conformance/**`. | **Direct.** ADP-WF-SANGAM "generic only, SD-433" (#175 l.260). GT-WF: E-46 WF-1..9, X-1..23, I-1..12 and W-05 "on Operaton, Flowable, Sangam" (#281 l.366; skip register l.886, reason SD-419). 12C-WF is "Sangam Trial 2" (l.518). Depends on WP-02 for SG-33 (l.459). Fold in `wf/trial2-sangam` (§6 l.909). [git] Uses `sangam-engine` in process through `org.sangam:sangam-bom:0.1.0-SNAPSHOT`, imported first (`adapters/workflow-sangam/pom.xml:32-33`). Builds only in profile `wf-sangam` (`core/workflow/pom.xml:38-47`), whose comment reads "Sangam 15e4135 jars come only from the private repo .m2-sangam-15e4135". | `cc/wp04-workflow` 886d72e. workflow-rules 2.2.0, 40/40 ops. REC05 claims "Sangam adapter maps ROUTING_INCIDENT to GATEWAY_NO_MATCH, X-13 updated (SD-445)"; **I could not find this mapping in code (see E1).** 54 in-memory tests green. Open: retest against `1105d9c` (P-444). | 2 |
| 05 Budget | M01 BUD-1/2/3; check-and-stage policy schema; consumes commitment events (SD-439). | None direct. Sends workflow commands only (E-45 B-2/B-3 → WP-04 + WP-05..12; §4 l.520-521). | beeb1d5. Compile gate green (JDK 25); "X-11 closed by b3fb197". | 3 |
| 06 Sanction | M02 lifecycle API, sanction-lookup, commitment register, reserve-on-approval, delegation lookup via the PDP. | None direct (B-2/B-3). | b957708. Compiles with checkstyle OFF. Open: switch checkstyle on and recompile. | 3 |
| 07 Bill | BILL-1: every bill type, claim registry, deductions, return/resubmission; hands bills to treasury (SD-221). | None direct. GA-55 coupling (E54 §2b). [git] Sangam's approve-401 work remaps `gov_ifms.py` `billType>formNumber` (HS l.153). | cbb608e. Compiles, checkstyle on, 53 local gate tests pass; commitmentId stays empty until WP-01 lands it. | 3 |
| 08 CDE | TRS-1/1a, payment CDE-3, settlement CDE-4, cash-gate and file-release-gate. | None direct. Money routes are PRE-P-78 (PK-ROUTES l.347). | bb5076b. Final report 2026-11-08 17:27 UTC. Open: PRE-P-78. | 3 |
| 09 Accounts | ledger-svc, ACC-1..4. | None. | b4d9a0a. Contracts 2.1.0, checkstyle on, exit 0. | 3 |
| 10 People | M07 HR/payroll/ESS/establishment, M08 pension, M09 GPF/NPS/loans. | None direct. Establishment workflows (CAP-06 #20, l.741) go through the WFR port. | 1663ab9. Compiles (PRE-D-16). Open: WP-15 pack data and manifest check. | 3 |
| 11 Receipts/funds | RMS-1/A/2/X, M10 DBT/PFMS, M11 deposits/TSB. | None. | 3f43593. Compiles. Open: SimulatedStatePortal; stale driver-sdk in `~/.m2` (I-39). | 3 |
| 12 Extended | M12 works, M14 cash/debt, M15 stamps, M16 AMS, SAC skeleton, optional modules. | None. | f52f00b. Compiles; SD-443 applied. Open: R-WP01-4, R-WP09-1, SLA re-scan fix, SD-428 check. | 3 |
| 13 Adapters | Driver SDK and kit, every EXT adapter with a simulator, E-44 simulators. Owns `adapters/**` except `adapters/workflow-*` (l.468). | None (workflow adapters are excluded). | 045536e. 25 drivers, 26 simulators. Open: gate tests not run; SD-428 check. | 2 |
| 14 UI | Angular shell and one lazy lib per module, including the workflow inbox (#251 l.336). | Indirect: the inbox goes through BFF and WFR. | 6bfbcfc. npm build. Open: merge check of 8cf84c6. | 4 |
| 15 Packs/E2E | Pack DATA: J-00, RJ, synthetic OD, PK-ROUTES ("SD-434 first set; money routes after P-78", #262), PK-CAL (#266), PK-CONTENT reason codes, deemed action and recall (#265). Also E2E and gates, CI lanes, CT topology. | Indirect. Route packs are engine-neutral and checked by WP-04's validator (F-02 l.443). They overlap Sangam's `gov.ifms` packs (E9). | 518dd12. 19-module compile and test-compile BUILD SUCCESS; 21 offline gates pass. Open: WP-06 R-04/R-10 and year-end scheduler; WP-09 pack and crash harness; WP-10 pack data; WP-07 RJ rows. | 5 |

**Trials (REC05 §5, l.100-104).**
- Trial 2 is `wf/trial2-sangam` at 3d6325e4. It is in main as `trials/workflow-sangam`, root-pom profile `demo` [git `pom.xml:215`].
- Trial 0 is `platform/jackson-2.22` at 1fa14c4f: 53/53 in four lanes.

**Merge order, planned and actual.**
- Planned (E56):
  - Builders "may start against a contract file already committed on `cc/contracts`" (l.452). The real branch name is `cc/wp01-contracts`.
  - DoD 6 says a branch is "never merged to main as done" (l.478).
  - §6 says fold-ins go in "without being merged to main as verified" (l.909).
- Actual (SD-444, REC05 l.91):
  - All 22 branches were fast-forwarded together: main `b4bbbb7` → `80dc4184`, with tag `pre-cc-merge`.
  - The next phase, P-445 (l.93-97), runs in this order: clean-clone full reactor build → SD-428 re-check → test phase on Oracle and SQL Server → promotion.
- Dates: E56 and E56a give no per-package dates.
- E56a has no Sangam-specific rule. Its generic rules still bind the WP-04 adapter: own paths only, paired migrations, idempotent writes with outbox and unknown-status handling, UNVERIFIED tags, "Do NOT run heavy tests, Docker or Testcontainers", and the shared compile lock (§3.1-3.6, l.29-47).

---

## B. IFMS requirements of Sangam, checked against the register

Marks: **C** = a REG row covers the IFMS criterion; **P** = a row touches the topic but not IFMS's criterion, DBs or scale; **N** = no row.

### B1. Architecture and adoption (E56 §1)

| # | Requirement (source) | REG | Mark |
|---|---|---|---|
| 1 | Sangam is only a candidate behind the same WFR port; Operaton is the selected engine; Sangam is adopted only after the frozen E-46 v0.8 gates pass on Oracle and SQL Server (SD-419, SD-422) (l.23) | No E-46 row. SG-04 l.13 IN PROGRESS (matrix PG17, Oracle 23, SS2022, MySQL 8.4); SG-05 l.14 OPEN. [git] Sangam's own E46ConformanceIT and IfmsPacksIT run on PostgreSQL (HS l.155-156). | P |
| 2 | No IFMS-specific code in Sangam; IFMS needs enter only as generic capability or pack data (SD-433); the adapter lives in oneifms (l.24) | SL-18 l.43 "no ifms.dev/vendor-specific code"; IFMS content ships as `gov.ifms` packs (SL-31 l.59, SL-54 l.79, SL-55 l.80) | C (no dedicated row) |
| 3 | Financial state machines stay in the domain, the workflow only sends commands, and the PDP decides authority (E-45 B-2/B-3/B-4) (l.23) | None. Sangam packs carry powers and bands (SL-31, SL-50). | N (see E9) |
| 4 | Integer paise plus currency (SD-398) (l.25; E53 §4 l.138, §5 l.151) | SG-20 l.84 IN PROGRESS; SG-22..25 l.30-33 IN PROGRESS; SG-01 l.10; SL-05 l.50; SL-50 l.57 "amounts in this pack stay in rupees (double)" | C, open |
| 5 | Oracle and SQL Server from day one (SD-97); lanes Oracle Free 23.26.3, SS2025, SS2022 (E53 l.129) | SG-04, SG-05, SG-29 l.82 | C, open |
| 6 | Only WP-02 adds a Sangam dependencyManagement entry or SCA exception (F-04 l.445) | sangam-bom (SG-26 l.34) | C |
| 7 | Jackson 2.22.x line (SG-33) (#161 l.246; l.459) | No SG-33 row. SG-27 l.35 lists jackson-module-scala 2.22.3. [git] `2aaf44f` "SG-33 Jackson guard". | N |

### B2. Build, consumption and versions

| # | Requirement | REG | Mark |
|---|---|---|---|
| 8 | Test only committed SHAs, from a private clone and private Maven repo, never `~/.m2` (E53 §0 l.21; I-38/I-39; E54 l.72) | SG-16 l.25 OPEN | C |
| 9 | Builds from a clean clone (SG-21) | l.29 "FIXED in working tree — file to be committed" (stale: E54 l.44 CLOSED at 15e4135) | C |
| 10 | A release candidate is tagged before acceptance (SG-02) | l.11 IN PROGRESS. [git] oneifms pins `0.1.0-SNAPSHOT`. | C, open |
| 11 | The licence pin reaches consumers; liquibase 4.33.0 (LIC-7) (SG-26) | l.34 FIXED (guard plus `sangam-bom`) | C (but see E4) |
| 12 | FEEL exception under SD-301 with conditions S-F-1..6 (SD-427; E53 §1b l.54-63) | l.35 CLOSED (owner exception); follow-ups → SL-05/06/07 l.50-52 IN PROGRESS | C for S-F-1..4; P for S-F-5; P for S-F-6 (Sangam's own OSV run, but IFMS requires an independent one) |
| 13 | Runtime dependency declared (SG-28) | l.83 FIXED | C |
| 14 | Schema changes only by migration; `databaseSchemaUpdate` off (SG-19; S-C-05) | l.28 "FIXED in code — regression test pending". [git] Since 15e4135 it is a setting, and the factory sets FALSE. | C, open |
| 15 | Oracle baseline installs (SG-29) | l.82 FIXED | C |
| 16 | Plain H2 fails fast, or the requirement is documented (SG-30) | No row. [git] `H2ModeGuardTest` exists at 15e4135. | N |
| 17 | SS2025 declared with a CI lane; JDK 27; server on JDK 25 (SG-05/06/07) | l.14-16; unit-local only, no CI lanes | C, open |

### B3. Engine and capability requirements (E53 §1 l.30-41, §1a l.47-50, §2 l.69-93, §3 l.97-119)

| Id(s) | Requirement | REG | Mark |
|---|---|---|---|
| S-C-01 (T-1, T-6 at 10×) | Port controls: SoD, maker-checker, payee, duplicate, 200 parallel, stale | SL-50 SoD steps l.57 | P |
| S-C-02 (T-3 10,000; T-4 5,000/100) | Working-day SLA and durable timers across restarts | SL-34 l.62, SL-02 l.37, SG-11 l.20, SL-53 l.78 | P |
| S-C-03 (T-7 10,000) | History including Hindi; CAG tamper-evident audit | AuditChain in SG-15 l.24 | P |
| S-C-04 | Restart survival; fail-closed PDP | none | N |
| S-C-05 | Schema by migration only | SG-19 | C |
| S-C-06 | OMG-only BPMN namespace | none | N |
| S-C-07 | Engine clock object | OperatonClock fix l.166; BusinessClock (SL-53) | P |
| S-C-08 / S-W-09 / X-20 | Engine-enforced tenancy | SL-13 l.45; SL-45 l.73 (sweep NOT run); SL-51 l.76 | P |
| S-C-09 / S-W-07 / S-R-3 / X-21 | Child pinned, never latest; unknown pin refuses import; engine binding attributes added on import and removed on export | SG-08(b) l.17; SL-54 l.79 | P |
| S-C-10 / S-R-2 / S-W-03..05 | Export returns the deployed text byte for byte; no rewrite; extensions kept | none | N |
| S-C-11 / T-8 / MG-1..9 | Running-case migration, 1,000 instances | migration fix in SG-03 l.12; UI P05 l.128 | P |
| S-C-12 / S-W-12 | CMMN records "completed" | SG-10 l.19 | C |
| S-C-13 / S-F-5 | DMN bands exact in paise; null/error; dates | SG-27(a)-(c) l.35; SL-05/06/07 | P |
| S-C-14 | Event registry; idempotent inbound | SG-03; FW-28/31/32 l.93-99 | C |
| S-C-15/16 / X-19 | Lease API (fetch-and-lock, extend, unlock, fenced late complete) from Java and one non-Java SDK | SL-16 l.40, FW-07 l.92, SL-08/09/10 | C; non-Java proof absent → P |
| S-C-17 | Ad-hoc sub-process | SL-01 l.36, FW-35 | C |
| S-C-18 | Cockpit, tasklist, modeller, admin | SL-37 l.65; UI parity l.117-164 | C (partly closed) |
| S-C-19 / T-5 | Batch operations; 1,000 failing jobs | GuardedBatchJobHandler l.115; P16 | P |
| S-C-20 / S-W-18 | Apache-2.0; clean SBOM; no unused libraries | SG-26/27 | P |
| S-C-21 / X-15 | Message, signal and timer races | SL-04 l.53 IN PROGRESS | P |
| S-C-22 / T-9/T-10 / I-9/I-10 | Upgrade with running cases; export of running cases | SL-46 l.74 (docs); SL-41 l.69 | P |
| S-C-23 / S-W-19 | Ownership model | SG-18 l.27 DECISION | C |
| S-W-01 | K-1 compensation FK / dead job | SG-08(a) | P |
| S-W-02 / WF-5 / S-R-1 | Refuse unsupported elements before deployment, naming each | none (SangamModelValidator EXT is in HS l.155, not in REG) | N |
| S-W-06 / SD-417 / S-D-430 | Compensation runs in reverse order, sequentially | SG-08(a) l.17 | C |
| S-W-08 / X-13 overlap | Refuse; audit GATEWAY_OVERLAP; case waits | SG-08(c) l.17 (ADR-D-25: AMBIGUOUS_ROUTE incident) | C (semantics differ, E1) |
| S-R-4 / SG-35 | Gateway no-match is an audited incident | none (code is in `0d04fea`) | N |
| S-W-10, S-W-11, S-W-13 / S-D-418 | Boundary-timer history at attach; multi-instance optimistic-lock retry at 2,000; SQL Server intermittent PREPARE refusal (3 clean passes) | none | N |
| S-W-14 | No engine-internal API use | SG-09 l.18 | C |
| S-W-15 | XXE refused | none | N |
| S-W-16 | SECURITY.md and disclosure route | SG-12 l.21 OPEN ([git] the file exists at `1105d9c`) | C (stale) |
| S-W-20 | JDK 27 | SG-06 | C |
| S-W-21 | DST fold; hot-root contention | SG-11; SL-53 OPEN | C, open |
| S-R-5 / S-D-419 / P-419 | Bounded JDBC timeouts; never hang | none | N |
| S-D-423 | PDP p99; 0 PDP_UNAVAILABLE at 10× | none | N |
| S-D-429 | T-4 passes at SMOKE scale (E54 l.25 records **T-4 FAIL**) | none | N |
| S-T-ROUTE/TIMER/STALE/ANON/SELF/MIG/REASON | W-05 threats; WF-T-REASON-1 → 422 REASON_CODE_UNKNOWN | none (SL-55 l.80 related) | N |
| S-A-1..7 | Escalation decision ids, SoD, exactly one of 2,000 races, audit row, planted reuse | none | N |
| E54 l.76 desk positives | tenantCheckEnabled, rejectDoctype, same-deployment binding, script tasks rejected | binding via SG-08(b) | P |

### B4. E53 §4/§5 numbers

The plan requires:
- **Lanes and passes:** 6 lanes (3 DBs × JDK 25 and 27), 3 clean consecutive passes each, at least 2 plants per check.
- **Mutation:** PIT at 70% or more (a recommendation).
- **Security scans:** OSV-Scanner, Semgrep, gitleaks, a licence check and a CycloneDX SBOM.
- **Soak:** 8 h at 20 starts/s, heap growth no more than 10%, no stuck job.
- **Performance:** p50/p95/p99 against the Operaton baseline.
- **Chaos:** JVM kill ×10, DB restart mid-commit, 60 s network cut.
- **IFMS scenarios:** maker-checker-approver, committee quorum, deemed approval, the 31 March peak at 10× starts, and the CAG trail.
- **Exit criteria:** 9,999,900 paise → band A; 10,000,000 → B; 10,000,100 → B/C; 0 unexcepted HIGH/CRITICAL findings; NOT_RUN never counts as PASS.

In REG:
- Lanes map to SG-04..07 (P), and performance to SG-15 l.24 OPEN (P).
- Nothing covers PIT, Semgrep, gitleaks, soak or chaos (N), nor the deemed-approval, year-end or CAG scenarios (N).
- Quorum appears only as a data-size issue (SL-47/48).

### B5. Data contracts

| Item | REG | Mark |
|---|---|---|
| GA-55 bill type (E54 §2b l.101-110; E56 §6 l.911; OQ-215..217 and MD-193 in REC05 l.110) | SL-15 l.39: "AT-1 scenario still uses billType GA-55" | C, open |
| Reason codes (E-45 C-1 l.554; S-T-REASON) | SL-55 OPEN | P |
| IST, DST-free, fiscal year from 1 April (E53 l.62) | SL-53, SL-07, SG-27(c) | P |
| Band edges: IFMS uses ₹99,999 / ₹1,00,000 / ₹1,00,001 = 9,999,900 / 10,000,000 / 10,000,100 paise | Sangam tests 9,999,999 / 10,000,000 / 10,000,001 (l.35) | P (different edges) |

### B6. Id cross-check

- **Ids REG cites:** SD-301 (l.35), SD-398 (l.84), SD-428 (l.71, 92, 103-105), I-45 (l.58, 92) and ADR-D-25 (l.17).
- **Ids REG does not cite:**
  - SD: SD-97, SD-233, SD-317, SD-367, SD-410, SD-412, SD-414, SD-417, SD-419, SD-422, SD-427, SD-432, SD-433, SD-434, SD-441, SD-444, SD-445, SD-446, SD-447.
  - P: P-78, P-283, P-285, P-321, P-331, P-418, P-419, P-423, P-429, P-430, P-432, P-433, P-444, P-445, P-446, P-447, P-450, P-451.
  - OQ and MD: OQ-109, OQ-212, OQ-215..217, MD-169, MD-170, MD-193.
- **SG and SL gaps:**
  - SG-30, SG-33, SG-34 and SG-35 have no row.
  - SG-31 and SG-32 are named only inside SL-19 (l.42).
  - SL-20..SL-29 do not exist in REG.

---

## C. E-54 SG rows: status and retest needed against `1105d9c`

REC05 §5 retest queue (l.102), whose result is to be E-54 v0.2 (P-451):
- X-13 with the WP-04 mapping;
- SG-08a regression;
- SG-34 selftest (`2aaf44f`);
- SG-29 plants;
- SG-28;
- SG-26 and "SD-427 checks 1–5".

| SG | Sev | E54 status (l.) | REG (l.) | Fix location [git] | Retest on 1105d9c |
|---|---|---|---|---|---|
| 01 | HIGH money | OPEN, single (39) | IN PROGRESS (10) | — | Boundary tests 99,999 / 1,00,000 / 1,00,001 in paise, plus PIT on ledger/billing. Run after packs4 (apply step 3). Not queued. |
| 02 | HIGH | OPEN (54) | IN PROGRESS (11) | — | Desk check for a release tag; still 0.1.0-SNAPSHOT. |
| 03 | HIGH | OPEN, single (55) | IN PROGRESS (12) | — | Recount test classes, excluding `handoff/worktree-snapshots` (E3). |
| 04 | HIGH | OPEN (56) | IN PROGRESS (13) | — | Desk check of SUPPORT-MATRIX for certified rows. |
| 05 | HIGH | OPEN (57) | OPEN, "Being validated" (14) | — | SS2025 lanes × JDK 25/27 × 3 passes. |
| 06 | HIGH | OPEN (58) | PASS unit, local (15) | — | JDK 27 on the 3 DBs. |
| 07 | HIGH | OPEN (59) | PASS unit, local (16) | — | Clean-clone build of `sangam-server` on JDK 25; needed for S-C-18 and the REST SDK rows. |
| 08 | HIGH | 08a CLOSED at 15e4135 (H2; DB lanes pending); 08b and 08c OPEN (60) | (a)(b)(c) "FIXED in working tree" (17) | SequentialCompensation, RoutingPolicy and StrictExclusiveGatewayBehavior all at 15e4135 | 08a regression plus DB lanes (queued). 08b: X-21 with plant LATEST_BINDING. 08c: X-13 overlap with plant X13_NO_OVERLAP_GUARD against ADR-D-25 AMBIGUOUS_ROUTE (E1). |
| 09 | MED | OPEN, single (61) | FIXED, pinned (18) | EngineInternalsPinTest at 15e4135 | S-W-14 ArchUnit count. |
| 10 | MED | OPEN (62) | FIXED (19) | at 15e4135 | S-C-12 completed-state test. |
| 11 | MED | OPEN (63) | FIXED (20) | at 15e4135 | DST fold and hot-root at 200 parallel; the engine JVM must be UTC once SL-53(b) lands (E8). |
| 12 | MED | OPEN (64) | OPEN (21) | `SECURITY.md` added in `2aaf44f` | Desk check of disclosure route and support window. |
| 13 | MED | OPEN (65) | DOCS FIXED (22) | — | Desk check of deploy/README §1.5; customer-path Oracle run not run. |
| 14 | MED | OPEN (66) | PARTLY PASS (23) | — | T50 air-gapped and native T01-T04, not run by either side. |
| 15 | MED | OPEN (67) | OPEN, single (24) | T-SCALE `16e0e11`; SL-43 `460c95b` | E53 §4 performance on Oracle and SQL Server, after the T-SCALE re-run. |
| 16 | MED | OPEN, mitigated (68) | OPEN (25) | — | Record the SHA per run; pin by SHA, not by `main` (E2). |
| 17 | LOW | OPEN (69) | DONE (26) | — | Desk check. |
| 18 | LOW | OPEN (70) | DECISION (27) | — | Owner decision only. |
| 19 | HIGH | OPEN, trial overrides to false (53) | FIXED in code, test pending (28) | at 15e4135 | S-C-05: start with schema update off; plant auto-DDL; drop the override. Not queued. |
| 20 | HIGH money | CLOSED at 15e4135 for in-memory; SQL store still to run (52) | IN PROGRESS (84) | — | SQL store on Oracle and SQL Server, after packs4. |
| 21 | HIGH | CLOSED at 15e4135 (44) | "file to be committed" (29) | — | Clean-clone build smoke test. |
| 22/23 | CRIT money | CLOSED at 15e4135 (40-41) | IN PROGRESS (30-31) | — | Regression M-2 and M-3, including the API path. Not queued. |
| 24/25 | HIGH money | CLOSED at 15e4135 (42-43) | IN PROGRESS (32-33) | — | Regression M-5 and M-7. Not queued. |
| 26 | MED | OPEN; "fix … not committed" (50) is stale | FIXED (34) | LiquibaseLicenceGuard and BomPinsTest at 15e4135 | Queued. Prove resolution without the consumer pin (E4). |
| 27 | MED | ACCEPTED WITH CONDITIONS; conditions NOT_RUN (51) | CLOSED (exception) (35) | exception file at 15e4135 | S-F-1..**6**. The queue lists only "checks 1–5" (E15). |
| 28 | LOW | OPEN; "not committed" is stale (49) | FIXED (83) | RuntimeDependenciesDeclaredTest at 15e4135 | Queued. Run with no uuid workaround (dropped in 06fb452e). |
| 29 | HIGH | Installs at 15e4135; plants not run (47) | FIXED (82) | OracleKeyColumnsTest at 15e4135 | Queued: (i) a duplicate key is refused; (ii) two TSTZ values at the same UTC instant are refused. |
| 30 | LOW | OPEN (48) | **no row** | H2ModeGuardTest at 15e4135 | Plain H2 must fail fast. Not queued. |
| 31 | MED | §2a (86) | via SL-19, FIXED (42) | ruleset 26789812 | Desk check of the ruleset; `--no-verify` can only be closed server-side. |
| 32 | LOW | "cannot close" (93) | SL-19: signed approvals, active once the owner adds a key (42) | 35996f9, 3cf78412, 9c831d00 | S1-S5; confirm the owner key is registered. |
| 33 | MED | OPEN, trial pins 2.22.3 (46) | **no row** | "SG-33 Jackson guard" in `2aaf44f` | Oracle SMOKE start on parent 2.22.4 with no trial pin; `trials/workflow-sangam` still pins 2.22.3 (E10). Not queued. |
| 34 | HIGH gov | fix "not yet committed" (94) | **no row** | `2aaf44f` (WIP, "NOT verified, do not merge") | Queued: S6/S7/S8 with throwaway keys; residual (a) delete a CONSUMED record; residual (b) `--no-verify`; run the new selftest against 35996f9 and show it fails there. |
| 35 | MED | OPEN (45; corrected 2026-11-09, l.120) | **no row** | `0d04fea` in 1105d9c only (RoutingIncidentHandler absent at 15e4135); its message says "PostgreSQL proof and the X-13 vs ADR-D-25 owner decision still open" | X-13 on Oracle, SS2025 and SS2022. **The adapter mapping it depends on is missing (E1).** |

**E54 §1 (l.23-31), still outstanding:**
- Only 1 of 6 lanes has run, and only at SS2025/JDK 25 SMOKE.
- T-4 FAILED there (P-429 pattern).
- Oracle was blocked by SG-29; that block is now lifted.
- At SMOKE, X-checks passed 21/23 (X-6 and X-8 NOT_RUN) and WF-checks 10/10.
- MG-checks, the capability matrix and the weakness matrix are all NOT_RUN.
- REC05 l.104: the full matrix is still due (SS2025, SS2022 and Oracle × JDK 25/27 × 3 passes).

**Count mismatches.**
- E54 l.74 says "31 register rows: double 14, single 17; CLOSED 6". The table has **32** rows (l.39-70, SG-35 was added later), and by my count 15 are double-verified. The §2a ids SG-31/32/34 are outside the table.
- E54 l.78 promises to cross-reference SL ids per row. That has not been done.

---

## D. REC-06 coordination facts

- **IFMS master:** `local_dbde6646-ddbf-4df5-bc21-f1c14ed060b7`, session "Session hierarchy और collaboration recall".
  - Transcript d4859ba1, worktree `resets-display-expiry-eeb1fe`, branch `claude/session-hierarchy-collaboration-6deb1f` (REC06 §2 l.81-87; REC05 l.55; SD-446).
  - It holds every former IFMS role: IFMS main `acdd1d5d`; WorkFlow master `ff9f2caf` (only allocator of ids, oneifms integrator, heavy-lock owner); Hosting `3812f9f6`; Business architecture `b47f453a`; and the Trial 0, Trial 2 and WP-01..15 builders as sub-agents.
- **Sangam peer:** "Workspace scan progress resumption", `local_30e74cc4-d0bb-476b-8128-12f9367ffa52` (transcript 6e98c2a7). It is the sole Sangam integrator.
  - It owns `D:\GITHUB  Project\Custom WorkFlow` (`sqamentor/sangam`); its integration tree is `…\.claude\worktrees\workspace-scan-resume-06b524`.
  - Former id `local_eb19d798` (transcript 898f1ff7, 628 turns) (REC06 l.74-75, 88; REC05 l.56).
  - Other peers on the same protocol: PHED `local_046fa70c`, halted 2026-11-04; DOIT `local_227a71a8`, halted 2026-11-05 (l.76).
- **Addressing:** old ids are retired and never reused (l.91; §6 step 4). Every report goes to `local_dbde6646` alone; this replaces the SD-233/SD-317 two-copy rule (l.86-87; REC05 l.55).
- **Protocol:** IFMS E-57 v0.1 (SD-441) and Sangam COORDINATION.md v1.1, harmonised in E-57 §13 (§4 l.109).
  - Typed messages, one topic each: `[REQUEST] [REPORT] [CONTRACT] [INCIDENT] [DECISION-NEEDED]`.
  - Working pattern: Sangam answers per SG id with an SL id and a fix commit.
  - Rules (REC05 §7 l.120): one owner per path; claim before work; contract-first; requests close by commit id; only the master allocates ids; every message is also logged in ledger §5 by its sender.
  - Never stop another owner's process or lock without telling the owner and getting sponsor approval (E-57 §8; REC05 l.50).
- **Shared ledger:** `D:\GITHUB  Project\JOINT-LEDGER-SANGAM-IFMS.md` (SD-447). Sangam proposed it on 2026-11-09 around 02:00 UTC (REC06 §4 l.111).
  - Contents: rules; live sessions (§1); a joint board with one row per cross-project id; the joint plan `JOINT-PLAN-SANGAM-IFMS.md` "to follow"; an append-only decisions and message log (§5); heavy-lock claims (§6); resume pointers.
  - It sits outside both repos, and each side edits only its own rows. Before it, only `HANDOFF-INDEX.md` was shared.
- **Cadence:**
  - Announce a new session id in ledger §1 and to Sangam (REC06 §6 step 4; REC05 §2 step 3).
  - Report every oneifms integration step in the ledger (l.113).
  - After each committed batch, run `refresh_handover.ps1`, then update REC05 (E-57 §10, I-51).
  - Rebuild REC-01 in every turn in which the sponsor spoke.
  - Claim `ifms-integ` in ledger §6 before the full build (REC05 l.94).
- **Heavy lock:** a shared FIFO, `heavy_lock.py` (I-44), in the ff9f2caf scratchpad (REC05 l.61).
  - IFMS owner names: `trial0`, `sangam` (the Trial 2 kit) and `ifms-integ`. Sangam uses `customwf-*`.
  - The queue was empty at 02:00 UTC.
- **Sangam already received:** `[REPORT] COORD-RESUME IFMS→Sangam` (answers to its four questions) and `[REPORT] JOINT-LEDGER ACK IFMS→Sangam`.
- **Sangam's open side (l.113):**
  - SG-35 `0d04fea` (IFMS retest pending);
  - SL-43; FWT-D11/D12;
  - SL-45 (3 of 5 isolation tests failing);
  - SL-53 (UTC JVM);
  - IFMS-WF cross-pack binding (388 calls; apply step 1);
  - MATRIX (SS2022 triage; Oracle, MySQL and SS2025 not run);
  - CI-MAIN sdks lane red because of stale native metadata, "fixed locally". The current Sangam worktree has those files modified and uncommitted.
- **Waiting on Sangam's decision:** `cwf-docs-wt` at 15e4135 holds 11 uncommitted SL-18 files. IFMS did not touch them (§5 l.126). Sangam has judged them superseded, and removal needs the owner's approval (row JL-S-01, REC05 l.133).
- **IFMS waits on:** the SL-18 confirmation against `1105d9c` (P-450). Only the sponsor may create `.ssh\sangam_allowed_signers` (REC05 l.112).

---

## E. Conflicts and risks against Sangam's current state

**E1 [P0] The X-13 retest prerequisite is not in code.** REC05 l.76 says the WP-04 adapter "maps ROUTING_INCIDENT to GATEWAY_NO_MATCH, X-13 updated (SD-445)". I searched for `ROUTING_INCIDENT`, `sangamRouting` and `SD-445` and found none of them in:
- main `80dc4184`;
- `cc/wp04-workflow` `886d72e` (local or origin; its commit touched no adapter file);
- `wf/trial2-sangam` `3d6325e4`;
- any oneifms branch tip;
- any of the 45 oneifms worktrees.

`adapters/workflow-sangam/.../OperatonBpmnPort.java` detects no-match only from exception text, at l.619: `m.contains("ENGINE-02004") || m.contains("No outgoing sequence flow")`. It records GATEWAY_NO_MATCH only in a catch block (l.755-779). Sangam's `0d04fea` instead raises an incident and writes ROUTING_INCIDENT to Sangam's own audit chain, so nothing is thrown. The X-13 retest on `1105d9c` will therefore reproduce SG-35.

The overlap path, isOverlap (l.626), only recognises the adapter's own GatewayGuardParseListener message. Whether that listener or Sangam's strict-XOR behaviour wins on overlap is unverified. Sangam also still lists the "X-13 vs ADR-D-25 owner decision" as open, while IFMS has recorded SD-445 (option a).

**E2 [P0] Sangam version pinning is by convention only.**
- Every consumer pins `0.1.0-SNAPSHOT`: `adapters/workflow-sangam/pom.xml:33`, `trials/workflow-sangam/pom.xml:33`, `trials/sangam-money/pom.xml:12`.
- The only binding to `15e4135` is the private repo `.m2-sangam-15e4135` (`core/workflow/pom.xml:38`).
- Sangam's local `main` is `9c831d00`, 10 commits behind `origin/main` `1105d9c` [git]. Anything that resolves "main" locally gets the wrong commit.
- So the retest needs a fresh clone at the `1105d9c` SHA, a new private repo, updated profile comments, and the SHA recorded per run (SG-16).

**E3 [P1] What `1105d9c` actually contains.**
- 14 `handoff/worktree-snapshots/wt-*` source copies sit outside the 32-module reactor: auth401, ifmswf, packs4, iso, perf and others. There are also stray root files: `CmmnTimerEventListenerH2Test.java`, `PackAcceptanceTest.java`, `gen_openapi.py`.
- E53's whole-tree scans (OSV, Semgrep, gitleaks, licence, SBOM) and SG-03 counts would double-count these unless they are scoped to `sangam/`.
- The SG-33, SG-34 and SECURITY.md work arrived through `2aaf44f`, which is labelled "WIP … NOT verified, do not merge". Sangam's own SD-428 check of that work is not done.

**E4 [P1] SG-26 cannot close the way IFMS defines it.** Both oneifms Sangam modules still pin `liquibase-core 4.33.0` explicitly (`pom.xml:35`, added in 51b78256: "inherited parent management beats the imported sangam-bom"). Meanwhile their comment l.32 says liquibase "must come from the BOM, not from a trial pin". The oneifms parent inherits `spring-boot-starter-parent`, which per REG l.34 manages Liquibase 5.0.4. Without the pin, Sangam's LiquibaseLicenceGuard would refuse to start. The same mechanism defeats BOM-supplied Jackson (E54 SG-33).

**E5 [P1] The apply order versus the retest.** Sangam's strict order (HS §7 l.210-214) is:
1. IFMS-WF `pin_cross_pack_calls`.
2. approve-401 (`ConfigConnector`, `Vars.payloadOf`, `gov_ifms.py` mappings; the 401 cause is "still unknown", HS l.153).
3. packs4: the whole money contract, then IfmsPacksIT on PostgreSQL.
4. SL-35 (which "depends on packs4", HS l.198).

None of these is in `1105d9c`; they live only in worktrees and snapshots. Consequences:
- A retest at `1105d9c` checks the state before any of them is applied, so E-54 v0.2 goes stale as each step lands.
- The SD-398 band checks (E53 §5, S-C-13, S-F-5, SG-01, SG-20 SQL store) should be scheduled after step 3.
- A second retest pin is needed after step 3 or step 4.

**E6 [P1] SL-54 versionTag binding (uncommitted row).** It is safe only on fresh tenants. The IFMS checks that redeploy into the same tenant are T-8 (1,000 running cases), T-10, I-8, I-10, MG-1..9, X-21 "unknown pin refuses import" and S-C-22.
- Each run, and each of the 3 clean repeats, needs a fresh tenant whenever IFMS-WF packs or a SL-41 rollback are involved.
- Sangam says the root fix "must be done before any environment that already holds packs is upgraded". That covers any IFMS demo environment that loads Sangam packs.

**E7 [P1] SL-55 poison loop.** A deterministic 422 REASONS_REQUIRED is retried about every 5 s forever. That conflicts with:
- E56a §3.2: every write has unknown-status handling;
- E-45 I-5, retries of a failing domain call;
- S-T-REASON, which expects a clean refusal with 422 REASON_CODE_UNKNOWN;
- T-5, retry incidents.

Any Trial 2 scenario on `gov.ifms` packs (for example re-appropriation) will spin and burn heavy-lock time. The pack part of the fix lands after apply step 4.

**E8 [P1] SL-53 decision (b): the engine JVM must run in UTC and fail start-up otherwise.** REC05 l.131 and REC06 l.103 record decision (b), but REG l.78 still says "waiting for owner decision". The fix is not in `1105d9c`: `DstFoldTimerH2Test` is absent at both commits.
- The IFMS host runs IST, so once (b) lands the Trial 2 harness and the WP-04/WP-15 topology must start the engine with UTC.
- IFMS's IST and fiscal-year semantics (S-F-5) must then come through `sangam.engine.timer-zone` and BusinessClock.

**E9 [P1] Two sources of IFMS workflow and policy data.**
- Sangam ships `gov.ifms` packs: 388 cross-pack calls; delegated-power tables with UNVERIFIED placeholders (SL-31: budget formulation, supplementary, surrender, scheme approval 100 cr, GPF HOO, advance HOO); bank-payment bands still in rupees (double) (SL-50); and `profiles/in/states/RJ.yaml` (E54 l.103).
- IFMS assigns all route-pack DATA to WP-15 (F-02 l.443; PK-ROUTES; PK-RJ #259). It puts authority in the PDP (E-45 B-4) and money logic in the domain (B-2), and says the State owns state law in settings packs (E56 §1.2).
- The joint plan must name one owner.

**E10 [P1] Plan deviation and two adapters.**
- E56 l.909 and DoD 6 said "rebase, never merge as verified". In fact SD-444 put both `trials/workflow-sangam` (profile `demo`) and `adapters/workflow-sangam` (profile `wf-sangam`) into main.
- Both use the Java package `in.oneifms.trial.sangam`. The differences are 22 extra lines in `SangamWorkflowEngine.java`, and two repro tests (SangamGatewayNoMatchTest, SangamCompensationOrderTest) that exist only in the trial copy.
- The trial still pins jackson-databind/core 2.22.3 (`trials/workflow-sangam/pom.xml:37-38`) against the parent's 2.22.4. E54 SG-33 said to drop that pin once Jackson 2.22 merged.
- For P-445 step 1 (clean-clone full build), turning on `-Pdemo` or `-Pwf-sangam` requires the private Sangam repo.

**E11 [P1] The register lags IFMS records.**
- REG shows SG-22..25 IN PROGRESS and SG-21 "to be committed", where E54 has them CLOSED at 15e4135.
- SG-26/28/29/08/10/11 are "in working tree", although the code is at 15e4135.
- SG-12 is OPEN, although the file exists; SL-53 is "undecided".
- Rows are missing for SG-30, 33, 34 and 35. The SG ids carry two status authorities, E54 and REG.

**E12 [P1] DB coverage gap for adoption.** IFMS needs E-46 v0.8 on Oracle and SQL Server (SD-419/422) and 6 lanes × 3 passes (E53 §4). Sangam proves E46 and IfmsPacksIT on PostgreSQL. Its matrix adds PostgreSQL and MySQL, which IFMS does not use; SS2025 is not run, and Oracle needs a rerun (REC06 l.113).

**E13 [P2] Money semantics.** Until packs4 lands, pack amounts are double rupees and DMN uses Double (SL-50, SL-05). Profiles hold free-text money strings ('2CR', '5L', '1CR'; HS l.156). The two sides also test different band edges (B5).

**E14 [P2] GA-55 ordering.** E56 l.911 requires the GA-55 fix in WP-01 "before WP-07 starts". WP-07 is already code-complete (cbb608e), while OQ-215..217 and MD-193 are still open. Sangam is independently remapping `billType>formNumber` (HS l.153), and AT-1 still uses GA-55 (SL-15). A joint bill-type data contract is needed.

**E15 [P2] Gaps in the retest queue (REC05 l.102 against E53 and E54):**
- S-F-6 (independent OSV) is missing; the queue says "checks 1–5".
- SG-19, SG-21..25, SG-30 and SG-33 regressions are not queued.
- The P-419 hang has no Sangam row; its cause is UNKNOWN (l.103: take two dumps 6 minutes apart next time).
- The T-4 SMOKE failure is in neither the queue nor REG.
- SL-18 confirmation (P-450) runs on a separate track.

**E16 [P2] Heavy-lock contention.** Sangam has queued gated runs: `customwf-ifmswf`, `auth401-it`, `packs4-it`, `sl45-it2`, FwtWorkerCompatIT, IfmsPacksIT, CrossTenantIsolationIT and TScaleLoadIT. IFMS needs the same FIFO lock for `ifms-integ`'s full build plus 18 lane runs and an 8 h soak (E53 §4 l.140). The joint plan must sequence them.

**E17 [P3] Timeline exposure.** E56 gives no dates. Only WP-04 is gated on Sangam, through P-444 and then P-451. Operaton remains the selected engine (E56 l.23), so the IFMS critical path, P-445, does not depend on Sangam unless the sponsor adopts it. The WP-02 Jackson item, listed as OPEN in E56 l.38, is now resolved (parent 2.22.4; Trial 0 lanes 53/53).