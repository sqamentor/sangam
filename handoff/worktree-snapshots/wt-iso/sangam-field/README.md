# sangam-field (GENERIC-FIELD-GEO workstream, module M6)

Field inspection, offline mobile capture and geo-tagged evidence (**N05**), and dispatch, trips and field-unit
tracking (**N17**), for any sector. Base package `org.sangam.field`. Domain knowledge is data only (SANGAM.md X.0e):
templates, checklists, unit kinds, skills, priorities, SLA targets, finding codes and grade bands come from
configuration or packs. Nothing in the code names a country, department or sector.

Boundary (CAPABILITY-COVERAGE.md): **IN** for capture, sync, evidence, job and unit state, assignment and proof.
**OUT** for dispatch telephony, live maps and route optimisation on a road graph. Those stay outside and are reached
through connectors.

The module depends on `sangam-core` (Dialect, Jdbc, Transactions, Hashes, CanonicalJson, AuditChain) and on
`sangam-geo` (geometry, geofence, nearest-N). Operaton and Spring MVC are optional dependencies: they are used only by
the DMN adapter and the controller.

## Entry point

`FieldModule` builds the whole object graph from `(Transactions, Dialect, Ports)`. Every port is optional. When a port
is unset, the module uses an air-gapped default (ARCHITECTURE §1.9):

- pure geofence checks;
- no DMN, in which case DMN-configured templates fail loudly;
- no events;
- a local evidence directory;
- the core audit chain.

## Requirements covered, file by file

