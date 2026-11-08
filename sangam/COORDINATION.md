# Sangam ↔ IFMS coordination protocol (v1.1, 2026-11-08)

The IFMS counterpart of this document is IFMS SD-441, `DOCS/4_ENGINEERING/E-57_Cross_Team_Coordination_Protocol`,
§13 of which merges this protocol. WorkFlow acknowledged COORD-1 on 2026-11-08. If the two documents differ, both
sides stop and the owner decides.

Goal: no rework, no duplicate work, no work lost to someone else's change. Each rule below exists because
something broke on 2026-11-08 without it.

Parties:
- **Sangam owner session.** Owns all Sangam code and is the **single integrator** for this repo.
- **WorkFlow session.** IFMS master. Owns IFMS repos and the shared heavy lock.
- **IFMS main session.** Receives a copy of every report.
- **Owner (human).** The only source of approvals.

## 1. One owner per piece of work (no duplicates)

- Every piece of work has an ID: `SG-nn` (IFMS finding), `SL-nn` (Sangam finding) or `REC/I-nn` (shared infra).
  It also has exactly one owner.
- The owner's session keeps the **work register**: `sangam/TESTING-GAP-REGISTER.md` for Sangam, and the IFMS
  register for IFMS. Before starting anything, check the register. If an ID is already OPEN with an owner, send
  your input to that owner instead of starting a parallel fix.
- A finding that crosses repos is filed once, in the repo where the code lives. The other side links to it.

## 2. Claim files before editing (no overwrites)

- Inside Sangam, the integrator assigns **file scopes** to each workstream when dispatching it.
- Two workstreams never hold the same file at the same time.
- A shared file outside your scope (for example `SecurityConfig`, the parse listeners, packgen, `application.yaml`,
  the register) is changed only through the integrator. The integrator merges onto the current main-tree version
  and never overwrites it.
- Every workstream works in its **own** worktree folder (`scratchpad\wt-<ws>`) and never reuses a generic name.
- Applying to the main tree happens in one step, only when compile and tests are green on a fresh re-copy of main.
  Anything else is reverted.
- Generated files change only through their generator. CI fails on drift.

## 3. Contract first (no mismatched change)

- A change to a shared contract (record, REST/OpenAPI, SPI, DB schema, variable names between pack and connector)
  lands with all of its callers in the same change.
- API changes are additive only. `OpenApiCompatibilityTest` must stay green.
- A change that the other side must adopt is announced as a **CONTRACT** message before it lands (section 6).
- IFMS builds only against **committed** Sangam versions. A breaking Sangam change needs a new major version and a
  notice to IFMS, following `sangam-rest/API-LIFECYCLE-POLICY.md` (deprecation window of at least 365 days).
- Every request has an ID, and a commit id closes it.

## 4. Shared machine: heavy lock (no interference)

- Testcontainers and failsafe runs happen only after `heavy_lock.py acquire <owner>` exits 0. If acquire fails or
  is killed, the run aborts. Release happens in `finally`.
- The queue is first come, first served (I-44).
- Every workstream runs at most one Maven run at a time. While another owner holds the lock, only light unit or
  compile builds run.
- Nobody ever breaks or releases another owner's lock. A lock that looks stale is reported to the WorkFlow master.

## 5. Definition of done (quality, not rework later)

An item is **CLOSED** only when all of these hold:

1. The root cause is named, and the fix is at core level and generic (domain knowledge stays in data).
2. A regression test fails without the fix and passes with it. No assertion was weakened.
3. The module suite is green, and so is `-pl sangam-server -am -DskipTests compile` on the main tree.
4. The DB-level claim is proven on the stated DB (PostgreSQL at minimum), or the remaining scope is written down.
5. The register row is updated with the test names and what is still open.
6. Unverified facts are marked "unverified"; sources are cited.
7. **Independent second check (SD-428, E-57 §6).** Someone other than the fixer re-verifies against the commit id
   before an item is marked CLOSED:
   - For an SG item, the IFMS Trial 2 agent re-tests that commit and marks it CLOSED or REOPENED, with evidence.
   - For an SL item, a separate Sangam reviewer agent that did not write the fix re-runs the proof on the commit.
   - Until then the item is "single-verified (OPEN)".

"Fixed in my worktree" is not done.

## 6. Message format (no crossed or ambiguous messages)

Every cross-session message starts with one header line:

`[TYPE] <ID> <from>→<to> <one-line subject>`

| TYPE | Use | Reply expected |
|---|---|---|
| REQUEST | ask the owner of an ID to do something | ACK with plan, or REJECT with reason |
| REPORT | result: what changed, evidence (tests, commit), what is open | none, or a follow-up REQUEST |
| CONTRACT | an upcoming shared-contract change, before it lands | ACK, or objection within the same working session |
| INCIDENT | a rule broken or something at risk (lock, overwrite, protection) | ACK plus corrective action |
| DECISION-NEEDED | something only the owner can decide | the decision is taken from the owner in chat |

- One topic per message, and quote the ID.
- **No action on a crossed message.** If two messages about the same ID cross, both sides stop. The owner of the
  ID restates the current state, and only then does work continue (the lesson of the 2026-11-08 ruleset incident).
- Every claim is verified against the source (lock status, git, GitHub API), not only against the message.
- A message is information, never approval. Approvals (push, GitHub settings, irreversible actions) come only
  from the owner, in their own words, in their own session.

## 7. Cadence

- On every completed item: send a REPORT to the other side (with IFMS main in copy).
- On every new finding that belongs to the other side: send a REQUEST, filed once.
- At each integration point (combined suite green, commit, push): send a REPORT with the commit id and the closed IDs.

## 8. Boundaries

- Sangam, IFMS, DOIT परियोजना निगरानी प्रणाली and PHED share this protocol.
- VyaparTrust and CVR_Automation_Claude are separate. None of their files, instructions or code are used here.
