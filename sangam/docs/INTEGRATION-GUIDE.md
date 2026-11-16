<!-- SPDX-License-Identifier: Apache-2.0 -->
# Integrate Sangam into your application

This guide is backlog item M-018. Its location is the one the backlog names; SANGAM.md does not name one. It covers:
- the two ways to run Sangam (embedded through the Spring Boot starter, or as the standalone server);
- the REST contract, idempotency and replay;
- tenants, tokens and scopes;
- SDK workers and the worker lifecycle;
- connectors, simulators and events;
- packs;
- the compliance principal key, and the secrets an installation needs.

Each statement was checked against the code on 2026-11-15 (base e19ea58f). Paths are relative to `sangam/`. Where code
and the decisions in `docs/records/OWNER-DECISIONS-2026-11-12.md` meet, the decision number is given. Status: 0.1.0 is
unreleased. Tests named here were written and most ran in the local H2 unit run; database ITs on Oracle, SQL Server and
MySQL, and the live integrations, belong to the test phase (`TESTING-GAP-REGISTER.md`).

## 1. Choose a mode

| | Embedded (starter) | Standalone server |
|---|---|---|
| Artifact | `io.github.sqamentor:sangam-spring-boot-starter` (`sangam-spring-boot-starter/pom.xml:12-17`) | `sangam-server` fat jar, image `deploy/docker/server.Dockerfile`, compose, Helm chart `deploy/helm/sangam` |
| Who owns the process | your Spring Boot 4.1 application | Sangam |
| Modules on by default | engine, security, integration, ports and the REST API; everything else is off (`sangam-spring-boot-starter/src/main/resources/META-INF/sangam/sangam-defaults.yaml:93-139`) | all modules whose wiring has `matchIfMissing = true`, compliance included (`sangam-server/.../config/ComplianceWiring.java:79`) |
| JDBC driver | yours: the starter excludes h2, postgresql, mssql-jdbc and the LGPL MariaDB driver (`sangam-spring-boot-starter/pom.xml:28-45`) | shipped in the image; for MySQL the image uses MariaDB Connector/J, which is staged into lib-ext (`deploy/README.md:129`) |

Both modes run the same product wiring. The starter depends on `sangam-server` with classifier `plain` and reuses its
configuration (`sangam-spring-boot-starter/pom.xml:23-27`). `ServerBeanSetEqualityTest` checks that the embedded bean
set equals the server's (`sangam-spring-boot-starter/README.md:87-92`).

## 2. Embedding through the Spring Boot starter

**Requirements** (`sangam-spring-boot-starter/README.md:8-15`)
- Spring Boot 4.1.x, checked at start. Any other line fails with an IllegalStateException (`SangamDefaultsEnvironmentPostProcessor.java:60,130-136`).
- JVM default time zone UTC (`-Duser.timezone=UTC`, ADR-D-27).
- Jackson databind 2.22.x.
- Liquibase 4.x.
- **BOM order:** import `io.github.sqamentor:sangam-bom` *before* the Spring Boot BOM.

**How it attaches**
- `org.sangam.spring.boot.autoconfigure.SangamAutoConfiguration` is registered in `META-INF/spring/...AutoConfiguration.imports` and carries `@AutoConfigureOrder(HIGHEST_PRECEDENCE)` (`SangamAutoConfiguration.java:32-36`).
- It backs off when the server wiring is already present (`@ConditionalOnMissingBean(type = SERVER_WIRING_MARKER)`).
- It imports the server's packages `org.sangam.server` and `org.sangam.rest.web` without `SangamServerApplication` (`SangamProductImportSelector.java:43,46,105-110`).

**Defaults**
- `META-INF/sangam/sangam-defaults.yaml` is loaded at the lowest precedence, so any property of yours wins. Turn it off with `sangam.starter.defaults.enabled=false` (`SangamDefaultsEnvironmentPostProcessor.java:52-54,73`).
- The datasource comes from `SANGAM_DB_URL`, `SANGAM_DB_USER` and `SANGAM_DB_PASSWORD` (`sangam-defaults.yaml:11`).
- Turn a module on with `sangam.modules.<name>.enabled=true`, for example `SANGAM_MODULE_PACKS=true`.
- **Compliance** stays off in a host, because it needs `sangam.compliance.jurisdictions` and there is no default (`sangam-spring-boot-starter/README.md:40-42`). Turning it on also brings the principal key into play (section 11).

