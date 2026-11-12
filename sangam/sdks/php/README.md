# Sangam PHP SDK (`sangam/sdk`)

Typed REST client and external-worker runtime for the Sangam API (`/api/v1`), for PHP 8.2+.
Runtime requirements: `ext-curl` and `ext-json` only (no Guzzle, no other packages). `ext-pcntl` + `ext-posix`
are optional and enable fork mode. Licence: Apache-2.0.

Tier-1 SDK per ADR-D-4 / R18. The wire contract (paths, JSON field names, headers, error handling, lease
protocol) is identical to the Java, .NET, Python and TypeScript SDKs in `sangam/sdks/`, and is **reconciled
against `sangam-rest/api/sangam-api.yaml` 0.1.0** (OpenAPI 3.1, WS-REST).

## Install

```bash
composer require sangam/sdk
```

## Client

```php
use Sangam\Sdk\SangamClient;
use Sangam\Sdk\Api\{DecisionsApi, OpsApi};
use Sangam\Sdk\Auth\ClientCredentialsTokenProvider;
use Sangam\Sdk\Model\{CloudEvent, TypedValue};

$client = new SangamClient('https://sangam.example.gov.in',
    new ClientCredentialsTokenProvider(
        'https://idp.example.gov.in/realms/sangam/protocol/openid-connect/token',
        'bill-worker', getenv('SANGAM_CLIENT_SECRET')),
    seat: 'SEAT-DDO-0042');                       // X-Sangam-Seat on every call (optional)

$me    = $client->me();
$pi    = $client->processes()->start('p1_bill', 'BILL-2026-0001',
             ['amount' => TypedValue::decimal('125000.00'), 'billDate' => TypedValue::localDate('2026-11-04')],
             idempotencyKey: 'start:BILL-2026-0001');
$replayed = $client->lastResponse()?->replayed();  // Idempotent-Replayed
$tasks = $client->tasks()->list(['businessKey' => 'BILL-2026-0001', 'mine' => true, 'size' => 20]);
foreach ($tasks as $t) { /* ... */ }
if ($tasks->totalCountCapped) { echo "more than {$tasks->totalCount}\n"; }   // A-16
$client->tasks()->claim($taskId);
$res = $client->tasks()->complete($taskId, $payloadHash, decision: 'APPROVED', remarks: 'ok');
$client->decisions()->evaluate('p1_amount_band', ['amount' => TypedValue::decimal('125000.00')],
    purpose: DecisionsApi::PURPOSE_AUTHORITY, businessKey: 'BILL-2026-0001');
$client->audit()->timeline('BILL-2026-0001', includeHistory: true);
$client->ops()->resolve($itemId, OpsApi::RESOLVE_CONFIRM_SUCCESS, 'bank statement UTR123',
    ['paymentReference' => 'UTR123']);
$client->admin()->seats()->list();
$client->admin()->assignments()->action($assignmentId, 'end', ['reason' => 'transfer']);
$client->repository()->deploy('p1', ['bill.bpmn' => file_get_contents('bill.bpmn')], source: 'ci');
$client->repository()->publishEvent('treasury-ack',
    CloudEvent::create('treasury.payment.acknowledged', 'urn:treasury', ['utr' => 'UTR123'], 'BILL-2026-0001'));
```

