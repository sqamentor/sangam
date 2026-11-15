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
npm run build:embed  # M-043 web components → dist-embed/sangam-elements.js (a separate artefact)
npm run e2e          # M-048 Playwright suite against a running stack (test phase; see "End-to-end tests")
```

`npm run build` and `npm run dev` need `../deploy/fonts` (or `SANGAM_UI_FONTS_DIR`): the approved Noto fonts are copied
from there and checked against their recorded SHA-256 (owner decisions 120, 121; [FONTS.md](FONTS.md)).

**Build result (2026-11-04):**
- `tsc` passes with zero errors in strict mode.
- `vite build` passes.
- `i18n:check` passes: 1,413 keys, identical in `en` and `hi`.
- The no-CDN check passes.
- The licence check passes: 243 packages and 0 unexplained.

Heavy areas are lazy-loaded chunks: the editors chunk (bpmn-js, dmn-js and form-js) is about 1.46 MB, or 432 KB gzipped, and loads only in the modeller.

**Supported browsers (owner decision 309):** Chrome and Edge 111 or later, Firefox 114 or later, Safari 16.4 or later. This is the Vite 8 default build target (`baseline-widely-available`), and `vite.config.ts` deliberately sets no `build.target` override. Older browsers are not supported: the built JavaScript and CSS may use syntax they cannot parse. The same baseline applies to the embeddable web components (`npm run build:embed`).

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
- `teams.enabled` (owner decision 162, default false). See [TEAMS.md](TEAMS.md).
- `dsc.agentUrl` (decision 165): the DSC signing agent on the signer's computer, an `http://127.0.0.1:<port>` style loopback origin. Any other value is ignored, and DSC signing then says it is not set up. The server's CSP `connect-src` must name the same origin.

`public/config.json` is the dev default. The UI image replaces it.

There is no meta CSP in `index.html` (owner decision 214). The UI server's Content-Security-Policy header is the only policy, and it names the configured OIDC issuer.

## Embeddable web components (M-043)

`npm run build:embed` (`vite.embed.config.ts`) builds `dist-embed/sangam-elements.js`: one self-contained ES module
with React, i18n, bpmn-js and the Sangam styles. It is a separate artefact from the UI image. It lets another
application (PHP, .NET, Java, React…) show a Sangam task form or process diagram without its own UI.

```html
<script type="module">
  import { configure } from '/static/sangam-elements.js';
  configure({
    apiBase: 'https://sangam.example.org/api/v1',          // or a same-origin path such as /api/v1
    getAccessToken: () => myApp.session.accessToken(),      // called before every request
    language: 'en',                                         // optional; 0.1.0 is English-only (Q-06)
    onUnauthenticated: () => myApp.session.refresh(),       // optional; called on 401
  });
</script>
<sangam-task-form task-id="…"></sangam-task-form>
<sangam-process-diagram process-definition-id="…" process-instance-id="…" overlay="tokens" height="460" label="…"></sangam-process-diagram>
```

| Element | Attributes | Behaviour |
|---|---|---|
| `<sangam-task-form>` | `task-id` (required), `lang` | The task's form, resolved exactly as in the tasklist (shipped schema, deployed form parsed inertly, custom fields). When the task offers `COMPLETE`, a submit completes it, bound to the payload hash, case version and form version that were loaded. A 409 stale answer keeps the input and blocks submit until the user continues. Decisions that need the tasklist's dialogs (approve with the declaration, reject with a reason code, forward, return) are not offered: the form is then read-only with a notice |
| `<sangam-process-diagram>` | `process-definition-id` (required), `process-instance-id`, `overlay` (`tokens`, `heat`, `duration`), `height` (120-4000), `label`, `lang` | The BPMN diagram with the instance's tokens when an instance is given, and the data-table alternative. The bpmn.io watermark stays visible (LIC-8) |

Events bubble and cross the shadow boundary (`composed`): `sangam-task-completed` `{ taskId, result }`,
`sangam-error` `{ taskId? , status, code?, title }` and `sangam-element-selected` `{ definitionId, activityId, name? }`.

Each element renders into its own open shadow root, so the host's CSS and Sangam's CSS never mix. Fonts are inherited
from the host page (browsers ignore `@font-face` inside a shadow root). The host page's `<html lang>` and storage are
left alone.

