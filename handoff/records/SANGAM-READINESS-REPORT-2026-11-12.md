# Sangam readiness report, 2026-11-12 (integrator)

This report combines four read-only audits (A pending items, B plug-and-play, C UI, D continuity) and B11's fiscal fixes. I checked the audits' key claims myself against the code; the corrections are noted inline. Source reports are in this folder: AUDIT-A-PENDING, AUDIT-B-PLUGPLAY, AUDIT-C-UI, AUDIT-D-CONTINUITY and B11-FISCAL-EVIDENCE (all dated 2026-11-12).

## 1. Is all the code written?
**No.** The register has 223 rows:
- 141 rows have their code done, with tests written but not run. That is 138 plus SL-125, SL-126 and SL-127, coded today.
- 5 rows need only a test or install run: SG-02, SG-04, SG-13, SG-14 and SL-111.
- 5 rows are owner or IFMS actions: SG-12, SG-18, SL-75 P2, CI-NVD-OIDC and SL-07.
- 2 rows are decided or deferred: SL-114 (Db2) and SG-27.
- All 66 sub-table rows are done. FWT-D11 and FWT-D12 still await their second check in the test phase.

**Code still to write:**

| Id | What is missing | Verified by me |
|---|---|---|
| SL-102 | Money thresholds are free text (`goi.py:116`, `'2CR'`, `'5CR'`); they need a typed minor-unit amount | yes |
| SL-78 | `CrossTenantIsolationIT:205-209` never checks the results of two set-up calls | yes |
| SL-79 | The `QuotaPoolDeadlockH2Support:150` assertion depends on machine load | audit A |
| SG-15 | Round-trip reduction (74 commits and 43 tenant reads per lifecycle); throughput never measured | audit A |
| SL-58 | SQL Server 2025 / JDK 25 failure; root cause unknown | audit A |
| SG-39 | Private-ingress switch in Helm; waits on the owner's route decision | audit A |

**Not done in docs:**
- `deploy/README.md:48` and `:826` say JRE 21, but the image is 25 (verified).
- HANDOFF §6c is stale.
- The SL-116 and SL-117 READMEs are not written.
- The TS SDK README and the SUPPORT-MATRIX Java SDK line are stale.
- `SANGAM.md:6621` says 283 paths; there are now 879.
- Some status words in the register are misleading (SL-106, SL-107, SG-37, SG-38, SL-03, SL-37).

**Correction to audit A:** SL-117 is coded (`ScheduleTenants`, 4 test classes). The T-SCALE evidence is centralised in `sangam/perf/results/2026-11-08-tscale-15e4135`.

**Nothing has run on this tree.** Unit, system, integration (5 databases), acceptance and flow tests are all pending. The last green run was on an older commit.

## 2. Was anything lost in the account switch? Is everything in one folder?
**Nothing was lost** (audit D, 97%):
- All 17 lokendra.tester commits are on origin/main.
- Every file in every worktree is identical or superseded in the integration tree, already in git, or not source.
- The one deletion audit D could not trace, `LegacyTimeZones*`, is planned: `SANGAM.md:4332` records its removal under the SL-53 UTC rule (verified).

**Content is centralised; git state is not.**
- The integration tree holds 1,936 uncommitted changes. This is the single real risk.
- A fresh safety snapshot is at `snapshots\20261112-0923`: base commit, `tracked.patch` (6.8 MB, reverse-apply check clean), untracked tar (303 files), status, and a memory copy.

**Before the next account switch:**
1. Commit and push through the owner-signed gate.
2. Open sessions at `D:\GITHUB  Project\Custom WorkFlow`. Memory is keyed to that folder.
3. Copy any Temp logs that reports cite to D: before the Temp scratchpads are retired, and retire them only with owner approval.
4. The raw T-SCALE files (1.6 GB `ev.json`, the `.jfr` profiles) exist only in `D:\wt-scale`. Keep them, or accept their loss before deleting that folder.
5. After the push, remove `handoff/worktree-snapshots/**` from main. Everything in it is applied or superseded.

## 3. Plug-and-play readiness (apart from testing)
**Not complete for "any project."**

Ready in code, pending tests:
- Standalone server and REST (879 paths);
- the embedded engine library;
- five SDKs (Java, .NET, PHP, Python, TS), each covering a subset of the API;
- compose, Helm, VM jar, air-gap and the native worker;
- packs, profiles and onboarding;
- OIDC and row-level multi-tenancy;
- Tier-1 databases;
- the UI image.

Gaps:

| Gap | Evidence |
|---|---|
| No single starter: only the REST layer auto-configures (15 configurations); the engine and module wiring is 36 `*Wiring` classes inside sangam-server | verified |
| SDKs type about 280 of 879 paths; no in-memory test engine (R18); not published to any registry | audit B |
| No release: `0.1.0-SNAPSHOT`, 0 git tags; publishing to Maven Central, NuGet, PyPI, npm or Packagist is not set up | verified |
| Database-per-tenant: `TenantEngineRegistry` is not used by the server | verified |
| Control-plane and roll-up endpoints (`/cells`, `/routes`, `/rollup`) have no OpenAPI contract | audit B |
| No Go, Rust or Kotlin SDKs (Tier 2) | audit B |
| No "integrate Sangam into your app" guide | audit B |
| Hard host constraints: Spring Boot 4.1.2 only, Jackson 2.22 only, UTC JVM, BOM import order | audit B |

## 4. UI
**Core is complete. The UI as a whole is not** (audit C, 90%):
- Routed screens with loading, empty and error states exist for the inbox, cases, operations, admin, tenants, modeller, analytics, audit and portal.
- There are no TODOs and no mock data.
- About 80-88% of the API is covered.

Gaps:
- There is no browser or E2E test suite.
- Core flows have no component tests; 72 of the 89 tests cover the parity screens.
- Domain-module request bodies have no schema.
- There has been no accessibility audit, although `SANGAM.md:816` requires one.
- Several features have no screen: bulk ingest (SL-44), pack uninstall and rollback, tenant custom fields, module security defaults, privacy policy and onboarding promotions.
- Several modeller parts are listed as not built.
- Only English and Hindi exist.
- `/m/*` routes are hidden in the menu but have no route guard.

## 5. Path to a stable release
1. **Finish the code:** SL-102, SL-78, SL-79 and SL-58 (investigate), SG-15, SG-39 (after the owner decides), the B11 confirmations, and the docs. Estimate: 2-3 days, plus SG-15.
2. **Commit, then the owner-signed push**, then CI on JDK 25, 21 and 27.
3. **Run the test stages one by one:** unit, system, integration (5 databases), acceptance, flow. Fix every failure at its root, then write the E2E report. Estimate: at least 1-2 weeks; this is the largest unknown.
4. **Release 0.1.0:** tag, images, Helm package and notes. This also unblocks IFMS SD-454/SD-451.

Items in section 3 and the UI gaps in section 4 count only if the owner puts them in the v1 scope.

Estimates are judgement, not evidence. The first test stage will show the real size.
