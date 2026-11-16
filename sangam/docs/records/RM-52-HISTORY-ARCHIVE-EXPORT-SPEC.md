<!-- SPDX-License-Identifier: Apache-2.0 -->
# Engine history archive export: specification (RM-52)

Status: **implemented on H2 (lane R7-rm, 2026-11-16) under decision 386 (1) to (3); PostgreSQL, Oracle and SQL Server runs open.** Section 4 records the implementation and its deviations. Written 2026-11-16 by lane R7-rm from the code at c02359c9. RM-52 (REMEDIATION-PLAN, X3 R-13,
C-35 advisory) asks for an `ACT_HI_*` archive-export spec so that an engine exit, or a purge under retention, never loses evidence
held only in Operaton's history tables. Section 1 records what the code does today, with the source of each fact. Section 2 is the
contract an implementation must meet. Section 3 lists the questions that need an answer before anyone codes it.

## 1. What holds process history today

| Store | What it holds | Source |
|---|---|---|
| Operaton `ACT_HI_*` | The engine's own history, written by its default DB handler at the configured level (`full` in the server, `sangam-server/src/main/resources/application.yaml:65`; per process key through `historyLevelByProcessKey`, and per model when M-057 is on, `SangamEngineSettings.java:144,215`) | Operaton 2.2.0 |
| Operaton `ACT_HI_*`, outbox-only definitions | For a key in `outboxOnlyHistoryKeys` only the audit level: process and case instances, activity and case-activity instances, tasks, variable instances. Details, form properties, incidents, job and external-task logs, identity links, decisions and operation logs are not written | `sangam-engine/.../history/OutboxOnlyDbHistoryEventHandler.java` |
| Audit chain | Class-B audited event types (`auditedHistoryEvents`) always, even when the history level would drop them; such an "audit-only" event gets no `ACT_HI_*` row | `MandatoryAuditHistoryLevel.java`, `MandatoryAuditHistoryEventHandler.java` |
| History read model `HRM_*` | Every history event, through the outbox, applied once per root (`HRM_CHECKPOINT`); full detail, including for outbox-only definitions | `sangam-flowable-ports/.../history/HistoryProjector.java` |
| `PROCESS_TRACE` | One row per process instance, removed in the same transaction as the instance's history | `sangam-engine/.../trace/ProcessTraceHistoryRemoval.java` (PATCHES.md B-36, I-18) |

How history leaves the engine today:

- **Removal-time cleanup:** Operaton deletes each history table by its own `REMOVAL_TIME_` and batch limit, so the rows of one instance
  can go in different cleanup runs (`ProcessTraceHistoryRemoval.java`, class comment).
- **Hierarchy retention:** a process tree is purged as one unit once every member has ended, the root's retention has passed and no legal
  hold applies; children go first, through the engine's history service (`sangam-ops/.../retention/HierarchyRetention.java`).
- **Partitioning (optional, IR-8):** nine `ACT_HI_*` tables can be range-partitioned by `REMOVAL_TIME_` on PostgreSQL, Oracle and SQL
  Server; `ACT_HI_PROCINST` is not (`sangam-persistence/.../sangam-history-partitioning.xml`). Dropping a partition removes history
  outside the engine's own delete path.
- **Erasure (DPDP):** `ErasureService` erases variables and comments and keeps ids, timestamps and the activity trail.

The audit exports (`/api/v1/audit/exports`, evidence packs, `AuditExportController.java`) export the audit chain, not `ACT_HI_*`. So
nothing exports a purgeable tree's engine history before it is deleted, and nothing writes it in a form that another engine or an
archive can read.

## 2. The export contract

**Unit.** One process tree (the hierarchy-retention unit: the root and every called or sub-process instance), within one tenant.
Case instances use their own tree. A tree is exported only when every member has ended.

**When.**
1. Before a purge: the hierarchy-retention purge of a tree, the removal-time cleanup and a partition drop must each find a verified
   archive of the tree first, and must otherwise refuse the purge for that tree. Refusing fails closed: history that has no archive is
   kept, never lost.
2. On demand: for an engine exit, an operator exports every ended tree of a tenant, or of all tenants.

**Content.** For each member instance, the rows of every engine history table that refers to it, by `PROC_INST_ID_`,
`ROOT_PROC_INST_ID_` or `CASE_INST_ID_`:
`ACT_HI_PROCINST`, `ACT_HI_ACTINST`, `ACT_HI_TASKINST`, `ACT_HI_VARINST`, `ACT_HI_DETAIL`, `ACT_HI_IDENTITYLINK`,
`ACT_HI_COMMENT`, `ACT_HI_ATTACHMENT`, `ACT_HI_OP_LOG`, `ACT_HI_INCIDENT`, `ACT_HI_JOB_LOG`, `ACT_HI_EXT_TASK_LOG`,
`ACT_HI_DECINST`, `ACT_HI_DEC_IN`, `ACT_HI_DEC_OUT`, `ACT_HI_CASEINST`, `ACT_HI_CASEACTINST`. Add the `ACT_GE_BYTEARRAY` rows they
reference (serialized and byte variable values, attachments, decision inputs and outputs), the deployed model of each definition used
(resource bytes and the definition key, version and version tag), the tree's `HRM_*` rows, and its `PROCESS_TRACE` rows.

**Format (engine-neutral).** A ZIP holding:
- `manifest.json`: tenant, root id, export time (UTC), Sangam and Operaton versions, the history level and outbox-only flag of each
  definition, and per file its row count and SHA-256;
