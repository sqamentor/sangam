# Sangam changes since `1105d9c` (summary for the next push)

**Base:** `1105d9c` (remote `main`, 2026-11-09). **Content:** the uncommitted integration tree of 2026-11-12, after the
code-complete phase. **State:** every change below is coded with its unit tests written and passes the compile-only
gate. **The test phase has not run:** "tests pending" means coded, tests written, not yet run. Nothing here is a test
result.

**Sources:** the rows of `TESTING-GAP-REGISTER.md` (cited by id), the owner decisions in JOINT-LEDGER §4, and the code
paths named. The register is authoritative; where this summary and a register row differ, the row wins.

## 1. Breaking and contract changes

### Money and packs

- **PACKS4: pack money in integer minor units.** Pack DMN, forms, set-variables, acceptance data, connectors and the
  profilegen bands move from rupees as doubles to integer minor units (packgen `money.py` convention: `money` DMN
  columns, `M()` in FEEL, no money literal in flow conditions). `LedgerConnector`, `LedgerDelegate` and the X12
  decoders now take minor units. The [CONTRACT] PACKS4-MINOR-UNITS notice went to IFMS on 2026-11-12 (PACKS4).
- **Pack version immutability; rollback becomes a roll-forward (SL-54).** The same pack id and version with different
  canonical content is refused with `PACK_VERSION_IMMUTABLE`. The canonical form is LF-normalised text and byte-exact
  binaries (`PackDeployer.canonical`). Equal canonical content reuses the existing deployment. A rollback now deploys
  the previous content as the next patch version above the highest installed one. A non-semver installed version
  answers 409 `PACK_VERSION_NOT_SEMVER`. A later release that reuses a roll-forward number is refused until it is
  bumped. In the OpenAPI `rollbackPack`, the `PackDeployResult` field `fresh` is corrected to `newDeployment`, the
  record field the controller returns.
- **129 pack version bumps.** 129 packs go from 1.0.0 to 1.0.1 against `15e4135` (`packgen/releases.py`:
  `BASELINE = '15e4135'`, `NEXT = '1.0.1'`). Three packs stay at 1.0.0: `gov.ifms.salary-monthly` and the two new packs
  `gov.intl.pfm-execution` and `sec.eu.gdpr-dsr` (SL-33).
- **Pack operations are serialised per tenant (SL-116).** Deploy, rollback and uninstall of a pack run under a row lock
  on `EVT_DEFINITION_SEQ` (kind `pack`), with a bounded wait.
- **Coded rejection reasons (SL-55).** Every automatic rejection route carries a code (`EXECUTION_FAILED`,
  `CLARIFICATION_LAPSED`, …). The inbox parks a deterministic 422 refusal as a PERMANENT dead letter with an
  `INBOX_MESSAGE_PARKED` audit entry, instead of retrying it forever.
- **Connector checks fail closed (SL-113, SL-115).** Pack defaults no longer pre-fill a connector check's result
  (for example `fundsAvailable`); a failed or skipped check takes an explicit "not confirmed" branch that ends in a coded
  rejection or an accountable hold (`FUNDS_NOT_CONFIRMED`, …).

### REST and `/engine-rest` (IFMS [CONTRACT] C-19)

- **SL-71:** `/error` now answers `application/problem+json`. `ProblemErrorController` replaces Boot's
  `BasicErrorController`; the original exception goes through `ProblemHandler`, a container-decided status is kept, and
  a 5xx never echoes its message. `sangam-control-plane` names the field for an unbindable body.
- **SL-72:** for tenant identities, a by-id miss gives one uniform 404 problem body (no engine code, no id, no path),
  including another tenant's object (`ENGINE-13034`). A `NullValueException` from a null argument also becomes this 404
  for tenants. Platform identities are unchanged.
- **SL-73:** `/engine-rest` is a fail-closed allowlist for tenant identities: anything else answers 403
  `ENGINE_REST_RESOURCE_NOT_TENANT_SCOPED`. `condition` and the history statistics stay excluded. A standalone task
  takes the caller's single tenant; a caller with several tenants is refused.
- **SL-77:** completing a task with a required form field missing answers 422 `REQUIRED_FIELDS_MISSING` with `fields[]`.
- **SL-80:** nested business keys give correlation parameter names joined with `.` (`a.b`, was `a/b`); an ambiguous
  segment answers 400.
