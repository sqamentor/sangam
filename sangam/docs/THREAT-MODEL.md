# Sangam STRIDE threat model per component (M-096)

Part I principle A6 asks for a STRIDE threat model per component (API, worker gateway, script sandbox, event gateway,
UI), reviewed each release, with open high risks blocking the release. `SANGAM.md` Part II §11 has a one-line STRIDE
table per component class. This file is the per-component model for the code as it is now: for each component it
names the trust boundaries, the control in the code for each STRIDE class, and the gaps.

**Evidence rule.** Every control and gap below cites the source type or file. Java types are named by module or
package tail (for example `server/config/SecurityConfig` is
`sangam-server/src/main/java/org/sangam/server/config/SecurityConfig.java`); other paths are relative to `sangam/`. The code was read on
2026-11-12 (Wave 2 tree, base commit `0e53f91b` plus the Wave 2 edits in progress). A listed test exists; no test has
been run in the current phase, so "test" never means "passed". Line numbers are left out on purpose because the Wave 2
lanes are still changing these files; search for the named type or method.

**Status words.** *Control*: present in the code. *Gap*: no control was found for that threat. Each gap has an id
(TM-n) so the integrator can turn it into a register row.

STRIDE: **S** spoofing, **T** tampering, **R** repudiation, **I** information disclosure, **D** denial of service,
**E** elevation of privilege.

## 1. REST API (`sangam-rest`, `sangam-server`)

**Trust boundaries.** Internet or department network → ingress or SCALE gateway → server (`/api/v1`, `/engine-rest`,
public hooks) → database. Callers are officers and citizens through the UI, integrations with service principals, EHR
CDS clients and channel providers (webhooks).

| STRIDE | Control | Evidence |
|---|---|---|
| S | JWT resource server: issuer, expiry and audience checked; platform issuers have their own decoders | `server/config/OidcConfig`; tests `OidcIssuerRoutingTest`, `PlatformIssuersTest` |
| S | The tenant comes only from the token's claim; a token without it gets 401 | `server/.../JwtPrincipalConverter` (VI.4 rule 6) |
| S | Dev token refused in production, compared in constant time | `server/config/SecurityConfig`, `server/security/DevTokenFilter`; `ProductionModeTest` |
| S | `X-Forwarded-*` trusted only from configured proxy CIDRs (`server.trustedProxies`) | `TrustedProxyFilter`; `TrustedProxyFilterTest` |
| S | CDS Hooks clients: per-client JWT with `iss`, `aud`, `exp`, and a cluster-wide `jti` replay guard (SL-138) | `HealthHooksController`, `ClientJwtVerifier`, `security/oidc/JdbcReplayGuard` |
| T | Idempotency-Key with a request fingerprint, bound to the principal; a replay returns the stored result | `rest/.../IdempotencyFilter`, `core/layer/IdempotencyStore`; `E4IdempotencyIT` |
| T | Approval bound to the payload hash and task version | `security/approval/ApprovalBindingService` (VI.4 rule 5) |
| R | Object-level denials write `AUTH_DENIED` in their own transaction, so the denial survives the rollback | `security/.../AuthorizationService`; compliance denials SL-137 (`ComplianceAudit`, leaf pool `sangam-compliance-audit`) |
| R | State changes are audited in the command transaction on the root's hash chain | `core/layer/AuditChain` (X.2.3) |
| I | Deny-wins policy compiled to SQL; every list, count and get goes through the query builder; invisible rows are 404 | `security/policy/PolicyCompiler`, `AuthorizedQueryBuilder`; `AuthorizationPolicyIT`, `E2AuthorizationIT` |
| I | Problem+json errors without stack traces (`server.error.include-stacktrace: never`); unhandled errors become a generic 500 | `application.yaml`, `ProblemHandler`, `ProblemErrorController` |
| I | Idempotency response bodies encrypted at rest under the tenant key (SL-139) | `IdempotencyStore`, wired in `SangamRestAutoConfiguration` |
| D | Per-tenant rate and size quotas on `/api/*` and `/engine-rest/*` (SL-43), on their own connection pool | `server/bridge/TenantQuotaFilter`, `server/config/QuotaWiring`; `TenantQuotaIT` |
| D | Body limits: multipart 256/260 MB, idempotency body read 16 MiB, inbound events 1 MiB | `application.yaml`, `IdempotencyFilter`, `InboundEventProcessor` |
| D | Bounded database waits answer 503 `DATABASE_UNAVAILABLE` (SL-59) | `deploy/README.md` §1.6 "Bounded waits" |
| E | URL rules deny by default; `/engine-rest` only for admin groups; actuator only for platform operators | `server/config/SecurityConfig`; `EngineRestRoleGateTest` |
| E | Platform-only groups (`XCELL`, `CELL_ADMIN`, platform admin) are removed from tenant tokens (SL-70) | `PlatformIssuers`, `SecurityIdentityFilter`; `TokenAuthenticationFiltersTest` |
| T/E | CSRF is off because the API is stateless and bearer-only; CORS is off unless `SANGAM_CORS_ORIGINS` lists origins | `server/config/SecurityConfig` |

