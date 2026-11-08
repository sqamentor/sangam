# sangam-ui

The Sangam web UI is one React 18 + TypeScript + Vite single-page app. It has role-based areas: tasklist, operations console, administration, modeller, analytics and process mining, audit, and the citizen portal. Every premium UI feature of Camunda 7/8, Flowable and Operaton is free here (ADR-D-14). Owner: WS-UI.

## Build and run

```
npm install          # wrapped in the shared heavy-build lock on the build machine
npm run build        # tsc --noEmit, then vite build → dist/
npm run lint         # tsc + i18n key check + no-CDN check
npm run test         # vitest --run (pure logic tests; not run in the coding phase)
npm run licences     # lists every installed package with its licence; fails on anything unexpected
npm run dev          # vite dev server on :5173, proxies /api and /engine-rest to SANGAM_BACKEND (default http://localhost:8080)
```

**Build result (2026-11-04):**
- `tsc` passes with zero errors in strict mode.
- `vite build` passes.
- `i18n:check` passes: 1,413 keys, identical in `en` and `hi`.
- The no-CDN check passes.
- The licence check passes: 243 packages and 0 unexplained.

Heavy areas are lazy-loaded chunks: the editors chunk (bpmn-js, dmn-js and form-js) is about 1.46 MB, or 432 KB gzipped, and loads only in the modeller.

## Runtime configuration (WS-DEPLOY contract, `deploy/README.md` §10)

At boot the app reads `/config.json` (`src/config.ts`), so nothing environment-specific is baked into the bundle.

The contract fields are:
- `apiBase` (default `/api/v1`, same origin);
- `oidc.issuer` and `oidc.clientId`;
- `defaultLocale` (`en` | `hi`);
- `locales`;
- `environment`.

The UI also reads these optional extras:
- `authMode` (`oidc` | `dev`). Dev-token mode is used only when `authMode` is `dev`, or when `environment` is `dev` and no mode is given.
- `devToken`.
- `oidc.scope`, `oidc.redirectPath` and `oidc.postLogoutPath`.

`public/config.json` is the dev default. The UI image replaces it.

## Cross-cutting

| Concern | Implementation | Files |
|---|---|---|
| OIDC login (R50, R67) | oidc-client-ts, authorization code + PKCE, silent renew, tokens kept in sessionStorage only | `src/auth/AuthProvider.tsx`, `src/areas/auth/SignIn.tsx` |
| Dev token mode | A static token for the server's dev profile, with a warning banner | same |
| Seat context and "acted for" (S02, IX.5 rule 4) | Seat switcher in the header. The contract headers are `X-Sangam-Seat` for the substantive seat and `X-Sangam-Acting-For-Seat` for an additional charge or leave substitute | `AuthProvider.tsx`, `app/Layout.tsx` |
| Typed API client (ARCHITECTURE §4) | Covers: Bearer auth; `Idempotency-Key` on every mutation, reused on a safe retry (R41, CX-4); problem+json; `X-Total-Count` / `X-Total-Count-Capped` ("more than N", A-16); ETag / If-Match; `/engine-rest` base | `src/api/client.ts` |
| Contract adapters | Contract shapes are mapped to view models, tolerating proposed extra fields. R33 TypedValue maps are converted both ways | `src/api/adapters.ts`, `src/api/endpoints.ts` |
| i18n Hindi + English (R54, T32) | i18next. All strings live in `src/i18n/locales/{en,hi}.json`. There is a language switch in the banner and on sign-in. Number and date formats follow `en-IN` / `hi-IN`. `scripts/check-i18n.mjs` fails on any missing key; dynamic keys are listed in `scripts/i18n-dynamic.json` | `src/i18n/*` |
| Government fonts, air-gapped | Hindi uses the central-government font Sakal Bharati (C-DAC GIST, OFL-1.1), served from `public/fonts/gov/`. `@fontsource/noto-sans` and `@fontsource/noto-sans-devanagari` (OFL-1.1) are bundled into `dist/assets` as the fallback. The language → font map puts the state-government font first, then central, then OSI. Per-tenant override comes from `/config.json` `fonts` or `/fonts/site/fonts.json`. See [FONTS.md](FONTS.md) | `src/fonts.ts`, `src/main.tsx`, `FONTS.md` |
| No CDN at runtime | The CSP meta allows `'self'` only. `scripts/check-no-cdn.mjs` scans `index.html` and `dist` | `index.html`, `scripts/check-no-cdn.mjs` |
| Accessibility (GIGW / WCAG 2.1 AA, R55) | See the list below the table | `components/ui.tsx`, `styles.css` |
| State contract (X.4.7.2) | Covers: loading after 10 s ("Still loading"); empty states that say why; 403 and 404 shown identically ("Not available for your current seat") with a seat-switch hint; stale banner with field diff and submit blocked until acknowledged (CX-2); conflict; partial success split into done / pending / uncertain; no stack traces (R28) | `components/ui.tsx`, `tasklist/TaskDetail.tsx` |
| Every operator action audited (R63, R42) | `ReasonDialog`: mandatory reason, server consequence preview, four-eyes and limit notices | `components/ui.tsx` |
| bpmn.io watermark (LIC-8 / PK-11) | bpmn-js, dmn-js and form-js-editor render their "powered by bpmn.io" link, and no CSS hides `.bjs-powered-by`. Forms at runtime (staff and citizen) use Sangam's own Apache-2.0 renderer for the form-js schema subset. This settles the PK-11 question: citizen screens carry no watermark, and the form builder keeps it | `components/Editors.tsx`, `components/BpmnDiagram.tsx`, `components/DmnView.tsx`, `components/FormRenderer.tsx` |

Accessibility measures:
- skip link and landmarks;
- focus moved to the page `h1` on every navigation;
- labelled fields and an error summary with links to the fields;
- real `<table>` elements with captions and `aria-sort`;
- native `<dialog>` for modals;
- roving-tabindex tabs;
- keyboard tree for the org hierarchy;
- `aria-live` announcements;
- a data-table alternative for every diagram and chart;
- AA contrast in light and dark themes, with a visible focus ring;
- `prefers-reduced-motion` respected;
- a 44 px-friendly layout that is responsive down to mobile.

## Screens implemented (file by file, with requirement ids)

### Work (tasklist, R76 / EC-01 / PF-18 / PF-33)

