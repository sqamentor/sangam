# sangam-resources (GENERIC-RESOURCES workstream)

This module holds the generic resources capabilities:
- **N01**: resource booking, allotment and scheduling;
- **N07**: queue and token management;
- **N08**: rostering, shifts and duty deployment.

One engine-neutral product layer serves all the systems listed in `spike/results/CAPABILITY-COVERAGE.md`, for example guest houses, theatre and outpatient slots, quarters and plot allotment, test slots, hearing lists, halls, vehicles, rooms, counters and duty rosters. The code contains no country, department or sector names (SANGAM.md X.0e). Resource types, entitlement, priority, tariffs, refunds, queue priority and routing, and roster constraints are all configuration or DMN data.

Status: **all code in scope is written and compiles** (`mvnw.cmd -q -B -DskipTests -pl sangam-resources -am install`). Following the phase rule (ARCHITECTURE.md §5), no tests were written or run.

## Entry point

```java
ResourcesContext ctx = ResourcesContext.builder(ResourcesDialect.detect(connection))
        .decisions(decisionSource)                                   // DMN tables (see "Rule contracts")
        .authorization(securityAuthorization)                        // VI.4: object guard + list predicate, same policy
        .billing(billingPort).notifications(notificationPort).displayFeed(feedPort)
        .approvals(new OperatonApprovalPort(runtimeService))
        .leave(new SecurityLeaveCalendar()).seatLink(new SecurityDutySeatLink(postingService))
        .attendance(attendanceConnector).metrics(metricsPort).audit(auditPort)
        .businessCalendars((tenant, id) -> /* sangam-config BusinessCalendar */)
        .settings(profile.map("resources"))                          // module defaults
        .build();
SangamResources res = new SangamResources(ctx).registerForBpmn();
ResourcesProcesses.deploy(repositoryService);                    // default approval models (idempotent)
Relay relay = new Relay(dialect, tx, ctx.outboxPublisher(tx).orElse(serverPublisher), relayConfig, audit);
ResourcesSweepers sweepers = res.sweepers(tx, nodeId, Duration.ofMinutes(5), 200);
ResourcesRestPort rest = res.rest(Transactions.of(engineAdapter));
QueueRestPort queue = res.queueRest(Transactions.of(engineAdapter));
RosterRestPort roster = res.rosterRest(Transactions.of(engineAdapter));
```

Every service method takes the caller's `java.sql.Connection` and never commits it, as the core layer does. The tenant always comes from `SecurityIdentity.tenantId()` (VI.4 rule 6). Times come from the database clock (A-1).

## Row-level visibility (VI.4 rules 1–3, A-3, A-16)

Every list, count and get-by-id that a caller can reach goes through `support/VisibleQuery.java`, shaped like sangam-security's `AuthorizedQueryBuilder`:
- the identity's tenant and the compiled VI.4 predicate (`port/Visibility.java`, wired by `ResourcesContext.Builder.authorization(...)` to `SecurityPorts.Authorization.visible`) are AND-ed into the SQL itself, so nothing is filtered afterwards;
- counts are capped (`CappedCount`, "more than N", A-16) and pages are capped at 1,000 (R28);
- a hidden row is reported as NOT_FOUND, exactly like a missing one;
- object checks for mutations go through `Guard` = `SecurityPorts.Authorization.require`, which runs the **same** predicate against the single row, so a list and an object check cannot disagree;
- history (`RES_EVENT`) is readable only for an entity the caller can see, and the event rows pass their own predicate;
- period-, service-point- and scope-bound lists first check that the parent itself is visible.

Internal lookups used inside a mutation or a sweeper stay unfiltered; the mutation is already guarded, and sweepers run as the tenant's system principal.

