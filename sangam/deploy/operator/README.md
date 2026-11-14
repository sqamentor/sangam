# sangam-operator (M-090)

A Kubernetes operator for Sangam: a separate artefact next to the Helm chart, which stays supported. Use one or the other for an installation, not both.

**Evidence level:** compiled. `ReconcilerTest` and `InstallationSpecTest` (and `KubeHttpTest` in `sangam-kube`) run against an in-memory API server and a fake HTTP layer; they have not been run yet (test phase). The operator has never been run against a real cluster.

## Resources

| Kind | What it does |
|---|---|
| `SangamInstallation` (`sgi`) | One Sangam server: image, replicas, database, extra env/envFrom, state claim, migration mode |
| `SangamCell` (`sgc`) | Makes the installation it names a SCALE cell (same settings as the chart's `scale.cell`; cell id immutable once bound) |

CRDs: `crds/`. Operator, ServiceAccount and namespaced Role: `manifests/operator.yaml`.

## What the operator does

1. **Schema first (R12).** When `spec.image` has not been migrated yet, the operator creates one migrate-only Job for that image (`SANGAM_LIQUIBASE_MODE=migrate-only`, Liquibase session lock, Operaton schema update; `backoffLimit: 0`). The server Deployment is not touched until the Job completes, so the running N-1 pods keep serving (R32). It never starts a second migration while another of the same installation is active.
2. **Failed migration.** Phase `MigrationFailed`; nothing rolls out and nothing is retried. Read the Job's log, then delete the Job to retry, or set `spec.image` back.
3. **Rollout.** Deployment (`maxUnavailable: 0`, `maxSurge: 1`, pods only validate the schema), Service, and the state claim `<name>-state` (created once, never deleted by the operator). Change `spec.restartToken` for a rolling restart.
4. **Status.** `Ready` once every replica of the current generation is available. Other phases: `Invalid`, `Paused`, `Migrating`, `RollingOut`, `Error`.

`spec.migration.mode: none` is for a DBA who applies the SQL out of band (`SANGAM_LIQUIBASE_MODE=update-sql`); the operator then only rolls out. The chart's `startup` mode (every pod migrates) is not offered.

## Settings

The operator sets only the database connection, Spring profiles (`<database.type>,oidc` plus `spec.springProfiles`), `JAVA_TOOL_OPTIONS`, the Liquibase mode, the instance id, outbox destinations and, for a cell, the cell keys and tokens. `spec.env` may not repeat these names. Every other server setting (OIDC issuer, public URLs, production posture and so on) goes in `spec.envFrom` (ConfigMaps or Secrets) or `spec.configMapName` (mounted at `/opt/sangam/config`), as with the chart's `server.envFrom` and `server.config`.

Operator process: `SANGAM_OPERATOR_NAMESPACE` (default: its own), `SANGAM_OPERATOR_RESYNC_SECONDS` (15), `SANGAM_OPERATOR_HEALTH_PORT` (8081, `GET /healthz`).

## Design notes

- Plain Kubernetes REST API over the JDK HTTP client and Jackson, through the shared client module `sangam-kube` (owner decision 155; the server's backup console uses the same client). No Kubernetes client library is used (none is in the build cache). TLS trusts only the service-account CA, and the rotated token is read again for every request.
- Level-triggered polling, not watches: a restart or a missed event loses nothing.
- Every write is idempotent: Job names derive from the image (a second create answers 409), and objects are written by server-side apply (field manager `sangam-operator`). Two operator replicas acting at once therefore cannot run two migrations for one image.

## Integration notes

- **Build:** module `deploy/operator` (artifact `sangam-operator`, shaded jar `sangam-operator.jar`), built with `-pl deploy/operator -am` because it depends on `sangam-kube`. Image: `deploy/operator/Dockerfile`.
- **Tables / screens / server wiring:** none.
- **Limits:** no admission webhook. The CRD schema (CEL) and the operator both validate. The cell id format is checked by the server at start-up (`CellId`), not by the operator.

## Third-party dependencies

Jackson (Apache-2.0) and the Sangam module `sangam-kube`, both shaded into the jar.
