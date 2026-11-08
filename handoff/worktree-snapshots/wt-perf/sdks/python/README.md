# Sangam Python SDK (`sangam_sdk`)

Typed REST client for the Sangam API (`/api/v1`) and an external-worker runtime implementing the VI.3 lease
lifecycle. Python 3.10+, **zero runtime dependencies** (HTTP is `urllib.request`), fully typed (`py.typed`).

Tier-1 SDK per ADR-D-4 / R18. **Contract: reconciled against `sangam-rest/api/sangam-api.yaml` 0.1.0**
(OpenAPI 3.1, WS-REST), the same wire contract as the Java, .NET, TypeScript and PHP SDKs.

## Install

```
pip install ./sangam/sdks/python
```

## Client

```python
from sangam_sdk import SangamClient, ClientCredentialsTokenProvider

client = SangamClient(
    "https://sangam.example.gov.in",
    token_provider=ClientCredentialsTokenProvider(
        "https://idp/realms/sangam/protocol/openid-connect/token", "bill-worker", secret),
    seat="SEAT-DDO-0042",                      # X-Sangam-Seat (acting_for_seat= -> X-Sangam-Acting-For-Seat)
)
pi = client.processes.start("p1_bill", "BILL-2026-0001", {"amount": Decimal("125000.00")},
                            idempotency_key="start:BILL-2026-0001")   # pi.idempotent_replayed
page = client.tasks.list(candidate_group="dds-checker", mine=False, size=50)   # page.total_count_capped (A-16)
res = client.tasks.complete(page.items[0].id, payload_hash=h, decision="APPROVE", remarks="ok")
client.decisions.evaluate("p1_amount_band", {"amount": Decimal("125000")}, purpose="AUTHORITY")
client.audit.timeline("BILL-2026-0001", include_history=True)          # Page[AuditEntry]
client.ops.resolve(item_id, "CONFIRM_SUCCESS", evidence="bank statement UTR123",
                   result={"paymentReference": "UTR123"})
client.repository.publish("bank-ack", source="urn:bank", type="payment.acknowledged", data={...})
client.me()
```

Groups: `work`, `tasks` (list/get/variables/claim/unclaim/complete/return/delegate), `processes`
(start/list/get/variables/cancel, messages, signals, cases), `decisions`, `audit` (timeline, verify), `admin`
(org-units, seats, assignments + `end_assignment`, delegations + `revoke_delegation`, policies, tenants,
`resource(name)`), `ops` (incidents + `retry_incident`, reconciliation + `resolve`, outbox + `requeue_outbox` /
`outbox_stats`, jobs + `set_job_retries`; any other action through `ResourceApi.action`), `repository`
(multipart deployments with `files` parts, event channels/definitions, CloudEvents publish, forms), and `me()`.

**Typed values (R33).** Variables are a VariableMap; `encode_variables` sends `Decimal` as
`{"type":"Decimal","value":"12.50"}` (never a float or bare string), large `int` as BigInteger, `datetime` as
Date, `date` as LocalDate, `bytes` as Bytes (base64); `TypedValue.decimal/big_integer/long/date/local_date/bytes/json`
build them explicitly. `decode_value` / `ctx.variable()` unwrap them.

Every mutation sends `Idempotency-Key` (yours, or a uuid4 per logical call reused across transport retries).
Errors are `application/problem+json` (`type` = `https://sangam.dev/problems/<kebab-code>`, stable `code`;
unknown codes are accepted): `StaleLeaseError` (409 STALE_LEASE), `GoneError` (410 GONE_INSTANCE_DELETED),
`SangamApiError` (others, `.problem.code`), `TransportError`. Result objects expose `idempotent_replayed`
(`Idempotent-Replayed` header). One token refresh on 401; safe calls retry with jitter and honour
`Retry-After`.

Auth: `StaticTokenProvider` (dev static token), `ClientCredentialsTokenProvider` (OIDC client credentials,
cached, single-flight refresh), `BasicAuthProvider` (dev gateways), `NoAuth`.

## Worker

```python
from sangam_sdk import Worker, BpmnError, OutcomeUnknown

worker = Worker(client, max_concurrency=4)

@worker.topic("payment", lock_duration_ms=120_000, retries=3, irreversible=True)
def pay(ctx):
    ctx.throw_if_cancelled()
    ref = treasury.pay(ctx.decimal("amount"), idempotency_key=ctx.operation_id)
    return {"paymentReference": ref}

worker.run()          # blocks; SIGTERM/SIGINT -> graceful stop
```

| Handler does | Worker sends |
|---|---|
| returns a dict / None | `POST /work/{id}/complete` with the variables |
| raises `BpmnError(code, msg, vars)` | `/bpmn-error` |
| raises `OutcomeUnknown(reason, details, vars)` | `/outcome-unknown` with `detail` = reason + details, `evidence` = vars (202, reconciliation item id logged) |
| raises `TaskFailed(msg, details, retries, retry_timeout)` | `/fail` with the overrides |
| raises `TaskCancelled` after a server cancel request | `/cancel-ack` |
| raises `TaskCancelled` otherwise (shutdown) | `/unlock` |
| raises anything else | `/fail` (retries - 1, back-off `retryTimeoutMs`); **`/outcome-unknown` if the subscription is `irreversible=True`** (the contract has no `effect` field) |
| lease lost (409/410 or expiry while unreachable) | nothing: result discarded, `ctx.cancel_event` set |

