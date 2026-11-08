# Sangam Java SDK (`org.sangam:sangam-sdk-java`)

This SDK is a typed REST client and external-worker runtime for the Sangam API (`/api/v1`). Owner: WS-SDK.

- **Java:** 21+. Handlers run on virtual threads.
- **Runtime dependencies:** `java.net.http` (JDK), `jackson-databind` and `jackson-datatype-jsr310` (Apache-2.0).
- **No dependency on server modules.** Any JVM application can embed it.
- **Contract:** reconciled against `sangam-rest/api/sangam-api.yaml` (OpenAPI 3.1, version 0.1.0).

## Build

```
cd sangam
mvnw.cmd -q -B -DskipTests -pl sdks/java -am install
```

## Client

```java
SangamClient client = SangamClient.builder()
    .baseUrl("https://sangam.example.gov.in")           // a trailing /api/v1 is accepted
    .tokenProvider(ClientCredentialsTokenProvider.builder()
        .tokenEndpoint("https://idp/realms/sangam/protocol/openid-connect/token")
        .clientId("bill-worker").clientSecret(secret).build())
    .seat("SEAT-DDO-0042")                               // optional X-Sangam-Seat
    .build();

ProcessInstance pi = client.processes().start(
    new StartProcessRequest("p1_bill", "BILL-2026-0001",
        Map.of("amount", TypedValue.decimal("125000.00")), null, null, null, null),
    "start:BILL-2026-0001");                            // Idempotency-Key (R41)

Page<TaskView> mine = client.tasks().list(TaskQuery.create().mine(true).size(20));
client.tasks().complete(taskId, new CompleteTaskRequest(payloadHash, caseVersion, "APPROVED", "ok", null, null), null);
```

API groups:

| Accessor | Covers |
|---|---|
| `work()` | `/work/fetch-and-lock`, `extend-lock`, `complete`, `fail`, `bpmn-error`, `unlock`, `outcome-unknown`, `cancel-ack` |
| `tasks()` | list (all filters), get, variables, claim, unclaim, complete (payloadHash), return, delegate |
| `processes()` | start, list, get, variables, cancel (TerminateRequest), `/messages`, `/signals`, `/case-instances` |
| `decisions()` | `/decisions/{key}/evaluate` |
| `audit()` | `/audit/{businessKey}/timeline` (paged), `/audit/verify` |
| `admin()` | org-units, seats, assignments, delegations, policies, tenants (generic CRUD + `action(id, "end"/"revoke")`) |
| `ops()` | incidents (retry), reconciliation (list, get, resolve), outbox (stats, requeue), jobs (retries), batches |
| `repository()` | `/deployments` (multipart), `/events/channels`, `/events/definitions`, `POST /events/{channel}` (CloudEvent), `/forms/{key}` |
| `me()` | `/me` |
| `transport()` | raw access for any endpoint that has no wrapper yet |

Transport behaviour:

- **Idempotency-Key (R41).** Every mutation sends an Idempotency-Key. If you do not pass one, a UUID is generated once per logical call and reused on every retry.
- **Retries (R28).** Safe calls are retried with full-jitter exponential back-off on transport errors and on 408/425/429/500/502/503/504, and `Retry-After` is honoured. A call is safe when it is a GET, or a mutation that carries an Idempotency-Key.
- **Token refresh.** A 401 triggers one token refresh and one repeat of the call.
- **Errors.** `application/problem+json` (RFC 9457) bodies become `SangamApiException` carrying a `Problem` (`code`, `type`, `traceId`, extensions):
  - `409` with `code=STALE_LEASE` or `type=.../stale-lease` → `StaleLeaseException`;
  - `410` (`GONE_INSTANCE_DELETED`) → `GoneException`.
- **Lists.** A list call returns `Page<T>` with `totalCount` and `totalCountCapped`, read from `X-Total-Count` and `X-Total-Count-Capped` (A-16).
- **Variables.** Variables are a VariableMap. Use `TypedValue` (R33) for money and large numbers: `{"type":"Decimal","value":"1234.50"}`. JSON fractions are read as `BigDecimal`, never as `double`.

Authentication:

