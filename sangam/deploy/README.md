# Sangam deployment and operations (WS-DEPLOY)

This directory holds everything needed to build, ship, install and run Sangam:

- container images;
- docker-compose stacks for the four Tier-1 databases;
- a Helm chart;
- the air-gapped bundle;
- the licence gate;
- the operations runbooks.

The CI workflows sit at the **repository root** in `.github/workflows/`, because GitHub only reads workflows from there (see [CI](#9-ci-github-actions)).

Requirements covered:

| Area | Requirements |
|---|---|
| Deployment targets | IX.6: State Data Centre VMs or Kubernetes, air-gapped, MeghRaj/NIC cloud |
| Availability | ADR-D-12: RPO ≤ 5 min, RTO ≤ 1 h, 99.5 % |
| Database test matrix | CF-11 |
| Licences | X.3 / X.3a (LIC-1…11) |
| Migrations | R12 (single Liquibase job, failed-migration recovery) |
| Telemetry | R13 (OpenTelemetry, privacy) |
| Database tiers | R17 |
| Caches and soak | R30 (bounded caches, soak hooks: metrics and dashboards) |
| Upgrades | R32 (N/N-1 rolling upgrade) |
| Hosting | R51 (log retention), R56 (no outbound calls by default) |

Also: `SUPPORT-MATRIX.md` (R5, R7: JDK, framework, driver and deployment matrix, native-light APIs, supported browsers) and `evidence/` (R52: audit evidence pack).

**Runtime since this update:**

- JDK 25 is the default image runtime.
- Spring Boot is 4.1.2.
- Environment names follow `sangam-server/src/main/resources/application*.yaml` (`SANGAM_DB_URL`, `SANGAM_OIDC_ISSUER`, `SANGAM_OTLP_ENABLED`, ...).
- `server.Dockerfile` also builds the SCALE apps through `--build-arg APP_MODULE=`.
- Writable state is `/var/lib/sangam`. It holds the compliance log archive, so back it up and keep it at least 180 days (R51). Helm: `server.state`, ReadWriteMany.
- SCALE on compose: `docker compose -f docker-compose.yml -f docker-compose.scale.yml --profile postgres up -d`. On Helm: `scale.*` (`examples/values-scale.yaml`).

**Supported browsers for the UI (owner decision 309):** Chrome and Edge 111 or later, Firefox 114 or later, Safari 16.4 or later. This is the Vite 8 default build target (`baseline-widely-available`), and `sangam-ui/vite.config.ts` deliberately sets no `build.target` override. Older browsers are not supported: the built JavaScript and CSS may use syntax they cannot parse. The same baseline applies to the embeddable web components (`npm run build:embed`). See `SUPPORT-MATRIX.md` section 9 and `sangam-ui/README.md`.

---

## 0. Layout

```
deploy/
├─ docker/
│  ├─ server.Dockerfile        Spring Boot server
│  │                           targets: runtime (Temurin JRE 25, uid 10001), runtime-distroless (distroless java25, uid 65532);
│  │                           JDK 21 variant: --build-arg JRE_IMAGE=eclipse-temurin:21.0.8_9-jre-noble@sha256:20e7f7288e1c18eebe8f06a442c9f7183342d9b022d3b9a9677cae2b558ddddd
│  │                           --build-arg DISTROLESS_IMAGE=gcr.io/distroless/java21-debian12:nonroot@sha256:7e37784d94dccbf5ccb195c73b295f5ad00cd266512dfbac12eb9c3c28f8077d
│  ├─ ui.Dockerfile            Vite build served by nginx-unprivileged (uid 101, :8080, runtime /config.json)
│  ├─ simulators.Dockerfile    government-system simulators (dev/test only)
│  ├─ server/                  SOURCE-OFFER.txt (MariaDB, LIC-5), drivers README (bring your own driver, LIC-2/3/4)
│  └─ ui/                      nginx template, security headers / CSP
├─ compose/
│  ├─ docker-compose.yml       profiles: postgres | oracle | sqlserver | mysql, plus simulators, observability
│  ├─ .env.example
│  ├─ db/                      per-database init (SQL Server RCSI, MySQL READ COMMITTED / utf8mb4, ...)
│  ├─ keycloak/                generate_realm.py → realm/sangam-realm.json (roles, groups, P1–P4 test seats)
│  ├─ otel/                    collector-base.yaml (exports nowhere), collector-observability.yaml
│  └─ observability/           Prometheus, Grafana provisioning + "Sangam overview" dashboard
├─ helm/sangam/                chart
│                              server, UI, HPA, PDB, NetworkPolicy, Liquibase job, backup CronJobs, examples/
├─ airgap/                     images.txt, save-images.sh/.ps1, load-images.sh/.ps1, verify-bundle.sh,
│                              generate-sbom.sh/.ps1
└─ licence/                    licence-policy.json, check_licences.py, dependency-check-suppressions.xml
```

---

## 1. Install

### 1.1 Developer / pilot laptop (docker compose)

```bash
cd sangam/deploy/compose
cp .env.example .env             # change every password
docker compose --profile postgres up -d --build
# or: --profile oracle (put ojdbc11.jar into ./drivers first), --profile sqlserver, --profile mysql
# add-ons: --profile simulators   --profile observability (and set OTEL_COLLECTOR_CONFIG=collector-observability.yaml)
# PDF validator and OCR sidecars: add -f docker-compose.sidecars.yml --profile pdf --profile ocr
```

Choose exactly **one** database profile at a time. Each profile starts its own `server-<db>` service, all under the network alias `sangam-server`.

**Production switch (decisions 143 and 198).** `SANGAM_SECURITY_PRODUCTION` is always set explicitly; a missing or
invalid value stops the server. `docker-compose.yml` defaults it to `true`, which turns every production guard on
(https and non-loopback endpoints, no dev tokens; a dev profile is ignored with a warning). The compose defaults use
`http://localhost:8180` for Keycloak, which a production server refuses, so on a developer laptop set
`SANGAM_SECURITY_PRODUCTION=false` in `.env` (non-production: guards off, dev defaults such as dev tokens still
refused). Dev defaults need `false` plus a dev profile (`dev`, `h2`, `test` or `local`), as
`docker-compose.cells.yml` does. Never set `false` on a real deployment.

**System audit scope (decision 144).** `SANGAM_ENGINE_SYSTEM_AUDIT_SCOPE` (`sangam.engine.system-audit-scope`) is
the audit scope of shared and system-owned engine work. It is required and has the form `system:<name>`, the whole
value matching `system:[a-z0-9][a-z0-9._-]{0,47}` (lower-case letters, digits, `.`, `_`, `-`; at most 48 characters
after the prefix). A missing, empty or invalid value stops the server. `docker-compose.yml` and `.env.example`
default it to `system:platform`, the same as the server's `application.yaml`. It can never be a customer tenant:
tenant ids have no `:`, and an identity whose tenant is a system scope is refused.

**PDF validator and OCR sidecars (M-081, M-103; decisions 114, 153, 167).** `--profile pdf` and `--profile ocr` start
`pdf-validator` (port 8085) and `ocr` (port 8086, built with `UBUNTU_SNAPSHOT` from
`deploy/licence/ocr-build-args.env`). The one-shot `sidecar-secrets-init` (`sidecar-secrets-init.sh`) makes demo
secrets at start into named volumes: a random token and a PKCS#12 key store (self-signed certificate for the service
name) per sidecar, and for the server a copy of each token and a trust store (the JRE CA list plus both
certificates). Nothing is committed; existing secrets are kept, delete the volumes to renew them. The sidecars are
read-only containers on the internal `sangam-sidecars` network (no outbound network, ports not published).
`-f docker-compose.sidecars.yml` wires the server: `SANGAM_PDF_VALIDATOR_URL`/`SANGAM_OCR_URL` (https),
`*_TOKEN_FILE`, `*_TRANSPORT=tls` and the trust store through `JDK_JAVA_OPTIONS`. With only one profile, set the other
URL empty in `.env` (that feature stays off, fail closed).

| URL | What |
|---|---|
| http://localhost:3000 | UI (proxies `/api` and `/engine-rest` to the server) |
| http://localhost:8080/api/v1 | Sangam API |
| http://127.0.0.1:8081/actuator/health | management (bound to localhost only) |
| http://localhost:8180 | Keycloak (realm `sangam`) |
| http://127.0.0.1:16686, :9090, :3001 | Jaeger, Prometheus, Grafana (observability profile) |

The compose database settings match the Stage B Testcontainers set (`spike/RUN-QUEUE.md`):

| Database | Image | Settings |
|---|---|---|
| PostgreSQL | `postgres:17` | UTC, read committed, `wal_level=replica` |
| Oracle | `gvenzl/oracle-free:23-slim-faststart` | PDB `FREEPDB1`, app user `sangam` |
| SQL Server | `mssql/server:2022-latest` | `sqlserver-init` sets **READ_COMMITTED_SNAPSHOT ON** and FULL recovery |
| MySQL | `mysql:8.4` | `--transaction-isolation=READ-COMMITTED`, utf8mb4 / utf8mb4_bin, UTC, ROW binlog + GTID; connected through the **shipped MariaDB Connector/J** |

**Test seats.** The realm comes with these test seat holders:

| Pack | Users |
|---|---|
| P1 | `p1.initiator1/2`, `p1.checker1/2`, `p1.approver1` (band 1), `p1.approver2` (band 2), `p1.treasury1` |
| P2 | `p2.da1`, `p2.so1/2`, `p2.us1` (also holds the DS-1 seat as additional charge), `p2.ds1`, `p2.ca1` |
| P3 | `p3.applicant1`, `p3.verifier1/2`, `p3.sanction1`, `p3.finance1` |
| P4 | `p4.citizen1`, `p4.clerk1`, `p4.inspector1/2`, `p4.approver1` |
| Department staff | `dept.admin` (tenant administrator of `dept-finance`), `operator.dev`, `auditor.dev`, `modeller.dev`, `reviewer.dev`, `approver.dev` |
| Platform realm `sangam-platform` | `admin.dev` (platform administrator), `platform.operator` |

All of them use the development password set in `keycloak/generate_realm.py`. Never reuse it.

**Platform realm (SL-70).** Only tokens from a separately configured platform issuer are platform identities:
`SANGAM_SECURITY_PLATFORM_ISSUER` (comma-separated issuers; compose sets the `sangam-platform` realm, with
`SANGAM_SECURITY_PLATFORM_JWK_SET_URI` for its keys). `sangam-admin` in a platform token is the platform administrator
(onboarding, other tenants, engine-wide history cleanup, tenant creation, actuator). `sangam-admin` in any other token is
the tenant administrator (`sangam-tenant-admin`) of the token's tenant. With no platform issuer configured, nobody is a
platform administrator. The platform issuer must differ from the tenant issuer; startup fails otherwise.

Tokens carry these claims: `tenant`, `seats[]`, `office`, `additional_charge[]`, `roles[]`, `groups[]` and `aud=sangam-server`.

Clients:

| Client | Type |
|---|---|
| `sangam-ui` | public, PKCE S256 |
| `sangam-worker` | client credentials |
| `sangam-cli` | password grant, dev only |

To change the realm, edit `keycloak/generate_realm.py`, run `python -I deploy/compose/keycloak/generate_realm.py`, and commit both files. CI checks that they match.

### 1.2 Kubernetes (Helm), State Data Centre / MeghRaj / NIC cloud

Prerequisites:

- Kubernetes ≥ 1.30 (the native `preStop.sleep` action is used);
- an ingress controller;
- a Tier-1 database prepared as in [§1.4](#14-database-preparation-all-installs);
- an OIDC issuer: Keycloak, or Parichay / a state IdP brokered through Keycloak (R50).

```bash
kubectl create namespace sangam
kubectl -n sangam create secret generic sangam-db --from-literal=username=sangam --from-literal=password='<pw>'
# optional key material (draft encryption, audit/package signing, connector client certs):
kubectl -n sangam create secret generic sangam-keys --from-file=keys/
helm upgrade --install sangam deploy/helm/sangam -n sangam \
  -f deploy/helm/sangam/examples/values-postgres.yaml \
  --set server.keys.existingSecret=sangam-keys
kubectl -n sangam get jobs,pods      # the migrate job runs first (pre-install hook)
```

Chart behaviour worth knowing:

- **Secrets.** Every secret comes in through `existingSecret`. The chart never creates credentials.
- **Database choice.** `database.type` is one of `postgres | oracle | sqlserver | mysql`. The chart builds the JDBC URL for that type, with the mandatory session settings. You can also set `database.jdbcUrl` yourself.
- **Oracle driver.** Oracle needs a customer-supplied driver: `drivers.existingClaim`, or `drivers.initImage` (an image you build with `/drivers/ojdbc11.jar`). The chart refuses to render without one (LIC-2). Exact steps: [§1.5](#15-adding-the-oracle-jdbc-driver-customer-supplied-lic-2).
- **Liquibase run mode** (`liquibase.mode`):
  - `job` (default, R12): a single pre-install/pre-upgrade hook Job. It runs `SANGAM_LIQUIBASE_MODE=migrate-only`, which applies the Liquibase session lock, the product changelogs and the Operaton schema, and then exits. Server pods only `validate`.
  - `startup`: every pod migrates under the Liquibase lock. Use it for dev or a single node.
  - `none`: the DBA applies SQL out of band. Pods validate.
- **Probes.** All probes use the management port 8081:
  - startup: `/actuator/health/liveness`, up to 6 min;
  - liveness: `/actuator/health/liveness`;
  - readiness: `/actuator/health/readiness`.
- **Scaling and disruption.**
  - HPA on the server, with a slow scale-down so job-executor leases can drain.
  - PDB `minAvailable: 1` for the server and the UI.
  - Rolling update with `maxUnavailable: 0`.
  - Topology spread over hosts and zones.
- **NetworkPolicy** (on by default):
  - The server accepts traffic from the UI, the ingress controller, pods labelled `sangam.io/api-client=true` (workers), and monitoring on 8081.
  - Egress goes only to DNS, the database, the OIDC issuer CIDRs, and the OTel collector (when enabled). Anything else goes in `networkPolicy.extraEgress`, for example connector endpoints. With the defaults there is no internet egress (R56).
- **Hosting behind an edge / reverse proxy (SL-18).** The server owns no browser-facing host name; every externally
  visible URL comes from configuration. `server.production: true` (default) sets `sangam.security.production`, and
  start-up then **fails** if an externally used URL (`spring.datasource.url`, OIDC issuer/JWKS, `sangam.*-url`) is
  unset or points at `localhost`/`127.0.0.1`/`::1`. Forwarding headers (`Forwarded`, `X-Forwarded-*`, and any header
  listed in `server.clientIpHeaders`, e.g. an edge's connecting-IP header) are honoured **only** when the direct peer
  is in `server.trustedProxies` (CIDR list, empty = trust nobody; env `SANGAM_TRUSTED_PROXIES`,
  `SANGAM_CLIENT_IP_HEADERS`); from any other peer they are stripped. Spring's own handling is off
  (`server.forward-headers-strategy=none`).
- **Key material required on a non-dev profile.** The server refuses to start without: a key-encryption key
  (`sangam.security.kek.keys.<id>` + `sangam.security.kek.current-id`, or `sangam.security.kek.pkcs12.*`), the registry
  signer (`sangam.registry.signer.private-key` PKCS#8 + `.public-key` X.509, Ed25519, base64) and the identifier-vault
  pepper (`sangam.registry.vault.hmac-pepper`, base64). Supply them as env from a Secret (`server.envFrom`), never in
  values.
- **Explicit production switch (decisions 143 and 198).** The chart always renders `SANGAM_SECURITY_PRODUCTION` from
  `server.production` (default `true`, boolean in the schema); a missing or invalid value stops the server. With
  `true` a dev profile in `server.springProfiles` is ignored with a warning; `false` together with a production
  profile is refused as contradictory. Keep `true` on every cluster install.
- **System audit scope (decision 144).** The chart always renders `SANGAM_ENGINE_SYSTEM_AUDIT_SCOPE` from
  `server.systemAuditScope` (default `system:platform`). The schema enforces the pattern
  `^system:[a-z0-9][a-z0-9._-]{0,47}$`, so an invalid value fails at `helm install`/`helm template`; the server
  refuses a missing or invalid value at start as well. It can never be a customer tenant.
- **One production predicate (SL-109).** The server is in production mode when `sangam.security.production=true`
  (Helm `server.production: true`, env `SANGAM_SECURITY_PRODUCTION`) **or** a Spring profile containing `prod` is
  active (`org.sangam.core.security.ProductionMode`, `config/ProductionEnvironment`). Every production guard uses this
  one predicate, so it can only make a guard stricter: hosting URLs, key material, database credentials, the debugger
  (start-up fails instead of silently skipping it), the simulator fallback, the health simulator, the webhook and live
  HTTP rules, modeller mocks, the dev-token filter, the dev bootstrap, and the onboarding and plugin guards. Before
  SL-109 several of these keyed only on a `prod` profile and did not fire under Helm.
- **Database credentials (SL-103).** In production the server refuses to start when `SANGAM_DB_USER` or
  `SANGAM_DB_PASSWORD` is unset, or when the password equals the default shipped in the database profile
  (`application-<db>.yaml`); a value set directly must not be blank. The message names the variable and never prints the
  value. The user name `sangam` stays allowed, because it is the documented application user
  (`config/ProductionDatabaseCredentials`, run inside the `HostingWiring` production guard before any bean is created).
- **Start checks that fail closed (decisions 143 and 152).** Three more refusals stop the start by name:
  - `DOCUMENT_ERASER_REQUIRED`: data erasure has no document eraser, so an erasure would scrub the database, report
    success and leave every document of the instance in the document store. The server's own eraser
    (`ContentDocumentEraser`) comes with the ports module: keep `sangam.modules.ports.enabled=true` (the default).
  - `SHARED_ISOLATION_DIRECTORY_REQUIRED`: a shared instance (no `sangam.tenancy.dedicated-tenant`) has no tenant
    directory (`AdminPort`), so it could not refuse tenants recorded with isolation `DATABASE` or `SCHEMA`
    (decision 97) and would serve them.
  - `AUDIT_CHECKPOINT_VERIFICATION_UNAVAILABLE`: `sangam.audit.checkpoint.trusted-public-key-refs` is set, but the
    audit port is not the JDBC audit adapter, so audit verification would not check the signed checkpoints. Use the
    JDBC audit adapter or remove the property.

  The first two apply everywhere except a validated development environment (`SANGAM_SECURITY_PRODUCTION=false`
  together with a dev profile, section 1.1; a missing or contradictory setting counts as production), where the
  server starts with a `DEVELOPMENT:` warning instead. The third applies in every environment. In the same way
  `sangam.packs.simulator-fallback` is a development default only: unset, it is on only with the `h2` profile in a
  validated development environment; set to `true` in production it refuses the start.
- **No country defaults (SL-104, ADR-D-20).** `SANGAM_CELL_JURISDICTION` (`sangam.cell.jurisdiction`) is required on a
  cell: start-up fails naming the property (`CellWiring.jurisdictionAndResidency`), and the chart refuses to render
  `scale.cell.id` without `scale.cell.jurisdiction`; the residency defaults to the jurisdiction.
  `SANGAM_LEDGER_RATE_SCOPE` (`sangam.ledger.rate-scope`) has no default: same-currency postings need none, and a
  cross-currency conversion with it unset fails naming the property (`LedgerWiring.profileRates`). The `IN` values in
  the compose files are development values only.
- **Promotion environment (SL-42).** `SANGAM_ONBOARDING_ENVIRONMENT` (`sangam.onboarding.environment`, Helm
  `server.environment`, for example `sandbox`, `uat`, `prod`) labels this environment for promotion bundles. It has no
  default; while it is unset the promotion endpoints answer 409 `PROMOTION_ENVIRONMENT_NOT_SET`.
- These guards are coded and their tests are written (`ProductionGuardsTest`, `ProductionModeTest`,
  `ProductionPredicateGuardTest`, `ProductionDatabaseCredentialsTest`, `PromotionControllerTest`); they have not yet
  been run.
- **Private exposure (SG-39, Q-09).** `ingress.enabled` is `false` by default (no public inbound). A private ingress is
  `ingress.enabled=true` plus `ingress.private.enabled=true` and either `ingress.private.preset` (`aws`, `azure` or
  `gcp`) or `ingress.private.className` (an internal class, required with no preset). The presets set the internal
  class and internal-load-balancer annotation (`alb` with `alb.ingress.kubernetes.io/scheme: internal`;
  `azure-application-gateway` with `appgw.ingress.kubernetes.io/use-private-ip: "true"`; GKE
  `kubernetes.io/ingress.class: gce-internal`), and they replace the same keys in `ingress.annotations`. With
  private=true a public `ingress.className` is refused twice: by `values.schema.json` and by a `fail` in
  `templates/ingress.yaml` (which also holds when schema validation is skipped). With an AWS ALB in IP target mode the
  load balancer reaches the pods directly from its subnets, not from `networkPolicy.ingressControllerNamespace`: list
  those subnet CIDRs in `networkPolicy.ingressFrom.cidrs` (default empty), which the server and UI NetworkPolicies
  admit as `ipBlock` sources next to the ingress controller namespace (or use instance mode).
  Management (8081) is a separate ClusterIP Service and is never routed by the ingress; NetworkPolicy admits it only
  from `networkPolicy.monitoringNamespace`. Egress is an allow-list (`networkPolicy.extraEgress`).
- **Service tokens to an edge.** An outbound HTTP connector's default header whose value is a secret reference
  (`secret://...`) is resolved from the secret port at call time, so edge service-token header pairs never sit in
  configuration.
- **Pod security.** Pods run as non-root with a read-only root filesystem, all capabilities dropped and seccomp `RuntimeDefault`.
- **Time zone (SL-53).** `server.javaToolOptions` must keep `-Duser.timezone=UTC` (the image sets `TZ=UTC` as well).
  The server refuses to start in a JVM with any other default zone, so an override that drops it fails the rollout
  instead of storing ambiguous timer due dates. Set the business zone of timers with `sangam.engine.timer-zone`.

### 1.3 VMs without Kubernetes

Run the same images with Docker or Podman, using `deploy/compose/docker-compose.yml` as the template:

- Use the server services only, pointed at the external database: set `SPRING_DATASOURCE_*`.
- Put a reverse proxy (nginx / HAProxy) in front of 2+ server VMs.
- Drop the database containers.

For a JVM without containers:

```bash
java -Dloader.path=/opt/sangam/lib-ext,/opt/sangam/drivers -cp sangam-server.jar \
     org.springframework.boot.loader.launch.PropertiesLauncher
```

The MariaDB jar goes in `lib-ext`, and your own drivers go in `drivers`.

Give a JVM without containers the same options as the image (`server.Dockerfile`):

```bash
JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:+UseG1GC -Duser.timezone=UTC \
  -Dfile.encoding=UTF-8 -Djava.io.tmpdir=/opt/sangam/tmp -Djdk.net.unixdomain.tmpdir=/opt/sangam/tmp"
```

**Every engine JVM runs in UTC (SL-53).** The server, and every application that embeds the engine (for example the
IFMS engine host), refuses to start unless the JVM default time zone is UTC (offset 0, no daylight saving time; a zone
without DST such as Asia/Kolkata is refused too): keep `-Duser.timezone=UTC` in every launch. Operaton stores timestamps in zone-less `TIMESTAMP`
columns as JVM-local wall time; in a zone with daylight saving time the repeated hour of an autumn fold is ambiguous
and a timer firing is lost. The check runs before any bean (and again in the engine bootstrap, before the first
database connection), the zone is never changed silently, and there is no switch to turn the check off. The business
zone of timers (calendar cycles such as `R/P1D`, cron expressions, durations in days, weeks or months, and the DST
timer guard) is `sangam.engine.timer-zone` (env `SANGAM_ENGINE_TIMERZONE`; default `UTC`, never the JVM zone: set it,
for example to `Asia/Kolkata`, wherever timers express business cut-offs); elapsed periods (`PT1H`) run on the UTC
timeline; a date-time written without an offset in a timer expression is UTC (write `17:00+05:30` for 17:00 IST). An
unknown or unreadable JVM zone (for example a typo in `-Duser.timezone`, which the JDK silently replaces by a GMT
offset) is refused as well. Business dates take the tenant zone from the tenant profile (`BusinessClock`). An existing
engine database written by a JVM in another zone holds wall times of that zone: moving it to UTC is a data migration
(SL-53a, UTC persistence), not a restart.

**UTC conversion gate (decision 262).** While the operator-run UTC conversion tool (sangam-ops `UtcConversionTool`,
M-114) moves a run to `APPLYING` or `ROLLING_BACK`, it closes the engine-work gate (`OPS_UTC_GATE`, one row in the
database): no server acquires an engine job or fetches an external task while it is closed. If that run fails or is interrupted, the gate **stays closed**, and engine
work stays stopped, until the operator resumes the run to `APPLIED` or rolls it back to `ROLLED_BACK`; only then does
it reopen. There is no forced reopen, so plan the conversion in a maintenance window and finish or roll back every
started run.

On a **Windows** host, set both temp directories to a folder the service account owns outside the user profile
(for example `D:\sangam\tmp`). The JDK's Windows selector opens an AF_UNIX socket in `jdk.net.unixdomain.tmpdir`
(default: the user temp folder). Endpoint-protection filters on `C:\Users\...` can block such sockets; the server
then fails at start-up with `IOException: Unable to establish loopback connection`. The build sets the same
property for its test JVMs (root `pom.xml`, surefire and failsafe).

On VMs, run the database migrations **once** before starting the nodes:

```bash
SANGAM_LIQUIBASE_MODE=migrate-only java ... (same command)
```

Do not add `SPRING_MAIN_WEB_APPLICATION_TYPE=none`: the Operaton REST (Jersey) starter needs the servlet context and
the run fails without it. The one-shot modes already bind both ports to 0 (random, nothing exposed) and exit 0 when done.

Then start every node with `SANGAM_LIQUIBASE_MODE=validate`.

A serving start never creates the engine tables (`operaton.bpm.database.schema-update: false`, the server's default
and, outside a validated development environment, the embedding starter's, decision 334). On a database without them
the start is refused with `ENGINE_SCHEMA_MISSING` before the product changelog or anything else touches the database,
instead of the engine's raw `ENGINE-03057`: run the `migrate-only` step above (or apply the SQL of `update-sql`) first.
An explicit `SANGAM_ENGINE_SCHEMA_UPDATE=true` (database profiles) is the operator's decision and is honoured.

#### Backup console in script mode (decision 220)

Without Kubernetes, the operations backup console (Ops > Backups) can run **your** backup scripts on the server host
and show their runs. It is off by default, and Sangam ships no backup scripts. Settings (`sangam.backup.console.script`,
in `config/application.yaml` or as environment variables):

| Property | Environment variable | Meaning |
|---|---|---|
| `enabled` | `SANGAM_BACKUP_CONSOLE_SCRIPT_ENABLED` | `true` switches script mode on (default `false`). Cannot be on together with `sangam.backup.console.kubernetes.enabled` (the Helm Job mode): the server refuses to start. |
| `history-dir` | `SANGAM_BACKUP_CONSOLE_SCRIPT_HISTORYDIR` | Required, absolute. Run history: one JSON and one `<run>.log` per run, the newest 100 per target, a lock file per target. With 2+ server VMs it must be one shared directory (NFS or similar) that every server sees at the same path. |
| `env-passthrough` | `SANGAM_BACKUP_CONSOLE_SCRIPT_ENVPASSTHROUGH` | Comma-separated extra variable names the scripts receive; `PATH`, `HOME`, `LANG`, `TZ` and the temp variables always pass, nothing else does. |
| `targets[i].id` | | Lower-case letters, digits and `-`; unique. |
| `targets[i].name` | | Label in the console (default: the id). |
| `targets[i].command[j]` | | The command: `command[0]` is the absolute path of the executable, the rest are its arguments. No shell runs it. |
| `targets[i].schedule` | | Shown only (for example `daily 01:00 by cron`); Sangam does not schedule the script, your cron or timer does. |
| `targets[i].timeout` | | ISO-8601 duration, default `PT2H`, at most 24 hours. |

```yaml
sangam:
  backup:
    console:
      script:
        enabled: true
        history-dir: /srv/sangam-backup/console-history
        env-passthrough: PGPASSFILE
        targets:
          - id: pg-full
            name: PostgreSQL full backup
            command: [/opt/sangam/backup-scripts/pg-full.sh, --verify]
            schedule: daily 01:00 (cron)
            timeout: PT3H
```

Requirements on the host:

- The scripts run with the server's own user and privileges. The scripts, their directory and the history directory
  must belong to that user (or root) and must not be writable by anyone else.
- The scripts need the database client tools (`pg_dump`/`pg_basebackup`, `mysqldump`/`mysqlbinlog`, `sqlcmd`, RMAN).
  The stock server image has none: on a VM install them on the host; with containers build your own image `FROM`
  `sangam-server` that adds the client your script calls.
- Credentials come from files or variables your script reads (for example a `.pgpass` named by `PGPASSFILE`); never
  put them in `command` (it is plain configuration), and keep the script from printing them: its output is the run
  log in the history directory.

Compose example (off by default): `docker compose -f docker-compose.yml -f docker-compose.backup-console.yml ...`
with `SANGAM_BACKUP_CONSOLE_SCRIPT_ENABLED=true` in `.env`. It keeps the history on the existing `backup` volume
(`/backup/console-history`, created once with owner uid 10001 and mode 0770), mounts `SANGAM_BACKUP_SCRIPTS_DIR`
read-only at `/opt/sangam/backup-scripts`, and passes `SANGAM_BACKUP_CONSOLE_SCRIPT_ENV_PASSTHROUGH`; put the
targets in `config/server/application.yaml`.

### 1.4 Database preparation (all installs)

| DB | Must have | Recommended |
|---|---|---|
| PostgreSQL 15–17 | UTF8 database, `timezone=UTC`, `default_transaction_isolation='read committed'`, user owning schema | `wal_level=replica`, physical replica at DR site, `max_connections` ≥ pods × (pool + leaf pools, section 1.6) + 20 |
| Oracle 19c/21c/23ai | AL32UTF8, app user with CONNECT, RESOURCE, CREATE VIEW/SEQUENCE/PROCEDURE/TRIGGER/TYPE, quota | ARCHIVELOG mode, FRA, Data Guard standby at DR |
| SQL Server 2019/2022 | `ALTER DATABASE sangam SET READ_COMMITTED_SNAPSHOT ON` (the server refuses to start without it), UTF-8 collation (`*_UTF8`) | FULL recovery model, Always On AG (async replica at DR) |
| MySQL 8.4 | `transaction_isolation=READ-COMMITTED`, `utf8mb4` / `utf8mb4_bin`, `default_time_zone='+00:00'`, `binlog_format=ROW` | GTID on, `sync_binlog=1`, `innodb_flush_log_at_trx_commit=1`, async replica at DR |

For SQL Server and MySQL, the scripts in `compose/db/` are the reference.

**CockroachDB: experimental only (decisions 156 and 205).** CockroachDB is not a supported database, no release check
runs on it, and nothing here claims it works. The server refuses a CockroachDB server unless
`SANGAM_EXPERIMENTAL_COCKROACHDB=true` (default off); then it logs an EXPERIMENTAL warning at every start and uses the
PostgreSQL dialect. The engine pool still has to run READ COMMITTED, which CockroachDB offers only after
`SET CLUSTER SETTING sql.txn.read_committed_isolation.enabled = true`. Use it for testing only.

### 1.5 Adding the Oracle JDBC driver (customer-supplied, LIC-2)

Sangam never ships the Oracle driver. The server starts through Spring Boot's `PropertiesLauncher` with
`-Dloader.path=/opt/sangam/lib-ext,/opt/sangam/drivers,/opt/sangam/config`, so **every `*.jar` in
`/opt/sangam/drivers` is on the class path**; nothing else has to be configured. Only one Oracle driver jar may be
present (do not mix `ojdbc11` and `ojdbc17`).

**1. Obtain the jar** (Oracle Free Use Terms and Conditions; the customer accepts them). Version tested by Sangam
CI: `com.oracle.database.jdbc:ojdbc11:23.8.0.25.04` (JDK 11+ driver; `ojdbc17` of the same release also works).

```bash
# Internet-connected staging host; copy the file to the air-gapped side with the bundle if needed.
mvn dependency:copy -Dartifact=com.oracle.database.jdbc:ojdbc11:23.8.0.25.04 -DoutputDirectory=./drivers
# or download https://repo1.maven.org/maven2/com/oracle/database/jdbc/ojdbc11/23.8.0.25.04/ojdbc11-23.8.0.25.04.jar
sha1sum ./drivers/ojdbc11-*.jar     # compare with the .sha1 file published next to it on Maven Central
```

**2. Put it where the server reads it**

| Install | Exact step |
|---|---|
| Compose (`--profile oracle`) | copy the jar into `sangam/deploy/compose/drivers/` (mounted read-only on `/opt/sangam/drivers`), then `docker compose --profile oracle up -d` |
| Docker / Podman on a VM | `docker run ... -v /srv/sangam/drivers:/opt/sangam/drivers:ro sangam/server:<v>` |
| JVM without containers | copy the jar to `/opt/sangam/drivers/` and start with the `loader.path` command in §1.3 |
| Helm, PVC | create a PVC, copy the jar into it, set `drivers.existingClaim=<pvc>` |
| Helm, init image (air-gap friendly) | build a tiny image (below), push it to your registry, set `drivers.initImage=<registry>/sangam/ojdbc:23`; the init container runs `cp <initImageDir>/*.jar /opt/sangam/drivers/`, so the image **needs `/bin/sh` and `cp`** |

```dockerfile
# ojdbc.Dockerfile (customer-built, never pushed to a public registry)
FROM busybox:1.36
COPY drivers/ojdbc11-23.8.0.25.04.jar /drivers/
```

```bash
docker build -f ojdbc.Dockerfile -t registry.local/sangam/ojdbc:23 . && docker push registry.local/sangam/ojdbc:23
helm upgrade --install sangam deploy/helm/sangam -n sangam -f deploy/helm/sangam/examples/values-oracle.yaml
```

The chart refuses to render `database.type=oracle` without one of the two driver sources. The migrate Job and every
server pod get the same `drivers` volume.

**3. Verify.** The migrate Job (or the first server) fails fast at start-up with a
driver error for `oracle.jdbc.OracleDriver` / `jdbc:oracle:thin` (no suitable driver) if the jar is missing. With the jar present the log shows the Liquibase run against
Oracle. To check a running container: `docker exec <c> ls -l /opt/sangam/drivers`.

### 1.6 Connection pool sizing (SG-15 T-SCALE)

`SANGAM_DB_POOL_SIZE` (server default 20; Helm `database.pool.maximumPoolSize`, chart default 30) is the number of database connections
one server node may hold. Size it from measurement, not from request concurrency. A request holds a connection
for about `R × t_rt + t_db`, where `R` is the JDBC round trips of the request, `t_rt` the network plus driver time
of one round trip and `t_db` the database work. By Little's law a node serving `X` requests per second needs

    pool = ceil( X × (R × t_rt + t_db) × 1.3 )        (1.3 = headroom for bursts)

and the database must allow `nodes × (pool + leaf pools) + admin/replication slots` connections (`max_connections`).
The leaf pools are separate HikariCP pools on the same database, derived from the main pool's settings: the quota pool
(SL-43, `sangam.quotas.pool.maximum-size`, default 4, while `sangam.quotas.enabled` is true) and the compliance
denial-audit pool `sangam-compliance-audit` (SL-137, `sangam.compliance.denial-audit.pool.maximum-size`, env
`SANGAM_COMPLIANCE_DENIAL_AUDIT_POOL_SIZE`, default 2, while the compliance module is enabled). The security
denial-audit pool `sangam-security-audit` is the third (SL-132, `sangam.security.denial-audit.pool.maximum-size`, env
`SANGAM_SECURITY_DENIAL_AUDIT_POOL_SIZE`, default 2; `SecurityAuditPoolWiring`): sangam-security writes `AUTH_DENIED`,
`SOD_VIOLATION_BLOCKED` and `POWER_LIMIT_EXCEEDED` to their `sec:d:` denial chains through it (`Options.denialTx`), and
the tenant data-key store uses it too (SL-161). The config store pool `sangam-config` is the fourth
(`sangam.config.store.pool.maximum-size`, env `SANGAM_CONFIG_STORE_POOL_SIZE`, default 4, while the config module is
enabled; `ConfigWiring.ConfigStorePool`): the config store (profiles, change marker, master codes, number series,
change register, case bindings) works on it, because a process start reads the profile change marker while its
engine command holds a main-pool connection, and a second main-pool connection there is the SL-43 pool deadlock.
With the defaults that is 12 connections per node on top of `pool` (4 + 2 + 2 + 4), so 32 per node with the server's
default pool of 20 and 42 per pod with the chart's default of 30; a read replica pool counts against the replica's
own limit. The four sizes are set in Helm under `database.pool.leafPools` (`quota`, `securityDenialAudit`,
`complianceDenialAudit`, `configStore`) and in compose by the env `SANGAM_QUOTA_POOL_SIZE`,
`SANGAM_SECURITY_DENIAL_AUDIT_POOL_SIZE`, `SANGAM_COMPLIANCE_DENIAL_AUDIT_POOL_SIZE` and
`SANGAM_CONFIG_STORE_POOL_SIZE` (SL-168); keep the security pool at 2 or more (SL-161). Engine spans (M-062, `sangam.tracing.engine.enabled`, env
`SANGAM_TRACING_ENGINE_ENABLED`, default off) are Helm `otel.engineTracing`; they leave the pod only with OTLP
enabled (`otel.enabled`). Read `R`
from the T-SCALE probe (`jdbc_round_trips_per_op` in `report.txt` of `TScaleLoadIT`, `-Dsangam.tscale=true`) and
`t_rt` from `hikaricp.connections.usage` divided by `R`. More connections than about 2–4 × the database's cores
add no throughput (PostgreSQL contends on WAL and locks); above that, lower `R` or add nodes.

Measured on one shared developer machine (PostgreSQL 17 in a local container, probe of 30 serial lifecycles,
2026-11-08/09): a process start takes 58 round trips (81 before SG-15) and a full T-SCALE lifecycle (start, fetch,
complete work, list, get, complete task) 365 (485 before). With `t_rt` of 1–3 ms on that host, one 20-connection
pool caps starts at `20 / (58 × t_rt)`, about 115–345 starts/s, before any other traffic. The 500 starts/s cell
floor (ADR-D-24) therefore needs a dedicated database host (`t_rt` ≤ 0.5 ms) and, per node,
`pool = ceil(500 / nodes × 58 × t_rt × 1.3)`, e.g. 10 connections per node for 2 nodes at 0.5 ms (plus the other
operations' share). These are estimates; no load run has yet confirmed them.

#### Bounded waits (SL-59, S-R-5 "never hang")

No JDBC wait is unbounded. The bounds below apply to every connection: engine, product layer, relay, the quota pool
(which copies the main pool's settings) and the read replica.

| Wait | Setting (default) | What happens when it expires |
|---|---|---|
| Waiting for a free pool connection | `spring.datasource.hikari.connection-timeout`, env `SANGAM_DB_CONNECTION_TIMEOUT_MS` (**2000 ms**, SCALE-ARCHITECTURE 1.6); replica: `sangam.datasource.replica.connection-timeout` (same default). `validation-timeout` 1000 ms (`SANGAM_DB_VALIDATION_TIMEOUT_MS`) | The request answers **503 `DATABASE_UNAVAILABLE`** with `Retry-After` (`sangam.db.unavailable-retry-after`, env `SANGAM_DB_UNAVAILABLE_RETRY_AFTER`, default 2 s), from controllers and from servlet filters alike, instead of a 500 or a hidden queue. A pool configured with `connection-timeout: 0` (HikariCP: wait for ever) refuses to start |
| Socket read on a connection | `sangam.db.network-timeout`, env `SANGAM_DB_NETWORK_TIMEOUT` (**300 s**). It is set as the driver's own property: pgjdbc `socketTimeout` (s), mssql-jdbc and MariaDB `socketTimeout` (ms), Oracle `oracle.jdbc.ReadTimeout` (ms). A value already in the JDBC URL or in `spring.datasource.hikari.data-source-properties` wins. `0` disables it (logged as a warning) | The driver fails the call and the pool discards the connection. This is the only bound that ends a read on a dead connection (TCP silently dropped, server gone): statement timeouts cannot cancel it (P-419). It must exceed the longest legitimate statement (large index builds in a migration, long-running jobs) |
| Engine statements | `sangam.engine.jdbc-statement-timeout-seconds` (30 s, unchanged) | Statement cancelled by the driver |
| Lock waits inside Sangam commands and jobs | `sangam.engine.lock-timeout` (2 s); PostgreSQL also `statement-timeout` (5 s) inside the command | Retryable conflict, retried by the command retrier |

An application that **embeds the engine** on its own pool (no Sangam server) sets the socket bound with
`SangamEngineSettings.setNetworkTimeout(Duration)` (default **300 s**, `0` disables it): the engine's
`IsolationGuardDataSource` applies it with `Connection.setNetworkTimeout` to every checked-out connection that has no
network timeout of its own, so a host-chosen `socketTimeout` is never changed. The host remains responsible for its
own pool's acquire timeout.

Accepted limits:

- **Oracle:** a plain `UPDATE`/`DELETE` on a row another session holds has no session lock timeout on Oracle (the
  lock statements use `FOR UPDATE WAIT n`). Such a DML wait is ended only by the 300 s socket bound, which also
  discards the connection.
- **To verify in the test phase:** that pgjdbc applies `socketTimeout` already during the connection handshake
  (`PoolWaitBoundsTest.aDeadConnectionTimesOutWithinTheBound`, a server that accepts and never answers). For
  statements on an open connection the bound is the driver's documented behaviour.

### 1.7 Optional services and keys (Wave 3)

All of these are off by default; the chart's `values.schema.json` checks each value.

- **Upload malware scanning (M-082).** `malware.scanner` is `none` (default), `clamd` or `icap`, rendered as
  `SANGAM_MALWARE_SCANNER`, with `malware.failClosed` (default true: a scanner error refuses the upload),
  `malware.timeout` (30s), `malware.maxBytes` (25 MiB), `malware.clamd.host`/`port` and `malware.icap.uri`.
  `malware.clamav.enabled=true` adds a ClamAV Deployment and Service (`clamav/clamav`, GPL-2.0, a separate service,
  never linked; owner decision 71) with a pinned tag; clamd is reachable only from the server pods, and its signature
  mirror must be allowed in `malware.clamav.egress` (air-gapped: a private mirror). With `scanner: clamd` and no host,
  the server uses this release's ClamAV service. Compose: `--profile malware` with `SANGAM_MALWARE_SCANNER=clamd`.
- **Secret providers (M-075).** `secrets.providers` (comma list: `file`, `vault`, `aws`, `azure`, `gcp`, `env`)
  switches on the platform secret resolver; empty means no resolver and connectors read `secret://` references from
  the environment only. Every `*Ref` is itself a `secret://` reference, never a value. `secrets.file.existingSecret`
  mounts a Kubernetes Secret read-only at `secrets.file.root`. Outbound providers need `networkPolicy.extraEgress`.
  Compose sets no `SANGAM_SECRETS_*` (an empty `SANGAM_SECRETS_PROVIDERS` would still switch the resolver on): put
  `sangam.secrets.*` into `config/server/application.yaml`.
- **State roll-up tier (M-063).** `sangam-national` `rollup.states` adds one roll-up instance per state (same image,
  `SANGAM_RU_SCOPE`, `SANGAM_RU_UPSTREAM_URL`, `SANGAM_RU_UPSTREAM_TOKEN` = a national token with role `TIER`); the
  national `tokens` Secret gains one `<token>:TIER` per state and the cells of that state send their roll-up stream
  to the state instance. Compose: `--profile state-tier` in `docker-compose.cells.yml`.
- **Trace links in the UI (M-044).** `ui.traceUrlTemplate` (`SANGAM_TRACE_URL_TEMPLATE`), an http(s) URL with the
  placeholder `{traceId}`; empty means no links.
- **Script worker (M-061).** `scriptWorker.enabled=true` runs BPMN script tasks in nsjail (owner decisions 53, 77,
  78). Credentials come from `scriptWorker.existingSecret` (key `token`, or `clientSecret` with `oidcTokenUrl` and
  `clientId`). The container runs as root with `SYS_ADMIN` and seccomp/AppArmor unconfined at container level, as
  decision 78 allows; nsjail applies its own per-script seccomp and runs every script as nobody in its own user
  namespace. It holds no database credentials and has no ingress. Compose: `--profile script-worker` (cgroup
  private, `systempaths=unconfined`). In Kubernetes, `procMount: Unmasked` is available only with `hostUsers: false`;
  whether nsjail needs it on a given runtime is checked in the test phase. The image builds only from the pinned build
  args of `deploy/licence/script-worker-build-args.env` (decisions 128 and 157: `NSJAIL_COMMIT`, `KAFEL_COMMIT`,
  `UBUNTU_SNAPSHOT`); the release records them and the image's `/licenses/OS-PACKAGES.txt` next to its SBOM.
- **PDF validator and OCR sidecars (M-081, M-103; decisions 114, 153, 167).** `pdfValidator.enabled` and
  `ocr.enabled` add a Deployment, a Service and (with `networkPolicy.enabled`) a NetworkPolicy each: ingress from this
  release's server pods only, on the sidecar port, and no egress at all. Each sidecar reads its accepted tokens from
  the Secret `existingSecret` key `token` (one per line; list old and new while rotating) and, with `transport: tls`
  (default), its PKCS#12 key store from keys `keystore.p12` and `keystore-password`; the certificate must name
  `<fullname>-pdf-validator` or `<fullname>-ocr`. `transport: mesh` serves plain HTTP and is only for an authenticated
  service mesh. The server gets `SANGAM_PDF_VALIDATOR_URL`/`SANGAM_OCR_URL`, `*_TRANSPORT`, `*_TOKEN_FILE` (from
  `clientExistingSecret` key `token`, default the same Secret; its first line is the token sent) and, with tls, the
  trust store of `sidecarTrustStore.existingSecret` (keys `truststore.p12`, `password`; it replaces the JVM default
  trust store, so include any public CAs the server needs) through `JDK_JAVA_OPTIONS`. Off: PDF output answers 422
  `FORMAT_NOT_AVAILABLE` and OCR is unavailable. The OCR image (`sangam-ocr`, port 8086) installs Tesseract from a
  pinned Ubuntu snapshot (`deploy/licence/ocr-build-args.env`); its OS packages are listed in
  `/licenses/OS-PACKAGES.txt` with their copyright files under `/licenses/sangam-ocr/os-packages` (decision 118).
- **Backup console (decision 155).** `backup.console.enabled` (with `backup.enabled`) adds the Role
  `<fullname>-backup-console` (batch `cronjobs` get/list, `jobs` get/list/create) bound to the server ServiceAccount,
  sets `SANGAM_BACKUP_CONSOLE_KUBERNETES_ENABLED=true` and the selector of this release's backup jobs on the server,
  mounts the server pod's service account token, and opens server egress to `backup.console.apiServer.cidrs` on
  `backup.console.apiServer.ports` (443, 6443); the cidrs are required with `networkPolicy.enabled`. Off by default:
  nothing of this is rendered. The UI, script worker and sidecar pods share the ServiceAccount but keep
  `automountServiceAccountToken: false`.
- **Microsoft Teams tab (decision 213).** `ui.teams.enabled` (`SANGAM_TEAMS_ENABLED`, default false) and
  `ui.teams.frameAncestors` (`SANGAM_TEAMS_FRAME_ANCESTORS`, space-separated https origins; empty = the UI image's
  built-in Microsoft list). Disabled, `/teams.html` cannot be framed like every other page. Compose: the same two
  variables in `.env`.
- **DSC signing agent (decision 165).** `ui.dscAgentOrigin` (`SANGAM_DSC_AGENT_ORIGIN`, default empty = DSC not set up)
  is the agent on the signer's own computer, `http://127.0.0.1:<port>` or `http://localhost:<port>` only (no path). The
  agent is IPv4 loopback only, so `http://[::1]:<port>` is not accepted. The UI image refuses anything else, and the
  same value is the only agent origin in the UI's CSP `connect-src` and in `/config.json` `dsc.agentUrl`. Start the
  agent with `--origin` set to the UI's own origin. Compose: the same variable in `.env`.
- **eSign and DSC add-on (decision 217).** Bouncy Castle is only in the optional module `sangam-esign`, never in the
  default server. To add it: build the server image with `--build-arg WITH_ESIGN=true` (it adds the sangam-esign jar
  and the Bouncy Castle jars the server does not ship to `/opt/sangam/lib-ext`, listed in `ESIGN-ADDON.txt`), or put
  the files of the release asset `sangam-esign-<v>.tgz` (the jar and `lib/*.jar`, flat) into `/opt/sangam/lib-ext`
  of a VM install or `PREBUILT_LIB_EXT` of a prebuilt image build. Live eSign stays off until its ESP profile is
  confirmed (decision 215).
- **NATS add-on (decision 232).** The NATS client (jnats) and the Bouncy Castle jar it needs for nkey signing
  (`bcprov-lts8on`) are not in the server. Without the add-on the server refuses every NATS setting at start (NATS
  channel adapters, NATS wake-up bus) and a route that needs NATS answers 501 `CAPABILITY_NOT_INSTALLED`. To add it:
  build the server image with `--build-arg WITH_NATS=true` (exactly those two jars into `/opt/sangam/lib-ext`, listed
  in `NATS-ADDON.txt`), or put the files of the release asset `sangam-nats-<v>.tgz` (`lib/*.jar`, flat) into
  `/opt/sangam/lib-ext` of a VM install or `PREBUILT_LIB_EXT` of a prebuilt image build. Compose and Helm run the
  image as built; neither adds the jars.
- **NATS or eSign, never both, in 0.1.0 (decision 235).** A server runs the NATS add-on or the eSign and DSC add-on,
  not both: each brings its own Bouncy Castle provider jar (`bcprov-lts8on` for jnats, `bcprov-jdk18on` for
  sangam-esign) holding the same packages, and only one copy can be loaded. The image build refuses
  `WITH_NATS=true` with `WITH_ESIGN=true`, `nats-addon.sh` refuses a `lib-ext` that already holds the eSign jars, and
  the server refuses at start when it finds both. This will be revisited when jnats moves to `bcprov-jdk18on`.
- **Health connectors and Fidelius: the eSign jar, never NATS (decision 243).** Fidelius needs Bouncy Castle
  (`bcprov-jdk18on` 1.84, the same jar as the eSign add-on). It is an optional dependency of
  `sangam-connectors-health` only and is never in the server fat jar. A server that uses Fidelius gets that jar from
  the eSign add-on (`WITH_ESIGN=true` or the `sangam-esign-<v>.tgz` files in `lib-ext`), so its licence and notice
  ship with the esign distribution. For the same reason the health module and the NATS add-on never run together.
  The licence check enforces the split: `bcprov-lts8on` is allowed only in the nats distribution and every other
  Bouncy Castle jar only in the esign distribution. No ABDM conformance is claimed.
- **DSC OCSP answer age (decision 259).** `SANGAM_DSC_OCSP_MAX_AGE` (`sangam.dsc.ocsp-max-age`; Helm
  `server.dscOcspMaxAge`; compose and `.env.example`), default `24h`, applies only with the eSign/DSC add-on. An OCSP
  answer whose `thisUpdate` is older than this is INDETERMINATE and the signing step refuses, even with a matching
  nonce; the clock-skew allowance stays. Values: a positive duration with one unit (`24h`, `90m`, `PT24H`, `P1D`;
  the Helm schema checks the form). OCSP and CRL over plain http go only to the `sangam.dsc.revocation-egress`
  origins (decision 251).
- **Pack operation re-dispatches (decision 290).** `SANGAM_INTEGRATION_RECONCILIATION_MAX_REDISPATCHES`
  (`sangam.integration.reconciliation.max-redispatches`; Helm `server.integrationReconciliationMaxRedispatches`;
  compose and `.env.example`), default `3`: how many times reconciliation re-dispatches on its own a pack operation
  that has no process instance (a REST-backed record create) before it hands the operation to a person. It must be
  a positive integer; `0`, a negative number, a blank value or text refuses the server start and names the property
  (the Helm schema accepts only an integer of at least 1).
- **Inbound Event Grid channel with Entra ID (decision 219).** A channel of type `eventgrid` with `authMode: entra`
  fetches the signing keys from its Entra authority, `login.microsoftonline.com` unless `entraAuthority` names
  another https host. Add that host to the channel egress hosts (`sangam.ports.egress-hosts`; empty by default, so an
  air-gapped server reaches nothing) and, in Helm with `networkPolicy.enabled`, a `networkPolicy.extraEgress` rule to
  it on port 443. Without it the key set cannot be fetched: every delivery is answered 503 and Event Grid retries
  until its own retry policy gives up; nothing is accepted unverified.
- **History data lake and connector breadth (W4-H).** `sangam.history.projection.datalake.*` and
  `sangam.connectors.{s3,graphql,jdbc}.tenants.<tenant>.<id>.*` are off until set in `server.config` (Helm) or
  `config/server/application.yaml` (compose), with `secret://` references for credentials and a
  `networkPolicy.extraEgress` rule to each endpoint.
- **Connectors behind an API read are read-only (decisions 320 and 321).** A low-code data object backed by a
  connector (`endpointRef`) and the geo external-layer query call the connector from an API read. The call is made
  only when the connector declares that request read-only; for an HTTP connector that is a request that resolves to
  `GET` or `HEAD` (the request's `method`, else the connector's default method) on a connector without an async
  callback. The operation name plays no part. Anything else is refused with 422
  `CONNECTOR_OPERATION_NOT_READ_ONLY` and a warning in the log, the connector is not called, and the refusal is
  permanent (not a retryable 503); a data object whose binding is not read-only is refused when it is defined. So an
  HTTP connector bound to a data object must resolve its list request to `GET` or `HEAD`.

### 1.8 India DLT SMS: delivery reports and the stored registration (decisions 304, 305)

Wired by `DltWiring` while `sangam.modules.integration.enabled` is true (the default); the settings are
`sangam.integration.dlt.*` (`DltProperties`).

**Delivery-report webhook (decision 304).** The SMS gateway posts delivery reports to

    POST /api/v1/hooks/sms-dlt/{tenant}/{connectorId}/delivery-reports

`{tenant}` is the tenant id and `{connectorId}` the id of that tenant's DLT SMS connector; the tenant in the path
only selects that tenant's connector and signing secret. The route asks for no Sangam token (it is public in
`SecurityConfig`, for exactly this path shape and `POST`), so expose it to the gateway through the same ingress or
edge that serves `/api`. A report is authenticated by its signature alone:

- **Header.** `X-Sangam-Signature: t=<epoch seconds>,v1=<hex HMAC-SHA256(secret, t + "." + raw body)>`
  (`WebhookSignature`, the same scheme as the SL-39 outbound webhooks). The header may carry several `v1` values
  (a secret rotation on the sender's side); one that verifies is enough.
- **Time window.** `t` must be within `sangam.integration.dlt.delivery-reports.tolerance` of the server clock,
  earlier or later (default `5m`; whole seconds are compared). A captured request replayed outside the window is
  refused.
- **Signing secret, by reference only.** One entry per connector, in `server.config` (Helm) or
  `config/server/application.yaml` (compose):

  ```yaml
  sangam:
    integration:
      dlt:
        delivery-reports:
          secrets:
            main:                              # any name
              connector: sms-dlt               # required: the connector id
              tenant: acme                     # optional: only this tenant's connector
              secret-ref: secret://dlt/webhook # a reference, never the secret
  ```

  An entry with a `tenant` wins over one without for that tenant; an entry without a `tenant` serves the connector
  of every tenant that has no entry of its own. The server refuses to start, naming the property, when `connector`
  is missing, when `secret-ref` does not start with `secret://` (a literal secret), or when two entries name the
  same connector (and tenant). The reference is resolved by the platform secret resolver when `secrets.providers` is
  set (section 1.7), otherwise from the environment (`SecretResolver.fromEnvironment`).
- **Fail closed.** A connector with no configured secret, or one whose reference cannot be resolved, refuses every
  report.
- **Answers.** `200` with `accepted`, `duplicates` and `ignored` counts; `404 NOT_FOUND` when the tenant has no DLT
  SMS connector with that id (checked first); `401 WEBHOOK_SIGNATURE_INVALID` for a missing or wrong signature or one
  outside the time window, before anything is read or written; `422 DLT_DELIVERY_REPORT_INVALID` for a body over
  1 MiB, a body that is not one report object or an array of 1 to 500 of them, an unknown `status`, or a report that
  names neither `clientRef` (the operation id) nor `messageId`. Nothing the sender wrote is echoed in a refusal.
- **Effect.** A report is recorded once by its `reportId` (without one, by the SHA-256 of the report), so a repeated
  report changes nothing; the state of a message only moves forward (`ACCEPTED`/`SUBMITTED`, `SENT`, then one final
  state: `DELIVERED`, `UNDELIVERED`, `FAILED`, `REJECTED` or `EXPIRED`), and `NOTIFICATION_DELIVERED` is written
  once per message, without the mobile number.

**Status-lookup fallback (decision 304).** For a message whose report did not arrive, every node looks the status
up through the connector (`DltDeliveryStatusPoller`; the claim is skip-locked, so two nodes never look up one
message at once). It runs every `delivery-status.interval` unless `delivery-status.enabled=false`, and only while
`sangam.integration.background.enabled` is true (the default); the first pass comes one interval after the start.
Without an environment variable of their own (set them in `server.config` or `config/server/application.yaml`):
`delivery-status.first-lookup-after` (`15m`), `.lookup-every` (`30m`), `.window` (`3d`, older sends are not looked
up), `.max-lookups` (`12`) and `.batch-size` (`100`). A duration that is zero or negative, or `max-lookups` or
`batch-size` below 1, refuses the start and names the property.

**Stored registration (decision 305).** The DLT registration of a tenant (entity id, headers, content templates,
URL prefixes) is stored in the database and is what the DLT SMS connector validates against. It is read and changed
through the admin routes, always for the caller's tenant:

| Route | Permission |
|---|---|
| `GET /api/v1/admin/dlt-registration` | `dlt.registration.read` |
| `PUT /api/v1/admin/dlt-registration/entity` | `dlt.registration.write` |
| `POST /api/v1/admin/dlt-registration/headers` | `dlt.registration.write` |
| `POST /api/v1/admin/dlt-registration/templates` | `dlt.registration.write` |
| `POST /api/v1/admin/dlt-registration/urls` | `dlt.registration.write` |

Both permissions are tenant-scoped and for tenant administrators. A change writes `DLT_TEMPLATE_REGISTERED`; an
entry the store's checks refuse is `422` with the code `ENTITY_INVALID`, `HEADER_INVALID`, `TEMPLATE_INVALID` or
`URL_INVALID`. Start-up configuration only seeds the store:
`sangam.integration.dlt.registration.seed.<tenant>.entity-id`, `.headers[]`, `.templates[]` (`template-id`,
`header`, `body`) and `.urls[]` are added where absent and never overwrite a stored entry; a seed entry the checks
refuse stops the start. Every node loads the stored registrations at start and then every
`registration.refresh-interval`, which is how a change made on another node arrives.

| Env | Property (`sangam.integration.dlt.`) | Helm | Default |
|---|---|---|---|
| `SANGAM_DLT_DELIVERY_REPORT_TOLERANCE` | `delivery-reports.tolerance` | `server.dlt.deliveryReportTolerance` | `5m` |
| `SANGAM_DLT_DELIVERY_STATUS_ENABLED` | `delivery-status.enabled` | `server.dlt.deliveryStatus.enabled` | `true` |
| `SANGAM_DLT_DELIVERY_STATUS_INTERVAL` | `delivery-status.interval` | `server.dlt.deliveryStatus.interval` | `5m` |
| `SANGAM_DLT_REGISTRATION_REFRESH_INTERVAL` | `registration.refresh-interval` | `server.dlt.registrationRefreshInterval` | `1m` |

Compose passes the same four from `.env` (`.env.example`). The Helm schema accepts a positive duration with one unit
(`5m`, `90s`, `PT5M`).

---

## 2. Upgrade (R12, R32)

Sangam supports **N → N+1 rolling upgrades**. During the rollout, pods of N and N+1 run against the upgraded schema. Every changelog is backward compatible for one release (expand / contract), and behaviour-changing defaults stay behind a flag for one LTS.

1. **Read the release notes** for the changelog class:
   - *online* (additive only, allowlisted per DB);
   - *offline* (needs a maintenance window).
2. **Back up** (§3): a fresh full backup plus confirmed log/WAL shipping.
3. **Pre-check:**

   ```bash
   helm diff upgrade ...    # or: helm template > /tmp/new.yaml
   ```

   Then confirm that no job is stuck: open incidents, `/api/v1/ops/jobs`, and the outbox backlog in Grafana.
4. **Upgrade:**

   ```bash
   helm upgrade sangam deploy/helm/sangam -n sangam -f <your values> --set server.image.tag=<N+1>
   ```

   The `migrate-<revision>` hook Job runs first. If it fails, Helm stops and the old pods keep running.
5. **Watch:**

   ```bash
   kubectl rollout status deploy/sangam-server
   ```

   Check readiness and error rate on the dashboard, and that `/api/v1/audit/verify` is still green.
6. **Contract phase** (dropping old columns) ships in **N+2** only, never in the same release.

**Rollback.** `helm rollback sangam <rev>` restores the N pods, which work on the expanded schema. Database downgrades are **not** supported. If a migration damaged data, restore (§3) and reconcile (§4.3).

### Knowledge-base vector index and the Oracle vector column (decision 247)

`SANGAM_AI_KB_VECTOR_INDEX` (`sangam.ai.kb.vector-index`; Helm `server.aiKbVectorIndex`, schema enum) picks the
ranking index of the AI knowledge base:

| Value | Database | Schema |
|---|---|---|
| `portable` (default) | every supported database | nothing extra |
| `pgvector` | PostgreSQL with the pgvector column | the product changelog |
| `oracle` | Oracle AI Database **23ai or later** | the separate changelog `db/changelog/sangam-oracle-vector.xml`, context `oracle-vector` (column `AI_KB_CHUNK.embedding_vec` of type `VECTOR`) |

Any other value stops the start. With `oracle` the server checks the database version first and refuses Oracle 19c
and 21c, before any changeset. The oracle-vector changelog is not part of the master changelog, so a plain
`liquibase update` of the master never applies it.

- **The server applies it itself** wherever it applies the product changelog: `SANGAM_LIQUIBASE_MODE=startup` (compose,
  Helm `liquibase.mode: startup`) and the Helm migrate Job (`liquibase.mode: job`, which receives
  `SANGAM_AI_KB_VECTOR_INDEX` from `server.aiKbVectorIndex`). Validating pods never apply it.
- **Operators who run Liquibase themselves** (Helm `liquibase.mode: none`, or a DBA-run Liquibase) apply the product
  changelog first and then, on Oracle 23ai or later only:

  ```bash
  liquibase update -changelog-file=db/changelog/sangam-oracle-vector.xml -contexts=oracle-vector
  ```

  The changelog's precondition halts on any database other than Oracle. `SANGAM_LIQUIBASE_MODE=update-sql` writes
  the master changelog only, so it does not include this column.

### Failed migration (R12)

The hook Job has `backoffLimit: 0`, so a failed migration is never retried blindly.

1. Read the logs:

   ```bash
   kubectl -n sangam logs job/sangam-migrate-<rev>
   ```

   Find the changeset id and the database error.
2. Check the Liquibase lock: `SELECT * FROM DATABASECHANGELOGLOCK`. If `LOCKED` is true and **no** migrate pod is running, release it:

   ```bash
   SANGAM_LIQUIBASE_MODE=release-locks
   ```

   Or run `UPDATE DATABASECHANGELOGLOCK SET LOCKED=0, LOCKGRANTED=NULL, LOCKEDBY=NULL WHERE ID=1`. Record this in the change log.
3. What to do next depends on the database:

   | Database | Failed DDL | Action |
   |---|---|---|
   | PostgreSQL, SQL Server | Rolled back with its transaction | Fix the cause (privilege, space, lock timeout), then re-run `helm upgrade` |
   | Oracle, MySQL | Auto-committed, so it may be half-applied | Compare the `DATABASECHANGELOG` rows with the objects. Revert the partial objects by hand (the release notes carry the inverse SQL per changeset), or restore from the pre-upgrade backup |

4. If it cannot be fixed within the window: `helm rollback`. The schema is still at N or N-expanded, and N pods run on it.

---

## 3. Backup and restore (ADR-D-12: RPO ≤ 5 min, RTO ≤ 1 h)

**Durability model.** The 5-minute RPO comes from two things together:

1. **asynchronous replication** to the DR site;
2. **continuous log shipping**: WAL, archived redo, transaction log or binlog, at most every 5 min.

Daily or weekly full backups bound the restore time. Replication does not protect against corruption or operator error, so backups are mandatory as well. Run a **restore test every month** and record the measured RTO (T26/T27).

What must be backed up together:

- the database: all Sangam and Operaton tables in one database/schema, including outbox, inbox, ledger, leases, audit chain and tombstones;
- the Kubernetes Secrets: database, keys, TLS;
- the Helm values;
- the Keycloak / IdP database;
- object storage for attachments, if configured;
- the deployed model packages, which are in the database, plus their signing keys.

The operations backup console shows and starts these backups: in Kubernetes the chart's backup Jobs
(`backup.console.enabled`, section 1.7), elsewhere your own scripts (script mode, section 1.3, decision 220). Both
cannot be on at once.

The Helm chart ships **examples** (`backup.enabled=true`) per `database.type`. They write to the PVC `<release>-backup`. Move that PVC to off-site storage with your storage tooling (Velero, array replication, rsync to a DR NAS).

| DB | Full | Incremental / logs (RPO) | Chart objects |
|---|---|---|---|
| PostgreSQL | `pg_basebackup` daily (tar + gzip, WAL streamed, SHA-256 manifest); `pg_dump -Fc` weekly | `pg_receivewal` Deployment, replication slot, synchronous flush: RPO of seconds | `*-pg-full`, `*-pg-logical`, `*-pg-receivewal` |
| Oracle | RMAN level 0 weekly, level 1 daily | `BACKUP ARCHIVELOG` every 5 min after `ARCHIVE LOG CURRENT` | `*-ora-level0`, `*-ora-level1`, `*-ora-archlog` (customer image with rman) |
| SQL Server | `BACKUP DATABASE` daily + differential every 6 h, `CHECKSUM` + `RESTORE VERIFYONLY` | `BACKUP LOG` every 5 min (FULL recovery) | `*-mssql-full`, `*-mssql-diff`, `*-mssql-log` |
| MySQL 8.4 | `mysqldump --single-transaction --source-data=2 --set-gtid-purged=ON` daily | `FLUSH BINARY LOGS` + `mysqlbinlog --read-from-remote-server --raw` every 5 min | `*-mysql-full`, `*-mysql-binlog` |

The backup user needs these privileges:

| Database | Privileges |
|---|---|
| PostgreSQL | `REPLICATION` + `pg_read_all_data` |
| Oracle | `SYSBACKUP` |
| SQL Server | `db_backupoperator` |
| MySQL | `RELOAD, REPLICATION CLIENT, REPLICATION SLAVE, SELECT, SHOW VIEW, EVENT, TRIGGER, PROCESS` |

### Restore, step by step (all databases)

1. **Stop writers.** Scale the server to 0 so no job executor, outbox relay or worker API keeps writing:

   ```bash
   kubectl scale deploy/sangam-server --replicas=0
   ```

   Keep `sangam.io/api-client` workers stopped too.
2. **Restore the database to a point in time** (commands below), just before the incident or at the latest available log.
3. **Start one server pod** with `SANGAM_RECONCILE_ON_START=true` and `SANGAM_JOB_EXECUTOR_ENABLED=false`, then run the post-restore checks (§4.3).
4. Enable the job executor and scale back up.

**PostgreSQL** (PITR):

```bash
# on a new empty data dir
tar -xzf base/<ts>/base.tar.gz -C $PGDATA && tar -xzf base/<ts>/pg_wal.tar.gz -C $PGDATA/pg_wal
cat >> $PGDATA/postgresql.auto.conf <<EOF
restore_command = 'gunzip -c /backup/wal/%f.gz > %p || cp /backup/wal/%f %p'
recovery_target_time = '2026-11-04 10:15:00+00'      # omit for "latest"
recovery_target_action = 'promote'
EOF
touch $PGDATA/recovery.signal && pg_ctl start
```

**Oracle**, in RMAN as SYSBACKUP:

```
STARTUP FORCE MOUNT;
RUN { SET UNTIL TIME "TO_DATE('2026-11-04 10:15','YYYY-MM-DD HH24:MI')"; RESTORE DATABASE; RECOVER DATABASE; }
ALTER DATABASE OPEN RESETLOGS;
```

**SQL Server:**

```sql
RESTORE DATABASE sangam FROM DISK='.../sangam_full_<ts>.bak' WITH NORECOVERY, REPLACE;
RESTORE DATABASE sangam FROM DISK='.../sangam_diff_<ts>.bak' WITH NORECOVERY;
-- every log backup after the diff, in order; the last one with STOPAT
RESTORE LOG sangam FROM DISK='.../sangam_log_<ts>.trn' WITH NORECOVERY;
RESTORE LOG sangam FROM DISK='.../sangam_log_<ts>.trn' WITH STOPAT='2026-11-04T10:15:00', RECOVERY;
ALTER DATABASE sangam SET READ_COMMITTED_SNAPSHOT ON;   -- verify; restore keeps it, a new DB does not
```

**MySQL 8.4:**

```bash
gunzip -c full/<ts>/sangam.sql.gz | mysql -u root -p
# replay binlogs written after the dump's GTID set (gtid_executed.txt), stop at the incident
mysqlbinlog --skip-gtids=false --exclude-gtids="$(cat full/<ts>/gtid_executed.txt)" \
  --stop-datetime="2026-11-04 10:15:00" binlog/mysql-bin.0000* | mysql -u root -p
```

---

## 4. Disaster recovery (ADR-D-12)

**Topology.** Primary site plus DR site: the State Data Centre and a second SDC, or NIC / MeghRaj.

- The database replicates asynchronously:
  - PostgreSQL: streaming replica;
  - Oracle: Data Guard (max performance);
  - SQL Server: AG async replica;
  - MySQL: GTID replica.
- The images, the chart and the secrets are already present at DR (the air-gapped bundle, §6).
- The DR Kubernetes or VMs are pre-installed with `server.replicaCount: 0`, pointing at the DR database.

**Failure boundary.** Loss of the primary site or host. RPO 0 is **not** claimed.

### 4.1 Failover (target ≤ 1 h)

1. **Declare** the disaster (incident commander), and freeze the primary if it is reachable: scale the server to 0, and fence the old primary database by revoking the app user or shutting it down. This prevents split-brain.
2. **Promote the DR database:**

   | Database | Command |
   |---|---|
   | PostgreSQL | `pg_ctl promote` / `SELECT pg_promote()` |
   | Oracle | `ALTER DATABASE FAILOVER TO <standby>` (DGMGRL `FAILOVER TO ...`) |
   | SQL Server | `ALTER AVAILABILITY GROUP ... FORCE_FAILOVER_ALLOW_DATA_LOSS` |
   | MySQL | `STOP REPLICA; RESET REPLICA ALL;` and make it writable |

   Record the last applied LSN / SCN / LSN / GTID. That is the measured RPO.
3. **Switch traffic:** DNS or the GSLB entry, or the ingress host, to the DR site.
4. **Start Sangam at DR** with the reconciliation steps below:

   ```bash
   helm upgrade ... --set server.replicaCount=2
   ```

### 4.2 Failback

Rebuild the old primary as a replica of DR. Then do a planned switchover in a window: a zero-loss switchover with Data Guard, AG or PostgreSQL controlled switchover. Repeat §4.3.

### 4.3 Reconciliation after a restore or failover (VI.2, VI.3, T35)

Asynchronous replication can lose up to 5 minutes. External systems may hold effects that Sangam's database no longer remembers, such as a payment sent to IFMS or a DigiLocker push.

1. **Outbox and inbox.**
   - The outbox relay resumes from the restored `root_seq`. Duplicates are expected and safe.
   - The inbox dedups by message id.
   - Broker messages newer than the restore point are **not** replayed blindly. They are re-delivered and deduplicated (T35).
2. **External operations.**
   - Every effectful step carries `operationId = UUIDv5(businessKey, step, attempt scope)`.
   - Run `POST /api/v1/ops/reconciliation/scan?since=<restore point - 10 min>`. For each operation in flight it queries the external system by operationId (IFMS JIT lookup, PFMS status files, eSign transaction, payment gateway status). Then it:
     - marks done what the external system confirms;
     - re-drives what the external system never saw;
     - parks as `UNKNOWN_OUTCOME` what has no lookup (capability class c). Those go to the reconciliation console for a human decision.
3. **Lost process starts.** A start that was lost restarts with a **new** operationId (T35-2 finding). Before re-submitting, the department checks for duplicates by business key on the reconciliation console.
4. **Audit.**
   - Run `GET /api/v1/audit/verify`. The hash chain must verify up to the restore point.
   - Record a `RESTORE` event with the restore point and the measured RPO/RTO.
5. **Leases.** Every external-task lease taken before the restore is stale. Workers get `409 stale-lease` and fetch again. No action is needed.
6. **Timers.** Timers that fell due during the outage fire by catch-up policy (A-12), bounded per job-executor cycle.

---

## 5. Key and secret rotation

| Secret | Where | Rotation | Procedure |
|---|---|---|---|
| DB app password | `database.existingSecret` | 90 days | Add a second password/user on the DB (PostgreSQL: `ALTER ROLE ... PASSWORD`; Oracle: gradual password rollover in 21c+; SQL Server: new login; MySQL: `ALTER USER ... RETAIN CURRENT PASSWORD`) → update the Secret → `kubectl rollout restart deploy/sangam-server` → drop the old password (`DISCARD OLD PASSWORD` on MySQL) |
| Backup user password | `backup.existingSecret` | 90 days | Same as above; CronJobs pick up the new Secret on their next run |
| OIDC signing keys (Keycloak) | IdP | 180 days | Add a new active key (Realm → Keys), keep the old one passive for longer than the max token lifetime (5 min) plus the JWKS cache, then disable it. The server refreshes the JWKS automatically |
| Worker client secret (`sangam-worker`) | IdP + worker deployments | 90 days | Regenerate in a second credential (Keycloak supports client-secret rotation with a grace period) → roll the workers → expire the old secret |
| TLS (ingress, DB TLS) | `ingress.tls.secretName`, DB | Before expiry (cert-manager) | Replace the Secret; nginx ingress reloads; the DB TLS CA goes in the JDBC params (`sslrootcert` / truststore) |
| Draft / field encryption keys (`key_id`) | `server.keys.existingSecret` | Yearly or on compromise | Add the new key file with a new `key_id` and mark it primary → rolling restart → new writes use it; a background re-encrypt job rewrites old rows → remove the old key only when `/api/v1/ops/keys` reports 0 rows on it |
| Audit chain / package signing keys | `server.keys.existingSecret` (HSM/DSC where required, R57) | Yearly | Publish the new public key in the trust list first; start signing with the new key; **keep old public keys forever** (old chains and signatures must still verify); record `KEY_ROTATED` in the security chain |
| Connector credentials (IFMS, PFMS SFTP keys, eSign ASP, DLT, DigiLocker) | `server.keys.existingSecret` or `server.envFrom` Secrets | Per provider policy | Register the new key with the provider → update the Secret → rolling restart; PFMS SFTP: register the new public key before removing the old one |
| Keycloak admin, Grafana admin | compose `.env` / IdP | On staff change | Change in place; never use the dev defaults outside a laptop |

After any rotation:

- run `/actuator/health`;
- complete one smoke task per pack;
- check the error rate on the dashboard.

---

## 6. Air-gapped install (IX.6, R56, AC-11/T50)

**On a connected build machine** (the release workflow does the same):

```bash
deploy/airgap/generate-sbom.sh --out deploy/airgap/out/sbom        # CycloneDX + licence gate
deploy/airgap/save-images.sh --version 0.1.0 --registry ghcr.io/sqamentor --sbom-dir deploy/airgap/out/sbom \
  --notices-dir <release notices/ directory>                        # --notices-dir is optional
```

On Windows (`-SbomDir` is required, `-NoticesDir` is optional):

```powershell
powershell -File deploy\airgap\generate-sbom.ps1 -Out deploy\airgap\out\sbom
powershell -File deploy\airgap\save-images.ps1 -Version 0.1.0 -Registry ghcr.io/sqamentor -SbomDir deploy\airgap\out\sbom -NoticesDir <release notices\ directory>
```

- `--sbom-dir` / `-SbomDir` is **required**: the application SBOMs (CycloneDX JSON) of this version, either the
  release's `sbom/` directory or the output of `generate-sbom`. A bundle never ships without its SBOMs (CR-03); an
  empty directory stops the build.
- `--notices-dir` / `-NoticesDir` is optional: the release's `notices/` directory (LICENSE, NOTICE, THIRD-PARTY.txt
  and the licence texts of each distribution). It is copied to `docs/notices/`.
- `--allow-missing` was **removed**. Every step is strict (RM-12): an image that cannot be pulled, an image SBOM that
  syft cannot write, or a missing LICENSE, NOTICE or source offer stops the bundle. The old option now exits with an
  error.
- Before anything is saved, the image list passes the bundle licence gate (`bundle_licence_gate.py`, RM-13).

On the shared build machine, wrap heavy steps in the lock:

```bash
python -E -X utf8 "%TEMP%\heavy_lock.py" run --owner customwf -- <cmd>
```

The bundle `sangam-airgap-<v>.tar` (with its `.sha256`) contains:

- `images/*.tar.gz`;
- `images.lock` (pinned digests);
- `chart/`, `compose/`, `docs/` (with `LICENSE`, `NOTICE` and, when given, `docs/notices/`);
- the MariaDB source offer and the OS-packages source offer;
- `sbom/` (the application SBOMs and one syft SBOM per saved image);
- `SHA256SUMS`.

**Grafana is not in the bundle** (RM-13): Grafana OSS is AGPL-3.0, a forbidden licence for anything Sangam ships.
`--with-observability` (`-WithObservability`) adds Jaeger and Prometheus only. A customer who wants dashboards pulls
`grafana/grafana-oss` themselves; the dev compose file still uses it for local development only.

Oracle Free, SQL Server and MySQL images are **excluded** unless `--with-dev-databases` is given. They are not redistributable by Sangam.

**Transfer** the bundle by approved media. **On the air-gapped side:**

```bash
sha256sum -c sangam-airgap-0.1.0.tar.sha256 && tar -xf sangam-airgap-0.1.0.tar && cd sangam-airgap-0.1.0
./verify-bundle.sh
./load-images.sh --registry registry.sdc.local           # docker load, retag, push
helm upgrade --install sangam chart/sangam-0.1.0.tgz -n sangam \
  -f my-values.yaml --set global.imageRegistry=registry.sdc.local   # see chart examples/values-airgap.yaml
```

Pin images by digest from `images.lock` (`server.image.digest`). There are no outbound calls by default:

- OTel is disabled;
- Keycloak runs without an internet IdP;
- NetworkPolicy allows no egress beyond DNS, the database and the IdP.

### Offline Maven and npm builds

To build from source inside the air-gapped network:

- **Maven:** on the connected side, run `./mvnw -B dependency:go-offline -Dmaven.repo.local=./m2-offline` from `sangam/`. Add the plugins used by CI as well:

  ```bash
  ./mvnw -Dmaven.repo.local=./m2-offline org.cyclonedx:cyclonedx-maven-plugin:2.9.1:help \
         org.owasp:dependency-check-maven:12.1.3:help
  ```

  Archive `m2-offline/` and transfer it. Inside, use `./mvnw -o -Dmaven.repo.local=/path/m2-offline ...`, or import it into an internal Nexus or Artifactory (Maven proxy) and point `~/.m2/settings.xml` `<mirrors>` at it:

  ```xml
  <mirror><id>internal</id><mirrorOf>*</mirrorOf><url>https://nexus.sdc.local/repository/maven-all/</url></mirror>
  ```

  The Oracle JDBC driver for CI tests comes only with the CI profile `-Poracle-it` (decision 123), from your internal mirror (Maven Central `com.oracle.database.jdbc`), never from the bundle.
- **npm:** never run install scripts (decision 130): every `npm ci` passes `--ignore-scripts`. The UI build needs no
  install script and no rebuild step since Vite 8 (M-117): rolldown and lightningcss use prebuilt optional packages
  (`@rolldown/binding-*`, `lightningcss-*`) that `npm ci` installs for the build host's OS and CPU, and esbuild is no
  longer in the lockfile. Fill the cache on the same OS and CPU as the offline build host (for the UI image: Linux),
  because npm fetches only the native packages of the platform it runs on. On the connected side, run
  `npm ci --ignore-scripts --cache ./npm-cache` in `sangam/sangam-ui`. Then either:
  - `npm pack` every dependency;
  - better: tar the `./npm-cache` directory.

  Inside, run `npm ci --ignore-scripts --offline --cache ./npm-cache`, or set `NPM_CONFIG_REGISTRY=https://nexus.sdc.local/repository/npm-all/` (an internal npm proxy). The UI Dockerfile accepts `--build-arg NPM_CONFIG_REGISTRY=...`.
- **Docker builds offline:** use the `prebuilt` targets (`--build-arg JAR_SOURCE=prebuilt`, `--build-arg DIST_SOURCE=prebuilt`). They need no network beyond the base images, which are in the bundle (`docker load`).
- **SBOM offline:** `deploy/airgap/generate-sbom.sh --offline`.

---

## 7. Licence rules enforced here (X.3a)

| Rule | Enforcement |
|---|---|
| LIC-1 GraalJS, LIC-10 Rhino / Nashorn / Jython / JRuby | `check_licences.py` forbidden components + nested-jar patterns; image build guard |
| LIC-2 Oracle JDBC, LIC-3 Db2 JCC, LIC-4 MySQL Connector/J | Never in an image or the fat jar (Dockerfile guard, licence gate `--jar`, dependency-tree grep in `licence-check.yml`). Customers mount `/opt/sangam/drivers` (compose `./drivers`, Helm `drivers.*`). CI fetches ojdbc at test scope only, through the CI-only profile `-Poracle-it` (decision 123), never active by default |
| LIC-5 MariaDB Connector/J | Shipped as `/opt/sangam/lib-ext/mariadb-java-client-<v>.jar` (+ sources jar, `SOURCE-OFFER.txt`), replaceable by volume; forbidden inside `BOOT-INF/lib` |
| LIC-6 AngularJS | Forbidden npm component |
| LIC-7 Liquibase ≥ 5 | Version ceiling in the policy + nested-jar pattern |
| LIC-8 bpmn.io | Conditional allow; `ui.yml` checks the watermark is still in the build |
| LIC-11 | `release.yml` regenerates SBOMs + licence report from the tag, `--strict` (unknown licences fail) |

Third-party images in the bundle are separate, unmodified programs:

| Image | Licence |
|---|---|
| Keycloak | Apache-2.0 |
| OTel collector | Apache-2.0 |
| PostgreSQL | PostgreSQL licence |
| Jaeger | Apache-2.0 |
| Prometheus | Apache-2.0 |
| Grafana OSS | AGPL-3.0, used unmodified over HTTP only. Optional; drop it if the department's policy forbids AGPL |

---

## 8. Observability (R13, R30, R51)

- **Traces.** The server exports OTLP to the collector. The collector deletes `Authorization`, cookie and variable attributes and hashes `enduser.id`.
- **Default export.** `collector-base.yaml` exports nowhere, so nothing leaves the host. `collector-observability.yaml` sends traces to Jaeger and metrics to Prometheus.
- **Metrics.** Server metrics are at `:8081/actuator/prometheus`. Grafana's "Sangam overview" dashboard shows:
  - HTTP rate and p95;
  - JVM heap;
  - Hikari pool;
  - outbox backlog and age;
  - reconciliation states;
  - job executor and incidents;
  - stale-lease rejections.

  The 24 h soak gate (R30) watches heap, cache metrics and these panels.
- **Alerting (M-091).** Compose: `observability/rules/sangam-alerts.yml` is mounted at `/etc/prometheus/rules` and
  loaded by `prometheus.yml` (`rule_files`). Helm: `metrics.prometheusRule.enabled` (default `false`) renders the same
  rules as a `PrometheusRule`, which needs the Prometheus Operator CRD (like `metrics.serviceMonitor`); add the
  Prometheus `ruleSelector` labels in `metrics.prometheusRule.labels`. `PrometheusAlertRulesTraceTest` keeps the two
  rule sets equal. Thresholds (`metrics.prometheusRule.thresholds`; the compose file has the same literals):

  | Alert | Fires when | Default threshold | For |
  |---|---|---|---|
  | `SangamServerDown` | the scrape of a server fails (`up == 0`) | none | 5 m |
  | `SangamOutboxDeliveryStale` | `max(sangam_outbox_oldest_age_seconds)` is above `outboxOldestAgeSeconds` (PARKED rows count) | 300 s | 5 m |
  | `SangamOutboxCapacityHigh` | `max(sangam_outbox_capacity_used_ratio)` is at or above `outboxCapacityRatio` (R40) | 0.5 | 5 m |
  | `SangamOutboxBackpressure` | `max(sangam_outbox_capacity_used_ratio)` is at or above 0.8, where the server starts refusing new submissions | fixed 0.8 | 1 m |
  | `SangamOutboxDeliveryParked` | `max(sangam_outbox_parked)` is above `outboxParked` (each PARKED delivery blocks its line) | 0 | 1 m |
  | `SangamRelayStalled` | a pod's `sangam_relay_last_cycle_age_seconds` is above `relayLastCycleAgeSeconds` (2 x the 30 s claim lease) | 60 s | 2 m |
  | `SangamRootLockWaitHigh` | `max by (process)` of `sangam_engine_root_lock_wait_seconds_max` is above `rootLockWaitSeconds` | 1 s | 5 m |
  | `SangamIncidentsOpen` | `max(sangam_incidents_open)` is above `incidentsOpen` | 0 | 15 m |
  | `SangamReconciliationItemsOpen` | `max(sangam_reconciliation_items{state="OPEN"})` is above `reconciliationOpen` | 0 | 15 m |
  | `SangamCertificateExpiring` (Helm only, `certManager.enabled`, default `true`) | a cert-manager certificate expires in less than `certExpiryDays` | 14 days | 1 h |

  The database-read gauges report the same value on every pod, so those rules take `max()`; the relay heartbeat is
  per pod and is checked per pod. The gauge names are a contract with `PrometheusAlertRulesTraceTest`. Without
  cert-manager the certificate rule has no series and never fires; `certManager.selector` narrows the certificates.
- **Logs.** JSON logs (ECS) go to stdout: structured console logging, one JSON object per line, is the default in
  every profile, development included (decision 324; `logging.structured.format.console: ecs` in the server's
  `application.yaml`), so text from a request cannot start a second log line. Ship them to an India-resident store with **≥ 180 days** retention (R51, CERT-In).
- **Time.** Nodes sync time with NIC/NPL NTP.

---

## 9. CI (GitHub Actions)

The workflows are at the repository root, in `.github/workflows/`. GitHub ignores workflows anywhere else. All of them run with `working-directory: sangam`.

| Workflow | Trigger | What |
|---|---|---|
| `ci-build.yml` | push/PR on `sangam/**` | Maven build + unit tests (H2, no Testcontainers); server image build (Temurin + distroless); `docker compose config` for every profile; realm generator drift; `helm lint --strict` + `helm template` for every example; kubeconform; shellcheck |
| `db-matrix.yml` | PR, nightly, weekly, dispatch, `workflow_call` | **CF-11.** PR: PostgreSQL + affected dialects (path filters; shared migrations/mappings → all Tier-1). Nightly: PostgreSQL, Oracle, SQL Server, MySQL. Weekly: MariaDB + Db2 (experimental, non-blocking). Release: full Tier-1 |
| `ui.yml` | `sangam-ui/**` | npm ci, lint, type-check, tests, build, LIC-8 watermark check, CycloneDX npm SBOM + licence gate, image build |
| `sdks.yml` | `sdks/**`, API spec | Java (JDK 21 + 25), .NET 8, Python 3.10/3.13, TypeScript, PHP 8.3; each skipped with a notice until its SDK exists |
| `licence-check.yml` | pom/package changes, weekly | Aggregate CycloneDX SBOM + npm SBOM → `check_licences.py` (forbidden licences, components, versions, fat-jar contents); runtime dependency-tree grep |
| `security.yml` | push/PR, weekly | CodeQL (Java, JS/TS; security-extended); OWASP Dependency-Check, which fails on CVSS ≥ 7 and uploads SARIF (`NVD_API_KEY` secret recommended) |
| `release.yml` | tag `vX.Y.Z` | also builds the R52 evidence pack (`evidence/`: SBOMs, licence, dependency-check, CodeQL summary, test reports, compliance report, config manifest, cosign-signed checksums; a missing mandatory area fails the release); full Tier-1 matrix + security as gates → versioned build → strict licence gate → multi-arch images on GHCR with provenance + SBOM attestations → syft CycloneDX image SBOMs → Helm package → air-gapped bundle (split in < 2 GiB parts) → `SHA256SUMS` → GitHub release |

Third-party actions are pinned to major versions. Pin them to commit SHAs (Dependabot `github-actions`) before the first public release.

---

## 10. Integration notes (contracts other workstreams must honour)

WS-DEPLOY cannot edit these modules. The deployment artefacts assume the following.

**WS-REST (sangam-server):**

- Packaging: a Spring Boot 4.1.2 fat jar `sangam-server-<v>.jar`.
- **MariaDB Connector/J must not be packaged inside it** (scope `provided` or excluded from repackage, LIC-5). The image adds it via `loader.path`.
- The PropertiesLauncher entry point must work.
- Ports: 8080 for the app, 8081 for management (`management.server.port`).
- Probes: liveness and readiness health groups.
- `SANGAM_LIQUIBASE_MODE` = `startup | validate | migrate-only | update-sql | release-locks`. `migrate-only` runs the product changelogs and the Operaton schema, then exits 0. `validate` fails start-up if changesets are pending.
- `SANGAM_JOB_EXECUTOR_ENABLED`, `SANGAM_RECONCILE_ON_START`, `SANGAM_KEYS_DIRECTORY`, `SANGAM_INSTANCE_ID`, `SANGAM_TENANT_DEFAULT`.
- Spring profiles `postgres | oracle | sqlserver | mysql`.
- Isolation guard: SQL Server RCSI check, MySQL READ COMMITTED.
- Graceful shutdown that drains the job executor.
- The JVM runs in UTC (`-Duser.timezone=UTC`); the server and the engine bootstrap refuse any other default zone, an
  unknown one included (SL-53). The same holds for every application that embeds `sangam-engine` (it sets
  `SangamEngineSettings.timerZone` for its business cut-offs). The timer business zone is `sangam.engine.timer-zone`
  (default UTC, never the JVM zone); business dates come from `BusinessClock`.

**WS-SECURITY:**

- The OIDC claim names are configurable: `SANGAM_SECURITY_OIDC_{AUDIENCE,TENANT_CLAIM,ROLES_CLAIM,SEATS_CLAIM}`. The defaults are `sangam-server`, `tenant`, `roles` and `seats`.
- Seat ids are in the format `SEAT-<pack>-<office>-<code>`, as in the dev realm.
- Effective dating and additional charge are resolved server-side. The token's `additional_charge` claim is informational only.

**WS-ENGINE (sangam-persistence):**

- The Liquibase changelogs are online-safe per DB.
- Each release lists the inverse SQL for non-transactional DDL (Oracle and MySQL). See "Failed migration".

**WS-INTEGRATION:**

- A simulator launcher jar, `sangam-integration/target/*-simulators.jar`, built by a Maven profile `simulators-app`.
- Its contract is in `docker/simulators.Dockerfile`:
  - `SANGAM_SIM_KIND` = `ifms | pfms | esign | dlt | digilocker | paygov | smtp`;
  - `SANGAM_SIM_BIND`, `SANGAM_SIM_PORT`, `SANGAM_SIM_DATA`, `SANGAM_SIM_FAULTS`;
  - `SANGAM_SIM_SMTP_PORT`: the SMTP port of kind `smtp`, default 2525 (the image exposes 9000 and 2525);
  - `SANGAM_SIM_PG_CALLBACK_URL`: optional callback URL of kind `paygov`, signed with the simulator's development
    secret;
  - the optional data-folder file `dlt-templates.json` (in `SANGAM_SIM_DATA`) with the DLT templates of kind `dlt`:
    `{"templates":[{"id":"...","header":"...","body":"... {#var#} ..."}],"urlWhitelist":["https://..."]}`;
  - the control endpoints `/__sim/health|ledger|faults`.
- The jar: `mvn -P simulators-app package -pl sangam-integration` (classifier `simulators`).
- The connector base-URL properties are `SANGAM_CONNECTORS_<NAME>_BASE_URL` and `SANGAM_CONNECTORS_PFMS_DIRECTORY`.
- The reconciliation scan endpoint for §4.3.

**WS-UI:**

- Read `/config.json` at boot. Its fields are `apiBase`, `oidc.issuer`, `oidc.clientId`, `defaultLocale`, `locales` and `environment`.
- Call the API on the same origin, under `/api/v1`.
- Build output goes to `dist/`.
- npm scripts `lint`, `typecheck`, `test` (vitest, `--run`) and `build`.

**Test modules (all):**

- Testcontainers suites are selected by `-Dsangam.db=<db>` / `SANGAM_IT_DB`, under a Maven profile `sangam-db-it` (failsafe).
- The images come from `SANGAM_IT_IMAGE_<DB>`.
- The Oracle and Db2 drivers come only from the profiles `sangam-oracle-driver` and `sangam-db2-driver`, at test scope.
- `-DskipITs` must skip all Testcontainers tests.
- Test JVMs run in UTC: the root `pom.xml` passes `-Duser.timezone=UTC` to surefire and failsafe (SL-53). A timer test
  selects its business zone with `sangam.engine.timer-zone`, never with the JVM zone.

**Metric names** used by the dashboard (to be emitted by the modules):

- `sangam_outbox_pending`, `sangam_outbox_oldest_age_seconds`;
- `sangam_reconciliation_items{state}`;
- `sangam_jobs_acquired_total`, `sangam_jobs_failed_total`;
- `sangam_incidents_open`;
- `sangam_lease_stale_total`, `sangam_lease_extended_total`.

### Third-party tools used by deploy/ (not shipped inside Sangam artefacts)

| Tool / image | Licence | Use |
|---|---|---|
| Eclipse Temurin JRE 25, distroless java25 (default); Temurin JRE 21, distroless java21 (JDK 21 build-arg variant) | GPL-2.0 + Classpath Exception (OpenJDK) | runtime base images |
| nginx-unprivileged | BSD-2-Clause | UI runtime |
| Keycloak 26 | Apache-2.0 | dev IdP |
| OpenTelemetry Collector contrib | Apache-2.0 | telemetry |
| Jaeger, Prometheus | Apache-2.0 | optional observability |
| Grafana OSS | AGPL-3.0 | optional observability (unmodified) |
| ClamAV (`clamav/clamav`, pinned tag) | GPL-2.0 | optional upload scanning, a separate service, never linked (owner decision 71) |
| CycloneDX Maven plugin / cyclonedx-npm | Apache-2.0 | SBOM |
| syft | Apache-2.0 | image SBOM |
| OWASP Dependency-Check | Apache-2.0 | CVE scan |
| CodeQL | GitHub terms (free for public repos) | SAST in CI only |

## First administrator (install runbook note)

Set `SANGAM_SECURITY_FIRST_ADMINS_TENANT` and `SANGAM_SECURITY_FIRST_ADMINS_SUBJECTS` (two different `person:subject:Display name` entries, comma-separated, with subjects from your identity provider) for the first start, then remove them. They take effect only while the tenant has no administrator role. No unauthenticated path creates users.
