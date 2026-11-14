# Sangam: code-complete plan (owner order of 2026-11-12)

## Order

1. Write all code end to end, with unit tests.
2. Update all documentation.
3. Push to `main` through the owner-signed gate.
4. Run tests one stage at a time: unit, system, integration, then acceptance.
5. Write the end-to-end report.

## Rules for every agent

These rules come from the owner and apply without exception.

- **Evidence-based coding.** Code a decision only at ≥97% confidence, and cite the evidence for it: file:line, log, register or ledger row, owner decision. Below 97%, write it up as a "needs confirmation" question for the integrator instead of coding.
- **Root-cause, generic fixes** (ADR-D-20). Data changes go only through the generators (packgen, profilegen, `generate_changelogs.py`, `generate_realm.py`, `gen_openapi.py`), and each generator's `--check` must pass.
- **Unit tests are written with every change.** A change without unit tests is incomplete. Red-to-green execution is deferred to the test phase.
- **Gate is compile only:** `.\mvnw.cmd -pl <your modules> -am -DskipTests -Dsangam.target=target-<ws> test-compile`. At the end of a work item, also run `-pl sangam-server -am`. Run at most 1 Maven build per agent at a time. No DB, Testcontainers or heavy-lock runs until the code is on `main`.
- **Edit the integration tree directly with targeted edits.** Re-diff each file just before editing, and keep its line endings. Never overwrite another agent's hunks. Never commit, stage or push.
- **Shared files are edited only after a claim.** Append a line to `D:\GITHUB  Project\sangam-handoff\FILE-CLAIMS.md` first (`<time> <agent/ws> CLAIM <path>` and later `RELEASE <path>`), and check that nobody else holds the claim. The shared files are:
  - `generate_changelogs.py` and its baselines
  - `SangamSchema.java`
  - every `pom.xml`
  - `sangam-api.yaml` and `gen_openapi.py`
  - `generate_realm.py` and the realm JSON
  - `application*.yaml`
  - `.gitattributes`
  - `.github/workflows/*`
  - `HANDOFF.md`
  - `TESTING-GAP-REGISTER.md` (integrator only)
- **Every report states:** the files and their SHA-256, the evidence and confidence for each decision, the questions still open, and the exact register-row text for the integrator to apply.

## Waves and owners

| Wave | Agent | Items |
|---|---|---|
| A (running) | SL-84 agent | SL-84 apply, then SL-86, SL-90, SL-91, then SL-87 |
| A | SL-88 agent | SL-88 apply; then SL-89 root cause (assigned after the apply) |
| A | SL-94 agent | SL-94 apply |
| A | SL-93 agent | SL-93 apply, then SL-96 |
| A | IFMS-WF agent | SL-57 set-up NPE (`JdbcLedgerStore.currencyOf` / `Jdbc.one`) |
| A | security agent | SL-70 (applied after SL-88), SL-72, SL-73, SL-95; then SL-71, SL-83, the JIT 409 race (new id) |
| B1 | money/ledger agent | SG-20, SG-01, SG-22, SG-23, SG-24, SG-25 (first check them against `15e4135`), SL-50, PACKS4 merge from `wt-packs4` |
| B2 | engine/FEEL agent | SL-04, SL-05, SL-06, SL-07, SG-19 test, ROUTING-INCIDENT contract test, SG-35-SANGAM regression, GP-13/D-TENDER compensation (`wt-comp`; new id), SL-54, SL-55, SL-59 |
| B3 | REST/packs agent | SL-77, SL-80, SL-40, SL-41, SL-42, SL-32, SL-33, SL-35, SL-44 (server side), AT-6 signed-plugin automation, APPROVE-401 2b |
| B4 | CI/deploy agent | CI-NVD-OIDC (`sangam-ci` role, `/shared/security/nvd-api-key`), SG-05 CI 2025 lane, SG-16 run provenance, SL-56 `.gitattributes`, SL-75 CI exclusions plus Dependabot, SG-38 production-start test, SG-37 relocation, SL-53 ADR-D-27, Q22 |
| B5 | SDK agent | stored-outcome completion, .NET and TS unit tests, TS ReDoS fix |
| C | docs | Register restatements (the 12 conflicts), the JL board, HANDOFF, READMEs (stale texts), `SANGAM.md` ADRs, CONTRACT notices to IFMS |
| D | stray-work scan | Centralise into the main folder (scan running); owner confirms each deletion |

## Owner decisions that block code

These go to the owner in batches: Q7 (quota leaf pool), Q8, Q9, Q10 (SG-03), Q12 (downloads), Q13, Q14, Q16, Q19, Q21, Q22, Q23, Q34 (SL-53a date), the SG-39 route, and the SL-07 semantics.

## Test-phase only (after the push)

SG-04 matrix rerun, SG-07, SG-13, SL-01, SL-12, SL-34, SL-37 residuals, SD-428 reviews, SL-78, SL-79, the database proofs for SL-84/88/93/94, IX8/AC-07, T-SCALE, SL-58.
