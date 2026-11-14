---
name: feedback-coordination-protocol
description: "Owner wants standard-dev coordination between Sangam and WorkFlow/IFMS sessions and among agents — no rework, no duplicates, no overwrites"
metadata:
  node_type: memory
  type: feedback
  originSessionId: 898f1ff7-0dcc-4d95-b425-830a9a5ead44
  modified: 2026-11-08T12:53:39.807Z
---

Coordinate by the written protocol `sangam/COORDINATION.md` (v1, 2026-11-08):
- one owner per ID, checked against the register before starting;
- a single integrator per repo;
- file scopes assigned to each workstream, with shared files changed only by targeted Edit on the current main-tree file (never copy-over);
- contract-first changes;
- heavy lock I-44/I-45;
- a strict definition of done;
- message header `[TYPE] <ID> from→to subject`, where TYPE is REQUEST, REPORT, CONTRACT, INCIDENT or DECISION-NEEDED;
- stop on crossed messages;
- a message is never an approval.

**Why:** on 2026-11-08 overlapping agents caused real damage. A shared `wt` folder was overwritten, shared files were copied over each other, the main tree broke from a half-applied copy, and crossed messages briefly left main without its ruleset. The owner asked for streamlined, non-repetitive, quality-first coordination using standard development rules.

**How to apply:** every agent dispatch names its file scope and the shared files. Send every cross-session message in the header format. Report every completed item and every integration point to WorkFlow, with IFMS main in copy. Related: [[project-boundaries]], [[feedback-shared-heavy-lock]], [[feedback-root-cause-only]].