**Gaps.**
- **TM-1 (R, medium).** Endpoint-gate refusals (`PermissionPort.require`) throw without an audit event, and rejected
  JWTs are not audited. `LOGIN_FAILED` covers only identity-resolution failures (`SecurityIdentityFilter`).
- **TM-2 (D, medium).** Requests with no tenant skip the quota (`TenantQuotaFilter` passes them through), so the public
  routes (channel and FHIR hooks, CDS Hooks) have no rate limit. No per-IP or per-client limiter exists.
- **TM-3 (E, medium, needs evidence).** `AuthorizedQueryBuilder` accepts raw SQL fragments for select, join, where and
  order by. That is safe only while no caller passes request input. The ArchUnit rule that forces every read through
  the builder (VI.4) is not in `ArchitectureGuardTest`.
- **TM-4 (I, low).** No HSTS header is configured by the server; TLS and its headers are left to the ingress.

## 2. Worker gateway (`/api/v1/work`, `WorkController`)

**Trust boundaries.** External workers (Java, .NET, PHP, Python, TypeScript SDKs) authenticate as service principals
and fetch, extend, complete and fail external work over REST. They are outside the server's trust zone.

| STRIDE | Control | Evidence |
|---|---|---|
| T | Lease fencing on `(workerId, lockVersion)` plus `attemptId`; a stale call gets 409 `STALE_LEASE` | `core/api/LeaseTable`, `rest/adapter/operaton/OperatonWorkAdapter.fence`, engine-side lease table in `OperatonEngineAdapter`; `E3LeaseIT` |
| T | The operationId is granted with the lease and never re-derived; the intent is recorded before the worker sees the work | `EngineAdapterWorkAdapter.recordIntent`, `WorkLedger`; `WorkApiLedgerIT` |
| R | Lease outcomes are audited; worker binding and unbinding write `WORKER_BOUND` / `WORKER_UNBOUND` | `EngineAdapterWorkAdapter`, `security/admin/WorkerBindingService` |
| D | `maxTasks` capped at 100, wait capped by `sangam.rest.max-async-response-timeout` (30 s), lease length capped by the engine's maximum lease; long-poll waiters capped per node (`sangam.work.long-poll.max-waiters`, 1000) | `EngineAdapterWorkAdapter`, `RestProperties`, `OperatonEngineAdapter.fetchAndLockWork`, `rest/support/WorkLongPoll`; `WorkLongPollTest` |
| E | Every `/api/v1/work` route requires the `WORK_EXECUTE` permission | `rest/web/WorkController` |

**Gaps.**
- **TM-5 (I/E, high; register SL-157). Status 2026-11-12: coded in Wave 2; 2026-11-15: WorkerTenantIsolationH2Test passed in the ci18 H2 run** (`EngineAdapterWorkAdapter` gives back a
  leased item of another tenant and checks the tenant on every later call; ITs not run). Finding as recorded: The worker fetch is not filtered by tenant on the adapter the server uses. With an
  `EngineAdapter` bean (always, in the server) the REST layer uses `EngineAdapterWorkAdapter`. Its `fetchAndLock` calls
  `OperatonEngineAdapter.fetchAndLockWork(workerId, topic, ...)`, which runs Operaton `fetchAndLock` without a tenant
  filter, and `map()` then labels every result with the caller's tenant (`ctx.tenantId()`). The leased item's own
  tenant (`LeasedWork.tenantId()`) is known but not compared. Operaton's tenant check needs an engine authentication,
  which only the `/engine-rest` filter and the AI bindings set (`EngineRestTenantFilter`, `AiBindings`). So a worker of
  tenant A that polls a topic name also used by tenant B can be handed B's work and variables. The fallback adapter
  `OperatonWorkAdapter` does filter (`tenantIdIn`, `checkTenant`). No cross-tenant worker test exists.
- **TM-6 (E, high; register SL-158). Status 2026-11-12: coded and enforced** (owner decision; 403
  `WORKER_TOPIC_NOT_BOUND` through `SecurityWiring.sangamWorkerTopicGuard`; tests not run). Finding as recorded: Topic binding (R26) is not enforced: `WorkerBindingService.requireTopic` and `mayWork` have no
  callers, so any principal with `WORK_EXECUTE` may fetch any topic of its tenant.
