---
name: feedback-agent-sync-board
description: "Owner order (2026-11-12, permanent). All agents in every wave sync with each other through a board, so there is no issue, gap, miss, crossed direction or double work."
metadata:
  node_type: memory
  type: feedback
  originSessionId: 6e98c2a7-cdac-4783-9098-832546572812
  modified: 2026-11-12T09:59:02.115Z
---

Owner, 2026-11-12: "All Agent Sync with each other set this rule so to avoid any issue or any GAP or any miss directions". Earlier the same day the owner said: share and move forward together, so that no agent's effort is wasted and nothing has to be redone.

**Why:** unsynced lanes caused real misses:
- the UI did not send a newly required field (SL-163);
- a constructor changed without its consumer being updated, which broke the build (PortsWiring);
- four copies of the same formula (SL-164);
- Wave 1 features were coded but never wired.

**How to apply:**
- Canonical rules: `D:\GITHUB  Project\sangam-handoff\AGENT-SYNC-RULES.md`. Board per wave: `WAVE<N>-SYNC-BOARD.md`, with open entries carried over between waves.
- Every agent brief must say to read AGENT-SYNC-RULES first.
- Agents post CHANGE (with a consumer grep) before any cross-module change and DONE (with SHA) when it lands. They use REQUEST/ACK between lanes and BROKEN for gate breaks, check the board before building anything, run gates on the live tree, and never break another lane silently.
- The integrator routes every open entry. No checkpoint while any CHANGE lacks a DONE, any REQUEST is unanswered or any BROKEN is open.

Related: [[feedback-sync-after-every-item]], [[feedback-coordination-protocol]], [[feedback-evidence-based-97-confidence]].
