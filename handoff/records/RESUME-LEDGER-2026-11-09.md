# Sangam RESUME LEDGER — 2026-11-09 (read FIRST, before HANDOFF.md)

Written about 01:40 UTC on 2026-11-09 by the resuming session `6e98c2a7…`, which runs in the worktree `.claude\worktrees\workspace-scan-resume-06b524`.
This ledger supersedes `HANDOFF.md` §3 and §6 wherever they disagree. `HANDOFF.md` (committed in `460c95b`) is still valid for the rules, the push procedure, the apply order and the backup layout.
Source: the full transcript `C:\Users\Lokendra Singh\.claude\projects\D--GITHUB--Project-Custom-WorkFlow\898f1ff7-0dcc-4d95-b425-830a9a5ead44.jsonl`, plus the logs. Nothing here is an approval.

The old session kept working after the 2026-11-08 backup (taken about 22:24–22:50 IST). It stopped only when the weekly limit cut off the main session and all its agents, between 22:00 and 23:14 UTC. That limit resets at 2026-11-15 01:30 IST on the old account.

## 1. Timeline after the backup (UTC, 2026-11-08)

| Time | Event |
|---|---|
| 16:36 / 16:45 | Owner asked for the status and the percentage done. The answer was an estimate: of the batch, about 75% in tree, about 45% proven on PostgreSQL, about 10% closed. Overall: IFMS about 60–65%, governments about 35–40%, Indian sectors about 25–30%, worldwide about 15–20%, platform quality about 55–60%. |
| 16:52 / 17:00 | **Owner's order:** preserve everything for the login switch, and keep updating it going forward for the IFMS sessions and their sub-sessions too. Done for Sangam: `HANDOFF.md`, the backup, a memory pointer, and `D:\GITHUB  Project\HANDOFF-INDEX.md`. The index has rows for Sangam, IFMS/oneifms and Hosting. The rows for Trial 2 (WorkFlow fills it), DOIT and PHED are still empty. A [REQUEST] HANDOFF-ALL went to WorkFlow and IFMS main. |
| 16:56–17:03 | IFMS main asked for REC-05. Commits `2aaf44f` (WIP snapshot, 559 paths, NOT verified) and `7cac6c4` (HANDOFF) were made, and the ids went to WorkFlow and IFMS. IFMS reported its handoff: REC-05, R-HANDOVER (Hosting `91ce85c`), IFMS docs `c99dde1`, oneifms `b4bbbb7`, and the backup `IFMS_SESSION_BACKUP_2026-11-08`. |
| 17:04 | **Owner's order, verbatim:** "अब तक जितना भी कोड हुआ है और जितनी पीआर ओपन हुई है, जितना कोड हुआ है वो कमिट करो और गिटहब पे पुश करो, जितनी पीआर ओपन हुई है उनको मर्ज करो और पुश करो गिटहब पे।" The gate approvals record this as the words `Do All Code commit and push and all PR merged`. |
| 17:15 | SL-43 pool deadlock found: 0 starts/s, 209 out of 209 failed. A fix agent was dispatched. |
| 17:28–17:33 | The owner wrote "Signed", but the first try produced no .sig file. The second try worked. **Branch `handoff/2026-11-08` @ `7cac6c4` was pushed**, and **draft PR #4 was opened** (to main, DO NOT MERGE). |
| ~17:55 | `f553b43`, snapshot 2: the remaining main-tree work, plus every private worktree's changed or new files, byte for byte, under `handoff/worktree-snapshots/`. These are NOT applied to the product source. |
| 17:59 | WorkFlow relayed **SG-35** (HIGH, money path). A fix agent was dispatched. |
| 18:18 | SG-35 root cause: since ADR-D-25, a no-match raises a `sangamRouting` NO_MATCHING_ROUTE incident and holds the case. The defect was that this incident was never written to the audit chain. The IFMS bill pack is fine; `gwBand` is the E-46 WF-4 fixture. The X-13 conflict went to the owner and to WorkFlow. |
| 18:27 | E8 Berlin build failure fixed (`sangam-ops` pom dependencies). |
| 18:30 | `3736f8f`, snapshot 3: **SL-36/SL-44 bulk** (PostgreSQL `BulkIngestPostgresIT` 3/3, now on `TenantQuotas`; separate quota port removed) and the SL-45 IT setup. New defect: module-controller errors turn into 401; bulk has `BulkProblemAdvice`, `OpsController` and the other modules are still open. |
| 18:40 | `0d04fea`: **SG-35 fix** `RoutingIncidentHandler`. It audits `ROUTING_INCIDENT` (code, gateway, incident, instance) in the same command, on every exclusive gateway. Engine 90/90. Both regressions fail without the fix. |
| 19:05 | T-SCALE: PostgreSQL `AuditChainIT` 8/8 passes, including mixed old/new history. Audit append dropped from 6 round trips to 2, outbox append from 5 to 3. SQL Server, Oracle and MySQL are still OPEN. |
| 19:15 | `eba6e62`: **FWT-D11/D12**. PostgreSQL `FwtWorkerCompatIT` 7/7 with the fix, 6 of 7 fail without it. The `fwt010` test now uses a capability-class `a` task, because the SL-16 no-redelivery rule for class c is intentional. The D11 test now requires exactly 400. The SD-428 review of the 10 ports fixes: VERIFIED. |
| 19:19 | SL-45: second rerun hit a setup failure (`401 NOT_LINKED`, the tenants were missing from `jit-provisioning-tenants`). Fixed in the test. |
| 19:23–19:40 | E8 Berlin passed 2 of 5. **SL-53** root cause: Operaton stores timestamps in zone-less JVM-local `TIMESTAMP` columns. The repeated 02:00 of the autumn fold reads back one hour early, and `TimerGuard` suppresses that fire. A timer-only patch was rejected as a workaround. Repro `DstFoldTimerH2Test` (wt-dst). **Owner decision (a)/(b) needed.** |
| 20:10 | `460c95b`: **SL-43 deadlock fix**. Overrides are read on the caller's connection; counting and audit run on the leaf pool `sangam-quota`. Red runs without the fix: 12/12 and 10/12 failed. Green: `TenantQuotaIT` 4/4 on PostgreSQL. Also in this commit: the SL-45 IT setup and HANDOFF updates. New defect: concurrent first-use JIT provisioning answers 409. |
| 20:29 | T-SCALE applied. Round trips per full lifecycle went from 485 to 365: start 81→58, work.fetch 45.7→36.6, work.complete 88→65, task.list 30→21, task.get 68→51, task.complete 172→133. **FREEZE** was sent to all agents. |
| 20:34 | **Freeze commit `16e0e11`.** The working tree is clean. |
| 17:06–21:31 | Push approval files `push-approval.txt` (signed, used for the branch push) and `push-approval-main.txt` through `main7.txt` were made. **No main approval was ever signed. Main was NOT pushed.** |
| 21:44 | **WorkFlow [DECISION] SD-445 (sponsor): SG-35 option (a).** ADR-D-25 stands: a no-match commits, raises an audited NO_MATCHING_ROUTE incident, and holds the case recoverable at the gateway. OneIFMS maps `sangamRouting` / `ROUTING_INCIDENT` to GATEWAY_NO_MATCH and updates X-13. SG-35 was re-graded to MEDIUM. SD-417 / SG-08a are closed (double-checked on H2 on 15e4135). Sangam sent fix commit `0d04fea` (local, not pushed). |
| 22:00–23:14 | Weekly limit. The main session died, and so did the agents: matrix (`ad14f7c`), SL-45 (`a5ce164`), approve-401 checker (`a2db3e6`), IFMS-WF (`ad2295e`), comp (`a752876`) and UI-parity-2 (`a91c784`). |

## 2. Git and GitHub state (verified 2026-11-09 01:32 UTC)
- **Main tree:** `D:\GITHUB  Project\Custom WorkFlow` is on branch `handoff/2026-11-08` @ **`16e0e11`**, and the working tree is clean.
  - Chain since `9c831d0`: `2aaf44f`, `7cac6c4`, `f553b43`, `3736f8f`, `0d04fea`, `eba6e62`, `460c95b`, `16e0e11`.
- **Remote:** `origin/main` = `15e4135`; `origin/handoff/2026-11-08` = `7cac6c4`.
- **PRs:** PR #4 is a draft. Dependabot PRs #2 (maven) and #3 (npm) are open.
- **Pending: fast-forward push of `16e0e11` to main**, through the signed gate (HANDOFF §5).
  - Fresh approval text: `D:\GITHUB  Project\sangam-handoff\2026-11-08\push-approval-main8.txt`. It expires at 02:02 UTC on 2026-11-09; regenerate it if it has expired.
  - After the push, the owner's 17:04 order also covers merging #2 and #3 (check for conflicts first), then sending UNFREEZE.

## 3. Results produced after the agents died (their scripts kept running)
- **SL-45 rerun 4** (`%TEMP%\sl45-it5.log`, PostgreSQL): `CrossTenantIsolationIT` ran 5 tests and **3 FAILED**.
  - The test setup now works, so these are **candidate real leaks**: "tenant A's calls changed tenant B's rows".
  - Tables affected: act_hi_op_log, act_hi_procinst, act_re_deployment, act_re_procdef, act_ru_execution, act_ru_task, outbox, outbox_delivery, root_seq.
  - Before concluding, check which of these rows are shared or global (`root_seq`, a deployment), as opposed to B-owned rows.
  - Then root-fix in the shared tenant-scoping layer (`wt-iso`).
- **approve-401 rerun** (`<oldscratch>\auth401-it6.log`): `PlugAndPlayIT` ran 7 tests, with **1 failure** and 1 skipped.
  - Reports: `wt-auth401\sangam\sangam-server\target-auth401\failsafe-reports`.
  - Not yet read: the 401 cause, the D-REFERRAL result, and T35.
- **Matrix, SQL Server 2022** (`<oldscratch>\mss22.log`): 175 tests ran, with **17 failures, 44 errors and 4 skipped**.
  - Most errors are test-harness gaps: `LayerSpecSupport` "IsolatedProductTables needs PostgreSQL (search_path)", which hit AuditChain, Inbox, OperationLedger, OutboxRelay, Tombstones and WebhookRelay; and `TenantQuotaIT` "no SEC_TENANT row". These need a generic, dialect-aware isolation, not a skip.
  - **To triage:** E2Authorization (4 errors), E3Lease (2 failures), E4Idempotency (4 failures, 2 errors), E5 (1), E7Delete (3), WorkApiLedger (3), DialectSchema (5 errors), AuthorizationPolicy (1), IdempotencyStore (1), and one each in E16a, E16b, E16c and E17a (GP-13, GP-21 and D-TENDER are known).
  - **Not run:** Oracle 23, MySQL 8.4 and SQL Server 2025.
- **comp (GP-13 / GP-21) PostgreSQL run:** the lock served it at 23:10 UTC. Its logs in `wt-comp` are not read yet.
- The `customwf-*` heavy lock queue is now empty; the last served run was IFMS `trial0` at 23:45 UTC.

