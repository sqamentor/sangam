# Sangam Stage C architecture (binding for all workstreams)

Source of truth for requirements: `../SANGAM.md`, in particular:
- the APPROVED BASELINE (top);
- Part VI (VI.1–VI.6);
- X.1 (R1–R62);
- X.2 (R39, R41, R42, R43, R47);
- X.4 (journeys);
- IX.3–IX.5 (government core, packs, seats);
- XI.10 (Stage B evidence, A-10…A-16, defects).

Premium and universal scope (R63–R84, UP-01…22) comes from `../spike/results/PREMIUM-AND-UNIVERSAL-SCOPE.md`. Flowable strengths come from `../spike/results/FLOWABLE-INTO-OPERATON.md`. The spike code in `../spike/` is **proven evidence**: reuse and harden it, do not reinvent it.

## 1. Principles: better than both Operaton and Flowable

1. **Operaton 2.2.0 as a dependency, extended by plugins (ADR-D-16).** Everything Operaton does stays available unchanged: BPMN 2.0.2, DMN 1.6 / FEEL, CMMN 1.1, the job executor, external tasks, history, the Operaton REST API, migration and modification. Sangam adds behaviour only through the following:
   - `ProcessEnginePlugin`;
   - `BpmnParseListener` / `CmmnTransformListener`;
   - command interceptors;
   - `HistoryEventHandler`;
   - `IncidentHandler`;
   - `JobHandler`;
   - delegate / connector SPIs;
   - Spring beans.

   Changing engine source is a last resort. It is recorded in `sangam-engine/PATCHES.md` (P-n) and offered upstream.
2. **Hexagonal product layer.** The domain logic (`sangam-core`) never imports Operaton. Engine access goes through the `EngineAdapter` port. Its adapter lives in `sangam-engine`.
3. **Every external effect is exactly-once by contract**:
   - operationId = UUIDv5(business key, step, attempt scope) (T35);
   - idempotency store (R41);
   - outbox/inbox (VI.2);
   - lease fencing with lockVersion and attemptId (VI.3);
   - `ExpiryPolicy` (A-10);
   - class (c) work goes to UNKNOWN_OUTCOME, then reconciliation.
4. **Security is in the data path.** Authorization (VI.4) is one compiled SQL predicate, used for both lists and single objects. Any matching deny wins, at any scope (A-13). It applies to every list and count (A-16). Operaton's built-in authorization stays **off** because it has no deny-wins.
5. **The four Tier-1 databases are equal**: PostgreSQL, Oracle, SQL Server, MySQL 8.4.
   - Every SQL statement is per dialect (A-15).
   - MySQL requires READ COMMITTED, utf8mb4 and millisecond datetime (P-1).
   - SQL Server requires RCSI.
   - H2 is for development only.
6. **Fixes for Operaton's known weaknesses** (all mandatory):

   | Weakness (measured or known) | Sangam fix | Module |
   |---|---|---|
   | Optimistic locking only; hot-root failures (E6) | L2 root lock (A-11), retry with jitter | engine |
   | Engine-native lease expiry, which caused a double payment (E3) | `ExpiryPolicy` hook in the same transaction (A-10) | engine |
   | No lease fencing | `SANGAM_LEASE` (work_id, lock_version, attempt_id); idempotent write per (work_id, lock_version) (XI.10.4) | engine + persistence |
   | DST autumn-fold timer runaway (E8) | UTC due dates, `lastFiredInterval`, catch-up policy, bounded job execution (A-12) | engine |
   | JVM-wide static `ClockUtil` | One engine per JVM; clock access only through the `SangamClock` port | engine |
   | Built-in authorization has no deny-wins | Product authorization (VI.4) | security |
   | Count queries are slow under authorization (A-16) | `AUTH_EFFECTIVE` indexes + capped count ("more than N") | security + persistence |
   | 4,000-char string variable limit | Large values stored as typed byte/JSON variables; the product layer chooses this automatically | engine |
   | MySQL second-precision datetime, utf8mb3 | P-1 precision patch + utf8mb4 changelog | engine + persistence |
   | REPEATABLE READ rejected | Isolation forced to READ COMMITTED on every pool | server |
   | CMMN timer listener never fires; exited human task labelled "terminated" | CMMN listener fix + audit mapping (R42) | engine / flowable-ports |
   | Ad-hoc sub-process rejected (GAP set) | Port (see the Flowable gap list) | flowable-ports |
   | Large multi-instance cost is n² (GP-09) | One instance per item + aggregation pattern (packs); MI above a threshold rejected at deploy with a clear message | engine + packs |
   | Rollback defect: product rows committed, engine rows lost (Q1) | Explicit rollback listener in the adapter | engine |