- **SL-59:** an exhausted connection pool answers 503 `DATABASE_UNAVAILABLE` with `Retry-After` (2 s acquire wait).
- **Additive endpoints:** pack lifecycle (SL-41) and promotion (SL-42) endpoints, generated through `gen_openapi.py`.

### Tenancy and security

- **SL-86: `-` is reserved as a tenant id.** The shared ("no tenant") key is `TenantKey.SHARED = "-"`, equal to
  `AbsentKey.NONE` (SL-84). `RequestContexts` answers 401 for it, `OnboardingRequest` refuses it and
  `SangamTenantIdProvider` fails closed. Changesets `0036-01..53` re-key the `''` rows, 46 of them with a HALT preflight
  on a key collision. `AUDIT_*`, `ROOT_SEQ`, `OUTBOX` and `HRM_CHECKPOINT` are not re-keyed (hash chain and sequences),
  so their legacy `''` streams read as shared; `Relay.claim` drains a legacy `''` stream before the `-` one.
- **SL-70, SL-95: platform roles only from the platform issuer.** `sangam-admin` in a tenant-issuer token is the
  tenant administrator of that tenant; `PLATFORM_ADMIN`, `CELL_ADMIN`, `sangam-cell-admin` and `XCELL` come only from
  `sangam.security.platform-issuer`. With no platform issuer configured, nobody is a platform administrator.
- **SL-91:** `SangamEngineSettings.setDefaultTenantId` throws on a null or blank id.

### Configuration and start-up refusals

- **SL-104: no `IN` defaults in product code (ADR-D-20).** `sangam.cell.jurisdiction` (`SANGAM_CELL_JURISDICTION`) is
  required on a cell; `sangam.ledger.rate-scope` (`SANGAM_LEDGER_RATE_SCOPE`) has no default and a cross-currency
  conversion fails naming it; the payment currency and the registry connector have no default either. Pack, case and
  hearing time use the tenant's business zone. The gov-pack default connector bindings and the payment-file worker
  topic are now pack data (`pack.json` `connectors[].simulatorBinding` and `workers[]`); `sangam.packs.bindings.*` still
  overrides them, and `governmentDefaults()` is deleted. The `IN` values remain only in the development compose files.
- **SL-109: one production predicate.** Production is `sangam.security.production=true` or an active `prod` profile,
  for every guard. Under Helm production (which sets only `SANGAM_SECURITY_PRODUCTION`) the debugger now fails the
  start-up, and the dev-token filter, the simulator fallback, the health simulator, the webhook and live HTTP rules and
  the modeller mocks are refused, which they were not before.
- **SL-103:** in production the server refuses to start when `SANGAM_DB_USER` or `SANGAM_DB_PASSWORD` is unset, or
  the password equals the shipped default.
- **SL-53 / ADR-D-27:** an engine JVM whose default zone is not UTC is refused at start-up. The business zone of timers
  is `sangam.engine.timer-zone`.
- **SL-117:** the `sangam.compliance.tenants` fallback to `sangam.tenant.default`/`dev` is removed. The schedule
  tenants are the tenant directory plus that optional list.
- **SL-125: a missing fiscal year fails closed (owner decision 2026-11-12).** No code assumes a 1 January calendar
  year any more. `FiscalCalendar.from` returns empty when `locale.fiscal` is absent; inside the map `startMonth`,
  `startDay` and `label` are required. `LocaleSettings.fiscal()` throws `FiscalYearNotConfiguredException`
  (`FISCAL_YEAR_NOT_CONFIGURED`, naming the profile and `locale.fiscal`); `hasFiscal()` is new. The profile resolver
  reports WARNING `FISCAL_NOT_CONFIGURED`, and ERROR `FISCAL_INVALID` for an incomplete map. `layers.yaml` no longer
  requires `locale.fiscal`, so the US profile stays valid without one. Ledger periods (`LedgerTenantDefinitions`,
  `LedgerConnector`, a business failure that is never retried), registry `fiscalStartMonth` (for `FISCAL_YEAR`, `{fy}`,
  `{fyStart}`, `FISCAL_YEAR_END`) and programme `fiscalYearStartMonth` now require the fiscal configuration;
  `pack-requires.json` requires `locale.fiscal` for every pack that reaches the resolver. Caveat: number series already
  stored with `fiscalStartMonth` 1 keep it; the shipped seeds hold none, so there is no migration.
