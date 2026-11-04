# Sangam scale architecture (SCALE workstream)

Status: **design + code complete, nothing measured**. Every number below that is not quoted from `../SANGAM.md`
XI.10 is a design estimate **[I]** and becomes a claim only after the testing phase (section 11). Owner rule: all
code first, testing last.

Inputs: `../SANGAM.md` X.0e (ADR-D-20…D-24), XI.10 (E6, E9, A-11, A-16), X.1 (R3, R4, R19, R22, R29, R36, R38,
R40, R41, R56, R61), `ARCHITECTURE.md`, the sangam-engine/-server/-integration READMEs, `deploy/README.md`.

| Target (ADR-D-24) | Value |
|---|---|
| National peak workflow starts | ~3,000 /s |
| National task + query operations | ~30,000 /s |
| Concurrent users | ~1,000,000 across cells |
| Per-cell floor | ≥ 500 starts/s; more capacity = more cells |

---

## 1. Shape

```
  browsers / SDKs / IFMS, HIS, banks
                │  HTTPS
        ┌───────▼────────┐  route cache (TTL 5 s + epoch), sticky on control-plane outage
        │ sangam-gateway │──────────────────────────────┐ GET /api/v1/routes (incremental)
        └───┬───────┬────┘                              │
            │       │   by tenant → cell                ▼
   ┌────────▼──┐ ┌──▼────────┐   …   ┌──────────────────────────┐
   │ cell in-rj│ │ cell in-mh│       │ sangam-control-plane     │ own DB, own Operaton engine
   │ N servers │ │ N servers │       │ tenant registry, cells,  │ (tenant-move BPMN)
   │ own DB    │ │ own DB    │       │ placement, routing, audit│
   └─────┬─────┘ └─────┬─────┘       └──────────▲───────────────┘
         │ outbox relay│ (rollup:national, xcell:<tenant>)     │ register + heartbeat (10 s)
         ▼             ▼                                      cells
   ┌──────────────────────────┐
   │ sangam-rollup            │ own DB; counts and ids only; district → state → nation
   └──────────────────────────┘
```

* **A cell is the blast radius** (ADR-D-21): its own application nodes, its own database (any Tier-1 DB:
  PostgreSQL, Oracle, SQL Server, MySQL 8.4), its own outbox relay, job executor and key material. No table, lock,
  pool or transaction is shared between cells. A cell holds a set of tenants, typically one state/UT or one large
  central department.
* **The control plane is never on the request path.** Cells serve with the control plane down; gateways serve
  cached routes. It is needed only to place a new tenant, move one, or change a route.
* **No cross-cell transaction exists anywhere.** National and cross-cell views are read models built from events.

Modules (all Apache-2.0, all new):

| Module | Package | Role |
|---|---|---|
| `sangam-cell` | `org.sangam.cell` | Cell-side ports: `CellId`, `GlobalIds`, `CellIdGenerator` + Operaton binding, `ControlPlaneClient`/`CellRegistrationAgent`, `RollupPublisher`, `CrossCellPublisher`, `RollupEnvelope` |
| `sangam-control-plane` | `org.sangam.controlplane` | Spring Boot app: registry, cell directory, placement, routing API, cell registration, tenant-move BPMN |
| `sangam-gateway` | `org.sangam.gateway` | Reactive routing edge (Spring WebFlux + Reactor Netty; no Spring Cloud BOM, see 5.5) |
| `sangam-rollup` | `org.sangam.rollup` | National read model: idempotent, sharded projections; dashboard queries |

---

## 2. Cell sizing, honestly reconciled with E9

### 2.1 What was measured

E9 (XI.10.1) is a **functional, low-confidence** benchmark: Operaton R1 ≈ 35 starts/s, R5-lite ≈ 11.5/s, one node,
untuned, with auth, audit and full history on, on a developer machine, and with the adapter defect XI.10.4
(17 % duplicate-lease errors and ~30 stuck instances in R1 under 4+ workers). It tells us the stack works end to end.
It does **not** tell us the engine's capacity. E6 tells us more about the real limit: a hot root without L2 loses
30–75 % of commands after 5 retries; with L2 (A-11) a single root is designed for at most ~50 commands/s (R3).

