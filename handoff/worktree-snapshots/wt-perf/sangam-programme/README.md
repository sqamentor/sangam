# sangam-programme (GENERIC-CASE-PROGRAMME workstream)

Capability **N15**: targets, indicators, milestones and periodic returns
(`spike/results/CAPABILITY-COVERAGE.md`). The scope covers:

- programmes, schemes, components and projects as one configurable hierarchy;
- indicators and hierarchical targets defined in configuration (units, frequencies, roll-up rules, formulas);
- milestones;
- progress returns with validation and multi-level approval;
- roll-up along the org/geo hierarchy;
- physical versus financial progress;
- variance and defaulter alerts;
- a dashboard feed through ports.

Base package `org.sangam.programme`. Domain knowledge (which indicators, units, kinds, reporting levels, thresholds)
exists **only as configuration data**; there are no country, department or sector names in code (SANGAM.md X.0e).

Build: `cd sangam && mvnw.cmd -q -B -DskipTests -pl sangam-programme -am install` (compiles, 29 source files).
Phase rule: code complete. Tests come in the testing phase; none were written or run.

## Wiring

```java
ProgrammeModule pm = new ProgrammeModule(dialect, EngineTx.transactions(engine), new Outbox(dialect),
        new ProgrammeModule.Ports(hierarchyPort, campaignStarter, dmnReturnValidator, notifier, dashboardPort, financialSource));
ProgrammeModule.install(pm);                                  // used by the BPMN delegates
ProgrammeRestPort api = new ProgrammeRestAdapter(pm, securityAuthorization);   // VI.4 required
```

Services take the caller's `Connection` and never commit it (product-layer rule). Every time comes from the database
clock (A-1).

## File-by-file coverage

Paths under `src/main/java/org/sangam/programme/`.