- **`areas/tasklist/TaskInbox.tsx` (S03).**
  - Sections: My seat, Acting for, Pool, Escalated, Returned to me.
  - Counts, SLA filter, sort, capped totals.
  - Claim, and bulk claim of up to 50 ("38 of 40 claimed").
  - Bulk approve as an R64 batch (D-34).
  - A "N new tasks — refresh" notice that never reorders rows under the cursor.
- **`areas/tasklist/TaskDetail.tsx` (S06–S09, R47, VI.4 rule 5, R43).**
  - Material values with "changed since last seen" markers, and rule evidence.
  - Maker-checker / SoD hints (IX.5 rule 5) that disable Approve with the reason.
  - Decisions: approve, forward, seek clarification, reject (coded reasons), reassign, delegate, complete.
  - Return to a chosen earlier level with reasons.
  - Resubmit through `POST /process-instances/{id}/resubmit`, which creates version n+1.
  - "Put up to" seat selection (S07) with a workload, leave and out-of-hierarchy warning, and a mandatory reason when not choosing the proposed seat.
  - Approval declaration bound to payload hash + version.
  - Stale / APPROVAL_INVALIDATED handling with a diff.
  - Draft save through `/forms/drafts` with If-Match.
  - Attachments with checksum and scan status; comments.
  - Version history with an any-to-any diff; lifecycle log (FC-52).
  - "Acted for" notice; partial-success summary.
- **`areas/tasklist/StartAndCases.tsx`.**
  - Start catalogue for processes and cases (R76).
  - Case workspace (EC-01, UP-09): CMMN plan tree with stages, milestones, discretionary items, manual start, disable / re-enable, complete and terminate; documents; comments; participants including external ones.
  - Reactivation as a linked new case (FC-59).
- **`areas/tasklist/MyWork.tsx`.**
  - My delegations and leave (S14), with no self-approval.
  - Notification centre (S13); Government Message items are flagged.
  - Field visits (S15, EC-16): offline queue with stable idempotency keys and sync when back online.

### Operations console (R63, R64, O01–O09; PF-01…06, 09, 10, 12, 13, 17, 43, 44; EC-11, EC-12, EC-13, EC-20)

- **`areas/ops/OpsDashboard.tsx` (O01, PF-13, FC-38).** Node health, backlog per topic, outbox use vs budget with drain time, incidents, reconciliation by age, SLA breaches, notification failure rate, job and partition health, security-incident clocks, engine metrics.
- **`areas/ops/Processes.tsx` (PF-01, PF-02, PF-17).**
  - Definition list.
  - Definition view with three overlays: running tokens, history heatmap and duration (bpmn-js).
  - Click an activity to filter instances; search instances.
  - Selection or "all matching" → batch (retry, suspend, activate, cancel, set variables, delete, restart, migrate), with a server count preview.
  - Suspend or activate a definition.
- **`areas/ops/InstanceDetail.tsx` (PF-01, 03, 05; FC-01, 15, 17, 20, 31/32; D-32, D-35).**
  - Diagram with live tokens; activity tree; variables (edit with a reason, masked fields).
  - History tabs: audit, job, external task, variable changes, user tasks, decisions, called instances.
  - Entity links.
  - Suspend / resume with deadline re-dating (D-32).
  - Business cancel vs technical delete with the O08 consequence preview.
  - Token modification with a preview; move across a call activity (FC-17); inject a task (FC-15).
  - Ad-hoc sub-process start / complete (FC-01).
  - Message to one branch (D-35) or a signal.
  - Restart from a chosen activity (PF-05).
- **`areas/ops/IncidentsRecon.tsx`.**
  - Incident workbench (O02): filter, bulk retry as a batch.
  - Incident detail (O03): consequence preview, claim, notes, stack trace for platform operators only (R28).
  - **Reconciliation console** (O04 / O05) for UNKNOWN_OUTCOME, LATE_RESULT_AFTER_DELETE and missing cancel-acks:
    - evidence files plus an evidence description;
    - confirm success with the result;
    - retry with the same operationId (class a/b only, after a lookup);
    - mark failed;
    - an SoD block (resolver ≠ initiator / approver) and a four-eyes notice.
- **`areas/ops/BatchMigration.tsx`.**
  - Batches (R64, PF-06): progress, pause / resume / cancel, four-eyes approve / reject, failure items.
  - **Migration** (PF-04, FC-18, EC-12, R48):
    - generate a plan;
    - 1→1, 1→N and N→1 mappings;
    - JSON migration document import / export;
    - validate, then a dry-run count;
    - pre-hook and post-migration modification;
    - execute as a batch.
- **`areas/ops/OpsMisc.tsx`.**
  - Case instances (PF-12) and cross-definition instance search.
  - Decisions (DMN view, evaluate, decision-instance history).
  - Job monitor (retries, due date, execute); outbox / inbox repair (O09).
  - Workers and topics (I01, FC-36/37).
  - **Event registry** (FC-02, FC-03, R83): channels with key / tenant detectors and dedup window; event definitions with correlation parameters and unique start; test send as a CloudEvent.
  - Notification / DLT failures (O06); deployments.
  - History cleanup / retention (PF-10, D-13, R29/R61).
  - **Sandbox debugger** (R80, EC-13, FC-44): breakpoints, step, fire timers, edit variables. The server refuses it on production.
  - Operator audit log (PF-09, PF-52, EC-20).

### Administration (A01–A11, IX.5, VI.4)

`areas/admin/Admin.tsx` covers:
- **Tenants** (A01, PF-49, EC-17): validate, activate, suspend.
- **Organisation hierarchy** (A02): keyboard tree, as-of date, add / edit, effective-dated move, CSV import, no deletion of units with history.
- **Seats.**
- **Postings / additional charge / leave substitute** (A03): effective-dated, with an impact preview of open tasks and SoD conflicts, approval, relieve.
- **Delegation-of-powers DMN tables** (A04): dmn-js editor, test cases, gap / overlap / hit-policy check, submit, and approval by someone other than the author with an effective date.
- **Policies, allow / deny** (A08): deny wins, conditions, "explain this decision".
- **SoD exceptions** with expiry and higher-authority approval.
- **Users and groups**, with membership.
- **Identity providers** (A05, R67): OIDC / SAML 2.0 / LDAP, test login before activation, LDAP sync.
- **Service principals** (I03): secret shown once, rotate, revoke.
- **Service registry** (R71, EC-07, PF-39), with capability class.
- **SLA models** (R79, EC-08, PF-40).
- **Routing strategies** (R82).
- **Templates**: DLT / e-mail / document (A09, EC-18).
- **Conversation channels** (R81, EC-15, PF-47).
- **AI agent definitions** (R72, EC-14, PF-28/46): guardrails, budgets, mandatory human review.
- **Retention classes** (A11).

