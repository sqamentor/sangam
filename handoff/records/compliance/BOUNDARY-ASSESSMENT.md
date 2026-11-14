# Sangam 0.1.0: dependency-boundary and replaceability assessment

Lane W3-X3, read-only review. Date: **2026-11-12**. Directive: owner decision 112 (epic E16). Also in scope: decisions 54R, 55R, 72-75, 80, 81 and 97, and `sangam/docs/M-015-QUARKUS-EXTENSION-DESIGN.md` sections 0-10.

The tree is `D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524`, a live working tree that other lanes are editing. Paths below are relative to `sangam/` unless stated otherwise. Line numbers are as read on 2026-11-12 and may drift.

Nothing was edited in the tree. No test or build was run, so this document reports no check as PASS. Wherever it says a test exists, that means the source exists, not that the test passes.

How each item was checked:
- **[V]**: I read the cited lines myself.
- **[S]**: reported by a read-only search sweep of the tree on the same date. Some were spot-checked; none were re-read line by line.

---

## 0. Summary

1. **Business rules are mostly framework-free.** Money arithmetic, the ledger guards, deny-wins authorization, the audit hash chain, delegation-of-powers bands and the programme state machine are plain Java plus `java.sql`. None of them imports Spring or Quarkus.
   - The core host-framework boundary is fenced by `HostFrameworkBoundaryTest` (a ratchet of 19 files).
   - `ArchitectureGuardTest.coreNeverDependsOnOperaton` keeps sangam-core free of Operaton.
2. **Payment confirmation is decided in a Spring host class.** `sangam-server/.../bridge/BillingPaymentPort.java` holds the rules and reads Spring `Environment`. Two ledger adapters are lambdas inside `LedgerWiring`. Decision 54R/80 and M-015 section 10.1 forbid business rules in a host. The boundary guard cannot see this, because it fences frameworks out of non-hosts, not business logic out of hosts.
3. **The plain-Java domain modules default to permissive ports.** These defaults are consent `PERMIT_ALL`, replay authorizer `ALLOW_ALL`, the resources guard `ALLOW_ALL`, no-op ledger audit and accounting sinks, and `payments == null` meaning "confirmed". The Spring server binds real ports, so today these defaults are latent there. A thinner host (decision 81) that forgets one port would **silently weaken** authorization, audit, accounting or payment. That contradicts 54R: "a missing required capability blocks the affected operations".
4. **Operaton is the deepest lock-in (XL).** It is not a JAR swap. The coupling covers:
   - 435 pack model files with the `operaton:` namespace and 1,690 `operaton:class` bindings;
   - about 111 main files using `org.operaton.bpm.engine.impl.*`;
   - the `ACT_*` runtime and history tables;
   - the `sangam-json` variable serializer;
   - 34 documented behaviour patches.
5. **The host conformance kit does not exist.** No test today shows the same operation giving the same business result on two hosts. Only bean-set equality exists (starter compared with the server).

---

## 1. VERIFIED (2026-11-12, file:line)

### 1.1 Where business-critical logic lives

| Area | Main logic (framework-free unless noted) | Framework or vendor coupling | Kind |
|---|---|---|---|
| Money type and rounding | `sangam-core/.../core/money/Money.java:28` stores a `long` minor unit with `*Exact` arithmetic. Rounding is decided only in `Money.round` / `roundDocument` / `allocate`. [S] | `@com.fasterxml.jackson.annotation.JsonValue` at `Money.java:139` [V]. sangam-core declares `jackson-databind` at compile scope (`sangam-core/pom.xml:17`) [V]. | **Direct type dependency** (annotation) in the domain type |
| Ledger rules | `sangam-ledger/.../LedgerService.java`: posting, policies, `closePeriod`, reversal. Default no-op sinks at `:43-45` [V] | none | plain Java |
| Ledger invariants | `sangam-ledger/.../JdbcLedgerStore.java:79-131` [V]. One short transaction: insert-if-absent of the entry (idempotent on tenant and reference), then guarded `UPDATE ... >= ?` per leg. The **available ≥ 0 invariant is enforced in SQL** (`:107-121`). | Jackson `ObjectMapper` (`:42`), `javax.sql.DataSource` | **Adapter** (JDBC) holding an invariant |
| Ledger to engine DMN and FX | `sangam-server/.../config/LedgerWiring.java:97-106` (DecisionPort lambda over Operaton `ProcessEngine`); `:115-138` (FX rates) [V] | Spring `@Bean`, Operaton public API | **Adapter in the host** |
| Ledger audit and GL export | `LedgerWiring.java:149-187` [V]: lambdas writing `LEDGER_POSTED` and outbox GL rows | Spring `ObjectProvider`, `TxSupport` | **Adapter in the host, with skip paths** (see SW-6, SW-7) |
| Billing | `sangam-billing/.../BillingService.java:114-121` collect: `payments == null ? amount : payments.confirm(...)` [V]. `TariffEngine` rounds through `Money` [S]. | none | plain Java |
| Payment confirmation | `sangam-server/.../bridge/BillingPaymentPort.java:36-67` [V]: the trusted-channel list, the status interpretation and the amount parse | Spring `Environment` (`:33,39,47,55`) | **Business rule in the host** |
| Authorization | `sangam-security/.../SangamSecurity.java:44`; `policy/PolicyCompiler.java` (deny-wins; unknown condition fails closed); `policy/AuthorizationService.java` [S] | `java.sql` only | plain Java |
| Endpoint gate | `sangam-server/.../bridge/SecurityPermissionBridge.java:100-107`: group persona map **OR** sangam-security allow on `*` [V] | none | adapter in the host |
| Delegation of powers | `sangam-security/.../delegation/DelegationOfPowersService.java:210-235` [V]: inclusive `compareTo`, posting cap | `DmnPowerEvaluator` on the Operaton DMN public API [S] | plain Java plus a DMN adapter |
| Duplicate power rule | `sangam-packs/.../packs/core/PowerLimitGuardListener.java:34-70` [V]: an Operaton `TaskListener` with its own DMN lookup. It uses other output columns (`powerLimit`/`noMonetaryLimit`, not `maxAmount`/`UNLIMITED`) and ignores the posting cap. | Operaton delegate API | **Rule logic inside an engine listener** |
| Tenant and seat engine plugin | `sangam-security/.../assignment/SangamSecurityEnginePlugin.java`, `SeatAssignmentParseListener`, `tenant/SangamTenantIdProvider` [S] | Operaton `impl.*` internals | adapter on engine internals |
| Audit chain | `sangam-core/.../layer/AuditChain.java:37` (SHA-256 chain over `CanonicalJson`), `RootSeq`, `AuditCheckpoints` (Merkle tree, Ed25519) [S]. `CanonicalJson.java` is a hand-written JCS writer that does not depend on Jackson ordering [S]. | `PendingAudit.java` uses Jackson (read only) [S] | plain Java |
| Mandatory audit in the engine | `sangam-engine/.../history/MandatoryAudit*.java` [S] | Operaton `impl.history.*` | adapter on engine internals |
| Engine audit default | `sangam-engine/.../SangamEngineComponents.java:56-57` [V]: when no sink is given, the default is the real `CoreAuditChainSink` | none | fail-safe default |
| Workflow state | `sangam-programme/.../ProgrammeService.java` (explicit transition switch, optimistic guard) [S]. `sangam-case/.../file/CaseFileService.java` [S]. Pack `CaseState` has **no transition table**; `LifecycleStateDelegate` sets any state, so the BPMN decides [S]. | Operaton delegate API in the packs | mixed |