| Group | Endpoints |
|---|---|
| client | `GET /me` |
| `work()` | `POST /work/fetch-and-lock`, `/work/{id}/extend-lock` (→ LockState), `/complete`, `/fail`, `/bpmn-error`, `/unlock`, `/outcome-unknown` (→ 202 OutcomeUnknownResult), `/cancel-ack` |
| `tasks()` | `GET /tasks`, `GET /tasks/{id}`, `GET /tasks/{id}/variables`, `POST /tasks/{id}/claim|unclaim` (no body, 204), `/complete` (→ CompleteTaskResult), `/return`, `/delegate` |
| `processes()` | `POST|GET /process-instances`, `GET /process-instances/{id}`, `GET /process-instances/{id}/variables`, `POST /process-instances/{id}/cancel` (→ TerminationResult), `POST /messages` (→ MessageResult), `POST /signals` (204), `POST /case-instances`, `GET /case-instances/{id}` |
| `decisions()` | `POST /decisions/{key}/evaluate` (purpose ROUTING / AUTHORITY / OTHER) |
| `audit()` | `GET /audit/{businessKey}/timeline` (paged AuditEntry), `GET /audit/verify` |
| `admin()` | `/admin/org-units`, `/admin/seats`, `/admin/assignments` (+ `/{id}/end`), `/admin/delegations` (+ `/{id}/revoke`), `/admin/policies`, `/admin/tenants` (generic CRUD + `action()`) |
| `ops()` | `/ops/incidents` (+ `retryIncident()`, `/resolve` via `action()`), `/ops/reconciliation` (+ `resolve()`), `/ops/outbox` (+ `requeueOutbox()`, `outboxStats()`, `/skip` via `action()`), `/ops/jobs` (+ `setJobRetries()`, `/execute`, `/suspension` via `action()`) |
| `repository()` | `POST|GET /deployments` (multipart: `name`, `source`, `duplicateFiltering`, one `files` part per resource), `/events/channels`, `/events/definitions`, `POST /events/{channel}` (CloudEvent → 202 PublishResult), `GET /forms/{key}` |

Responses are decoded JSON (associative arrays) with the field names of `sangam-api.yaml`; the tenant always
comes from the token, so no call takes a `tenantId`.

Variables (`VariableMap`, R33): plain JSON values pass through; exact types use `Model\TypedValue`
(`decimal()`, `bigInteger()`, `long()`, `integer()`, `boolean()`, `string()`, `date()`, `localDate()`, `json()`,
`bytes()`), sent as `{"type": "Decimal", "value": "1234.50"}`. Decimal, BigInteger, Long, Date, LocalDate and
Bytes (base64) travel as strings - money is never a PHP float. `TypedValue::unwrap()` and
`TaskContext::variable()` / `decimal()` / `dateTime()` read them back (Decimal → exact numeric string).

Transport behaviour (`src/Http/HttpTransport.php`):

- `Authorization` from the token provider; on 401 the token is invalidated and the call repeated once.
- Every mutation carries `Idempotency-Key` (the caller's key, or a UUIDv4 generated once per logical call and
  reused for its retries). The government profile requires it on start, correlate, task decisions, cancel,
  delete and deploy - always satisfied. `lastResponse()->replayed()` exposes `Idempotent-Replayed`.
- Optional client-level `X-Sangam-Seat` / `X-Sangam-Acting-For-Seat` (`seat:` / `actingForSeat:` or `setSeat()`).
- Errors are `application/problem+json` → `SangamApiException` with a `Problem` (`code`, `type`
  `https://sangam.dev/problems/<kebab-code>`; unknown codes are accepted); `STALE_LEASE` (409) →
  `StaleLeaseException`; 410 (`GONE_INSTANCE_DELETED`) → `GoneException`.
- Safe calls (GET, or any mutation with an Idempotency-Key) are retried on network errors and 408/425/429/5xx with
  jittered exponential back-off, honouring `Retry-After`.
- Lists accept a bare JSON array or `{items: [...]}` and read `X-Total-Count` / `X-Total-Count-Capped`.
- The bytes travel through an `HttpSender` (default `CurlSender`, ext-curl). Pass `sender:` to `SangamClient` to
  inject another wire (a proxy-aware client, or the test fake in `tests/Support/FakeSender.php`); auth, keys,
  retries and error mapping stay in `HttpTransport`. ext-curl is required only for the default sender.
- A mutation without a body (claim, unclaim) is sent with an empty body and no Content-Type.

Token providers: `StaticTokenProvider` (dev token), `ClientCredentialsTokenProvider` (OIDC client credentials,
cached until `expires_in - 30 s`), `BasicAuthProvider` (test gateways), `NoAuth`.