### Modeller (R69, R43, R80; PF-21, 22, 23, 36, 37; EC-04, EC-05, EC-21; A06 / A07; D-43)

- **`areas/modeller/Modeller.tsx`.**
  - Packages (hash-pinned bundles, FC-41).
  - Package workspace:
    - bpmn-js modeller, dmn-js modeller (all hit policies, FC-54) and form-js form builder, all with the watermark kept;
    - CMMN source editor with a plan outline that flags the listeners rejected at deploy (D-08);
    - save to the contract `PUT /packages/{id}` with If-Match;
    - validation report;
    - diff vs the deployed version, with an in-flight count;
    - history, model tests, comments;
    - Git sync (pull / push / configure);
    - **play mode**: deploy to the sandbox and start an instance.
  - Lifecycle: submit → review → approve → deploy → retire.
  - Review queue (A07): the reviewer cannot be the author.
  - **Import assessment** (EC-21, the LEAP equivalent).
- **`areas/modeller/DataAndPages.tsx`.**
  - **Data objects / data dictionary / ERD** (R78, EC-06, PF-38): DB- or REST-backed, sensitive fields, versioning, record browser.
  - **Low-code pages** (R77, EC-03, PF-35): heading / text / list / detail / KPI / action blocks bound to named queries, plus a runtime at `/pages/:key`.

### Analytics and process mining (R65, R66, R79; PF-07, 14, 19, 20, 42; EC-10; UP-07)

`areas/analytics/Analytics.tsx` covers:
- KPI tiles (`/analytics/summary`);
- throughput;
- SLA performance;
- XES / OCEL export;
- process mining: duration heatmap on the diagram, conformance (fitness / precision / deviations, with a warning when no fixture exists), bottlenecks, variants;
- reports (duration, completed tasks);
- threshold alerts;
- AI agent token / cost usage (R72).

### Audit and documents (R42, R74; S11, U01–U03; PF-41, EC-09, EC-18, PF-30 / 50)

`areas/audit/Audit.tsx` covers:
- **Case timeline** with seat, person, acted-for, package / rule version, payload hash, operationId and known / pending / uncertain status.
- **Hash-chain verification**: a client-side prevHash linkage check on the loaded page plus `GET /audit/verify`.
- Evidence pack.
- Audit search and export.
- Documents: versions, checksums, scan status, legal hold, bilingual template generation.

### Citizen / applicant portal (C01–C06, UP-22)

`areas/portal/Portal.tsx` covers:
- service catalogue (SLA, fee, documents);
- application form with the DPDP notice and consent, draft or submit;
- status in plain words with the SLA date;
- clarification reply with documents;
- withdraw, outcome download, appeal within the deadline;
- payment status, with "do not pay again" while UNKNOWN_OUTCOME;
- grievances and DPDP data-rights requests.

### Shared components

| File | What it does |
|---|---|
| `components/ui.tsx` | Fields, tables, pagination, tabs, dialogs, `ReasonDialog`, problem rendering |
| `components/BpmnDiagram.tsx` | Overlays: tokens / heatmap / duration / incidents / selection |
| `components/DmnView.tsx` | Read-only DMN view |
| `components/Editors.tsx` | BPMN / DMN / form / CMMN editors |
| `components/FormRenderer.tsx` | Bilingual form-js-schema renderer |
| `components/CrudPage.tsx` | Declarative registry screens |
| `components/Charts.tsx` | Dependency-free accessible SVG charts |
| `areas/help/Help.tsx` | Accessibility statement and about / licences (GIGW footer) |

## Premium-feature coverage map

C = implemented in the UI. "UI ready; server [P]" = the screen calls a proposed endpoint (see API reconciliation).

### Camunda 7 EE, Camunda 8, Flowable Enterprise (PF-01…PF-52)

| PF | Feature | Sangam screen | Status |
|---|---|---|---|
| 01 | Cockpit history views | InstanceDetail history tabs, Processes history mode, `/history/*` | C |
| 02 | Heatmap | BpmnDiagram heat / duration overlays (`/analytics/activities`) | C |
| 03 | Instance modification | InstanceDetail → Modify (`/modify`) | C (preview [P]) |
| 04 | Migration UI | BatchMigration → Migration | C |
| 05 | Restart | InstanceDetail → Restart (`/ops/restarts`) | C |
| 06 | Batch operations | Processes batch bar, Batches, BatchDetail | C |
| 07 | Cockpit reports | Analytics → Reports | C (duration [P]) |
| 08 | DMN live editing | Modeller DMN editor + delegation tables (governed, R43) | C |
| 09 | Operation auditing | OperatorAudit (`/history/operation-log`) | C |
| 10 | Cleanup view | Cleanup (`/ops/cleanup`) | C (TTL edit [P]) |
| 11 | Task search / dashboard | TaskInbox | C |
| 12 | CMMN case views | OpsCases, CaseWorkspace | C |
| 13 | Metrics dashboard | OpsDashboard | UI ready; server [P] |
| 14, 19 | Optimize | AnalyticsDashboard, Reports, Alerts | C (throughput / SLA / alerts [P]) |
| 15 | LTS / support | — (not a UI feature; R68) | n/a |
| 16 | SSO / LDAP | SignIn (OIDC), IdentityProviders (LDAP sync) | C (IdP admin [P]) |
| 17 | Operate | Ops area | C |
| 18 | Tasklist | Work area | C |
| 20 | Process mining | ProcessMining | C (conformance [P]) |
| 21 | Web Modeler collaboration | PackageWorkspace comments, history, review | C ([P] comments) |
| 22 | Git sync / CI | PackageWorkspace → Git | UI ready; server [P] |
| 23 | Play / test mode | PackageWorkspace → Play, Tests | C (tests [P]) |
| 24 | Identity / tenants | Tenants, Users, Groups, Policies | C |
| 25, 26 | Console, backups | Not in WS-UI scope (R70, Stage D) | Gap |
| 27, 39 | Connectors, service registry | ServiceRegistry | UI ready; server [P] |
| 28, 46 | Agentic AI | AgentDefinitions, AgentUsage | UI ready; server [P] |
| 29 | RPA | ServiceRegistry connector `RPA` | Partial (worker side E) |
| 30, 50 | Documents / templates | Documents, Templates, TaskDetail attachments | C (hold / templates [P]) |
| 31 | IDP (document processing) | — | Gap (R75, Stage E) |
| 32 | Engine | n/a | n/a |
| 33 | Flowable Work | Work area + CaseWorkspace | C |
| 34 | Rich forms | FormBuilder + FormRenderer | C |
| 35 | Pages | PageBuilder, PageRuntime | UI ready; server [P] |
| 36 | Flowable Design | Modeller | C |
| 37 | Design Git / approval / tests | Modeller Git, ReviewQueue, Tests | C / [P] |
| 38 | Data objects | DataObjects + ERD | UI ready; server [P] |
| 40 | SLA models | SlaModels; SLA columns in the inbox and analytics | UI ready; server [P] |
| 41 | Full audit trails | Timeline, AuditSearch | C |
| 42 | Business dashboards | AnalyticsDashboard, Pages KPI blocks | C |
| 43, 44 | Control / Hub | Ops area, Migration, OpsDashboard job health | C |
| 45 | Inspect | Debugger | UI ready; server [P] |
| 47 | Engage | ConversationalChannels | UI ready; server [P] |
| 48 | Mobile | Responsive layout + FieldVisits (offline) | C |
| 49 | Multi-tenant admin | Tenants | C |
| 51 | SaaS | — (deliberately not replicated) | X |
| 52 | Operator permissions + audit | Policies, OperatorAudit, ReasonDialog on every action | C |

