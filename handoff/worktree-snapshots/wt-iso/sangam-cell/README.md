# sangam-cell (SCALE)

Cell-side scale ports, in new code only (engine and server code belong to other workstreams). Design:
`../SCALE-ARCHITECTURE.md`. Used by sangam-control-plane, sangam-gateway and sangam-rollup for the shared contracts.

## Files and requirements covered

| File | Covers |
|---|---|
| `id/CellId.java` | ADR-D-21 cell identity (immutable, never reused) |
| `id/GlobalIds.java` | SCALE 6: `<cell>.<uuidv7>` ids, `<tenant>/<key>` business keys unique across cells |
| `id/CellIdGenerator.java` | Port (engine-neutral) |
| `id/OperatonCellIdGenerator.java`, `id/CellIdGeneratorPlugin.java` | Operaton binding of the port (replaces StrongUuidGenerator; no hot `ACT_GE_PROPERTY` row) |
| `registration/ControlPlaneClient.java`, `CellDescriptor.java`, `CellHeartbeat.java`, `CellRegistrationAgent.java` | Cell registration + 10 s heartbeat; tenant-path lookup with stale-if-error (SCALE 5.2, 10) |
| `rollup/RollupPublisher.java`, `RollupEnvelope.java`, `TenantPathResolver.java` | Outbox publisher for `rollup:national` (VI.2, R4 at-least-once, event/field allow-lists for residency, SCALE 8.1, 9) |
| `rollup/CrossCellPublisher.java` | `xcell:<tenant>` cross-cell messages, operationId as Idempotency-Key (R36, R38, R41; SCALE 8.3) |
| `rollup/MiniJson.java` | Dependency-free bounded JSON (R11 depth limit) |

## Integration notes (precise patch requests; the integrator wires them)

**IR-1 Cell id generator (sangam-server `EngineWiring`, WS-ENGINE).** When `sangam.cell.id` (env `SANGAM_CELL_ID`)
is set, build the engine with the plugin after Sangam's own:
`SangamEngineFactory.build(ds, settings, url, List.of(new CellIdGeneratorPlugin(CellId.of(cellId)), portsPlugin…))`
(or `SangamEngineConfigurer.configure(cfg, ds, List.of(…))`). `SangamProcessEnginePlugin.preInit` sets
`StrongUuidGenerator`; plugins run in order, so this one wins. No schema change: ids are ≤ 53 chars. Product-layer ids
that use `Hashes.uuidV7()` may stay as they are (globally unique already); new ones should use `GlobalIds.newId`.

**IR-2 Cell registration (sangam-server, new `config/CellWiring`).** Conditional on `sangam.cell.id`:
```java
ControlPlaneClient cp = new ControlPlaneClient(URI.create(env("SANGAM_CONTROL_PLANE_URL")), env("SANGAM_CONTROL_PLANE_TOKEN"), Duration.ofMinutes(5));
CellRegistrationAgent agent = new CellRegistrationAgent(cp,
    new CellDescriptor(cellId, region, jurisdiction, residency /* SANGAM_CELL_RESIDENCY */, publicApiUrl, adminUrl, dbVendor, 500, 2000, version),
    () -> new CellHeartbeat(cellId, status, startsPerSec, p99CommandMs, activeNodes, poolUsagePct,
                            outboxCapacityMonitor.fillPct(), tenantCount, version), Duration.ofSeconds(10));
```
`start()` on `ApplicationReadyEvent`, `close()` on shutdown. Metrics from Micrometer (`sangam_process_starts`
rate, `http.server.requests` p99, Hikari `hikaricp.connections.usage`) and `OutboxCapacityMonitor` (integration).
Status DRAINING while the node is shutting down. Only one node per cell needs to report load figures for the cell
(the leader of `LeaderElection`); all nodes may register (idempotent).