| File | Covers |
|---|---|
| `FieldSchema.java` | DDL for all 17 `FLD_*` tables in the core dialect tokens (A-15). Every PK starts with `tenant_id`. Booleans are SMALLINT. Coordinates are stored as BIGINT degrees×10⁷, so all four Tier-1 dialects store them exactly |
| `FieldModule.java` | Wiring and port defaults (§1.9 air-gapped) |
| `FieldConfig.java` | Offer timeout, arrival radius, road factor, speed per unit kind, SLA per priority, candidate radius, clock skew, pull page size, overdue grace. All of it is data |
| `FieldAudit.java` | R42 / X.2.3: field and dispatch events on the core hash-chained `AuditChain`. The root is the assignment or the job, so one visit or one response reads as one timeline |
| `FieldException.java`, `FieldJson.java`, `support/FieldSql.java` | Stable error codes and their HTTP status (RFC 9457). JSON mapping. A per-tenant gap-free sequence (row-locked UPDATE, portable). The device change feed |
| `template/FieldTemplate.java` | N05 `Checklist` / `FormVersion` (C03, forms flagged offline-capable). Sections and items: BOOLEAN, CHOICE, MULTI_CHOICE, NUMBER, TEXT, DATE, PHOTO, SIGNATURE, GEO_POINT. Each item has i18n labels, required, options with scores, weight, critical, minPhotos, visibleIf, range, expected answer and finding code. The template also defines scoring (WEIGHTED_SUM, DMN or NONE, grade bands, critical-fail grade), a geofence rule and evidence rules |
| `template/TemplateRegistry.java` | Versioned templates (`FLD_TEMPLATE`), published from the admin API, a pack resource or a directory. Versions are immutable, so packets captured offline against version n stay verifiable. Each version has a content hash |
| `template/ChecklistValidator.java` | Visibility, required items, types, ranges, options and photo counts |
| `scoring/ScoringService.java` | Weighted-sum score from 0 to 100, critical-fail override and grade bands, or DMN through `DecisionPort`. The decision receives the answers, the weighted score, the critical flag and the non-compliant items. Findings feed penalty DMN and CAPA (N05 `Finding` / `NonCompliance`, UP-18) |
| `ports/FieldPorts.java` | `GeoPort` with `PureGeoPort`, `DecisionPort`, `FieldEvents` (outbox hook, VI.2) |
| `adapters/GeoServicePort.java` | `GeoPort` over sangam-geo `GeoService`. FEATURE fences (ward, village, parcel) resolve against stored layers (N13 → N05) |
| `adapters/OperatonDecisionAdapter.java` | `DecisionPort` over Operaton `DecisionService.evaluateDecisionByKey`. Decisions can be tenant-scoped, with a fallback to the shared pack decision |
| `schedule/Assignment.java`, `AssignmentService.java` | N05 `Assignment`: inspector seat or principal, target, template version, due date, visit window, fence, selection id, task id and business key. Status machine PLANNED → ASSIGNED → IN_PROGRESS → SUBMITTED → ACCEPTED/REJECTED, plus CANCELLED and OVERDUE. Optimistic `version`. Overdue sweep. Reassignment revokes the assignment on the old inspector's devices |
| `schedule/FenceSpec.java` | A visit fence built from the template rule and the target: either a circle around the target, or a stored feature or fence reference |
| `schedule/RandomSelector.java` | Risk-based random selection with an audited seed (N05; open question V7). Weighted sampling without replacement (Efraimidis–Spirakis) on a SplittableRandom seeded with SHA-256(seed). The population hash, weights and sample are stored, the draw is audited, and `verify` re-runs it. DMN risk weights are optional |
| `schedule/RotationRule.java` | Inspector rotation: excludes the last N inspectors of the target, picks the least loaded, and accepts an optional DMN veto (`allowed`) |
| `schedule/RouteOrdering.java` | Route list: nearest-neighbour ordering followed by 2-opt. Distances come from GeoMath |
| `schedule/ScheduleService.java` | Recurring schedules: cadence, target list, sample size, risk DMN, assignee pool and rotation. Each run is claimed by advancing `next_run_at` with a compare-and-set, so it is exactly-once |
| `sync/DeviceRegistry.java` | Device registration bound to a principal and optionally to a dispatch unit. Registers an EC P-256 (or Ed25519) public key and hands out the queue policy and a queue key id. ACTIVE, REVOKED and LOST states; a revoked device is refused on every call. Keeps the chain head |
| `sync/QueuePolicy.java`, `sync/LocalQueueContract.java`, `sync/LocalQueueCipher.java` | The encrypted local-queue contract: AES-256-GCM with a 96-bit IV. AAD = tenant\|device\|operationId. Envelope v1. Stable operationId. Upload in sequence order and delete only after NEW or REPLAY. Crypto-erase on revoke. Includes a JVM reference implementation for conformance |
| `sync/SyncService.java` | Idempotent upload (R41) keyed by (tenant, operationId) and the packet hash. Outcomes: NEW, REPLAY (stored result), CONFLICT (same id, different packet), REJECTED (hash mismatch or business rule, rolled back to a savepoint so the answer is stable). Each packet is processed in its own transaction. Packet types: VISIT_SUBMIT, EVIDENCE_META, ASSIGNMENT_ACK, DISPATCH_STATUS, POSITION_PING. Pull follows a change cursor and returns assignments, revocations, offline templates, the unit's jobs and offers, and the device status and policy. Inbox semantics (R38) |
| `sync/ConflictResolver.java` | Offline conflict rules. Administrative fields are server-owned and capture fields are client-owned (AUTO_MERGED). A cancelled or reassigned assignment gives REJECTED_STALE; a second submission gives DUPLICATE_SUBMISSION. Both are held in `FLD_CONFLICT` for review |
| `visit/VisitService.java` | The submission pipeline, shared by sync and online capture: conflict check → declared evidence → checklist → time, accuracy, mock-location and geofence checks → score → findings → SUBMITTED. A FLAGGED visit is recorded and marked for review. The review step enforces maker-checker (the inspector cannot review their own visit). Conflict resolution re-applies the held submission |
| `evidence/CapturePacket.java` | N05 `CapturePacket`: answers, media refs, GPS fix with accuracy, provider and mock flag, device clock, server receipt, device id, app version, SHA-256, a per-device prevHash chain and an optional signature |
| `evidence/PacketHasher.java` | Canonical packet hash: numbers as decimal strings, ISO instants, nulls dropped, then RFC 8785-style core `CanonicalJson`. Verifies SHA256withECDSA signatures |
| `evidence/ExifReader.java` | Minimal, bounded pure-Java JPEG EXIF reader: Make, Model, Software, DateTimeOriginal, OffsetTimeOriginal, and GPS position, date and time |
| `evidence/IntegrityChecks.java`, `IntegrityReport.java` | Anti-spoofing checks: device clock ahead of receipt, visit window, accuracy limit, mock location, geofence (INSIDE / OUTSIDE / INDETERMINATE, accuracy-aware), hash match, duplicate photo, EXIF time and GPS consistency, photo age. Results are VERIFIED, FLAGGED or REJECTED |
| `evidence/EvidenceService.java`, `EvidenceBlobStore.java` | Content-addressed evidence storage (file-system default, atomic rename). Declared media is held as PENDING_UPLOAD until the bytes arrive and match the declared hash. Re-verification at rest. Per-device chain verification reports HASH, LINK and GAP breaks |
| `dispatch/DispatchModel.java` | N17 `Unit`, `UnitStatus` (AVAILABLE, ASSIGNED, EN_ROUTE, ON_SCENE, RETURNING, OFF_DUTY), `Job`, `Offer`, `Trip` / `Leg`, `PositionFix`, `Eta`, `Candidate`, with transition tables |
| `dispatch/DispatchService.java` | Candidates by distance and skill (N13 nearest-N, R82). Offer, then accept, decline or timeout, then re-dispatch. Direct assignment. Job and unit states kept in step. Pings (idempotent per unit and time) set ON_SCENE automatically inside the arrival radius. ETA from distance × road factor ÷ speed per kind. Response-time SLA. Closure with proof of service (an N05 packet). Cancel and re-dispatch. Trips with legs, odometer and track distance from pings, approved maker-checker (GX08 logbook) |
| `rest/FieldRestPort.java`, `FieldRestService.java`, `FieldCaller.java` | Framework-neutral REST port at `/api/v1/field`, including the existing sangam-ui contract (`GET /visits`, `POST /visits/{id}/record` with Idempotency-Key, R41) |
| `web/FieldController.java`, `web/CallerResolver.java` | Spring MVC adapter. Errors are RFC 9457 problem+json |

