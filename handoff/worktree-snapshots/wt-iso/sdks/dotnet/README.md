<!-- SPDX-License-Identifier: Apache-2.0 -->
# Sangam .NET SDK (`Sangam.Sdk`)

Typed REST client and external-worker runtime for the Sangam API (`/api/v1`). Tier-1 SDK per ADR-D-4 / R18.

- Target: **net8.0**. Runtime dependencies: **none beyond the BCL** (`System.Net.Http`, `System.Text.Json`).
- Wire contract identical to the Java, Python, TypeScript and PHP SDKs (same paths, camelCase JSON, Idempotency-Key
  rules, problem+json handling, worker lifecycle).
- Contract: **reconciled against `sangam-rest/api/sangam-api.yaml` 0.1.0** (OpenAPI 3.1, WS-REST), plus
  `sangam/ARCHITECTURE.md` §4 and `SANGAM.md` VI.3. The `outcome-unknown`, `cancel-ack` and
  `/ops/reconciliation/{id}/resolve` endpoints are confirmed by the contract.

## Layout

| Path | Contents |
|---|---|
| `Sangam.Sdk.sln` | Solution (SDK + example) |
| `src/Sangam.Sdk/Sangam.Sdk.csproj` | Package metadata (net8.0, Apache-2.0) |
| `src/Sangam.Sdk/SangamClient.cs` | `SangamClient` + `SangamClientOptions`: entry point, API groups |
| `src/Sangam.Sdk/Http.cs` | `HttpTransport` (auth, 401 refresh, Idempotency-Key, problem+json, retries), `ApiRequest`, `RetryPolicy` |
| `src/Sangam.Sdk/Auth.cs` | `ITokenProvider`, static, Basic, OIDC `ClientCredentialsTokenProvider` |
| `src/Sangam.Sdk/Errors.cs` | `Problem` (RFC 9457), `SangamApiException`, `StaleLeaseException` (409), `GoneException` (410), `TransportException` |
| `src/Sangam.Sdk/Logging.cs` | `ISangamLogger` structured hook (event, message, fields) + console/delegate loggers |
| `src/Sangam.Sdk/Models.cs` | Wire records (`ExternalWork`, worker requests, `LockState`, `OutcomeUnknownResult`, R33 `TypedValue`, tasks, processes, messages, cases, decisions, audit, reconciliation, `CloudEvent`, `PublishResult`, ops bodies, `Page<T>`) |
| `src/Sangam.Sdk/Apis.cs` | `WorkApi`, `TasksApi`, `ProcessesApi`, `DecisionsApi`, `AuditApi`, `AdminApi`, `OpsApi`, `RepositoryApi`, `ResourceApi` |
| `src/Sangam.Sdk/Worker/SangamWorker.cs` | Worker runtime |
| `src/Sangam.Sdk/Worker/TaskContext.cs` | Per-item context (identities, lease state, cancellation, logger) |
| `src/Sangam.Sdk/Worker/Outcomes.cs` | `TaskHandler`, `TopicSubscription`, `Backoff`, `BpmnErrorException`, `OutcomeUnknownException`, `TaskFailedException` |
| `examples/BillPaymentWorker/` | P1 bill/payment worker for topic `payment` |

## Client

```csharp
using var client = new SangamClient(new SangamClientOptions
{
    BaseUrl = new Uri("https://sangam.example.gov.in"),
    TokenProvider = new ClientCredentialsTokenProvider
    {
        TokenEndpoint = new Uri("https://idp/realms/sangam/protocol/openid-connect/token"),
        ClientId = "bill-worker", ClientSecret = secret,
    },
});

var pi = await client.Processes.StartAsync(
    new StartProcessRequest("p1_bill", "BILL-2026-0001",
        new Dictionary<string, object?> { ["amount"] = TypedValue.Decimal(125000.00m) }),   // R33 typed money
    idempotencyKey: "start:BILL-2026-0001");
var tasks = await client.Tasks.ListAsync(new TaskQuery { BusinessKey = "BILL-2026-0001", Mine = true });
var result = await client.Tasks.CompleteAsync(tasks.Items[0].Id,
    new CompleteTaskRequest(PayloadHash: hashTheUserApproved, Decision: "approve", Remarks: "ok"));
var timeline = await client.Audit.TimelineAsync("BILL-2026-0001");
var me = await client.MeAsync();
await client.Ops.ResolveAsync(itemId, new ReconciliationResolveRequest(
    Reconciliation.ConfirmSuccess, Evidence: "bank statement 2026-11-04 line 17"));
```

