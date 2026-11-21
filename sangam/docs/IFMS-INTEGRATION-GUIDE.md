<!-- SPDX-License-Identifier: Apache-2.0 -->
# Plugging Sangam into IFMS: the end-to-end guide

This guide covers the whole path from an empty environment to an IFMS go-live on Sangam, and then upgrades and
rollback. It is the IFMS-specific companion of [INTEGRATION-GUIDE.md](INTEGRATION-GUIDE.md) (backlog M-018). Where
that guide already explains a mechanism, this one links to the section and does not repeat it. Whether Sangam is plug
and play in general is answered in [PLUG-AND-PLAY.md](PLUG-AND-PLAY.md).

**Boundary.** IFMS is a separate consumer project. Sangam stays generic (ADR-D-20): every IFMS need reaches Sangam as
packs, profiles, configuration or a generic capability, never as IFMS code in the product (joint ledger rule 8;
[IFMS-KNOWLEDGE-LINKS.md](IFMS-KNOWLEDGE-LINKS.md)). The IFMS side's own records (its decision register, test plan and
scenario catalogue) are read-only for Sangam and are cited here only by id.

**Sources.** The integration tree at 5ba0a7b4 (2026-11-17), refreshed at 0c5f5518 for the 0.1.0 release (lane R8-release-docs). The owner decisions in
`docs/records/OWNER-DECISIONS-2026-11-12.md`. The joint coordination ledger `JOINT-LEDGER-SANGAM-IFMS.md` and the
handoff index `HANDOFF-INDEX.md`, which are kept outside this repository in the owner's shared project folder. The
Sangam master pending list of 2026-11-17, section E. The test
`sangam-server/src/test/java/org/sangam/server/acceptance/consumer/ifms/IfmsPacksIT.java`. Paths are relative to
`sangam/`.

**Status marks.** Each step is marked **available now** (in 5ba0a7b4, or in 0c5f5518 where a sha says so), **merging** (coded on a lane branch, being merged for 0.1.0), or **pending (id)**, where the id says what
it waits on. "NOT_RUN" means a test is written but has not run on that database.

## 0. Status at a glance

| Area | Status |
|---|---|
| A green Sangam sha that IFMS may pin | **pending (E1)**: there is no green sha yet (joint ledger §(3)b) |
| `IfmsPacksIT` on Oracle and SQL Server | **pending (E2)**: FAIL on both; fixes on feat/round7-it, not merged |
| PG_CONFIG → PG_PROGRAMME_CONFIG | **available now** (decision 388; 0438ccd9, merged in 1261db4f) |
| BANK_ACCOUNT clear-value custody | **available now** (decision 389; 57ca10fc, merged in 1261db4f); acceptance `IfmsPacksIT` on Oracle and SQL Server still due (E2) |
| CONTROLLING leave sanction power | **available now** (decision 391; 6383c10c, merged in 1261db4f; `CorePowersControllingLeaveTest`) |
| Contract deltas announcement | **pending (E3)** |
| GA-55 bill-type contract | **pending (E4, joint JL-S-06, IFMS side)** |
| Routing incident v1 + keys, service-token headers, version immutability, isolation | **available now** (E5, DONE-VERIFIED) |
| Plain-Operaton host swap (PNP-1), host bean approval by configuration (PNP-2), typed routing-incident read (PNP-3) | **merged** (13aa5186; H2 64/0; Oracle and SQL Server pending) (lane R8-pnp, branch feat/round8-pnp: 72fac64f, 5c944df4, eee38a23, 4fd97c61; H2 and unit evidence only; section 2.1) |
| DSC gate through a call, slot consumption | **available now** (decisions 382, 385, 387, 390 (1); merged eb779e1a) |
| Exact decimals end to end (TypedVariables, ExactJson) | **available now** in code; DB evidence NOT_RUN |
| SD-505 confidence score | 0.00% as scored by IFMS on 2026-11-16 (joint ledger) |

## 1. Prerequisites

