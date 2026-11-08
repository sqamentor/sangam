# @sangam/sdk: Sangam TypeScript / Node.js SDK

This package is a typed REST client for the Sangam API (`/api/v1`) and an external-worker runtime that follows the VI.3 lease protocol. It is a Tier-1 SDK under ADR-D-4 / R18.

- Node 20.3 or later. The SDK uses the built-in `fetch`, `AbortSignal.any` and `crypto.randomUUID`.
- Ships as ESM with `.d.ts` types.
- No runtime dependencies. The only dev dependencies are `typescript` and `@types/node`.
- Licence: Apache-2.0.

The wire contract is **reconciled against `sangam-rest/api/sangam-api.yaml` 0.1.0** (OpenAPI 3.1, WS-REST). It matches the Java, .NET, Python and PHP SDKs: the same paths, camelCase JSON, Idempotency-Key rules and outcome mapping.

## Install and build

```sh
npm install
npm run build        # emits dist/ (ESM + .d.ts)
npm run typecheck    # src + examples
```

## Client

```ts
import { SangamClient, ClientCredentialsTokenProvider, TypedValues } from '@sangam/sdk';

const client = new SangamClient({
  baseUrl: 'https://sangam.example.gov.in',
  tokenProvider: new ClientCredentialsTokenProvider({
    tokenEndpoint: 'https://idp/realms/sangam/protocol/openid-connect/token',
    clientId: 'bill-worker',
    clientSecret: process.env.SECRET!,
  }),
  seat: 'SEAT-DDO-01',               // default X-Sangam-Seat (optional)
});

const pi = await client.processes.start(
  { key: 'p1_bill', businessKey: 'BILL-2026-0001', variables: { amount: TypedValues.decimal('125000.00') } },
  { idempotencyKey: 'start:BILL-2026-0001' },   // "start once" across retries
);
pi.idempotentReplayed;               // true when the server answered from its idempotency store
const page = await client.tasks.list({ candidateGroup: 'checker', size: 50 });
// page.totalCount / page.totalCountCapped come from X-Total-Count(-Capped) (A-16)
const done = await client.tasks.complete(taskId, { payloadHash, decision: 'approve', remarks: 'ok' });
```

| Group | Endpoints |
|---|---|
| `client.me` | `GET /me` |
| `client.work` | `POST /work/fetch-and-lock`, `/work/{id}/extend-lock` (→ LockState), `/complete`, `/fail`, `/bpmn-error`, `/unlock`, `/outcome-unknown` (202 → OutcomeUnknownResult), `/cancel-ack` |
| `client.tasks` | `GET /tasks`, `GET /tasks/{id}`, `GET /tasks/{id}/variables`, `POST /tasks/{id}/claim` and `/unclaim` (no body, 204), `POST /tasks/{id}/complete` (→ CompleteTaskResult), `/return`, `/delegate` |
| `client.processes` | `POST /process-instances` (201), `GET /process-instances` (paged, filters), `GET /process-instances/{id}`, `GET /process-instances/{id}/variables`, `POST /process-instances/{id}/cancel` (→ TerminationResult), `POST /messages` (→ MessageResult), `POST /signals` (204), `POST /case-instances` |
| `client.decisions` | `POST /decisions/{key}/evaluate` (→ DecisionEvaluateResult) |
| `client.audit` | `GET /audit/{businessKey}/timeline` (paged AuditEntry, `includeHistory`), `GET /audit/verify` (`chainId`, `businessKey`) |
| `client.admin` | `/admin/org-units`, `/seats`, `/assignments` (+ `endAssignment`), `/delegations` (+ `revokeDelegation`), `/policies`, `/tenants` (generic CRUD) |
| `client.ops` | `/ops/incidents` (+ `retryIncident`, `resolveIncident`), `/ops/outbox` (+ `outboxStats`, `requeueOutbox`, `skipOutbox`), `/ops/jobs` (+ `setJobRetries`; `/execute`, `/suspension` via `jobs().action`), `/ops/reconciliation` (`state`, `kind` filters; `resolve` with `CONFIRM_SUCCESS` / `RETRY_SAME_OPERATION` / `MARK_FAILED` + evidence) |
| `client.repository` | `POST /deployments` (multipart: `name`, `source`, `duplicateFiltering`, one `files` part per resource; 201), `GET /deployments`, `/events/channels`, `/events/definitions`, `POST /events/{channel}` (CloudEvent → 202 PublishResult; build with `cloudEvent()`), `GET /forms/{key}` |

