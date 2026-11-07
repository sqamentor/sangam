# Premium feature gap re-check

Date: 2026-11-08. Status: **research input**. Nothing here is adopted, implemented or tested. It adds to `PREMIUM-AND-UNIVERSAL-SCOPE.md` (PF-01…52) and `FLOWABLE-INTO-OPERATON.md` (EC-01…21).

## Method and limits

- **Sources:** public vendor pages only. No login was used. No code, enterprise artefact, licence-gated material or UI asset was downloaded or copied. All design sketches below are generic and independent.
- **Evidence tags:**
  - **[F]** page fetched on the access date. The fetch tool returns a model summary of the page, not the raw text.
  - **[S]** search-result summary; the page itself was not fetched.
  - **[U]** unverified; check before relying on it.
- **Access date:** 2026-11-08 for every URL below. Vendor publication dates are given only where the page showed one.
- **Edition status is not settled per feature.**
  - The Flowable items come from a commercial product line.
  - The Camunda 8 items are mostly in components that need a licence for production (licences page, cited in PF-32). The 8.9 notes do not label features as enterprise-only.
  - Two consequences: an item here may not be strictly "premium", and the pricing-page tier markers were partly lost in the fetch.
- **Mapping to Sangam:** based on READMEs and repeated keyword searches of `sangam/` (`*.java`, `*.ts(x)`, `*.yaml`, `*.json`).
  - **MISSING means no implementation was found by search.** It is strong evidence, not proof. A differently named implementation could exist.
  - Sangam is at "coding phase snapshot; not yet tested", and nothing was run for this check. **A file listed here shows that code exists, not that it works.**
- **Coverage limits:**
  - The 8.9 and 8.8 release notes were read to about 87% and 88% of their length; the tails were not read. The 8.9 read stopped partway through the alpha2 section.
  - Camunda 8.10 notes were not reviewed.
  - No fresh feature walk of the Camunda 7 EE docs was done beyond the CE-to-EE update page, which added nothing new. Treat C7 EE as re-checked lightly.
  - Flowable features below the release-note level (for example "policies", "query models") were searched once and not found. They remain open.

**Status values:** MISSING; PARTIAL (some code, stated gap); PRESENT (found in code, not in the old lists); X (not software, or outside the self-hosted scope).

**Confidence** covers both whether the vendor feature exists and the Sangam mapping.

## Source key

| Key | URL |
|---|---|
| S1 | https://documentation.flowable.com/latest/admin/release-notes/2026.1.0-release [F] |
| S2 | https://documentation.flowable.com/latest/ai/ai-introduction [F] |
| S3 | https://documentation.flowable.com/latest/user/hub/introduction [F] |
| S4 | https://documentation.flowable.com/latest/user/engage/user-engage [F] |
| S5 | https://documentation.flowable.com/latest/user/user-mobile-app [F] |
| S6 | https://documentation.flowable.com/latest/user/engage/integrations [S] |
| S7 | https://docs.camunda.io/docs/reference/announcements-release-notes/890/whats-new-in-89/ [F] |
| S8 | https://docs.camunda.io/docs/next/reference/announcements-release-notes/890/890-release-notes [F, partial] |
| S9 | https://docs.camunda.io/docs/reference/announcements-release-notes/880/880-release-notes/ [F, partial] |
| S10 | https://camunda.com/pricing/ [F; tier markers partly lost] |
| S11 | https://docs.camunda.io/docs/components/early-access/alpha/bpmn-copilot/ [S] |
| S12 | https://docs.camunda.io/docs/components/early-access/alpha/feel-copilot/ [S] |
| S13 | https://docs.camunda.io/docs/components/modeler/web-modeler/process-landscape-visualization/ [F] |
| S14 | https://marketplace.camunda.com/en-US/home [F; title and footer only] |
| S15 | https://unsupported.docs.camunda.io/8.1/optimize/components/userguide/process-analysis/outlier-analysis/ [S; old version] |
| S16 | https://camunda.com/platform/optimize/ [S] |
| S17 | https://docs.camunda.org/manual/7.24/update/ce-to-ee/ [F] |

