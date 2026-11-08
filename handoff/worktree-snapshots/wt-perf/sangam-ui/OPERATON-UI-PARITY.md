# Operaton UI parity table

Every page, plugin and action found in `reference/operaton/webapps/frontend/ui` (Cockpit, Tasklist, Admin, Welcome) and `reference/operaton/webapps-neo/frontend/src`, mapped to a Sangam screen. **Status is about code: nothing here has been run in a browser or against a server.**

No Operaton UI code was copied; screens are written from the REST contract. Every `/engine-rest` screen is limited to platform roles in the UI (owner decision); server enforcement is WS-REST's.

## Pages and plugins

| Operaton area | Sangam screen | Route |
|---|---|---|
| Cockpit dashboard, metrics | OpsDashboard; metrics chart | `/ops`, `/admin/engine` |
| Processes, thumbnails, definition page, version selector, instance search | ProcessDefinitions, DefinitionDetail | `/ops/processes` |
| Token, history, duration, job-definition, call-activity overlays | BpmnDiagram | same |
| Definition TTL, suspension, delete, job definitions, called definitions | Engine views | `/ops/engine` |
| Instance page: tree with filter, variables, incidents, called instances, cancel, suspend, modify, restart | InstanceDetail | `/ops/instances/:id` |
| Jobs, external tasks, user tasks, identity links, variables | Jobs; Engine views | `/ops/jobs`, `/ops/engine` |
| Decisions, DRD page, matched rules | Decisions, DrdPage | `/ops/decisions`, `/ops/drd/:id` |
| Deployments, preview, download, redeploy, delete with counts | Deployments; Engine views | `/ops/deployments`, `/ops/engine` |
| Batches, migration, batch modification | Batches, Migration; Engine views | `/ops/batches`, `/ops/migration` |
| Task dashboard, reports, cleanup, operation log | TaskDashboard, Reports, Cleanup, OperatorAudit | see nav |
| Tasklist: list, multi-key sort, claim, delegate, complete, forms, comments, diagram | TaskInbox, TaskDetail | `/tasks` |
| Filters: editor, criteria, variables, sharing, board; task search; sort by variable | TaskFiltersPage | `/tasks/filters` |
| Start process, standalone task | StartCatalog; Engine views | `/start`, `/ops/engine` |
| Admin: users, groups, tenants, authorizations, system, metrics, diagnostics | EngineAdminPage | `/admin/engine` |
| Welcome, account | Welcome, Account | `/welcome`, `/account` |
| Setup | Removed by owner decision: install-time bootstrap | — |
| Neo plugin host, points | `src/plugins/host.tsx`, `/admin/plugins` | `/x/:pluginId/:id` |

## Known limits

| Item | Limit |
|---|---|
| Embedded forms | Parsed into Sangam forms; `cam-script` logic does not run |
| External forms | Link only, no iframe |
| Multi-key sort | Server keys: created, due, priority, name. Amount and reference are not sortable: `TaskQuery` has no business-key order, and amount is a process variable whose type differs by process |
| Membership fields | Searchable list of ids; a typed id is still accepted |
| Matched rules | Depends on the rule id in `/history/decisions` and dmn-js `data-row-id`, both unverified |
| Locales de, es, fr, nl | Out of scope for now |

## Action-level audit (historic)

Taken before the later passes, so its Partial, Missing and In progress marks are out of date; **"Pages and plugins" and "Known limits" above are current.** Its rows are kept as the action inventory.

# Operaton to Sangam action parity

Status is from reading Sangam source only; nothing was run. Engine views = `/ops/engine`, Engine admin = `/admin/engine` (`modules/operaton.tsx`).

Method limits: Operaton templates were checked by extracting their controls, labels and `ng-click`s, plus the JS REST calls of the main dialogs, not by reading every line. REST paths for Operaton SDK resource calls follow the standard engine REST API; those not visible in source were not individually confirmed. Sangam calls to contract APIs (`/ops/*`) were checked in the UI only, not on the server.

## Cockpit

### Process definition