**Delegate coupling.** Domain modules implement Operaton `JavaDelegate`/`TaskListener`/`ExecutionListener` directly [S]:

| Module | Files |
|---|---|
| packs | 29 |
| resources | 9 |
| programme | 7 |
| case | 5 |
| registry | 5 |
| integration | 4 |

Coupling to the engine plugin API is therefore pervasive in the domain modules. The engine port (`sangam-core/.../core/engine/SangamEngineAdapter.java`, "no Operaton type appears here") covers only the REST and worker surface [S].

### 1.2 Guard tests (sources exist; not run)

| Guard | What it fences | Gap |
|---|---|---|
| `sangam-core/src/test/java/org/sangam/core/arch/HostFrameworkBoundaryTest.java:63-66` [V] | 13 host-framework root packages (decision 81) | Scans **source text** only, not poms |
| `HostFrameworkBoundaryTest.java:79-98` [V] | `HOSTS`: the server, the starter, `sangam-quarkus-extension/`, the control plane, the gateway, the rollup, the sangam-rest host area and 3 SDK adapters | **Does not stop business logic in a host.** The whole of `sangam-server/` is a host, so `BillingPaymentPort` passes. |
| `HostFrameworkBoundaryTest.java:104-130` [V] | Shrink-only ratchet of 19 files: field 1, geo 1, registry 7, rest 4, security 6 | — |
| `HostFrameworkBoundaryTest.java:182-190` [V] | core, persistence and engine are never hosts and never on the ratchet | — |
| `ArchitectureGuardTest.java:304-319` [V] | sangam-core main sources and pom have no `org.operaton` | No equivalent for "domain modules use only the public Operaton API". Internal-API use is ledgered in `sangam-engine/PATCHES.md` plus `EnginePatchLedgerGuardTest` [S]. |
| `sangam-core/.../licence/ReactorBannedDependenciesTest.java:67-119` [V] | Enforcer coverage of licence-policy bans, Liquibase 5, gRPC, test-scoped drivers | **No `io.quarkus` rule and no pom-level "no Spring in a non-host" rule.** Both are planned in M-015 sections 5.1-5.2 but not written. |
| `MoneyGuardTest` (`noFloatingPointOnTheMoneyPath`, `moneyColumnsAreIntegerMinorUnits`, `billingRoundsOnlyThroughMoney`) [V names]; `PackMoneyGuardTest` (DMN `typeRef double`) [V] | No float on the money path | `Vars.decimal` accepts `Double` (`sangam-packs/.../core/Vars.java:136-138`) [V]. `Vars.engineValue` turns a fractional `BigDecimal` into `Double` for engine storage (`Vars.java:294-310`) [V]. See U-4. |
| `ProductionPredicateGuardTest` [V name] | One production predicate (`sangam-core/.../security/ProductionMode.java:28`; the default is **false** unless the flag is set or a profile contains "prod") [V] | — |

**Pom fact (corrects one search report).** `sangam-engine/pom.xml:68-77` **excludes** `spring-beans` and `spring-core` from `operaton-engine` [V] (D-38). M-015 section 1.5 says a non-Spring host carries Spring jars "in any case". That holds for the Operaton Quarkus extension's closure, not for `sangam-engine` itself.

**Spring at compile scope in non-host poms** [V]:

| Pom | Spring artifacts |
|---|---|
| `sangam-rest/pom.xml:108-117` | spring-webmvc, spring-jdbc, spring-boot-autoconfigure |
| `sangam-registry/pom.xml:35` | spring-web |
| `sangam-geo/pom.xml:44` | spring-webmvc |
| `sangam-field/pom.xml:49` | spring-webmvc |
| `sangam-security/pom.xml:46-66` | 6 Spring Security / Spring artifacts |

### 1.3 Decision 75 is implemented (fail-closed)

`sangam-rest/.../config/SangamRestAutoConfiguration.java:140-178` [V]:
- `@ConditionalOnMissingBean PermissionPort` falls back to `GroupPermissionAdapter` only after `requireDevelopmentWithoutSecurity`.
- That check refuses a production start whatever the flag says.
- Otherwise it refuses unless `sangam.security.allow-without-security-module=true` or a `dev`/`h2` profile is active.
- It logs ERROR on every start.