The gap: the cell floor (500 starts/s) is **≈14× E9**. The R22 hypothesis (R1 ≥ 2,000 PI/s on 16 vCPU app + 16 vCPU
PG) is ≈57× E9. Neither is evidence. The plan below is what has to be true for 500/s, and section 11 is how we prove
it or learn the real number.

### 2.2 Where a start's cost goes [I]

A typical government or hospital start (start event → 1–2 service steps → first user task) with Sangam on:

| Write | Rows per start (estimate) |
|---|---|
| Operaton runtime (execution, task, variables, identity links, job) | 6–10 |
| Operaton history (level `audit`: proc inst, task, activity) | 3–6 (`full`: 10–20) |
| Sangam outbox + deliveries (history-projector, rollup, channels) | 2–4 |
| Audit chain, operation ledger, idempotency, lease | 3–5 |
| **Total** | **~15–25 rows, 2–4 commits** (async continuations split transactions) |

At 500 starts/s plus 5,000 ops/s per cell (~25 % writes; a completion costs about as much as a start), a cell
database sees **~25,000–40,000 row writes/s and ~3,000–5,000 commits/s**. That is within reach of one well-sized
primary (32 vCPU, 256 GB RAM, NVMe, synchronous standby in the same DC) on PostgreSQL / Oracle / SQL Server, but it
is the dominant cost and the first thing to measure (T-SCALE-1).

### 2.3 Per-cell reference size [I]

| Component | Size | Why |
|---|---|---|
| API nodes | 6 × 8 vCPU / 16 GB | REST + task queries; ~1,000 req/s each at p99 < 300 ms |
| Executor nodes | 4 × 8 vCPU / 16 GB, `SANGAM_JOB_EXECUTOR_ENABLED=true`, API pods `false` | async continuations, timers, relay |
| Per-node start rate needed | 500 / 10 ≈ **50 starts/s per node** (≈ 1.4× E9 per node) | the per-node bar is modest; the shared DB is the real limit |
| Primary DB | 32 vCPU, 256 GB, NVMe, sync standby | 2.2 |
| Read replica(s) | 1–2 (async) | dashboards, history, audit timeline, search (4.5) |
| DB connections | 20–25 per node, ≤ 250 per cell | pool wait > 2 s → 503 back-pressure, never queue unbounded |
| Users | ~50,000–80,000 concurrent sessions per cell | 1M / 12–20 cells; think time ~30 s → ~2,000–2,700 req/s per 80k users |

### 2.4 National cell count [I]

* Throughput alone: 3,000 starts/s ÷ (500 × 0.7 target utilisation) ≈ **9 loaded cells + 1 spare**.
* Residency decides more than throughput (section 9): each state that requires in-state hosting needs its own cell
  (its SDC). Realistically **12–20 cells**: large states alone (UP, MH, BR, WB, TN, RJ, KA, GJ …), small states and
  UTs grouped where their policy allows (`IN` zone), one or two central cells (`IN-C`), one cell for private tenants.
  Most cells then run far below 500/s; that headroom is what absorbs a moved tenant or a state's peak day.
* Gateways: ~33,000 req/s at peak; a Reactor Netty proxy node does ~8,000–15,000 req/s → **4–8 nodes**, HPA 4–40.
* Control plane: 2 replicas, tiny (19,500 tenants ≈ 2 MB route snapshot).
* Roll-up: see 8.4; per-event projection is good for a few thousand events/s per roll-up DB.

### 2.5 What must be proven (summary, details in 11)

1. One cell sustains **≥ 500 starts/s and ≥ 5,000 ops/s for 30 min** on each Tier-1 DB with auth, audit and history
   on, with p99 inside the thresholds, zero lost or duplicated work, and the E9 adapter defect fixed.
2. The per-node rate (≥ 50 starts/s) and the DB headroom at that load (CPU < 70 %, commit latency p99 < 10 ms).
3. Linear scale-out: 2 cells ≈ 2 × one cell through the gateway (no shared bottleneck).
4. Failure behaviour of section 10 (cell down, control plane down, gateway node loss, tenant move under load).

