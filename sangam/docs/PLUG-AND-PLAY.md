<!-- SPDX-License-Identifier: Apache-2.0 -->
# Is Sangam plug and play?

**Short answer: not yet, fully.** The design goal is that a government, department or company adopts Sangam by
configuration and data alone, with no Java change (`ACCEPTANCE-PLUG-AND-PLAY.md`, SANGAM.md X.0e, ADR-D-20 to 24).
On the integration tree at 5ba0a7b4 (2026-11-17), that goal holds in code for the Spring Boot 4.1 embedding and for
the standalone server. It does not hold yet for the evidence, the release or one host:
- A production start needs about fifteen settings that nobody can default for you (section 2).
- The Quarkus host refuses every start with `CAPABILITY_MISSING` (section 3.6).
- There is no green sha, no release tag and no published artifact.
- The Oracle and SQL Server results that a government buyer scores are FAIL or NOT_RUN (section 4).

This page answers the question mode by mode. Every claim names its source: a file (relative to `sangam/`), a test, a
register row (`TESTING-GAP-REGISTER.md`) or an owner decision (`docs/records/OWNER-DECISIONS-2026-11-12.md`). How to
wire each mode is in [INTEGRATION-GUIDE.md](INTEGRATION-GUIDE.md); this page does not repeat it. For IFMS
specifically, see [IFMS-INTEGRATION-GUIDE.md](IFMS-INTEGRATION-GUIDE.md).

**How to read the tables.** "Zero code" means it works by adding a dependency or starting an image. "Config" means
settings only. "Code" means you must write Java (or another language). "Not available" means it does not exist or is
refused today. "Evidence" gives the test and its recorded result: PASS, FAIL or NOT_RUN (written, not run).

## 1. Verdict by mode

