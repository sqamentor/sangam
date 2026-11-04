# Premium-feature catalogue and universal scope (ADR-D-14, ADR-D-15)

Date: 2026-11-05. Status: **research input**. Every new requirement below (R63–R84) and every new pattern (UP-01…UP-22) is **PROPOSED**, not adopted. Nothing here is implemented or tested.

**Inputs:**
- `SANGAM.md` X.0c (ADR-D-14, ADR-D-15), X.1 (R1–R62), X.4.7 (screen inventory S/C/O/A/U/I), VIII.9 (stages);
- `spike/GOV-PATTERNS.md` (GP-01…GP-40);
- `spike/results/scorecard.md` (Operaton 2.2.0 chosen, 85.2 vs 72.1);
- local checkouts `reference/operaton` (2.3.0-SNAPSHOT) and `reference/flowable-engine` (main, after 8.0.0).

**Evidence tags:**
- **[F]**: read on the official page cited (fetched 2026-11-05).
- **[S]**: search-result summary of the cited page; the page itself was not fetched.
- **[L]**: read in the local source checkout. This is not a release artefact; it was not checked against a release tag.
- **[I]**: our inference.
- **[U]**: unverified; check before relying on it.

**Pricing:** none of the vendors publishes a price list.
- Camunda's pricing page offers a free Development tier and then "Talk to sales" for Production and SaaS [F] (https://camunda.com/pricing/).
- Flowable's product page offers a trial, a demo and a contact form, with no prices [F] (https://www.flowable.com/products).
- No Camunda 7 Enterprise price was found.

No prices are given or estimated in this document.

---

## 0. Edition facts that frame the catalogue

| Fact | Source |
|---|---|
| Camunda 7 **Community Edition reached end of life in October 2025**: 7.24 (14 Oct 2025) is the last CE feature release, with no further CE releases or security patches, and the repository is archived | [S] https://camunda.com/platform-7/ ; https://forum.camunda.io/t/camunda-7-community-end-of-life-eol-clarification/60208 |
| Camunda 7 **Enterprise** EoL extended to **9 Apr 2030**, with an optional paid extended support to Apr 2032 [S, the 2032 date comes from one source, U] | [S] https://camunda.com/blog/2025/02/camunda-7-enterprise-end-of-life-extension/ |
| Camunda 8: **Zeebe, Operate, Tasklist, Identity, Optimize** have source under the Camunda License v1, and the compiled software is proprietary. **Production self-managed use requires the Enterprise Edition** | [F] https://docs.camunda.io/docs/reference/licenses/ |
| Camunda 8: **Web Modeler, Console, Camunda Hub**: source is proprietary ("on demand"), and production use requires Enterprise | [F] same page |
| Camunda 8: **Apache 2.0**: Connector SDK, REST connector, Connector Runtime and Bundle images. **All other OOTB connectors** are under the Camunda License v1 and need Enterprise for production. **Desktop Modeler** is MIT | [F] same page |
| Optimize is described as "business intelligence tooling for Camunda enterprise customers"; for Camunda 7 it was an EE-only component | [F] https://docs.camunda.io/optimize/components/what-is-optimize/ ; [S] https://forum.camunda.io/t/can-we-use-camunda-optimize-with-non-enterprise-bpm-platform/37450 |
| Flowable OSS 7.0 **removed the UI applications** (modeler, task, admin). Flowable Design has a 30-day trial, after which export of non-standard model types (data objects, service registry) is limited | [F] https://www.flowable.com/blog/releases/flowable-open-source-7-0-0-release |
| Flowable Control is **deprecated** (limited maintenance to 31 Dec 2027; removed from 2026.2). **Flowable Hub** is its successor | [F] https://documentation.flowable.com/latest/admin/release-notes/2026.1.0-release ; [S] https://documentation.flowable.com/latest/user/control/dashboards |
| Operaton 2.2 is "feature complete and API-compatible with Camunda 7 CE 7.24". webapps-neo is a **technical preview**, off by default (`operaton.bpm.webapp.neo.enabled=true`) | [F] https://docs.operaton.org/docs/documentation/reference/release-notes/2_2/ |

**Consequence [I]:** Operaton 2.2 inherits the Camunda 7 **CE** scope, so **none of the Camunda 7 EE-only Cockpit features** listed in §1.1 come with it, except where webapps-neo has started to rebuild them (§1.4).

---

## 1. Task 1: premium-feature catalogue

**Column meanings:**
- **Op OSS / Fl OSS**: what Operaton 2.2 / Flowable 8 OSS already provide free.
  - "UI" means a shipped user interface.
  - "API" means engine/REST capability with no UI.
  - "neo" means present in the webapps-neo source [L] (preview, not a stable release).
- **Sangam coverage**: C = covered by an existing requirement or screen; P = partial; N = not covered; X = deliberately not replicated.
- **New R**: the proposal in §2.

### 1.1 Camunda 7 Enterprise Edition

Tier for every row: Camunda 7 EE (subscription; production support to Apr 2030), unless noted.