---

## 3. Engine throughput levers (cell side)

| Lever | Setting / design | Owner |
|---|---|---|
| **Job executor** | Separate executor pods from API pods. Per executor node: core 16 / max 32 threads, queue 64, `maxJobsPerAcquisition` 20, `waitTimeInMillis` 500 with back-off ×2 to 5 s, `lockTimeInMillis` 5 min, acquire with SKIP LOCKED (already on), timers first (on), job priorities (on: interactive continuations > batch). `acquireByDueDate` with the due-date index. | WS-ENGINE settings, IR-7 |
| **Async history pipeline** | Default history level `audit` for high-volume definitions via `PerDefinitionHistoryLevel` (FC-26, exists); `full` only where a pack needs it. Detail history goes to the read model through the outbox (`OutboxHistoryEventHandler` → `history-projector`), not into `ACT_HI_*` twice. Option "outbox-only history" per definition: IR-5. | WS-ENGINE/WS-INTEGRATION |
| **Connection pools** | READ COMMITTED (forced), 20–25 per node, `connectionTimeout` 2 s → 503 + Retry-After instead of a hidden queue; statement timeout 5 s (R3). | server config |
| **Partitioned / bucketed hot tables** | `OUTBOX`/`OUTBOX_DELIVERY` daily partitions (R4, exists). `ACT_HI_*` partitioned by removal time (R29) on PostgreSQL/Oracle; SQL Server partition function; MySQL range partitions. Roll-up counters bucketed by day + shard. `ACT_RU_*` stays unpartitioned (engine queries), kept small by fast completion. | WS-ENGINE (IR-8), this module |
| **No hot rows** | No global counters: ids are `<cell>.<uuidv7>` (never `DbIdGenerator`'s `ACT_GE_PROPERTY` row); `ROOT_SEQ` is per root; national totals are sharded counters (16 rows per node/metric/day); the control plane's heartbeat is one row per cell. Model rule: never funnel a district's hospitals into one long-lived root (R3: ≤ 50 cmd/s per root); use one instance per item + aggregation (GP-09 pattern). | design rule, packs |
| **Read replicas for queries** | Dashboards, audit timeline, history, search, exports → replica (stale ≤ 1 s acceptable). Task lists and anything followed by a write stay on the primary (read-your-writes). Needs a routing DataSource in the server: IR-6. | WS-REST/server |
| **Caching** | Operaton deployment + DMN caches (bounded, R30); VI.4 policy cache ≤ 5 s; gateway route cache (TTL 5 s + epoch); tenant-path cache in cells (5 min, stale-if-error). **Never** cache task lists, counts or authorization results beyond R19's 5 s. | all |
| **Back-pressure** | Layered, each answering 503/429 + Retry-After and never dropping committed work: gateway per-tenant in-flight cap (429) → cell outbox fill (R40: 80 % → new starts 503, 95 % → only completions) → DB pool wait (503) → job queue full (acquisition pauses). Placement stops at outbox fill ≥ 50 % or pool ≥ 80 %. | gateway, integration, control plane |
| **Count cost (A-16)** | Capped counts ("more than N", `X-Total-Count-Capped`) on every list; `AUTH_EFFECTIVE` indexes. | WS-SECURITY |
| **One engine per JVM (D-10/W-7)** | Static `ClockUtil`: one engine per JVM; scale = more pods, never more engines per pod. Cells are already that. | — |

---

## 4. Users and edge

### 4.1 One million concurrent users
Sessions are stateless JWTs; no server-side session store. 1M users at ~30 s think time ≈ 33,000 req/s, matching the
30,000 ops/s target. The gateway holds only connection state; a node is lost without user impact beyond in-flight
requests (clients retry idempotent calls with `Idempotency-Key`, R41).

### 4.2 Tenant resolution
JWT `tenant` claim (unverified at the edge, verified by the cell, R19) → host `<tenant><suffix>` → `X-Sangam-Tenant`
header. Mis-routing by a forged header ends at a cell that rejects the token's tenant mismatch; routing is not
authorization.

---

## 5. Control plane

### 5.1 Tenant registry (`CP_TENANT`)
N-level hierarchy (`parent_code`, materialised `path`, `depth`, `level_kind` as data: NATION, STATE, DEPT, DISTRICT,
HOSPITAL, OFFICE … ADR-D-20). Per tenant: `jurisdiction`, `residency` (inherited from the parent and only narrowable),
`sector`, `expected_starts_per_sec` (placement weight), `cell_id`, `status` (ACTIVE, DRAINING, MOVING, RETIRED) and
`route_epoch` (bumped on every route-relevant change, compare-and-set on cut-over).

### 5.2 Cell directory (`CP_CELL`)
Registration is an idempotent `PUT /api/v1/cells/{id}` (residency immutable, retired ids never reused); heartbeat
every 10 s with status, starts/s, p99, nodes, pool usage, outbox fill, tenant count. No heartbeat for 30 s → DOWN.

### 5.3 Placement policy (`PlacementService`)
1. Hard filters: UP, placement open, **cell residency within the tenant's zone**, projected load ≤ 0.7 × capacity,
   tenant count < capacity, outbox fill < 50 %, pool < 80 %.
2. Hierarchy affinity: nearest placed ancestor's cell, then siblings' cells (a state's districts and hospitals share
   a cell, so state ↔ district ↔ hospital workflows stay intra-cell).
3. Headroom; ties by cell id (deterministic).

### 5.4 Routing API
`GET /api/v1/routes/tenants/{code}` → `{cellId, apiBaseUrl, state, routeEpoch, ttlSeconds, retryAfterSeconds, path}`;
`GET /api/v1/routes?sinceMs=` incremental snapshot for gateways. States: ROUTABLE, DRAINING, MOVING, CELL_DOWN,
UNPLACED.

### 5.5 Gateway
Reactive proxy on Spring WebFlux / Reactor Netty (Apache-2.0). Spring Cloud Gateway was not used: it needs a Spring
Cloud release-train BOM, and the parent POM forbids importing a second Spring BOM; the proxy needs ~400 lines.
Features: tenant resolution (4.2), sticky route cache (stale-if-error up to 24 h), request-id propagation
(`X-Request-Id` kept or minted, echoed; `traceparent` passed through), `X-Sangam-Cell` / `X-Sangam-Route-Epoch`
headers to the cell, 503 + Retry-After for DRAINING writes, MOVING, CELL_DOWN, unreachable cell and 421 (tenant moved:
cache invalidated), 429 above the per-tenant in-flight cap, streaming bodies.

### 5.6 Security
Static bearer tokens per role (ADMIN, CELL, GATEWAY, ROLLUP, DASHBOARD) from Kubernetes Secrets, constant-time
comparison; mTLS at the mesh is recommended. OIDC for ADMIN is a gap (12).

---

## 6. Global identifiers

| Kind | Format | Property |
|---|---|---|
| Engine/product row id | `<cellId>.<uuidv7>`, ≤ 53 chars (fits `VARCHAR(64)`) | Unique across cells with no coordination; time-ordered (B-tree friendly); prefix = minting cell, kept after a move |
| Cell id | `in-rj-01` (2–16 chars, immutable, never reused) | |
| Tenant code | `RJ-JPR-H0042` (`[A-Z0-9_-]{1,64}`, nationally unique in the registry, immutable) | |
| Business key | `<tenantCode>/<localKey>` (`GlobalIds.businessKey`) | Unique nationally because the tenant code is; **survives a move unchanged** (no cell in it) |
| operationId (R36) | UUIDv5(tenant, root, activity instance, loop, retrySafeKey) | Unchanged by a move; cross-cell exactly-once key |
| Event id | UUIDv7 from the cell outbox | Roll-up dedup key |

Routing always goes by tenant, never by id prefix. The Operaton binding is `CellIdGeneratorPlugin` (IR-1).

---

## 7. Tenant move between cells (drain → copy → verify → cut-over)

Runs as BPMN `sangam-tenant-move` on the control plane's own Operaton engine (`bpmn/tenant-move.bpmn`): every step
async with a retry cycle, so a control-plane restart resumes the move; failures in copy/verify roll back.

| Step | Source cell | Target cell | Route / registry |
|---|---|---|---|
| 1 prepare | – | tenant created in IMPORTING (refuses traffic) | ACTIVE |
| 2 freeze | refuses new starts, inbound events and writes for the tenant (503 + Retry-After); in-flight commands finish; relay keeps draining | – | DRAINING (gateway: reads pass, writes 503) |
| 3 drain loop | report in-flight commands, pending outbox, locked work; poll every 15 s until 0 (timers are copied, not drained) | – | DRAINING |
| 4 copy | consistent export of all tenant rows (engine + product + audit chain + inbox/ledger/idempotency), pull handle with watermark and audit chain head | pulls and loads the export (cell to cell, residency-preserving; the control plane never sees data) | MOVING (gateway: 503) |
| 5 verify | per-table row counts + order-independent content hash | same | mismatch → rollback |
| 6 cut-over | – | activate; append `TENANT_MOVED_IN` anchored on the source chain head | `cell_id` = target, `route_epoch`+1 (CAS), ACTIVE |
| 6b | mark MOVED: answers 421 so stale gateways re-resolve | – | |
| 7 retain | data kept read-only for `move-retention` (7 d) = rollback window | – | |
| 8 purge | purge (legal-hold aware, R61) | – | COMPLETED |

**No data loss:** the source is frozen before the copy and unfrozen only by a rollback after the target import was
dropped, so there is no instant with two writable copies. Work that was in flight finished on the source before the
copy; outbox deliveries drained before the copy; inbound messages arriving during the move get 503 and are redelivered
by their sender/broker after cut-over to the target, whose inbox dedups them (R38). Leased external tasks are drained
(locked work = 0) before the copy; timers move with the data and fire on the target (their due dates are UTC, R31).
**Audit continuity:** the target's chain starts with an entry that commits to the source's final chain head; the
control-plane audit records every step with a content hash. **Ids** do not change (prefix = minting cell).
**Downtime:** writes for one tenant are unavailable for drain + copy + verify (minutes for a hospital, more for a
large department); other tenants of both cells are unaffected.

Cell-side endpoints needed: IR-4.

---

## 8. National roll-up and cross-cell workflows

### 8.1 Stream
Each cell's relay publishes destination `rollup:national` (`RollupPublisher`): CloudEvents 1.0 with
`sangamcell`, `sangamtenant`, `sangamtenantpath`, `sangamroot`, `sangamrootseq`. Only allow-listed event types
(process start/end, task complete, incident create, cross-cell, explicit metrics) and allow-listed fields leave the
cell; no personal data (section 9). At-least-once from the relay; 4xx = poison.

### 8.2 Projection (`RollupProjector`)
One transaction per event: insert `RU_EVENT_SEEN(event_id)` (duplicate → acknowledged, nothing counted) + add deltas
to `RU_COUNTER` for **every prefix of the tenant path** (hospital → district → state → nation) on shard
`hash(eventId) mod 16`, per UTC day. Dedup and counters commit together, so counts are exact under redelivery and
crashes. Metrics are generic (`process.start`, `task.complete`, `process.open` …, optionally per definition).
Queries: totals per node, daily series, children drill-down, per-cell feed lag.

### 8.3 Cross-cell workflows (state → central and back)
Never a distributed transaction. The sending cell commits its own state change **and** an outbox event to
`xcell:<targetTenant>` in one local transaction; `CrossCellPublisher` posts it through the gateway to the target
cell's `/api/v1/messages` with `Idempotency-Key = operationId` (R36/R41); the target inbox (R38, PK
tenant+source+message_id) makes the receive exactly-once, and replies come back the same way with their own
operationId. A message that cannot be delivered stays PENDING/PARKED in the sender's outbox (alerted), never lost.
A long cross-cell interaction is modelled as two processes correlated by business key (message start/catch events),
with SLA timers on the waiting side.