## Findings

| ID | Feature | Vendor | Source | Sangam file or MISSING | Confidence |
|---|---|---|---|---|---|
| NG-01 | Generate and edit models from natural language, including full apps | Camunda, Flowable | S11, S2, S1 | MISSING | High |
| NG-02 | Expression assistant and expression playground | Camunda | S12, S9 | MISSING | High for the playground; Medium for the assistant (alpha) |
| NG-03 | Knowledge bases and RAG for agents | Flowable | S2 | MISSING | High |
| NG-04 | Agent-to-agent (A2A) interoperability | Flowable, Camunda (alpha) | S2, S8 | MISSING | High |
| NG-05 | MCP client: agent tools from remote MCP servers | Camunda, Flowable | S7, S8, S1 | MISSING (no MCP in code; R14 is requirement text) | High |
| NG-06 | Platform MCP server exposing cluster or design data | Camunda, Flowable | S7, S1 | MISSING | High |
| NG-07 | Orchestrator and external-platform agent types | Flowable | S2 | MISSING | High |
| NG-08 | Document classification and AI triage of inbound email and attachments | Camunda, Flowable | S8, S1 | MISSING (extends PF-31/R75) | High |
| NG-09 | AI-generated dashboards, translations and agent prompts | Flowable | S2, S1 | MISSING | Medium |
| NG-10 | Agent test assertions judged by an LLM | Camunda | S8 | MISSING | Medium |
| NG-11 | Microsoft Teams task app with live updates | Camunda | S8, S10 | MISSING (`TEAMS` is only a config value: `sangam-rest/.../adapter/jdbc/JdbcRegistryAdapter.java:40`) | High |
| NG-12 | Real-time in-app conversations with multimedia, search and audit | Flowable Engage | S4 | MISSING | High |
| NG-13 | Chatbot framework driven by case and process models | Flowable Engage | S4 | MISSING | High |
| NG-14 | Live third-party messenger integration (WhatsApp, WeChat) | Flowable Engage | S4, S6 | MISSING (`WHATSAPP` config value only; no WeChat) | High |
| NG-15 | SAP integration | Camunda | S10 | MISSING (only text mentions in `sangam-packs` profiles) | Medium: scope and tier unread |
| NG-16 | ServiceNow integration | Camunda | S10 | MISSING | Medium: scope and tier unread |
| NG-17 | Marketplace for connectors and extensions **[U]** | Camunda | S14 (listing types not visible) | MISSING | Low |
| NG-18 | Token flow simulation on the diagram | Camunda, Flowable | S10, S1 | MISSING (no equivalent found; Play runs against a sandbox instead) | High |
| NG-19 | Reusable element templates, custom palette, composite form components | Camunda, Flowable | S9, S7, S1 | MISSING | High |
| NG-20 | Process landscape: generated map of call-activity dependencies | Camunda | S13 | MISSING | High |
| NG-21 | Canvas locking and takeover | Camunda | S8 | MISSING | High |
| NG-22 | Service mocks for model tests, with global and per-service toggles | Flowable | S1 | MISSING | High |
| NG-23 | Command-line tool for the platform | Camunda (c8ctl) | S8 | MISSING (SDKs exist, no CLI found) | High |
| NG-24 | Per-tenant usage metrics | Camunda | S8, S9 | MISSING (R70 is requirement text) | High |
| NG-25 | Camunda 7 history and identity data migrators | Camunda | S7, S8 | MISSING (`sangam-flowable-ports/.../FlowableModelImporter.java` handles Flowable models only) | Medium: need depends on Sangam's users |
| NG-26 | OAuth on-behalf-of token exchange for service calls | Flowable | S1 | MISSING | High |
| NG-27 | Inbound email channel storing mail and attachments as content | Flowable | S1 | MISSING (no mail-store reader found) | High |
| NG-28 | Action models: named, reusable user-triggered behaviour | Flowable | S1 | MISSING | Medium: full semantics unread |
| NG-29 | Index maintenance and batch reindexing | Flowable | S3, S1 | MISSING | Medium: relevant only once R65 has an index |
| NG-30 | Global search across sections, instances, tasks and definitions | Flowable Hub | S3 | MISSING | High |
| NG-31 | Outlier analysis with significant variables | Camunda Optimize | S15, S16 | MISSING (variants and bottlenecks exist in `sangam-ui/src/areas/analytics/Analytics.tsx`) | Medium: current docs not fetched |
| NG-32 | Bulk model-test runs with reports; test export from design to runtime | Flowable, Camunda | S1, S8, S9 | PARTIAL: `sangam-ui/src/api/endpoints.ts:313` runs tests, marked proposed endpoint [P]; no export or bulk report | Medium |
| NG-33 | Mobile app: camera upload, document versions, connect to another server | Flowable | S5, S4 | PARTIAL: responsive shell and FieldVisits per `sangam-ui/README.md`; no native app. Camera upload **[U]** | Medium |
| NG-34 | Cluster and tenant variables with merged scopes | Camunda, Flowable | S7, S3 | PARTIAL: `sangam-config/.../profile/ProfileResolver.java` layers config; availability in BPMN expressions and an edit UI **[U]** | Medium |
| NG-35 | Global user task listeners set by configuration | Camunda | S7 | PARTIAL: `sangam-flowable-ports/.../task/TaskEventDispatcher.java` attaches to every user task; no per-deployment configuration found | Medium |
| NG-36 | Hierarchy-aware retention | Camunda | S8 | PARTIAL **[U]**: cleanup view exists per `sangam-ui/README.md`; hierarchy behaviour not confirmed | Low |
| NG-37 | Continuous database backup and restore | Camunda | S8 | PARTIAL: `deploy/helm/sangam/templates/backup-cronjobs.yaml` has WAL streaming for PostgreSQL; other databases not checked here | Medium |
| NG-38 | Secret management UI, bulk import and cluster-to-cluster export | Camunda, Flowable | S8, S3 | PARTIAL: `sangam-integration/.../connector/SecretResolver.java` resolves references; no bulk or copy tool found | Medium |
| NG-39 | Dual-region deployment reference architecture | Camunda | S7, S8 | PARTIAL: `deploy/README.md` §4.1 failover runbook, `sangam-cell` cell descriptors; no active dual-region architecture found | Medium |
| NG-40 | Inbound channels for file, SFTP, SQL and Azure Service Bus | Flowable | S1 | PARTIAL: Kafka, Rabbit, JMS and webhook in `sangam-flowable-ports/.../events/channel/`; `SftpTransport.java` exists, inbound use **[U]**; no file, SQL or Service Bus channel found | Medium |
| NG-41 | Sequence values managed by administrators | Flowable Hub | S3 | PARTIAL: `sangam-resources/.../support/Sequences.java`; module-local, no admin view found | Low |
| NG-42 | Business ID uniqueness on start | Camunda | S8 | PRESENT: `sangam-integration/.../inbox/UniqueStartGuard.java` | Medium: vendor semantics unread |
| NG-43 | Scheduled backups | Camunda | S8, S10 | PRESENT: `deploy/helm/sangam/templates/backup-cronjobs.yaml` | High |
| NG-44 | Revision diff view | Flowable | S1 | PRESENT: `sangam-ui/src/api/endpoints.ts:305` (`/packages/{id}/diff`) | Medium: visual comparison not checked |
| NG-45 | Deep links that resume after login | Flowable | S1 | PRESENT: `sangam-ui/src/auth/AuthProvider.tsx:177` | High |
| NG-46 | Vendor SaaS items: PrivateLink, SaaS regions, SaaS metrics endpoint | Camunda | S8 | X: Sangam is self-hosted (R56) | High |
| NG-47 | Customer success, personal training and consulting | Camunda | S10 | X: services, not software. Optional; relates to R68 | High |

