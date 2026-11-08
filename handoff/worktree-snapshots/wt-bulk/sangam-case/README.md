# sangam-case (GENERIC-CASE-PROGRAMME)

A generic case file on top of CMMN (N04), meetings and committees (N16), and references with a deadline (N16).
Base package `org.sangam.casefile`. Every piece of domain knowledge is configuration data (SANGAM.md X.0e). That
includes case types, party roles, stages, milestones, number series, committee kinds and reference kinds (questions,
assurances, dignitary references and any other "reference with a deadline").

Build: `cd sangam && mvnw.cmd -q -B -DskipTests -pl sangam-case,sangam-programme -am install` (compiles).
Phase rule: the code is complete. Tests come in the testing phase; none were written or run.

## Wiring

```java
CaseModule.Settings s = new CaseModule.Settings(dialect, EngineTx.transactions(engine)); // Q1: rows commit with engine state
s.outbox = outbox;                     // CF events -> destination "case-events"
s.links = instanceLinkService;         // sangam-flowable-ports FC-31/32
s.content = contentService;            // sangam-flowable-ports FC-40
s.calendars = name -> calendars.get(name);          // FC-23 WorkCalendar by name
s.notifier = ...; s.escalationSeats = ...; s.venues = ...; s.history = ...; s.processes = ...; s.partyDirectory = ...;
CaseModule module = new CaseModule(s);
cfg.getProcessEnginePlugins().add(new CaseFileEnginePlugin(module, true)); // after WS-ENGINE and WS-PORTS plugins
CaseRestAdapter rest = new CaseRestAdapter(module, securityAuthorization); // CaseFileRestPort, MeetingRestPort, ReferenceRestPort
// scheduler (or a timer-started process with DeadlineDelegate op=SWEEP): module.deadlines().sweep(200)
```

## File-by-file coverage

Paths are under `src/main/java/org/sangam/casefile/`.