**Schema (decision 334)**
- In a development environment the starter sets `operaton.bpm.database.schema-update=true` and `sangam.liquibase.mode=startup`. Development means `sangam.security.production=false` and an active profile of dev, h2, test or local.
- Everywhere else they are `false` and `validate`. A start against a database without the engine tables is refused with `ENGINE_SCHEMA_MISSING`.
- Migrate once with `SANGAM_LIQUIBASE_MODE=migrate-only` (`SangamDefaultsEnvironmentPostProcessor.java:113-122`; `sangam-spring-boot-starter/README.md:57-68`).

**Your own routes**
- Sangam's `SecurityFilterChain` has no matcher and ends with `denyAll()`.
- Declare your own chain with a `securityMatcher` and `@Order(1)` (`sangam-spring-boot-starter/README.md:69-85`).
- A minimal host is a plain `@SpringBootApplication`: `sangam-spring-boot-starter/src/test/java/org/example/sangamhost/SampleHostApplication.java`.

## 3. The standalone server

**Main class:** `org.sangam.server.SangamServerApplication`, which scans `org.sangam.server` and `org.sangam.rest.web` (`sangam-server/src/main/java/org/sangam/server/SangamServerApplication.java:15-20`).

| Setting | Value (`sangam-server/src/main/resources/application.yaml`) |
|---|---|
| App port | `server.port: ${SANGAM_PORT:8080}` (:4) |
| Management port | `management.server.port: ${SANGAM_MANAGEMENT_PORT:8081}` (:444). Endpoints health, info, metrics and prometheus (:448). Probes are on, with `/livez` and `/readyz` also on the app port (:451-454). |
| Default Spring profile | `h2` (:16), for development only |
| Forwarded headers | `server.forward-headers-strategy: none` (:8). The client address is resolved only through trusted proxies (below). |

**Run it**
- **Compose:** `cd sangam/deploy/compose; cp .env.example .env; docker compose --profile postgres up -d --build`. Other database profiles are oracle, sqlserver and mysql, one at a time. Add-ons: `--profile simulators`, `--profile observability` (`deploy/README.md:77-86`).
- **Helm:** chart `deploy/helm/sangam` (`Chart.yaml`, kubeVersion >= 1.30). Probes `/actuator/health/liveness` and `/readyz` on 8081 (`deploy/README.md:172-195`).
- **Image:** `deploy/docker/server.Dockerfile`, with targets `runtime` and `runtime-distroless`. It loads drivers and plugins from `/opt/sangam/lib-ext`, `/opt/sangam/drivers` and `/opt/sangam/config` through `PropertiesLauncher` (:173).

**Settings every installation decides** (`deploy/README.md:88-101,204-253`)
- **Production switch:** `SANGAM_SECURITY_PRODUCTION`, always explicit. Compose defaults it to true; Helm uses `server.production: true`. One predicate decides production: `sangam.security.production=true` or an active profile containing `prod` (`sangam-server/.../config/ProductionEnvironment.java:48`).
- **Database:** `SANGAM_DB_URL`, `SANGAM_DB_USER`, `SANGAM_DB_PASSWORD`. Production refuses an unset value or the shipped default password.
- **Trusted proxies:** Spring property `sangam.http.trusted-proxies` (env `SANGAM_TRUSTED_PROXIES`, `application.yaml:92`), Helm value `server.trustedProxies`. It must list the UI and the ingress, because the per-address one-time-code limit counts the address the server resolves (decision 371 (1)). In production the server refuses to start while it is empty (`TRUSTED_PROXIES_REQUIRED`, register DEC-371-1).
- **System audit scope:** `sangam.engine.system-audit-scope`, default `system:platform` (decision 144).
- **Key material outside development:**
  - KEK `sangam.security.kek.*`;
  - registry signer `sangam.registry.signer.*`;
  - pepper `sangam.registry.vault.hmac-pepper`;
  - the compliance principal key (section 11).