### Typed variables (R33)

Variables are a VariableMap. Plain JSON values pass through unchanged. Typed values use the contract's TypedValue form, `{"type": ..., "value": ...}`.

- **Building.** Use `TypedValues.decimal | bigInteger | long | integer | boolean | string | date | localDate | json | bytes`. Decimal, BigInteger, Long, Date, LocalDate and Bytes (base64) are sent as strings.
- **Reading.** `ctx.variable(name)` and `fromTypedValue` / `unwrapVariables` unwrap a typed value:
  - Decimal: an exact `string` (for example `'12.50'`), because JS has no decimal type
  - BigInteger and Long: `bigint`
  - Date: `Date`
  - LocalDate: `'YYYY-MM-DD'`
  - Bytes: `Uint8Array`
- **Helpers.** `ctx.decimal(name)`, `ctx.bigint(name)` and `ctx.date(name)`.
- **Serialising.** A plain `bigint` in a request body is serialised as a string.

### Transport rules (`src/http.ts`)

- Every mutation sends an `Idempotency-Key`. If the caller passes none, the SDK generates a UUID per logical call and reuses it on every retry.
  - The government profile requires an Idempotency-Key on start, correlate, task decisions, cancel, delete and deploy. The auto-generated key satisfies it.
  - Pass your own business-derived key for "do once" semantics across process restarts.
  - On object responses, `idempotentReplayed` reflects the `Idempotent-Replayed` header.
- **Seat headers.** The client can carry defaults for `X-Sangam-Seat` and `X-Sangam-Acting-For-Seat`, set with the `seat` and `actingForSeat` options. They can be overridden per call through `CallOptions`.
- **No tenant in requests.** The tenant always comes from the token, so no request carries `tenantId`.
- Only safe calls are retried: a GET, or a mutation that carries an Idempotency-Key. They are retried on network errors and on 408/425/429/5xx, with jittered exponential back-off. A numeric `Retry-After` header is honoured.
- A 401 triggers one token refresh and one repeat.
- Error responses are parsed as `application/problem+json`:
  - Problem `type` is `https://sangam.dev/problems/<kebab-code>`, with a stable `code`. Unknown codes are accepted.
  - 409 `STALE_LEASE` raises `StaleLeaseError`.
  - 410 `GONE_INSTANCE_DELETED` raises `GoneError`.
  - Any other error status raises `SangamApiError`.
  - A network failure after all retries raises `TransportError`.

Token providers (`src/auth.ts`):

- `StaticTokenProvider`: the dev-profile static token.
- `ClientCredentialsTokenProvider`: OIDC client credentials. The token is cached and refreshed before expiry, and only one refresh request runs at a time.
- `BasicAuthProvider`: for dev or test gateways only.
- `noAuth`: no credentials.

## Worker

```ts
import { SangamWorker, BpmnError, OutcomeUnknownError } from '@sangam/sdk';

const worker = new SangamWorker(client, { maxConcurrency: 8, usePriority: true });
worker.subscribe('payment', async (ctx) => {
  const res = await fetch(url, { headers: { 'Idempotency-Key': ctx.operationId }, signal: ctx.signal });
  return { paymentReference: (await res.json()).reference };
}, { lockDurationMs: 120_000, retries: 3, irreversible: true, processDefinitionKey: 'p1_bill' });
await worker.run();              // stops gracefully on SIGTERM / SIGINT
// or: worker.start(); ... await worker.stop(30_000);
```