- **SL-127: billing has no invented period.** Invoice, penalty, waive and refund without a period use the tenant's
  fiscal period; with no fiscal year they fail `FISCAL_YEAR_NOT_CONFIGURED` (422 on REST). The default period label
  changes from the calendar year (for example `2026`) to the fiscal label (for example `2026-27`); owner accepted
  2026-11-12.
- **SL-126 (UI):** the number-series form sends the server's field names `reset` and `fiscalStartMonth` (it sent
  `resetPolicy`, which the registry ignored, so a UI-defined series never reset); `fiscalStartMonth` (1-12) is offered
  only for a `FISCAL_YEAR` reset.
- **SL-87:** `CH_MESSAGE.body` is nullable; a message with neither body nor attachments answers 422
  `CHANNELS_REJECTED`.
- **Helm (SL-59):** the chart default `database.pool.connectionTimeoutMs` goes from 10000 to 2000.

### SDKs (SDK-MODEL-DRIFT): source-compatibility changes

The hand-written models now follow `sangam-api.yaml` exactly: optional fields are never defaulted, and the A-1 rule
also requires `lockVersion`. The stray `effect` field is removed (the server never sends it, `WorkPort.java:28-32`), so
irreversibility is the subscription flag in every SDK. A per-SDK contract guard checks 18 schemas.

- **Java:** model records change shape (`AuditEntry`, `CompleteTaskRequest`, `ExtendLockResult`, `ExternalWork`,
  `FailWorkRequest`, `FetchAndLockRequest`, `FetchTopic`, `ReturnTaskRequest`, `TaskView`, `WorkOperation`), with
  `TaskHandler`, `TopicSubscription` and `SangamWorker` adjusted. Code that calls the record constructors must be
  updated.
- **.NET:** the same record changes in `Models.cs`, with `Apis.cs`, `SangamWorker.cs` and `TaskContext.cs`.
- **TypeScript:** fields that are optional in the YAML are optional in `models.ts`.
- **PHP:** `ExternalWork` `lockVersion` and `variables`, and `LockState` `cancelRequested`, are nullable with no
  defaults; `toTopicRequest()` is deprecated. PHP is not run locally (no PHP installed); its first PHPUnit run is the
  `sdks.yml` php job.
- **All SDKs (SDK-STORED-OUTCOME):** Python, TypeScript, PHP and .NET workers complete from the stored outcome, as Java
  does.

## 2. New configuration properties

| Property (env) | Default | Item |
|---|---|---|
| `spring.datasource.hikari.connection-timeout` (`SANGAM_DB_CONNECTION_TIMEOUT_MS`) | 2000 ms | SL-59 |
| `spring.datasource.hikari.validation-timeout` (`SANGAM_DB_VALIDATION_TIMEOUT_MS`) | 1000 ms | SL-59 |
| `sangam.db.network-timeout` (`SANGAM_DB_NETWORK_TIMEOUT`) | 300s; 0 disables | SL-59 |
| `sangam.db.unavailable-retry-after` (`SANGAM_DB_UNAVAILABLE_RETRY_AFTER`) | 2s | SL-59 |
| `sangam.datasource.replica.connection-timeout`, `.validation-timeout` | the main pool's values | SL-59 |
| `sangam.security.platform-issuer` (`SANGAM_SECURITY_PLATFORM_ISSUER`) | empty: nobody is platform administrator | SL-70 |
| `sangam.security.platform-jwk-set-uri` (`SANGAM_SECURITY_PLATFORM_JWK_SET_URI`) | empty | SL-70 |
| `sangam.onboarding.environment` (`SANGAM_ONBOARDING_ENVIRONMENT`; Helm `server.environment`) | none: unset gives 409 `PROMOTION_ENVIRONMENT_NOT_SET` | SL-42 |
| `sangam.cell.jurisdiction` (`SANGAM_CELL_JURISDICTION`) | none (was `IN`); required on a cell | SL-104 |
| `sangam.cell.residency` (`SANGAM_CELL_RESIDENCY`) | the jurisdiction (was `IN`) | SL-104 |
| `sangam.ledger.rate-scope` (`SANGAM_LEDGER_RATE_SCOPE`) | none (was `IN`) | SL-104 |
| `sangam.engine.timer-zone` | `UTC` | SL-53 |
| `sangam.packs.operation.lock-timeout` | 10 s | SL-116 |
| `sangam.compliance.tenants` (`SANGAM_COMPLIANCE_TENANTS`) | empty (was a fallback to `sangam.tenant.default`, then `dev`) | SL-117 |