## 4. The REST contract

**Where it comes from**
- One generated file, `sangam-rest/api/sangam-api.yaml` (OpenAPI 3.1).
- It is generated from the route tables in `sangam-rest/api/routes/*.py` by `python -E -X utf8 api/gen_openapi.py api/sangam-api.yaml`, run in `sangam-rest` (`sangam-rest/README.md:7`). Never edit the YAML by hand.
- CI regenerates it and fails on a diff (`.github/workflows/ci-build.yml:74-80`).
- Events are in `sangam-rest/api/sangam-events.asyncapi.yaml`.

**Conventions** (`sangam-api.yaml:10-28`)
- Base path `/api/v1`, JSON, ISO-8601 UTC times, string ids.
- `Authorization: Bearer <token>`. The tenant comes from the token, never from the request (section 6).
- Errors are RFC 9457 `application/problem+json` with a stable `code`. The `Problem.code` enum is open: a client must accept codes it does not know.
- Lists use `?page=&size=`: page is 0-based; size defaults to 50, from 1 to 500. They return `X-Total-Count`, and `X-Total-Count-Capped: true` when the count is capped.
- The unchanged Operaton REST API at `/engine-rest` is not part of the contract. Only `sangam-admin` (platform) and `sangam-engine-admin` reach it (`sangam-rest-core/.../support/Permissions.java:142`).

**Versioning** (`sangam-rest/API-LIFECYCLE-POLICY.md:5-30`)
- Within `/api/v1` only additive changes are made; a breaking change moves to `/api/v2`.
- A deprecation lasts at least 365 days and is announced with the `Deprecation`, `Sunset` and `Link` headers. After the sunset the answer is `410 API_SUNSET`.
- The gate is `OpenApiCompatibilityTest` against `api/baseline/`.

**SDK surface**
- `python sdks/codegen/generate_sdk_surface.py` generates the typed clients from the contract for Java, TypeScript, Python, .NET and PHP.
- `--check` fails on drift.
- `sdks/codegen/surface-manifest.json` maps each operationId to its method name in each SDK (`generate_sdk_surface.py:5-18`).

## 5. Idempotency and replay

**The key**
- Every POST, PUT, PATCH and DELETE command under `/api/v1` takes an optional `Idempotency-Key`: 1 to 128 printable ASCII characters (`IdempotencyFilter.java:83-87`; `sangam-api.yaml` parameter IdempotencyKey).
- The key is scoped by tenant, command type and key (`sangam-core/.../layer/IdempotencyStore.java:19`).
- The same key with the same request replays the first answer. A replayed answer carries `Idempotent-Replayed: true`, or `compacted` after the response retention, plus `Idempotency-Key-Expires`.
- **Required only in the government profile** (`sangam.rest.government-profile`, default false, `application.yaml:339`). There, commands marked for it (start, correlate, task decisions, cancel, delete, deployment) without a key get `400 IDEMPOTENCY_KEY_REQUIRED` (`IdempotencyFilter.java:100-106`).

**What the server checks**
- The fingerprint is a SHA-256 over the command type, method, path template and parameters, query, `If-Match`, `X-Sangam-Acting-For-Seat` and the body (`IdempotencyFilter.java:46-48,129-140`).
- On a replay the caller's permission is checked again; a failure is `403 ACTION_NOT_PERMITTED`. Every replay is audited as `IDEMPOTENT_REPLAY`. If that audit cannot be written, the answer is `503 AUDIT_UNAVAILABLE` and no replay happens (`IdempotencyFilter.java:188-212`).
- Who may replay what is derived from the contract: each `x-sangam-command-type` with its `x-sangam-permission`, plus an explicit map for the rest. Any other type is refused (`sangam-server/.../bridge/ReplayRules.java:20-27`). For example, the public one-time-code issue has the rule of the public rights submission (decision 360).

**Retention** (`IdempotencyStore.java:150`)
- A claim lives 30 s, a stored response 24 h, a key 30 days.
- Stored responses are encrypted at rest.

