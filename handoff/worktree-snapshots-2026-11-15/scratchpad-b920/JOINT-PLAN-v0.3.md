# Sangam ⇄ IFMS Joint Plan (JOINT-PLAN v0.3, 2026-11-10)

**Status:** PROPOSED by Sangam as `[CONTRACT] JOINT-PLAN`. It becomes binding after the IFMS ACK, or once both sides agree the amendments. An ACK is a receipt, never an approval (§2 rule 9).
- **Ownership:** Sangam owns this file. IFMS edits only rows marked **IFMS**, by targeted Edit, and logs each change in JL §5.
- **No approvals here:** approvals come only from the owner, in the owner's own words, in the owner's own session.

**As of 2026-11-10 00:55 UTC** (`[DateTime]::UtcNow`).
- **Built from:** JL as written at 00:45:22Z; LED at 2026-11-09 23:49:48Z; REG at 00:47:33Z; IDX at 2026-11-09 02:46:24Z; PEND (IFMS pending register, read-only) at 00:26:53Z; E56X; the SANGAM transcript and the Sangam sub-agent transcripts (SUBAG); git of both repos; GitHub; the lock files; the process list.
- **Version history:** v0.1 (02:33–02:36Z) and v0.2 (19:24Z) were never sent; the second usage-limit outage (20:14:49Z–~23:16Z) overtook v0.2. v0.3 applies the findings pass. Each finding was verified at source first; findings that were wrong or overtaken by newer events are listed in §11 E.
- **Id hygiene in this file:** conflicts are now **K-n** (v0.2 called them C-n, which clashed with the contracts C-n; LED §6h "Correction C-1" = K-1). Risks are now **RK-n** (v0.2 used R-n, which clashed with the retest windows R1–R3).
- **Before sending,** the integrator re-runs §0.9.

**File.** The integrator writes this plan to `D:\GITHUB  Project\JOINT-PLAN-SANGAM-IFMS.md` (still missing at 00:24Z), marked DRAFT until sent, then points to it from JL §3 and §7, from LED and from the Sangam IDX row, and copies it to BK before sending (§9).