API groups:

- `MeAsync` (`GET /me`).
- `Work`: the worker API.
- `Tasks`: list, get, variables, claim/unclaim (no body, 204), complete (returns `CompleteTaskResult`), return, delegate.
- `Processes`: start (201), list, get, variables, cancel (returns `TerminationResult`), messages (returns one
  `MessageResult`), signals (204), case start/get.
- `Decisions`: `DecisionEvaluateRequest` → `DecisionEvaluateResult`.
- `Audit`: paged timeline of `AuditEntry` (`includeHistory`), verify by `chainId` / `businessKey`.
- `Admin`: org-units, seats, assignments (`end` via `ActionAsync`), delegations (`revoke`), policies, tenants, any
  other collection.
- `Ops`: incidents (typed `RetryIncidentAsync`), reconciliation list (state, kind) / get / resolve, outbox
  (`RequeueOutboxAsync`, `OutboxStatsAsync`), jobs (`SetJobRetriesAsync`). Other actions (incident resolve, outbox
  skip, job execute/suspension) go through `ResourceApi.ActionAsync`.
- `Repository`: multipart deployments (fields `name`, `source`, `duplicateFiltering`, one `files` part per resource),
  event channels/definitions, `PublishEventAsync(channel, CloudEvent)` → `PublishResult`, forms.

Variables are a VariableMap. Plain JSON values pass through; R33 typed values use `TypedValue.Decimal`, `BigInteger`,
`Long`, `Integer`, `Boolean`, `String`, `Date`, `LocalDate`, `Json` and `Bytes` (Decimal, BigInteger, Long, Date,
LocalDate and base64 Bytes travel as strings). `TaskContext.Variable<T>` and `TypedValue.Unwrap<T>` unwrap them:
`{"type":"Decimal","value":"12.50"}` reads as `12.50m`.

Seat context: `SangamClientOptions.SeatId` / `ActingForSeatId` send `X-Sangam-Seat` / `X-Sangam-Acting-For-Seat` on
every call, and `ApiRequest.WithHeader` overrides them per request. `StartWithResponseAsync` and
`CompleteWithResponseAsync` return the `ApiResponse<T>`; its `IdempotentReplayed` property reflects the
`Idempotent-Replayed` header.

Transport rules:

- Every mutation sends `Idempotency-Key`; when the caller gives none, one `Guid` is generated per logical call and reused
  on every retry (R41). This meets the government profile, which requires a key on start, correlate, task decisions,
  cancel, delete and deploy. Pass a business-derived key to stay safe across process restarts.
- Safe calls (GET, or a mutation with a key) are retried on transport errors and 408/425/429/500/502/503/504 with
  jittered exponential back-off and `Retry-After` (R28).
- A 401 invalidates the token provider once and repeats the call.
- Errors are decoded from `application/problem+json` (`type` = `https://sangam.dev/problems/<kebab-code>`, `code` =
  machine code): 409 `STALE_LEASE` → `StaleLeaseException`, 410 `GONE_INSTANCE_DELETED` → `GoneException`, everything
  else `SangamApiException` with `.Problem`. Unknown codes are accepted and kept in `Problem.Code`.
- Lists accept a bare array or `{items}` and read `X-Total-Count` / `X-Total-Count-Capped` into `Page<T>` (A-16).

## Worker

```csharp
await using var worker = new SangamWorker(client, new SangamWorkerOptions { MaxConcurrency = 4 });
worker.Subscribe(new TopicSubscription("payment", async (ctx, ct) =>
{
    var amount = ctx.Variable<decimal>("amount");
    var reference = await PayAsync(amount, idempotencyKey: ctx.OperationId, ct);
    return new Dictionary<string, object?> { ["paymentReference"] = reference };
}) { LockDuration = TimeSpan.FromMinutes(2), Irreversible = true });
await worker.RunAsync(stopToken);   // graceful stop when stopToken is cancelled
```

Lifecycle (VI.3):