| ID | Feature | What it does | Op OSS | Fl OSS | Sangam coverage | New R | Source |
|---|---|---|---|---|---|---|---|
| PF-01 | Cockpit **history views** | Definition and instance history: finished and cancelled instances, audit log, job log, external-task log, variable change log, called instances, executed decisions, incidents, user tasks | neo has a history mode (`?history=true`, executed-activity badges) [L] | API only | **P**: S11 case timeline, U01 audit search, R42 (business audit); there is no generic operator history UI | R63 | [F] https://docs.camunda.org/manual/7.24/webapps/cockpit/bpmn/process-history-views/ |
| PF-02 | **Heatmap** | Token counts overlaid on the BPMN diagram per node and flow | No (no heatmap in neo [L]) | No | **N**: named in Part I §8 design text only, with no R | R63 | [F] same page |
| PF-03 | **Instance modification** UI | Move, skip or cancel tokens in a running instance | API | API [U] | **C**: O03 "modify (skip/move token)" + R49 limits | — | [F] https://docs.camunda.org/manual/7.24/webapps/cockpit/bpmn/process-instance-modification/ |
| PF-04 | **Instance migration** UI | Map activities between versions and migrate instances, also as a batch | API + neo `Migrations` page [L] | API (migration + batch service) [L] | **P**: R48 contract, GP-20; no UI requirement | R63 | [F] https://docs.camunda.org/manual/7.24/webapps/cockpit/bpmn/process-instance-migration/ |
| PF-05 | **Instance restart** UI | Restart completed or cancelled instances from chosen activities | API [I] | No [U] | **N** | R63 | [F] https://docs.camunda.org/manual/7.24/webapps/cockpit/bpmn/process-instance-restart/ |
| PF-06 | **Batch operations** UI | Bulk delete, retries, suspend, migrate, set variables and so on, with monitoring | API + neo `Batches` page [L] | API (batch service) [L] | **P**: O02 guarded bulk retry, R26 admin permission, VI.1 rule 3 (one root per transaction) | R64 | [F] https://docs.camunda.org/manual/7.24/webapps/cockpit/batch/batch-operation/ |
| PF-07 | Cockpit **reports** | Process-instance duration report and completed-task report | API (history reports) [I] | No | **N** | R65 | [F] https://docs.camunda.org/manual/7.24/webapps/cockpit/reporting/ |
| PF-08 | **DMN live editing** | Edit decision tables in Cockpit, then download or redeploy | No | No | **C**: A04 + R43 (governed change with four-eyes; deliberately not unreviewed live edit) | — | [F] https://docs.camunda.org/manual/7.24/webapps/cockpit/dmn/live-editing/ |
| PF-09 | Cockpit **operation auditing** | Who changed what in Cockpit, and when | User-operation-log API [I] | No | **C**: R42 (mandatory audit), O03 mandatory reason | — | [F] https://docs.camunda.org/manual/7.24/webapps/cockpit/auditing/ |
| PF-10 | **Cleanup view** | History-cleanup status, data statistics, manual trigger | Engine cleanup, no UI | Cleanup API [I] | **P**: R29 (cleanup service); no UI | R63 | [F] https://docs.camunda.org/manual/7.24/webapps/cockpit/cleanup/ |
| PF-11 | **Search tasks** / open-tasks dashboard | Search all tasks; save and share queries | Legacy tasklist filters (CE) [I] | No | **C**: S03 inbox + R25 (literal saved filters) | — | [F] https://docs.camunda.org/manual/7.24/webapps/cockpit/tasks-dashboard/ |
| PF-12 | **CMMN case views** | Case instance view in Cockpit | No | No | **N**: R45 is CMMN at Stage E; no operator view | R63 | [F] https://docs.camunda.org/manual/7.24/webapps/cockpit/cmmn/case-instance-view/ |
| PF-13 | Dashboard **metrics** plugin | Execution metrics on the Cockpit dashboard | neo `plugins/bundled/metrics` [L] | No | **C**: O01 + R13 | — | [S] https://docs.camunda.org/manual/latest/webapps/cockpit/dashboard ; [F] https://docs.camunda.org/manual/7.23/update/ce-to-ee ("Metrics" section) |
| PF-14 | **Optimize** (for Camunda 7) | Reports, dashboards, KPIs, alerts, branch and task analysis, collections, from a separate analytics store | No | No | **N**: R15 is XES export only | R65 | [F] https://docs.camunda.io/optimize/components/what-is-optimize/ ; [S] forum thread above |
| PF-15 | **Enterprise support / LTS patches** (C7 EE; C8 "24×7 support with SLAs") | Security patches and fix releases twice a year to 2030; SLA-backed support | Community only | Community only | **N**: R5/R32 define a matrix and an upgrade window, but no published patch lifecycle | R68 | [S] https://camunda.com/blog/2025/02/camunda-7-enterprise-end-of-life-extension/ ; [F] https://camunda.com/pricing/ |

