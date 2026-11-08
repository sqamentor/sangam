# sangam-integration (WS-INTEGRATION)

This module covers every external effect Sangam has: the outbox relay, the inbox, API idempotency, the operation ledger, reconciliation, cancel/delete/erase with tombstones, the connector catalogue with simulators, the publishers, and the Operaton bridge.

Base package: `org.sangam.integration`. The connector SPI lives in `sangam-core`, package `org.sangam.core.integration`, so packs can implement connectors without depending on this module.

**Status: complete, compiling code. Nothing has been tested yet** (owner phase rule: code first, testing last). Build with:

```
mvnw.cmd -q -B -DskipTests -pl sangam-integration -am install
```

## 1. Exactly-once by contract: how one external call flows

1. **Lease.** A BPMN service task is either an external task (`ConnectorExternalTaskWorker`, the preferred path) or an async JavaDelegate (`SangamConnectorDelegate`). External tasks are leased through the core `EngineAdapter`, with lockVersion and attemptId fencing.
2. **Intent.** `ConnectorInvoker` records the intent before dispatch (R37):
   - writes `OPERATION_LEDGER.recordIntent`, where operationId = UUIDv5(tenant, root, activityInstanceId, loopCounter[, retrySafeKey]) (R36);
   - writes the `CONNECTOR_CALL` journal and the `OPERATION_DISPATCHED` audit.
3. **Settled operations are never re-dispatched.** A repeat attempt follows A-2:
   - (a): dispatch again with the same operationId;
   - (b): look up first;
   - (c): only after a proven `NO_EFFECT`; otherwise UNKNOWN_OUTCOME.
4. **Dispatch.** The connector runs outside any transaction. operationId is sent downstream as the idempotency key or transaction id.
5. **Outcome.** The outcome is recorded with lockVersion fencing:
   - success or business failure goes to the ledger;
   - an unknown outcome triggers one lookup for (a)/(b), or else `markUnknown`, which opens a reconciliation item.

   The worker then does one of: complete, raise a BPMN error, fail with retries, or hold (`retries=0`, which raises an incident).
6. **Lease expiry.** `LedgerExpiryPolicy` (A-10) runs in the same engine transaction. For class (a) and (b) it re-offers the work; for class (c) it holds it and opens UNKNOWN_OUTCOME. This is the fix for the measured double payment.
7. **Reconciliation.** `ReconciliationEngine` looks the operation up and resolves it as confirm, mark-failed or retry-same-operation, or escalates it to a human task. It then releases the held work (`OperatonHeldWorkReleaser`). The released attempt replays the settled ledger outcome.

## 2. Requirements covered, file by file

Paths are under `src/main/java/org/sangam/integration/` unless marked *core*.

### Connector SPI (*core*: `sangam-core/src/main/java/org/sangam/core/integration/`)

| File | Requirements |
|---|---|
| `SangamConnector.java` | R71 connector SDK; A-2 / A-8 capability class; R36 operationId forwarding; R27 rule statement |
| `ConnectorDescriptor.java`, `ConnectorProfile.java` | R71 service registry entry; ARCHITECTURE §1.9 air-gapped simulator profile |
| `ConnectorRequest.java`, `ConnectorResult.java`, `LookupResult.java` | A-2 three-way classification (Success / BusinessFailure / TransientFailure(noEffect) / OutcomeUnknown); outcome lookup for (a)/(b); R33 typed input |

### Connector core (`connector/`)

| File | Requirements |
|---|---|
| `ConnectorInvoker.java` | VI.3 rules 4–5, R36, R37, A-2, A-8, R28 (no blind retry of a non-idempotent effect); audit `OPERATION_DISPATCHED` / `OPERATION_OUTCOME` / `OPERATION_UNKNOWN` (X.2.3) |
| `ConnectorCallJournal.java` | R37 durable call intent with provider handles; proof of `NO_EFFECT`; VI.5 erasure of request data |
| `ConnectorRegistry.java` | R71 / PF-39 service registry, with per-tenant override |
| `Connectors.java` | R71 built-in catalogue for the SIMULATOR profile |
| `EgressPolicy.java` | R27 / T23 / T47: tenant allow-list; private, metadata and CGNAT ranges blocked; DNS pinning; redirect limit; bounded responses |
| `HttpTransport.java` | R27 per-hop egress; timeouts bounded by the lease deadline; mTLS; A-2 failure classification (NoEffect vs Unknown); R28 retry with jitter |
| `RetryPolicy.java`, `TlsConfig.java`, `SecretResolver.java`, `Json.java` | R28 `R3/PT1M` with jitter; mTLS; credentials by reference (R73 rule); R33 BigDecimal JSON |

