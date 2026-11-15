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

**Read-only requests (owner decisions 320 and 321).** `SangamConnector.readOnly(ConnectorRequest)` answers, per request, whether `invoke` of exactly that request changes no state and sends nothing outside. The default is `false`. The answer comes from what the request will really do, never from a list of names: in the HTTP, JDBC, GraphQL and SOAP connectors the payload, not the operation name, decides the effect. An inquiry that sends a message (X12 eligibility or claim status, a CDS Hooks call, Aadhaar OTP e-KYC, a DigiLocker pull) is not read-only.

- A path that calls a connector from an **API read** (outside `ConnectorInvoker`, so without the operation ledger, idempotency and maker-checker) invokes only a request the connector declares read-only and otherwise refuses with `CONNECTOR_OPERATION_NOT_READ_ONLY` (HTTP 422, a permanent refusal) and a warning in the log; the connector is not invoked. The API-read entries are `packs/ApiReadGateway.queryReadOnly` (implemented by `IntegrationConnectorGateway`; used by the low-code data-object records `GET` through sangam-server `RestBackedRecordsGateway`), sangam-geo `ExternalLayerGateway.query` and sangam-rest `ModuleConnectors.read`. A REST-backed data object is also refused at definition when a probe list request against its binding is not read-only (`ApiReadGateway.readOnlyRefusal`); the run-time check is the guard.
- A **pack step in query mode** is not an API read (an engine job inside a process, with its audit): it keeps `ConnectorGateway.query`, which this rule does not change.
- What the connectors of this module answer: `HttpRestConnector` true when the resolved method (the request's `method`, else the configured default) is `GET` or `HEAD` and no async callback is configured; `JdbcConnector` true when the resolved statement (the request's `statement`, else the operation) is a `QUERY` whose SQL is one plain `SELECT` (not `WITH`, not `… INTO`, not a locking select); `S3Connector` true for `get` and `head`. Every other connector here keeps the default (`ConnectorReadOnlyTableTest` lists them; adding an override there needs its rows in that test).

### Connector core (`connector/`)

| File | Requirements |
|---|---|
| `ConnectorInvoker.java` | VI.3 rules 4–5, R36, R37, A-2, A-8, R28 (no blind retry of a non-idempotent effect); audit `OPERATION_DISPATCHED` / `OPERATION_OUTCOME` / `OPERATION_UNKNOWN` (X.2.3); decision 299: an `AuditedConnector`'s own events are written with the successful `OPERATION_OUTCOME`, same transaction, same chain, once per operation |
| `AuditedConnector.java` | Decision 299: a connector names the catalogued events of a successful operation (`DigiLockerRequesterConnector`: `DOCUMENT_FETCHED` per fetched file with URI, issuer, doc type, SHA-256; `DigiLockerPushConnector`: `DOCUMENT_PUSHED` with issuer org, URI, doc type, duplicate; no file content, no holder id) |
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
| `connector/s3/S3ObjectClient.java`, `S3Connector.java` | M-105 S3-compatible object store (put, get, head, delete) over the JDK HTTP client and the shared `HttpTransport` (egress, L2 lock rule); SigV4 with the payload hash; keys limited to the AWS safe-character set plus `=` (partitioned lake layouts, `name=value/`; sent as it is and signed as `%3D`); class (a): a put records operationId as `x-amz-meta-sangam-operation-id` and the lookup is a HEAD that must name this operation |
| `connector/graphql/GraphQlConnector.java` | M-105 GraphQL over a configured HTTP endpoint (`HttpRestConnector`); optional allow-list of reviewed documents (then a free query is refused); `errors` in a 2xx answer → `GRAPHQL_ERRORS` with the partial data kept |
| `connector/jdbc/NamedStatement.java`, `JdbcConnector.java` | M-105 external database: configured named statements only (`:name`, no `?`), operationId and tenantId bound by the connector, exact decimals, one transaction per call, SQL state → `CONSTRAINT_VIOLATION` / `DATA_ERROR` / `INVALID_STATEMENT` / no-effect transient, commit failure → unknown, bounded rows; optional lookup statement with `:operationId` (class a/b) |

### Government connectors (`connector/gov/`)