- **TM-7 (S/T, medium).** The lease is not bound to the authenticated principal: `workerId` comes from the request body.
  In `OperatonWorkAdapter.fence` the attempt check is skipped when `attemptId` is null.
- **TM-8 (I, medium).** No per-worker variable filter: all variables are returned unless the worker asks for a subset.
  `READ_VARIABLE` is granted at bind time but never checked.

## 3. Script sandbox (ADR-D-8) and model code

**Trust boundaries.** Process models come from packs and from the modeller. ADR-D-8 says scripts run in an
out-of-process script worker, never in the engine.

| STRIDE | Control | Evidence |
|---|---|---|
| E | BPMN script tasks are refused at deploy (`rejectScriptTasks`, default true) | `engine/deploy/SangamBpmnParseListener`, `SangamEngineSettings`; `FwtScriptTaskDeployH2Test` |
| E | Package review reports an inline or resource script as the error `SCRIPT_IN_ENGINE` | `rest/adapter/operaton/OperatonPackageToolsAdapter` |
| E | No script runtime ships: GraalJS excluded in the engine pom; the server image build refuses script-engine jars | `sangam-engine/pom.xml`, `deploy/docker/server.Dockerfile`, `deploy/licence/check_licences.py` |
| T | Java serialization of variables off; XML external entities off | `engine/SangamProcessEnginePlugin` |

**Gaps.**
- **TM-9 (E, high; register SL-159, backlog M-061). Status 2026-11-15: coded** (`tools/sangam-script-worker` runs each script under nsjail, owner decisions 53, 77, 78, 89 and 128; script tasks are rewritten to the worker convention at deploy by `ScriptTaskDeployRewrite`; unit tests passed in the ci18 H2 run; a script under a real nsjail on Linux is not yet run). Finding as recorded: The out-of-process script worker does not exist (`sangam-flowable-ports/README.md` FC-27, FC-47:
  "script worker: WS-INTEGRATION"). There is no sandbox, CPU or memory limit for scripts anywhere.
- **TM-10 (E, high; register SL-160).** Owner decision 2026-11-12: an allow-list at deploy (only reviewed delegate
  classes and expression functions are deployable; `SANGAM.md` X.0k). **Status: coded** (part A
  `deploy/ModelCodeAllowList`, part B `deploy/ExpressionPolicy`; sangam-engine README note 29; tests not run).
  Finding as recorded: Model code runs in the engine: service tasks with `operaton:class`, `expression` or
  `delegateExpression` are accepted (`OperatonPackageToolsAdapter` treats them as bound), and no restriction on the
  beans or classes an expression may reach was found. Only `parseScriptTask` is blocked; script execution listeners,
  script conditions and script input/output parameters are not checked by the parse listener.
- **TM-11 (D, medium).** Apart from the JDBC statement timeout there is no time bound on expression or delegate
  execution.

## 4. Event gateway (inbox, broker consumers, webhooks, cross-cell, SCALE gateway)

**Trust boundaries.** Brokers (Kafka, JMS), webhook senders and receivers, other cells, and the reactive SCALE gateway in
front of the cells.

| STRIDE | Control | Evidence |
|---|---|---|
| S/T | Outbound webhooks signed: `X-Sangam-Signature: t=..,v1=HMAC-SHA256(secret, t + "." + body)`, 5-minute tolerance, several `v1` during rotation, constant-time compare | `integration/publish/WebhookSignature`, `HttpWebhookPublisher`; `WebhookRelayIT` |
| S | Inbound signature checks on Sangam webhooks and provider channels (WhatsApp, Teams, WeChat, FHIR channel secret, telemetry) | `integration/inbox/WebhookInboxReceiver`, `ChannelHooksController`, `channels/messenger/WebhookVerifiers`, `FhirSubscriptionReceiver` |
| S/E | The tenant of an inbound message comes from its channel binding; a payload claim must match | `integration/inbox/ChannelBinding` |
| T/D | Kafka: auto-commit off, ack only after the inbox commit; JMS: transacted sessions | `integration/inbox/KafkaInboxConsumer`, `JmsInboxListener`; `InboxIT`, `E5OutboxInboxIT` |
| D | Poison messages go to the dead-letter store with a reconciliation item; back-pressure pauses or rolls back | `integration/inbox/PoisonInbound`, `relay/BackpressureGate`; `T08PoisonOutboxIT` |
| D | Tenant admission (M-008, SL-142): a non-ACTIVE tenant's message is not stored; PARK for tenants that left the cell | `integration/relay/TenantAdmission`, `server/config/BrokerInboxWiring`, `CellWiring`; `KafkaInboxConsumerTenantStateTest`, `JmsInboxListenerTenantStateTest` |
| E/S | Cross-cell messages need the `XCELL` role (platform issuer only), body at most 1 MiB, deduplicated by Idempotency-Key | `CellCrossCellInboxFilter` |
| D | SCALE gateway: per-tenant in-flight cap; hop-by-hop headers removed | `sangam-gateway/.../CellProxy` |
| S | Egress allow-list for webhooks and connectors (loopback, private and metadata ranges blocked) | `integration/connector/EgressPolicy` |