### Generic connectors

| File | Requirements |
|---|---|
| `connector/http/HttpRestConnector.java` | R71 REST; **FC-06 HTTP task**; B-16 connect-plugin parity; mTLS option; Idempotency-Key = operationId; lookup GET (class a/b) |
| `connector/http/SoapConnector.java` | R71 SOAP; **FC-50** CXF web-service task; WS-Addressing MessageID = operationId; XXE-safe parsing; fault → business failure or unknown |
| `connector/mail/SmtpEmailConnector.java` | R71 e-mail; **FC-07 mail task**; R60 gov.in e-mail adapter; Message-ID = operationId; STARTTLS required; A-2 SMTP classification |
| `connector/file/FileTransport.java`, `LocalDirectoryTransport.java` (ported) | R58 file interface; atomic write |
| `connector/file/SftpTransport.java` | R71 SFTP; R58 SFTP; pinned host key; key or password authentication; atomic rename; R27 egress |
| `connector/file/FileDropConnector.java` | R71 SFTP/file drop; class (a) through a deterministic name and presence lookup |

### Government connectors (`connector/gov/`)

| File | Requirements |
|---|---|
| `IfmsJitConnector.java` | R58, IX.4 P1, GOV-8: IFMS JIT PI/PD/FD; token caching; class (a) by default |
| `PfmsFileConnector.java`, `PfmsStatusPoller.java` | R58: signed payment files out (HMAC or RSA `.sig`), status files in; class (a) via a deterministic batch id; IX.4 P1/P3 |
| `PaymentAcknowledgement.java`, `BankAcknowledgementFeed.java` | IX.4 P1 bank/treasury acknowledgement; P3 DBT; normalised feed |
| `EsignConnector.java` | R57, GOV-7: CCA eSign 2.1; signature bound to the approval payload hash (VI.4 rule 5); class (b) |
| `DigiLockerPushConnector.java` | R59, GOV-9: issuer push; idempotent by URI; class (a); IX.4 P4 |
| `DltSmsConnector.java`, `DltRegistry.java` | R60, GOV-10: per-tenant DLT registry (entity, header, template); template and URL validation before send; class (c) |
| `PaymentGatewayConnector.java`, `PaymentGatewayCallbackVerifier.java` | R58 gateway adapters (NTRP/PayGov/GRAS shape); R27 callback authentication (HMAC, timestamp, replay guard); IX.4 P4 fee paid but callback lost |
| `GovConnectors.java`, `GovHttp.java`, `GovXml.java` | Wiring for the LIVE and SIMULATOR profiles; shared outcome mapping; hardened XML |

### Simulators (`sim/`, ported from `spike/simulators`, plus new ones)

| File | Requirements |
|---|---|
| `Fault`, `FaultInjector`, `EffectsLedger`, `SimHttpServer`, `Xml` | Fault injection (delay, drop response = UNKNOWN_OUTCOME, duplicate, error); effect ledger for "no double payment" checks |
| `ifms/IfmsJitSimulator`, `pfms/PfmsSftpSimulator`, `pfms/PfmsFiles`, `esign/EsignSimulator`, `digilocker/DigiLockerPushSimulator`, `dlt/DltSmsSimulator` | Proven spike simulators (IX.7d/IX.7e GOV-7…GOV-10) |
| `rest/RestEchoSimulator`, `soap/SoapEchoSimulator`, `pay/PaymentGatewaySimulator`, `mail/SmtpSinkSimulator` | New simulators for the REST, SOAP, payment-gateway (signed callback) and SMTP connectors |
| `SimulatorProfile.java` | One-stop SIMULATOR profile for air-gapped development (§1.9) |

### Outbox relay (`relay/`)