| File | Covers |
|---|---|
| `ProgrammeSchema.java` | DDL in dialect tokens (A-15) plus `{dec}` (NUMBER/DECIMAL(24,6)) and `{date}`. Tables: PG_CONFIG, PG_PROGRAMME, PG_INDICATOR, PG_PERIOD, PG_TARGET, PG_MILESTONE, PG_RETURN_DEF, PG_RETURN, PG_RETURN_VALUE, PG_RETURN_FINDING, PG_RETURN_EVENT, PG_ACHIEVEMENT, PG_FINANCIAL, PG_ALERT, PG_DASHBOARD_SNAPSHOT, with indexes. All tables are tenant-first |
| `ProgrammeException.java` | Stable codes with HTTP status for RFC 9457 mapping (R28) |
| `ProgrammeModule.java` | Wiring, port defaults (air-gapped §1.9), static holder for delegates |
| `support/Db.java` | JDBC/JSON helpers; canonical JSON with decimals as strings (R33); optimistic `row_version` |
| `config/IndicatorDefinition.java` | N15 `Indicator`: unit, frequency (WEEKLY…ANNUAL), kind (PHYSICAL/FINANCIAL/OUTPUT/OUTCOME), aggregation (SUM, AVG, MIN, MAX, LAST, WEIGHTED_AVG, FORMULA), direction, cumulative, decimals, validation rules, parent indicator, weight |
| `config/ReturnDefinition.java` | N15 `ReturnDefinition`: C03 form key/version, items, cross-indicator rules, validation DMN key, due offset, reminder offsets, approval levels, reporting level, per-office process key |
| `config/ProgrammeConfig.java` | Configuration parsing and validation: units, programme kinds and allowed parents, indicators, returns, fiscal-year start, variance thresholds, physical-progress basis. Checks formula references, formula and parent cycles, and weight indicators |
| `indicator/FormulaEvaluator.java` | Safe arithmetic/comparison parser (no script engine, D-8): + - * /, comparisons, && \|\|, min/max/abs/round/div/coalesce/if. Null propagation; bounded length and depth |
| `indicator/IndicatorRegistry.java` | Versioned, hash-pinned configuration publish (R43 style). Indicator and return versions are immutable; pinned versions stay readable for old returns |
| `period/PeriodService.java` | N15 `ReportingPeriod`: deterministic ids; generation per fiscal year with a configurable start month; due dates; OPEN/CLOSED/FROZEN with a reason on re-open; pro-rata share |
| `programme/ProgrammeService.java` | Programme/scheme/component/project hierarchy (kind from configuration), lifecycle, tree and subtree, paged list under the VI.4 predicate with capped count (A-16) |
| `target/TargetService.java` | N15 `Target` per period and org unit. Lifecycle DRAFT → PROPOSED → APPROVED / REJECTED, revision versions with a reason, maker ≠ checker. Allocation down the hierarchy by explicit values or weights, with exact-sum rounding and a parent-sum guard |
| `milestone/MilestoneService.java` | N15 `Milestone`: planned and actual dates, weight, percent, dependencies (DONE only after its dependencies), evidence refs (N05), delay detection, weighted physical and planned progress |
| `returns/ValidationEngine.java` | Validation: required, min/max, integer/decimals, non-decreasing cumulative, max ratio of target, definition rules over `key`/`prev.key`/`target.key`, external DMN through `ReturnValidator` |
| `returns/ReturnService.java` | N15 `ReturnSubmission`: DUE → DRAFT → SUBMITTED → VALIDATED → ACCEPTED (n distinct approvers; submitter excluded) / RETURNED → FROZEN; re-open with a reason. **GP-30** campaign per period, **GP-09** one process per office, idempotent. Defaulter list, reminder schedule (C15), state history, outbox events, content hash of submitted values, late flag |
| `rollup/RollupService.java` | Consolidation up the org/geo hierarchy: leaf rows from accepted returns and manual entry, then bottom-up aggregation per rule and FORMULA recomputation per node. Cumulative-to-date rules; reported values win over rollup; drill-down and time series |
| `progress/ProgressService.java` | Physical vs financial progress: milestones or weighted PHYSICAL achievement against target; expenditure/allocation, release and utilisation, and the gap. Financial entry, import (`FinancialSource`, e.g. sangam-ledger) and hierarchy rollup |
| `progress/VarianceService.java` | Variance alerts against the pro-rata approved target (warn/critical thresholds, adverse direction), DEFAULTER and MILESTONE_DELAY alerts, de-duplication, escalation in place, acknowledgement, outbox event |
| `dashboard/DashboardFeed.java` | C14 feed: snapshot per programme, period and node (progress, return pendency, alerts, indicator values). Stored for queries; published on change on outbox destination `programme-dashboard` and through `DashboardPort` |
| `ports/ProgrammePorts.java` | Outbound ports: `HierarchyPort`, `CampaignStarter`, `ReturnValidator`, `Notifier`, `DashboardPort`, `FinancialSource`, each with a none/flat default |
| `rest/ProgrammeRestPort.java` | REST port (routes below) |
| `rest/ProgrammeRestAdapter.java` | Port implementation: one transaction per call. VI.4 object checks inside the transaction; lists through the compiled `visible` predicate (A-3, no post-filtering); tenant from the identity (VI.4 rule 6) |
| `delegate/DelegateSupport.java` | Delegate inputs (field, then variable), tenant, actor, engine connection (Q1) |
| `delegate/ReturnCampaignDelegate.java` | Timer-driven campaign start (GP-30/GP-09); generates missing periods |
| `delegate/ReturnStateDelegate.java` | `op` = SUBMIT / VALIDATE / ACCEPT / RETURN / FREEZE / REOPEN, with outputs for gateways |
| `delegate/RollupDelegate.java` | Indicator and financial rollup and dashboard refresh |
| `delegate/DefaulterReminderDelegate.java` | Reminders and defaulter alerts |
| `delegate/VarianceDelegate.java` | Variance alerts, with a `varianceCritical` flag for escalation |
| `delegate/MilestoneDelayDelegate.java` | Delay detection and alerts |

## REST routes (WS-REST mounts them under `/api/v1`)

| Route | Port method | Permission |
|---|---|---|
| `PUT /programme-config`, `GET /programme-config/history` | publishConfig, configHistory | ADMIN on `PROGRAMME:*` |
| `POST /periods/generate`, `GET /periods`, `POST /periods/{id}/state` | generatePeriods, listPeriods, changePeriodState | ADMIN / READ `*` |
| `POST /programmes`, `GET /programmes`, `GET/PATCH /programmes/{id}`, `POST /programmes/{id}/status`, `GET /programmes/{id}/tree` | create/list/get/update/status/tree | MODIFY parent (ADMIN `*` at top), READ (list predicate alias `p`), APPROVE for status |
| `PUT /programmes/{id}/targets`, `GET /programmes/{id}/targets`, `POST /targets/{id}/propose\|approve\|reject\|revise\|allocate` | target methods | MODIFY; APPROVE for approve/reject |
| `POST /programmes/{id}/milestones`, `PATCH /milestones/{id}`, `GET /programmes/{id}/milestones` | milestone methods | MODIFY / READ |
| `POST /returns/campaigns` | startCampaign | START on programme |
| `GET /returns`, `GET /returns/{id}` | listReturns (predicate alias `r`), getReturn | READ on `PROGRAMME_RETURN` |
| `PUT /returns/{id}/values`, `POST /returns/{id}/submit` | saveReturnDraft, submitReturn | WORK |
| `POST /returns/{id}/validate\|accept\|send-back\|freeze`, `POST /returns/{id}/findings/{seq}/ack` | … | APPROVE |
| `POST /returns/{id}/reopen` | reopenReturn | ADMIN |
| `GET /returns/defaulters` | defaulters | READ on `PROGRAMME_RETURN:*` |
| `POST /programmes/{id}/achievements`, `POST /programmes/{id}/rollup`, `GET /programmes/{id}/achievements[/children\|/series]` | achievement methods | MODIFY / READ |
| `PUT /programmes/{id}/financial`, `POST /programmes/{id}/financial/import`, `GET /programmes/{id}/progress[/children]` | financial/progress | MODIFY / READ |
| `POST /programmes/{id}/variance-check`, `GET /programme-alerts`, `POST /programme-alerts/{id}/ack` | alert methods | MODIFY / READ |
| `POST /programmes/{id}/dashboard/refresh`, `GET /programmes/{id}/dashboard[/children]` | dashboard methods | MODIFY / READ |

