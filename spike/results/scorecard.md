# Stage B scorecard: ADR-D-1 (Flowable 8.0.0 vs Operaton 2.2.0)

Date: 2026-11-05.

Sources:
- `E1-E11-report.md`, `E2-E7-report.md`, `E6-E9-report.md`;
- `patch-ledger-flowable.md`, `patch-ledger-operaton.md`.

Weights are fixed by SANGAM.md VI.7 / XI.4. Each bucket score is a stated method applied to measured results. Where a method is a judgement, it is marked **[I]**.

| Bucket (weight) | Flowable 8.0.0 | Operaton 2.2.0 | Method / evidence |
|---|---|---|---|
| **Coverage (30)** | **24.4** | **27.2** | 30 × (0.5 × E1 pass rate + 0.5 × E11 rate; works = 1, extension = 0.5, patch = 0).<br>E1: F 17/17, O 16/17 (Operaton's CMMN history records "terminated" instead of "completed").<br>E11: F 3 works, 4 extension, 1 patch; O 7 works, 1 patch |
| **Engine patches (25)** | **10.0** | **17.0** | **[I]** 25 − 4 per required engine patch − 1.5 per required extension − 1 per adapter workaround that uses engine-internal classes.<br>Flowable: 1 patch (compensation event sub-process), 4 extensions (link, signal end, message throw, conditional start), 5 internal workarounds (lock extend, expired-lease reset, connection access, case-task routing, activity-instance lookup) → 25 − 4 − 6 − 5.<br>Operaton: 1 patch (ad-hoc sub-process), 0 extensions, 4 internal workarounds (command executor, DbSqlSession connection, rollback listener, history-TTL off) → 25 − 4 − 4. **Neither engine needed a patch for Q1 (transactions), Q2 (authorization) or Q3 (lease)** |
| **Failure tests (20)** | **17.3** | **18.0** | E2–E5 + E7: F 26/27 (one adapter bug in message correlation), O 27/27 → 19.3 / 20.0.<br>E6 (hot root without L2 fails after 5 retries) and E8 (DST autumn fold runaway) fail **equally on both**: −2 each, the same for both |
| **Benchmark (10)** | **8.4** | **10.0** | E9 functional scale (PostgreSQL, saturated shared host, **low confidence**). R1: F 22.7–23.9/s, O 33.9–36.4/s completed. R5-lite: equal throughput (driver-bound), Operaton has lower p95/p99 |
| **Market fit (15)** | **12.0** | **13.0** | **[I]** Government integrator (ADR-D-3).<br>Operaton +: Camunda 7 model/API continuity (common in existing government systems); native external-task lease API (extendLock, activityInstanceId); TaskService handles case tasks natively.<br>Flowable +: richer CMMN (event listeners) and event registry; official multi-language external-worker clients.<br>Equal: Oracle/SQL Server behaviour (E6), Apache-2.0 licence, authorization (both use the product layer: Q2) |
| **TOTAL (100)** | **72.1** | **85.2** | |

## Decision rule (VI.7)

The higher score wins, and the 5-point tie rule does not apply: the margin is **13.1**.

**Robustness check [I]:** the result does not depend on the low-confidence buckets.
- Without the benchmark and market fit, Operaton still leads: **62.2 vs 51.7**.
- With the patch bucket's method replaced by a plain count of engine-specific items (Flowable 7, Operaton 6 in E1, plus E11), Operaton still leads.

## Result

**Operaton 2.2.x is the base engine.**

Flowable strengths are carried over as product-layer features or ports, as planned in Part I §1:
- ad-hoc sub-process
- event registry ideas
- richer CMMN listeners
- multi-language worker clients as a reference