| Resource type (security descriptor) | Table, id column | Read methods |
|---|---|---|
| `RES_RESOURCE` | `RES_RESOURCE.resource_id` | `ResourceCatalogue.search`, `count`, `children`, `visible`; booking search |
| `RES_BOOKING` | `RES_BOOKING.booking_id` | `BookingService.list`, `count`, `visible`, `visibleByRef` |
| `RES_ALLOTMENT` | `RES_ALLOTMENT.allotment_id` | `AllotmentService.list`, `count`, `overdue`, `visible` |
| `RES_WAITLIST` | `RES_WAITLIST.entry_id` | `WaitlistService.list`, `count`, `visible` |
| `RES_DRAW` | `RES_DRAW.draw_id` | `WaitlistService.findDraw` |
| `RES_EVENT` | `RES_EVENT.entity_id` | `BookingService.history` |
| `RES_SERVICE_POINT` | `RES_SERVICE_POINT.service_point_id` | `QueueService.servicePoint(s)`, `countServicePoints`, stats, display snapshot |
| `RES_COUNTER` | `RES_COUNTER.counter_id` | `QueueService.counter(s)` |
| `RES_TOKEN` | `RES_TOKEN.token_id` | `QueueService.token(s)`, `countTokens`, `position`, `slaBreaches` |
| `RES_CALL_EVENT` | `RES_CALL_EVENT.token_id` | `QueueService.callEvents` |
| `RES_ROSTER` | `RES_ROSTER_PERIOD.period_id` | `RosterService.period(s)` |
| `RES_SHIFT_TEMPLATE`, `RES_ROTATION` | `shift_id`, `rotation_id` | `RosterService.shift(s)`, `rotation(s)` |
| `RES_ROSTER_MEMBER`, `RES_COVERAGE_DEMAND` | `person_id`, `demand_id` | `RosterService.members`, `demands` |
| `RES_ROSTER_ASSIGNMENT` | `assignment_id` | `RosterService.assignment`, `personalRoster`, `unitRoster`, `onDuty`, `countAssignments` |
| `RES_SWAP_REQUEST` | `swap_id` | `RosterService.swap(s)` |

A person reading their own bookings, allotments or duties is a policy matter: a grant conditioned on `requester_id`, `holder_id` or `person_id`.

## After-commit delivery (outbox)

Services never call the notification or display-feed ports directly. `ResourcesContext.notify(c, ...)` and `ResourcesContext.feed(c, ...)` append to the core `Outbox` (`org.sangam.core.layer.Outbox`) in the business transaction:
- notifications: root `res-notify:<recipient>`, destination `sangam.resources.notification` (unordered);
- display feed: root `res-feed:<servicePointId>`, destination `sangam.resources.display-feed` (ordered, so a board sees calls in commit order).