### 8.4 Roll-up capacity [I] and scaling path
National stream ≈ 3 events per workflow ≈ 9,000–12,000 events/s at peak; each touches ~4 levels × 2–3 metrics ≈ 10
row updates → ~100,000 updates/s if projected naively on one DB: **too much for one roll-up DB**. Path:
(a) one roll-up per state/region (path prefix), national roll-up fed by their per-minute aggregates
(`sangam.rollup.metric` events); (b) micro-batched ingest (arrays, already accepted) with in-memory pre-aggregation
per (node, metric, day) within a batch before commit (gap, 12); (c) Kafka as transport (`KafkaPublisher` exists).

---

## 9. Data residency per cell

* Every cell has a **residency zone** (`IN`, `IN-RJ`, `IN-RJ-SDC1`, `US-…`, `EU-…`); zones are hierarchical codes.
* Every tenant has a required zone; children may only narrow it. Placement and moves only choose cells whose zone lies
  **within** the tenant's zone (`ResidencyRules`). A Rajasthan tenant requiring `IN-RJ` cannot land in `IN-MH` or in a
  generic `IN` cell; a private hospital with `IN` may go to any Indian cell.
* Personal and business data never leaves its cell. Crossing the boundary: (1) roll-up events with allow-listed,
  non-personal fields (counts, definition keys, amounts in minor units, categories); (2) cross-cell business messages,
  which carry only what the receiving jurisdiction is entitled to (pack-defined payload; DPDP purpose tag, R53);
  (3) tenant moves, cell to cell, only between cells satisfying the tenant's zone.