| Provider | Use |
|---|---|
| `ClientCredentialsTokenProvider` | OIDC client credentials (Basic or POST-body client auth). The token is cached and refreshed 30 s before expiry, with single-flight refresh. |
| `TokenProvider.of(token)` | Static token for the dev profile |
| `TokenProvider.basic(user, password)` | Basic auth for dev gateways (Flowable-client parity) |
| `TokenProvider.none()` | No authentication |

## Worker

```java
SangamWorker worker = SangamWorker.builder(client)
    .maxConcurrency(8)
    .subscribe(TopicSubscription.topic("payment", ctx -> {
            BigDecimal amount = ctx.variable("amount", BigDecimal.class);   // unwraps TypedValue
            String ref = gateway.pay(ctx.idempotencyKey(), amount);         // = operationId (R36)
            return Map.of("paymentReference", ref);
        })
        .lockDuration(Duration.ofMinutes(2))
        .irreversible(true)
        .build())
    .build();
worker.run();   // blocks; SIGTERM -> graceful close()
```

| Handler result | Call sent |
|---|---|
| returns `Map` (or `null`) | `complete` with those variables |
| throws `BpmnError(code, msg, vars)` | `bpmn-error` |
| throws `OutcomeUnknownException` | `outcome-unknown` (`detail`, `evidence`) → UNKNOWN_OUTCOME reconciliation item (VI.3 rule 4, AC-03) |
| throws `TaskFailedException` | `fail` with the exception's retries and retry timeout, or the subscription defaults |
| throws `TaskCancelledException` / interrupted after `cancelRequested` | `cancel-ack` |
| same, with no cancel requested (shutdown) | `unlock` |
| any other exception | `fail` (retries − 1, back-off `retryTimeoutMs`), or `outcome-unknown` if the subscription is `irreversible` (VI.3 rule 5) |

Runtime guarantees:

- **Free-slot fetching.** The worker long-polls `fetch-and-lock` only for free slots (`maxTasks` = free permits).
- **Fetch back-off.** Fetch errors back off from 0.5 s up to 60 s, with jitter.
- **A-1.** A handler starts only for items returned LOCKED with an `attemptId`.
- **No hand-off of unknown work.** An item for an unknown topic, or beyond the requested count, is unlocked at once.
- **Heartbeat.** `extend-lock` runs every lease/3 (VI.3 rule 3). A heartbeat with `cancelRequested` sets `ctx.isCancelRequested()`.
- **Lease loss.** A 409/410 on any call, or lease expiry while unreachable, marks the lease as lost. The handler thread is interrupted and its result discarded. The next attempt reuses the same operationId (T36).
- **Fencing.** Every call carries `(workerId, lockVersion, attemptId)` (R20, VI.3 rule 2).
- **Idempotency-Key on worker calls.** The key is `<operationId>:<action>:<lockVersion>`; heartbeats add a sequence number.
- **Result retries.** Result calls are retried until the lease expires. After that the work is left to lease expiry, and the operationId identity stays stable.
- **Graceful `close()`.** Fetching stops, the long poll is interrupted, and in-flight handlers get `shutdownTimeout` to finish. The rest are interrupted and unlocked.
- **Runtime subscriptions.** Topics can be added or removed while the worker runs (`subscribe` / `unsubscribe`).
- **Structured logging.** Plug in your logger through `SangamLogger`: `(level, event, message, fields, error)`. Events include `work.started`, `work.complete`, `work.lease.lost`, `work.abandoned` and `work.fetch.failed`. Fields are workerId, workId, topic, operationId, attemptId, lockVersion, businessKey and processInstanceId. The default implementation is `System.Logger`.

## Example: P1 bill payment

`org.sangam.sdk.examples.BillPaymentWorker` serves topic `payment` (`wf1_payment` in the bill model).

- It posts a payment instruction to `TREASURY_URL` with `Idempotency-Key = operationId`.
- A connect error is reported as `fail`, which is safe to retry.
- A timeout, or a 5xx after sending, is reported as `outcome-unknown` (AC-03).
- A 400/422 from the gateway raises the BPMN error `PAYMENT_REJECTED`.
- When `TREASURY_URL` is unset, a built-in simulator answers.

Environment variables:

| Variable | Meaning |
|---|---|
| `SANGAM_URL` | Sangam server URL |
| `SANGAM_TOKEN` | Static token; alternative to the three OIDC variables below |
| `SANGAM_OIDC_TOKEN_URL`, `SANGAM_CLIENT_ID`, `SANGAM_CLIENT_SECRET` | OIDC client credentials |
| `SANGAM_SCOPE` | OIDC scope |
| `SANGAM_TOPIC` | Topic to serve |
| `SANGAM_WORKER_ID` | Worker id |
| `TREASURY_URL` | Treasury endpoint; unset uses the simulator |

## Requirements covered (file by file)

| File | Requirement ids |
|---|---|
| `worker/SangamWorker.java` | R9 / VI.3.1–3.2 (lease lifecycle, heartbeat lease/3, cancel-ack, unlock), A-1 (start only after LOCKED), R20 (fencing, 409 handling), R21 (lease expiry left to the server sweeper), R36 / A-2 (operationId vs attemptId, idempotency keys), VI.3 rule 5 (irreversible → outcome-unknown), R28 (back-off with jitter), FC-36, FC-37, D-17, D-18, D-40, D-44 |
| `worker/TaskContext.java` | R36 (operationId as downstream idempotency key), A-2, R33 (typed variable unwrap), R13 (log fields) |
| `worker/OutcomeUnknownException.java`, `TaskFailedException.java`, `BpmnError.java`, `TaskCancelledException.java` | VI.3.1 transitions, AC-03 |
| `worker/TopicSubscription.java`, `Backoff.java` | VI.3.2 rule 3 (lease 5 min default, 24 h max), R28 |
| `http/HttpTransport.java`, `RetryPolicy.java`, `ApiRequest.java` | R41 (Idempotency-Key), RFC 9457 problem+json, R28, ARCHITECTURE §4 auth |
| `error/*` | R20 (`STALE_LEASE`), R23 / VI.5 (`GONE_INSTANCE_DELETED`) |
| `auth/*` | ARCHITECTURE §4 OIDC bearer, dev static token |
| `api/*`, `model/*` | ARCHITECTURE §4 endpoints, VI.4 rule 5 (payloadHash), A-16 (capped counts), R33 (`TypedValue`), R38 (CloudEvent), R42 (audit), VI.3 rule 4 (reconciliation resolve) |
| `examples/BillPaymentWorker.java` | IX.4 P1, AC-01, AC-03, R36 |
| all | R18 / ADR-D-4 (Tier-1 Java SDK) |

Flowable gap-list ids (`spike/results/FLOWABLE-INTO-OPERATON.md`):

| Id | What the SDK does |
|---|---|
| **FC-36** | Client for the Sangam worker API (lockVersion, attemptId) |
| **FC-37** | Official worker client, one of five Tier-1 languages |
| **D-17** | External-task fencing: every call carries lockVersion, and 409 means abandon |
| **D-18** | Unlock carries the owner (workerId) and lockVersion |
| **D-40** | Long polling with back-off |
| **D-44** | No longer a Java-only client |

Flowable external-worker client parity:

- runtime subscribe and unsubscribe;
- `numberOfTasks`, mapped to free slots;
- `lockDuration` per topic;
- complete, fail with retries and retry timeout, and bpmnError;
- Basic and bearer auth.

Not ported:

- **`cmmnTerminate`.** CMMN external work uses the processTask → BPMN pattern (FC-11).
- **Typed-variable envelope.** Flowable's envelope is replaced by R33 `TypedValue`.

The Flowable clients (Apache-2.0) were a design reference only. No code was copied, so there is no NOTICE obligation.

## Third-party dependencies

| Name | Licence |
|---|---|
| com.fasterxml.jackson.core:jackson-databind | Apache-2.0 |
| com.fasterxml.jackson.datatype:jackson-datatype-jsr310 | Apache-2.0 |

## Gaps

- **No in-memory test engine yet.** R18 requires one for Tier 1.
- **Contract tests not written.** T01–T04 and T18 are deferred to the testing phase, per the owner's phase rule.
- **Untyped admin and ops bodies.** These are `Map<String, Object>`; typed records for OrgUnit, Seat and the other admin schemas can be added later.
- **`effect` field not in the contract.** `ExternalWork.effect` is not in `sangam-api.yaml`, so irreversibility comes from `TopicSubscription.irreversible`.