| File | Requirements |
|---|---|
| `OutboxRelayService.java` | VI.2.3, R4, R1, A-5: scheduled loop; multi-node via skip-locked epochs; parallel publish; poison → PARKED/SKIPPED immediately; exhausted → DLQ; per-destination deferral without spending attempts; audited requeue/skip |
| `PublisherRegistry.java` | Destination → publisher routing (exact or prefix). **Plug-in point for WS-PORTS channels** (FC-02/FC-03) |
| `PermanentPublishException.java` | VI.2.3 rule 5 poison classification |
| `DeadLetterStore.java` | VI.2.3 rule 5 PARKED/SKIPPED dead letters; VI.2.6 rule 3 dead-letter channel; `/ops/outbox` work list; erasure |
| `CircuitBreaker.java` | Destination back-pressure; a webhook outage does not park the backlog |
| `WakeupNotifier.java` | R1: one payload-free NOTIFY per cycle (PostgreSQL `pg_notify`); the poll stays authoritative; D-40 |
| `OutboxCapacityMonitor.java`, `BackpressureGate.java` | VI.2.4, R40, A-5: per-dialect size probe; 50/80/95 % levels; `503 BACKPRESSURE` by command category; λ, μ and drain time B/(μ−λ) |
| `OutboxRetention.java` | VI.2.5: a day is removed only when all its deliveries are DONE/SKIPPED; alert for overdue open rows |
| `LeaderElection.java` | R29 leader-elected cleanup (DB-clock lease, epoch) |
| `OutboxWriter.java` | VI.2.2 write side in the command transaction; **FC-02 send event**; FC-25/FC-43 typed events; `SubscriptionResolver` for WS-PORTS |

### Publishers (`publish/`)

| File | Requirements |
|---|---|
| `CloudEvents.java` | VI.2.3 rule 2: CloudEvents `id` = event_id; per-root order extension |
| `HttpWebhookPublisher.java` | R71 webhook (SL-39): HMAC-SHA256 over `t.body` with the subscription secret from `SecretResolver` (rotation: several `v1`), `Idempotency-Key` = event id, egress/SSRF guard, 410 disables, 429/503 `Retry-After` defers without an attempt, auto-disable hold then dead letter |
| `WebhookSignature.java` | shared sender/receiver signature scheme; constant-time compare, timestamp tolerance (default 5 min) |
| `WebhookHealth.java` | SL-39 destination health in `INTEGRATION_WEBHOOK_STATE`: consecutive failures, auto-disable (audited `WEBHOOK_DISABLED`), re-enable (`WEBHOOK_ENABLED`) |
| `KafkaPublisher.java` | R71 Kafka; **FC-03**; idempotent producer; key tenant:root for per-root order (optional dependency) |
| `JmsPublisher.java` | R71 JMS/AMQP; **FC-03**; transacted persistent send; JMSXGroupID per root (optional dependency) |

### Inbox (`inbox/`)

| File | Requirements |
|---|---|
| `InboxService.java` | VI.2.6, R38, T09, T35: receive and commit before the broker ack; skip-locked processing; per-message transaction; BUFFERED / REJECTED / EXPIRED; dead letter; back-pressure (R40). **Plug-in point for WS-PORTS** (`receive`, `registerHandler`) |
| `ChannelBinding.java` | VI.2.6 rule 5 / VI.4 rule 6: tenant only from the binding or the authenticated source |
| `InboundMessage.java`, `InboundMapper.java` | FC-03 inbound pipeline shape (key/tenant detectors, CloudEvents) |
| `EventDefinitionPolicy.java` | VI.2.6 rule 3 buffer / reject / start-new, TTL; **FC-04** uniqueStart; **D-33** early messages buffered |
| `UniqueStartGuard.java` | VI.2.6 rule 4, FC-04, T35: one start per (tenant, definition, business key) |
| `InboxHandler.java`, `CorrelatingInboxHandler.java`, `MessageCorrelationPort.java` | **FC-02** inbox → message correlation / start; D-33 |
| `KafkaInboxConsumer.java`, `JmsInboxListener.java` | **FC-03** inbound channels; ack only after the DB commit; pause/rollback under back-pressure; **D-45** |
| `WebhookInboxReceiver.java` | R71 webhook inbound; R27 authenticated callbacks (HMAC, timestamp tolerance, replay via inbox dedup) |
| `PaymentAcknowledgementIngestor.java` | IX.4 P1/P3/P4 bank acknowledgement → inbox → correlation; R58 |