* The control plane holds names, codes and hierarchy only; the roll-up holds counts. Both can therefore run in a
  national DC (NIC, `IN`) without breaking state residency.
* Backups of a cell stay in the cell's zone (deploy README 3).

---

## 10. Failure modes

| Failure | Effect | Behaviour |
|---|---|---|
| **One cell down** (DB or all nodes) | Only its tenants | Gateway answers 503 + Retry-After 30 (CELL_DOWN after 30 s without heartbeat, immediately on connect errors). Other cells unaffected. Cross-cell messages to it wait in senders' outboxes; roll-up shows the cell's feed lag. Recovery per ADR-D-12 (RPO ≤ 5 min, RTO ≤ 1 h, deploy README 4) + reconciliation (4.3). |
| **Cell DB failover** | Seconds to minutes | Pools reconnect; in-flight commands fail and are retried by clients (idempotency); leases fenced (VI.3). |
| **Control plane down** | No placements, moves or route changes | Cells serve; gateways serve cached routes (stale-if-error, 24 h); cells serve cached tenant paths; a move in progress pauses (BPMN jobs resume on restart). Only brand-new tenants cannot be routed. |
| **Control plane DB lost** | As above until restored | Rebuildable: cells re-register within 10 s; tenant→cell mapping restored from backup (RPO ≤ 5 min) and cross-checked against each cell's tenant list. |
| **Gateway node lost** | In-flight requests on it | L4 balancer drops it; clients retry with Idempotency-Key. Stateless, HPA replaces it. |
| **All gateways down** | Edge outage | Cells are still reachable on their own ingress for operators and server-to-server integrations (documented emergency path). |
| **Router mis-route (stale cache)** | One request | Source cell answers 421 for a moved tenant → gateway invalidates and answers 503 Retry-After 1; never served by the wrong cell because cells refuse foreign tenants. |
| **Roll-up down** | Dashboards stale | Cells' `rollup:national` deliveries back up in their outboxes (bounded by R40 capacity alerts; roll-up is an unordered destination so it never blocks other destinations); replayed on recovery, dedup keeps counts exact. |
| **Move fails mid-way** | One tenant writes unavailable longer | Copy/verify failure → rollback (drop target import, unfreeze source). Retryable cell errors → job retries, then incident for the operator; tenant stays frozen (safe) until resolved. |
| **Split brain fear** (both cells writable) | Prevented | Freeze-before-copy + CAS route epoch + source 421 after cut-over. |

