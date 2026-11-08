# Sangam — session handoff (2026-11-08, written by the Sangam owner session)

**For whoever resumes (new login, new session):** read this file completely before doing anything.
It is the single source of truth for where the work stands. Nothing here is an approval. Approvals come only from the owner, in their own words, in their own session.

- Repo: `D:\GITHUB  Project\Custom WorkFlow` (two spaces in the path). GitHub `sqamentor/sangam`.
- Durable backup of everything: `D:\GITHUB  Project\sangam-handoff\2026-11-08\` (see §9).
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
- **Private worktrees:** every workstream works in its own NEW folder `scratchpad\wt-<ws>`. Never reuse `wt` or another workstream's folder.
  - **When copying, exclude only build outputs** (`target-*` and the module-root `target/`). `sangam-programme/src/main/java/org/sangam/programme/target` is SOURCE code. Losing it broke two workstreams on 2026-11-08.
- **Applying to the main tree:** apply in one step, and only when `-pl sangam-server -am -DskipTests compile` and the tests are green on a fresh re-copy of main. A shared file is changed only by a targeted Edit on the CURRENT main-tree file, never by copying a worktree file over it.
- **Heavy lock (I-44/I-45):**
  - **Command:** `python "C:\Users\Lokendra Singh\AppData\Local\Temp\claude\D--GITHUB--Project-IFMS\ff9f2caf-1246-4228-ae70-82a16cf209ae\scratchpad\heavy_lock.py" acquire <owner> "<what>"`. A backup copy is in the handoff folder.
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
- **Tool quirks:** `ssh-keygen` from the Bash tool fails with 0xC0000142. Use PowerShell, or `C:\Windows\System32\OpenSSH\ssh-keygen.exe`.

## 3. Git and GitHub state

- **Local `main`** is 4 commits ahead of `origin/main`. These are not pushed:
  - `4bd5a2e`: push gate
  - `35996f9`: owner-signed approvals
  - `3cf7841`: owner key in `.githooks/allowed_signers`
  - `9c831d0`: `.gitattributes`, LF for governance files
- **Remote main** is still `15e4135`.
- **Uncommitted:** about 501 changed or untracked paths in the main tree. This is the whole of the 2026-11-08 testing-phase work. A backup patch is in §9.
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
- **Trust root:** the owner-held `C:\Users\Lokendra Singh\.ssh\sangam_allowed_signers`. It was created by the owner on 2026-11-08 and verified to match `.githooks/allowed_signers` byte for byte: no BOM, fingerprint `SHA256:GVyGHB4GMD5muUTFctICsYNr0IDfunf7b3LsHXo7d1o`. The `SANGAM_ALLOWED_SIGNERS` environment variable is ignored.

**Steps for each push:**
1. Commit on a branch. If needed, open a PR. Fast-forward only.
2. Generate the text to sign: `node tools/governance/push-approval.mjs text --sha <sha> --remote <exact remote URL> --ref refs/heads/main --words "<owner's words>" > a.txt`. It expires in 30 minutes or less.
3. **The owner signs it personally:** `ssh-keygen -Y sign -f "$env:USERPROFILE\.ssh\sangam_push_owner" -n sangam-push a.txt`
4. Record the signature: `node tools/governance/push-approval.mjs record --signed a.txt --sig a.txt.sig --conversation <id>`
5. Push. The hook re-verifies the signature at push time.

The selftest `node tools/governance/selftest-push-approval.mjs` passes 31/31 under PowerShell and Git Bash. It takes about 4 minutes.

## 6. Status of every workstream at handoff

"In tree" means applied to the main tree and uncommitted. "SV" means single-verified, still OPEN until the SD-428 check. Worktree folders are under `scratchpad\`, and the backup copy is under `<handoff>\scratchpad\`.

### 6a. Done, in tree (needs the SD-428 check, then commit)

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

### 6b. In progress when the session ended (agents are gone; resume from these notes)

| ID / workstream | Worktree | Exact state | Next step |
|---|---|---|---|
| **approve-401, T35, bill validation** | `wt-auth401` | Bill fixes are done in the worktree, not in main: `Vars.payloadOf` `variable>parameter`; `gov_ifms.py` `billType>formNumber`, `docId>typeId`, headOfAccount→scheme/code, signature→chainId; `ConfigConnector` turns an unknown chain or missing amount into a failure. `BillConnectorContractTest` and `ConnectorInputRenameTest` pass. The cause of the 401 is still unknown (token checks pass offline). The gated PostgreSQL run of `PlugAndPlayIT`, `E17bDomainIT` and `T35RestoreIT` (script `scratchpad\auth401-it.ps1`) was queued. | Run the gated PostgreSQL job and read the 401 `detail`; fix it at the root. Prove AT-1..7, D-REFERRAL and T35 (T35's ledger assertion must not change). Apply as targeted Edits. Then report to packs4 (apply order step 2). |
| **SL-45 tenant isolation** | `wt-iso` | Two leaks are fixed in main: static resolution, and an unscoped `/engine-rest` (`EngineRestTenantFilter`). The IT seed bug is fixed, using `pg_catalog`. The rerun of `CrossTenantIsolationIT` was queued (log `%TEMP%\sl45-it2.log`). | Run it and root-fix any `/api/**` leak in the shared tenant-scoping layer, throwing `TenantIsolationException` (keep the "SL-13 tenant isolation" message). Then report the matrix. |
| **IFMS-WF** (first IFMS workflows) | `wt-ifmswf` | Fix 1: `packs.py` `pin_cross_pack_calls` (cross-pack calls fail under D-12; 388 call activities). Fix 2: `SangamModelValidator` EXT findings. Both are in the worktree only; the test files are in main. The PostgreSQL run before the fixes gave E46 8/9 and IfmsPacksIT 0/6. The post-fix gated run (`customwf-ifmswf`) was queued. | Prove `E46ConformanceIT` 9/9 and `IfmsPacksIT` 6/6, then apply **FIRST** (apply order step 1): a targeted Edit to `packs.py`, regenerate, `--check`. Then do bills and payments (item 4). |
| **packs4: money in minor units, plus SL-33** | `wt-packs4` | Green in the worktree: 81 packs tests; `money.py` helper; `PackMoneyGuardTest`; `LedgerConnector` and X12 decoders in minor units; profilegen bands in paise; SL-33 PFM and GDPR packs. Not applied. The gated script `scratchpad\packs4-it.ps1` is ready. | Wait for steps 1 (IFMS-WF) and 2 (approve-401), then merge onto them, regenerate, land the whole money contract in one step, and run `IfmsPacksIT` on PostgreSQL. Then do SL-35. Note the free-text profile money strings ('2CR', '5L', '1CR'), which no code reads. |
| **SL-36 / SL-44 bulk** | `wt-bulk`, `wt-bulk2` | Bulk ingest is built. It must switch from the separate `BulkQuota` port to `TenantQuotas.current().checkSize(tenant, QuotaLimit.BATCH_SIZE, rows)`, which is now in main. A size refusal returns 413. `BulkIngestPostgresIT` is gated. | Switch, then prove on PostgreSQL. Apply; the changelog goes after 0031-01/02. Update SL-36 and SL-44. |
| **FWT-D11 / D12** | `wt-fwtd` | D11: `FlowableWorkerCompatProblemAdvice`, plus a `SecurityConfig` rule allowing `DispatcherType.ERROR` for authenticated callers (a direct `/error` stays 403). D12: `WorkPort.lockVersionOfAttempt` (contract ACKed). The SD-428 review of 10 ports fixes is VERIFIED. The gated `FwtWorkerCompatIT` (`scratchpad\fwtd-it.ps1`) was queued. | Run it, apply as targeted Edits (keep the SL-45 filter and the SL-37 permissions in `SecurityConfig`), and update the FWT rows. |
| **GP-13 / D-TENDER compensation, GP-21 CMMN timer** | `wt-comp` | The H2 work and the negative proof were in progress. The PostgreSQL scripts are `scratchpad\run-comp-pg.ps1` and `run-gp21.ps1` (owner customwf-comp). | Find the root cause: compensation runs once instead of once per completed multi-instance. For GP-21, decide between a product defect and the test clock. Prove on PostgreSQL. |
| **SG-15 T-SCALE** | `wt-perf`, `wt-perf-base` | Fixes are in the worktree only: `ResolvedIdentityCache` (exact; revocation is immediate on all service paths, `ResolvedIdentityCacheH2Test` 9/9), single-statement PostgreSQL `RootSeq`/`AuditChain`, batched `Outbox` deliveries, and a pool formula in `deploy/README.md` §1.6. The baseline 11.6 starts/s is against a target of 500. Before and after runs were queued; if lost, re-queue with `scratchpad\run-tscale.ps1 -Label base -Tree wt-perf-base -Warmup 60 -Measure 300 -Probe 30`, then `-Label after -Tree wt-perf -Its "TScaleLoadIT,AuditChainIT"`. | Get both reports and `AuditChainIT` (including the mixed old/new history test) green on PostgreSQL. Apply; add the security README note about the 5-minute bound for out-of-band DB edits. |
| **UI parity, round 2** | `wt-uip2` | The UI is green 3 times. The `taskForm` flake is fixed at the root (1.9 s down to under 0.3 s). Additive APIs are approved: task-list variables, `activityInstanceId` filters, an activity-state filter. Server tests were rerunning. | Finish the server tests and `OpenApiCompatibilityTest`, then apply. Items: P13, P14, P35, P45, P49, the duplicate label, P02 and P59 tests. |
| **Matrix runner** | (repo scripts, `AcceptanceDatabase`) | `sqlserver2025` lane added (`2025-CU10-ubuntu-24.04`, RCSI); 5-minute container start timeout. PostgreSQL: E1 17/17, E11 8/8, E16c 15/15, E17b 6/6, E17c 6/6, E16a 19/20 (GP-13), E16b 19/20 (GP-21), E17a 4/5 (D-TENDER). E10 now passes. The Europe/Berlin E8 run was in progress. | Run E8 Berlin, then the full suite on SQL Server 2022, Oracle 23, MySQL 8.4 and SQL Server 2025 (gated, owner `customwf-matrix`). Update SG-04/05. |

### 6c. Not started / open
- **SL-35:** a new document type, end to end. It depends on packs4.
- **IX.8 follow-ups:**
  - AC-01..06 on PostgreSQL;
  - AC-07 on the other databases;
  - AC-08 cross-language worker run. .NET 8 is not installed (only 7.0.410) and PHP is not installed; ask the owner before any download.
- **Design decisions needed from the owner, raised but not yet asked:**
  - `S3DocumentStore` builds its own HTTP client, so it can't be unit-tested.
  - `CONTENT_ITEM.tenant_id` is NOT NULL, while the code uses a `_shared` key for the shared tenant.
  - The `task.suspend`/`task.activate` events may never fire (unverified).
- **SDKs:** Python, TypeScript and PHP don't complete from the stored outcome; the .NET and TypeScript SDKs have no unit tests.
- **AC-03 expectation:** packs3 moved `UNKNOWN_OUTCOME` from the case state to the audit expectations. I judged that correct; the owner may revisit it.

## 7. Apply order for the shared packs/connector files (strict)
1. IFMS-WF lands `pin_cross_pack_calls` (`packs.py`).
2. approve-401 lands `ConfigConnector`, `Vars.payloadOf` and the `gov_ifms.py` mappings.
3. packs4 merges onto both, regenerates, and lands the whole money contract (ledger, config, health, security tables) in one step, then runs `IfmsPacksIT` on PostgreSQL.
4. SL-35.

## 8. Road to the next push (in order)
1. Finish §6b, applying each item one at a time per §7.
2. **SD-428 pass:** dispatch one reviewer agent, which did not write the fixes, to re-verify every SL item on the main tree, with revert checks for regression tests where they are missing. One known gap: `EngineSlotReleasedOnContextCloseTest` was never run against the old code.
3. **Full H2 suite on the combined tree:** `.\mvnw.cmd -pl <all modules> -am clean verify` (H2, no failsafe). Root-fix anything red.
4. Commit on a branch. The message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
5. Run the signed push (§5); the owner signs. Watch CI. When main CI is green, tell WorkFlow, and ask the owner about required status checks.
6. Send a REPORT to WorkFlow and IFMS main: commit id, closed IDs, next, blocked.

## 9. Durable backup (`D:\GITHUB  Project\sangam-handoff\2026-11-08\`)

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
  - `D:\wt-fwt`: FWT draft fixes.
  - `D:\wt-scale`: T-SCALE baseline results, `tscale-base\` with JFR.
  - `D:\sangam-wt-ci`: CI agent; can be deleted.
  - `D:\GITHUB  Project\cwf-docs-wt`: docs agent build evidence.

If the live main tree is ever lost, restore it from `main-tree-tracked.patch` plus `main-tree-untracked\`. If a worktree in `%TEMP%` is lost, use the copy under `scratchpad\`.

## 10. First actions for the resuming session
1. Read this file, `sangam/COORDINATION.md` and the end of `sangam/TESTING-GAP-REGISTER.md`.
2. Run `git status` and check that the main tree still matches `main-tree-status.txt`. If it does not, restore it from §9.
3. Run `heavy_lock.py status` and look for Sangam (`customwf-*`) tickets. Their old waiter processes died with the old session. Remove nothing; start fresh gated acquires.
4. Send WorkFlow `[REPORT] HANDOFF Sangam→WorkFlow`: a new session has resumed from `HANDOFF.md`, with no change to ownership.
5. Re-dispatch the §6b workstreams one by one, each with its worktree path, its scope and the rules in §2. Follow the apply order in §7.