| Code | Status | When (`IdempotencyStore.java:74,225,252,256`; `IdempotencyFilter.java:231-238`) |
|---|---|---|
| `IDEMPOTENCY_KEY_INVALID` | 400 | not 1-128 printable ASCII |
| `IDEMPOTENCY_KEY_REUSED` | 422 | same key, different request |
| `IDEMPOTENCY_KEY_PRINCIPAL_MISMATCH` | 422 | the key belongs to another principal |
| `IDEMPOTENCY_IN_PROGRESS` | 409 | the first request is still running; `Retry-After: 1` |

**Downstream idempotency.** Each external work item carries an `operationId`: a UUIDv5 of the tenant, the root, the activity instance and the loop. A worker forwards it to the external system as that system's idempotency key (`sangam-api.yaml` schema ExternalWork).

## 6. Tenants

**Where the request tenant comes from**
- From the token, never from the request.
- For an OIDC JWT, the claim named by `sangam.security.claims.tenant` (default `tenant`, `application.yaml:376-378`). A JWT without it is refused (`SangamResourceServerConfiguration.java:91-92`).
- For an API token, the tenant it was issued in.

**Engine tenant (decision 145)**
- Process work runs under the engine tenant set when the instance was created by an authenticated, authorised caller.
- A `tenantId` process variable is business data. It never selects the tenant.
- A conflict or a missing tenant is refused with `TENANT_CONTEXT_CONFLICT`, `TENANT_CONTEXT_MISSING` or `TENANT_SHARED_SCOPE_NOT_ALLOWED` (`sangam-core/.../tenant/TenantRefusal.java:8-20`).
- Other refusals: `403 TENANT_MISMATCH`, `401 RESERVED_TENANT_REFUSED`, `421 TENANT_MOVED`, `421 TENANT_NOT_ON_INSTANCE`.

**Creating a tenant**
- `POST /api/v1/admin/tenants` (createTenant), platform administrators only.
- The tenant id must match `[A-Za-z0-9][A-Za-z0-9_.-]{0,59}`, otherwise `409 INVALID_TENANT_ID`. An existing id gets `409 TENANT_EXISTS` (`sangam-security/.../admin/TenantAdminService.java:210-237`).
- The set-up creates the tenant's platform role rows (decision 368, `SangamSecurity.java:124`).
- Packs are deployed when a tenant is created or activated (`sangam-server/.../bridge/PacksTenantDeployer.java:20-24`).

**Platform role rows of existing tenants (decision 370 (1))**
- At every start the server adds the platform role rows each tenant lacks. It is idempotent, never overwrites a role an administrator defined, and audits `GRANT_CHANGED`.
- A failure stops the start (`sangam-server/.../config/SecurityWiring.java:279-300`; `RoleAdminService.java:178-216`).

**Platform administrator**
- Only an identity from `sangam.security.platform-issuer` is a platform administrator.
- In a tenant token, `sangam-admin` becomes `sangam-tenant-admin` (`Permissions.java:112-121`; `deploy/README.md:144-149`).

## 7. Security tokens and scopes (decisions 358, 368, 370)

**Ways to authenticate**
- An OIDC JWT, with profile `oidc` and `SANGAM_OIDC_ISSUER`, or `spring.security.oauth2.resourceserver.jwt.*`. The audience defaults to `sangam-server`.
- A Sangam API token `sgm_<tokenId>.<secret>`, sent as `Authorization: Bearer` (`sangam-server/.../security/spring/TokenAuthenticationFilters.java:47-48`).
- Only SHA-256 of the API token secret is stored, and the secret is shown once (`sangam-security/.../admin/ApiTokenService.java:30-37`). Expiry is at most one year, otherwise `409 INVALID_EXPIRY`.

**The scopes are platform groups.** The groups usable as scopes are the keys of `Permissions.DEFAULT_GROUP_PERMISSIONS` (`sangam-rest-core/.../support/Permissions.java:152-184`; `SecurityWiring.platformRoles()`, :110-118). Each one is a role row (`SEC_ROLE`) of every tenant.