| Operaton action (dialog/file) | What it does | Sangam screen/action | Status |
|---|---|---|---|
| Version selector (`directives/change-version.html`) | Switch definition version | DefinitionDetail version select | Built |
| Suspend/activate (`processDefinition/update-suspension-state-dialog.html`) | Include instances, run now or at a date | DefinitionDetail dialog (include instances); Engine views > Definitions (executionDate) | Built (schedule only in Engine views) |
| Time to live (`directives/time-to-live/time-to-live-dialog.html`) | Set or clear TTL; offers removal-time batch | Engine views > Set TTL; Cleanup > Change TTL | Partial (TTL value is required, so it cannot be cleared; no batch link) |
| Instance table (`processDefinition/process-instance-table.html`) | List and open instances | DefinitionDetail, InstanceTable | Built |
| Diagram activity filter | Filter instances by activity | DefinitionDetail diagram selection | Built |
| Incidents tab (`pd-incidents-tab.html`) | Definition incidents | Link to `/ops/incidents?processDefinitionKey=` | Built |
| Called definitions table (`called-process-definition-table.html`) | Static called definitions | Engine views > Called definitions | Built (typed definition id) |
| Job definition table, suspension, override priority | Manage job definitions | Engine views > Job definitions | Built |
| Job definition diagram overlays, call-activity overlays, bulk job priority, process thumbnails | | | In progress |
| Job definition retries (neo `Processes.jsx`) | Set retries for all jobs of a definition | None | Missing |

### Process instance

| Operaton action (dialog/file) | What it does | Sangam screen/action | Status |
|---|---|---|---|
| Cancel (`cancel-process-instance-dialog.html`) | Delete with skip listeners, skip IO mappings; lists subprocess instances | InstanceDetail CancelDialog: mode, reason, preview | Partial (no skipCustomListeners/skipIoMappings) |
| Suspend/activate (`processInstance/update-suspension-state-dialog.html`) | Toggle instance state | InstanceDetail SuspendDialog | Built |
| Modify (neo `BPMNViewer.jsx`, single) | Token move/start/cancel | InstanceDetail ModifyDialog | Built |
| Modify as batch (neo `BPMNViewer.jsx`) | Async modification across instances | No MODIFY type in DefinitionDetail batches | Missing |
| Add variable (`components/variables/variable-add-dialog.html`) | Name, type, value | InstanceDetail Add variable | Partial (7 types only; no Short, Float, Object, Xml, Null, Bytes) |
| Edit variable (`variableInstancesTab.js`) | Update value | InstanceDetail Edit | Partial (no local execution-scope write; scope shown only) |
| Delete variable (neo `Processes.jsx`) | Remove a variable | None | Missing |
| Inspect object variable (`variable-inspect-dialog.html`) | Serialized/deserialized view and edit, type name | Value shown as JSON | Missing |
| Upload binary variable (`variable-upload-dialog.html`) | Upload file to variable | None | Missing |
| Download binary variable (`variableInstancesTab.js`) | Download value | None | Missing |
| Variable search (`variable-instances-tab-search-config.json`) | Filter and sort variables | None | Missing |
| Activity tree select (`directives/activity-instance-tree.html`) | Select node to filter tabs | Tree is display-only; diagram picks feed dialogs, not tabs | Partial (no tab filtering) |
| Tree quick filter (`directives/quick-filter.html`) | Name, running, completed, canceled | None | Missing |
| Incidents tab (`directives/incidents-tab.html`) | Instance incidents, cause and root-cause links, stack trace | Tree badge only; `/ops/incidents` has no instance filter | Partial (no per-instance list or cause links) |
| Incident job retry (`incident-job-retry-action.html`, `job-retry-dialog.html`) | Increment retries, keep or set due date | IncidentDetail Retry; Jobs Set retries with due date | Built |
| Incident external task retry (`externalTaskRetryDialog.js`) | Increment retries | IncidentDetail Retry; Engine views > External tasks | Built |
| Incident annotation (`incidentsTab.js`) | Set annotation | IncidentDetail note; Engine views > Annotation | Built |
| Bulk job retry (`job-retry-bulk-dialog.html`) | Pick failed jobs, keep or set due date, per-job result | Incidents bulk retry as batch | Partial (no due-date choice; Jobs page has no multi-select) |
| Jobs tab (`jobs-tab.html`) | Jobs of this instance | Job log tab is history; Jobs page has no instance filter | Partial (no runtime per-instance list) |
| Job suspend/activate (`jobsTab.js`) | Toggle one job | None | Missing |
| Reschedule job (`jobs-reschedule-modal.html`) | Recalculate from creation or now, or set date, with cascade | Jobs dialog new due date, sent on retries call as a UI proposal | Partial (no recalculate, no cascade, no standalone change) |
| Job stack trace (neo `Processes.jsx`) | Show trace | IncidentDetail only, platform operators | Partial (not on Jobs page) |
| User tasks tab (`user-tasks-table.html`) | Tasks, assignee, link to tasklist | Engine views > User tasks, typed processInstanceId | Partial (not on the instance page) |
| Identity links (`identity-links-modal.html`) | Add/remove candidate users and groups | Engine views > User tasks | Built |
| External tasks tab (`process-instance-runtime-external-tasks.html`) | List for instance | Engine views > External tasks | Built |
| External task unlock (neo `Processes.jsx`) | Release lock | None | Missing |
| External task error details (neo `Processes.jsx`) | Show full error | errorMessage column only | Missing |
| Called instances tab (`called-process-instance-table.html`) | Open called instances | InstanceDetail called log | Built |
| Operation log annotation (neo `Processes.jsx`) | Set or clear annotation | Audit tab is read-only | Missing |