### Flowable Enterprise UI items (EC-01…EC-21, `spike/results/FLOWABLE-INTO-OPERATON.md` §C.6)

| EC | Item | Screen |
|---|---|---|
| 01 | Work / case workspace | TaskInbox, TaskDetail, StartCatalog, CaseWorkspace |
| 02 | Form designer + runtime | FormBuilder (form-js-editor), FormRenderer |
| 03 | Pages | PageBuilder, PageRuntime |
| 04 | Design modeller (BPMN, DMN, forms, CMMN, version history) | PackageWorkspace; CMMN as a source editor with outline (no maintained bpmn.io CMMN editor) |
| 05 | Git, deployment approval, test models | PackageWorkspace Git / Tests, ReviewQueue |
| 06 | Data objects / ERD | DataObjects |
| 07 | Service registry | ServiceRegistry |
| 08 | SLA models | SlaModels |
| 09 | Full audit trails | Timeline, AuditSearch |
| 10 | Dashboards / reports | AnalyticsDashboard, Reports, Alerts |
| 11 | Control | OpsDashboard, Processes, InstanceDetail, Incidents |
| 12 | Hub (assisted migration, job health) | Migration, OpsDashboard partitions, Jobs |
| 13 | Inspect | Debugger |
| 14 | Agents | AgentDefinitions, AgentUsage |
| 15 | Engage | ConversationalChannels |
| 16 | Mobile | Responsive shell, FieldVisits |
| 17 | Multi-tenant admin | Tenants |
| 18 | Documents / output templates | Documents, Templates |
| 19 | SaaS | X, not replicated (R56) |
| 20 | Operator permissions + audit | Policies (+ explain), OperatorAudit |
| 21 | LEAP model analyser | ImportAssessment |

### UI-facing Flowable OSS items (FC)

| FC | Item | Screen |
|---|---|---|
| 01 | Ad-hoc sub-process | InstanceDetail → Ad-hoc dialog (contract `/ad-hoc`) |
| 02 / 03 | Event registry and channels | EventRegistry |
| 04 | Unique start | EventRegistry definition "unique start" |
| 15 | Dynamic injection | InstanceDetail → Inject (contract `/inject`) |
| 17 | Cross-boundary move | InstanceDetail → Move across call activity [P] |
| 18 | Migration document, 1→N / N→1, hooks | Migration |
| 20 | Activity-instance view | InstanceDetail activity tree; instance token counts derived from it |
| 31 / 32 | Instance / entity links | InstanceDetail → Links (contract `/links`) |
| 36 / 37 | Worker console | Workers |
| 38 | Actuator / metrics | OpsDashboard metrics |
| 39 | Forms API | FormRenderer + `/forms/{key}`, `/forms/drafts` |
| 40 | Content | Documents, attachments |
| 41 | App = package | Packages |
| 44 | Debugger | Debugger |
| 45 | Diagram rendering | bpmn-js / dmn-js in the browser |
| 46 | Validation report | PackageWorkspace → Validation |
| 52 | Task lifecycle fields | TaskDetail → Lifecycle tab |
| 53 / 54 | Decision services, hit policies | dmn-js editor and viewer |
| 59 | Case reactivation | CaseWorkspace → Reactivate |

### UI-facing Operaton weaknesses (D)

| D | Weakness | Screen |
|---|---|---|
| 08 | CMMN listeners | CMMN outline marks timer / user event listeners as "rejected at deploy" |
| 12 | DMN latest-binding | Package binding shown; the validation report lists closure findings |
| 13 | History TTL | Cleanup shows the retention class, never TTL deletion of government records |
| 32 | Pause / resume moves deadlines | InstanceDetail → Resume with "move deadlines" |
| 34 | Bulk task completion | TaskInbox → Approve selected (batch) |
| 35 | Branch-addressed correlation | InstanceDetail → Send message with branch key (`correlationKeys`) |
| 42 | AngularJS webapps EOL | This app replaces them |
| 43 | No released modeller, no CMMN editor | Modeller (+ CMMN source editor) |
| 55 | Slow capped counts | "More than N" everywhere (A-16) |

### Universal patterns with UI

| UP | Pattern | Screen |
|---|---|---|
| 04 / 10 | Routing strategies, SLA escalation | RoutingStrategies, SlaModels |
| 05 | Four-eyes / SoD | SodExceptions, task SoD hints, batch / recon four-eyes |
| 06 | Human-in-the-loop AI | AgentDefinitions human review |
| 07 | Process mining loop | ProcessMining |
| 09 | Ad-hoc / case work | CaseWorkspace, ad-hoc dialog |
| 22 | External parties | Portal |