**Paths and abbreviations (full paths)**
- IT = Sangam integration tree `D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524`, branch `claude/workspace-scan-resume-06b524`.
- JL = `D:\GITHUB  Project\JOINT-LEDGER-SANGAM-IFMS.md`. IDX = `D:\GITHUB  Project\HANDOFF-INDEX.md`. LED = `D:\GITHUB  Project\sangam-handoff\RESUME-LEDGER-2026-11-09.md`.
- BK = `D:\GITHUB  Project\sangam-handoff\2026-11-09\` (to be created, §9).
- HO = IT `HANDOFF.md`: the 1105d9c version plus 2 uncommitted §2 lines (L60 "JDK parity with CI", L61 "Heavy lock from a detached runner only").
- REG = IT `sangam\TESTING-GAP-REGISTER.md`. COORD = IT `sangam\COORDINATION.md` v1.1.
- SCR = this login's Sangam scratchpad `C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad`.
- OLD = the old Sangam scratchpad `C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad`.
- SUBAG = Sangam sub-agent transcripts `C:\Users\Lokendra Singh\.claude\projects\D--GITHUB--Project-Custom-WorkFlow--claude-worktrees-workspace-scan-resume-06b524\6e98c2a7-cdac-4783-9098-832546572812\subagents\agent-<id>.jsonl`.
- LOCKDIR = `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\D--GITHUB--Project-IFMS\ff9f2caf-1246-4228-ae70-82a16cf209ae\scratchpad` (`heavy_lock.py`, `compile_lock.py`, `wait_lock.py`, `heavy-build.lock` + `.queue` + `.served.log`, `compile.lock` + `.queue`).
  - IFMS Trial 0 lane logs and `t2_run_15e.py`: `C:\Users\Lokendra Singh\AppData\Local\Temp\claude\369a6365dc7e1264\ff9f2caf-1246-4228-ae70-82a16cf209ae\scratchpad`.
  - Tracked copies: `D:\GITHUB  Project\IFMS\00_MASTER_INDEX\session_tools\scratch_A` (byte-identical `heavy_lock.py`, `compile_lock.py`, `wait_lock.py`) and `scratch_B`; v2 in `session_tools\heavy_lock_v2`.
- PEND = `D:\GITHUB  Project\IFMS\PFMS_IFMS_COMPLIANCE\24_Open_Questions\PENDING_ITEMS_REGISTER.csv` (IFMS-owned; Sangam reads only).
- P452-WT = `D:\GITHUB  Project\oneifms-p452` (IFMS worktree, branch `cc/wp04-routing-incident`, not on the remote).
- E56X = SCR `e56-extraction.md` (read-only extraction of E-56 v0.3, REC-05 v1.3, REC-06, E-53, E-54; findings E1–E17).
- IFMS-WT = `D:\GITHUB  Project\IFMS\.claude\worktrees\resets-display-expiry-eeb1fe`.
- T2 = IFMS Trial 2. RV = Sangam SD-428 reviewer agent, never an author of what it checks. OWN = owner (sponsor). S = Sangam, I = IFMS.
- Transcripts: Sangam this login `C:\Users\Lokendra Singh\.claude\projects\D--GITHUB--Project-Custom-WorkFlow--claude-worktrees-workspace-scan-resume-06b524\6e98c2a7-cdac-4783-9098-832546572812.jsonl`; Sangam old login `C:\Users\Lokendra Singh\.claude\projects\D--GITHUB--Project-Custom-WorkFlow\898f1ff7-0dcc-4d95-b425-830a9a5ead44.jsonl`; IFMS `d4859ba1-b81f-44be-bb27-3de47ae23f03` (path per REC-05 §1).

## 0. Facts verified at source (2026-11-10 00:15–00:55 UTC, read-only)
### 0.1 Sangam repo
- `origin/main` = local `main` = `1105d9cb` (ls-remote 00:55Z). 0 unpushed Sangam commits.
- IT at `1105d9c`, uncommitted (00:50Z): 1,183 status entries incl. 15 untracked files; 157 tracked files with content changes when CR at end of line is ignored (97 regenerated pack files + 60 others); the rest are line-ending only.
- Content by id (file mtimes UTC):
  - **IFMS-WF step 1** (11-09 02:37–02:39Z): `packs.py`, `SangamModelValidator` + test, `IfmsPacksIT`, `.gitattributes` (SL-56); untracked `CrossPackCallBindingTest`, `acceptance\e46\.gitattributes`.
  - **SL-63** `ResolvedIdentityCache` (record → `CachedIdentity`, 18:49Z); **SL-64** airgap scripts (19:09Z); regenerated native metadata (2 files, 01:44Z).
  - **UI-PARITY-2 + SL-60** (23:21:34Z; `sangam-api.yaml` 23:21:46Z, regenerated only by `gen_openapi.py`, +47 lines, 0 removed): 34 changed files incl. REG, and 6 new tests (`SecurityDialectTest`, `PolicyCompilerH2Test`, `SangamUiParityApiTest`, `BpmnDiagram.test.tsx`, `ui-parity-2.test.tsx`, `host.test.tsx`).
  - **SL-68** citations (integrator): `EngineConfigurerHolder` 23:24:44Z, `StandardBatchHandlers` 23:30:08Z, `SangamEngineConfigurer` 23:34:21Z.
  - **SG-36 + SL-61/62/65/66** (00:00:59–00:05:12Z): `HttpTransport`, `HttpRestConnector`, `LiveHttpConnectors`, `SoapConnector`, `HttpRestConnectorServiceTokenTest`, `LiveHttpConnectorsTest`; untracked `HttpRestConnectorHeadersTest`, `HttpTransportRequestBuildTest`, `SoapConnectorHeadersTest`.
  - **SL-45 + SL-69** (applying since the 00:28:43Z GO; in IT by 00:44Z): `SangamProcessEnginePlugin`, `TenantIsolationException`, `ProblemHandler`, `ObservabilityConfig`, `CrossTenantIsolationIT`, `EngineRestTenantRefusalTest`; new `TenantScopedCreation`, `TenantScopedCreationH2Test`, `ProblemHandlerIsolationTest`, `ObservabilityMdcScopeTest`.
  - **SL-74**: the three empty root files removed from the working tree (integrator, ~00:47Z).
  - REG (00:47:33Z); HO (+2 lines, 19:02:37Z).
- **REG ids:** SL rows run to **SL-75** (SL-20..SL-29 unused). **Next free: SL-76.** Next ADR-D: **ADR-D-27** (SANGAM.md stops at ADR-D-26).
  - New since v0.2: SL-65 (HIGH security, request-build failures and the secret rule), SL-66 (LOW, SoapConnector headers), SL-67 (HIGH test-harness: CI one-engine-per-JVM cascade, order-dependent `ResolvedIdentityCacheH2Test`, 3 NPEs), SL-68 (LOW, citations, FIXED in tree), SL-69 (MEDIUM security, MDC carried tenant B's log context, FIXED with SL-45), SL-70 (HIGH security, a tenant-issuer token maps to a platform role), SL-71 (MEDIUM, module-controller and unmapped `/engine-rest` errors become 401; = old MOD-CTRL-401), SL-72 (MEDIUM security, existence oracle), SL-73 (HIGH security, tenant-less `/engine-rest` resources), SL-74 (LOW hygiene, empty root files, FIXED in tree; = REPO-STRAY-ROOT-FILES), SL-75 (MEDIUM hygiene, `handoff/worktree-snapshots/**` on main; = HYGIENE-SNAPSHOTS, OPEN after step 3).
  - SL-60 row exists (FIXED in tree, single-verified). SL-61, SL-62 and SL-66 are FIXED in tree (single-verified).
  - **REG damage:** the integrator's scripted `set_status` edit at 00:35:03Z (file written 00:35:19Z) broke the SL-65 row (lines 90–91). The row held a raw newline inside its severity cell (an example header value), so the helper treated the first physical line as the whole row and overwrote the id cell `SL-65` with the new status; the old IN PROGRESS status stayed at the end. Still damaged at 00:50Z (K-33).
  - No rows yet for SG-30..SG-35 or SL-53a; SG-31 and SG-32 appear only inside SL-19 (E56X B6). Stale rows: K-12, §3.1 B REG-ALLOC.
- `.gitattributes` LF rules cover only `sangam/sangam-packs/**` and the packs README. `git check-attr`: `sangam-rest/api/sangam-api.yaml`, `reflect-config.json` and `model-classes.txt` have no eol rule; `core.autocrlf=true` (K-6).
- Root `sangam/pom.xml` surefire/failsafe argLines (L288, L301) carry no `-Duser.timezone`; only `sangam-ops/pom.xml:91` sets UTC. `.github/workflows/ci-build.yml` runs JDK 25, 21 and 27 with `env TZ: UTC` on Linux.
- Two PATCHES.md files with I-/P- ids that collide with IFMS's I-/P- ids: `sangam-engine` (I-1..I-16, P-1..P-3) and `sangam-flowable-ports` (I-1..I-8, P-1..P-5).

### 0.2 CI on main (gh, unchanged since 2026-11-09 01:49Z)
- **build 42547172757 (1105d9c):** JDK 25 and 27 fail to compile `sangam-security` (`ResolvedIdentityCache.java:[52,27]`, from 16e0e11). The JDK 21 job (Temurin 21.0.13) compiled the whole reactor and failed in sangam-server tests: 83 run, 2 F (`ResolvedIdentityCacheH2Test.membershipEndAndSourceRemovalAreSeenAtOnce`, `DecisionCitationGuardTest`), 20 E (the D-10 one-engine-per-JVM cascade). The log also shows NPEs at `OutboxCapacityMonitor.java:83` (SCR `ci-check-128934575060.log`). Compose + Helm lint fails at ShellCheck.
- security 42546959193 and sdks 42546959171 (on 16e0e11): compile failure; stale native metadata. db-matrix 42547175740: compile failure. ui green. licence-check last ran on 15e4135.
- **Fixes in IT vs CI failures:** SL-63 (compile 25/27), SL-64 (ShellCheck), regenerated metadata (sdks), SL-68 (`DecisionCitationGuardTest`, FIXED in tree). **Open: SL-67**: the cascade; the `ResolvedIdentityCacheH2Test` failure, which passes alone on JDK 21 + UTC, so it is order or shared-state dependent; and the NPE. Line 83 is `Double drain = mu > lambda ? open / (mu - lambda) : (open == 0 ? 0.0 : null);`: the conditional unboxes null when mu ≤ lambda and open > 0, a product defect inside SL-67's scope.
- **JDK 21 statement:** corrected in REG SL-63 (23:35Z), LED §6h and to IFMS (`[REPORT] SANGAM-MAIN-COMPILE CORRECTION`, 23:38:45Z; IFMS P-444 updated). Residual: HO L60 still says JDK 21.0.10 "has a javac bug"; both JDK 21 javac builds accept the code, and javac 22+ refuses it (K-1).
- PR #3 CLOSED by Dependabot at 2026-11-09 01:44:37Z, not merged. The only ruleset is 26789812 `protect-main` (active).

### 0.3 Local toolchain and worktrees
- **JDKs:** default 21.0.10; `C:\Program Files\Java\jdk-23`; JDK 25.0.5 and 27+35 installed twice (Sangam `C:\Users\Lokendra Singh\tools\jdk25\jdk-25.0.5.0.2+1`, `C:\Users\Lokendra Singh\tools\jdk27\jdk-27+35`; IFMS `D:\GITHUB  Project\.tools\OpenJDK25U-jdk_x64_windows_hotspot_25.0.5_8\jdk-25.0.5+8`, `D:\GITHUB  Project\.tools\OpenJDK27U-jdk_x64_windows_hotspot_27_35`). No download needed.
- **Time-zone probe (00:2xZ):** with `TZ=UTC` set, JDK 21.0.10 and 25.0.5 still report `Asia/Calcutta` on this Windows host. Only `-Duser.timezone=UTC` gives UTC (K-36).
- **Registered worktrees** (as verified at 19:24Z, not re-checked): IT; the base checkout `D:\GITHUB  Project\Custom WorkFlow` (`handoff/2026-11-08` @ 16e0e11, do not use); 10 OLD worktrees detached at 9c831d0 (wt-auth401, wt-bulk, wt-bulk2, wt-forms, wt-fwtd, wt-iso, wt-packs4, wt-perf, wt-perf-base, wt-uiparity); `D:\GITHUB  Project\cwf-docs-wt` (15e4135, 11 files, JL-S-01); `D:\wt-fwt` (9c831d0; probably superseded by eba6e62, unverified); `D:\wt-scale` (15e4135; tscale-base/tscale-smoke evidence that exists nowhere else).
- **SCR work folders (00:45Z):** wt-d10, d10-staging, d10-logs (SL-67); wt-auth401pg and a401-* keep folders (APPROVE-401); wt-sg36, sg36-evidence, sl6162-evidence, sl6566-evidence (SG-36); sl45\; uip2-work\; hlv2-adoption, hlv2-concurrency, hlv2-ops, hlv2-refute-conc01 (HEAVY-LOCK-V2 review); sync-backup-4, sync-backup-pg; base-1105d9c + .tar; wf-* collector outputs; planner_*.txt; tools-verify\ (this pass's read-only scripts).
- **Missing on disk (00:24Z):** this plan file and BK.

### 0.4 Running Sangam agents (00:55Z; transcripts in SUBAG; details §9.3)
| Workstream | Agent | State |
|---|---|---|
| SL-53 (+ADR-D-27; SL-53a later) | `a559ce3ccab6a4b3b` | "WIRE b1" 23:49:25Z; minimal (b1) guard rewired 23:52–23:54Z, (b2) predicate removed; touched-module runs on JDK 21 (SCR `dst-touched-jdk21c.log`). E8 on PostgreSQL in a UTC JVM passed before the final wiring (Berlin 5 run/0 F/1 skipped; Kolkata 5/0/4 skipped; 23:33–23:52Z). SCR `SL-53.patch` refreshed 00:12:27Z. Reports before applying |
| SL-45 + SL-69 (next SL-70, SL-73, SL-72) | `aa33e846abeb4489f` | Report 00:28:03Z: two real `/engine-rest` leaks on 1105d9c (a deployment into another tenant; tenant-less batches running unauthenticated); 0 `/api` leaks; PG JDK 25 red, then 5/5 green. GO 00:28:43Z; applied in IT by 00:44Z; SHA check and unit suites next |
| APPROVE-401 | `adbd0a0881b88eb1b` | Run 2 (23:52:04–00:20:58Z): E17b 6/6 incl. D-REFERRAL; PlugAndPlayIT 6 pass + AT-6 skipped; IfmsPacksIT 2/6; T35 server needed 276 s at 100% CPU, past the IT's 5-min health wait. Run 3 (00:37:28–00:40:42Z): server up in 57 s, event definition deploys (422 fixed), next stop 422 `WEBHOOK_SECRET_REQUIRED`. Run 4 (lock 00:45:05Z; free by 00:55Z) used the documented `allow-unsigned-webhooks` switch (K-23) |
| SG-36 + SL-61/62/65/66 | `a2f414ffffbb900fc` | Finished 00:34:47Z: 9 files SHA-identical to wt-sg36; `-pl sangam-integration -am test` JDK 25 in IT 612/612 (26:38 min). Idle until RV |
| UI-PARITY-2 + SL-60 | `a7102621b8c6f9ff6` | Finished 00:46:02Z: all gates green (UI 5 runs 89/89; rest 19/19 incl. OpenApiCompatibilityTest 6/6 on JDK 21 and 25; security/ports/ops/ai 29/266/22/26; IT after apply: JDK 21 compile, JDK 25 test-compile, touched suites one JVM per class incl. server 91/91). In one surefire JVM the server fails only on SL-67 (11 classes). SL-68 and SL-45 landed in IT during its runs |
| SL-67 | `acab37a2ef6d1dc85` (launched 23:38:36Z, SCR wt-d10) | Red full-suite runs on JDK 21 and 25 (SCR `d10-logs\red-jdk21-fs.log`, `red-jdk25-fs.log`); regression draft `OutboxCapacityMonitorTest` (SCR `d10-staging\ocm`). Told at 00:28:47Z to keep off SL-45's files, and at 00:46:25Z that surefire's property is `-DreuseForks=false` (`-Dsurefire.reuseForks` is silently ignored, so "one JVM per class" runs that used it ran in one JVM) |
- Finished earlier: IFMS-WF (`af5674588bc930fc7`).
- **Workflows:** HEAVY-LOCK-V2 review re-run `wtkb390st` (23:39:29Z, running). The first run `wbbi231l9` (= wf_89269959-bb5) stopped at the limit at 20:15:04Z; only its concurrency reviewer finished. Joint-plan re-run `wnh0ytk81` (23:39:41Z; this pass).
- The integrator wrote SL-63, SL-64, SL-68 and SL-74 itself.

### 0.5 Locks (LOCKDIR, 00:55Z)
- **Heavy lock:** free; queue empty. Served after 19:24Z: customwf-iso 19:29:59Z and 19:50:29Z (SL-45 red and green); customwf-dst 23:28:35Z and 23:33:30Z (SL-53 E8); customwf-auth401 23:52:04Z (run 2), 00:36:09Z (aborted after 13 s, Maven exit 1), 00:37:28Z (run 3), 00:45:05Z (run 4).
- **Compile lock:** held by `ifms-p452` since 00:28:48Z (P-452 finish: spotless, compile/test-compile, H2/kit tests, planted run; `-am -Pdemo`, JDK 25). First held 23:42:37Z; IFMS released it at ~00:05Z after I-60.
- **Tool:** still v1, holding by owner name with no liveness (the root cause of I-59 and of I-60's stuck compile lock). v2 implemented and self-tested by IFMS (10/10 twice); the swap is HELD until Sangam's ACK and needs both locks free.
- **Backups on D::** byte-identical copies in scratch_A: `heavy_lock.py` (SHA-256 5C8815D5…), `compile_lock.py` (83DDF9AC…), `wait_lock.py` (3BA96243…).

### 0.6 Machine (00:33Z)
- 8 logical CPUs, 31.9 GB RAM, 4.9 GB free (0.9 GB at 18:5xZ).
- 8 java.exe: 4 Sangam Maven builds (UI-PARITY-2's IT gate job since 23:30:59Z, APPROVE-401, SL-67, SL-53) with their forked test JVMs, plus 1 IFMS Maven (P-452). The Sangam light-build cap of 3 was exceeded, and APPROVE-401 run 2 lost T35 to the CPU load (K-41).
- Two containers outside both projects have been up ~8 h (hxe, SAP HANA Express; one database container of a project outside this context). Untouched.

### 0.7 IFMS (JL, PEND, git, messages)
- **oneifms (ls-remote 00:55Z):** main = `970a07cc` (docs only: E-58 v0.2 reading copy, 00:22Z); before that `26051321` (23:43Z, P-445 fixes 1–7). `integ/test-phase-2026-11-09` = 26051321 (pushed); `cc/wp04-workflow` 886d72e; `wf/trial2-sangam` 3d6325e4; tag `pre-cc-merge` → b4bbbb7. `cc/wp04-routing-incident` is not on the remote.
- **P-445 step 1 DONE:** run 6 `-Pdemo` full reactor 126/126 BUILD SUCCESS on 26051321 (IFMS 26ca9fb, 23:41:03Z). The test phase on Oracle and SQL Server is next; there is no JL §6 claim for it yet.
- **Root pom at 26051321, profile `demo`:** `trials/workflow-sangam` (L219) and `adapters/workflow-sangam` (L224). So `-Pdemo` builds already need org.sangam 0.1.0-SNAPSHOT jars, today from the Trial 2 private repo `.m2-sangam-15e4135` (PEND **P-460** HIGH, owners "IFMS master; Sangam (artifact publication)"). No `wf-sangam` profile exists.
- **org.sangam consumers at 26051321:** `adapters/workflow-sangam`, `trials/workflow-sangam`, `trials/sangam-money` (standalone, outside the reactor; its pom comment names Sangam 0196b57 jars from `.m2-sangam`), and `build/oneifms-parent` (enforcer `workflow-engine-boundary`, L184–206, bans org.sangam* unless allowed).
- **P-452 IN PROGRESS:** the implementation is uncommitted in P452-WT (9 modified + 5 new files, all under `adapters/workflow-sangam`, `tests/workflow-conformance` and `docs`; none under `trials/workflow-sangam`). Builder incident **I-60**: the agent ended its turn while its Maven batch ran; the batch was killed and the compile lock stayed held until IFMS released it. Relaunched with foreground Maven. X-13 stays on hold until the commit and two SD-428 verifications.
- **SL-53:** IFMS **SD-449** records the owner's words that Sangam relayed; IFMS asks the owner to confirm in the IFMS session (SD-381). **P-463** (HIGH) APPROVED TO PROCEED, sequenced after P-452 (both change the adapter's EngineHost). **P-453 CLOSED.** R-98 v0.2 filed after its critic pass; P-464 (re-grep count) and P-465 (E-19 claim correction) are IFMS-internal follow-ups.
- **R-99 v0.2** (22 critic fixes, IFMS 7f31aa9): Central: DH-1 enters, PAO-1 approves (role PAO, not PR_AO), holder DDO-1, no money ceiling. Rajasthan rows restated in pack fields, every seat id ASSUMED; RJ data is a new tenant override in `RJ.yaml`. Sangam's answer to the stage question: `gov_ifms.py:399` defaults `stage=RELEASE`, but the DDO-wise allocation is `AUTHORISATION` (`GOI.yaml:158–163`); this is now part of SL-57. MD-195..207 and OQ-218..227 are open (from R-99 v0.1); OQ-228..256 were opened at 7f31aa9.
- **P-454 DONE as a draft map:** E-58 v0.2, reading copy on oneifms main 970a07cc. Its Sangam section is built from pushed refs only.
- **P-458 IMPLEMENTED;** the swap waits for Sangam's ACK.
- **New PEND rows:** P-459 (MEDIUM, WP-04→WP-02: parent enforcer rule `workflow-engine-boundary` incl. org.sangam*, plus a tests/architecture scan and planted fixture; K-42), P-460 (above), P-461 (sanction contract) and P-462 (E-14 retag), which are IFMS-internal.
- **P-449 (LOW):** the SG-34 re-check is the selftest alone (31/31) inside P-444 (K-8). **P-444:** retest on the next Sangam main sha, which IFMS expects to carry SL-63, SL-67 and SL-68.
- **Records:** REC-05 v1.4 (JL §1 still says v1.3); E-54 still v0.1; DECISION_REGISTER adds SD-449; incidents add I-60.
- **IFMS→Sangam messages since 19:24Z (arrival):** 23:16:25Z R-99 v0.1; 23:40:12Z R-98 v0.1; 23:44:53Z ONEIFMS-MAIN 26051321; 23:45:08Z R-98 ACK received; 00:11:05Z SD-449 + I-60 + R-99 v0.2 + E-58; 00:27:01Z E-58 PUBLISHED + ACK. **JL §5 stays the fallback channel.**

### 0.8 Clock drift and zero-loss gaps
- **Clock:** "~" stamps still drift; several new ones look like IST minus 5 h instead of minus 5 h 30 min (inferred). The owner's SL-53 words (23:49:14Z) are logged "2026-11-10 ~00:20" in JL §4, LED §6i and the relay text to IFMS. Sangam's R-98 ACK (sent 23:40:46Z) is logged "~23:50". The second outage began at 20:14:49Z but is logged "~20:40". IFMS has already corrected its ONEIFMS-MAIN (23:43Z) and R-98-ACK-received (~23:46) stamps. Table in §11 F.
- **Zero loss:** plan file and BK missing; REG SL-65 row damaged; LED has nothing after 23:50Z and still states the retracted partial apply (§6h, K-34); memory pointer partly stale; every agent's work, E56X, the CI evidence, runner logs and the tools-verify scripts exist only in Temp; UI-PARITY-2's gate logs sit in OLD `wt-uip2\logs-2026-11-09\int-*.log`.

### 0.9 Re-verify immediately before sending
1. IT `git status --porcelain -uall` counts and `git diff --ignore-cr-at-eol --numstat`.
2. REG integrity: every table row starts with `| SL-`, `| SG-` or a sub-table id; no raw newline inside a cell; the last SL id.
3. ls-remote of both repos.
4. Both lock files, both queues and the served-log tail.
5. JL mtime and every §2/§5/§6 entry newer than 00:45:22Z; PEND rows P-444..P-465.
6. ListAgents, the last messages in SUBAG, and the `wtkb390st` result.
7. `gh run list --limit 10`.

## 1. Roles and addressing (only two sessions are live on this login)
**Rules:** address sessions by id; titles are labels. ListAgents refs change after an app restart (refs held 18:28–00:46Z: SL-53 5e7879, SL-45 b100e6, APPROVE-401 584a98, SG-36 e613ea, UI-PARITY-2 be3d42, SL-67 14f3a8).
| Former role (old login) | Now held by | Address / ids | Lock names | IFMS confirmation |
|---|---|---|---|---|
| Sangam owner session and sole integrator (`local_eb19d798`, transcript 898f1ff7) | SANGAM session (renamed from "Workspace scan progress resumption"); owns SL/ADR-D ids, REG and Sangam pushes | `local_30e74cc4-d0bb-476b-8128-12f9367ffa52`, transcript 6e98c2a7-cdac-4783-9098-832546572812 | `customwf-integ` | n/a |
| Sangam workstream agents (6 died 11-08 22:00–23:14Z; 5 stopped 11-09 04:10:09Z; 5 agents and 2 workflows stopped at 20:14:49Z) | Sub-agents of SANGAM: 4 working, 2 idle (§0.4, §9.3); the rest queued (§3.1 C) | §9.3 | `customwf-<ws>`, one per workstream | n/a |
| WorkFlow master (`ff9f2caf`): id allocator, oneifms integrator, lock-tool owner | IFMS session (SD-446) | `local_dbde6646-ddbf-4df5-bc21-f1c14ed060b7`, transcript d4859ba1-b81f-44be-bb27-3de47ae23f03 | `ifms-integ` | Confirmed (JL §1) |
| IFMS main relay (`local_acdd1d5d`) | IFMS session; one copy of each report is enough | same | — | Confirmed |
| Hosting (`local_3812f9f6`) | IFMS session, Hosting role (P-450) | same | — | Confirmed |
| Business architecture/deck (`local_b47f453a`) | IFMS session, Business-architecture role (P-455 answered; R-99 v0.2) | same | — | Confirmed |
| Trial 2 (agent `abd0d8e5f7a37d1ed`; owner of E-54) | IFMS sub-agent, to be re-spawned for TRIAL2-RETEST | via IFMS | `sangam` (an IFMS owner name) | **Confirm agent id and spawn time** |
| Trial 0 / Jackson (`a5be6abafc902b674`) | IFMS sub-agent | via IFMS | `trial0` | **Confirm** |
| WP-01..WP-15 builders (WP-04 `a5d3a2148bfc53f43` died 21:44Z on 11-08) | IFMS sub-agents. The WP-04 P-452 builder was relaunched after I-60 (lock `ifms-p452`, not yet in JL §0 rule 7). WP-02 (fix 3 done; P-459 open) and WP-15 (route data, JL-S-05) need re-spawns | via IFMS | `ifms-integ`, `ifms-p452`, `sangam` | **Confirm who re-spawns WP-02, WP-15 and Trial 2, and when; add `ifms-p452` to JL §0 rule 7** |
| Flowable trial agent (`a94bfd6716c0cd345`), lock owner `flowable` | Paused (SD-422) | — | `flowable` | **Confirm that `flowable` is retired** |
| oneifms integrator | IFMS session | same | `ifms-integ` | Confirmed |
| DOIT परियोजना निगरानी प्रणाली (`local_227a71a8`, SeMT Research, halted 2026-11-05; ref [76db95] on 11-04) | **Proposed interim:** the IFMS session holds DOIT coordination only (IDX row, MACHINE-QUIET/FREE notices, sync relay); no session does DOIT project work (Q29) | IFMS session | — | **IFMS to confirm; owner decides** |
| PHED (`local_046fa70c`, PHED Rajasthan, halted 2026-11-04; ref [5f08c4]) | Same arrangement as DOIT | IFMS session | — | **IFMS to confirm; owner decides** |

## 2. Protocol (COORD v1.1 = E-57 §13; JL §0) and proposed COORD v1.2
1. **Header and types:** `[TYPE] <ID> <from>→<to> <subject>`, TYPE = REQUEST, REPORT, CONTRACT, INCIDENT or DECISION-NEEDED. DECISION-NEEDED goes only to the owner, in the asker's own session; facts from the other side are asked with REQUEST (Sangam's 03:01:48Z "[DECISION-NEEDED] SL-53" to IFMS was functionally a REQUEST). An IFMS `[DECISION] SD-nnn` reports a recorded sponsor decision and never approves a Sangam push, merge or setting.
2. **One topic per message.** Proposed v1.2 exceptions: `[REPORT] BATCH <sha>` and `[CONTRACT] JOINT-PLAN`. Per-id asks live in JL §2 rows; a message points to the row.
3. **Log every message in JL §5, by its sender.** Stamps come from a clock command at write time (`[DateTime]::UtcNow.ToString('u')` or `date -u`), never "~". Check every send result; log `UNDELIVERED` when a send fails and resend after the owner next types. IFMS logged Sangam's 23:49:31Z, 23:50:36Z and 00:11:55Z sends as "received" lines; Sangam still appends its own sender entries (CLOCK-CORRECTIONS). JL §2 rows stay authoritative; read JL §5 every turn because queued messages arrive late.
4. **Crossed messages or rows:** both sides stop; the id owner restates the state in its JL §2 row and in `[REPORT] <ID> RESTATE`. Cite JL rows by Id, never by line number.
5. **Verify every claim at source** (git, gh, lock files, transcripts) and mark anything else "unverified".
6. **Cadence:** after each Sangam push, `[REPORT] BATCH <sha>` in the same turn, with the commit→id map and the `sangam-bom`/API delta since the previous sha. **The first line of a BATCH names any consumer-side runtime requirement the sha adds** (P1: the SL-53 UTC JVM). IFMS answers `[REPORT] RETEST <sha>`; IFMS sends `[REPORT] ONEIFMS-MAIN <sha>` after every move of oneifms main. `[CONTRACT]` before landing anything the other side must adopt. `[INCIDENT]` for lock breaks, a red main that affects the other side, cross-tenant leaks, secret leaks and data-loss risk.
7. **Zero loss:** §9 binds both sides.
8. **Ids:**
   - Sangam allocates SL-nn (REG holds SL-75; this plan proposes SL-76 and SL-77, so next free after REG-ALLOC = SL-78), ADR-D-nn (next ADR-D-27) and JL-S-nn. **PATCHES ids are always written qualified**, `PATCHES(engine) I-7` or `PATCHES(ports) P-3`, because bare I-/P- ids are IFMS's (v1.2).
   - **Proposed v1.2 child-id rule:** a scheduled follow-up of a decided item may take the parent id plus a letter (SL-53a); it has its own REG row and is a full id.
   - IFMS allocates SG, SD, P, OQ, MD, EV-M, I, E, X, WP and REC ids, and JL-I-nn. Only the integrator allocates SL ids; agents never pick ids.
9. **Definitions of done:**
   - **D-SL** = COORD §5 items 1–6, then item 7: an RV (never an author) re-runs the proof on the pushed commit; REG marked CLOSED.
   - **D-SG** = COORD §5 items 1–6 + a T2 retest of the pushed sha recorded in E-54 (closure authority; REG mirrors it). Where an SG also has an SL row, D-SL applies to that row.
   - **D-SL+X** = D-SL + D-MSG to IFMS + IFMS confirmation in its row.
   - **D-IF** = committed; pushed through an E-55 card; SD-428 second method; evidence commit; E-54/REC/P row updated.
   - **D-OWN-DEC** = the owner's words verbatim in JL §4. **D-OWN-EXE** = the decided action executed and verified (separate state).
   - **D-MSG** = sent, delivery checked, logged, ACK received. **An ACK is a receipt, never an approval.** An objection within the working session goes to the owner as DECISION-NEEDED; if unanswered by the push cut, the owner decides.
   - **D-DOC** = targeted edit committed (and pushed, for Sangam). Shared files (JL, IDX, this plan, LED): edit made and logged.
10. **Rules for every item:** root cause only; the regression test is red without the fix; assertions are never weakened and test timeouts are never lengthened to hide load; confidence ≥99.99%. Sangam stays generic (ADR-D-20): an IFMS need becomes a generic capability or pack/config data, never IFMS-specific Java. Agents apply targeted Edits to IT only on the integrator's explicit GO, after a green run on a fresh re-copy of IT. **Shared files change only through the integrator**, with one proposed v1.2 exception: a workstream agent may edit only its own JL §6 claim row, by targeted Edit after a re-read (already practised by the SL-45 and APPROVE-401 agents; K-39). REG rows are written by the integrator, or by an agent whose GO names those rows. Sangam pushes go only through the signed gate; oneifms pushes only through E-55 cards with the owner's words, one branch at a time (I-53). Agents never read or touch `.ssh` and never type secrets. VyaparTrust and CVR_Automation_Claude are out of scope.
11. **Write safety for shared files** (JL, IDX, this plan, LED, REG): re-read immediately before each change; exact-match targeted edits only; never Write, Set-Content or copy over a shared file; if a match fails, re-read and re-apply. **Never put a raw newline inside a Markdown table cell** (write `\n`). A scripted edit parses whole rows, refuses rows that span several physical lines, and is followed by the §0.9 item 2 integrity check (K-33).
12. **Owner-question routing:** each Q in §6.2 names the session that answers it. Whichever session receives the owner's words appends them verbatim to JL §4 in the same turn.
13. **Send budget:** since the owner last typed in SANGAM (23:50:23Z) Sangam has sent 2 messages to IFMS (23:50:36Z, 00:11:55Z); since 18:21:39Z, 8 (18:26:40, 18:46:26, 19:11:24, 23:38:45, 23:40:46, 23:49:31, 23:50:36, 00:11:55Z). Each side keeps to ≤8 per owner turn, in priority INCIDENT > CONTRACT > REPORT; lower-priority content goes into JL rows.
14. **Dispatch brief** (every Sangam workstream agent gets it; the integrator records in LED that it was given):
    - Maven (HO §2): run `.\mvnw.cmd` from `sangam\`; never `install`; always `-am` with `-Dsangam.target=target-<ws>`.
    - Compile gates on JDK 21, 25 and 27. **Tests run in a UTC JVM via `-Duser.timezone=UTC`** (argument or argLine); `TZ=UTC` does not change the JVM zone on Windows. One JVM per class is `-DreuseForks=false`; `-Dsurefire.reuseForks` is silently ignored.
    - Heavy runs only through a detached runner: Start-Process; acquire must exit 0, else abort; Maven under a watchdog; release in the runner's own `finally`; after the v2 swap, `--pid` and a heartbeat every ≤600 s.
    - **A build left running when you end your turn must be a background task the harness tracks (it resumes you when the task ends, as UI-PARITY-2 did at 00:46Z) or a detached runner whose log path you report.** An untracked background build dies with you (IFMS I-60).
    - One Maven run per workstream; respect the light-build cap (§4.4).
    - I-50: Maven run from bash can exit 127 without doing anything; read Maven's own result, never an exit code after a pipe.
    - Save red evidence to BK before fixing. Never use the base checkout; never treat `handoff/**` snapshots as product source.
    - Generated files change only through their generators (packgen, profilegen, `generate_changelogs.py` for 5 dialects, `gen_openapi.py`, `generate_native_config.py`).
    - Deliver a patch plus green proof; apply only on the integrator's GO; after the apply, run the gates in IT and report them.
    - Keep to the named file scope; take ids only from the integrator; shared files only via the integrator (except your own JL §6 claim row).
    - Copy the patch to BK at every milestone. Never touch `.ssh` or another owner's process or lock. Commits carry the attribution line.

## 3. Workstreams
### 3.0 File-scope hotspots (one holder at a time; changes via the integrator)
- **SecurityConfig, security filters and the token→role mapping** (incl. `DevTokenFilter`): S-ISO (SL-45 applied; then SL-70, SL-73, SL-72) → S-AUTH401 (2a) → S-MODCTL (SL-71) → S-SEC. S-UIP2 kept SecurityConfig untouched.
- **ProblemHandler** (+ `BulkProblemAdvice`, `FlowableWorkerCompatProblemAdvice`): S-ISO (SL-45 changed `isolationProblem`; SL-72 next) → RV of SL-44 and FWT-D11/D12 → S-MODCTL (SL-71; regression gates BulkIngestPostgresIT, FwtWorkerCompatIT) → S-SEC (log injection at `ProblemHandler.java:40,98`; re-check line numbers after SL-45).
- **Identity/tenant layer** (`ResolvedIdentityCache`/`CachedIdentity`, JIT, token→role): integrator (SL-63, done) → S-D10 (SL-67, test order only) → S-ISO (SL-70) → S-JIT (SL-76) → S-PERF.
- **Engine configurer and engine-slot lifecycle** (`SangamEngineConfigurer`: SL-68 javadoc done, SL-53 constructor guard; `EngineConfigurerHolder`: SL-68 done; `SangamProcessEnginePlugin`: SL-45 applied; sangam-server test configuration): S-DST (SL-53, production code) and S-D10 (SL-67, test harness only; must not edit `SangamEngineConfigurer`) → S-PERF.
- **Engine tenant creation** (`TenantScopedCreation`, `TenantIsolationException`): S-ISO.
- **Integrator only:** root `sangam/pom.xml` and `sangam-server/pom.xml` surefire/failsafe settings (SL-53's `-Duser.timezone=UTC` argLine and any SL-67 fork/reuse change arrive as hunks; applied in order SL-53 → SL-67); `application.yaml`; the parse listeners (SeatAssignmentParseListener, CompliancePurposePlugin, ConfigCaseBindingPlugin); `.gitattributes`; REG; HO; SANGAM.md; COORD.
- **Packs chain** (`packs.py`, `gov_ifms.py`, packgen, profilegen, the regenerated packs): S-IFMSWF step 1 (applied) → S-AUTH401 step 2 (holds `gov_ifms.py`: 2a generic now; 2b bill-type crosswalk only after JL-S-06) → S-IFMSWF2 (SL-57 data incl. `stage=AUTHORISATION`, after step 2) → S-PACKS4 step 3 (3-way merge onto IT) → S-PACKVER (SL-54), `customwf-inbox` (SL-55 core) → S-SL35 step 4 (+ SL-55 a) → the SG-37 move.
- **`sangam-api.yaml`** (only via `gen_openapi.py`) **and `OperatonTaskAdapter`:** S-UIP2 (applied) → the owner of the next id, one at a time.
- **sangam-ui:** S-UIP2 (applied) → S-SEC → UI-UPGRADE-1.
- **Connectors** (`HttpTransport`, `HttpRestConnector`, `LiveHttpConnectors`, `SoapConnector`): S-SG36 (SG-36, SL-61, SL-62, SL-65, SL-66 applied) → RV.

### 3.1 Sangam workstreams (one owner per id; contributors in brackets)
**REG-ALLOC (the integrator writes it in one targeted edit before any new dispatch):**
- Remap of v0.2's proposed ids onto REG: CI-BUILD-SG15-CACHE-TEST, CI-BUILD-SERVER-D10-CONTEXT and CI-LOG-OUTBOX-CAPACITY-NPE → **SL-67** (one agent; the NPE is a separate product root cause inside SL-67, with its own regression `OutboxCapacityMonitorTest`, its own BATCH line and its own RV check). CI-BUILD-CITATION-GUARD → **SL-68**. MOD-CTRL-401 → **SL-71**. REPO-STRAY-ROOT-FILES → **SL-74**. HYGIENE-SNAPSHOTS / REPO-SNAPSHOTS-ON-MAIN → **SL-75**. H2-BITAND → **SL-60**.
- New proposals: **SL-76** = JIT-409 (+ triage of ENGINE-REST-HTTP500 `ACT_UNIQ_AUTH_USER_INDEX_6`); **SL-77** = GP-13/D-TENDER (+ GP-21, the SL-12 follow-up); **SL-53a** = UTC persistence (a), scheduled target (child-id rule, §2 rule 8).
- Unchanged homes: UIP2-TASKLIST-OR = REG UI-parity row P45; CI-SDKS-NATIVE = SL-56 scope + SL-16 regeneration.
- Later findings get ids when reproduced: CI-DBM-*, the matrix product suspects, CODESCAN defects.

**A. In IT or running now**
| Id (aliases) | Owner [contrib.] | Status (00:55Z) | Depends on | Next step | DoD → verifies | Push |
|---|---|---|---|---|---|---|
| IFMS-WF (step 1; Sangam part of SD-434) | integrator (authors af5674588bc930fc7, ad2295e73baf6fad3) | APPLIED, uncommitted. PG E46 9/9; IfmsPacksIT 2/6 (unchanged in APPROVE-401 run 2); units core 75/75, packs 74/74, flowable-ports 266/266; SHA identity 1111/1111; `packgen --check` OK | Q3 | RV: `CrossPackCallBindingTest` red without `pin_cross_pack_calls`, green with it, on a re-copy of IT; known-red per test: IFMSPACKSIT-6of6 | D-SL → RV; T2 in R1 | P1 |
| SL-63 compile (SG-06/SG-07 regression; SG-21-class recurrence) | integrator | FIXED in IT | — | RV: clean compile on JDK 25 and 27, red before the fix | D-SL → RV + CI build/security/db-matrix | P1 |
| SL-64 ShellCheck | integrator | FIXED in IT | — | `shellcheck -S warning airgap/*.sh`; RV | D-SL → RV + CI lint | P1 |
| SL-68 citations (was "SL-03 residual") | integrator | FIXED in IT (23:24–23:34Z); `DecisionCitationGuardTest` green on JDK 21 + UTC | — | RV | D-SL → RV + CI JDK 21 | P1 |
| SL-74 empty root files | integrator | FIXED in tree (deletion in the next commit) | — | RV: reference grep shows nothing uses them | D-SL → RV | P1 |
| SG-36 + SL-61 + SL-62 (HIGH) + SL-65 (HIGH security) + SL-66 | S-SG36 (a2f414ffffbb900fc) | APPLIED 00:00–00:05Z; SHA-identical; 612/612 on JDK 25 in IT; REG SL-61/62/66 FIXED in tree; SL-65 row damaged (REG-REPAIR-SL65) | — | RV per id, red-before-green each; `[CONTRACT] SG-36-HEADERS` before P1 (C-18); one BATCH line per id | D-SG (SG-36) → T2 re-check incl. letter case and the secret rule; D-SL per SL id | P1 |
| UI-PARITY-2 (+ SL-60; P02, P13, P14, P35, P45, P49, P59; SL-37 rows; UIP2-TASKLIST-OR = P45) | S-UIP2 (a7102621b8c6f9ff6, finished) | APPLIED 23:21:34Z; report 00:46:02Z: all gates green (§0.4). SL-68 and SL-45 landed in IT during its runs | — | RV: SL-60 and each P-row; the P1 candidate gate re-runs the touched suites, OpenApiCompatibilityTest and `gen_openapi.py --check` on the final tree; P14 on Oracle/SS/MySQL later (S-MATRIX); additive APIs into CONTRACT-NOTICES | D-SL → RV | P1 |
| SL-45 (+ AC-09) + SL-69 | S-ISO (aa33e846abeb4489f) | Report 00:28:03Z (two real `/engine-rest` creation leaks; `TenantScopedCreation`; `ProblemHandler.isolationProblem` NPE; MDC scope filter). PG JDK 25 red → 5/5; `TenantScopedCreationH2Test` 6/11 red → 11/11. GO 00:28:43Z; applied in IT | — | SHA check + unit suites in IT (JDK 25); RV; `[INCIDENT] SL-45` (confirmed) in Wave 0 | D-SL → RV; IFMS told | P1 if RV passes |
| SL-70 (HIGH security), SL-73 (HIGH security), SL-72 (MEDIUM security) | S-ISO (same agent, one at a time) | OPEN; integrator decisions 00:28:43Z | SL-45 applied | Red → green each; report before applying. SL-70: a tenant token never carries a platform role (`sangam-admin` in a tenant token = TENANT_ADMIN of that tenant; PLATFORM_ADMIN only from the platform issuer); first check for a contract that relies on the old mapping. SL-73: fail-closed allowlist of tenant-scoped `/engine-rest` resources for tenant tokens, 403 for the rest; standalone tasks take the caller's tenant; the allowlist goes to IFMS as `[CONTRACT]` (C-19). SL-72: a by-id call on another tenant's object answers exactly like a missing id (404, no engine code, no id echo) | D-SL → RV; SL-73 D-MSG | P1 if green, else P1b |
| SL-53 (+ JL-S-03, ADR-D-27, the DST part of SG-11) | S-DST (a559ce3ccab6a4b3b) | DECIDED (b1) by the owner 23:49:14Z/23:50:23Z (IFMS SD-449); WIRE b1 23:49:25Z; rewired; touched-module runs in progress | — | Deliver (C-9): the core guard refuses at engine bootstrap for every embedding, naming `-Duser.timezone=UTC` and `sangam.engine.timer-zone`; no disable flag; an unknown zone is refused; the timer zone defaults to UTC when unset, never to the JVM default. Regressions red→green: DstFoldTimerH2Test autumn, Berlin and Asia/Kolkata timer cases, IST business dates at 00:00–05:30 stay correct (REC-01 U-855). Unit suites JDK 25 and 21; E8 PG re-run on the final patch (`customwf-dst`); root argLine hunk (integrator applies); deploy docs (every engine JVM, embedded hosts included, runs `-Duser.timezone=UTC`); ban on no-argument `now()`/`systemDefault()` in Sangam product code (ArchUnit or Checkstyle, planted violation). GO → apply → RV | D-SL → RV; SG-11 DST part D-SG → T2; BATCH flags the sha | P1 if RV passes |
| SL-67 (CI-BUILD-SG15-CACHE-TEST, CI-BUILD-SERVER-D10-CONTEXT, CI-LOG-OUTBOX-CAPACITY-NPE) | S-D10 (acab37a2ef6d1dc85, SCR wt-d10) | Red runs on JDK 21 and 25 in progress | — | Root fix keeps the D-10 guard in production. Preferred: the harness guarantees one engine per JVM (context reuse or forks). A test-only opt-in property is acceptable only if production code never reads it and `EngineSlotReleasedOnContextCloseTest` still proves release without it; RV decides (K-35). Cover the new `SangamUiParityApiTest` context. NPE: fix the conditional at `OutboxCapacityMonitor.java:83` with `OutboxCapacityMonitorTest` (mu ≤ lambda, open > 0). Regression: the full sangam-server suite in ONE JVM on JDK 21 and 25 with `-Duser.timezone=UTC`, in two class orders. Surefire hunks go to the integrator | D-SL → RV + CI JDK 21 | P1 if green, else known-red (Q2) |
| APPROVE-401 / T35 / bill validation (step 2; SL-14 AT 401, SL-16 T35, D-REFERRAL, IfmsPacksIT gpfAdvance BILL_INVALID) | S-AUTH401 (adbd0a0881b88eb1b) | Runs 2–4 (§0.4) | IFMS-WF ✓; **JL-S-06 before 2b** | Send the agent the 2a/2b split now (at 23:18:02Z it was told "`gov_ifms.py` is yours in step 2" without it): **2a** = generic fixes (`Vars.payloadOf`, `ConfigConnector`, `DevTokenFilter`, T35); **2b** = the `billType>formNumber`, `docId>typeId`, headOfAccount→scheme/code and signature→chainId remaps, moved out of generator code into a data crosswalk read by a generic step, only after JL-S-06. Automate the AT-6 signed-plugin setup (no skip). T35: no lengthened health wait (run 2's failure was CPU load); RV confirms the `allow-unsigned-webhooks` switch does not weaken what T35 asserts; preferred is a configured test webhook secret (`secret://` ref) so the signing rule stays on (K-23) | D-SL → RV (SL-14/SL-16 rows) | P1 (2a) / P1b (2b) |

**B. Integrator tasks now (light; no heavy lock)**
| Id | Next step | DoD | When |
|---|---|---|---|
| REG-REPAIR-SL65 | Rewrite the SL-65 row as one physical line: id `SL-65`, severity "HIGH security (raised 2026-11-09 23:53:40Z: the HTTP client's refusal message echoed the header value into logs and the OutcomeUnknown reason)", gap and work cells unchanged, status "FIXED in tree 2026-11-10, single-verified … Red 37/38 → green; 612/612 on JDK 25". Write the example value escaped or drop it; drop the old IN PROGRESS cell. Run the §0.9 item 2 check. Fix the edit helper so it parses whole rows (§2 rule 11). Record damage and repair in LED | D-DOC | Wave 0, first |
| ZERO-LOSS-CHECKPOINT | Create BK and copy in: this plan; E56X; SCR `SL-53.patch` (00:12:27Z) + a fresh wt-dst patch; SCR `sl45\` (fix bundle, sl45.diff, runner logs, IT reports); SCR `wt-d10`, `d10-staging`, `d10-logs`; the wt-auth401pg diff, `a401-*` logs and keep folders; the wt-sg36 diff, `sg36-evidence`, `sl6162-evidence`, `sl6566-evidence`; SCR `uip2-work\` + OLD `wt-uip2\logs-2026-11-09\int-*`; SCR `hlv2-*`; SCR `ci-check-128934575060.log`; SCR `pg-e8-utcjvm-*.log`; SCR `ifmswf-it1.log`; SCR `base-1105d9c` + .tar; SCR `sync-backup-*`; IT `git diff --binary` + every untracked file (15 at 00:50Z); the runner scripts; SCR `wf-*`; SCR `planner_*.txt`; SCR `tools-verify\`. Manifest in LED. (compile_lock.py already has a D: copy in scratch_A) | D-DOC | Wave 0 |
| LIGHT-BUILD-CAP | Keep ≤3 concurrent Sangam Maven builds machine-wide, ≤2 while an IFMS ACCEPTANCE lane, the soak or a boot-timed heavy run (T35) runs. Now: SL-45's IT unit suites, SL-67 and SL-53 continue; APPROVE-401's H2 runs and every new dispatch wait for a slot. Record in LED | rule applied; LED line | Wave 0 |
| REG-ALLOC + JL-S-07 (REG-REBASELINE, REGISTER-SYNC, SANGAM-TGR-SYNC, ID-RECONCILE-SG33-35) | Protect SL-54..SL-75 and SG-36..SG-39 with a BK patch first. Write SL-76, SL-77, SL-53a. Add rows SG-30..SG-35 (SG-31/SG-32 from SL-19 and E-54). Copy SL-58/SL-59 details from E-54 and E-53. Mirror E-54 closures (SG-20, SG-21, SG-22..25 CLOSED in-memory at 15e4135; SG-08a CLOSED). Restate: SL-53 (decided b1, SL-53a); SL-63 (JDK 21 text done); SL-19 retitled "server ruleset 26789812 blocks force-push/deletion; the signed-approval gate is client-side"; SG-06/SG-07 (CI lanes exist in ci-build.yml for JDK 21/25/27; 25/27 fail on 1105d9c; fixed by SL-63); SG-08(b)(c), SG-09, SG-10, SG-11, SG-26, SG-28, SG-29 (fix commit 15e4135, not "working tree"); SL-18 item (4) (SG-36 disproved it); SG-12, SG-19 (regression test owner below), SL-01/03/04/05/06/45; SG-15 point (1). Record the provisional SG-29..SG-41 → SL-01..SL-13 mapping (old transcript 03:27Z) | D-DOC; IFMS cross-checks against E-54 | Wave 0 |
| JL-RESTATE (Sangam rows) | Restate by targeted Edit: SG-35 (branch now `cc/wp04-routing-incident`; no "retest runs now"), SG-34 (P-449 vs plants), SG-29/SG-26/SG-28 (next sha, not 1105d9c), SL-45 (applied; two real leaks), IFMS-WF (real times; R-99 v0.2 received), APPROVE-401, SL-18, SG-15, JL-S-03 and SL-53 (decided), CI-MAIN, UI-UPGRADE-1, JL-S-04 (`jackson-2-bom.version`), SL-56 (scope). Add rows SL-67, SL-69..SL-73, SL-53a. JL §6 Sangam rows: close `customwf-ifmswf`, `customwf-uip2`; refresh `customwf-dst` | D-DOC | Wave 0 |
| JL-S4-OWNER-WORDS | Append verbatim, with transcript times: 11-08 05:32:05Z (boundaries), 06:43:03Z "हाँ, लगाओ", 07:01:29Z "हाँ, signed करो", 08:36:39Z "हाँ, रखो"; 11-09 01:51:59Z (two sessions), 18:21:39Z (recheck order); exact times for existing entries (17:04:36Z, 01:40:50Z, 01:39:48Z, 01:51:02Z, 23:49:14Z, 23:50:23Z) | D-DOC | Wave 0 |
| SL-56 + generator EOL sweep (covers CI-SDKS-NATIVE) | Add `eol=lf` for `sangam-rest/api/sangam-api.yaml`, the `generate_native_config.py` outputs (reflect-config.json, model-classes.txt) and every other generator whose `--check` compares bytes (`generate_changelogs.py` outputs; verify each). Regression: each `--check`, `OpenApiCompatibilityTest` and the E46 SHA checks on a `core.autocrlf=true` checkout. Drift pre-commit check. Renormalization as its own commit | D-SL → RV + CI sdks | P1 |
| ROUTING-INCIDENT-CT (Sangam half of C-1) | `RoutingIncidentContractTest` pins `sangamRouting`, the two codes, `ROUTING_INCIDENT` and the payload keys | D-SL → RV | P1 |
| ADR-D-27 | Write ADR-D-27 in SANGAM.md: engine JVM in UTC (b1); business zone only via `sangam.engine.timer-zone`/`BusinessClock`; UTC persistence (a) scheduled as SL-53a; consequences for embedding hosts | D-DOC | with SL-53 (P1) |
| CONTRACT-NOTICES (answers IFMS's ask for the `sangam-bom`/API delta since 15e4135) | Classify each change as additive or `[CONTRACT]`; list below this table | D-MSG inside BATCH P1 | Wave 0 → BATCH |
| SG36-HEADERS-CONTRACT | Send `[CONTRACT] SG-36-HEADERS` (C-18) before P1: file-mounted secrets ending in a newline are now refused, and reserved request headers are refused | D-MSG | Wave 0 |
| SL45-INCIDENT | Send `[INCIDENT] SL-45` (confirmed): two `/engine-rest` creation leaks on 1105d9c, fix applied in IT, ships after RV; ask whether any IFMS path uses `/engine-rest` or creates deployments/batches under a tenant identity | D-MSG | Wave 0 |
| HEAVY-LOCK-V2 (P-458), Sangam half | Finish review `wtkb390st`; send ACK or changes with file:line. Adapt the runners: `--pid` = runner pid; heartbeat job every 600 s against the 1800 s lease; stop the job, then release in `finally`. Agree a MACHINE-QUIET swap window (both locks free) | D-MSG; runners adapted | Wave 0 |
| HANDOFF-DOC-UPDATE | HO list below this table | D-DOC | P1 |
| LED-CORRECTIONS (+ new §6j) | Append dated corrections, never rewrite; list below this table | D-DOC | Wave 0 |
| MEMORY-POINTER | Update `project-handoff-2026-11-08.md`: drop "state as of ~01:50"; agents now SL-53, SL-45, APPROVE-401, SL-67 working, SG-36 and UI-PARITY-2 idle, IFMS-WF finished; PR #3 closed by Dependabot 01:44:37Z; read order IDX → JL → this plan → LED (§6f on) → IT HO (not the base checkout); both session ids and titles; the two-session setup. Point the MEMORY.md index line to JL and this plan | D-DOC | Wave 0 |
| CLOCK-CORRECTIONS (Sangam half) | Append JL §5 corrections for Sangam's own lines with the real times in §11 F; add Sangam sender entries for 03:18:28Z (IFMS-WF-SOD ACK), 23:49:31Z, 23:50:36Z and 00:11:55Z; correct JL §4 and LED §6i ("~00:20" → 23:49:14Z) and tell IFMS in the JOINT-PLAN message; fix the Sangam §2 "Updated" cells | D-DOC | Wave 0 |
| ARTIFACT-ROUTE (P-460, Sangam half) | Proposal in this plan (C-2): a reproducible build of the pinned Sangam sha into a private Maven repo inside the oneifms lane, with the recipe (JDK, `-am`, target, a checksum list of produced jars) in each BATCH; no registry publication before SD-419/SD-422 (Q5); or IFMS keeps both Sangam modules out of the default/CI reactor until SD-419. IFMS chooses in P-460 | D-MSG; DoD: an oneifms CI build that does not depend on `.m2-sangam-15e4135` | Wave 1 |
| E-58-REVIEW (P-454) | Read E-58 v0.2's Sangam-links section and report corrections in JL §5 | D-MSG | Wave 1 |
| OLD-INVENTORY (+ FREEZE-UNFREEZE) | Inventory list below this table; removal only on the owner's words (Q26) | D-DOC | Waves 0–1 |
| OLD-SCRIPTS-RETARGET | Before reuse, retarget to D:-based worktrees cut from IT, in detached-runner form: `run-gp21.ps1` (points at the base checkout), `run-tscale.ps1` ("main tree"), `run-comp-pg.ps1` ('+' separator → commas), `packs3-it.ps1`, `packs4-it.ps1`, `sg14-run.ps1`, `fwtd-it*.ps1`, `auth401-it.ps1` | D-DOC | before dispatch |

**CONTRACT-NOTICES** (each classified additive or `[CONTRACT]`): SL-16 `WorkOutcome` (6th component of CompleteRequest); TenantGuard 401 TENANT_UNAVAILABLE / NOT_LINKED; the SL-43 `sangam-quota` pool; the SL-44 bulk API; the SL-37 role gate and task form; SL-51 `/engine-rest` 429/413/403; ROUTING_INCIDENT v1; sangam-bom pins (jackson-bom 2.22.3, module-scala 2.22.3, liquibase 4.33.0, java-uuid-generator 5.2.0); SG-36 + SL-61/62/65/66 header and secret rules (C-18); UI-PARITY-2 filters, `activityInstanceId`, history `state`, `GET /tasks?variableNames=` (≤20), `400 FILTER_UNSUPPORTED` (`sangam-api.yaml` +47 lines, additive); P45 (non-admin task list fixed); SL-45 refusals (deployments outside the caller's tenant and cross-tenant batches → 403 `TENANT_ISOLATION_VIOLATION`; batches take the caller's tenant); SL-69 MDC scope; SL-70, SL-72, SL-73 when they land (C-19); SL-54 immutability (C-16); the SL-55 incident/dead-letter; SL-71 status codes; the JL-S-04 property contract; SL-53 (b1) guard and timer-zone default (C-9).

**HO updates:** §3 git state. §4 L98 "WorkFlow is the IFMS master" → the IFMS session (SD-446). §2 L60: "JDK 21 javac (21.0.10 and CI's 21.0.13) accepts the name-shadowing error; javac 22+ refuses it; so compile gates also run on JDK 25 and 27" (K-1); keep L61 (detached runner). §6a SG-06 "JDK 25 and 27 green (329 tests)" → broken by 16e0e11, fixed by SL-63. §6b SG-15 "9/9 locally" → CI fails it order-dependently (SL-67). §6b SL-43 green locally vs D-10 errors in CI (SL-67). §6b-URGENT-2 SG-35 "HIGH, silently parked" → MEDIUM, 0d04fea, SD-445. §10 first actions → §10 of this plan. The IDX Sangam row: owner cell = SANGAM + its id; handoff pointers incl. this plan and IT HO.

**LED corrections:** §2 PR state; §3 comp logs were read (GP-13 PASS on PG) and the E16b failure is gp39, not GP-21; §5.2, §6a and §6b: SL-53 decided (b1) with SL-53a; §6 resume plan obsolete; §6a L98: PR #3 closed by Dependabot 01:44:37Z and main carries vite 6.4.5, vitest 4.1.12 and react-router-dom 7.18.5 through 2aaf44f; §6a L102–103: CI red; §6f "~05:20" vs the notice at 04:10:09Z; §6g JDK 21.0.13 compiles; §6h: outage 20:14:49Z → ~23:16Z, the "partial apply" statement was retracted at 23:53:40Z (K-34), the HEAVY-LOCK-V2 re-run is task `wtkb390st`, the SL-67 agent is `acab37a2ef6d1dc85`; §6i owner words at 23:49:14Z and 23:50:23Z; §7: IT is on 1105d9c and the HANDOFF commit goes on the IT branch, never on `handoff/2026-11-08`. New §6j: every event after 23:50Z (SG-36 children applied 00:05Z; SL-45 GO and SL-69..SL-73 at 00:28:43Z; UI-PARITY-2 report 00:46:02Z; REG damage; SL-74/SL-75; APPROVE-401 runs 3–4).

**OLD-INVENTORY:** every OLD `wt-*`, including those with no snapshot (wt-dst, wt-sg35, wt-i8, wt-conf, wt-sl37, wt-quota, wt-quota-stash, wt-uip2-ctl, wt-packs2, wt-packs3, wt-perf-base, wt-base); loose OLD folders (parked\, keep\, keep-old\, fwtd-bak\, sg27\, ix8\, cofog-src\, tscale-after-probe\, tscale-base\) and files (DstFoldTimerH2Test.java, SecurityIdentityFilter.fixed.java, sangam-api.handedited.yaml (bypasses `gen_openapi.py`), sl15.patch, sl15b.patch, apply-all.patch, final.patch, mine.patch); `D:\wt-fwt` and `D:\wt-scale`. For each: diff against its true base (9c831d0, 15e4135 or 1105d9c); record unique content, owner and disposition in LED; copy unique content to BK.

**C. Queued for dispatch.** At most 3 Sangam builds at once (2 during IFMS ACCEPTANCE, the soak or a boot-timed heavy run). Each starts from a D:-based worktree cut from IT HEAD and gets the brief in §2 rule 14.
| Id (aliases) | Owner [contrib.] | Status | Depends on | Next step | DoD → verifies | Push |
|---|---|---|---|---|---|---|
| SD-428-PASS RV-1 / RV-2 | S-REVIEW: `customwf-review-a` (connectors, isolation, security) and `customwf-review-b` (build, harness, UI, packs); new agents, never authors (§9.3 author map) | open; **first dispatch of Wave 1** | items applied | RV scope below this table | D-SL (REG CLOSED/REOPENED) | P1 gate |
| SL-54 pack versions | S-PACKVER `customwf-packver` | OPEN, HIGH | step 1 | Same id and version with a different checksum is refused; duplicate filtering on rollback/redeploy (SANGAM.md X.2.4.2); regression after a rollback and after a changed-content redeploy: every cross-pack call resolves to exactly one definition. P1 must not ship content-changed packs under an existing version: default is a packgen version bump of the changed callee packs (`gov.ifms.bill`, p1-bill-payment, tender, up14) | D-SL → RV; C-16 `[CONTRACT]` | P1 (bump) / P1b (guard) |
| SL-55 core | `customwf-inbox` | OPEN, HIGH | — | A deterministic business refusal in inbox processing is non-retryable and becomes an audited incident or dead-letter with the reason; poison-message regression | D-SL → RV | P1 if green, else P1b |
| SL-55 packs (a) | S-SL35 | OPEN | step 3 | The generator puts a coded reason on every failure route; test over every generated pack | D-SL → RV | P1c |
| SL-57 budget-distribution seats | S-IFMSWF2 `customwf-ifmswf` | R-99 v0.2 received | step 2 applied (S-AUTH401 holds `gov_ifms.py`) | Pack data only: Central DH-1 enters, PAO-1 approves (role PAO, not PR_AO), holder DDO-1, SoD by person and seat, PAO BUDGET_DISTRIBUTION with no monetary limit, fail-closed catch-all; default `stage=AUTHORISATION` with a test that `ledger.distribute` moves the limit into AUTHORISATION for DDO-1; RJ as a new tenant override in `RJ.yaml`, VERIFIED rows only, ASSUMED seat ids as labelled placeholders tied to MD-195..207/OQ-218..227. The PR_AO placeholder in wt-ifmswf is NOT applied. The IfmsPacksIT driver acts as each seat's real holder | D-SL → RV (`SectorConformanceTest.ifmsBudgetDistributionByThePrincipalAccountsOfficer` red → green) + IFMS confirms the encoded rows (C-12) | P1b |
| IFMSPACKSIT-6of6 | S-IFMSWF2 | 2/6 | step 2 | Per test: gpfAdvance (step 2); budgetDistribution (allocation power table + ledger set-up; SL-57); reappropriation (tenant profile; SL-55); leave (EL credit + timer). Fix test setup only where setup is the cause; product causes get SL ids; no weakened assertions | D-SL → RV | P1b re-gate |
| PACKS4 (step 3; SANGAM-BANKPACK-MONEY, SL-50(c), SL-33, Sangam parts of SG-01/SG-20, band edges) | S-PACKS4 `customwf-packs4` | green in OLD wt-packs4 (older base) | steps 1–2; `[CONTRACT] PACKS4` answered | 3-way merge onto IT, regenerate (`--check`), IfmsPacksIT on PG. Money strings ('2CR', '5L', '1CR') resolved in data: profilegen writes integer minor units; a generic validator refuses non-integer money (no lakh/crore parser in Java). Add IFMS edges 9,999,900 / 10,000,000 / 10,000,100 paise to the boundary tests. SL-33: cite rule values or mark placeholders | D-SL → RV; SG-01/SG-20 D-SG → T2 R2 | P1b |
| SL-35 (step 4) + SL-32 | S-SL35 `customwf-sl35` | not started | P1b | E2E adoption of a profile document type; PG proof; then the SL-32 clock/steering driver | D-SL → RV | P1c |
| SG-35-SANGAM | S-SG35 `customwf-sg35` | open | — | PG proof of `StrictExclusiveRoutingH2Test` sg35*; gwBand bill-pack regression (E-46 WF-4 fixture as pack data + test, red without 0d04fea); REG row (MEDIUM, SD-445); inventory OLD wt-sg35 | D-SL → RV; D-SG via X-13 after P-452 | P1b |
| SL-71 (MOD-CTRL-401, MODULE-ERRORS, unmapped `/engine-rest` errors) | S-MODCTL `customwf-modctl` | OPEN | SL-45 applied; SL-67; SL-72 (same ProblemHandler); RV of SL-44 and FWT | One generic ProblemAdvice for every Sangam REST controller and the engine-rest error dispatch; the status is kept; a direct `/error` stays denied; regression per controller family | D-SL → RV | P1b |
| SL-76 JIT-409 (+ ENGINE-REST-HTTP500 triage) | S-JIT `customwf-jit` | to allocate | identity layer after SL-70 | Idempotent upsert or single-flight, with a concurrency regression; triage whether the H2 unique violation is the same race; tell IFMS if its users are JIT-provisioned | D-SL+X | P1b |
| SL-77 GP-13/D-TENDER + GP-21 (SL-12 follow-up) | S-COMP `customwf-comp` | to allocate; GP-13 PASS on PG in OLD wt-comp (uncommitted); D-TENDER and GP-21 never ran | — | Read the wt-comp diff; red/green on PG with classes comma-separated; GP-21 alone (product vs test clock, with evidence); other DBs via S-MATRIX | D-SL → RV | P1b |
| SL-59 bounded waits (P-419; S-R-5) | `customwf-sl59` [T2 evidence] | OPEN, HIGH | IFMS dumps | Read dump 7c748857; bounded statement/query timeouts on every engine and Sangam JDBC wait path (configurable); regression: a planted blocked connection times out within the bound (red without the fix); state whether JobMetricsInterceptor/RollbackGuardInterceptor can retry on a closed connection; check whether 460c95b is relevant | D-SL+X | P1b |
| SL-58 T-4 SMOKE | joint triage: IFMS leads (P-429) [`customwf-sl58` repro] | OPEN | — | Compare Sangam vs Operaton at SMOKE on H2; if the kit's SMOKE scale is the cause, close as "not Sangam" with evidence; otherwise root-fix | D-SL or a closure note | R1/R2 |
| MATRIX (+ AC-07; Sangam parts of SG-04/SG-05/SG-13; CI-DBMATRIX-NO-SIGNAL; CI-DBM-ORACLE-ROOTSEQ-NULL; CI-DBM-MYSQL-P2-UTF8MB4-FK; CI-DBM-SQLSERVER-DIALECT; CI-DBM-POSTGRES-ACCEPTANCE; SL-44 other DBs; P14) | S-MATRIX `customwf-matrix` | SS2022 17F/44E (mostly harness); others not run | P1 | Steps below this table | D-SL per id → RV; SG-04/05/13 D-SG → T2 | P1b+ |
| SG-15 T-SCALE (+ SL-11; E2.7 1677.77 ms vs 111 ms; SL-43 nested-connection audit) | S-PERF `customwf-perf` | round trips 485→365; starts/s not measured | committed sha; SL-67; Q7 | Read OLD `tscale-after-probe\report.txt` and `D:\wt-scale` evidence; quiet-window run on a committed sha (starts/s, p50/p95/p99); next targets 74 commits and 43 SEC_TENANT reads per lifecycle; root-cause E2.7; audit nested main-pool connections; old path on SS/Oracle/MySQL | D-SG → T2 (E-53 §4) | after P1 |
| SECURITY (CODESCAN-DEPCHECK-STALE, -SPRING-CSRF-BYPASS, -WEAK-CRYPTO, -WIDER-TYPE-COMPARISON, -SENSITIVE-LOG, -TEST-SQL-CONCAT, -MEDIUM-JAVA, -UI-XSS-DOM, -UI-BUILD-SCRIPTS; VULN-kotlin-stdlib, VULN-protobuf-java, OWASP-SUPPRESSIONS; CI-SECURITY-NVD-KEY-MISSING) | S-SEC `customwf-sec` | open; Java analysis stale since 15e4135 | SL-63 | Triage each CodeQL finding: fix with an SL id or a written justification. Re-verify each OWASP suppression (expiry 2027-05-31) against the CVE text and the jar contents; they stay only once the reviewer named in Q13 signs them. NVD: after the owner adds the secret (Q18), run dependency-check with it. Alerts are dismissed only on the owner's words (Q21) | D-SL per fix; suppressions D-OWN-DEC (Q13) | P1b+ |
| CODESCAN-SDK-TS-REDOS + SDK-STORED-OUTCOME (SL-16 residual) + AC-08 | S-SDK `customwf-sdk` | open; AC-08 blocked on downloads | Q12 | Linear regex + test; stored-outcome completion in Python/TS/PHP; unit tests for .NET/TS; AC-08 after Q12 | D-SL; AC-08 D-OWN-DEC then D-SL | later |
| IX8 AC-01..AC-06 (SANGAM.md IX.8 acceptance) | S-IX8 `customwf-ix8` | open | P1b | Run AC-01..AC-06 on PostgreSQL on the P1b sha; each failure gets an SL id and a root fix; AC-03 per Q11 | D-SL per failure; REG row per AC | P1b re-gate |
| SG-37 programme ids in product files | integrator | ACKed with scope | steps 2–3 | Generic "consumer licence-policy exception <ref>" wording; consumer-acceptance material to a labelled location with frozen bytes unchanged; the SD-427 evidence path keeps a pointer | D-SG → T2 | P1c |
| SG-38 / SG-39 / SG-14 (+ AC-11; registry signer, HMAC pepper) | S-DEPLOY `customwf-deploy` | open | Q12 for kind/GraalVM | SG-38: boot test with `sangam.security.production=true` and Helm-rendered settings; trace `database.existingSecret` → pod env. SG-39: schema switch with an internal-LB default and a lint test. SG-14: missing proofs after approval | D-SG → T2/Hosting | P1b / later |
| SL-18 | integrator | built; P-450 5/8 confirmed; 3 parts → SG-36/38/39 | those rows | Close after them; RV covers every file 2aaf44f introduced | D-SL+X | — |
| Sangam parts of SG-27 / SG-30 / SG-33 / SG-34 / SG-09 / SG-16 / SG-07 / SG-12 / SG-18 / SG-19 | integrator [RV; S-MATRIX for SG-07; `customwf-small` for SG-19] | per row | — | Per-SG steps below this table | per row | P1–P1b |
| DEC-TASK-EVENTS | `customwf-small` | unverified | — | Test whether task.suspend/activate events fire on a bulk update; root-fix if not, else close | D-SL | P1b |
| SL-04, SL-05/06, SL-07, SL-30, SL-36, SL-40-42 residuals, SL-44, SL-51, SL-52, FWT-D11/D12 docs | authors: S-MATRIX (SL-04 GP-46/49/D-LAND-ACQ, SL-44, SL-51 PG); integrator (SL-05/06 PG + REG, SL-07 doc, SL-36 + TRACEABILITY FWT rows + PREMIUM-GAP-RECHECK, SL-40-42 additive REST plan); S-IFMSWF2 (SL-30 after step 3) | per REG | — | Authors make the first proofs; RV verifies | D-SL / D-DOC | per row |
| SL-14 / SL-31 / SL-15 + SANGAM-AT1-BILLTYPE | integrator | owner/IFMS input | Q8, Q9; JL-S-06; MD-192 | SL-14: REG L38 still cites the 401; SL-31 per Q9; AT-1: verify on main (old transcript says RJ_FVC in 2aaf44f; IFMS verified RJ-FVC-GENERAL); re-key the GA-55 template to GA-55-A after MD-192 | D-SL | P1b |
| SL-53a UTC persistence (a) | S-DST (later) | scheduled (owner 23:49:14Z); date = owner (Q34) | SL-53 landed; after the SL-54/SL-55 wave (proposed) | Design a MyBatis Date type handler at SqlSessionFactory build plus migration of existing rows for all 5 dialects (`generate_changelogs.py`); `[CONTRACT]` to IFMS before landing | D-SL → RV; D-SG if IFMS adopts | after P1c |
| TECH-DEBT-PARSELISTENER | integrator | record | — | `AbstractBpmnParseListener` is deprecated for removal (SeatAssignmentParseListener:31, CompliancePurposePlugin:68, ConfigCaseBindingPlugin:58); plan the migration before the Operaton release that removes it | D-DOC | later |
| REPO-RESOLUTION-HYGIENE | integrator | open | — | Find where `camunda-nexus` (401) and jvnet (PKIX) enter Maven resolution; remove or justify against the SD-301/SD-427 allowlist | D-SL | P1b |
| AC-10 Hindi/English usability | integrator; OWN | not tracked before | Q28 | Plan the session | D-OWN-DEC | later |
| SL-75 HYGIENE-SNAPSHOTS + CI-TRIGGER-300-FILE-LIMIT | integrator | OPEN (after step 3) | OLD-INVENTORY | **P1:** CodeQL `paths-ignore: handoff/**` + `.github/dependabot.yml` exclusion; scope whole-tree scans to `sangam/`. **P2:** dedicated removal push → owner-approved dispatch (Q16) → dismissal of #40–#51 on the owner's words (Q21) | D-SL | P1 / P2 |
| CI-MAIN + CI-LICENCE-NOT-RUN-ON-HEAD | integrator | red | P1 | After P1: owner-approved dispatch of skipped workflows; then `[REPORT] CI green` to IFMS (P-435; promised 2026-11-08 06:57Z) | all green | after P1 |
| UI-UPGRADE-1 | S-UI (later) | "Separate upgrade later" stands; PR #3 closed by Dependabot; main carries the WIP upgrade | Q20 | Re-scope from today's baseline | D-OWN-DEC + D-SL | later |
| JL-S-01 (+ CWF-DOCS-WT, IFMS P-448) | integrator | verified superseded | Q6 | Zip the 11 files into BK, then `git worktree remove` on the owner's words | D-OWN-DEC + D-OWN-EXE | after Q6 |
| Pushes P1, P1b, P1c, P2, RC | integrator; the owner signs each | — | §4.2 | §4.2 | pushed via the gate; BATCH sent | §4 |

**MATRIX steps:** make harness isolation and tenant seeding dialect-aware (no skips) and the test SQL dialect-neutral; classify the product suspects and give each an SL id (E4 e4_4 LayerSqlException instead of SimulatedCrash; E5 e5_5 BUFFERED expected, RECEIVED observed; 401 TENANT_UNAVAILABLE in E3, E4, E7 and WorkApiLedger); on the fixed head reproduce Oracle ROOT_SEQ NULL, the MySQL P-2 FK failure and the SQL Server dialect/DATEADD issues; one DB per ticket; run the SG-13 Oracle customer-driver path.

**SD-428-PASS RV scope:**
- **RV-1** (items already on 1105d9c; PG tickets interleaved from Wave 1): SL-01/02/08/09/10/12/13/14/16/17/18/30/31/32/34/36/37/38/39/40/41/42/43/44/46/47/48/49/50/51/52; UI parity round 1, T08, the outbox repair, auto-deploy, BusinessDateGuard, E10, FW-07, FW-30, FWT-D11/D12, the EngineSlotReleased gap; every file that 2aaf44f, f553b43 and 3736f8f introduced, incl. the SL-18 Java and the SG-33 guard; the SG-34 gate (run the new selftest cases against 35996f9, where they must fail).
- **RV-2** (every SL in the P1 candidate): SL-45, SL-53, SL-56, SL-60, SL-61, SL-62, SL-63, SL-64, SL-65, SL-66, SL-67, SL-68, SL-69, SL-74 (+ SL-70/72/73 if included), IFMS-WF (`CrossPackCallBindingTest`, the SangamModelValidator EXT finding), the UI-PARITY-2 P-rows.

**Per-SG steps (Sangam parts):** SG-27: restate the JL row as "exception accepted; SD-427 conditions incl. S-F-6 pending (IFMS)"; name the condition-5 tests (FeelBomPinsTest/FeelBoundaryGuardTest; verify) and the SBOM entry. SG-30: SUPPORT-MATRIX note "H2 requires MODE=PostgreSQL". SG-33 and SG-34: the BATCH names 2aaf44f (WIP, not verified); the SG-34 trust-root statement comes from the gate record of the 01:40Z push, never from reading `.ssh` (else Q25). SG-09: read OLD wt-i8 and `files-i8.txt`; report the EngineInternalsPinTest i8 outcome; decide on an ArchUnit gate. SG-16: every runner logs the sha, JDK, JVM zone and DB. SG-07: acceptance ITs on JDK 25 after P1. SG-12: update REG and the alert count (Q17). SG-18: draft an ownership/continuity plan (Q19). **SG-19:** `customwf-small` writes the red-before-green regression (no-DDL start with a planted auto-DDL), D-SL.

### 3.2 IFMS workstreams (IFMS owns these rows; Sangam's view of what it needs)
| Id (aliases) | Owner | Status (00:55Z) | Next step (IFMS) | What Sangam needs or gives |
|---|---|---|---|---|
| P-452 / SD-445-IFMS (+ X-13, WP-04-SANGAM-ADAPTER, ADP-WF-SANGAM) | IFMS WP-04 | IN PROGRESS: uncommitted in P452-WT on `cc/wp04-routing-incident`; I-60; relaunched; compile lock `ifms-p452` | Commit, two SD-428 verifications, push card, `[REPORT]` to Sangam, then X-13. Settle the SG-08c overlap order. **Bring the incident path to `trials/workflow-sangam` (the copy Trial 2 runs) or converge to one copy (K-37)** | C-1 + its contract test; X-13 retests only after the P-452 commit |
| WP02-WORKFLOW-REACTOR (P-445 fix 3) + P-459 | IFMS WP-02/master | fix 3 done: both Sangam modules in `-Pdemo`; P-459 OPEN | State what P-459 still needs, given that the parent already has `workflow-engine-boundary` with org.sangam* (K-42); planted enforcer proof | — |
| ONEIFMS-MAIN-DEMO-SANGAM / P-460 | IFMS master + Sangam (artifact publication) | `-Pdemo` on main needs `.m2-sangam-15e4135` | Choose the artifact route (ARTIFACT-ROUTE; Q5) or keep both Sangam modules out of the default/CI reactor until SD-419 | Recipe + checksum list in each BATCH |
| P-445 (+ ONEIFMS-MAIN, SD-444) | IFMS master (`ifms-integ`) | step 1 DONE (126/126); main 970a07cc | **Claim the Oracle/SQL Server test-phase lanes in JL §6**; SD-428 re-check; push card for the test-phase fixes | Sangam never delays it (§4.4) |
| I-55 residuals | IFMS master | records corrected for X-13 | Also correct the REC-02/965062f claim "All 21 branches pushed, local=remote verified" (wp04 886d72e, wp03 07ffae0e, trial2 3d6325e4 were pushed only at 02:07–02:16Z) and WP04 skip register S-WP04-06 (SG-33 "closed by the WP-02 pin" vs E-54 OPEN) | — |
| TRIAL2-RETEST (P-444; TRIAL2-PIN-1105D9C, SANGAM-RETEST-BASELINE, TRIAL2-RETEST-1105d9c, TRIAL2-RETEST-QUEUE) | IFMS T2 | waits for the next Sangam main sha | Steps below this table, incl. the UTC-JVM prerequisite (K-43) | `[REQUEST] TRIAL2-RETEST-SCOPE` (§3.3) |
| P-463 (SL-53 IFMS side) | IFMS master | APPROVED TO PROCEED after P-452 | `sangam.engine.timer-zone=Asia/Kolkata` in EngineHost and topology; six `LocalDate.now()` lines; `-Duser.timezone=UTC` argLines; planted UTC/IST round trip of the nine Timestamp sites and one timer on both DBs; time-rule SD; ArchUnit/Checkstyle ban | Sangam flags the SL-53 sha in BATCH |
| SD-449 / P-453 | IFMS master | SD-449 DECIDED; P-453 CLOSED; owner confirmation in the IFMS session requested (Q35) | Record the owner's confirmation | — |
| JL-S-04 (consumer side) | IFMS WP-02 | proposal open | ACK C-5 once the property name is confirmed | Contract text and guard messages |
| SANGAM-15E4135-CONTRACT (+ ADR-D-26) | IFMS WP-04/WP-15/T2 | partly adopted | Audit OneIFMS DMN/rule packs and E-46 fixtures for typeRef double, reliance on null inputs, overlapping XOR | C-7, C-8 |
| SD-427 / P-433 (SG-27 conditions) | IFMS T2 | not run | S-F-1..**S-F-6** (the queue says "checks 1–5"; S-F-6 independent OSV is missing, E15) | Sangam names the condition-5 tests |
| SG-20-SQL | IFMS T2 | not run | M-1..M-7 + plant twin on the SQL ledger store, Oracle + SS2025, at R1 and again at R2 | — |
| TRIAL2-LANES (P-432; E-53 §4) | IFMS T2 | not run on a fixed sha | 6 lanes (Oracle 23.26.3, SS2025, SS2022 × JDK 25/27) × 3 clean passes = **18 runs** of 40–100 min; SS2025 ACCEPTANCE first, two dumps ≥6 min apart on a hang; then the 8 h soak, perf vs Operaton, chaos | §4.4 long slots; SL-59 |
| P-419 / P-429 | IFMS T2/T0 | P-419 PARTLY; P-429 OPEN | Re-run with dumps and a socket-timeout count; lead the T-4 triage | SL-59, SL-58 |
| E-53-COVERAGE | IFMS T2 | not built | W-05 threats (S-T-*), authorisation item 6 (S-A-1..7), MG-1..9, the matrices, PIT ≥70%, Semgrep, gitleaks, scoped to `sangam/` (E3) | Sangam fixes failures (SG → SL) |
| SD-417 / TRIAL0-KITS | IFMS T2/T0 | open | X-22 on Oracle/SS (JDK 25/27); spotless; X-6 on Operaton; `completedActivityCount` + plant in both adapter copies; X-8 harness fix | — |
| TRIAL0-JACKSON (+ SD-437, P-437, P-439, P-446) | IFMS T0 | lanes 53/53; OSV gate exit 2 | Restatement below this table | Sangam keeps the 2.22.3 pin (C-4) |
| CI-ONEIFMS-QUEUED | IFMS master + OWN | every run queued since 2026-11-08 18:20Z | Q31 | — |
| JL-S-05 / SD-434-PACK-HOME / SD-434-PACKS / WP-15 | joint (IFMS answers) | open | ACK or counter C-12; re-spawn WP-15 | Proposal C-12 |
| JL-S-06 / P-438 / MD-192 (GA-55) | IFMS master (WP-01) | P-438 PARTLY; OQ-215..217, MD-193 open; SI-GA-55.pdf awaits OCR | `[REPORT] MD-192` (GA-55-A; RJ-FVC-GENERAL verified); OCR later | Step 2b waits for it |
| P-455 (IFMS-WF-SOD; R-99) | IFMS Business architecture | ANSWERED; R-99 v0.2 | Restate row IFMS-WF-SOD; keep seat ids ASSUMED until the sponsor names them | SL-57 |
| P-450 / HOSTING-SL-18 (+ SG-36..SG-39) | IFMS Hosting | 5/8 confirmed | Re-check SG-36 with SL-61/62/65/66 on the P1 sha; SG-38/39 later; record SL-53 in E-37 | Sangam fixes |
| P-458 / HEAVY-LOCK-V2 | IFMS (tool owner) | implemented; swap HELD | Swap after Sangam's ACK in a quiet window; REPORT the swap time | ACK + runner adaptation |
| P-454 (E-58) | IFMS master | DONE as a draft map (E-58 v0.2) | Take corrections from Sangam's review | E-58-REVIEW |
| REC-05 / REC-06 / REC-01 / REC-02 / PENDING | IFMS master | REC-05 v1.4 | After each batch: `refresh_handover.ps1`; confirm REC-02 rows for b6090f7, b2e03be, c6aa635, 965062f and PENDING rows for the SG-34/SG-35 retests and the cde-events CR; JL §1 to v1.4 | — |
| HANDOFF-INDEX (+ HANDOFF-ALL, HANDOFF-INDEX-ROWS) | each side its own row; IFMS for DOIT/PHED (interim) | IFMS row main 80dc4184 and "P-447 owner decision"; Trial 2 row "retest on 1105d9c queued"; DOIT/PHED empty | IFMS row → main 970a07cc and "Keep local only (SD-448)"; Trial 2 row → next sha; JL §7 item 5 the same (shared section; Sangam edits with IFMS's ACK); DOIT/PHED per Q29 | Sangam updates its row |
| SD-441 residual + COORD v1.2 | IFMS master; Sangam (COORD text) | E-57 §14 added | ACK COORD v1.2 (§2); commit E-57 and run `refresh_handover.ps1` if not done | Sangam edits COORD §1/§6/§8 |
| P-435 (GH-REQUIRED-CHECKS) | IFMS re-scopes; OWN decides in SANGAM (SD-436) | cites deleted ruleset 26789296 | Re-scope to ruleset 26789812, owned by Sangam | `[REPORT] CI green`; Q14 |
| E-54 v0.2 (P-451) | IFMS T2 | v0.1 + corrections | Contents below this table | REG mirrors it |
| cde-events 2.0.0 decimal-money CR (59 decimal money fields vs SD-398) | IFMS master | open | Decide the CR; it intersects C-6 and SD-434 bills/payments | — |
| P-78 (CDE classification) | OWN (IFMS session) | open | — | The bills/payments packs wait for it |
| Hosting OQ-109, E-37 H-2..H-8 | IFMS Hosting | waiting for the backend | State whether any depends on Sangam (SL-18/C-13) | — |
| SANGAM-ADOPTION (+ SD-422, SD-422-PAUSES, SD-419) | OWN; IFMS presents | blocked on P-432, SG-04, SG-05 | Keep the adapter non-production until the sponsor decides; present after P-432 (Q32) | Sangam CI evidence |
| oneifms primary checkout stale refs | IFMS master | `D:\GITHUB  Project\oneifms` on m2/sd212-owners e1dd58dc, local main 98ea8799 | Warn in REC-05, or update the refs | — |
| IFMS owed commit ids | IFMS master | — | The P-452 commit + X-13 kit; Trial 2 evidence; E-54 v0.2; the TRIAL2-PIN update | — |

**TRIAL2-RETEST steps (on the P1 BATCH):** fresh `.sangam-src-<sha>` and `.m2-sangam-<sha>`, fresh tenant per sha (SL-54). **If the sha contains SL-53, every lane that boots sangam-engine runs `-Duser.timezone=UTC` and sets `sangam.engine.timer-zone` first** (kit runner argLine; `TZ=UTC` does not work on this host); IFMS decides whether that comes before P-463 (K-43). Every run json records the Sangam sha, build JDK, run JDK, JVM zone, DB and tenant id. Update every pin: `SangamWorkflowEngine.java:17` SANGAM_COMMIT; the comment at `core/workflow/pom.xml:38`; `trials/workflow-sangam/pom.xml:17,52`; `trials/sangam-money/pom.xml` (standalone; 0196b57 per its comment); the evidence README (still says 0196b57); `t2_run_15e.py:8,16`; `docs/handover/trial2-sangam.md:16` (SG-34 and SG-33 = 2aaf44f).

**TRIAL0-JACKSON restatement:** the deepmerge finding is OneIFMS's `ui/oneifms-ui/package-lock.json` (P-446); `OSV_MANIFESTS_SCANNED` is unset locally (only CI sets it); deepmerge is already excepted until 2026-11-27; inferred, not run: the regex may break on paths with spaces; fold P-437 into P-439 and re-run on main.

**E-54 v0.2 contents:** per SG the Sangam commit and the evidence; add SG-31, SG-32, SG-34 to the table; fix the header, §1, §3 and the row count (31 vs 32); update the DELIVERABLES_INDEX row and close P-441; add SL cross-references (E-54 l.78); record SL-61, SL-62, SL-65 and SL-66 as children of SG-36, and the SL-63 recurrence.

### 3.3 SG retest list (IFMS decides; E-54 is the closure authority; Sangam only reports)
- **Windows:** R1 = the P1 sha; R2 = the P1b sha (after steps 2–3); R3 = the P1c sha (after SL-35, SL-54, SL-55). **[P-444]** marks the agreed scope; everything else is a proposed amendment sent as `[REQUEST] TRIAL2-RETEST-SCOPE`.
| SG | Sangam fix | Window | What T2 runs | Blocked by |
|---|---|---|---|---|
| SG-35 [P-444] | 0d04fea | R1 (plain-API H2 reproducer); after P-452 (X-13 on H2, Oracle, SS2025) | One `sangamRouting` incident + one ROUTING_INCIDENT row; X-13 shows the audited hold | P-452 (both adapter copies, K-37) |
| SG-08 [P-444 for 08a] | 15e4135; 0d04fea (audit) | R1 | 08a regression + DB lanes; 08b X-21 with plant LATEST_BINDING; 08c AMBIGUOUS_ROUTE plants with `adapterGuards=false`; fix the hard-coded 0196b57 label | 08c waits for the P-452 overlap answer |
| SG-34 [P-444, P-449] | 2aaf44f (WIP, not verified) | R1 | Sangam asks for the E-54 c809ef8 plan as the SD-428 second check: S6–S8 plants with throwaway keys, a local bare remote and a sandboxed HOME (never the real `.ssh`), residual (a), cross-proof vs 35996f9; the selftest (31/31) is supporting evidence only (K-8) | Q25 |
| SG-32 | 35996f9, 3cf7841, 9c831d0 | with SG-34 | Close together with SG-34 | SG-34 |
| SG-31 | ruleset 26789812 (SL-19) | R1 (desk) | Two API reads, or a plant on a temporary ref | Q15 |
| SG-29 [P-444] | 15e4135 | R1 (Oracle 23.26.3) | Duplicate-key plant; same-UTC-instant plant | — |
| SG-28 [P-444] | 15e4135 | R1 | dependency:tree with feel-engine excluded; a run without the uuid workaround | — |
| SG-26 [P-444] | 15e4135 | R1 | dependency:tree + guard plant; closure per JL-S-04 | JL-S-04 |
| SG-27 [P-444, as SD-427] | exception | R1 | S-F-1..S-F-6 | — |
| SG-06 / SG-07 | 2aaf44f; regressed at 16e0e11, fixed by SL-63 (P1) | R1 | Sangam reports the regression; E-54 updated; second method for SG-07: JDK 25 and 27 builds of the P1 sha | SL-63 |
| SG-36 (+ SL-61, SL-62, SL-65, SL-66) | P1 | R1 | Default-header path incl. letter case; reserved request headers refused; secret rule (trailing newline refused, value never echoed) | `[CONTRACT] SG-36-HEADERS` |
| SG-19, SG-30, SG-33, SG-21..SG-25 regressions | 15e4135 (SG-33 guard: 2aaf44f) | R1 (proposed) | Checks below this table | SD-437 (SG-33) |
| SG-09, SG-10, SG-11 (hot root; DST part with SL-53) | 15e4135; SL-53 (P1) | R1 (proposed) | Pin tests fail on a planted drift; S-C-12 or "CMMN out of scope"; S-W-21 hot root at 200 parallel; DST fold with a UTC JVM and timer zones Berlin and Asia/Kolkata | SL-53 in P1; UTC-JVM prerequisite |
| SG-03 | flowable-ports tests | R1 (desk) | Recount: 265 tests at 1105d9c (266th in P1); exclude `handoff/worktree-snapshots/**` | DEC-S3DOCSTORE, DEC-CONTENT-TENANT, DEC-TASK-EVENTS |
| SG-12, SG-13, SG-17 | 2aaf44f / docs | R1 (desk) | Policy + alert count; deploy/README §1.5; SANGAM.md | Q17 |
| SG-01, SG-20 (Sangam parts) | PACKS4 | R2 | Boundary tests in paise (IFMS edges) + PIT ≥70%; SG-20-SQL | PACKS4 |
| SG-15 | 16e0e11 + SL-67 | R2/R3 | E-53 §4 perf on Oracle/SS | T-SCALE |
| SG-37, SG-38, SG-39 | P1b/P1c | R2/R3 | Re-check | — |
| SG-04, SG-05, SG-13 (live) | MATRIX | later | Certify from CI evidence; SS2025 three times | MATRIX |
| SG-02, SG-16, SG-18 | — | — | RC tag pin; the standing rule; the owner's decision | Q5, Q19 |
| SG-21 | closed by T2 at 15e4135 | — | REG sync only; E-54 v0.2 notes the SL-63 recurrence | — |
| P45, SL-45, SL-55, SL-54, SL-73 (consumer view) | P1 / P1b / P1c | R1–R3 | Only if IFMS uses those paths | — |

**Regression checks (proposed for R1):** SG-19: a no-DDL lane, or the source plus a plant. SG-30: a plain-H2 URL is refused. SG-33: H2 and Oracle runs with 2.22.4 and real FEEL; a 2.21.7 plant; drop the 2.22.3 trial pins; dependency:tree for module-scala. SG-21..25: M-2, M-3, M-5, M-7 + a clean-clone smoke build.

## 4. Sequencing
### 4.1 Waves (a priority queue with slot limits, not simultaneous starts)
**Wave 0 — now (light work plus already-claimed heavy runs).** Sangam integrator, in order:
1. REG-REPAIR-SL65.
2. ZERO-LOSS-CHECKPOINT.
3. Write this plan to D: and add the JL §3/§7, LED and IDX pointers.
4. LIGHT-BUILD-CAP; send the APPROVE-401 agent the 2a/2b split.
5. REG-ALLOC, JL-RESTATE, JL-S4-OWNER-WORDS.
6. Light fixes: SL-56 (+ sangam-api.yaml, native metadata), ROUTING-INCIDENT-CT.
7. CONTRACT-NOTICES.
8. MEMORY-POINTER, LED-CORRECTIONS (+ §6j), CLOCK-CORRECTIONS.
9. The owner Q-batch in SANGAM (§6.2; Q4 answered).

Sangam sends this owner turn (2 of 8 used), in this order:
1. `[CONTRACT] JOINT-PLAN`.
2. `[INCIDENT] SL-45` (confirmed).
3. `[CONTRACT] SG-36-HEADERS`.
4. `[REPORT] HEAVY-LOCK-V2 ACK` or changes, after `wtkb390st`.
5. `[REQUEST] TRIAL2-RETEST-SCOPE` (scope, SG-34 depth, SG-36 children, the UTC-JVM prerequisite).
6. `[CONTRACT] COORD-V1.2`, only after IFMS ACKs §2 (may slip to the next owner turn).
The SANGAM-MAIN-COMPILE restatement was already sent (23:38:45Z) and is dropped.

Agents: SL-45 (apply, then SL-70, SL-73, SL-72), SL-53, SL-67 and APPROVE-401 continue; SG-36 and UI-PARITY-2 are idle until RV questions. Each delivers patch + proof and applies only on the integrator's GO.

IFMS: P-452 commit + two verifications → X-13; claim the P-445 DB lanes in JL §6; P-463 after P-452; P-460 route; HEAVY-LOCK-V2 swap after the ACK; restate the IFMS rows (K-44); confirm the DOIT/PHED interim; the re-spawn plan for WP-02, WP-15 and Trial 2; the owner's SD-449 confirmation (Q35).

**Wave 1 — before the P1 cut**
- Dispatch queue (≤3 building): S-REVIEW (RV-a, RV-b) → S-PACKVER (SL-54) → `customwf-inbox` (SL-55 core) → S-SG35.
- Applies (integrator GO): SL-45 + SL-69 (done) → SL-67 → SL-53 (+ root argLine, ADR-D-27) → step 2a → SL-55 core → SL-54 bump → SL-70/SL-73/SL-72 if green by the cut.
- Heavy lock: IFMS P-445 DB lanes (claims) → the lock-v2 swap window → SL-53 E8 on the final patch → RV-1 PG slices → the P1 gate.
- IFMS: P-452 committed; P-445 test phase; the Trial 2 UTC-JVM argLine or P-463; Trial 2 re-spawned; ARTIFACT-ROUTE answered.

**Wave 2 — P1 pushed (owner signs) → BATCH P1 (first line flags the SL-53 UTC requirement) → R1**
- IFMS: R1 on H2, then Oracle 23.26.3 and SS2025 on JDK 25; the SG-36 re-check; X-13 when P-452 lands.
- Sangam starts: S-PACKS4 (after `[CONTRACT] PACKS4`), S-IFMSWF2 (SL-57 after step 2; IfmsPacksIT), S-MODCTL (SL-71), S-JIT (SL-76), S-COMP (SL-77), `customwf-sl59`, S-MATRIX (harness first), S-PERF (quiet window after Q7), S-SEC triage, S-DEPLOY (SG-38, SG-39), E-58-REVIEW.

**Wave 3 — P1b → BATCH → R2:** PACKS4; step 2b (after JL-S-06); the SL-54 guard; SL-57 data; SL-71, SL-76, SL-77, SL-59; SL-70/72/73 if not in P1; IfmsPacksIT 6/6 re-gate; IX8 AC-01..06. IFMS R2: money and bands (SG-01, SG-20); SG-20-SQL again; SD-434 WF parts; TRIAL2-LANES begin (18 runs, interleaved).

**Wave 4 — P1c → BATCH → R3, then P2 and RC:** SL-35; SL-55 packs; the SG-37 move; SG-35-SANGAM if not landed. IFMS R3: SD-434 WF-1..9 end to end on fresh tenants; E-54 v0.2 (P-451); E-53 coverage, soak, perf and chaos; SANGAM-ADOPTION after P-432. Sangam: P2 snapshot removal (SL-75, owner-approved dispatch afterwards); SG-04/05 certification; RC tag (Q5); SL-53a per Q34.

### 4.2 Pushes and the gate
**P1 core:** SL-63, SL-64; SL-56 (own commit) with the regenerated native metadata; IFMS-WF step 1 (+ `CrossPackCallBindingTest`, the SangamModelValidator EXT finding) with the SL-54 version bump; SG-36 with SL-61, SL-62, SL-65, SL-66; SL-68; SL-74; UI-PARITY-2 + SL-60; SL-45 + SL-69; ROUTING-INCIDENT-CT; REG (JL-S-07), HANDOFF-DOC-UPDATE; the CodeQL/dependabot exclusion of `handoff/**` (SL-75 P1 part).

**P1 only if green and RV-checked by the cut:** SL-67, SL-53 + ADR-D-27, SL-70, SL-73, SL-72, step 2a, SL-55 core, the DEC-TASK-EVENTS test.

**Not in P1:** SL-53a; step 2b (until JL-S-06); PACKS4 (P1b); SL-57 data (after step 2); SL-35 (P1c); SL-71, SL-76, SL-77; the snapshot removal (P2); UI-UPGRADE-1.

**Gate, for P1 and every later push:**
1. Commit the candidate locally first.
2. On a clean clone of that exact sha: compile on JDK 21, 25 and 27; the full H2 suite in one JVM with `-Duser.timezone=UTC` (the SL-53 guard needs a UTC JVM; this is also the SL-67 regression); RV-2 re-runs every SL in the push; a PG proof per item, or the remaining scope written down; every run records sha, JDK, JVM zone and DB.
3. Known-red tests only from the published list, each owned by an id (likely: SL-67 if not fixed; IfmsPacksIT 4/6, per test). The owner decides whether such a push may go (Q2).
4. No open DECISION-NEEDED and no unanswered CONTRACT for any included item.
5. A fresh owner signature; push that same sha by fast-forward through the signed gate.
6. If the push changes more than 300 files: owner-approved dispatch of the skipped workflows (Q16).
7. `[REPORT] BATCH <sha>` in the same turn (C-3, with CONTRACT-NOTICES).

P1b, P1c, P2 and the RC tag pass the same gate, each with its own signed push and BATCH. A push between P1 and P1b is optional for IFMS to re-pin.

### 4.3 IFMS retest windows (IFMS decides; fresh tenants or a fresh DB per Sangam sha until SL-54 closes, P-444)
- **R1** on the P1 sha: the P-444 scope, the SG-36 re-check, and whatever IFMS accepts from `[REQUEST] TRIAL2-RETEST-SCOPE`; X-13 only after P-452. **Prerequisite when P1 contains SL-53:** lanes that boot sangam-engine run `-Duser.timezone=UTC` with `sangam.engine.timer-zone` set (K-43).
  - Fallback if P1 slips: IFMS may build 1105d9c with JDK 21 (CI shows it compiles; jars are `--release 21` bytecode). It lacks SL-45, SG-36 and the other P1 fixes, so it covers the P-444 scope minus SG-06, SG-07, SG-15 and SG-36; IFMS's call in row TRIAL2-RETEST.
- **R2** on the P1b sha: money and bands (SG-01, SG-20, SG-20-SQL); the SD-434 parts IfmsPacksIT backs; SG-37, SG-38, SG-39.
- **R3** on the P1c sha: SD-434 WF-1..9 end to end; the SL-55 refusals. TRIAL2-LANES then continue.

### 4.4 Heavy-lock and compile-lock schedule (both projects; every duration is an unverified estimate)
**Rules**
- **Acquire and release:** acquire must exit 0, else abort; release in the detached runner's own `finally`; a watchdog on every run; after the v2 swap, `--pid` + heartbeat ≤600 s; no lock is ever held in an agent's own shell; no agent ends its turn holding a lock or with an untracked build running (I-59, I-60).
- **Capacity:** one ticket per workstream; one heavy job machine-wide.
- **Slot lengths:** Sangam IT slices ≤45 min; the matrix runs one DB per ticket. Indivisible long runs get **named long slots** claimed in JL §6 with a planned start and end (IFMS ACCEPTANCE lanes, the 8 h soak, T-SCALE, the full H2 suite, `ifms-integ` builds). **JL §6 needs "planned start" and "planned end" columns** (proposed COORD v1.2; IFMS ACKs the table change).
- **Alternation by discipline** (the tool is FIFO only): when both sides have pending claims, a side enqueues its next ticket only after the other side's claimed ticket has been served, logged in JL §6. **Sangam work never delays P-445**, IFMS's critical path (E17); its DB lanes come next.
- **Expiry:** a claim not queued within 2 h of its planned start expires and is re-claimed.
- **Light builds** (compile/unit) run outside the heavy lock but are capped: Sangam ≤3 concurrent Maven builds machine-wide; ≤2 while an IFMS ACCEPTANCE lane, the soak or a boot-timed heavy run (T35) runs; 0 during T-SCALE and the soak's quiet window. Whether Sangam's builds also take `compile_lock.py` is IFMS's decision as tool owner (Q30).
- **Quiet windows:** `[REQUEST] MACHINE-QUIET <window>` plus a JL §6 "quiet" row that every agent checks before compiling; end with `[REPORT] MACHINE-FREE`.
- **Hands off:** never touch another owner's ticket or process; a stale lock is an `[INCIDENT]` to IFMS.
- **Tool changes:** the tool path stays unchanged until the P-458 swap with both locks free; backups exist in scratch_A.

**Inventory and proposed order** (lock files at 00:55Z; JL §6 at 00:45:22Z)
| # | Owner name | Side | Run | Est. (min) | State |
|---|---|---|---|---|---|
| 1 | ifms-p452 | I | P-452 finish (compile lock): spotless, compile/test-compile, H2/kit tests, planted run (`-am -Pdemo`, JDK 25) | IFMS | holding the compile lock since 00:28:48Z |
| 2 | customwf-auth401 | S | T35RestoreIT runs 3–4 (run 4 with the allow-unsigned-webhooks switch, K-23) | ~10 each | served 00:37:28Z and 00:45:05Z; heavy lock free at 00:55Z |
| 3 | ifms-integ | I | P-445 test phase: Oracle and SQL Server DB lanes | IFMS claims | **to claim in JL §6; served before any new Sangam heavy ticket** |
| 4 | — | both | Lock-v2 swap window (both locks free, MACHINE-QUIET) | 5 | after Sangam's ACK |
| 5 | customwf-dst | S | SL-53 E8 Berlin + Kolkata on the final (b1) patch | 2×10–20 | after #3, per alternation |
| 6 | customwf-iso | S | SL-70 / SL-73 / SL-72 PG proofs (one per ticket) | ≤45 each | Wave 1 |
| 7 | customwf-review-a / -b | S | RV-1 PG slices (SL-43 TenantQuotaIT, FwtWorkerCompatIT, BulkIngestPostgresIT, SL-51, E11, T08) | ≤45 each | to claim |
| 8 | customwf-integ | S | P1 candidate gate: full H2 suite in one JVM (`-Duser.timezone=UTC`) + JDK 21/25/27 compile on a clean clone (light but long; quiet hours) | 60–90 | to claim |
| 9 | sangam | I | R1: H2, then Oracle 23.26.3 and SS2025 on JDK 25 | 60–120 | after BATCH P1 and the UTC-JVM prerequisite |
| 10 | customwf-sg35 / -sl59 / -packver / -inbox | S | SG-35 PG proof; SL-59 repro; SL-54 PG regression; SL-55 poison test | ≤45 each | Wave 2 |
| 11 | customwf-matrix | S | SS2022 → Oracle 23 → MySQL 8.4 → SS2025, one DB per ticket, interleaved with #12 | 45 each | Wave 2 |
| 12 | sangam | I | TRIAL2-LANES: 18 runs, SS2025 ACCEPTANCE first | 40–100 each | named long slots from R2 |
| 13 | customwf-perf | S | T-SCALE (quiet window, committed sha) | ≥30 | after P1 + Q7 |
| 14 | customwf-packs4 / -ifmswf / -ix8 | S | IfmsPacksIT re-gate; packs on PG; IX8 AC-01..06 | ≤45 each | Wave 3 |
| 15 | sangam | I | R2, then R3 | 60–120 each | after P1b / P1c |
| 16 | sangam | I | 8 h soak (overnight quiet window; Sangam light builds paused) | 480 | Wave 4 |
| 17 | various | both | SD-417 X-22, SG-20-SQL, Trial 0 X-6, TRIAL0-JACKSON re-run on main, WP-04 X-13 lane, SL-58 repro, GP-21 alone, SG-13 Oracle customer path, SL-44 other DBs, SL-30, SL-04 ITs, chaos, perf vs Operaton | per claim | as claimed |
- Stale JL §6 rows to close or refresh: `customwf-ifmswf` ("queued by agent"), `customwf-uip2` ("may queue"), `customwf-dst` ("may queue", E8 Berlin only) — Sangam; `ifms-integ` ("Run 6 RUNNING", "26051321 not pushed") and `sangam` ("TRIAL2-RETEST on 1105d9c") — IFMS.

**Capacity (estimate, unverified):** IFMS ≈ 18 lane runs × ~70 min + 8 h soak + 3 retests × ~1.5 h + 4–8 h P-445 test phase + ~4 h perf/chaos ≈ 40 h; Sangam ≈ 30 slices × ~40 min ≈ 20 h. ~60 h of serialized lock time: at least 2.5 days of continuous use. **The lock, not coding, is the bottleneck;** the order protects P-445 first, then the P1 gate, then R1.

## 5. Interface contracts
- **C-1 ROUTING-INCIDENT v1** (sent in `[INCIDENT] SG-35-ADAPTER` 03:01:30Z; IFMS ACK reached Sangam 03:05:53Z; stable from 1105d9c):
  1. **Incident:** on AMBIGUOUS_ROUTE or NO_MATCHING_ROUTE, an Operaton incident with `incidentType = "sangamRouting"` (`StrictExclusiveGatewayBehavior.INCIDENT_TYPE`), `activityId` = the gateway id, `incidentMessage` = `<CODE>: <text>` where CODE is exactly `NO_MATCHING_ROUTE` or `AMBIGUOUS_ROUTE`. The command commits, the execution stays at the gateway, nothing after it runs.
  2. **Audit:** in the same command, class-B audit event `ROUTING_INCIDENT` (`RoutingIncidentHandler.AUDIT_EVENT`) with keys `code, incidentId, processInstanceId, processDefinitionId, gateway, message` (verified at 1105d9c).
  3. **Detection** (embedded engine): after the completion call, `runtimeService.createIncidentQuery().processInstanceId(pid).incidentType("sangamRouting").list()`; the code is the message prefix before the first `:`.
  4. **Recovery:** an authorized data correction plus a signal (`OperatonEngineAdapter.correctRoute`) re-runs the policy; exactly one flow is taken, or `RoutingException` is thrown and the correction rolls back.
  5. **Errors:** a throwing condition propagates as an error, never counts as false, and the default flow is not taken.
  - **IFMS mapping:** NO_MATCHING_ROUTE → GATEWAY_NO_MATCH; AMBIGUOUS_ROUTE → the kit's overlap code. Never map every `sangamRouting` incident to no-match. The ENGINE-02004 path stays for Operaton. Applies to every adapter copy that runs Sangam, including `trials/workflow-sangam` (K-37).
  - **Changes:** additive only, or `[CONTRACT]` with 365-day deprecation. **Tests:** Sangam pins v1 with `RoutingIncidentContractTest` (P1); IFMS mirrors the strings in one constants class with its own contract test (P-452 adds `SangamRoutingIncidentContractTest` and a drift gate). A public constants type is optional (v1.1, later).
- **C-2 Pin and artifacts.** IFMS builds only committed, pushed Sangam shas, in private `.sangam-src-<sha>` / `.m2-sangam-<sha>`; the version is 0.1.0-SNAPSHOT, so the sha is the only pin until an RC tag (Q5). **Live conflict (K-27):** oneifms main's `-Pdemo` already builds both Sangam modules from `.m2-sangam-15e4135`. Resolution path: P-460 + ARTIFACT-ROUTE (reproducible pinned build in the lane, or the modules out of the default/CI reactor until SD-419). Adapter non-production until SANGAM-ADOPTION.
- **C-3 BATCH and RETEST.** `[REPORT] BATCH <sha>`: first line = any consumer-side runtime requirement (P1: UTC JVM for SL-53); then one line per commit since the previous BATCH: `<sha7> | SG/SL ids | single-verified/CLOSED | tests | DB proven | JDKs compiled | CI run ids`; plus known-red per test with owners; what is not in the push; build instructions (JDK, `-am`, target, jar checksums); CONTRACT-NOTICES. The first BATCH also lists what 2aaf44f contains (WIP, not verified): the SG-33 guard, the SG-34 gate, SG-06/07/13/14/17, SG-12 SECURITY.md, SL-18, SL-50, the SL-14 design, the GA-55 template and AT-1 bill type, the UI toolchain upgrade, the VULN suppressions. `[REPORT] RETEST <sha>`: per SG CLOSED/REOPENED/BLOCKED/NOT_RUN, evidence commit, E-54 row.
- **C-4 Jackson (JL-S-02):** the guard supports 2.22.x with databind and module-scala on one minor line; sangam-bom pins 2.22.3; the OneIFMS parent is on 2.22.4. The runtime proof on the Sangam lane is owed (SG-33), incl. dependency:tree for module-scala. A bump to 2.22.4 is optional, needs module-scala 2.22.4 verified and a `[CONTRACT]`, and re-opens the SD-427 closure check.
- **C-5 BOM consumption (JL-S-04):** Boot-parent consumers set `liquibase.version=4.33.0` and **`jackson-2-bom.version`** (Boot 4.1.2 imports `com.fasterxml.jackson:jackson-bom:${jackson-2-bom.version}`, spring-boot-dependencies-4.1.2.pom:3346–3347; the oneifms parent sets `jackson-2-bom.version=2.22.4`). JL-S-04 says `jackson-bom.version`; Sangam corrects its row before IFMS ACKs. Non-Boot-parent consumers import `sangam-bom` first. Guards fail fast and name the property. Proposed SG-26 closure criterion: fail-fast guard plus documented override; E-54 decides.
- **C-6 Money:** BIGINT integer minor units (paise) plus an ISO-4217 currency (SD-398). The pack↔connector units change under PACKS4, announced by `[CONTRACT] PACKS4`. IFMS edges 9,999,900 / 10,000,000 / 10,000,100 paise are added beside Sangam's 9,999,999 / 10,000,000 / 10,000,001. The cde-events decimal-money CR intersects this contract.
- **C-7 FEEL/DMN (ADR-D-26):** typeRef `number`, double refused; exact numbers; errors are errors; null-input semantics; date-time `=` gives a lint warning only (SL-07).
- **C-8 Strict XOR (ADR-D-25):** several true conditions → AMBIGUOUS_ROUTE; no match → an audited, recoverable hold.
- **C-9 Engine JVM zone (SL-53) — DECIDED** (owner 23:49:14Z and 23:50:23Z; IFMS SD-449):
  - **Sangam:** the core refuses at engine bootstrap in any non-UTC JVM (every embedding), naming `-Duser.timezone=UTC` and `sangam.engine.timer-zone`; no disable flag; an unknown zone is refused; the timer zone defaults to UTC when unset, never to the JVM default; the business zone comes only from `sangam.engine.timer-zone`/`BusinessClock`; regressions include Berlin and Asia/Kolkata timer cases and IST business dates 00:00–05:30; Sangam bans no-argument `now()`/`systemDefault()` in its product code (static rule + planted violation); Sangam flags the SL-53 sha in BATCH before IFMS moves its pin.
  - **IFMS (P-463, after P-452):** `sangam.engine.timer-zone=Asia/Kolkata` in EngineHost and topology; six `LocalDate.now()` lines; `-Duser.timezone=UTC` in surefire/failsafe argLines (JVM-zone carrier); planted UTC/IST round trip of the nine Timestamp sites and one timer on both DBs; a new time-rule SD; ArchUnit/Checkstyle ban with a planted violation.
  - **Adoption order:** embedding JVMs run `-Duser.timezone=UTC` and set the timer zone BEFORE the pin moves to the SL-53 sha. That includes the Trial 2 kit JVMs (`trials/workflow-sangam`) for R1 (K-43).
  - **SL-53a** (UTC persistence (a)) is scheduled; date = owner (Q34); `[CONTRACT]` before it lands. Hosting records the rule in E-37.
- **C-10 H2** must use MODE=PostgreSQL (DatabaseGuard); SUPPORT-MATRIX note in P1.
- **C-11 Worker/REST surface** = the CONTRACT-NOTICES list (§3.1 B).
- **C-12 Pack data ownership (JL-S-05), Sangam's proposal:** authority (who may approve what, money ceilings, SoD seats) → IFMS PDP policy data (E-45 B-4) from sourced research such as R-99; financial state and money logic → IFMS domain services (B-2); route topology → IFMS WP-15 route packs (engine-neutral, validated by WP-04); Sangam owns the generic pack format, generator and validator, and its `gov.ifms` packs are derived DATA (ADR-D-20): every IFMS value comes from IFMS's published sources through a generic importer/crosswalk with a drift check and cites its IFMS record; placeholders stay marked; when the two differ, IFMS's source wins and Sangam regenerates. SL-57 (R-99) is the first case; the sponsor decides only if the sides disagree (Q23).
- **C-13 Hosting (SL-18):** P-450 confirmed conditions 1, 2, 3, 5, 7; condition 4 → SG-36 (+ SL-61/62/65/66), 8 → SG-38, 6 → SG-39; also the Oracle driver via `loader.path` (SG-13) and C-9.
- **C-14 Locks:** §4.4 plus P-458 v2 with amendment 1: the record holds the holder pid, its start time and a lease; heartbeat re-arms a full lease; unknown liveness counts as alive; auto-release only for a dead holder with an expired lease, only by the oldest live ticket, audited; FIFO unchanged. No agent ends its turn holding a lock or with an untracked build running.
- **C-15 Retest inputs/outputs:** every run json records the Sangam sha, build JDK, run JDK, JVM zone, DB and tenant id (fresh per sha).
- **C-16 Pack versions (SL-54):** once SL-54 lands, the same id and version with a different checksum is refused, and rollback/redeploy reuse existing definitions; `[CONTRACT]` before P1b.
- **C-17 Bill-type data (JL-S-06):** IFMS owns the bill-type taxonomy (WP-01); Sangam consumes it as data through a crosswalk file, agreed before step 2b.
- **C-18 Connector headers and secrets (SG-36 children; `[CONTRACT] SG-36-HEADERS` before P1):** headers are case-insensitive and sent once; the connector's own headers win; request headers that try to set reserved names (Authorization, Idempotency-Key, `X-Sangam-*`, secret-filled default headers, case duplicates) are refused fail-closed (SL-62). Default-header `secret://` values resolve at call time; a port failure or a blank/unknown value is AuthUnavailable (no effect; lookup Unavailable) (SL-61). Request-building failures (invalid header name/value, invalid method) are non-retryable NoEffect INVALID_REQUEST with value-free messages (SL-65). Header-bound secrets (bearer, basic, OAuth2 client secret, default-header refs) refuse leading/trailing whitespace and control characters; key-store/trust-store passwords refuse control characters only; a file-mounted secret ending in a newline is refused at build time, and after rotation at call time it is "credential unavailable", nothing sent (SL-65). SOAP: same "connector headers win" rule, configured headers checked at build, soapAction characters validated (SL-66).
- **C-19 `/engine-rest` tenant scope (SL-45 now; SL-73 pending):** deployments outside the caller's tenant and cross-tenant batches are refused 403 `TENANT_ISOLATION_VIOLATION`; batches belong to the caller's tenant. SL-73 (pending the agent's list): tenant tokens reach only allowlisted tenant-scoped `/engine-rest` resources, everything else 403; standalone tasks created by a tenant token take the caller's tenant; platform tokens unchanged. An additive restriction; `[CONTRACT]` with the exact allowlist before it lands.

## 6. Decisions
### 6.1 Already taken (verbatim, real times from the transcripts; state)
**Sangam session, old login (transcript 898f1ff7), 2026-11-08:**
| Time (UTC) | Owner's words (verbatim or summary) | State |
|---|---|---|
| 00:48:21Z, 01:23:59Z | Root cause only: "कोई भी इशू है ना तो रूट से फिक्स करना है, उसका कुछ जुगाड़ लगाना है, नहीं लगाना है" | standing |
| 01:57:14Z | "you can talk with IFMS session and to end so  you both will be sync always" | standing |
| 02:45:13Z | Pasted strict-XOR policy: "Choose “Sangam में error दें” for ambiguous exclusive/XOR routing." → ADR-D-25 | standing |
| 04:55:46Z | Approved the 15e4135 push with conditions, incl. keeping the onboarding-hierarchy and COFOG/GA-55 decisions explicitly open | push consumed; conditions stand → Q8 |
| 05:32:05Z | IFMS, Sangam, DOIT and PHED belong together; VyaparTrust is separate and nothing is mixed | standing |
| 05:34:23Z | Complete every pending Sangam item without delay | standing |
| 05:38:16Z | Collaborate with the WorkFlow and IFMS main sessions; Sangam is plug-and-play for IFMS | standing |
| 06:43:03Z | "हाँ, लगाओ" — the ruleset (now 26789812) | done |
| 07:01:29Z | "हाँ, signed करो" — signed approvals | done |
| 08:36:39Z | "हाँ, रखो" — the SL-50 cap | done |
| 11:25:29Z | "Do all thiings at your level end to end" | standing |
| 12:48:55Z, 12:50:32Z | Coordinate by standard development rules → COORD v1.1, SD-441 | standing |
| 16:52:38Z, 17:00:21Z | Zero information loss across login switches; basis of §9 | standing |
| 17:04:36Z | "अब तक जितना भी कोड हुआ है … जितनी पीआर ओपन हुई है उनको मर्ज करो और पुश करो गिटहब पे।" | **consumed** |
| 17:05:58Z | "Branch push + PR (Recommended)" | **consumed** |
| 17:30:45Z | "signed" (branch push) | **consumed** |
| 17:37:27Z | "Do All Code commit and push and all PR merged to main    first commit and push then merged  to main without missing single charactor of code any how any way ." | **consumed** |
| 17:41:47Z | "Go Ahead I Approved" | **consumed** |
- The five consumed approvals were used up by the 2026-11-09 push and the PR merges; they are not standing approvals for any later push.

**Sangam session, this login (transcript 6e98c2a7):**
| Time (UTC) | Owner's words | State |
|---|---|---|
| 11-09 01:29:44Z | Resume exactly where the limit stopped; lose no progress | standing |
| 01:39:48Z | "b: engine JVM in UTC" (SL-53) | refined by 23:49:14Z |
| 01:40:50Z | "signed" — 16e0e11 to main | consumed |
| 01:51:02Z | "Separate upgrade later" (PR #3 / UI-UPGRADE-1) | standing (Q20) |
| 01:51:59Z | "मैंने आईएमएस सिस्टम्स को दोबारा से वो कर दिया है इनेबल. … सिर्फ दो ही सेशन है जो ऑन है अभी इस लॉगिन के साथ में. तुम एक दूसरे से पूरा कोऑर्डिनेट करो, कुछ ही तक ही मिस ना हो, पूरा प्रॉपर प्लान हो. और ठीक है, स्टार्ट नाउ." | standing |
| 01:54:46Z (queued) | "First, identify that laser that you are sharing with each other. So you're planning with each other." | done: JL |
| 18:21:39Z | "Try again recheck all do not miss anything end to end that was stopped due to infra" | standing |
| 23:49:14Z | "IFMS की तरफ़ की शर्तें मंज़ूर हैं।" — (b1) strict UTC now for every engine JVM incl. embedding hosts; (a) as scheduled target SL-53a (date = owner); IFMS P-463 conditions and the Sangam items | **DECIDED** (IFMS SD-449); answers Q4 |
| 23:50:23Z | The same sentence, followed by the P-463 list item by item (timer-zone Asia/Kolkata in EngineHost and topology; six `LocalDate.now()` lines; `-Duser.timezone=UTC` in surefire/failsafe; UTC and IST round trip of nine Timestamp sites and one timer; new time-rule SD; ArchUnit/Checkstyle ban with a planted violation) | DECIDED |

**IFMS sponsor decisions:** SD-445 (SG-35 option (a), MEDIUM; SD-417/SG-08a closed); SD-446 (one IFMS session holds every former IFMS role; its SD-444 re-confirmation applies to oneifms only); SD-447 (the joint ledger); SD-448 ("Keep local only"; no remote for the IFMS docs repo; single-disk risk accepted); **SD-449** (SL-53 owner decision as relayed; IFMS asks for confirmation in its own session); SD-427 (FEEL exception with conditions); SD-428 (double verification); SD-441 (E-57); SD-444 (oneifms only); SD-419/SD-422 (adoption after the gates); SD-433 (superseded by SD-446); SD-435/SD-436 (Sangam owns ruleset 26789812).

**Integrator decisions (not the owner's):**
- 11-09 02:33:39Z option (A) for IFMS-WF: apply step 1 now (departs from COORD §2 → Q3); 18:2xZ detached-runner rule; 18:59:36Z GO SG-36.
- 20:01:36Z GO SL-61/62; allocated SL-65 (then MEDIUM) and SL-66 (LOW); scope extended to `HttpTransport`, `SoapConnector`, `LiveHttpConnectors`; SL-65/66 to wait for a separate GO.
- 23:17:58–23:18:10Z resumes (APPROVE-401 told "`gov_ifms.py` is yours in step 2"; UI-PARITY-2 told to re-run the Maven gates if their logs were incomplete, then apply as planned).
- 23:24:44–23:34:21Z SL-68 edits; 23:38:36Z SL-67 dispatch; 23:39:29Z HEAVY-LOCK-V2 review re-run.
- 23:49:25Z "WIRE b1".
- 23:53:40Z GO SL-65/SL-66/SL-62-prefix; SL-65 raised to HIGH security; secret rule split (header-bound vs non-header); retraction of the "mid-apply" reading.
- 00:28:43Z GO SL-45; allocated SL-69 (MDC), SL-70, SL-71, SL-72, SL-73 with their rules.
- 00:35:03Z scripted REG edit (caused K-33); 00:46:25Z reuseForks note to SL-67; 00:47:12Z REG rows SL-74, SL-75.

### 6.2 Needed — owner questions
Each stays OPEN until the owner's words are in JL §4. Q1–Q28 and Q34 are asked in the SANGAM session; Q29–Q33 and Q35 go to the IFMS session; Q23 only if the sides disagree. **Q4 is answered** (23:49:14Z/23:50:23Z) and not asked again. Full texts (identical to the structured output):
- **Q1** [SANGAM] P1 push: when the integrator presents a fresh push-approval text for the P1 candidate sha (contents and gate in §4.2), sign it in your own session if you agree. Nothing is pushed before your signature. Recommendation: sign once the gate is met. Blocks IFMS R1 and the CI-green report.
- **Q2** [SANGAM] Known-red policy: may a push go to main if it fixes red jobs but leaves documented known-red tests, each owned by an SL id, with no new failure versus the previous main (example: SL-67's one-JVM cascade if not fixed by the cut)? (a) yes, with the known-red list in the approval text; (b) only fully green pushes. Recommendation: (a) for P1 only, because IFMS's retest is blocked until main compiles on JDK 25/27; (b) from P1b on.
- **Q3** [SANGAM] IFMS-WF step 1 was applied with IfmsPacksIT at 2/6 (was 0/6; E46 8/9 → 9/9; no new failure), although COORDINATION §2 says apply only when green. Accept a time-boxed exception that expires at the P1b re-gate (6/6, or each remaining failure owned by an SL id), or require a revert? Recommendation: accept.
- **Q5** [SANGAM] SG-02 release candidate and artifact route (also IFMS P-460): tag v0.1.0-rc.1 on the first main commit whose build, security, sdks, ui and licence-check runs are green; OneIFMS builds that tag (or the pinned sha) reproducibly into a private Maven repo in its lane; no package-registry publication before the adoption decision (SD-419/SD-422). (a) as proposed; (b) also publish to GitHub Packages (a settings change); (c) no tag until every register row closes. Recommendation: (a). This decides how oneifms -Pdemo, its CI and the WP-04 adapter get Sangam jars.
- **Q6** [SANGAM] cwf-docs-wt (JL-S-01, IFMS P-448) is a registered worktree at 15e4135 with 11 uncommitted files verified as superseded by main. May the integrator zip them into D:\GITHUB  Project\sangam-handoff\2026-11-09\ and run git worktree remove? Recommendation: yes.
- **Q7** [SANGAM] DEC-SL43-LITERAL: the SL-43 fix (460c95b) uses a separate leaf pool, sangam-quota. It is deadlock-free, but a thread can hold one main-pool and one quota connection, so "never a second connection" is not met literally. Accept the leaf pool, or require quota counting outside the engine transaction? Recommendation: accept; the SD-428 reviewer re-checks it. T-SCALE waits for this answer.
- **Q8** [SANGAM] SL-14 onboarding hierarchy: on 2026-11-08 04:55Z you asked to keep this decision open, but orgLevels with a 3-level profile default is on main via 2aaf44f. Confirm that design or name the change? Recommendation: confirm it as tenant-configurable data, with the default documented as a placeholder.
- **Q9** [SANGAM] SL-31: six delegated-power tables are placeholders (budget formulation, supplementary, surrender, scheme approval CAA 100 cr, GPF HOO, advance HOO). Supply authoritative sources, or keep them as tenant-configured placeholders with readiness reporting until IFMS supplies sourced values (as with R-99)? Recommendation: keep the placeholders and source the values through IFMS (C-12).
- **Q10** [SANGAM] (a) DEC-S3DOCSTORE: may S3DocumentStore take an injected HTTP client so it can be unit-tested? Recommendation: yes. (b) DEC-CONTENT-TENANT: CONTENT_ITEM.tenant_id is NOT NULL but the code uses a '_shared' key; keep the key, or allow NULL as the shared marker? Recommendation: keep the key (a schema change needs a [CONTRACT] to IFMS and all five dialects).
- **Q11** [SANGAM, optional] AC-03-EXPECTATION: the packs moved UNKNOWN_OUTCOME from the case state to the audit expectations. Keep that? Recommendation: keep it.
- **Q12** [SANGAM] Downloads (no JDK is needed; 25 and 27 are installed): approve, defer or refuse (1) .NET 8 SDK and PHP for the AC-08 cross-language worker run; (2) kind and GraalVM for the SG-14 Helm and native proofs. Exact file names, sources and sizes will be stated before any download. Recommendation: defer (2) until SG-38 lands; approve (1) when SDK-STORED-OUTCOME is done.
- **Q13** [SANGAM] Who reviews and signs the OWASP dependency-check suppressions (expiry 2027-05-31), incl. the kotlin-stdlib CVE-2026-53914 and protobuf-java CVE-2026-0994 false-positive suppressions, which arrived through unverified 2aaf44f with no reviewer named?
- **Q14** [SANGAM] Ruleset 26789812 (GH-REQUIRED-CHECKS / P-435): once CI is green, add required status checks (build JDK 25, security, sdks, ui, licence-check)? Also require signed commits or a PR rule? Recommendation: required checks after a green run; the PR rule and signed commits are your call. Agents change no settings without your words.
- **Q15** [SANGAM] SG-31: accept two independent API reads of ruleset 26789812 as the SD-428 second check, or approve a temporary ruleset on a throwaway ref for a planted force-push and delete (a settings change)? Recommendation: two API reads; a planted test only if IFMS's E-54 requires it.
- **Q16** [SANGAM] Standing rule: after any push of more than 300 changed files (including the snapshot-removal push), approve a manual dispatch of build, security, sdks, ui, licence-check and db-matrix? Recommendation: yes.
- **Q17** [SANGAM] SG-12: enable GitHub private vulnerability reporting for sqamentor/sangam (SECURITY.md promises it) and secret-scanning non-provider patterns and validity checks (the repo is public)? Recommendation: yes to both.
- **Q18** [SANGAM] Please add the NVD_API_KEY repository secret yourself (CI-SECURITY-NVD-KEY-MISSING). Agents never handle secrets.
- **Q19** [SANGAM] SG-18 governance: name maintainers, a review rule and a continuity plan (Sangam can draft the plan for your review), or record that single-owner governance is accepted? Recommendation: let Sangam draft; you decide the content.
- **Q20** [SANGAM] UI-UPGRADE-1: vite 6.4.5, vitest 4.1.12, react-router-dom 7.18.5 and esbuild 0.25.12 reached main inside unverified 2aaf44f, skipping the separate review you asked for ("Separate upgrade later"); Dependabot closed PR #3 at 2026-11-09 01:44:37Z. Accept the upgrade after the SD-428 review, or require a separate review or a revert? Then re-scope UI-UPGRADE-1 from today's baseline. Recommendation: accept after the SD-428 review, with the UI suite green.
- **Q21** [SANGAM] May Sangam dismiss specific CodeQL/dependency-check alerts with written justifications (e.g. the 12 snapshot copies #40–#51 after the snapshot removal SL-75; findings that are test-only or build-time only)? Each batch is listed for your words first.
- **Q22** [SANGAM] CI-DBMATRIX-UNIT-GATING (CF-11): should the db-matrix legs skip the H2 unit tests (already run in build), or run with -fae and report both, so a unit failure never removes the database evidence? Recommendation: skip them in the DB legs.
- **Q23** [IFMS session; only if the sides disagree] JL-S-05 / SD-434-PACK-HOME, the single source of truth for IFMS workflow and policy data: (a) IFMS (PDP authority data, domain money logic, WP-15 route packs), with Sangam deriving its gov.ifms values through a generic importer (plan C-12); (b) Sangam's gov.ifms packs, exported to OneIFMS. Recommendation: (a).
- **Q24** [SANGAM] Delete the merged remote branches handoff/2026-11-08 (@7cac6c4) and dependabot/maven/spike/adapter-flowable/maven-821e6e383e (@1d2dee0)? The local base checkout is not affected. Recommendation: yes.
- **Q25** [SANGAM; only if the push-gate record does not show it] Does your owner-held <profile>/.ssh/sangam_allowed_signers file exist? Agents never read .ssh. The answer shows which trust root verified the 2026-11-09 01:40 UTC push, which the SG-34 retest needs.
- **Q26** [SANGAM] Old worktrees: after their unique content is copied to D:\GITHUB  Project\sangam-handoff\2026-11-09\ and listed in the resume ledger, may the integrator remove the 10 old-session worktrees detached at 9c831d0 plus D:\wt-fwt and D:\wt-scale (git worktree remove / prune)? Each is listed for you first. Recommendation: yes, after the inventory.
- **Q27** [SANGAM] Two containers that belong to neither Sangam nor IFMS (hxe, SAP HANA Express; one database container of a project outside this context) have been up about 8 hours outside the heavy lock; free RAM was 0.9 GB at 18:5x UTC and 4.9 GB at 00:33 UTC. Stop them during the IFMS ACCEPTANCE lanes, T-SCALE and the 8 h soak, or leave them running? Agents will not touch them.
- **Q28** [SANGAM, low priority] AC-10, the Hindi/English usability session (SANGAM.md IX.8): when, and who participates?
- **Q29** [IFMS session] DOIT and PHED: confirm the interim arrangement (the IFMS session holds coordination only: index rows and machine notices, no project work), or start or name sessions for them?
- **Q30** [IFMS session] Locks: should the compile lock also wait on the heavy lock, and should Sangam's light builds take the compile lock (REC-05 §6 open item)? Recommendation: decide with the HEAVY-LOCK-V2 swap; until then Sangam caps its own light builds (§4.4).
- **Q31** [IFMS session] CI-ONEIFMS-QUEUED: every sqamentor/oneifms Actions run has been queued since 2026-11-08 18:20 UTC. Please check Actions minutes, billing and runners (owner-only settings). Meanwhile, may local lanes count as the CI half of SD-428?
- **Q32** [IFMS session] SANGAM-ADOPTION / SD-422: confirm the timing (the IFMS session presents the Trial 2 result and Sangam's CI evidence with a recommendation after P-432) and that the Sangam adapter stays non-production on oneifms main until then — noting that main's -Pdemo profile already builds adapters/workflow-sangam and trials/workflow-sangam from a private Sangam repo (P-460).
- **Q33** [IFMS session, FYI only] P-447 / SD-448: you decided to keep the IFMS docs repository local only ("Keep local only"). Sangam's evidence backup (sangam-handoff) is on the same D: disk; Sangam code goes to GitHub with each push. Raise this again only to revisit the single-disk risk.
- **Q34** [SANGAM] SL-53a: by when should engine timestamps move to zone-independent UTC storage (option (a): a data migration for all five database dialects, announced to IFMS by [CONTRACT] first)? Recommendation: plan it for after the P1c wave (SL-54/SL-55) and confirm the date then.
- **Q35** [IFMS session; raised by IFMS] SD-449: IFMS asks you to confirm in the IFMS session the SL-53 decision you gave in the Sangam session ("IFMS की तरफ़ की शर्तें मंज़ूर हैं।", 2026-11-09 23:49:14 and 23:50:23 UTC). The content does not change.

## 7. Risks
| # | Risk | Handling |
|---|---|---|
| RK-1 | Main is red on JDK 25/27; IFMS cannot build it | SL-63 fixed in IT; P1; JDK 21 fallback (§4.3) |
| RK-2 | False claims in records (965062f, X-13, the JDK 21 statement, the retracted partial apply) | Verify-at-source rule; restatements (§11 C) |
| RK-3 | Unverified WIP content on main (2aaf44f, f553b43, 3736f8f) | RV-1; SG-16; SL-75/P2; scan scoping |
| RK-4 | Lock starvation or slowdown (IFMS lanes slowed from 40 to 100 min) | §4.4 rules, claims, quiet windows, light-build cap |
| RK-5 | The lock tools sit in a dead session's temp folder; the v2 swap is risky | D: copies in scratch_A; swap only with both locks free; adversarial review; `*.v1.bak` |
| RK-6 | Lost or late messages (send limit; app queuing 18:33–19:05) | JL §5 fallback; send budget; `UNDELIVERED` log |
| RK-7 | Clock drift in logs | Stamps from a clock command; correction entries |
| RK-8 | Id collisions (plan vs REG; SG-33/34/35 in X.0g; PATCHES I-/P- vs IFMS) | Only the integrator allocates; REG first; qualified PATCHES ids; this plan's K-/RK- prefixes |
| RK-9 | SL-54 breaks environments that already hold packs | Fresh tenants; the version bump; the guard |
| RK-10 | SL-55 endless retries burn lock time | Known-red list; the core fix; IFMS tags them SL-55 |
| RK-11 | The SL-53 guard refuses non-UTC embedding JVMs, incl. Trial 2's | C-9 adoption order; BATCH flag; P-463; K-43 |
| RK-12 | oneifms -Pdemo already needs private Sangam jars (happened) | P-460, ARTIFACT-ROUTE, Q5 |
| RK-13 | oneifms CI never starts | Q31 |
| RK-14 | File-scope collisions | §3.0 |
| RK-15 | State that lives only in Temp is lost | BK checkpoints (§9) |
| RK-16 | Workflows skipped by the 300-file path-filter window | Q16 |
| RK-17 | Usage limits kill agents mid-run (three outages so far) | §9 |
| RK-18 | A message or ledger entry taken as an approval | §2 rules 1 and 9; signed gate; E-55 cards |
| RK-19 | Push-gate residuals (consumed-record reuse; `--no-verify`) | Q14; SG-34 plants |
| RK-20 | Public repo with CodeQL highs while Java analysis is stale | SL-63 first; S-SEC; Q21 |
| RK-21 | Retest REOPENs for known-red items | Known-red list in BATCH; REOPEN only on regression |
| RK-22 | The two oneifms adapter copies diverge (P-452 touches only the adapters copy) | IFMS applies to both or converges (K-37) |
| RK-23 | Machine memory and CPU (cap breached at 00:33Z; containers outside the lock) | Light-build cap; Q27 |
| RK-24 | Windows JVMs ignore `TZ=UTC`; local runs default to IST while CI runs UTC | `-Duser.timezone=UTC` in the brief and gates; SL-53 root argLine (K-36) |
| RK-25 | Local javac 21 accepts code that javac 22+ refuses | Compile gates on JDK 25 and 27 (HO §2) |
| RK-26 | Agent bases differ from IT | Red/green on a re-copy of IT before applying |
| RK-27 | Sangam delays IFMS's critical path P-445 (E17) | §4.4 priority |
| RK-28 | Pack-data ownership conflicts | C-12, JL-S-05, Q23 |
| RK-29 | SG-36 ships without its HIGH children | Ship SL-61/62/65/66 together (applied) or keep SG-36 open in BATCH |
| RK-30 | Evidence on a single disk (SD-448 accepted; BK also on D:) | Sangam code to GitHub with each push; Q33 |
| RK-31 | Scripted edits corrupt shared tables (SL-65 row) | §2 rule 11; integrity check; REG-REPAIR-SL65 |
| RK-32 | Builds left running when an agent ends its turn die or go unread (I-60) | §2 rule 14; tracked background tasks or detached runners |
| RK-33 | The SL-65 secret rule refuses file-mounted secrets with a trailing newline in consumer deployments | `[CONTRACT] SG-36-HEADERS` before P1 |
| RK-34 | Agents editing shared files outside their rows | COORD v1.2 rule (K-39) |
| RK-35 | Long T35-type boots fail under machine load and invite timeout bumps | Cap during boot-timed runs; never lengthen the wait (§2 rule 10) |

## 8. WP ↔ Sangam dependencies (E-56 v0.3 + REC-05 §4; every WP is a sub-agent of the IFMS session)
| WP | Builds | Sangam dependency | Needs from Sangam | Sangam needs from it | REC-05 §4 state | Next |
|---|---|---|---|---|---|---|
| 01 Contracts | contracts, registry, pack schemas | Indirect: WFR contract; GA-55 fix before WP-07 | — | Bill-type taxonomy (JL-S-06, C-17); MD-192; OQ-215..217 | f7c8545 compiles | MD-192 to Sangam |
| 02 Kernel | lib-kernel, money, persistence, parent poms, Jackson 2.22 pin | **Direct (build):** parent manages `jackson-2-bom 2.22.4`; `workflow-engine-boundary` bans org.sangam*; fix 3 put both Sangam modules in -Pdemo | C-5 text, guards | ACK of JL-S-04; P-459 answer (K-42) | 3fd91e2 done | Re-spawn (confirm) |
| 03 Platform | identity, mdm, BFF, gateway, hosting | Indirect: PDP decides workflow actions (E-45 B-4); CAL-SRC working days; Hosting confirms SL-18 | SG-36/38/39 fixes | PDP authority data (C-12) | 07ffae0 | — |
| 04 Workflow | WFR; Operaton, Flowable and **Sangam** adapters; kit; W-05 | **Direct:** `sangam-engine` in process; demo profile; GT-WF on Sangam | C-1, C-2, C-9, C-19 | P-452 commit (both copies); SG-08c overlap answer; P-463 after P-452 | 886d72e; P-452 on `cc/wp04-routing-incident` (uncommitted) | P-452 |
| 05 Budget | M01 BUD-1/2/3 | None direct | — | Accepts the SL-57 seat model (R-99 v0.2: role PAO) | beeb1d5 | — |
| 06 Sanction | M02 lifecycle | None direct | — | — | b957708 | — |
| 07 Bill | BILL-1 | None direct; GA-55 coupling | — | Bill-type rows (JL-S-06) | cbb608e | — |
| 08 CDE | treasury/payment | None direct; PRE-P-78 | — | P-78 for bills/payments packs | bb5076b | — |
| 09 Accounts | ledger-svc | None | — | — | b4d9a0a | — |
| 10 People | HR/ESS/pension/GPF | None direct; establishment workflows via WFR | — | SD-434 ESS semantics | 1663ab9 | — |
| 11 Receipts | RMS, DBT | None | — | — | 3f43593 | — |
| 12 Extended | works, cash, stamps, AMS | None | — | — | f52f00b | — |
| 13 Adapters | drivers, simulators (excludes `adapters/workflow-*`) | None | — | — | 045536e | — |
| 14 UI | Angular shell, workflow inbox via BFF/WFR | Indirect | — | — | 6bfbcfc | — |
| 15 Packs/E2E | pack DATA: J-00, RJ, PK-ROUTES, PK-CAL, PK-CONTENT | Indirect: route packs overlap Sangam `gov.ifms` (E9) | C-12 importer contract | Route data source of truth (JL-S-05) | 518dd12 | Re-spawn (confirm) |
| Trial 0 | `platform/jackson-2.22` | Jackson line (C-4) | — | Re-run on main; X-6 | 1fa14c4f | — |
| Trial 2 | `wf/trial2-sangam` (`trials/workflow-sangam`, profile demo; standalone `trials/sangam-money`) | **Direct:** retest of Sangam shas; JVMs must be UTC for the SL-53 sha | BATCH, C-3, C-15 | R1–R3, E-54 v0.2 | 3d6325e4 | Re-spawn at P1 |
- **Merge order:** E-56 planned five waves; in fact all 22 branches reached main through SD-444 (b4bbbb7 → 80dc4184; then 26051321 and docs-only 970a07cc). P-445: build ✓ (step 1 DONE) → SD-428 re-check → test phase on both DBs → promotion.
- **Kit and fixture paths** for Sangam's E46ConformanceIT and IfmsPacksIT (read-only): kit `5e7b3f4`, oneifms-trial0 `ab1b958`, oneifms-sangam `51b7825`.

## 9. Resilience to usage limits and crashes (binding for both sides)
### 9.1 Principles
- Agents and workflows die at limits without warning: 2026-11-08 22:00–23:14Z; 2026-11-09 04:10:09Z (Sangam notice; IFMS lost work at ~05:20 per I-58); **2026-11-09 20:14:49Z → recovery 23:16:25Z** (5 Sangam agents and 2 workflows stopped between 20:14:48Z and 20:40:51Z; detached runners released `customwf-iso` correctly).
- Therefore every workstream is resumable from its worktree, its log and its JL/LED row alone; locks must heal themselves (v2); records are updated before every long wait.
### 9.2 Standing rules
1. **Before any wait over ~10 min:** update the JL row or LED and copy patch and evidence to BK.
2. **Heavy runs** only through detached runners with a watchdog (after the swap: `--pid` + heartbeat).
3. **After any outage:** `heavy_lock.py status` and `compile_lock.py status`; release only your own holders after a liveness check (Maven/forked JVMs, containers), and log it; never touch the other side's holders; check `docker ps` for orphaned Testcontainers of your own runs.
4. **Agent notes:** at each milestone every agent writes the last step, the next step and the files touched to its own log in BK.
5. **Resuming agents:** resume by id (context survives). If gone, a new agent continues from the worktree, the log, the LED row and SUBAG, never from memory.
6. **Temp is not durable:** copy to BK at the end of each wave, before any expected limit and before announcing a result; JL evidence cells cite D: paths; workflow outputs (like this plan) are persisted by the parent.
7. **On a "usage limit reached" notice:** stop dispatching; checkpoint BK, LED and JL; write a "stopped at" line per agent.
8. **Messages:** JL §5 is the fallback channel; read it every turn; keep the send budget; resend `UNDELIVERED` after the owner types.
9. **Records:** Sangam keeps its JL rows, LED, REG, HO at each integration, plus memory; IFMS keeps REC-05, REC-06, REC-01, REC-02, REC-04, its registers, and runs `refresh_handover.ps1`; each side keeps its IDX row.
10. **No untracked background builds** when an agent ends its turn (§2 rule 14).
### 9.3 Sangam workstreams: resume table (00:55Z)
| WS | Agent | Worktree / base | File scope (main files) | Last evidence | Next step | Lock owner / JL §6 | If the agent is gone |
|---|---|---|---|---|---|---|---|
| SL-53 | a559ce3ccab6a4b3b | OLD `wt-dst` (copy re-synced from SCR `base-1105d9c` via SCR `resync.py`) | engine timer/clock, configurer guard, DstSafe calendar, CycleExpression, TimerGuard, settings javadoc; root argLine hunk | SCR `SL-53.patch` (00:12:27Z), `dst-touched-jdk21*.log`, `pg-e8-utcjvm-*.log` | report → GO → apply; E8 re-run on the final patch | customwf-dst / refresh claim | New agent from wt-dst + patch |
| SL-45 → SL-70, SL-73, SL-72 | aa33e846abeb4489f | OLD `wt-iso` (git worktree @9c831d0, re-synced from IT) | TenantScopedCreation, TenantIsolationException, SangamProcessEnginePlugin, ObservabilityConfig, ProblemHandler, CrossTenantIsolationIT, EngineRestTenantRefusalTest; next: token→role mapping, `/engine-rest` allowlist | SCR `sl45\` (runner logs red 19:29–19:48Z, green 19:50–20:05Z; report 00:28:03Z) | finish IT gates; then SL-70 | customwf-iso / DONE for SL-45; new claims per follow-up | New agent from SCR `sl45\fix-bundle` + diff |
| APPROVE-401 | adbd0a0881b88eb1b | SCR `wt-auth401pg` (PG copy synced to IT ~23:30Z) + OLD `wt-auth401` | Vars, ConfigConnector, DevTokenFilter, T35; `gov_ifms.py` (2b later) | SCR `a401-it2.log`, `a401-it3*.log`, keep folders | T35 result; 2a/2b split; report | customwf-auth401 / row current | New agent from wt-auth401pg + logs |
| SG-36 + SL-61/62/65/66 | a2f414ffffbb900fc | SCR `wt-sg36\sangam` | HttpTransport, HttpRestConnector, LiveHttpConnectors, SoapConnector + tests | report 00:34:47Z (612/612); SCR `sl6162-evidence`, `sl6566-evidence`, `sg36-evidence` | none until RV questions | none (unit) | New agent from IT (applied) + REG rows |
| UI-PARITY-2 + SL-60 | a7102621b8c6f9ff6 | SCR `uip2-work\mine2` (base `base-int2`); OLD `wt-uip2`, `wt-uip2-ctl` | OperatonTaskAdapter, sangam-rest, sangam-ui, SecurityDialect, `gen_openapi.py` output | report 00:46:02Z; OLD `wt-uip2\logs-2026-11-09\int-*.log` | none until RV questions | customwf-uip2 / close row | New agent from IT (applied) |
| SL-67 | acab37a2ef6d1dc85 | SCR `wt-d10`, `d10-staging` | sangam-server test harness and surefire hunks; OutboxCapacityMonitor + test | SCR `d10-logs\red-jdk21-fs.log`, `red-jdk25-fs.log` | red evidence → root fix → green in one JVM, two orders → report | none (light; claim if a run exceeds 30 min) | New agent from wt-d10 + d10-logs |
- **Author map (RV independence):** IFMS-WF af5674588bc930fc7, ad2295e73baf6fad3; SG-36/SL-61/62/65/66 a2f414ffffbb900fc; SL-45/SL-69 (and SL-70/72/73) aa33e846abeb4489f; SL-53 a559ce3ccab6a4b3b; UI-PARITY-2/SL-60 a7102621b8c6f9ff6; SL-67 acab37a2ef6d1dc85; APPROVE-401 adbd0a0881b88eb1b; SL-56, SL-63, SL-64, SL-68, SL-74 and light fixes: the integrator; SG-35 (0d04fea), SL-43, FWT-D11/D12, T-SCALE: old-session agents; comp a752876ae3a570e9b; matrix ad14f7c8100ce581c. The RV is none of these and not the integrator.

## 10. First actions after a login switch (both sides)
1. Read, in order: IDX; JL §1–§6; this plan (§0, §3, §4, §9.3); your own resume file (Sangam: LED §6f onward, then IT HO; IFMS: REC-05 v1.4 and REC-06).
2. From `D:\GITHUB  Project\IFMS`, run `python 00_MASTER_INDEX/session_lineage.py` to list every session, transcript and sub-agent.
3. Announce the new session id in JL §1 and to the other session, by id.
4. Run `status` on both locks; release only your own stale holders after a liveness check, and log it.
5. Call ListAgents and resume each agent by id; read SUBAG for the last message of each. For any agent that is gone, re-dispatch from §9.3 with the brief in §2 rule 14.
6. Check git: IT status against the BK manifest; REG integrity (§0.9 item 2); `git worktree list`; `origin/main` of both repos.
7. The send budget resets once the owner types; resend anything `UNDELIVERED`; read JL §5 for queued messages.
8. Continue the current wave (§4.1).

## 11. Appendix
### A. Id map (every id → its row or question)
- **Sangam rows (§3.1 A):** IFMS-WF, SL-63, SL-64, SL-68 (CI-BUILD-CITATION-GUARD, old "SL-03 residual"), SL-74 (REPO-STRAY-ROOT-FILES), SG-36, SL-61, SL-62, SL-65, SL-66, UI-PARITY-2, UI-PARITY-R2, SL-37 rows, SL-60 (H2-BITAND), UIP2-TASKLIST-OR (P45), SL-45, AC-09, SL-69, SL-70, SL-72, SL-73, SL-53, JL-S-03, SL-67 (CI-BUILD-SG15-CACHE-TEST, CI-BUILD-SERVER-D10-CONTEXT, CI-LOG-OUTBOX-CAPACITY-NPE), APPROVE-401, SL-14, SL-16.
- **Integrator tasks (§3.1 B):** REG-REPAIR-SL65, ZERO-LOSS-CHECKPOINT, LIGHT-BUILD-CAP, REG-ALLOC, JL-S-07, REG-REBASELINE, REGISTER-SYNC, SANGAM-TGR-SYNC, ID-RECONCILE-SG33-35, JL-RESTATE, JL-S4-OWNER-WORDS, SL-56, CI-SDKS-NATIVE, CI-SDKS-NATIVE-METADATA, ROUTING-INCIDENT-CT, ADR-D-27, CONTRACT-NOTICES, SG36-HEADERS-CONTRACT, SL45-INCIDENT, HEAVY-LOCK-V2, HANDOFF-DOC-UPDATE, LED-CORRECTIONS, MEMORY-POINTER, CLOCK-CORRECTIONS, ARTIFACT-ROUTE (P-460), E-58-REVIEW (P-454), OLD-INVENTORY, FREEZE-UNFREEZE, OLD-SCRIPTS-RETARGET; SANGAM-MAIN-COMPILE (sent 23:38:45Z).
- **Queued rows (§3.1 C):** SD-428-PASS, SD-428; SL-54; SL-55; SL-57, IFMS-WF-SOD; IFMSPACKSIT-6of6; PACKS4, SANGAM-BANKPACK-MONEY, SL-50, SL-33, SG-01, SG-20; SL-35, SL-32; SG-35-SANGAM; SL-71 (MOD-CTRL-401, MODULE-ERRORS); SL-76 (JIT-409, ENGINE-REST-HTTP500); SL-77 (GP-13, D-TENDER, COMP-GP13-GP21, GP-21, SL-12); SL-59; SL-58; MATRIX, AC-07, SG-04, SG-05, SG-13, CI-DBMATRIX-NO-SIGNAL, CI-DBM-ORACLE-ROOTSEQ-NULL, CI-DBM-MYSQL-P2-UTF8MB4-FK, CI-DBM-SQLSERVER-DIALECT, CI-DBM-POSTGRES-ACCEPTANCE, SL-44, P14; SG-15, SL-11, E2.7, SL-43; SECURITY with the CODESCAN-* items, VULN-kotlin-stdlib and VULN-protobuf-java and OWASP-SUPPRESSIONS (Q13), CI-SECURITY-NVD-KEY-MISSING (Q18); SDK-STORED-OUTCOME, CODESCAN-SDK-TS-REDOS, AC-08 (Q12); IX8 AC-01..06; SG-37; SG-38, SG-39, SG-14, AC-11; SL-18; SG-27, SG-30, SG-33, SG-34, SG-09, SG-16, SG-07, SG-12, SG-18 (Q19), SG-19; DEC-TASK-EVENTS; SL-04, SL-05, SL-06, SL-07, SL-30, SL-36, SL-40-42-RESIDUALS, SL-51, SL-52, FWT-D11/D12; SL-31 (Q9), SL-15, SANGAM-AT1-BILLTYPE, GA-55/P-438; SL-53a (Q34); TECH-DEBT-PARSELISTENER; REPO-RESOLUTION-HYGIENE; AC-10 (Q28); SL-75 (HYGIENE-SNAPSHOTS, REPO-SNAPSHOTS-ON-MAIN), CI-TRIGGER-300-FILE-LIMIT (Q16); CI-MAIN, CI-LICENCE-NOT-RUN-ON-HEAD; UI-UPGRADE-1 (Q20); JL-S-01, CWF-DOCS-WT (Q6); NEXT-PUSH (§4.2).
- **IFMS rows (§3.2):** P-452, SD-445, X-13, WP-04-SANGAM-ADAPTER, ADP-WF-SANGAM, WP04-SANGAM-ADAPTER, WP02-WORKFLOW-REACTOR, P-459, ONEIFMS-MAIN-DEMO-SANGAM, P-460, P-445, ONEIFMS-MAIN, SD-444, I-55, I-60, TRIAL2-RETEST, P-444, P-449, TRIAL2-PIN-1105D9C, SANGAM-RETEST-BASELINE, TRIAL2-RETEST-1105d9c, TRIAL2-RETEST-QUEUE, P-463, SD-449, P-453, R-98, P-464, P-465, JL-S-04, SANGAM-15E4135-CONTRACT, ADR-D-26, SD-427, P-433, SG-20-SQL, TRIAL2-LANES, P-432, P-419, P-419-SANGAM, TRIAL2-SS2025-ACC-HANG, P-429, E-53-COVERAGE, SD-417, TRIAL0-KITS, TRIAL0-JACKSON, SD-437, P-437, P-439, P-446, JACKSON-2.22.4, CI-ONEIFMS-QUEUED (Q31), JL-S-05, SD-434, SD-434-PACK-HOME, SD-434-PACKS (Q23), JL-S-06, MD-192, P-455, R-99, MD-195..207, OQ-218..227, OQ-228..256, P-450, HOSTING-SL-18, P-458, P-454, E-58, REC-05, REC-06, REC-01, REC-02, HANDOFF-INDEX, HANDOFF-ALL, HANDOFF-INDEX-ROWS, INDEX-TRIAL2, SD-441, P-435, GH-REQUIRED-CHECKS, REPO-RULESET-PROTECT-MAIN (Q14), E-54, E-54-V02, P-451, P-441, cde-events CR, P-78, OQ-109, E-37, SANGAM-ADOPTION, SD-422, SD-422-PAUSES, SD-419 (Q32), DOIT-PHED-SYNC (Q29), P-447 and SD-448 (Q33), P-456, P-457, P-461, P-462 (IFMS-internal; not joint).
- **Owner questions only:** DEC-SL43-LITERAL (Q7), DEC-S3DOCSTORE, DEC-CONTENT-TENANT, DESIGN-Q-S3-CONTENT (Q10), AC-03-EXPECTATION (Q11), CI-DBMATRIX-UNIT-GATING (Q22), REPO-STALE-BRANCHES (Q24), SANGAM-ALLOWED-SIGNERS (Q25), SG-31 (Q15), SG-02 (Q5).
- **Protocol and other items:** HEAVY-LOCK, LOCK-TICKETS, I-44, I-45, I-59: §4.4 and C-14. COORD-1/SD-441, COORD-RESUME, COORD-RESYNC, JOINT-LEDGER, JOINT-PLAN, XSESSION-SEND-LIMIT: §2. SD-317 and SD-433: §1. SD-432, SD-436, PUSH-16e0e11, NOTIFY-PUSH-1105d9c, SYNC-1105d9c, E57-BATCH-SYNC-1105D9C, HANDOFF-RESUMED, IFMS-REQ-TO-SANGAM, SANGAM-REQ-TO-IFMS, NOTIFY-IFMS-MAIN-RED: BATCH P1, C-3 and §3.2. PATCHES(engine)/PATCHES(ports) ids: §2 rule 8.
### B. Closed — no action (reason)
- SD-432: closed for the local gate; follow-ups SG-31, SG-32, SG-34. SD-436: ruleset 26789812 active; text fixes under P-435 and SL-19. I-44: FIFO in place. LOCK-TICKETS: queue empty. I-59: closed by both sides. PUSH-16e0e11: pushed 01:42Z. SD-428: stands; executed through SD-428-PASS and §3.3. SG-21: closed by T2; REG sync only. INDEX-TRIAL2: filled. JOINT-LEDGER: ACKed (SD-447). SD-445-LEDGER-CORRECTION: done as I-55 (residual in the I-55 row). REC-05 corruption: verified clean (0 control characters). P-453: CLOSED by SD-449. Q4: answered 23:49:14Z/23:50:23Z. SANGAM-MAIN-COMPILE correction: sent 23:38:45Z.
### C. Conflicts (K-n; each verified at source by its id owner and recorded in the row named; full texts in the structured output `unresolved_conflicts`)
- Open: K-1 HO L60 JDK 21 wording; K-2 CI-MAIN completeness (SL-67); K-3 SL-53 records not restated; K-4 clock drift; K-5 JL-S-04 property; K-6 SL-56 scope; K-7 SG-27 status; K-8 SG-34 depth (P-449); K-9 P-444 scope; K-10 SG-33; K-11 TRIAL0-JACKSON gate; K-12 stale Sangam JL rows; K-13 stale HO; K-14 LED §7; K-15 IfmsPacksIT owners; K-16 SL-58 vs P-429; K-17 SL-59 vs P-419; K-18 SG id clash; K-19 rulesets; K-20 965062f; K-21 E-54 internal; K-22 GA-55; K-23 APPROVE-401 state and the webhook switch; K-24 comp/matrix attribution; K-25 roles/protocol; K-26 UIP2-TASKLIST-OR scope; K-27 -Pdemo needs private jars; K-28 JL §7 and IDX; K-29 HEAVY-LOCK-V2; K-30 missing JL §5 entries; K-31 still unverified; K-33 REG SL-65 row damaged; K-34 retracted partial apply still recorded; K-35 ResolvedIdentityCacheH2Test cause; K-36 TZ vs `-Duser.timezone`; K-37 P-452 covers one adapter copy; K-39 agents editing shared files; K-41 light-build cap breach; K-42 P-459 vs the existing parent rule; K-43 R1 vs the SL-53 guard; K-44 stale IFMS JL rows.
- Resolved this pass (record only): K-32 plan v0.2 SL ids vs REG (remapped in §3.1); K-38 "no compile_lock.py copy" (scratch_A holds one); K-40 UI-PARITY-2 gate evidence (report 00:46:02Z, all green; the agent waited on its own background work and resumed itself, unlike I-60).
### D. E-56 extraction (E56X) mapping
| Finding | Plan row(s) |
|---|---|
| E1 | P-452 (both copies), C-1, X-13 hold (I-55 done) |
| E2 | C-2, ARTIFACT-ROUTE/P-460, TRIAL2-RETEST pins incl. `trials/sangam-money`, SG-16, Q5 (local main fast-forwarded: done) |
| E3 | SL-75, SL-74, RV-1 (2aaf44f), scans scoped to `sangam/` |
| E4 | JL-S-04, C-5 |
| E5 | §4 waves and R1–R3 |
| E6 | SL-54, fresh tenants, C-16 |
| E7 | SL-55 |
| E8 | SL-53 (decided b1), JL-S-03, C-9, SL-53a, Q34, K-43 |
| E9 | JL-S-05, C-12, Q23 |
| E10 | P-452 copies (K-37), ONEIFMS-MAIN-DEMO-SANGAM, the SG-33 trial pin |
| E11 | JL-S-07 / REG-ALLOC |
| E12 | MATRIX, TRIAL2-LANES, SANGAM-ADOPTION |
| E13 | PACKS4, C-6 |
| E14 | JL-S-06, C-17 |
| E15 | TRIAL2-RETEST-SCOPE, SD-427 S-F-6, SL-58, SL-59, P-450 |
| E16 | §4.4 |
| E17 | §4.4 priority, RK-27, SANGAM-ADOPTION |
- **B1:** adoption gates → SANGAM-ADOPTION, MATRIX, TRIAL2-LANES; no IFMS-specific code → §2 rule 10, SG-37, C-12; domain/PDP split → C-12; paise → PACKS4, C-6; Oracle/SQL Server → MATRIX, SG-29; only WP-02 adds the Sangam dependencyManagement entry → C-5, WP-02; Jackson → C-4.
- **B2:** C-2/C-15 (committed shas, private repos), SL-63/SG-21 (clean clone), Q5 (RC), C-5/SG-26, SD-427/SG-27, SG-28, SG-19, SG-29, SG-30/C-10, SG-05/06/07 + MATRIX.
- **B3:** S-C-01 → E-53-COVERAGE + SL-50; S-C-02 → SL-58/P-429, SL-53, SG-11; S-C-03 → SG-15 + E-53; S-C-04, S-D-423 → E-53 (IFMS PDP); S-C-05 → SG-19; S-C-06, S-W-02/WF-5/S-R-1 → IFMS-WF; S-C-07 → SL-53 BusinessClock + SL-53a; S-C-08/S-W-09/X-20 → SL-45, SL-69, SL-70, SL-72, SL-73; S-C-09/S-W-07/S-R-3/X-21 → SG-08b, SL-54; S-C-10/S-R-2/S-W-03..05 → E-53; S-C-11/T-8/MG-1..9 → E-53 with fresh tenants; S-C-12 → SG-10; S-C-13/S-F-5 → PACKS4 band edges, SD-427; S-C-14 → SG-03 + E-53; S-C-15/16/X-19 → SL-16, AC-08, SDK-STORED-OUTCOME; S-C-17 → SL-01; S-C-18 → UI-PARITY-2, SL-37, P45; S-C-19/T-5 → E-53 + SL-55; S-C-20/S-W-18 → SG-26/27, SECURITY, scan scope, C-18; S-C-21/X-15 → SL-04; S-C-22/T-9/T-10/I-9/I-10 → SL-46, SL-54 + E-53; S-C-23/S-W-19 → SG-18, Q19; S-W-01 → SG-08a; S-W-06/S-D-430 → SD-417; S-W-08 → P-452 + SG-08c; S-R-4 → SG-35; S-W-10/11/13, S-D-418 → TRIAL2-LANES; S-W-14 → SG-09; S-W-15 → E-53 (rejectDoctype); S-W-16 → SG-12; S-W-20 → SG-06; S-W-21 → SG-11, SL-53; S-R-5, S-D-419 → SL-59; S-D-429 → SL-58/P-429; S-T-* → E-53 + SL-55; S-A-1..7 → E-53.
- **B4:** lanes → TRIAL2-LANES; PIT → SG-01/03; scans → E-53 + SECURITY; soak and chaos → §4.4 #16/#17; perf → SG-15; IFMS scenarios → E-53 + SD-434 R3 + SL-47/48; exit criteria → C-6, SECURITY, §2 DoD. **B5:** GA-55 → C-17; reason codes → SL-55; IST/fiscal year → C-9, SL-07; band edges → C-6. **B6:** ids → §11 A.
### E. Findings and critic points not adopted, or changed by newer evidence (one line each)
- **"Next free SL-69; MOD-CTRL-401 = SL-69, JIT-409 = SL-70, GP-13/D-TENDER = SL-71, NPE = SL-72":** overtaken at 00:28:43Z and 00:47:12Z (REG now SL-69..SL-75). Adopted the remap (CI items → SL-67, citations → SL-68, MOD-CTRL-401 → SL-71); JIT-409 → SL-76 and GP-13/D-TENDER → SL-77 proposed; the NPE stays inside SL-67 as dispatched.
- **"UI-PARITY-2: 38 tracked files + 7 untracked test paths":** the agent's own list is 34 changed (incl. REG) + 6 new; the difference is likely line-ending-only entries.
- **"No UI-PARITY-2 gate evidence anywhere":** gates ran in IT after the apply and were reported green at 00:46:02Z; kept the caveat that SL-68 and SL-45 landed mid-run, so the P1 gate re-runs them.
- **"SL-53.patch mtime 18:40:16Z" (and v0.2's "03:26Z"):** now 00:12:27Z (refreshed by the agent).
- **Lock states quoted by the findings** (heavy `customwf-dst` since 23:33:30Z; compile `ifms-p452` since 23:42:37Z): true then; at 00:55Z heavy free, compile `ifms-p452` since 00:28:48Z (after a release at ~00:05Z).
- **"R-98 v0.1, critic pass pending" / "R-99 v0.1" / "E-58 v0.1, P-454 OPEN":** overtaken: R-98, R-99 and E-58 are v0.2 (IFMS 7f31aa9); P-453 CLOSED (SD-449); P-454 DONE as a draft map.
- **"oneifms main = 26051321":** now 970a07cc (docs-only push, 00:22Z).
- **"Sangam's relays 23:49:31Z/23:50:36Z never logged":** IFMS logged them as received lines; Sangam still adds its own sender entries. The IFMS COORD-RESYNC ACK (19:13:49Z) is still unlogged (K-30).
- **IFMS stamps "~00:15" (push) and "~00:22" (R-98 ACK received):** already corrected by IFMS (23:43 from the push reflog; ~23:46).
- **"[INCIDENT] SL-45 worded suspected or confirmed":** confirmed by the 00:28:03Z report (two real `/engine-rest` leaks).
- **"P-459 bears on the C-2 opt-in":** adopted, but the parent at 26051321 already carries a `workflow-engine-boundary` rule with org.sangam* (K-42).
- **"Six Sangam agents active vs the cap":** at 00:55Z four agents work and two are idle; the cap breach was measured as 4 Sangam Maven builds at 00:33Z.
- Kept from v0.2: UIP2-TASKLIST-OR stays a BATCH REPORT unless IFMS reports a REST/UI use (WP-04 embeds sangam-engine); REC-05 has 0 control characters; IFMS dropped the 1105d9c pin (R1 = P1 sha, JDK 21 fallback); P-447 decided as SD-448 (FYI Q33); no JDK download needed; REG already had SL-57..SL-59; the send budget resets when the owner types; DOIT/PHED ids recorded in both forms; HO is cited by section; compile-lock use is IFMS's call (Q30); the public routing constants type is an optional v1.1; SL-58 gets joint triage first; ROUTING-INCIDENT v1 was sent (03:01:30Z) and I-55 done; the taskForm flake was root-fixed (HO §6b).
### F. Clock corrections (real times; for JL §5/§4 and LED correction entries)
**Sangam sends** (send time per transcript 6e98c2a7 → stamp written):
| Message | Real time | Stamp |
|---|---|---|
| COORD-RESUME | 01:53:13Z | ~01:55 |
| CONTRACT JOINT-LEDGER | 01:57:21Z | ~02:00 |
| COORD-RESUME-2 | 02:18:10Z | ~02:35 |
| REPORT IFMS-WF | failed 02:34:06Z (title), delivered 02:34:22Z | ~03:05/~03:08 |
| ONEIFMS-MAIN + COORD-RESUME-2 ACK | 02:36:06Z | ~03:20 |
| INCIDENT SG-35-ADAPTER + CONTRACT ROUTING-INCIDENT v1 | 03:01:30Z | ~03:45 |
| DECISION-NEEDED SL-53 | 03:01:48Z | ~03:50 |
| REQUEST IFMS-WF-SOD | 03:12:59Z | ~04:12 |
| REPORT IFMS-WF-SOD ACK | 03:18:28Z | not logged |
| REPORT SL-18 ACK | 03:48:49Z | ~04:30 |
| REPORT HEAVY-LOCK-STALE | 18:26:40Z | correct |
| INCIDENT SANGAM-MAIN-COMPILE | 18:46:26Z | ~18:50 |
| REPORT COORD-RESYNC | 19:11:24Z | ~19:20 |
| REPORT SANGAM-MAIN-COMPILE CORRECTION | 23:38:45Z | ~23:40 |
| REPORT SL-53 R-98 ACK | 23:40:46Z | ~23:50 |
| REPORT SL-53 OWNER DECISION | 23:49:31Z (its text says the owner answered "~00:20"; real 23:49:14Z) | logged by IFMS "received before 00:08" |
| REPORT SL-53 OWNER DECISION (itemised) | 23:50:36Z | logged by IFMS "received before 00:08" |
| REPORT SD-449/I-60/R-99v0.2/E-58 ACK | 00:11:55Z | logged by IFMS "~00:15 received" |
- **Sangam internal events:** option (A) 02:33:39Z; STEP1-LANDED 03:12:53Z (JL row says "~04:10"); SL-60 allocation 03:15:37Z; first 11-09 limit notice 04:10:09Z (LED "~05:20"); second limit notice 20:14:49Z, agents stopped 20:14:48–20:40:51Z, recovery 23:16:25Z (LED/JL "~20:40 → 23:15"); owner words 23:49:14Z and 23:50:23Z (JL §4/LED §6i "~00:20"); REG SL-65 damage 00:35:03Z.
**IFMS messages** (arrival at Sangam = latest possible send time → stamp):
| Message | Arrival | Stamp |
|---|---|---|
| COORD-RESUME | 02:15:13Z | ~02:20 |
| JOINT-LEDGER ACK | 02:18:35Z | ~02:22 |
| ONEIFMS-MAIN | 02:35:25Z | ~03:05; push stated 02:55, reflog 02:29:38Z |
| IFMS-WF ACK | 02:45:46Z | ~03:15 |
| SG-35-ADAPTER ACK | 03:05:53Z | ~03:58 |
| IFMS-WF-SOD ACK | 03:18:18Z | ~04:20 |
| SL-18 REPORT | 03:45:36Z | ~04:55 |
| HEAVY-LOCK-STALE INCIDENT, its ACK, CONTRACT HEAVY-LOCK-V2, SANGAM-MAIN-COMPILE ACK, HEAVY-LOCK-V2 amendment 1 | all delivered together at 19:12:09Z (queued) | 18:23, 18:33, 18:34, 18:50, 19:05 |
| COORD-RESYNC ACK | 19:13:49Z | not logged |
| IFMS-WF-SOD (R-99 v0.1) | 23:16:25Z | ~23:40 |
| SL-53 (R-98 v0.1) | 23:40:12Z | ~23:45 |
| ONEIFMS-MAIN 26051321 | 23:44:53Z | ~23:45 (corrected) |
| SL-53 R-98 ACK received | 23:45:08Z | ~23:46 (corrected) |
| SD-449 + I-60 + R-99 v0.2 + E-58 | 00:11:05Z | 00:08 |
| E-58 PUBLISHED + ACK | 00:27:01Z | not logged at 00:45:22Z |
- The "18:50" IFMS stamp is later than the JL write at 18:47:27Z. No separate delivery of "COORD-RESUME-2 IFMS→Sangam ~03:07" appears in the Sangam transcript; IFMS to check.