### Decisions

| Operaton action (dialog/file) | What it does | Sangam screen/action | Status |
|---|---|---|---|
| Decision list, table, instances | Browse | Decisions | Built |
| Decision input/output tables | Show values | Decisions, Decision history | Built |
| Decision TTL (`pages/decision-definition.html`, `time-to-live`) | Set or clear TTL | None | Missing |
| DRD diagram page, matched-rule highlighting | | | In progress |

### Batches

| Operaton action (dialog/file) | What it does | Sangam screen/action | Status |
|---|---|---|---|
| Suspend/activate (`cam-cockpit-batch-view.html`) | Toggle batch | BatchDetail pause/resume | Built |
| Delete (`batches/templates/delete-modal.html`) | Keep or delete history | BatchDetail cancel; Engine views > Batch history delete | Partial (no cascade choice) |
| Retry all (`batches/components/batch.js`) | Increment retries for batch jobs | None | Missing |
| Failed jobs: retry one, delete one (`cam-cockpit-batch-view.html`) | Act on one failed job | Failed items list, read-only | Missing |
| Failed job stack trace, job log (`job-log.html`) | Diagnose | Item message only | Missing |
| Batch search (`cam-cockpit-batch-view.html`) | Search widget | Status filter | Partial (status only) |

### Deployments

| Operaton action (dialog/file) | What it does | Sangam screen/action | Status |
|---|---|---|---|
| Delete (`cam-cockpit-delete-deployment-modal.html`) | Cascade, skip listeners, skip mappings, instance counts | Engine views > Deployment resources > Delete | Partial (no counts before delete) |
| Redeploy | Redeploy | Engine views | Built |
| Create (neo `Deployments.jsx`) | Upload | Modeller upload | Built |
| Resource preview and download | | | In progress |

### Dashboards

| Operaton action (dialog/file) | What it does | Sangam screen/action | Status |
|---|---|---|---|
| Task dashboard links (`task-dashboard.html`) | Click a count to open matching tasks | TaskDashboard shows counts only | Partial (no click-through) |
| Reports, dashboard | View | Reports, OpsDashboard | Built |

## Tasklist