### Idempotency (`idempotency/`)

| File | Requirements |
|---|---|
| `IdempotencyService.java` | X.2.2, R41, T01, T55–T60: profile check; claim; `Prefer: wait`; P-1; outcome classification; replay with re-authorization and masks; compact replay; metrics (X.2.2.7 rule 4) |
| `IdempotencyServletFilter.java` | **HTTP filter contract** for WS-REST: cached body, buffered response, replay/refusal without calling the handler |
| `IdempotencyContext.java` | X.2.2.4 step 2: complete as the last write (L6) in the engine transaction |
| `IdempotentRequest.java`, `IdempotentResponse.java` | X.2.2.2 fingerprint input (If-Match and acting-for-seat included); RFC 9457 problem+json; X.2.2.6 error codes and headers |
| `IdempotencyProfile.java` | X.2.2.1 government profile (key required) |
| `ReplayAuthorizer.java` | X.2.2.4 step 4 (WS-SECURITY port) |

### Ledger and reconciliation

| File | Requirements |
|---|---|
| `ledger/OperationLedgerService.java` | VI.3 rules 4–5, R36, R37: identity; views; open operations for the VI.5 audit; worker `outcomeUnknown` |
| `ledger/LedgerExpiryPolicy.java` | **A-10** ExpiryPolicy; A-8; VI.5 rule 4; **D-20 / D-21** (measured double payment) |
| `reconciliation/ReconciliationEngine.java` | VI.3 rule 4, VI.5 rule 3, OJ-02/OJ-03, R58, T02/T27/T36/T39: lookup → auto-resolve, or escalate to a human task; class (b) not-found confirmation; back-off; multi-node claim |
| `reconciliation/ReconciliationTaskPort.java`, `HeldWorkReleaser.java` | Human-task escalation port; release of held work |

### Cancel / delete / erase (`lifecycle/`)

| File | Requirements |
|---|---|
| `InstanceLifecycleService.java` | VI.5, R23, A-4, T10/T11/T39: separate cancel and delete permissions; reason; CANCEL_REQUESTED tombstones; `INSTANCE_CANCELLED` / `INSTANCE_DELETED` audit with open operation ids; late result → 410 + `LATE_RESULT_AFTER_DELETE`; heartbeat `cancelRequested`; cancel ack; expiry sweep and purge |
| `ErasureService.java` | VI.5.1 erasure; X.2.2.4 step 5; X.2.3.8; GOV-4 DPDP: scrubs engine, outbox, inbox, idempotency, journal, DLQ, audit payloads and documents; keeps metadata and open reconciliation items; refused under legal hold |
| `LegalHoldStore.java` | VI.5.1 legal holds; R29; **R61** (Public Records Act) |
| `LifecyclePorts.java` | Ports for open work, engine cancel/delete, data eraser, document eraser (R74), authorizer |

### Operaton bridge (`bridge/`)

| File | Requirements |
|---|---|
| `ConnectorExternalTaskWorker.java` | VI.3 lease protocol (fencing, heartbeat at lease/3, hold on unknown); BPMN service task → connector with operationId; **FC-06 / FC-07** as external work; VI.5 late result |
| `TopicBinding.java`, `WorkContextResolver.java` | Per-topic lease (VI.3.2 rule 3, max 24 h); R28 retries; tenant from engine identity |
| `operaton/SangamConnectorDelegate.java` | JavaDelegate bridge (FC-06/FC-07, B-16) with operationId; intent committed before the call |
| `operaton/SendEventDelegate.java` | **FC-02** send-event task → outbox in the engine transaction |
| `operaton/OutboxHistoryEventHandler.java`, `SangamIntegrationEnginePlugin.java` | **FC-25 / FC-43**: history → typed outbox events; ADR-D-16 plugin |
| `operaton/OperatonMessageCorrelationPort.java` | FC-02, D-33: correlate by business key or `sangamCorrelationKey`; start by key |
| `operaton/OperatonHeldWorkReleaser.java` | A-10 HOLD release (`setRetries` / `setJobRetries`) |
| `operaton/OperatonLifecyclePorts.java` | VI.5: open work with lease rows, business cancel (`sangam.cancel` message or delete with listeners), technical delete, variable erasure |
| `operaton/IntegrationBridge.java`, `EngineContext.java` | Wiring holder; command-context JDBC connection (Q1) |