## API reconciliation (WS-REST `sangam-rest/api/sangam-api.yaml`, read 2026-11-04)

The UI was written against ARCHITECTURE §4 and then reconciled with the published contract (`src/api/endpoints.ts`, where [E] marks engine-rest calls).

**Reconciled to the contract:**
- **Headers:** `X-Sangam-Seat` and `X-Sangam-Acting-For-Seat`.
- **Tasks:**
  - `CompleteTaskRequest {payloadHash, caseVersion, decision, remarks, formVersion, variables}`;
  - `ReturnTaskRequest {reason, reasonCode, targetActivityId}`;
  - wrapped `TaskDetail {task, …}`.
- **Resubmit and versions:** `/process-instances/{id}/resubmit` and `/versions`.
- **Variables:** R33 TypedValue maps everywhere.
- **Instance commands:**
  - `/process-instances/{id}/activity-tree`;
  - `/modify`;
  - `/inject`;
  - `/ad-hoc/*`;
  - `/links`;
  - cancel as `POST /cancel` (business) or `DELETE ?reason=` (technical).
- **Ops:**
  - `/ops/restarts`;
  - `/ops/migrations/plan|execute` (validate through plan; count through `/ops/batches/preview`);
  - `/ops/batches/{id}/control {action}`;
  - batch / item `state`;
  - `/ops/jobs/{id}/retries|execute`;
  - `/ops/outbox/{outboxId}/requeue|skip`;
  - `/ops/reconciliation/{id}/resolve {action, evidence, result, reason}`;
  - `/ops/cleanup`.
- **History:** `/history/*` and `/history/operation-log`.
- **Packages:**
  - `PUT /packages/{id} {resources}` with If-Match;
  - `/packages/{id}/transitions {action, reason, environment, activateAt}`;
  - `/packages/{id}/diff`.
- **Forms:** `/forms/{key}` (FormVersion → renderable schema) and `/forms/drafts`.
- **Audit:**
  - `/audit/{bk}/timeline`, `/audit/events`;
  - `/audit/verify` (`ok` + `chains`);
  - `/audit/export`.
- **Analytics:** `/analytics/summary|activities|tasks|variants|export/*`.
- **Events:** keyed by `key`; definitions have no PUT, so an edit deletes and re-creates.
- **Admin:**
  - policies have no PUT, so an edit is create + delete;
  - DELETE carries `?reason=`;
  - org units are a flat list, made into a tree on the client;
  - assignments are filtered with `activeOn`.
- **Runtime tokens on the definition diagram:** `GET /engine-rest/process-definition/{id}/statistics?incidents=true` (Operaton REST, unchanged, behind Sangam auth).

**Former proposals:** every static path the UI calls is now in the contract (910+ operations; paths checked by script, request and response shapes not checked). `/tasks` `sortBy` and `sortOrder` accept comma-separated lists for multi-key sort.

## Dependencies and licences

Direct dependencies:

| Package | Version | Licence | Use |
|---|---|---|---|
| react, react-dom | 18.3.1 | MIT | UI |
| react-router-dom | 6.28.0 | MIT | Routing |
| i18next | 23.16.8 | MIT | i18n |
| react-i18next | 15.1.4 | MIT | i18n |
| oidc-client-ts | 3.1.0 | Apache-2.0 | OIDC login |
| bpmn-js | 17.11.1 | bpmn.io licence (not OSI; allowed with the watermark visible, LIC-8) | BPMN viewer / modeller |
| dmn-js | 16.8.2 | bpmn.io licence (as above) | DMN viewer / modeller |
| @bpmn-io/form-js-editor, @bpmn-io/form-js-viewer | 1.12.0 | bpmn.io licence (as above) | Form builder (modeller only) |
| @fontsource/noto-sans, @fontsource/noto-sans-devanagari | 5.1.0 | OFL-1.1 | Bundled fonts |
| Sakal Bharati (C-DAC GIST, file in `public/fonts/gov/`) | 13.02 | OFL-1.1 | Bundled font (Hindi); provenance and SHA-256 in FONTS.md |
| typescript (dev) | 5.6.3 | Apache-2.0 | Type check |
| vite (dev) | 5.4.11 | MIT | Build |
| @vitejs/plugin-react (dev) | 4.3.4 | MIT | Build |
| vitest (dev) | 2.1.8 | MIT | Unit tests |
| @types/react, @types/react-dom (dev) | 18.3.12 / 18.3.1 | MIT | Types |

The full installed tree is generated by `npm run licences`: 243 packages; "runtime" means it is in the production dependency tree. Apart from bpmn.io:
- everything is OSI-approved;
- `component-props` declares MIT in its `component.json`;
- `caniuse-lite` (CC-BY-4.0) is browser-support data read only by the build tooling and is not shipped in `dist/`.

<details><summary>All installed packages and licences</summary>