**Token handoff.** The user signs in to the host application, which obtains an access token from the same OIDC issuer
that the Sangam server trusts (`sangam.security` issuer and audience settings). `getAccessToken` returns it. Sangam
puts it only in the `Authorization: Bearer` header of its own requests. It is never stored, never logged and never
written to an attribute, so a token never appears in the page's HTML. All authorisation stays on the server: an
element shows exactly what the token's user may see, and a hidden task answers as not available.

**CORS.** When `apiBase` is on another origin, add the host page's origin (scheme, host and port, no path) to
`sangam.security.cors.allowed-origins` on the server. The server already allows the headers the elements send
(`Authorization`, `Content-Type`, `Idempotency-Key`, `If-Match`) and exposes `ETag` and `X-Total-Count`
(`SecurityConfig.corsSource`). Leave the list empty when the host page and the API share an origin, for example when the
host's reverse proxy serves `/api/v1`; that is the simpler set-up.

**CSP on the host page.** The module needs:
- `script-src` with the origin that serves `sangam-elements.js` (`'self'` when the host serves it);
- `connect-src` with the Sangam API origin (`'self'` for a same-origin `apiBase`);
- `style-src 'unsafe-inline'`, because the styles are placed in each shadow root as a `<style>` element, as the Sangam
  UI's own CSP allows (`index.html`);
- `img-src data:`, because the library build inlines its image assets as data URLs.

No other origin is contacted, so the elements work air-gapped (R56).

## End-to-end tests (M-048)

The Playwright suite lives in `e2e/` (`playwright.config.ts`, specs named `*.e2e.ts` so vitest never collects them).
It runs against a real stack, never mocks: the UI image at `SANGAM_E2E_BASE_URL` (default `http://localhost:8080`) and
the server in test mode (`deploy/docker/server-test-mode.Dockerfile`, in-memory H2, dev token). `SANGAM_DEV_TOKEN`
must be set to that server's development token. `e2e/support/global-setup.ts` seeds the data: it deploys
`e2e/fixtures/e2e-simple.bpmn` to the sandbox, starts an instance and records the ids. Specs that change data start
their own instance.

| Spec | Covers |
|---|---|
| `shell.e2e.ts` | dev sign-in; no request leaves the origin (R56); `/fonts/noto/*` byte-identical to `deploy/fonts`; Devanagari renders in Noto Sans Devanagari; Sakal Bharati and @fontsource never requested (decisions 120, 121) |
| `tasklist.e2e.ts` | inbox → detail → claim → complete through the decision dialog; a missing task shows "not available" |
| `ops-diagram.e2e.ts` | definition and instance diagrams; the bpmn.io watermark visible, inside the canvas and uncovered, checked by rendered geometry (LIC-8, RM-47) |
| `a11y.e2e.ts` | axe-core with colour contrast on the main screens; serious and critical violations fail (M-046) |
| `embed.e2e.ts` | the M-043 module in a host page: same origin, and cross origin when `SANGAM_E2E_CORS_ORIGIN` names an origin the server allows |

Status: written in Wave 4. `@playwright/test` (owner decision 106) is a pinned devDependency in `package-lock.json`;
its Chromium and Firefox browsers are installed by the local binary (`node_modules/.bin/playwright install`, never
`npx playwright`) only in the test phase, logged in the downloads log. Run order:
`npm run build:embed`, start the stack, then `npm run e2e`. The compose-backed CI job belongs to `ui.yml` (W4-C).

## Cross-cutting