## Corrections to the earlier picture

These change how existing rows should be read. They are not new features.

1. **PF-28/46 (agents) and PF-47/EC-15 (Engage) have no runtime.** `JdbcRegistryAdapter` stores agent and channel definitions as documents. A case-insensitive search found no LLM client and no Teams, Slack, Matrix or WhatsApp adapter class. "UI ready; server [P]" therefore understates the remaining work: the execution side is unbuilt.
2. **PF-25/26:** `sangam-ui/README.md` lists backups as a gap. That is true for the UI, but Helm backup jobs exist (NG-43), so the gap is narrower than it reads.
3. **Many "C" rows in the UI README depend on [P] endpoints.** That table describes screens, not end-to-end function. It should not be counted as parity.

## Design sketches for MISSING items

These are generic designs. All inherit the common rules: R19 authorization, R36 operation identity, R42 audit, R43 governed deployment, no feature gating. All AI features need a self-hosted model option (R56).

| ID | Sketch |
|---|---|
| NG-01 | `ModelAssistant` port takes a prompt and optional current model, and returns a draft BPMN. The draft must pass the existing package validation and goes into a package as a draft only, never deployed. Pluggable provider. Store prompt and model in the audit trail. |
| NG-02 | Server endpoint evaluates an expression against sample variables in a sandbox with no side effects and a time limit. The assistant is a thin LLM call that proposes an expression; the user runs it in the playground before saving. |
| NG-03 | `KnowledgeBase` versioned model: ingest from R74 documents, chunk, embed, store in a pluggable vector store (pgvector as default candidate). Retrieval applies R19 filters **before** ranking, and stores the source references used by each answer. |
| NG-04 | Inbound and outbound adapters following the public A2A protocol. Outbound runs as an R9-leased job with an R36 key. Inbound maps to a process start under a service principal. Remote agents are allow-listed under R27. |
| NG-05 | Connector type `MCP` in the service registry: allow-listed servers, tool filter, secret reference. Each tool call is one durable activity with retry and incident handling. Write-capable tools require human review (R72). |
| NG-06 | MCP server module over the REST API, using the caller's own token. Read-only by default; write tools are opt-in per tenant and use the existing reason and four-eyes rules. Never bypasses REST. |
| NG-07 | Add `ORCHESTRATOR` (sub-agents as tools inside an ad-hoc sub-process, with depth and budget limits) and `EXTERNAL` (delegates to a third-party platform through a connector) to the agent definition. Depends on the missing agent runtime. |
| NG-08 | Classifier step with versioned templates: labels, extraction engine per template, confidence. Below a threshold, route to a human task. Corrections feed the evaluation set. Extends R75. |
| NG-09 | Separate assistants that output reviewable drafts: dashboard JSON, translation catalogue entries (human review before merge, as R54 needs), prompt drafts. Nothing activates without approval. |
| NG-10 | Test assertion type `judge(criteria, threshold)` with a fixed model and prompt recorded in the result. Report it separately from deterministic tests, because it is not repeatable. |
| NG-11 | Teams bot using Microsoft's public Bot Framework and Adaptive Cards. Shows tasks and notifications, deep-links to Sangam. Sensitive completions require Sangam sign-in. Follows the R81 identity rule. Own artwork. |
| NG-12 | `Conversation` module: participants checked by R19, messages, attachments through R74, WebSocket delivery, retention classes, search. Conversations can link to a case. |
| NG-13 | Bot as a BPMN process: a message event receives input, a task sends a prompt, variables capture answers. A channel-neutral message contract lets one model serve many channels. |
| NG-14 | `ChannelAdapter` per provider under the public Meta WhatsApp Cloud API and WeChat Official Account API. Verify webhook signatures, map opted-in identities, honour template and consent rules. Tenant supplies its own provider account. |
| NG-15 | Connector pack over SAP's public OData and RFC interfaces. Avoid bundling SAP libraries: the customer supplies any restricted client library (like the LIC-4 driver rule). Licence review required first. |
| NG-16 | Connector pack over ServiceNow's public Table REST API: create and update records, inbound webhook to message correlation. |
| NG-17 | Signed catalogue index of packs and connectors: manifest, licence, publisher, checksum, compatibility. `sangam-onboarding`'s dependency resolver could consume it. An offline mirror is needed for R56. Verify the vendor feature first. |
| NG-18 | Client-side token animation over the existing diagram from a pure model walk, with choices at gateways. No engine involved. Check the licence of any bpmn.io add-on before use, or write it independently. |
| NG-19 | Versioned JSON template: applies to an element type, with fields bound to task properties and validation rules. Stored in packages and applied in the properties panel. "Save task as template" writes this format. Composite form components use the same versioning. |
| NG-20 | Parse call activities across a tenant's packages into a graph. Render read-only, filtered by R19, showing missing targets and version bindings. Extends the existing package closure findings. |
| NG-21 | Per-file edit lease row (holder, expiry, heartbeat). Others see read-only with a request-takeover action; takeover is audited and the previous editor's draft is kept. Optimistic version check on save stays. |
| NG-22 | Mock definitions per service operation stored with test models: fixed or conditional response, error, delay. Honoured only on non-production profiles, enforced as for R80. |
| NG-23 | `sangam` CLI over the REST API: login by device flow or service principal; deploy, run tests, list and query, batch status. JSON output and stable exit codes for CI. No direct database access. |
| NG-24 | Daily roll-up per tenant: instances, tasks, decisions, active users, storage. Read through R19 for capacity planning only; never used for licensing (R70). |
| NG-25 | Standalone migrator that reads C7 and Operaton `ACT_HI_*` tables and identity tables, maps to the Sangam audit and history store, and records source ids. Resumable, with reconciliation counts and a dry run. Decide first whether anyone needs it. |
| NG-26 | Support RFC 8693 token exchange in the connector auth layer. Audience-scoped short-lived tokens, the acting user recorded in R42, cached per user and audience only. Reject it for jobs with no user context. |
| NG-27 | IMAP or Graph-API poller as an inbound channel: idempotent on message id, attachments to R74 with the malware hook, then the normal event pipeline. Poison handling via the existing `PoisonInbound`. |
| NG-28 | Versioned `Action` model: label, scope (case, process or task type), permission, optional form, target (BPMN, REST or script). Server-side permission check, R36 key on invocation, and the same result in every UI. |
| NG-29 | Only if R65 adds a search index: a rebuild from history as a batch (R64), with progress, an alias switch and a drift check. Otherwise record as not applicable. |
| NG-30 | Server endpoint with per-type capped results, built from existing R19 predicates, plus a `Ctrl+K` dialog. Client-only section matching can ship first. Hidden records never affect counts. |
| NG-31 | On the R65 read model: per-activity duration distribution, flagging instances beyond a statistical threshold, then comparing variable-value frequency in outliers versus others. Minimum sample size required; present as correlation, not cause. Variable access follows R19. |

## Counts

| Status | Count | IDs |
|---|---|---|
| MISSING | **31** | NG-01…31 |
| PARTIAL | **10** | NG-32…41 |
| PRESENT | **4** | NG-42…45 |
| X | **2** | NG-46, 47 |
| **Total** | **47** | |

Items with an open **[U]**: NG-17, 33, 34, 36, 40.

## Open verification items

1. Read the rest of the 8.9 and 8.8 notes, then the Camunda 8.10 notes.
2. Fetch a rendered Camunda pricing table to settle the tier of Teams, SAP, ServiceNow and others.
3. Fetch the listing types for the Camunda Marketplace (NG-17) and the current Optimize outlier page (NG-31).
4. Run a fuller Camunda 7 EE walk of the Cockpit manual.
5. Search Flowable docs specifically for policies, query models, variable extractors and business calendars.
6. Confirm NG-34, 36, 37, 40 by reading the code, not just by search.