| File | Covers |
|---|---|
| `CaseFileSchema.java` | DDL in dialect tokens (A-15) for 25 `CF_*` tables and their indexes: config, number series, case, party, party key, case party, record, folder, document, note, event, hearing, case link, deadline, committee, member, meeting, agenda, attendance, notice, minutes, decision, action item, reference, reference move. Every table is tenant-first. This is the source for WS-ENGINE's Liquibase changelogs |
| `CaseFileException.java` | Stable codes with an HTTP status (RFC 9457 mapping; R28: no personal data or SQL in messages) |
| `CaseModule.java` | Wiring and the holder used by the delegates and the CMMN listener |
| `support/Db.java` | JDBC/JSON helpers, database clock (A-1), ISO period/duration parsing, LIKE escaping |
| `config/CaseConfig.java` | **N04** case type: fields with type checks, roles (min/max, sensitive), conflicting roles, stages with allowed moves and required roles, terminal stage, milestones, deadline rules (on open or on a stage) with an escalation ladder, duplicate-check roles, default folders, CMMN key, number series. **N16** reference kind: reply period, calendar, warning, ladder, fields, reply approval, duplicate window, source categories, follow-up kind, extensions, optional case type and process. **N16** committee kind: quorum rule (ALL / MAJORITY / n / p%), chair rule, notice hours, member roles, minutes approval by chair, action-item SLA, ladder and process, meeting numbering |
| `config/CaseConfigRegistry.java` | Versioned, hash-pinned configuration (R43). Tenant over shared fallback (FC-33 style). Pinned versions per case or reference; retire |
| `common/CaseEvents.java` | The module journal (`CF_EVENT`) plus an outbox event in the same transaction (VI.2) on `case-events`. Merge support |
| `common/NumberService.java` | N09-style gap-free numbers per series and period, under a row lock |
| `deadline/DeadlineService.java` | **C12/R79** deadlines on business calendars (FC-23): RUNNING → WARNED → BREACHED with an escalation ladder (OWNER / SUPERIOR via the seat directory, AJ-04 / `SEAT:x`), met (late flag), cancel, extend with reason and a limit, reassign. The sweeper uses skip-locked claims and `next_check_at`, so it is multi-node safe. Notifier and in-process listeners |
| `file/CaseFileService.java` | **N04** open (typed, numbered, validated fields, default folders, deadlines, CMMN/BPMN binding), get / by number / by CMMN instance / by business key, **cross-case search under VI.4/R19** (compiled predicate, capped count A-16), attribute update (sensitive values masked in the journal), header change, stage machine, milestones, assign (deadlines follow), close / reopen, optimistic `row_version` |
| `file/PartyService.java` | **N04 party model**: party registry (persons and organisations, external N14 ref via `PartyDirectory`), hashed identifier keys, name normalisation, candidates / register-or-match, roles on cases (role limits, conflicting roles), effective-dated removal. **Conflict check** across cases and **duplicate-case check** |
| `file/CaseContentsService.java` | **Linked records** (any record type; engine records also as FC-32 entity links), **document folder tree** over the FC-40 content store (attach, upload, new version, move, remove, open), exhibits, **notes** (ALL / INTERNAL / PRIVATE, append-only edit by supersede) |
| `file/HearingService.java` | **N04 Hearing/Event** set by an outside forum (GP-51): schedule, outcome, adjourn, next date, cancel, **cause list** (feed to N01) |
| `file/CaseLinkService.java` | **Related cases** (any data link type), **merge** (parties, records, folders, documents, notes, hearings and deadlines move; the merged case keeps its number; `resolve`), **split** (new case with selected parties, records and documents) (GP-54) |
| `file/CaseTimelineService.java` | **Timeline**: the journal (including merged cases) plus process and CMMN history from the FC-25 read model (`ProcessHistorySource`), newest first |
| `meeting/CommitteeService.java` | **N16 committees**: constitution with an order reference, members by person or seat (IX.5), voting and non-voting, one chair, effective-dated membership, reconstitution, dissolution, quorum |
| `meeting/MeetingService.java` | **N16 meetings**: schedule with numbering and venue booking (N01 port), reschedule, cancel (corrigendum and cancellation notices). **Agenda** linked to files, cases and applications, with reorder, withdraw and carry forward. **Notices** with a notice period, an urgency reason, a content hash and acknowledgement. **Attendance**, with proxies only when the terms allow. **Quorum** (with the chair rule), start / adjourn (adjourned sitting plus carry-forward) / end. **Decisions** with votes and dissent, journalled on the linked case. **Minutes** DRAFT → CIRCULATED_FOR_COMMENT → APPROVED (approver ≠ drafter, chair rule, content-hash binding, eSign ref) → ISSUED |
| `meeting/ActionItemService.java` | **N16 action items**: owner seat or person, SLA with the kind's ladder, optional process per item (GP-09), start, **ATR** submit and verify (verifier ≠ author; return with reason), drop, reassign. Pendency per seat, ATR report, ATR placed on the next agenda |
| `reference/ReferenceService.java` | **N16 references with a deadline**: register (kind fields, source category, duplicate window, numbering, reply clock on a calendar or a fixed date from the source, optional case file and process), assign / transfer with movement history, start, interim reply, **reply maker-checker** (draft → approve with hash → reply), late flag, extension (limit from kind), follow-up (e.g. an assurance from an answer), close. Queue search under VI.4 with a capped count; pendency (open / overdue / due soon / replied late) |
| `cmmn/CaseFileCmmnListener.java` | **N04 on top of CMMN**: case created → open or bind the file (`caseFileId`, `caseFileType` or the type's `cmmnCaseKey`); milestone occurred → timeline; case completed / terminated → timeline and optional close |
| `cmmn/CaseFileEnginePlugin.java` | ADR-D-16 registration (post CMMN transform listener) |
| `delegate/CaseFileDelegate.java` | BPMN `op`: OPEN, BIND, STAGE, MILESTONE, UPDATE, ADD_PARTY (with conflict count), LINK_RECORD, NOTE, ASSIGN, DUPLICATES, CLOSE |
| `delegate/MeetingDelegate.java` | BPMN `op`: SCHEDULE, AGENDA, SEND_NOTICES, CHECK_QUORUM, START, END, ADJOURN, ISSUE_MINUTES, CREATE_ACTIONS, CARRY_FORWARD, PLACE_ATR, PENDING_ACTIONS |
| `delegate/ReferenceDelegate.java` | BPMN `op`: REGISTER, ASSIGN, TRANSFER, START, INTERIM, DRAFT, APPROVE, REPLY, EXTEND, FOLLOW_UP, STATUS, CLOSE |
| `delegate/DeadlineDelegate.java` | BPMN `op`: SWEEP, START, MEET, STATE, ATR, VERIFY_ATR |
| `delegate/DelegateSupport.java` | Engine connection (Q1), tenant, actor, typed variables. `CaseFileException` becomes the BPMN error `CASEFILE_<CODE>` |
| `ports/CaseFilePorts.java` | Outbound ports with air-gapped defaults (§1.9): Notifier, Calendars, VenueBooking (N01), PartyDirectory (N14), ProcessHistorySource (FC-25), ProcessStarter (GP-09), EscalationSeats (AJ-04) |
| `rest/CaseApi.java`, `rest/CaseFileRestPort.java`, `rest/MeetingRestPort.java`, `rest/ReferenceRestPort.java` | REST ports with request/response records and route comments (§4 conventions) |
| `rest/CaseRestAdapter.java` | One transaction per call. VI.4 object check in the transaction (K3). Lists use the compiled predicate (R19, no post-filtering). **R19 party masks** unless READ_SENSITIVE. Confidential documents need READ_SENSITIVE. Owners may act on their own action items |

## REST routes (WS-REST mounts them under `/api/v1`)

| Area | Routes |
|---|---|
| Config | `PUT/GET /case-config/{CASE_TYPE\|REFERENCE_KIND\|COMMITTEE_KIND}` |
| Case files | `POST/GET /case-files`, `GET /case-files/{id}`, `GET /case-files/by-number/{n}`, `PATCH /case-files/{id}`, `POST /case-files/{id}/stage\|assign\|close\|reopen\|milestones` |
| Parties | `POST /parties`, `GET /parties/candidates`, `GET /parties/{id}/cases`, `POST /case-files/{id}/parties`, `DELETE /case-files/{id}/parties/{cpId}`, `GET /case-files/{id}/conflicts`, `GET /case-files/{id}/duplicates` |
| Contents | `POST/DELETE /case-files/{id}/records[/{rid}]`, `POST /case-files/{id}/folders`, `POST /case-files/{id}/documents` (JSON attach or multipart upload), `GET /case-files/{id}/documents/{d}/content`, `DELETE /case-files/{id}/documents/{d}`, `GET/POST /case-files/{id}/notes`, `PUT /case-files/{id}/notes/{n}`, `GET /case-files/{id}/timeline` |
| Related | `POST/DELETE /case-files/{id}/links[/{l}]`, `POST /case-files/{id}/merge`, `POST /case-files/{id}/split` |
| Hearings, deadlines | `POST /case-files/{id}/hearings`, `POST /hearings/{id}/outcome`, `GET /hearings/cause-list`, `POST /deadlines/{id}/extend`, `GET /deadlines/due` |
| Committees | `POST/GET /committees`, `GET /committees/{id}`, `POST/DELETE /committees/{id}/members[/{m}]`, `POST /committees/{id}/reconstitute\|dissolve`, `GET /committees/{id}/pendency` |
| Meetings | `POST/GET /meetings`, `GET /meetings/{id}`, `POST /meetings/{id}/reschedule\|cancel\|start\|adjourn\|end`, agenda (`POST`, `DELETE /{item}`, `PUT /order`, `POST /carry-forward`), `POST /meetings/{id}/notices`, `POST /notices/{id}/ack`, `PUT /meetings/{id}/attendance`, `GET /meetings/{id}/quorum`, `POST /meetings/{id}/decisions`, minutes (`POST`, `/circulate`, `/comments`, `/approve`, `/issue`), `GET /meetings/{id}/atr`, `POST /meetings/{id}/atr/place` |
| Action items | `POST/GET /action-items`, `POST /action-items/{id}/start\|atr\|verify\|drop\|reassign` |
| References | `POST/GET /references`, `GET /references/{id}`, `POST /references/{id}/assign\|transfer\|start\|interim-reply\|draft-reply\|approve-reply\|reply\|extend\|follow-ups\|close`, `GET /references/pendency` |

## Integration notes

1. **Schema (WS-ENGINE / sangam-persistence).** Copy `CaseFileSchema.ddl(dialect)` into the per-dialect Liquibase changelogs. `CaseFileSchema.create` is for development only.
2. **WS-SECURITY `ResourceRegistry`.** Register three resource types:
   - `CASE_FILE`: table `CF_CASE`, alias `cf`, id `case_id`, tenant `tenant_id`, attributes `case_type`, `org_unit`, `owner_seat`, `confidentiality`, `status`;
   - `CASE_REFERENCE`: table `CF_REFERENCE`, alias `r`, id `reference_id`, attributes `ref_kind`, `org_unit`, `assigned_seat`, `source_category`;
   - `COMMITTEE`: table `CF_COMMITTEE`, id `committee_id`.

   The id `*` stands for module-wide grants (create, configuration, registers). Permissions used: READ, READ_SENSITIVE, READ_HISTORY, MODIFY, START, ASSIGN, APPROVE, CANCEL, ADMIN.
3. **Ports to bind (integrator, sangam-server).**
   - `Notifier` → WS-INTEGRATION outbox and connectors (class (b), exactly once).
   - `Calendars` → WS-ENGINE `WorkCalendar` registry.
   - `EscalationSeats` → `SecurityPorts.SeatDirectory.escalationSeat`.
   - `ProcessHistorySource` → `HistoryQueryService.activityInstances` with the VI.4 predicate.
   - `ProcessStarter` → engine `RuntimeService` in the same command.
   - `VenueBooking` → the resource module (N01) when it exists.
   - `PartyDirectory` → the N14 registry when it exists.
4. **Outbox relay (WS-INTEGRATION).** The destination `case-events` carries every module event (`casefile.<EVENT_TYPE>`, envelope with scope and payload) for notifications, dashboards and the audit chain.
5. **Deadline sweep.** Schedule `DeadlineService.sweep(batch)` every minute, or deploy a timer-started process with `DeadlineDelegate` `op=SWEEP`. It is safe on several nodes.
6. **CMMN.** Register `CaseFileEnginePlugin` after `SangamProcessEnginePlugin` and `SangamPortsPlugin`. A CMMN model binds to a case type through the type's `cmmnCaseKey`, or through the variables `caseFileId` / `caseFileType`.
7. **Packs (WS-PACKS)** can ship case types, reference kinds and committee kinds as JSON (`CaseConfigRegistry.publishJson`) together with BPMN that uses the delegates. Candidate packs are review meetings, committee meetings, legislative questions, assurances, dignitary references, board meetings and the N04 refactors listed in the capability study. The existing `CommitteeDelegate` in sangam-packs (vote tally for in-process committees) is unaffected. A pack can use `MeetingService` when a standing committee is involved.
8. **Role convention.** The committee chair is the member role `CHAIR`. Every other role is data from the committee kind.

## Third-party dependencies

None new. The module uses Operaton 2.2.0 (`operaton-engine`, Apache-2.0), `jackson-databind` (Apache-2.0) and `slf4j-api` (MIT) from the parent BOM, plus `sangam-core` and `sangam-flowable-ports`.

## Gaps (explicit)

- eSign is recorded as a reference (`esign_ref`). The signing flow is WS-INTEGRATION's connector.
- Venue booking and the external party registry are ports with pass-through defaults until N01 and N14 exist.
- Duplicate references are detected per subject and source within the configured window. There is no fuzzy matching.
- Not yet tested (phase rule).