Sources: `sangam-server/src/main/resources/application.yaml` (diff against `1105d9c`), `ReadReplicaWiring.java:52`,
`PacksWiring.java:95`, `LedgerWiring.java:114-123`, `CellWiring.java:205-208`.

## 3. New metrics

| Metric | Type | Tags | Item |
|---|---|---|---|
| `sangam.schedule.tenants` | gauge (tenants in the last cycle) | `scheduler` | SL-117 |
| `sangam.schedule.empty` | counter (empty cycles) | `scheduler` | SL-117 |
| `sangam.schedule.directory_failures` | counter (failed tenant-directory reads) | `scheduler` | SL-117 |

Source: `sangam-server/.../config/ScheduleTenantsMonitor.java:36-38`.

## 4. New reason codes and problem codes

- **Pack rejection reason codes:** the generated catalogue `sangam-packs/src/main/resources/META-INF/sangam/reason-codes.json`
  (packgen `reason_codes.py`; at this regeneration 60 labelled codes plus 2 dynamic sources). An uncatalogued code
  stops generation (SL-55, SL-113, SL-115). Read the file rather than a list here.
- **Problem codes named in this summary:** `PACK_VERSION_IMMUTABLE`, `PACK_VERSION_NOT_SEMVER` (SL-54);
  `ENGINE_REST_RESOURCE_NOT_TENANT_SCOPED` (SL-73); `REQUIRED_FIELDS_MISSING` (SL-77); `DATABASE_UNAVAILABLE` (SL-59);
  `PROMOTION_ENVIRONMENT_NOT_SET` (SL-42); `CHANNELS_REJECTED` (SL-87); `PACK_OPERATION_IN_PROGRESS` (SL-116, 409);
  `FISCAL_YEAR_NOT_CONFIGURED` (SL-125, SL-127; 422 on the billing REST API). Profile findings `FISCAL_NOT_CONFIGURED`
  (warning) and `FISCAL_INVALID` (error) (SL-125). Audit entry `INBOX_MESSAGE_PARKED` (SL-55).

## 5. Ids fixed or coded in the tree (tests pending)

Every id below is "tests pending": coded, tests written, not yet run in the test phase. Some rows also record an
earlier single-verified local run; that is not the test phase and does not close the row (SD-428).