Subscriptions also take `local_variables`, `process_definition_key` and `business_key` (TopicRequest filters);
`Worker(use_priority=True)` sets `usePriority` on fetch. The heartbeat reads `LockState`
(`id, lockVersion, lockExpiresAt, cancelRequested`).

Runtime behaviour: long-poll fetch for free slots only; A-1 check (no handler without `attemptId`);
overdelivered or unsubscribed items are unlocked at once; heartbeat at lease/3 with `cancelRequested`; worker
Idempotency-Keys `<operationId>:<action>:<lockVersion>` (heartbeat `...:extend-lock:<lockVersion>:<seq>`);
result calls retried until the lease expires; `stop(timeout)` waits for in-flight work, then signals
cancellation and unlocks; `subscribe`/`unsubscribe` at runtime.

Python threads cannot be interrupted: cancellation is cooperative (`ctx.throw_if_cancelled()`,
`ctx.cancel_event.wait(...)`).

Logging: stdlib logger `sangam_sdk`; each record has `extra={"sangam_event", "sangam_fields"}` (workerId,
workId, topic, operationId, attemptId, lockVersion, businessKey). Pass `SangamLogger(hook=fn)` to route lines
elsewhere.

Money: JSON numbers with a fraction are decoded as `Decimal`; outgoing `Decimal` is always a TypedValue (R33).

## Example

`examples/bill_payment_worker.py`: P1 bill payment on topic `payment` (`wf1_payment`). Env: `SANGAM_URL`,
`SANGAM_TOKEN` or `SANGAM_OIDC_TOKEN_URL`/`SANGAM_CLIENT_ID`/`SANGAM_CLIENT_SECRET`/`SANGAM_SCOPE`,
`SANGAM_TOPIC`, `SANGAM_WORKER_ID`, `TREASURY_URL` (unset = simulator).

## Requirements covered, file by file

| File | Requirement ids |
|---|---|
| `src/sangam_sdk/worker.py` | R9 / VI.3.1–VI.3.2 (lease lifecycle, heartbeat lease/3, cancel-ack, unlock), A-1 (start only after LOCKED), A-2 (operationId vs attemptId), R20 / D-17 (lockVersion fencing, 409 abandon), D-18 (unlock carries workerId + lockVersion), R36 (operationId forwarded as idempotency key), VI.3.2 rule 5 / R28 (no blind retry for irreversible; jittered retry back-off), AC-03 (outcome-unknown), D-40 (long poll + back-off), FC-36 (worker API client), FC-37 / D-44 (Python worker client) |
| `src/sangam_sdk/http.py` | R41 (Idempotency-Key on every mutation), R28 (jittered retries, Retry-After), RFC 9457 problem+json, R20 (409 → `StaleLeaseError`), R23 (410 → `GoneError`) |
| `src/sangam_sdk/api.py` | ARCHITECTURE §4 endpoints (work, tasks, processes, messages, signals, cases, decisions, audit, admin, ops, deployments, events, forms), A-16 (`X-Total-Count`, `X-Total-Count-Capped`), VI.4 rule 5 (`payloadHash`), VI.5 (cancel reason) |
| `src/sangam_sdk/models.py` | sangam-api.yaml 0.1.0 schemas: VI.3 `ExternalWork` / `LockState` / `OutcomeUnknownResult`, VI.4 rule 5 `CompleteTaskResult`, VI.5 `TerminationResult`, R42 / AC-01 `AuditEntry`, R16 `CloudEvent` / `PublishResult`, reconciliation items |
| `src/sangam_sdk/typed.py` | R33 TypedValue / VariableMap encoding and decoding (E18, T18) |
| `src/sangam_sdk/auth.py` | ARCHITECTURE §4 auth (OIDC bearer, dev static token), FC-37 (Basic parity) |
| `src/sangam_sdk/log.py` | structured logging hook (R13 JSON logs) |
| `src/sangam_sdk/client.py` | R18 / ADR-D-4 Tier-1 SDK entry point; IX.5 rules 1 and 4 (`X-Sangam-Seat`, `X-Sangam-Acting-For-Seat`); `GET /me` |
| `examples/bill_payment_worker.py` | P1 (IX.4), AC-01, AC-03, R36 |

Flowable gap ids: FC-36, FC-37, D-17, D-18, D-40, D-44. The structure follows the Flowable external-worker
clients (subscribe/unsubscribe, result builders) as a **design reference only; no code was copied**, so no
Flowable NOTICE text is required.

## Third-party dependencies

None at runtime. Build backend: setuptools (MIT).

## Integration notes / gaps

- Reconciled with `sangam-api.yaml` 0.1.0 (contract deltas 1–17 applied). Endpoints outside the SDK's typed
  surface (history, packages, documents, links, batches, migrations, analytics, forms drafts, ad-hoc/inject) are
  reachable only through `client.transport.execute(ApiRequest(...))` or `ResourceApi`.
- Work endpoints are fenced and declare no Idempotency-Key; the SDK still sends its derived key (valid per the
  header pattern, harmless).
- A long-poll in flight cannot be aborted by `urllib`; `stop()` does not wait for it (daemon poller), and any
  items it returns after stop are unlocked.
- No in-memory test engine yet (R18 Tier 1 asks for one); contract tests T01–T04/T18 are not written.
