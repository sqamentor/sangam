# Audit D: account-switch continuity and centralisation (2026-11-12, read-only)

Scope: every git worktree from `git worktree list`, the base checkout, `D:\sangam-wt-ci`, the non-git copies in the old-session scratchpad (`C:\Users\Lokendra Singh\AppData\Local\Temp\claude\5cef910d...\scratchpad\`), the refs, the handoff/ledger files and the memory directory.
Method: `git status --porcelain -uall` per worktree, then a content comparison against the integration tree (`.claude\worktrees\workspace-scan-resume-06b524`). Line endings were normalised, and build output was skipped (`target*`, `node_modules`, `obj/bin`, `.class/.jar/.log/.jfr`). Each changed file was put in one of these classes:
- SAME: identical to the integration tree.
- EOL-only.
- OLD_COMMITTED: equal to a blob already in git (origin/main, 9c831d0, 15e4135, 0196b57, 2aaf44f, 3736f8f, 16e0e11c, f553b43, 7cac6c4e).
- In the snapshot commit: equal to a file under `handoff/worktree-snapshots/`, which is committed on origin/main by f553b43.
- DIFF/MISSING: everything else.

Every DIFF/MISSING file was then checked by mtime and by a line-containment test, and the doubtful ones were read as diffs.
Nothing was modified. The only writes were scripts and results in this session's scratchpad, plus this report.

## Answers

1. **Was planned work lost during the switch? No.** Every file changed in the old-account worktrees and copies falls into one of three groups:
   - (a) already in the integration tree, identical or superseded by a newer version;
   - (b) already in git on origin/main (an old committed blob, or the snapshot commit f553b43);
   - (c) a non-source artefact: a log, a scratch helper or a reference clone.

   All 17 commits by the owner's git address are reachable from origin/main. **Confidence 97%.**
2. **Is everything centralised? Yes for content, not for git state.** All product content is in the integration tree. In 0 of the 137 distinct non-artefact paths is the copy newer than the integration version. But the integration tree's work (1,913 status entries) is **uncommitted and unpushed**, and it is the only full copy. **Confidence 97%.**
3. **Will nothing be lost on the next switch? Only if the integration tree is committed and pushed, or at least snapshotted again, first.** The memory and the ledgers are on the Windows profile and on D:, so they do not depend on the Claude account. The details are below.

## Refs and commits (evidence)
- `git for-each-ref` lists:
  - main = origin/main = claude/workspace-scan-resume-06b524 = 1105d9cb
  - handoff/2026-11-08 = 16e0e11c
  - origin/handoff/2026-11-08 = 7cac6c4e
  - origin/dependabot/maven/... = 1d2dee0f
  - origin/dependabot/npm_and_yarn/... = 77f51234
- `git rev-list --all --not origin/main` returns exactly **one** commit: 77f51234, the Dependabot npm bump. The owner said "separate upgrade later"; it stays on GitHub.
- Commits by the owner's git address: 17 in total, **0** not on origin/main. The key ids 0196b57, 15e4135, 2aaf44f, 3736f8f, 0d04fea, f553b43, 16e0e11c and 7cac6c4e are all ancestors of origin/main.
- The stash is empty.
- The Sangam clones inside oneifms-sangam (`.sangam-src` at 0196b57 and `.sangam-src-15e4135`) are clean, and their HEADs are on Sangam main.

## Per location
| Location | Uncommitted | Verdict |
|---|---|---|
| Base checkout `D:\GITHUB  Project\Custom WorkFlow` (handoff/2026-11-08 @16e0e11c) | 0 | SAFE (clean) |
| `D:\GITHUB  Project\cwf-docs-wt` | 11 | SAFE. The 7 added files are identical or committed. HttpRestConnector, application.yaml, values.yaml and server.Dockerfile are superseded: the integration versions have SL-61/62/65 and later work, which I read. |
| `D:\wt-fwt` | 3 | SAFE. The FWT-D11/D12 design moved to `lockVersionOfAttempt`, and ProblemAdvice is in the integration tree. I read both. |
| `D:\wt-scale` | 9 | SAFE. TScaleLoadIT is committed. report.txt and timeline.csv are in the integration tree under `sangam/perf/results/2026-11-08-tscale-15e4135/{base,smoke}`. **Excluded on purpose:** `tscale-smoke\ev.json` (1.64 GB raw events) and the two `.jfr` files (7 MB and 41 MB). These exist only here. |
| `D:\sangam-wt-ci` (not a git repo; a copy from 08-11) | — | SAFE. 52 source diffs are older than the integration tree, and all register ids are present there. The other 33.8k files are `reference/operaton` and `reference/flowable-engine` upstream clones, which also exist in the base checkout, plus .NET `obj/bin` and `.pytest_cache`. |
| Old scratchpad git worktrees wt-auth401, bulk, bulk2, forms, fwtd, iso, packs4, perf, perf-base, uiparity (all detached @9c831d0) | 391–3,561 each | SAFE. Most entries are EOL-only or old committed blobs, and every unique change is in the snapshot commit f553b43. The rest are older than the integration tree: the pack BPMNs there are the 1.0.0 generator output, and the integration tree has the 1.0.1 regeneration. |
| Old scratchpad non-git copies: wt-comp, wt-ifmswf, wt-uip2, wt-uip2-ctl, wt-dst, wt-sg35, wt-qdl, wt-small, wt-quota, wt-conf, wt-i8, wt-packs2/3, wt-sl37, wt-base, wt | — | SAFE. These are full-tree copies. Some were still edited by current-account agents after the switch: wt-ifmswf until 11-11 15:00, wt-dst until 11-10 07:21, wt-auth401 until 11-10 07:21. In every case the integration file is newer and is a superset. Spot reads:<br>- IfmsPacksIT at the old `acceptance/ifms` path was moved to `acceptance/consumer/ifms` (SG-37). Only the money literals and the package line differ.<br>- The wt-comp `.mine` files (SL-101) are applied in `SequentialCompensation`, `SangamBpmnParseListener` and `InheritedWeaknessH2Test` (SL-101 tests at lines 231–247).<br>- TESTING-GAP-REGISTER copies: every SG/SL/SD/GP/AT/FWT/E id in them is present in the integration register. |
| `BulkQuota.java` (wt-bulk) | — | SUPERSEDED, and kept in git. It exists only in the snapshot commit. Nothing in the integration tree references it: the quota moved to `TenantQuotas` (ledger §1, 18:30). |
| Small scratch dirs: sg27, mine, keep-old, drafts, quota-patch, sl50, tscale-* | — | Not product source. These are SG-27 probe poms and copies, my.patch, sl43.patch and helper scripts. Their substance is committed (SG-27, SL-43). They only matter as history. |
| `D:\GITHUB  Project\oneifms-sangam` | 0 | IFMS repo, clean. No Sangam-only work in it. |

## At risk (would be lost if the disk or Temp were cleaned)
1. **The integration tree itself.** It is uncommitted and unpushed: 1,669 M, 224 ??, 20 D (`git diff --shortstat`: 1,472 files, +25,595/−11,366). It holds all of the code-complete work (SL-84..118, SG-xx, PACKS4, SDK and so on), and it is the **only full copy**.
   - The last safety snapshot, `sangam-handoff\snapshots\20261111-1924` (BASE 1105d9c, taken 12-11 00:54), is **stale**: 1,728 of the changed paths have an mtime after it.
   - The 20 deletions are intentional, as far as I could check:
     - stray root files (E3);
     - `acceptance/ifms/*` and `e46` resources, which moved to `acceptance/consumer/ifms` (SG-37);
     - `LegacyTimeZones*`. I did not verify whether this was replaced or dropped, so confirm it with its owner.
2. **The old-session scratchpad is under `%TEMP%`.** Storage Sense or Temp cleanup can delete it. No unique source is left in it, but these are only there:
   - run logs (IT, PostgreSQL, matrix);
   - `.jfr` profiles;
   - `apply_*.py` helpers;
   - the old worktrees' git metadata. Losing it would leave prunable `git worktree` entries; that is cosmetic.
3. **This session's scratchpad is under `%TEMP%`** (`C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7...\scratchpad`, 205k files, 3.6 GB). It holds the current agents' gate logs, before/after generator outputs (`gen*`, `prof-*`, `packs-before`) and `res-before.json`. These are evidence, not source. Copy whatever a report cites to D:.
4. **`D:\wt-scale\tscale-smoke\ev.json` (1.64 GB) and the `.jfr` files** are raw T-SCALE evidence that was excluded on purpose. They are only on D:; that is acceptable if the owner agrees.
5. **The memory copy in `sangam-handoff\2026-11-08\memory` is stale.**
   - Stale: MEMORY.md, project-handoff, project-boundaries, feedback-shared-heavy-lock.
   - Missing: feedback-code-first-then-push-then-test, feedback-evidence-based-97-confidence, feedback-unstable-internet-recheck.
   - The live memory is fine (see Continuity), but the D: backup lags.

## Safe
- Everything committed: all refs except 77f51234 are reachable from origin/main on GitHub.
- The snapshot commit f553b43 (`handoff/worktree-snapshots/**`, 12,911 tracked files on main) preserves every old private worktree's work byte for byte.
- On D:, independent of the Claude account:
  - `sangam-handoff\` (RESUME-LEDGER, FILE-CLAIMS, CODE-COMPLETE-PLAN and the 2026-11-08 backup with its patch and transcript)
  - `D:\GITHUB  Project\JOINT-LEDGER-SANGAM-IFMS.md`
  - `JOINT-PLAN-SANGAM-IFMS.md`
  - `HANDOFF-INDEX.md`
  - the repo `HANDOFF.md`, which is uncommitted (part of item 1)

## Continuity
- **Memory:** `C:\Users\Lokendra Singh\.claude\projects\D--GITHUB--Project-Custom-WorkFlow\memory\` (11 files, last written 12-11 00:05).
  - It lives on the Windows user profile, keyed by the project path. It does not depend on the Claude account, so switching accounts in the same Claude Code install and Windows user keeps it.
  - Caveat: the key is derived from the working directory. A session opened in the worktree path (`...--claude-worktrees-workspace-scan-resume-06b524`) has **no** memory dir. Only the base path `D:\GITHUB  Project\Custom WorkFlow` loads it.
  - Risk: a reinstall or a different Windows user would lose it. Keep the D: copy current.
- Old-account session transcripts are only in the backup (`sangam-handoff\2026-11-08\main-session-transcript.jsonl`). They are not needed to resume; the ledgers carry the state.

## Concrete steps before the next switch (owner approval needed for any git write)
1. Take a **fresh safety snapshot** of the integration tree into `sangam-handoff\snapshots\<UTC>\` with the same method as before: `git diff --binary HEAD` gives `tracked.patch`, the untracked files go into a tar, and HEAD goes into `BASE.txt`. It is read-only for the tree and covers the gap since 00:54.
2. Better still, follow the owner's code-first order: commit the integration tree on its branch and push it through the owner-signed gate, so the work exists on GitHub. Until then there is a single copy on disk.
3. Refresh `sangam-handoff\2026-11-08\memory` (or a new dated folder) from the live memory dir. Add a line to RESUME-LEDGER saying: "open sessions from `D:\GITHUB  Project\Custom WorkFlow` (memory key)".
4. Copy to D: (under `sangam-handoff\evidence\`) any log or profile from either Temp scratchpad that a register row or report cites. After that, the old Temp scratchpad can be retired. That needs owner approval for deletion, and `git worktree prune` should run only after the folders are removed.
5. After the push: remove `handoff/worktree-snapshots/**` from main (D-18 / E3, the owner's call). Everything in it is confirmed applied or superseded, except BulkQuota.java, which is superseded.
6. Get the `LegacyTimeZones*` deletion confirmed by its owner before committing.

Evidence files, from this session's scratchpad:
- `C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\r_git.json`
- `C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\r_dir.json`
- `C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\unpreserved.json`
- `C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\g2.txt`

Scripts: `audit3.py` and `an3.py` in the same folder.
