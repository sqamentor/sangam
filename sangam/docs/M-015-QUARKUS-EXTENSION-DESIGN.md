<!-- SPDX-License-Identifier: Apache-2.0 -->
# M-015: framework-independent product layer and host adapters (Option B architecture)

Status: **architecture document; no adapter code**. Lane W3-H (B49), 2026-11-12.

This document follows the owner's architecture directive of 2026-11-12, which replaces decisions 54 and 55:

- **Target.** Option B, delivered in phases: a small plain-Java assembly layer, with Spring and Quarkus as thin, replaceable host adapters. There is no duplicated wiring and no custom DI framework. The standalone Spring product stays the supported baseline.
- **Native.** JVM only at first. Native support needs its own build, dependency and runtime evidence.
- **Release fallback.** If Quarkus would delay 0.1.0, use Option C for 0.1.0 and keep a phased backlog.
- **Option A.** Allowed only as an explicitly bounded "engine SDK" that is never labelled full Sangam support.
- **Fail closed.** A missing required capability must block the affected operations. It must never silently downgrade them.

The Wave 3 rule: **no extraction of the `*Wiring` classes in Wave 3.** Other lanes are editing them now, and the integrator schedules the phases below.

**Decision 81 (M-144).** Quarkus is only one possible outer host. Any later framework (Micronaut, Helidon or another) must be a thin adapter that needs very little code and leaves the core untouched. The design is therefore host-neutral: section 10 defines the **Host SPI** (the seams every host provides), the **host conformance kit** and a worked third-host example. Wherever this document says "the Quarkus host", the same rules apply to any other outer host.

Owner decisions 72-75 (2026-11-12, after the first version of this document) answered Q-W3H-3 and Q-W3H-4 (section 9).

## 0. What exists for M-015 in the tree

| Item | State |
|---|---|
| `sangam-quarkus-extension` module | **Not created.** No Option A source was written, so nothing had to be deleted. |
| Root `pom.xml`, `sangam-bom` | Unchanged. |
| This document | Rewritten (Option B). |
| `sangam-core/src/test/java/org/sangam/core/arch/HostFrameworkBoundaryTest.java` | Host-framework boundary guard with a shrink-only ratchet (section 5): hosts per decisions 72 and 74, banned packages per decision 81. |
| Downloads | **POM files only**, resolved by `dependency:tree` (0 jars). Logged in `sangam/docs/records/DOWNLOADS-WAVE3-2026-11-12.md`. |

## 1. Evidence base

### 1.1 Spring imports outside the hosts

Spring and Quarkus references on code lines in main sources, outside `sangam-server` and the starter, on the live tree on 2026-11-12:

| Module | Files | What they are |
|---|---|---|
| sangam-rest | 92 | MVC controllers, `SangamRestAutoConfiguration`, `TxSupport` (Spring transactions) |
| sangam-control-plane / sangam-gateway / sangam-rollup | 15 / 6 / 9 | Separate Spring Boot applications (SCALE). See Q-W3H-3. |
| sangam-security | 7 | 6 in `security/oidc/*`, plus `security/saml/SamlProperties.java`, added today by W3-D (M-076, in progress) |
| sangam-registry | 7 | `registry/rest/*` controllers |
| sangam-geo / sangam-field | 1 / 1 | One controller each |
| core, persistence, engine, integration, flowable-ports, packs, compliance, config, ledger, billing, case, programme, resources, channels, ops, modeller, onboarding, ai, connectors-health | **0** | Plain Java already |

**After decisions 72-74 (recount on the live tree, 2026-11-12T12:4xZ).** The REST host area of sangam-rest (88 files), the control plane, the gateway and the rollup are hosts now; `SamlProperties.java` no longer names Spring (decision 73 moves the SAML Spring filter chain into sangam-server). **19 files remain on the ratchet:** sangam-rest 4 (`TxSupport`, `ReadTransactions`, `OperatonOpsAdapter`, `JdbcAuditExportAdapter`), sangam-security 6 (`oidc/*`), sangam-registry 7, sangam-geo 1, sangam-field 1. The decision 81 ban on Micronaut, Helidon, CDI, JSR-330, Guice, Dropwizard, JAX-RS and Jersey added none: CDI and JSR-330 are used nowhere in the reactor, and JAX-RS and Jersey only in `sangam-server/enginerest` (a host).

### 1.2 The wiring

- **Size.** `sangam-server/.../config` had 38 `*Wiring` classes at checkpoint 05c89814. On the live tree today there are 43 of 64 files, because Wave 3 lanes are adding wiring. Across the folder there are 293 `@Bean`, 210 `ObjectProvider` uses (155 `ifAvailable`/`getIfAvailable`/`stream` decisions) and 55 `@ConditionalOnProperty`.
- **Business decisions inside Spring methods:**
  - `EngineWiring.sangamEngineSettings`, `EngineWiring.java:37-54`: settings derived from properties, the security module flag and tracing.
  - `EngineConfigurationCustomizer.java:59-71`: the **engine plugin order**.
  - `EngineConfigurationCustomizer.java:113-120`: the module ports handed to the engine.
  - `SecurityWiring.java:77-89`: `SangamSecurity` built from the dialect, options and denial pool.

### 1.3 Transaction integration (P-1)

- The engine and the product rows (audit chain, ledger, idempotency) commit **in one connection**. In the server this works because the Spring engine configuration wraps the DataSource in a `TransactionAwareDataSourceProxy` (`EngineConfigurationCustomizer.java:37-42`).
- `TxSupport` (`sangam-rest/.../support/TxSupport.java:12-15`) is Spring transaction code.
- The core already has a host-neutral `Transactions` interface (`sangam-core/.../layer/Transactions.java:17`).
- Operaton's Quarkus extension needs Agroal and Narayana JTA (section 6), so a Quarkus host shares the connection through **JTA**, not through Spring.

### 1.4 Jakarta level and version pins