| Handler result | Call sent | Notes |
|---|---|---|
| returns variables (or nothing) | `complete` | |
| throws `BpmnError(code, msg, vars)` | `bpmn-error` | routed by the model |
| throws `OutcomeUnknownError` | `outcome-unknown` | the reason and details are sent as `detail`, the error's variables as `evidence`. The 202 OutcomeUnknownResult carries the opened `reconciliationItemId`, which is logged (VI.3.2 rule 4, AC-03) |
| throws `TaskFailedError` | `fail` | the error can override `retries` and `retryTimeoutMs`; `retries: 0` raises an incident |
| throws `TaskCancelledError` (or aborts) after a cancel request | `cancel-ack` | VI.3.1 CANCEL_REQUESTED → CANCELLED |
| throws `TaskCancelledError` during shutdown | `unlock` | another worker can take the item at once |
| throws any other error | `fail` | retries = item retries (or the subscription default) − 1; `retryTimeoutMs` grows from about 1 min (R28) |
| throws any other error on a subscription marked `irreversible: true` | `outcome-unknown` | never a blind retry (VI.3.2 rule 5). `effect` is not in contract 0.1.0; it is honoured only if a server sends `effect: irreversible` |
| lease lost (409 / 410 / expiry while unreachable) | none | `ctx.signal` is aborted and the result is discarded |

Runtime behaviour (`src/worker.ts`):

- **Fetching.** The worker long-polls `fetch-and-lock` and asks only for as many items as it has free slots. It unlocks items it did not ask for, and items for topics it is no longer subscribed to. Fetch errors back off exponentially with jitter, from 0.5 s up to 60 s.
- **Topic filters.** Per topic: `variables`, `localVariables`, `processDefinitionKey` and `businessKey`. The worker option `usePriority` sets the matching fetch-and-lock flag.
- **A-1.** A handler runs only for an item that came back LOCKED with an `attemptId` and a `lockVersion`.
- **Heartbeat.** The worker calls `extend-lock` every lease/3. It reads the LockState answer (`lockVersion`, `lockExpiresAt`, `cancelRequested`). When `cancelRequested` is set, the worker sets `ctx.cancelRequested` and aborts `ctx.signal`. A failed heartbeat is retried until the lease expires.
- **Fencing.** Every call carries `{workerId, lockVersion, attemptId}`.
- **Idempotency keys.** Each worker call uses `<operationId>:<action>:<lockVersion>`. Heartbeats add `:<seq>`, so a heartbeat is never answered with an old replayed expiry. The contract declares no Idempotency-Key for fenced calls; the derived key is harmless (1–128 printable ASCII).
- **Reporting.** A result call is retried with back-off until the lease expires. After that the worker gives up and logs it: the server lets the lease expire and reuses the same operationId (T36).
- **Subscriptions.** `subscribe` and `unsubscribe` work while the worker runs.
- **Logging.** `Logger.log(level, event, message, fields, error)`. The default is one JSON object per line from `consoleJsonLogger()`. Lines produced for a work item always carry `workerId`, `workId`, `topic`, `operationId`, `attemptId`, `lockVersion`, `businessKey` and `processInstanceId`.

### Example: P1 bill payment

`examples/bill-payment-worker.ts` handles the `payment` topic of the P1 bill model (`wf1_payment`). Its environment variables:

- `SANGAM_URL`
- either `SANGAM_TOKEN`, or `SANGAM_OIDC_TOKEN_URL` + `SANGAM_CLIENT_ID` + `SANGAM_CLIENT_SECRET` (+ `SANGAM_SCOPE`)
- `SANGAM_TOPIC`
- `SANGAM_WORKER_ID`
- `TREASURY_URL` (unset = built-in simulator)

It reads `amount` as an exact Decimal and returns `paidAmount` as a typed Decimal. It sends `Idempotency-Key = operationId` to the treasury, so a retry never pays twice. Failures map as follows:

- A refused connection is sent as `fail`; nothing reached the treasury, so a retry is safe.
- A timeout or a 5xx is sent as `outcome-unknown` (AC-03).
- A 400 or 422 is sent as the BPMN error `PAYMENT_REJECTED`.

Run it with `npm run example:bill-payment`.

## Requirements covered, by file

