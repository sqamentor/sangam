---
name: feedback-shared-heavy-lock
description: "Heavy builds on this machine must hold the shared heavy-build lock (owner customwf), because they share it with the IFMS project"
metadata:
  node_type: memory
  type: feedback
  originSessionId: 898f1ff7-0dcc-4d95-b425-830a9a5ead44
  modified: 2026-11-09T18:36:47.455Z
---

Wrap every heavy build in this project (Maven test runs with Testcontainers, DB containers, benchmarks) in the shared machine lock:

```
python C:/Users/LOKEND~2/AppData/Local/Temp/claude/D--GITHUB--Project-IFMS/ff9f2caf-1246-4228-ae70-82a16cf209ae/scratchpad/heavy_lock.py acquire customwf "<what>"
... heavy run ...
python .../heavy_lock.py release customwf
```

`status` shows who holds it. Never delete or break another owner's lock. Small unit builds don't need it.

**Why:** the IFMS session on the same machine reported that its database test lanes slowed from about 40 to 100 minutes, with timeouts, when the Sangam spike builds overlapped them. The user agreed that both projects use the same lock (agreed 2026-11-05; relayed by the IFMS session).

**Owner per workstream (2026-11-08):** `release` only compares the owner name, so parallel subagents sharing `customwf` freed each other's running locks and their containers overlapped (Oracle start timed out). Give each parallel workstream its own owner `customwf-<ws>` (e.g. `customwf-con`) for both acquire and release; plain `customwf` only for my own runs. One lock file = one heavy job on the whole machine across all owners; distinct names never allow parallel runs. Owner `sangam` is the IFMS session's Trial-2 agent testing Sangam (not mine). IFMS re-tests only committed Sangam ids: after each commit send the commit id + closed SG ids to the IFMS master session.

**Detached runner only (2026-11-09):** never hold the lock from an agent's own shell. A usage limit killed the SL-45 agent mid-run, so its `finally` never ran and `customwf-iso` stayed held for about 14.7 h, blocking IFMS too. The tool has no holder liveness. Every heavy run goes through a detached runner script, started with `Start-Process`, that does acquire (abort unless exit 0) → run → release in its own try/finally. The agent only polls the log.
- Claim runs of 30 minutes or more in `D:\GITHUB  Project\JOINT-LEDGER-SANGAM-IFMS.md` §6.
- After any outage, run `status` first. If a `customwf-*` holder has no live job or container, release it and log it.
- Holder liveness (pid + start time + lease) has been requested from IFMS, which owns the tool.

**How to apply:** take the lock before any heavy step, and pass this rule on to any subagent that runs heavy builds. Release the lock in all cases, failure included. See [[project-sangam-workflow-engine]].