| | Spring Boot 4.1.2 | Quarkus 3.40.1.1 (`io.quarkus:quarkus-bom`) |
|---|---|---|
| Servlet / JAX-RS | 6.1.0 / 4.0.0 (EE 11) | 6.0.0 / 3.1.0 (EE 10) |
| Spring compatibility APIs | Spring 7.0.10, Security 7.1.2 | `quarkus-spring-*-api` 6.2.SP3, Boot API 3.4, Security API 6.4 |
| jackson-databind / jackson-module-scala | Sangam pins 2.22.3 | 2.21.7 / 2.21.7 |
| scala-library | Sangam pins 2.13.18 | 2.13.16 |
| jakarta.el-api | Operaton needs 6.0.1 | 6.0.1 |
| liquibase-core | 4.33.0 (LIC-7) | 4.33.0 |

### 1.5 Operaton's engine depends on Spring jars

Operaton's engine itself depends on `org.springframework:spring-beans` and `spring-core` 7.0.10 at compile scope, inside `operaton-engine:2.2.0` (dependency tree, section 6). A Quarkus host therefore carries Spring **jars** in any case. The boundary in this document is about **Sangam code** naming a framework, not about jars on the class path.

## 2. (a) The assembly-layer boundary

### 2.1 New module `sangam-assembly`

- **Imports.** Plain Java. It imports neither Spring nor Quarkus; `HostFrameworkBoundaryTest` enforces this, and the module is not a host.
- **Dependencies.** sangam-core, sangam-engine and the domain modules it assembles.
- **What it holds:**
  - **Settings readers.** Pure functions from a host-neutral `SangamProperties` view (`String get(String key)`, typed getters with defaults) to the existing settings objects. Example: `EngineAssembly.settings(props, capabilities)`, which takes over `EngineWiring.java:37-54`.
  - **Constructors and composition.** "Given these collaborators, build X", for example `SecurityAssembly.security(ds, tx, options)` and `EngineAssembly.plugins(available)`. The plugin **order** list moves here from `EngineConfigurationCustomizer.java:59-71`.
  - **Capability decisions.** Every `ObjectProvider.ifAvailable` and `@ConditionalOnProperty` becomes an explicit `Capability` value (section 3). The assembly resolves a `CapabilitySet` once at start-up from the properties and the collaborators the host supplied. It then **refuses** (fail-closed) or **degrades only conveniences**, and it says which.
  - **Lifecycle descriptors.** Each long-running component (relay, drainer, schedulers, the long-poll pool, about 70 hooks in 32 files today) is described as `Lifecycle { start(); stop(Duration drain); phase }`. The host maps it to `SmartLifecycle` (Spring) or `StartupEvent`/`ShutdownEvent` (Quarkus). Start and stop **order** is decided in the assembly, not in the host.

### 2.2 Host-specific code that stays in the hosts

These are thin adapters, one per framework:

- **Bean registration.** Spring: `@Configuration` classes whose `@Bean` methods are one line each, `return XAssembly.x(...)`. Quarkus: CDI producers of the same shape. Bean names are kept, which preserves the `ServerBeanSetEqualityTest` contract.
- **Property source binding.** Spring `Environment`, or SmallRye `Config`, wrapped as `SangamProperties`.
- **Transaction binding.** Spring `PlatformTransactionManager` plus `TransactionAwareDataSourceProxy`, or a Quarkus JTA `TransactionManager` plus Agroal. Both are exposed through the core `Transactions` interface. `TxSupport`'s Spring code stays in the Spring host.
- **HTTP.** The MVC controllers stay Spring-only. **Owner decision 278:** in 0.1.0 the Quarkus host offers the ports and the engine only, with no REST controllers and no OIDC over HTTP; the capability catalogue marks `http` and `oidc-http` as Spring-only (`Capabilities.SPRING_ONLY`). A contract-generated JAX-RS binding would be a later, second HTTP binding.
- **Security front end.** OIDC filters, `SecurityFilterChain`, Quarkus OIDC.
- **Host checks.** The Boot line check, the start-up UTC trigger (the core check itself is `EngineTimeZone`), and the defaults property source.

### 2.3 How the Spring idioms translate

| Spring idiom today | Assembly-layer form |
|---|---|
| `@ConditionalOnProperty("sangam.modules.x.enabled")` | `ModuleSwitch.enabled(props, "x", default)`. The host registers the adapter only when it is true; the decision and its default live once in the assembly. |
| `ObjectProvider<T>.ifAvailable(...)` for an **optional** collaborator | `Optional<T>` parameter plus a capability marked CONVENIENCE |
| `ObjectProvider<T>` for a collaborator that is **required when a feature is on** | The capability is REQUIRED. If it is missing, start-up fails, or the operation refuses with `CAPABILITY_MISSING`. |
| `@ConditionalOnMissingBean` default adapters (`SangamRestAutoConfiguration`) | **Not allowed for required capabilities** (see 3.3). Defaults only for conveniences. |
| `BeanPostProcessor` engine customisation | `EngineAssembly.configure(cfg, collaborators)`, called by each host's engine hook |
| `@ConfigurationProperties` | Typed settings records, read by the assembly |

## 3. (b) Capability matrix and fail-closed behaviour

### 3.1 Matrix

R = required (fail-closed when missing); C = convenience (may be absent and is reported).