## Third-party dependencies added

None new. The module uses `jackson-databind` and `jackson-datatype-jsr310` (Apache-2.0, the parent's Jackson BOM line,
already used by sangam-rest), `operaton-engine` (Apache-2.0, optional), and `spring-webmvc` (Apache-2.0, optional,
from the parent Spring Boot BOM). `jakarta.servlet-api` has provided scope. EXIF parsing, geohash and geometry use
no library.

## Integration notes

1. **Liquibase (WS-ENGINE).** Turn `FieldSchema.ddl(dialect)` into changesets for the four dialects. The tables are
   FLD_TEMPLATE, FLD_ASSIGNMENT, FLD_SCHEDULE, FLD_SELECTION, FLD_DEVICE, FLD_SYNC_OP, FLD_SEQ, FLD_CHANGE,
   FLD_CONFLICT, FLD_PACKET, FLD_EVIDENCE, FLD_FINDING, FLD_UNIT, FLD_JOB, FLD_OFFER, FLD_TRIP and FLD_POSITION.
   Sync relies on JDBC savepoints, which all four dialects support.
2. **sangam-server wiring.** Build `new FieldModule(Transactions.of(dataSource or engine), dialect, new FieldModule.Ports(...))`
   and bind its ports:
   - `geo`: `new GeoServicePort(geoService)` from sangam-geo;
   - `decisions`: `new OperatonDecisionAdapter(processEngine, tenantScoped)`;
   - `events`: the integration outbox (VI.2), whose event types are `field.assignment.*`, `field.visit.submitted`,
     `dispatch.job.*` and `dispatch.offer.created`;
   - `blobs`: an object store if one is configured;
   - `audit`: the default is `FieldAudit.chain(dialect)`.

   Register `FieldController(module.rest, callerResolver)` as a bean.
3. **CallerResolver.** Map sangam-rest's `RequestContext` (OIDC tenant claim, principal, groups, `X-Sangam-Seat`) to
   `FieldCaller`. Apply the VI.4 endpoint policy in front of the controller. Suggested permissions:
   - `field.capture`: visits, sync, devices, evidence upload;
   - `field.admin`: templates, schedules, selections;
   - `field.review`: review, conflicts;
   - `dispatch.operate`: jobs, units, offers, trips.
4. **Schedulers.** Run these periodically per tenant through the engine job executor or a Spring scheduler:
   - `ScheduleService.runDue`;
   - `AssignmentService.sweepOverdue`;
   - `DispatchService.expireOffers`.
5. **BPMN.** An inspection user task can create an assignment with `taskId` and `businessKey`. When
   `field.visit.submitted` fires, a message correlation completes the task. N17 jobs can be started from a process,
   and `dispatch.job.closed` resumes it.
6. **UI.** The `FieldVisits` screen already calls `GET /field/visits` and `POST /field/visits/{id}/record`. Both are
   implemented, and the response adds `version` (send it back as `version` for conflict detection) and item
   `type` / `required`.
7. **N12 AVL feeds.** Post pings to `POST /units/{id}/pings`, or call `DispatchService.ping`, from an inbound connector.

## Known gaps

- ETA uses straight-line distance × road factor. Road-network routing is out of scope and needs a connector.
- EXIF is read only from JPEG. HEIC and PNG evidence gets hash, duplicate and fix checks but no EXIF checks.
- The PWA client in sangam-ui does not yet implement the encrypted queue contract (`LocalQueueContract`). It uses the
  online `record` call with Idempotency-Key.
- No tests were run in this phase. The phase rule is code first, tests last.