## 4. Agents' last known state
Worktrees are under the OLD scratchpad, `<oldscratch>` = `C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\`. It is still present; copy it to D: before relying on it. Snapshot 2 (`f553b43`) also holds the files as of about 17:55.
- **IFMS-WF** (`wt-ifmswf`): CONTRACT sent.
  - `packs.py pin_cross_pack_calls` adds `versionTag` binding to 388 cross-pack calls. `--check` passes: 1086 files identical.
  - New `CrossPackCallBindingTest`, plus the `SangamModelValidator` EXT finding.
  - The post-fix PostgreSQL proof (E46 9/9, IfmsPacksIT 6/6) has not been seen. **This is apply order step 1.**
- **UI-parity-2** (`wt-uip2`): all 8 items green.
  - Additive APIs: `variableNames` (at most 20), `activityInstanceId` filters, history `state` filter, and `400 FILTER_UNSUPPORTED`.
  - Root fix: the `OperatonTaskAdapter.list` OR-branch. Without it, the task list failed for every non-admin user.
  - UI items: P13, P14, P35 (filters kept in browser storage), P45, P49, and the duplicate label.
  - Not applied, because of the freeze. Found on the way: H2 `BITAND` history-permission issue.
- **DST / SL-53** (`wt-dst`): repro only; waits for the owner's decision.
- **approve-401, SL-45, comp, matrix**: see §3. **packs4**: unchanged (HANDOFF §6b).
- **T-SCALE throughput**: can now be measured, because the SL-43 fix is in. Use `run-tscale.ps1` against the main tree.

## 5. Open owner decisions
1. **Push:** sign the latest `push-approval-main*.txt`, then write "signed".
2. **SL-53 (HIGH): DECIDED 2026-11-09 by the owner, verbatim "b: engine JVM in UTC".** The engine JVM must run in UTC and the server refuses to start otherwise. The tenant zone comes only through `sangam.engine.timer-zone` / `BusinessClock`. Record this as an ADR-D-27 candidate in SANGAM.md at the next integration. The options were:
   - (a) Store engine timestamps in UTC, with a migration.
   - (b) Make a UTC engine JVM mandatory; the tenant zone then comes only through `timer-zone`/`BusinessClock`. This is the recommended option.
3. Smaller items, in HANDOFF §6b-5 and §6c:
   - the SL-43 literal "never a second connection" note;
   - AC-08 downloads (.NET 8, PHP);
   - `S3DocumentStore` testability;
   - `CONTENT_ITEM.tenant_id` versus the `_shared` key;
   - the `task.suspend`/`task.activate` events.

## 6. Resume plan
1. Push `16e0e11` to main once signed, merge the Dependabot PRs, and send UNFREEZE.
2. Re-dispatch the workstreams, keeping the HANDOFF §7 apply order: IFMS-WF, then approve-401, then packs4, then SL-35.
   - In parallel, each in its own worktree: the SL-45 leak fix; applying UI-parity-2; comp; the matrix (SQL Server 2022 triage, Oracle, MySQL, SQL Server 2025); a generic module-controller ProblemAdvice (§6b-3); the JIT race (§6b-5); SL-53 after the decision; T-SCALE throughput.
3. The SD-428 pass, then the full H2 suite, then the next signed push, then a REPORT to WorkFlow and IFMS main.

## 6a. Progress 2026-11-09, resuming session
- **01:40 UTC:** the owner signed `push-approval-main8.txt` ("signed"). Gate record #2 was made, and the gate re-verified it at push time. **`16e0e11` was pushed to main by fast-forward (`15e4135..16e0e11`).**
  - PR #4 is MERGED.
  - **Dependabot PR #2** (spike JDBC bumps, spike-only, CLEAN) was merged, giving merge commit **`1105d9c`**. That is now `origin/main`.
  - **Dependabot PR #3 was NOT merged.** It is CONFLICTING and makes major-version jumps (vite 5→8, vitest 2→3, react-router 6→7, esbuild 0.21→0.28), and its own CI was red. **Owner decision 2026-11-09, verbatim "Separate upgrade later":** leave PR #3 open, and do the UI toolchain upgrade later as its own workstream (UI-UPGRADE-1) with the full UI suite. It is not part of the current batch.
- **Main CI on `16e0e11`:**
  - `ui` passed.
  - `sdks` FAILED: stale native metadata, because the SDK model gained `WorkOperation` and `WorkOutcome` (SL-16) without a regeneration. The generator was re-run in the integration worktree; the fix is uncommitted until the next push.
  - `security` was running.
  - `build` and `db-matrix` did not trigger on the push, so they were dispatched manually on `1105d9c` (runs 42547172757 and 42547175740).
- **Integration tree from now on:** `D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524`, branch `claude/workspace-scan-resume-06b524`, based on `origin/main`.
  - The base checkout stays on `handoff/2026-11-08` @ `16e0e11`; do not use it.
  - Agents apply targeted Edits to the integration tree only.
- **FREEZE lifted.**
- **SL-53 fix agent:** running in `wt-dst`.
- **Main hygiene:** `handoff/worktree-snapshots/**` on main is picked up by Dependabot and the dependency graph. Remove it from main once every workstream has applied its work (a follow-up).

## 6b. Joint ledger with IFMS (2026-11-09)
- **IFMS session on this login:** "Session hierarchy और collaboration recall" (`local_dbde6646-ddbf-4df5-bc21-f1c14ed060b7`). It runs in the IFMS worktree `resets-display-expiry-eeb1fe`, and is re-establishing the IFMS hierarchy and the collaboration with Sangam.
- **This Sangam session:** `local_30e74cc4-d0bb-476b-8128-12f9367ffa52`.
- **Owner's order:** fix the single ledger both sides share, then plan together from it. So `D:\GITHUB  Project\JOINT-LEDGER-SANGAM-IFMS.md` was created as PROPOSED and sent as `[CONTRACT] JOINT-LEDGER`. It is awaiting IFMS ACK.
- **Joint plan:** being drafted by workflow `wf_8248786f-040`, to go into `D:\GITHUB  Project\JOINT-PLAN-SANGAM-IFMS.md`.

## 6c. Progress 2026-11-09 ~03:00 UTC
- **Joint ledger:** BINDING (IFMS SD-447). IFMS master = session `local_dbde6646…` (SD-446; holds every former IFMS role). IFMS oneifms main = `80dc4184` (fast-forwarded 02:55 UTC). The Trial 2 retest of Sangam `1105d9c` is queued (IFMS P-444, lock owner `sangam`).
- **Answers sent to IFMS:**
  - SG-34 is in `2aaf44f`; SG-29, SG-26 and SG-28 are in `15e4135`; SG-27 is closed by owner exception.
  - The Jackson 2.22 line is compatible (the guard accepts 2.22.x).
  - `cwf-docs-wt` is fully superseded by main. Deleting it needs owner approval (JL-S-01).
- **IFMS-WF report:**
  - PostgreSQL: E46 9/9, IfmsPacksIT 2/6. Unit suites: core 75/75, packs 74/74, flowable-ports 266/266.
  - The integrator chose option (A): apply step 1 now, because the 6/6 gate was circular. IfmsPacksIT stays red and reported until steps 2 and 3 land.
  - New register ids: **SL-54** (versionTag binding ambiguity), **SL-55** (failed execution → rejection with no reason → endless inbox retries), **SL-56** (CRLF breaks `packgen --check` and E46 SHA). They were added to `sangam/TESTING-GAP-REGISTER.md` in the integration tree.
  - IFMS-WF must not touch `gov_ifms.py` until approve-401 (step 2) has landed.

## 6d. E-56/REC-06 extraction findings (2026-11-09 ~03:40 UTC)
The full text of the read-only reader's output is in the task output for agent `ac9abd6e359dffd42`. Key points:
- **E1 (P0), verified at source:** the WP-04 adapter does NOT implement the SD-445 mapping. A retest of X-13 on `1105d9c` would falsely re-open SG-35. Sent `[INCIDENT] SG-35-ADAPTER` with `[CONTRACT] ROUTING-INCIDENT v1` to IFMS.
- **E2:** consumers pin `0.1.0-SNAPSHOT`; the only link to a commit is a private `.m2` repo. Sangam's local `main` ref was stale (`9c831d0`) and is now fast-forwarded to `1105d9c`. A release/RC tag would need owner approval (SG-02).
- **E3:** `handoff/worktree-snapshots/**` and stray root files (`CmmnTimerEventListenerH2Test.java`, `PackAcceptanceTest.java`, `gen_openapi.py`) are on main, and they pollute whole-tree scans. `2aaf44f` is "WIP, not verified" and needs the SD-428 check.
- **E4:** under a Spring Boot parent, the imported `sangam-bom` loses to the parent's management (JL-S-04).
- **E8:** the SL-53 (b) strict-UTC guard would also refuse the IFMS JVM that embeds the engine in IST (JL-S-03). The SL-53 agent has been told to keep the guard predicate pluggable. The owner's re-decision is pending, after IFMS answers.
- **E9 / E14:** pack-data ownership (JL-S-05) and the GA-55 bill-type contract (JL-S-06) need a joint decision.
- **E11 / E15:** register reconciliation (JL-S-07). Missing rows: SG-30/33/34/35, T-4 SMOKE FAIL, P-419 hang.
- **E16:** the heavy-lock plan across both projects (IFMS needs 18 lane runs and an 8 h soak).
- **E17:** Operaton remains IFMS's selected engine. Sangam is a candidate behind the WFR port, so IFMS's critical path does not depend on Sangam unless the sponsor adopts it.

## 6e. Step 1 applied (2026-11-09 ~04:10 UTC)
- **IFMS-WF applied to the integration tree, uncommitted.**
  - Changed files: `packs.py`, `SangamModelValidator` and its test, `IfmsPacksIT`, `CrossPackCallBindingTest`, `e46/.gitattributes`, and new LF rules in the root `.gitattributes` (SL-56). 97 BPMN files were regenerated.
  - Checks: compile green; unit suites core 75/75, packs 74/74, flowable-ports 266/266; SHA identity 1111/1111; `packgen --check` OK.
  - About 1008 `git status` entries are line-ending only.
- **IfmsPacksIT** stays 2/6 on PostgreSQL until steps 2 and 3 land.
- **Step 2:** `STEP1-LANDED` was sent to approve-401, which now runs phase 2.
- **New register ids:**
  - SL-57: budget distribution SoD seat clash (pack data; seat model requested from IFMS, citing CAM 2.2.4).
  - SL-58: T-4 SMOKE FAIL, from E-54.
  - SL-59: P-419 hang (unbounded JDBC timeouts).
- **IFMS:** ACKed ROUTING-INCIDENT v1. It opened P-452 to implement the mapping in WP-04, and holds X-13 until then (incident I-55).

## 6f. Usage-limit outage and recovery (2026-11-09 ~05:20 → 18:30 UTC)
- **What the 5-hour limit stopped at ~05:20 UTC:**
  - the agents SL-53 (wt-dst), SG-36 (wt-sg36), SL-45 (wt-iso), approve-401 (wt-auth401, phase 2, at "the DevTokenFilter change") and UI-PARITY-2 (wt-uip2);
  - the joint-plan workflow's reviser (planner, critics and 6 collectors had finished).
- **Integration tree after the outage (verified 18:30 UTC):** intact, and holds only step 1 plus the register and native metadata. No agent left a half-applied change.
- **INCIDENT, stale heavy lock:**
  - `customwf-iso` was held from 03:39:37 UTC for ~14.7 h. The SL-45 agent's in-shell job died and its `finally` never ran.
  - IFMS logged it (`[INCIDENT] HEAVY-LOCK-STALE`) and touched nothing.
  - Sangam verified that no job or container was alive, then released it at **18:25:51 UTC** (exit 0).
  - Root fix:
    - (1) Sangam heavy runs go ONLY through a detached runner (acquire → run → release in its own `finally`); agents never hold the lock in their own shell.
    - (2) `[REQUEST] HEAVY-LOCK-LIVENESS` to IFMS, the tool owner: holder pid + start time + lease heartbeat, and stale auto-release with an audit line.
- **Resumed by SendMessage with their contexts intact (18:30 UTC):** SL-53, SL-45, approve-401, SG-36 and UI-PARITY-2, each with the detached-runner rule.
- **Joint plan:** the workflow was resumed with the reviser fold-in, covering E-56 extraction, the joint ledger and the post-draft facts, plus a final completeness check and fix (`wf_8248786f-040`, task `wylis0na4`). The E-56 extraction is saved at `scratchpad\e56-extraction.md` in session `6e98c2a7…`.
- **IFMS during the outage:**
  - its workflows resumed from their journals;
  - the `ifms-integ` reactor run 1 failed at spotless; fix `7710acf1` is unpushed; run 2 is running under the compile lock;
  - SL-18 review done; records committed (I-58);
  - still waiting for JOINT-PLAN.
- **Session ids unchanged:** SANGAM `local_30e74cc4…`, IFMS `local_dbde6646…`. ListAgents refs changed after the app restart, so always address sessions by name or id.

## 6g. Main CI on `1105d9c`: root causes and fixes (2026-11-09 ~19:00 UTC)
- **build / db-matrix / security (OWASP, CodeQL) red:** COMPILE error in `sangam-security` `ResolvedIdentityCache` (SL-63).
  - Cause: the inherited `Map.Entry` shadows the record `Entry` in the anonymous `LinkedHashMap` subclass.
  - Local JDK 21.0.10 javac accepted it; CI's 21.0.13, 25 and 27 refuse it.
  - Fixed in the integration tree (record renamed `CachedIdentity`). Red on JDK 25 locally, then green. Full reactor `clean test-compile` on JDK 25 is BUILD SUCCESS. `ResolvedIdentityCacheH2Test` 9/9 on JDK 25.
  - New rule in HANDOFF §2: compile gates also run on JDK 25 (`.tools`).
  - IFMS informed (`[INCIDENT] SANGAM-MAIN-COMPILE`; retest option (b): pin `460c95b`).
- **Compose + Helm lint red:** ShellCheck SC2046/SC2034 in the airgap scripts (SL-64). Fixed in the tree; CI verifies on the next push.
- **sdks red:** stale native metadata; regenerated in the tree earlier.
- **SG-36:** fix proven, red 15/15 → green 32/32. Go-ahead given to apply. New findings: SL-61 (secret-port failure → OutcomeUnknown) and SL-62 HIGH security (case-insensitive header collision lets a request `authorization` header precede the connector's), assigned to the same agent.

## 6h. Second usage-limit outage (2026-11-09 ~20:40 → 23:15 UTC) and recovery
- **What the limit stopped:**
  - the agents SL-45, SL-53, SG-36 (mid-apply of SL-61/62), APPROVE-401 (mid ConfigConnector edits in its worktree) and UI-PARITY-2 (gates running);
  - the HEAVY-LOCK-V2 review workflow (the concurrency reviewer finished; the adoption reviewer and all verifiers failed);
  - the joint-plan workflow's final-check (the reviser finished: plan v0.2).
- **Detached runners worked as designed.** SL-45's red/green JDK 25 runs completed and released the lock (19:29 and 19:50 UTC). The lock was free at 23:17 UTC. The only processes left running are VyaparTrust's `vt-runner` (not ours; untouched).
- **Partial apply found:** `HttpRestConnector.java` (SL-61/62) applied, but its 3 test files were not, and `HttpRestConnectorHeadersTest` is a different copy. The SG-36 agent was told the exact state and must finish the apply first. No other agent left a partial apply.
- **All 5 agents resumed** with context.
- **Correction C-1:** CI JDK 21.0.13 DOES compile `1105d9c`; only JDK 25 and 27 fail (SL-63 text corrected; IFMS informed). The JDK 21 job failed in `sangam-server` tests: 83 run, 2 F, 20 E.
  - **SL-67:** the D-10 one-engine-per-JVM cascade in a single surefire JVM. Fix agent wt-d10.
  - **SL-68:** bare citations. Fixed and green.
- **R-99 v0.1 received from IFMS** (budget-distribution seat model; SL-57 data). It is encoded after step 2.

## 6i. SL-53 final decision (2026-11-10 ~00:20 UTC)
- **Owner, verbatim, in the Sangam session:** "IFMS की तरफ़ की शर्तें मंज़ूर हैं।"
- **What it confirms:**
  - (b1) strict UTC now for every engine JVM, including embedding hosts (IFMS WP-04);
  - (a) UTC persistence as the scheduled target SL-53a, with the date still the owner's;
  - IFMS conditions P-463: `sangam.engine.timer-zone=Asia/Kolkata`, `-Duser.timezone=UTC` argLines, the six `LocalDate.now()` fixes, the Timestamp and timer round trip, the time-rule SD, and the ArchUnit ban.
- **The facts behind it:** IFMS R-98 showed that (b2) would store IST wall time in a UTC-convention DB, and only (b1) avoids a later +05:30 shift.
- **Actions:** the SL-53 agent was told "WIRE b1" (it reports before applying); the decision was relayed to IFMS with the verbatim quote.
- **Adoption caveat for IFMS:** embedding JVMs must be UTC and must set `timer-zone` BEFORE pinning the Sangam sha that contains SL-53.

## 6j. Joint plan v0.3 sent; owner decisions (2026-11-10 ~01:35 UTC)
- **JOINT-PLAN v0.3** is written to `D:\GITHUB  Project\JOINT-PLAN-SANGAM-IFMS.md` and was sent as `[CONTRACT] JOINT-PLAN`. It holds 34 owner questions (Q1–Q35) and conflicts K-1..K-44. The source copy is in the scratchpad: `JOINT-PLAN-v0.3*.md/txt/json`.
- **Owner decisions** (in each case the recommended option was chosen; recorded in JL §4):
  - P1 may carry known-red tests, each with an SL id and listed in the signed approval text; from P1b on, only fully green.
  - The UI toolchain upgrade already on main via `2aaf44f` is accepted after the SD-428 review with the UI suite green.
  - The AT-3 expectation change is accepted (ACCEPTANCE-PLUG-AND-PLAY.md L4–L6).
  - RC route: tag `v0.1.0-rc.1` on the first all-green main; IFMS builds privately; no registry publication before SD-419/422.
- **Apply step 2 (APPROVE-401) is applied.** New ids SL-76..SL-81. The next free id is **SL-82**.
- **REG SL-65 row repaired** (K-33). The integrity check is clean apart from the pre-existing 6-cell rows SG-27 and SL-17.
- **Still-open owner questions** (see the plan): Q6 cwf-docs-wt removal, Q7 SL-43 leaf pool, Q12 downloads, Q13 security reviewer, Q14 required checks, Q17 private vulnerability reporting, Q18 NVD_API_KEY secret, Q24 delete merged branches, Q26 remove old worktrees, Q27 the HANA container, Q34 SL-53a date, plus others.

## 6k. State at 2026-11-11 04:40 UTC (after the third limit outage and a network outage)
- **Standing rule (owner, 2026-11-11):** after ANY interruption, run the memory `feedback-unstable-internet-recheck` checklist.
- **Running:**
  - the DB-matrix detached runner `runner-matrix.ps1`, pid 31660. It holds `customwf-matrix`; Oracle 23 is running. Order: Oracle → SQL Server 2025 → MySQL → SQL Server 2022. Logs: `scratchpad\runner-matrix.log` and `matrix-<db>.log`. The snapshot hash is D0DA89557B07EF27.
  - agents SL-53 (`a559ce3`, applied; final suites), APPROVE-401 (`adbd0a0`, SL-76/SL-81), SL-67 (`acab37a`, point-8 runs plus SL-82, then apply) and IFMS-WF (`af56745`, R-99/SL-57, background gate).
- **Applied in the integration tree:** steps 1 and 2; SG-36 + SL-61/62/65/66; SL-45 + SL-69; UI-PARITY-2 + SL-60; SL-53; SL-63, SL-64, SL-68, SL-74.
- **Owner decisions** are in JL §4: SL-70 = platform issuer; P1 may be known-red; UI upgrade accepted; AT-3 accepted; RC tag without a registry.
- **IFMS:**
  - JOINT-PLAN v0.3 ACK/AMEND received (R-100); amendments (a)–(d) accepted, edits still to make.
  - HEAVY-LOCK-V2: no swap; v2.1 pending, then Sangam's second review.
  - P-452 has been pushed; P-466 and P-463 come next.
- **Next free SL id:** SL-84.

## 6l. Shared AWS config, DB matrix, SL-84 (2026-11-11)
- **Shared config:** the owner approved it ("I Approved Go Ahead"). The Sangam part is applied (KMS, IAM, OIDC role, `sangam-local`, `/shared/github/org`) and verified by simulation. See `D:\GITHUB  Project\SHARED-CONFIG-AWS.md`. Still owner actions: the access key for `sangam-local`, the NVD key value, and an admin user with MFA instead of root.
- **DB matrix:** Oracle exit 1 (33/36 IT classes). Root cause: ORA-01400, empty `parent_code` in `CFG_MASTER_CODE` (Oracle `''` = NULL). Fix agent dispatched (wt-sl84); IFMS informed. The runner continues with SQL Server 2025, then MySQL, then SQL Server 2022.
- **Next free SL id:** SL-85.

## 6m. SL-57 applied; matrix findings SL-86..SL-89 (2026-11-11 06:15 UTC)
- **SL-57:** applied to the integration tree by IFMS-WF (gates green on JDK 25 and 21); PostgreSQL IfmsPacksIT runner pid 29408 queued (lock ticket `customwf-ifmswf-19924`). Resume IFMS-WF when it ends to report each case.
- **SL-84:** fix prepared in `wt-sl84` (`AbsentKey.NONE = "-"`, data changeset 0033-01). Integrator decision sent: apply only after the Oracle 23 AuditChainIT (runner pid 33768) is green, regenerate in the integration tree; take `QueueService` `RES_TOKEN_SEQ.series` under SL-84; design proposal for SL-86/SL-87 before any edit.
- **New:** SL-86 (tenant `''` in NOT NULL keys, Oracle), SL-87 (empty NOT NULL content columns, Oracle), SL-88 (SQL Server untyped NULL bind on `HRM_PROCESS_INSTANCE.end_time`; fix agent `wt-sl88`), SL-89 (SQL Server `SANGAM_FETCH_LOCK` lock timeouts, triage after SL-88).
- **Infra:** at 05:41Z Docker restarted (all containers exit 255; VyaparTrust and HANA containers came back up, not Sangam's). The SQL Server 2025 leg is invalid after that; I stopped its test JVM (pid 22888, my own runner) at 06:07Z and the runner moved on to MySQL. The SQL Server 2025 leg must be rerun after the matrix.
- **Agents running:** SL-84 (decision sent; also owns the SL-86/87/90/91 design proposal), SL-70/72/73 security, SL-88 (pointer: core `Jdbc.bind` binds `Types.NULL`). New after the engine tenant sweep: SL-90 (pack ledgers bind a null tenant into NOT NULL PKs, all DBs) and SL-91 (`setDefaultTenantId` unchecked). IFMS impact: SL-88 exposed (SQL Server lanes), SL-86 not reachable from sangam-engine alone, SL-90/91 not exposed per IFMS EV-M-0521. Integrator ACCEPTED the SL-84 agent's designs (2026-11-11): SL-86 uses `TenantKey.NONE` = the engine `defaultTenantId` `"default"`, reserved at every entry point, with per-table data changesets and a collision preflight; SL-90 is folded into SL-86; SL-91 makes the setter refuse blank; SL-87 is per column. Order: SL-84 apply after the Oracle proof, then SL-86+90+91 in a fresh copy, then SL-87. SL-84 now also covers QueueService `RES_TOKEN_SEQ.series`. NEW SL-92 (HIGH): `findAndRegisterModules` picks up jackson-module-scala, so nested config maps come back empty (resources `Sql:29`, ports `Json:43`, server `ComplianceAuthorityGateway:28`); fix agent in `wt-sl92`. IFMS loads sangam-engine only, so SL-92 does not touch it. **Next free SL id:** SL-93.

## 6n. Network outage ~07:10 UTC and recheck (2026-11-11)
- **Outage:** ENOTFOUND stopped 3 agents: security (SL-70/72/73), SL-88 and SL-92. All three were resumed with SendMessage at 07:15Z, along with their last step and the source-package correction.
- **Recheck at 07:14Z:**
  - The lock is still with `customwf-matrix`. Queued behind it: auth401, ifmswf, sl84, sl88.
  - Runner pids 31660 (matrix), 29408 (ifmswf PG), 33768 (sl84 Oracle) and 33152 (sl88 SQL Server) are all alive.
  - The matrix is on its SQL Server 2022 leg (container up).
- **Matrix results so far:**
  - Oracle exit 1, caused by SL-84.
  - SQL Server 2025 exit 1: SL-88, SL-89 and SL-93, and the leg is invalid after 05:36Z because the Docker restart dropped the container.
  - MySQL exit 1: 125 of 127 tests failed, caused by the new SL-94 (the engine patch `P-2-mysql-utf8mb4` hits the FK `ACT_FK_BYTEARR_DEPL` incompatible error; the code has not changed since 15e4135). A fix agent is on `wt-sl94`.
  - The matrix snapshot predates SL-82, so its capacity NPE is already fixed in the tree.
- **SL-88:** fix prepared in `wt-sl88` and the design accepted. It applies after the SQL Server runner 33152 is green. SL-89 is shown not to be a consequence of SL-88.
- **Lesson:** a robocopy that excludes `target*` also drops the source package `sangam-programme/.../programme/target`, (the SL-88 agent hit this). CORRECTION: my own check reported it missing from `wt-sec`, but I used a wrong path; `wt-sec` has no `sangam\` level and the package is present, and the security agent's hash compare confirms it. `wt-sl92` has it now. Use only `/XD node_modules dist target-*`.
- **Register:** the SL-88 row was broken by an embedded newline and has been repaired to one line. All SL rows now have 7 cells, except SL-17, which was already that way in HEAD.
- **Next free SL id:** SL-95. **Rerun needed:** a SQL Server 2025 leg from a fresh snapshot after SL-84, SL-88 and SL-94 are applied.

## 6o. SL-92 applied; SL-94 prepared (2026-11-11)
- **SL-92** (Jackson module discovery; production affected through feel-engine's jackson-module-scala) was APPLIED to the integration tree. It covers 10 files: 3 mappers, 3 poms and 4 new tests, including the reactor-wide `JacksonModuleDiscoveryGuardTest`.
  - SHA-256: `Sql.java` 08b05377…, `Json.java` 26f3a7ea…, `ComplianceAuthorityGateway.java` fdab9489…, resources pom 9e470809…, flowable-ports pom 2a0249f0…, server pom 4e509195…, guard ad3dcf72…, `SqlJsonTest` d6133c06…, `QueueNestedSettingsH2Test` 44f03ca0…, `JsonModulesTest` 82759373….
  - Integration-tree gate on JDK 25 is green. Log: scratchpad `sl92it-test-jdk25.log`.
- **SL-94** (MySQL P-2 utf8mb4 FK error 3780): design accepted, prepared in `wt-sl94`. It will be applied after the MySQL runner (pid 36488, queued) is green.
- **Done (owner: "Yes, add it"):** `kms:ViaService` was added to Sangam's two SSM read policies (now v3) and verified by simulation. IFMS's OneIFMS AWS part is applied and verified (see SHARED-CONFIG-AWS.md).

## 6p. Matrix finished; plan amendments applied; SL-93 dispatched (2026-11-11 ~09:40 UTC)
- **Owner:** "I Approved Continue" (after "Yes, add it" for `kms:ViaService`, which is done).
- **DB matrix** finished at 09:10Z and released the lock. Every leg exited 1:
  - Oracle: SL-84.
  - SQL Server 2025: SL-88, SL-89 and SL-93; invalid after 05:36Z.
  - MySQL: SL-94.
  - SQL Server 2022: 211 tests, 39 failures and 45 errors; SL-88 dominant (546 errors); SL-89 timeouts on `SANGAM_FETCH_LOCK` and `ROOT_SEQ`; the rest is probably a cascade.
  - Rerun the matrix from a fresh snapshot after SL-84, SL-88 and SL-94 are applied, then triage what is left.
- **Lock queue at 09:24Z:** `customwf-ifmswf` (SL-57 PG IfmsPacksIT) holds the lock; then sl84 (Oracle), sl88 (SQL Server 2025) and sl94 (MySQL); SL-93 will join. The auth401 T35 run was served at 09:10Z; its result is not yet triaged (APPROVE-401 workstream).
- **SL-93:** fix agent dispatched (`wt-sl93`, owner `customwf-sl93`).
- **JOINT-PLAN amendments (a)-(d):** applied now. The ACK at 04:35Z claimed they were applied but they had not been; the correction is logged in JL §5.

## 6q. OWNER PAUSE at ~09:58 UTC 2026-11-11 ("pause all things immedialty will resume after 6 hour")

**What was stopped:**
- All Sangam agents: security (SL-70/72/73), the independent SL-70 reviewer, SL-93, SL-94, SL-88 and SL-84. SL-92 was already finished.
- The detached runners for SL-88 (pid 33152, holding the lock and running its SQL Server 2025 IT), SL-94 (36488) and SL-93 (29284) were killed with their whole process trees.
- `customwf-sl88` was released by its owner. The sl93 and sl94 queue tickets drop by themselves after 90 s.
- No Sangam JVM or container is left. The only containers still running are the non-Sangam postgres and HANA ones.

**State to resume from (nothing committed):**
- **Integration tree:** contains SL-57, SL-92 and every earlier applied item, all uncommitted.
- **SL-57:** the PostgreSQL IfmsPacksIT (IFMS-WF runner, finished ~09:40Z) FAILED with 1 error, `ifmswf-pg1.log`. Triage it with the IFMS-WF agent (af5674588bc930fc7, resumable); it reports per case and points to SL-55 if reappropriation is red.
- **SL-84:** the Oracle 23 `AuditChainIT` (runner finished 09:43Z) FAILED with 8 of 8 errors, `sl84-oracle-it.log`, so the fix is NOT proven. Triage before any apply. Also resume the SL-84 agent (aa5a53d58600d37c6), which owns the SL-86/87/90/91 implementation after SL-84.
- **SL-88:** prepared in `wt-sl88` with the design accepted. Its SQL Server 2025 IT was killed mid-run, so re-queue `runner-sl88.ps1`. Agent: a7402b58a8ed7bf5f.
- **SL-94:** prepared in `wt-sl94` with the design accepted. Its MySQL IT never ran; re-queue `runner-sl94.ps1`. Agent: a58e3da30f4ed1c94.
- **SL-93:** prepared in `wt-sl93`, one file (`DialectSchemaIT` moved to `ContractSupport` with prefixed probe tables). Its runner never ran; re-queue `runner-sl93.ps1`. Agent: ab26e7474f8868f4e. It also found that `IsolatedProductTables` is PostgreSQL-only, so every `LayerSpecSupport` IT fails on SQL Server, MySQL and Oracle. Register that as SL-96.
- **SL-70:** prepared in `wt-sec`, with JDK 25 and 21 green (server 118/118). Before any apply it still needs the PostgreSQL acceptance set (owner `customwf-sec`, never queued) and the independent review (stopped before it reported, so restart it fresh). Agent: a04d2d691806c4475. SL-72 and SL-73 come after it. SL-95 (cell-admin) is registered.
- **Matrix:** rerun all four legs from a fresh snapshot after SL-84, SL-88 and SL-94 are applied.
- **Next free SL id:** SL-96, to be used for the `IsolatedProductTables` finding.

## 6r. Resume 2026-11-11 18:30Z and the owner's new order (2026-11-12)
- **Recheck at resume:** the lock was free, no JVMs were running, the tree was unchanged, and Docker had restarted (machine reboot).
- **Triage of the two red runs:**
  - SL-84 Oracle: the server started, there were 0 `ORA-01400` errors, and changesets 0033-01/02 ran. The 8 `AuditChainIT` errors are SL-96 (new, registered): `IsolatedProductTables` is PostgreSQL-only. SL-84 is approved to apply.
  - SL-57 PostgreSQL: the IfmsPacksIT set-up hit an NPE at `JdbcLedgerStore.currencyOf:142` → `Jdbc.one:98` (`Optional.of(null)`). The IFMS-WF agent is root-causing it.
- **Owner order (2026-11-12, saved in memory `feedback-code-first-then-push-then-test`):**
  1. Code everything end to end, with unit tests written.
  2. Update all docs.
  3. Push to main through the owner-signed gate.
  4. Then run tests stage by stage (unit → system → integration → acceptance) and write the E2E report.
  5. Centralise stray Sangam work into the one main folder; delete folders only after the owner confirms.
  6. The IFMS knowledge base may be used for research and linking.
- **Agents switched to code-first** (compile-only gates, no DB runners):
  - SL-84 → then SL-86+90+91 → then SL-87.
  - SL-88, SL-94 and SL-93 → then SL-96.
  - IFMS-WF: the SL-57 set-up NPE.
  - Security: SL-72, SL-73 and SL-95 now; SL-70+ applies after SL-88.
  - Two read-only inventory agents: all open items, and stray work across the workspace.

## 6s. Code-complete phase in progress (2026-11-12)
- **Plan and claims:** `sangam-handoff\CODE-COMPLETE-PLAN-2026-11-12.md` (waves A, B1-B5, C and D) and `sangam-handoff\FILE-CLAIMS.md`.
- **Applied to the tree (compile gate green, not tested):**
  - SL-84 (13 files, generator regenerated)
  - SL-88 (16 files)
  - SL-93 (1 file)
  - SL-94 (6 files)
  - SL-97 (IN currency data plus `ProfileDataTest`)
  - SL-57 and SL-92 from earlier
- **Owner decisions:** Q7, Q8/Q9, Q10 and Q22 answered (recommended options, see JL §4).
- **New ids:** SL-97 (profile currency gap) and SL-98 (refuse a money ledger with no currency; B1). SL-96 design chosen: namespace per database, filled by the product's own schema path. **Next free SL id: SL-99.** The GP-13 compensation and JIT 409 items take the next ids when their agents propose them.
- **Running:**
  - SL-84 agent: SL-86, SL-90, SL-91, then SL-87
  - SL-88 agent: SL-89, `Jdbc.one` diagnosability
  - SL-93 agent: SL-96
  - IFMS-WF: all-profile currencies
  - Security: SL-70, SL-72, SL-73, SL-95, SL-71, SL-83, JIT race
  - B1: money and ledger, PACKS4, SL-98
  - B2: engine and FEEL
  - B3: REST and packs, SG-03
  - B4: CI and deploy, Q22
  - B5: SDKs
  - Stray-work scan
- **Gate lesson:** use `clean test-compile`. Incremental builds missed `Jdbc.setNull` because files were applied with preserved timestamps.

## 6t. Stray-work scan, safety snapshot and progress (2026-11-12)
- **Safety snapshot of the uncommitted integration tree:** `sangam-handoff\snapshots\<UTC stamp>\`. It contains `tracked.patch` (git diff --binary vs HEAD 1105d9c, 1.5 MB), `untracked.tar` (80 files) and `BASE.txt`. Restore with `git apply` plus `tar -xf`.
- **Scan results** (full report in the agent output; recommendations need the owner's confirmation before any deletion):
  - Already in main or the integration tree: the base checkout, cwf-docs-wt, wt-fwt, D:\sangam-wt-ci, and the old-session wt-auth401/bulk2/forms/fwtd/iso.
  - Still to merge: wt-packs4, giving SL-33 PFM/GDPR packs (B3) and PACKS4 money (B1, after APPROVE-401 2b).
  - Still to merge: the wt-comp `.mine` files, giving SL-101 (B2).
  - Still to merge: the D:\wt-scale T-SCALE text evidence (SL-93 agent; the 1.6 GB `ev.json` is excluded).
  - Still to merge: `BulkQuota.java` from wt-bulk (SL-93 agent decides whether it was superseded or lost).
  - Dependabot branch `origin/dependabot/npm_and_yarn/sangam/sangam-ui/...` (77f51234) is not merged. The owner said earlier, "Separate upgrade later".
- **IFMS knowledge base for Sangam linking:** under `D:\GITHUB  Project\IFMS\` see `DOCS\1_RESEARCH` (R-76..R-100), `DOCS\4_ENGINEERING` (E-45..E-59), `DOCS\3_FINAL\F-07`, `10_COMMON_IFMS_BLUEPRINT`, `09_IFMS_COMPREHENSIVE_SYSTEM_STUDY`, `06_STANDARDS_INTEROP\OMG_BPMN` and `00_MASTER_INDEX`. These are to be linked folder-wise in the docs phase.
- **Applied since 6s** (each compiles; none is tested yet):
  - SL-89 (job conflicts keep their retry, plus WARN and metric)
  - `Jdbc.one` diagnosable
  - SL-96 (namespace per database)
  - SL-70/73/95
  - SL-97 (all profile currencies)
  - the IfmsPacksIT executor race
  - SL-98 (refuse a money ledger with no currency)
  - SG-01 paise edges
- **Closed by evidence:** SG-20 and SG-22..25 were already in 15e4135.
- **New ids:** SL-99 (session LOCK_TIMEOUT), SL-100 (JIT 409 race), SL-101 (GP-13 compensation). **Next free: SL-102.**
- **Open questions for the owner, next batch:**
  - US `locale.fiscal`
  - folder deletions (after merge)
  - Q12/13/14/16/17/19/21/23/24/26/34
- **Running:** the independent conformance audit (owner asked for cross-verification).

## 6u. Independent conformance audit (owner asked for cross-verification, 2026-11-12)
- **Verdict:** CONFORMS WITH DEVIATIONS.
- **On basis:**
  - Operaton 2.2.0 is consumed unchanged; no engine package is shadowed.
  - Apache-2.0 and the licence policy are enforced.
  - Liquibase is pinned; there is no GraalJS and no gRPC.
  - The four Tier-1 databases are in CI.
  - Domain knowledge lives as data, through the generators.
  - No closed dependency, foreign database or engine swap was found, committed or in progress.
- **Deviations, now tracked as work:**
  - SL-104 (D-20): about 10 hard-coded India defaults in committed Java. Owner: SL-88 agent.
  - SL-105 (D-16): `PATCHES.md` lacks TenantScopedCreation, the SL-89 retry semantics and the impl-import ledger. Owner: SL-93 agent.
  - SL-106: missing future guards (enforcer, SPDX, ArchUnit-style core rule, gRPC/@Disabled bans, generator `--check` steps, a wider SG-37 check). Owners: SL-93 agent and B4.
  - SL-107: direct unit tests missing for PipelineRunner, PortsEventBridge, TokenAuthenticationFilters and OidcConfig.IssuerRouting.
  - D-18: the handoff snapshots and the WIP commit on main (SL-75 P2, the owner's call).
  - ADR-D-27 has no register row in SANGAM.md, and `PATCHES.md` calls it "proposed" (docs wave).
- **For the owner:** `sangam-billing` and other modules (geo, field, programme, resources, ai, channels, modeller, onboarding, cli) are listed in SANGAM.md Part XII but have no ADR. Ask whether to record an ADR or review their scope.

## 6v. Code-complete status (2026-11-12, late)
- **Coded in tree (compile gates green, tests NOT run):**
  - SL-84 to SL-118, except SL-111 (needs a test-phase re-run) and SL-114 (owner: drop Db2);
  - SG-01, SG-03, SG-05, SG-16, SG-19, SG-37 (widening in progress), SG-38;
  - PACKS4; CI-NVD-OIDC;
  - the SDK items (stored outcome, drift guard, PHP seam, TS ReDoS);
  - ROUTING-INCIDENT-CT and SG-35-SANGAM;
  - SL-04 to SL-07, SL-32, SL-33, SL-40 to SL-42, SL-53 ADR-D-27, SL-54, SL-55, SL-56, SL-57, SL-59, SL-70 to SL-73, SL-75 P1, SL-77, SL-80, SL-83.
- **Final packgen regeneration** by B3b: --check passes, 1123 files identical.
- **Owner decisions 2026-11-12** (recorded verbatim in JL §4): Q7, Q8/Q9, Q10, Q22, module ADR (record ADR-D-28), Db2 (drop from Tier 2), J-P1-E09 (ask IFMS; REQUEST sent), CI dispatch after a push of more than 300 files (standing yes).
- **Running:**
  - SL-93 agent: SG-37 widening;
  - B8: SL-35 end to end;
  - D1: docs wave (SANGAM.md ADR-D-27 row, ADR-D-28, Db2, canonical form; PATCHES; stale READMEs; SUPPORT-MATRIX; deploy README; CHANGES file; IFMS-KNOWLEDGE-LINKS; HANDOFF).
- **Register:** 12 stale rows restated with evidence (SG-06/08..12/21/26/28/29, SL-16, SL-19). Two rows broken by pipe characters were repaired (SL-32, SL-40). Rows SG-27, SL-17 and SD-428 were already malformed in HEAD.
- **Before the push, still to do:**
  - finish SG-37, SL-35 and D1;
  - a final full compile gate (JDK 25 and JDK 21);
  - generator --check for packgen, profilegen, changelogs, openapi and realm;
  - the remaining owner questions batch (Q12, Q13, Q14, Q17, Q19, Q21, Q23, Q24, Q26, Q34, US locale.fiscal, Dependabot version PRs, folder deletions);
  - commit and the owner-signed push.

## 7. Session note
- **Why HANDOFF.md was not edited:** the resuming session runs in the worktree `workspace-scan-resume-06b524`, which is on `9c831d0`. A hook blocks edits to the base checkout.
- **Plan:** commit this ledger into `HANDOFF.md` during the next integration commit on `handoff/2026-11-08`, after the push.

## 6w. Final gates and owner batch (2026-11-12)
- **Gates (B10 + integrator):** profilegen second run no diff (152 files); generate_changelogs, generate_scale_changelogs and realm regenerated unchanged; OpenAPI byte-identical; packgen --check OK (1123); full reactor clean test-compile on JDK 25 green (log scratchpad finalgate-jdk25.log). JDK 21 is not installed locally; the CI matrix (25/21/27) covers it after the push.
- **APPROVE-401:** crosswalk v0.2 applied; IFMS verified RPR 15 (pay, para 2.4(3)(b)) and RPR 16 (TA, para 2.7(1)); disabled placeholder RJ_PWFAR_SD_REFUND_TBD; no subTypeOf on DLIS.
- **Owner answers:** commit, then the owner signs the push; US fiscal = fail closed core-wide (corrected after I misdescribed the current default: FiscalCalendar.from silently returns 1 January) -> SL-125, B11 evidence phase running; stray folders deleted only after the push; Dependabot npm branch 77f51234 reviewed after the push.
- **Next free SL id: SL-126.**
- **IFMS messages (2026-11-12):** I-62 heavy-lock breach (17 IFMS *IT classes ran Testcontainers for about 31 min under a compile lock only). No Sangam impact: no heavy run in code-complete. P-463: oneifms main is now 6eebe7d4 (the adapter runs only in a UTC JVM and sets the timer zone from the kit calendar). R1 on an SL-53 sha needs the adapter (P-466) or waits for K5, because trials/workflow-sangam still uses the old argLine. Plan the SL-53 test leg around this.
- **IFMS (2026-11-12):** oneifms main is now 053345f0. P-466(b) done: trials/workflow-sangam is frozen, and the Trial 2 lanes run adapters/workflow-sangam. K5 done (UTC at every IFMS JVM launch). R1 on the Sangam SL-53 sha can now run against the adapter. SD-454/SD-451: the IFMS Sangam job stays BLOCKED/NOT_PROVEN until a published, pinned Sangam release exists (Sangam has none yet: 0.1.0-SNAPSHOT, no tag).

## 6x. Whole-code verification (owner asked, 2026-11-12)
- Audits A (pending), B (plug-and-play), C (UI) and D (continuity) are done. Combined report: sangam-handoff\SANGAM-READINESS-REPORT-2026-11-12.md.
- B11 coded SL-125, SL-126 and SL-127 (tests written, not run). Two points await owner confirmation: the billing label becomes fiscal, and an incomplete fiscal map is an ERROR.
- Code still open: SL-102, SL-78, SL-79, SG-15, SL-58, SG-39 (decision); plus stale docs.
- Continuity: nothing was lost in the account switch. A safety snapshot is at snapshots\20261112-0923 (patch reverse-check clean, plus memory copy). **Open new sessions at D:\GITHUB  Project\Custom WorkFlow** (memory is keyed to that path). Raw T-SCALE files exist only in D:\wt-scale.
- **Owner decisions (2026-11-12):**
  - v1 scope: 0.1.0 = the current scope, all code done and all test stages green.
  - 0.2+ backlog: single starter, SDK coverage and publishing, DB-per-tenant wiring, control-plane/roll-up OpenAPI, Tier-2 SDKs, integration guide; UI E2E and component tests, a11y audit, missing screens, more languages.
  - Billing uses the fiscal label (SL-127 accepted).
  - An incomplete fiscal map is an ERROR (SL-125 accepted).
  - Start all remaining code now (SL-102, SL-78, SL-79, SL-58, SG-15, docs), evidence first at >=97%.
- **OWNER SCOPE CHANGE (2026-11-12, supersedes the v1-scope line above):**
  - 0.1.0 includes EVERY KNOWN item: all plug-and-play gaps and all UI gaps from the readiness report, coded and fixed. Only unknown or unrecorded items go to 0.2.
  - Order unchanged: ALL coding first, then the test phase.
  - Each code line is written on evidence at >=97% confidence and cross-verified.
  - New items fit the existing strategy, design, framework and ADRs, with no reshuffle that breaks existing work.

## 6y. Master backlog and owner decisions (2026-11-12)
- **Master backlog:** sangam-handoff\BACKLOG-0.1.0-MASTER.md. 136 items (131 to do), estimate 742-1,827 engineer-days, critical path 60-135 days (judgement).
- **Owner: proceed with ALL of it in 0.1.0** (confirmed after seeing the size).
- **SL-129 offline queue:**
  - One device key as the contract specifies, with user and tenant bound into each entry's AAD.
  - At logout, entries stay encrypted and are hidden from other users.
  - Full device registration plus the /sync protocol.
  - Old plaintext entries are encrypted on first load, replayed only after the current user explicitly confirms them, and the plaintext is deleted.
- **Downloads:** approved per batch list (package, version, source, size, licence). Download only after the owner says yes.
- **SL-130/131 billing:** reservation in the registry NumberingService (GAP_FREE, ref_type/ref_id). Key = document identity: invoice = tenant + ledger + consumer + cycle; receipt = tenant + paymentRef.
- **SG-15 below-97% items:** evidence phase first, then code; anything still below 97% returns to the owner.
- **task.get:** one AUDIT_VIEWED per user-visible request; internal lookups write no extra event.
- **SL-133 (M-003):** the JavaDelegate connector path self-deadlocks on the ROOT_SEQ row today. Owner chose (ii) durable pending-audit rows, written on C2 with the ledger and drained into the root chain after the job; scheduled for Wave 2, once W1-B (schema) and W1-C (IntegrationWiring) release.
- **Owner decisions (2026-11-12, build gates):**
  - Download batch 1 approved: Error Prone 2.52.0, NullAway 0.14.3, PIT 1.30.2 with the junit5 plugin 1.2.3, JaCoCo 0.8.16, jqwik 1.10.2, Jazzer 0.30.0, the Cucumber JUnit engine 8.0.4, gitleaks 8.30.1, Trivy 0.76.2, Grype 0.121.3, Scorecard action 2.5.0, Kubescape 4.0.20, SLSA generator 2.1.0.
  - LGPL allowed for build-time-only tools: SpotBugs, FindSecBugs, Semgrep, Sonar. They go in licence-policy.json as an exception and must never ship.
  - Traceability gate scope: implemented, partial and test-pending rows. Enforcement off.
  - Q-10: CODEOWNERS and DCO are record-only. Sign off future commits with `git commit -s`.
- **Wave 1 progress:**
  - W1-I (M-003) became SL-133; the owner chose option (ii), done in Wave 2.
  - W1-E: M-004 closed. M-010 and M-011 carried to Wave 2 as SL-134 (see WAVE2-CARRY-2026-11-12.md).
  - W1-H: M-098 (class-size ratchet) and M-096 (record-only) are coded. M-094 and M-095 are being wired with the approved downloads.
- **W1-J done (B28):** M-110 stale docs and M-111 supersessions in X.5.3 are complete, and XII.10 now points to the wave plan. Register: added AC-01..AC-11, SL-53a, AT-6, Q22/CF-11, UI-UPGRADE-1, LMMH-HIER, JDBC-ONE and IFMSPACKS-EXEC-RACE, and restated SL-37 and OPERATON-UI-PARITY from their sub-tables. P13, P14, P35 and P45 still need a second check. **C-19 [CONTRACT] sent to IFMS 2026-11-12 as advance notice; the sha follows the push.** Follow-ups for W1-G: OPERATON-UI-PARITY.md:33-34 and the platform.tsx:2-6 header.
- **C-19 ACK from IFMS (2026-11-12):** no objection to SL-71, SL-72 or SL-73. IFMS checked oneifms main 053345f0 and found nothing that uses /engine-rest or parses /error or problem bodies, so it expects no impact. IFMS will re-check against the committed sha after the push (JL §5, EV-M-0589).
- **OWNER 2026-11-12: checkpoint commit after Wave 1.** Steps: all Wave 1 lanes released; full compile gate; all generators unchanged on a second run; fresh snapshot; a local commit (`git commit -s`) on branch claude/workspace-scan-resume-06b524. No push; the push to main still needs the owner signature after all coding is done. Tests still run after coding.
- **OWNER RULE 2026-11-12:** nothing may be missed. After every item, small or big, cross-verify it against the plan and sync every tracker. Live tracker: sangam-handoff\backlog-status.json plus gen_backlog_status.py, which writes BACKLOG-STATUS-0.1.0.md (136 M-ids; the generator fails on an unknown id). Checklist after each item: verify the work, then update the register, the status json and generator, the carry list and the ledger, and confirm claims are released.
- **W1-F (B24) done:**
  - SL-135 is coded for per-definition levels and for global activity and audit (ACT_HI_* is unchanged, and a test proves it).
  - Owner decision: a global history level of none is refused at startup. B24 is coding that now.
  - SL-136: the configmap line and the trace test are done.
- **SL-135:** the refusal of global none at startup is coded (B24). Integrator decisions: a none base under per-definition overrides stays allowed; the deprecated history=variable alias is routed through the mandatory-audit wrapper (being coded).
- **W1-G (B25) done:** M-001/SL-129 is CODED (all owner decisions applied; the plaintext-deletion order was verified by the integrator). M-028/SL-143 is CODED. fake-indexeddb 6.2.8 was installed per the approval, and storage.test.ts passed 2/2 (the single approved file). The server-side AAD |userId change is in the Wave 2 carry list.
- **SL-135:** the variable alias is wrapped. Integrator decisions: variable as the per-definition base maps to activity; history=auto is wrapped after the build (with >=97% evidence) or else refused.
- **Owner 2026-11-12, licence and tools:**
  - The LGPL build-only exception now also covers spotbugs-annotations 4.10.5 and three sonar-scanner-java-library 4.1.2.1663 parts.
  - Semgrep: local rules only, and its pip dependencies get a licence check.
  - The never-shipped guard is BLOCKING.
- **SL-144 (M-094/M-095):** wired report-only.
- **SL-135:** history=auto is refused at startup (javap evidence). The variable base maps to activity. Per-definition-base auto is also being refused.
- **Wave 2 carry:** the jqwik, Jazzer and Cucumber harnesses.
- **W1-F (B24) done:** M-012/SL-135 and M-055/SL-136 are CODED, and all of B24's claims are released. The integrator verified the final plugin hash A2048CD8 and the four refusal points.
- **W1-A (B19) done:**
  - **Coded:** M-013, M-017 and M-050; M-051 is partial (317 record-backed bodies typed). The register row is SL-145.
  - **Verified by the integrator:** gen_openapi writes 5 outputs, all byte-identical on a second run.
  - **Integrator edits:** .gitattributes now sets eol=lf for the new contracts and baselines (claimed and released). The ci-build cmp lines were sent to W1-H.
  - **SL-146 (new):** the notifications and registry.events destinations have no publisher.
  - **Owner decisions:** the 122 Map bodies get contract types from the service keys (no runtime change); the 37 audit names go into X.4.0.3. Both are carried to Wave 2.

## 6z. Wave 1 closed; checkpoint commit (2026-11-12)
- **Close-out:** all 30 Wave 1 M-ids have a status. CODED 18, CODED_NOT_LIVE 3, PARTIAL 3, CARRIED 3, CLOSED 2, BLOCKED 1 (Q-17).
- **Gates passed:**
  - Full reactor clean test-compile on JDK 25: green.
  - All generators: 1332 outputs, 0 changed, no stray dirs.
  - Secret-pattern scan: 2105 files, 0 hits.
- **Snapshot:** snapshots\20261112-1237 (reverse-check clean).
- **CHECKPOINT COMMIT 0e53f91b** on claude/workspace-scan-resume-06b524 (signed off; local only, NOT pushed). Working tree clean after it.
- **Next:** Wave 2, built from master §3 and the WAVE2-CARRY list.
- **OWNER DECISIONS 2026-11-12 (Wave 2 questions):**
  - **Q-09 (SG-39):** first-class ingress.private.enabled with an internal className, plus schema-validated AWS/Azure/GCP annotation presets. A public class with private=true is refused.
  - **Q-04:** separate Ed25519 keys per purpose and environment, held through the secret provider; the custodian is named later. Checkpoint every hour or every 10,000 entries (configurable).
  - **Q-17:** record an ADR that the React 18 UI replaces webapps-neo (M-112 unblocked).
  - **Q-06: ENGLISH ONLY for the initial release.**
    - UI labels, validation messages, notifications, help text and docs are in English.
    - DEFERRED to a later release: Hindi and all other translations, the BHASHINI integration, translation-review workflows, ≥10 locales, diglot templates and RTL.
    - KEEP: externalized strings, stable keys, Unicode, locale-aware formatting, English as default and fallback, the working i18n infra (hi.json is not removed).
    - The English-only UI must still accept, store and display Hindi and other Unicode names, documents and data. Original-language regulatory source documents are preserved.
    - Multilingual UI is deferred scope, not a release blocker. Reassess before a State-facing pilot.
  - **Owner priority for 0.1.0:** core workflow correctness, real authorization, financial controls, auditability, Oracle/SQL Server compatibility, end-to-end acceptance.
- **WAVE 2 STARTED (2026-11-12, base 0e53f91b):** 13 lanes, W2-A to W2-L plus W2-N (B29 to B41), per WAVE2-RULES-2026-11-12.md. Every Wave 2 M-id and all 23 carry items have a lane (integrator coverage check: 0 missing).
- **W2-I (B37) done:**
  - M-116 CODED: BusinessClock hook; the 13 journeys are confirmed checkable.
  - M-095 harnesses written and parked in sangam-packs/src/test/m095-pending, waiting for W2-A to add 4 test dependencies (requested). After that, the integrator moves the parked files.
  - New defects routed to W2-K: SL-148 (ModelGuard NPE) and SL-149 (simulator wall clock).
  - The server gate was briefly red because of W2-D in-progress work (org.sangam.engine.trace); recheck at close-out.
- **W2-G (B35) done:** SG-39 CODED; M-091 PARTIAL (SL-150). The integrator mounted the rules directory in docker-compose.yml (claimed and released). The 3 missing gauges went to W2-N (SangamMetrics.java added to its lane); the README text went to W2-J. The Maven gate was blocked by W2-L in-progress work (OperatonTaskAdapter denials()); recheck at close-out.
- **W2-F (B34) done:** M-097 CODED (SL-151): IdSource, the core determinism ratchet, and the Schema.java javadoc. The AuditChain debt went to W2-E. The JobIdentityBinder/LockScope ThreadLocals are carried for the engine owner's evidence (W2-D).
- **Wave 2 progress (2026-11-12):**
  - **Lanes reported (all verified by the integrator):** I (M-116 CODED; M-095 parked, then unblocked), G (SG-39 CODED; M-091 partial), F (M-097 CODED), K (M-011, M-010b CODED), A (wiring CODED; starter decisions taken), N (SL-142 park, SL-129 server side and gauges CODED; SL-146 evidence), L (SL-132, SG-15 and SL-148/149 CODED), D (M-098 CODED; M-054 opt-in; M-062 spans; M-106 evidence), J (X.4.0.3 +37, ADR-D-29, SL-147 docs, GLOSSARY, THREAT-MODEL), E (SL-133, jti, series reservation, M-073 and M-056 tables CODED).
  - **New register rows:** SL-148 to SL-152 and SL-157 to SL-161. The SL-157/158 (TM-5/TM-6 worker tenant and topic) security items are HIGH.
  - **Owner decisions:**
    - Starter: plain jar plus classifier; core modules on, domain modules opt-in; defaults shipped; drivers excluded.
    - SL-146 goes through subscriptions.
    - Job side data lives in Sangam side tables.
    - AUDIT_VIEWED only when audit is read.
    - TM-10 allow-list at deploy.
    - p3_duplicate gets its own duplicateSeat.
    - Compliance seat variables are passed at process start.
  - **Integrator edits:** docker-compose prometheus rules mount; .gitattributes and ci-build for the compliance BPMN (packgen output). All claimed and released.
  - **Integrator slips, recorded honestly:**
    1. The SL-148/149 message went to W2-L instead of W2-K. B40 handled it correctly, and nothing conflicted.
    2. I ran packgen test_compliance.py (16 OK) to check CI discovery, which went against the code-first order. I will not repeat it.
  - **Resumed with routed work:** A, L, D, E, I, G, K, N, J. Messages queued for H and C.
  - **W2-E is waiting** for W2-D's contract file W2-JOB-SIDE-TABLES-CONTRACT.md before adding EXEC_JOB_SLOT and JOB_TRACE_CONTEXT. Resume B33 when it exists.
- **W2-I (B37) done:** M-095 CODED (harnesses moved; packs compile green); the E16aPatternsIT frozen-settings fix is written. The server main build is RED from cross-lane drift: W2-N changed the SlaService constructor and PortsWiring:493 (W2-A) is not updated yet. W2-A was told to fix this first.
- **W2-J (B38) round 2 done:** X.4.0.3 gains INBOUND_PARKED and POISON_INBOUND_* (140 names); the security, rest, engine, persistence and compliance READMEs are fixed; Oracle SKIP LOCKED was verified in Job.xml; X.0k records the owner decisions. The integrator added "keyed by job id + planned table names" to X.0k. Open: W2-H AsyncAPI regeneration; W2-A security leaf pool.
- **W2-K (B39) round 2:** M-010d CODED (duplicateSeat). M-010c stopped: it needs ComplianceConfig and IncidentReportingService granted, and an owner answer on who starts the breach-notification-plan process (asked 2026-11-12; the owner replied with a message, which is being read).
- **OWNER ORDER 2026-11-12, team sync:** all agents must sync with each other, so there is no double work, no crossed edits and no wasted effort. Created sangam-handoff\WAVE2-SYNC-BOARD.md: CHANGE before any cross-module change (consumer grep included), DONE with SHA, REQUEST and ACK between lanes, BROKEN for gate breaks, check the board before building, and gates run on the live tree. Broadcast to the running lanes A, B, C, D, G, H, L and N. Finished lanes get it on resume. Triggered by the live break: the SlaService signature changed while PortsWiring was not updated.
- **Owner 2026-11-12 (re-asked):** the breach-notification-plan process is started by the planner with seats (W2-K resumed with grants). Correct identifier-types to an array in the contract and baseline (W2-H). The sync board is live: agents post CHANGE, DONE, REQUEST and BROKEN entries. The PortsWiring break was resolved by W2-A at 09:02Z; the new BROKEN JdbcTenantKeyStore (W2-L SL-161) was routed. W2-G round 2 done (M-091 CODED). W2-H done (121 Map bodies; M-025, M-059, M-074, M-099 and M-052 still to do, resumed). W2-N round 2 done (SL-146 and erasure CODED).
- **OWNER ORDER 2026-11-12: retroactive Wave 1 sync audit.** The Wave 2 sync-board rules are applied to Wave 1 (checkpoint 0e53f91b). Three read-only auditors are running: X1 (interface/consumer consistency, duplicated logic, conflicting decisions), X2 (old tests contradicted by new behaviour; ratchet guards against the live tree), X3 (FILE-CLAIMS hygiene with SHA checks, register truth, backlog-status truth). Reports go to sangam-handoff\AUDIT-X1/X2/X3-*.md. Findings are routed to the owning lanes via the sync board.
- **W2-K round 3:** M-010c is CODED for 11 of 12. The breach-plan start is stopped on evidence (it would loop); the owner was re-asked and replied with a message, which is pending. **Owner decisions:** SL-158 ENFORCE; Hindi test change accepted; downloads batch 2 approved CI-first; purpose basis from the jurisdiction pack; queue priority classes from tenant config. .gitattributes now has eol=lf for the generated SDK trees.
- **Owner 2026-11-12:** the breach plan is started by a SEPARATE start after breach registration (W2-K resumed with SangamCompliance granted). **W2-A batch done** (starter coded; plain jar; Kafka version managed once). W2-A was resumed for the SL-158 guard, the SL-161 leaf pool, M-073 verify and erasure chains. **W2-B done** (SDK surface, test engines, T01-T04/T18, AC-08, Tier-2). **W2-C done** (14 UI items, M-045 foundations).
- **W2-D round 2 and W2-H done.** **Owner SL-160 part B:** execution limited to variables and identity, a reviewed method list, approved beans only, JUEL-only scripts (W2-D resumed). The contract-first M-025/059/074/099 controllers are carried to their master-backlog waves. Slip: W2-H ran its own Python test once (recorded).
- **W2-E round 3 done:** EXEC_JOB_SLOT (0040-01) and JOB_TRACE_CONTEXT (0040-02), matching the contract; AuditChain.denialChain is now in core (verified; the generator changed 0 files). W2-L was resumed to make both copies delegate to it. Open question to W2-D: owner_node is VARCHAR(64).
- **Retroactive Wave 1 audit X1 done:** 10 findings (1 HIGH, SL-163 programme publish; others in SL-164). 6 Wave 1 wiring gaps were already fixed by Wave 2.
- **Owner decisions:** X1-03 one core fiscal helper; breach plan only where the jurisdiction defines rules (IN keeps its fixed flow).
- **New bug SL-162:** CMP_BREACH columns missing from the changelog (W2-E).
- **W2-A round 3 done:** SL-158 enforced, SL-161 leaf pool, M-073 verify and erasure all wired.
- **W2-K round 3 done:** all 12 compliance processes seat-routed.
- **C-20 [CONTRACT] sent to IFMS:** profile money is now in minor units.
- **Resumed:** W2-E (SL-162, plan column, hashedChain), W2-C (X1-01/02, axe-core), W2-A (X1-05/10), W2-F (X1-03), W2-J (docs batch), W2-L (X1-07, OperatonWorkAdapter guard).
- **W2-D round 3 done:** SL-160 part B CODED (ExpressionPolicy on the JUEL parse tree; all 208 shipped and 10 E46 models pass). Two known limits recorded (bare bean read; get() on an unknown name). The yaml docs and the explicit operaton-juel dependency were routed to W2-A.
- **OWNER ORDER (permanent, 2026-11-12):** all agents sync with each other. The canonical file is sangam-handoff\AGENT-SYNC-RULES.md, with one board per wave, and it is saved in memory (feedback-agent-sync-board). Every future brief points to it. Close-out gate: no open CHANGE, REQUEST or BROKEN entries and no held claims.
- **C-20 ACK from IFMS (2026-11-12):** no IFMS reader is affected by profile money moving to minor units. IFMS checked oneifms main 2c6a57ea and found no use of objectGroupLimits, appraisal.ladder, procurementThresholds or ResolvedProfile, and no parsing of unit text. Labels such as "AD_150L_5CR" are case-state codes, not money. IFMS will re-check once the sha is announced (JL §5).
- **W2-H round 3 done.** Owner accepted the 34 baseline corrections (200 to 201). Rule: baseline corrections need an owner decision or an explicit precedent. W2-B gets SDK regeneration; W2-L gets the BROKEN WorkLongPoll fix, traceContext fill and Me.locale.
- **W2-C round 2 done:** SL-163 FIXED (the programme publish form sends fiscalYearStartMonth); X1-02 fixed; axe-core 4.14.0 is installed (MPL-2.0, no transitive dependencies) and wired into npm test; owner decision #49 is applied in the UI. GET /programme-config is carried to Wave 3.
- **W2-J round 3 done:** the docs batch for W2-G/A/K/N, X1-09 and the X.0k owner decisions is complete. HANDOFF §8 now points to AGENT-SYNC-RULES. The earlier SL-132/SL-142 doc text was corrected to the now-live wiring.
- **W2-A round 4 done:** X1-05 (one leaf-pool helper), X1-10 (failsafe argLine), explicit operaton-juel dependency, SL-160 keys documented, topic guard on both adapters (SL-158 gap closed), long-poll settings shared. Verified by the integrator.
- **Audits X2 and X3 done:**
  - **X2:** guard tests would fail. Fixes are routed to W2-D/K/L/N/F/B/H/J. Owner decision: the generator splits large classes. Owner decision: fiscalStartDay is added to 3 definitions.
  - **X3:** claims are clean, and 208 release SHAs match. Integrator fixes:
    - register rows SL-129, SG-27 and SL-17 rebuilt (a pipe in the text had broken them);
    - stale counts and claims fixed in SL-135, SL-144, SL-127 and SL-145;
    - status M-004 set to CODED and M-008 to PARTIAL;
    - _as_of added to the status JSON;
    - a STANDING-CLAIM line added for the register.
  - **Close-out gate addition:** a static recompute of every guard (no test run).
- **W2-N X2-03 done.**
- **W2-K round 4 done:** X2-02, the X2-03 Vars citation, X1-06 (compliance chain via hashedChain) and the second-start guard. The server gate is RED from W2-L in-progress work (OperatonEngineAdapter RouteCorrection, the X2-04 split); BROKEN is posted to W2-L.
- **W2-D round 4 done:** X2-01 PATCHES ledger rows; X2-03 five citations. owner_node can exceed 64 chars (evidence), so W2-E was resumed to widen it to 255. The live engine gate is red because of W2-L in-progress RouteCorrection (BROKEN posted).
- **W2-E round 5 done:** executor node ids widened to 255 (0042-01..03: EXEC_PARTITION.owner_node, EXEC_MAP.coordinator_node, EXEC_NODE.node_id PK, with the SQL Server PK drop/re-add); ExecutorNodeIdWidthTest; second generator run no diff. Live gates green at report time.
- **W2-C round 3 done:** fiscalStartDay on the number-series and programme-config forms (fiscalDay.ts), a validity hint, months sent as integers. The Hindi-label skip for new labels is covered by Q-06/#49. Owner decisions: X2-06 AgentUsage allow-listed (not money; W2-F); .NET SDK one file per class (W2-B).
- **W2-F round 4 done:** programme periods honour fiscalStartDay; MoneyGuardTest AgentUsage entry is exactly 2 (static recount; nothing real excused). Server gate GREEN at its third run after the W2-L TenantLocales breaks cleared.
- **W2-B round 4 done:** .NET one file per class (783 models, 67 APIs, max 528 lines); all 5 SDKs regenerated vs contract 217C21BE (fiscalStartDay); all gates green including sangam-server. Only W2-L (X2-04 adapter split, Me.locale) remains before the Wave 2 close-out.
- **W2-L round 5 done:** X2-04 adapters exactly at their ceilings (981 and 1195, verified); X2-03; M-045 Me.locale server side (TenantLocales). The last Wave 2 item is W2-A wiring setProfiles. Then the Wave 2 CLOSE-OUT.

2026-11-12T11:09Z integrator: WAVE 2 CLOSED. Gate: X4 static guards all PASS; X5 board 64 CLOSED / 5 DEFERRED / 2 OPEN -> both closed by integrator (SlaService deprecated ctor removed; SL-158 note in 5 SDK READMEs); SL-165 SSRF hardening ExternalLayerGateway.requestInput + first sangam-geo test; X4 LOW SPDX on 4 fuzz seeds; register SL-165..SL-168; backlog-status refreshed (CODED 50, PARTIAL 11, NOT_STARTED 68); reactor test-compile green JDK 25; generators idempotent (contract 217C21BE); secret scan 0; snapshot sangam-handoff\snapshots\20261112-1639. Wave 3 board + carry C3-01..C3-16 drafted (WAVE3-SYNC-BOARD.md). NEXT: owner OK for the Wave 2 checkpoint commit (local, -s), then launch Wave 3 lanes W3-A..W3-M.
2026-11-12T11:11Z integrator: Wave 2 checkpoint commit 05c89814 (local, signed off, owner-approved, not pushed). 3078 files. Next: launch Wave 3 lanes per WAVE3-SYNC-BOARD.md.
2026-11-12T11:14Z integrator: Wave 3 launched, 13 lanes (W3-A B42 .. W3-M B54), brief sangam-handoff\WAVE3-LANE-BRIEF.md, board WAVE3-SYNC-BOARD.md. On resume: check the board, FILE-CLAIMS and each lane's report before relaunching anything.

## 2026-11-12T16:00:10Z Wave 3 close-out (integrator)
- All Wave 3 lanes reported and released; gate clear (board DONE 2026-11-12T16:00:10Z). Owner decisions 114 (veraPDF separate validator), 115 (contracts only in DRAFT), 116-136 (E16 C-01..C-21, H2 MPL-2.0). Compliance deliverables in sangam-handoff\compliance (X1-X4), audit X5 board. Wave 4 plan draft: WAVE4-LANE-PLAN-DRAFT.md. Register now to SL-181. Next: owner approval for the Wave 3 checkpoint commit (git commit -s, Co-Authored-By line), then Wave 4 launch.


- 2026-11-12T16:13:13Z integrator: Wave 3 checkpoint committed 928c3e9e (signed, 824 files, not pushed, owner approved). Wave 4 opened: WAVE4-LANE-BRIEF.md, WAVE4-SYNC-BOARD.md; 12 lanes launched (W4-A..K, W4-M).

- 2026-11-13T01:13:15Z integrator: Wave 4 owner Q&A complete: decisions 137-221 in OWNER-DECISIONS-2026-11-12.md (all W4Q-01..46 plus lane follow-ups). Downloads D-1..D-3 done, D-4 Camel deferred, D-5 Quarkus closure approved under 137(2) (W4-F), pgvector image fetched (W4-C). Wave 4b running from WAVE4B-ASSIGNMENTS.md (round 1 done, round 2 in progress). M-141c landed, host ratchet 0. Integrator helper INT-H2 doing wiring queue (items 1-11). Lane agent ids are in the transcript (W4-A a5e956d09c23c36f3 .. W4-M acdbbc4c7815ec9e6, INT-H2 a2f808a5bc19fef2e). Known gate blockers: W4-A UnverifiedPayments resolve/Resolution change with stale H2 test, W4-E Dmn16DeploymentH2Test, W4-B EnvironmentPolicy members. Close-out tasks: groupId sweep to io.github.sqamentor (decision 210), full reactor gate, generators, backlog-status regen, register sync, then owner OK for the Wave 4 checkpoint commit. On resume: check board, FILE-CLAIMS and lane reports before relaunching anything.

## 2026-11-13T03:20:56Z Wave 4b progress (integrator)
- Owner decisions 222-251 recorded in OWNER-DECISIONS-2026-11-12.md (Quarkus test scope 229, field bounds 230/242, Flowable listeners 231, NATS add-on 232/235, licence texts 233/234, DSC 236-238/241/251, Fidelius 243 (replaces 239), MySQL zone 240, profiles 244/245, tender 246, Oracle vector context 247, payroll blocked 248, ruleCaseType 249, shared-deployment read 250).
- Full reactor offline test-compile EXIT 0 at 03:09Z (INT-H2). No tests run.
- Open lanes: W4-E (partition service, SharedDeployments, 151 capture, 168, 138, 148), W4-K round 4, W4-G 247, W4-H 241/251, W4-C 247/243, W4-M 242/149 501/audit scope, W4-F M-150 on Quarkus, INT-H2 queue.
- Close-out carry list: scratchpad w4-closeout-todo.md (W4-J resume for [::1], DscSigner mount, enrolment screens, NUMBER scale UI).

## 2026-11-13T05:13:37Z coordinator (Wave 4 close-out continues)
- Owner decisions 265-275 recorded (EC-06 202+operation and list shape; DSC slot fill on upload, open-task holder, manifest pairs; ESI 270 continuation-only/rename, 271 separate rules + boundary BLOCKED, 272 ceiling per share once per unit; 273 bulk replay resolver; 274 Quarkus full host; 275 jna 5.17.0).
- Lane reports in: W4-B replay fail-closed (603/607, bulk now 273), W4-K round 6 (263 swap + HAPI smoke run 6/6 PASS executed once, 243 X.509, 264, 257), W4-F Quarkus plugin fix, INT-H2 batch B + full reactor gate EXIT 0 04:40:45Z.
- Dispatched: INT-H2 (263 server pom, DSC slots, EC-06, bulk), W4-K (269, 270-272), W4-F (274/275), W4-M (265/266 contract, leave perm, AsyncAPI), W4-B (273), W4-C (263 rows, jna), W4-J (UI close-out).
- Next: collect reports, groupId sweep (210), full gate + generators, backlog-status.json, register sync, memory, ask owner about Wave 4 checkpoint commit.

## 2026-11-15 account switch: everything gathered and committed (integrator, third login)
- Last board entry before the stop: 2026-11-13T08:21:52Z (Wave 4b/5 close-out in progress; no agent survived the switch).
- Commit 71b2c66d: Wave 4, 4b and Wave 5 close-out work, 2075 files, CODED, tests written NOT_RUN. Parent 928c3e9e.
- Whole-disk search (C: and D:) for Sangam trees: 37 scratchpad wt-* copies, cwf-docs-wt, D:\wt-fwt, D:\wt-scale, D:\sangam-wt-ci, the other scratchpad folders of sessions 898f and b920, sangam-read, pushgate, oneifms-sangam .sangam-src (both), and the 2026-11-08 backup. Rule: a file is preserved when its bytes (raw or CRLF/LF folded) equal a git blob. Everything else that is code was copied to handoff/worktree-snapshots-2026-11-15/ (manifests inside).
- Owner orders 2026-11-15 (verbatim): "Branch + PR, merge to main too"; "Everything into public repo" (records); "first commit all changes ... then push all committed to main, if any PR exist then merge into main end to end without checking and testing"; "Logs local rakho, sirf code GitHub par bhejo".
- Records copied to handoff/records/ (ledgers, decisions, claims, boards, compliance, statutory evidence, W4F/W4H staged, W4I parked, joint ledger and plan, memory). Not copied: sangam-handoff\snapshots (superseded tree states) and 2026-11-08 (transcripts, 1.9 GB).
- Local only: D:\GITHUB  Project\sangam-handoff\2026-11-15\non-code\ (logs, build outputs, transcript dumps), D:\wt-scale\tscale-*\ (JFR, 1.6 GB ev.json).
- Heavy lock: two dead waiting tickets from 2026-11-11 (customwf-sl94-36464, customwf-sl93-24224); not removed.
- Open PRs on GitHub: none. Next: owner-signed push of this branch tip to refs/heads/main; then tests stage by stage (nothing after 2026-11-13T04:40Z is gated).