Mutations carry `Idempotency-Key` (R41), which the REST layer's idempotency store handles. Optimistic versions
(`expectedVersion`) map `CONFLICT` to 409.

## Integration notes

1. **Schema (WS-ENGINE / sangam-persistence).** Copy `ProgrammeSchema.ddl(dialect)` into the per-dialect Liquibase
   changelogs. `create` is for development only. Expand the module tokens `{dec}` and `{date}` (see `expand`).
2. **HierarchyPort (integrator).** Bind it to WS-SECURITY `OrgUnitService` (children, parent, level, nodes at a level
   under a root, responsible seat) or to a sangam-config classification `Tree` for geography. Without a binding the
   default is `FLAT` (single node).
3. **ResourceRegistry (WS-SECURITY).** Register the following resource types:
   - `PROGRAMME`: table `PG_PROGRAMME`, alias `p`, id column `programme_id`, tenant `tenant_id`;
   - `PROGRAMME_RETURN`: table `PG_RETURN`, alias `r`, id `return_id`; useful scope attributes are `org_unit`, `return_key` and `programme_id`.

   The id `*` stands for module-wide grants (configuration, periods, defaulter lists).
4. **ReturnValidator (integrator).** Bind it to Operaton DMN: evaluate `validationDmnKey` with the inputs map and map the
   output columns `indicator`, `severity`, `code` and `message` to `ExternalFinding`.
5. **CampaignStarter (integrator).** Bind it to the engine adapter (`startProcessInstanceByKey` with tenant and business
   key `returnKey/periodId/orgUnit`) inside the same command, so the return row and the instance commit together (Q1).
6. **Notifier.** Bind it to the outbox and the integration connectors (exactly once).
7. **Outbox destinations for WS-INTEGRATION's relay.**
   - `programme-events` carries `programme.return.*` and `programme.alert.*`.
   - `programme-dashboard` carries `programme.dashboard.snapshot` for C14 / WS-UI analytics.

   `DashboardPort` is an optional direct push.
8. **FinancialSource.** Optional binding to sangam-ledger consumption (allocated, released, committed, expended per head).
9. **Packs (WS-PACKS)** ship two things:
   - the configuration JSON (indicators, returns, kinds);
   - BPMN using the delegates: a timer start runs `ReturnCampaignDelegate`, a per-office process runs a form task, then `ReturnStateDelegate` SUBMIT, VALIDATE and ACCEPT, with a gateway on `returnAdvanced`; daily timers run `DefaulterReminderDelegate` and `MilestoneDelayDelegate`.
10. **Form versions.** `ReturnDefinition.formKey/formVersion` refer to sangam-flowable-ports forms (FC-39). The REST layer
    renders the form; values are saved through `saveReturnDraft`.

## Dependencies

| Dependency | Scope | Licence |
|---|---|---|
| `org.sangam:sangam-core`, `org.sangam:sangam-flowable-ports` (Paging, EngineTx) | compile | Apache-2.0 |
| `org.operaton.bpm:operaton-engine` 2.2.0 (BOM) | compile | Apache-2.0 |
| `com.fasterxml.jackson.core:jackson-databind` (BOM 2.22.3) | compile | Apache-2.0 |
| `org.slf4j:slf4j-api` | compile | MIT |

No new third-party dependency.

## Gaps (explicit)

- Not tested (phase rule). The behaviour tests come in the testing phase.
- Weekly and fortnightly periods are fiscal-year aligned; the last period absorbs the remaining days, so there are no ISO weeks.
- Targets allocated to non-direct descendants: allocation goes one level at a time (repeat per level).
- Alert de-duplication keys are permanent: an acknowledged alert is not re-raised for the same key.