Logging: implement `Log\SangamLogger::log(Level, event, message, fields, error)`. Defaults: `StderrJsonLogger`
(one JSON object per line). `PsrLoggerAdapter` wraps any PSR-3 logger (Monolog etc.) without a `psr/log`
dependency; `CallbackLogger` takes a closure. Every worker line carries `workerId`, `workId`, `topic`,
`operationId`, `attemptId`, `lockVersion`, `businessKey`, `processInstanceId`.

## Worker

**Bind the worker to its topics first (SL-158, enforced in 0.1.0).** The server lets a worker fetch, complete and fail work
only on the topics its principal is bound to (WORK on `WORKER_TOPIC/<topic>`). Any other call is answered
**403 `WORKER_TOPIC_NOT_BOUND`** and recorded as `AUTH_DENIED`. Create the worker as a service principal with its topics
(`POST /api/v1/admin/service-principals` with `topics: [...]`) or bind an existing one before you start it; see
`sangam-server/README.md` (worker topic binding).

```php
use Sangam\Sdk\Worker\{SangamWorker, TopicSubscription, TaskContext, BpmnError, OutcomeUnknownException};

$worker = new SangamWorker($client, maxConcurrency: 4);
$worker->subscribe(new TopicSubscription('payment', function (TaskContext $ctx): array {
    // forward $ctx->operationId() as the downstream idempotency key (R36)
    return ['paymentReference' => callTreasury($ctx->variables(), $ctx->idempotencyKey())];
}, lockDurationMs: 120_000, retries: 3, irreversible: true));
$worker->run();   // until SIGTERM / SIGINT / $worker->stop()
```

### Modes

PHP runs one thread per process, so the runtime has two modes (`$worker->mode()`; forced with `mode:`):

| | **fork** (default when `ext-pcntl` + `ext-posix`, Linux/macOS) | **inline** (Windows, or no pcntl) |
|---|---|---|
| Concurrency | up to `maxConcurrency` child processes, one per item | one item at a time |
| Fetch | parent long-polls with `maxTasks` = free slots; the poll is shortened so the parent is back for the next heartbeat | `maxTasks` = 1 |
| Heartbeat | parent sends `extend-lock` every lease/3 for each child's item and writes the returned LockState to a per-child socket pair | handler calls `$ctx->heartbeat()` during long work (throttled to lease/3); the runtime heartbeats again before reporting |
| Cancel requested | parent sends `SIGUSR1`; the child sees `$ctx->isCancelRequested()` | set from the heartbeat answer |
| Lease lost (409/410/expiry) | parent sends `SIGTERM`; the child dies, nothing is reported | `$ctx->isLeaseLost()`; the result is discarded |
| Report | the child reports its own outcome, fenced | the runtime reports |
| Shutdown (SIGTERM/SIGINT) | stop fetching; wait `shutdownTimeoutMs` (heartbeating); `SIGUSR2` (cooperative stop, `throwIfCancelled()` throws) and 10 s more; then `SIGKILL` and `unlock` from the parent | stop fetching; the running handler sees `isShuttingDown()`; a handler that ignores it runs to the end |

Under systemd use `KillMode=mixed` (or send SIGTERM only to the parent): a SIGTERM delivered to the children
directly is treated as lease loss and their results are dropped. Children ignore SIGINT so Ctrl-C shuts down
gracefully through the parent. LockState hand-off: after every successful heartbeat the parent writes
`{lockVersion, lockExpiresAt, cancelRequested}` as one JSON line to the child's end of a `stream_socket_pair()`;
the child drains it in `$ctx->heartbeat()` and always right before reporting, so its fenced report carries the
current lockVersion. If no socket pair can be created, the child falls back to the lockVersion it was forked with.
The parent ignores SIGPIPE so a write to a child that just exited is harmless.

### Outcome table