| File | Requirements |
|---|---|
| `IfmsJitConnector.java` | R58, IX.4 P1, GOV-8: IFMS JIT PI/PD/FD; token caching; class (a) by default |
| `PfmsFileConnector.java`, `PfmsStatusPoller.java` | R58: signed payment files out (HMAC or RSA `.sig`), status files in; class (a) via a deterministic batch id; IX.4 P1/P3 |
| `PaymentAcknowledgement.java`, `BankAcknowledgementFeed.java` | IX.4 P1 bank/treasury acknowledgement; P3 DBT; normalised feed |
| `EsignConnector.java` | R57, GOV-7: CCA eSign 2.1; signature bound to the approval payload hash (VI.4 rule 5); class (b) |
| `DigiLockerPushConnector.java` | R59, GOV-9: issuer push; idempotent by URI; class (a); IX.4 P4; `DOCUMENT_PUSHED` (decision 299) |
| `DltSmsConnector.java`, `DltRegistry.java` | R60, GOV-10: per-tenant DLT registry (entity, header, template); template and URL validation before send; class (c) |
| `DltDeliveryReports.java`, `DltDeliveryStatusPoller.java` | Decision 304: signed delivery-report webhook (`X-Sangam-Signature`, HMAC-SHA256, 5 minute tolerance; unsigned, wrong or stale refused 401, fail closed without a secret), per-report idempotency (`NOTIFICATION_DELIVERY_REPORT`), per-message state that only moves forward (`NOTIFICATION_DELIVERY`), `NOTIFICATION_DELIVERED` once per message on its root chain; status-lookup fallback through the connector lookup (class (a)/(b) gateways), same rules |
| `DltRegistrationStore.java` | Decision 305: stored per-tenant DLT registration (`DLT_REGISTRATION`, `DLT_REGISTRATION_ITEM`) changed through the permission-checked admin route; `DLT_TEMPLATE_REGISTERED` on chain `dlt:registry` in the same transaction, nothing when nothing changes; start-up configuration only seeds; `DltRegistry` follows the store by revision (`refresh`) |
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
| `history/DataLakeHistoryPublisher.java` | M-057 data-lake history sink: one NDJSON object per `sangam.history.*` event at `{prefix}/tenant={t}/date={UTC day of the v7 event id}/{event_id}.json` in an S3-compatible bucket (a redelivery overwrites the same object); 400/403/404/411/413 permanent, 429/503 with `Retry-After` deferred, else retried |

### Signature verification interfaces (`signature/`, owner decisions 140, 165 and 217)

| File | Requirements |
|---|---|
| `EsignResponseVerifier.java`, `DscSignatureVerifier.java` | framework-independent verification of a CCA eSign 2.1 response and of a DSC-signed PDF; implementations come from the optional module **sangam-esign** through `ServiceLoader`; absent = eSign and DSC stay blocked, nothing else changes |
| `SignatureVerification.java`, `SignaturePolicy.java`, `EsignExpectation.java`, `XmlResponseSignature.java` | three outcomes (VERIFIED_VALID, VERIFIED_INVALID, INDETERMINATE) with audit evidence; trust anchors and revocation always required; ESP XML signature check with the JDK |
| `DscSigning.java` | decision 165: framework-independent prepare and complete of a DSC signature (signed attributes, prepared digest, algorithm SHA256withRSA or SHA256withECDSA); implemented by **sangam-esign** through `ServiceLoader`; absent = DSC signing stays off |
| `../dsc/` | decision 165 loopback DSC agent (JDK SunPKCS11, bound to 127.0.0.1 only; it answers a Host of 127.0.0.1:port or localhost:port from a 127.0.0.1 peer and refuses [::1] and IPv4-mapped forms with 421; origin checks, local approval and PIN dialogs); no Bouncy Castle |
| `../dsc/DscSigningFlow.java` | decision 165 and 140 server flow behind `POST /api/v1/tasks/{id}/dsc/prepare` and `/dsc/complete` (sangam-server): checks the chain and algorithm and that the signer certificate is the caller's own approved enrolment (decision 227; names and CN are shown, never trusted), issues a random `prepareId`; complete consumes it first (once only, bound to tenant, task, signer principal and signer certificate, expires, default 10 minutes), checks the enrolment is still approved, assembles, then verifies against the enrolled certificate; only VERIFIED_VALID yields a signed PDF, which the server stores only by compare-and-set on the signed version (decision 228); `hasVerifiedSignature(tenant, task, document, principal)` is true only for that principal's stored VERIFIED_VALID signature of exactly that document (decisions 236 and 237) whose certificate is, at the time of the check, still the approval checked at prepare (decision 241, bound by identity: prepare stores the enrolment id and its approval_seq, complete and the gate require that row still APPROVED with the same values; a revoke voids; after re-enrolment and a new approval the officer signs again; the evidence stays recorded); `signatureStatus` gives the reason when it is false: VERIFIED, ENROLMENT_REVOKED (signed, but that enrolment no longer stands, so completion answers `422 DSC_ENROLMENT_REVOKED`) or NOT_SIGNED |
| `../dsc/DscEnrolment.java`, `../dsc/DscCertificateEnrolments.java`, `../dsc/JdbcDscCertificateEnrolments.java` | decision 227 certificate enrolment: the server derives SHA-256 fingerprint, issuer and serial from the DER; PENDING until an admin other than the signer approves or rejects (reason required to reject); the signer or an admin revokes; one live enrolment per tenant and certificate; table `INTEGRATION_DSC_CERTIFICATE` (reference DDL `db/sangam-dsc-enrolment-reference.sql`); every decision is a conditional UPDATE on the expected status |
| `../dsc/DscPreparedStore.java`, `../dsc/JdbcDscPreparedStore.java` | prepared state in table `INTEGRATION_DSC_PREPARED` (reference DDL `db/sangam-dsc-reference.sql`; the changelog is generated in sangam-persistence); single use by a conditional UPDATE; the prepared PDF is dropped once an outcome is recorded; `purgeExpired` removes unused expired rows |