| Capability | Spring host (server, starter) | Quarkus host (future) | Kind | When missing |
|---|---|---|---|---|
| UTC JVM (ADR-D-27) | `EngineTimeZoneEnvironmentPostProcessor` plus the `SangamEngineConfigurer` constructor | runtime-init recorder plus the same constructor | R | Start-up refused (already the case in the core) |
| READ COMMITTED and DB start-up checks (`IsolationGuard`, `DatabaseGuard`, SQL Server RCSI) | yes | yes, through `SangamEngineConfigurer.prepare` | R | Start-up refused |
| Schema before engine (Liquibase 4.x) | `SchemaBootstrap` | the assembly's schema step | R | Start-up refused |
| One transaction for engine plus product rows (P-1) | Spring transactions plus the proxy | JTA (Narayana) plus Agroal: **new adapter, unproven** | R | **Every command that writes product rows refused**; never two transactions |
| Tenant and seat engine plugin (`SangamSecurityEnginePlugin`) and job identity binding | yes | yes, the same class | R | Start-up refused when any tenant-bearing module is on |
| Authorization (deny-wins policy, `SangamSecurity`) | yes | yes, the same class | R | Operations needing a permission refused (403/503). **No group-based fallback** (3.3). |
| Audit chain and single outbox writer (integration plugin, `sangam.history.outbox-handler`) | yes | yes | R | Start-up refused |
| Idempotency store (`IDEMPOTENCY_RECORD`) | yes | yes | R for every idempotent command | Commands carrying `Idempotency-Key` refused |
| Outbox relay and inbox dedupe (`OUTBOX*`, `INBOX`) | yes | yes | R when integration is on | Publish and inbound refused, not dropped |
| Job partition leases and fencing (`EXEC_NODE/PARTITION/SLOT`) | yes | yes | R when the job executor is on | Job executor refused to start |
| FEEL / Jackson 2.22 (`JacksonCompatibilityGuard`) | yes | build step plus runtime check | R | Build or start-up refused |
| Money and ledger invariants (opt-in modules) | yes | yes, plain Java | R when the module is on | Module refused |
| REST API, OpenAPI | yes | **no** in 0.1.0 (decision 278: Spring-only) | C | Not offered |
| OIDC resource server, API tokens over HTTP | yes | **no** in 0.1.0 (decision 278: Spring-only) | C (HTTP only) | No HTTP surface; the Java API still authorizes |
| Metrics (Micrometer), tracing (OTel) | yes | Quarkus equivalents | C | Off, logged |
| Defaults property source | `sangam-defaults.yaml` | the same file as a SmallRye source | C | Host config only |
| Async long-poll, actuator, webapp | yes | no | C | Not offered |

### 3.2 The security-plugin inconsistency, resolved

**The inconsistency.** The previous version of this document did two contradictory things. It listed `SangamSecurityEnginePlugin` among the "Spring-free engine plugins" a Quarkus host could use, and it also said an Option A host "gets no security plugin".

**The facts:**

- **The plugin class is Spring-free.** `sangam-security/.../assignment/SangamSecurityEnginePlugin.java:4-11` imports only Operaton (`AbstractProcessEnginePlugin`, `ProcessEngineConfigurationImpl`, `BpmnParseListener`) and Sangam (`SangamSecurity`, `SangamTenantIdProvider`). Its only constructor takes a `SangamSecurity` (`:27`).
- **`SangamSecurity` is Spring-free.** It has no `org.springframework` import. It is built in `SecurityWiring.java:77-89` from the DataSource dialect, `Transactions` (core) and options. That construction logic is assembly material.
- **The module jar is not Spring-free.** In `sangam-security/pom.xml:45-66`, `spring-security-config`, `spring-security-web`, `spring-security-oauth2-resource-server`, `spring-security-oauth2-jose`, `spring-context` and `spring-web` are **compile** scope.
  - Six of the 7 Spring files are in `security/oidc/*`. The seventh, `security/saml/SamlProperties.java`, was added today by W3-D for M-076; by decision 73 the SAML Spring filter chain lives in sangam-server and the SAML logic stays plain Java, and on the live tree at 12:4xZ `SamlProperties.java` no longer names Spring.
  - No class outside `oidc` imports `org.sangam.security.oidc`, as checked by grep over `sangam-security/src/main/java` at 11:5x UTC.
- **What the plugin protects.** It holds the tenant id provider and seat assignment, both authorization- and tenant-critical.

**The resolution.**

- **Classification.** The security engine plugin is a **REQUIRED** capability for every host that runs tenant-bearing processes. It is never a convenience. An engine-only host without it would be the silent downgrade the directive forbids.
- **Phase 1 prerequisite (phase 1a).** Split `security/oidc/*` and the Spring-bound part of `security/saml/*` into their own Spring host-adapter module, `sangam-security-spring`, which `sangam-server` depends on. `sangam-security` keeps only plain Java, with no Spring dependency in its pom.
- **The split is mechanical:**
  - same package names;
  - classes move between jars only;
  - a CHANGE to W3-D (owner of `sangam-security/**`) plus a REQUEST to the integrator for the reactor.
  - Until the split lands, a non-Spring host **cannot** depend on sangam-security without the Spring Security jars. That is one more reason the Quarkus host is not a 0.1.0 deliverable.

### 3.3 Silent downgrades in today's Spring server

These are findings for the integrator. They are not fixed here; the files belong to other lanes.

- **Fallback to a development permission port.** `sangam.modules.security.enabled=false` (`SecurityWiring.java:61`) removes `SangamSecurity` and its engine plugin. `PermissionPort` then falls back to `GroupPermissionAdapter` (`SangamRestAutoConfiguration.java:136-139`), whose Javadoc calls it a "Development default; replaced by sangam-security". The fallback is silent.
- **Job identity binding.** `EngineWiring.java:50` then also drops the job identity binding.
- **What the directive requires.** Under the directive this must become either a refusal (production profiles) or an explicitly declared development mode. It must never be a silent change of authorization model. **Decision 75** grants this to W3-D: the switch-off is refused at start-up unless the development flag is set (SecurityWiring, the `SangamRestAutoConfiguration` permission fallback and the `EngineWiring` job identity line), with a test.

## 4. (c) Phased extraction plan

Each phase is small and ships on its own behind the Spring baseline. In every phase the Spring server keeps its bean names and passes `ServerBeanSetEqualityTest`, the shared behavioural tests (section 5) and the four-database ITs before the next phase starts. No phase starts in Wave 3.

