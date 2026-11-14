---
name: feedback-root-cause-only
description: "Owner rule for Sangam — every issue fixed at the root, core level, end to end; never jugaad/workaround/\"kaam chalao\"; ≥99.99% confidence it never recurs"
metadata:
  node_type: memory
  type: feedback
  originSessionId: 898f1ff7-0dcc-4d95-b425-830a9a5ead44
  modified: 2026-11-08T02:57:09.881Z
---

Every defect is fixed at its root, at the core level, end to end, so the same failure can never recur, and I must be ≥99.99% confident after the fix. Never a jugaad, bypass, temporary patch, test-only shortcut, weakened/replaced assertion, extra driver or flag just to make something pass, or "kaam chalao" code.

**Why:** The owner (2026-11-08, repeated twice) said shortcuts now become failures later in the production system; Sangam serves governments and regulated sectors.

**How to apply:**
- For each failure: find the real cause (reproduce it in isolation), fix it in the shared/core place so every current and future caller is covered, and add a regression test that would have caught it.
- A changed expectation versus a spec (Stage B, acceptance plan) needs a cited SANGAM.md decision id; otherwise the old expectation stays.
- If no root fix is possible inside scope, say so plainly and leave the test failing; never hide it.
- Pass the same rule verbatim to every subagent brief.
- Parallel agents in one working tree (learned 2026-11-08): never `install` (corrupts shared ~/.m2; use `-pl X -am clean verify`); each workstream its own heavy-lock owner `customwf-<ws>`; agents that edit product code work in a private worktree copy and apply changes to the main tree only when it compiles and tests pass, so the main tree always compiles.

Related: [[project-sangam-workflow-engine]], [[feedback-fast-parallel-work]].