**Not premium in Camunda 7:**
- **LDAP** is a CE engine plugin (`identity-ldap`) [S] (https://docs.camunda.org/manual/7.4/user-guide/process-engine/identity-service/).
- **OAuth2 SSO** for the webapps is in the CE `camunda-bpm-spring-boot-starter-security` [S] (https://docs.camunda.org/manual/latest/user-guide/spring-boot-integration/spring-security/). Its stated limit is that users see only their own user and groups in Admin.
- Both become premium in Camunda 8 (PF-16).

### 1.2 Camunda 8: commercial / self-managed Enterprise licence

Tier for every row: free for **non-production** only; **production needs Self-Managed Enterprise** (or SaaS). Exceptions are noted.

| ID | Feature | What it does | Op OSS | Fl OSS | Sangam coverage | New R | Source |
|---|---|---|---|---|---|---|---|
| PF-16 | **SSO, LDAP / Active Directory** | Enterprise identity integration | `identity-ldap` plugin [L]; OIDC through configuration [U] | `flowable-ldap`, `flowable-spring-security` [L] | **P**: R50 (SAML 2.0, OIDC); LDAP appears only in Part I §5 text; no SCIM | R67 | [S] https://forum.camunda.io/t/camunda-8-self-managed-free-edition-in-production-and-enterprise-feature-differences/68245 (**automated bot answer: low confidence [U]**) |
| PF-17 | **Operate** | Incidents, variable edit, batch retry/cancel, modification, batch move, migration, suspend/resume, delete, audit operations, wait states | Legacy Cockpit (CE) + neo partial | No UI | **P**: O01–O09, R48, R49 | R63 | [F] https://docs.camunda.io/docs/components/operate/operate-introduction/ |
| PF-18 | **Tasklist** | User-task UI with forms | Legacy Tasklist + neo `Tasks` [L] | **No** (removed in 7.0) [F] | **P**: S03/S08 + R47 cover the government inbox; there is no generic (seat-free) inbox | R76 | [S] https://docs.camunda.io/docs/components/tasklist/introduction-to-tasklist/ |
| PF-19 | **Optimize** (C8) | Reports, dashboards, process KPIs, alerts, branch analysis, task analysis, user-task analytics, collections, maintained dashboards | No | No | **N** | R65 | [F] https://docs.camunda.io/optimize/components/userguide/process-analysis/process-analysis-overview/ ; [S] alerts: https://docs.camunda.io/docs/components/optimize/userguide/additional-features/alerts/ |
| PF-20 | **Process mining / variant analysis** | Discovery, variants, conformance | No | No | **P**: R15 (XES export, Stage E) | R66 | **[U]**: not confirmed on any fetched Camunda page |
| PF-21 | **Web Modeler / Camunda Hub**: collaboration | Browser modelling in shared projects, versions, READMEs, multi-user | Desktop modeler only (`operaton-modeler`, MIT, ported from Fluxnova) [S] | None | **P**: A06/A07, R43, R46 | R69 | [S] https://docs.camunda.io/docs/components/modeler/web-modeler/use-shared-project-for-organization-wide-collaboration/ ; https://github.com/operaton (operaton-modeler) |
| PF-22 | Web Modeler **Git sync, CI/CD** | Pull, merge and push to GitHub/GitLab/Azure/Bitbucket; snapshot tags; pipeline deploy | No | No | **N** | R69 | [S] https://docs.camunda.io/docs/components/modeler/web-modeler/process-applications/git-sync/ ; https://docs.camunda.io/docs/components/modeler/web-modeler/integrate-web-modeler-in-ci-cd/ |
| PF-23 | **Play / Test mode** | Run and test a process from the modeller | No | No | **P**: A06 "simulate", I02 sandbox | R69, R80 | [S] https://docs.camunda.io/docs/8.8/components/modeler/web-modeler/ |
| PF-24 | **Identity / Admin + multi-tenancy** | OIDC (Keycloak, Entra ID, Okta), users, groups, roles, tenants | Admin webapp, authorizations, tenant ids [F intro] | IDM engine API, no UI [L] | **C**: R19, R50, A01, A05, A08 | — | [S] https://docs.camunda.io/docs/8.8/components/identity/identity-introduction/ ; https://docs.camunda.io/docs/8.8/components/identity/tenant/ |
| PF-25 | **Console** | Cluster management and monitoring | No | No | **N** | R70 | [F] licences page (proprietary component) |
| PF-26 | **Cluster backups, custom data retention** | Backup orchestration; retention policies | Cleanup API; no backup tooling | Same | **P**: R29 retention; restore is tested only at Stage D | R70 | [S] forum bot answer above (**low confidence [U]**) |
| PF-27 | **Out-of-the-box connectors** (non-Apache set) | Prebuilt integrations; the SDK and REST connector are Apache 2.0 | `connect-plugin` (HTTP/SOAP) [L] | `flowable-http`, `flowable-mail`, `flowable-camel` [L] | **P**: R27 egress; R58–R60 government connectors only | R71 | [F] licences page |
| PF-28 | **Agentic AI** | AI Agent connector (task and ad-hoc sub-process variants), tool calls, document input, model-call limits | No | No | **P**: R14, R37 (Stage E) | R72 | [S] https://docs.camunda.io/docs/components/connectors/out-of-the-box-connectors/agentic-ai-aiagent-subprocess/ ; https://docs.camunda.io/docs/next/components/connectors/out-of-the-box-connectors/agentic-ai-aiagent-documents/ |
| PF-29 | **RPA** | Robot Framework scripts on a standalone RPA worker for systems without APIs; document keywords | No | No | **N** | R73 | [S] https://docs.camunda.io/docs/components/rpa/overview/ ; pricing page lists RPA as "Non-production" in the free tier [F] |
| PF-30 | **Document handling** | Document store (AWS S3, Azure Blob, GCP; local/in-memory for development only) used by forms, connectors, Tasklist and the API | Task attachments API [I] | `flowable-content-api` only [L] | **P**: R47 attachments, R29 references > 256 KB | R74 | [S] https://docs.camunda.io/docs/self-managed/concepts/document-handling/getting-started/ |
| PF-31 | **IDP** | IDP applications and extraction templates in Web Modeler; extracted data used in BPMN | No | No | **N** | R75 | [S] https://docs.camunda.io/docs/components/modeler/web-modeler/idp/ |
| PF-32 | **Engine for production** (Zeebe) | The engine itself is licensed for production | Apache 2.0 | Apache 2.0 | **C**: ADR-D-14, X.3 licence inventory | — | [F] licences page |

### 1.3 Flowable Enterprise (Work / Design / Control / Hub / Inspect / Engage; "Case Platform" and "Agentic Case Platform" in 2026.1)

Tier for every row: commercial subscription (trial available; no public price), unless noted.

| ID | Feature | What it does | Op OSS | Fl OSS | Sangam coverage | New R | Source |
|---|---|---|---|---|---|---|---|
| PF-33 | **Flowable Work** end-user app | Work items (cases, processes, tasks) with ownership, due dates and priority; forms; case documents; contacts; reports; dashboards. Can also run headless | Legacy Tasklist | **None** | **P**: S01–S15 are government-specific; no generic case workspace | R76 | [F] https://documentation.flowable.com/latest/user/work/work-introduction |
| PF-34 | **Rich forms** (designer + runtime) | Flowable forms; OSS forms are not compatible with Work/Design | Camunda Forms / form-js [I] | Form API and model only [L] | **C**: R47 forms contract | — | [S] https://forum.flowable.org/t/difference-between-community-edition-and-enterprise/3420 |
| PF-35 | **Pages** | "Flowable Pages Definitions": low-code UI pages | No | No | **N** | R77 | [F] https://documentation.flowable.com/latest/user/design/introduction |
| PF-36 | **Flowable Design** modeller | Apps, BPMN, CMMN, DMN, forms, pages and "other" models, with version history | Desktop modeler (BPMN, DMN, Forms; no CMMN editor, see Part I §8 note) | None | **P**: A06, R46 | R69 | [F] same page |
| PF-37 | Design **Git connectivity, deployment approval, test models** (2026.1) | Git integration; approval workflow for app deployments; test-model feature | No | No | **P**: approval is covered (A07, R43); Git and tests are not | R69, R80 | [F] 2026.1 release notes |
| PF-38 | **Data objects / data dictionary** | DB-backed or REST-backed business objects; ERD view; references between instances | No | No | **N** | R78 | [S] https://documentation.flowable.com/latest/develop/be/data-models ; [F] 2026.1 release notes (ERD, data object references) |
| PF-39 | **Service registry** | Model external services once and reuse them in processes, cases and data objects | No | No | **P**: A10 endpoints, R27 | R71 | [S] https://www.flowable.com/blog/business/flowable-technical-capabilities-enterprise-operations-platform |
| PF-40 | **SLA models** | SLA for tasks, cases and processes (2025.2), with email actions and templates (2026.1) | Timers only | Timers only | **P**: R31 (timer policy), GP-06, AC-06 | R79 | [S] https://documentation.flowable.com/latest/admin/release-notes/2025.2.01-release ; [F] 2026.1 notes |
| PF-41 | **Full audit trails** | Task assignment and variable-change audit | History + user operation log | History | **C**: R42 | — | [F] Flowable capabilities blog above |
| PF-42 | **Business-user dashboards and reports** | Dashboards without developers; agentic dashboard components | No | No | **N** | R65 | [F] same blog; 2026.1 notes |
| PF-43 | **Flowable Control** (operations; deprecated) | Engine visibility: failing processes, case data changes, deployments, cluster resource and throughput dashboards | Legacy Cockpit (CE) | No | **P**: O01–O09 | R63 | [S] https://documentation.flowable.com/latest/user/control/dashboards ; https://documentation.flowable.com/latest/user/control/platform/ |
| PF-44 | **Flowable Hub** (successor to Control) | Apps, definitions and instances; **assisted case/process migration**; job-execution health dashboard; agent dashboard; licence info | neo partial | No | **P**: R48, O-screens | R63 | [F] 2026.1 notes ; [S] https://documentation.flowable.com/latest/user/hub/introduction |
| PF-45 | **Flowable Inspect** | Debug and test: trigger timers, send signals and messages, change variables, agent instance view. Disabled by default; not for production | Test helpers (assertions) [I] | Test helpers [I] | **P**: I02 sandbox, R18 test engine | R80 | [S] https://documentation.flowable.com/latest/user/inspect |
| PF-46 | **Agents** (Agentic Case Platform) | Configurable autonomy, guardrails on requests and responses, MCP, agent evaluators, OpenAI Responses API, token-count dashboards | No | No | **P**: R14 (Stage E) | R72 | [F] 2026.1 notes |
| PF-47 | **Flowable Engage** | Conversational and messaging engagement (named product) | No | No | **N** | R81 | [F] product listed in https://documentation.flowable.com/latest/user/user-introduction ; features **[U]** |
| PF-48 | **Flowable Mobile** | Mobile client (named product) | No | No | **P**: S15 (offline field app) | R76 | [F] same page; features **[U]** |
| PF-49 | **Multi-tenant administration** | Tenant management view in multi-tenant Design (3.16); multi-tenant cloud | Tenant ids + Admin | Tenant ids | **C**: A01, R19 | — | [S] https://documentation.flowable.com/latest/admin/release-notes/3.16.0-release |
| PF-50 | **Content / documents and output templates** | Case documents, document and content-item events (2026.1); document templates **[U]** | Attachments | Content API only | **P**: S12 output documents, R47 attachments | R74 | [F] work introduction; 2026.1 notes |
| PF-51 | **Enterprise Cloud** (hosted) | Vendor-hosted SaaS | — | — | **X**: Sangam is self-hosted (R56). Running a SaaS is out of scope; R70 supplies the tooling an operator needs | — | [F] https://www.flowable.com/products |
| PF-52 | Control **fine-grained operator permissions + operator audit log** (3.12) | Per-user permissions for operator functions (for example reindex, delete deployment); audit table of Control actions | Authorizations + operation log | No | **C**: R19, R26, R42 | — | [S] https://documentation.flowable.com/latest/admin/release-notes/3.12.0-release |

### 1.4 Operaton: what is free today, and the gaps

| Area | Free in Operaton | Evidence |
|---|---|---|
| Engines | BPMN 2.0, CMMN 1.1, DMN 1.3; REST; Spring Boot starter; Quarkus extension; engine plugins `identity-ldap`, `connect-plugin`, `spin-plugin` | [F] https://docs.operaton.org/docs/documentation/introduction/ ; [L] `reference/operaton/engine-plugins`, `quarkus-extension`, `spring-boot-starter` |
| Legacy webapps | Cockpit, Tasklist and Admin at **Camunda 7 CE scope**. They are AngularJS (EOL), so Sangam does not ship them (LIC-6) | [F] intro page; SANGAM X.3a LIC-6 |
| webapps-neo (preview) | Pages: Dashboard, Processes (with **history mode** and token view), Decisions, Deployments, **Batches**, **Migrations**, Tasks, StartProcessList, Admin, Account, Setup; plugin host/registry; bundled **metrics** plugin; locales de, en, es, fr, nl | [L] `reference/operaton/webapps-neo/frontend/src/pages`, `src/plugins` ; [F] 2.2 notes (preview, disabled by default) |
| Modeller | `operaton-modeler`: desktop BPMN, DMN and Forms on bpmn.io, MIT, ported from Fluxnova Modeler. No published release was found **[U]** | [S] https://github.com/operaton ; https://awesome.ecosyste.ms/projects/github.com%2Foperaton%2Foperaton-modeler |

**Gaps against the premium catalogue** (all must be built by Sangam):
- **Operations UI that is missing:** heatmap; restart UI; reports; cleanup view; CMMN views.
- **Operations UI that exists only as preview pages in neo:** history, migration and batches.
- **Products with no equivalent:**
  - Optimize-class analytics;
  - process mining;
  - web collaborative modelling with Git sync and test mode;
  - console and backup tooling;
  - a connector catalogue beyond HTTP/SOAP;
  - AI agents, RPA, IDP;
  - a document store;
  - SLA models, data objects, pages;
  - debugger;
  - conversational channels;
  - a published LTS policy.

**Premium buildability view [I]:**
- **Operaton** gives a usable UI or API starting point for 30 of 52 catalogue rows (UI for about 10, many only as preview).
- **Flowable OSS** gives API-only starting points for about 20 and **no UI for any row**.

This supports, but does not change, the Stage B result (scorecard.md).

---

## 2. Proposed new requirements R63–R84

**Stage meanings (VIII.9):**
- C = full pilot.
- D = production readiness.
- E = expansion.
- "C→D" means a pilot subset in C and full scope in D.

**Rules that apply to all of R63–R84:**
- Every screen and every read goes through R19 (`AuthorizedQueryBuilder`).
- Every bulk or remote effect uses R36 operation identity.
- Everything ships under the Sangam open-source licence with **no feature gating, licence key or usage cap**, per ADR-D-14.

| REQ | Title | Normative statement | Stage | Closes |
|---|---|---|---|---|
| R63 | **Operations console** (Cockpit/Operate/Control/Hub parity) | The console MUST provide:<br>• definition and instance runtime + history views (audit, job, external-task, variable-change logs);<br>• a **heatmap** and duration overlay;<br>• incident handling;<br>• instance **modification, restart and migration** UIs (migration plan, validation, dry-run count, batch execution);<br>• a cleanup/retention view;<br>• deployment view;<br>• DMN decision-instance and CMMN case-instance views.<br>Every state-changing action MUST require a reason, show a consequence preview (as in O03/O08), honour R49 limits and be audited (R42). | C (O01–O09 + history + migration) → D (rest); CMMN views E | PF-01, 02, 04, 05, 10, 12, 17, 43, 44 |
| R64 | **Bulk / batch operations** | Bulk retry, cancel, suspend/resume, migrate, modify, set-variable and delete MUST run as asynchronous batches with:<br>• a pre-execution count from the same R19 predicate;<br>• progress, pause, resume and cancel;<br>• a per-item result and failure list;<br>• one root per transaction (VI.1 rule 3);<br>• an admin permission (R26);<br>• four-eyes above a configurable item count or value. | C | PF-06, GP-08 |
| R65 | **Process analytics and BI** | Sangam MUST provide reports, dashboards, process KPIs with targets, threshold **alerts**, duration/branch/task/user-task analysis and sharing. They MUST run on a separate read model fed from history or the outbox, so that analytics load cannot affect runtime. Every figure MUST use the R19 predicates; a dashboard never reveals counts of hidden records. | D (core) → E (advanced) | PF-07, 14, 19, 42 |
| R66 | **In-product process mining** | On top of XES/OCEL export (R15), Sangam MUST offer process discovery, variant analysis, conformance checking against the deployed BPMN, and bottleneck analysis, tenant-scoped and authorized. "Mining" MUST NOT be claimed without conformance fixtures. | E | PF-20, UP-07 |
| R67 | **Identity federation** | In addition to R50 (SAML 2.0, OIDC), Sangam MUST support:<br>• LDAP/Active Directory (read and scheduled sync, group mapping);<br>• SCIM 2.0 provisioning;<br>• MFA policy pass-through;<br>• just-in-time user creation.<br>All of these are free and none is gated. | C (OIDC, LDAP) → D (SCIM) | PF-16 |
| R68 | **Published support lifecycle** | Sangam MUST publish:<br>• LTS lines and their end dates;<br>• a security-patch policy (severity → maximum days to fix);<br>• signed advisories and SBOMs;<br>• an upgrade path (R32).<br>Patches MUST be available to everyone, with no paid patch stream. Commercial support is optional and MUST NOT carry exclusive fixes. | D | PF-15 |
| R69 | **Collaborative web modeller** | A browser modeller MUST cover BPMN, DMN and forms (CMMN and data models in E), with:<br>• multi-user presence and locking or merge;<br>• comments;<br>• version history and diff;<br>• **Git sync** (GitHub, GitLab, Gitea, generic);<br>• a test/play mode against a sandbox tenant;<br>• deployment only through R43 review and approval.<br>The bpmn.io watermark rule (Part I §8) applies. | C (BPMN/DMN/forms, single-user + R43) → E (collaboration, CMMN) | PF-21, 22, 23, 36, 37 |
| R70 | **Platform console and lifecycle** | Sangam MUST provide:<br>• cluster and tenant inventory;<br>• node health;<br>• guided rolling upgrades (R12, R32);<br>• **backup and restore orchestration** with verification;<br>• retention configuration;<br>• Helm and an operator;<br>• usage metrics for capacity planning only (never for licensing). | D | PF-25, 26 |
| R71 | **Connector catalogue and service registry** | An Apache-2.0 connector SDK and a declarative **service registry** (typed operations, auth reference, capability class a/b/c per R36) MUST exist. The built-in set MUST include:<br>• REST, SOAP, GraphQL;<br>• e-mail;<br>• SFTP/file;<br>• webhook inbound;<br>• Kafka, AMQP;<br>• JDBC;<br>• S3-compatible storage;<br>• chat notifications.<br>All connectors are bound by R27 and R36. | C (REST, e-mail, SFTP, webhook) → E (breadth) | PF-27, 39 |
| R72 | **AI-agent orchestration** | Sangam MUST provide:<br>• an agent task type (an ad-hoc sub-process whose tools are connectors, workers or MCP);<br>• pluggable model providers, including self-hosted and offline models;<br>• guardrails on input and output;<br>• evaluators;<br>• token and cost budgets with a dashboard;<br>• a mandatory human-review task for write actions.<br>Everything inherits R14 and R37. | E | PF-28, 46, UP-06 |
| R73 | **RPA worker** | An open-source RPA worker (for example Robot Framework, Apache 2.0) MUST run scripts as worker tasks under the R9 lease. Scripts MUST be versioned in the package (R43). The worker MUST support document upload/download keywords, attended and unattended bot pools, and bot credentials held by reference only. | E | PF-29, UP-20 |
| R74 | **Document service** | Sangam MUST provide a document service with:<br>• pluggable storage (S3-compatible, Azure Blob, GCS, file system; DB for small files);<br>• immutable versions with checksums;<br>• a malware-scan hook;<br>• preview;<br>• template-based generation (PDF/A, bilingual);<br>• retention and legal hold (R29, R61);<br>• document references passed to forms, workers and agents instead of content. | C (store + references) → D (generation, scan) | PF-30, 50 |
| R75 | **Intelligent document processing** | Sangam MUST provide:<br>• extraction templates using pluggable OCR and LLM engines (an offline option is required for R56);<br>• per-field confidence;<br>• routing to a human validation task below a threshold;<br>• validated results fed back as evaluation data. | E | PF-31, UP-06 |
| R76 | **Universal work UI** | A seat-free generic inbox and task UI MUST exist alongside the government screens. It MUST include:<br>• a start catalogue for processes and cases;<br>• a **case workspace** (CMMN stages, milestones, discretionary items, documents, comments, participants);<br>• responsive/mobile layout;<br>• i18n (R54) and accessibility (R55). | C (inbox and task) → E (case workspace) | PF-18, 33, 48 |
| R77 | **Low-code pages** | A versioned page model (lists, detail views, dashboards, actions) MUST bind to data objects (R78) and named queries (R25). It is deployed in the package (R43) and is authorized on the server, never only on the client. | E | PF-35 |
| R78 | **Data objects and data dictionary** | Typed business entities, backed by a DB table or a REST service through R71, MUST have:<br>• a data dictionary and ERD;<br>• schema versioning;<br>• field-level authority (R19);<br>• references from processes, forms and rules;<br>• R36 identity for REST-backed writes. | D → E | PF-38 |
| R79 | **SLA model** | Declarative SLAs per task, case and process MUST include:<br>• target, warning and breach thresholds;<br>• a business calendar (R31);<br>• pause/resume conditions (for example "waiting for customer");<br>• escalation actions (reassign, notify, raise priority);<br>• SLA reports in R65.<br>Timers implement R31 semantics. | C (subset for AC-06) → D | PF-40, UP-04, UP-10 |
| R80 | **Model test harness and debugger** | Sangam MUST provide:<br>• model unit tests runnable from the modeller and CI;<br>• scenario record and replay (R42 replay purpose);<br>• a non-production debugger (step, trigger timers, send messages and signals, edit variables).<br>The debugger MUST be impossible to enable on a production profile. | D | PF-23, 37, 45 |
| R81 | **Conversational and messaging channels** | Chat and messaging adapters (for example Matrix, Teams, Slack, WhatsApp Business) MUST be able to start processes and complete simple tasks. The channel identity MUST map to a principal (R19) and must never be trusted alone for sensitive actions. | E | PF-47 |
| R82 | **Routing and assignment strategies** | Declarative assignment MUST support round-robin, least-loaded, skills/attribute match, SLA-priority ordering and pull queues with claim limits. Candidates are computed on the server and re-validated at commit (as in S07). | D | UP-04, UP-10 |
| R83 | **Event-stream ingestion and correlation** | Stream sources (Kafka and similar) MUST feed R38 with:<br>• keyed correlation;<br>• time windows;<br>• de-duplication and debounce;<br>• out-of-order and late-event policy;<br>• aggregation of many events into one process start or message.<br>Bounded state and back-pressure follow R40. | E | UP-03, UP-21 |
| R84 | **MySQL Tier-1 conditions** (amends R17 per ADR-D-15) | On MySQL, Sangam MUST:<br>• pin MySQL 8.4 LTS;<br>• use fractional-second date/time columns (`DATETIME(3)` or finer) in **all** Sangam and engine tables;<br>• enforce READ COMMITTED per session (startup check fails otherwise);<br>• use `utf8mb4` with a binary/case-sensitive collation;<br>• create the business-key unique constraints that R38 `uniqueStart` relies on;<br>• prove the R2 acquisition SQL, lock timeouts and `jdbcStatementTimeout` behaviour with the shipped (MariaDB) driver. | B (finding) → C | §4 findings M-1…M-5 |

**Count:** 22 new requirements (R63–R84). R63–R81 close premium features; R82–R83 come from the universal patterns; R84 comes from the database note.

---

## 3. Task 2: universal (non-government) scope

### 3.1 New universal patterns UP-01…UP-22

| ID | Pattern | Typical domains | Engine/product concern | Nearest GP | Requirements |
|---|---|---|---|---|---|
| UP-01 | **High-throughput straight-through processing** (no human step; thousands of instances per second; minimal history) | Payments, card authorisation follow-up, e-commerce orders, telecom activation | History level per definition, partitioned history, no hot root, outbox drain | GP-08 (bulk) | R22, R29, R40 + new test |
| UP-02 | **Saga across microservices** (forward steps, compensations, unknown outcomes, timeouts per step) | Order-to-cash, travel booking, telecom provisioning, loan disbursal | Compensation + R36 identity + reconciliation | GP-11, GP-12, GP-13 | R36, R37, R23 |
| UP-03 | **Event-stream correlation** (windowed, many-to-one, out-of-order, late events) | Fraud signals, IoT telemetry, shipment tracking | Stream-to-message bridge with bounded state | GP-15 (partial) | R38, R83 |
| UP-04 | **SLA-based routing / skills-based assignment** | ITSM, claims, contact centres, healthcare triage | Assignment strategies, SLA priority, workload | GP-06, GP-17 (partial) | R79, R82 |
| UP-05 | **Four-eyes / dual control with separation of duties** (maker ≠ checker, thresholds, no self-approval through delegation) | Banking payments, limit changes, master-data change | Payload-hash-bound approvals, SoD checks | GP-01, GP-34 | R19 (VI.4 rule 5), R43, O05 — **covered** |
| UP-06 | **Human-in-the-loop AI review** (agent or IDP proposes; a human approves, edits or rejects below a confidence threshold; feedback captured) | KYC, claims triage, invoice capture, prior authorisation | Confidence routing, bound approval of agent output, evaluation data | — | R14, R72, R75 |
| UP-07 | **Process-mining / continuous-improvement loop** (measure → discover → change → redeploy → compare) | All domains | Analytics read model, version comparison | GP-36 (partial) | R65, R66, R43 |
| UP-08 | **Long-lived entity lifecycle** (customer, policy, contract or asset, with events years apart) | Insurance policy, telecom subscription, asset management | Continue-as-new, business-key uniqueness, message buffering | GP-10 | R49, R38 |
| UP-09 | **Dynamic case with ad-hoc work and external experts** | Claims investigation, disputes, peer review in prior authorisation, legal matters | CMMN/ad-hoc, discretionary tasks, external participants | GP-21 | R45, R76 |
| UP-10 | **Tiered escalation and swarming** (priority-dependent SLA, major-incident bridge, parallel resolver groups) | ITSM incident, customer complaints | Non-interrupting escalations, re-prioritisation in flight | GP-06 | R79, R82 |
| UP-11 | **Change approval with scheduled window and rollback** (CAB, freeze calendar, implement in window, verify, roll back) | ITSM change, release management, manufacturing engineering change | Calendar gating, compensation as rollback | GP-03, GP-04, GP-24 | R31, R23 + new test |
| UP-12 | **Order decomposition and amendment in flight** (one order → many sub-orders; partial fulfilment; amend or cancel while running) | Telecom provisioning, B2B e-commerce | Dynamic multi-instance, message-driven amendment, partial compensation | GP-09, GP-14 | R36, R49 |
| UP-13 | **Returns, refunds and partial fulfilment** | E-commerce, retail | Split shipments, partial refunds, idempotent refunds | GP-13 | R36, R58-style connector via R71 |
| UP-14 | **Three-way match with tolerance exceptions** (PO ↔ goods receipt ↔ invoice) | Procure-to-pay | DMN tolerances, exception queue, supplier clarification | GP-29, GP-31, GP-16 | R8, R71 |
| UP-15 | **Periodic recertification at scale** (re-KYC, access reviews, licence renewals for every entity) | Banking, IAM, HR | Mass timers, bulk start, staggered load | GP-30, GP-08 | R31, R64 |
| UP-16 | **Fan-out provisioning and de-provisioning with deadline and evidence** (day-1 readiness; leaver revoke-all within N hours) | HR onboarding/offboarding, IAM | Parallel connectors, completion evidence, compensation on failure | GP-05, GP-33 | R71, R36, R42 |
| UP-17 | **Regulated clinical/benefit pathway** (consent check, minimum-necessary data, payer/provider callbacks, statutory decision clocks) | Healthcare referrals, prior authorisation | Consent gating, data minimisation, statutory timers, appeals | GP-35, GP-22, GP-15 | R53-style privacy controls, R79, R38 |
| UP-18 | **Non-conformance → CAPA with effectiveness check** (root cause, actions, verification after N days, recurrence linking) | Manufacturing, quality, pharma (GxP) | Delayed verification timers, linked cases, e-signature on closure | GP-22, GP-34 | R45, R57-style signing, R31 |
| UP-19 | **Contract lifecycle** (redlining loops, parallel legal/finance review, signature, obligation and renewal tracking) | Legal/CLM, procurement | Document versions, parallel review, obligation timers | GP-02, GP-03, GP-25, GP-30 | R74, R57-style signing, R79 |
| UP-20 | **RPA step against a legacy UI** (bot pool, attended/unattended, screen failures as incidents) | Insurance, banking back office, any legacy system | Bot lease, credential vaulting, evidence screenshots | — | R73 |
| UP-21 | **IoT/event-triggered process with storm control** (dedupe, debounce, threshold, auto-close on clear) | IoT maintenance, utilities, facilities | Mass starts from events, correlation and closure by later event | GP-15 | R83, R40 |
| UP-22 | **External-party collaboration** (supplier, customer or broker tasks via portal or magic link; no internal account) | P2P supplier onboarding, insurance brokers, admissions applicants | External principals, scoped one-time access, document exchange | GP-16 (citizen screens C01–C06 are the government analogue) | R19, R67, R76 |

### 3.2 Domains → patterns

| Domain | Typical processes | GP patterns reused | UP patterns needed | Key R |
|---|---|---|---|---|
| Banking: **KYC** | Onboarding, CDD/EDD, sanctions screening, periodic re-KYC | GP-16, GP-29, GP-01 | UP-05, UP-06, UP-15, UP-22 | R72, R75, R64 |
| Banking: **loan origination** | Application, bureau pull, scoring, credit approval by band, documentation, disbursal | GP-01, GP-05, GP-29, GP-12, GP-34 | UP-02, UP-05, UP-06 | R36, R43 |
| Banking: **disputes / chargebacks** | Intake, provisional credit, merchant representment, scheme deadlines, arbitration | GP-06, GP-15, GP-22, GP-35 | UP-09, UP-04 | R79, R38 |
| Insurance: **claims** | FNOL, triage, assessment, fraud check, reserve, settlement, recovery/subrogation | GP-16, GP-17, GP-12, GP-21 | UP-06, UP-09, UP-04, UP-20 | R72, R75, R79 |
| E-commerce / **order-to-cash** | Order, payment authorisation, fulfilment, shipment, invoice, returns | GP-12, GP-13, GP-14, GP-15 | UP-01, UP-02, UP-12, UP-13 | R22, R36, R71 |
| **Procure-to-pay** | Requisition, approval by band, PO, goods receipt, invoice match, payment, supplier onboarding | GP-01, GP-29, GP-31, GP-11 | UP-14, UP-22, UP-16 | R71, R78 |
| HR **onboarding / offboarding** | Offer, background check, provisioning, induction; leaver revocation, exit | GP-05, GP-18, GP-33 | UP-16, UP-15 | R67, R71 |
| Healthcare: **referrals / prior authorisation** | Referral intake, eligibility, clinical criteria, peer review, payer decision clock, appeal | GP-16, GP-22, GP-35, GP-15 | UP-17, UP-06, UP-09 | R75, R79 |
| Manufacturing / **quality (CAPA)** | Non-conformance, deviation, investigation, CAPA, effectiveness check, change control | GP-21, GP-22, GP-34 | UP-18, UP-11 | R45, R74 |
| **IT service management** | Incident, problem, change (CAB), request fulfilment | GP-06, GP-03, GP-04, GP-24 | UP-10, UP-11, UP-04 | R79, R82 |
| **Telecom provisioning** | Order capture, decomposition, network activation, number porting, fallout handling | GP-14, GP-09, GP-15 | UP-01, UP-02, UP-12, UP-08 | R36, R83 |
| **Education (admissions)** | Application window, document check, ranking, offers, waitlist, fee, enrolment | GP-24, GP-26, GP-28, GP-16, GP-08 | UP-22 | R64, R76 (largely covered by GP) |
| **Legal / contract lifecycle** | Request, drafting, redlining, approvals, signature, obligations, renewal | GP-02, GP-03, GP-25, GP-30, GP-34 | UP-19, UP-09 | R74, R79 |
| **AI-agent orchestration with human in the loop** | Agent research/draft, tool calls, human approval, audit of prompts and costs | GP-34 (bound approval) | UP-06, UP-07 | R14, R37, R72 |
| **IoT / event-driven** | Alarm → work order → dispatch → close on clear; predictive maintenance | GP-15, GP-17 | UP-03, UP-21, UP-01 | R83, R40 |
| **RPA / system integration** | Legacy data entry, report extraction, swivel-chair elimination | GP-27, GP-31 | UP-20, UP-02 | R73, R71 |

**Coverage summary:**
- 22 UP patterns are proposed.
- UP-05 is already covered by existing requirements; the other 21 need at least one new test, and most need R63–R83.
- All 16 domains reuse at least two GP patterns, so the government corpus remains the base corpus [I].

---

## 4. Task 3: database note (PostgreSQL, Oracle, SQL Server, MySQL)

### 4.1 Official / tested database support

| DB | Operaton 2.2: release notes [F] | Operaton: supported-environments page [F] | Operaton: local test containers [L] | Flowable 8 OSS: CI matrix [L] | Flowable Enterprise system requirements [F] |
|---|---|---|---|---|---|
| PostgreSQL | 14 or later | 14 / 15 / 16; Aurora PG 14 / 15 | `postgres:14` | 15, 16, 17, 18 | 13–14 (v3.13+), 15–17 (v3.17+); AWS Aurora |
| Oracle | 21 (unchanged) | 19c / 23ai | `oracle-xe:21-faststart` | 21, 23 (`gvenzl/oracle-*-slim-faststart`) | 19c, 21 (v3.13+), 23 (v3.17+) |
| SQL Server | 2022 (unchanged) | 2017 / 2019 / 2022; Azure SQL (VM, MI, DB) | `mssql/server:2022-latest` | 2019, 2022 | 2017, 2019, 2022 (v3.17+); Azure SQL |
| **MySQL** | **8.4 or later** | **8.0** | `mysql:8.4` | **8.0, 8.4** | 8.0, 8.4 (v3.17+) |
| MariaDB | 10.11 or later | not listed | `mariadb:10.11` | 10.6, 11.4 | 10.6, 11.4 |
| DB2 | unchanged | 11.5 (not z/OS) | `db2:12.1.2.0` | 11.5.9.0 | 10.x, 11.x (v3.17+) |

**Notes on the table:**
- The CI workflows in both repos run all of these databases: Operaton `integration-build.yml` and `unit-tests-with-databases.yaml`; Flowable `mysql.yml`, `oracle.yml`, `postgres.yml`, `sql-server.yml`, `mariadb.yml`, `db2.yml` [L].
- Flowable's OSS docs list only the database types (h2, mysql, oracle, postgres, db2, mssql), with no versions [F] (https://www.flowable.com/open-source/docs/bpmn/ch03-Configuration).
- The Flowable Enterprise table (https://documentation.flowable.com/latest/admin/installs/system-requirements) uses Enterprise version numbers (3.x). Whether it applies to OSS 8.0 is **[U]**.
- **Discrepancy:** the Operaton 2.2 release notes (https://docs.operaton.org/docs/documentation/reference/release-notes/2_2/) and its supported-environments page (https://docs.operaton.org/docs/documentation/introduction/supported-environments/) disagree on MySQL (8.4+ vs 8.0), on Oracle (21 vs 19c/23ai) and on MariaDB. Sangam should pin from the test containers and its own CI, and report the discrepancy upstream [I].

### 4.2 MySQL-specific limitations reported upstream

| # | Finding | Applies to | Evidence |
|---|---|---|---|
| M-1 | **No millisecond precision in the Camunda-7-lineage MySQL schema.** `TIMESTAMP`/`DATETIME` values are rounded to whole seconds. `sendFractionalSeconds=false` is "mandatory" on Connector/J (≥ 5.1.37). Due-date queries with milliseconds return nothing, and due dates are rounded. **Operaton keeps this:** its MySQL create scripts use plain `datetime` (for example `CREATE_TIME_ datetime`, `DUE_DATE_ datetime`), while its MariaDB, Oracle and other scripts use `datetime(3)`/`timestamp(3)`. **Flowable's MySQL schema uses `datetime(3)`/`timestamp(3)`.** | Operaton (chosen base) | [F] https://docs.camunda.org/manual/7.24/user-guide/process-engine/database/mysql-configuration/ ; [L] `reference/operaton/engine/src/main/resources/org/operaton/bpm/engine/db/create/activiti.mysql.create.engine.sql` vs `activiti.mariadb.create.*.sql` ; [L] `reference/flowable-engine/modules/flowable-engine/src/main/resources/org/flowable/db/create/flowable.mysql.create.*.sql` |
| M-2 | **Isolation:** the engine is developed and tested only on READ COMMITTED. REPEATABLE READ (the MySQL default) "is known to cause deadlocks". Clustered or replicated DBs must behave like READ COMMITTED. MySQL bug #112850 shows a deadlock that occurs only under READ COMMITTED, so switching levels can move a deadlock rather than remove it. | Both (Operaton documented) | [F] https://docs.camunda.org/manual/7.24/user-guide/process-engine/database/database-configuration/ ; [F] https://docs.camunda.org/manual/7.24/introduction/supported-environments/ ; [S] https://forum.camunda.io/t/read-committed-isolation-level-for-camunda/10948 ; [S] https://bugs.mysql.com/bug.php?id=112850 |
| M-3 | **Business-key uniqueness is not in the default schema.** `ACT_UNIQ_RU_BUS_KEY` / `ACT_UNIQ_HI_BUS_KEY` must be added manually on MySQL if relied upon. | Operaton | [F] database-configuration page above |
| M-4 | **`jdbcStatementTimeout` is ignored with JDBC batch processing on MariaDB** (and DB2). This matters because LIC-4 ships the **MariaDB driver for MySQL**, so the behaviour must be tested with that driver against MySQL. | Operaton; Sangam driver choice | [F] database-configuration page above; SANGAM X.0c (ADR-D-15) |
| M-5 | **Collation and case-sensitivity.** Flowable's own CI sets `utf8mb4` / `utf8mb4_bin` before its tests run. A user forum thread on `utf8mb4_unicode_ci` received only "should not have much influence, test first". Operaton's MySQL dialect adds no collation clause for case-sensitive comparisons (`databaseSpecificCollationForCaseSensitivity` is empty), so query case-sensitivity follows the database collation [I]. | Both | [L] `reference/flowable-engine/.github/workflows/mysql.yml` ; [S] https://forum.flowable.org/t/is-it-able-to-change-character-set-to-charset-utf8mb4-collate-utf8mb4-unicode-ci/10207 ; [L] `DbSqlSessionFactory.java` lines 217–262 |
| M-6 | **Historical:** MySQL < 5.6.4 has no millisecond precision. This is irrelevant for 8.x but explains M-1's origin. | Flowable docs | [F] https://www.flowable.com/open-source/docs/bpmn/ch03-Configuration |
| M-7 | **Driver licensing:** Connector/J is not bundled (LIC-4); the MariaDB driver is the shipped default. | Sangam | SANGAM X.0c, X.3a |

**Consequences for Sangam [I]:**
- **M-1 is the most important.** Lease expiry (R9, R21), lock timeouts (R3), timer due dates (R31) and per-root ordering would run at one-second resolution on MySQL if the inherited schema is used. R84 therefore requires fractional seconds in all tables through a Sangam R12 migration, with a test that compares behaviour across all four Tier-1 databases.
- **M-2:** an R84 startup check must refuse to start when the **session** isolation level is not READ COMMITTED. The forum reports that a global setting does not always reach application sessions.
- **M-3 and M-4:** fold into the R38 `uniqueStart` tests and the R2/R3 timeout tests on MySQL with the MariaDB driver.
- The MySQL support for `FOR UPDATE SKIP LOCKED` (8.0+) needed by R2/R28 is **not verified** here: no MySQL manual page was fetched **[U]**.

---

## 5. Counts

| Item | Count |
|---|---|
| Premium features catalogued | **52** (PF-01…PF-52): Camunda 7 EE 15, Camunda 8 17, Flowable Enterprise 20 |
| Already covered by R1–R62 / X.4 screens (C) | **11**: PF-03, 08, 09, 11, 13, 24, 32, 34, 41, 49, 52 |
| Partially covered (P) | **25** |
| Not covered (N) | **15** |
| Deliberately not replicated (X) | **1** (PF-51, hosted SaaS) |
| New requirements | **22**: R63–R84 (R63–R81 premium parity, R82–R83 universal patterns, R84 MySQL) |
| New universal patterns | **22**: UP-01…UP-22 |
| Domains mapped | 16 (banking ×3, insurance, order-to-cash, P2P, HR, healthcare, CAPA, ITSM, telecom, admissions, CLM, AI-HITL, IoT, RPA) |

## 6. Open verification items

1. Camunda 8 enterprise-only list for SSO, LDAP, backups and retention. The only source is an automated forum answer; check against the Camunda licence/pricing terms.
2. Camunda process-mining capability (PF-20) and Web Modeler comments (PF-21): not confirmed on a fetched page.
3. Flowable Engage, Mobile and document-template features (PF-47, PF-48, PF-50): only the product names are confirmed.
4. Whether the Flowable Enterprise database table applies to Flowable OSS 8.0: check the 8.0.0 tag's CI workflows. The matrix above is from local `main`.
5. The Operaton docs discrepancy on MySQL, Oracle and MariaDB versions (§4.1): raise it upstream.
6. The Camunda 7 Extended Support date of 2032 (single source).
7. Whether Flowable OSS has instance modification and restart APIs (PF-03, PF-05): check the local source.
