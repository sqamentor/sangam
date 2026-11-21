# Spike archive (`spike/`, Stage B engine spike)

Owner directive of 2026-11-17, section 7. `spike/` is the Stage B spike that answered ADR-D-1 (Flowable 8.0.0 or
Operaton 2.2.0). It is **archived and unsupported**: not built by CI, not maintained, not scanned for new
vulnerabilities, not for production use. It stays in the tree for now; deleting it needs the owner's confirmation.

## Exact source commit

| Item | Value |
|---|---|
| Tag | `spike-archive-0d28f052` (created locally, not pushed; the owner signs and pushes tags) |
| Commit | `0d28f052fb4611c617ed0c09c1ded876fd259700` (the last commit before the archive change) |
| Tree of `spike/` at that commit | `819cc10baf6e71d4be30515d2ff0ab8bfa7d8f25` (332 files) |

The tag was made as a lightweight local tag. If the owner wants a signed annotated tag of the same name, delete the
local one first (`git tag -d spike-archive-0d28f052`) and create it on the same commit:
`git tag -s spike-archive-0d28f052 0d28f052fb4611c617ed0c09c1ded876fd259700 -m "spike/ before archive"`. Check that
`git rev-parse spike-archive-0d28f052^{commit}` prints the commit above. The tag must never be moved.

## Retrieval

```
git fetch origin tag spike-archive-0d28f052                       # once the owner has pushed it
git rev-parse spike-archive-0d28f052:spike                         # must print 819cc10baf6e71d4be30515d2ff0ab8bfa7d8f25
git show spike-archive-0d28f052:spike/GOV-PATTERNS.md              # one file
git archive --format=tar -o spike.tar spike-archive-0d28f052 spike # the whole folder
git worktree add ../sangam-spike spike-archive-0d28f052            # browse or build it as a tree
```

Until the tag is pushed, the commit is still reachable from `main` history, so `git show 0d28f052:spike/<path>` works
as well.

## Live inputs moved out of `spike/` (history kept with `git mv`)

These files were read by CI, tests or generators. They moved unchanged (same ids, evidence and provenance text; links
inside them that name other `spike/` files are historical and resolve through the tag). `git log --follow` shows their
earlier history.

| Old path | New path | Read by |
|---|---|---|
| `spike/results/TRACEABILITY-REGISTER.csv` | `sangam/docs/records/TRACEABILITY-REGISTER.csv` | `.github/workflows/governance.yml` (traceability job), `.github/scripts/traceability_gate.py` default |
| `spike/results/TRACEABILITY-REGISTER.md` | `sangam/docs/records/TRACEABILITY-REGISTER.md` | the register's summary; links to the CSV next to it |
| `spike/results/FLOWABLE-INTO-OPERATON.md` | `sangam/docs/records/FLOWABLE-INTO-OPERATON.md` | `sangam-server` `DecisionCitationGuardTest` (FIO D-n / FC-n citations) |
| `spike/results/IFMS-CENTRAL-REQUIREMENTS.md` | `sangam/docs/records/IFMS-CENTRAL-REQUIREMENTS.md` | `sangam-packs/tools/profilegen/coverage.py` |
| `spike/results/IFMS-STATES-REQUIREMENTS.md` | `sangam/docs/records/IFMS-STATES-REQUIREMENTS.md` | `sangam-packs/tools/profilegen/coverage.py` |
| `spike/results/IFMS-RESEARCH-DELTA.md` | `sangam/docs/records/IFMS-RESEARCH-DELTA.md` | `sangam-packs/tools/profilegen/coverage.py` |

## What still names `spike/` and why that is not a dependency

- Provenance text ("ported from `spike/...`", "Stage B `spike/...`") in code comments, READMEs, generated pack
  citations and model namespaces (`http://sangam.org/spike/...`). These are records of origin; nothing reads the path.
  Changing a namespace would change deployed model identity, so they stay.
- Citations to other `spike/results/*.md` research documents (for example `US-HEALTH-WORKFLOWS.md`,
  `SECTOR-WORKFLOWS.md`) inside generated packs. They are text only; `packgen` output is unchanged. They resolve
  through the tag if `spike/` is ever deleted.
- `.github/workflows/security.yml` excludes `spike/**` from CodeQL, `.gitleaks.toml` allow-lists
  `spike/results/E10.json`, `.gitignore` ignores spike run output. These are exclusions, not inputs, and stay while
  the folder exists.
