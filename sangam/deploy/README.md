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

Also: `SUPPORT-MATRIX.md` (R5, R7: JDK, framework, driver and deployment matrix, native-light APIs) and `evidence/` (R52: audit evidence pack).

**Runtime since this update:**

- JDK 25 is the default image runtime.
- Spring Boot is 4.1.2.
- Environment names follow `sangam-server/src/main/resources/application*.yaml` (`SANGAM_DB_URL`, `SANGAM_OIDC_ISSUER`, `SANGAM_OTLP_ENABLED`, ...).
- `server.Dockerfile` also builds the SCALE apps through `--build-arg APP_MODULE=`.
- Writable state is `/var/lib/sangam`. It holds the compliance log archive, so back it up and keep it at least 180 days (R51). Helm: `server.state`, ReadWriteMany.
- SCALE on compose: `docker compose -f docker-compose.yml -f docker-compose.scale.yml --profile postgres up -d`. On Helm: `scale.*` (`examples/values-scale.yaml`).

---

## 0. Layout

```
deploy/
├─ docker/
│  ├─ server.Dockerfile        Spring Boot server
│  │                           targets: runtime (Temurin JRE 21, uid 10001), runtime-distroless (uid 65532)
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
```

Choose exactly **one** database profile at a time. Each profile starts its own `server-<db>` service, all under the network alias `sangam-server`.

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
| Platform | `admin.dev`, `operator.dev`, `auditor.dev`, `modeller.dev`, `reviewer.dev`, `approver.dev` |

All of them use the development password set in `keycloak/generate_realm.py`. Never reuse it.

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
- **Oracle driver.** Oracle needs a customer-supplied driver: `drivers.existingClaim`, or `drivers.initImage` (an image you build with `/drivers/ojdbc11.jar`). The chart refuses to render without one (LIC-2).
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
- **Pod security.** Pods run as non-root with a read-only root filesystem, all capabilities dropped and seccomp `RuntimeDefault`.

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

On VMs, run the database migrations **once** before starting the nodes:

```bash
SANGAM_LIQUIBASE_MODE=migrate-only SPRING_MAIN_WEB_APPLICATION_TYPE=none java ... (same command)
```

Then start every node with `SANGAM_LIQUIBASE_MODE=validate`.

### 1.4 Database preparation (all installs)

| DB | Must have | Recommended |
|---|---|---|
| PostgreSQL 15–17 | UTF8 database, `timezone=UTC`, `default_transaction_isolation='read committed'`, user owning schema | `wal_level=replica`, physical replica at DR site, `max_connections` ≥ pods × pool + 20 |
| Oracle 19c/21c/23ai | AL32UTF8, app user with CONNECT, RESOURCE, CREATE VIEW/SEQUENCE/PROCEDURE/TRIGGER/TYPE, quota | ARCHIVELOG mode, FRA, Data Guard standby at DR |
| SQL Server 2019/2022 | `ALTER DATABASE sangam SET READ_COMMITTED_SNAPSHOT ON` (the server refuses to start without it), UTF-8 collation (`*_UTF8`) | FULL recovery model, Always On AG (async replica at DR) |
| MySQL 8.4 | `transaction_isolation=READ-COMMITTED`, `utf8mb4` / `utf8mb4_bin`, `default_time_zone='+00:00'`, `binlog_format=ROW` | GTID on, `sync_binlog=1`, `innodb_flush_log_at_trx_commit=1`, async replica at DR |