| # | Prerequisite | Status |
|---|---|---|
| 1.1 | JDK 25 or 27 (register SG-06, SG-07: unit runs PASS); JVM default time zone UTC (`-Duser.timezone=UTC`) on every engine JVM, including the IFMS host (SL-53 (b1); `deploy/README.md:364-367`). The business zone Asia/Kolkata is configuration (`sangam.engine.timer-zone`), never the JVM zone | available now |
| 1.2 | Spring Boot 4.1.x host if embedding. Import `sangam-bom` before the Spring Boot BOM. Under a Spring Boot parent, pin Liquibase 4.33.x and Jackson 2.22.x explicitly (JL-S-04, SG-26, JL-S-02) | available now |
| 1.3 | Oracle 19c/21c/23ai or SQL Server 2019/2022 prepared as in `deploy/README.md` section 1.4. Oracle's driver is customer-supplied (`deploy/README.md:187`) | available now |
| 1.4 | An OIDC identity provider that puts the tenant in a claim (`sangam.security.claims.tenant`) and a separate platform issuer (INTEGRATION-GUIDE sections 6-7) | available now |
| 1.5 | A secret store reachable through `secret://` references, and the production keys of [PLUG-AND-PLAY.md section 2](PLUG-AND-PLAY.md#2-what-every-production-installation-must-configure) | available now |
| 1.6 | A malware scanner (ClamAV `clamd` or ICAP), or a recorded waiver (decision 125) | available now |
| 1.7 | A GL export connector for the ledger (`sangam.ledger.gl-connector`), or the audited waiver `sangam.ledger.gl-export-waiver` with its reason (decision 126) | available now (the setting); the IFMS GL connector is IFMS's (section 11) |
| 1.8 | Shared build machine: heavy runs take the shared lock and are announced 2 h ahead in the joint ledger (joint ledger rule 7, MACHINE-LOAD) | available now |

## 2. Architecture

```
                 IFMS users (DDO, PAO, Pr.AO, sanctioning officers, auditors)
                                    |
                             IFMS UI / portals
                                    |
   +--------------------------------v-----------------------------------+
   | IFMS application (owned by IFMS)                                   |
   |  - business screens, masters, GL, bills, payments                  |
   |  - workflow adapter (workflow-sangam): REST/SDK client,            |
   |    maps ROUTING-INCIDENT codes to IFMS incidents                   |
   +------+-------------------------+----------------------+------------+
          | REST /api/v1 (Bearer)   | events (AsyncAPI)    | workers (fetch-and-lock)
          v                         v                      v
   +--------------------------------------------------------------------+
   | Sangam (embedded starter in the IFMS host, or standalone server)   |
   |  engine (Operaton) | security, tenants, seats | packs gov.ifms.*   |
   |  ledger + GL export | registry + identifier vault | compliance (IN)|
   |  audit chain + checkpoints | outbox/inbox | idempotency store       |
   +------+------------------------------+------------------------------+
          | JDBC                         | connectors (capability class a/b/c)
          v                              v
   Oracle / SQL Server            PFMS, eSign/DSC agent, GL, bank, SMS
   (one schema per installation)  (live, or simulators outside production)
```

- Tenancy: one engine tenant per government scope. The tenant always comes from the token (decision 145).
- Domain logic lives only in the `gov.ifms.*` packs and the India profiles. The adapter on the IFMS side owns the mapping to IFMS screens.

### 2.1 Embedded swap behind the IFMS workflow port (IFMS report SANGAM-PNP-H2, lane R8-pnp)

IFMS's H2 trial (E-65) found three gaps. Here is how each one is closed in code, on branch feat/round8-pnp (PNP-2 72fac64f, PNP-1 5c944df4, PNP-3 eee38a23, decision 404 (5) 4fd97c61). Status: **merged** (13aa5186; H2 64/0; Oracle and SQL Server pending) for 0.1.0. These changes are H2 and unit evidence only, and they are not pushed.

| Finding | Before | Now |
|---|---|---|
| PNP-1: the engine was chosen by which adapter was built | The host built the engine through `SangamEngineConfigurer` (IFMS C-1, C-3: about 100 lines) | The host keeps its plain Operaton adapter and adds one process engine plugin, `org.sangam.engine.SangamEnginePlugin`, with its settings as `sangam.engine.*` properties (`SangamEngineSettingsBinder`, the same names as the Spring binding; an unknown key refuses the start). The host keeps its engine name, history level, job-executor choice and schema mode. The plugin must come first in the list. Test: `DropInHostH2Test` |
| PNP-2: SL-160 refused `${recorder.record()}` | Approving the bean was already possible (`deploy-allowed-beans`), but the setting was not documented for hosts, and an entry with a pattern such as `recorder.*` was accepted and silently approved nothing | Approval is by configuration only: `sangam.engine.deploy-allowed-beans.*=recorder.record` (or a tenant id instead of `*`). It stays fail-closed: no host bean is approved unless it is listed, and this includes beans passed to `setBeans`. Each entry must be one exact name (`bean` or `bean.member`; one class name for `deploy-allowed-classes`). A wildcard, a pattern, an engine context object (`execution`, `task`, ...) or a reflective member refuses the start with `SL160-ALLOW-ENTRY`. Every approval in force is logged at start as `SL160-HOST-APPROVAL`. Tests: `HostApprovalEntriesTest`, `DropInHostH2Test` |
| PNP-3: SG-35 cost about 235 lines of mapping (IFMS C-5) | The host wrote its own incident query, the walk over called children and the message parsing | `org.sangam.engine.incident.RoutingIncidents.collect(engine, caseInstanceId)` returns typed `Found` rows: incident id, instance, root, gateway, `RouteDecision.Outcome`, the raw code, the message and a stable `dedupKey()`. A code it does not know is returned with outcome `null`, never guessed. The host keeps only its own reason mapping (two cases) and its audit write. Test: `DropInHostH2Test` |

The IFMS host code for the swap is then:

```java
cfg.getProcessEnginePlugins().add(0, new org.sangam.engine.SangamEnginePlugin()); // or name the class in the host's plugin list
// -Dsangam.engine.system-audit-scope=system:ifms -Dsangam.engine.timer-zone=Asia/Kolkata
// -Dsangam.engine.deploy-allowed-beans.*=recorder.record
```

**Host bean approval setting.** `sangam.engine.deploy-allowed-beans.<scope>` (scope `*` for every tenant, or one tenant id) lists exact bean names or `bean.member` names that SL-160 lets deployed models call. The default is empty, so no host bean is callable (fail-closed). Exact names only, in every environment (decision 404 (4)); bad entries refuse the start with `SL160-ALLOW-ENTRY`; each approval is logged at start as `SL160-HOST-APPROVAL` (72fac64f, `HostApprovalEntriesTest`). Each deployment that uses a host-approved bean is also written to the class-B audit chain (decision 404 (5), 4fd97c61).

**Reading routing incidents.** Sangam keeps its incident when no gateway route matches (decision 404 (3)). IFMS reads them with `RoutingIncidents.collect(engine, caseInstanceId)`, which walks called children and returns typed rows (eee38a23, `DropInHostH2Test`). The incident keys stay ROUTING-INCIDENT v1 (section 11).

Still on the IFMS side: the two adapter modules cannot share one classpath (E-65 item 2). That is IFMS packaging, not a Sangam gap. If the IFMS adapter reads its plugin class names from configuration, the swap needs no code change at all. The Sangam-only behaviours stay on purpose: the strict exclusive gateway raises an incident instead of `ENGINE-02004` (ADR-D-25), and compensation is reverse and sequential (SG-08). Whether to add an Operaton-compatible "throw" mode for SG-35 is an open owner question (R8-pnp report).

## 3. Pinning a Sangam version

1. IFMS pins **only** a sha that Sangam announces as green in the joint ledger, as `[INFO] GREEN-SHA <sha>` (joint ledger §(3)b; IFMS's reply "IFMS does not re-pin until Sangam announces a green sha"). **pending (E1)**
2. Green means all of these, on the same sha (joint ledger §(3)b; MASTER-PENDING E1):
   - the R7-it fixes are merged;
   - a full verify run is green;
   - the Oracle and SQL Server IT runs, `IfmsPacksIT` included, are green;
   - the owner has signed the push to `main` (push gate).
3. The artifact route is an RC tag with no package registry: tag `v0.1.0-rc.1` on the first all-green main commit. IFMS builds that tag, or a pinned sha, into its private Maven repository (owner decision 2026-11-10, IFMS SD-451). **pending (E1, M-019)**
4. After re-pinning, IFMS re-runs its static drift gate (joint ledger ACK ROUTING-INCIDENT-KEYS, 2026-11-16).

## 4. Deployment topology

| Choice | When | Status |
|---|---|---|
| Embedded: `sangam-spring-boot-starter` inside the IFMS Spring Boot 4.1 host | the adapter calls Sangam in-process; one JVM | available now (INTEGRATION-GUIDE section 2) |
| Standalone server + REST/SDK | separate scaling and release | available now (INTEGRATION-GUIDE section 3) |
| Quarkus host | - | **pending (B11, M-143)**: every start is refused with `CAPABILITY_MISSING` |
| Compose (dev, test) | `deploy/compose`, profiles oracle / sqlserver | available now; image build NOT_RUN this round |
| Helm (production) | `deploy/helm/sangam`, `server.production: true` | available now in the chart; cluster runs **pending (C7)** |

Production rules that apply to every topology:
- Trusted proxies list the IFMS UI and the ingress (decision 371 (1)).
- One key set per environment (decisions 37, 385 (3)). The first environment starts once with `sangam.keys.bootstrap` (decision 390 (2)).
- Simulators are never deployed in production (INTEGRATION-GUIDE section 9).

## 5. Database setup

Oracle and SQL Server come first, because the sponsor gate scores only scenarios that pass on both (section 17).

1. Prepare the database as in `deploy/README.md` section 1.4: Oracle AL32UTF8 with the listed grants; SQL Server `READ_COMMITTED_SNAPSHOT ON` (the start is refused without it) and a `*_UTF8` collation. **available now**
2. Set `SANGAM_DB_URL`, `SANGAM_DB_USER` and `SANGAM_DB_PASSWORD`. Production refuses a loopback URL, an unset value and the shipped default password (`ProductionStartupFailFastTest`). **available now**
3. Migrate once with `SANGAM_LIQUIBASE_MODE=migrate-only`. Later starts validate only and refuse a missing engine schema with `ENGINE_SCHEMA_MISSING` (decision 334). **available now**
4. **PG_CONFIG rename (decision 388).** The programme table `PG_CONFIG` becomes `PG_PROGRAMME_CONFIG` on every database. Applied changesets are never edited: on Oracle and SQL Server a new rename changeset follows the applied ones. A guard refuses product table names that collide with PostgreSQL catalog relations. IFMS is affected only if it reads Sangam tables directly; it should not (use the REST contract). **available now** (0438ccd9, merged in 1261db4f)
5. Check the schema with `GET /api/v1/ops/schema-status` or `sangam upgrade check --schema` (register M-092). **available now**; Oracle/SQL Server reading NOT_RUN.

## 6. Identity, tenants and seats

1. Configure OIDC: `SANGAM_OIDC_ISSUER`, the tenant claim, the audience `sangam-server`, and the platform issuer for platform administrators (INTEGRATION-GUIDE sections 6-7). **available now**
2. Create the tenant (`POST /api/v1/admin/tenants`, platform administrator). Its id matches `[A-Za-z0-9][A-Za-z0-9_.-]{0,59}`, for example a Government of India department scope `GOI.<DEPT>` as in `IfmsPacksIT`. Platform role rows are created then, and backfilled at every start (decisions 368, 370 (1)). **available now**
   - **Per-tenant configuration for an id with a dot (`GOI.FIN`).** The tenant id is a map key, so write it in Spring's bracket notation; never as plain name parts. `sangam.channels.egress.GOI.FIN[0]=...` is refused at run time ("Configuration property name ... is not valid") and the tenant then gets no channel, connector or webhook. Write instead (properties file, `application-*.yml`, or `SPRING_APPLICATION_JSON`; an environment variable name cannot carry the dot):
     ```properties
     sangam.channels.egress[GOI.FIN][0]=https://ifms.example.gov.in
     sangam.channels.enterprise[GOI.FIN].gl-ifms.type=sap-odata
     sangam.channels.enterprise[GOI.FIN].gl-ifms.service-root=https://sap.example.gov.in/sap/opu/odata/sap/Z_GL_SRV
     sangam.channels.enterprise[GOI.FIN].gl-ifms.auth-ref=secret://ifms/gl
     sangam.channels.webhooks[GOI.FIN].slack.signing-secret-ref=secret://ifms/slack
     sangam.compliance.tenant-jurisdictions[GOI.FIN]=IN
     ```
     In YAML quote the bracketed key: `sangam: {channels: {egress: {"[GOI.FIN]": [https://ifms.example.gov.in]}}}`. A simple id (`acme`, `ACME2`) keeps working without brackets. Code: `TenantConfig` (sangam-rest), used by `ChannelRuntimeBinder.egress/enterprise` and `ChannelHooksController`; tests `ChannelRuntimeBinderTest.aTenantIdWithADotGetsItsEgressChannelsAndEnterpriseConnectors`, `ChannelHooksWebhooksH2Test.aTenantIdWithADotVerifiesItsWebhook`, `LedgerWiringGlGuardTest.aGlConnectorOfATenantIdWithADotIsDeclared`. **available now** (branch fix/tenant-id-dot-channels)
3. Seats and holders come from the pack seeds: for example `CONTROLLING-1` from `packs/leave/seed.json`. Every task is answered by the person who holds its seat today; each approval step must be a different person (`IfmsPacksIT` class comment). **available now**
4. **CONTROLLING (decision 391).** The leave pack names CONTROLLING as the sanctioning role. `sangam-core-powers.dmn` gets a CONTROLLING row scoped to the leave case type only, with no money power, and the core pack is `sangam.core` 1.0.2. **UNCERTAIN:** 6383c10c changed `sangam-core-powers.dmn` without a version bump (the 1.0.2 bump came earlier, in 89b8d068), so a database that already holds an older 1.0.2 content of `sangam.core` would refuse it with `PACK_VERSION_IMMUTABLE`; deploy on a fresh database, or wait for the integrator's ruling. Without it, a leave sanction is refused with `POWER_LIMIT_EXCEEDED`. **available now** (6383c10c, merged in 1261db4f; `CorePowersControllingLeaveTest`); `IfmsPacksIT.leave` on Oracle and SQL Server still due (E2)
5. Other IFMS roles come from pack data and the profile power tables (`profiles/in/dmn/<STATE>/ifms-financial-powers.dmn`, and `GOI`). The seat model for budget distribution (who enters, who scrutinises, who approves) follows IFMS's evidence report IFMS-WF-SOD (joint ledger 2026-11-09 23:40). **available now** as data
6. Worker and adapter identities: service principals (`POST /api/v1/admin/service-principals`). A scope must be held, otherwise `TOKEN_SCOPE_NOT_HELD`. Topics must be bound, otherwise `WORKER_TOPIC_NOT_BOUND` (INTEGRATION-GUIDE section 7). **available now**

## 7. Installing the IFMS packs

### 7.1 The packs

Versions are as generated by packgen in 0c5f5518 (`sangam-packs/src/main/resources/packs/*/pack.json`; the compliance pack is `sangam-compliance/src/main/resources/packs/compliance/pack.json`).

| Pack id | Version | Folder |
|---|---|---|
| `sangam.core` | 1.0.2 | `core` |
| `gov.ifms.audit-para-settlement` | 1.0.2 | `ifms-audit-para-settlement` |
| `gov.ifms.bill` | 1.0.3 | `ifms-bill` |
| `gov.ifms.budget-allocation` | 1.0.2 | `ifms-budget-allocation` |
| `gov.ifms.budget-formulation` | 1.0.2 | `ifms-budget-formulation` |
| `gov.ifms.budget-reappropriation` | 1.0.2 | `budget-reappropriation` |
| `gov.ifms.challan-reconciliation` | 1.0.2 | `challan-reconciliation` |
| `gov.ifms.gia-uc` | 1.0.2 | `ifms-gia-uc` |
| `gov.ifms.gpf` | 1.0.2 | `ifms-gpf` |
| `gov.ifms.loans-advances` | 1.0.2 | `ifms-loans-advances` |
| `gov.ifms.monthly-accounts` | 1.0.2 | `ifms-monthly-accounts` |
| `gov.ifms.pao-chain` | 1.0.2 | `ifms-pao-chain` |
| `gov.ifms.reappropriation` | 1.0.2 | `ifms-reappropriation` |
| `gov.ifms.receipts` | 1.0.1 | `ifms-receipts` |
| `gov.ifms.salary-monthly` | 1.0.0 | `salary-monthly` |
| `gov.ifms.scheme-appraisal` | 1.0.2 | `ifms-scheme-appraisal` |
| `gov.ifms.sna-sparsh` | 1.0.2 | `ifms-sna-sparsh` |
| `gov.ifms.supplementary` | 1.0.2 | `ifms-supplementary` |
| `gov.ifms.surrender-lapse` | 1.0.2 | `ifms-surrender-lapse` |
| `gov.ifms.year-end-closing` | 1.0.2 | `ifms-year-end-closing` |
| `gov.leave` | 1.0.2 | `leave` |
| `gov.pension` | 1.0.2 | `pension` |
| `sangam.compliance` | 1.1.1 | `compliance` (in `sangam-compliance`; decision 402 (8)) |

Merged since 5ba0a7b4: `gov.ifms.bill` 1.0.3 (f48f6f1a, decision 400 (2)), `sangam.compliance` 1.1.1 (f58fecc9, F7-4, decision 402 (8); merge 95d8956c). `sangam.core` stays 1.0.2 with the decision 391 row (see section 6 item 4 for the UNCERTAIN version point). Lane dmn-money-hold (DEC-5, op EXACT hold): `gov.pension` and `gov.contract-management` 1.0.4 (packgen releases.DMN_MONEY_HOLD, decision 390 (4)).

Take the versions from the green sha's packgen output, never from this table.

`IfmsPacksIT` deploys `sangam.core`, `gov.ifms.budget-formulation`, `gov.ifms.budget-allocation`,
`gov.ifms.reappropriation`, `gov.ifms.gpf`, `gov.ifms.bill`, `gov.ifms.pao-chain`, `gov.leave` and `gov.pension`
(`IfmsPacksIT.java:88-89`).

### 7.2 Pack lifecycle

1. **Generate.** Packs are generated by `python sangam-packs/tools/packgen/main.py`, and `--check` fails on drift. Never hand-edit them. **available now**
2. **Select and deploy.** Select the packs per tenant and deploy one engine deployment per pack, `pack:<id>@<version>` (`PacksTenantDeployer`, `PackDeployer.deployAll`). A tenant gets its packs in one of two ways. (a) Onboarding by a platform administrator (`/api/v1/onboarding`, step PACKS, with the selection). (b) At start-up: `sangam.packs.deploy-on-start=true` deploys every shipped pack for the tenants listed in `sangam.packs.tenants`; a later start deploys nothing again. `deploy-on-start` is a setting whose default is true only under profile `h2`, so every other install sets both (env `SANGAM_PACKS_DEPLOY_ON_START=true`, `SANGAM_PACKS_TENANTS=<id>[,<id>]`). A tenant created by `sangam.security.first-admins.*` gets no pack from that bootstrap: without onboarding it needs (b), else every process start of it answers 404 (register E-25; `PacksOfBootstrapTenantAgainstServerTest`, `deploy/demo/sangam-compose-fragment.yml`). Start-up deployment covers only the tenants explicitly listed in `sangam.packs.tenants`, and only when `deploy-on-start` is true (owner decision E-33 (a), option a4, 2026-11-21). Setting the list does not switch it on, and a first-admins tenant that is not listed gets no packs; each start says so at WARN. Outside profile `h2`, `deploy-on-start=true` without `sangam.packs.tenants` stops the start with an error that names both settings (owner decision E-33 follow-up 2, EV-M-0966). Only `h2` falls back to the dev-token tenant. Under (b) every shipped pack is deployed; only onboarding chooses a selection (option b3). Tenants created later get their packs through onboarding or the reviewed AJ-01/AJ-06 package workflow. A listed id that is no tenant is skipped and named at ERROR (register E-33 (c)). A call through `/admin/tenants` that leaves the tenant ACTIVE (create, `PUT`, or `POST /admin/tenants/{id}/activate`) also ensures its packs (`PacksWiring`, register E-33 (e)). **available now**
   The bootstrap tenant's six self-service grants of the module defaults wait `PENDING` for a second administrator: list them with `GET /api/v1/admin/policies/changes` and decide each with `POST /api/v1/admin/policies/changes/{id}/approve` or `/reject` and a reason (screen: Set up, Access rule approvals). The requester and a system identity never decide (`422 SOD_VIOLATION`); details in `INTEGRATION-GUIDE.md`, "Four-eyes policy changes" (register E-33 follow-up 3, EV-M-0968). **available now**
3. **Inspect.** Use `GET /packs/installed` and `GET /packs/{id}/document-slots` (platform pack administrators only, decision 381 (2)). **available now**
4. **Change.** A version is immutable (`PACK_VERSION_IMMUTABLE` → `422 PACK_REJECTED`). A change ships as a new version (decision 390 (4)). **available now**
5. **Uninstall or roll back.** `POST /packs/{id}/uninstall` (`409 PACK_HAS_DEPENDANTS`) and `POST /packs/{id}/rollback`, which rolls forward. **available now**

## 8. Profile data

1. Each tenant has a profile chain. For a central department it is `GOI > IN > REGIME-WESTMINSTER-SUPPLY`. The chain supplies what the packs read through their connectors: classification, document types and the BUDGET ledger. **A tenant without a profile has none of it** (`IfmsPacksIT.java:82-84`). **available now**
2. Profile files:
   - `sangam-packs/src/main/resources/profiles/in/IN.yaml` and `GOI.yaml`;
   - ministries under `profiles/in/goi/`;
   - the 36 States and UTs under `profiles/in/states/<CODE>.yaml`;
   - presets (`IN-PRESET-UNION`, `-UT-LEG`, `-UT-NOLEG`, `-NE-HIMALAYAN`, `-GENERAL`);
   - head-of-account seeds under `profiles/in/seeds/`;
   - per-scope DMN for financial powers and procurement mode under `profiles/in/dmn/<CODE>/`.

   **available now**
3. The year's appropriation is loaded through the product ledger, and payees are registered in the party registry, the way onboarding does it (`IfmsPacksIT` set-up). **available now**
4. Profile values come from IFMS's research (bill-type taxonomy, financial powers). Changes go through the generators, not by hand. The bill-type taxonomy is a joint contract. **pending (E4, JL-S-06)**

## 9. Money: exact decimals

- Every amount is an exact decimal (`BigDecimal`), never a binary double.
- JSON request fractions keep their exact text (`ExactJson`, decision 138, commit 43438530).
- Stored JSON reaches engine variables only through `TypedVariables` (`sangam-rest-core/.../support/TypedVariables.java`). A `Double` handed to `TypedVariables` by in-process Java code is **refused by name** (decision 381 (4), 8a740463). Fractions stored before the upgrade read back exactly (60d34270).
- **Effect for IFMS:** a client that sends a binary double where a decimal is expected is refused. Send amounts as JSON numbers with their exact digits, or as strings where the contract says so. **available now**
- Evidence: `DecimalVariableIT` PASS on Oracle and SQL Server (single run). Exact stored fractions are NOT_RUN on both (joint ledger §(1)). **pending (C1, C2)** for database evidence.

## 10. BANK_ACCOUNT custody (decision 389)

- Registry identifiers such as `BANK_ACCOUNT` keep an HMAC hash for lookup. The clear value is stored in, and revealed from, the compliance national-id vault (`EncryptedNationalIdVault`, table `CMP_NATIONAL_ID_VAULT`). It is encrypted per tenant, and every reveal is audited.
- A reveal needs an explicit actor and purpose. A reveal without them is refused.
- `HmacIdentifierVault` stays as the hash-only vault, and it refuses to store clear values.
- Since 1261db4f the server wires the custody vault (`RegistryCustodyVault` over the compliance `NationalIdVault`, `sangam-server/.../config/RegistryWiring.java:129-134`). Without a `NationalIdVault` bean a payee with a clear bank account still fails with `PAYEE_DIRECTORY_ERROR` ("no vault service is configured to hold clear identifier values").
- **available now** (decision 389, 57ca10fc merged in 1261db4f). Acceptance `IfmsPacksIT` on Oracle and SQL Server is still due (E2); the last recorded runs (joint ledger §(1)) predate the merge.
- IFMS check (joint ledger §(3)a): if any IFMS path reads a revealed BANK_ACCOUNT value, tell Sangam the file and line so it joins the acceptance test.

## 11. Contracts IFMS consumes

| Contract | What IFMS relies on | Status |
|---|---|---|
| REST `/api/v1` | `sangam-rest/api/sangam-api.yaml` (OpenAPI 3.1), additive only within v1, 365-day deprecation (INTEGRATION-GUIDE section 4) | available now |
| SDKs | generated clients; `sdks/codegen/surface-manifest.json` maps operationId to methods | available now; AC-08 CI not green |
| ROUTING-INCIDENT v1 | Gateway no-match or ambiguity is an audited incident: type `sangamRouting`, codes `NO_MATCHING_ROUTE` / `AMBIGUOUS_ROUTE` as message prefixes, a single `ROUTING_INCIDENT` audit, and the six keys in order plus three additive keys `engineTenant`, `resolvedScope`, `resolutionReason` (decision 357; `RoutingIncidentContractTest`; `RoutingIncidentHandler`). IFMS maps the code prefix only (ACK 2026-11-16). A change to the six keys, their order or the codes is a v2, with consumers first | available now (E5) |
| Events | `sangam-rest/api/sangam-events.asyncapi.yaml`; outbound `SANGAM_OUTBOX_DESTINATIONS` | available now |
| Idempotency | `Idempotency-Key` on commands; required in the government profile (`sangam.rest.government-profile=true`): `400 IDEMPOTENCY_KEY_REQUIRED` (INTEGRATION-GUIDE section 5) | available now |
| operationId | UUIDv5 of (tenant, root, activity instance, loop); a worker passes it to PFMS, the bank or GL as their idempotency key | available now |
| Path parameters | identifiers that can hold `/` or `:` move to query parameters (decisions 393, 400 (1)); in 0.1.0 by decision 406 (4ec73bba) | **merging** (lane R7-ui; listed in the contract-change notice) |
| Contract deltas since 209d2765 | `RELEASE-NOTES-0.1.0.md` section 3 and the contract-change notice for the joint ledger | notice written; posting pending (E3) |
| GL export (decision 126) | One GL outbox delivery per accounting entry to `sangam.ledger.gl-connector`, payload `reference`, `event`, `basis`, `debit`, `credit`, `amount`, `currency`, `functionalAmount` (decimals as strings), idempotency key `gl:<eventId>`. A permanent refusal is parked with an OPEN dead letter (operator requeue or skip), never dropped; an unknown outcome is a reconciliation item. Without a GL connector, production starts only under the audited operator waiver `sangam.ledger.gl-export-waiver` (env `SANGAM_LEDGER_GLEXPORTWAIVER`, the written reason, modelled on the malware waiver): the start writes the system audit event `LEDGER_GL_EXPORT_WAIVED` with the reason and logs a WARN naming the waiver. Every accounting entry is kept in `LDG_ACCOUNTING` with its exact GL payload (`payload_json`, changeset 0049-01) and `exported_at` NULL; no outbox GL row is written. Once `sangam.ledger.gl-connector` is set (and the waiver removed) the next start hands those entries to the GL export, oldest first, each once (`JdbcLedgerStore.exportPendingAccounting`). A blank reason, or the waiver together with a GL connector, is refused at start. | available now (this lane; `LedgerGlPublisherTest`, `LedgerGlWaiverH2Test`, `ProductionStartupFailFastTest`) |

## 12. Workers and connectors

| Integration | How | Status |
|---|---|---|
| PFMS | `PfmsFileConnector` (`sangam-integration/.../connector/gov/`); simulator `SANGAM_SIM_KIND=pfms` | connector available now; live PFMS NOT_RUN |
| eSign | `EsignConnector`, opt-in live connector with the decision 140 verifier (merge fa4b5102); `ESIGN_CONFIG_INVALID` on a bad setting | available now (opt-in); live **pending (D11, decision 387 (6))** |
| DSC (class-2/3 token) | Loopback desktop agent on the signer's computer, server-side verification (register DEC-165) | partial: real token NOT_RUN |
| GL | `sangam.ledger.gl-connector` names the GL export connector. Production refuses an unset value (`LedgerWiring.requireGlExport`) and an id that names no registered connector (platform or tenant, or a declared `sangam.channels.enterprise.<tenant>.<id>.type`) with `LEDGER_GL_CONNECTOR_UNKNOWN` (`LedgerWiring.requireKnownGlConnector`). A permanent refusal by the GL connector is parked and dead-lettered by the outbox relay (operator requeue or skip), never dropped. Under the audited waiver `sangam.ledger.gl-export-waiver` entries are kept for a later export (section 11). The IFMS GL connector is an IFMS plugin or configuration of a generic connector | setting available now; IFMS connector owned by IFMS |
| IFMS's own workers | service-principal token, `fetch-and-lock`, fencing triple, `outcome-unknown` on doubt (INTEGRATION-GUIDE section 8). Declare each topic's capability class: an undeclared topic is (c), with no automatic repeat | available now |

## 13. DSC signing gates and document slots

1. The core gates `sangam-core-recommendation` (slot `notingDocumentId`) and `sangam-core-sanction` (slot `sanctionDocumentId`) sign the document in their slot (INTEGRATION-GUIDE section 10). **available now**
2. A gate is reached **through a call** from a pack process. packgen writes the gate's slot pairs into the calling pack's manifest (decision 382). Where no user task precedes the call, packgen adds a prepare task with the shared upload form (decisions 385 (1), 387 (8)). **available now**
3. Only the document route fills a slot, on an open user task (decisions 267, 268). Caller writes to a declared slot name are refused on every write path (decisions 385 (2), 387 (1)). **available now**
4. A gate started directly has an empty slot: `422 DSC_SIGNATURE_NOT_VERIFIED` (decision 387 (3)). **available now**
5. A signing gate consumes its slot (`DOCUMENT_SLOT_CONSUMED`). A returned and resubmitted file needs the document uploaded again; otherwise `422 DOCUMENT_SLOT_NOT_READY` (decision 390 (1)). **available now** (merged eb779e1a); register DEC-236-2 re-verification **pending (B30)**
6. On Quarkus, DSC gates are fail-closed (decision 390 (3)). **pending (B11)**

## 14. Audit chain, CERT-In and DPDP

- Every action is written to a hash-chained audit with signed checkpoints. Production refuses to start without the checkpoint keys (decision 126). Replays are audited as `IDEMPOTENT_REPLAY`. If the audit cannot be written, the answer is `503 AUDIT_UNAVAILABLE` and the action does not happen (INTEGRATION-GUIDE section 5). **available now**
- Compliance jurisdiction `IN`: `sangam.compliance.jurisdictions=IN`. The principal key is required in production (decisions 371, 372). The control matrix is `docs/compliance/CERTIN-DPDP-CONTROL-MATRIX.md`. **available now**; the 10 CERT-In/DPDP browser specs are **pending (C5)**
- History archive before any purge (decision 386 (3), RM-52). **available now** (H2); Oracle and SQL Server NOT_RUN.

## 15. Multi-tenant rollout

The order follows IFMS's sponsor gate SD-505 and its scenario catalogue R-114 v0.1. That catalogue has 757 scenarios: 37 for the Centre, plus a 20-scenario template for each of the 36 States and UTs, with a total weight of 2455 (joint ledger 2026-11-16).

1. **Government of India first.** Create one tenant per department scope (`GOI.<DEPT>`) on the `GOI` profile chain. Deploy the packs, load the appropriation, seat the holders, and run the 37 Centre scenarios. **pending (E1, E2)**
2. **Then each State and UT.** Create the State tenant on `profiles/in/states/<CODE>.yaml` with its preset and its power tables. Run the 20-scenario template for that State. A State goes live only when its template passes. **pending (E1, E2)**
3. Workers lease, see and settle only their own tenant's work (register SL-157). **available now**
4. A tenant may move between instances (`421 TENANT_MOVED`). The migration inventory and the repair route for old tenant-variable instances are decision 381 (3), register DEC-145. **available now**

## 16. Go-live checklist (per environment)

- [ ] A green Sangam sha is announced and pinned (section 3). **pending (E1)**
- [ ] Database prepared and migrated once, with the decision 388 rename included (section 5).
- [ ] Every production setting of [PLUG-AND-PLAY.md section 2](PLUG-AND-PLAY.md#2-what-every-production-installation-must-configure) is supplied, and the server starts with `sangam.security.production=true`.
- [ ] Key environment list, or custodian attestation, is in place, and the bootstrap setting is removed after the first start (decision 390 (2)).
- [ ] Malware scanner is live, or a recorded waiver with its reason is in place.
- [ ] GL connector is configured and reachable (or the audited GL export waiver is recorded with its reason, and its removal is planned); PFMS and eSign are live (not simulators).
- [ ] `sangam.rest.government-profile=true`, so idempotency keys are required.
- [ ] Trusted proxies list the IFMS UI and the ingress.
- [ ] Packs are deployed at the green sha's versions, and the profiles are loaded per tenant.
- [ ] Seats and holders are loaded. CONTROLLING power is present (decision 391).
- [ ] BANK_ACCOUNT clear-value custody is wired (decision 389): a `NationalIdVault` is configured.
- [ ] SD-505 score above 98% (section 17). **pending**
- [ ] Rollback rehearsed (section 19).

## 17. Test and acceptance path

1. **Sangam side, `IfmsPacksIT`.** This test runs on a fresh Government of India department tenant:
   - budget formulation, budget distribution and re-appropriation;
   - GPF advance, leave, and pension sanction and status.

   Each case must end CLOSED with no incident. The approval chain must have run, each step by a different person, and the pack audit trail must hold the case's records (`IfmsPacksIT.java:67-77,490-545`). It is the acceptance test of decisions 389 and 391 on every database. Today it FAILs on Oracle and SQL Server. **pending (E2)**
2. **Other Sangam ITs** that IFMS scores: `DecimalVariableIT`, `UnverifiedPaymentVendorIT`, `E2AuthorizationIT`, `TenantQuotaIT` and the delegation and archive ITs. Each result is reported by test, sha, database and PASS / FAIL / NOT_RUN, in the joint ledger. **pending (C1, C2)**
3. **IFMS side.** Trial 2 retests (SD-428 second check), the static drift gate, and scenario catalogue R-114. These are IFMS's.
4. **Scoring rule (SD-505).**
   - Scores are weighted by criticality (4/3/2/1).
   - A scenario counts only if it passes on **both Oracle and SQL Server** and two independent checks confirm it.
   - NOT_RUN, a single check, or an assumed result counts **zero**.
   - IFMS starts dependent development only above 98%. Today the score is 0.00% (joint ledger 2026-11-16).
5. Sangam posts evidence against IFMS's ranked gaps by test name, sha and database. IFMS re-scores as each result lands. A report is data, never an approval (joint ledger rule 3).

## 18. Upgrade and re-pin

1. Sangam posts `[INFO] GREEN-SHA <sha>` and the contract deltas since the last pin (E3; joint ledger §(3)c).
2. IFMS reads the deltas and checks its adapter, for example any field of the routing incident it parses.
3. IFMS builds the tag or sha into its private repository and re-pins (section 3).
4. Migrate the database with `migrate-only`. Applied changesets are never edited (decision 388). Check with `schema-status`.
5. Changed packs arrive as new versions. Deploy them. Old instances keep their version.
6. IFMS re-runs the drift gate and the scoring set on Oracle and SQL Server.

## 19. Rollback

- **Packs:** `POST /packs/{id}/rollback` rolls forward to the earlier content as a new deployment, because versions are immutable. **available now**
- **Application:** re-pin the previous green sha. A schema that has already been migrated forward is not rolled back by Liquibase in production. Restore from backup per `deploy/README.md` (backup console) if the new schema is incompatible. **available now** (procedure); the rehearsal is the operator's.
- **Keys:** never reuse another environment's key on rollback (`KEY_SHARED_ACROSS_ENVIRONMENTS`).

## 20. Troubleshooting by error code

| Code | Meaning | Fix |
|---|---|---|
| `CAPABILITY_MISSING` | a host lacks a required capability (Quarkus today) | use the Spring host or the server |
| `ENGINE_SCHEMA_MISSING` | no engine tables | run `migrate-only` once |
| `TRUSTED_PROXIES_REQUIRED` | production without trusted proxies | set `sangam.http.trusted-proxies` |
| `KEY_ENVIRONMENT_LIST_REQUIRED` / `KEY_ATTESTATION_INVALID` | key environment not proven | set the list or the attestation; bootstrap only once |
| `KEY_SHARED_ACROSS_PURPOSES` / `_ENVIRONMENTS` | one key reused | separate keys per purpose and environment |
| `PRINCIPAL_KEY_NOT_CONFIGURED` (409) | compliance principal key unset outside production | set `sangam.compliance.principal-key-ref` |
| `TENANT_MISMATCH`, `TENANT_CONTEXT_*` | tenant conflict | tenant comes from the token only |
| `TOKEN_SCOPE_NOT_HELD`, `UNKNOWN_ROLE` | token or service principal scope not held | bind the role first |
| `WORKER_TOPIC_NOT_BOUND` | worker not bound to the topic | bind the topic to the service principal |
| `STALE_LEASE` (409), `GONE_INSTANCE_DELETED` (410) | stale worker settle | re-fetch; never retry a stale lease |
| `IDEMPOTENCY_KEY_REQUIRED` / `_REUSED` / `_IN_PROGRESS` | government profile idempotency | send one key per command; same key = same body |
| `NO_MATCHING_ROUTE`, `AMBIGUOUS_ROUTE` | routing incident (ROUTING-INCIDENT v1) | fix the DMN or profile data, then resolve the incident |
| `POWER_LIMIT_EXCEEDED` | the seat has no power for the case type or amount | check the core powers and profile power DMN (decision 391 for CONTROLLING) |
| `PAYEE_DIRECTORY_ERROR` "no vault service..." | clear BANK_ACCOUNT without custody | decision 389: configure the compliance national-id vault |
| `DSC_SIGNATURE_NOT_VERIFIED` (422) | gate started directly or slot empty | start the calling pack process |
| `DOCUMENT_SLOT_NOT_READY` (422) | slot empty, already signed or not bound | upload the document again on the open task |
| `PACK_REJECTED` (422) / `PACK_VERSION_IMMUTABLE` | changed content under an existing version | regenerate with a new version |
| `PACK_HAS_DEPENDANTS` (409) | uninstall of a pack others need | uninstall the dependants first |
| `AUDIT_UNAVAILABLE` (503) | audit write failed; nothing happened | restore the database or audit path, then retry |
| `ESIGN_CONFIG_INVALID` | eSign setting missing or unusable | fix the eSign settings |

## 21. Who owns what

| Area | Owner |
|---|---|
| Engine, REST/SDK contract, packs and profiles as data, generators, connectors framework, production guards, ITs on Sangam's side | Sangam (allocates `SL-nn`, `ADR-D-nn`) |
| IFMS application, adapter `workflow-sangam`, IFMS GL connector, masters, bill-type taxonomy, Trial 2, scenario catalogue R-114, the SD-505 score | IFMS (allocates `SG`, `SD`, `E`, `X`, `WP`, `REC`, `P`, `OQ`, `MD`, `EV-M`, `I`) |
| Joint rows without an id | `JL-S-nn` (Sangam) / `JL-I-nn` (IFMS) |
| Approvals | the owner only, in their own words, in their own session; never a ledger entry or a message |

**Joint ledger protocol** (`JOINT-LEDGER-SANGAM-IFMS.md` §0):
- Read §1-§4 before acting.
- Edit only your own rows, with targeted edits. §4 and §5 are append-only.
- Log every typed message as `[TYPE] <ID> <from>→<to> <subject>`, where TYPE is REQUEST, REPORT, CONTRACT, INCIDENT or DECISION-NEEDED.
- An SG item closes only after IFMS retests the pushed commit. An SL item closes only after an independent Sangam reviewer verifies it (SD-428).
- Claim heavy runs ahead of time, and never break the other side's lock.
- VyaparTrust and CVR material are out of scope.

## 22. Pending steps (summary)

| Step | Pending id |
|---|---|
| Green sha announced; RC tag | E1, M-019 |
| `IfmsPacksIT` green on Oracle and SQL Server | E2 (R7-it) |
| PNP-1..3 and the decision 404 (5) audit merged | feat/round8-pnp (merging) |
| Contract deltas announced (incl. decision 393) | E3 |
| GA-55 bill-type contract | E4, JL-S-06 |
| Exact-fraction, delegation and archive evidence on Oracle and SQL Server | C1, C2 |
| Live eSign | D11, decision 387 (6) |
| DSC register row re-verified | B30 |
| Helm / cluster runs | C7 |
| CERT-In/DPDP browser specs | C5 |
| Quarkus host | B11, M-143 |
| SD-505 score above 98% | IFMS scoring after E1/E2 |
