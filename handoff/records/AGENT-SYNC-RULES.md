# Agent sync rules: PERMANENT, for every wave and every agent (owner order 2026-11-12)

The owner requires every agent to stay in sync with every other agent at all times, so that there is no issue, gap, miss, crossed direction, double work or wasted effort. These rules apply to Wave 2, all later waves, the test phase, and every auditor or helper agent. Every agent brief must point here. An agent that has not read this file must not start work.

## The board
- The active board is `D:\GITHUB  Project\sangam-handoff\WAVE<N>-SYNC-BOARD.md` for the current wave (Wave 2: `WAVE2-SYNC-BOARD.md`). The integrator creates each new wave's board and carries over every open entry.
- Each entry is one line, newest at the bottom: `UTC time | lane/worker | TYPE | what | consumers or target lane`.
- TYPE is one of CHANGE, DONE, REQUEST, ACK, BROKEN or INFO.

## Rules
1. **Read before starting.** Read the board, `FILE-CLAIMS.md`, the carry list, the register row and the master backlog row before you start any piece of work.
2. **Never build twice.** If someone already built or is building the thing (a helper, formula, pool, table, guard, screen or test), reuse it or coordinate. Never write a second copy. Duplicates found later count as defects (see SL-164).
3. **CHANGE before a cross-module change.** Before you change anything other modules depend on, post a CHANGE first. That covers a public or protected signature, constructor, bean, table or column, config key, REST or AsyncAPI shape, event name, file another lane imports, or emitted label or format.
   - Give the old and new shape, a grep of every consumer (file:line), and the lane that owns each consumer.
4. **Never break another lane silently.** If a consumer is in another lane's file, add an overload (keep the old signature) or post a REQUEST to the owner. Change the consumer yourself only when you have been granted that file.
5. **DONE on landing.** When the change lands, post DONE with the SHA-256 of each changed file. Do the same when you answer a REQUEST.
6. **REQUEST, then ACK.** When you need something from another lane, post a REQUEST naming the owner lane. The owner checks the board at every step and answers with ACK, then DONE.
7. **BROKEN, not workarounds.** Gates run on the live tree. If another lane's change breaks your gate, post BROKEN with the error line and the owner lane, and keep working on what you can. Never work around it on a copy, and never edit their file.
8. **Claims.** CLAIM a file in `FILE-CLAIMS.md` before editing it, and RELEASE it with the SHA-256 afterwards. A file without an owner gets one through the integrator before anyone edits it.
9. **Owner decisions.** Read them on the board as INFO entries and in `OWNER-DECISIONS-*.md`. Never act against one. If evidence contradicts a decision, stop and report; do not override it.
10. **Integrator duties.**
    - Read the board after every report.
    - Route every open REQUEST and BROKEN to its owner.
    - Close-out gate: a wave is not checkpointed while any CHANGE has no DONE, any REQUEST is unanswered, any BROKEN is unresolved, or any lane holds a claim.
11. **The usual house rules still apply.** Evidence at 97% or higher, root-cause fixes, write tests but don't run them until the test phase, no downloads without approval, no commit/push/stash by agents.

## Why
Real misses happened when agents were not synced:
- **SL-163:** the UI did not send a newly required field.
- **PortsWiring break:** a constructor changed without updating its consumer.
- **SL-164:** four copies of one formula.
- **Inactive wiring:** six Wave 1 features shipped with no wiring.