1. **Long-poll** `POST /work/fetch-and-lock` with `maxTasks` = free slots (`SemaphoreSlim`, `MaxConcurrency`). Each
   subscription can set `Variables`, `LocalVariables`, `ProcessDefinitionKey` and `BusinessKey`; the worker option
   `UsePriority` asks for higher-priority work first.
   Fetch errors back off exponentially with jitter (0.5 s → 60 s).
2. **A-1:** only items returned LOCKED with `lockVersion` and `attemptId` run. Items beyond the requested count or for
   an unsubscribed topic are unlocked at once.
3. **Heartbeat** `extend-lock` every lease/3 (`HeartbeatFraction`); the `LockState` answer refreshes `lockExpiresAt`
   and `lockVersion`. `cancelRequested` sets `ctx.IsCancelRequested` and
   cancels the handler token.
4. **Fencing:** every call carries `(workerId, lockVersion, attemptId)`. A 409/410 on any call, or lease expiry while
   the server is unreachable, marks the lease lost, cancels the handler token and **discards** the result.
5. **Idempotency-Key** for worker calls: `<operationId>:<action>:<lockVersion>`; heartbeats add `:<seq>`.
6. **Reporting** is retried with back-off until the lease expires; after that the lease expires on the server and the
   next attempt reuses the same `operationId` (T36).
7. **Shutdown:** `StopAsync` / `DisposeAsync` stop fetching, wait `ShutdownTimeout` for in-flight handlers, then cancel
   the rest; those are unlocked so another worker can take them.
8. `Subscribe` / `Unsubscribe` work at runtime.

Handler outcome mapping:

| Handler result | Call sent |
|---|---|
| returns variables (or null) | `complete` |
| `BpmnErrorException(code, message, vars)` | `bpmn-error` |
| `OutcomeUnknownException(reason, details, vars)` | `outcome-unknown` with `detail` = reason + details and `evidence` = vars; 202 `OutcomeUnknownResult`, UNKNOWN_OUTCOME reconciliation item (VI.3.2 rule 4) |
| `TaskFailedException` (optional retries / retry timeout) | `fail` with the overrides |
| `OperationCanceledException` after a cancel request | `cancel-ack` |
| `OperationCanceledException` during forced shutdown | `unlock` |
| any other exception | `fail` with retries = (item retries or subscription retries) − 1 and `RetryBackoff` timeout; **`outcome-unknown`** when the subscription is `Irreversible` (VI.3.2 rule 5; contract 0.1.0 has no per-item `effect`, so the subscription flag is the only source) |
| anything after lease loss | nothing (result discarded) |

Logging: `ISangamLogger.Log(level, event, message, fields, error)`. Events include `worker.started`,
`work.fetch.failed`, `work.started`, `work.heartbeat`, `work.lease.lost`, `work.cancel.requested`, `work.complete`,
`work.fail`, `work.bpmn-error`, `work.outcome-unknown`, `work.abandoned`, `work.report.gave-up`, `worker.stopped`.
Fields always include `workerId`, `workId`, `topic`, `operationId`, `attemptId`, `lockVersion`, `businessKey`.

## Example: P1 bill payment

`examples/BillPaymentWorker` handles topic `payment` (bill model `wf1_payment`). It sends the payment instruction with
`Idempotency-Key = operationId`; connect failure → `TaskFailedException` (retry), timeout after sending →
`OutcomeUnknownException` (AC-03), 400/422 → BPMN error `PAYMENT_REJECTED`. `amount` is read through TypedValue
unwrapping, and `paidAmount` is returned as a typed `Decimal` (R33). Environment: `SANGAM_URL`, `SANGAM_TOKEN`
or `SANGAM_OIDC_TOKEN_URL` + `SANGAM_CLIENT_ID` + `SANGAM_CLIENT_SECRET` (+ `SANGAM_SCOPE`), `SANGAM_TOPIC`
(default `payment`), `SANGAM_WORKER_ID`, `TREASURY_URL` (unset = built-in simulator).

```
dotnet run --project examples/BillPaymentWorker
```

## Build

```
dotnet build Sangam.Sdk.sln
```

The supported target is net8.0. For a compile check on a machine with only an older SDK, set the MSBuild property
`SangamTargetFramework` (for example the environment variable `SangamTargetFramework=net7.0`); the code uses no
net8-only API.