| Group | Grants (abridged) |
|---|---|
| `sangam-user` | tasks, processes, cases, documents, forms, module reads |
| `sangam-citizen` | portal only |
| `sangam-worker` | `work.execute`, message send, documents, event publish |
| `sangam-integration` | work, messages, events, process start and read |
| `sangam-operator` | user plus module writes, ops, reconciliation, batch, history |
| `sangam-auditor` | audit read and export, history, reads |
| `sangam-tenant-admin` | tenant administration, policy, packages, compliance admin |
| `sangam-dpo` | compliance read, modify and admin, audit read and export |
| `sangam-model-author`, `-reviewer`, `-approver`, `-deployer` | the model lifecycle |
| `sangam-system` | internal system work |

`sangam-admin`, `sangam-platform` and `sangam-engine-admin` are never token scopes.

**Issuing a token** (`ApiTokenService.issue`, :80-99)
- Every requested scope must be a role the owner holds now: role bindings of the person, its groups and its seats, plus its IdP roles. When a caller issues for itself, its current roles count too.
- An IdP group name alone is never a held role (decision 370 (2)).
- A scope not held is refused with `409 TOKEN_SCOPE_NOT_HELD` (`ApiTokenService.java:58`), and nothing is stored.
- Issuing for another person needs ADMIN on API_TOKEN.

**Service principals** (`POST /api/v1/admin/service-principals`; `sangam-server/.../bridge/SecurityIdentityAdminBridge.java:247-296`)
- Name, scopes and topics are required, otherwise `400 VALIDATION_FAILED`.
- The server creates the person `svc-<name>`, binds its worker topics, then binds each scope through `RoleAdminService.bind` and issues the token. The default lifetime is 365 days.
- `bind` needs CHANGE_GRANT on the role. A missing right is `403 ACTION_NOT_PERMITTED`. A scope that is no defined role of the tenant is `409 UNKNOWN_ROLE` (`RoleAdminService.java:97-122`).
- Any refusal rolls the whole creation back (decision 368).

**Break-glass** (`ApiTokenService.java:101-124,177-188`; routes `/admin/security/break-glass/grants...`)
- Its own path. The roles come from the tenant's token-class configuration after dual approval.
- The token name starts with `break-glass:`, and every use is audited as `BREAK_GLASS_USED`.

**Worker topics.** A worker may only fetch topics it is bound to: WORK on `WORKER_TOPIC/<topic>`, otherwise `403 WORKER_TOPIC_NOT_BOUND` (`SecurityWiring.java:146-181`).

## 8. SDK workers and the worker lifecycle

**SDKs** (under `sdks/`)
- Tier 1, hand-written plus generated surface: Java, TypeScript, Python, .NET, PHP.
- Framework adapters: `java-spring-boot`, `typescript-nestjs`, `python-frameworks`.
- Tier 2, generated (`sdks/tier2/generate_tier2.py`): Go, Kotlin, Rust.
- The cross-language run (AC-08) is the `ac08` job in `.github/workflows/sdks.yml:366-412`. Its CI result is not green yet (register AC-08).

**Worker endpoints**
- All under `/api/v1/work` with permission `work.execute`, and all fenced (`x-sangam-fenced: true`, `sangam-api.yaml:159-521`).
- `POST /work/fetch-and-lock`, then `/work/{id}/extend-lock`, `/complete`, `/fail`, `/bpmn-error`, `/unlock`, `/outcome-unknown` and `/cancel-ack`.

**Lifecycle**
1. **Fetch and lock.** Send `workerId`, `topics[]` (each with `name` and `lockDurationMs`), optionally `maxTasks` and a long-poll `asyncResponseTimeoutMs`. The default lease is 5 min and the maximum 24 h (`sangam-defaults.yaml:318-319`). Each item carries `id`, `tenantId`, `lockVersion`, `attemptId`, `operationId`, `lockExpiresAt`, `retries` and the stored `operation` state.
2. **Heartbeat.** `extend-lock` with `workerId`, `lockVersion` and `newDurationMs`. The answer's `cancelRequested` tells the worker to stop and `cancel-ack`.
3. **Settle.** `complete`, `fail`, `bpmn-error` or `unlock`, always with `(workerId, lockVersion, attemptId)`. A stale lease is `409 STALE_LEASE`; a deleted instance is `410 GONE_INSTANCE_DELETED`.
4. **Unknown outcome.** When the worker cannot tell whether the external call took effect, it reports `outcome-unknown` instead of guessing.