| Mode | Zero code | Config | Code | Not available yet | Evidence |
|---|---|---|---|---|---|
| Embedded starter (Spring Boot) | Add `sangam-spring-boot-starter`; `SangamAutoConfiguration` attaches and loads `sangam-defaults.yaml` at the lowest precedence (INTEGRATION-GUIDE section 2) | Datasource `SANGAM_DB_*`; modules `sangam.modules.<name>.enabled`; compliance needs `sangam.compliance.jurisdictions` (no default); schema `SANGAM_LIQUIBASE_MODE=migrate-only` once outside development (decision 334) | Your own `SecurityFilterChain` with a `securityMatcher` and `@Order(1)` for your routes (`sangam-spring-boot-starter/README.md:69-85`); your JDBC driver | Any Spring Boot line other than 4.1.x (refused, `SangamDefaultsEnvironmentPostProcessor.java:130-136`) | `ServerBeanSetEqualityTest`, `HostSchemaAndPrecedenceTest` (unit, H2) |
| Standalone server + REST | Image `deploy/docker/server.Dockerfile`; compose profiles postgres, oracle, sqlserver, mysql (`deploy/README.md:77-86`) | Every production setting in section 2 | None for the server; your client calls `/api/v1` (`sangam-rest/api/sangam-api.yaml`) | `/engine-rest` is not part of the contract (INTEGRATION-GUIDE section 4) | `ProductionStartupFailFastTest` (H2) |
| SDKs and workers | Generated typed clients for Java, TypeScript, Python, .NET, PHP; Tier 2 Go, Kotlin, Rust (`sdks/codegen/generate_sdk_surface.py`) | Service-principal token with `sangam-worker` scope and bound topics (INTEGRATION-GUIDE sections 7-8) | The worker's business logic; the Java `SangamWorker` runtime does fetch, heartbeat, fencing and idempotency for you | Cross-language CI run AC-08 not green (register AC-08; MASTER-PENDING C6) | `ac08` job, `.github/workflows/sdks.yml:366-412`: not green |
| Connectors | Built-in connectors (for example `PfmsFileConnector`, `EsignConnector` in `sangam-integration/.../connector/gov/`) and simulators (`sangam.integration.simulators.enabled`) | Topic binding `sangam.integration.topics`; connector secrets as `secret://` references; each connector's capability class (INTEGRATION-GUIDE section 9) | A new external system needs a `SangamConnector` in a signed plugin jar in `lib-ext/plugins` | Live eSign waits for the owner's ESP sandbox confirmation (decision 387 (6); MASTER-PENDING D11) | Simulators only; live runs NOT_RUN |
| Packs | 147 generated packs ship (`sangam-packs/README.md:2-6`); deployed per tenant by onboarding, at start-up for the listed tenants, or on activation through `/admin/tenants` (`PacksTenantDeployer`); a tenant created by `sangam.security.first-admins.*` gets none from that bootstrap (register E-25) | `sangam.packs.tenants` together with `sangam.packs.deploy-on-start=true` (its default is true only under profile h2), pack selection (`PacksWiring.java`, `sangamPacksDeployOnStart`) | New domain processes are pack data through packgen, never hand-edited (`sangam-packs/tools/packgen/main.py`) | A deployed pack version is immutable; a changed pack needs a new version (decision 390 (4)) | `PackDocumentSlotsTest` (unit); `IfmsPacksIT` FAIL on Oracle and SQL Server (section 4) |
| Onboarding bundles and tenants | `POST /api/v1/admin/tenants`; platform role rows added at every start (decision 370 (1)); signed bundles and promotion (`sangam-onboarding/.../bundle/BundleService.java`, `PromotionService.java`) | Tenant id, profile chain (for example `sangam-packs/src/main/resources/profiles/in/GOI.yaml`), seats, holders | None by design: a scenario that needs a Java change fails (`ACCEPTANCE-PLUG-AND-PLAY.md`) | The plug-and-play suite has not run on the current tree | `PlugAndPlayIT` last ran 2026-11-10 (register APPROVE-401); `PlugAndPlayAcceptanceTest` has no recorded run (`ACCEPTANCE-PLUG-AND-PLAY.md`, status) |
| Spring host | Yes, Spring Boot 4.1 | as above | as above | - | as above |
| Plain Operaton host (own `ProcessEngineConfiguration`, no Spring), lane R8-pnp, not merged | Add `sangam-engine`, then add `org.sangam.engine.SangamEnginePlugin` first in the plugin list | `sangam.engine.*` as system properties or `Properties` (`SangamEngineSettingsBinder`; an unknown key refuses the start); host beans the models call: `sangam.engine.deploy-allowed-beans.<tenant or *>=bean.member` (exact names only, `SL160-ALLOW-ENTRY`; logged as `SL160-HOST-APPROVAL`) | Your mapping of the routing outcome to your own codes; `RoutingIncidents.collect` does the read (IFMS-INTEGRATION-GUIDE section 2.1) | A plugin list read from configuration is the host's own (generic Operaton) feature | `DropInHostH2Test`, `HostApprovalEntriesTest` (H2, unit) |
| Quarkus host | No | - | - | Refuses every start with `CAPABILITY_MISSING` (section 3.6) | `QuarkusHostTest` written, not run (MASTER-PENDING C14) |
| Databases | PostgreSQL, Oracle, SQL Server, MySQL 8.4, H2 (dev) (`deploy/README.md` section 1.4) | Database preparation per engine (section 3.4) | Oracle driver is customer-supplied (`deploy/README.md:187`) | Oracle and SQL Server acceptance not green (section 4); decision 388 rename not merged | section 4 |
| Identity (OIDC) | No: you bring an IdP | `SANGAM_OIDC_ISSUER` or `spring.security.oauth2.resourceserver.jwt.*`; tenant claim `sangam.security.claims.tenant`; `sangam.security.platform-issuer` (INTEGRATION-GUIDE sections 6-7) | Claim mapping in your IdP | A JWK set URI without an issuer is refused in production (RM-09) | `ProductionStartupFailFastTest.jwkSetUriWithoutIssuerIsRefused` |
| Secrets | `secret://` references through providers such as `file` (`sangam.secrets.providers`) | Every key of section 2; one key per purpose and per environment (decision 37, 385 (3)) | None | Custodian process for the attestation is the owner's (decision 387 (4)) | `ProductionStartupFailFastTest` key cases |
| Deployment: compose | `docker compose --profile postgres up -d --build` (`deploy/README.md:77-86`) | `.env` from `deploy/compose/.env.example`; production is the compose default (`deploy/README.md:88-93`) | None | - | Image builds NOT_RUN in this round (MASTER-PENDING C9) |
| Deployment: Helm | Chart `deploy/helm/sangam` (kubeVersion >= 1.30) | values: `server.production`, `server.trustedProxies`, `server.keyEnvironment.*`, `compliance.principalKeyRef`, `malware.*`, driver source for Oracle (`deploy/README.md:187-269,628-634`) | None | Helm not installed on the build machine; K8s runs NOT_RUN (MASTER-PENDING C7) | NOT_RUN |