| Package | Version | Licence | Scope |
|---|---|---|---|
| @babel/code-frame | 7.29.7 | MIT | build |
| @babel/compat-data | 7.29.7 | MIT | build |
| @babel/core | 7.29.7 | MIT | build |
| @babel/generator | 7.29.12 | MIT | build |
| @babel/helper-compilation-targets | 7.29.7 | MIT | build |
| @babel/helper-globals | 7.29.7 | MIT | build |
| @babel/helper-module-imports | 7.29.7 | MIT | build |
| @babel/helper-module-transforms | 7.29.7 | MIT | build |
| @babel/helper-plugin-utils | 7.29.7 | MIT | build |
| @babel/helper-string-parser | 7.29.11 | MIT | build |
| @babel/helper-validator-identifier | 7.29.7 | MIT | build |
| @babel/helper-validator-option | 7.29.7 | MIT | build |
| @babel/helpers | 7.29.10 | MIT | build |
| @babel/parser | 7.29.12 | MIT | build |
| @babel/plugin-transform-react-jsx-self | 7.29.7 | MIT | build |
| @babel/plugin-transform-react-jsx-source | 7.29.7 | MIT | build |
| @babel/runtime | 7.29.10 | MIT | runtime |
| @babel/template | 7.29.7 | MIT | build |
| @babel/traverse | 7.29.12 | MIT | build |
| @babel/types | 7.29.11 | MIT | build |
| @bpmn-io/cm-theme | 0.2.2 | MIT | runtime |
| @bpmn-io/cm-theme | 0.1.0-alpha.2 | MIT | runtime |
| @bpmn-io/diagram-js-ui | 0.2.4 | MIT | runtime |
| @bpmn-io/dmn-variable-resolver | 0.7.1 | MIT | runtime |
| @bpmn-io/draggle | 4.1.2 | MIT | runtime |
| @bpmn-io/feel-analyzer | 0.5.1 | MIT | runtime |
| @bpmn-io/feel-editor | 2.8.1 | MIT | runtime |
| @bpmn-io/feel-editor | 1.12.1 | MIT | runtime |
| @bpmn-io/feel-lint | 3.2.0 | MIT | runtime |
| @bpmn-io/feel-lint | 2.1.0 | MIT | runtime |
| @bpmn-io/feelers-editor | 1.2.0 | MIT | runtime |
| @bpmn-io/feelers-lint | 1.0.0 | MIT | runtime |
| @bpmn-io/feelin | 6.1.1 | MIT | runtime |
| @bpmn-io/form-js-editor | 1.12.0 | SEE LICENSE IN LICENSE | runtime |
| @bpmn-io/form-js-viewer | 1.12.0 | SEE LICENSE IN LICENSE | runtime |
| @bpmn-io/lang-feel | 4.0.2 | MIT | runtime |
| @bpmn-io/lang-feel | 2.4.0 | MIT | runtime |
| @bpmn-io/lang-feelers | 1.0.0 | MIT | runtime |
| @bpmn-io/lezer-feel | 2.5.0 | MIT | runtime |
| @bpmn-io/lezer-feel | 3.0.1 | MIT | runtime |
| @bpmn-io/lezer-feel | 1.9.0 | MIT | runtime |
| @bpmn-io/lezer-feel | 2.5.0 | MIT | runtime |
| @bpmn-io/lezer-feelers | 1.0.0 | MIT | runtime |
| @bpmn-io/properties-panel | 3.58.1 | MIT | runtime |
| @bpmn-io/semver-compat | 0.1.0 | MIT | runtime |
| @camunda/feel-builtins | 1.5.0 | MIT | runtime |
| @camunda/feel-builtins | 0.2.0 | MIT | runtime |
| @carbon/grid | 11.66.0 | Apache-2.0 | runtime |
| @carbon/layout | 11.63.0 | Apache-2.0 | runtime |
| @codemirror/autocomplete | 6.20.4 | MIT | runtime |
| @codemirror/commands | 6.11.1 | MIT | runtime |
| @codemirror/lang-json | 6.0.2 | MIT | runtime |
| @codemirror/language | 6.13.1 | MIT | runtime |
| @codemirror/lint | 6.9.7 | MIT | runtime |
| @codemirror/state | 6.7.6 | MIT | runtime |
| @codemirror/streamparser | 6.0.0 | MIT | runtime |
| @codemirror/view | 6.43.15 | MIT | runtime |
| @esbuild/win32-x64 | 0.21.5 | MIT | build |
| @fontsource/noto-sans | 5.1.0 | OFL-1.1 | runtime |
| @fontsource/noto-sans-devanagari | 5.1.0 | OFL-1.1 | runtime |
| @ibm/telemetry-js | 1.12.0 | Apache-2.0 | runtime |
| @jridgewell/gen-mapping | 0.3.13 | MIT | build |
| @jridgewell/remapping | 2.3.5 | MIT | build |
| @jridgewell/resolve-uri | 3.1.2 | MIT | build |
| @jridgewell/sourcemap-codec | 1.6.0 | MIT | build |
| @jridgewell/trace-mapping | 0.3.31 | MIT | build |
| @lezer/common | 1.5.5 | MIT | runtime |
| @lezer/highlight | 1.2.5 | MIT | runtime |
| @lezer/html | 1.3.13 | MIT | runtime |
| @lezer/json | 1.0.3 | MIT | runtime |
| @lezer/lr | 1.4.12 | MIT | runtime |
| @lezer/markdown | 1.8.1 | MIT | runtime |
| @marijn/find-cluster-break | 1.0.4 | MIT | runtime |
| @remix-run/router | 1.21.0 | MIT | runtime |
| @rollup/rollup-win32-x64-gnu | 4.67.5 | MIT | build |
| @rollup/rollup-win32-x64-msvc | 4.67.5 | MIT | build |
| @types/babel__core | 7.20.5 | MIT | build |
| @types/babel__generator | 7.27.0 | MIT | build |
| @types/babel__template | 7.4.4 | MIT | build |
| @types/babel__traverse | 7.28.0 | MIT | build |
| @types/estree | 1.0.10 | MIT | build |
| @types/prop-types | 15.7.15 | MIT | build |
| @types/react | 18.3.12 | MIT | build |
| @types/react-dom | 18.3.1 | MIT | build |
| @types/trusted-types | 2.0.7 | MIT | runtime |
| @vitejs/plugin-react | 4.3.4 | MIT | build |
| @vitest/expect | 2.1.8 | MIT | build |
| @vitest/mocker | 2.1.8 | MIT | build |
| @vitest/pretty-format | 2.1.9 | MIT | build |
| @vitest/pretty-format | 2.1.8 | MIT | build |
| @vitest/pretty-format | 2.1.8 | MIT | build |
| @vitest/runner | 2.1.8 | MIT | build |
| @vitest/snapshot | 2.1.8 | MIT | build |
| @vitest/spy | 2.1.8 | MIT | build |
| @vitest/utils | 2.1.8 | MIT | build |
| array-move | 4.0.0 | MIT | runtime |
| assertion-error | 2.0.1 | MIT | build |
| atoa | 1.0.0 | MIT | runtime |
| baseline-browser-mapping | 2.11.35 | Apache-2.0 | build |
| big.js | 6.2.2 | MIT | runtime |
| bpmn-js | 17.11.1 | SEE LICENSE IN LICENSE | runtime |
| bpmn-moddle | 8.1.0 | MIT | runtime |
| browserslist | 4.29.5 | MIT | build |
| cac | 6.7.14 | MIT | build |
| caniuse-lite | 1.0.30001820 | CC-BY-4.0 | build |
| chai | 5.3.3 | MIT | build |
| check-error | 2.1.3 | MIT | build |
| classnames | 2.5.1 | MIT | runtime |
| clsx | 2.1.1 | MIT | runtime |
| component-event | 0.2.1 | MIT | runtime |
| component-props | 1.1.1 | MIT (component.json) | runtime |
| component-xor | 0.0.4 | MIT | runtime |
| contra | 1.9.4 | MIT | runtime |
| convert-source-map | 2.0.0 | MIT | build |
| crelt | 1.0.7 | MIT | runtime |
| css.escape | 1.5.1 | MIT | runtime |
| csstype | 3.2.3 | MIT | build |
| debug | 4.4.3 | MIT | build |
| deep-eql | 5.0.2 | MIT | build |
| diagram-js | 14.11.3 | MIT | runtime |
| diagram-js-direct-editing | 3.6.0 | MIT | runtime |
| didi | 10.2.2 | MIT | runtime |
| didi | 11.0.1 | MIT | runtime |
| dmn-js | 16.8.2 | SEE LICENSE IN LICENSE | runtime |
| dmn-js-boxed-expression | 16.8.2 | SEE LICENSE IN LICENSE | runtime |
| dmn-js-decision-table | 16.8.2 | SEE LICENSE IN LICENSE | runtime |
| dmn-js-drd | 16.8.2 | SEE LICENSE IN LICENSE | runtime |
| dmn-js-literal-expression | 16.8.2 | SEE LICENSE IN LICENSE | runtime |
| dmn-js-shared | 16.8.2 | SEE LICENSE IN LICENSE | runtime |
| dmn-moddle | 10.0.0 | MIT | runtime |
| dom-iterator | 1.0.2 | MIT | runtime |
| domify | 3.0.0 | MIT | runtime |
| domify | 3.0.0 | MIT | runtime |
| domify | 3.0.0 | MIT | runtime |
| domify | 3.0.0 | MIT | runtime |
| domify | 1.4.2 | MIT | runtime |
| domify | 3.0.0 | MIT | runtime |
| domify | 3.0.0 | MIT | runtime |
| dompurify | 3.4.17 | (MPL-2.0 OR Apache-2.0) | runtime |
| electron-to-chromium | 1.5.476 | ISC | build |
| es-module-lexer | 1.7.0 | MIT | build |
| esbuild | 0.21.5 | MIT | build |
| escalade | 3.2.0 | MIT | build |
| escape-html | 1.0.3 | MIT | runtime |
| estree-walker | 3.0.3 | MIT | build |
| expect-type | 1.4.0 | Apache-2.0 | build |
| feelers | 1.5.1 | MIT | runtime |
| feelin | 3.2.0 | MIT | runtime |
| flatpickr | 4.6.13 | MIT | runtime |
| focus-trap | 8.2.4 | MIT | runtime |
| gensync | 1.0.0-beta.2 | MIT | build |
| htm | 3.1.1 | Apache-2.0 | runtime |
| html-parse-stringify | 3.1.0 | MIT | runtime |
| i18next | 23.16.8 | MIT | runtime |
| ids | 1.0.5 | MIT | runtime |
| inferno | 5.6.3 | MIT | runtime |
| inferno-shared | 5.6.3 | MIT | runtime |
| inferno-vnode-flags | 5.6.3 | MIT | runtime |
| inherits-browser | 0.1.0 | ISC | runtime |
| js-tokens | 4.0.0 | MIT | runtime |
| jsesc | 3.1.0 | MIT | build |
| json5 | 2.2.3 | MIT | build |
| jwt-decode | 4.0.0 | MIT | runtime |
| lezer-feel | 1.9.0 | MIT | runtime |
| lodash | 4.18.1 | MIT | runtime |
| loose-envify | 1.4.0 | MIT | runtime |
| loupe | 3.2.1 | MIT | build |
| lru-cache | 5.1.1 | ISC | build |
| luxon | 3.7.2 | MIT | runtime |
| magic-string | 0.30.21 | MIT | build |
| marked | 15.0.12 | MIT | runtime |
| min-dash | 5.1.0 | MIT | runtime |
| min-dash | 5.1.0 | MIT | runtime |
| min-dash | 5.1.0 | MIT | runtime |
| min-dash | 5.1.0 | MIT | runtime |
| min-dash | 5.1.0 | MIT | runtime |
| min-dash | 5.1.0 | MIT | runtime |
| min-dash | 3.8.1 | MIT | runtime |
| min-dash | 5.1.0 | MIT | runtime |
| min-dash | 4.2.3 | MIT | runtime |
| min-dash | 5.1.0 | MIT | runtime |
| min-dom | 5.3.0 | MIT | runtime |
| min-dom | 5.3.0 | MIT | runtime |
| min-dom | 5.3.0 | MIT | runtime |
| min-dom | 5.3.0 | MIT | runtime |
| min-dom | 5.3.0 | MIT | runtime |
| min-dom | 4.2.1 | MIT | runtime |
| min-dom | 5.3.0 | MIT | runtime |
| mitt | 3.0.1 | MIT | runtime |
| moddle | 5.0.4 | MIT | runtime |
| moddle | 6.2.3 | MIT | runtime |
| moddle-xml | 9.0.6 | MIT | runtime |
| moddle-xml | 10.1.0 | MIT | runtime |
| ms | 2.1.3 | MIT | build |
| nanoid | 3.3.20 | MIT | build |
| node-releases | 2.0.63 | MIT | build |
| object-refs | 0.4.0 | MIT | runtime |
| oidc-client-ts | 3.1.0 | Apache-2.0 | runtime |
| opencollective-postinstall | 2.0.3 | MIT | runtime |
| path-intersection | 3.1.0 | MIT | runtime |
| pathe | 1.1.2 | MIT | build |
| pathval | 2.0.1 | MIT | build |
| picocolors | 1.1.1 | ISC | build |
| postcss | 8.5.32 | MIT | build |
| preact | 10.29.8 | MIT | runtime |
| react | 18.3.1 | MIT | runtime |
| react-dom | 18.3.1 | MIT | runtime |
| react-i18next | 15.1.4 | MIT | runtime |
| react-refresh | 0.14.2 | MIT | build |
| react-router | 6.28.0 | MIT | runtime |
| react-router-dom | 6.28.0 | MIT | runtime |
| rollup | 4.67.5 | MIT | build |
| saxen | 8.1.2 | MIT | runtime |
| scheduler | 0.23.2 | MIT | runtime |
| selection-ranges | 3.0.3 | MIT | runtime |
| selection-ranges | 4.1.1 | MIT | runtime |
| selection-update | 0.1.2 | MIT | runtime |
| semver | 6.3.2 | ISC | runtime |
| semver | 6.3.2 | ISC | runtime |
| semver | 7.8.6 | ISC | runtime |
| siginfo | 2.0.0 | ISC | build |
| source-map-js | 1.2.2 | BSD-3-Clause | build |
| stackback | 0.0.2 | MIT | build |
| std-env | 3.10.0 | MIT | build |
| style-mod | 4.1.4 | MIT | runtime |
| tabbable | 6.5.1 | MIT | runtime |
| table-js | 9.4.0 | MIT | runtime |
| ticky | 1.0.1 | MIT | runtime |
| tiny-svg | 3.1.3 | MIT | runtime |
| tinybench | 2.9.0 | MIT | build |
| tinyexec | 0.3.2 | MIT | build |
| tinypool | 1.1.1 | MIT | build |
| tinyrainbow | 1.2.0 | MIT | build |
| tinyspy | 3.0.2 | MIT | build |
| typescript | 5.6.3 | Apache-2.0 | build |
| update-browserslist-db | 1.3.4 | MIT | build |
| vite | 5.4.11 | MIT | build |
| vite-node | 2.1.8 | MIT | build |
| vitest | 2.1.8 | MIT | build |
| void-elements | 3.1.0 | MIT | runtime |
| w3c-keyname | 2.2.8 | MIT | runtime |
| why-is-node-running | 2.3.0 | MIT | build |
| yallist | 3.1.1 | ISC | build |