**Capability class (SANGAM.md A-8; `sangam-core/.../layer/CapabilityClass.java`)**
- Each connector or topic declares (a) idempotent with lookup, (b) lookup only, or (c) neither.
- An undeclared topic is (c), and `effect=irreversible` is an alias of (c).
- On lease expiry: (a) retries with the same operationId; (b) looks the outcome up first; (c) goes to UNKNOWN_OUTCOME and a reconciliation item, with no automatic repeat.

**The Java SDK runtime does this for you** (`org.sangam.sdk.worker.SangamWorker`, `sdks/java/src/main/java/org/sangam/sdk/worker/SangamWorker.java:43-61`)
- long-poll fetch and a heartbeat at a third of the lease;
- the fencing triple on every call, and the Idempotency-Key `<operationId>:<action>:<lockVersion>`;
- unlocking of in-flight items on shutdown.

Worker credentials are a service-principal API token, or the Keycloak client `sangam-worker` (client credentials, `deploy/README.md:158`).

**Tenant isolation.** A worker leases, sees and settles only its own tenant's work, even when topic names collide (register SL-157, WorkerTenantIsolationH2Test).

## 9. Connectors, simulators and events

**Connectors**
- A connector implements `org.sangam.core.integration.SangamConnector` and declares a `ConnectorDescriptor` with its capability class.
- Third-party connectors and packs come as one signed plugin jar in `lib-ext/plugins`, found through `META-INF/services` (`examples/plugin-connector-example/README.md:1-15`).
- Topics bind to connectors with `sangam.integration.topics` (`<topic>=<connectorId>:<operation>`, `application.yaml:260-261`).

**Simulators**
- A connector's profile is `LIVE` or `SIMULATOR` (`sangam-core/.../integration/ConnectorProfile.java`).
- The simulators (`sangam-integration/.../sim/SimulatorProfile.java`) start lazily on loopback; `sangam.integration.simulators.enabled` defaults to false (`application.yaml:256-257`).
- `deploy/docker/simulators.Dockerfile` runs one simulator per container (`SANGAM_SIM_KIND` ifms, pfms, esign, dlt, digilocker, paygov or smtp). Never deploy them in production.

**Events**
- The AsyncAPI file names the channels (webhook, channel, rollup, cross-cell, inbox).
- Outbound destinations are set with `SANGAM_OUTBOX_DESTINATIONS` (`application.yaml:262`).
- Inbound Azure Event Grid is off by default (`sangam.ports.events.eventgrid.enabled`, `PortsWiring.java:444`).

## 10. Packs

**What a pack is**
- A `SangamPack` with a `PackDescriptor`: id, version, names, domain, processes, resources, seed roles and seats, connectors with their capability class, and covered ids (`sangam-packs/README.md:37`).
- Resource kinds are BPMN, DMN, CMMN, FORM, SEED and OTHER.
- 147 packs ship (`sangam-packs/README.md:2-6`).

**Generating them**
- Packs are generated, never hand-edited: `python sangam-packs/tools/packgen/main.py` writes them, and `--check` fails on any difference (`main.py:2-10,116-124`).
- Domain knowledge lives in the pack data, not in the engine.

**DSC gates are reached through a call (decisions 382, 385 and 387 (3))**
- The signing levels of `sangam-core-recommendation` (slot `notingDocumentId`) and the approval of `sangam-core-sanction` (slot `sanctionDocumentId`) sign the document in their slot (`DscSignatureGuardListener`).
- Only the document route fills a slot, on an open user task of the case (decisions 267, 268 and 385 (2)).
- So a gate process is started by a call from a pack process, with `in variables="all"`. Where no user task of the caller comes before the call, packgen puts a prepare task there; its form requires the slot (`sangam-packs/tools/packgen/prepare.py`).
- A gate process started directly, with no caller, has no user task before its gate. Its slot stays empty, and the signature is refused with `422 DSC_SIGNATURE_NOT_VERIFIED`. Start the pack process that calls it instead.