| Phase | Scope | Moves into `sangam-assembly` | Exit proof |
|---|---|---|---|
| **1a** | Security jar split | Nothing moves into the assembly. `security/oidc/*` and the Spring part of `security/saml/*` move to `sangam-security-spring`. | sangam-security pom without Spring; boundary ratchet shrinks by 6 (the `oidc/*` entries); server bean set unchanged |
| **1b** | **Engine construction** (the smallest slice) | The `EngineWiring.sangamEngineSettings` logic; the plugin order (`EngineConfigurationCustomizer.java:59-71`); module-port collection (`:113-120`); `SangamProperties`, `Capability`, `CapabilitySet` | `EngineConfigurationCustomizer` becomes about 20 lines over `EngineAssembly`; identical plugin order (a test compares the lists); server bean set unchanged |
| 2 | Security and tenancy | `SangamSecurity` construction, denial-transaction pool choice, identity binding, security bootstrap | Shared security behaviour tests on the Spring host |
| 3 | Integration core | Outbox writer and relay, inbox, audit drainer, idempotency, lifecycle descriptors | Relay, inbox and idempotency behaviour tests; host migration dry run (section 7) |
| 4 | Ports, packs and CMMN | `PortsWiring`, `PacksWiring` (static `PackRuntime`), case plugin | Packs deployment tests |
| 5 | Domain modules, one per sub-phase | Ledger and billing, registry, resources, programme, compliance, and the rest | Module tests; ratchet shrinks |
| 6 | **First non-Spring host** (Quarkus JVM mode, or another host by the same Host SPI), after phases 1-3 | Nothing moves; the host adapter implements the Host SPI seams (section 10) over the assembly | The host conformance kit (section 10.2) green on **both** hosts; Oracle and SQL Server ITs on the new host; licence closure (section 6) |
| 7 | Quarkus native (separate evidence) | Nothing moves | Native build, dependency and runtime evidence |

A bounded **engine SDK** (the Option A shape) may be released after phase 1b only if the owner asks for one. It would be labelled "Sangam engine SDK for Quarkus (not full Sangam support)", with a published capability list. Without phases 1a and 2 it cannot include tenant security, so its capability gate must refuse tenant-bearing deployments.

## 5. (d) Proof plan

1. **No-Quarkus clean build.**
   - `mvnw -o clean verify` of the default reactor passes with **no** `io.quarkus` artifact in `~/.m2`. CI runs it in a job with an empty cache warmed only from the default reactor.
   - The Quarkus host is a separate reactor profile, `-Pquarkus-host`, so the standalone distribution never needs it.
   - `ReactorBannedDependenciesTest` gains a rule: no `io.quarkus` artifact in a non-host module.
2. **Dependency-boundary guards.**
   - **Written now:** `HostFrameworkBoundaryTest` (sangam-core tests, not run). Only the hosts may name a host-framework package on a main-source code line.
     - **Hosts:** `sangam-server/`, `sangam-spring-boot-starter/`, `sangam-quarkus-extension/`; the host applications `sangam-control-plane/`, `sangam-gateway/`, `sangam-rollup/` (decision 74); the REST host area of sangam-rest, listed explicitly (`web/`, `modules/`, `config/`, plus `ComplianceController`, `ComplianceProblemAdvice` and `DeprecationHeadersFilter`; decision 72); the SDK adapters `sdks/java-spring-boot/`, `sdks/python-frameworks/`, `sdks/typescript-nestjs/` (decision 74).
     - **Banned packages** (decision 81): `org.springframework`, `io.quarkus`, `io.micronaut`, `io.helidon`, `jakarta.enterprise`, `javax.enterprise`, `jakarta.inject`, `javax.inject`, `com.google.inject`, `io.dropwizard`, `jakarta.ws.rs`, `javax.ws.rs`, `org.glassfish.jersey`.
   - **Ratchet:** 19 existing files (138 before decisions 72 and 74) are on a set ratchet that only shrinks. A new file fails; a listed file that becomes clean fails until its entry is removed. No re-snapshot at Wave 3 close.
   - `sangam-core`, `sangam-persistence` and `sangam-engine` may never appear. `sangam-assembly` is added to that list when it is created.
   - **Next:** a pom-level guard. No non-host module declares a Spring or Quarkus dependency at compile scope; Operaton's own transitive `spring-core`/`spring-beans` are allowed by name.
3. **Shared behavioural tests.**
   - A host-neutral test kit, `sangam-assembly` test-jar, holds scenario tests: tenant isolation, deny-wins, audit chain continuity, idempotent replay, outbox ordering per root, inbox dedupe, job fencing after a lease loss, ledger balance.
   - Each host runs the same kit through a `HostHarness` SPI: Spring `@SpringBootTest`; Quarkus `@QuarkusTest`; any later host its own harness. This is the host conformance kit of section 10.2.
   - A required capability that is removed must make the matching scenario **refuse**, which is asserted, never pass silently.
4. **Oracle and SQL Server ITs.** The existing Testcontainers ITs run against each host. SQL Server RCSI and Oracle isolation are covered by `DatabaseGuard`. Run under the heavy-build lock, in the test phase.

## 6. Dependency and licence closure (analysis only)

**Method.** Three throwaway probe poms (outside the tree) were resolved online, Maven Central only, with `maven-dependency-plugin:3.8.1:tree`. POMs only; no jar was downloaded. Each artifact's declared licence was classified against `deploy/licence/licence-policy.json`, following the parent chain.

**Probes:**

- runtime: `org.operaton.bpm.quarkus:operaton-bpm-quarkus-engine:2.2.0` plus `io.quarkus:quarkus-arc` under `io.quarkus:quarkus-bom:3.40.1.1`;
- deployment and test: `operaton-bpm-quarkus-engine-deployment:2.2.0`, `quarkus-arc-deployment`, `quarkus-core-deployment`, `quarkus-extension-processor`, `quarkus-junit5(-internal)`, `quarkus-jdbc-h2`;
- build plugin: `io.quarkus:quarkus-extension-maven-plugin:3.40.1.1`.

**Result: 280 distinct artifacts.**

| Licence class | Count |
|---|---|
| Apache-2.0 | 234 |
| EPL-2.0 / EDL-1.0 (Jakarta APIs, JUnit, Sisu, H2 dual MPL/EPL) | 21 |
| BSD (ASM, paranamer, hamcrest, CRaC) | 9 |
| MIT / MIT-0 | 8 |
| UPL-1.0 (GraalVM `nativeimage`, `word`, build-time) | 2 |
| Public Domain (`jboss-transaction-spi`, `aopalliance`) | 2 |
| GPL-2.0 with Classpath exception (`io.smallrye.classfile:jdk-classfile-backport:26`, build-time, via gizmo2 / bootstrap) | 1 |
| CDDL / GPL+CPE (`javax.annotation-api:1.3.2`, test) | 1 |
| **LGPL dual-licensed with Apache-2.0** | **2** |

All classes are on the policy's allowed list, with these caveats:

- **Narayana.** `org.jboss.narayana.jta:narayana-jta:7.3.4.Final` and `narayana-jts-integration:7.3.4.Final` declare **Apache-2.0**. They come in through Operaton's engine extension → `quarkus-agroal` → `quarkus-narayana-jta`. This is not an LGPL problem.
- **LGPL dual-licensed (build time only, never shipped):**
  - `com.github.javaparser:javaparser-core:3.28.2`, "GNU Lesser General Public License / Apache License, Version 2.0", via `quarkus-extension-processor`, an annotation processor;
  - `net.java.dev.jna:jna:5.8.0`, "LGPL, version 2.1 / Apache License v2.0", via `quarkus-devservices-common` → `testcontainers` in the **deployment** closure.
  - `check_licences.py` treats several plain licence entries as alternatives (`check_licences.py:164-165`), so both pass on Apache-2.0.
  - Neither is in a runtime SBOM. The licence is **elected** as Apache-2.0, to be recorded at integration.
- **Pinned exceptions break under the Quarkus BOM.** Under `quarkus-bom`, the FEEL closure resolves `jackson-module-scala_2.13:2.21.7` and `scala-library:2.13.16`. The SG-27 pinned exceptions are `watch=true` for exactly 2.22.3 and 2.13.18 (`licence-policy.json:73`, `:77`), so **the licence check fails** and `JacksonCompatibilityGuard` refuses the engine. A Quarkus host must import `sangam-bom` before the Quarkus BOM. This is a REQUIRED build-time check (section 3).
- **Jakarta level.** Operaton's engine needs `jakarta.el-api` 6.0.1 (EE 11), which the Quarkus BOM also manages, so EL itself does not clash. Servlet and JAX-RS remain EE 10 versus EE 11 (1.4), which is why Sangam's HTTP layer cannot move to Quarkus.
- **Spring jars in a Quarkus host.** These are unavoidable: `operaton-engine` → `spring-beans` and `spring-core` 7.0.10 (Apache-2.0).
- **Not verified offline.** The Operaton Quarkus extension's configuration hook points (how a `ProcessEngineConfigurationImpl` customiser and plugins are supplied) need the extension **jar**. That inspection is a phase 6 prerequisite and is outside this analysis.

## 7. (e) Host-migration procedure for persisted cases and jobs

This applies to moving one database's workload from the Spring host to the Quarkus host, or back. Both hosts run the same schema (Liquibase 4.x changelogs, `SangamSchema`) and the same engine version, so **no data migration happens**; what moves is ownership of in-flight work. One engine per database applies throughout (decision 51 / Q-02 A).

1. **Pre-checks.**
   - Same Sangam, Operaton and changelog versions on both hosts: `DATABASECHANGELOG` max id, and `ACT_GE_PROPERTY` `schema.version`.
   - The target host starts in **standby**: job executor off (`operaton.bpm.job-execution.enabled=false` or its Quarkus key), relay and inbox workers off, HTTP not routed.
2. **Drain the source.**
   - Mark every source node draining: `UPDATE EXEC_NODE SET draining=1 WHERE node_id IN (...)`.
   - Stop routing new API traffic at the edge.
   - Wait until in-flight requests finish (the `IDEMPOTENCY_RECORD` rows in `state` IN_PROGRESS with `owner_node` = a source node reach a final state, or their `claim_expires_at` passes).
3. **Stop the source job executor and lease holders.**
   - Graceful stop. The `Lifecycle.stop(drain)` order (relay, drainer, job executor) is decided by the assembly.
   - Partitions are released: `EXEC_PARTITION.owner_node` set to NULL by the drain, or left to expire at `lease_expires_at`.
   - Operaton job locks (`ACT_RU_JOB.LOCK_OWNER_`, `LOCK_EXP_TIME_`) and external-task locks (`ACT_RU_EXT_TASK.LOCK_EXP_TIME_`) **are not released by hand**. They expire, and the fencing epochs (`claim_epoch`, `owner_epoch`, Sangam lock version on external tasks) make any late completion from the old owner fail.
4. **Outbox and inbox continuity.**
   - Rows are left untouched. `OUTBOX_DELIVERY` rows in flight carry `claim_owner`/`claim_epoch`/`claim_expires_at`; the target relay claims them with a higher epoch after expiry.
   - Per-root ordering (`root_id`, `root_seq`, `ordered_flag`) is preserved because delivery is by root sequence, not by node.
   - `INBOX` dedupe is keyed by (tenant_id, source, message_id), independent of the host, so a redelivered message is still recognised.
5. **Idempotency continuity.** `IDEMPOTENCY_RECORD` is keyed by (tenant_id, command_type, idem_key) with a fingerprint. A client retry on the target host replays the stored response or waits for the claim to expire; it never executes twice.
6. **Start the target.**
   - Verification queries first (step 8).
   - Then enable, in order: relay and inbox, job executor (it acquires `EXEC_PARTITION` leases), and HTTP routing.
7. **Rollback.**
   - Before step 6 completes: restart the source with `draining=0`. Nothing was changed except lease expiry.
   - After step 6: run the same procedure in reverse (drain the target, stop it, start the source).
   - Correctness holds in both directions because ownership is always by lease plus epoch, never by host identity.
8. **Verification queries** (run before the target starts and again after one acquisition cycle):
   - `SELECT COUNT(*) FROM EXEC_PARTITION WHERE owner_node IN (<source nodes>) AND lease_expires_at > CURRENT_TIMESTAMP` → 0 before the target starts.
   - `SELECT COUNT(*) FROM ACT_RU_JOB WHERE LOCK_OWNER_ IN (<source>) AND LOCK_EXP_TIME_ > CURRENT_TIMESTAMP` → 0.
   - `SELECT state, COUNT(*) FROM OUTBOX_DELIVERY GROUP BY state` and `SELECT state, COUNT(*) FROM INBOX GROUP BY state`: the same counts before and after, apart from progress. No row in a new failure state.
   - `SELECT COUNT(*) FROM IDEMPOTENCY_RECORD WHERE state='IN_PROGRESS' AND claim_expires_at > CURRENT_TIMESTAMP` → 0.
   - Audit chain verification (`AuditVerifyWiring` job, or the CLI) is green for every tenant touched.
   - Business smoke: one case per tenant progresses one step; one job per partition executes.