## Requirements covered (file by file)

| Id | Where |
|---|---|
| R9 / VI.3 (lease lifecycle, heartbeat lease/3, cancel-ack, outcome-unknown, unlock) | `Worker/SangamWorker.cs`, `Worker/TaskContext.cs`, `Apis.cs` (`WorkApi`), `Models.cs` |
| R18 (Tier-1 .NET SDK, same lifecycle semantics) | whole SDK |
| R20 (fencing token lockVersion, 409 stale-lease) | `Worker/SangamWorker.cs`, `Errors.cs`, `Http.cs` |
| R28 (retry with jitter, no blind retries of irreversible effects) | `Http.cs` (`RetryPolicy`), `Worker/Outcomes.cs` (`Backoff`), `Worker/SangamWorker.cs` |
| R36 (operationId forwarded as idempotency key) | `Worker/TaskContext.cs`, `examples/BillPaymentWorker/Program.cs` |
| R41 (Idempotency-Key on every mutation) | `Http.cs`, `Apis.cs`, `Worker/SangamWorker.cs` |
| A-1 (handler only after LOCKED confirmation) | `Worker/SangamWorker.cs` (`PollLoopAsync`) |
| A-2 (operationId vs attemptId) | `Models.cs` (`ExternalWork`), `Worker/TaskContext.cs` |
| A-16 (capped counts) | `Apis.cs` (`ReadPageAsync`), `Models.cs` (`Page<T>`) |
| AC-03 (payment success with lost response → UNKNOWN_OUTCOME) | `examples/BillPaymentWorker/Program.cs`, `Worker/Outcomes.cs` |
| VI.4 rule 5 (payloadHash on task complete) | `Models.cs` (`CompleteTaskRequest`), `Apis.cs` (`TasksApi`) |
| R33 (typed JSON values, money as Decimal strings) | `Models.cs` (`TypedValue`), `Worker/TaskContext.cs` (`Variable<T>`), example |
| IX.5 rules 1 and 4 (seat context, acting-for seat) | `SangamClient.cs`, `Http.cs` |
| R16 (CloudEvents envelope for inbound events) | `Models.cs` (`CloudEvent`), `Apis.cs` (`RepositoryApi.PublishEventAsync`) |
| RFC 9457 problem+json, OIDC client credentials | `Errors.cs`, `Auth.cs` |

Flowable gap list (`spike/results/FLOWABLE-INTO-OPERATON.md`):

| Gap id | How this SDK covers it |
|---|---|
| FC-36 external-worker REST API | Client side of `/api/v1/work/**` (`WorkApi`) |
| FC-37 official worker clients in several languages | This is the .NET Tier-1 client: subscribe/unsubscribe at runtime, long-poll acquire, complete / fail with retries and retry timeout / BPMN error, Basic or bearer auth, configurable worker id and concurrency |
| D-17 external-task fencing missing | Every worker call sends `lockVersion` + `attemptId`; 409 → abandon |
| D-18 unlock without owner check | `unlock` / `cancel-ack` carry `workerId` + `lockVersion` + `attemptId` |
| D-40 long polling not cluster-aware | Client uses the Sangam long-poll endpoint with `asyncResponseTimeoutMs` and back-off; wake-up is server side |
| D-44 Java-only worker client | Closed for .NET by this SDK |

Design reference: the Flowable external-worker clients (`flowable-external-client-*`, Apache-2.0). No code was copied;
only the client structure (subscription per topic, acquire/complete/fail/bpmnError) informed the design, so no
third-party NOTICE entry is needed.

## Third-party dependencies

None (BCL only).

## Gaps

- Every wrapped endpoint is reconciled with `sangam-api.yaml` 0.1.0. Some contract endpoints have no typed method yet;
  they are reachable through `HttpTransport.ExecuteAsync` or `ResourceApi`. These are task comments/attachments; process
  delete, activity-tree, set variables, resubmit, versions, modify, trigger, links, ad-hoc and inject; process and case
  definitions; case commands; packages; batches; migration; form validation and drafts.
- No in-memory test engine yet (R18 Tier-1 "test engine"); contract tests T01–T04/T18 come in the testing phase.
- Compile-checked with .NET SDK 7.0.410 (`SangamTargetFramework=net7.0`); not yet built against a net8 SDK.