| Concern | Implementation | Files |
|---|---|---|
| OIDC login (R50, R67) | oidc-client-ts, authorization code + PKCE, silent renew, tokens kept in sessionStorage only | `src/auth/AuthProvider.tsx`, `src/areas/auth/SignIn.tsx` |
| Dev token mode | A static token for the server's dev profile, with a warning banner | same |
| Seat context and "acted for" (S02, IX.5 rule 4) | Seat switcher in the header. The contract headers are `X-Sangam-Seat` for the substantive seat and `X-Sangam-Acting-For-Seat` for an additional charge or leave substitute | `AuthProvider.tsx`, `app/Layout.tsx` |
| Typed API client (ARCHITECTURE §4) | Covers: Bearer auth; `Idempotency-Key` on every mutation, reused on a safe retry (R41, CX-4); problem+json; `X-Total-Count` / `X-Total-Count-Capped` ("more than N", A-16); ETag / If-Match; `/engine-rest` base | `src/api/client.ts` |
| Contract adapters | Contract shapes are mapped to view models, tolerating proposed extra fields. R33 TypedValue maps are converted both ways | `src/api/adapters.ts`, `src/api/endpoints.ts` |
| i18n Hindi + English (R54, T32) | i18next. All strings live in `src/i18n/locales/{en,hi}.json`. There is a language switch in the banner and on sign-in. Number and date formats follow `en-IN` / `hi-IN`. `scripts/check-i18n.mjs` fails on any missing key; dynamic keys are listed in `scripts/i18n-dynamic.json` | `src/i18n/*` |
| Fonts, air-gapped (owner decisions 120, 121) | Noto Sans and Noto Sans Devanagari (OFL-1.1): the approved files in `sangam/deploy/fonts`, copied into the build as `dist/fonts/noto/` by `scripts/deploy-fonts.mjs`, which fails the build when a file differs from its recorded SHA-256. No @fontsource packages and no Sakal Bharati. The language → font map puts a site's state-government font first, then a central one, then Noto. Per-tenant override comes from `/config.json` `fonts` or `/fonts/site/fonts.json`. See [FONTS.md](FONTS.md) | `src/fonts.ts`, `scripts/deploy-fonts.mjs`, `vite.config.ts`, `FONTS.md` |
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
  - DSC signing of a task PDF by the assignee (`TaskDscSign.tsx`, decisions 165, 227, 228, 236-238, 241): `POST /tasks/{id}/dsc/prepare` and `/dsc/complete` through the loopback agent; the task is then completed with the usual action, with no second signature. Shown only when `dsc.agentUrl` is set.
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
- **`areas/dsc/DscCertificates.tsx` (decision 227).**
  - `/my/dsc-certificates` (`MyDscCertificates`, task.write): enrol the certificate of my DSC token (read through the agent) or from a DER / PEM file, list my enrolments, revoke with a reason.
  - `/admin/dsc-certificates` (`DscCertificateQueue`, `dsc.certificate.approve`): approval queue by status; approve (reason optional), reject and revoke (reason required, at most 512 characters); an admin never decides their own enrolment (SELF_APPROVAL).
  - A revocation voids signatures on tasks not yet completed: the step must be signed again (decision 241).

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
    - CMMN 1.1 diagram editor of Sangam's own on diagram-js 14.11.3 (owner decision 161, `components/cmmn/`): plan items, stages, milestones, entry and exit criteria and sentry on-parts, a toolbar and outline usable from the keyboard, CMMNDI written on save (a layout is generated for files without one), parts it does not show kept unchanged; the XML source view with its plan outline is the fallback and flags the listeners rejected at deploy (D-08);
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

C = implemented in the UI. "UI ready; server [P]" = the screen called a proposed endpoint when this map was written (2026-11-04). The [P] markers are kept as history: every static API path the UI calls is now in the contract (see API reconciliation, "Former proposals", and "Module routes" below). Paths were matched by script; request and response shapes have not been verified against a running server.

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
| 43 | No released modeller, no CMMN editor | Modeller (+ own CMMN 1.1 diagram editor with XML fallback, decision 161) |
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
| react-router-dom | 7.18.5 | MIT | Routing |
| i18next | 23.16.8 | MIT | i18n |
| react-i18next | 15.1.4 | MIT | i18n |
| oidc-client-ts | 3.1.0 | Apache-2.0 | OIDC login |
| bpmn-js | 17.11.1 | bpmn.io licence (not OSI; allowed with the watermark visible, LIC-8) | BPMN viewer / modeller |
| dmn-js | 16.8.2 | bpmn.io licence (as above) | DMN viewer / modeller |
| @bpmn-io/form-js-editor, @bpmn-io/form-js-viewer | 1.12.0 | bpmn.io licence (as above) | Form builder (modeller only) |
| Noto Sans, Noto Sans Devanagari (files from `sangam/deploy/fonts`, not npm) | 2.015 / 2.007 | OFL-1.1 | Bundled fonts (owner decisions 70, 95, 120); provenance and SHA-256 in `deploy/fonts/README.md` |
| typescript (dev) | 5.6.3 | Apache-2.0 | Type check |
| vite (dev) | 8.3.5 | MIT | Build (Rolldown bundler, Oxc transforms, Lightning CSS minifier; M-117) |
| @vitejs/plugin-react (dev) | 6.1.2 | MIT | Build |
| rolldown, @rolldown/pluginutils (dev, pinned by `overrides`) | 1.2.15 / 1.0.1 | MIT | Build; pinned to the releases paired with vite 8.3.5 so the lockfile does not float to newer builds |
| vitest (dev) | 4.1.12 | MIT | Unit tests |
| jsdom, @testing-library/dom, @testing-library/react (dev) | 25.0.1 / 10.4.0 / 16.1.0 | MIT | Test environment |
| axe-core (dev) | 4.14.0 | MPL-2.0 | Accessibility checks in component tests (M-046, `src/test/axe.ts`, `src/a11y.test.tsx`); not shipped |
| @types/react, @types/react-dom (dev) | 18.3.12 / 18.3.1 | MIT | Types |