This procedure becomes an IT in phase 6 (H2 plus PostgreSQL first, then Oracle and SQL Server). The SQL above is written for PostgreSQL; the other dialects come from `generate_changelogs.py` conventions at that time.

## 8. (f) 0.1.0 recommendation

**Recommendation: Option C for 0.1.0** (no Quarkus host), plus **phase 1a** (the security jar split) as a 0.1.0 item if the integrator can schedule it after Wave 3's wiring edits. Phase 1b and later go to the phased backlog.

The evidence on the delay:

- **Phase 6 (the first Quarkus host) needs phases 1-3 first.** Phases 1-3 touch about 15 of the 43 shared wiring files, which Wave 3 lanes are editing now. The directive forbids extraction in Wave 3, so the earliest start is Wave 4.
- **New transaction adapter.** The Quarkus host needs a JTA-based adapter for P-1 (1.3), a REQUIRED capability with no Sangam code today. It must pass the four-database ITs. Its integrity risk equals SL-43 / M-003 class work.
- **The Quarkus HTTP surface needs an owner decision** (a contract-generated JAX-RS binding). Without it, the Quarkus host is not full Sangam support.
- **Build pins.** The licence policy and FEEL pins fail under the Quarkus BOM unless `sangam-bom` comes first (section 6), so a host-side build check is needed.
- **Estimate.** Phases 1-3 plus 6 are, by judgement, about 30-60 engineer-days, using the same estimating basis as the master backlog (XL items 15-40 days each). That is on the embedding critical path (M-014 → M-016 → M-018 → M-019, master backlog section 5).

Phase 1a costs little (a mechanical class move), removes a real inconsistency and shrinks the ratchet. Phase 1b is also small, but it edits `EngineWiring` and `EngineConfigurationCustomizer`, which belong to the Wave 3 freeze.

## 9. Requests and questions

- **REQUEST to the integrator:**
  - Schedule phase 1a and 1b as backlog rows (posted as M-141 sub-item text, WAVE3-SYNC-BOARD 2026-11-12T12:3xZ).
  - Root `pom.xml` gets **no** change now: no `sangam-quarkus-extension` or `sangam-assembly` module until a phase starts.
  - When phase 6 starts, the reactor gets a `quarkus-host` profile, not a default module, to keep the no-Quarkus clean build.
- **Finding (3.3):** the silent permission fallback when the security module is off; owners W3-D and the integrator.
- **Q-W3H-3, answered by decision 74:** sangam-control-plane, sangam-gateway, sangam-rollup and the SDK framework-adapter packages are hosts. Done in `HostFrameworkBoundaryTest.HOSTS`.
- **Q-W3H-4, answered by decision 72:** the sangam-rest controllers and Spring configuration classes are the REST host area; the services they call stay framework-free. Done in `HOSTS`, listed by package or file (section 5).

## 10. Host SPI (decision 81, M-144): any outer host

Spring is today's host and Quarkus a possible second one; neither is special. A host is a **thin adapter**: it implements the seams below over the plain-Java assembly layer (`sangam-assembly`, section 2), registers nothing that decides product behaviour, and leaves the core, the domain modules and the assembly untouched. The SPI types live in `sangam-assembly` (package `org.sangam.assembly.host`), are plain Java, and are fenced by `HostFrameworkBoundaryTest` like the rest of the assembly. The sketches below were design only in Wave 3.

**Wave 4 status (W4-F, M-144 and M-150; code and tests written, not run).**

- **The SPI is code** in `sangam-assembly`, package `org.sangam.assembly.host`:
  - `SangamProperties`, `ModuleSwitch`, `Capability`;
  - `Capabilities`, the one catalogue of the 3.1 matrix plus the 10.1 seams;
  - `CapabilitySet`, `CapabilityMissingException` (`CAPABILITY_MISSING`);
  - `Lifecycle`, `SangamRuntime`, `ConnectionBinding`, `SecurityContextBinding`, `HostScheduler`, `HealthCheck`, `HttpBinding`;
  - `HostSeams`, which derives the seam capabilities from what a host actually handed over.
- **The kit is a separate module**, `sangam-host-conformance` (package `org.sangam.host.conformance`), not the `sangam-assembly` test-jar this section first named. The kit needs JUnit at compile scope and the `sangam-rest` ports, and the assembly keeps its dependencies to sangam-core. The kit contains:
  - `HostHarness`;
  - `HostConformanceKit`, with the M-150 equivalence scenarios against `golden-results.properties`;
  - `HostFailClosedKit`.
- **The Spring baseline runs the kit** through `sangam-server/src/test/java/org/sangam/server/host/SpringHostHarness.java`:
  - **Removable now:** the security module (decision 75 refusal).
  - **Gaps:** every other required capability is a declared gap, with the extraction phase that makes it removable. Only the baseline host may have gaps.
