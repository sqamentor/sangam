---
name: feedback-unstable-internet-recheck
description: "The owner's internet is unstable and usage limits recur. After ANY interruption, recheck everything that may have stopped and resume it; never assume it is still running"
metadata:
  node_type: memory
  type: feedback
  originSessionId: 6e98c2a7-cdac-4783-9098-832546572812
  modified: 2026-11-11T04:38:06.638Z
---

The owner said on 2026-11-11: "do not miss any info if any stopped then recheck it again because my internet is not stable so please cautious". On 2026-11-09/11 there were three usage-limit outages and one network (ENOTFOUND) outage. Each one killed every agent and the workflows mid-run.

**Why:** the owner wants zero loss. An agent that dies holding a lock, or in the middle of an apply, silently leaves the shared state broken. The worst case so far was a heavy lock held for 14.7 h.

**How to apply:** after every outage, error notification or "Try again", run this checklist:
1. `ListAgents`: send a RESUME SendMessage to every failed agent, with its exact last step.
2. `heavy_lock.py status` and the queue: a `customwf-*` holder with no live job or container gets released and logged.
3. Check the detached runners (`scratchpad\runner-*.log`, the pid is alive) and `docker ps`.
4. Integration tree: `git diff --name-only` and untracked files. Look for half-applied work by comparing hashes against the agent's worktree, ignoring line endings.
5. Workflows: resume with `resumeFromRunId`; cached agents replay.
6. JOINT-LEDGER §5: read IFMS messages that may have queued while away.
7. Update RESUME-LEDGER and the JL before the turn ends.

Heavy runs only ever go through detached runners, so they survive an outage. Related: [[feedback-shared-heavy-lock]], [[project-handoff-2026-11-08]].