## 2. What every production installation must configure

The server refuses to start in production (`sangam.security.production=true` or a profile containing `prod`,
`ProductionEnvironment.java:48`) unless each of these is set. The list is the valid production baseline of
`sangam-server/src/test/java/org/sangam/server/deploy/ProductionStartupFailFastTest.java:106-143`; each line has a
test case in that class that removes it and expects the refusal.

| # | Setting | Refusal without it | Source |
|---|---|---|---|
| 1 | `sangam.security.production` = `true` or `false`, explicit | "must be true or false (decision 143)" | decision 143, 198 |
| 2 | Datasource URL, not loopback | "point at a loopback host" | SL-103 |
| 3 | `SANGAM_DB_USER`, `SANGAM_DB_PASSWORD` (not the shipped default) | "SANGAM_DB_USER is unset" / "SANGAM_DB_PASSWORD is unset" | `config/ProductionDatabaseCredentials` |
| 4 | KEK: `sangam.security.kek.keys.<id>` + `sangam.security.kek.current-id` (or PKCS#12) | "No key-encryption key configured" | `deploy/README.md:219-222` |
| 5 | Registry signer `sangam.registry.signer.private-key` / `.public-key` (Ed25519) | "sangam-registry refuses to start in production" | RM-06 |
| 6 | Identifier-vault pepper `sangam.registry.vault.hmac-pepper` | KEY_SHARED_ACROSS_PURPOSES if it reuses another key | decision 37 |
| 7 | Malware scanner (`SANGAM_MALWARE_SCANNER` = `clamd` or `icap`) **or** `sangam.malware.production-waiver` with a non-blank reason | "(decision 125)" | `MalwareScanProductionRequirementTest`; `deploy/README.md:628-634` |
| 8 | Audit checkpoint keys `sangam.audit.checkpoint.private-key-ref` / `public-key-ref` (`secret://`) | "(decision 126)" | decision 126 |
| 9 | Compliance principal key `sangam.compliance.principal-key-ref` (base64, >= 32 bytes) | "(owner decision 371)" | decisions 371 (5), 372 |
| 10 | Key environment `sangam.keys.environment` **and** `sangam.keys.foreign-fingerprints`, **or** the custodian attestation `sangam.keys.attestation` + `sangam.keys.custodian-public-key`; first environment only: `sangam.keys.bootstrap=true` once | `KEY_ENVIRONMENT_LIST_REQUIRED`, `KEY_ATTESTATION_INVALID` | decisions 385 (3), 387 (4), 390 (2); `deploy/README.md:236-269` |
| 11 | GL export connector `sangam.ledger.gl-connector` (the ledger module is on by default), naming a connector the integration module has, **or** `sangam.ledger.gl-export-waiver` (env `SANGAM_LEDGER_GLEXPORTWAIVER`) with a non-blank reason: audited (`LEDGER_GL_EXPORT_WAIVED`), entries kept in `LDG_ACCOUNTING` with `exported_at` NULL and exported once a connector is set; both set, or a blank reason, is refused | "sangam.ledger.gl-connector must be set in production"; an id naming no connector: "LEDGER_GL_CONNECTOR_UNKNOWN"; waiver: "ambiguous" / "needs a written reason" | decision 126 part 2; `LedgerWiring.requireGlExport`, `LedgerWiring.requireKnownGlConnector` |
| 12 | Trusted proxies `sangam.http.trusted-proxies` listing the UI and the ingress | `TRUSTED_PROXIES_REQUIRED` | decision 371 (1), register DEC-371-1 |
| 13 | Development conveniences off: `sangam.security.bootstrap.enabled=false`, `sangam.security.dev-token.enabled=false`, `sangam.debug.enabled=false`, no development platform issuer | "...is not allowed with sangam.security.production=true" | decisions 143, 152 |
| 14 | OIDC complete: issuer together with any JWK set URI | "OIDC token validation is incomplete in production (RM-09)" | RM-09 |
| 15 | JVM in UTC: `-Duser.timezone=UTC` (every mode, not only production) | refused before any bean | ADR-D-27, SL-53; `deploy/README.md:364-367` |

Also required by setting, not by the production switch:
- **System audit scope** `sangam.engine.system-audit-scope`, form `system:<name>` (`deploy/README.md:97-101`).
- **Cell jurisdiction** `SANGAM_CELL_JURISDICTION` on a cell; there are no country defaults (SL-104, `deploy/README.md:306-310`).
- **Compliance jurisdictions** `sangam.compliance.jurisdictions` when compliance is on; Helm sets `IN` (INTEGRATION-GUIDE section 10).
- **Ledger rate scope** `sangam.ledger.rate-scope` before any cross-currency conversion (`deploy/README.md:309-310`).
- **Government profile** `sangam.rest.government-profile=true` if `Idempotency-Key` must be required on commands (INTEGRATION-GUIDE section 5).

None of these has a safe default, so "start the image and go" works only in development (`SANGAM_SECURITY_PRODUCTION=false`
with a dev profile, `deploy/README.md:88-93`). That is deliberate (fail closed), not a defect. But it means production
is plug and play only once these values exist.

## 3. Notes per mode

### 3.1 Embedded starter
- Requirements: Spring Boot 4.1.x, Jackson databind 2.22.x, Liquibase 4.x, UTC JVM, and the `sangam-bom` imported before the Spring Boot BOM (`sangam-spring-boot-starter/README.md:8-15`).
- Under a Spring Boot *parent*, inherited management beats an imported BOM, so the host pins Liquibase and Jackson itself (joint ledger JL-S-04).
- Compliance is off in a host until `sangam.compliance.jurisdictions` is set. Turning it on brings the principal key (row 9) into play.

### 3.2 Standalone server, REST and events
- One contract, generated from route tables: `sangam-rest/api/sangam-api.yaml`, checked in CI for drift. Events are in `sangam-rest/api/sangam-events.asyncapi.yaml`.
- `/api/v1` changes are additive only; a deprecation lasts at least 365 days (`sangam-rest/API-LIFECYCLE-POLICY.md:5-30`).
- Path parameters that can hold `/` or `:` move to query parameters (decision 393). This is a 0.1.0 contract change that clients must regenerate for.

### 3.3 Packs, profiles and onboarding
- Domain knowledge is data: packs (BPMN, DMN, CMMN, forms, seeds) and profiles (`sangam-packs/src/main/resources/profiles/`). Sangam stays generic (ADR-D-20; `docs/IFMS-KNOWLEDGE-LINKS.md`).
- A DSC gate is reached through a call from a pack process, never started directly (decisions 382, 385 (1), 387 (3)). A gate that signs consumes its slot (decision 390 (1), merged at eb779e1a).

### 3.4 Databases
- Preparation per engine is in `deploy/README.md` section 1.4. Examples: SQL Server needs `READ_COMMITTED_SNAPSHOT ON`, or the start is refused; Oracle needs AL32UTF8; MySQL must be 8.4 or later (`MYSQL_VERSION_REFUSED`).
- Outside development, the schema is migrated once with `migrate-only`. A start without engine tables is refused with `ENGINE_SCHEMA_MISSING` (decision 334).
- **Decision 388, pending:** the table `PG_CONFIG` becomes `PG_PROGRAMME_CONFIG` on every database. The code is lane commit 0438ccd9 on feat/round7-it and is not in 5ba0a7b4 (`sangam-persistence/.../SangamSchema.java:120` still lists `PG_CONFIG`). Until it merges, a PostgreSQL database cannot complete the baseline after changeset 0036-26 (decision 388).
- YugabyteDB, CockroachDB and MariaDB are experimental or refused by name (register M-108, M-108-CRDB, SL-112-LB; decision 384).

### 3.5 Identity and secrets
- The tenant always comes from the token, never from the request (decision 145; INTEGRATION-GUIDE section 6).
- Token scopes are platform groups the owner already holds (decision 370 (2)).
- Every key is per purpose and per environment (decision 37). One key serving two purposes is refused with `KEY_SHARED_ACROSS_PURPOSES`.

### 3.6 Quarkus host
- The Quarkus host is an optional adapter (decision 186). It supplies five seams (config source, lifecycle, scheduling, security context, shared transaction). Every other required capability is `CAPABILITY_MISSING` at the engine build and at start-up, so **every Quarkus start is refused today**: `docs/M-015-QUARKUS-EXTENSION-DESIGN.md:326,350`; `sangam-quarkus-extension/src/test/java/org/sangam/quarkus/host/QuarkusHostTest.java:29-32`.
- DSC gates stay fail-closed on Quarkus until it has the pack runtime, the document route and HTTP (decision 390 (3)).
- Remaining work: lane L-quarkus, phases 2, 3 and 5 (MASTER-PENDING B11; decision 396). Its tests run only in the opt-in profile `-Psangam-quarkus` (decision 396 (1)), written and not run (MASTER-PENDING C14). Whether it stays in 0.1.0 is an owner question (MASTER-PENDING lane 3).

## 4. Evidence: what has and has not run

Sangam's own answer to the IFMS sponsor gate (joint ledger, `[REPORT] SANGAM-EVIDENCE-FOR-SD-505`, 2026-11-16) is
the current record. It is restated here without improvement:

| Capability | Oracle | SQL Server |
|---|---|---|
| `IfmsPacksIT` (government packs end to end) | FAIL (6 run, 3 ERROR) | FAIL (6 run, 3 ERROR); later runs NOT_RUN (testCompile) |
| `DecimalVariableIT` (exact money in variables) | PASS (single run) | PASS (single run) |
| Exact stored fractions, `TypedVariables` | NOT_RUN (unit/H2 only) | NOT_RUN |
| `UnverifiedPaymentVendorIT` | PASS | PASS |
| BANK_ACCOUNT clear-value vault (decision 389) | FAIL | NOT_RUN |
| CONTROLLING leave power (decision 391) | NOT_RUN | NOT_RUN |
| `E2AuthorizationIT` | FAIL | FAIL |
| `TenantQuotaIT` | PASS | FAIL |
| Delegation tables, archive gate | NOT_RUN | NOT_RUN |
| Full acceptance run | NOT_RUN | NOT_RUN |

Other NOT_RUN families: MySQL and PostgreSQL acceptance, Playwright, AC-08, Helm, image builds, SBOM, performance,
cloud brokers and the Quarkus host (MASTER-PENDING section C, C1-C14). **There is no green sha** (joint ledger
§(3)b). The fixes for the Oracle and SQL Server failures are on feat/round7-it (a4db44ac, 57ca10fc, 6383c10c,
0438ccd9) and are not merged into 5ba0a7b4.

## 5. Not yet available (summary)

| Item | Id | Source |
|---|---|---|
| Green sha with Oracle and SQL Server acceptance green | E1, E2 | MASTER-PENDING section E |
| Release tag `v0.1.0`, published artifacts (Maven Central, npm, PyPI, NuGet, Packagist) | M-019, D15 | MASTER-PENDING D15; joint ledger 2026-11-10 (RC tag, no registry) |
| Quarkus host | M-143, B11 | section 3.6 |
| Cross-language worker CI | AC-08 | register AC-08 |
| PG_PROGRAMME_CONFIG rename merged | decision 388 | section 3.4 |
| BANK_ACCOUNT clear-value custody merged | decision 389 | joint ledger §(2) |
| CONTROLLING leave power merged | decision 391 | joint ledger §(2) |
| Live eSign | decision 387 (6), D11 | MASTER-PENDING D11 |
| Live Azure Event Grid / Service Bus | SL-170 | INTEGRATION-GUIDE section 13 |
| Migration inventory of the old tenant-variable fallback | DEC-145 | INTEGRATION-GUIDE section 13; decision 381 (3) |

## 6. Verdict

1. **By design**, Sangam is plug and play: domain behaviour is packs and profiles, never Java, and a scenario that needs Java fails acceptance.
2. **Embedding on Spring Boot 4.1** and **the standalone server** work by dependency or image plus configuration. No product code is needed.
3. **Production is configuration-heavy on purpose.** The fifteen fail-closed settings of section 2 must be supplied, and the GL connector (or its audited waiver) and the malware scanner (or a waiver) are among them.
4. **New external systems need code:** a connector plugin. **New domains need data:** packs through packgen.
5. **Quarkus is not usable:** every start is refused with `CAPABILITY_MISSING`.
6. **The evidence is not there yet.** Oracle and SQL Server acceptance is FAIL or NOT_RUN, the plug-and-play suite has not run on the current tree, and AC-08 is not green.
7. **No release exists:** no green sha, no tag, no published artifact.
8. So the honest answer for 0.1.0 today is: **plug and play in design and in code for Spring and the server, not yet proven, and not released.**
