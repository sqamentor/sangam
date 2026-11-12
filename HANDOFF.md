# Sangam — session handoff (2026-11-08, written by the Sangam owner session)

**For whoever resumes (new login, new session):** read this file completely before doing anything.
It is the single source of truth for where the work stands. Nothing here is an approval. Approvals come only from the owner, in their own words, in their own session.

**Updated 2026-11-12 (docs wave, second pass after the readiness report).** §3, §6.0, §6c, §8 and §10 give the current state. §6a, §6b and §7 are the 2026-11-08 record, kept for history; where they disagree with §6.0, §6.0 and `sangam/TESTING-GAP-REGISTER.md` win.

**Continuity rule.** Open every Sangam session at `<repo>` (the `Custom WorkFlow` checkout itself), never at a worktree or another folder: the owner's auto-memory is keyed to that path, so a session opened elsewhere starts without it. Work then happens in the integration tree under it (§3).

- Repo: `<repo>` (the local checkout; its path contains two spaces). GitHub `sqamentor/sangam`.
- Durable backup of everything: `<handoff>\2026-11-08\` (see §9).
- **Path placeholders** (no local absolute paths in this file): `<repo>` is the local checkout; `<handoff>` is the `sangam-handoff` folder next to it; `<tools>` is the `.tools` folder next to it; `<lock-scratchpad>` is the IFMS master session's scratchpad that holds `heavy_lock.py`; `<home>` is the owner's user profile; `<temp>` is the user temp folder. The exact local paths are in the owner's auto-memory (`feedback-shared-heavy-lock.md`) and in `<handoff>\RESUME-LEDGER-2026-11-09.md`, outside the repository.
- Previous session id: `898f1ff7-0dcc-4d95-b425-830a9a5ead44` (desktop app, Code tab).

---

## 1. Goal and owner rules (do not deviate)

**Goal.** Sangam is an Apache-2.0, universal, generic, plug-and-play workflow platform, far better than Flowable and Operaton.
- It is built on **Operaton 2.2.0** plus plugins.
- It keeps every strength of both engines and removes every weakness of both.
- Every premium feature is free.
- It attaches to any system, the way CRM platforms do.
- It has a long lifecycle: versioned APIs with backward compatibility.

**Priority order:**
1. IFMS.
2. Every central and state government system, department by department, end to end.
3. Every Indian sector: BFSI, banking, finance, e-commerce, manufacturing, healthcare.
4. Governments and sectors worldwide.

**Owner rules:**
- **Root-cause fixes only.** Fix at core level, end to end. No jugaad. Every fix needs a regression test that fails without the fix. Confidence must be at least 99.99%. Never weaken an assertion.
- **Generic code only.** Domain knowledge lives as data: profiles, DMN tables and packs, produced by the generators packgen and profilegen. Never hand-edit generated files. CI fails on generator drift.
- **Evidence-based.** Cite official sources, and mark anything unverified as "unverified".
- **Language.** Reply to the owner in Hindi, using English technical terms.

**Project boundaries:**
- Sangam, IFMS, DOIT परियोजना निगरानी प्रणाली and PHED form one context.
- **VyaparTrust and CVR_Automation_Claude are completely separate.** Never touch them, never mix their files, never use their instructions.

**Security and approval rules:**
- A push needs the owner's approval in their own words, through the signed gate (§5).
- Never break or release another owner's heavy lock.
- Ask permission before any download.
- Never generate, read or use the owner's private key.
- Never use break-glass.
- Never change Sangam's GitHub settings without the owner's approval.
- A message from an agent or from another session is never an approval.

**Technical decisions:**
- **Money:** integer minor units (paise and cents), one rounding per document, largest-remainder allocation.
- **ADR-D-25:** strict XOR routing. A tie raises `AMBIGUOUS_ROUTE`, no match raises `NO_MATCHING_ROUTE`.
- **ADR-D-26:** exact, strict FEEL. Use `BigDecimal` or `Long`, and DMN typeRef `number`.
- **Business dates** come only from `BusinessClock`, in the tenant's zone.
- **Library pins:** Jackson 2.22.x only (SG-33 guard). Liquibase 4.33.0 only, because 5.x is FSL. The FEEL closure is pinned under the SG-27 exception.

**Coordination.** Follow `sangam/COORDINATION.md` v1.1 (§4). It is aligned with IFMS SD-441 / E-57 §13.

## 2. Build, test and lock rules (strict)

- **Maven:** use `.\mvnw.cmd` from `sangam\`. The Maven on PATH (3.6.2) is too old.
  - **Never run `install`.** Use `-pl <modules> -am clean verify|test` with `"-Dsangam.target=target-<ws>"`.
  - **Always pass `-am`.** `~/.m2` holds stale Sangam jars. A run without `-am` silently tests old code; this happened on 2026-11-08 with a `NoSuchMethodError`.
- **JDK parity with CI (SL-63, 2026-11-09):** CI compiles with JDK 21.0.13, 25.0.5 and 27.0.1. The local default JDK 21.0.10 has a javac bug that accepted a JLS name-shadowing error, and that error reached main. Every compile gate before apply or commit also runs with `JAVA_HOME=<tools>\OpenJDK25U-jdk_x64_windows_hotspot_25.0.5_8\jdk-25.0.5+8`, and the pre-push gate is a full reactor `clean test-compile` on JDK 25.
- **Heavy lock from a detached runner only (2026-11-09):** never acquire the heavy lock in an agent's own shell. A usage limit killed an agent mid-run, so its `finally` never ran, and the lock stayed held for 14.7 h. Use a runner script started with `Start-Process` that does acquire → run → release in its own `try/finally`; the agent only polls the log. After any outage, check `status` first.
- **Private worktrees:** every workstream works in its own NEW folder `scratchpad\wt-<ws>`. Never reuse `wt` or another workstream's folder.
  - **When copying, exclude only build outputs** (`target-*` and the module-root `target/`). `sangam-programme/src/main/java/org/sangam/programme/target` is SOURCE code. Losing it broke two workstreams on 2026-11-08.
- **Applying to the main tree:** apply in one step, and only when `-pl sangam-server -am -DskipTests compile` and the tests are green on a fresh re-copy of main. A shared file is changed only by a targeted Edit on the CURRENT main-tree file, never by copying a worktree file over it.
- **Heavy lock (I-44/I-45):**
  - **Command:** `python "<lock-scratchpad>\heavy_lock.py" acquire <owner> "<what>"`. A backup copy is in the handoff folder (`<handoff>\2026-11-08\heavy_lock.py.copy`).
  - **Gating:** Testcontainers and failsafe runs start ONLY if the acquire exits 0; otherwise abort. Release in `finally`.
  - **Queue:** first come, first served, with tickets in `heavy-build.lock.queue/`. `status` shows the holder and the waiting tickets; tickets already served are not listed.
  - **Other rules:** run one Maven job at a time per workstream. Start each acquire fresh, right before its step, and never reuse an old waiter. Each job uses its own unique owner name (`customwf-<ws>`). The IFMS owners are `sangam` and `trial0`.
- **Acceptance suite:**
  - **Location and profile:** tests live in `sangam-server/src/test/java/org/sangam/server/acceptance/**`. Run them with failsafe profile `sangam-db-it` and `-Dsangam.db=postgres|oracle|sqlserver|sqlserver2025|mysql`.
  - **Auth:** OIDC test issuer `AcceptanceIdp`.
  - **Model files:** use the `.model`/`.tpl` suffix. No `*.bpmn`, `*.dmn` or `*.cmmn` under the server test resources.
- **Generators:**
  - Packs: `sangam-packs/tools/packgen`, run with `--check`.
  - Profiles: `profilegen`.
  - Changelogs: `sangam-persistence/tools/generate_changelogs.py`, five dialects.
  - OpenAPI: `sangam-rest/api/gen_openapi.py`. API changes are additive only, and `OpenApiCompatibilityTest` must stay green.
- **Tool quirks:** `ssh-keygen` from the Bash tool fails with 0xC0000142. Use PowerShell, or the Windows OpenSSH `ssh-keygen.exe` (`System32\OpenSSH`).

## 3. Git and GitHub state

**Current (2026-11-12):**
- **Remote main** is `1105d9c` (2026-11-09). It includes the push `15e4135..16e0e11` the owner signed on 2026-11-09 (`push-approval-main8.txt`, JOINT-LEDGER §4).
- **Integration tree:** `.claude\worktrees\workspace-scan-resume-06b524`, branch `claude/workspace-scan-resume-06b524`, HEAD `1105d9c`. All code-complete work sits there **uncommitted** (about 1,900 changed or new paths in `git status`). Nothing is committed, staged or pushed by the agents.
- **Main CI on `1105d9c`:** main does not compile on the CI JDKs 25.0.5 and 27.0.1 (SL-63; the fix is in the tree). The other main-CI fixes are in the tree too (SL-64 ShellCheck, CI-NVD-OIDC, SL-75 P1, SL-106); none is proven until the first CI run after the push.
- **Change summary for the push:** `sangam/CHANGES-SINCE-1105d9c.md`.

**At the 2026-11-08 handoff (historical):**
- **Local `main`** is 4 commits ahead of `origin/main`. These are not pushed:
  - `4bd5a2e`: push gate
  - `35996f9`: owner-signed approvals
  - `3cf7841`: owner key in `.githooks/allowed_signers`
  - `9c831d0`: `.gitattributes`, LF for governance files
- **Remote main** is still `15e4135`.
- **WIP snapshot commit:** all main-tree work (559 paths) is committed as **`2aaf44f`** on the local branch **`handoff/2026-11-08`**, on top of `9c831d0`. It is not pushed and is NOT verified: do not merge it as is. The working tree is checked out on that branch.
  - The next push goes through §8: a verified commit on a branch, then the signed gate.
  - The private-worktree work (§6b) is NOT in this commit; it is backed up under §9 `scratchpad\`.
- A backup patch of the same content is in §9.
- **GitHub ruleset:** `protect-main` (id 26789812) blocks non-fast-forward pushes and deletion, with no bypass and no PR rule. Adding required status checks or a PR rule needs the owner's decision, made after main CI is green.
- **Main CI:** on `15e4135` it was red (build, sdks, security, ui). The fixes are in the uncommitted tree and are unproven until a CI run.

## 4. Coordination with IFMS

- **WorkFlow** is the IFMS master. Session `local_ff9f2caf-1246-4228-ae70-82a16cf209ae`, name "WorkFlow". It owns oneifms and the heavy lock.
- **IFMS main** session: `local_acdd1d5d…`, name "IFMS". Copy every report to it.
- Sync Hosting, DOIT and PHED when relevant.
- **Message format:** `[TYPE] <ID> <from>→<to> <subject>`, where TYPE is REQUEST, REPORT, CONTRACT, INCIDENT or DECISION-NEEDED.
  - If two messages cross, both sides stop and the owner of the ID restates the current state.
  - Verify every claim at its source (lock status, git, GitHub API).
- **Definition of done** includes the **SD-428 independent second check**:
  - an SG item is closed by the IFMS Trial 2 agent re-testing the commit;
  - an SL item is closed by a Sangam reviewer agent that did not write the fix.
  - Until then the item is "single-verified (OPEN)".
- **Sync** after every committed batch, to WorkFlow and IFMS main: done (commit ids), next, blocked, requests.
- **Hosting requirements (SL-18):** done in the tree. Report them to WorkFlow and Hosting with the batch.

## 5. Push procedure (owner-signed gate, SG-34 hardened)

- **Hook:** `core.hooksPath=.githooks`. The pre-push hook runs `tools/check-ignored-sources.sh` and `node tools/governance/push-approval.mjs check`.
- **Trust root:** the owner-held `<home>\.ssh\sangam_allowed_signers`. It was created by the owner on 2026-11-08 and verified to match `.githooks/allowed_signers` byte for byte: no BOM, fingerprint `SHA256:GVyGHB4GMD5muUTFctICsYNr0IDfunf7b3LsHXo7d1o`. The `SANGAM_ALLOWED_SIGNERS` environment variable is ignored.

**Steps for each push:**
1. Commit on a branch. If needed, open a PR. Fast-forward only.
2. Generate the text to sign: `node tools/governance/push-approval.mjs text --sha <sha> --remote <exact remote URL> --ref refs/heads/main --words "<owner's words>" > a.txt`. It expires in 30 minutes or less.
3. **The owner signs it personally:** `ssh-keygen -Y sign -f "$env:USERPROFILE\.ssh\sangam_push_owner" -n sangam-push a.txt`
4. Record the signature: `node tools/governance/push-approval.mjs record --signed a.txt --sig a.txt.sig --conversation <id>`
5. Push. The hook re-verifies the signature at push time.

The selftest `node tools/governance/selftest-push-approval.mjs` passes 31/31 under PowerShell and Git Bash. It takes about 4 minutes.

## 6. Status of every workstream at handoff

"In tree" means applied to the main tree and uncommitted. "SV" means single-verified, still OPEN until the SD-428 check. Worktree folders are under `scratchpad\`, and the backup copy is under `<handoff>\scratchpad\`.

### 6.0 Current state (2026-11-12)

- **Phase.** The owner's order of 2026-11-12 is: write all code end to end with unit tests, then update the docs, then push to `main`, then test stage by stage (unit, system, integration, acceptance), then the end-to-end report (`<handoff>\CODE-COMPLETE-PLAN-2026-11-12.md`, JOINT-LEDGER §4). Code and docs run in parallel now; the test phase starts after the push.
- **Readiness report.** `<handoff>\SANGAM-READINESS-REPORT-2026-11-12.md` (integrator) combines audits A (pending items), B (plug-and-play), C (UI) and D (continuity) in the same folder. Its findings: the code is **not** all written yet (list below); nothing has run on this tree; nothing was lost in the account switch, but the integration tree holds about 1,900 uncommitted changes, which is the single real risk (safety snapshot `<handoff>\snapshots\20261112-0923`).
- **Release scope (owner decision 2026-11-12, relayed by the integrator).** 0.1.0 contains every **known** item: the open register rows plus all the plug-and-play gaps and UI gaps of the readiness report (sections 3 and 4), coded and fixed in the first release. They are listed in `SANGAM.md` XII.9 and are being planned or coded; the integrator keeps the detailed backlog. 0.2 takes only items not yet known or recorded. This supersedes an earlier draft that moved the plug-and-play and UI gaps to 0.2+.
- **Master backlog and waves.** The detailed backlog is `<handoff>\BACKLOG-0.1.0-MASTER.md` (items M-001…M-136 in five waves, lanes and file owners per wave, owner questions Q-01…); `SANGAM.md` XII.10 points to it. Wave 1 is checkpointed in commit `0e53f91b` (code complete, tests written, not run). **Wave 2** is being coded from that commit under `<handoff>\WAVE2-RULES-2026-11-12.md` (which keeps every Wave 1 rule) and the carry list `<handoff>\WAVE2-CARRY-2026-11-12.md`, in lanes W2-A…W2-N with one owner per file. Owner decisions for Wave 2: English only for 0.1.0 (Q-06, SL-147, `SANGAM.md` XII.9); a first-class private ingress (Q-09); Ed25519 signing keys per purpose and environment (Q-04); the React UI ADR (Q-17, ADR-D-29, `SANGAM.md` X.0j). Later owner questions use the backlog's Q-numbers; the older Q-numbers in this file stay as they were recorded.
- **Coded today (B11, tests written, not run):** SL-125 (a missing fiscal year fails closed core-wide, `FISCAL_YEAR_NOT_CONFIGURED`), SL-126 (UI number-series field names `reset` and `fiscalStartMonth`) and SL-127 (billing refuses a missing period without a fiscal year).
- **Code still to write** (readiness report §1): SL-102 (typed money thresholds), SL-78 and SL-79 (test-code fixes; a re-run alone does not close them), SG-15 (round-trip reduction; throughput never measured) — these four are being coded now; SL-58 (SQL Server 2025 / JDK 25 failure, root cause unknown); SG-39 (after the owner's route decision); later SL-53a (after Q34); and the 0.1.0 full-scope backlog above.
- **What "done" means here.** An item is coded in the integration tree with its unit tests written, and it passes the compile-only gate (`-DskipTests test-compile`). **No new test has been run.** The register rows say "coded, tests written, not yet run" or "FIXED in tree … PENDING (test phase)"; none of them is closed by this phase.
- **Where each item stands:** `sangam/TESTING-GAP-REGISTER.md` (owned by the integrator; nobody else edits it). The list of ids fixed in the tree is in `sangam/CHANGES-SINCE-1105d9c.md` §5.
- **Owner decisions recorded on 2026-11-12** (JOINT-LEDGER §4, verbatim): Q7 "Accept leaf pool (Recommended)"; Q8+Q9 "Confirm both (Recommended)"; Q10 "Yes (a), keep key (b) (Recommended)"; Q22 "Skip unit tests in DB legs (Recommended)"; modules without an ADR "Record one ADR (Recommended)" (now ADR-D-28, SANGAM.md X.0i); Db2 "Drop from Tier 2 (Recommended)" (SL-114); J-P1-E09 SoD seat "Ask IFMS for the rule (Recommended)"; CI dispatch after any push of more than 300 files "Yes, dispatch all (Recommended)" (standing approval, Q16).
- **Still open with the owner** (CODE-COMPLETE-PLAN "Owner decisions that block code"; audit A): Q12 (downloads), Q13, Q14, Q19, Q21, Q23, Q24, Q26, Q34 (the SL-53a date), the SG-39 route and the SL-07 semantics. Also owner actions: SG-12 (Q17, GitHub private vulnerability reporting), CI-NVD-OIDC (store the NVD parameter value), SL-75 P2 (removing `handoff/worktree-snapshots/**`).
- **Test phase only** (after the push, per the plan): the SG-04 matrix rerun, SG-07, SG-13, SL-01, SL-12, SL-34, the SL-37 residuals, the SD-428 reviews (including FWT-D11/D12), the database proofs for SL-84/88/93/94, IX8/AC-07, T-SCALE and the SL-58 investigation run. SL-78 and SL-79 run here once their test-code fixes land.

### 6a. Done, in tree (needs the SD-428 check, then commit) — record of 2026-11-08

| ID | What | Evidence |
|---|---|---|
| CI (5 jobs) and SG-33 | compose duplicate key; php phpunit; dotnet `global.json`; python test extra; ts pack directory; OWASP 13.0.0 with suppressions (expiry 2027-05-31, needs human review); ui `--run`; `JacksonCompatibilityGuard` plus `jackson-bom` 2.22.3 in `sangam-bom` | local repros; the real proof is a CI run |
| SG-34 | push gate hardening (§5) | selftest 31/31 |
| SL-12 and the FWT ports fixes | 15 defects in flowable-ports, plus 10 FWT fixes | 265 tests; SD-428 review VERIFIED all 10 |
| SL-13 / SL-14 | `TenantIsolationGuard` (cross-tenant calls); onboarding org levels | `CrossTenantCallH2Test`, `OrgLevelsTest`, `OnboardingOrgLevelsH2Test` |
| SL-16 / SL-17 | worker ledger (`WorkLedger`, `CompleteRequest.outcome`); LIVE HTTP connectors | PostgreSQL: `WorkApiLedgerIT` 3/3, `E3LeaseIT` 9/9, `OperationLedgerIT` 6/6, `TombstonesIT` 3/3, `E7DeleteIT` 3/3 |
| SL-38/46, 40, 41, 42 | API lifecycle and compatibility gate; exact formulas and validation; custom fields; pack uninstall and rollback; sandbox promotion | unit and H2 tests |
| SL-43 / SL-39 | per-tenant quotas (`org.sangam.core.quota`, `JdbcTenantQuotas`, `SEC_TENANT_QUOTA_WINDOW`); webhooks (HMAC, jitter, idempotency key, auto-disable) | PostgreSQL: `TenantQuotaIT` 4/4, `WebhookRelayIT` 3/3, `OutboxRelayIT` 6/6 |
| SL-50 | owner decision 2026-11-08, "हाँ, रखो": the checker's concurrence is capped at the authoriser's band. Also fixed: checker and authoriser now have separate SoD step ids (`core.py`) | `BankPaymentConcurrenceTest` 6/6 |
| SL-30/31/32/34/47 | pension powers; IFMS powers (6 of 8 are placeholders); `PackAcceptanceTest` runner; working-day calendar; JSON size | 73 packs tests |
| SL-37 | UI `/engine-rest` role gate; `GET /api/v1/tasks/{id}/form`; `OperatonClock` slot release | `EngineRestRoleGateTest`, `TaskFormEndpointTest`, `OperatonClockRegistrationTest` |
| SL-51 / SL-52 | engine-rest refusals (429/413/403, `TenantIsolationException`); deterministic connector timeout test | `EngineRestTenantRefusalTest` 4/4 (red 3/4 without the fix); integration suite 17/17 three times |
| UI parity, round 1 | 44 PASS and 7 FIXED, including `GuardedBatchJobHandler` (engine batches gave 500) | 53 UI, 21 server and 2 engine tests |
| T08, outbox repair, auto-deploy | `OutboxRepair` contract; `auto-deployment-enabled: false` | PostgreSQL T08 4/4; `OutboxRepairDeadLetterTest`; `NoClassPathAutoDeploymentTest` |
| BusinessDateGuard | the custom-field date comes from `BusinessClock` | core and rest suites green |
| SG-06/07/13/14/17, SL-18 | JDK 25 and 27 green (329 tests); Oracle driver docs; Helm, compose, airgap and server-image proofs; ADR-D-1 doc; hosting (`HostingWiring`, `TrustedProxyFilter`, connector service tokens) | see the register. Not run: native worker (no GraalVM) and a kind/Helm install |
| E10, matrix | the LIC checks read the shipped Boot jar; E10 passes on PostgreSQL | `pg-e10b.log` |
| COORD-1 | `sangam/COORDINATION.md` v1.1 | WorkFlow ACK |

### 6b. In progress when the session ended (agents are gone; resume from these notes) — record of 2026-11-08; superseded by §6.0 and the register

| ID / workstream | Worktree | Exact state | Next step |
|---|---|---|---|
| **approve-401, T35, bill validation** | `wt-auth401` | Bill fixes are done in the worktree, not in main: `Vars.payloadOf` `variable>parameter`; `gov_ifms.py` `billType>formNumber`, `docId>typeId`, headOfAccount→scheme/code, signature→chainId; `ConfigConnector` turns an unknown chain or missing amount into a failure. `BillConnectorContractTest` and `ConnectorInputRenameTest` pass. The cause of the 401 is still unknown (token checks pass offline). The gated PostgreSQL run of `PlugAndPlayIT`, `E17bDomainIT` and `T35RestoreIT` (script `scratchpad\auth401-it.ps1`) was queued. | Run the gated PostgreSQL job and read the 401 `detail`; fix it at the root. Prove AT-1..7, D-REFERRAL and T35 (T35's ledger assertion must not change). Apply as targeted Edits. Then report to packs4 (apply order step 2). |
| **SL-45 tenant isolation** | `wt-iso` | Two leaks are fixed in main: static resolution, and an unscoped `/engine-rest` (`EngineRestTenantFilter`). The IT seed bug is fixed, using `pg_catalog`. The rerun of `CrossTenantIsolationIT` was queued (log `<temp>\sl45-it2.log`). | Run it and root-fix any `/api/**` leak in the shared tenant-scoping layer, throwing `TenantIsolationException` (keep the "SL-13 tenant isolation" message). Then report the matrix. |
| **IFMS-WF** (first IFMS workflows) | `wt-ifmswf` | Fix 1: `packs.py` `pin_cross_pack_calls` (cross-pack calls fail under D-12; 388 call activities). Fix 2: `SangamModelValidator` EXT findings. Both are in the worktree only; the test files are in main. The PostgreSQL run before the fixes gave E46 8/9 and IfmsPacksIT 0/6. The post-fix gated run (`customwf-ifmswf`) was queued. | Prove `E46ConformanceIT` 9/9 and `IfmsPacksIT` 6/6, then apply **FIRST** (apply order step 1): a targeted Edit to `packs.py`, regenerate, `--check`. Then do bills and payments (item 4). |
| **packs4: money in minor units, plus SL-33** | `wt-packs4` | Green in the worktree: 81 packs tests; `money.py` helper; `PackMoneyGuardTest`; `LedgerConnector` and X12 decoders in minor units; profilegen bands in paise; SL-33 PFM and GDPR packs. Not applied. The gated script `scratchpad\packs4-it.ps1` is ready. | Wait for steps 1 (IFMS-WF) and 2 (approve-401), then merge onto them, regenerate, land the whole money contract in one step, and run `IfmsPacksIT` on PostgreSQL. Then do SL-35. Note the free-text profile money strings ('2CR', '5L', '1CR'), which no code reads. |
| **SL-36 / SL-44 bulk** | `wt-bulk`, `wt-bulk2` | Bulk ingest is built. It must switch from the separate `BulkQuota` port to `TenantQuotas.current().checkSize(tenant, QuotaLimit.BATCH_SIZE, rows)`, which is now in main. A size refusal returns 413. `BulkIngestPostgresIT` is gated. | Switch, then prove on PostgreSQL. Apply; the changelog goes after 0031-01/02. Update SL-36 and SL-44. |
| **FWT-D11 / D12** | `wt-fwtd` | D11: `FlowableWorkerCompatProblemAdvice`, plus a `SecurityConfig` rule allowing `DispatcherType.ERROR` for authenticated callers (a direct `/error` stays 403). D12: `WorkPort.lockVersionOfAttempt` (contract ACKed). The SD-428 review of 10 ports fixes is VERIFIED. The gated `FwtWorkerCompatIT` (`scratchpad\fwtd-it.ps1`) was queued. | Run it, apply as targeted Edits (keep the SL-45 filter and the SL-37 permissions in `SecurityConfig`), and update the FWT rows. |
| **GP-13 / D-TENDER compensation, GP-21 CMMN timer** | `wt-comp` | The H2 work and the negative proof were in progress. The PostgreSQL scripts are `scratchpad\run-comp-pg.ps1` and `run-gp21.ps1` (owner customwf-comp). | Find the root cause: compensation runs once instead of once per completed multi-instance. For GP-21, decide between a product defect and the test clock. Prove on PostgreSQL. |
| **SG-15 T-SCALE** | `wt-perf`, `wt-perf-base` | Fixes are in the worktree only: `ResolvedIdentityCache` (exact; revocation is immediate on all service paths, `ResolvedIdentityCacheH2Test` 9/9), single-statement PostgreSQL `RootSeq`/`AuditChain`, batched `Outbox` deliveries, and a pool formula in `deploy/README.md` §1.6. The baseline 11.6 starts/s is against a target of 500. Before and after runs were queued; if lost, re-queue with `scratchpad\run-tscale.ps1 -Label base -Tree wt-perf-base -Warmup 60 -Measure 300 -Probe 30`, then `-Label after -Tree wt-perf -Its "TScaleLoadIT,AuditChainIT"`. | Get both reports and `AuditChainIT` (including the mixed old/new history test) green on PostgreSQL. Apply; add the security README note about the 5-minute bound for out-of-band DB edits. |
| **UI parity, round 2** | `wt-uip2` | The UI is green 3 times. The `taskForm` flake is fixed at the root (1.9 s down to under 0.3 s). Additive APIs are approved: task-list variables, `activityInstanceId` filters, an activity-state filter. Server tests were rerunning. | Finish the server tests and `OpenApiCompatibilityTest`, then apply. Items: P13, P14, P35, P45, P49, the duplicate label, P02 and P59 tests. |
| **Matrix runner** | (repo scripts, `AcceptanceDatabase`) | `sqlserver2025` lane added (`2025-CU10-ubuntu-24.04`, RCSI); 5-minute container start timeout. PostgreSQL: E1 17/17, E11 8/8, E16c 15/15, E17b 6/6, E17c 6/6, E16a 19/20 (GP-13), E16b 19/20 (GP-21), E17a 4/5 (D-TENDER). E10 now passes. The Europe/Berlin E8 run was in progress. | Run E8 Berlin, then the full suite on SQL Server 2022, Oracle 23, MySQL 8.4 and SQL Server 2025 (gated, owner `customwf-matrix`). Update SG-04/05. |
| **SL-43-DEADLOCK** (HIGH) | `wt-qdl` | FIXED in tree (uncommitted), single-verified. Root cause: quota override reads and the counting UPDATE took a second connection from the main pool (`Transactions.of(ds)`) while the engine/REST transaction held one. Fix: overrides read on the caller's Spring-bound connection, else single-flight through a dedicated leaf pool `sangam-quota` (`sangam.quotas.pool.maximum-size`, default 4); counting and refusal audit on the leaf pool; generation-guarded `invalidate` after `configure` commits. Red without the fix: `QuotaPoolDeadlockTtl0H2Test` 12/12 failed, `Ttl30s` 10/12 failed (pool timeout). Green: both, plus `JdbcTenantQuotasTest` 9/9, `EngineRestTenantRefusalTest` 4/4, PostgreSQL `TenantQuotaIT` 4/4. | SD-428 second check; re-run T-SCALE. New finding: concurrent first-use JIT provisioning answers 409 "user exists". |

### 6b-URGENT. SL-43 REOPENED: quota connection-pool deadlock (HIGH, found 2026-11-08 ~22:40 IST)
- **Defect:** `JdbcTenantQuotas.overrides` takes a second pool connection through `QuotaWiring`'s `Transactions.of(ds)` while the caller already holds one. This happens in `TenantQuotaEnginePlugin.checkVariables` and in `TenantQuotaFilter`.
- **Effect:** under concurrency the pool starves (20/20 active, 49 waiting). The T-SCALE baseline got **0 starts/s**: 209 attempts, all failed, with 60 s timeouts, 403 via `/error` and 500s.
- **Where it is:** in the main tree and in commit `2aaf44f`. **Do not merge the handoff branch until this is fixed.**
- **Fix:** a fix agent was dispatched (worktree `scratchpad\wt-qdl`, lock owner `customwf-qdl`). If that session died, redo it: read on the caller's transaction, or use a cache refreshed outside any held connection. Add a regression test with 3×pool concurrent starts that deadlocks without the fix. Also audit the other new code for the same nested-connection pattern.
- **T-SCALE:** throughput cannot be measured until this is fixed. Baseline round trips per full lifecycle: **484.7**, plus 63.3 in the background. Report: `scratchpad\tscale-base\report.txt`.

### 6b-URGENT-2. SG-35 (HIGH, money path, from IFMS Trial 2, single-verified)
- **Defect:** on 15e4135, a bill whose amount matches no band at the `gwBand` exclusive gateway is silently parked. The case keeps running with no task, and no NO_MATCHING_ROUTE incident is raised.
- **Regression:** 0196b57 raised ENGINE-02004 correctly. Seen on Oracle 23 with JDK 25 (kit X-13). Evidence: oneifms-sangam 7c34baa, E-54 (IFMS b2e03be).
- **Cause:** suspected to be the ADR-D-25 strict-XOR change.
- **Fix:** a fix agent was dispatched (worktree `scratchpad\wt-sg35`, lock owner `customwf-sg35`). If it died, redo it: no match must fail closed with an audited `NO_MATCHING_ROUTE` incident, for every exclusive gateway. Add a regression test on H2, on the IFMS bill pack and on PostgreSQL. WorkFlow needs the fix commit id.

### 6b-3. Module-controller errors become 401 (found by SL-44)
- **Defect:** controllers under `sangam-rest/modules` are outside `ProblemHandler`'s package scope. Their errors go to `/error`, and the security chain turns them into 401.
- **Status:** bulk is fixed by `BulkProblemAdvice`. `OpsController` and the other module controllers still need a generic root fix: a single advice for all Sangam REST controllers, with a test.
- **SL-36 and SL-44:** CLOSED in the register. SL-44 passed `BulkIngestPostgresIT` 3/3.

### 6b-4. E8 DST autumn fold: repeating timers lose a fire (found by the matrix runner, PostgreSQL, Europe/Berlin)
- **Result:** E8 under Europe/Berlin passed 2 of 5, with 1 skipped.
- **Failures:** both fall-back cases fail with `exactly 6 fires (got 5)` and `every fire inside its own interval (misplaced 3)`.
- **Diagnosis:** likely a product defect in the R31 timer catch-up and DST handling (`TimerPolicies`, the `sangam:catchUp` DST guard).
- **Fix:** a fix agent was dispatched (worktree `scratchpad\wt-dst`). If it died, redo it: repeating timers must fire exactly once per interval across a fall-back fold, computed in the tenant zone.
- **Update 2026-11-09 (register SL-53):** root cause proven, and it is NOT the timer arithmetic. The calendar computes the right next due (01:00Z = 02:00 CET). Operaton stores it as zone-less JVM-local `TIMESTAMP` "02:00", which reads back as 02:00 CEST = 00:00Z. The job then fires at once and `TimerGuard` suppresses it as a duplicate interval, so one fire is lost. Repro `DstFoldTimerH2Test` (wt-dst, sangam-engine, H2, Berlin): autumn fails 5/6, spring passes. **Owner decision needed:** (a) UTC persistence of engine timestamps plus a data migration, or (b) a mandatory UTC engine JVM, with the tenant zone set only through `timer-zone`/`BusinessClock`. Nothing applied to product code; PostgreSQL E8 not re-run.
- **Matrix runner:** the full acceptance set is now running on SQL Server 2022, then Oracle 23, MySQL 8.4 and SQL Server 2025.

### 6b-5. JIT provisioning race (found by the SL-43 deadlock work)
- **Defect:** when a new identity's first requests arrive concurrently, JIT provisioning answers `409 "user ... already exists"`. Not fixed yet.
- **Root fix needed:** an idempotent upsert, or a single-flight per subject, with a concurrent regression test.
- **SL-43 deadlock:** FIXED in the tree. It is single-verified; see the SL-43 register row.
- **Owner note:** the quota counting uses a small separate `sangam-quota` pool, so a thread holding a main-pool connection may take one quota-pool connection. That is deadlock-free, but it is not the literal "never a second connection" rule. The literal rule would mean moving the quota counting out of the engine transaction.

### 6c. Open items outside the code list (current, 2026-11-12)

The 2026-11-08 version of this list is resolved as follows (register rows cited; all coded with tests written, not run):
- **SL-35** (new document type end to end): FIXED in tree (B8); follow-ups split out as SL-119 to SL-123.
- **`S3DocumentStore` testability and the `_shared` content key:** decided by the owner (Q10) and coded under SG-03 (a) and (b).
- **`task.suspend` / `task.activate` never firing:** verified from the Operaton bytecode and coded under SG-03 (c) (one Sangam TaskManager emits one event per affected task).
- **SDK gaps:** every SDK completes from the stored outcome (SDK-STORED-OUTCOME) and the .NET, TypeScript and PHP SDKs have unit tests (SDK-UNIT-TESTS; PHP tests not run, no PHP installed).

Still open (no register rows of their own; audit A "Planned or decided items that have no register row"):
- **IX.8 follow-ups:** AC-01..06 on PostgreSQL; AC-07 on the other databases; the AC-08 cross-language worker run, which needs Q12 (.NET 8 and PHP are not installed; ask the owner before any download); AC-10 usability (Q28). Register rows for AC-01…AC-11 are backlog M-115: the text was drafted in Wave 1 (W1-J report), and the integrator applies it.
- **C-19 [CONTRACT] notice** to IFMS for SL-71/72/73 (§8 step 1; backlog M-119). JOINT-LEDGER §5 has no Sangam→IFMS entry after 2026-11-11 09:58; check whether it was sent without being logged. A draft is in the Wave 1 W1-J report; sending it and logging it in JL §5 are the owner's or the integrator's act.
- **Docs-wave items still open after this pass:** the misleading first status words of some register rows (SL-106, SL-107, SG-37, SG-38, SL-03, the SL-37 parent, SL-43) and the missing register rows of audit A (backlog M-118; integrator only), the stale header comment in `sangam-ui/src/modules/platform.tsx` (code, lane W1-G), the parity-doc line for `cam-script` and iframe forms in `sangam-ui/OPERATON-UI-PARITY.md` (lane W1-G; the decision itself is recorded in `SANGAM.md` X.5.3), and a regenerated UI licence list (`npm run licences`, backlog M-117).
- **Stray work:** the T-SCALE text evidence from `wt-scale` (drive root, §9) is centralised in `sangam/perf/results/2026-11-08-tscale-15e4135`; the raw files (1.6 GB `ev.json`, the `.jfr` profiles) exist only in that folder. Keep them, or accept their loss, before it is deleted.
- **AC-03 expectation:** packs3 moved `UNKNOWN_OUTCOME` from the case state to the audit expectations. I judged that correct; the owner may revisit it.

## 7. Apply order for the shared packs/connector files (strict)
1. IFMS-WF lands `pin_cross_pack_calls` (`packs.py`).
2. approve-401 lands `ConfigConnector`, `Vars.payloadOf` and the `gov_ifms.py` mappings.
3. packs4 merges onto both, regenerates, and lands the whole money contract (ledger, config, health, security tables) in one step, then runs `IfmsPacksIT` on PostgreSQL.
4. SL-35.

## 8. Road to the next push (in order; replaces the 2026-11-08 list)

**Agent sync rules (permanent owner order, 2026-11-12).** Every agent, in every wave, the test phase and every audit,
reads `<handoff>/AGENT-SYNC-RULES.md` before it starts and follows it: read the wave's sync board
(`<handoff>/WAVE<N>-SYNC-BOARD.md`), FILE-CLAIMS, the carry list and the register row first; post a CHANGE with a grep
of every consumer before any cross-module change and DONE with the SHA-256 when it lands; REQUEST/ACK between lanes;
BROKEN when a gate breaks; never build a second copy; never break another lane silently; never act against an owner
decision. Every agent brief points to that file.

**Process for every agent until the push** (CODE-COMPLETE-PLAN "Rules for every agent"):
- Edit the integration tree directly with targeted edits, keep each file's line endings, never overwrite another agent's hunks. Agents never commit, stage or push.
- **Shared files** are edited only after a claim: append `<UTC time> <agent/ws> CLAIM <path>` to `<handoff>\FILE-CLAIMS.md`, check that nobody else holds it, and append `RELEASE <path>` as soon as the edit is done. Shared files: `generate_changelogs.py` and its baselines, `SangamSchema.java`, every `pom.xml`, `sangam-api.yaml` and `gen_openapi.py`, `generate_realm.py` and the realm JSON, `application*.yaml`, `.gitattributes`, `.github/workflows/*`, `HANDOFF.md`, and `TESTING-GAP-REGISTER.md` (integrator only).
- Data changes only through the generators, each with `--check` green. The gate is compile only; no DB, Testcontainers or heavy-lock run until the code is on `main`.
- Every report states the files with their SHA-256, the evidence and confidence per decision, the open questions, and the exact register-row text for the integrator.

**Steps:**
0. Finish the code still to write (§6.0): SL-102, SL-78, SL-79, SG-15, the SL-58 investigation, SG-39 after the owner's route decision, and the 0.1.0 full-scope backlog (`SANGAM.md` XII.9).
1. Finish the docs wave (Wave C of the plan): register restatements, the JL board, this file, the READMEs, the `SANGAM.md` ADRs, and the [CONTRACT] notices to IFMS (C-19 for SL-71/72/73). Done in the second pass (2026-11-12): deploy README JRE 25, the SL-116 and SL-117 READMEs, the SDK test statements, the contract counts in `SANGAM.md` XII.1, the UI and modeller README drift, `CHANGES-SINCE-1105d9c.md` for SL-125..127. Done in Wave 1, lane W1-J (M-110, M-111): the stale lines in the security, server, ops, AI and channels READMEs, `SECTOR-CONFORMANCE.md` (SL-35 and SL-38…SL-46 now fixed), the status of `ACCEPTANCE-PLUG-AND-PLAY.md`, the `OperatonPackageToolsAdapter` comments Javadoc, four supersession rows in `SANGAM.md` X.5.3 and the wave-plan pointer `SANGAM.md` XII.10. Done in Wave 2, lane W2-J: the 33 `SecurityEventType` and 4 `CaseState` audit names in `SANGAM.md` X.4.0.3 (the AsyncAPI catalogue is regenerated by W2-H); ADR-D-29 (X.0j) with two X.5.3 rows; the English-only scope (XII.9) and the XII.4 #2/#4/#5 status; the SCALE-ARCHITECTURE §3 job-executor sentence; `sangam/docs/GLOSSARY.md` and `sangam/docs/THREAT-MODEL.md` (STRIDE per component, M-096; its high gaps TM-5, TM-6, TM-9 and TM-10 need register rows); the deploy README (private ingress, alerting, leaf-pool sizing); the compliance, server, persistence and field READMEs (SL-137, SL-141, SL-142, SL-139, `BIL_DOCUMENT`, SL-129).
2. Integrator: apply the register rows reported by the agents; confirm every FILE-CLAIMS line has its RELEASE; run the full gate `.\mvnw.cmd -pl sangam-server -am -DskipTests test-compile` and every generator `--check`.
3. Commit on a branch. The message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
4. Run the signed push (§5); the owner signs. After a push of more than 300 files, dispatch every CI workflow (owner's standing approval, Q16). Watch CI.
5. Test phase, one stage at a time: unit, system, integration, acceptance (heavy runs through the detached-runner heavy lock, §2). Record each result in the register before the next stage. Then the SD-428 independent checks.
6. Write the end-to-end report and send a REPORT to WorkFlow and IFMS main: commit id, closed ids, next, blocked.

## 9. Durable backup (`<handoff>\2026-11-08\`)

- `main-tree-HEAD.txt`: base commit `9c831d0…`.
- `main-tree-tracked.patch`: the full binary diff of tracked files against HEAD, 6.9 MB. It was verified with `git apply --check --reverse` (exit 0). To restore on a clean checkout of `9c831d0`: `git apply --binary main-tree-tracked.patch`.
- `main-tree-untracked\` with `main-tree-untracked-list.txt`: the 181 untracked files (build outputs excluded).
- `main-tree-status.txt`: `git status --porcelain` at handoff time.
- `scratchpad\`: every private worktree (`wt-*`), the run scripts (`*.ps1`), patches, logs and tools, with build outputs and `node_modules` excluded.
- `agent-transcripts\`: every background agent's full transcript (JSONL) and outputs. This is the record of every decision and result.
- `main-session-transcript.jsonl`: this session's full transcript.
- `memory\`: a copy of the auto-memory, including `MEMORY.md` and `feedback-coordination-protocol.md`.
- `heavy_lock.py.copy`: the lock tool, for reference only. Always use the original path in §2.
- **Other worktrees on D: (durable):**
  - `wt-fwt` (drive root): FWT draft fixes.
  - `wt-scale` (drive root): T-SCALE baseline results, `tscale-base\` with JFR.
  - `sangam-wt-ci` (drive root): CI agent; can be deleted.
  - `cwf-docs-wt` (next to `<repo>`): docs agent build evidence.

If the live main tree is ever lost, restore it from `main-tree-tracked.patch` plus `main-tree-untracked\`. If a worktree in `<temp>` is lost, use the copy under `scratchpad\`.

## 10. First actions for the resuming session

**Current (2026-11-12):**
0. Open the session at `<repo>` (continuity rule, top of this file), so the owner's auto-memory loads.
1. Read `<handoff>\RESUME-LEDGER-2026-11-09.md`, then `SANGAM-READINESS-REPORT-2026-11-12.md`, `CODE-COMPLETE-PLAN-2026-11-12.md` and the tail of `FILE-CLAIMS.md` in the same folder, then this file, `sangam/COORDINATION.md` and `sangam/TESTING-GAP-REGISTER.md`.
2. Work in the integration tree (`.claude\worktrees\workspace-scan-resume-06b524`); never edit the base checkout. Check `git status` there before anything else.
3. Check `FILE-CLAIMS.md` for any CLAIM without a RELEASE before you touch a shared file.
4. Run `heavy_lock.py status` (§2) after any outage; no heavy run before the push.
5. Continue §8 from the first step not yet done.

**At the 2026-11-08 handoff (historical):**
1. Read this file, `sangam/COORDINATION.md` and the end of `sangam/TESTING-GAP-REGISTER.md`.
2. Run `git status` and check that the main tree still matches `main-tree-status.txt`. If it does not, restore it from §9.
3. Run `heavy_lock.py status` and look for Sangam (`customwf-*`) tickets. Their old waiter processes died with the old session. Remove nothing; start fresh gated acquires.
4. Send WorkFlow `[REPORT] HANDOFF Sangam→WorkFlow`: a new session has resumed from `HANDOFF.md`, with no change to ownership.
5. Re-dispatch the §6b workstreams one by one, each with its worktree path, its scope and the rules in §2. Follow the apply order in §7.
