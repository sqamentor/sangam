# W2 job side tables: contract between W2-D (engine) and W2-E (changelogs) — B32, 2026-11-12

Owner decision (coordinator, 2026-11-12): job side data lives in **Sangam side tables**; Operaton's `ACT_RU_JOB` and
`ACT_RU_EXT_TASK` are never altered. Everything here is **OFF by default**: with `sangam.engine.executor-partitioning`
false and trace links off, the engine never reads or writes these tables (X.2.1.1 strict no-op). W2-E owns the DDL
(`generate_changelogs.py`, all dialects); W2-D owns the engine code that reads and writes the rows. Please answer with
ACK or with the column changes you need; the engine side is coded against the DDL below and its H2 tests create these
tables from this exact DDL.

## 1. `EXEC_JOB_SLOT` (M-056, R39 / SANGAM.md X.2.1.2, X.2.1.3)

The X.2.1.3 `JOB(... slot, owner_partition, owner_epoch, recoveries)` columns, moved to a side table keyed by the
Operaton job id.

```sql
CREATE TABLE EXEC_JOB_SLOT (
  job_id          VARCHAR(64)  NOT NULL,   -- ACT_RU_JOB.ID_
  tenant_id       VARCHAR(64)  NOT NULL,   -- hash input; the engine default tenant for untenanted jobs (never '')
  root_id         VARCHAR(64)  NOT NULL,   -- hash input: ACT_RU_JOB.ROOT_PROC_INST_ID_
  slot            INTEGER      NOT NULL,   -- murmur3_32(tenant_id || ':' || root_id) mod S, 0 <= slot < S; never changes
  owner_partition INTEGER,                 -- set by a partitioned acquisition (fenced), NULL when unowned
  owner_epoch     BIGINT,                  -- the owning partition's epoch at acquisition, NULL when unowned
  recoveries      INTEGER      NOT NULL,   -- X.2.1.6 in-flight recovery count; the engine inserts 0
  created_at_ms   BIGINT       NOT NULL,   -- engine clock, epoch ms (as SANGAM_LEASE)
  CONSTRAINT PK_EXEC_JOB_SLOT PRIMARY KEY (job_id)
);
CREATE INDEX EXEC_JOB_SLOT_SLOT  ON EXEC_JOB_SLOT (slot);
CREATE INDEX EXEC_JOB_SLOT_OWNER ON EXEC_JOB_SLOT (owner_partition, owner_epoch);
```

Engine rules (W2-D):
- **Insert:** in the creating command's transaction, before Operaton flushes the job (a command-context close listener,
  the same mechanism as the L2 flush backstop, PATCHES.md I-8), only when partitioning is on, for every new job that
  has a root process instance. A job without a root (batch, history cleanup, timer start event) gets **no row** and
  stays unpartitioned.
- **Delete:** in the same transaction when Operaton deletes the job entity (executed, cancelled). Jobs removed by
  Operaton's bulk delete statements bypass the entity cache; their rows become orphans, which acquisition ignores (it
  always joins through `ACT_RU_JOB`). An orphan sweep belongs with the W3-F lease lifecycle.