| Id | Change (register row, shortened) |
|---|---|
| SG-03 | (a) `S3DocumentStore` takes an injected HttpClient and Clock; (b) `_shared` content key kept and documented; (c) task suspension events |
| SG-05 | `sqlserver2025` DB-matrix lane, tier 1-candidate, non-gating |
| SG-16 | `tools/provenance/run_provenance.py`: commit, dirty-diff SHA-256, JDK, test-JVM zone, database, drift per CI test job |
| SG-19 | `EngineNoDdlStartH2Test` (no DDL from the engine) |
| SG-36 | live HTTP connector resolves `secret://` default headers |
| SG-37 | consumer ids out of product files (`ConsumerIdsOutOfProductFilesTest`); consumer acceptance tests moved to `acceptance/consumer/ifms` (partly coded) |
| SG-38 | production settings traced to the pod; production boot test per fail-fast case (partly coded) |
| SL-04 | message to a multi-instance branch: 2 more `BranchCorrelationH2Test` cases |
| SL-33 | worldwide PFM and EU GDPR DSR packs with conformance methods |
| SL-42 | `PromotionController` (bundle export, plan, apply bound to the plan hash) |
| SL-45, SL-69 | cross-tenant `/engine-rest` creation leaks (`TenantScopedCreation`); MDC keys cleared |
| SL-53 | strict UTC engine JVM (ADR-D-27) |
| SL-54 | pack-version immutability, roll-forward rollback |
| SL-55 | coded rejection routes; inbox parks deterministic refusals; reason-code catalogue |
| SL-56 | generator outputs `text eol=lf` |
| SL-57 | `gov.ifms` budget distribution seat data |
| SL-59 | bounded JDBC waits; 2 s acquire, 503 with Retry-After |
| SL-61, SL-62, SL-65, SL-66 | `secret://` read at call time; case-insensitive headers sent once; guarded request building; SOAP headers |
| SL-63 | compiles on CI JDK 25.0.5 and 27.0.1 |
| SL-64 | ShellCheck fixes in `deploy/airgap` |
| SL-67 | one-JVM server test run: Spring context cache size 1 |
| SL-68 | bare decision citations |
| SL-70, SL-95 | platform roles only from the platform issuer |
| SL-71, SL-72, SL-73 | problem+json on `/error`; uniform 404; `/engine-rest` allowlist |
| SL-74 | three empty root files removed |
| SL-75 | P1: CodeQL and Dependabot ignore `handoff/**` (P2 is the owner's decision) |
| SL-76 | token-authenticated events API skips the channel HMAC |
| SL-77 | required form fields enforced at completion |
| SL-80 | correlation parameter names from RFC 6901 pointers |
| SL-81 | Jackson 3 strict primitives restored |
| SL-82 | `OutboxCapacityMonitor` null unboxing |
| SL-83, SL-108 | `SecurityAdminBridge` uses database time |
| SL-84 | Oracle `AbsentKey.NONE = "-"` sentinel; changesets `0033-01/02` |
| SL-85 | `LiveHttpConnectorsTest` key-store fixture |
| SL-86 | `-` as the shared tenant key; changesets `0036-01..53` |
| SL-87 | nullable content columns; changesets `0035-01..03` |
| SL-88, SL-89, SL-99 | SQL Server null binds; bounded lock waits retried; session lock timeout restored |
| SL-90 | pack ledger and batch keys use `Vars.tenantKey` |
| SL-91 | `setDefaultTenantId` refuses null or blank |
| SL-92 | `sangam-resources` `Sql` ObjectMapper |
| SL-93, SL-96 | dialect-neutral `DialectSchemaIT`; per-database isolated test namespaces |
| SL-94 | MySQL utf8mb4 engine patch |
| SL-97, SL-98 | ledger currencies from the profiles; a money ledger without an ISO 4217 currency is refused |
| SL-100 | concurrent JIT provisioning (no 409 `USER_EXISTS`) |
| SL-101 | multi-instance compensation runs once per completed instance |
| SL-103 | production DB credentials |
| SL-104 | no country defaults in product Java (`DomainNeutralityGuardTest`) |
| SL-105 | `PATCHES.md` complete (B-1…B-26, I-1…I-19) with pins |
| SL-106 | reactor maven-enforcer `bannedDependencies` (partly; open) |
| SL-107 | direct unit tests for changed code (partly; open) |
| SL-109 | one production predicate |
| SL-110 | undefined `-Psangam-oracle-driver` / `-Psangam-db2-driver` flags removed |
| SL-112 | `mariadb` acceptance lane |
| SL-113, SL-115 | fail-closed connector checks |
| SL-116 | pack operations serialised |
| SL-117 | schedule tenants from the directory, WARN throttle and metrics |
| SL-118 | core execution: status lookup after a lost callback |
| SL-125 | a missing fiscal year fails closed core-wide (`FISCAL_YEAR_NOT_CONFIGURED`); no assumed start month |
| SL-126 | UI number-series form uses the server names `reset` and `fiscalStartMonth` |
| SL-127 | billing refuses a missing period without a fiscal year; default label is the fiscal label |
| SL-102 | _(placeholder: being coded by another worker; row text follows from the integrator)_ |
| SL-78 | _(placeholder: being coded by another worker; row text follows from the integrator)_ |
| SL-79 | _(placeholder: being coded by another worker; row text follows from the integrator)_ |
| SG-15 | _(placeholder: round-trip work being coded by another worker; row text follows from the integrator)_ |
| PACKS4 | pack money in minor units |
| APPROVE-401 | problem bodies from server controllers (applied 2026-11-10) |
| CI-NVD-OIDC | dependency-check reads the NVD key through OIDC (waits for the owner to store the parameter) |
| SDK-STORED-OUTCOME, SDK-UNIT-TESTS, SDK-MODEL-DRIFT, CODESCAN-SDK-TS-REDOS | SDK completion from the stored outcome; .NET/TS/PHP unit tests; models aligned to the YAML; TS ReDoS fix |
| ROUTING-INCIDENT-CT, SG-35-SANGAM | ROUTING-INCIDENT v1 contract test; bill-pack regression for `0d04fea` |

Decided without code: SL-114 (Db2 dropped from Tier 2, owner 2026-11-12, "Drop from Tier 2 (Recommended)"). Still
open: SL-111, SL-58, SG-39 and the test-phase rows (CODE-COMPLETE-PLAN "Test-phase only"). SL-102, SL-78, SL-79 and
SG-15 are being coded (placeholders above). The 0.1.0 scope also takes in the plug-and-play and UI gaps of the
readiness report (`SANGAM.md` XII.9).