</details>

## Generic modules, R46 and Operaton parity

- **Declarative engine:** `src/modules/engine.tsx` (list, detail, action dialogs).
- **Screens:** `src/modules/{config,finance,registry,resources,casework,programme,fieldgeo,compliance}.tsx`.
- **R46:** `npm run compat` regenerates `src/modeller/compatibility.json` and `test-plan/R46-ROUND-TRIP-TESTS.md` from the bpmn.io palettes, the Operaton parsers and `scripts/compat-rules.json`. The matrix is at `/modeller/compatibility`; the package workspace scans models and asks for acknowledgement before submit or deploy.
- **Operaton parity:** `OPERATON-UI-PARITY.md`, with screens in `src/modules/operaton.tsx` on `/engine-rest`.
- **Onboarding:** `/admin/onboarding` uses the contract `/onboarding/**` routes. The request parts are JSON editors, not a guided form.
- **UI plugins:** `src/plugins/host.tsx` reads the authenticated list at `/plugins/ui` and imports entries from `/plugins/ui/{id}/assets/{entry}` by same-origin dynamic import, under strict `script-src 'self'`. Needs anonymous GET on that asset route (WS-CONFORMANCE). Plugin code must not contain secrets; see `examples/ui-plugin-hello/README.md`.
- **Request bodies:** registry, resources, geo and field operations have no body schemas in the OpenAPI file (derived from controllers), so their form field names are unverified. Each dialog has a JSON editor for corrections.