PAdES signing (`PadesSigning`), RFC 3161 time-stamps, `PdfSignatureValidator` and the Bouncy Castle verifiers moved to `sangam-esign` (decision 217). This module has no Bouncy Castle dependency (`BouncyCastleConfinementTest`).

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
| `reconciliation/ReconciliationEngine.java` | VI.3 rule 4, VI.5 rule 3, OJ-02/OJ-03, R58, T02/T27/T36/T39: lookup → auto-resolve, or escalate to a human task; class (b) not-found confirmation; back-off; multi-node claim; a `CONFIRM_SUCCESS` that moves the ledger to SUCCEEDED writes the connector's catalogued events through `ConnectorInvoker.writeConfirmedEvents` in the resolving transaction (`confirmations(invoker)`), so they are written once whichever path confirms |
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

### REST-backed data objects (EC-06, owner decisions 265 and 266)

`sangam-server` `RestBackedRecordsGateway` maps the sangam-rest data-object seam onto this module's `IntegrationConnectorGateway`: list is a connector query (operation `list`), create a submit with its own operationId and business key `data-object:<key>` and no process instance, the outcome a `lookup`. Known gap in 0.1.0: a low-code `reference` field cannot point at a REST-backed data object; the reference is checked against DB-backed records only, so such a record write is refused (fail-closed, no connector call is made to check it). See the sangam-rest README.

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
| `PackOperationStore.java` | New table `PACK_OPERATION` (DDL in its `DDL` field). It holds the first result per operationId and payload hash, and maps to the ledger id. Decision 289: `settle` never moves a final row (COMPLETED, FAILED, CANCELLED) again and returns whether it applied; when a row first becomes final it calls the `PackSettlementListener` registered with `onSettled(kind, listener)` for the business key's prefix (`data-object` for `data-object:<key>`), once, in the settling transaction (an exception rolls it back) |
| `PackSettlementListener.java` | Decision 289: `settled(Connection, Settlement)` with tenant, operationId, connector, operation, business key, process instance, final status, reason code, external ref and the connector output (the same shape after a dispatch and after a reconciliation confirmation) |
| `PackDispatchPublisher.java` | Relay publisher for `pack-dispatch`. It calls the connector through `ConnectorInvoker` and delivers the result |
| `PackMessages.java`, `PackMessageInboxHandler.java` | Deliver the README §4 messages through the inbox, correlated on the pack variables (see the list below) |
| `PackReconciliationHooks.java` | Refuses a retry for class (c); delivers `sangam.reconciliationResolved` (CONFIRMED_SUCCESS / CONFIRMED_FAILURE / RETRY_SAME_OPERATION), settling the row under its lock. Decision 290: a retry of an operation with no process instance (a REST-backed data-object create) re-dispatches it at once with the same operationId; automatic re-dispatches are bounded (`maxRedispatches`, default 3); past the bound the row stays UNKNOWN (`REDISPATCH_LIMIT_REACHED`) and `ReconciliationEngine.openForHuman` opens a new item that goes straight to a human; a human's retry re-dispatches. Operations of a process instance still become RETRY_REQUESTED for the pack's re-submit. A reconciliation settlement stores `result_json` in the dispatch shape (`storedResult`: the connector output, or `{message}`), never the resolution wrapper, so the operation lookup returns the record on both paths |
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