**IR-3 Roll-up and cross-cell publishers (sangam-server `IntegrationWiring`, WS-INTEGRATION).**
```java
publisherRegistry.register(RollupPublisher.DESTINATION,
    wrapPoison(new RollupPublisher(CellId.of(cellId), URI.create(env("SANGAM_ROLLUP_INGEST_URL")), cp::tenantPath, env("SANGAM_ROLLUP_TOKEN"), null)));
publisherRegistry.register(CrossCellPublisher.PREFIX + "*",
    wrapPoison(new CrossCellPublisher(URI.create(env("SANGAM_GATEWAY_URL")), env("SANGAM_SERVICE_TOKEN"), cellId)));
```
`wrapPoison` maps `RollupPublisher.PoisonEventException` to `PermanentPublishException` (so the relay parks/skips
at once). Add `rollup:national` as an **unordered** default destination (`sangam.integration.default-destinations`)
for history events; `xcell:<tenant>` is set per event by the pack step that sends a cross-cell message
(payload `{operationId, messageName, businessKey, variables}`). The target cell's `/api/v1/messages` must accept
`X-Sangam-Source-Cell`/`X-Sangam-Source-Tenant` from a service principal with role `XCELL` and use the
Idempotency-Key as inbox `message_id` (WS-REST/WS-INTEGRATION).

**IR-4 Tenant-admin API on the cell (WS-REST + WS-INTEGRATION + WS-ENGINE).** Under
`/api/v1/admin/cell/tenants/{tenant}` (service role `CELL_ADMIN`, Idempotency-Key honoured):
`POST /import/prepare`, `POST /freeze`, `GET /drain-status` → `{inFlightCommands, pendingOutbox, lockedWork, dueTimers}`,
`POST /export` → `{url, token, rows, watermark, auditHead}` (consistent snapshot of every row with `tenant_id`/
`TENANT_ID_` = tenant: ACT_RU_*, ACT_HI_*, ACT_RE_* deployments of the tenant, ACT_GE_BYTEARRAY referenced, all product
tables), `POST /import` `{url, token, watermark}` → `{rowCounts}`, `GET /checksum` → `{rowCounts, contentHash}`
(order-independent hash, same canonical row form on both sides), `POST /activate` `{sourceAuditHead}`,
`POST /moved` `{targetCell}` (then answer **421** for that tenant), `POST /unfreeze`, `POST /import/drop`,
`POST /purge` (refuses rows under legal hold, R61). Freeze = the server's back-pressure filter returns 503 + Retry-After
for the tenant's mutations and inbound events. Contract is in `sangam-control-plane/.../move/CellOps.java`.

**IR-5 History "outbox-only" per definition (WS-ENGINE/WS-INTEGRATION).** Option to skip `DbHistoryEventHandler`
for listed definitions and keep only the outbox history (read model), cutting 3–20 row writes per start.

**IR-6 Read-replica routing (sangam-server/WS-REST).** A routing DataSource: dashboards, audit timeline, history,
exports, search use `sangam.datasource.replica.*` when configured; task lists and read-then-write stay on the primary.

**IR-7 Job executor settings (sangam-server `application.yaml`).** Expose `operaton.bpm.job-execution.core-pool-size`,
`max-pool-size`, `queue-capacity`, `max-jobs-per-acquisition`, `wait-time-in-millis`, `max-wait`, `lock-time-in-millis`
as env (`SANGAM_JOB_*`) with SCALE 3 defaults.

**IR-8 History partitioning (sangam-persistence/WS-ENGINE).** Per-dialect partitioning of `ACT_HI_*` by
`REMOVAL_TIME_` (R29) as an optional changelog context `partitioned-history`.

## Third-party dependencies
| Dependency | Licence |
|---|---|
| org.sangam:sangam-core (internal) | Apache-2.0 |
| org.operaton.bpm:operaton-engine 2.2.0 (optional, only for the plugin) | Apache-2.0 |
| org.slf4j:slf4j-api 2.0.17 | MIT |
JDK HTTP client only; no new third-party library.