## New module routes (to reconcile)

`src/modules/platform.tsx` has screens for sangam-ai, sangam-channels, sangam-modeller and sangam-ops. Their routes are **not in the contract yet**, so apart from the READMEs' `/landscape`, `/models/token-walk`, `/models/{file}/lease`, `/templates`, `/actions`, `/catalogue` and `/packages/{id}/tests/run`, these paths and all field names are assumptions for WS-REST to confirm:

- **AI:** `/ai/agent-runs`, `/ai/agent-approvals`, `/ai/knowledge-bases`, `/ai/drafts`, `/ai/classifications`, `/ai/expressions/{evaluate,propose}`.
- **Channels:** wired to the live `ChannelsController` (`/channels/conversations`, `/channels/opt-ins`, `/channels/status`). The server needs `GET /channels/conversations?caseRef=` before the case panel can list a case's conversations, and an attachment reference on send; opt-ins have no list route.
- **Modeller:** `/service-mocks`, `/actions/{key}/run`.
- **Ops:** `/search`, `/ops/usage`, `/ops/outliers`, `/ops/variables`, `/ops/secrets`, `/ops/sequences`, `/ops/index-jobs`, `/ops/retention/{plan,execute}`, `/ops/migration-status`.

Not built: bpmn-js custom palette and form-js composite components (templates are managed as data but not injected into the editors), the action menu inside task and case screens, Teams tab, test report export.

## Integration notes

- **Heavy-build lock.** The task text names `heavy_lock.py run`, but the shared script (`…/D--GITHUB--Project-IFMS/…/scratchpad/heavy_lock.py`) offers only `acquire` / `release` / `status`. `npm install` and `npm run build` were therefore run inside `acquire customwf … release customwf`, with release in a `finally` block.
- **UI image.** The image serves `dist/` and a runtime `/config.json`, and proxies `/api` and `/engine-rest` (WS-DEPLOY §10). SPA fallback to `index.html` is required for deep links and for `/auth/callback`.
- **OIDC client.** The public client `sangam-ui` needs redirect URI `<origin>/auth/callback` and post-logout URI `<origin>/`.
- **Roles.** Roles are read from `/me.roles`, then `/me.groups`, then the token claims `roles` / `realm_access.roles` / `groups`. The expected role names are TASK_USER, OPERATOR, PLATFORM_OPERATOR, ADMIN, PLATFORM_ADMIN, MODELLER, REVIEWER, ANALYST, AUDITOR, INTEGRATION and CITIZEN.

## Gaps

- Browser and end-to-end tests have not been run (the coding phase comes first). `src/lib/logic.test.ts` covers the pure logic and has not been executed yet.
- Every screen marked [P] depends on WS-REST adding the proposed endpoint.
- Hash-chain linkage is checked on the client only when AuditEntry carries `prevHash`. The cryptographic recomputation is done server-side by `/audit/verify`.
- R70 platform console / backup orchestration (PF-25 / 26) and R75 IDP (PF-31) have no screen (Stage D / E, outside this UI's C scope).
- There is no maintained bpmn.io CMMN modeller, so CMMN editing is source + outline, not graphical.
- Collaborative multi-user presence in the modeller (R69 Stage E) is not built. Comments, history and Git are.
- WCAG conformance is designed in but not yet verified with an audit tool or assistive-technology testing. Hindi strings are an author's translation and need review by a native reviewer.