| Handler does | Call sent | Notes |
|---|---|---|
| returns array / null | `complete` with the variables | |
| throws `BpmnError(code, msg, vars)` | `bpmn-error` | routed by the model |
| throws `OutcomeUnknownException(reason, details, variables)` | `outcome-unknown` with `detail` = reason + details, `evidence` = variables | UNKNOWN_OUTCOME reconciliation item, never auto-repeated (VI.3.2 r4) |
| throws `TaskFailedException` | `fail` with its `retries` / `retryTimeoutMs`, else the defaults | handler asserts nothing happened downstream |
| throws `TaskCancelledException` after a cancel request | `cancel-ack` | VI.3.1 CANCEL_REQUESTED → CANCELLED |
| throws `TaskCancelledException` otherwise (shutdown, own decision) | `unlock` | another worker can take it at once |
| throws anything else | `fail`, retries = (item retries ?? subscription retries) − 1, `retryTimeoutMs` from back-off (≈1 min, ×2, ≤10 min, jitter) | on subscriptions with `irreversible: true`: `outcome-unknown` instead (VI.3.2 r5); the API has no per-item effect flag |
| any call answered 409 stale-lease / 410 gone | — | lease lost: work abandoned, nothing changes server-side |

Every worker call carries `{workerId, lockVersion, attemptId}` and `Idempotency-Key: <operationId>:<action>:<lockVersion>`
(heartbeats: `<operationId>:extend-lock:<lockVersion>:<seq>`). Result reports are retried with back-off until
the lease expires; after that the lease expires server-side and the next attempt reuses the same operationId.
Items without `id`, `lockVersion` or `attemptId` (no LOCKED confirmation) are never started (A-1); every
member of `ExternalWork` is nullable, as in the contract, and an absent member stays null. Items for an unsubscribed topic, or
more items than requested, are unlocked at once. `subscribe()` / `unsubscribe()` work while running.
Work endpoints are fenced by (workId, lockVersion, attemptId) rather than keyed (`x-sangam-fenced`); the derived
Idempotency-Key is still sent and accepted. `TopicSubscription` also takes `localVariables`,
`processDefinitionKey` and `businessKey` (TopicRequest filters); `SangamWorker(usePriority: true)` sets
FetchAndLockRequest.usePriority. `ExternalWork::$errorMessage` carries the previous attempt's failure.
`TopicSubscription::topicRequest()` returns the typed `Model\TopicRequest`; the array form
`toTopicRequest()` is deprecated and kept only for compatibility.

### Durable steps (M-025, R37)

`$ctx->step($name, $fn, ?StepOptions $options)` journals one side-effecting call under the work's operationId
(`GET/PUT /work/{id}/steps/{name}`, fenced by the lease). `$fn` gets a `Step`: send `$step->idempotencyKey()`
(`operationId:name`) to the remote API and report its reference with `$step->setProviderRef()`.

```php
$paymentId = $ctx->step('charge', function (Step $step) use ($bank, $amount): string {
    $r = $bank->charge($amount, idempotencyKey: $step->idempotencyKey());
    $step->setProviderRef($r->id);
    return $r->id;
}, StepOptions::withLookup(fn(Step $s): ?string => $bank->findByKey($s->idempotencyKey())?->id));
```

- Recorded `SUCCEEDED`: the recorded result is returned (R33 typed values unwrapped) and `$fn` is not called.
- `$fn` returns: `INTENT` then `SUCCEEDED`. `$fn` throws `StepNoEffectException`: `FAILED`, may run again later.
  Any other exception: the step stays `INTENT` (uncertain) and the exception propagates.
- `INTENT` left by an earlier attempt: the lookup is asked first (a value is recorded and returned, `null` means
  no effect, so the step runs again); else `StepOptions::idempotentCall()` runs again with the same key; else
  (the default, capability class c) `OutcomeUnknownException`, so the worker reports outcome-unknown.
- 409 STALE_LEASE or 410 from the journal: the lease is marked lost and `TaskCancelledException` is thrown.