| Operaton action (dialog/file) | What it does | Sangam screen/action | Status |
|---|---|---|---|
| Filter sidebar (`cam-tasklist-filters.html`) | Pick a filter to drive the list | TaskFiltersPage section; inbox has no filters | Partial (not in inbox) |
| Filter general (`...form-general.html`) | Name, color, description, priority, refresh | Name, owner, properties JSON | Partial (JSON, no guided fields) |
| Filter criteria (`...form-criteria.html`) | Add/remove criteria | Query JSON | Partial (JSON) |
| Filter variables (`...form-variable.html`) | Variables shown on cards | Properties JSON; inbox does not show them | Partial (no effect in inbox) |
| Filter permissions (`...form-permission.html`) | Global read, add/remove user or group | Sharing screen, GRANT only | Partial (no global read) |
| Filter delete (`cam-tasklist-filter-modal.html`) | Delete | Filters > Delete | Built |
| Sort by variable (`cam-tasklist-sorting-inputs.html`) | Sort on a variable value | None | Missing |
| Multi-key sort, embedded/external forms | | | In progress |
| Task search (`cam-tasklist-search-plugin.html`) | Criteria search | Text and SLA filter | Partial (no field criteria) |
| Claim, unclaim (`cam-tasklist-task-meta.html`) | Claim | TaskDetail | Built |
| Set/reset assignee (`cam-tasklist-task-meta.js`) | Assign any user | TaskDetail reassign to seat; Engine views > Assign | Built |
| Due and follow-up dates (`cam-tasklist-task-meta.html`) | Set and reset inline | Engine views > Update, free text, name required | Partial (not in TaskDetail) |
| Groups (`cam-tasklist-groups-modal.html`) | Candidate groups | Engine views > identity links | Built |
| Comment (`cam-tasklist-comment-form.html`) | Add comment | TaskDetail Comments | Built |
| Generic form (`cam-tasklist-form-generic-variables.html`) | Add/remove variables, load variables | Renders existing variables only | Partial (cannot add variables) |
| Save and complete (`cam-tasklist-form.html`) | Save draft, complete | TaskDetail | Built |
| Task card variables (`cam-tasklist-variables.html`, detail modal) | View and download values | None in inbox | Missing |
| Start process (`cam-tasklist-process-start-modal.html`) | Search, pick, business key, start | StartCatalog | Partial (no search; no ad hoc variables) |
| Create task (`cam-tasklist-create-task-modal.html`) | Name, assignee, tenant, description | Engine views > New | Partial (no tenant) |
| Attachments (neo `Tasks.jsx`) | Add file or URL, download, delete | TaskDetail upload, download | Partial (no delete, no URL) |
| Tabs, history, diagram, shortcuts | View | TaskDetail, Layout | Built |

## Admin

| Operaton action (dialog/file) | What it does | Sangam screen/action | Status |
|---|---|---|---|
| Authorization edit (`authorizations.html`) | Inline update, all/none permission toggles | Create and delete; permissions JSON | Partial (no update, no toggles) |
| Authorization delete (`confirm-delete-authorization.html`) | Delete | Engine admin | Built |
| User create, profile, delete, unlock (`userCreate.html`, `userEdit.html`) | Manage users | Engine admin | Built (no password repeat) |
| Admin password reset | | | In progress |
| Group membership (`create-group-membership.html`) | Tick groups | Typed group id, one at a time | Partial (no picker) |
| Tenant membership for user (`create-tenant-user-membership.html`) | Tick tenants | Typed tenant id | Partial (no picker) |
| Tenant membership for group (`create-tenant-group-membership.html`, `groupEdit.html`) | Add, remove | Add only, typed id | Partial (no remove) |
| Group create, edit, delete | Manage | Engine admin | Built |
| Tenant create, delete | Manage | Engine admin | Built |
| Tenant rename (`tenantEdit.html`) | Update engine tenant | Sangam `/admin/tenants` only | Partial (engine tenant not updated) |
| Setup (`setup.html`) | First admin | `/setup` via `/engine-rest/user/create` | Partial (not the setup endpoint; no password repeat) |
| System, diagnostics | View | SystemInfo, Diagnostics | Built |
| Metrics chart | | | In progress |

## Welcome and account

| Operaton action (dialog/file) | What it does | Sangam screen/action | Status |
|---|---|---|---|
| Profile update (neo `Account.jsx`) | Edit own profile | Account read-only | Missing |
| Password change (neo `Account.jsx`) | Change own | Account | Built |
| Own tenants add/remove (neo `Account.jsx`) | Self-service | Account read-only | Missing |
| Welcome links, plugin API, extra locales | | | In progress |