---

## 11. Testing-phase load model and acceptance (`perf/`)

`perf/k6/sangam-cell-mix.js` (k6, AGPL tool, not shipped or linked; scripts only, not run now) reproduces per cell:
500 starts/s (75 % hospital flows, 25 % department flows), 5,000 ops/s (40 % task lists, 15 % task get, 15 %
claim+complete, 15 % worker fetch/complete, 10 % instance get, 5 % search), profiles `cell`, `national` (6× through
the gateway), `soak` (24 h at 60 %), `spike` (150 %).

### 11.1 Must be proven
| Id | Claim | Pass criterion |
|---|---|---|
| T-SCALE-1 | Cell floor | 500 starts/s + 5,000 ops/s for 30 min steady state on PostgreSQL, Oracle, SQL Server, MySQL; ≥ 98 % of offered starts accepted |
| T-SCALE-2 | Latency | p95/p99 per op inside the k6 thresholds (start p99 < 300 ms, list p99 < 250 ms, get p99 < 120 ms) |
| T-SCALE-3 | Correctness under load | 0 lost, 0 duplicated instances/effects (operationId ledger audit after the run); 5xx (non-back-pressure) < 0.1 % |
| T-SCALE-4 | DB headroom | primary CPU < 70 %, commit p99 < 10 ms, no lock-wait p99 > 100 ms at the floor |
| T-SCALE-5 | Linear scale-out | 2 cells via gateway ≥ 1.9× one cell; gateway p99 overhead < 10 ms |
| T-SCALE-6 | Back-pressure | spike profile: only 503/429 with Retry-After, no lost work, recovery to steady state < 5 min |
| T-SCALE-7 | Soak (R30) | 24 h at 60 %: no heap/connection growth, outbox drain rate > arrival rate |
| T-SCALE-8 | Tenant move under load | a hospital tenant moved while the cell runs at 60 %: other tenants' p99 unchanged ±10 %, moved tenant checksum equal, audit chain continuous, write outage measured and published |
| T-SCALE-9 | Failure drills | cell kill, control-plane kill, gateway node kill, roll-up kill: behaviour as section 10 |
| T-SCALE-10 | Roll-up exactness | replay the whole stream twice: national totals equal per-cell truth (SQL count on cells) |
| T-SCALE-11 | E9 defect fixed | 4+ concurrent workers: 0 duplicate-lease errors, 0 stuck instances (XI.10.4) |

