# BACKLOG 0.1.0 MASTER: consolidated, de-duplicated, ordered (2026-11-12)

Consolidator, read-only. This file is the only output.
- **Inputs:** BACKLOG-P1/P2/P3-2026-11-12, SANGAM-READINESS-REPORT-2026-11-12, AUDIT-A-PENDING, AUDIT-B-PLUGPLAY and AUDIT-C-UI (all 2026-11-12). Files named "AUDIT-A/B/C-2026-11-12.md" do not exist; these three are the audits meant.
- **Tree checked:** `.claude\worktrees\workspace-scan-resume-06b524` (HEAD 1105d9c plus uncommitted changes). Register: `sangam\TESTING-GAP-REGISTER.md`.
- **Method:** Grep, Glob and Read only, plus read-only git. I also listed the entries of the Operaton 2.2.0 DMN-model jar in the local `.m2`; nothing was downloaded. No Maven, npm or tests.
- **Paths:** relative to `sangam\` unless they start with `SANGAM.md`, `.github` or `spike\`.

**Binding owner rules applied (2026-11-12).**
- Every known planned item is in 0.1.0, including Stage D/E, Tier-2, plug-and-play and UI.
- Only unknown or unrecorded items go to 0.2. **None were found:** every item below is recorded somewhere.
- Code comes first, tests after. Every line is written on evidence at ≥97%.
- New work fits SANGAM.md D-1…D-28 without breaking existing work.
- Superseded items stay out.

---

## 0. Spot-check settlements (sweeps disagreed, or a claim was wrong)

| # | Claim(s) | Settled as | Evidence (file:line) |
|---|---|---|---|
| S-1 | P2 counts "X.4 screens" BUILT as one group; P3-001, 003-007 and 010 say MISSING | **MISSING** (P3 is right) | Grep of `sangam-ui/src` for `packs/installed`, `custom-fields`, `bulk/jobs`, `module-defaults`, `privacy-policy`, `onboarding/promotions`, `tests/reports` and `tests/export`: 0 hits (the only `/apply` hit is `/portal/apply`, `app/App.tsx:288`) |
| S-2 | P2 counts R1 BUILT; P1-012 says the wake-up has no listener | **PARTIAL** (P1 is right) | The only sender is `sangam-integration/.../relay/WakeupNotifier.java:34` (`SELECT pg_notify`). No `LISTEN`, `getNotifications` or `PGNotification` anywhere in `sangam/` |
| S-3 | P2 counts R13 BUILT; P1 §3 says "HTTP spans only, not in SDK payloads" | **PARTIAL** | In main code, `traceparent` appears only in `sangam-server/.../SecurityConfig.java` and `sangam-gateway/.../CellProxy.java`. There is none in `sangam-engine` or `sdks/*/src` (→ M-062) |
| S-4 | P2 counts R40 BUILT; P1-025 says there are no alert rules | **Both are true** | The R40 monitor exists (`OutboxCapacityMonitor.java:85-88`, audit A). `deploy/` has 0 hits for `PrometheusRule`, `alert:` or `rule_files` |
| S-5 | P1 #56 and P2 LIC-8 say the watermark is BUILT; P2-027 says it has no test | **PARTIAL** | `.github/workflows/ui.yml:62-67` only greps `dist/` for "bpmn.io". No test asserts that `.bjs-powered-by` is visible (→ M-049) |
| S-6 | P1-001: "no MQTT, AWS, Azure or GCP clients anywhere" | **Corrected; PARTIAL stays** | MQTT telemetry inbound already uses Paho: `sangam-integration/pom.xml:93-96` and `telemetry/MqttTelemetryConsumer.java:4-10,20`. Azure Service Bus inbound runs over REST with no SDK: `sangam-channels/.../inbound/PolledChannels.java:139-153` and `ChannelsWiring.java:71,235-240`. The event-gateway `ChannelAdapters` still handles only kafka, jms and rabbitmq (P1 cite `:80-105`) |
| S-7 | P1-002: "no `ce-*` header handling in main" | **Corrected; PARTIAL stays** | `sangam-server/.../bridge/PortsEventBridge.java:305-310` sets `ce-id`, `ce-source`, `ce-type` and `ce-specversion`, but the body is still the structured envelope (`:286-304`). There is no binary-mode writer or parser |
| S-8 | P3-062: "LMMH segments are validated alone" | **CODED in the working tree (uncommitted)**; only docs and a register row are left | `sangam-config/.../master/ClassificationRegistry.java:164-166` (`checkHierarchy`) and `:251` (`CODE_NOT_UNDER_PARENT`). `profiles/in/IN.yaml:61-88` has the HOA hierarchy (subMajor path [major]; minor path [major, subMajor], `anyParentFlag: standard`). Test: `ProfileDataTest`. Stale text: `sangam-packs/tools/lmmh/README.md:37` and `tools/profilegen/lmmh.py:4-5`. `git status`: M |
| S-9 | P3-009 asks whether the task/case menu must use `/invoke` ("subject-bound") | **It must use `/run`.** Settled by the contract; no owner question | `sangam-api.yaml`: `/actions/{key}/run` is "Permission checked on the subject; idempotent per request key". `/invoke` is "BPMN and connector targets run; SCRIPT targets are refused" |
| S-10 | P1-010: accepting DMN 1.4-1.6 "needs an engine pin exception" | **Corrected** | The Operaton 2.2.0 DMN-model jar (`.m2/.../operaton-dmn-model/2.2.0`) bundles `DMN14.xsd` and `DMN15.xsd`. The 2.1.6 model constants list the 20211108 (1.4) and 20230324 (1.5) namespaces. The 1.6 namespace is not bundled. So 1.4 and 1.5 need no engine change, and 1.6 needs Sangam-side handling. Whether the engine *executes* these models is not yet proven (known risk, M-126) |
| S-11 | P3-026 / P2-031a: offline queue | **Now register row SL-129**, OPEN and blocked on owner decisions | `TESTING-GAP-REGISTER.md:162`; `sangam-ui/src/areas/tasklist/MyWork.tsx:138,145` (plaintext `localStorage`) |
| S-12 | P2 housekeeping: stray `sangam-control-plane/` and `sangam-rollup/` at the repo root | **Closed by SL-128** (FIXED in tree) | `TESTING-GAP-REGISTER.md:161` (the folders were moved to the scratchpad, not deleted) |
| S-13 | P3-067: SL-102 "MISSING" | **FIXED in tree (B12)**; reference only | `TESTING-GAP-REGISTER.md:126`; FILE-CLAIMS 04:38 and 04:49 RELEASE |
| S-14 | P1-003 asks whether broker task mode is deferred by ADR-D-5 | **Not deferred.** ADR-D-5 defers only gRPC streaming | `SANGAM.md:4196` ("gRPC streaming only after a measured need") |
| S-15 | P3-043 asks whether AsyncAPI is superseded by D-5 | **Not superseded.** R16 keeps AsyncAPI. Only gRPC/proto is deferred | `SANGAM.md:4381` (R16), `:4196`; `sangam/pom.xml:340-341` bans `io.grpc` |
| S-16 | P1-032: XPath detectors | **SUPERSEDED** by the recorded weakness decision FW-42 (b) | `spike/results/FLOWABLE-WEAKNESS-AUDIT.md:121` ("JSON only; XML and expression deserializers are not ported"); `sangam-flowable-ports/README.md:275` ("avoided") |
| S-17 | P1-019 / P3-012: is a graphical CMMN editor required? | **Planned** (Stage E), so in 0.1.0 | `spike/results/FLOWABLE-INTO-OPERATON.md:205` (EC-04 "C → E") and `:274` (D-43 "C → E") |
| S-18 | P3-051 FC-51 CockroachDB: drop it like Db2? | **Planned as an experimental tier** | R17 `SANGAM.md:4382` ("Experimental: CockroachDB, YugabyteDB"). SL-114 dropped only Db2 |
| S-19 | P3-050: patch Operaton locally, or wait for upstream? | **Settled by ADR-D-16** | `SANGAM.md:4264`: a source change only where no extension point exists; recorded in the patch ledger and offered upstream; no hard fork. Today `sangam-engine/PATCHES.md:11-22` has only the DDL patches P-1, P-2 and P-3 |
| S-20 | P2-016: may partitioned executors be built? | **Build them, off by default** | R39 `SANGAM.md:4404` says they are out of scope only until A-6 is specified; X.2.1 specifies it (P1-S11, P2-016). Enabling waits for T54 |
| S-21 | P1-014: exclusive-over-hierarchy default | **The design says on**, and no recorded decision says off | `SANGAM.md:494` (§4.3 "on by default"); `SangamEngineSettings.java:152-153` (`false`, no reason recorded) |
| S-22 | P3-014 / P3-019 / P1-001 licence questions | **Settled by the licence policy** | `deploy/licence/licence-policy.json:5-6` allows EPL-2.0, EDL-1.0 (JGit, Paho) and MPL-2.0 (axe-core) |
| S-23 | P3-063: are the US health payer items "traceability only"? | **Partly settled** | ADR-D-23 `SANGAM.md:4279` names CMS-0057-F, so the payer APIs are in. The No Surprises Act items (IDR, AEOB, emergency pack) are not named → Q-12 |
| S-24 | P2 re-verify #2 (billing) and P3-058 | **Confirmed open** | `sangam-billing/.../BillingService.java:70,111` (`series.next(...)` takes no operationId); README `:23-24` |
| S-25 | P2-009 / P3-061 long-poll | **Confirmed** | `EngineAdapterWorkAdapter.java:99` and `OperatonWorkAdapter.java:92` (`Thread.sleep`); no `DeferredResult`; no `threads:` or `virtual` in `sangam-server/src/main/resources` |
| S-26 | P2-032 CDS Hooks | **Confirmed** | `HealthHooksController.java:80-82` checks only that some principal exists |
| S-27 | P2-011 idempotency | **Confirmed** | No `encrypt`, `Encryptor` or `cipher` in any `*dempotency*.java` |
| S-28 | P2 re-verify #4: broker consumers ignore the tenant freeze | **Confirmed by absence** (85%; evidence phase) | Tenant state is used only in `CellTenantStateFilter` / `CellTenantStateStore` (HTTP) and `CellWiring.java:92-110`. No reference in any `*Inbox*` or `*Broker*` main class |
| S-29 | P2 re-verify #1: compliance denial audits roll back with the caller | **Not proven either way** (evidence phase) | The only own-transaction code in `sangam-compliance` main is `RetentionScheduler.java:162` |
| S-30 | P3-016: 600 open bodies | **Confirmed** | `sangam-api.yaml`: 600 × `additionalProperties: true` |
| S-31 | P3-015: route guards | **Confirmed** | `sangam-ui/src/app/App.tsx:191-192, 197, 205-217, 222` have no `g(...)` |

---

## 1. Master item list by epic

**Sizes in engineer-days:** S = 1-3, M = 3-8, L = 8-20, XL = 20-45.
**"Owner?"** = blocked on the owner (yes/no, plus the question id).
**Wave** = the wave in §3.
**⚠** = evidence below 97%: the item needs an evidence phase before code (see §5).

### E1: Correctness and security defects (13)

| ID | Sources | Scope (one line) | Design anchor | Deps | Modules / files | Risk to existing work → guard | Size | Owner? | Wave |
|---|---|---|---|---|---|---|---|---|---|
| M-001 | P3-026 = P2-031(a) = SL-129 | Replace the plaintext `localStorage` field queue with `LocalQueueContract`: AES-256-GCM, a non-extractable WebCrypto key in IndexedDB, the v1 envelope, an operationId fixed at capture, device-ordered sync, and logout handling | sangam-field N05, EC-16; `sangam-field/.../sync/LocalQueueContract.java:8-31`; R53 | Q-01; Q-11 (fake-indexeddb) | `sangam-ui/src/areas/tasklist/MyWork.tsx:129-149`, new `src/lib/offlineQueue.ts`, `auth/AuthProvider.tsx` (logout), sangam-field sync endpoints | Visits queued today could be lost → one-time migration path (Q-01-d); queue unit tests; old-key read-only fallback | L | **yes, Q-01** | 1 |
| M-002 | P3-058 = P2-RV2 (XII.4 #5) | Reserve invoice and receipt numbers once per operationId, so a retry reuses the number (gap-free N09) | R36; N09 | none | `sangam-billing/.../BillingService.java:70,111`; `sangam-config/.../catalogue/NumberSeries` (reservation keyed by operationId); changelog via `generate_changelogs.py` | Changes the shape of the gap-free series → keep `NumberSeries.next` behaviour for callers without an operationId; `BillingServiceTest` and `BillingBusinessDateTest` extended; all-dialect changeset | M | no | 1 |
| M-003 ⚠ | P3-065 | Make the dispatch audit complete on the JavaDelegate connector path (today `auditOrNull = null` to avoid a ROOT_SEQ self-deadlock) | R42; SL-43 history | SG-15 (B15) releases `AuditChain` / RootSeq | `sangam-integration/.../connector/ConnectorInvoker.java:113-118`, `sangam-core/.../layer/AuditChain.java`, server wiring | The deadlock could come back → evidence phase first (append inside the job transaction vs after-commit through the outbox); H2 deadlock regression (`QuotaPoolDeadlock*` pattern) | M | no | 1 (evidence) / 2 (code) |
| M-004 | P3-062 | LMMH parent-aware validation: **coded in tree (S-8)**. Left: stale docs, a register row, and confirming the seed | ADR-D-20; GA-02 | Q-13 (seed slips 1128-1132, not blocking) | `tools/lmmh/README.md:37`, `tools/profilegen/lmmh.py:4-5`, register | Stricter validation rejects mis-parented codes → that is intended; `ProfileDataTest` covers it | S | no (Q-13 data only) | 1 |
| M-005 | P1-012(a) | A LISTEN consumer for the relay's `pg_notify` that wakes waiting long-polls and task-list push at once (the poll stays authoritative) | §4.4, §5.2; R1 | none | `sangam-integration/.../relay/WakeupNotifier.java`, new `relay/WakeupListener.java`, `sangam-server/.../IntegrationWiring.java` | Holding a pooled connection forever → a dedicated non-pooled connection with reconnect; a no-op on the other dialects; R1 "1 s poll authoritative" kept | M | no | 1 |
| M-006 | P2-032 (XII.4 #8) | Verify CDS Hooks 2.0 client JWTs against a per-client JWKS registry: `iss` allow-list, `aud` = service URL, `jti`, expiry | ADR-D-23; US-HLT-074 | none | `sangam-rest/.../connectorshealth/HealthHooksController.java:79-85`, `sangam-security` (new verifier), `HealthConnectorsProperties.java` | Other `/hooks/*` paths keep their signature check → applied only to the `/cds-services/*` route; negative tests | M | no | 1 |
| M-007 | P2-011 (XII.4 #2) | Encrypt stored idempotency responses at rest through `FieldEncryptor` / `TenantKeyStore`, with key rotation | R41 / X.2.2; R53 | none | `sangam-core/.../layer/IdempotencyStore.java`, `JdbcIdempotencyAdapter.java`, `IdempotencyService.java`, changelogs (5 dialects) | T57 byte-identical replay → decrypt before replay; `DialectSchemaIT` change; old unencrypted rows read with a format marker | M | no | 1 |
| M-008 ⚠ | P2-RV4 (XII.4 #7, IR-6) | Kafka, JMS and Rabbit inbox consumers honour the tenant FROZEN/MOVING state (pause or park, never drop) | ADR-D-21; IR-4/IR-6 | evidence phase | `sangam-integration/.../inbox/KafkaInboxConsumer`, `JmsInboxListener`, `CellTenantStateStore` (read-only), `CellWiring.java` | Never ack a message for a frozen tenant (redeliver) → the existing "not acked on back-pressure" path (`sangam-server/README.md:307`) | M | no | 1 |
| M-009 ⚠ | P2-RV1 (XII.4 #3) | Compliance denial audits must survive the caller's rollback (own transaction) | R42; CMP-* | evidence phase | `sangam-compliance/src/main/**` (denial paths), `ComplianceWiring.java` | Double audit on a retry → idempotent audit key | S | no | 1 |
| M-010 ⚠ | P2-RV5 (XII.4 #3) | Generated pack BPMN gets DI layout and seat routing | IX.5; ADR-D-20 (generators only) | evidence phase | `sangam-packs/tools/packgen/*`, pack resources (one regeneration) | Changes generated files → generator `--check`; packs `--check OK` count unchanged apart from DI | M | no | 1 |
| M-011 ⚠ | P2-RV3 (XII.4 #5) | Confirm that packs no longer use `WorkingDayCalendar` in production (probably resolved), and move the class out of `packs.sim` | R31 | none | `sangam-server/.../PacksWiring.java:172-177`, `sangam-packs/.../sim/WorkingDayCalendar` | A package move breaks imports → compile gate | S | no | 1 |
| M-012 | P1-S15 check | Guard test: history level `none` never suppresses the mandatory `AuditChain` | X.2.3; X.5.3 | none | `sangam-engine/.../history/PerDefinitionHistoryLevel.java:38` (test only) | none (test only) | S | no | 1 |
| M-013 | P2-003(b) (XII.4 #9) | Plugin asset path: the contract `{path}` must allow slashes, matching `/{id}/assets/**` | R16 | none | `sangam-server/.../web/UiPluginController.java:39`, `gen_openapi.py` route | `OpenApiCompatibilityTest` → a deprecation entry if the path shape changes | S | no | 1 |

### E2: Plug-and-play (6)

| ID | Sources | Scope | Anchor | Deps | Files | Risk → guard | Size | Owner? | Wave |
|---|---|---|---|---|---|---|---|---|---|
| M-014 | P1-023(a) = P2-001 = P3-040(a); XII.9 host constraints | `sangam-spring-boot-starter`: the 36 `*Wiring` classes become conditional auto-configurations (engine + plugins + product layer; packs and domain modules opt-in by property). Plus a sample host app and start-time checks with clear messages (Boot 4.1.2, Jackson 2.22, UTC JVM, BOM order) | ADR-D-16, D-14, D-27; §2, §5.5 | none (needs the Wave-2 server-config freeze) | new module; `sangam-server/.../config/*Wiring.java` (move); `sangam-bom/pom.xml`; root `pom.xml` reactor | **HIGH**: bean ordering, `@ConditionalOnMissingBean`, static `PackRuntime` (PacksWiring:164), and the D-27 UTC guard must stay (`EngineTimeZoneEnvironmentPostProcessor`) → a mechanical move with the same bean names; `ServerStartsIT` unchanged; a server-config **freeze** for the other lanes during the wave | XL | no | 2 |
| M-015 ⚠ | P1-023(b) = P3-040(b) | Quarkus extension (build-time and native surface; UTC rule inside the host) | §2, §5.5; ADR-D-16, D-27 | M-014 | new `sangam-quarkus-extension`; reactor | The product layer is Spring-based → evidence phase on how to reuse ports without Spring; separate module, so there is no effect on the server | XL | no | 3 |
| M-016 ⚠ | P1-016 = P2-002 = P3-041 | Database-per-tenant: an outbox, relay, inbox and idempotency store per tenant DB; Liquibase per tenant DB; an onboarding path | §7; R4/A-5; R32; FC-34; ADR-D-21; XI.10.4 W-7 | **Q-02**; M-014 | `sangam-server` (auto-config from M-014), `sangam-flowable-ports/.../tenancy/TenantEngineRegistry.java`, `OutboxRelayService`, `sangam-persistence` | **HIGH**: every DataSource-bound component; the static `ClockUtil` (W-7) → strictly opt-in; single-DB default untouched; cell tests | XL | **yes, Q-02** | 3 |
| M-017 | P2-003(a) = P3-042 | OpenAPI contracts for the control plane (`/cells`, `/routes`, `/admin/tenants…`), roll-up (`/rollup`, `/ingest`) and gateway, generated like `gen_openapi.py`, with a compatibility gate per app | R16, ADR-D-5; SCALE-ARCHITECTURE | none | `sangam-rest/api/gen_openapi.py`, new `api/routes/controlplane.py`, `rollup.py`, `gateway.py`; per-app compat tests | Additive → the per-app `OpenApiCompatibilityTest` | M | no | 1 |
| M-018 | P2-019 = P3-044 | "Integrate Sangam into your application" guide: embed, REST and the worker lifecycle, operationId, A-8, connectors and simulators, events, packs, the BOM order, required secrets | X.4.10 IJ-01/02; §P8 | M-014, M-050, M-019 | `sangam/docs/INTEGRATION-GUIDE.md` | none | M | no | 5 |
| M-019 | P2-017 = P3-037 | Publishing: signed Maven Central (BOM, starter, modules, Java SDK), npm, PyPI, NuGet, Packagist; a version policy; the first `v0.1.0` tag | ADR-D-14/17/18; R18; §14.7 | **Q-05**; M-014, M-020, M-021 | `.github/workflows/release.yml`, `sdks.yml`, root `pom.xml` (distributionManagement, signing), SDK manifests | Irreversible once published → dry-run jobs first; the tag only after the test phase; LIC-9 naming check | M | **yes, Q-05** | 5 |

### E3: SDKs (8)

| ID | Sources | Scope | Anchor | Deps | Files | Risk → guard | Size | Owner? | Wave |
|---|---|---|---|---|---|---|---|---|---|
| M-020 ⚠ | P2-005 = P3-035 | Typed surface for all 879 paths in the 5 Tier-1 SDKs, **generated from the contract** (R16 "one canonical schema generates the types"), keeping today's hand-written worker runtime | R16, R18, ADR-D-4 | M-051 | `sdks/*/src/**`, a generator script; `deploy/native/sdk-worker/generate_native_config.py` (regenerate) | Drift from today's hand-written models → `ModelContractTest` / ContractGuard per SDK; native metadata check (CI fails when stale). ⚠ generator gaps (SANGAM.md C15) | XL | no | 2 |
| M-021 | P2-006 = P3-033 = P1 §3 (L4) | R18 test engine with two layers: an in-process fake per language (VI.3 lease, fencing, STALE_LEASE, UNKNOWN_OUTCOME), plus a "test mode" server image (H2, BusinessClock steering, time-skip) for process-level tests | R18, §3.3d L4, VI.3 | M-020 | `sdks/*/src` test-support packages; `sangam-server` test profile / image | A fake whose semantics diverge from VI.3 → contract tests M-023 run against both the fake and the real server | L | no | 2 |
| M-022 | P2-007 = P3-038 = P1 §3 (IX.6) | Tier-2 generated SDKs for Go, Rust and Kotlin (openapi-generator) plus a minimal worker-loop helper | R18 "generated, community-maintained" | M-051; Q-11 (toolchains) | `sdks/{go,rust,kotlin}`, `.github/workflows/sdks.yml` | Additive | L | no (Q-11 downloads) | 2 |
| M-023 | P3-034 = P3-075 (AC-08) | SDK contract tests T01-T04 and T18 for each Tier-1 SDK, plus the AC-08 cross-language harness and a compose CI job (code now, run in the test phase) | R18; IX.8 AC-08 | M-021; Q-11 (.NET 8, PHP) | `sdks/*/test*`, `.github/workflows/sdks.yml` | none to product code | L | no (Q-11) | 2 |
| M-024 | P3-036 | .NET SDK built and tested on net8.0 (a run, no code expected) | ADR-D-4 | Q-11 | `sdks/dotnet/*` | none | S | no (Q-11) | test phase |
| M-025 | P2-028 | `context.step(...)` durable step helper in the 5 SDKs, backed by a journal endpoint; documented as not exactly-once | R37 / E02 | M-050, M-020; journal route in the contract | `sdks/*` worker runtimes; `sangam-rest` route; `ConnectorCallJournal.java` | Additive | M | no | 3 |
| M-026 | P1-033 | Saga and compensation worker helpers (handler registration, operationId reuse) | §3.2; R36 | M-020 | `sdks/*` worker modules | Additive | M | no | 3 |
| M-027 | P1-022 | Framework adapters as separate packages: Spring Boot (Java SDK), ASP.NET Core hosted service, Laravel/Symfony, NestJS, FastAPI/Django | §5.3; ADR-D-4 | M-020 | `sdks/dotnet` (`Sangam.Sdk.Hosting`), `sdks/php`, `sdks/typescript`, `sdks/python`, `sdks/java`; `sdks.yml` | Additive; the core SDKs stay dependency-light | L | no | 3 |

### E4: UI (22)

| ID | Sources | Scope | Anchor | Deps | Files | Risk → guard | Size | Owner? | Wave |
|---|---|---|---|---|---|---|---|---|---|
| M-028 | P3-015 | Add role `Guard`s on `/m/*`, `/m/ai`, `/m/channels`, `/x/:pluginId/:id` and `/notifications`, using role constants shared with `nav.ts` | R19; `App.tsx:153-164` pattern | none | `sangam-ui/src/app/App.tsx:191-222`, `app/nav.ts` | A wrong role set hides a screen → take the sets from `nav.ts:128`; a guard test | S | no | 1 |
| M-029 | P3-001 (SL-44 UI) | Bulk ingest screen: list, create, chunked upload with hash, close/abort, row and error report, the 413 quota message | SL-44 | M-051 | `modules/platform.tsx` or new `modules/bulk.tsx`, `api/endpoints.ts`, `App.tsx`, `nav.ts`, i18n en/hi | Additive; roles mirror the server check (`BulkController.java:53` `requireType`) | M | no | 2 |
| M-030 | P3-003 | Pack lifecycle screen (installed / uninstall / rollback) with the PACK_* 409 reasons | SL-41, SL-54, SL-116 | none | `modules/config.tsx` or `areas/admin/Admin.tsx`, `endpoints.ts`, i18n | Additive | S | no | 2 |
| M-031 | P3-004 | Tenant custom fields: list, create, retire, and rendering in object forms | SL-40; X.0e | M-051 | `modules/config.tsx`, `modules/engine.tsx`, `endpoints.ts`, i18n | Touches the shared declarative engine → behind the field-schema presence check; component tests (M-047) | M | no | 2 |
| M-032 | P3-005 | Module security defaults screen (`/admin/security/module-defaults`) | R19 | none | `areas/admin/Admin.tsx`, `endpoints.ts`, i18n | Additive | S | no | 2 |
| M-033 | P3-006 | Privacy policy: an admin edit/version screen plus a public portal view (a DPDP notice is public by nature) | sangam-compliance DPDP/GDPR packs; R53 | none | `modules/compliance.tsx`, `areas/portal/Portal.tsx`, i18n | Additive | S | no | 2 |
| M-034 | P3-007 | Onboarding promotions screen (export a signed bundle, plan with diff and signature check, maker-checker apply) | SL-42; ADR-D-20…24 | none | `modules/config.tsx`, `endpoints.ts`, i18n | Additive | S | no | 2 |
| M-035 | P3-029 | Guided, schema-driven onboarding wizard instead of JSON editors (the JSON editor stays as a fallback) | X.0e | M-051 (onboarding request schema) | `modules/config.tsx` (OnboardingWizard) | Low; keep the JSON fallback | M | no | 2 |
| M-036 | P3-008 | Modeller templates: bpmn-js palette and properties provider, form-js composite components, `templates/{id}/apply` | NG-19; R46 | none | `areas/modeller/Modeller.tsx`, `components/BpmnDiagram.tsx`, new `modeller/templates.ts`, `endpoints.ts` | bpmn-js internals change between majors → pin to the current major; interacts with M-117 | L | no | 2 |
| M-037 | P3-009 | Action menu in TaskDetail and CaseWorkspace through **`/actions/{key}/run`** (subject-bound, idempotent; S-9) | NG-28 | none | `areas/tasklist/TaskDetail*.tsx`, `StartAndCases.tsx`, `endpoints.ts` | Core screens → component tests (M-047) | M | no | 2 |
| M-038 | P3-010 | Test report list and detail (judge results shown apart) plus sealed export | NG-32; R80 | none | `modules/platform.tsx`, `endpoints.ts` | Additive | S | no | 2 |
| M-039 | P3-025 | Secondary domain actions: registry as-of, reveal (reason dialog + audit), transfer-reissue, expire; field eta, pings, sync upload, reverify; rosters duties and on-call; queue call-events; `/links`; `/case-definitions`; meeting-notice ack; programme milestones | declarative module engine | M-051 | `modules/{registry,fieldgeo,resources,casework,programme}.tsx`, i18n | Field names verified against the M-051 schemas | M | no | 2 |
| M-040 | P3-021 | Channels live stream: a fetch-stream SSE client (bearer header) with `Last-Event-ID` reconnect, plus multi-node fan-out in `ChannelsLiveHub` | NG-12 | M-005 (LISTEN fan-out) | `modules/platform.tsx:107`, `api/client.ts`; `sangam-rest/.../channels/ChannelsLiveHub.java:27-52` | Per-node in-memory hub → fan-out over LISTEN/outbox; keep the 10 s poll as a fallback | M | no | 3 |
| M-041 ⚠ | P1-019 = P3-012 | Graphical CMMN editor (a diagram-js based modeller, or cmmn-js after a maintenance check) | §8; EC-04 / D-43 "C → E" | evidence on cmmn-js | `sangam-ui/src/areas/modeller/*`, `components/Editors.tsx:157-206` | bpmn.io licence (LIC-8 watermark) → keep the source editor as a fallback | XL | no | 4 |
| M-042 | P3-027 | Task inbox sort by amount and by reference, through a typed task-index column | Operaton parity P41 | changelog lane | `sangam-rest` tasks adapter, persistence (`generate_changelogs.py`), `areas/tasklist/TaskInbox.tsx:173` | Schema change on 5 dialects → additive column with a backfill job | M | no | 3 |
| M-043 | P1-018 | Embeddable web components `<sangam-task-form>` and `<sangam-process-diagram>` (Vite library build, token handoff, CSP/CORS guidance) | §8; R47 | none | `sangam-ui/vite.config.*`, `components/FormRenderer.tsx`, `BpmnDiagram.tsx`, `.github/workflows/ui.yml` | A separate artefact; watermark kept (LIC-8) | M | no | 4 |
| M-044 | P1-020 | Cockpit trace link: a configurable trace-UI URL template and a trace id recorded per command/instance | §8, §9; R13 | M-062 | `areas/ops/InstanceDetail.tsx`, `ObservabilityConfig.java` | Additive | S | no | 3 |
| M-045 | P1-021 = P2-018 = P3-020 | Languages: a data-driven locale registry (bundled for air-gap), ≥10 locales including a state language, locale-aware number/date formats, currency from the tenant locale (removes the INR default), diglot templates, pack/form label generation, `check-i18n` for N locales, RTL if Urdu | R54 (GOV-1); §14.6; ADR-D-20 | **Q-06** (content only) | `sangam-ui/src/i18n/*` (`index.ts:11,52-53,68`), `scripts/check-i18n.mjs`, profilegen/packgen label emitters, `sangam-compliance` notices | The `Lang` type is used across the UI → widen the type first; `hindi-locale.test.ts` | L | **yes, Q-06** (content) | 2 (code) |
| M-046 | P2-020 = P3-019 = P1 §3 (R55) | Accessibility: axe on component and E2E tests plus a CI gate, the GIGW Annexure II conformance matrix, and an assistive-technology audit plan | R55 (GOV-2); §14.6 | M-047 / M-048 | `sangam-ui/package.json`, `ui.yml`, new `sangam-ui/GIGW-CONFORMANCE.md` | none (axe-core MPL-2.0 is allowed by `licence-policy.json:6`) | M | no (Q-15 auditor) | 2 |
| M-047 | P3-017 | Component tests for the core flows: TaskDetail decisions, StartAndCases/CaseWorkspace, MyWork, Admin, Modeller, Analytics, Audit verify, Portal, Incidents/Reconciliation, BatchMigration, the 13 domain modules, AuthProvider/OIDC | §14.3 P3 pyramid (SANGAM.md:765) | none | `sangam-ui/src/**/*.test.tsx` | none | L | no | 2-3 |
| M-048 | P3-018 | Playwright E2E suite plus a compose-backed CI job and seed data | SANGAM.md:765 | Q-11 (browsers) | `sangam-ui/e2e/**`, `package.json`, `.github/workflows/ui.yml` | CI time → a separate job | L | no (Q-11) | 4 |
| M-049 | P2-027(c) | Test that the bpmn.io watermark (`.bjs-powered-by`) is visible | LIC-8; X.3 (SANGAM.md:5431) | none | `sangam-ui/src/components/BpmnDiagram.test.tsx` | none | S | no | 2 |

### E5: Contracts (3)

| ID | Sources | Scope | Anchor | Deps | Files | Risk → guard | Size | Owner? | Wave |
|---|---|---|---|---|---|---|---|---|---|
| M-050 | P2-004 = P3-043 (AsyncAPI part) = P1 §3 (R16) | AsyncAPI 3 document for outbox, webhook and broker events (CloudEvents envelope, X.4/X.5.2 audit event names), event JSON Schemas, and an OpenAPI ↔ AsyncAPI consistency test | R16; T48; X.5.2 | none | new `sangam-rest/api/sangam-events.asyncapi.yaml` plus a generator; `sangam-core/.../layer/Outbox.java` (read); `HttpWebhookPublisher.java` | Event names become change-controlled; payloads that differ by module surface as test failures → fix at the emitter, schema-first | M | no | 1 |
| M-051 | P3-016 | Typed request schemas for the 600 `additionalProperties: true` bodies: request records in the controllers, emitted by `gen_openapi.py`. Additive (stays open where custom fields SL-40 need it). Then reconcile the UI field names | contract-first; ADR-D-5 | none | `gen_openapi.py`, `api/routes/*.py`, `sangam-rest/.../modules/*` controllers; UI `modules/*.tsx` (W2) | Could reject working clients → typed properties stay additive (no `additionalProperties:false` in 0.1.0); `OpenApiCompatibilityTest` | L | no | 1 (contract) / 2 (UI) |
| M-052 | P2-014 | R44 per-release standards profile (BPMN, DMN, CMMN, FEEL D-26 policy), generated from the same compat data, published by `release.yml` | R44; CF-8 | M-126 (DMN rows later) | `sangam-ui/scripts/gen-compat.mjs`, `scripts/compat-rules.json`, `deploy/evidence/build_evidence_pack.py`, `release.yml` | none | M | no | 1 (generator) / 5 (release step) |

### E6: Engine and scale (11)

| ID | Sources | Scope | Anchor | Deps | Files | Risk → guard | Size | Owner? | Wave |
|---|---|---|---|---|---|---|---|---|---|
| M-053 | P2-009 = P3-061 (XII.4 #6) | Non-blocking worker long-poll: `DeferredResult` woken by the M-005 listener, with bounded waiters per node | R9, CF-3, D-40; R22 | M-005 | `EngineAdapterWorkAdapter.java:99`, `OperatonWorkAdapter.java:92`, `TrackingWorkPort.java:206,332`, the work controller, `application.yaml` | Lease/fencing and tenant thread-locals across the async hand-off → carry the context explicitly; `FwtWorkerCompatIT` and `E3LeaseIT` | M | no | 1 |
| M-054 ⚠ | P1-013 | Optional virtual-thread job executor plus per-job-type bulkheads (opt-in flag) | §4.4; R5; R30 | none | `SangamEngineConfigurer.java`, `SangamEngineSettings.java:140-142` | Operaton `Context` ThreadLocal stacks → off by default; soak evidence before any default change | M | no | 2 |
| M-055 ⚠ | P1-014 | Default `jobExclusiveOverProcessHierarchies = true`, as §4.3 says (S-21) | §4.3; VI.1 L2 | T-SCALE A/B in the test phase | `SangamEngineSettings.java:153`, `deploy/helm/sangam/values.yaml` + schema | Call-activity throughput (SG-15) → env override kept; A/B T-SCALE evidence recorded before release | S | no | 1 |
| M-056 ⚠ | P2-016 | Partitioned executors `EXEC_PARTITION` / `EXEC_SLOT`: epochs, fencing, rebalance, count change. **Off by default** | R39 / X.2.1; A-6 | T54 (whether to enable) | `sangam-engine` (acquisition), changelogs (all dialects), `sangam-core` | Touches job acquisition → when off it must be a strict no-op (X.2.1.1); H2 acquisition tests | XL | no | 2-3 |
| M-057 | P1-011 | History: per-element level overrides, an external projector (OpenSearch/ES HTTP, Kafka, data lake) with an idempotent upsert by `event_id`, an `external` mode switch | §4.6; R4, R42/X.2.3 | none | `sangam-engine/.../history/`, `sangam-integration/.../publish/` (new OpenSearch publisher), settings | External mode must keep the mandatory audit (X.2.3) → M-012 guard; outbox mode unchanged | L | no | 3 |
| M-058 | P1-012(b) | Wake-up bus for Oracle, SQL Server and MySQL: NATS and Redis notifiers (optional) | §4.4, §5.2; R1 | M-005, M-064 (NATS client) | `WakeupNotifier.java`, new notifiers, `IntegrationWiring.java` | None for correctness (the poll stays authoritative) | M | no | 3 |
| M-059 | P1-008 | Model-declared query and update handlers (`sangam:query` / `sangam:update`), deploy validation, FEEL strict evaluation, audit, `/process-instances/{id}/queries/{name}` and `/updates/{name}`, SDK methods | §3.3d L6; R19, R25, R42, R49; ADR-D-26 | M-020 | `sangam-engine/.../deploy/*ParseListener.java`, `.../feel/SangamFeel`, `sangam-rest`, `gen_openapi.py`, `sdks/*` | An update is a modification → R49 approvals and the VI.4 r5 hash binding enforced; only models that use the new attribute are validated | L | no | 3 |
| M-060 | P2-029 = P1 §3 (L7) | Engine-level continue-as-new: atomic handover, moving pending message subscriptions and inbox BUFFERED items, original deadlines, a chain link | R49 / E21 | R38 inbox | `sangam-flowable-ports/.../dynamic/` or `sangam-engine`; `ProcessPort`; `LinkedInstanceDelegate.java` | uniqueStart and inbox semantics → a single transaction; `E16aPatternsIT` expectations updated | L | no | 3 |
| M-061 | P2-008 = P1 §3 (VI.7) | Out-of-process script worker (JS, Python) on the lease protocol (seccomp, no network, cgroup limits), its image, Helm/compose entries, a topic convention, an importer rewrite scriptTask → externalTask | R6 / ADR-D-8; LIC-1/10 | **Q-07**; M-021 | new `tools/sangam-script-worker`; `deploy/docker`, `deploy/helm/sangam/templates`; `OperatonPackageToolsAdapter.java:195,434` | Isolation escapes → escape tests (R6 "add test"); GraalJS/Rhino stay banned (`pom.xml:330-332`) | L | **yes, Q-07** | 3 |
| M-062 | P1 §3 (R13 note; S-3) | OTel: engine parent spans and links for jobs, timers and workers; `traceparent` in worker task payloads and events; bounded cardinality; off by default (R56) | R13; R56 | none | `sangam-engine` (command interceptor), `sangam-integration` publishers, `sdks/*` (propagation), `ObservabilityConfig.java` | An exporter failure must not affect the engine → no-op exporter by default | M | no | 2 |
| M-063 | P3-057 | Roll-up micro-batch pre-aggregation plus a state-level roll-up tier | SCALE-ARCHITECTURE §8.4; ADR-D-24 | SG-15 measurements | `sangam-rollup`, `generate_scale_changelogs.py` | Additive tier; scale changelog `--check` | L | no | 3 |

### E7: Integration (9)

| ID | Sources | Scope | Anchor | Deps | Files | Risk → guard | Size | Owner? | Wave |
|---|---|---|---|---|---|---|---|---|---|
| M-064 | P1-001 (S-6) | Event-gateway channel families, inbound and outbound, each classpath-optional: NATS JetStream, AWS SQS/SNS/EventBridge, Azure Service Bus/Event Grid, Google Pub/Sub, MQTT events. Use plain REST clients where a public REST API exists (the Service Bus precedent, `PolledChannels.java:139-153`); reuse Paho for MQTT | §6; ADR-D-14, D-16; R38, R4, R26 | none | `sangam-flowable-ports/.../events/channel/ChannelAdapters.java:80-105` (new switch arms), new adapters, `sangam-integration/.../publish/*Publisher.java`, `PublisherRegistry.java`, `ChannelsWiring.java`, poms (optional), `licence-policy.json`, `ReactorBannedDependenciesTest` | Air-gapped default "no outbound" → new arms only, off unless configured; Kafka/JMS/Rabbit untouched | XL | no | 3 |
| M-065 | P1-002 (S-7) | CloudEvents binary content mode in and out (Kafka headers, JMS properties, HTTP `ce-*`), opt-in per channel, structured stays the default | §6; R16 | M-064 | `CloudEvents.java:37-38`, `KafkaPublisher`, `JmsPublisher`, `HttpWebhookPublisher`, `InboundEventPipeline.java` | Existing consumers keep the structured format (default) | M | no | 3 |
| M-066 ⚠ | P1-003 (S-14) | Broker task mode: external-task creation is dispatched through the outbox as a CloudEvent; a reply consumer maps `(taskId, lockVersion, attemptId)` onto the VI.3 lease transitions; R26 principal/topic binding | §5.1; VI.3 / R9, R20, R36, R26; ADR-D-5 | M-064 | `sangam-integration` (new `broker/BrokerTaskDispatcher`, `BrokerReplyConsumer`), `sangam-engine` hooks, `IntegrationWiring.java` | **High semantic risk**: a stale reply must give `409 STALE_LEASE`, never bypass `OPERATION_LEDGER` → reuse the REST lease service; FwtWorkerCompat-style tests | L | no | 3 |
| M-067 | P1-004 | Serverless connectors as HTTP connector templates with provider auth (AWS SigV4, function key, GCP ID token), an async-invoke callback, element templates and simulators. No vendor SDKs | §5.1; R27; ADR-D-13 | M-075 | `sangam-integration/.../connector/http/*`, `LiveHttpConnectors.java`, `sangam-modeller/.../template/ElementTemplates.java`, `.../sim/` | No new engine path | M | no | 3 |
| M-068 | P1-005 | Call an API by contract reference: a `sangam:operationRef` extension, an OpenAPI/AsyncAPI document store per package, request/response JSON-Schema validation, a modeller picker | §3.3d L2; R16, R43, R44, R11, R27 | M-050 | `SangamBpmnParseListener.java`, `ConnectorInvoker.java`, `sangam-modeller/.../template/`, `Modeller.tsx`, `gen_openapi.py` | Only tasks with the attribute are validated (existing packs unaffected); swagger-parser is Apache-2.0 | L | no | 3 |
| M-069 | P1-006 | Arazzo 1.1.0 import (to BPMN with HTTP tasks) and export, REST endpoints, a UI action | §3.2, §3.3a | M-068 | `sangam-modeller` or `sangam-flowable-ports/.../modelimport/`, `gen_openapi.py`, UI | Additive | M | no | 4 |
| M-070 ⚠ | P1-009 | Temporal interop through Temporal's **HTTP API**: a start-and-await connector plus an inbound adapter. No Temporal SDK, because gRPC is banned (`ArchitectureGuardTest.java:151`, `pom.xml:340-341`, ADR-D-5) | §3.3d L10; ADR-D-5 | M-068 | `sangam-integration/.../connector/temporal/`, a simulator, an element template | ⚠ Temporal HTTP API coverage needs an evidence phase; classpath-gated | M | no | 4 |
| M-071 | P3-053 | Channel provider gaps: WeChat encrypted mode, media, token refresh and template rules; SAP RFC against a **customer-supplied** JCo (the LIC-2 driver pattern); a Microsoft Graph mail source; MATRIX and SLACK adapters; a conversation search index and scheduled retention | NG-12…NG-27 | none | `sangam-channels/**`, `ChannelsScheduler` | JCo cannot be shipped → reflection/`lib-ext` only; ChannelRuntime refuses unknown kinds today, and the new kinds are additive | L | no | 3 |
| M-072 | P2-022 | DigiLocker requester (pull with consent) connector plus simulator, bound as the `AdultIdentityVerifier`; an API Setu publication note (OpenAPI export profile) | R59 (GOV-9); ADR-D-13 | none | `sangam-integration/.../connector/gov/`, `sim/digilocker/`, `ComplianceWiring.java:124` | Additive | M | no | 3 |

### E8: Security and compliance (17)

| ID | Sources | Scope | Anchor | Deps | Files | Risk → guard | Size | Owner? | Wave |
|---|---|---|---|---|---|---|---|---|---|
| M-073 | P2-012 = P3-045 (XII.4 #2) | Signed Merkle checkpoints over audit-chain segments (Ed25519 signer reused), stored, exported, and verified by `/audit/verify`; accepts chains written before checkpoints | R42 / X.2.3.4 rule 4 | SG-15 (B15) releases `AuditChain`; Q-04 (production key only) | `sangam-core/.../layer/AuditChain.java:27`, `JdbcAuditExportAdapter.java`, checkpoint table (changelogs), a scheduler in `sangam-server`, `sangam-registry/.../Ed25519Signer.java` | Chain-head contention (SG-15 just changed it) → checkpoints read committed segments off the hot path; configurable interval | M | no (Q-04 production key) | 2 |
| M-074 | P2-013 (XII.4 #5) | Executable package signatures on APPROVED, verification on deploy and promotion, `ENV_BINDING` rows, export/import between environments, air-gapped verification | R43 / X.2.4.5; T70 | M-073 (key handling), M-075 | `sangam-rest/.../port/PackagePort.java:8-57` + adapter, `sangam-onboarding/.../bundle/BundleSigner.java` (reuse), changelogs, `routes/promotion.py` | `/packages` contract change; `PacksTenantDeployer` must keep working with unsigned dev packages → a "dev: unsigned allowed" profile flag | L | no (Q-04) | 3 |
| M-075 | P1-015 | Secret-provider resolvers over plain HTTP (Vault, AWS Secrets Manager, Azure Key Vault, GCP Secret Manager) plus a mounted-file resolver (K8s/Docker secrets). The env fallback stays | §7, §14.5; R27, R56 | none | `sangam-integration/.../connector/SecretResolver.java:9-36` + implementations, a `sangam-server` config bean (`WebhooksWiring.java:77`, `PipelineTelemetryWiring.java:76`), the Helm schema | Opt-in beans; no outbound by default (R56) | M | no | 3 |
| M-076 ⚠ | P2-023(a) | SAML 2.0 adapter per R50 "MUST provide SAML 2.0 … adapters" (`SANGAM.md:4415`) | R50 (GOV-6); X.4a | **Q-03**; Q-11 | `sangam-security/.../` (new saml package), `deploy/compose/keycloak/` | Filter chain and `ResolvedIdentityCache` just changed (SG-15) → a separate optional filter chain, off by default | M | **yes, Q-03** | 3 |
| M-077 | P2-023(b) | Parichay claims/profile mapper plus simulator conformance (T87, T89) | R50; ADR-D-13 | none | `sangam-security/.../oidc/`, `sangam-integration/.../sim/` | Additive mapper next to `KeycloakClaimsMapper` | M | no | 3 |
| M-078 | P2-023(c) | Aadhaar Sub-AUA connector stub plus simulator and data vault (no biometric storage) | R50; ADR-D-13 | none | `sangam-integration/.../connector/gov/`, `sim/`, `sangam-compliance` `NationalIdGuard` | Additive; the vault is encrypted (FieldEncryptor) | M | no | 3 |
| M-079 ⚠ | P2-025(a) | A concrete `dsc.sign` DSC connector plus a local DSC agent (PKCS#11 USB-token bridge) | R57 (GOV-7); VI.4 r5 | M-081 | `sangam-integration/.../connector/gov/` (new `DscConnector`), a new agent module, `sim/` | ⚠ Agent technology and OS support need evidence; additive | L | no | 4 |
| M-080 | P2-025(b) | PAdES/CMS: embed the ESP PKCS#7 into the PDF, RFC 3161 timestamp, LTV, signature validation for AU-01; bound to the approval hash | R57; VI.4 r5 | M-081 | `sangam-packs/.../core/SignatureDelegate.java:14-60`, `EsignConnector.java`, the M-081 renderer | Cryptographic correctness → validation against the simulator and standard test vectors | L | no | 4 |
| M-081 | P2-021 = P2-020 (PDF/UA part) | PDF/A-2b and PDF/UA renderer (Apache PDFBox; never iText/AGPL) bound to `DocumentExtrasPort`, `AuditExportPort` and the registry; bundled Devanagari fonts; a validation step | R61 (GOV-11); R42; R55 | Q-11 (PDFBox, fonts) | new renderer in `sangam-integration`; `PortsDocumentExtrasBridge.java:30`, `RegistryPortBridges.java:177`, `JdbcAuditExportAdapter.java:147-148`, `licence-policy.json` | Licence gate `--strict`; image size → fonts as a separate layer | L | no (Q-11) | 3 |
| M-082 | P2-030 (XII.4 #6) | ClamAV (clamd TCP) and ICAP `MalwareScanner` adapters, server wiring, a compose/Helm sidecar, a fail-closed setting | R47 / X.2.5.4; T74 | none | new adapter in `sangam-integration`; `ChannelsWiring.java:123,173`; `PortsDocumentBridge.java`; `deploy/compose`, `deploy/helm` | ClamAV is GPL but runs as a separate service (not linked) | M | no (Q-11 image) | 3 |
| M-083 | P1-017 | Optional OPA/Cedar `allow(principal, action, resource)` adapter for **object checks only**, deny-only (it can only restrict). Never used for list-visible actions | §7; VI.4 rule 1 + A-3 (list part superseded) | none | `sangam-security/.../policy/` | Two decision paths disagreeing (T37) → deny-only AND semantics; T37 equivalence test unchanged | M | no | 4 |
| M-084 | P3-047 = P2-031(b) (XII.4 #8) | Break-glass access: a time-boxed, dual-approved token class with an alarmed audit event and a review task (AJ-05 step 7; the README design) | AJ-05 | none | `sangam-security/**`, `sangam-server` wiring | Security surface → off unless configured; audit and alert tests | M | no | 3 |
| M-085 | P3-048 | Scheduled, resumable, tenant-scoped field re-encryption after key rotation, with a metric | field encryption (KEK) | none | `sangam-security` (`AesGcmFieldEncryptor.reencrypt`), a server scheduler | Data rewrite → idempotent per row; dry-run mode | M | no | 3 |
| M-086 ⚠ | P3-049 | Set-based field-mask projection for list views (instead of one object check per row) | R19 | SG-15 | `sangam-security` policy compiler | Must equal the per-row result → an equivalence property test | M | no | 3 |
| M-087 | P2-024 | DPDP: a data-processor agreement template (IN jurisdiction pack) plus a bound adult/guardian verifier | R53 (GOV-4); ADR-D-23 | M-072 | `sangam-compliance/src/main/resources/compliance/jurisdictions/IN/`, `ComplianceWiring.java` | Additive | S | no | 3 |
| M-088 | P2-027(a,b) | Licence process: an aggregated NOTICE (the Operaton NOTICE verbatim: Camunda, Odysseus JUEL, Quartz, ASF), a generated THIRD-PARTY-LICENSES book in the release | ADR-D-17; LIC-11; X.3.6 | none | root `NOTICE`, `deploy/licence/check_licences.py`, `release.yml` | none | S | no | 5 |
| M-089 | P2-026 | GeM SKU catalogue plus a local-content dossier template and generator (PK-07 public method) plus a procurement evidence-pack area | R62 (GOV-12); ADR-D-11 (a), (d); CF-10 | **Q-16** (content) | `sangam/deploy/evidence/`, `release.yml:306-311` | none | S | **yes, Q-16** (content) | 5 |

### E9: Operations (4)

| ID | Sources | Scope | Anchor | Deps | Files | Risk → guard | Size | Owner? | Wave |
|---|---|---|---|---|---|---|---|---|---|
| M-090 | P1-024 | Kubernetes operator: CRDs `SangamInstallation` and `SangamCell`, migration-Job orchestration under the schema lock, rolling restart | §5.5, §9; R12, R32; ADR-D-21 | none | new `deploy/operator/` | A separate artefact; Helm stays supported | XL | no | 4 |
| M-091 | P1-025 (S-4) | Prometheus alert rules (outbox at 50% R40, parked deliveries, `root_lock_wait_seconds`, incidents, stale relay, cert expiry) plus a Helm `PrometheusRule` template | §9; VI.2.3/4, VI.1 | metric names in `SangamMetrics.java` | `deploy/compose/observability/`, `deploy/helm/sangam/templates/` | none to code | S | no | 2 |
| M-092 | P1-026 | `sangam-cli upgrade check` (models, config, schema version, `ApiDeprecationRegistry`) plus OpenRewrite-recipe and codemod scaffolding | §14.7; R32, R43 | none | `tools/sangam-cli`, `sangam-rest/.../lifecycle/ApiDeprecationRegistry.java` | none | M | no | 4 |
| M-093 | P3-039 | CLI token refresh plus OS keychain storage (with a 0600 file fallback) | NG-23 | none | `tools/sangam-cli` | none | M | no | 3 |

### E10: Engineering gates (5)

| ID | Sources | Scope | Anchor | Deps | Files | Risk → guard | Size | Owner? | Wave |
|---|---|---|---|---|---|---|---|---|---|
| M-094 | P1-027 | Error Prone + NullAway, SpotBugs + FindSecBugs, Semgrep, Gitleaks, Trivy/Grype, OpenSSF Scorecard, a Helm CIS check, SLSA L3 provenance. **Report-only** with a baseline/suppression file | §14.5, §14.9 | Q-11 (plugins); Q-14 (making them required, existing) | `.github/workflows/security.yml`, `release.yml`, root `pom.xml` (profiles) | CI noise → report-only until after the test-phase triage | M | no (Q-11) | 1 |
| M-095 | P1-028 | Wire PIT, JaCoCo, jqwik, Jazzer, a Gherkin runner and Sonar with non-blocking thresholds; first harnesses (jqwik model generator, Jazzer BPMN/DMN/JSON parser targets) | §14.3 P4-P6, §14.4, §14.6 | Q-11 | root `pom.xml`, test trees of `sangam-engine`, `sangam-core`, `sangam-flowable-ports` | None to main code | L | no (Q-11) | 1 (wiring) / 2 (harnesses) |
| M-096 | P1-029 | A CI traceability gate over `spike/results/TRACEABILITY-REGISTER.csv`, a glossary with a bounded-context check, a PR template with an ADR link, a STRIDE threat model per component, CODEOWNERS, a DCO check | §14.1, §14.3, §14.7 | **Q-10** (enforcement only) | `.github/**`, `spike/results/`, `sangam/docs/` | none (record-only until Q-10) | M | no (Q-10 enforcement) | 1 |
| M-097 ⚠ | P1-030 | Determinism: ban `System.currentTimeMillis`, `new Date()`, `Instant.now()` and `ThreadLocal` in `sangam-core` packages; inject `Clock` and an id provider | §14.2 D11; ADR-D-27 (BusinessClock) | none | `BusinessDateGuardTest.java:28,119-123`, `ArchitectureGuardTest.java`, `sangam-core` call sites | A wide mechanical refactor → an allow-list ratchet (empty target), core only; Operaton `ClockUtil` out of scope | M | no | 2 |
| M-098 | P1-031 | Class-size gate (about 800 lines, with an allow-list ratchet), and freeze `SangamEngineSettings` after bootstrap | §1.2 W7, §14.2 D4 | none | `ArchitectureGuardTest.java`; `SangamEngineSettings.java:189-237` | Setter users at bootstrap → freeze only after `SangamEngineConfigurer` completes | S | no | 1 (gate) / 2 (freeze) |

### E11: Stage D/E items (11)

| ID | Sources | Scope | Anchor | Deps | Files | Risk → guard | Size | Owner? | Wave |
|---|---|---|---|---|---|---|---|---|---|
| M-099 | P3-046 | SCIM 2.0 `/scim/v2/Users` and `/Groups`, mapped to seats and postings (replaces the 501 at `JdbcIdentityAdminAdapter.java:59`) | R67 | M-051 (contract) | `sangam-security`, `sangam-rest`, `gen_openapi.py` | Security surface → its own role; audit | L | no | 4 |
| M-100 | P3-014 | Package Git pull/push through JGit (EDL-1.0, allowed), with credentialRef through `SecretResolver` and EgressPolicy | R69; PF-22, PF-37 | M-075; Q-11 | `sangam-packs` (new), the `PackageToolsPort` binding (`OperatonPackageToolsAdapter.java:346-355`) | A new outbound path → EgressPolicy allow-list | M | no (Q-11) | 4 |
| M-101 | P3-013 | Modeller multi-user presence (SSE) plus UI indicators | R69 | M-040 pattern | `sangam-modeller` (lease), `sangam-rest`, UI modeller | Additive | M | no | 4 |
| M-102 | P3-022 | Platform console plus backup orchestration: a `BackupPort` with adapters (K8s CronJob trigger and status; VM: the configured backup scripts), an API and a screen | R70; PF-25/26 | M-090 (optional) | new REST port, `sangam-server`, UI, Helm | Destructive operations → maker-checker and audit | L | no | 4 |
| M-103 | P3-023 = P3-054 (OCR/PDF part) | IDP: an OCR and extraction port with a Tesseract sidecar (Apache-2.0, air-gap friendly), PDF/image extraction for the knowledge base, a review screen | R75; PF-31; NG-03/04 | M-081 (PDFBox), Q-11 | `sangam-ai`, UI | Additive | L | no (Q-11) | 4 |
| M-104 ⚠ | P3-024 | RPA: a generic external-worker RPA bridge plus product adapters (choose by evidence: Robot Framework, Apache-2.0, and orchestrator REST) | R71; PF-29 | none | `sangam-integration` | Additive | M | no | 4 |
| M-105 | P3-052 | Connector breadth: GraphQL, JDBC, S3, chat through the connector SPI with EgressPolicy, plus a Camel task as an optional plugin module | R71; FC-08 | none | `sangam-integration`, a new optional module | Large Camel tree → optional module, licence gate | L | no | 4 |
| M-106 ⚠ | P3-050 | Engine source patches D-19 (SKIP LOCKED `ExternalTask.xml`), D-10 (per-engine clock), FC-24, D-27, D-52 **under ADR-D-16**: per patch, prove that no extension point exists, record it in the patch ledger, offer it upstream | ADR-D-16 (S-19); `sangam-engine/PATCHES.md` | evidence phase per patch | `sangam-engine`, `PATCHES.md` | **HIGH**: a changed engine jar affects every module and the DB matrix → only where there is no extension point; DB-matrix proof in the test phase | XL | no | 4 (evidence from 2) |
| M-107 | P3-051 (P-4, P-5, FC-18, EC-06) | Flowable-ports: CMMN plan-item state-machine extensions and repetition with collections (P-4, FC-56/57); native CMMN external worker and case migration (P-5, FC-11/19); historic-instance migration (FC-18); REST-backed data objects (EC-06, today 501) | FLOWABLE-INTO-OPERATON; PATCHES.md:13,28 | M-106 | `sangam-flowable-ports`, `sangam-engine`, `sangam-integration` (EC-06), the UI DataObjects screen | **High** (engine) → behind the existing CMMN isolation; run-off kept | XL | no | 4 |
| M-108 ⚠ | P3-051 (FC-51) + R17 | Experimental DB tier: CockroachDB and YugabyteDB (dialect, changelogs, a non-gating CI lane) | R17 (S-18); ADR-D-15 | none | `sangam-persistence`, `generate_changelogs.py`, `db-matrix.yml` | Tier-1 changelogs must not change → separate dialect files; experimental label in SUPPORT-MATRIX | XL | no | 4 |
| M-109 | P3-011 | Teams app: manifest, tab task list, task module, IdP token exchange, live updates | NG-11 | M-040 | new `sangam-ui` route or package; `sangam-channels` messenger | A new external surface → token exchange through the existing OIDC | M | no | 4 |

### E12: Docs and stale lines (3)

| ID | Sources | Scope | Anchor | Deps | Files | Risk → guard | Size | Owner? | Wave |
|---|---|---|---|---|---|---|---|---|---|
| M-110 | P3-032 + readiness §1 docs + P3 "BUILT but README stale" | Remaining drift. B16 already fixed the UI/modeller/deploy/TS/SUPPORT-MATRIX/HANDOFF §6c/SL-116/117 READMEs and SANGAM.md XII.1 (FILE-CLAIMS 04:29). Left: `sangam-security/README.md:253,260`; `sangam-server/README.md:307`; `OperatonPackageToolsAdapter.java:57` (comments are stored, not in memory); `SECTOR-CONFORMANCE.md:168,180-213`; `ACCEPTANCE-PLUG-AND-PLAY.md:6`; the `sangam-ops`/`sangam-ai`/`sangam-channels` "Screens needed"/"Server wiring" lines; `ui/README.md:426,436,660` versions (check after B16) | §P8 | none | those files | none | M | no | 1 |
| M-111 | P1-032, P3-043 (gRPC part), P3-028, P3-060, P2-S01 | Record the supersessions in SANGAM.md: §6 XPath → FW-42 (b); gRPC/proto deferred by ADR-D-5 (AsyncAPI kept); cam-script/iframe forms out (CSP plus ADR-D-8); road ETA/GIS → external worker. Plus the parity-doc line | X.5.3 pattern | none | `SANGAM.md` (X.5.3 table), `sangam-ui/OPERATON-UI-PARITY.md:33-34` | none | S | no | 1 |
| M-112 | P1-S16 | ADR record: the React 18 `sangam-ui` replaces the webapps-neo base (de facto, XII.1) | §8 | **Q-17** | `SANGAM.md` | none | S | **yes, Q-17** | 1 |

### E13: Register remainders (12; 5 of them reference-only)

| ID | Sources | Scope | Anchor | Deps | Files | Risk → guard | Size | Owner? | Wave |
|---|---|---|---|---|---|---|---|---|---|
| M-113 | P3-068 = SG-39 | A first-class private-ingress switch, an internal-LB default, a schema entry, a lint test | SG-39 (register :166) | **Q-09** | `deploy/helm/sangam/values.yaml:319-323`, `values.schema.json` | A public className is accepted silently today → schema validation | S | **yes, Q-09** | 2 |
| M-114 | P2-033 = P3-073 (SL-53a, Q34) | UTC persistence of engine timestamps: a MyBatis date type handler set when the `SqlSessionFactory` is built, migration changesets for 5 dialects, a register row, DST-fold tests | ADR-D-27 follow-up (`SANGAM.md:4335`) | **Q-08** | `sangam-engine` bootstrap, `generate_changelogs.py`, `sangam-persistence` | **HIGH**: a DB-contract change; existing rows are converted → COORDINATION §3 contract notice; a reversible changeset; keep the strict-UTC rule | L | **yes, Q-08** | 4 |
| M-115 | P3-075 | Register rows for AC-01…AC-11 (AC-08 code is M-023; AC-09 was unblocked by SL-78) | IX.8; X.0 | none | `TESTING-GAP-REGISTER.md` (integrator) | none | S | no (Q-15 for AC-10) | 1 |
| M-116 | P3-074 (SL-32) | A test clock and steering driver (`BusinessClock` hook) plus the 13 manual journeys (CORE-1/2/3, AC-02, AC-06, GP-49/47/28/44, SEC-BANK-AO-1, J-P1-E11, J-P1-E09) | SL-32 (register :60) | Q-14 (E09, AC-06) | `sangam-packs` / `sangam-server` tests | none to main code | M | partly, Q-14 | 2 |
| M-117 | P3-030 + P3-031 (UI-UPGRADE-1) | Toolchain upgrade to vite 8 (React stays at 18.3.1) with a regenerated lockfile and updated licence tables; close PR #3 and the stale branch | owner decision 2026-11-09 (Q20) | M-047, M-048; Q-11; Q-18 | `sangam-ui/package.json`, `package-lock.json`, `vite.config.ts`, `README.md:419-692` | bpmn-js and form-js against the bundler → after the component/E2E tests exist | M | no (Q-11, Q-18 for PR #3) | 5 |
| M-118 | Audit A "no register row" plus "stale status words" | Add rows (SL-53a, AT-6, Q22/CF-11, UI-UPGRADE-1, the LMMH hierarchy, `Jdbc.one`, the IfmsPacksIT executor race); fix the status words (SL-106, SL-107, SG-37, SG-38, SL-03, the SL-37 parent, SL-43) | register rules | none | `TESTING-GAP-REGISTER.md` (integrator only) | none | S | no | 1 |
| M-119 | Audit A (C-19) | Draft the C-19 [CONTRACT] notice for SL-71/72/73 to IFMS and log it in JL §5 (sending it is the owner's or integrator's act) | COORDINATION | none | JOINT-LEDGER (integrator) | none | S | no | 1 |
| M-120 | P3-067 = SL-102 | **Reference, no new work.** FIXED in tree by B12 (register :126) | ADR-D-20 | — | — | — | — | — | — |
| M-121 | P2-010 = P3-071 = SG-15 | **Reference, in progress (B15).** Round-trip reduction; the measurement is test-phase | ADR-D-24, R22 | — | — | — | — | — | — |
| M-122 | P3-069 = SL-78 | **Reference, fixed today** (register :103). Run in the test phase | — | — | — | — | — | — | — |
| M-123 | P3-070 = SL-79 | **Reference.** Diagnostic added (register :104); root cause and deterministic sync after a failing run | — | — | — | — | — | — | test phase |
| M-124 | P3-072 = SL-58 | **Reference, CLOSED: not a Sangam defect** (register :83; B14) | — | — | — | — | — | — | — |

### E14: Modelling, AI and domain data (12)

| ID | Sources | Scope | Anchor | Deps | Files | Risk → guard | Size | Owner? | Wave |
|---|---|---|---|---|---|---|---|---|---|
| M-125 | P1-007 | Semantic diff of BPMN, DMN and forms (element, flow and expression level) as a CLI, a REST endpoint and a UI compare view, usable in PR checks | §3.3d L1; R43 | none | `sangam-modeller` (new `diff/`), `tools/sangam-cli`, `sangam-rest`, `areas/modeller/` | Read-only feature | L | no | 4 |
| M-126 ⚠ | P1-010 (S-10) | DMN: run 1.4 and 1.5 models natively (bundled schemas); accept 1.6 through Sangam namespace handling for 1.5-compatible models (lint on 1.6-only constructs); a `dmn.preview-1.7` flag; deploy-time runner for embedded test cases (DMN16-6); R44 profile rows | §3.1, §3.3a/c; ADR-D-26 / SG-27; R8, R44 | M-052 | `sangam-engine/.../deploy/`, `.../feel/`, the `DmnModelLintTest` area | 245 shipped DMN files must not change results → no engine or FEEL change (SG-27 pin respected); ⚠ execution of 1.4/1.5 to be proven | L | no | 4 |
| M-127 | P2-015 = P1 §3 (§3.3b) | Bounded CMMN → BPMN converter for a supported subset, with a diagnostics report (T43), manual review and a state-migration mapping; run-off stays | R45 / E16 | none (M-107 for state mapping) | `sangam-flowable-ports/.../cmmn/`, `OperatonPackageToolsAdapter.java` (assessment rows) | Additive tool | L | no | 4 |
| M-128 | P3-055 | Modeller catalogue: hosted publishing and install (optional; off in air-gapped installs), and the token walk over boundary events, event sub-processes and compensation | NG-17, NG-18 | none | `sangam-modeller`, UI catalogue | Additive; offline signed index kept | M | no | 4 |
| M-129 ⚠ | P3-054 (non-OCR part) | AI runtime: a vector-index port (pgvector on PG; native vector types where the Tier-1 DB has one; an uncapped portable fallback), A2A streaming and push, MCP server-initiated requests | NG-03/04/05/08; ADR-D-15 | none | `sangam-ai` (`KnowledgeBaseService.java`) | Search results change from brute force → an equivalence test on the fallback | L | no | 4 |
| M-130 ⚠ | P3-002 | Bulk sinks for more object types (registry parties, config master codes, roster duties, resources: types with idempotent create APIs), each idempotent outside the connection | SL-44; BulkSinks SPI | none | `sangam-ops/.../bulk/BulkSinks.java`, owning modules, server wiring | Per-sink idempotency (README rule) | M | no | 3 |
| M-131 ⚠ | P3-056 | Map migrated legacy history (`OPS_MIG_ROW`) into product views and the audit chain | NG-25 | M-073 | `sangam-ops` | Audit integrity → legacy entries marked as imported in their own segment, never re-hashing the live chain | M | no | 4 |
| M-132 | P3-059 | Field evidence EXIF for HEIC and PNG | sangam-field | Q-11 (library) | `sangam-field` evidence | Licence check | S | no | 3 |
| M-133 ⚠ | P3-063 (S-23) | US health: payer APIs (CMS-0057-F, in ADR-D-23); US Core and PAS profile validation; Fidelius encryption; the CAQH CORE SOAP/MIME envelope. Plus IDR, AEOB and the emergency pack **if Q-12 says yes** | ADR-D-23 | Q-12 (No Surprises Act items) | `sangam-connectors-health`, `sec.ushealth` packs (packgen) | HAPI validator licence check | XL | partly, Q-12 | 4 |
| M-134 | P3-064 | PFM / IFC P and G rows: the missing packs (budget preparation, the budget-document bundle, breach case, standard reports, transfer-deadline monitor, cash plan, Do-Not-Pay, suspense ageing, budget-to-accounting reconciliation, replenishment); G rows (fiscal transparency, procurement complaints, debt chain, PEFA); IFC-189 profile thresholds; the [U] values | ADR-D-20 | **Q-13** (data); SL-102 done | packgen, profilegen, packs | Generator `--check`; data volume | XL | **yes, Q-13** (data) | 4 |
| M-135 ⚠ | P1 §3 (R48 note) | R48 live execution-state migration from Flowable: inventory, dry-run, rejection of unsupported states, worker freeze/drain, mapping, reconciliation, rollback conditions, duplicate-effect protection; plus a REST compatibility classification | R48 / E20; D-1 | M-107 (FC-18) | `sangam-flowable-ports` (importer), `sangam-ops` | ⚠ Scope needs an evidence phase (a real migration corpus is "add test") | XL | no | 4 |
| M-136 | P1 §3 (L5 note) | R42 replay fixtures: scenario regression of a new model against recorded fixtures (not Temporal-style replay) | R42 (replay E); §3.3d L5 | M-125 | `sangam-modeller`, test fixtures store | Fixtures are a separate purpose from audit (R42) | L | no | 4 |

**Out of 0.1.0 by a recorded decision (not resurrected):** P1-S01…S14 and P1-S17 (P1 §2); P1-S15 is a check (→ M-012) and P1-S16 an ADR record (→ M-112); P2-S01…S05; P1-032 (FW-42 (b)); P3-028; P3-031 (only its closure action, in M-117); P3-060; the gRPC/proto half of P3-043 (ADR-D-5); P3-066 and P2-RV6 (real government wire formats are parked ON-REQUEST by ADR-D-13 rule 1, an adoption task, not code).

---

### E15: Host-independent product architecture (owner directive 2026-11-12, replaces decisions 54/55)

Owner: the target is Option B, done incrementally. A framework-independent product layer with replaceable Spring and Quarkus hosts; the standalone Spring product stays the supported baseline; no duplicated wiring and no custom DI framework. The whole extraction is NOT required for 0.1.0: if Quarkus integration would delay the release, 0.1.0 uses Option C (no Quarkus host) and keeps this phased backlog. Option A (a limited engine SDK) only if genuinely needed, with an explicit capability boundary, never labelled full Sangam support. Native: JVM only first; native needs separate build, dependency and runtime evidence. Without Quarkus, only declared host-specific conveniences may go; financial correctness, authorization, audit, transaction integrity and duplicate prevention never weaken, and a missing required capability blocks the affected operations.

| ID | Sources | Scope | Anchor | Deps | Files | Risk → guard | Size | Owner? | Wave |
|---|---|---|---|---|---|---|---|---|---|
| M-137 | owner 2026-11-12; M-015 | Option B architecture document: assembly-layer boundary, per-host capability matrix (required vs convenience, fail closed), security-plugin capability inconsistency resolved with evidence, phased extraction plan, proof plan, 0.1.0 recommendation | ADR-D-16, D-27; SANGAM.md:298,592,1938 | none | `sangam/docs/M-015-QUARKUS-EXTENSION-DESIGN.md` | Doc only | M | no | 3 (W3-H) |
| M-138 | owner 2026-11-12 | Dependency and licence closure of Quarkus 3.40.1.x + Operaton Quarkus 2.2.0, classified against licence-policy.json (Narayana, LGPL, Jakarta EE level), before any adapter code | licence-policy.json | decision 56 downloads | report under sangam-handoff | Analysis only | S | no | 3 (W3-H) |
| M-139 | owner 2026-11-12 | Dependency-boundary guard: only host modules may import org.springframework or io.quarkus; current violations held in a shrink-only allow-list ratchet; clean build without Quarkus in CI | ArchitectureGuardTest pattern | M-137 | `sangam-core` guard tests, `ci-build.yml` | Guard only (ratchet) | S | no | 3 (W3-H) |
| M-140 | owner 2026-11-12 | Controlled host-migration procedure for persisted cases and jobs (drain or lease handover, executor stop and start, idempotency and outbox continuity, rollback, verification queries), with tests in the test phase | VI.3; R39 | M-137 | `sangam/docs`, test phase | Procedure + tests | M | no | 3 (doc), test phase |
| M-141 | owner 2026-11-12 | Extraction phase 1: the smallest slice (e.g. engine construction) moved from Spring wiring into the plain-Java assembly layer, Spring becoming a thin adapter, bean-set equality kept (M-014), shared behavioural tests | M-137 | M-137, M-139 | new assembly module, `sangam-server` config | HIGH: shared wiring files → after Wave 3 lanes release them; behavioural equality tests | L | no | 4, if M-137 shows no release delay; else 0.2 |
| M-141a | W3-H 2026-11-12T12:37:03Z; decisions 72-75 | Security jar split: the 6 Spring files of sangam-security/oidc (ClientJwtVerifier, IdentityResolutionFilter, SangamAuthenticationToken, SangamResourceServerConfiguration, SecurityWebSupport, TokenAuthenticationFilters) and the Spring Security compile dependencies move to a host module (sangam-security-spring or sangam-server); sangam-security keeps SangamSecurity, the engine plugin and plain-Java SAML logic. DoD: 0 Spring imports and no Spring compile dependency in sangam-security main, its 6 ratchet entries removed, ServerBeanSetEqualityTest unchanged, security switch-off still refused (decision 75) | M-139 | M-139 | sangam-security, new host module | HIGH: auth path; bean-set equality | M | no | 4 |
| M-141b | W3-H 2026-11-12T12:37:03Z | EngineAssembly in a plain-Java sangam-assembly module: settings record, engine plugin order of EngineConfigurationCustomizer and the module-port capability set as host-neutral code; EngineWiring and EngineConfigurationCustomizer call it; P-1 transaction sharing stays in the Spring host. DoD: same plugin list and order asserted by a test, a missing REQUIRED capability refuses start, 0 Spring imports in sangam-assembly (guard) | M-137 | M-141a | new sangam-assembly, sangam-server config | HIGH: engine construction | M | no | 4 |
| M-141c | W3-H 2026-11-12T12:37:03Z | The 4 sangam-rest non-host files naming Spring (TxSupport, ReadTransactions, OperatonOpsAdapter, JdbcAuditExportAdapter) move behind core Transactions or into the REST host area; ratchet 19 to 15 | M-139 | M-141b | sangam-rest | MEDIUM | S | no | 4, else 0.2 |
| M-141d | W3-H 2026-11-12T12:37:03Z | The registry (7), field (1) and geo (1) controllers move into the REST host area or a module host package; ratchet to 6 or less | M-139 | M-141c | sangam-registry, sangam-field, sangam-geo | LOW | S | no | 4, else 0.2 |
| M-142 | owner 2026-11-12 | Extraction phases 2..n (persistence/outbox/relay/inbox, security and authorization, integration, REST host adapter decision) | M-137 | M-141 | `sangam-server` config, modules | HIGH → per-phase equality tests, Oracle/SQL Server ITs | XL | no | 0.2 (phased) |
| M-143 | owner 2026-11-12; M-015 | Quarkus JVM host adapter over the assembly layer (thin, optional, absent from the standalone distribution); native only after separate evidence | M-137 | M-138, M-141/M-142 | new `sangam-quarkus-*` | Optional host; required capabilities fail closed | L | no | 0.1.0 only if no delay (else Option C: 0.2) |
| M-144 | owner 2026-11-12 (decision 81) | Host-neutral host contract: one documented host SPI over the plain-Java assembly layer (lifecycle, transaction/connection binding, HTTP binding, security-context binding, config source, scheduling, health), so Spring, Quarkus or any later host (e.g. Micronaut, Helidon, Jakarta EE, plain Java) is a thin adapter with no core change; a reusable host conformance kit (the shared behavioural tests every host must pass, incl. money, authorization, audit, duplicate prevention, fail-closed on a missing required capability); boundary guard bans every host framework package from non-host code, not only Spring and Quarkus | M-137 | M-137, M-139, M-141 | assembly module, `sangam-core` guard tests, host-conformance test module, `sangam/docs` | Contract + guard; no second host built for it | M | no | design 3 (W3-H, in M-137 doc); guard 3; SPI and kit with M-141 |
| M-145 | owner 2026-11-12 (decision 112) | Exact-version dependency inventory (Maven direct/transitive/plugins/BOMs; npm; SDKs; images; CI actions; fonts) with source, integrity, usage scope, SPDX licence and obligations, and a scoped decision register (ACCEPTED FOR DEFINED USE / WITH CONDITIONS / REVIEW REQUIRED / REJECTED FOR DEFINED USE) with evidence, accountable owner, conditions and review date | licence-policy.json, DOWNLOADS logs | none | `sangam-handoff/compliance/*`, later `sangam/deploy/licence/` | Read-only analysis | L | no | 3 (W3-X1, W3-X2) |
| M-146 | owner 2026-11-12 (decision 112) | Dependency-boundary and replaceability assessment: where money, authorization, accounting, workflow state and audit logic live; vendor types exposed; persisted formats; exit paths; every fallback classified SAFE / FAIL-CLOSED / SILENT-WEAKENING | decisions 54R, 75, 80, 81 | M-137 | `sangam-handoff/compliance/BOUNDARY-ASSESSMENT.md` | Read-only analysis | M | no | 3 (W3-X3) |
| M-147 | owner 2026-11-12 (decision 112); M-088 | Per-distribution SBOM (CycloneDX, server fat jar, starter, SDK packages, each image) and aggregated NOTICE and source-offer deliverables generated by the build | M-145 | M-145 | root pom, release workflow, `deploy/licence` | Build-time only | M | no | 4 |
| M-148 | owner 2026-11-12 (decision 112) | CI and release controls: licence gate over each SBOM against licence-policy and the decision register, unpinned image/action check, new-transitive-component diff, security findings gate, and a rule that a skipped or blocked check is never reported as PASS | M-145, M-147 | M-147 | `.github/workflows`, `deploy/licence` | Gate only | M | no | 4 |
| M-149 | owner 2026-11-12 (decision 112) | Remediation of the M-145 and M-146 findings (conflicts, REVIEW REQUIRED items, SILENT-WEAKENING fallbacks, missing obligations), each with affected modules and acceptance criteria | M-145, M-146 | M-145, M-146 | per finding | Root-cause fixes + tests | L | no | 4 |
| M-150 | owner 2026-11-12 (decision 112); M-144 | Cross-host equivalence: shared behavioural tests showing the same operation, inputs and rule versions give the same business result on every supported host, plus a supported build and run without optional Quarkus components | M-144 | M-141, M-144 | host conformance test module | Tests | M | no | 4 (code), test phase (run) |

## 2. Epics: summary

| Epic | Items | Work items | Size mix (S/M/L/XL) | Days (low-high) |
|---|---|---|---|---|
| E1 correctness and security defects | 13 | 13 | 5/7/1/0 | 34-91 |
| E2 plug-and-play | 6 | 6 | 0/3/0/3 | 69-159 |
| E3 SDKs | 8 | 8 | 1/2/4/1 | 59-144 |
| E4 UI | 22 | 22 | 8/9/4/1 | 87-221 |
| E5 contracts | 3 | 3 | 0/2/1/0 | 14-36 |
| E6 engine and scale | 11 | 11 | 1/4/5/1 | 73-180 |
| E7 integration | 9 | 9 | 0/5/3/1 | 59-145 |
| E8 security and compliance | 17 | 17 | 3/10/4/0 | 65-169 |
| E9 operations | 4 | 4 | 1/2/0/1 | 27-64 |
| E10 engineering gates | 5 | 5 | 1/3/1/0 | 18-47 |
| E11 Stage D/E | 11 | 11 | 0/4/4/3 | 104-247 |
| E12 docs | 3 | 3 | 2/1/0/0 | 5-14 |
| E13 register remainders | 12 | 7 (+5 reference) | 4/2/1/0 | 18-48 |
| E14 modelling, AI, domain | 12 | 12 | 1/3/5/3 | 110-262 |
| **Total** | **136** | **131** | **27/57/33/14** | **742-1,827** |

---

## 3. Waves and file-ownership lanes

**Rules for every wave:**
- One lane per file set. No two parallel workers touch the same file.
- Shared files each have exactly one owner per wave:
  - `SANGAM.md`
  - `TESTING-GAP-REGISTER.md` (always the integrator)
  - the root `pom.xml` and each module `pom.xml`
  - `sangam-api.yaml` and `gen_openapi.py` (with `api/routes/*.py`)
  - `generate_changelogs.py` with its baselines and `SangamSchema.java`
  - packgen and profilegen
  - `generate_realm.py` and the realm JSON
  - `application*.yaml`
  - `.github/workflows/*`
  - `deploy/helm/sangam/values.yaml` with `values.schema.json`
  - `sangam-ui/package.json` and its lock
  - `HANDOFF.md`
- FILE-CLAIMS.md CLAIM/RELEASE still applies.
- **B15 (SG-15) holds claims now.** No lane edits `AuditChain`, RootSeq, `ResolvedIdentityCache`, the outbox append or `TenantQuotaEnginePlugin` until B15 releases them.

### Wave 1: E1 defects and unblocked foundations (contracts and schemas before the SDKs and UI that consume them)

| Lane | Sole owner of | Items |
|---|---|---|
| W1-A Contracts | `sangam-api.yaml`, `gen_openapi.py`, `api/routes/*.py`, new `sangam-events.asyncapi.yaml`, the new control-plane/roll-up/gateway contracts, `OpenApiCompatibilityTest` and the deprecation file, the request records in `sangam-rest/.../modules/*`, `UiPluginController.java` | M-050, M-051 (contract), M-017, M-013 |
| W1-B Persistence and data correctness | `generate_changelogs.py` and baselines, `SangamSchema.java`, `sangam-persistence` changelogs, `sangam-core/.../IdempotencyStore.java`, `JdbcIdempotencyAdapter`, `IdempotencyService`, `sangam-billing/**`, `sangam-config/.../catalogue/NumberSeries*` | M-007, M-002 |
| W1-C Integration runtime | `sangam-integration/.../relay/*` (not the outbox append), `sangam-integration/.../inbox/*Consumer*`/`*Listener*`, `IntegrationWiring.java`, `CellWiring.java`, the `sangam-rest` work adapters (`EngineAdapterWorkAdapter`, `OperatonWorkAdapter`, `TrackingWorkPort`, the work controller), `application*.yaml` | M-005 → M-053; M-008 (evidence → code) |
| W1-D Security and compliance | `sangam-security/**` except `ResolvedIdentityCache` and READMEs, `sangam-security/pom.xml`, `sangam-connectors-health/**`, `sangam-rest/.../connectorshealth/*`, `HealthConnectorsProperties`, `sangam-compliance/src/main/**`, `ComplianceWiring.java` | M-006, M-009 (evidence → code) |
| W1-E Packs and config data | packgen, profilegen, `tools/lmmh/**`, `sangam-packs/src/main/resources/**`, `PacksWiring.java`, `sangam-packs/.../sim/**` | M-010 (evidence → packgen), M-011, M-004 |
| W1-F Engine | `sangam-engine/**`, `deploy/helm/sangam/values.yaml` and `values.schema.json` | M-055, M-012 |
| W1-G UI | `sangam-ui/**` including `package.json` and the lock | M-028; M-001 (once Q-01 and Q-11 are answered; until then, evidence and tests only) |
| W1-H Build gates | root `sangam/pom.xml`, `.github/workflows/*`, `.github/` templates, CODEOWNERS, `sangam-core/src/test/.../ArchitectureGuardTest.java`, `BusinessDateGuardTest.java` | M-094, M-095 (wiring), M-096, M-098 (gate) |
| W1-I Evidence only (read-only) | none | M-003 (design evidence while B15 holds `AuditChain`); M-106 per-patch extension-point inventory can start here or in W2 |
| W1-J Docs and integrator | `SANGAM.md`, `TESTING-GAP-REGISTER.md`, `HANDOFF.md`, `JOINT-LEDGER`, the READMEs named in M-110 (except `tools/lmmh`, which is W1-E) | M-110, M-111, M-112 (after Q-17), M-115, M-118, M-119 |

**Wave 1 items:**
- **Code:** M-001…M-013, M-017, M-028, M-050, M-051, M-053, M-055, M-094, M-095, M-096, M-098 (gate), M-110, M-111, M-115, M-118, M-119; M-052 (generator part).
- **Evidence only:** M-003.
- **Blocked inside Wave 1:** M-001 (Q-01; Q-11 for fake-indexeddb), M-094 and M-095 (Q-11 for plugin downloads), M-112 (Q-17, not urgent).

### Wave 2: consumers of the contracts, the starter, and engine internals

| Lane | Sole owner of | Items |
|---|---|---|
| W2-A Starter (**server-config freeze**) | `sangam-server/src/main/java/org/sangam/server/config/**`, new `sangam-spring-boot-starter`, `sangam-bom/pom.xml`, root `pom.xml` (reactor) | M-014 |
| W2-B SDKs | `sdks/**`, `.github/workflows/sdks.yml`, `deploy/native/sdk-worker/**` | M-020, M-021, M-022, M-023 (code), the SDK side of M-062 |
| W2-C UI | `sangam-ui/**` | M-029…M-039, M-045 (code), M-046, M-047, M-049, the UI part of M-051 |
| W2-D Engine | `sangam-engine/**` | M-054, M-056 (engine part), M-062 (engine part), M-098 (settings freeze); M-106 evidence |
| W2-E Persistence and audit (after B15 RELEASE) | `generate_changelogs.py`, `SangamSchema.java`, changelogs, `sangam-core/.../AuditChain.java`, `ConnectorInvoker.java` | M-073, M-056 (tables), M-003 (code) |
| W2-F Core determinism | `sangam-core/src/main/**` except `AuditChain`/RootSeq | M-097 |
| W2-G Deploy and ops | `deploy/helm/**`, `deploy/compose/observability/**` | M-091, M-113 (after Q-09) |
| W2-H Contracts (contract-first for Wave 3) | `sangam-api.yaml`, `gen_openapi.py`, `api/routes/*.py`, the AsyncAPI document | routes for M-025, M-059, M-074, M-099; M-052 (rows) |
| W2-I Acceptance tests | `sangam-packs/src/test/**`, `sangam-server/src/test/**/acceptance/**` | M-116 |
| W2-J Docs and integrator | `SANGAM.md`, register, `HANDOFF.md` | register rows for new ids; wave notes |

### Wave 3: features that add wiring (into the starter's auto-config structure from Wave 2)

| Lane | Sole owner of | Items |
|---|---|---|
| W3-A Event gateway and brokers | `sangam-flowable-ports/.../events/**`, `sangam-integration/.../publish/**`, new `broker/**`, `ChannelsWiring` channel arms | M-064, M-065, M-066, then M-058 |
| W3-B Connectors | `sangam-integration/.../connector/**` (http, gov, secrets), `sim/**`, `sangam-modeller/.../template/**` | M-067, M-068, M-072, M-075 |
| W3-C Channels | `sangam-channels/**` | M-071 |
| W3-D Security | `sangam-security/**`, `deploy/compose/keycloak/**`, `generate_realm.py` and the realm | M-076 (after Q-03), M-077, M-084, M-085, M-086 |
| W3-E Compliance and documents | `sangam-compliance/**`, new PDF renderer package, `PortsDocument*Bridge`, `RegistryPortBridges`, `JdbcAuditExportAdapter`, `licence-policy.json` | M-081, M-082, M-087, M-078 |
| W3-F Engine features | `sangam-engine/**`, `sangam-flowable-ports/.../dynamic/**` | M-057, M-059, M-060, M-056 (finish) |
| W3-G Packages and persistence | `PackagePort` and adapter, `sangam-onboarding/.../bundle/**`, changelog generator | M-074, M-042 (column) |
| W3-H Embedding variants | new `sangam-quarkus-extension`; tenancy auto-config files | M-015, M-016 (after Q-02) |
| W3-I SDK helpers | `sdks/**` | M-025, M-026, M-027 |
| W3-J UI | `sangam-ui/**` | M-040, M-042 (UI), M-044, M-047 (finish) |
| W3-K Script worker and roll-up | new `tools/sangam-script-worker`, `deploy/docker/**`, `sangam-rollup/**`, `generate_scale_changelogs.py` | M-061 (after Q-07), M-063 |
| W3-L Ops and CLI | `tools/sangam-cli/**`, `sangam-ops/**`, `sangam-field/**` | M-093, M-130, M-132 |

### Wave 4: Stage D/E, modelling, AI, heavy UI, SL-53a

| Lane | Sole owner of | Items |
|---|---|---|
| W4-A Engine patches and CMMN | `sangam-engine/**`, `PATCHES.md`, `sangam-flowable-ports/**` | M-106 → M-107, M-127, M-135 |
| W4-B Persistence | `generate_changelogs.py`, `sangam-persistence`, `db-matrix.yml` | M-114 (after Q-08), M-108 |
| W4-C Security and identity | `sangam-security/**`, `sangam-rest` SCIM controllers | M-099, M-083 |
| W4-D Integration breadth | `sangam-integration/**` | M-069, M-070, M-104, M-105, M-079, M-080 |
| W4-E Packages, modeller, AI | `sangam-packs` (Git), `sangam-modeller/**`, `sangam-ai/**`, `sangam-ops` (legacy) | M-100, M-101 (server side), M-103, M-125, M-126, M-128, M-129, M-131, M-136 |
| W4-F UI | `sangam-ui/**` | M-041, M-043, M-048, M-101 (UI), M-102 (UI), M-109 |
| W4-G Ops and deploy | `deploy/operator/**`, `deploy/helm/**`, `tools/sangam-cli` | M-090, M-092, M-102 (server/Helm) |
| W4-H Domain packs | packgen, profilegen, `sangam-connectors-health/**` | M-133 (after Q-12), M-134 (after Q-13) |

### Wave 5: closure before the test phase

| Lane | Sole owner of | Items |
|---|---|---|
| W5-A Release | `release.yml`, `sdks.yml`, root `pom.xml`, SDK manifests, `deploy/evidence/**`, `NOTICE`, `deploy/licence/**` | M-019 (after Q-05), M-052 (release step), M-088, M-089 (after Q-16) |
| W5-B UI toolchain | `sangam-ui/package.json` and lock, `vite.config.ts` | M-117 |
| W5-C Docs and integrator | `sangam/docs/**`, `SANGAM.md`, register, `HANDOFF.md` | M-018, final drift pass, register closure |

After Wave 5 comes the test phase (owner order): push to main, then unit, system, integration (5 DBs), acceptance and flow tests, then the E2E report. It also covers M-024, M-123, the SG-15 measurement, and the T-SCALE A/B for M-055.

---

## 4. Owner questions (merged and de-duplicated)

Kept only where evidence or a recorded decision cannot settle the point. These are ADR conflicts, owner-only facts, accounts, keys or people, and legal sign-offs. Stage D/E inclusion is already answered ("include") and is not asked.

Settled without asking (see §0): P1 Q2, Q3, Q4, Q5 (part), Q6, Q7, Q9, Q10, Q12, Q13, Q14, Q15, Q16 and Q18; P1 Q19 (FW-42); P2 on P2-001, 005, 006, 007, 015, 016, 025 and 028; P3 G-1, #2, the axe part of #4, the version part of #5, #6, #7, the starter part of #9, the AsyncAPI part of #9, the break-glass part of #10, and #11.

| Q | Exact question | Options (recommended first, with the reason) | Blocks | Wave 1? |
|---|---|---|---|---|
| **Q-01** | SL-129 (PWA offline queue): (a) key scope, (b) what happens to unsynced entries at logout or user switch, (c) web-subset envelope or full device registration plus `/sync`, (d) old plaintext entries, (e) the fake-indexeddb test dependency? | **(a)** One key per device, as `LocalQueueContract` specifies (`sangam-field/.../sync/LocalQueueContract.java:8-31`), with userId and tenantId bound as AES-GCM AAD, so an entry cannot be replayed under another user. That closes the SL-129 flaw without departing from the spec. *Alt:* a key per user and tenant. **(b)** Keep entries encrypted and locked to their author; prompt to sync at logout; never replay them under a different login. *Alt:* wipe at logout (data loss). **(c)** The full contract: device registration plus `/sync`, because the server side already exists and the owner rule is everything known. *Alt:* the web subset now. **(d)** Encrypt-and-bind only after the logged-in user confirms they are theirs; otherwise drop after a visible notice. *Alt:* drop silently. **(e)** Approve fake-indexeddb (Apache-2.0, dev-only) | M-001 | **YES** |
| **Q-02** | DB-per-tenant (FC-34, R4/A-5, R32) conflicts with XI.10.4 W-7 (Operaton's JVM-wide `ClockUtil`: one engine per JVM). How is it realised in 0.1.0? | **(A)** One engine per JVM: a server instance bound to one tenant DB, with its own outbox/relay/inbox, placed and routed by the ADR-D-21 control plane. `TenantEngineRegistry` stays a library for real-clock embedded hosts. This satisfies A-5 and W-7 with no engine change. **(B)** Several engines per JVM through `TenantEngineRegistry` with the D-10 real-clock limit, made fully correct only after the D-10 per-engine-clock patch (M-106). **(C)** Record cells as the realisation and keep FC-34 library-only (needs an ADR amendment) | M-016 | no |
| **Q-03** | R50 says the identity layer "MUST provide SAML 2.0 … adapters". Is that satisfied by Keycloak brokering in front of Sangam's OIDC, or must `sangam-security` be a SAML SP? | **(A)** Both: an optional SAML SP adapter in `sangam-security` (Spring Security saml2 / OpenSAML, Apache-2.0), plus Keycloak brokering documented as the default air-gapped path (X.4a). This meets R50 literally. Caveat: OpenSAML is published to the Shibboleth repository, not Central, so it needs repository approval (Q-11). **(B)** Brokering only, with an ADR amendment of R50 | M-076 | no |
| **Q-04** | Who holds the signing keys for audit Merkle checkpoints and executable packages, and is it one key per environment or per deployer? | **(A)** Separate Ed25519 keys per purpose and environment (audit checkpoint; package signing), referenced through the secret provider (M-075), held by a custodian you name. Reuse the existing `Ed25519Signer` / `BundleSigner` code. Checkpoint every hour or every 10,000 entries (configurable). Key separation limits the blast radius. **(B)** Reuse the onboarding bundle key | M-073, M-074 (production configuration only; code is not blocked) | no |
| **Q-05** | Publishing: which registries and namespaces for 0.1.0, do you own them, and who holds the signing credentials? | **(A)** Maven Central `org.sangam` **only if you control the sangam.org domain** (Central verifies the namespace); otherwise `io.github.sqamentor`. npm `@sangam`, PyPI `sangam-sdk`, NuGet `Sangam.Sdk`, Packagist `sangam/sdk`, all owner accounts with 2FA, and CI tokens stored by you as secrets. **(B)** GitHub Packages only for 0.1.0 | M-019 (+ coordinates in M-014, M-020, M-022) | no |
| **Q-06** | Which ≥8 further UI languages and which state language(s), who translates and reviews (including the Hindi native review), and is a machine draft allowed as a first pass? | **(A)** The Eighth-Schedule languages with the largest populations: Bengali, Marathi, Telugu, Tamil, Gujarati, Kannada, Malayalam, Odia, Punjabi (Urdu optional, RTL). A machine draft through the optional BHASHINI hook (R54), marked unreviewed, with native review before GA. **(B)** en/hi plus one state language now, the rest as data packs later | M-045 (content; the registry code proceeds) | no |
| **Q-07** | R6 script worker: which runtimes, and which isolation model on State-DC VMs without Kubernetes? | **(A)** One `sangam-script-worker` container (a Java SDK worker) that runs each execution as a child process of Node.js (MIT) or CPython (PSF), under seccomp, a no-network namespace and cgroup v2 limits. It needs only Docker/Podman on a VM. Rhino and GraalJS stay banned. **(B)** A container per execution (needs container-runtime API access, heavier). **(C)** WASM (Chicory), which R6 defers until its limits are verified | M-061 | no |
| **Q-08** | SL-53a (Q34): on what date is UTC timestamp persistence migrated, and may the strict-UTC start rule be relaxed afterwards? | **(A)** Code it in Wave 4 as reversible changesets; you pick the cutover date; keep the strict-UTC rule as well (defence in depth). ADR-D-27 says "the date is the owner's". **(B)** Code now, migrate after 0.1.0 | M-114 | no |
| **Q-09** | SG-39: which private-ingress route? | **(A)** Both: a first-class `ingress.private.enabled` with an internal `className`, **plus** per-cloud annotation presets in values (AWS, Azure, GCP), schema-validated, so that a public className with private=true is refused. **(B)** A separate internal ingress class only. **(C)** Annotations only | M-113 | no |
| **Q-10** | With a single owner and reviewer: enforce CODEOWNERS, two reviewers and DCO sign-off, or only record them? (Also open as Q19 / SG-18.) | **(A)** Record now (files present, DCO check report-only); enforce when a second maintainer exists. **(B)** Enforce now (every PR blocks on the owner) | M-096 (enforcement part only) | no |
| **Q-11** | Download approval for the code phase (extends Q12). Maven plugins (Error Prone, NullAway, SpotBugs, PIT, JaCoCo, jqwik, Jazzer, Cucumber); fake-indexeddb, axe-core, Playwright with browsers, vite 8; the .NET 8 SDK and PHP; Go, Rust and Kotlin toolchains with openapi-generator; PDFBox and fonts; JGit; OpenSAML (Shibboleth repo); the Tesseract and ClamAV images; an EXIF library? | **(A)** Approve the list. All are Apache-2.0, MIT, BSD, EPL/EDL or MPL dev-only, which `licence-policy.json:5-6` allows. Each goes through the licence gate, pinned and recorded in the SBOM. **(B)** Approve per item as each wave starts | M-001 (tests), M-094, M-095; later M-022, M-023, M-024, M-048, M-076, M-081, M-082, M-100, M-103, M-117, M-132 | **YES** (M-001 tests, M-094, M-095) |
| **Q-12** | US health: the payer APIs are in by ADR-D-23 (CMS-0057-F). Are the No Surprises Act items (IDR, AEOB, emergency pack, US-HLT-114…116, 122), which the README lists "for traceability only", in 0.1.0? | **(A)** Include them. ADR-D-23 names USA health as a jurisdiction pack, and "every known item" applies. **(B)** Keep them as traceability only (an ADR-D-23 scope note) | M-133 (part) | no |
| **Q-13** | IFMS and domain data: who supplies the [U] placeholder values and where does the IFMS pack data live (Q23)? Who confirms the full LMMH seed (correction slips 1128-1132)? | **(A)** The IFMS team supplies them through the JL channel, and packs keep [U] markers until then (ADR-D-13: parked, not blocking). **(B)** Use public sources only and mark them INFERRED | M-134 (data), M-004 (seed only) | no |
| **Q-14** | SL-32: what are the SoD seat-conflict semantics of J-P1-E09, and what is the exact AC-06 scenario text? | **(A)** Follow IFMS R-102 v0.1 as already inferred (JL:295) and have you confirm it; you supply the AC-06 text. **(B)** Leave both scenarios manual for 0.1.0 | M-116 (2 of 13 scenarios) | no |
| **Q-15** | Who runs the AC-10 usability session (Q28), with which officers, and who does the assistive-technology accessibility audit? | People to name. *Rec:* one department nominee plus an external accessibility auditor (GIGW/IS 17802 familiarity) | M-115 (AC-10), M-046 (audit), test phase | no |
| **Q-16** | GeM / R62: who signs the local-content (Class-I ≥50%) declaration, and which SKUs are listed for 0.1.0? | **(A)** You sign. SKUs per ADR-D-11: implementation/migration, AMC/support, hosted (MeitY CSP), training/certification. **(B)** Defer the listing; ship the generator only | M-089 (content) | no |
| **Q-17** | Record an ADR that the React 18 `sangam-ui` replaces the webapps-neo (Preact) base of Part I §8? | **(A)** Yes: it is de facto (XII.1), and an ADR closes the gap that P1-S16 found. **(B)** No ADR (leaves §8 contradicting the code) | M-112 | no |
| **Q-18** | Cleanup approvals: close PR #3 and delete the stale Dependabot npm branch (Q24); remove the retired worktrees (Q26); remove `handoff/worktree-snapshots/**` from main (SL-75 P2); delete the SL-128 stray copies that were moved to the scratchpad? | **(A)** Approve after the push, because everything in them is applied or superseded (audit D, readiness §2). **(B)** Keep them until the 0.1.0 release | M-117; hygiene | no |

**Wave-1 blockers:** Q-01 and Q-11.

**Owner actions already tracked elsewhere, not re-asked:** Q13 (who signs the dependency-check suppressions), Q14 (required checks), Q17 (private vulnerability reporting, SG-12), Q21 (dismissing alerts), storing the CI-NVD-OIDC SSM parameter, SL-07 (the C-7 check of ADR-D-26 semantics), SL-15 (the GA-55 form), SL-31 (power tables).

---

## 5. Totals, critical path and known risks

- **Totals:**
  - 136 master items: 131 work items plus 5 reference-only.
  - Sizes: 27 S, 57 M, 33 L and 14 XL.
  - **About 742 to 1,827 engineer-days** before the test phase. These are judgement estimates, not evidence.
  - With 10 parallel lanes that is about 75-185 working days of calendar time, if the owner questions are answered on time.
- **Critical path.** The longest serial chain is the Stage D/E engine line:
  - Line: M-106 engine patches (XL, evidence from Wave 2) → M-107 P-4/P-5/FC-18/EC-06 (XL) → M-135 R48 live migration (XL). That is about 60-135 days.
  - Close behind is the embedding line: M-014 starter (XL) → M-016 DB-per-tenant (XL, Q-02) → M-018 guide → M-019 publishing (Q-05). That is about 46-106 days.
  - Then the contract line: M-051 typed schemas (L) → M-020 generated SDKs (XL) → M-021/M-023 → M-019. That is about 39-93 days.
  - **To shorten it:** start the M-106 per-patch evidence in Wave 1/2 (W1-I/W2-D), and answer Q-02 and Q-05 early.
- **Known risks (evidence <97%; an evidence phase is needed before code):**
  - E1: M-003 (deadlock-free audit append), M-008 (broker freeze path), M-009 (denial-audit transaction), M-010 (DI/seat routing in the generator), M-011 (probably already resolved).
  - E2/E3: M-015 (Quarkus without the Spring product layer), M-016 (Q-02), M-020 (generator gaps C15).
  - E4: M-041 (cmmn-js maintenance).
  - E6: M-054 (ThreadLocal `Context` on virtual threads), M-055 (throughput effect), M-056 (job acquisition).
  - E7: M-066 (lease semantics over a broker), M-070 (Temporal HTTP API coverage).
  - E8: M-076 (OpenSAML repository), M-079 (DSC agent technology), M-086 (mask equivalence).
  - E11: M-104 (RPA product choice), M-106 (extension point vs patch), M-108 (CockroachDB/Yugabyte dialects).
  - E14: M-126 (whether 1.4/1.5 models actually execute), M-129 (vector types on Oracle and SQL Server), M-130 (sink type list), M-131 (audit integrity), M-133 (scope), M-135 (migration corpus).
  - M-001 depends on Q-01.

---

## 6. Coverage table (every source id → M-id)

| Source id | M-id | | Source id | M-id | | Source id | M-id |
|---|---|---|---|---|---|---|---|
| P1-001 | M-064 | | P2-001 | M-014 | | P3-001 | M-029 |
| P1-002 | M-065 | | P2-002 | M-016 | | P3-002 | M-130 |
| P1-003 | M-066 | | P2-003 | M-017, M-013 | | P3-003 | M-030 |
| P1-004 | M-067 | | P2-004 | M-050 | | P3-004 | M-031 |
| P1-005 | M-068 | | P2-005 | M-020 | | P3-005 | M-032 |
| P1-006 | M-069 | | P2-006 | M-021 | | P3-006 | M-033 |
| P1-007 | M-125 | | P2-007 | M-022 | | P3-007 | M-034 |
| P1-008 | M-059 | | P2-008 | M-061 | | P3-008 | M-036 |
| P1-009 | M-070 | | P2-009 | M-053 | | P3-009 | M-037 |
| P1-010 | M-126 | | P2-010 | M-121 (ref) | | P3-010 | M-038 |
| P1-011 | M-057 | | P2-011 | M-007 | | P3-011 | M-109 |
| P1-012 | M-005, M-058 | | P2-012 | M-073 | | P3-012 | M-041 |
| P1-013 | M-054 | | P2-013 | M-074 | | P3-013 | M-101 |
| P1-014 | M-055 | | P2-014 | M-052 | | P3-014 | M-100 |
| P1-015 | M-075 | | P2-015 | M-127 | | P3-015 | M-028 |
| P1-016 | M-016 | | P2-016 | M-056 | | P3-016 | M-051 |
| P1-017 | M-083 | | P2-017 | M-019 | | P3-017 | M-047 |
| P1-018 | M-043 | | P2-018 | M-045 | | P3-018 | M-048 |
| P1-019 | M-041 | | P2-019 | M-018 | | P3-019 | M-046 |
| P1-020 | M-044 | | P2-020 | M-046, M-081 | | P3-020 | M-045 |
| P1-021 | M-045 | | P2-021 | M-081 | | P3-021 | M-040 |
| P1-022 | M-027 | | P2-022 | M-072 | | P3-022 | M-102 |
| P1-023 | M-014, M-015 | | P2-023 | M-076, M-077, M-078 | | P3-023 | M-103 |
| P1-024 | M-090 | | P2-024 | M-087 | | P3-024 | M-104 |
| P1-025 | M-091 | | P2-025 | M-079, M-080 | | P3-025 | M-039 |
| P1-026 | M-092 | | P2-026 | M-089 | | P3-026 | M-001 |
| P1-027 | M-094 | | P2-027 | M-088, M-049 | | P3-027 | M-042 |
| P1-028 | M-095 | | P2-028 | M-025 | | P3-028 | SUPERSEDED (recorded by M-111) |
| P1-029 | M-096 | | P2-029 | M-060 | | P3-029 | M-035 |
| P1-030 | M-097 | | P2-030 | M-082 | | P3-030 | M-117 |
| P1-031 | M-098 | | P2-031 | M-001, M-084 | | P3-031 | SUPERSEDED (closure action in M-117) |
| P1-032 | SUPERSEDED (FW-42; M-111) | | P2-032 | M-006 | | P3-032 | M-110 |
| P1-033 | M-026 | | P2-033 | M-114 | | P3-033 | M-021 |
| P1-S01…S14, S17 | out (superseded) | | P2-S01…S05 | out (superseded; S01 recorded by M-111) | | P3-034 | M-023 |
| P1-S15 | M-012 | | P2-RV1 | M-009 | | P3-035 | M-020 |
| P1-S16 | M-112 | | P2-RV2 | M-002 | | P3-036 | M-024 |
| P1 §3 R13 | M-062 | | P2-RV3 | M-011 | | P3-037 | M-019 |
| P1 §3 R48 | M-135 | | P2-RV4 | M-008 | | P3-038 | M-022 |
| P1 §3 L4 | M-021 | | P2-RV5 | M-010 | | P3-039 | M-093 |
| P1 §3 L5 | M-136 | | P2-RV6 | PARKED (ADR-D-13) | | P3-040 | M-014, M-015 |
| P1 §3 L7 | M-060 | | P2 housekeeping (stray root dirs) | closed by SL-128 | | P3-041 | M-016 |
| P1 §3 §3.3b | M-127 | | SL-129 | M-001 | | P3-042 | M-017 |
| P1 §3 IX.6 Tier-2 | M-022 | | XII.9 host constraints | M-014 | | P3-043 | M-050 (gRPC part superseded; M-111) |
| P1 §3 AsyncAPI | M-050 | | Audit A missing rows / stale words | M-118 | | P3-044 | M-018 |
| P1 §3 WCAG | M-046 | | Audit A C-19 notice | M-119 | | P3-045 | M-073 |
| P1 §3 VI.7 script worker | M-061 | | Readiness §1 docs | M-110 | | P3-046 | M-099 |
| | | | | | | P3-047 | M-084 |
| | | | | | | P3-048 | M-085 |
| | | | | | | P3-049 | M-086 |
| | | | | | | P3-050 | M-106 |
| | | | | | | P3-051 | M-107, M-108 |
| | | | | | | P3-052 | M-105 |
| | | | | | | P3-053 | M-071 |
| | | | | | | P3-054 | M-129, M-103 |
| | | | | | | P3-055 | M-128 |
| | | | | | | P3-056 | M-131 |
| | | | | | | P3-057 | M-063 |
| | | | | | | P3-058 | M-002 |
| | | | | | | P3-059 | M-132 |
| | | | | | | P3-060 | SUPERSEDED (recorded by M-111) |
| | | | | | | P3-061 | M-053 |
| | | | | | | P3-062 | M-004 |
| | | | | | | P3-063 | M-133 |
| | | | | | | P3-064 | M-134 |
| | | | | | | P3-065 | M-003 |
| | | | | | | P3-066 | PARKED (ADR-D-13) |
| | | | | | | P3-067 | M-120 (ref) |
| | | | | | | P3-068 | M-113 |
| | | | | | | P3-069 | M-122 (ref) |
| | | | | | | P3-070 | M-123 (ref) |
| | | | | | | P3-071 | M-121 (ref) |
| | | | | | | P3-072 | M-124 (ref) |
| | | | | | | P3-073 | M-114 |
| | | | | | | P3-074 | M-116 |
| | | | | | | P3-075 | M-115 (+ M-023 for AC-08; M-122 for AC-09) |

**Completeness check:**
- P1: every PARTIAL/MISSING id 001-033 is mapped, as are S15 and S16 and the §3 code-gap notes.
- P2: every id 001-033 is mapped, as are RV1-RV6 and the housekeeping note.
- P3: every id 001-075 is mapped; 028, 031 and 060 are superseded and 066 is parked.
- No source id is unmapped.
