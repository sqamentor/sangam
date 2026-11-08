# sangam-server

Owner: WS-REST. A Spring Boot **4.1.2** application (Spring Framework 7.0.10, Spring Security 7.1.2).

**Version note for WS-DEPLOY.** `deploy/README.md` §10 says "Spring Boot 3.x". The server is on **4.1.2**, because `operaton-bpm-spring-boot-starter` 2.2.0 is built against it (`operaton-root` 2.2.0: `version.spring-boot=4.1.2`). The jar layout, PropertiesLauncher, `loader.path`, actuator probes and the `management.server.port` behaviour are the same as on 3.x.

The server provides:
- **Sangam API** at `/api/v1/**`: sangam-rest controllers, ports and default adapters;
- **Operaton REST** at `/engine-rest/**`: unchanged, behind Sangam authentication, open only to engine administrators;
- **Actuator** on the **management port 8081** (`SANGAM_MANAGEMENT_PORT`);
- the **OpenAPI 3.1 contract** at `/api/v1/openapi.yaml`, served offline (AC-11).

## Run

```
cd sangam
mvnw.cmd -q -B -DskipTests -pl sangam-server -am install
mvnw.cmd -pl sangam-server spring-boot:run -Dspring-boot.run.profiles=h2
curl -H "Authorization: Bearer sangam-dev-token-change-me" http://localhost:8080/api/v1/me
curl http://localhost:8081/actuator/health/readiness
```

Packaged jar (PropertiesLauncher, `<layout>ZIP</layout>`). Extra drivers come from `loader.path`:

```
java -Dloader.path=/opt/sangam/lib-ext -jar sangam-server-0.1.0-SNAPSHOT.jar --spring.profiles.active=postgres,oidc
```

The fat jar does **not** contain the MariaDB driver (LGPL-2.1, LIC-4/LIC-5). It is excluded from `repackage`. The image adds it to `/opt/sangam/lib-ext`, as does the Oracle `ojdbc` driver supplied by the customer. Inside the build, `spring-boot:run` and tests still have the MariaDB driver on the runtime class path.

## Profiles

| Profile | Database | Driver in the jar? | Notes |
|---|---|---|---|
| `h2` (default) | in-memory H2 | yes | Development only. Dev static token. Operaton creates its own schema. If no Liquibase changelog is present, the product tables come from `org.sangam.core.layer.Schema` (`sangam.persistence.dev-schema=true`) |
| `postgres` | PostgreSQL | yes (pgJDBC) | Tier 1 |
| `oracle` | Oracle | **no**: customer-supplied via `loader.path` (LIC-4) | Tier 1 |
| `sqlserver` | SQL Server | yes (mssql-jdbc) | Tier 1. **RCSI must be ON** (`sangam.db.require-rcsi`) |
| `mysql` | MySQL 8.4 LTS | **no**: MariaDB Connector/J via `loader.path` (Connector/J is never shipped) | Tier 1 under R84. Session READ COMMITTED, utf8mb4, binary collation. P-1 datetime precision comes from sangam-engine |
| `oidc` | — | — | JWT resource server from `SANGAM_OIDC_ISSUER`. Combine it with a database profile |

All database profiles read `SANGAM_DB_URL`, `SANGAM_DB_USER` and `SANGAM_DB_PASSWORD`. The non-h2 profiles write JSON (ECS) logs.

## Deployment contract (deploy/README.md §10)

| Item | Implementation |
|---|---|
| Ports | App on `8080` (`SANGAM_PORT`). Management on `8081` (`management.server.port`, `SANGAM_MANAGEMENT_PORT`) |
| Probes | `/actuator/health/liveness` and `/actuator/health/readiness` on 8081. Also `/livez` and `/readyz` on 8080 (`add-additional-paths`) |
| `SANGAM_LIQUIBASE_MODE` | `startup` (default): update at start. `validate`: startup fails if changesets are pending (`Liquibase.listUnrunChangeSets`). `migrate-only`: runs the product changelog; Operaton creates or updates its schema (`operaton.bpm.database.schema-update=true` for that run only); then exits 0. `update-sql`: writes the pending SQL to stdout or to `SANGAM_LIQUIBASE_SQL_FILE`, then exits 0. `release-locks`: `Liquibase.forceReleaseLocks()`, then exits 0. One-shot modes disable the job executor and bind ports `0`. Code: `config/LiquibaseMode`, `config/SchemaBootstrap` |
| `SANGAM_JOB_EXECUTOR_ENABLED` | → `operaton.bpm.job-execution.enabled` (default true) |
| `SANGAM_RECONCILE_ON_START` | → `sangam.reconcile-on-start`. Bound here; **consumed by sangam-integration** (reconciliation scan, deploy §4.3) |
| `SANGAM_KEYS_DIRECTORY` | → `sangam.keys.directory`. Bound here; consumed by the modules that sign or encrypt (audit checkpoint key, idempotency body encryption) |
| `SANGAM_INSTANCE_ID` | → `sangam.rest.node` (idempotency claim owner, relay node). Falls back to `HOSTNAME` |
| `SANGAM_TENANT_DEFAULT` | → `sangam.tenant.default`. The fallback tenant for the **dev token only**. A JWT without a tenant claim is always 401 |
| Graceful shutdown | `server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase=30s`. The Operaton job executor stops on context close, so in-flight jobs drain within the phase timeout |
| Isolation guard | See below (SQL Server RCSI, MySQL READ COMMITTED and utf8mb4) |
| Test DB matrix | Maven profile `sangam-db-it` with failsafe. It is active when `-Dsangam.db=<db>` is given, or with `-Psangam-db-it` plus `SANGAM_IT_DB`. It passes `sangam.db` to the ITs. `-DskipITs` skips them |

## Security modes (`config/SecurityConfig`, `config/OidcConfig`)

