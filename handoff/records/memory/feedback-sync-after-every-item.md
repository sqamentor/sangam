---
name: feedback-sync-after-every-item
description: "Owner rule 2026-11-12. After every item, smallest or biggest, cross-verify it against the plan and sync every tracker so nothing is ever missed."
metadata:
  node_type: memory
  type: feedback
  originSessionId: 6e98c2a7-cdac-4783-9098-832546572812
  modified: 2026-11-12T06:27:34.908Z
---

Owner (2026-11-12): "do not miss anything any how any way, strictly follow things end to end as per planned, always cross verify planned item and current work, sync all things after single smallest and biggest items or tasks completion."

**Why:** Wave 1 work showed real drift. M-052 was planned but never assigned to a lane. The master backlog status had not been updated. Register status words had gone stale (SL-37). Agents' claims also needed checking against code (SL-58, LegacyTimeZones, T-SCALE evidence).

**How to apply:** after EVERY item result, run this checklist in order:
1. Cross-verify the key claims myself against the code (file:line, hashes, or generator runs).
2. Update the register row in `sangam/TESTING-GAP-REGISTER.md`.
3. Update `sangam-handoff\backlog-status.json`, then run `gen_backlog_status.py`. It fails on an unknown M-id.
4. Add any leftovers or cross-lane needs to `sangam-handoff\WAVE2-CARRY-*.md`.
5. Add decisions and progress to `RESUME-LEDGER-2026-11-09.md`.
6. Check that FILE-CLAIMS has released the item's claims.

Before closing a wave, check that every planned M-id of that wave has a lane and a status. Related: [[feedback-evidence-based-97-confidence]], [[feedback-coordination-protocol]], [[feedback-code-first-then-push-then-test]].