**Gaps.**
- **TM-12 (T, medium).** The webhook signature covers the timestamp and body only, not the `ce-*` headers, so the event
  type, subject and tenant attributes can be changed in transit without breaking it.
- **TM-13 (S, medium).** `WebhookInboxReceiver` skips verification when no secret is configured.
- **TM-14 (R, medium).** Kafka and JMS records whose tenant claim the binding refuses are dropped with a WARN log only
  (no audit event, no dead letter).
- **TM-15 (D, medium).** A non-admitted tenant pauses its whole Kafka partition, which delays the other tenants on it.
- **TM-16 (S/T, medium).** No message-level authentication on Kafka or JMS, and TLS or SASL is not enforced; the broker
  settings are passed through as configured.
- **TM-17 (D, medium).** The gateway reads the tenant from the JWT without checking its signature (by design: "routing
  is not authorization", `TenantResolver`). The cell refuses a forged token, but the forged request still counts against
  the named tenant's in-flight cap at the gateway.
- **TM-18 (I/E, low).** The gateway answers "not registered" for unknown tenants (enumeration), has no Spring Security
  of its own, and exposes metrics on its management port.

## 5. UI (`sangam-ui`, served by the UI image)

**Trust boundaries.** The browser (officer, citizen, field device) → UI static files (nginx in the UI image) → API.
The identity provider is on another origin.

| STRIDE | Control | Evidence |
|---|---|---|
| S | OIDC authorization code with PKCE (`oidc-client-ts`); the user store is sessionStorage | `src/auth/AuthProvider.tsx` |
| T/I | CSP `script-src 'self'`, `object-src 'none'`, `frame-ancestors 'none'`, nosniff, X-Frame-Options DENY | `deploy/docker/ui/security-headers.inc.template`, `index.html` |
| T | No remote scripts, styles or fonts (`check-no-cdn`, part of `npm run lint`) | `scripts/check-no-cdn.mjs` |
| T | No `dangerouslySetInnerHTML`; embedded forms parsed inertly; external form URLs limited to http/https | `src/components/embeddedForm.ts` |
| E | Route guards on the module routes (a UI convenience; the server is the boundary) | `src/app/App.tsx`, `src/app/access.ts`; `access.test.ts` |
| I | Offline field queue: non-extractable AES-256-GCM key, AAD `tenant|device|operation|user` (SL-129) | `src/lib/offline/crypto.ts`, `fieldSync.ts`, `storage.ts` |
| T | UI plugin assets: signature checked against trusted keys; served with nosniff and `CSP: sandbox` | `server/.../plugins/PluginManager`, `UiPluginController`; `UiPluginControllerTest` |

**Gaps.**
- **TM-19 (I, medium).** Access tokens in sessionStorage are readable by any script that runs in the page; there is no
  token binding (DPoP).
- **TM-20 (E, medium).** Signed UI plugin modules run in the shell's origin and can read its tokens; the asset CSP does
  not restrict a module the shell imports.
- **TM-21 (I, low).** The offline queue key is one per installation (owner decision: user bound in the AAD). The AAD is
  not secret, so another local user of the same browser profile can decrypt entries with developer tools.
- **TM-22 (D, needs evidence).** The meta CSP in `index.html` has `connect-src 'self'`; with an identity provider on
  another origin this may block the OIDC calls and silent renew. To be checked in the browser test phase.
- **TM-23 (T, low).** `check-no-cdn.mjs` scans `.html` and `.css` only, not the JavaScript bundle.

## 6. Review

This model is reviewed at each release (Part I A6). The high gaps (TM-5, TM-6, TM-9, TM-10) are release blockers under
that rule unless the owner records an exception. TM-5, TM-6, TM-9 and TM-10 are register rows SL-157…SL-160;
the other gaps are not yet register rows. The integrator owns `TESTING-GAP-REGISTER.md`.