- **Phase 1b (M-141b) is code:** `org.sangam.assembly.engine.EngineAssembly` holds the engine settings read from configuration, the module ports, the plugin order (the same 8 classes, compared by `EngineAssemblyTest`) and the engine capability check. `EngineConfigurationCustomizer` and `EngineWiring` call it; the line references in section 1 describe the code before this move.
- **M-141d is done:** the registry and geo Spring controllers moved into the REST host area of `sangam-rest` (`modules/registry/web`, `modules/geo/web`); the unregistered field adapter was removed. Their 9 ratchet entries are gone, and the field, geo and registry modules joined the never-a-host list of `HostFrameworkBoundaryTest` beside `sangam-assembly` and `sangam-host-conformance`.
- **M-141c is written and waiting for its consumer grant:** `TxSupport` and `ReadTransactions` become plain Java with Spring implementations in `sangam-rest` `config/`; the last 4 ratchet entries go when it lands.
- **M-143 (the Quarkus host) is in 0.1.0 as an optional adapter.** Owner decision 186 replaces the earlier deferral to 0.2. The module is `sangam-quarkus-extension`, built only by an opt-in reactor profile.
  - **Seams:** it supplies five: config source, lifecycle, scheduling, security context, and the shared JTA transaction (Narayana and the Agroal data source the engine also holds). Every other required capability is `CAPABILITY_MISSING`, so both the engine build and the start-up refuse.
  - **Closure (D-5, decision 137(2)):** Operaton 2.2.0 with Quarkus 3.40.1.1. Jackson is held at 2.22.3. Spring is excluded. There is no JDBC driver extension and no quarkus-maven-plugin.
  - **Status:** download DONE, licence review ALLOWED, technical compatibility NOT_RUN, deployment approval NOT APPROVED.
  - **Owner decisions 274 to 287 that concern this host (Wave 4, 2026-11-13):**
    - **274:** Quarkus is a full host in 0.1.0. Every module port is assembled with CDI producers over the same plain-Java assembly the Spring configuration calls (reuse, no fork). The full kit must pass, equivalence included, with no gaps and no loosening of the kit.
    - **275:** `jna` is pinned to 5.17.0 in the Quarkus test closure.
    - **276:** W4-F leads extraction phases 1b, 3, 2 and 5, in that order, one phase per round. The Spring configuration files change only by their owners, through a REQUEST per file.
    - **277:** the Spring-free part of `sangam-rest` moves into its own plain module; the proposal (`sangam-rest-core`) is posted contract-first.
    - **278:** the Quarkus host offers the ports and the engine only: no REST controllers and no OIDC over HTTP. `Capabilities.SPRING_ONLY` marks `http` and `oidc-http`.
    - **279:** each Quarkus seam is an optional CDI bean (`QuarkusConfigSource`, `QuarkusLifecycle`, `QuarkusScheduling`, `QuarkusSecurityContext`). Without one, its capability is missing and the start is refused (`HostSeams.Builder.withoutScheduler` removes the standalone fallback). The harness removes a seam with `quarkus.arc.exclude-types`, and runs the `utc-jvm` case in a separate JVM with `-Duser.timezone=Asia/Kolkata`. All 16 required capabilities are removable on Quarkus, with no gaps.
    - **285:** `sangam-rest-core` is approved, package names unchanged, `sangam-rest` depending on it; the move waits for a window with no open claims on the six packages.
    - **286:** `job-leases` is required only while executor partitioning is on; the default is unchanged on both hosts.
    - **287:** the engine area's three removals on Quarkus: (a) jackson-databind 2.21.7 forced into the application; (b) `sangam.engine.apply-product-schema` bound on Quarkus, `false` refused (no host schema step); (c) the database checks also run on the very data source the engine is handed, on every host, refused with the database guard's own message.
  - **Phase 1b on Quarkus (decisions 276, 286, 287) is code:** `EngineAssembly.checkBeforeDatabase` (UTC, Jackson line, schema step, job leases while partitioning is on) and `EngineAssembly.checkDatabase` (the guard on the engine's data source) give `EngineChecks`; a new `requireCapabilities` overload takes them. The Quarkus host leaves `EngineAssembly.ENGINE_AREA` to the engine step and refuses its host gaps with the database-free checks in one refusal before any database access. The Spring host keeps the three-argument check until its owner switches `EngineConfigurationCustomizer` (REQUEST to INT-H2).
  - **Still open:**
    - the phase 3, 2 and 5 capabilities on Quarkus (decision 276);
    - the Spring host's switch to the phase 1b engine checks (INT-H2);
    - the P-1 money scenario on four databases;
    - the M-150 kit run on Quarkus (written, not run).

### 10.1 The seams every host provides

| Seam | What the host supplies | What stays in the assembly or core | Kind |
|---|---|---|---|
| **Lifecycle** | Calls `SangamRuntime.start()` once its own container is up, and `stop(Duration drain)` on shutdown; maps them to its own events (Spring `SmartLifecycle`, Quarkus `StartupEvent`/`ShutdownEvent`, Micronaut `StartupEvent`/`ShutdownEvent`, Helidon SE server start and stop) | Every `Lifecycle` descriptor (relay, drainer, schedulers, long-poll pool), its **phase** and the start and stop **order** (section 2.1); the UTC check (`EngineTimeZone`) and the database checks run inside `start()` | R |
| **Transaction and connection binding** | A `DataSource` and a `ConnectionBinding`: the host's transaction manager exposed as the core `Transactions` (`sangam-core/.../layer/Transactions.java`, `inTransaction(Function<Connection,T>)`), with the guarantee that the engine and the product rows of one command use **the same connection and commit once** (P-1, section 1.3) | `Transactions.of(EngineAdapter)` for engine-bound product rows; `Transactions.of(DataSource)` for the short own-transaction claims (idempotency, relay, inbox, sweepers); the READ COMMITTED and RCSI checks | R |
| **HTTP binding** | Optional. If offered: routes from the published contract (`sangam-api.yaml`), each a one-line call to the sangam-rest ports (`org.sangam.rest.port`), problem responses rendered from `ApiProblem` unchanged | The ports, `RequestContext`, the problem catalogue and codes, idempotency handling (`IDEMPOTENCY_RECORD`) | C (when absent, the host offers no HTTP surface and says so; the Java API still authorizes) |
| **Security-context binding** | Turns the host's authenticated request (token, session, mTLS) into a `SangamPrincipal` and a `RequestContext` per call, and binds them for the call's duration only | Every authorization decision (`SangamSecurity`, deny-wins), tenant resolution, the engine security plugin, job identity binding. The host never decides a permission. | R |
| **Config source** | A `SangamProperties` view over its config system (`String get(String key)` plus typed getters), with `sangam-defaults.yaml` as the lowest source | Every default, every `ModuleSwitch`, every settings record (section 2.3) | R |
| **Scheduling** | A `HostScheduler` (fixed delay, cron, a virtual-thread executor) or nothing, in which case the assembly's own executor runs | **What** runs and how often; cluster correctness comes from `EXEC_PARTITION` leases and fencing epochs, never from the host scheduler, so a host scheduler that fires twice cannot double-execute | R when the job executor, relay or sweepers are on |
| **Health** | Maps each `HealthCheck { name(); check() -> UP or DOWN with details }` to its own endpoint (Spring actuator, MicroProfile Health, Micronaut management, Helidon health) | The checks themselves (database, engine, relay backlog, lease ownership, `CapabilitySet` state) | C (absent: no endpoint; the checks still gate `start()`) |

**The rule for every seam.** The host passes collaborators in; the assembly resolves the `CapabilitySet` once (section 3). A REQUIRED seam that is missing **refuses** start-up or the affected operations with `CAPABILITY_MISSING`; it never falls back to a weaker behaviour. A host may not add a default for a REQUIRED seam (the `@ConditionalOnMissingBean` rule of section 2.3 applies to every host).

**What a host may not contain.** Business rules, permission checks, money arithmetic, audit writes, idempotency or dedupe logic, retry or ordering policies, SQL. If an adapter needs one of these, the assembly is missing an entry point, and that is fixed in the assembly, not in the host.

### 10.2 Host conformance kit

One host-neutral test kit, shipped as the `sangam-assembly` test-jar (`org.sangam.assembly.conformance`), which every host must pass before it is supported. It is the shared behavioural suite of section 5.3, made the entry gate for a host.

- **Harness SPI.** `HostHarness { start(Map<String,String> props); stop(); Transactions transactions(); Optional<HttpBinding> http(); void as(SangamPrincipal p, Runnable call); void withoutCapability(Capability c); }`. Each host provides one implementation in its own test sources: Spring `@SpringBootTest`, Quarkus `@QuarkusTest`, any later host its own.
- **Scenarios, each run on every host and on H2 plus PostgreSQL, then Oracle and SQL Server:**

| Area | Scenario | Pass condition |
|---|---|---|
| Money | A balanced ledger posting commits with the process step that caused it; an unbalanced one is refused; a billing period is charged once | One commit, balances equal, no second charge on replay |
| Money | Engine step and product rows in one transaction, with a failure injected after the product write | Both roll back together (P-1), never one of them |
| Authorization | Deny-wins policy; cross-tenant read and write | Denied (403), audited; no tenant sees another's data |
| Authorization | Security module switched off without the development flag | Start-up refused (decision 75); never the group-permission fallback |
| Audit | Chain continuity over a run of commands; tamper with one row | Verification green, then red at the tampered row |
| Duplicate prevention | Idempotent command replayed with the same key; with the same key and another body | The stored response replayed; the second refused (fingerprint mismatch) |
| Duplicate prevention | Inbound message delivered twice | Processed once (`INBOX` dedupe) |
| Duplicate prevention | Outbox delivery with the relay restarted mid-batch, on the same and on another node | Delivered once per root sequence, order kept |
| Duplicate prevention | Job lease lost to another node, the old owner completes late | The late completion fails on the fencing epoch; the job runs once |
| Fail-closed | For **every** REQUIRED row of the matrix in 3.1: `withoutCapability(c)` | Start-up refused, or the matching operation refused with `CAPABILITY_MISSING`; the scenario must not pass silently |
| Boundary | The host's module is listed in `HostFrameworkBoundaryTest.HOSTS`, its framework package in `HOST_FRAMEWORK_PACKAGES`, and no non-host module gains a ratchet entry | Guard green |

- **Proof of thinness.** The kit's report lists the host module's main-source line count and its count of classes per seam. A host that needs product logic to pass a scenario fails review even when the scenario is green.

### 10.3 Worked example: what a third host would need (example only; not built)

Take **Micronaut 4** as the third host (Helidon SE is noted where it differs). Everything below is the full list; nothing in the core, the domain modules or the assembly changes.

1. **Module.** `sangam-micronaut-host` (reactor profile `micronaut-host`, never a default module, so the default build stays free of Micronaut, as in section 5.1). Add `"sangam-micronaut-host/"` to `HostFrameworkBoundaryTest.HOSTS`; `io.micronaut` is already in `HOST_FRAMEWORK_PACKAGES`, so nothing else may import it.
2. **Bean registration.** One `@Factory` class per assembly area (engine, security, integration, ports, modules), each method one line: `return EngineAssembly.engine(props, collaborators);`. Micronaut uses `jakarta.inject` annotations; that is allowed inside the host module only.
3. **Config source.** Wrap Micronaut's `Environment` as `SangamProperties`; add `sangam-defaults.yaml` as the lowest-priority property source.
4. **Lifecycle.** An `ApplicationEventListener` for `StartupEvent` calls `SangamRuntime.start()`, one for `ShutdownEvent` calls `stop(drain)`.
5. **Transaction and connection binding (the only seam with real work).** Operaton has no Micronaut extension, so the host builds the engine through `EngineAssembly` with an external transaction manager (Micronaut Data JDBC transactions or JTA) and supplies a `ConnectionBinding` that hands the engine and the product code the same connection. It must pass the P-1 money scenario of 10.2 on all four databases; until it does, the capability is missing and every command writing product rows is refused.
6. **Security-context binding.** A Micronaut Security `AuthenticationProvider` or filter result is turned into `SangamPrincipal` and `RequestContext`; no permission logic.
7. **HTTP binding (optional).** Either none (capability C, "not offered"), or Micronaut controllers generated from `sangam-api.yaml`, each a one-line call into a sangam-rest port. Netty, not a servlet container, so the Servlet 6.0 versus 6.1 issue of section 1.4 does not arise; the JAX-RS mismatch does not arise either.
8. **Scheduling.** Micronaut `TaskScheduler` behind `HostScheduler`, or none.
9. **Health.** One `HealthIndicator` that delegates to the assembly's `HealthCheck` list.
10. **Conformance.** `MicronautHostHarness` in the module's tests; the whole kit of 10.2 green.
11. **Before any build:** a licence closure like section 6 for the Micronaut artifacts against `licence-policy.json`, the Jackson and Scala pins of section 1.4 checked against Micronaut's BOM, and a download approval. None of this was done here; this example names no versions.

**Helidon SE** differs in two places: it has no dependency injection, so step 2 is plain constructor calls in a `main` (the thinnest possible host); and its HTTP routing replaces Micronaut controllers in step 7. **Helidon MP** is CDI based, so its producers use `jakarta.enterprise`, which the guard allows inside its host module only.

**Expected size.** About one factory or wiring class per assembly area plus one class per seam, by judgement roughly 10-15 small classes with no product logic; the transaction binding of step 5 is the one piece that needs its own integrity review.
