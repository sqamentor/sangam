---
name: feedback-fast-parallel-work
description: "The user wants maximum speed — parallel agents, batched questions, short updates; finish the Sangam baseline by Fri 2026-11-06"
metadata:
  node_type: memory
  type: feedback
  originSessionId: 898f1ff7-0dcc-4d95-b425-830a9a5ead44
  modified: 2026-11-04T05:20:52.960Z
---

The user asked (2026-11-04) for a working style that finishes everything as fast as possible. The target is to close the Sangam research and specification phase in 2–3 days, before the weekend of Sat 2026-11-07.

**Why:** they want clear, finalised answers quickly, so they can decide the approach and the plan.

**How to apply:**
- Run independent research and writing as parallel subagents. Each writes to a scratch file, then merge it into SANGAM.md with a script and an integrity check.
- Batch owner questions once a day in a single AskUserQuestion.
- Keep chat updates short; put details in SANGAM.md.
- Don't idle-wait: start the next independent work while agents run.
- Be clear that "complete" means an approved spec baseline, not built software. See [[project-sangam-workflow-engine]].