### Other

| File | Requirements |
|---|---|
| `IntegrationMaintenance.java` | R29 leader-elected cleanup; VI.2.4 sampling; X.2.2 step 5; VI.5 rule 4 sweep |
| `schema/IntegrationSchema.java` | Reference DDL of this module's tables (see §4) |

### Flowable gap-list items (`spike/results/FLOWABLE-INTO-OPERATON.md`) covered here

| Item | Where | Notes |
|---|---|---|
| FC-02 | `SendEventDelegate`, `OutboxWriter`, `InboxService`, `CorrelatingInboxHandler`, `OperatonMessageCorrelationPort` | Event send and receive. WS-PORTS owns the registry and the `sangam:eventType` parse listener |
| FC-03 | `KafkaPublisher`, `JmsPublisher`, `KafkaInboxConsumer`, `JmsInboxListener`, `InboundMapper`, `ChannelBinding` | — |
| FC-04 | `UniqueStartGuard`, `EventDefinitionPolicy.uniqueStart` | — |
| FC-06 | `HttpRestConnector` + worker / delegate | — |
| FC-07 | `SmtpEmailConnector` + worker / delegate | — |
| FC-25, FC-43 | `OutboxHistoryEventHandler` | — |
| FC-50 | `SoapConnector` | — |
| D-20, D-21 | `LedgerExpiryPolicy` | — |
| D-33 | Inbox buffering | — |
| D-40 | `WakeupNotifier` | — |
| D-45 | Kafka/JMS adapters | — |

Not here: **FC-08** (Camel task, stage E) is a gap. **D-17, D-18 and D-19** are owned by WS-ENGINE (lease table, owner check, SKIP LOCKED fetch); this module consumes their fencing.

## 3. Third-party dependencies added

| Dependency | Version | Licence | Scope |
|---|---|---|---|
| org.eclipse.angus:angus-mail (+ jakarta.mail-api, jakarta.activation) | 2.0.4 | EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0 | compile |
| org.apache.sshd:sshd-sftp (+ sshd-core, sshd-common) | 2.15.0 | Apache-2.0 | compile |
| org.apache.kafka:kafka-clients | 3.9.2 | Apache-2.0 | optional |
| jakarta.jms:jakarta.jms-api | 3.1.0 | EPL-2.0 OR GPL-2.0 WITH CPE | optional |
| jakarta.servlet:jakarta.servlet-api | 6.0.0 | EPL-2.0 OR GPL-2.0 WITH CPE | provided |
| org.operaton.bpm:operaton-engine | 2.2.0 (BOM) | Apache-2.0 | optional (provided by sangam-engine) |

## 4. Integration notes

### Tables for WS-ENGINE (sangam-persistence Liquibase)

Reference DDL is in `schema/IntegrationSchema.java`.

New tables:
- `CONNECTOR_CALL`
- `INTEGRATION_DEAD_LETTER`
- `INTEGRATION_LEADER`
- `INTEGRATION_WEBHOOK_STATE` (SL-39 webhook destination health)
- `UNIQUE_START`
- `RECONCILIATION_ATTEMPT`
- `LEGAL_HOLD`

New indexes on core tables:
- `OPERATION_LEDGER(activity_instance_id, state)`
- `TOMBSTONE(task_id, state)`
- `OUTBOX(created_at)`
- `INBOX(tenant_id, correlation_key)`
- `IDEMPOTENCY_RECORD(tenant_id, resource_ref)`

The core tables are used exactly as in `org.sangam.core.layer.Schema`: OUTBOX, OUTBOX_DELIVERY, INBOX, IDEMPOTENCY_RECORD, OPERATION_LEDGER, RECONCILIATION_ITEM, TOMBSTONE, ROOT_SEQ and AUDIT_*. Partitioning of OUTBOX by day is optional (VI.2.5); `OutboxRetention` is the portable path.

### Wiring for sangam-server

Transactions:
- **The inbox and the lifecycle services must use `Transactions.of(engineAdapter)`**, so that the correlation, cancel or delete and the product rows commit together.
- `ConnectorInvoker` for the external-task worker can use either transaction type.
- The invoker inside `IntegrationBridge` (used by delegates) must use `Transactions.of(dataSource)`, so the intent commits before the remote call.
- `InstanceLifecycleService` assumes the Operaton adapter's `inEngineTransaction` reuses the command context, so that the nested `RuntimeService` call joins the same transaction.