The full installed tree is generated by `npm run licences`. The list below is its 2026-11-13 output after the Vite 8 upgrade (M-117), installed on Windows x64 with `npm install --ignore-scripts`: 280 packages, of which 151 are "runtime" (in the production dependency tree in `package-lock.json`) and 129 are "build" (build and test tooling, not shipped in `dist/`). Native binaries for other platforms (`@rolldown/binding-*`, `lightningcss-*`) are in the lockfile but are not installed here; they carry the same licences as their parent package (MIT and MPL-2.0). Apart from bpmn.io:
- everything is OSI-approved;
- `component-props` declares MIT in its `component.json` and nothing in `package.json`; the nine bpmn.io packages declare `SEE LICENSE IN LICENSE`. Both readings are recorded per exact version under `reviewed_licences` in `deploy/licence/licence-policy.json`;
- `lightningcss` (MPL-2.0, file-level copyleft) is the Vite 8 CSS minifier. It is used unmodified at build time and is not shipped in `dist/`;
- `@csstools/color-helpers` is MIT-0 (OSI-approved), a jsdom test dependency. MIT-0 is in `deploy/licence/licence-policy.json` (coordinator ruling 2026-11-13), the one policy file that `scripts/list-licences.mjs` reads (RM-51: the script has no allow-list of its own), so `npm run licences` reports 0 packages needing a check;
- Vite 8 and @vitejs/plugin-react 6 no longer use the Babel compiler (`@babel/core` and its plugins), esbuild, Rollup or browserslist, so they are gone from the tree, and so is `caniuse-lite` (CC-BY-4.0). `@babel/code-frame` and `@babel/runtime` remain as dependencies of other packages.

<details><summary>All installed packages and licences</summary>