| File | Requirement ids |
|---|---|
| `src/models.ts` | sangam-api.yaml 0.1.0 schemas; VI.3 (ExternalWork, LockState, OutcomeUnknownResult), A-2, R36, A-16 (Page), VI.4 rule 5 (payloadHash), VI.4 rule 6 (no tenant in requests), VI.5 (TerminateRequest / TerminationResult), R38 (CloudEvent), R41 (Replayable) |
| `src/typed.ts` | R33 (TypedValue builders and unwrapping; exact decimals, no binary floats) |
| `src/http.ts` | R41 (Idempotency-Key on every mutation, `Idempotent-Replayed`), IX.5 rules 1 and 4 (seat headers), R28 (retry with jitter), RFC 9457 problem+json, R20 (409 STALE_LEASE → `StaleLeaseError`), R23 (410 → `GoneError`) |
| `src/errors.ts` | R20, R23, VI.3.2 rule 2 |
| `src/auth.ts` | ARCHITECTURE §4 auth (OIDC bearer, dev static token); FC-37 parity (basic auth) |
| `src/api.ts` | ARCHITECTURE §4 / sangam-api.yaml (typed API surface); FC-36 (consumer of the Sangam worker REST API); A-16 (capped counts); VI.3.2 rule 4 (outcome-unknown, reconciliation resolve); VI.5 (cancel with reason); R63 (ops repairs); R38 (CloudEvents) |
| `src/client.ts` | R18 / ADR-D-4 (Tier-1 TypeScript SDK) |
| `src/worker.ts` | R9 / VI.3 (lease lifecycle, heartbeat at lease/3, cancelRequested, release), A-1 (start only after LOCKED), A-2 (operationId vs attemptId), R20 / D-17 (fencing on every call, abandon on 409), D-18 (unlock carries owner + lockVersion), R36 (operationId forwarded as idempotency key), R41, R28 (no blind retries of irreversible effects, back-off), D-40 (long-poll fetch with back-off), AC-03 (outcome-unknown), R33 (typed getters), FC-37 / D-44 (worker client beyond Java) |
| `src/logger.ts` | ARCHITECTURE §1.8 (JSON logs with operationId and businessKey) |
| `examples/bill-payment-worker.ts` | IX.4 P1, AC-01, AC-03, R36, R33 |

### Flowable gap list ids (spike/results/FLOWABLE-INTO-OPERATON.md)

| Id | How this SDK covers it |
|---|---|
| FC-36 | Client of the Sangam worker REST API (`WorkApi`), which follows the VI.3 states, lockVersion and attemptId |
| FC-37 | Official worker client for TypeScript/Node, and parity with the Flowable external-worker clients: topic subscribe and unsubscribe at runtime, configurable workerId, number of tasks and lock duration per topic, topic filters, complete / fail (retries + retry timeout) / bpmn-error results, polling with back-off, and bearer or basic auth |
| D-17 | Fencing: `lockVersion` and `attemptId` go on every call, and a 409 makes the worker abandon the item |
| D-18 | `unlock` carries `workerId` + `lockVersion` + `attemptId`, so the server can check the owner |
| D-40 | Long-poll `fetch-and-lock` with an async timeout, and the client waits with back-off |
| D-44 | The worker client is no longer Java-only |

**Design reference.** The structure follows the Flowable external-worker clients (`flowable-external-client-{js,python,...}`, Apache-2.0). No Flowable code was copied, so no NOTICE entry is needed.

## Integration notes and gaps

- **Reconciliation.** The SDK is reconciled against `sangam-rest/api/sangam-api.yaml` 0.1.0, including `outcome-unknown` and `cancel-ack`, which are in the contract.
- **Endpoints without typed wrappers.** These are reachable through `ResourceApi.action` / `getSub` or `client.transport`: task comments and attachments, activity tree, modify, migrate, packages, documents, links, history and batches.
- **Admin and ops bodies.** These are JSON objects (`ResourceApi`). Only incident retry/resolve, job retries and outbox requeue/skip have typed request bodies.
- **Tests.** The test engine (the R18 Tier-1 "test engine") and contract tests T01–T04 / T18 are not written yet, because tests come last.
- **`cmmnTerminate`.** The Flowable CMMN worker result `cmmnTerminate` is not offered. In Sangam a CMMN external worker is modelled as processTask → BPMN external task (FC-11 pattern).