The core `Relay` hands each committed event to `port/ResourcesOutboxPublisher.java` (`ctx.outboxPublisher(tx)`, chain the server's publisher with `orElse`), which calls the configured ports. A rolled-back transaction shows and sends nothing. Delivery is at-least-once; `data.eventId` lets receivers deduplicate. `Builder.deliverDirectly(true)` exists only for development without the OUTBOX tables.

## Default approval models

Shipped in this module's resources and deployed by `bpmn/ResourcesProcesses.deploy(repositoryService)` (duplicate filtering, so safe on every start and node). Both are generic: one seat-assigned user task, a timeout, and a service task that applies the decision.

| Process key | File | Candidate seat (configuration) | Timeout | Decision |
|---|---|---|---|---|
| `sangam-booking-approval` | `src/main/resources/bpmn/sangam-booking-approval.bpmn` | resource attribute `approverSeat`, else type `extra.approverSeat`, else setting `bookingApproverSeat` → variable `approverSeatId` | `extra.approvalHoldMinutes` (default 2880) → `approvalTimeout` | `BookingActionDelegate` `APPROVAL_DECISION`; timeout rejects; a booking that already lapsed ends quietly (`INVALID_STATE`) |
| `sangam-roster-publish` | `src/main/resources/bpmn/sangam-roster-publish.bpmn` | setting `rosterApproverSeat.<unitId>`, else `rosterApproverSeat` → `approverSeatId` | setting `rosterApprovalTimeout` (default `P7D`) | `PublishRosterDelegate`; timeout returns the roster to GENERATED |

The user task uses the sangam-security seat assignment (`sangam.seatVariable=approverSeatId`, `sangam.duty=APPROVER`), so the approver is whoever holds the seat at task time, including acting charge, with SoD recorded. A pack or tenant deployment under the same key takes precedence.

## Scheduled sweepers

`ResourcesSweepers` (`res.sweepers(tx, nodeId, leaseTtl, batch)`) has one entry point per sweeper, per tenant. Each call takes a `(tenant, sweeper)` lease in `RES_SWEEP_LEASE` (insert-if-absent or take-over of an expired lease, with a fencing counter), re-checks the lease under a row lock before working, and releases it with the matching fencing value. Every sweep is idempotent, so many nodes can call every method on any schedule; a node that finds a live lease of another node returns `ran=false` at once.

| Method | Sweeper key | What it does | Suggested interval |
|---|---|---|---|
| `expireHolds(tenant)` | `expire-holds` | Lapsed holds and approval holds → EXPIRED, claims released, waitlist promoted | 1 min |
| `markNoShows(tenant)` | `booking-no-shows` | Confirmed bookings past the type's no-show grace → NO_SHOW, refund by policy | 5 min |
| `expireWaitlistOffers(tenant)` | `waitlist-offers` | Lapsed offers → EXPIRED; confirmed offers → PROMOTED | 1 min |
| `queueNoShows(tenant)` | `queue-no-shows` | Called tokens not served in time → NO_SHOW, for every open service point | 1 min |
| `rosterApplyLeave(tenant)` | `roster-apply-leave` | Duty overlapping approved leave → LEAVE, hours freed, seat link ended, cover offered (open periods with future duty) | 15 min |
| `rosterReconcileAttendance(tenant)` | `roster-attendance` | Attendance PRESENT / ABSENT / PARTIAL for ended duty (lookback setting `attendanceLookbackDays`, default 7) | 1 h |
| `flagOverdueAllotments(tenant)` | `overdue-allotments` | Overdue allotments: one `OVERDUE` history event and one holder notice per due date | 1 day |
| `runAll(tenant)` | all | Every sweeper once, each under its own lease | — |

`tenants()` lists the tenants that have resources data. Duty-seat links end through the existing `PostingService.expireDue` sweeper of sangam-security (the posting carries the duty end).

## No double booking under high concurrency

PostgreSQL exclusion constraints are not used, because Oracle, SQL Server and MySQL lack them. The same two mechanisms run on all five dialects, specialised per statement (A-15). See `dialect/ResourcesDialect.java` and `booking/ClaimLedger.java`.

1. **Slot-granular claim rows.** `RES_CLAIM` has the primary key `(tenant_id, resource_id, slot_key, unit_no)`.
   - A booking inserts one row for every grid cell it touches and every capacity unit it takes.
   - The grid is `gcd(slotMinutes, alignMinutes, 60, zone offsets)` minutes on the epoch-minute axis.
   - Every booking of a resource uses the same grid, so two overlapping intervals always share a cell, and the database rejects the second insert.
   - Because the grid is aligned to the zone offsets, back-to-back bookings in any zone never share a cell.

   | Dialect | Insert behaviour |
   |---|---|
   | PostgreSQL | `INSERT .. ON CONFLICT DO NOTHING`, so the transaction stays usable. A concurrent inserter waits, then sees 0 rows. |
   | Oracle | ORA-00001, statement-level rollback |
   | SQL Server | 2627/2601. Requires `XACT_ABORT OFF` (the default) and RCSI. |
   | MySQL 8.4 | 1062, statement-level rollback (InnoDB, READ COMMITTED) |
   | H2 | 23505 (development only) |

   If any cell cannot be claimed, the call removes the claims it made and fails with `SLOT_TAKEN`. Holds that have lapsed and orphan claims standing in the way are released, then the claim is retried once.
2. **Resource-row serialisation for long allotments.**
   - The allotment locks the `RES_RESOURCE` row and checks for an overlapping `RES_ALLOTMENT` while it holds the lock. This lock is the R35 `resource:` exclusive lease within the transaction.
   - Lock syntax per dialect:
     - PostgreSQL, MySQL and H2: `FOR UPDATE`;
     - Oracle: `FOR UPDATE WAIT 5`;
     - SQL Server: `WITH (UPDLOCK, HOLDLOCK, ROWLOCK)`.
   - Types in `BOTH` mode take the same lock on slot bookings, so the two kinds of booking see each other.
3. **Rostering.** Two database keys keep a person from being rostered twice for overlapping duty, across periods:
   - the unique index on `RES_ROSTER_ASSIGNMENT (tenant, period, person, day, shift)`;
   - person-hour claims in `RES_PERSON_CLAIM`.
4. **Queue.** Two mechanisms prevent the same token being called twice:
   - call-next locks the counter row, then claims the next token with `SKIP LOCKED`;
   - for Oracle, ordered candidates are claimed one by one.

## Requirements covered, file by file

Paths are relative to `src/main/java/org/sangam/resources/`.

### Foundation

| File | Covers |
|---|---|
| `ResourcesSchema.java` | DDL for every table, using portable type tokens. Covers A-15 for five dialects and avoids reserved words (`MODE`, `START`, `RANK`, `RULE`, `USER`, `LEVEL`). Amounts are stored as decimal strings. For the integrator's Liquibase. |
| `dialect/ResourcesDialect.java` | A-15 per-dialect SQL: insert-if-absent exclusion, row locks, skip-locked, paging, duplicate and retryable classification, database clock (A-1), H2 |
| `ResourcesContext.java` | Ports, DMN evaluator, VI.4 guard, business calendars (C12), module settings (C04), `RES_EVENT` history plus the external audit hook (R42) |
| `SangamResources.java` | Wiring and BPMN registration |
| `port/ResourcesPorts.java` | Outbound ports, each with a safe stand-alone default: billing (N10, idempotency keys R41), notifications, display feed (N07), approval (C02), leave and duty-seat link (N08, IX.5), attendance, metrics (C14), audit |
| `rules/DecisionSource.java`, `rules/RuleEvaluator.java` | DMN as data. Standalone Operaton DMN engine, cached by content hash. The table hash is recorded with every decision. |
| `support/Sql.java`, `support/Sequences.java`, `support/ResourcesException.java` | JSON and CLOB helpers. Gap-free references per series and period under a row lock. Stable error codes (R28: no SQL in messages). |

### N01 Catalogue, calendars, booking, waitlist, allotment

| File | Covers |
|---|---|
| `catalogue/ResourceType.java` | Types defined in configuration (R78 data objects). Each type carries: attribute schema and validation; booking mode (SLOT, ALLOTMENT, BOTH, NONE); granularity and alignment; hold, notice, window and duration limits; no-show grace; approval; bump policy; DMN keys; tariff, currency and refund tiers; parent-type rule. |
| `catalogue/Resource.java`, `catalogue/ResourceCatalogue.java` | Resources with capacity, unit label, attributes (indexed in `RES_RESOURCE_ATTR`), owning org unit, location and geo point, calendar, status. Hierarchy uses a materialised path: move a subtree, ancestors, children. Search covers type, subtree, unit, location, capacity, attributes and text. Optimistic versioning. |
| `calendar/AvailabilityCalendar.java`, `calendar/Interval.java` | Weekly opening hours per zone, overnight windows, business-day closure from the sangam-config `BusinessCalendar` (C12) |
| `calendar/CalendarService.java` | Calendars. Blackouts, maintenance windows, holidays and reserved periods, which apply down the hierarchy and report the bookings they affect. Slot templates (sessions). Slot generation, alignment helpers. |
| `booking/BookingModel.java` | State machine HELD → (PENDING_APPROVAL →) CONFIRMED → CHECKED_IN → CHECKED_OUT, with exits CANCELLED, NO_SHOW, BUMPED, EXPIRED, REJECTED. Request and result shapes. |
| `booking/BookingRules.java` | DMN entitlement (class, duration, window, tariff, priority, approval), priority, tariff inputs and cancellation refund, each with a profile-data fallback |
| `booking/ClaimLedger.java` | The exclusion primitive (see above). Pooled capacity units. Usage and owner queries. |
| `booking/BookingService.java` | Covers the following booking operations:<br>- availability per slot and search across resources;<br>- quote;<br>- hold with expiry;<br>- confirm, charging through the billing port (R41 keys) and starting approval when required;<br>- approval decision;<br>- check-in and check-out (early check-out frees the remaining cells; overstay is recorded);<br>- cancellation with a refund policy;<br>- priority bump with notice, reason and authority;<br>- recurring series (all-or-nothing or best-effort);<br>- requester limits;<br>- hold-expiry and no-show sweepers using skip-locked;<br>- history. |
| `booking/WaitlistService.java` | Waitlists by resource or type scope with DMN priority (category, seniority) and duplicate guard. Position. Automatic promotion on release as a timed offer (GP-28). Accept offer. Allot from a waitlist. Audited lottery: published seed, entrants hash, SHA-256-seeded Fisher-Yates, the full order stored in `RES_DRAW`, public recomputation. |
| `booking/AllotmentService.java` | Long-term allotment per capacity unit with valid-from and valid-to. Entitlement and charge. One active allotment per holder (configurable). Notice to vacate, vacate, extend and cancel. Overdue list. Vacancy search. Waitlist promotion on vacancy. |

### N07 Queue and token

| File | Covers |
|---|---|
| `queue/QueueModel.java`, `queue/QueueService.java` | Covers the following:<br>- service points and counters with skills;<br>- token issue: walk-in, kiosk, SMS, QR, or check-in from an N01 booking;<br>- display numbers with no gaps per service point, day and series;<br>- DMN priority class and routing to a skill;<br>- call next by skill and priority;<br>- call out of order, recall with a maximum then NO_SHOW, skip, serve, complete with outcome;<br>- transfer within or across service points;<br>- cancel, no-show sweep;<br>- SLA due time and breaches;<br>- wait-time and per-counter statistics sent to C14;<br>- display snapshot and feed events (webhook/websocket port);<br>- optional SMS;<br>- `RES_CALL_EVENT` history. |

### N08 Rostering

| File | Covers |
|---|---|
| `roster/RosterModel.java` | Shift templates, rotation patterns, roster periods (DRAFT → GENERATED → SUBMITTED → PUBLISHED → CLOSED), members, coverage demand per shift, skill, day and duty seat, assignments (DUTY, ON_CALL, OFF, LEAVE), swap and cover requests. Constraints come from profile data with a DMN override. |
| `roster/RosterConstraintChecker.java` | Overlap, leave, rest hours, maximum per day and week, weekly off, consecutive days and nights, skill |
| `roster/RosterGenerator.java` | Rule-based draft: rotations first, then a greedy, fairness-ordered fill of coverage demand under the constraints. Ties are broken by a seeded random; the seed and entrants hash are recorded. Audited random deployment. Unfilled demand report. The solver remains an optional external worker. |
| `roster/RosterService.java` | CRUD for shifts and rotations. Periods, members and demand. Generate, with person-hour claims. Manual assign or override with an audited reason. Submit for approval (C02), decide, publish (notifies members). **Duty ⇒ seat link**: publishing calls `DutySeatLink.link`, so the person on duty acts in the duty seat for the window (IX.5 additional charge), and cancelling unlinks it. Also: swap and cover with constraint re-check, on-call, leave integration (overlapping duty becomes LEAVE and the substitute is offered cover), attendance reconciliation, personal and unit rosters, who is on duty now. |
| `security/SecurityLeaveCalendar.java`, `security/SecurityDutySeatLink.java` | Leave from sangam-security `SEC_LEAVE`. The seat link goes through `PostingService.grantAdditionalCharge` / `endPosting`. |

### BPMN integration (Operaton delegates; product rows commit with the engine command)

| File | Use |
|---|---|
| `bpmn/ResourcesRuntime.java` | Service registry, engine connection, identity resolver (default: the instance tenant's system principal with `onBehalfOf=initiator`) |
| `bpmn/HoldBookingDelegate.java` | Hold from a process. Module errors become `BpmnError(reason)` for boundary events. |
| `bpmn/BookingActionDelegate.java` | Field `action`: `CONFIRM`, `APPROVAL_DECISION`, `CHECK_IN`, `CHECK_OUT`, `CANCEL`, `BUMP`, `EXPIRE_HOLDS`, `MARK_NO_SHOWS` |
| `bpmn/EntitlementCheckDelegate.java` | Entitlement pre-check in application processes |
| `bpmn/WaitlistDelegate.java` | Field `action`: `ENQUEUE`, `DRAW`, `ALLOT_NEXT`, `ALLOT`, `VACATE`, `ACCEPT_OFFER` |
| `bpmn/IssueTokenDelegate.java`, `bpmn/QueueSlaBreachDelegate.java` | Token issue; SLA breach and no-show sweep |
| `bpmn/PublishRosterDelegate.java`, `bpmn/RosterSwapDecisionDelegate.java` | Roster publication and swap approval outcomes. A swap that breaks a constraint raises `BpmnError ROSTER_CONSTRAINT`. |
| `bpmn/OperatonApprovalPort.java` | `ApprovalPort` on `RuntimeService`. Business key, tenant and initiator. |

### REST ports (for WS-REST; no Spring in this module)

`rest/ResourcesRestPort.java` + `ResourcesRestAdapter.java`, `rest/QueueRestPort.java` + `QueueRestAdapter.java`, and `rest/RosterRestPort.java` + `RosterRestAdapter.java`. Each method takes the `SecurityIdentity` first and runs in one `Transactions` unit. Errors are `ResourcesException`. `httpStatus()` and `reason()` map onto problem+json:
- 404 NOT_FOUND;
- 409 CONFLICT, UNAVAILABLE, STALE;
- 422 NOT_ENTITLED, RULE, CONSTRAINT;
- 400 INVALID.

## REST endpoints (base `/api/v1/resources`)

Mutations accept `Idempotency-Key` (R41). Lists use `page`/`size`, with `size` capped at 1,000.

Every list, count and single read applies the visibility predicate. Capped counts return `X-Total-Count` and `X-Total-Count-Capped`:
- `GET /items/count`, `/bookings/count`, `/waitlist/count`, `/allotments/count`, all taking `?…&cap=`;
- `GET /service-points/{id}/tokens/count?state=&cap=`;
- `GET /rosters/periods/{id}/assignments/count?cap=`.

**Catalogue and calendars**

| Endpoints | Purpose |
|---|---|
| `GET /types`<br>`PUT /types/{typeId}`<br>`DELETE /types/{typeId}` | Resource types |
| `POST /items`<br>`GET /items?type=&parent=&ancestor=&unit=&location=&minCapacity=&status=&q=&attr.<k>=<v>`<br>`GET /items/{id}`<br>`PATCH /items/{id}` (If-Match version)<br>`POST /items/{id}/status`<br>`POST /items/{id}/move`<br>`GET /items/{id}/children` | Resources |
| `GET /items/{id}/availability?from=&to=`<br>`GET /items/{id}/slots?from=&to=`<br>`GET /search?from=&to=&quantity=&type=…` | Availability |
| `PUT /items/{id}/slot-templates/{tid}`<br>`DELETE /slot-templates/{tid}` | Slot templates |
| `PUT /calendars/{id}`<br>`GET /calendars/{id}` | Calendars |
| `POST /blackouts`<br>`DELETE /blackouts/{id}` | Blackouts |

**Booking**

| Endpoints | Purpose |
|---|---|
| `POST /bookings/quote`<br>`POST /bookings`<br>`POST /bookings/series` | Quote and hold |
| `GET /bookings?resource=&requester=&state=&from=&to=&mine=`<br>`GET /bookings/{id}`<br>`GET /bookings/by-ref/{ref}` | Read |
| `POST /bookings/{id}/confirm`<br>`POST /bookings/{id}/approval`<br>`POST /bookings/{id}/check-in`<br>`POST /bookings/{id}/check-out`<br>`POST /bookings/{id}/cancel`<br>`POST /bookings/{id}/bump`<br>`POST /bookings/series/{recurrenceId}/cancel` | Life cycle |
| `GET /history/{entityType}/{id}` | History |

**Waitlist, draws and allotment**

| Endpoints | Purpose |
|---|---|
| `POST /waitlist`<br>`GET /waitlist?scope=&state=`<br>`GET /waitlist/{id}`<br>`POST /waitlist/{id}/withdraw`<br>`POST /waitlist/{id}/accept` | Waitlist |
| `POST /draws`<br>`GET /draws/{id}`<br>`POST /draws/verify` | Draws |
| `POST /allotments`<br>`POST /allotments/from-waitlist`<br>`GET /allotments?resource=&holder=&state=`<br>`GET /allotments/{id}`<br>`POST /allotments/{id}/notice`<br>`POST /allotments/{id}/vacate`<br>`POST /allotments/{id}/extend`<br>`POST /allotments/{id}/cancel`<br>`GET /allotments/overdue` | Allotment |

**Operations**

| Endpoints | Purpose |
|---|---|
| `POST /ops/expire-holds`<br>`POST /ops/no-shows` | Sweepers (system identity) |

**Queue** (`QueueRestPort`)

| Endpoints | Purpose |
|---|---|
| `PUT /service-points/{id}`<br>`GET /service-points`<br>`GET /service-points/{id}`<br>`POST /service-points/{id}/status` | Service points |
| `PUT /service-points/{id}/counters/{counterId}`<br>`GET /service-points/{id}/counters`<br>`POST /counters/{id}/open`, `/pause`, `/close` | Counters |
| `POST /service-points/{id}/tokens`<br>`GET /service-points/{id}/tokens`<br>`GET /tokens/{id}`<br>`GET /tokens/{id}/position`<br>`POST /tokens/{id}/transfer`<br>`POST /tokens/{id}/cancel` | Tokens |
| `POST /counters/{id}/call-next` (204 when empty)<br>`POST /counters/{id}/call/{tokenId}`<br>`POST /counters/{id}/recall`, `/skip`, `/serve`, `/complete` | Counter actions |
| `POST /service-points/{id}/no-show-sweep`<br>`GET /service-points/{id}/sla-breaches`<br>`GET /service-points/{id}/stats` | Monitoring |
| `GET /service-points/{id}/display?next=`<br>`GET /service-points/{id}/events?after=&limit=` | Display feed |

**Roster** (`RosterRestPort`, base `/api/v1/resources/rosters`)

| Endpoints | Purpose |
|---|---|
| `GET/POST /shifts`<br>`PUT/DELETE /shifts/{id}`<br>`GET/POST /rotations`<br>`PUT/DELETE /rotations/{id}` | Shifts and rotations |
| `POST /periods`<br>`GET /periods?unitId=`<br>`GET /periods/{id}`<br>`PUT /periods/{id}/constraints`<br>`POST /periods/{id}/close` | Periods |
| `GET /periods/{id}/members`<br>`PUT/DELETE /periods/{id}/members/{personId}` | Members |
| `GET/POST /periods/{id}/demand`<br>`DELETE /periods/{id}/demand/{demandId}` | Coverage demand |
| `POST /periods/{id}/generate`, `/randomise`, `/submit`, `/decision`, `/publish`, `/apply-leave`, `/reconcile-attendance` | Period life cycle |
| `GET /periods/{id}/assignments`<br>`GET /periods/{id}/on-duty?at=`<br>`POST /periods/{id}/assignments`<br>`POST /periods/{id}/on-call`<br>`GET/DELETE /assignments/{id}` | Assignments |
| `GET /people/{personId}/assignments?from=&to=` (`me` = the caller) | Personal roster |
| `POST /swaps`<br>`GET /periods/{id}/swaps`<br>`GET /swaps/{id}`<br>`POST /swaps/{id}/accept`, `/decision`, `/cancel` | Swaps and cover |

## Configuration and rule contracts

**Resource type** (`ResourceType.fromMap`): the definitions are loaded with `ResourceCatalogue.loadTypes` from the profile list `resources.types`. Keys by group:

| Group | Keys |
|---|---|
| Identity | `typeId`, `name`, `kind` |
| Booking mode and granularity | `bookingMode` (SLOT, ALLOTMENT, BOTH, NONE), `slotMinutes`, `alignMinutes` |
| Booking windows | `holdMinutes`, `minNoticeMinutes`, `maxAdvanceDays`, `minDurationMinutes`, `maxDurationMinutes`, `noShowGraceMinutes` |
| Capacity | `defaultCapacity`, `unitLabel` |
| Approval and bumping | `requiresApproval`, `approvalProcessKey`, `allowBump` |
| DMN decision keys | `entitlementDecision`, `priorityDecision`, `tariffDecision`, `cancellationDecision` |
| Charges and refunds | `defaultTariffCode`, `currency`, `refundTiers[{minNoticeHours, refundPercent}]` |
| Structure | `allowedParentTypes`, `attributes[{name, type, required, indexed, values, default}]` |

`extra` keys:

| Area | Keys |
|---|---|
| References | `refPrefix`, `allotmentRefPrefix` |
| Limits | `maxActivePerRequester`, `maxActivePerHolder` |
| Booking behaviour | `flexibleTimes`, `earlyCheckInMinutes`, `approvalHoldMinutes`, `beneficiaryPays` |
| Waitlist | `offerMinutes`, `priorityByCategory`, `allowMultipleWaitlistEntries` |
| Refunds | `noShowRefundPercent` |

**Module settings:**

| Area | Settings |
|---|---|
| Limits | `maxClaimRows` (20000), `maxAvailabilityDays` (92), `maxSeriesOccurrences` (366) |
| Waitlist | `waitlistPromotionBatch` (10), `autoPromoteWaitlist` (1), `autoAllotFromWaitlist` (0) |
| Attendance | `attendanceGraceMinutes` (15) |

**DMN tables.** The inputs are listed in `BookingRules`; all tables receive the resource and requester facts. Each table's outputs:

| Table | Outputs |
|---|---|
| Entitlement | `allowed`, `entitlementClass`, `priorityLevel`, `maxDurationMinutes`, `maxAdvanceDays`, `tariffCode`, `requiresApproval`, `reason` |
| Priority | `priorityLevel`, `priorityScore` |
| Tariff | `tariffCode` plus any pricing inputs |
| Cancellation | `refundPercent`, `fee`, `ruleRef`. Inputs added: `noticeHours`, `state`, `cancelledBy`, `noShow` |
| Queue priority and routing | See `QueueService` |
| Roster constraints | See `RosterModel.RosterConstraints` |

## Integration notes (for the integrator)

1. **Liquibase.** Copy `ResourcesSchema.templates()` (via `ddl(dialect)`) into sangam-persistence, one changeSet per statement and per dialect.
2. **Root pom.** The module is added to `<modules>` and `<dependencyManagement>`.
3. **Security resource types.** Register one `ResourceDescriptor` per row of the visibility table above in `ResourceRegistry` (table, id column, attribute columns used by conditions), then wire `Builder.authorization(authorizationService)`. Without it the module falls back to tenant-only visibility and allow-all guards (development).
   - Permission names used: READ (lists, counts, reads), MODIFY, ADMIN, APPROVE, CANCEL, WORK.
   - The system principal used by sweepers and BPMN delegates needs grants on these types (VI.4 rule 7).
4. **Billing.** Implement `BillingPort` over N10 (demand, payment, refund) or sangam-ledger. Charges and refunds carry idempotency keys `booking:<id>:charge|refund` and `allotment:<id>:charge`. The default is free of charge.
5. **Approvals.** Use `OperatonApprovalPort`. For atomic product and engine writes, run the REST adapters on `Transactions.of(engineAdapter)`.
   - Approval models call back through `BookingActionDelegate(APPROVAL_DECISION)`, `PublishRosterDelegate` and `RosterSwapDecisionDelegate`.
   - Default process keys: `sangam-booking-approval` and `sangam-roster-publish`; the default models ship in this module (see "Default approval models"); call `ResourcesProcesses.deploy` once at start.
6. **Leave.** `SecurityLeaveCalendar` reads `SEC_LEAVE` directly (read-only), because sangam-security has no leave-query port yet. A `SeatDirectory`-style leave port in sangam-core would remove this coupling. `SecurityDutySeatLink` uses `PostingService`.
7. **Outbox.** Notifications and the display feed go through the core outbox (see "After-commit delivery"). The relay must route `sangam.resources.notification` and `sangam.resources.display-feed` to `ctx.outboxPublisher(tx)`; the `OUTBOX`, `OUTBOX_DELIVERY` and `ROOT_SEQ` core tables must exist.
8. **Sweepers.** Schedule the `ResourcesSweepers` methods per tenant (see "Scheduled sweepers"); add `RES_SWEEP_LEASE` (in `ResourcesSchema.OPS`) to Liquibase.
9. **Business calendars.** Supply a `(tenant, id) -> Optional<BusinessCalendar>` lookup from sangam-config profiles.
10. **SQL Server** must keep `XACT_ABORT OFF` on the pool, so a duplicate claim ends only the statement. **MySQL** must stay on READ COMMITTED (already enforced server-wide).
11. **H2.** H2 has no `SKIP LOCKED`, so `FOR UPDATE` serialises callers there (development only).

## Dependencies

No new third-party dependency:
- sangam-core, sangam-config, sangam-security;
- Jackson (Apache-2.0);
- SLF4J (MIT);
- Operaton engine and DMN (Apache-2.0, `provided`).