Install the A-10 expiry policy:

```java
engineAdapter.setExpiryPolicy(new LedgerExpiryPolicy(ledgerService))
```

Register the engine plugin:

```java
new SangamIntegrationEnginePlugin(new OutboxHistoryEventHandler(outboxWriter, defaultTenant))
```

Then call `IntegrationBridge.install(...)` at start-up.

Start these services:
- `OutboxRelayService`
- `InboxService`
- `ReconciliationEngine`
- `IntegrationMaintenance`
- `ConnectorExternalTaskWorker` (one per worker node)

### Ports other workstreams implement

- `ReplayAuthorizer`: WS-SECURITY (VI.4 object check without cache, plus field masks).
- `LifecyclePorts.Authorizer`: WS-SECURITY (`CANCEL_INSTANCE`, `DELETE_INSTANCE`, `ERASE_DATA`).
- `ReconciliationTaskPort`: WS-PACKS or WS-ENGINE. It starts a reconciliation-workbench process whose user task calls `ReconciliationEngine.resolveManually`.
- `LifecyclePorts.DocumentEraser`: owner of the R74 document store.
- `IdempotencyServletFilter.CommandResolver` / `IdentityResolver`: WS-REST. Handlers call `IdempotencyContext.current().ifPresent(ctx -> ctx.complete(c, status, bodyBytes, resourceRef))` as the last write in their engine transaction, with the exact bytes they return.
- `BackpressureGate.check(Category.START | INBOUND_EVENT | ...)`: WS-REST, before commands.

### WS-PACKS (`packs/`)

This module depends on `sangam-packs` as an optional dependency. There is no cycle, because packs depends only on sangam-core.

| File | What it does |
|---|---|
| `IntegrationConnectorGateway.java` | The real `org.sangam.packs.port.ConnectorGateway`: `submit`, `query`, `lookup`, `cancel`, `openReconciliation`, `capabilityOf` |
| `PackOperationStore.java` | New table `PACK_OPERATION` (DDL in its `DDL` field). It holds the first result per operationId and payload hash, and maps to the ledger id |
| `PackDispatchPublisher.java` | Relay publisher for `pack-dispatch`. It calls the connector through `ConnectorInvoker` and delivers the result |
| `PackMessages.java`, `PackMessageInboxHandler.java` | Deliver the README §4 messages through the inbox, correlated on the pack variables (see the list below) |
| `PackReconciliationHooks.java` | Refuses a retry for class (c); delivers `sangam.reconciliationResolved` (CONFIRMED_SUCCESS / CONFIRMED_FAILURE / RETRY_SAME_OPERATION) |
| `PackConnectorBindings.java` | Maps pack connector ids to registry connectors, with an optional payload mapper |
| `PacksIntegration.java` | One-call wiring; installs the gateway into `PackRuntime` |

Messages and the pack variable each one is correlated on:
- `sangam.operationResult` (resultStatus, resultReasonCode, resultExternalRef): `operationId`.
- `sangam.signatureResult` (signatureResult): `signatureOperationId`.
- `sangam.cancelAck` (cancelResult): `openOperationId`.
- `sangam.reconciliationResolved` (resolution, resolutionEvidence, resolvedBy): `operationId`.

Each message falls back to `operationId` when its first variable does not match.