| Package | Version | Licence | Scope |
|---|---|---|---|
| @asamuzakjp/css-color | 3.2.0 | MIT | build |
| @babel/code-frame | 7.29.7 | MIT | build |
| @babel/helper-validator-identifier | 7.29.7 | MIT | build |
| @babel/runtime | 7.29.13 | MIT | runtime |
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
| @csstools/color-helpers | 5.1.0 | MIT-0 | build |
| @csstools/css-calc | 2.1.4 | MIT | build |
| @csstools/css-color-parser | 3.1.0 | MIT | build |
| @csstools/css-parser-algorithms | 3.0.5 | MIT | build |
| @csstools/css-tokenizer | 3.0.4 | MIT | build |
| @ibm/telemetry-js | 1.12.0 | Apache-2.0 | runtime |
| @jridgewell/sourcemap-codec | 1.6.0 | MIT | build |
| @lezer/common | 1.5.5 | MIT | runtime |
| @lezer/highlight | 1.2.5 | MIT | runtime |
| @lezer/html | 1.3.13 | MIT | runtime |
| @lezer/json | 1.0.3 | MIT | runtime |
| @lezer/lr | 1.4.12 | MIT | runtime |
| @lezer/markdown | 1.8.1 | MIT | runtime |
| @marijn/find-cluster-break | 1.0.4 | MIT | runtime |
| @microsoft/teams-js | 2.59.0 | MIT | runtime |
| @oxc-project/types | 0.155.0 | MIT | build |
| @rolldown/binding-win32-x64-msvc | 1.2.15 | MIT | build |
| @rolldown/pluginutils | 1.0.1 | MIT | build |
| @standard-schema/spec | 1.1.0 | MIT | build |
| @testing-library/dom | 10.4.0 | MIT | build |
| @testing-library/react | 16.1.0 | MIT | build |
| @types/aria-query | 5.0.4 | MIT | build |
| @types/chai | 5.2.3 | MIT | build |
| @types/deep-eql | 4.0.2 | MIT | build |
| @types/estree | 1.0.10 | MIT | build |
| @types/prop-types | 15.7.15 | MIT | build |
| @types/react | 18.3.12 | MIT | build |
| @types/react-dom | 18.3.1 | MIT | build |
| @types/trusted-types | 2.0.7 | MIT | runtime |
| @vitejs/plugin-react | 6.1.2 | MIT | build |
| @vitest/expect | 4.1.12 | MIT | build |
| @vitest/mocker | 4.1.12 | MIT | build |
| @vitest/pretty-format | 4.1.12 | MIT | build |
| @vitest/runner | 4.1.12 | MIT | build |
| @vitest/snapshot | 4.1.12 | MIT | build |
| @vitest/spy | 4.1.12 | MIT | build |
| @vitest/utils | 4.1.12 | MIT | build |
| agent-base | 7.1.4 | MIT | build |
| ansi-regex | 5.0.1 | MIT | build |
| ansi-styles | 4.3.0 | MIT | build |
| ansi-styles | 5.2.0 | MIT | build |
| aria-query | 5.3.0 | Apache-2.0 | build |
| array-move | 4.0.0 | MIT | runtime |
| assertion-error | 2.0.1 | MIT | build |
| asynckit | 0.4.0 | MIT | build |
| atoa | 1.0.0 | MIT | runtime |
| axe-core | 4.14.0 | MPL-2.0 | build |
| base64-js | 1.5.1 | MIT | runtime |
| big.js | 6.2.2 | MIT | runtime |
| bpmn-js | 17.11.1 | SEE LICENSE IN LICENSE | runtime |
| bpmn-moddle | 8.1.0 | MIT | runtime |
| call-bind-apply-helpers | 1.0.2 | MIT | build |
| chai | 6.3.0 | MIT | build |
| chalk | 4.1.2 | MIT | build |
| classnames | 2.5.1 | MIT | runtime |
| clsx | 2.1.1 | MIT | runtime |
| color-convert | 2.0.1 | MIT | build |
| color-name | 1.1.4 | MIT | build |
| combined-stream | 1.0.8 | MIT | build |
| component-event | 0.2.1 | MIT | runtime |
| component-props | 1.1.1 | MIT | runtime |
| component-xor | 0.0.4 | MIT | runtime |
| contra | 1.9.4 | MIT | runtime |
| convert-source-map | 2.0.0 | MIT | build |
| cookie | 1.1.1 | MIT | runtime |
| crelt | 1.0.7 | MIT | runtime |
| css.escape | 1.5.1 | MIT | runtime |
| cssstyle | 4.6.0 | MIT | build |
| csstype | 3.2.3 | MIT | build |
| data-urls | 5.0.0 | MIT | build |
| debug | 4.4.3 | MIT | runtime |
| decimal.js | 10.6.0 | MIT | build |
| delayed-stream | 1.0.0 | MIT | build |
| dequal | 2.0.3 | MIT | build |
| detect-libc | 2.1.2 | Apache-2.0 | build |
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
| dom-accessibility-api | 0.5.16 | MIT | build |
| dom-iterator | 1.0.2 | MIT | runtime |
| domify | 3.0.0 | MIT | runtime |
| domify | 3.0.0 | MIT | runtime |
| domify | 3.0.0 | MIT | runtime |
| domify | 3.0.0 | MIT | runtime |
| domify | 1.4.2 | MIT | runtime |
| domify | 3.0.0 | MIT | runtime |
| domify | 3.0.0 | MIT | runtime |
| dompurify | 3.4.17 | (MPL-2.0 OR Apache-2.0) | runtime |
| dunder-proto | 1.0.1 | MIT | build |
| entities | 6.0.1 | BSD-2-Clause | build |
| es-define-property | 1.0.1 | MIT | build |
| es-errors | 1.3.0 | MIT | build |
| es-module-lexer | 2.3.2 | MIT | build |
| es-object-atoms | 1.1.2 | MIT | build |
| es-set-tostringtag | 2.1.0 | MIT | build |
| escape-html | 1.0.3 | MIT | runtime |
| estree-walker | 3.0.3 | MIT | build |
| expect-type | 1.4.0 | Apache-2.0 | build |
| fake-indexeddb | 6.2.8 | Apache-2.0 | build |
| fdir | 6.5.0 | MIT | build |
| feelers | 1.5.1 | MIT | runtime |
| feelin | 3.2.0 | MIT | runtime |
| flatpickr | 4.6.13 | MIT | runtime |
| focus-trap | 8.2.4 | MIT | runtime |
| form-data | 4.0.6 | MIT | build |
| function-bind | 1.1.2 | MIT | build |
| get-intrinsic | 1.3.0 | MIT | build |
| get-proto | 1.0.1 | MIT | build |
| gopd | 1.2.0 | MIT | build |
| has-flag | 4.0.0 | MIT | build |
| has-symbols | 1.1.0 | MIT | build |
| has-tostringtag | 1.0.2 | MIT | build |
| hasown | 2.0.4 | MIT | build |
| htm | 3.1.1 | Apache-2.0 | runtime |
| html-encoding-sniffer | 4.0.0 | MIT | build |
| html-parse-stringify | 3.1.0 | MIT | runtime |
| http-proxy-agent | 7.0.2 | MIT | build |
| https-proxy-agent | 7.0.6 | MIT | build |
| i18next | 23.16.8 | MIT | runtime |
| iconv-lite | 0.6.3 | MIT | build |
| ids | 1.0.5 | MIT | runtime |
| inferno | 5.6.3 | MIT | runtime |
| inferno-shared | 5.6.3 | MIT | runtime |
| inferno-vnode-flags | 5.6.3 | MIT | runtime |
| inherits-browser | 0.1.0 | ISC | runtime |
| is-potential-custom-element-name | 1.0.1 | MIT | build |
| js-tokens | 4.0.0 | MIT | runtime |
| jsdom | 25.0.1 | MIT | build |
| jwt-decode | 4.0.0 | MIT | runtime |
| lezer-feel | 1.9.0 | MIT | runtime |
| lightningcss | 1.33.0 | MPL-2.0 | build |
| lightningcss-win32-x64-msvc | 1.33.0 | MPL-2.0 | build |
| lodash | 4.18.1 | MIT | runtime |
| loose-envify | 1.4.0 | MIT | runtime |
| lru-cache | 10.4.3 | ISC | build |
| luxon | 3.7.2 | MIT | runtime |
| lz-string | 1.5.0 | MIT | build |
| magic-string | 0.30.21 | MIT | build |
| marked | 15.0.12 | MIT | runtime |
| math-intrinsics | 1.1.0 | MIT | build |
| mime-db | 1.52.0 | MIT | build |
| mime-types | 2.1.35 | MIT | build |
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
| ms | 2.1.3 | MIT | runtime |
| nanoid | 3.3.20 | MIT | build |
| nwsapi | 2.2.28 | MIT | build |
| object-refs | 0.4.0 | MIT | runtime |
| obug | 2.2.1 | MIT | build |
| oidc-client-ts | 3.1.0 | Apache-2.0 | runtime |
| opencollective-postinstall | 2.0.3 | MIT | runtime |
| parse5 | 7.3.0 | MIT | build |
| path-intersection | 3.1.0 | MIT | runtime |
| pathe | 2.0.3 | MIT | build |
| picocolors | 1.1.1 | ISC | build |
| picomatch | 4.0.7 | MIT | build |
| postcss | 8.5.32 | MIT | build |
| preact | 10.29.8 | MIT | runtime |
| pretty-format | 27.5.1 | MIT | build |
| punycode | 2.3.1 | MIT | build |
| react | 18.3.1 | MIT | runtime |
| react-dom | 18.3.1 | MIT | runtime |
| react-i18next | 15.1.4 | MIT | runtime |
| react-is | 17.0.2 | MIT | build |
| react-router | 7.18.5 | MIT | runtime |
| react-router-dom | 7.18.5 | MIT | runtime |
| rolldown | 1.2.15 | MIT | build |
| rrweb-cssom | 0.8.0 | MIT | build |
| rrweb-cssom | 0.7.1 | MIT | build |
| safer-buffer | 2.1.2 | MIT | build |
| saxen | 8.1.2 | MIT | runtime |
| saxes | 6.0.0 | ISC | build |
| scheduler | 0.23.2 | MIT | runtime |
| selection-ranges | 3.0.3 | MIT | runtime |
| selection-ranges | 4.1.1 | MIT | runtime |
| selection-update | 0.1.2 | MIT | runtime |
| semver | 7.8.6 | ISC | runtime |
| set-cookie-parser | 2.7.3 | MIT | runtime |
| siginfo | 2.0.0 | ISC | build |
| source-map-js | 1.2.2 | BSD-3-Clause | build |
| stackback | 0.0.2 | MIT | build |
| std-env | 4.3.0 | MIT | build |
| style-mod | 4.1.4 | MIT | runtime |
| supports-color | 7.2.0 | MIT | build |
| symbol-tree | 3.2.4 | MIT | build |
| tabbable | 6.5.1 | MIT | runtime |
| table-js | 9.4.0 | MIT | runtime |
| ticky | 1.0.1 | MIT | runtime |
| tiny-svg | 3.1.3 | MIT | runtime |
| tinybench | 2.9.0 | MIT | build |
| tinyexec | 1.3.1 | MIT | build |
| tinyglobby | 0.2.17 | MIT | build |
| tinyrainbow | 3.2.0 | MIT | build |
| tldts | 6.1.86 | MIT | build |
| tldts-core | 6.1.86 | MIT | build |
| tough-cookie | 5.1.2 | BSD-3-Clause | build |
| tr46 | 5.1.1 | MIT | build |
| typescript | 5.6.3 | Apache-2.0 | build |
| vite | 8.3.5 | MIT | build |
| vitest | 4.1.12 | MIT | build |
| void-elements | 3.1.0 | MIT | runtime |
| w3c-keyname | 2.2.8 | MIT | runtime |
| w3c-xmlserializer | 5.0.0 | MIT | build |
| webidl-conversions | 7.0.0 | BSD-2-Clause | build |
| whatwg-encoding | 3.1.1 | MIT | build |
| whatwg-mimetype | 4.0.0 | MIT | build |
| whatwg-url | 14.2.0 | MIT | build |
| why-is-node-running | 2.3.0 | MIT | build |
| ws | 8.22.1 | MIT | build |
| xml-name-validator | 5.0.0 | Apache-2.0 | build |
| xmlchars | 2.2.0 | MIT | build |
</details>