For SQL Server and MySQL, the scripts in `compose/db/` are the reference.

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
deploy/airgap/save-images.sh --version 0.1.0 --registry ghcr.io/sqamentor --sbom-dir deploy/airgap/out/sbom
# Windows: powershell -File deploy\airgap\save-images.ps1 -Version 0.1.0
```

On the shared build machine, wrap heavy steps in the lock:

```bash
python -E -X utf8 "%TEMP%\heavy_lock.py" run --owner customwf -- <cmd>
```

The bundle `sangam-airgap-<v>.tar` (with its `.sha256`) contains:

- `images/*.tar.gz`;
- `images.lock` (pinned digests);
- `chart/`, `compose/`, `docs/`;
- the MariaDB source offer;
- `sbom/`;
- `SHA256SUMS`.

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
- Grafana has analytics and update checks off;
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

  The Oracle JDBC driver for CI tests comes from your internal mirror (Maven Central `com.oracle.database.jdbc`), never from the bundle.
- **npm:** on the connected side, run `npm ci` in `sangam/sangam-ui`. Then either:
  - `npm pack` every dependency;
  - better: tar `~/.npm/_cacache` after `npm ci --cache ./npm-cache`.

  Inside, run `npm ci --offline --cache ./npm-cache`, or set `NPM_CONFIG_REGISTRY=https://nexus.sdc.local/repository/npm-all/` (an internal npm proxy). The UI Dockerfile accepts `--build-arg NPM_CONFIG_REGISTRY=...`.
- **Docker builds offline:** use the `prebuilt` targets (`--build-arg JAR_SOURCE=prebuilt`, `--build-arg DIST_SOURCE=prebuilt`). They need no network beyond the base images, which are in the bundle (`docker load`).
- **SBOM offline:** `deploy/airgap/generate-sbom.sh --offline`.

---

## 7. Licence rules enforced here (X.3a)

| Rule | Enforcement |
|---|---|
| LIC-1 GraalJS, LIC-10 Rhino / Nashorn / Jython / JRuby | `check_licences.py` forbidden components + nested-jar patterns; image build guard |
| LIC-2 Oracle JDBC, LIC-3 Db2 JCC, LIC-4 MySQL Connector/J | Never in an image or the fat jar (Dockerfile guard, licence gate `--jar`, dependency-tree grep in `licence-check.yml`). Customers mount `/opt/sangam/drivers` (compose `./drivers`, Helm `drivers.*`). CI fetches ojdbc at test scope only (`-Psangam-oracle-driver`) |
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
- **Logs.** JSON logs (ECS) go to stdout. Ship them to an India-resident store with **≥ 180 days** retention (R51, CERT-In).
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
  - `SANGAM_SIM_BIND`, `SANGAM_SIM_PORT`, `SANGAM_SIM_DATA`;
  - the control endpoints `/__sim/health|ledger|faults`.
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

**Metric names** used by the dashboard (to be emitted by the modules):

- `sangam_outbox_pending`, `sangam_outbox_oldest_age_seconds`;
- `sangam_reconciliation_items{state}`;
- `sangam_jobs_acquired_total`, `sangam_jobs_failed_total`;
- `sangam_incidents_open`;
- `sangam_lease_stale_total`, `sangam_lease_extended_total`.

### Third-party tools used by deploy/ (not shipped inside Sangam artefacts)

| Tool / image | Licence | Use |
|---|---|---|
| Eclipse Temurin JRE 21, distroless java21 | GPL-2.0 + Classpath Exception (OpenJDK) | runtime base images |
| nginx-unprivileged | BSD-2-Clause | UI runtime |
| Keycloak 26 | Apache-2.0 | dev IdP |
| OpenTelemetry Collector contrib | Apache-2.0 | telemetry |
| Jaeger, Prometheus | Apache-2.0 | optional observability |
| Grafana OSS | AGPL-3.0 | optional observability (unmodified) |
| CycloneDX Maven plugin / cyclonedx-npm | Apache-2.0 | SBOM |
| syft | Apache-2.0 | image SBOM |
| OWASP Dependency-Check | Apache-2.0 | CVE scan |
| CodeQL | GitHub terms (free for public repos) | SAST in CI only |

## First administrator (install runbook note)

Set `SANGAM_SECURITY_FIRST_ADMINS_TENANT` and `SANGAM_SECURITY_FIRST_ADMINS_SUBJECTS` (two different `person:subject:Display name` entries, comma-separated, with subjects from your identity provider) for the first start, then remove them. They take effect only while the tenant has no administrator role. No unauthenticated path creates users.
