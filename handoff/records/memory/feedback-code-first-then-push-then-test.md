---
name: feedback-code-first-then-push-then-test
description: "Owner order from 2026-11-12: finish ALL Sangam code end to end (with unit tests written) → update all docs → push to main → only then run tests stage by stage (unit, system, integration, acceptance) → final E2E report"
metadata:
  node_type: memory
  type: feedback
  originSessionId: 6e98c2a7-cdac-4783-9098-832546572812
  modified: 2026-11-11T18:32:03.680Z
---

The owner gave this order on 2026-11-12, after resuming from a 6-hour pause:

1. **Code first.** Every open item gets coded end to end: register rows, ledgers, decisions and findings. Unit tests are written alongside; without them the work counts as incomplete. Any unit-test framework is allowed, but it must improve lifecycle, stability and extensibility.
2. **Docs.** Every Sangam-related document is updated.
3. **Push.** The complete code goes to `main` on GitHub.
4. **Then test, one stage at a time:** unit, system, integration, acceptance. Finish with a complete end-to-end report that misses no logic and no test stage.
5. **Centralise.** Collect stray Sangam work from the whole workspace and one folder up: worktrees, CI, scratch copies. Complete it and merge it into the one main Sangam project folder ("one stop, one folder"). Remove sub-folders that are no longer needed, but only after confirming with the owner.
6. **IFMS knowledge base.** IFMS's knowledge base (central and state IFMS data) may be used for research, linking and verification. Keep the links folder-wise.
7. **Accounts.** The owner did 80–90% of the earlier work under a different login (lokendra.tester) and is moving to a new account. Check for anything lost in that transition.

**Why:** the owner wants one complete, centralised code base on main before spending time on long test runs.

**How to apply:**
- During coding, run only light checks (compile and test-compile gates).
- No heavy DB, Testcontainers or matrix runs until the code is on main.
- The push still goes through the owner-signed push gate.
- Deleting folders needs explicit owner confirmation per item.

Related: [[feedback-root-cause-only]], [[feedback-shared-heavy-lock]], [[project-handoff-2026-11-08]].