Wired in `ports/EventRegistryWiring.java`. This module depends on `sangam-flowable-ports` as an optional dependency. There is no cycle, because ports depends only on sangam-core. Because the dependency is optional, a deployment that uses the HTTP connector's `aws-sigv4` auth or the AWS Secrets Manager secret provider must also have `sangam-flowable-ports` on its runtime classpath, since the one SigV4 signer (`org.sangam.ports.events.channel.AwsSigV4`) lives there; sangam-server already includes it. Call this once at start-up:

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

### Cell tenant states on the broker path (`relay/TenantAdmission.java`, M-008, SL-142)

Both consumers ask the cell's `TenantAdmission` before they store a message:
- **ACTIVE**: stored and acked.
- **FROZEN, IMPORTING**: not acked. Kafka seeks back and pauses the partition; JMS rolls the session back. The broker redelivers it.
- **MOVED, PURGED**: the tenant will never take it in this cell again. `PoisonInbound.park` writes, in one transaction, a dead letter with the full message, a `POISON_INBOUND` item with `"parked":true`, the tenant state, target cell and move id, and `INBOUND_PARKED` on `sec:<tenant>`. Only then is the message acked. If the park write fails, or no `PoisonInbound` is configured, it is not acked.

An operator replays a parked message to the tenant's new cell with `PoisonInbound.replayParked(itemId, transport, actor, reason)`: the original message id is kept, so the target inbox deduplicates a repeat. `reprocess` refuses a parked item; `discard` closes it. A failing admission check counts as redeliver. A message is never dropped.

### Requested change in sangam-core

- None open. `POISON_INBOUND` is now in `ReconciliationQueue.Kind`. Resolve these items through `PoisonInbound`, because the core queue's actions do not close the dead letter.

`RootSeq` is package-private. Nothing here needs it today, because audit goes through `AuditChain.append`.

## 5. Known gaps and limits

- **No tests run yet** (phase rule). Every class compiles. Behaviour still needs to be verified with the simulators and Testcontainers in the testing phase.
- **JavaDelegate path** (SL-133): the job transaction holds the root's `ROOT_SEQ` row while the delegate calls the invoker on a second connection, so appending to the root chain there would self-deadlock. In deferred mode (`ConnectorInvoker.deferringAudit(drainer)`) the invoker writes its audit events as `AUDIT_PENDING` rows (`PendingAudit`), atomically with the ledger and without the `ROOT_SEQ` lock. `PendingAuditDrainer` then appends them to the root chain after the job transaction ends: root lock first, `pending_id` order, original time, delete in the same commit, plus a periodic sweep as a backstop. No event is lost on rollback. Direct mode is unchanged for the other paths. It is live once the server wires the `PendingAudit`, drainer and bridge beans.
- **DNS pinning** is check-then-connect plus a re-check of pinned addresses. The JDK `HttpClient` cannot connect to a pre-resolved IP while keeping SNI, so a very short rebinding window remains.
- **Capacity sampling is per node.** Each node's `BackpressureGate` acts on its own last sample.
- **Oracle**: the relay and inbox claims inherit the core `ROWNUM` skip-locked caveat (core `Dialect` comment).
- **SMTP** has no outcome lookup, so it defaults to class (c): a lost SMTP reply goes to reconciliation.
- **Real government wire formats** (IFMS, PFMS, NIC SMS, payment-gateway schemas) are ON-REQUEST (RQ-1/RQ-3). The connectors follow the simulator formats; only the mappings will change.
- **Not implemented**: FC-08 Camel (stage E); GraphQL, JDBC, S3 and chat connectors from R71 (stage E breadth); signed Merkle checkpoints of the audit chain (core).