`EngineWiring.java:52-54` [V] binds `TenantContext::runAs` only when security is on; the security-off path goes through the same guard [S]. M-015 section 3.3's "silent" finding is therefore closed in code. Its test, `PermissionFallbackFailClosedTest`, has not been run.

---

## 2. Fallback classification

**Classes:**
- **SW (SILENT-WEAKENING):** a control is weakened without refusal or a loud flag.
- **SW-L (latent):** the Spring server always binds the real port today, but any other host, or a module override, would hit the weak default.
- **FC (FAIL-CLOSED):** the operation or the start-up is refused.
- **SAFE:** a convenience, with the limitation stated.

### 2.1 SILENT-WEAKENING (most important)

| ID | Location | Control | Behaviour | Class | Evidence |
|---|---|---|---|---|---|
| SW-1 | `sangam-server/.../bridge/BillingPaymentPort.java:62-64` | Payment confirmation | The connector call completes without `amount` and without `status`, so `s == null` and the **full reported amount is confirmed** and allocated to the ledger | SW | [V] |
| SW-2 | `BillingPaymentPort.java:39,44-46` | Payment verification | Channels `counter,cash` (the default for `sangam.billing.trusted-channels`) are **accepted as reported with no verification**. The channel string comes from connector input (`sangam-billing/.../BillingConnector.java:115`, `r.str("channel")`). There is no production guard and no channel permission. | SW | [V] |
| SW-3 | `sangam-billing/.../BillingService.java:116` | Payment confirmation | `payments == null ? amount` treats "no payment port" as "confirmed". Latent in the server (`BillingWiring.java:72` always binds one); live for any other host. | SW-L | [V] |
| SW-4 | `sangam-registry/.../RegistryPorts.java:41,194-200`; wiring at `sangam-server/.../config/RegistryWiring.java:103-106` | DPDP consent / purpose binding | With `sangam.modules.compliance.enabled=false` (`ComplianceWiring.java:70`) or no `SangamCompliance` bean, consent is `PERMIT_ALL`. There is no log. `PERMIT_ALL` is not a `DevelopmentOnly` port, so `RegistryModule.java:72-77` does not refuse it (it does refuse the ephemeral signer and vault). | SW | [V] |
| SW-5 | `sangam-integration/.../connector/gov/EsignConnector.java:216-222,281-297` | eSign signature | A `status=1` answer with any non-empty `DocSignature` becomes `Success`. The signature is never verified against `UserX509Certificate` or the document hash, and the ESP response signature is not checked. No downstream verifier exists: `MeetingService.java:598` stores only `esign_ref`, and no other main source reads `UserX509Certificate`. | SW | [V] |
| SW-6 | `sangam-ledger/.../JdbcLedgerStore.java:79-125` together with `LedgerService.java:248-258` | Audit and accounting continuity | The ledger posting commits on **its own pool connection** (`ds.getConnection()`, `c.commit()`). The DataSource is the raw bean, not the transaction-aware proxy (`SangamRestAutoConfiguration.java:86`, `LedgerWiring.java:87`). `LEDGER_POSTED` audit and GL rows are written afterwards through `TxSupport.withConnection` (`LedgerWiring.java:149-186`). If the caller's transaction rolls back or the node crashes between the two, the retry finds a **duplicate** and skips both audit and accounting (`pr.duplicate() ? List.of()`, `if (!pr.duplicate())`). The result is a ledger movement with **no audit row and no GL entry, permanently**. | SW | [V]. The caller transaction context is U-2. |
| SW-7 | `sangam-server/.../config/LedgerWiring.java:163-167` and `:211-218` | GL accounting export | `sangam.ledger.gl-connector` is set but there is no `OutboxWriter` (integration off), so every accounting entry is **dropped** (`return`). With no `PublisherRegistry` or `ConnectorInvoker`, no GL publisher is registered. Neither case logs. | SW | [V] |
| SW-8 | `sangam-ledger/.../LedgerService.java:43-45` | Ledger audit, accounting, breach | Default sinks are no-ops (`e -> { }`). Latent in the server, which always sets them; live for any other host. | SW-L | [V] |
| SW-9 | `sangam-server/.../config/LedgerWiring.java:150-153` | Ledger audit | No `AuditPort` means no `LEDGER_POSTED` (`return`). Latent: the JDBC AuditPort is the default. | SW-L | [V] |
| SW-10 | `sangam-rest/.../web/IdempotencyFilter.java:190-201` | Audit of idempotent replay (X.2.2.7 rule 2) | The audit write fails, so the stored response **is still returned**; only a WARN is logged | SW | [V] |
| SW-11 | `sangam-server/.../config/AuditVerifyWiring.java:56-66` | Signed audit-checkpoint verification | Trusted keys are configured but the AuditPort is not `JdbcAuditAdapter`, so verification is **skipped with a WARN**. Latent. | SW-L | [V] |
| SW-12 | `sangam-server/.../config/OidcConfig.java:81-92`; default at `SangamSecurityProperties.java:146-153` | Token validation | (a) With only `jwk-set-uri`, `JwtValidators.createDefault()` runs, so **`iss` is not checked**. (b) A blank audience **disables the `aud` check**; the Javadoc says so, but there is no production refusal. | SW | [V] |
| SW-13 | `sangam-server/.../bridge/SecurityPermissionBridge.java:121-129` | Deny-wins on the replay re-check | When sangam-security denies READ on the stored resource, `|| groups.has(ctx, commandPermission(...))` still allows the replay for any persona-group holder. This contradicts the class Javadoc (`:28-30`, "sangam-security still allows READ"). | SW (needs owner confirmation of intent) | [V] |
| SW-14 | `sangam-server/.../config/SharedInstanceIsolationWiring.java:43-52` | Tenant isolation (decision 97) | No `AdminPort` means the isolation filter is **disabled with a WARN**. Latent: the default AdminPort exists. | SW-L | [V] |
| SW-15 | `sangam-integration/.../idempotency/IdempotencyService.java:87`; `ReplayAuthorizer.java:18` | Replay authorization | A null authorizer becomes `ALLOW_ALL`. Not registered in the server (`IntegrationWiring.java:111-112`); live for a host that reuses `IdempotencyServletFilter`. | SW-L | [V] |
| SW-16 | `sangam-resources/.../ResourcesContext.java:82,267` | Object-level authorization | The builder default guard is `ALLOW_ALL`. The server's security-off branch goes through decision 75; any other host that does not call `.authorization(...)` gets allow-all. | SW-L | [V] |
| SW-17 | `sangam-integration/.../connector/gov/GovConnectors.java:105-107,170-172`; `PaymentGatewayCallbackVerifier.java:131,142` | Payment-gateway webhook replay | The live path passes `null`, so the replay guard is an **in-memory per-node** set (the Javadoc says this is for tests). A missing `pgCallbackSecretRef` gives a `null` verifier. No main-source consumer of the verifier exists today. | SW-L | [V] |
| SW-18 | `sangam-server/.../config/IntegrationWiring.java:414-418` | DPDP erasure of stored documents | `DocumentEraser.NONE` is the default. No implementation exists in any main source (only `ErasureService` and `LifecyclePorts` reference it). Erasure leaves the documents untouched. | SW | [V] |
| SW-19 | `sangam-server/.../config/MalwareScanWiring.java:30`; `PortsWiring.java:362-368` | Upload malware scan | The default `sangam.malware.scanner=none`, so uploads are stored unscanned and `require-clean` is false. There is no production refusal. (Channels attachments **do** refuse by default; see FC-14.) | SW (whether this is "required" is U-6) | [V] |
| SW-20 | `sangam-server/.../bridge/PacksAuditSink.java:33` | Audit attribution | A blank tenant on a pack audit event is chained under `defaultTenant` | SW (low) | [V] |
| SW-21 | `sangam-packs/.../core/PowerLimitGuardListener.java:38-40,57-58` | Delegated financial powers | A missing `amount` variable means **no ceiling check** (`amount == null || ...`). `allowed == null` is treated as allowed. `DelegationOfPowersService.withinPower` (`:210-212`) has the same null-amount rule, but requires `allowedFlag` and applies the posting cap, which the listener does not. Two implementations, two answers. | SW (divergence) | [V] |

