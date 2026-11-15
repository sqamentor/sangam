# Archive index

Historical material that is no longer part of the `main` tree. Archived code is a record of past work: it is **not
maintained, not scanned for new vulnerabilities and not supported for production use**. Do not build or deploy from it.

| Archive | Tag | Commit | Original path | Removed from `main` |
|---|---|---|---|---|
| Handoff records and worktree snapshots (2026-11-08 and 2026-11-15) | `archive/handoff-2026-11-15` (annotated, tag object `8db5bd79eb1a1e8f450d5b5a999fa84160c4e7ce`) | `a168a54246d03eb78559089f094be1e73c19b0f5` | `handoff/` (16,203 files) | 2026-11-15 |

## What it holds

- `handoff/worktree-snapshots/` and `handoff/worktree-snapshots-2026-11-15/`: byte-for-byte copies of files from private
  development worktrees that were never merged as they stood (earlier versions of lane work, scripts, SQL, patches).
- `handoff/records/`: ledgers, file claims, sync boards, owner decisions, compliance notes, statutory evidence, staged and
  parked lane code, the joint Sangam-IFMS ledger and plan.

The records that active code still cites were copied to `sangam/docs/records/` before removal.

## Retrieval

```
git fetch origin tag archive/handoff-2026-11-15
git show archive/handoff-2026-11-15:handoff/records/OWNER-DECISIONS-2026-11-12.md      # one file
git archive --format=tar -o handoff.tar archive/handoff-2026-11-15 handoff               # the whole folder
git worktree add ../sangam-archive archive/handoff-2026-11-15                            # browse it as a tree
```

The tag is annotated and must never be moved or overwritten; a new archive gets a new tag and a new row here.

## Scanner findings in the archive

While `handoff/` was on `main`, code scanning reported findings inside it (Gitleaks 89, OpenSSF Scorecard
PinnedDependencies 68, and most of Scorecard's dependency advisories). Removing the folder takes those findings out of
the active tree. **They were archived, not fixed.** The secret-scan triage of those findings is recorded in
`sangam/docs/security/2026-11-15-secret-scan-triage.md`; its conclusion (no credential) is provisional as stated there.
If a real secret is ever found in the archive, the secret must be revoked: archiving is not remediation.
