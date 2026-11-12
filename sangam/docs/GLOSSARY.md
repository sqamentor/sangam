# Sangam glossary (ubiquitous language, M-096)

Part I principle A3 asks for a glossary of the domain terms before code, with a check of names against bounded
contexts. This file is that glossary. Every term names its **bounded context** (the module that owns it) and the
**code type** that defines it, so a reader can check the meaning in the source. The specification references point
into `SANGAM.md`.

Status (2026-11-12): written by the docs lane in Wave 2 and checked by hand against the code. The automated check that
Part I A3 describes (ArchUnit or a CI script that reads this table) does not exist yet. It belongs to the build lane
(M-096; enforcement waits for owner question Q-10).

## 1. Bounded contexts

| Context | Module(s) | Owns |
|---|---|---|
| Product core | `sangam-core` | Engine-neutral ports and the product layer: engine adapter, leases, outbox, operation ledger, idempotency, audit chain, reconciliation, tombstones, business time |
| Engine | `sangam-engine` | Operaton 2.2 as a dependency plus plugins: root lock, fenced leases, deploy guards, timers |
| Security and organisation | `sangam-security` | Tenancy, identity, authorization policy, the organisation model (units, seats, postings), delegation of powers, segregation of duties, approval binding, worker binding |
| Integration | `sangam-integration` | Inbox, broker consumers, relay and back-pressure, connectors, dead letters |
| REST | `sangam-rest`, `sangam-server` | The `/api/v1` contract, the worker API, wiring |
| Packs | `sangam-packs` | Government approval lifecycle and the packs (process, decision and form models shipped as data) |
| Configuration | `sangam-config` | Layered profiles, master data, number series, locale |
| Generic modules | `sangam-ledger`, `sangam-registry`, `sangam-billing`, `sangam-case`, `sangam-programme`, `sangam-resources`, `sangam-geo`, `sangam-field` | Ledgers, registered objects and numbering, billing documents, case files, programmes, bookings and queues, geometry, field work |
| Scale | `sangam-cell`, `sangam-control-plane`, `sangam-gateway`, `sangam-rollup` | Cells, placement, tenant routing, national roll-up |
| Flowable ports | `sangam-flowable-ports` | Flowable strengths on Operaton: event registry, content, SLA, batches, tenancy registry |

## 2. Terms