Pack connectors without a binding go to the fallback gateway (the packs' simulator in development) or fail with `CONNECTOR_NOT_CONFIGURED`. ReconciliationEngine gained `hooks(ResolutionGuard, ResolutionListener)`.

### N11 pipelines (`pipeline/`) and N12 telemetry (`telemetry/`)

Reference DDL to port to sangam-persistence: `PipelineStore.DDL`, `TelemetrySchema.DDL`.

- Pipelines: build `PipelineRunner` and `PipelineScheduler`, then call `PipelineMessages.wire` and `PipelineDelegate.install`. `stageRoot` must be shared storage for a restart on another node.
- Telemetry: call `TelemetryWiring.wire`, and ingest through `TelemetryHttpReceiver` or `MqttTelemetryConsumer`.

### One operationId

`ExternalWork.operationId` (T35) is the ledger key and the downstream key. The delegate mints the same identity, and pack calls use the pack's UUID. The 12-argument `Invocation` constructor derives an id and logs a warning.

### Hand-off to WS-PORTS (event registry)

Wired in `ports/EventRegistryWiring.java`. This module depends on `sangam-flowable-ports` as an optional dependency. There is no cycle, because ports depends only on sangam-core. Call this once at start-up:

```java
EventRegistryWiring.wire(inboxService, publisherRegistry, eventDispatcher, channelPublisher, historyProjectorOrNull)
```

It does three things:
- Inbox rows with source `evt:*` go unchanged to ports' `EventDispatcher` (a raw core `Inbox.Handler`), via `InboxService.registerSourceHandler`. They run inside the inbox processing transaction, which must be the engine transaction.
- Destinations `channel:*` are published by `ChannelPublisher`.
- Optionally, destination `history-projector` (`PortsHistoryEventHandler.DESTINATION`) is published by `HistoryProjector`.

The generic plug-in points remain available:

- Outbound channels register publishers with `PublisherRegistry.register("kafka:*", kafkaPublisher)`.
- Subscriptions: implement `OutboxWriter.SubscriptionResolver`.
- Inbound channel adapters call `InboxService.receive(ChannelBinding, InboundMessage)`.
- Event definitions become `EventDefinitionPolicy` entries in `InboxService.policies()`.
- Per-event-type handlers are registered with `InboxService.registerHandler`.

### Variables expected from WS-ENGINE

Every instance should get these variables at start:
- `sangamTenantId`
- `sangamRootId`
- `sangamBusinessKey`

If they are missing, the processInstanceId and the default tenant are used.

### Poison inbound messages (`inbox/PoisonInbound.java`)

Some broker messages can never be stored: the payload is not JSON, the mapper rejects it, or the JMS body is not readable as text. For each one, `KafkaInboxConsumer` and `JmsInboxListener` (6-argument constructors with a `PoisonInbound`) do the following in one transaction:
- write it to `INTEGRATION_DEAD_LETTER`;
- open one `POISON_INBOUND` reconciliation item per dead letter (`task_id = dead-letter:<id>`);
- write `POISON_INBOUND_RECEIVED` on `sec:<tenant>`.

Then they ack it, so it is not redelivered forever. If even that write fails, they do not ack.

`InboxService.receive` throws `PoisonMessageException` for malformed JSON. The webhook receiver answers 400 `PAYLOAD_NOT_JSON`.

Operators resolve an item with `PoisonInbound.reprocess` (corrected payload, original message id) or `discard`. Both are audited.

### Requested change in sangam-core

- None open. `POISON_INBOUND` is now in `ReconciliationQueue.Kind`. Resolve these items through `PoisonInbound`, because the core queue's actions do not close the dead letter.

`RootSeq` is package-private. Nothing here needs it today, because audit goes through `AuditChain.append`.

## 5. Known gaps and limits

- **No tests run yet** (phase rule). Every class compiles. Behaviour still needs to be verified with the simulators and Testcontainers in the testing phase.
- **JavaDelegate path**: its invoker runs without the audit chain (`auditOrNull = null`), to avoid a ROOT_SEQ self-deadlock with the surrounding job transaction. Dispatch audit is therefore only complete on the external-task path.
- **DNS pinning** is check-then-connect plus a re-check of pinned addresses. The JDK `HttpClient` cannot connect to a pre-resolved IP while keeping SNI, so a very short rebinding window remains.
- **Capacity sampling is per node.** Each node's `BackpressureGate` acts on its own last sample.
- **Oracle**: the relay and inbox claims inherit the core `ROWNUM` skip-locked caveat (core `Dialect` comment).
- **SMTP** has no outcome lookup, so it defaults to class (c): a lost SMTP reply goes to reconciliation.
- **Real government wire formats** (IFMS, PFMS, NIC SMS, payment-gateway schemas) are ON-REQUEST (RQ-1/RQ-3). The connectors follow the simulator formats; only the mappings will change.
- **Not implemented**: FC-08 Camel (stage E); GraphQL, JDBC, S3 and chat connectors from R71 (stage E breadth); signed Merkle checkpoints of the audit chain (core).
