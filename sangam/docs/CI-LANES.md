# CI lanes: the three-level build strategy

Owner decisions 411, 412 and 413 (docs/records/OWNER-DECISIONS-2026-11-12.md).

| Level | Where | When |
|---|---|---|
| 1 | Local machine, under the shared heavy lock | Quick runs: expected time up to 30 minutes and lock queue wait up to 30 minutes |
| 2 | GitHub Actions, workflow `.github/workflows/ci-lanes.yml` on a `ci/**` branch | Long runs, or a lock queue wait over 30 minutes |
| 3 | AWS CodeBuild project `sangam-ci-lanes` (ap-south-1) | GitHub is backed up (oldest queued run over 30 minutes) or its minutes are used up |

`main` and release tags stay behind the owner-signed push gate. Lanes push only `refs/heads/ci/<lane>-<shortsha>`.

## Selection: `ci-lane.json`

One planner, `sangam/tools/ci/ci_plan.py`, serves both levels 2 and 3. Values come from the workflow_dispatch inputs
or the CodeBuild variables `CI_MODULES`, `CI_DBS`, `CI_CHUNKS` and `CI_UI`. If those are not set, the planner reads
`ci-lane.json` at the repository root. If that file is missing, it uses the defaults.

```json
{"modules": "sangam-engine", "dbs": "postgres", "chunks": "A-contracts", "ui": false}
```

- `modules`: the Maven modules the build job runs (`-pl <modules> -am`, unit tests on H2). An empty value runs the whole reactor.
- `dbs`: any of `postgres`, `oracle`, `sqlserver`, `sqlserver2025` and `mysql`. Each database gets one `it` matrix leg (`fail-fast: false`).
- `chunks`: the R7-it chunks `A-contracts`, `B-engine`, `B2-e2`, `C-e16a`, `D-e16bc`, `E-e17` and `F-platform`, or `all`. These are the same class lists as the local lane scripts. Each leg runs `verify -Psangam-db-it,sangam-it-only[,oracle-it] -Dsangam.db=<db> -pl sangam-server -am -Dit.test=...`. An empty value runs every IT of `modules`.
- `ui`: `true` runs `npm test` and the Playwright Chromium install with a spec listing. The full-stack e2e stays in `ui.yml`.

Database images are pulled by the digests that `db-matrix.yml` uses, and the pull time is printed in the job summary.

Vendor terms (decision 122): the Oracle and SQL Server legs run only when the repository variables
`SANGAM_ACCEPT_ORACLE_FREE_TERMS` or `SANGAM_ACCEPT_MSSQL_EULA` are set to `Y`. On level 3 the same names come from
the CodeBuild project environment. If a variable is not set, the leg fails and gives the reason; it never passes.
Only the owner sets these variables.

The workflow has `permissions: contents: read`, uses no secrets, and pins every action by SHA. It passes
`deploy/licence/workflow_guards.py` (pins, contexts, silent, curl, npx, npm-scripts and runtimes). Surefire and
failsafe reports are uploaded as the artifacts `build-reports` and `it-<db>-reports`.

## Helper: `sangam/tools/ci/route_build.py`

```
python -I sangam/tools/ci/route_build.py --lane r8-ci --modules sangam-engine --dbs postgres --chunks A-contracts --expected-min 40
python -I sangam/tools/ci/route_build.py --lane r8-ci --level github ...      # force a level
python -I sangam/tools/ci/route_build.py --lane r8-ci --github-exhausted ...  # route to AWS
```

- **Decide.** The helper reads `heavy_lock.py status x`. It estimates the wait as the number of waiters times the mean hold time of the recent `served` entries, plus what is left of the current holder's hold. It also reads `gh run list --status queued` for ci-lanes and compares both against the expected time and the 30-minute limit.
- **Level 2.** The helper makes a commit object of HEAD plus `ci-lane.json` with git plumbing; the work tree and the index are not touched. It pushes that commit to `refs/heads/ci/<lane>-<shortsha>` and refuses any other ref. It then waits with `gh run watch`, downloads the artifacts to `--out`, and prints each job's duration and the test counts. It exits 0 when everything is green and 1 when a job or test fails.
- **Level 3.** The helper runs `git archive` on the same commit, uploads it to `s3://sangam-ci-<account>/src/sangam-src.zip`, and runs `aws codebuild start-build --project-name sangam-ci-lanes` with the `CI_*` overrides. It polls the build, downloads the artifact (`reports/build`, `reports/it-<db>`), prints the counts, and estimates the cost. The buildspec is `sangam/tools/ci/aws/buildspec.yml`. It installs Temurin JDK 25.0.1 and runs the databases one after another.
- **Level 1.** The helper prints the decision only. The lane then runs its own script under the heavy lock.

Old `ci/*` branches can be deleted by the owner; they carry no product history.

## Push gate exemption (decision 411)

`tools/governance/push-approval.mjs check` lets a push through without a signed approval only when the ref matches
`refs/heads/ci/<name>` and the remote is `sqamentor/sangam` (https or ssh). It still refuses deletions and pushes
that are not fast-forwards. Each exempt push is written to the push ledger as `CI_EXEMPT`, with the commit and the
ref. Every other ref (main, release/*, tags, `ci`, `cix`) needs the owner-signed approval as before. The tests are
in `tools/governance/selftest-push-approval.mjs`.