### 2.2 FAIL-CLOSED

| ID | Location | Control | Evidence |
|---|---|---|---|
| FC-1 | `SangamRestAutoConfiguration.java:140-178` | Security module off: refused in production; elsewhere refused unless the dev flag or a dev profile is set; ERROR logged | [V] |
| FC-2 | `EngineWiring.java:52-54` plus the same guard | Job identity binding | [V]/[S] |
| FC-3 | `BillingPaymentPort.java:47-50`, `:66` with `BillingService.java:120-121` | Unconfigured channel or no invoker gives `null`, then "payment not confirmed" | [V] |
| FC-4 | `BillingService.java:117-130` | Currency mismatch, and a receipt replayed with another amount, consumer or ledger, are refused | [V] |
| FC-5 | `JdbcLedgerStore.java:107-121` | Insufficient available or account not OPEN rolls the posting back | [V] |
| FC-6 | `LedgerWiring.java:122-125` | Cross-currency with no `rate-scope` throws (SL-104). A failed rate lookup gives `Optional.empty()`, which leads to refusal, not a guessed rate (`:126-136`) | [V] |
| FC-7 | `RegistryModule.java:68-77` | Ephemeral signer or HMAC vault refused outside dev profiles. **Caveat:** it uses its own profile list, not `ProductionMode` | [V] |
| FC-8 | `EsignConnector.java:89-93,135-150` | A LIVE connector without a request signer is refused; an unbound document hash is refused | [S] |
| FC-9 | `IdempotencyFilter.java:182-187` | Replay re-checks authorization without cache and returns 403 (subject to SW-13) | [V] |
| FC-10 | `SecurityPermissionBridge.java:131-139` | An authorization exception gives `false` (deny). Logged at DEBUG only. | [V] |
| FC-11 | `OidcConfig.java:63-72` | A platform issuer equal to the tenant issuer, or a multi-issuer JWKS, refuses start-up | [V] |
| FC-12 | `SecurityConfig.java:64-68,174-196` | No OIDC and no dev token gives 401 everywhere. The dev token is refused in production. | [S] |
| FC-13 | `SamlWiring.java:26,44-47` | `saml.enabled=true` refuses start-up in this build | [S] |
| FC-14 | `ChannelsWiring.java:190-192` | Attachments refused when no scanner is present (`allow-unscanned=false`) | [S] |
| FC-15 | `SecurityKeysWiring.java:51-60`, `PackageSigningWiring.java:51-70`, `DigiLockerWiring.java:58-66`, `DedicatedTenantWiring.java:76-80`, `EngineWiring.java:64-70` (test mode), `PortsWiring.java:159-165` (debugger), `IntegrationWiring.java:629-633`, `HealthConnectorsWiring.java:107-113` | Production refusals | [S] |
| FC-16 | Webhook verifiers (`WebhookVerifiers.java`, `WeChatCrypto.java`, `EnterpriseConnectors.java:112`), `TokenAuthenticationFilters.java:68`, `SecurityIdentityFilter.java:114-119` | A missing signature, bad token or failed identity resolution is denied | [S] |
| FC-17 | `SangamEngineComponents.java:41-57` | UTC JVM required. The audit sink default is the real chain, not `NONE`. | [V] |

### 2.3 SAFE

