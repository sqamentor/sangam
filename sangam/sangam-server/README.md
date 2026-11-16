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
java -Duser.timezone=UTC -Dloader.path=/opt/sangam/lib-ext -jar sangam-server-0.1.0-SNAPSHOT.jar --spring.profiles.active=postgres,oidc
```

The JVM must run in UTC (SL-53): the server refuses to start with any other default time zone, before any bean.
`spring-boot:run` forks the JVM with `-Duser.timezone=UTC` (pom), the image sets it in `JAVA_TOOL_OPTIONS`, and the
build passes it to every test JVM. The business zone of timers is `sangam.engine.timer-zone` (default `UTC`); see
deploy/README.md section 1.3.

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

All database profiles read `SANGAM_DB_URL`, `SANGAM_DB_USER` and `SANGAM_DB_PASSWORD`. Every profile, h2 included, writes JSON (ECS) console logs, one object per line (`logging.structured.format.console: ecs` in `application.yaml`): a value from a request that reaches a log message is escaped inside the JSON string and cannot forge a log line.

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
| `SANGAM_COMPLIANCE_DENIAL_AUDIT_POOL_SIZE` | → `sangam.compliance.denial-audit.pool.maximum-size` (default 2). Connections of the compliance denial-audit leaf pool `sangam-compliance-audit` per node (SL-137, `config/ComplianceWiring`); count them in the database's connection budget (deploy README §1.6) |
| `SANGAM_CDS_BASE_URL` | → `sangam.connectors-health.cds-service.base-url` (default empty). The public URL CDS Hooks clients put in `aud` (SL-138, M-006). Without registered clients every `/cds-services` call is refused (401 `CDS_CLIENT_NOT_CONFIGURED`); with clients it is required at start-up |
| `SANGAM_JOB_VIRTUAL_THREADS` | → `SangamEngineSettings.jobVirtualThreads` (read by `applyEnvironment`, default false). M-054: the job executor's pool threads are virtual threads (sangam-engine README) |
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
- `/engine-rest/**` needs `sangam-admin` (platform) or `sangam-engine-admin` (Operaton's own authorization stays **off**);
- other actuator endpoints need a platform identity with `sangam-operator` or `sangam-admin` (SL-70);
- everything else is denied.

**Platform issuer (SL-70).** Platform and tenant identities are told apart by the token's verified issuer, never by a
group alone:
- `sangam.security.platform-issuer` (env `SANGAM_SECURITY_PLATFORM_ISSUER`, comma-separated) lists the issuers of the
  separate platform realm. Only their tokens are platform identities: they keep their groups and get the marker group
  `sangam-platform`; with `sangam-admin` they are the platform administrator (also the sangam-security role
  `platform-admin`). `sangam.security.platform-jwk-set-uri` replaces OIDC discovery for a single platform issuer.
- In every other token (tenant realm, dev token whose `sangam.security.dev-token.issuer` is not listed, API tokens
  `sgm_...`) `sangam-admin` becomes `sangam-tenant-admin`, the administrator of the token's own tenant, and the
  platform-only groups and roles (`sangam-admin`, `platform-admin`, `sangam-platform`, `sangam-system`) are removed,
  also when they come from the organisation model.
- Nothing configured: nobody is a platform administrator. A platform issuer equal to the tenant issuer is refused at
  startup. A token claiming a platform issuer is verified only against that issuer's keys.
- Platform-only operations: onboarding (`/api/v1/onboarding/**`, including `packs/resolve` and bundles of any tenant),
  tenant creation, the engine-wide history cleanup (`POST /api/v1/ops/cleanup`, any platform identity with
  `ops.write`), writes to other tenants' configuration profiles, untenanted engine objects, the actuator.
- The `h2` (development) profile lists the dev token's issuer `urn:sangam:dev-token`, so the local dev token is the
  platform identity there.

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
- **Dev bootstrap.** `SecurityBootstrap`, h2 only, creates tenant `dev` and an allow-all policy for `sangam-admin` and `sangam-tenant-admin`. It is refused when `sangam.security.production=true`.

**Integration.**
- **Services.** Outbox writer and relay, inbox (engine transaction), ledger, `LedgerExpiryPolicy` (A-10, through the engine settings), lifecycle and erasure, reconciliation, maintenance, connector registry, `IntegrationBridge.install`, connector worker (`sangam.integration.topics`).
- **Start order.** Everything starts in the `IntegrationServices` `SmartLifecycle` (phase `MAX_VALUE-100`), after `EventRegistryWiring.wire(...)` (PortsWiring) has run.
- **History handlers.** `sangam.history.outbox-handler=integration|engine|none` (default `integration`) and `sangam.history.read-model-projection` (default `true`) make integration's `OutboxHistoryEventHandler` the only outbox writer. Ports' `PortsHistoryEventHandler` only projects the read model.
- **Back-pressure.** `IntegrationBackpressureFilter` (filter order 50) returns 503 `BACKPRESSURE` for starts and inbound events.
- **Per-tenant quotas (SL-43, `QuotaWiring`).** `sangam.quotas.defaults.*` plus per-tenant overrides in the tenant's `quotas` (onboarding `tenant.quotas`): `startsPerSecond`, `apiCallsPerMinute`, `maxBatchSize`, `maxVariableBytes`, `maxDocumentBytes`, `maxStorageBytes` (negative = unlimited). Counted in fixed windows in `SEC_TENANT_QUOTA_WINDOW` (one conditional UPDATE per counted call, so all nodes share one count). `TenantQuotaFilter` (order 45) counts API calls and refuses oversized bodies; `TenantQuotaEnginePlugin` counts process/case starts and checks written variable sizes before the command flushes; `ProductBatchService` and `ContentService` check batch size, document size and stored bytes. Rate limits answer 429 `TENANT_QUOTA_EXCEEDED` with `Retry-After`, size limits 413 `TENANT_QUOTA_EXCEEDED`; the problem names `limit`, `allowed`, `requested`; the first refusal per window is audited (`TENANT_QUOTA_EXCEEDED`, chain `quota:<tenant>`).
- **Outbound webhooks (SL-39, `WebhooksWiring`).** `sangam.integration.webhooks.subscriptions.<name>.url/secret-ref` publish outbox destination `webhook:<name>` (signed, idempotency key = event id, egress allow-list per URL, no redirects, one attempt per relay try). `disable-after` consecutive failures disable the destination (audited); deliveries are held for `hold-while-disabled` and then dead-lettered; `WebhookHealth.enable` re-enables.
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
- **One operation per pack and tenant (SL-116).** `PacksWiring` gives the one `PackDeployer` a `bridge/PackOperationTxLock`, so deploy, rollback and uninstall of a pack run inside a Spring transaction (joined when the caller has one) that first row-locks the pack's mark in `EVT_DEFINITION_SEQ` (kind `pack`, key = tenant scope and pack id). The engine joins that transaction on the same connection, so the lock and the deployment commit together. The wait is bounded by `sangam.packs.operation.lock-timeout` (default 10 s): PostgreSQL `SET LOCAL lock_timeout`; SQL Server, MySQL and H2 a session lock wait that is set back right after the lock statement; Oracle `FOR UPDATE WAIT 2`. A lock-wait timeout is reported as `PACK_OPERATION_IN_PROGRESS`, which is 409 on the pack lifecycle API and in tenant provisioning (`PacksTenantDeployer`); a rejected pack stays 422 `PACK_REJECTED`. Another pack or another tenant is not blocked. Deploy several packs in one caller transaction only when no concurrent operation on them is possible (sangam-packs README §2). Coded, tests written (`PackOperationLockTest`, `PackOperationTxLockTest`, `PacksTenantDeployerTest`, `PackOperationLockH2Test`, `DefinitionVersionsPackKindH2Test`), not yet run.
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
  The tenants come from the tenant directory: `config/ScheduleTenants.resolve` reads every page of it as the system principal (`system:compliance`, home tenant from `AttributionTenant`: `sangam.tenant.default`, else `default`) and skips `SUSPENDED` tenants (SL-117). `sangam.compliance.tenants` (`SANGAM_COMPLIANCE_TENANTS`) is only an optional list of extra tenants on top of it; there is no yaml fallback. The compliance schedule then keeps only the tenants with an effective jurisdiction (`sangam.compliance.jurisdictions`, `sangam.compliance.tenant-jurisdictions.<tenant>` or onboarding). The directory read goes through `TenantAdminService.listForSystem` (sangam-security), a read-only listing of tenant id and status that only the system principal may call; the platform-admin `list` refused the system principal with 403, which was the root cause of the empty schedule. The same reader serves the compliance, channels, case, AI, field and ops schedules. `config/ScheduleTenantsMonitor` logs a WARN naming the scheduler when a schedule turns empty, then at most once every 5 minutes while it stays empty (so the 5-minute compliance cycle warns every cycle). A failed directory read is logged at WARN at once, then at most every 5 minutes per scheduler while it persists, with one INFO line on recovery; the next cycle retries. Metrics, tagged `scheduler`: `sangam.schedule.tenants`, `sangam.schedule.empty` and `sangam.schedule.directory_failures` (every failure counted). Coded, tests written (`ScheduleTenantsTest`, `ScheduleTenantsMonitorTest`, `ComplianceSchedulerEmptyTest`, and in sangam-security `TenantAdminServiceSystemListingH2Test`), not yet run.
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
| sangam-packs | Done (SL-34, M-011): `PacksWiring.packCalendar` installs `org.sangam.packs.calendar.WorkingDayCalendar` over the tenant's profile calendar (`bridge/ProfileWorkingDays`; calendar name `sangam.packs.calendar`, default `government`), so pack SLAs count the tenant's working days and holidays. Without a `ProfileResolver` bean the packs keep the default working days (logged at WARN) |
| WS-PORTS / WS-REST (IR-6) | Adopt `CellReadReplicaRouter.routing()` for the read-only queries: ports' `HistoryQueryService` read model (built in `PortsWiring`), and the read part of `JdbcAuditAdapter` / `JdbcAuditExportAdapter`, keeping their `AUDIT_VIEWED` / `AUDIT_EXPORTED` writes on the primary. Engine-history reads (Operaton `HistoryService`) need a second, read-only query engine on the replica, which does not exist |
| WS-SECURITY (IR-3, IR-4) | Service principals with roles `XCELL` and `CELL_ADMIN` must carry a tenant claim (`*` or their home tenant), because a token without one is 401 today. The target tenant of a cross-cell message is `X-Sangam-Tenant` |
| WS-INTEGRATION (IR-4) | Broker side of the freeze: addressed by M-008 / SL-142 (see PR-8 below): `BrokerInboxWiring` passes the cell's `TenantAdmission` to every Kafka and JMS consumer, and a non-ACTIVE tenant's message is not stored. Content-store files (`ContentService`, `sangam.content.root`) and the audit export files are not moved with the rows |

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
| PR-8 (FW-28; M-008, SL-142) | `inbox/KafkaInboxConsumer`, `inbox/JmsInboxListener`, `config/BrokerInboxWiring`, `config/CellWiring` | **Tenant admission (M-008, SL-142).** `BrokerInboxWiring` gives every consumer the cell's `TenantAdmission` (`CellWiring.sangamInboxTenantAdmission`; without cells there is no bean and every tenant is admitted). Before storing a message a consumer asks for a decision: ADMIT stores and acks; REDELIVER does not ack (Kafka pauses and seeks back, JMS rolls the session back), so the broker redelivers it; PARK (a tenant that left the cell for good: MOVED or PURGED) writes the message to the dead-letter store with a reconciliation item through `PoisonInbound.park`, for an operator replay to the tenant's new cell, and only then acks. If the park write fails, or no dead-letter store is configured, the message is not acked. A failing admission check counts as REDELIVER. `CellWiring.sangamInboxTenantAdmission` answers ADMIT for ACTIVE, REDELIVER for FROZEN and IMPORTING, and PARK (with the target cell and move id) for MOVED and PURGED. Tests `KafkaInboxConsumerTenantStateTest` and `JmsInboxListenerTenantStateTest` are written and not run. **Before M-008 (reviewed, not changed):** both commit/ack the broker only after `InboxService.receive` committed. Unmappable messages and foreign-tenant messages are acked (dropped and logged). Back-pressure and other runtime errors (database) are not acked (Kafka seeks back; JMS rolls back). The former gap (malformed JSON in a mappable message was caught as an infrastructure error and redelivered forever) is fixed: `InboxService.receive` checks the payload first and throws `PoisonInbound.PoisonMessageException`, and both consumers catch it, write the dead letter, reconciliation item and audit entry through `PoisonInbound`, then ack (if that write fails, the message is not acked and comes back; with no dead-letter store configured it is logged and dropped) |

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
| sangam-registry | `RegistryModule` with every port (identity, outbox, visibility, privacy, compliance consent, DMN, documents, signer, vault); identifier-type seeds from `packs/*/registry/*.json` | — | registry events go to the destinations subscribed to their type (`OutboxWriter`; nothing is written without integration or without a subscriber, SL-146); `sangam.registry.publish` |
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
| `sangam.work.long-poll.enabled` (env `SANGAM_WORK_LONG_POLL_ENABLED`) | true | M-053 / SL-141: `/api/v1/work/fetch-and-lock` waits as an async servlet response (`WorkLongPoll`), so no request thread is held during the wait. `false` keeps the blocking wait in the request thread |
| `sangam.work.long-poll.max-waiters` (env `SANGAM_WORK_LONG_POLL_MAX_WAITERS`) | 1000 | Waiting requests per node; above it a request waits in its own thread as before |
| `sangam.work.long-poll.poll-threads` (env `SANGAM_WORK_LONG_POLL_THREADS`) | 4 | Threads that run the polls; each holds a pool connection while it polls. Polls follow the old schedule (100 ms doubling to 1 s) and run at once on a relay wake-up (`WakeupListener`). The wait is capped by `sangam.rest.max-async-response-timeout` (30 s) |
| `sangam.security.denial-audit.pool.maximum-size` (env `SANGAM_SECURITY_DENIAL_AUDIT_POOL_SIZE`) | 2 | SL-132 / SL-161: connections of the leaf pool `sangam-security-audit` (`config/SecurityAuditPoolWiring`) for security denial audits and the tenant data-key store; on top of `SANGAM_DB_POOL_SIZE` on every node (deploy README §1.6) |
| `sangam.integration.dlt.delivery-reports.secrets.<name>.connector`, `.tenant`, `.secret-ref`; `.delivery-reports.tolerance` (env `SANGAM_DLT_DELIVERY_REPORT_TOLERANCE`) | none; 5m | Decision 304 (`config/DltWiring`, `DltProperties`): the signing secret (a `secret://` reference, a literal is refused at start) of the delivery-report webhook `POST /api/v1/hooks/sms-dlt/{tenant}/{connectorId}/delivery-reports` per DLT SMS connector, for one tenant or for every tenant. The route is public at the filter chain; the `X-Sangam-Signature` (`t=`, `v1=` HMAC-SHA256 over `t.body`) is checked first and a connector without a configured secret refuses every report (401 `WEBHOOK_SIGNATURE_INVALID`). `NOTIFICATION_DELIVERED` is written once per delivered message |
| `sangam.integration.dlt.delivery-status.enabled`, `.interval` (env `SANGAM_DLT_DELIVERY_STATUS_ENABLED`, `SANGAM_DLT_DELIVERY_STATUS_INTERVAL`), `.first-lookup-after`, `.lookup-every`, `.window`, `.max-lookups`, `.batch-size` | true, 5m, 15m, 30m, 3d, 12, 100 | Decision 304: the status-lookup fallback (`DltDeliveryStatusPoller.runOnce`) for messages whose delivery report did not arrive, scheduled on every node (skip-locked claim) on the thread `sangam-dlt`; only with `sangam.integration.background.enabled`. A non-positive duration refuses the start |
| `sangam.integration.dlt.registration.refresh-interval` (env `SANGAM_DLT_REGISTRATION_REFRESH_INTERVAL`), `.registration.seed.<tenant>.entity-id`, `.headers[]`, `.templates[].template-id` / `.header` / `.body`, `.urls[]` | 1m; none | Decision 305: the per-tenant DLT registration is stored (`DLT_REGISTRATION`, `DLT_REGISTRATION_ITEM`) and changed through `/api/v1/admin/dlt-registration` (`dlt.registration.read` / `dlt.registration.write`, audit `DLT_TEMPLATE_REGISTERED`); the DLT SMS connector validates against it. The seed only adds what is absent at start (an invalid entry stops the start); `refresh-interval` is how often a node loads the registrations other nodes changed |
| `sangam.config.store.pool.maximum-size` (env `SANGAM_CONFIG_STORE_POOL_SIZE`) | 4 | Connections of the leaf pool `sangam-config` (`config/ConfigWiring.ConfigStorePool`) for the config store's own short transactions (profile change marker, profile and master-code reads, number series, change register). A process start reads the change marker inside its engine command, which already holds a main-pool connection; on the main pool that second connection is the SL-43 pool deadlock. On top of `SANGAM_DB_POOL_SIZE` on every node (deploy README §1.6). A main pool that is not HikariCP gets no leaf pool (warning at start) |
| `sangam.audit.pending.workers` (env `SANGAM_AUDIT_PENDING_WORKERS`), `.sweep-interval`, `.sweep-min-age`, `.sweep-batch` | 2, 30s, 10s, 200 | SL-133 / M-003: the drainer that appends `AUDIT_PENDING` rows of the JavaDelegate connector path to their root chains after the job transaction ends. `workers` drain in parallel; the sweep picks up rows whose drain signal was lost (`sweep-interval` 0 = no sweep) |
| `sangam.audit.checkpoint.private-key-ref`, `.public-key-ref`, `.interval`, `.max-entries`, `.overlap`, `.export-dir` | unset, unset, 1h, 10000, 1h, unset | M-073 (owner Q-04): signed Merkle checkpoints per tenant every `interval` or every `max-entries` events (`config/AuditCheckpointWiring`, on only when `private-key-ref` is set). Keys are `secret://` references only, one Ed25519 key per purpose and environment (base64 PKCS#8 private, base64 X.509 public); `overlap` must exceed the longest transaction |
| (every key setting) | — | Owner decision 37 (Q-04), one key per purpose: the start is refused with `KEY_SHARED_ACROSS_PURPOSES`, naming both settings, when two key settings of different purposes name the same `secret://` reference or hold the same key material (literal, behind a reference, in the KEK key store or in the plugin trusted-keys folder; Ed25519 keys also by their raw bytes). The purposes and their settings are the table in `config/KeyPurposeSeparation` (KEK, registry signer, registry and national-id vault peppers, compliance principal key, audit checkpoint, package signing, onboarding bundle signing, extension catalogue trust, plugin signing trust); values are compared by digest and never logged. Owner decision 385 (3), one key per environment: `sangam.keys.environment` names the environment, `sangam.keys.foreign-fingerprints` is the list of the other environments' key fingerprints (SHA-256 of a public key, HMAC-SHA256 under a fixed label of a secret), `sangam.keys.fingerprint-export` is where the start writes this environment's own list; a key on the list is refused with `KEY_SHARED_ACROSS_ENVIRONMENTS`, and production refuses to start without a name and a list (`KEY_ENVIRONMENT_LIST_REQUIRED`); see deploy/README.md section 1 |
| `sangam.audit.checkpoint.trusted-public-key-refs` | unset | M-073 verification on every node (`config/AuditVerifyWiring`): a comma list of `secret://` references to base64 X.509 Ed25519 public keys (the current key and retired keys still trusted); when set, audit verify also checks the signed checkpoints |
| `sangam.tracing.engine.enabled` (env `SANGAM_TRACING_ENGINE_ENABLED`) | false | M-062: engine spans (one per command, jobs as root spans) over the server's OpenTelemetry (`EngineWiring`). Not under `sangam.engine.*`, because that prefix binds onto `SangamEngineSettings`. Spans leave the server only where the OTLP export is configured |
| `sangam.engine.job-virtual-threads`, `sangam.engine.job-bulkheads.<type>`, `sangam.engine.job-bulkhead-wait` | false, none, 10s | M-054 (sangam-engine README note 25); either option switches to `SangamJobExecutor` (`EngineWiring.sangamJobExecutor`). `SANGAM_JOB_VIRTUAL_THREADS` wins over the key |
| `sangam.engine.executor-partitioning`, `sangam.engine.executor-slots`, `sangam.engine.trace-links` | false, 4096, false | M-056 / M-062 engine options, all off by default (sangam-engine README note 28) |
| `sangam.engine.deploy-allowed-classes.<tenant or *>`, `sangam.engine.deploy-allowed-beans.<tenant or *>` | empty | SL-160 / TM-10: tenant-approved delegate classes and beans a model may name at deploy, beyond Sangam's own (sangam-engine README note 29) |
| `sangam.packages.signing.private-key-ref`, `.public-key-ref` | unset | M-074 (Q-04, decision 84): `secret://` references to this environment's own Ed25519 package signing key (base64 PKCS#8 private, base64 X.509 public), not shared with the audit checkpoint or onboarding keys (`config/PackageSigningWiring`). Unset: nothing is signed here. A literal key, or a reference that does not resolve, fails the start-up |
| `sangam.packages.signing.trusted-public-key-refs` | empty | M-074: comma list of `secret://` references to the public keys of the environments this one imports from (and retired keys still trusted); the own public key is always trusted. Outside the `h2`/`dev` profiles, or in production, an unsigned package is refused at deploy and import (`PACKAGE_UNSIGNED`), so every non-dev environment, E2E included, needs a package key |
| `sangam.packages.git.enabled` | `false` | M-100 (R69): binds package Git pull and push to sangam-integration's `PackageGitSync` (`config/PackageGitWiring`), only when JGit is on the class path. Off, or without JGit: `POST /packages/{id}/git/pull\|push` answer `501 CAPABILITY_NOT_INSTALLED` |
| `sangam.packages.git.egress` | empty | M-100: list of egress rule specs (`https://host[:port][/path]`) a package remote must match; https only, no credentials in the URL, private addresses refused. Empty: every remote is refused with `422 GIT_INVALID` before any connection |
| `sangam.packages.git.timeout`, `.max-files`, `.max-bytes`, `.work-dir` | `60s`, sync defaults, system temp | M-100: limits and working directory of one pull or push |
| `sangam.security.reencryption.enabled` | false | M-085: the field re-encryption job that rewrites encrypted columns to the current key (`config/FieldReencryptionWiring`, `RestStoreReencryptionSources`); off unless `true` |
| `sangam.security.reencryption.period`, `.batch-size`, `.max-batches`, `.dry-run`, `.tenants` | 15m, job default, 50, false, empty | M-085: run interval (positive), rows per batch (1..10000), batches per run (at least 1), count only without rewriting, and extra tenants on top of every non-suspended tenant of the directory (`ScheduleTenants`). Metric `sangam.field.reencrypt.rows` with tags `source` and `outcome` |
| `sangam.test-mode.enabled` | false | M-021: the test-mode clock (`/api/v1/test-mode/clock`, platform administrator only, every change audited `TEST_CLOCK_CHANGED` and undone if the audit write fails). Refused at start-up on a production profile or with the Helm production flag; the route answers 404 while off |
| `sangam.work.announce.enabled`, `.coalesce` | true, 20ms | M-066 (decision 101): after each committed command that created external tasks, `ExternalWorkAnnouncer` sends one payload-free wake-up hint on the M-005 path (pg_notify, in-JVM, or the M-058 NATS/Redis bus), coalesced over `coalesce` (zero or positive). Workers still take work only through the REST lease; `false` leaves the waiters' own poll (`config/WorkAnnounceWiring`) |
| `sangam.history.projection.search.{url,index,username,password-ref,api-key-ref,allow-private}` | unset, `sangam-history`, unset, unset, unset, false | M-057 (decision 88): OpenSearch/Elasticsearch sink of every `sangam.history.*` outbox event, `PUT {url}/{index}/_doc/{event_id}` (idempotent per event id); `{tenant}` in `index` gives one index per tenant; credentials are secret references; production profiles require https (`config/HistoryProjectionWiring`). Nothing is configured by default |
| `sangam.history.projection.kafka.{topic,bootstrap-servers,properties.*}` | unset | M-057: Kafka sink of the history events, record key = event id, idempotent producer; a property value starting with `secret://` is resolved through the `SecretResolver`. The data-lake sink is Wave 4 |
| JVM property `sangam.pdf.fonts` or env `SANGAM_PDF_FONTS` | unset | M-081 (decisions 94, 95): comma- or path-separator-separated list of `.ttf` files for the PDF renderer; the server image ships Noto Sans and Noto Sans Devanagari under `/opt/sangam/fonts` (`deploy/fonts`). Read as a JVM system property or environment variable, not a Spring property. Unset or unreadable fonts, or no veraPDF validator: PDF output answers 422 `FORMAT_NOT_AVAILABLE`, never an unvalidated PDF. Owner decision 114: veraPDF (it needs Rhino, LIC-10) runs as a separate validator container, so the server answers 422 for PDF until the Wave 4 validator client lands |

**Worker topic binding (SL-158, enforced in 0.1.0; R26).** A worker fetches, completes and fails work only on the
topics it is bound to (WORK on `WORKER_TOPIC/<topic>`). Any other call is **403 `WORKER_TOPIC_NOT_BOUND`**, naming the
worker and the topic, and the refusal is recorded as `AUTH_DENIED` on the denial chain (`config/SecurityWiring`
`sangamWorkerTopicGuard`). **Migration step before upgrading from an earlier build:** bind every worker principal to
its topics, either by creating the worker as a service principal with its topics (`POST
/api/v1/admin/service-principals` with `topics: [...]`) or by binding an existing worker. A role that grants WORK on
`WORKER_TOPIC/*` (an administrator) needs no binding.

**Embedding Sangam in your own application (M-014).** Use `sangam-spring-boot-starter`; its README is the host
guide. In short: it imports this module's own wiring from the `plain` library jar of `sangam-server` (classifier
`plain`, without this module's `application*.yaml`), while the runnable fat jar keeps its name
(`sangam-server-<v>.jar`). In a host the core modules (engine, integration, security, ports, REST) are on and the
packs, domain modules and compliance are off until enabled (`sangam.modules.<m>.enabled`). Sangam's defaults ship as
the lowest-precedence property source (`META-INF/sangam/sangam-defaults.yaml`). The starter brings no JDBC driver; the
host adds its own. Sangam's `SecurityFilterChain` ends with `denyAll()`, so a host serves its own routes through its
own chain with a request matcher and a higher precedence.
| `sangam.packs.ledger-post.ledger` | `RECEIVABLES` | Ledger for `ledger.post` |
| `sangam.security.first-admins.tenant`, `.subjects` | unset | Install-time first administrators: two `person:subject:name` entries |

Pipeline definitions are read from `classpath:pipelines/<id>.json`. Pipelines start with no external data sources or HTTP egress.

**Platform access.** `/engine-rest/**`, all methods, requires `sangam-admin` (only from a platform issuer, SL-70) or `sangam-engine-admin`. **First administrator:** set `sangam.security.first-admins.*` at install, with subjects from the identity provider; no API creates a user without authentication. Verified UI plugins are served at `/api/v1/plugins/ui`.

### Settings of the premium wave (ai, channels, modeller, ops, 835)

| Setting | Default | Meaning |
|---|---|---|
| `sangam.ai.llm.base-uri`, `.model`, `.embedding-model`, `.api-key-ref` | unset | LLM endpoint, models and secret ref of the key |
| `sangam.ai.llm.timeout` | 60s | Per-call timeout |
| `sangam.ai.llm.allow-private` | false | Allow a private-network LLM address |
| `sangam.ai.expression.limit` | 2s | Expression-help evaluation limit |
| `sangam.ai.kb.chunk-chars`, `.overlap-chars` | 1200, 150 | Knowledge-base chunking (ranking has no candidate cap since M-129: `sangam.ai.kb.max-candidates` is no longer read) |
| `sangam.ai.agents-json`, `sangam.ai.tools-json`, `sangam.ai.a2a-json` | unset | Agents, connector tools (need sangam-integration) and inbound A2A agents |
| `sangam.ai.mcp.rest-base-url` | `http://127.0.0.1:${server.port}` | Where MCP tools call back into this API |
| `sangam.ai.mcp.write-tenants` | empty | Tenants whose MCP write tools are on |
| `sangam.ai.http-allowed-origins` | empty | Browser Origins allowed on `/api/v1/ai/mcp` and `/api/v1/ai/a2a/**`; a request with an Origin not listed gets 403, a request with no Origin (non-browser) passes |
| `sangam.ai.worker.tenants`, `.batch`, `.every`, `.lease`, `.external-retry` | unset, 20, 5s, 2m, 5m | Agent worker |
| `sangam.channels.documents-dir` | `./data/channels` | Channel attachment storage |
| `sangam.channels.allow-unscanned` | false | Accept attachments with no malware scanner |
| `sangam.malware.production-waiver` | empty | Production refuses to start without a malware scanner (owner decision 125) unless this names the reason for running without one; the waiver is logged at every start |
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
| `sangam.modules.connectors-health.enabled` (env `SANGAM_MODULE_CONNECTORS_HEALTH`) | false (decision 187: optional module, off by default) | Switches the health connectors module on (FHIR subscription hooks, CDS Hooks, `POST /api/v1/connectors-health/x12/835`). Off, those routes answer 501 `CAPABILITY_NOT_INSTALLED` and nothing else changes. On, the 835 route takes `application/edi-x12` or `text/plain`, and needs `ops.write`: one inbox message per claim, deduplicated on trace and patient control number |
**Concurrent builds.** Each concurrent build of one checkout needs its own `-Dsangam.target=<folder>`.