1. **OIDC JWT** is used when `spring.security.oauth2.resourceserver.jwt.issuer-uri` or `jwk-set-uri` is set (profile `oidc`).
   - `OidcConfig` builds a lazily resolved Nimbus decoder. It validates the issuer and timestamps, and checks that the **`aud` claim contains `SANGAM_SECURITY_OIDC_AUDIENCE`** (default `sangam-server`; blank disables the check).
   - Claim names are configurable:
     - `SANGAM_SECURITY_OIDC_TENANT_CLAIM` (default `tenant`). A missing tenant gives 401, with no fallback (VI.4 rule 6, K1).
     - `SANGAM_SECURITY_OIDC_ROLES_CLAIM` (default `roles`, then `groups`, then Keycloak `realm_access.roles`).
     - `SANGAM_SECURITY_OIDC_SEATS_CLAIM` (default `seats`). Its list goes into `SangamPrincipal.claims["seats"]`.
   - The principal is `preferred_username`, else `sub`.
2. **Dev static token** (`sangam.security.dev-token.*`, on in `h2` only).
   - The token is compared in constant time and must be at least 16 characters.
   - Startup is **refused** together with the `prod` profile.
   - The tenant comes from config, else `SANGAM_TENANT_DEFAULT`.

Both modes put an `org.sangam.rest.port.SangamPrincipal` on the request as the attribute `SangamPrincipal.ATTRIBUTE`.

**Seat context:** if the token carries a seats list and the header `X-Sangam-Seat` names a seat that is not in it, the request is rejected with 403 problem `SEAT_NOT_HELD`. Whether the seat is held on the action date (effective dating, additional charge) is checked server-side by sangam-security (IX.5, K7). The token's `additional_charge` claim is informational only.