## Generic modules, R46 and Operaton parity

- **Declarative engine:** `src/modules/engine.tsx` (list, detail, action dialogs).
- **Screens:** `src/modules/{config,finance,registry,resources,casework,programme,fieldgeo,compliance}.tsx`.
- **R46:** `npm run compat` regenerates `src/modeller/compatibility.json` and `test-plan/R46-ROUND-TRIP-TESTS.md` from the bpmn.io palettes, the Operaton parsers and `scripts/compat-rules.json`. The matrix is at `/modeller/compatibility`; the package workspace scans models and asks for acknowledgement before submit or deploy.
- **Operaton parity:** `OPERATON-UI-PARITY.md`, with screens in `src/modules/operaton.tsx` on `/engine-rest`.
- **Onboarding:** `/admin/onboarding` uses the contract `/onboarding/**` routes. The request parts are JSON editors, not a guided form.
- **UI plugins:** `src/plugins/host.tsx` reads the authenticated list at `/plugins/ui` and imports entries from `/plugins/ui/{id}/assets/{entry}` by same-origin dynamic import, under strict `script-src 'self'`. Needs anonymous GET on that asset route (WS-CONFORMANCE). Plugin code must not contain secrets; see `examples/ui-plugin-hello/README.md`.
- **Request bodies:** registry, resources, geo and field operations have no body schemas in the OpenAPI file (derived from controllers), so their form field names are unverified. Each dialog has a JSON editor for corrections.