7. **No GraalJS. Scripts run out of process (D-8).** No bundled Oracle, Db2 or MySQL drivers (LIC-4); the MariaDB driver is shipped. Liquibase is pinned at 4.33.0. Jackson is at least 2.22.3.
8. **Observability:**
   - OpenTelemetry traces with operationId and businessKey on every span;
   - Micrometer metrics;
   - JSON logs.
9. **Air-gapped by default:** no outbound calls unless configured.

## 2. Runtime shape

```
 clients (any stack) ──REST/OpenAPI──▶ sangam-server (Spring Boot)
   SDKs: Java, .NET, PHP,             ├─ sangam-rest      (Sangam API /api/v1/**, worker API /api/v1/work/**)
   Python, TypeScript                  ├─ Operaton REST   (/engine-rest/**, unchanged, behind Sangam auth)
 sangam-ui (React) ───────────────────▶├─ sangam-security (OIDC, VI.4 policy, seats, SoD)
                                       ├─ sangam-integration (outbox relay, inbox, connectors, reconciliation)
                                       ├─ sangam-flowable-ports (event registry, ad-hoc, dynamic injection, ...)
                                       ├─ sangam-packs (P1–P4 + universal packs: BPMN/DMN/forms/delegates)
                                       ├─ sangam-engine (Operaton + plugins, EngineAdapter impl)
                                       └─ sangam-persistence (Liquibase: product tables, 4 dialects)
                                                 │
                                       PostgreSQL | Oracle | SQL Server | MySQL
```

## 3. Modules, packages and owners

Each workstream writes **only inside its own directory**. If you need something from another module, depend on its public API. If that API does not exist yet, define a small port interface in your own module and note it in your module README under "Integration notes". The integrator wires it in `sangam-server`.

| Directory | artifactId | Base package | Owner workstream | Contents |
|---|---|---|---|---|
| `sangam-core` | sangam-core | `org.sangam.core` | integrator | Engine-neutral contracts (`api.*`: EngineAdapter, ExternalWork, LeaseTable, ExpiryPolicy, StaleLeaseException) + product primitives (`layer.*`: AuthorizationPolicy, OperationLedger, IdempotencyStore, Outbox, Inbox, Relay, Tombstones, AuditChain, ReconciliationQueue, Dialect, CanonicalJson, Hashes, …), ported from the spike. Other workstreams may **add** new files under `org.sangam.core.<their-area>` but must not change existing files. Request changes in the README instead |
| `sangam-persistence` | sangam-persistence | `org.sangam.persistence` | WS-ENGINE | Liquibase 4.33.0 master changelog + SQL changelogs per dialect for **all** product tables (lease, ledger, idempotency, outbox, inbox, tombstones, audit chain, AUTH_EFFECTIVE, seats/org/delegation, event registry, forms, packs config) |
| `sangam-engine` | sangam-engine | `org.sangam.engine` | WS-ENGINE | `OperatonEngineAdapter` (hardened from the spike), `SangamProcessEnginePlugin` and every weakness fix in §1.6 |
| `sangam-security` | sangam-security | `org.sangam.security` | WS-SECURITY | OIDC resource server, VI.4 policy compiler + `AUTH_EFFECTIVE` maintenance, multi-tenancy, seat/post model, hierarchy, effective-dated assignment, leave/additional charge ("acted for"), delegation of powers via DMN, maker-checker / SoD by person and seat, payload-hash approval binding, admin APIs as services |
| `sangam-integration` | sangam-integration | `org.sangam.integration` | WS-INTEGRATION | Outbox relay, inbox, idempotency, reconciliation workflows, cancel/delete/erase with tombstones (VI.5), connectors (HTTP/REST, SOAP, email, SMS/DLT, SFTP/file, eSign, DigiLocker, payment gateway, PFMS/IFMS) with simulator profiles, back-pressure |
| `sangam-flowable-ports` | sangam-flowable-ports | `org.sangam.ports` | WS-PORTS | Every Flowable strength from the gap list, implemented on Operaton |
| `sangam-rest` | sangam-rest | `org.sangam.rest` | WS-REST | Spring MVC controllers + `api/sangam-api.yaml` (OpenAPI 3.1): tasks, process/case start, messages, worker API, audit timeline, admin, reconciliation, packs. RFC 9457 problem+json errors. Idempotency-Key header |
| `sangam-packs` | sangam-packs | `org.sangam.packs` | WS-PACKS | Government approval core (IX.3) + P1 bill/payment, P2 file movement, P3 grant/DBT, P4 licence/permit + further government domains (works/WAMS, work order, tender, e-recruitment, RTI, grievance, DBT …; GP-01…GP-55) + universal packs (UP-01…22). Each pack: BPMN, DMN, forms JSON, Java delegates/workers, seed seats/roles, simulators |
| `sangam-server` | sangam-server | `org.sangam.server` | WS-REST | Spring Boot app: wiring, configuration profiles (h2, postgres, oracle, sqlserver, mysql), isolation guard, OTel, actuator |
| `sdks/java`, `sdks/dotnet`, `sdks/python`, `sdks/typescript`, `sdks/php` | — | `org.sangam.sdk` / `Sangam.Sdk` / `sangam_sdk` / `@sangam/sdk` / `Sangam\Sdk` | WS-SDK | Worker + client SDKs from `sangam-rest/api/sangam-api.yaml`. Each has a long-poll worker loop, fencing (lockVersion), extendLock heartbeat, retries, Idempotency-Key and examples |
| `sangam-ui` | — (npm) | — | WS-UI | React + TypeScript + Vite: tasklist, cockpit/operations, admin, modeller (bpmn-js/dmn-js, watermark kept, LIC-8), form renderer, audit timeline, reconciliation console, analytics/process-mining views, Hindi + English |
| `deploy/` | — | — | WS-DEPLOY | Dockerfiles, docker-compose (4 DBs + Keycloak + simulators), Helm chart, air-gapped bundle script, GitHub Actions (build, DB matrix CF-11, licence check) |