| Location | Why it is safe | Evidence |
|---|---|---|
| `SecurityPermissionBridge.java:100-107` | Endpoint gate is groups OR security. It is documented (`:22-27`) that object checks apply deny-wins. It relies on every adapter making object checks; see R-9. | [V] |
| `SangamRestAutoConfiguration.java:128-133` | No `FieldEncryptor` means idempotency bodies are stored in plaintext; the KEK is required when security is on | [S] |
| `SangamRestAutoConfiguration.java:455-463` | `VariableMasking` without a port; `SecurityWiring` always binds one | [S] |
| `InboundTenantGate.java:49-50` | All tenants admitted on non-cell installs (by design) | [S] |
| `AuditCheckpointWiring.java:43` | Signed checkpoints are opt-in. **Owner question U-7** if they should be required in production. | [S] |
| `LedgerWiring.java:101-104` | DMN tenant decision falls back to the shared decision (by design; decision versioning still applies) | [V] |
| `AiWiring.java:126-131` | No security means knowledge retrieval returns nothing (fails empty) | [S] |
| Security-off fallbacks in `IntegrationWiring:375-381`, `ProgrammeWiring:78-82`, `PortsHistoryBridge:145-146`, `RegistryWiring:96-102`, `PacksWiring:141-146`, `ResourcesWiring:114`, `QuotaWiring:112`, `CaseWiring:97` | Reachable only behind FC-1 (dev only, ERROR logged). SAFE for production; a dev install is weak by design. | [S] |

---

## 3. Significant dependencies: boundary and replaceability

Versions are from the root `pom.xml`: Operaton `:71`, Jackson `:74`, Liquibase `:76`, PostgreSQL `:90`, MariaDB `:91`, MSSQL `:92`, Hikari `:93`, H2 `:95`, Kafka `:98`, JMS `:99`, PDFBox `:106`, veraPDF `:108`, OpenSAML `:112`, Spring Boot `:115` [S]. UI versions are from `sangam-ui/package.json:23-28` [V].

### 3.1 Replaceability table