## Module routes (reconciled 2026-11-12)

`src/modules/platform.tsx` has the screens for sangam-ai (`/m/ai`), sangam-channels (`/m/channels`), the modeller tools (`/modeller/tools`) and the ops tools (`/ops/tools`). Every path below is in `sangam-rest/api/sangam-api.yaml` (checked by script on 2026-11-12) and has a controller:

- **AI** (`rest/modules/ai/AiUiController`, the console view; `AiController` for the expressions): `/ai/agent-runs`, `/ai/agent-approvals`, `/ai/knowledge-bases`, `/ai/drafts`, `/ai/classifications`, `/ai/expressions/{evaluate,propose}`.
- **Channels** (`rest/modules/channels/ChannelsController`): `/channels/conversations` (`GET` takes `caseRef`), `/channels/conversations/{id}/attachments`, `/channels/opt-ins` (`GET` lists them), `/channels/status`.
- **Modeller** (`rest/modules/modeller/ModellerController` and `ServiceMocksController`; `rest/web/PackageToolsController` for the tests run): `/landscape`, `/models/token-walk`, `/models/{file}/lease`, `/templates`, `/actions`, `/actions/{key}/run`, `/catalogue`, `/service-mocks`, `/packages/{id}/tests/run`.
- **Ops** (`rest/modules/ops/OpsUiController`, the console view over the same services and checks as `OpsController` `/ops-admin`): `/search`, `/ops/usage`, `/ops/outliers`, `/ops/variables`, `/ops/secrets`, `/ops/sequences`, `/ops/index-jobs`, `/ops/retention/{plan,execute}`, `/ops/migration-status`.