Coarse rules:
- open: `/actuator/health/**`, `/actuator/info`, `/livez`, `/readyz` and `/api/v1/openapi.yaml`;
- `/api/**` needs authentication;
- `/engine-rest/**` needs `sangam-admin` or `sangam-engine-admin` (Operaton's own authorization stays **off**);
- other actuator endpoints need `sangam-operator` or `sangam-admin`;
- everything else is denied.

Errors are RFC 9457 `application/problem+json` (`UNAUTHENTICATED`, `ACTION_NOT_PERMITTED`, `SEAT_NOT_HELD`). The API is stateless (no session, no CSRF). CORS comes from `sangam.security.cors.allowed-origins`.

## Isolation guard (`config/IsolationGuard`)

- Every Hikari pool is forced to `TRANSACTION_READ_COMMITTED` before it starts. MySQL and MariaDB also get `connectionInitSql = SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED`.
- At startup **every `DataSource` bean** is checked over a real connection. Any isolation other than READ COMMITTED fails startup.
- SQL Server requires `sys.databases.is_read_committed_snapshot_on = 1`.
- MySQL requires `@@transaction_isolation = READ-COMMITTED` and `@@character_set_database = utf8mb4*`. A server version other than 8.4.x gives a warning.

## Observability

- The Spring Boot OpenTelemetry starter emits traces and metrics, with `service.name=sangam` and sampling 1.0.
- OTLP export is **off by default** (air-gapped, ARCHITECTURE §1.9). Enable it with `SANGAM_OTLP_ENABLED=true` and `SANGAM_OTLP_TRACES_ENDPOINT`.
- The MDC filter adds `traceId`, `spanId`, `tenant` and `principal` to the JSON logs.
- Actuator exposes `health` (with probes), `info`, `metrics` and `prometheus` on 8081.

**Dashboard metrics** (`metrics/SangamMetrics`, a `MeterBinder`). The snapshot is cached for 15 s; a missing table gives NaN.

| Prometheus name | Micrometer meter | Source |
|---|---|---|
| `sangam_outbox_pending` | `sangam.outbox.pending` | `OUTBOX_DELIVERY` rows in state `PENDING`, `CLAIMED` or `PARKED` (not `DONE`; sangam-core `Relay`) |
| `sangam_outbox_oldest_age_seconds` | `sangam.outbox.oldest.age` (base unit seconds) | `MIN(OUTBOX.created_at)` over undelivered rows |
| `sangam_reconciliation_items{state}` | `sangam.reconciliation.items` | `RECONCILIATION_ITEM` grouped by state (`OPEN`, `RESOLVED`) |
| `sangam_incidents_open` | `sangam.incidents.open` | `RuntimeService.createIncidentQuery().count()` |

Emitted elsewhere:
- `sangam_lease_stale_total` and `sangam_lease_extended_total` come from sangam-rest;
- `sangam_jobs_acquired_total` and `sangam_jobs_failed_total{cause}`: sangam-engine `EngineMetrics.jobsAcquired` / `jobFailed`, bound to Micrometer in `bridge/EngineMicrometerMetrics` (engine note 17).

## Requirements covered (file by file)

| File | Requirements |
|---|---|
| `pom.xml` | ADR-D-16: Operaton 2.2.0 as a dependency, through its starter on Spring Boot 4.1.2. D-7: Liquibase 4.33.0. LIC-4/LIC-5: MariaDB is excluded from the fat jar; no Oracle, Db2 or Connector/J. PropertiesLauncher (ZIP layout). Test DB matrix profile `sangam-db-it`. D-5: REST first |
| `SangamServerApplication.java`, `config/LiquibaseMode.java` | ARCHITECTURE §2 runtime shape. Deploy §10 one-shot schema modes |
| `config/SecurityConfig.java`, `config/OidcConfig.java`, `security/*` | ARCHITECTURE §4 Auth: OIDC JWT with configurable tenant, roles, seats and audience; dev static token. VI.4 rule 6 and K1. IX.5 rule 1 (seat context, `SEAT_NOT_HELD`). R50. §1.4 (`/engine-rest` behind Sangam auth, Operaton authorization off). RFC 9457 |
| `config/IsolationGuard.java` | ARCHITECTURE §1.5 and §1.6 (READ COMMITTED on every pool; SQL Server RCSI). R84 (MySQL READ COMMITTED, utf8mb4, 8.4 LTS) |
| `config/SchemaBootstrap.java` | D-7 (Liquibase from sangam-persistence, modes startup, validate, migrate-only, update-sql and release-locks). A-15 (per-dialect DDL through `core.layer.Schema` in development) |
| `config/ObservabilityConfig.java`, `metrics/SangamMetrics.java`, `application.yaml` | ARCHITECTURE §1.8 and R13 (OTel, Micrometer, JSON logs). VI.2.4 outbox depth. VI.3 rule 4 reconciliation depth. R63 incidents. §1.9 and AC-11 (no outbound calls unless configured) |
| `web/OpenApiController.java` | D-5 (REST/OpenAPI first). AC-11 (API docs offline) |
| `application-*.yaml` | ARCHITECTURE §1.5 (four Tier-1 databases plus H2 for development). R17 / R84. Deploy §10 environment variables and ports |
| `config/ModuleWiring.java` | ARCHITECTURE §3 (the integrator wires the modules in sangam-server) |

## Integration notes

All five modules are wired. The wiring classes live in `config/`:
- `EngineWiring`, `SecurityWiring` and `IntegrationWiring` (W1);
- `PortsWiring` and `PacksWiring` (W2);
- the `ModuleWiring` index.

`SangamRestAutoConfiguration` (sangam-rest) keeps a `@ConditionalOnMissingBean` default for every port. A module bridge in `org.sangam.server.bridge` replaces it. Each module can be switched off with `sangam.modules.<engine|security|integration|ports|packs>.enabled=false`; the server then runs on the REST defaults.

**Engine (single engine, P-1).**
- **One engine.** The Operaton starter still builds the only engine, on the Spring `DataSource` and transaction manager. `bridge/EngineConfigurationCustomizer` runs `SangamEngineConfigurer` on the starter's configuration before the build.
- **Plugin order:** Sangam → security → integration → ports.
- **Raw DataSource.** The engine is given the raw Spring `DataSource`, not `IsolationGuardDataSource`, so that `TxSupport` and the engine share one connection. `IsolationGuard` still enforces READ COMMITTED.
- **Beans:**
  - `sangamEngineAdapter` (an `EngineAdapter`, which switches the worker API to `EngineAdapterWorkAdapter`);
  - `sangamClock`;
  - Micrometer `EngineMetrics`.
- **Schema.** `applyProductSchema=false` (the server runs Liquibase), `applyEnginePatches=true`. The h2 profile uses `MODE=PostgreSQL`.

**Security.**
- **One filter chain.** `SecurityConfig` stays the only filter chain, which keeps the deploy contract (claims, dev token, `SEAT_NOT_HELD`). Security's `ApiTokenFilter` (`sgm_` tokens) and `SecurityIdentityFilter` (`PrincipalResolver`, `TenantContext`, login batching) are added to it. `SangamResourceServerConfiguration` is not used.
- **Bridges:**
  - `PermissionPort`: the group map, or a security grant; replay is re-checked with the VI.4 `READ` object check.
  - `AdminPort`: org units (renames effective-dated via `OrgUnitService.renameUnit` → `SEC_ORG_UNIT_NAME`), seats, postings, additional charge, leave, policies and tenants. Person delegations use the JDBC default.
  - `IdentityAdminPort`: SoD exceptions, users, groups, members and service principals (`ApiTokenService.listTenant`).
  - Every list comes from `SecurityDirectoryService` (paged and tenant-bound in SQL, capped total); the bridges run no SQL of their own.
- **Job identity.** `EngineWiring` installs `TenantContext::runAs` as the engine's job identity binder (VI.4 rule 7).
  - `IdentityExtrasPort` (core): `PrivilegeService` + `ApiTokenService`.
- **Errors.** `SecurityProblemAdvice` maps `SangamSecurityException` to problem+json.
- **Dev bootstrap.** `SecurityBootstrap`, h2 only, creates tenant `dev` and an allow-all policy for `sangam-admin`. It is refused when `sangam.security.production=true`.

**Integration.**
- **Services.** Outbox writer and relay, inbox (engine transaction), ledger, `LedgerExpiryPolicy` (A-10, through the engine settings), lifecycle and erasure, reconciliation, maintenance, connector registry, `IntegrationBridge.install`, connector worker (`sangam.integration.topics`).
- **Start order.** Everything starts in the `IntegrationServices` `SmartLifecycle` (phase `MAX_VALUE-100`), after `EventRegistryWiring.wire(...)` (PortsWiring) has run.
- **History handlers.** `sangam.history.outbox-handler=integration|engine|none` (default `integration`) and `sangam.history.read-model-projection` (default `true`) make integration's `OutboxHistoryEventHandler` the only outbox writer. Ports' `PortsHistoryEventHandler` only projects the read model.
- **Back-pressure.** `IntegrationBackpressureFilter` (filter order 50) returns 503 `BACKPRESSURE` for starts and inbound events.
- **Idempotency.** Integration's `IdempotencyServletFilter` is not registered: sangam-rest's `IdempotencyFilter` owns R41.
- **Packs.** `PacksIntegration.wire(...)` (`sangamPacksIntegration`) installs the `IntegrationConnectorGateway` into `PackRuntime`, with the dispatch publisher, inbox handlers and reconciliation hooks.
  - Site bindings come from `sangam.packs.bindings.<packConnector>.{connector,operation,mapping.*,constants.*,pass-through}`; see `config/PackBindingsProperties`.
  - An unbound pack connector falls back to the packs' simulator only when `sangam.packs.simulator-fallback=true`, which is the default only on h2. The fallback is refused on a production profile; otherwise the call fails with `CONNECTOR_NOT_CONFIGURED`.

**Ports (sangam-flowable-ports).** `sangamPortsPlugin`, `EventDispatcher` (with `UniqueStartPort` → integration `UniqueStartGuard`), `IdmFacade` (with `IdentityExtrasPort`), and `EventRegistryWiring.wire(...)`. REST ports bridged:

| REST port | Implementation |
|---|---|
| `EventPort` | `EventRegistry` + `WebhookInboundEndpoint` (`POST /events/{channel}`) |
| `DynamicPort` | `AdHocSubProcessService` (FC-01), `DynamicInjectionService` (FC-15); no more 501 |
| `CasePort` | default adapter + `CmmnEventListenerService` (FC-55), `CaseLifecycleService` (FC-59) |
| `FormPort` | `SangamFormService` / `FormValidator` (drafts stay on the default) |
| `DocumentPort`, `DocumentExtrasPort` | `ContentService` (`sangam.content.root`), versions, legal hold, `OutputTemplateRenderer` |
| `DeploymentPort` | `AppDeploymentService` (FC-41) for bundles with a manifest or `.event`/`.channel` files |
| `LinkPort` | `InstanceLinkService` |
| `InstanceControlPort` | cross-instance move → `CrossInstanceStateChangeService` (FC-17) |
| `DebugPort` | `DebuggerService` (`sangam.debug.enabled=true`, never on prod) |

Flowable worker compatibility is served at `/api/v1/compat/flowable/external-job-api/...` (`web/FlowableWorkerCompatController`).

**Packs.**
- **Runtime ports.** `PackRuntime` gets the audit chain, the security seat directory, the engine clock, and **`JdbcLedgerStore` / `JdbcBatchLedger`** from sangam-engine. The lock timeout is `sangam.packs.ledger.lock-timeout`, default 5 s.
- **Deployment.** `PackRegistry.discover()` + `PackDeployer`, with start-up deployment for `sangam.packs.tenants` when `sangam.packs.deploy-on-start` is set (default true on h2).
- **Per-tenant provisioning.** `PacksTenantDeployer.ensureDeployed(tenant)` runs automatically after a tenant is created or activated through `/admin/tenants`, and before a `/packages/{id}/transitions` `deploy`. A decorator in `PacksWiring` does this.
- **REST side of the pack contract** (sangam-rest):
  - the task-local variables `actingPerson`, `actingSeat` and `presentedPayloadHash` are set on every completion and return;
  - `initiator`, `initiatorPerson` and `initiatorSeat` are set at start;
  - listener refusals of the form `"<status> <CODE>: ..."` become problem+json (`422 SOD_VIOLATION`, `422 POWER_LIMIT_EXCEEDED`, `409 APPROVAL_INVALIDATED`).

**Compliance (sangam-compliance; DPDP Act 2023 / Rules 2025, CERT-In).** Wired in `config/ComplianceWiring` (switch: `sangam.modules.compliance.enabled`).
- **Facade.** `SangamCompliance` runs on `Transactions.of(engineAdapter)`, or on Spring-joined transactions when sangam-engine is disabled. `ComplianceRuntime.install(...)` is called at start-up.
- **Configuration.** `bridge/ComplianceConfigProvider`:
  - It installs the jurisdiction packs named in `sangam.compliance.jurisdictions` (no default; with none set, a compliance feature refuses with 409 `JURISDICTION_NOT_SET` and only the raw-identifier start guard is skipped; from `META-INF/sangam/jurisdictions.idx`). A pack that is named but not installed fails start-up.
  - A tenant uses `sangam.compliance.tenant-jurisdictions.<tenant>`, else the first configured pack.
  - Every key under `sangam.compliance.*` is a `ComplianceConfig` property; `sangam.compliance.tenant.<tenant>.<key>` overrides it for one tenant.
- **Ports bound:**
  - legal holds → integration `LegalHoldStore` (`IntegrationLegalHoldAdapter`);
  - erasure → integration `ErasureService` (`IntegrationErasureAdapter`);
  - principal messages → `bridge/ComplianceNotifier`: an outbox event to `compliance-notify` in the caller's transaction, delivered by a relay publisher through the DLT SMS (`sangam.compliance.notify.sms-connector`, which needs `mobile` + `templateId` attributes) and e-mail (`notify.email-connector`) connectors, exactly once per event and channel;
  - Data Protection Board and CERT-In → `bridge/ComplianceAuthorityGateway`, through the e-mail connector (or the HTTP connector for an `https://` recipient), one operation per case, kind and recipient;
  - workflow starts → the engine (`bridge/ComplianceWorkflowStarter`);
  - log archive → `bridge/ComplianceLogArchiveStore`, a write-once filesystem store at `sangam.compliance.log-archive.root`, declared country `log-archive.country` (no default).
- **Ports left at the module's "not configured" defaults** (the evidence report lists them as gaps): principal directory, principal authenticator, personal-data port, adult-identity verifier (there is no DigiLocker identity-verification connector; only DigiLocker push exists), guardianship verifier, processor notifier, geo resolver, national-ID vault, security evidence. The SNTP client is the module's built-in one; it is used only when `ntp.enabled=true`.
- **ERASE_DATA.** The `LifecyclePorts.Authorizer` in `IntegrationWiring` lets principal `system:retention` hold `ERASE_DATA`, and nothing more.
- **Schedulers** (`bridge/ComplianceScheduler`, `sangam.compliance.schedule.enabled`). Leader-elected on `INTEGRATION_LEADER` (lease `sangam-compliance-scheduler`), ticking every minute:
  - `retention.runCycle` hourly (batch size `schedule.retention-batch`);
  - `rights.escalateOverdue`, `breaches.overdue` and `incidents.overdue` every 5 minutes;
  - `logs.seal` (last segment to the start of today, UTC) then `purgeExpired`, daily;
  - `ntp.check` hourly;
  - `integrity.verifyTenant` daily.
  The tenants come from the tenant directory (`AdminPort`, as the system principal) plus `sangam.compliance.tenants`.
- **Engine hooks** (`bridge/CompliancePurposePlugin`, ordered after the ports plugin):
  - Built-in process start and end listeners run `nationalIds.requireNoRawIdentifiers` on every start.
  - For a declared purpose (`operaton:property name="sangam:purpose"`, or the start variable `sangamPurpose`), the start also runs `purposeBinding.require` for the principal in `sangam:principalVariable` (default `sangamApplicantId`) plus `retention.track`; the end runs `onPurposeCompleted`.
  - A refusal becomes `"<status> <CODE>: ..."`, which sangam-rest returns as problem+json.
- **Public endpoints** in `SecurityConfig`: `GET /api/v1/compliance/notices/**`, `/privacy-policy` and `/jurisdiction`; `POST /api/v1/compliance/rights-requests` and `/consent-managers/*/artefacts`. **Consent-manager artefacts must be protected by mTLS at the ingress** (DPDP r.4); the application does not authenticate them.

**SCALE cell (sangam-cell README IR-2, IR-3, IR-4, IR-6, IR-7).** `config/CellWiring` is active only when `SANGAM_CELL_ID` (`sangam.cell.id`) is set. Do not put `sangam.cell.id` in YAML even as an empty placeholder: an empty value counts as set.
- **IR-2 registration and heartbeat** (`bridge/CellRegistrationService`, `CellLoadSampler`):
  - Every node registers the cell (idempotent; retried until the control plane answers) and records its presence (REST_DOCUMENT kind `cell:node`, which gives `activeNodes`).
  - Only the leader of `sangam-cell-heartbeat` (integration `LeaderElection`, INTEGRATION_LEADER) runs the `CellRegistrationAgent` with load figures:
    - starts per second, from engine history since the previous sample (there is no `sangam_process_starts` counter);
    - p99 of `http.server.requests` (the percentile is published in `application.yaml`);
    - Hikari usage, from active / max connections;
    - the integration `OutboxCapacityMonitor` fill (DEGRADED at 80 % or more);
    - the tenant count.
  - Registration starts on `ApplicationReadyEvent`. At shutdown the leader sends a final DRAINING heartbeat before the services stop.
  - Settings: `SANGAM_CONTROL_PLANE_URL` / `_TOKEN`, `SANGAM_CELL_REGION`, `_JURISDICTION`, `_RESIDENCY`, `_PUBLIC_URL`, `_ADMIN_URL`, `_HEARTBEAT_INTERVAL` and `_CAPACITY_*`. Without a control-plane URL the cell does not register (logged).
- **IR-3 roll-up and cross-cell publishers:**
  - `rollup:national` (`RollupPublisher`, needs `SANGAM_ROLLUP_INGEST_URL`) and `xcell:*` (`CrossCellPublisher`, needs `SANGAM_GATEWAY_URL`, `SANGAM_SERVICE_TOKEN`) are registered on the `PublisherRegistry`. Each is wrapped by `bridge/CellPoisonPublisher`, so `PoisonEventException` becomes `PermanentPublishException`.
  - With an ingest URL, the outbox writer in `IntegrationWiring` adds `rollup:national` as an **unordered** default destination for `sangam.history.*`, `sangam.xcell.*` and `sangam.rollup.*` events.
  - **Receiving side:** `POST /api/v1/messages` with `X-Sangam-Source-Cell` is taken by `bridge/CellCrossCellInboxFilter`. It requires role `XCELL` and writes the message to the integration inbox (source `xcell:<cell>`, `message_id` = the Idempotency-Key, tenant = `X-Sangam-Tenant`). The answer is 202, or 200 `DUPLICATE`. `bridge/CellCrossCellInboxHandler` then correlates `messageName` + `businessKey` in the inbox's engine transaction; with no waiting instance the message is buffered.
- **IR-4 tenant-admin API** (`web/CellTenantAdminController`, `/api/v1/admin/cell/tenants/{tenant}`, role `CELL_ADMIN`; contract tag `Cell`, command types `cell.tenant.*`):
  - **Routes:** prepare, freeze, drain-status, export, import, checksum, activate, moved, unfreeze, import/drop and purge, plus `GET /api/v1/admin/cell/exports/{exportId}` (the target cell pulls with `X-Sangam-Export-Token`).
  - **State:** the per-tenant state is stored in REST_DOCUMENT (`cell:tenant-state`). `bridge/CellTenantStateFilter` (filter order 40) enforces it:
    - FROZEN → 503 `TENANT_FROZEN` with Retry-After for mutations and inbound events; reads and completion of already-locked work go through;
    - IMPORTING → 503;
    - MOVED / PURGED → 421 `TENANT_MOVED` with `targetCell`.
  - **Rows:** `bridge/CellTenantMover` moves them over JDBC:
    - every table with `tenant_id` / `TENANT_ID_`, plus child tables and the tenant's deployment byte arrays;
    - identity-keyed product rows are re-inserted with new ids;
    - nullable foreign keys are written in a second pass;
    - the export is a gzipped NDJSON file under `SANGAM_CELL_EXPORT_DIR`, with a one-time token that expires (`SANGAM_CELL_EXPORT_TTL`, default 2 h).
  - **Checksum:** an order-independent per-table multiset hash, comparable across vendors.
  - **Rules:** activate checks the audit chain heads, and purge refuses while a LEGAL_HOLD is active.
- **IR-6 read replica** (`config/ReadReplicaWiring`, `bridge/CellReadReplicaRouter`):
  - `SANGAM_DB_REPLICA_URL` (+ user, password, pool size) creates a read-only, READ COMMITTED replica pool. The GET routes `/api/v1/analytics/**`, `/api/v1/audit/**` and `/api/v1/history/**` carry the replica hint for their thread.
  - The router is deliberately not a `DataSource` bean. The engine, Liquibase, `TxSupport` and every REST adapter stay on the primary (P-1), and read paths that write (`AUDIT_VIEWED`) stay correct.
  - Read-only query code adopts `CellReadReplicaRouter.routing()` / `onReplica(...)` explicitly. **Still open:** no adapter uses it yet (see the table below).
- **IR-7 job executor:** `operaton.bpm.job-execution.{core-pool-size,max-pool-size,queue-capacity,max-jobs-per-acquisition,wait-time-in-millis,max-wait,lock-time-in-millis}` come from the same `SANGAM_JOB_*` variables and SCALE 3 defaults (16, 32, 64, 20, 500 ms, 5 s, 5 min) as `SangamEngineSettings`.

**Still open** (requested from the owning workstreams):

| Owner | Item |
|---|---|
| sangam-security | Add resource type `COMPLIANCE` to `ResourceRegistry` (sangam-compliance README §5: READ / MODIFY / ADMIN). It is missing today, so policy-based authorisation of the compliance API is not possible yet |
| sangam-integration / packs | A DigiLocker identity-verification (pull / eKYC) connector for the compliance `AdultIdentityVerifier` (DPDP r.10). Today only DigiLocker push exists |
| sangam-engine | `configure()` sets `processEngineName` "sangam", but the starter's name wins. (Done: job identity binding via `settings.setJobIdentityBinder(TenantContext::runAs)` in `EngineWiring`, note 16; job counters, note 17.) |
| sangam-flowable-ports | Delete or retire on `EventRegistry` (today a delete deploys an inactive version, and deleting an event definition returns 422). A read-model query API for `HistoryPort`, an SLA aggregate for `InsightsPort`, and pause / resume / four-eyes on `ProductBatchService` for `OpsPort`; until then these REST ports stay on their defaults |
| sangam-persistence | Liquibase for the `PortsSchema` tables and `PACK_OPERATION` (dev creates them) |
| sangam-packs | A product-table `BusinessCalendar` (today the packs' `WorkingDayCalendar`) |
| WS-PORTS / WS-REST (IR-6) | Adopt `CellReadReplicaRouter.routing()` for the read-only queries: ports' `HistoryQueryService` read model (built in `PortsWiring`), and the read part of `JdbcAuditAdapter` / `JdbcAuditExportAdapter`, keeping their `AUDIT_VIEWED` / `AUDIT_EXPORTED` writes on the primary. Engine-history reads (Operaton `HistoryService`) need a second, read-only query engine on the replica, which does not exist |
| WS-SECURITY (IR-3, IR-4) | Service principals with roles `XCELL` and `CELL_ADMIN` must carry a tenant claim (`*` or their home tenant), because a token without one is 401 today. The target tenant of a cross-cell message is `X-Sangam-Tenant` |
| WS-INTEGRATION (IR-4) | Kafka/JMS inbound channels do not pass the HTTP freeze filter: a frozen tenant's broker events are still received (they wait in the inbox). Pause the tenant's channel consumers on freeze. Content-store files (`ContentService`, `sangam.content.root`) and the audit export files are not moved with the rows |

## Flowable weakness audit patches (spike/results/FLOWABLE-WEAKNESS-AUDIT.md §4)

| PR | Where | What changed |
|---|---|---|
| PR-1 (FW-07, FW-02) | `web/FlowableWorkerCompatController` | No fallback to the current lease any more. `FlowableWorkerCompat.fencingToken` decides: an echoed `sangamLockVersion` is used, otherwise **409 `FENCING_TOKEN_REQUIRED`**. With `sangam.compat.flowable.allow-unfenced=true` (default false), an unfenced call must echo `sangamAttemptId`; it is compared with the lease's attempt id, the lease is resolved **in the same transaction** as complete/fail/bpmnError/cmmnTerminate, and every such call is audited (`UNFENCED_WORKER_CALL`). Jobs are mapped by the ports' `FlowableWorkerCompat.toFlowableJob`, which converts `Date` values. The private mapper and attempt-id parser are gone. Translation errors become problem+json with the ports' status and code |
| PR-2 | `bridge/PortsErrors` | `PortsProblem` keeps its own status: 413 `PAYLOAD_TOO_LARGE`, 403 `TENANT_MISMATCH` / `NOT_SANDBOX`, 409 `FENCING_TOKEN_REQUIRED`, 422 `PAGE_TOO_DEEP` / `EXPRESSION_NOT_ALLOWED` / `FRAGMENT_INVALID` / `LIMIT_EXCEEDED` / `RESERVED_VARIABLE` |
| PR-3 (FW-02, FW-28, FW-29, FW-47) | `bridge/PortsEventBridge` | A REJECTED event maps by its code: `TENANT_MISMATCH` → 403, `PAYLOAD_TOO_LARGE` → 413, anything else → 422 `EVENT_REJECTED` (with `rejectionCode`). JSON goes through the bounded `Json.MAPPER`. Deploying an inbound WEBHOOK/REST channel without `config.secretRef` is refused with 422 `WEBHOOK_SECRET_REQUIRED`, unless `sangam.ports.events.allow-unsigned-webhooks=true` |
| PR-4 (FW-39, FW-40) | `bridge/PortsDocumentBridge`, `config/PortsWiring` | Downloads use the tenant-checked `ContentService.open(tenant, id, version)` and a read bounded by `sangam.content.max-bytes` (default 256 MiB); above it, 413. Uploads are bounded by the same property in `ContentService` |
| PR-5 (FW-02) | `config/PortsWiring`, `application.yaml` | `sangam.ports.events.max-payload-bytes` (default 1 MiB) goes to `InboundEventProcessor`. `spring.servlet.multipart.max-file-size` / `max-request-size` follow the content limit (256MB / 260MB, spill to disk above 1 MB). JSON bodies are bounded by the event payload limit and by `Json.MAPPER` (16 MiB) |
| PR-6 (FW-16) | `bridge/PortsHistoryBridge` | Probed and capped ports totals are reported as "more than N" (`X-Total-Count-Capped: true`, total = lower bound), and the REST `sangam.rest.count-cap` applies on top |
| PR-7 (FW-28, FW-32) | `sangam-integration` `inbox/InboxService` | An `IllegalArgumentException` (ports' `PortsProblem`, also when wrapped by the engine transaction) from a source handler such as `evt:*` → `EventDispatcher` is permanent. The row is parked after the first failure: INBOX state EXPIRED, claim released, plus an `INTEGRATION_DEAD_LETTER` entry (kind INBOX, reason `PERMANENT <code>: ...`) in one transaction. Transient errors keep the BUFFERED/TTL path. If parking itself fails, the row is buffered |
| PR-8 (FW-28) | `inbox/KafkaInboxConsumer`, `inbox/JmsInboxListener` | Reviewed, not changed. Both commit/ack the broker only after `InboxService.receive` committed. Unmappable messages and foreign-tenant messages are acked (dropped and logged). Back-pressure and other runtime errors (database) are not acked (Kafka seeks back; JMS rolls back). **Gap:** `InboxService.receive` parses the payload (`Json.tree`), so malformed JSON in a mappable message is an `IllegalArgumentException` caught as an infrastructure error, and it is redelivered forever. It should be acked as a poison message (WS-INTEGRATION) |

**Testing phase (scenarios, not run):**
- **Flowable worker compatibility:** a client that does not echo the lock version gets 409. With the opt-in on: a wrong attempt id gets 409 `STALE_LEASE`, and a correct one completes and writes `UNFENCED_WORKER_CALL`.
- **Events:** an event over the payload limit gets 413; an event for another tenant gets 403; an unsigned public webhook channel deploy gets 422.
- **Documents:** a document over `max-bytes` gets 413 on upload and on download; an open with another tenant's id gets 404.
- **Inbox:** an `evt:*` row whose dispatcher throws `PortsProblem` is parked after one attempt (dead letter present, no retries).
- **Kafka/JMS:** a REJECTED or unmappable message is acked, and a database outage is not (it is redelivered after recovery); the malformed-JSON gap above should be tested as well.

**Gaps:**
- Downloads are still buffered (up to `max-bytes`), because the sangam-rest `Download` record carries a `byte[]`. True streaming needs a streaming download type in sangam-rest.
- Uploads through the REST controllers are also buffered (`MultipartFile.getBytes()`).
- PR-7 parks with a dead letter but opens no reconciliation item: core `ReconciliationQueue.Kind` has no kind for a poison inbound event, only UNKNOWN_OUTCOME and LATE_RESULT_AFTER_DELETE.
- With the opt-in, the unfenced lease check reads core `LeaseTable` (`spike_lease`). If the sangam-engine `EngineAdapter` keeps its leases elsewhere (`SANGAM_LEASE`), the opt-in path refuses with `STALE_LEASE` (fail closed).

## Third-party dependencies (new in this module)

| Dependency | Licence |
|---|---|
| Spring Boot 4.1.2 starters (webmvc, actuator, security, security-oauth2-resource-server, jdbc, opentelemetry, validation) | Apache-2.0 |
| Operaton `operaton-bpm-spring-boot-starter-rest` 2.2.0 | Apache-2.0 |
| Micrometer Prometheus registry | Apache-2.0 |
| HikariCP 6.3.0 | Apache-2.0 |
| Liquibase core 4.33.0 | Apache-2.0 (FSL applies to 5.x only, LIC-7) |
| H2 2.3.232 (runtime, development) | MPL-2.0 / EPL-1.0 |
| PostgreSQL JDBC 42.7.12 | BSD-2-Clause |
| Microsoft mssql-jdbc 12.10.2 | MIT |
| MariaDB Connector/J 3.5.11 | LGPL-2.1 (runtime class path only, **not** in the fat jar; LIC-4/LIC-5) |

## Generic modules (final integration wave)

Each module is wired in its own `config/<Module>Wiring`. It can be switched off with `sangam.modules.<name>.enabled=false`, and its REST API is registered by `org.sangam.rest.modules.<pkg>.<Name>RestConfiguration` only when the module is on.

| Module | Wired | Engine plugin (order) | Leader jobs / destinations |
|---|---|---|---|
| sangam-config | JDBC config store, profile resolver, maker-checker admin, seeds; `profiles/profiles.idx` + seeds at start; `config.*` connector bindings | `ConfigCaseBindingPlugin` (last) | — |
| sangam-ledger | `LedgerService` (rates, decisions, accounting, audit); `ledger.*` through `LedgerDefiningConnector`; per-tenant ledgers from profiles | — | `ledger:gl` (when `sangam.ledger.gl-connector` is set) |
| sangam-billing | `BillingService`, tariffs, payee directory over sangam-registry; `billing.*`, `registry.party` | — | — |
| sangam-connectors-health | FHIR / PAS / CDS Hooks / X12 per tenant (`sangam.connectors-health.tenants.*`), simulator fallback | — | — (`POST /api/v1/hooks/fhir/**` public, secret-checked; `/cds-services/**` authenticated) |
| sangam-registry | `RegistryModule` with every port (identity, outbox, visibility, privacy, compliance consent, DMN, documents, signer, vault); identifier-type seeds from `packs/*/registry/*.json` | — | `sangam.registry.events`, `sangam.registry.publish` |
| sangam-resources | `SangamResources` (DMN, approvals, audit, seats/leave, notifications, calendars); `ResourcesProcesses.deploy` at start | — | sweepers (`sangam-resources-sweepers`); `sangam.resources.notification` (unordered), `sangam.resources.display-feed` (ordered) |
| sangam-geo | `GeoService`, WFS/WMS connectors (`sangam.geo.connectors[n]`) | `GeoEnginePlugin` (after case) | — |
| sangam-case | `CaseModule` (outbox, links, content, calendars, escalation seats, history, venue booking via resources, parties via registry) | `CaseFileEnginePlugin` (after ports) | deadline sweep; `case-events`, `case-notify` |
| sangam-programme | `ProgrammeModule` (hierarchy, campaigns, DMN return validation) | — | `programme-events`, `programme-dashboard`, `programme-notify` |
| sangam-field | `FieldModule` (geo, DMN, outbox, evidence store `sangam.field.evidence.root`) | — | run-due / overdue / offer-expiry jobs; `field-events` |

Plugin order (`bridge/EngineConfigurationCustomizer`): Sangam → security → integration → ports → case files → geo → compliance → config binding.

**Data at rest.** `config/SecurityKeysWiring` builds sangam-security's `FieldEncryptor` from `sangam.security.kek.keys.<id>` + `current-id` (base64), or from `sangam.security.kek.pkcs12.*`.
- **Development key:** the ephemeral key is used only on the `h2`/`dev` profiles. Any other profile without a key **fails at start-up**.
- **What is encrypted:** form drafts (`FORM_DRAFT`) and the sensitive `REST_DOCUMENT` kinds: portal drafts and grievances, evidence metadata, identity providers, service principals and notifications.
- **Legacy rows:** rows written in clear before stay readable.

**Module role templates.** New tenants get `security.defaults().install(c, admin, true)` at onboarding. `POST /api/v1/admin/security/module-defaults` (permission `tenant.admin`) runs it on demand.

**Downloads stream.** `Download` carries a body supplier and is copied to the response with `StreamingResponseBody`. Document content from the ports content store is streamed and cut off above `sangam.content.max-bytes`.

## Settings added in the latest rounds

| Setting | Default | Meaning |
|---|---|---|
| `sangam.modules.pipelines.enabled`, `sangam.modules.telemetry.enabled` | true | Module switches |
| `sangam.pipelines.stage-root` | `data/pipeline-stage` | Stage files; must be shared storage for restart on another node |
| `sangam.pipelines.max-concurrent` | 4 | Concurrent runs |
| `sangam.pipelines.schedule.enabled`, `.every` | true, 30s | Leader-elected scheduler |
| `sangam.integration.kafka.{enabled,config.*,channels[n].*}` | off | Channel: `source`, `tenant`, `principal`, `event-type`, `topics`, `group-id`, `concurrency` |
| `sangam.integration.jms.{enabled,channels[n].*}` | off | Channel: as above, with `queue`, or `topic` and `subscription`; needs a `ConnectionFactory` bean |
| `sangam.rest.idempotency-cleanup-minutes` | 60 | Leader-elected cleanup interval |
| `sangam.packs.ledger-post.ledger` | `RECEIVABLES` | Ledger for `ledger.post` |
| `sangam.security.first-admins.tenant`, `.subjects` | unset | Install-time first administrators: two `person:subject:name` entries |

Pipeline definitions are read from `classpath:pipelines/<id>.json`. Pipelines start with no external data sources or HTTP egress.

**Platform access.** `/engine-rest/**`, all methods, requires `sangam-admin` or `sangam-engine-admin`. **First administrator:** set `sangam.security.first-admins.*` at install, with subjects from the identity provider; no API creates a user without authentication. Verified UI plugins are served at `/api/v1/plugins/ui`.

### Settings of the premium wave (ai, channels, modeller, ops, 835)

| Setting | Default | Meaning |
|---|---|---|
| `sangam.ai.llm.base-uri`, `.model`, `.embedding-model`, `.api-key-ref` | unset | LLM endpoint, models and secret ref of the key |
| `sangam.ai.llm.timeout` | 60s | Per-call timeout |
| `sangam.ai.llm.allow-private` | false | Allow a private-network LLM address |
| `sangam.ai.expression.limit` | 2s | Expression-help evaluation limit |
| `sangam.ai.kb.chunk-chars`, `.overlap-chars`, `.max-candidates` | 1200, 150, 2000 | Knowledge-base chunking and retrieval candidates |
| `sangam.ai.agents-json`, `sangam.ai.tools-json`, `sangam.ai.a2a-json` | unset | Agents, connector tools (need sangam-integration) and inbound A2A agents |
| `sangam.ai.mcp.rest-base-url` | `http://127.0.0.1:${server.port}` | Where MCP tools call back into this API |
| `sangam.ai.mcp.write-tenants` | empty | Tenants whose MCP write tools are on |
| `sangam.ai.http-allowed-origins` | empty | Browser Origins allowed on `/api/v1/ai/mcp` and `/api/v1/ai/a2a/**`; a request with an Origin not listed gets 403, a request with no Origin (non-browser) passes |
| `sangam.ai.worker.tenants`, `.batch`, `.every`, `.lease`, `.external-retry` | unset, 20, 5s, 2m, 5m | Agent worker |
| `sangam.channels.documents-dir` | `./data/channels` | Channel attachment storage |
| `sangam.channels.allow-unscanned` | false | Accept attachments with no malware scanner |
| `sangam.channels.poll-seconds`, `sangam.channels.poll.<type>.*` | 60 | Polling channel interval and per-type settings |
| `sangam.channels.bot-process` | unset | Channel type to bot process key |
| `sangam.channels.teams.jwks-uri` | `https://login.botframework.com/v1/.well-known/keys` | Teams (Bot Framework) token keys |
| `sangam.channels.egress.<tenant>` | empty | Allowed outbound hosts per tenant |
| `sangam.modeller.lease-seconds` | 120 | Model edit lease |
| `sangam.modeller.mocks.file` | unset | Extra service-mock rules on top of the stored `MOD_SERVICE_MOCK` rules of the default tenant; ignored on a production profile |
| `sangam.modeller.token-exchange.{token-endpoint,client-id,client-secret-ref,audience,connectors,egress}` | unset | Token exchange for listed connectors |
| `sangam.ops.tenants` | unset | Tenants served by ops jobs |
| `sangam.ops.vars.cache-millis` | 30000 | Scoped variable cache |
| `sangam.ops.retention-json` | unset | Retention key to ISO duration |
| `sangam.ops.retention.auto-execute`, `.batch` | false, 500 | Scheduled purge (plan and log only when false) |
| `sangam.ops.task-listeners-json` | unset | Global task listener rules |
| `sangam.ops.outliers.allowed-variables` | empty | Variables outlier analysis may read |
| `sangam.ops.notification-destinations` | `notify*,email*,mail*,sms*,dlt*` | Connector ids treated as notifications |
| `sangam.modules.connectors-health.enabled` | true | Enables `POST /api/v1/connectors-health/x12/835` (`application/edi-x12` or `text/plain`, needs `ops.write`; one inbox message per claim, deduplicated on trace and patient control number) |
**Concurrent builds.** Each concurrent build of one checkout needs its own `-Dsangam.target=<folder>`.
