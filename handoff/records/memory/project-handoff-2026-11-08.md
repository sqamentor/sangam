---
name: project-handoff-2026-11-08
description: READ FIRST when resuming Sangam — RESUME-LEDGER-2026-11-09 (post-backup events) then repo HANDOFF.md, backup on D:
metadata:
  type: project
---

Resume order:
0. `D:\GITHUB  Project\JOINT-LEDGER-SANGAM-IFMS.md`. This is the single shared ledger with the IFMS session (proposed 2026-11-09), and the joint plan is linked from its §3.
1. `D:\GITHUB  Project\sangam-handoff\RESUME-LEDGER-2026-11-09.md`. This is the newest file. It covers everything after the 2026-11-08 backup: the commits up to the freeze commit `16e0e11`, the SD-445 decision, the results produced after the agents died, the open decisions and the plan.
2. Repo `D:\GITHUB  Project\Custom WorkFlow\HANDOFF.md`, for the rules, the push gate, the apply order and the backup layout.

State as of 2026-11-09 ~01:50 UTC:
- `origin/main` is `1105d9c`, containing `16e0e11`. The owner signed, and the push went through.
- PR #4 and Dependabot PR #2 are merged. PR #3 was held back: it conflicts and makes major bumps.
- **The integration tree is now the worktree `.claude\worktrees\workspace-scan-resume-06b524`.** The base checkout stays on `handoff/2026-11-08` @ `16e0e11`; do not edit it.
- SL-53 is decided as (b), engine JVM in UTC.
- Agents running: SL-53, IFMS-WF, SL-45 and UI-parity-2.
- The old account's weekly limit resets on 2026-11-15.
- The old agents' worktrees are in `C:\Users\LOKEND~2\AppData\Local\Temp\claude\5cef910d4d286f64\898f1ff7-0dcc-4d95-b425-830a9a5ead44\scratchpad\wt-*`.

Owner decisions recorded on 2026-11-09/10 (verbatim words in JOINT-LEDGER §4):
- **PR #3:** "Separate upgrade later".
- **SL-53:** (b) "engine JVM in UTC", then on 2026-11-10 "IFMS की तरफ़ की शर्तें मंज़ूर हैं।" That means strict UTC for every engine JVM now, including embedding hosts, and UTC persistence (a) later as SL-53a, with the date still the owner's.
- **IFMS R-99:** IFMS's research note giving the budget-distribution seat model, with official sources. It is encoded as pack data after apply step 2.
- **2026-11-10 decisions:**
  - P1 push may carry known-red tests, each with an SL id and listed in the signed approval text; from P1b on, only fully green pushes.
  - The UI upgrade already on main via 2aaf44f is accepted after the SD-428 review.
  - The AT-3 expectation change is accepted.
  - RC route: tag v0.1.0-rc.1 on the first all-green main, with no registry publication before adoption.
- **Joint plan v0.3:** `D:\GITHUB  Project\JOINT-PLAN-SANGAM-IFMS.md`. Its open owner questions are listed there.

**Why:** the owner demands zero information loss across profile switches, including the work, conversations and decisions that came after the backup.

**How to apply:**
- Keep the ledger and HANDOFF.md updated after every significant change.
- Sessions that run in a `.claude\worktrees\*` worktree can't edit the base checkout (a hook blocks it). Write ledger updates to `D:\GITHUB  Project\sangam-handoff\`, and fold them into HANDOFF.md at the next integration commit.

Related: [[feedback-coordination-protocol]], [[project-sangam-workflow-engine]], [[project-boundaries]].