Field names are not verified where the contract gives no body schema (see "Request bodies" above). The header comment of `src/modules/platform.tsx` still says these routes are not in the contract; that comment is stale (code, not changed in this docs pass).

Not built: bpmn-js custom palette and form-js composite components (templates are managed as data but not injected into the editors), the action menu inside task and case screens, test report export. The Teams tab is an optional module (owner decision 162): see [TEAMS.md](TEAMS.md).

## Integration notes

- **Heavy-build lock.** The task text names `heavy_lock.py run`, but the shared script (`…/D--GITHUB--Project-IFMS/…/scratchpad/heavy_lock.py`) offers only `acquire` / `release` / `status`. `npm install` and `npm run build` were therefore run inside `acquire customwf … release customwf`, with release in a `finally` block.
- **UI image.** The image serves `dist/` and a runtime `/config.json`, and proxies `/api` and `/engine-rest` (WS-DEPLOY §10). SPA fallback to `index.html` is required for deep links and for `/auth/callback`.
- **OIDC client.** The public client `sangam-ui` needs redirect URI `<origin>/auth/callback` and post-logout URI `<origin>/`.
- **Roles.** Roles are read from `/me.roles`, then `/me.groups`, then the token claims `roles` / `realm_access.roles` / `groups`. The expected role names are TASK_USER, OPERATOR, PLATFORM_OPERATOR, ADMIN, PLATFORM_ADMIN, MODELLER, REVIEWER, ANALYST, AUDITOR, INTEGRATION and CITIZEN.

## Gaps

- There is no browser or end-to-end test suite, and no screen has been exercised against a live server in a browser. Vitest unit and component tests exist: 8 `*.test.ts(x)` files under `src` with 93 tests (53 in `src/modules/operaton-parity.test.tsx`, 19 in `ui-parity-2.test.tsx`, 4 in `registryNumbering.test.ts` for SL-126, the rest in logic, Hindi locale, task form, plugin host and BPMN diagram tests). Core flows (task decisions, case workspace, admin, audit verify, portal, the declarative domain modules) have no component tests. CI runs the tests in `ui.yml` (step "Unit tests", `npm run test`, which is `vitest --run`). `TESTING-GAP-REGISTER.md` records a vitest jsdom run for the UI-parity rows (SL-37); the current tree has not been run since.
- The [P] markers in the coverage map are historical; the endpoints are in the contract (see the coverage-map legend). End-to-end behaviour against a server is unverified.
- `/m/*`, `/m/ai`, `/m/channels`, `/notifications` and `/x/:pluginId/:id` have no route `Guard` in `App.tsx`; they are hidden in the menu by role, and only the server enforces access.
- Hash-chain linkage is checked on the client only when AuditEntry carries `prevHash`. The cryptographic recomputation is done server-side by `/audit/verify`.
- R70 platform console / backup orchestration (PF-25 / 26) and R75 IDP (PF-31) have no screen (Stage D / E, outside this UI's C scope).
- There is no maintained bpmn.io CMMN modeller, so CMMN editing is source + outline, not graphical.
- Collaborative multi-user presence in the modeller (R69 Stage E) is not built. Comments, history and Git are.
- WCAG conformance is designed in but not yet verified with an audit tool or assistive-technology testing. Hindi strings are an author's translation and need review by a native reviewer.