If T-SCALE-1 fails, the published result is the measured per-cell number with raw data (R22), and the national plan
is re-derived as 3,000 ÷ (measured × 0.7) cells; the cell architecture does not change, only the count.

---

## 12. Deploy and gaps

* `deploy/helm/sangam-national`: umbrella with control plane, gateway (HPA, PDB, zone spread), roll-up and example
  cells `cell-a`/`cell-b` (aliases of the `sangam` chart); `helmfile.yaml.gotmpl` for N cells (one release each).
* `deploy/compose/docker-compose.cells.yml`, profile `two-cells`: cells `in-ra-01` and `in-rb-01` with separate
  databases, control plane, gateway, roll-up; `deploy/docker/scale.Dockerfile` builds the three new images.

**Known gaps (left for later, listed honestly):**
1. Cell-side wiring and endpoints are integration requests IR-1…IR-8 (`sangam-cell/README.md`); until IR-2/IR-3/IR-4
   are done, cells do not self-register, the roll-up gets no events, and tenant moves cannot run end to end.
2. Roll-up micro-batch pre-aggregation and state-level roll-up tier (8.4) are designed, not built.
3. Control-plane ADMIN authentication is static tokens; OIDC (Parichay/state IdP) for operators is not wired.
4. Gateway: no WebSocket/SSE upgrade proxying; no per-tenant rate limit across gateway nodes (cap is per node).
5. Control-plane HA is active-active on one DB; multi-DC control plane (DR replica) follows deploy README 4.
6. No tests were written or run (owner rule); all numbers in sections 2 and 8.4 are estimates.