| Term | Meaning | Context | Defined in (code) | Spec |
|---|---|---|---|---|
| Tenant | The isolation unit (a department, or a client). Taken from the authenticated identity, never from the request | Security | `security/tenant/TenantContext` | VI.4 rule 6 |
| Security identity | The authenticated principal with its tenant and groups, as the product layer sees it | Product core | `core/security/SecurityIdentity` | VI.4 |
| Org unit | A node of the hierarchy (department, directorate, district, office, section) | Security | `security/org/OrgModel.OrgUnit`, `OrgUnitService` | IX.5 rule 2 |
| Seat | A post: designation plus office. Authority belongs to the seat, not to the person; seat ids are never reused | Security | `security/org/OrgModel.Seat`, `SeatService` | IX.5 rule 1 |
| Posting | A person holding a seat for an effective-dated range: substantive, additional charge, or substitute | Security | `security/org/PostingService` | IX.5 rules 1, 4 |
| Delegation of powers | The value limit a seat may approve, from a versioned DMN table effective on the action date | Security | `security/delegation/DelegationOfPowersService`, `DmnPowerEvaluator` | IX.5 rule 3 |
| Segregation of duties (SoD) | The same person or seat may not act twice in one chain; exceptions need a higher authority | Security | `security/sod/SegregationOfDutiesService` | IX.5 rule 5 |
| Approval binding | An approval is bound to the payload hash and task version it was given for | Security | `security/approval/ApprovalBindingService` | VI.4 rule 5 |
| Authorization policy | Deny-wins grants compiled into SQL predicates; every read goes through the query builder | Security | `security/policy/PolicyCompiler`, `AuthorizedQueryBuilder`, `ResourceRegistry` | VI.4 rules 1–3 |
| Worker binding | A worker principal bound to a tenant and topics | Security | `security/admin/WorkerBindingService` | R26 |
| Root (root instance) | The top process instance of a call hierarchy; children share its root id | Engine | `engine/lock/RootLock` | VI.1 |
| L2 root lock | The row lock on the root's `ROOT_SEQ` row that every state-changing command takes first | Engine | `engine/lock/RootLock` | VI.1, A-11 |
| External work | A unit of work handed to an external worker (an Operaton external task) | Product core | `core/api/EngineAdapter.ExternalWork` | VI.3 |
| Lease | The fenced right of one worker to complete one external work item: `(taskId, lockVersion, attemptId)` | Product core | `core/api/LeaseTable` | VI.3 rule 2 |
| Expiry policy | What happens when a lease expires (re-offer or hold), so an unknown outcome is not paid twice | Product core | `core/api/ExpiryPolicy` | A-10 |
| operationId | The stable id of one external effect, granted with the lease; the key for deduplication and reconciliation | Product core | `core/layer/OperationLedger` | VI.3 rule 4 |
| Operation ledger | The record of each external operation's state (intent, acknowledged, failed, unknown outcome) | Product core | `core/layer/OperationLedger`; worker side `rest/adapter/operaton/WorkLedger` | VI.3, R37 |
| Outbox | Events written in the command transaction and delivered later by the relay | Product core | `core/layer/Outbox` | VI.2 |
| Inbox | Inbound messages stored once, by key, before they are processed | Integration | `integration/inbox/InboxService` | VI.2 |
| Channel binding | The fixed tenant (or allowed tenants) of an inbound channel; a payload claim must match it | Integration | `integration/inbox/ChannelBinding` | VI.4 rule 6 |
| Tenant admission | The per-tenant decision of a broker consumer: admit, redeliver or park | Integration | `integration/relay/TenantAdmission` | ADR-D-21 IR-4/IR-6, SL-142 |
| Back-pressure | Refusing new work when the outbox budget is used up; continuations still commit | Integration | `integration/relay/BackpressureGate` | VI.2.4 |
| Dead letter / poison | A message that can never be processed, parked with a reconciliation item | Integration | `integration/inbox/PoisonInbound` | VI.2 |
| Reconciliation item | Work for an operator when the system cannot decide alone (unknown outcome, poison, late result) | Product core | `core/layer/ReconciliationQueue` | VI.3, VI.5 |
| Tombstone | The marker of a cancelled or deleted instance that still has work in flight | Product core | `core/layer/Tombstones` | VI.5 |
| Idempotency key | The client key under which the first result of a command is stored and replayed | Product core | `core/layer/IdempotencyStore` | R41 |
| Audit chain | The hash chain of audit events per root (or per tenant security chain), written in the command transaction | Product core | `core/layer/AuditChain`, `core/layer/PendingAudit` | X.2.3 |
| Audit event name | A name of the X.4.0.3 catalogue; normative per X.5.2 | Security / Packs | `security/audit/SecurityEventType`, `packs/core/CaseState.auditEvent()` | X.4.0.3, X.5.2 |
| Business clock | The source of business dates in the tenant's zone; never the JVM zone | Product core | `core/time/BusinessClock` | ADR-D-27 |
| Field encryptor | Encryption of sensitive fields under a tenant key | Product core | `core/security/FieldEncryptor` | R53 |
| Connector | A configured call to an external system, through the outbox or external work only | Integration | `integration/connector/ConnectorInvoker`, `ConnectorRegistry`, `EgressPolicy` | R27 |
| Pack | A versioned bundle of process, decision and form models plus seeds, deployed as data | Packs | `packs/spi/PackDescriptor`, `PackRegistry` | R43, ADR-D-20 |
| Case state | A state of the shared approval lifecycle (DRAFT … CLOSED and the side exits) | Packs | `packs/core/CaseState` | IX.3 |
| Profile | A layered configuration document resolved per scope (product, jurisdiction, tenant) | Configuration | `config/profile/ProfileResolver`, `ResolvedProfile` | ADR-D-20 |
| Number series | A gap-free or gapped sequence for document numbers | Generic (registry) | `registry/numbering/NumberingService` | N09 |
| Billing document | An invoice or receipt identified by its document identity, with its number and allocation plan | Generic (billing) | `billing/DocumentNumbers`, `billing/numbering/RegistryDocumentNumbers` | SL-130, SL-131 |
| Case file | A generic file of papers, notes and references (N04), with committees and meetings (N16) | Generic (case) | `casefile/file/CaseFileService` | N04, N16 |
| Capture packet | One field capture (answers, evidence hashes, location), queued encrypted on the device | Generic (field) | `field/evidence/CapturePacket`, `field/sync/LocalQueueContract` | N05, SL-129 |
| Cell | One deployment unit with its own database, holding a set of tenants | Scale | `cell/id/CellId`, `cell/id/GlobalIds` | ADR-D-21 |
| Placement | The choice of the cell a tenant lives in | Scale | `controlplane/placement/PlacementService` | ADR-D-21 |
| Roll-up | The national read model of counts and identifiers across cells | Scale | `rollup/RollupProjector` | SCALE-ARCHITECTURE §8 |

## 3. Bounded-context check: one word, several meanings

These words mean different things in different contexts. In code, documents and API names, always qualify them.

| Word | Meanings (context: type) | Rule |
|---|---|---|
| Case | Approval lifecycle state (Packs: `CaseState`); case file (Case: `CaseFileService`); CMMN case instance (Engine) | Say "case state", "case file" or "CMMN case" |
| Task | Operaton user task (Engine); external work item (Product core: `ExternalWork`) | Say "user task" or "external work"; the worker API uses "work" |
| Ledger | Generic value ledger (`sangam-ledger`: `LedgerService`); operation ledger (`OperationLedger`); work ledger (`WorkLedger`) | Say "value ledger", "operation ledger" or "work ledger" |
| Registry | Registered-object kernel (`sangam-registry`); pack registry (`PackRegistry`); event registry (`EventRegistry`, Flowable ports); resource registry (`ResourceRegistry`, Security); connector registry (`ConnectorRegistry`) | Always name which registry |
| Delegation | Delegation of powers (Security: a value limit of a seat); Operaton task delegation (Engine: handing a task to another user) | Say "delegation of powers" or "task delegation" |
| Event | Audit event (`AuditChain`); outbox event (`Outbox`); CloudEvent on a webhook; event-registry event (`EventRegistry`) | Say "audit event", "outbox event", "CloudEvent" or "registry event" |
| Document | Stored content (`DocumentStore`, `ContentService`); billing document (`DocumentNumbers`); document-type catalogue (Configuration) | Say "content", "billing document" or "document type" |
| Channel | Inbound channel binding (Integration: `ChannelBinding`); messaging channel (`sangam-channels`) | Say "inbound channel" or "messaging channel" |
| Profile | Configuration profile (`ProfileResolver`); the profiles that profilegen writes as data under `sangam-packs/src/main/resources/profiles` (for example the government finance profiles) | Say "configuration profile" or "pack profile" |
| Seat | Organisation seat (Security: `OrgModel.Seat`); the generated SDK model `Seat` is the same concept in the contract | Same meaning; the SDK type is a view of the contract |