**Deployment**
- `PackDeployer.deployAll(tenantId)` makes one engine deployment per pack (`pack:<id>@<version>`).
- A version is immutable (`PACK_VERSION_IMMUTABLE`, answered as `422 PACK_REJECTED`). A rollback rolls forward.
- `sangam.packs.deploy-on-start` is true only on the h2 profile. `sangam.packs.tenants` lists the tenants (`sangam-server/.../config/PacksWiring.java:66-68,261-282`).
- Routes: `GET /packs/installed`, `POST /packs/{id}/uninstall` (`404 PACK_NOT_INSTALLED`, `409 PACK_HAS_DEPENDANTS`) and `POST /packs/{id}/rollback`.

**Jurisdiction packs**
- Compliance jurisdictions (IN, EU-GDPR, US-HIPAA) are under `sangam-compliance/src/main/resources/compliance/jurisdictions/`.
- Select them with `sangam.compliance.jurisdictions`; there is no default. Helm sets `IN` (`deploy/helm/sangam/values.yaml:583-585`).

## 11. The compliance principal key (decisions 371 and 372)

**What it keys.** Data-principal tokens in the compliance audit, and the identifier and client-address hashes of rights-request one-time codes, are HMAC-SHA256 under one key per environment (`deploy/README.md:223-233`).

**Setting it**
- `sangam.compliance.principal-key-ref` (env `SANGAM_COMPLIANCE_PRINCIPAL_KEY_REF`, `application.yaml:156-160`; Helm `compliance.principalKeyRef`).
- The value is a `secret://` reference whose secret is the base64 of at least 32 bytes, for example `secret://compliance/principal-key` with `SANGAM_SECRET_COMPLIANCE_PRINCIPAL_KEY`. Generate one with `openssl rand -base64 32` (`deploy/compose/.env.example:57-63`).

**Enforcement** (`ComplianceWiring.principalKey`, `sangam-server/.../config/ComplianceWiring.java:188-211`)
- In production the server refuses to start without it (decision 371 (5)).
- Outside production, without it, the server starts with a warning, and consents, guardian verifications, nominations, personal-data read audits and one-time codes are refused with `409 PRINCIPAL_KEY_NOT_CONFIGURED`.
- A literal key, or a reference whose secret is missing, not base64 or shorter than 32 bytes, stops the start everywhere.
- The server's start refusal is the only enforcement (decision 372 (1)): the Helm chart renders without the key. A compose stack in its default production mode needs it (decision 372 (2)).
- A host on the starter meets the key only once it turns compliance on (section 2).

**Rotation.** Changing the key changes every token written after the change, so rotate it only with a recorded change.

**One-time code.** The code itself stays a salted SHA-256, short-lived and attempt-limited (decision 371 (6)).

## 12. Secrets an installation needs (checklist)

- Database credentials (`SANGAM_DB_*`).
- Outside development:
  - the KEK;
  - the registry signer key pair;
  - the HMAC pepper;
  - the compliance principal key, when compliance is on (`deploy/README.md:218-233`).
- The OIDC issuer, and the platform issuer for platform administrators.
- Per-connector credentials and webhook secrets, as `secret://` references.
- The audit checkpoint signing key (`secret://audit/checkpoint-<env>`, `application.yaml:245-251`). Keys per purpose and per environment are decision 37: the start refuses one key for two purposes (`KEY_SHARED_ACROSS_PURPOSES`) and a key on the list of another environment's key fingerprints (`KEY_SHARED_ACROSS_ENVIRONMENTS`, decision 385 (3)); production needs `sangam.keys.environment` and `sangam.keys.foreign-fingerprints` (deploy/README.md section 1, register DEC-37).

## 13. Open points that affect integrators (from the register)

- **AC-08:** the cross-language CI run is not green yet.
- **DEC-145:** there is no migration inventory for instances that relied on the old tenant-variable fallback.
- **SL-170:** Event Grid and Service Bus have not been run against the live Azure services.