- **No foreign key** to `ACT_RU_JOB` (Operaton's own DDL and upgrade scripts own that table; bulk deletes must not fail).
- **Acquisition** (only on a node with partitioning on): a job is acquirable by this node when it has no row
  (unpartitioned), or its slot is in a STABLE `EXEC_SLOT` row of a partition this node owns (`owner_incarnation`,
  ACTIVE, unexpired lease), or its slot's partition is not live-owned by anyone (liveness: never strand a job). On
  acquisition the row gets `owner_partition` / `owner_epoch` by a fenced update (X.2.1.5). Correctness never depends
  on this table: Operaton's REV_ compare-and-set on the job and the L2 root lock are unchanged.

## 2. `JOB_TRACE_CONTEXT` (M-062 links, R13)

```sql
CREATE TABLE JOB_TRACE_CONTEXT (
  job_id        VARCHAR(64)  NOT NULL,   -- ACT_RU_JOB.ID_ or ACT_RU_EXT_TASK.ID_ (same engine id generator: unique)
  kind          VARCHAR(16)  NOT NULL,   -- 'JOB' | 'EXTERNAL_TASK'
  traceparent   VARCHAR(55)  NOT NULL,   -- W3C trace-context, version 00: 2-32-16-2 hex with dashes
  tracestate    VARCHAR(512),            -- W3C tracestate, optional
  created_at_ms BIGINT       NOT NULL,   -- engine clock, epoch ms
  CONSTRAINT PK_JOB_TRACE_CONTEXT PRIMARY KEY (job_id)
);
```

Evidence for covering external tasks: R13 (SANGAM.md:4378) requires links for async jobs, timers **and workers**, and
SANGAM.md:557 / :661 require each worker task to carry `traceparent`. A worker fetches the task later, in another
request, so the creating context must be stored with the task, exactly as for a job. Same table, `kind` tells them
apart.

Engine rules (W2-D):
- Written only when a tracer is bound **and** `sangam.engine.trace-links` is on (default off), in the creating
  command's transaction, for each new job and external task, from the tracer's current context. Nothing is written
  when there is no current context.
- Read when a job runs (the job span is a root span **linked** to the stored context) and offered to the REST fetch
  for worker payloads (`traceparent` per task: W2-L / W2-B consume it through an engine method, not this table).
- Deleted with the job / external task entity in the same transaction; orphans as for `EXEC_JOB_SLOT`.
- Never contains ids, variables or business keys beyond the W3C header values (no sensitive data, R13).

## 3. Partition ownership tables the engine **reads** (X.2.1.3, W2-E's M-056 tables)

The partitioned acquisition reads these columns only (claim / renew / rebalance writers are W3-F). Proposed portable
types; please keep the names:

```sql
CREATE TABLE EXEC_PARTITION (
  partition_id      INTEGER     NOT NULL,
  owner_node        VARCHAR(255),
  owner_incarnation VARCHAR(36),           -- UUID text; a fresh value per process start
  owner_epoch       BIGINT      NOT NULL,   -- default 0, strictly increasing
  lease_expires_at  TIMESTAMP,              -- engine DATABASE clock (A-1); compared with the dialect's now()
  state             VARCHAR(16) NOT NULL,   -- UNOWNED | ACTIVE | DRAINING | RETIRED | SHARED
  drain_deadline    TIMESTAMP,
  map_epoch         BIGINT      NOT NULL,
  CONSTRAINT PK_EXEC_PARTITION PRIMARY KEY (partition_id)
);
CREATE TABLE EXEC_SLOT (
  slot            INTEGER     NOT NULL,
  partition_id    INTEGER     NOT NULL,
  state           VARCHAR(16) NOT NULL,     -- STABLE | MOVING
  from_partition  INTEGER,
  move_started_at TIMESTAMP,
  map_epoch       BIGINT      NOT NULL,
  CONSTRAINT PK_EXEC_SLOT PRIMARY KEY (slot)
);
CREATE INDEX EXEC_SLOT_PARTITION ON EXEC_SLOT (partition_id, state);
```

`EXEC_MAP` and `EXEC_NODE` (X.2.1.3) are not read by the W2 engine code.

## 4. Dialect mapping (for the generator)

| Canonical | PostgreSQL / H2 | Oracle | SQL Server | MySQL |
|---|---|---|---|---|
| `VARCHAR(n)` | `VARCHAR(n)` | `VARCHAR2(n CHAR)` | `NVARCHAR(n)` | `VARCHAR(n)` |
| `INTEGER` | `INTEGER` | `NUMBER(10)` | `INT` | `INT` |
| `BIGINT` | `BIGINT` | `NUMBER(19)` | `BIGINT` | `BIGINT` |
| `TIMESTAMP` | `TIMESTAMP` | `TIMESTAMP` | `DATETIME2(3)` | `DATETIME(3)` |

Use the generator's existing mapping where it differs (for example if `NVARCHAR` is not its SQL Server choice);
the engine binds strings, ints and longs and compares timestamps only with the dialect's own `now()`.

## 5. Open points for W2-E
1. Changeset ids and context: plain product changelog (not `partitioned-history`), so the tables always exist and the
   OFF default needs no DDL toggle.
2. `recoveries` default: the engine always inserts 0; a DB default of 0 is welcome but not required.