## 4. API conventions (shared by REST, SDKs and UI)

- Base path `/api/v1`. JSON. Times in ISO-8601 UTC. Ids are strings.
- Auth: `Authorization: Bearer <OIDC JWT>`; tenant from the token claim `tenant`; dev profile uses a static token.
- Mutations accept `Idempotency-Key` (R41). Errors are `application/problem+json`.
- Lists: `?page=&size=` plus `X-Total-Count`, capped ("more than N" → header `X-Total-Count-Capped: true`) (A-16).
- **Worker API** (`/api/v1/work`):
  - `POST /fetch-and-lock` takes `{workerId, topics[{name, lockDurationMs}], maxTasks, asyncResponseTimeoutMs}` and returns `[ExternalWork]`.
  - `ExternalWork` contains `{id, topic, processInstanceId, activityInstanceId, businessKey, variables, lockVersion, attemptId, operationId, lockExpiresAt}`.
  - `POST /{id}/extend-lock`, `/{id}/complete`, `/{id}/fail`, `/{id}/bpmn-error`, `/{id}/unlock` all carry `{workerId, lockVersion, ...}`.
  - A stale lockVersion returns 409 `stale-lease`.
- Tasks: `GET /tasks`, `GET /tasks/{id}`, `POST /tasks/{id}/claim|unclaim|complete|return|delegate` (complete carries `payloadHash` per VI.4 rule 5).
- Processes: `POST /process-instances` (`{key, businessKey, variables}`), `GET /process-instances/{id}`, `POST /process-instances/{id}/cancel`, `POST /messages`, `POST /signals`.
- Cases: `POST /case-instances`. Decisions: `POST /decisions/{key}/evaluate`.
- Audit: `GET /audit/{businessKey}/timeline`, `GET /audit/verify`.
- Admin: `/admin/org-units`, `/admin/seats`, `/admin/assignments`, `/admin/delegations`, `/admin/policies`, `/admin/tenants`.
- Ops: `/ops/incidents`, `/ops/reconciliation`, `/ops/outbox`, `/ops/jobs`.
- Deploy: `/deployments`. Events: `/events/channels`, `/events/definitions`, `POST /events/{channel}`. Forms: `/forms/{key}`.

## 5. Build and working rules for every workstream

- Java 21, Maven wrapper `./mvnw` (Windows: `mvnw.cmd`) from `sangam/`. Build your module with `mvnw.cmd -q -B -DskipTests -pl <module> -am install`.
- **Phase rule (owner, 2026-11-05): complete ALL code first; testing comes last.** Write production code end to end. You may write test classes, but do **not** run Testcontainers, benchmark or Docker suites now. Only compile, plus fast pure unit tests if useful.
- Any heavy command (a full multi-module build, npm install of large trees, dotnet/php/python toolchains) must be wrapped in the shared machine lock: `python -E -X utf8 "%TEMP%\heavy_lock.py" run --owner customwf -- <command>` (see `../spike/RUN-QUEUE.md`).
- Each source file starts with the Apache 2.0 short header: `// SPDX-License-Identifier: Apache-2.0`. Ported Operaton/Flowable code keeps its original header and adds "Modified by Sangam".
- No new dependency with a non-OSI licence. Record each new third-party dependency in your module README (name, licence).
- No `git commit`. The integrator commits.
- Done means: the code compiles, every planned feature in your scope is implemented (no TODO stubs for in-scope features), and the module `README.md` lists the requirement ids (R…, A-…, GP-…, UP-…) covered, file by file.