- one JSON Lines file per table, named after the table, one object per row, the column names as Operaton writes them; timestamps in
  ISO-8601 UTC, numbers as exact decimal text (no binary floating point), byte arrays as base64 with their own SHA-256;
- `models/`: the deployed model resources, byte for byte.

The export must be deterministic (rows ordered by table, then primary key), so that two exports of the same tree are identical byte
for byte.

**Integrity and audit.** The manifest digest is written to the audit chain as an event of the tree's business key (a new audited event
type), and the ZIP is signed with an archive key of its own (decision 37: one key per purpose and environment). A purge checks that
the archive exists in the archive store, that its digest matches the audit event, and that it verifies against that key, before
anything is deleted.

**Tenancy and erasure.** An archive holds one tenant's rows only. An erasure that runs after the export also applies to the archive:
the erased values are removed from the archive's `ACT_HI_VARINST`, `ACT_HI_DETAIL` and `ACT_HI_COMMENT` lines, and a new manifest
records the erasure. The audit chain keeps both digests.

**Proof an implementation must carry.**
- An H2 test that exports a tree with a call activity, a user task, a decision and a byte variable, then reads the archive back and
  finds every row the engine held.
- A test that each of the three purge paths refuses a tree with no archive, or with an archive that fails verification.
- The same export on PostgreSQL, Oracle and SQL Server in the test phase.
- A test that a second export of the same tree is identical.

## 3. Questions before it is coded

1. **Archive store.** Where archives live: the existing document store under a retention class, or a separate store. Recommended: the
   document store, with a records-retention class longer than the engine retention.
2. **Purge gating.** Whether every purge needs an archive, or only trees of definitions marked as records. Recommended: every tree. It
   costs storage, and losing evidence cannot be undone.
3. **Partition drops.** Partition dropping (IR-8) removes rows by time, not by tree, so it cannot check per tree. Recommended: drop only
   partitions whose trees all have verified archives, and otherwise keep the partition and report it.

Owner: the engine history area (sangam-engine, sangam-ops retention). Register row: RM-52.

## 4. Implementation (lane R7-rm, decision 386)

Decision 386 answered section 3 as recommended: (1) the document store, with a records-retention class longer than the engine
retention; (2) every tree needs a verified archive before any purge; (3) a partition is dropped only when every tree in it has one.

| Part | Code | Proof |
|---|---|---|
| Gate contract | `sangam-core` `core/history/HistoryArchiveGate` (`refusal(connection, tenant, rootId)`, `null` = archived and verified; `PurgeRefusedException`); the default refuses every tree (`HISTORY_ARCHIVE_NOT_CONFIGURED`) | - |
| Engine paths | `sangam-engine` `trace/HistoryArchiveGuard` and `trace/ProcessTraceHistoryRemoval`: every process and decision history delete (by id, deployment cascade, removal-time cleanup) asks the gate for each root tree first; one refusal refuses the whole request (PATCHES.md B-48, I-28) | `HistoryArchiveGuardH2Test` (5) |
| Export and verify | `sangam-ops` `archive/HistoryArchiveExporter` (every `ACT_HI_*` table of section 2, the `ACT_GE_BYTEARRAY` rows they reference, `HRM_*`, `PROCESS_TRACE`, models, manifest; deterministic) and `archive/HistoryArchives` (Ed25519 signature, document store, `HISTORY_ARCHIVED` audit event; refusals `HISTORY_ARCHIVE_MISSING`, `_AUDIT_INVALID`, `_NOT_STORED`, `_DIGEST_MISMATCH`, `_INVALID`, `_SIGNATURE_INVALID`, `_STALE`, `_UNVERIFIABLE`, `_RETENTION_TOO_SHORT`) | `HistoryArchivePurgeTest` (3) |
| Retention purge | `sangam-ops` `retention/HierarchyRetention.execute(plan, archiver, purger, ...)`: exports each tree first; a tree without a verified archive is never handed to the purger and gets a `HISTORY_PURGE_REFUSED` audit event | `OpsTest`, `HistoryArchivePurgeTest` |
| Partition drop | `sangam-ops` `archive/PartitionDropGuard.check(from, to)`: allowed only when every tree in the range is archived, otherwise the refused roots are reported | `HistoryArchivePurgeTest` |
| Server wiring | `HistoryArchiveWiring` (`sangam.history.archive.*`: store root, retention class and days, `secret://` signing refs only; the retention class must be longer than the longest engine retention); off = every purge refused | `HistoryArchiveWiringTest` (3) |

Before-fix proofs (each check removed, the tests above fail): the engine gate (4 of 5 `HistoryArchiveGuardH2Test` cases), the
retention purge (`OpsTest`), the engine path in the server (`HistoryArchivePurgeTest`), and, run alone, the partition guard
(`HistoryArchivePurgeTest` line 221: the drop was allowed while a tree had no archive).

Deviations from section 2:
- The export time is on the `HISTORY_ARCHIVED` audit event, not in the manifest, so two exports of a tree are identical byte for byte.
- The archive event is on the root's audit chain (tenant, root id), not the business key's.
- An archive is stale when a table's row count differs from the manifest; a changed value with the same count is not detected.

Open: an erasure after the export does not yet re-manifest the archive; the store does not yet enforce the retention class's
lifecycle; the PostgreSQL, Oracle and SQL Server runs (test phase); `sangam-assembly` and the Quarkus host have no archive wiring, so
they refuse every purge (fail closed).