| Dependency (version) | Boundary and public contract | Vendor types or behaviour exposed | Persisted formats and data owner | Replacing it means | Effort | Exit path |
|---|---|---|---|---|---|---|
| **Operaton 2.2.0** (engine, DMN, CMMN, FEEL via the Scala FEEL 1.22.1 closure) | `SangamEngineAdapter` (core port) for REST and workers; `OperatonEngineAdapter`; `SangamProcessEnginePlugin`; 28 `rest/adapter/operaton/*` adapters | `JavaDelegate`, `TaskListener`, `DelegateExecution`, `ProcessEngine` across 10+ domain modules; about 111 main files on `engine.impl.*` (engine 47, flowable-ports 33, server 13) [S]; 34 behaviour patches B-1..B-34 (`sangam-engine/PATCHES.md`) [S] | `ACT_RU_*`/`ACT_HI_*`/`ACT_RE_*` (engine-owned schema, Sangam patches P-1..P-3); 435 BPMN/DMN/CMMN pack files with `xmlns:operaton` and 1,690 `operaton:class` bindings [V]; variables in `sangam-json` (`application/json`) plus `sangam-large-string`, Java serialization off (`SangamProcessEnginePlugin.java:116-117`) [S]; job locks and timers in `ACT_RU_JOB` | Running cases, timers and jobs cannot move to another engine: drain to completion or re-instantiate with a migration map. History stays in `ACT_HI_*`, which needs an export or archive. The Sangam audit chain (`AUDIT_*`) is engine-independent and survives. | **XL** | Keep Operaton and pin it. Fork-readiness is the exit: Apache-2.0, 0 source patches. To reduce lock-in: (1) move rule logic out of listeners into services (R-7); (2) a public-API-only guard for domain modules; (3) a model export of the `operaton:` extensions to a neutral template form; (4) a history archive export. |
| **Spring Boot 4.1.2 / Framework 7.0.10 / Security 7.1.2** | Hosts (server, starter, control plane, gateway, rollup) plus the sangam-rest REST area; `TxSupport` (Spring transactions); OIDC filters in `sangam-security/oidc/*` | Spring in 19 ratchet files; compile-scope Spring in 5 non-host poms (1.2) [V]; `TransactionAwareDataSourceProxy` gives P-1 (one connection) | None owned by Spring. Session and OIDC state are not persisted by Sangam. | Host swap per M-015 phases 1a-6. Running cases are unaffected, because the schema and engine are the same (M-015 section 7). | **L** (M-015: 30-60 engineer-days) | M-015 Option B: `sangam-assembly` plus the Host SPI. Phase 1a (security jar split) first. Option C for 0.1.0. |
| **Jackson 2.22.3** (pinned by the FEEL closure) | Everywhere. sangam-core has a compile dependency. `JsonNode` appears in public signatures of `integration/connector/Json.java:59,70` and `http/Jwt.java:55-65` [S]. | `@JsonValue` on `Money` [V]; `ORDER_MAP_ENTRIES_BY_KEYS` canonical output in `ops/BulkIngest`, `resources/support/Sql` [S] | Engine variable bytes (`JsonValueSerializer`, BigDecimal for floats); `*_json` columns; outbox payloads (caller-supplied strings). **Audit and idempotency hashes use `CanonicalJson`, not Jackson** [S]. | A swap must reproduce the stored variable JSON exactly (numbers as BigDecimal, key order irrelevant). Hashes are unaffected, except the two `ORDER_MAP_ENTRIES` users, whose stored hashes could change. | **M** | Keep it. Move the remaining hash or canonical uses to `CanonicalJson`. Drop `@JsonValue` from `Money` (or keep it as the single accepted annotation). Keep `JsonNode` out of the port signatures. |
| **Liquibase 4.33.0** (4.x until D-7 licence review) | `sangam-persistence/.../SangamSchema.java` (`CommandScope`); 2 MySQL custom changes; `SchemaBootstrap`, control-plane and rollup schema config [S] | `DATABASECHANGELOG`/`LOCK` | Formatted-SQL baselines per dialect (portable); XML/YAML masters; engine patch changesets (`runAlways`) | Another migrator must import the applied history (`DATABASECHANGELOG`) or be baselined at the current version | **M** | Flyway or a home-grown runner over the same SQL files. The baselines are plain SQL. Map the `runAlways` patches. |
| **HikariCP 6.3.0** | 8 files in `sangam-server/.../config` (pool sizing, `IsolationGuard`, read replica) [S] | Hikari MXBean and config types in the host only | None | Host-local | **S** | Agroal/JTA in the Quarkus host (M-015 section 6), or any `DataSource` |
| **JDBC drivers** (pg 42.7.12, mssql 12.10.2, mariadb 3.5.11, h2 2.3.232; ojdbc11 **test scope only**, `sangam-server/pom.xml:271`) | Runtime dependencies of host poms; dialect handling is Sangam's own (`EngineDialect`) [S] | Error-code mapping and the RCSI/isolation checks are per dialect | The database is customer-owned | Per-driver version bump | **S** | Driver swap per database. The Oracle production driver supply is U-3. |
| **Keycloak 26.3.3** (compose only) | Standard OIDC through the Spring resource server; realms in `deploy/compose/keycloak/realm/*.json` [S] | Claim fallback to Keycloak `realm_access.roles` (`SangamSecurityProperties.java:146-147`) [V]; `KeycloakClaimsMapper` (no SDK import) [S] | Identities live in the IdP | Any OIDC IdP with `tenant`/`roles`/`seats` claims | **S** | Claim names are configurable (`SANGAM_SECURITY_OIDC_*`) |
| **OpenSAML 5.2.3** (transitive via `spring-security-saml2-service-provider`, `sangam-server/pom.xml:165`) | `SamlWiring` checks the class by name; plain SAML logic in `sangam-security/saml` (decision 73) [S] | None in Sangam code | None | SAML is not enabled in this build (FC-13) | **S/M** | Keep it behind the Spring host. A Quarkus or other host needs its own SAML SP. |
| **PDFBox 3.0.8** | 6 files in `sangam-integration/.../document/pdf/pdfbox/` behind the renderer [S] | None outside the package | Generated PDFs stored as documents (customer data). PDF/UA tagging behaviour is PDFBox-specific. | New renderer, plus re-validation of the PDF/UA output. Existing PDFs are unaffected. | **M** | OpenPDF or iText (licence review). The port is already isolated. |
| **veraPDF 1.30.4** | **Managed in the root pom (`:108`, `:186-188`) but no module declares or imports it** [S] | — | — | — | — | Remove the managed entry or wire it (decision for the licence lane) |
| **Broker clients** (Kafka 3.9.2, JMS 3.1.0, amqp-client 5.37.1, Paho 1.2.5, jnats 2.26.4, jedis 7.5.3) | Two parallel adapter sets: `sangam-flowable-ports/.../events/channel/*` behind the `ChannelAdapters` SPI (all `<optional>`, class-presence gated); and `sangam-integration` publish/inbox/wake-up [S] | None outside the adapters | Outbox and inbox rows are Sangam-owned. Ordering and dedupe come from `OUTBOX_DELIVERY`/`INBOX`, not the broker. | In-flight outbox rows redeliver to the new broker. Inbox dedupe is keyed by message id, so consumers must keep stable ids. | **S each** (M to merge the two adapter sets) | Already optional per broker |
| **Cloud HTTP** (no AWS/Azure/GCP SDKs) | `integration/connector/HttpTransport.java`; `SecretResolver` port with AWS/Azure/GCP resolvers; own SigV4, SAS and GCP token code; `CloudChannels`; `S3DocumentStore`; Graph, Slack and Bot Framework in channels [S] | Signing protocols are reimplemented in Sangam (a maintenance and security-review cost, not a lock-in) | S3 objects are customer-owned | Per endpoint | **S** | Already SDK-free |
| **bpmn.io UI** (bpmn-js 17.11.1, dmn-js 16.8.2, form-js 1.12.0; no cmmn-js) | 3 components: `Editors.tsx`, `BpmnDiagram.tsx`, `DmnView.tsx` [S] | Templates bind `operaton:topic` and other `operaton:` properties (`sangam-ui/src/modeller/templates.test.ts:17-25`) [V] | Models are saved as BPMN/DMN XML with `operaton:` and `sangam:` extensions (owned by Operaton's schema) | Another modeller must round-trip the `operaton:` and `sangam:` extensions | **M/L** | Keep it. The licence terms (bpmn.io licence, watermark clause) are U-5 for the licence lane. |

### 3.2 Data that outlives any dependency swap

| Data | Owner | Format | Notes |
|---|---|---|---|
| Audit chain (`AUDIT_*`, `AUDIT_PAYLOAD`, checkpoints) | Sangam | SHA-256 over `CanonicalJson`, Ed25519 checkpoints | Independent of the engine and Jackson. This is the strongest exit asset. |
| Ledger (`LDG_ENTRY`, `LDG_BALANCE`) | Sangam | BIGINT minor units; `legs_json`/`info_json` | Engine-independent |
| Idempotency, outbox, inbox, `EXEC_*` leases | Sangam | Relational | Host-independent (M-015 section 7) |
| `ACT_*` runtime and history, deployments (BPMN XML) | Operaton | Engine schema | Moving them needs the engine, or a drain-and-archive |
| Process variables | Operaton tables, Sangam serializer | `sangam-json` | Readable JSON; a swap must keep the serializer name |

---

## 4. Cross-host equivalence (what exists)

| Exists | What it proves | Evidence |
|---|---|---|
| `sangam-spring-boot-starter/.../ServerBeanSetEqualityTest.java:84` | The server, the server with the starter, and `SampleHostApplication` produce the **same bean set**. No business operation runs. | [S] |
| `sangam-server/.../acceptance/AbstractAcceptanceIT.java:16-18` plus `AcceptanceDatabase.java:24-65` | The same ITs run per **database** (`-Dsangam.db`), always on the **Spring server** | [S] |
| Module H2 tests (`AuditCheckpointsH2Test`, `PartitionLeaseManagerH2Test`, `LedgerJdbcH2Test`, inbox tests) | Plain-Java behaviour, but **different scenarios** from the ITs, so no equivalence assertion is possible | [S] |
| `sdks/conformance/ac08/run_ac08.py` and `sdks/testing/t18-vectors.json` | Same inputs across 5 SDK languages against one server. **This is the pattern to copy.** | [S] |
| `HostHarness`, `org.sangam.assembly.conformance`, `CapabilitySet`, `CAPABILITY_MISSING` | **Do not exist.** They appear only in M-015 sections 5 and 10. | [S] |

**Gaps against M-015 section 10.2 (all of them):**
- No scenario runs on two hosts.
- No fail-closed-per-required-capability test.
- No balanced/unbalanced ledger posting test tied to an engine step.
- No test that pins and compares the DMN or rule version in a result across hosts. `approvalRuleVersion` is written at `PowerLimitGuardListener.java:62`, but no test compares it.
- No P-1 test on any host other than Spring.
- The security-off refusal is unit-tested on `MockEnvironment` only (`PermissionFallbackFailClosedTest`, `EngineWiringFailClosedTest`, the latter marked "not run").

---

## 5. ASSUMPTIONS

- **A-1.** The "supported hosts" for 0.1.0 are the Spring server and the Spring Boot starter embedding (M-015 section 8, Option C). There is no Quarkus host in 0.1.0.
- **A-2.** The SW-L items are rated latent on the basis that `sangam-server` is today's only full host and binds the real ports. Any embedding that builds the domain services directly (decision 81 thin hosts, tests, SDK samples) inherits the permissive defaults.
- **A-3.** Under India eSign (CCA eSign API), the ASP is expected to verify the ESP response and the returned signature. This is stated as an assumption about the regulatory and technical expectation, not as a legal conclusion. Decision 112 forbids inventing legal conclusions.
- **A-4.** Effort sizes are by judgement: S ≤ 5 days, M 5-15, L 15-40, XL > 40 engineer-days. They use the master-backlog basis cited in M-015 section 8.
- **A-5.** File counts marked [S] came from text search over `src/main/java`. They are approximate (±few) while lanes edit live.

## 6. UNRESOLVED

- **U-1. Is SW-13 intended?** It is unknown whether the persona-group override on the replay re-check is intended. If it is, the class Javadoc is wrong. If it is not, it is an authorization defect. **Owner: W3-D.**
- **U-2. Transaction context of ledger postings.** It is not traced in which transaction `LedgerService.apply` runs when called through `LedgerConnector` or the pack ledger port: an engine command, a relay, or an external task. The severity of SW-6 depends on it. In every context, the duplicate-skip loses audit and GL if the second step fails.
- **U-3. Oracle production driver.** `ojdbc11` is test scope only. How production gets the Oracle driver (operator-supplied, licence terms) is a question for the licence lane.
- **U-4. Money in `Double` engine variables.** It is not established which pack paths call `Vars.engineValue` with a fractional money value. Such a value would be stored as `DOUBLE_` in `ACT_RU_VARIABLE`. `MoneyGuardTest` does not cover this conversion.
- **U-5. bpmn.io licence.** The licence terms and the watermark obligation for bpmn-js, dmn-js and form-js are for the licence lane (decision 112). They are not assessed here.
- **U-6. Malware scanning.** Is it a REQUIRED control in production (SW-19)? This needs an owner decision.
- **U-7. Signed audit checkpoints and GL export.** Should these be required in production profiles?
- **U-8. `BillingConnector` actor.** `BillingConnector.java:115-116` takes `actor` from connector input (`r.str("actor")`), which then goes into receipts and audit. It is not traced whether the invoker overrides it with the authenticated principal.
- **U-9. Delegate coupling.** The 29/9/7/5/5/4 counts of `JavaDelegate`/`DelegateExecution` users are from search. A per-class split of "rule versus glue" is not done.

---

## 7. Prioritised remediation

Ordered by decision 54R priority: correctness, then authorization, money, audit. Each item names its owner modules and acceptance criteria. None of these is implemented by this lane.

| # | Item | Modules | Acceptance criteria |
|---|---|---|---|
| **R-1** | **Payment confirmation fails closed and leaves the host.** Move the confirmation rules into `sangam-billing` as a plain-Java `PaymentConfirmationPolicy`. A missing `status` and `amount` means NOT confirmed. Trusted channels are empty by default, refused in production unless explicitly configured, and need a channel permission plus an audit event `PAYMENT_ACCEPTED_UNVERIFIED`. `BillingService` with `payments == null` refuses. | sangam-billing, sangam-server (`BillingPaymentPort` shrinks to config binding) | Unit tests: an empty connector output gives "payment not confirmed". Production start with `trusted-channels` set and no explicit acknowledgement is refused. `BillingService(…, null)` gives `collect` refused. `BillingPaymentPort` holds no conditional business logic (review). |
| **R-2** | **Ledger posting, audit and GL become atomic, or a repair path exists.** Either post on the caller's connection (the P-1 pattern, using `Transactions`), or record pending audit and accounting in the same `LDG_ENTRY` transaction (an outbox row written by `JdbcLedgerStore.post`) and emit it on duplicate when it is missing. | sangam-ledger, sangam-server (`LedgerWiring`) | IT: inject a failure after the ledger commit and before the audit, then retry. Exactly one `LEDGER_POSTED` audit row and one GL outbox row exist. Run on H2 plus PostgreSQL, then Oracle and SQL Server. |
| **R-3** | **GL connector without integration is refused.** `sangam.ledger.gl-connector` set with no `OutboxWriter` or publisher fails start-up. | sangam-server | Start-up test: gl-connector on and integration off gives `IllegalStateException`. No `return` on a null writer in the accounting sink. |
| **R-4** | **Registry consent fails closed.** Replace the `PERMIT_ALL` default with `DENY_ALL`, or mark it `DevelopmentOnly` so `RegistryModule` refuses it outside dev. Make `RegistryModule` use `ProductionMode`. | sangam-registry, sangam-server (`RegistryWiring`) | `RegistryPorts.defaults().developmentOnlyReasons()` names consent. A non-dev start with compliance off is refused. A consent check without a port gives a refusal. |
| **R-5** | **eSign response verification.** Verify the `DocSignature` against `UserX509Certificate` and the bound document hash, verify the ESP response signature, and validate the certificate chain against the configured CCA trust anchors. An unverifiable response gives `OutcomeUnknown` or a business failure, never `Success`. | sangam-integration (`connector/gov`) | Tests with a valid response, a tampered signature, a wrong hash, and an untrusted chain: only the valid one succeeds. The simulator signs with a test CA. |
| **R-6** | **Permissive defaults in plain-Java modules become fail-closed.** Cover `ReplayAuthorizer.ALLOW_ALL`, `ResourcesContext.Guard.ALLOW_ALL`, the no-op `LedgerService` sinks, `RegistryPorts` consent and visibility, and `BillingService` payments. Each default either refuses or is a `DevelopmentOnly` marker checked at assembly. This is the precondition for decision 81 thin hosts and the input to M-015's `CapabilitySet`. | sangam-integration, sangam-resources, sangam-ledger, sangam-registry, sangam-billing; later sangam-assembly | A new guard test, `PermissiveDefaultGuardTest` (core arch), bans `ALLOW_ALL`/`PERMIT_ALL`/no-op lambdas as a constructor or builder default for ports in a controls list (authz, audit, accounting, payment, consent), with a shrink-only ratchet. Each module test asserts that the default refuses. |
| **R-7** | **One power-limit rule.** `PowerLimitGuardListener` delegates to `DelegationOfPowersService` (or a shared plain-Java evaluator) with one DMN output contract and the posting cap. A missing `amount` on a monetary case type is refused. | sangam-packs, sangam-security | Shared test vectors: the listener and the service give identical allow/deny over band boundaries, null amount, `allowed` absent, and the posting cap. |
| **R-8** | **OIDC validation is complete in production.** Refuse start-up in production when the issuer is unset (JWKS-only) or the audience is blank. | sangam-server (`OidcConfig`) | Start-up tests: production with JWKS only is refused; production with a blank audience is refused; non-production logs WARN. |
| **R-9** | **Replay deny-wins (SW-13).** After U-1 is answered, remove the `|| groups.has(...)` override or document it. Also audit audit-write failure on replay: refuse the replay (or return 503) when its audit row cannot be written (SW-10). | sangam-server, sangam-rest | Test: an object-level deny on the resource plus a persona group gives 403 on replay. A failing `AuditPort` gives no stored body returned. |
| **R-10** | **Latent warn-only controls are refused when configured.** Cover SW-11 (checkpoint keys with a non-JDBC audit port), SW-14 (shared-instance isolation without `AdminPort`), SW-17 (a cluster-wide `JdbcReplayGuard` for payment-gateway callbacks, as in `HealthConnectorsWiring`), and SW-18 (a `DocumentEraser` implementation for `ContentService`, or refuse erasure requests that include documents). | sangam-server, sangam-integration | One start-up or behaviour test per item. No `LOG.warn`-and-continue remains on these paths. |
| **R-11** | **The boundary guard also fences business logic out of hosts.** Add a host-thinness rule: no `Money`/`BigDecimal` arithmetic, no `compareTo` on amounts, and no SQL in `sangam-server/**/bridge` and `config`, with a ratchet. Add the pom-level rule (no Spring or Quarkus at compile scope in non-host poms) and the `io.quarkus` ban to `ReactorBannedDependenciesTest`. | sangam-core tests, root pom | Guard sources written. The ratchet lists today's offenders (`BillingPaymentPort`, `LedgerWiring` lambdas). A new offender fails. |
| **R-12** | **Seed of the host conformance kit.** Start the M-015 section 10.2 kit as a host-neutral scenario set run first by the Spring server and by a plain-Java harness: money, P-1, deny-wins, audit tamper, idempotent replay, inbox dedupe, job fencing, and fail-closed per required capability. Copy the AC-08 vector pattern. | new test-jar (later `sangam-assembly`), sangam-server tests | The same scenario classes and vectors run on 2 harnesses. Each result includes the rule and DMN version, and the results are compared. Removing a required capability makes the scenario refuse (asserted). |
| **R-13** | **Operaton exit readiness (backlog).** (a) A guard that domain modules use only `org.operaton.bpm.engine.delegate.*` and the public API; `impl.*` is allowed in engine, flowable-ports, security/assignment and server bridges with a ratchet. (b) An `ACT_HI_*` history-archive export. (c) A documented model-extension inventory (`operaton:*` attributes in use). | sangam-core tests, sangam-engine, docs | Guard plus ratchet written. Export tool spec. Inventory generated from pack resources. |
| **R-14** | **Money type hygiene.** Resolve U-4 (no fractional `Double` engine variables for money) and remove or justify `@JsonValue` on `Money`, for a framework-free core. | sangam-packs, sangam-core | `MoneyGuardTest` extended to `Vars.engineValue`. Core `Money` has no Jackson annotation, or there is a documented exception. |
| **R-15** | **Dead and duplicate dependency entries.** Decide on veraPDF (managed, unused) and merge the two broker adapter sets. | root pom, sangam-flowable-ports, sangam-integration | The SBOM shows no managed-but-unused entries. One adapter per broker family. |

**For 0.1.0 (correctness, money, authz, audit first):** R-1, R-2, R-3, R-4, R-5, R-7, R-8 and R-9 are release-relevant. R-6 and R-11 are the structural fix that keeps these from recurring under decision 81. R-12 and R-13 are phased with M-015 (M-137..M-144).