NOT exactly-once for remote APIs: a crash between the remote call and the `SUCCEEDED` record leaves `INTENT`;
the options above decide how a later attempt resolves it, never by a blind re-issue. The test engine serves the
journal with the server's transitions (`src/Testing/EngineSteps.php`).

### Sagas (M-026, R36)

```php
Saga::step('charge')
    ->action('payment.charge', fn(TaskContext $ctx): array => ['chargeId' => charge($ctx)])
    ->compensation('payment.refund', function (CompensationContext $c): array {
        refund($c->forwardProviderRef(), $c->idempotencyKey());
        return [];
    })
    ->build()
    ->registerOn($worker);
```

Two subscriptions (options `lockDurationMs`, `retries`, `irreversible`, `variables`). The forward completion adds
the variable `sangamSaga_<name>` (`.` and `-` as `_`): an R33 Json value `{step, operationId, providerRef, workId}`
(providerRef = `$ctx->setOutcomeReference()`). The compensation (the BPMN compensation handler's service task) gets
a `CompensationContext`: `task()`, `forward()` (null without a record), `forwardOperationId()`,
`forwardProviderRef()` and `idempotencyKey()` = `forwardOperationId:compensate:name` (its own operationId without a
record). The same topic for both is an error at build; restricted `variables` get the record variable added for the
compensation.

### Framework adapter (M-027)

Not shipped yet: Laravel and Symfony adapters need `illuminate/*` and `symfony/*`, which are not installed here and
must not become dependencies of this package. Planned as separate packages next to the SDK: a Laravel
ServiceProvider plus the artisan command `sangam:work`, and a Symfony bundle plus the console command
`sangam:work`, both registering handlers and sagas from configuration or the container and stopping the worker with
`SangamWorker::stop()` on SIGTERM.

## Example

`examples/bill-payment-worker.php`: P1 bill/payment worker for topic `payment` (`wf1_payment`), with treasury
simulator. Environment: `SANGAM_URL`, `SANGAM_TOKEN` or `SANGAM_OIDC_TOKEN_URL` + `SANGAM_CLIENT_ID` +
`SANGAM_CLIENT_SECRET` (+ `SANGAM_SCOPE`), `SANGAM_TOPIC` (default `payment`), `SANGAM_WORKER_ID`, `TREASURY_URL`.
It reads `amount` as an exact decimal (`$ctx->decimal()`, TypedValue Decimal or plain) and completes with
`paidAmount` as a typed Decimal. Connect failure → `fail` (safe); timeout/5xx after sending → `outcome-unknown`
(AC-03); 400/422 → `PAYMENT_REJECTED`.

## Requirements covered, file by file

| File | Requirement ids |
|---|---|
| `src/SangamClient.php` | R18 (Tier-1 PHP SDK), FC-37, D-44, IX.5 (seat / acting-for-seat headers), VI.4 rule 6 (`/me`) |
| `src/Http/HttpTransport.php` | R41 (Idempotency-Key on every mutation), R28 (retry with jitter, bounded), ARCHITECTURE §4 (problem+json, bearer auth), R20/D-17 (409 stale-lease mapping) |
| `src/Http/RetryPolicy.php`, `src/Worker/Backoff.php` | R28 |
| `src/Http/ApiRequest.php`, `ApiResponse.php`, `Json.php` | ARCHITECTURE §4 |
| `src/Error/*` | RFC 9457, R20 (`StaleLeaseException`), R23/VI.5 (`GoneException`) |
| `src/Auth/*` | ARCHITECTURE §4 (OIDC bearer, dev static token), FC-37 (basic-auth parity) |
| `src/Log/*` | ARCHITECTURE §1.8 (structured/JSON logs with operationId and businessKey) |
| `src/Model/ExternalWork.php`, `LockState.php` | VI.3, R36 (operationId), A-2 (attemptId separate from operationId) |
| `src/Model/TypedValue.php` | R33 (typed VariableMap values; no binary floats for money) |
| `src/Model/CloudEvent.php` | R16 (CloudEvents 1.0 inbound events) |
| `src/Model/Page.php`, `src/Api/ApiSupport.php` | A-16 (capped counts) |
| `src/Api/WorkApi.php` | FC-36 (worker REST API consumer), R9/VI.3 (all lease transitions incl. outcome-unknown, cancel-ack), D-18 (unlock carries owner + version) |
| `src/Api/TasksApi.php` | VI.4 rule 5 (`payloadHash`, caseVersion), FC-52 (task lifecycle fields) |
| `src/Api/ProcessesApi.php` | VI.5 / R23 (cancel with reason), CMMN case start |
| `src/Api/OpsApi.php` | VI.3.2 r4 / AC-03 (reconciliation resolve: CONFIRM_SUCCESS / RETRY_SAME_OPERATION / MARK_FAILED, with evidence), R63 (incident retry, job retries, outbox requeue) |
| `src/Api/AuditApi.php` | R42, AC-01 |
| `src/Api/AdminApi.php`, `ResourceApi.php`, `RepositoryApi.php` | IX.3–IX.5 admin basics, R16 (events), R47 (forms) |
| `src/Worker/SangamWorker.php` | R9/VI.3 (lease lifecycle, heartbeat lease/3, cancelRequested), A-1, R20/D-17 (fencing, abandon on 409), D-40 (long poll with back-off), R18, FC-37, D-44 |
| `src/Worker/Runner.php` | R36/R41 (keys from operationId), VI.3.2 r4–5 (outcome-unknown, no blind retry of irreversible effects), R28 |
| `src/Worker/TaskContext.php` | R36 (operationId as downstream idempotency key), A-2 |
| `src/Worker/OutcomeUnknownException.php` | VI.3.2 r4, AC-03 |
| `examples/bill-payment-worker.php` | P1 (IX.4), AC-01, AC-03, R36 |

Flowable gap ids: **FC-36** (consumer of the Sangam worker API), **FC-37** (worker clients in several languages:
PHP is new — Flowable ships no PHP client, so there was no client code to port; the structure follows the Java
SDK in `sdks/java`), **D-17** (fencing), **D-18** (owner-checked unlock), **D-40** (long poll), **D-44** (no longer
Java-only). No Flowable code is copied into this package, so no extra NOTICE entry is needed.

## Integration notes / known gaps

- Reconciled against `sangam-api.yaml` 0.1.0. The YAML has more endpoints than this SDK wraps with typed methods
  (comments, attachments, activity tree, modify/migrate, packages, documents, links, history, audit events/export,
  definitions); reach them with `$client->transport()->execute(ApiRequest::get(...))` or `ResourceApi`.
- Responses are associative arrays, not typed classes (except ExternalWork, LockState, Page, TypedValue,
  CloudEvent); field names follow the YAML.
- Inline mode cannot pre-empt a handler: a handler that never calls `heartbeat()` loses its lease after
  `lockDurationMs`, and its late result is rejected (409) and dropped.
- Typed surface: the whole contract is typed by the generated surface (M-020): `$client->api()` exposes every operation of `sangam-api.yaml` (one class per contract tag), generated by `sdks/codegen/generate_sdk_surface.py` and checked by the `codegen` job of `sdks.yml`; the hand-written groups below stay as they are; models are readonly classes with `fromArray()`.
- Test engine (R18, M-021): `Sangam\Sdk\Testing\SangamTestEngine` on the `HttpSender` seam; contract tests T01–T04/T18 in `tests/ConformanceTest.php` and the AC-08 worker `examples/ac08-worker.php` (written, not run; no PHP toolchain was available to compile them).
- Trace context (M-062): the `traceContext` closure of `SangamClient` returns `['traceparent' => ..., 'tracestate' => ...]`; off by default.
- Third-party dependencies: none (only PHP extensions).
