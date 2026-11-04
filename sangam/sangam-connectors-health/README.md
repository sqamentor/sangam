<!-- SPDX-License-Identifier: Apache-2.0 -->
# sangam-connectors-health (US-HEALTH workstream)

Generic health-interoperability connectors on the `SangamConnector` SPI (`org.sangam.core.integration`). Nothing here is
specific to one country or payer (ADR-D-20): FHIR servers, clearinghouses and CDS services are configuration.
Requirements: `../../spike/results/US-HEALTH-WORKFLOWS.md` (US-HLT-nnn).

**Status: compiling code; nothing has been tested yet** (owner phase rule: code first, testing last).

```
mvnw.cmd -q -B -DskipTests -pl sangam-connectors-health -am install
```

Base package `org.sangam.connectors.health`. Depends on `sangam-core` and `sangam-integration` (HttpTransport with the
R27 egress policy, `Json`, `SecretResolver`, `FaultInjector`/`EffectsLedger`, inbox types).

## Files and requirements

| File | What | Capability class | Requirements |
|---|---|---|---|
| `HealthHttp` | A-2 outcome mapping (no-effect / business / unknown), OperationOutcome codes | — | A-2, R36 |
| `fhir/FhirClient` | FHIR R4 REST on Jackson trees: read, search + paging, conditional create, update with If-Match, batch/transaction, operations, metadata, SMART discovery; `X-Request-Id` = operationId; 401 → token refresh once | — | US-HLT-075, 076, 079 |
| `fhir/SmartBackendAuth` | SMART Backend Services (`private_key_jwt`, RS384/ES384, `exp` ≤ 5 min, `jti`), client-secret and static tokens, token cache | — | US-HLT-077, US-HLT-014 |
| `fhir/FhirConnector` | Connector ops `read`, `search`, `create` (conditional create on `urn:sangam:operation|<operationId>`), `update`, `transaction` (each POST entry made conditional), `operation`; lookup by the operation identifier | (a) | US-HLT-075, R36, A-2 |
| `fhir/FhirSubscriptionReceiver` | Subscription rest-hook → inbox: R5 Backport (history Bundle, handshake/heartbeat, event numbers, gap detection) and R4 classic; constant-time channel secret; dedup id = subscription#event | — | US-HLT-078, VI.2.6, R27 |
| `x12/X12` | X12 005010 model, parser (ISA fixed width, separators from ISA, SE/GE/IEA count and control checks) and writer | — | US-HLT-090, 091 |
| `x12/X12Builders` | 270, 276, 278, 837P, 837I builders; 999 acknowledgment | — | US-HLT-085–088, 090, 105 |
| `x12/X12Decoders` | 271 (EB, AAA), 277 (STC categories), 278 (HCR), 835 (BPR/TRN/CLP/CAS/LQ/PLB with balancing), 999, TA1 | — | US-HLT-085–090, 104, 106 |
| `x12/X12Connector` | Clearinghouse connector: `eligibility`, `claimStatus`, `serviceReview` (real-time, CORE headers, PayloadID = operationId, ISA13 from operationId), `submitClaim` (batch → 999, lookup by PayloadID), `parse`, `acknowledge` | (b) | US-HLT-085–092 |
| `pas/PasConnector` | Da Vinci DTR `$questionnaire-package`; PAS `Claim/$submit` (bundle given or built), `$inquire`, cancel; decision, X12 review action, specific denial reason, preAuthRef | (b) | US-HLT-058, 060, 071–073 |
| `cdshooks/CdsHooksConnector` | CDS Hooks client (discovery, call with client JWT, feedback); CRD coverage-information summary (covered, pa-needed, doc-needed, DTR questionnaires) | (a) | US-HLT-070, 074 |
| `cdshooks/CdsHooksService` | CDS Hooks service side (discovery + dispatch to handlers, e.g. DMN), fail-open with no cards | — | US-HLT-074 |
| `npi/Npi` | NPI format and Luhn check digit (prefix 80840) | — | US-HLT-095 |
| `sim/HealthSimulator` | One loopback simulator: FHIR server with SMART token, PAS/DTR, CRD service, X12 clearinghouse (271/277/278/999), 835 generator; deterministic outcome rules; faults and effects ledger | — | ARCHITECTURE §1.9 |
| `HealthConnectors` | SIMULATOR wiring and the pack connector-id bindings of the `sec.ushealth.*` packs | — | R71 |

## HAPI FHIR: not used

`hapi-fhir-structures-r4` alone is about 20 MB (plus `hapi-fhir-base` and its dependencies). Sangam routes resources
and reads a few fields; it does not hold the clinical record (ADR-D-22). The connectors therefore use Jackson trees.
Profile validation (US Core, PAS) is a gap that can be added later with the HAPI validator as an optional dependency
(Apache-2.0) if a tenant needs it.

## Reuse for India (ABDM, NHCX)

ABDM and NHCX exchange FHIR R4 bundles, so the same classes apply:

| ABDM / NHCX need | Use |
|---|---|
| Gateway session token (`/gateway/v0.5/sessions`, client id + secret) | `SmartBackendAuth` mode `CLIENT_SECRET` with the gateway token URL (or a small adapter that posts the JSON body); the token is cached the same way |
| `X-CM-ID`, `X-HIP-ID`, `X-HIU-ID`, `REQUEST-ID`, `TIMESTAMP` headers | `FhirClient.Config.headers` (static) plus `X-Request-Id` = operationId |
| HIP data push, HIU fetch, care-context bundles (OPConsultRecord, DischargeSummary, DiagnosticReport documents) | `FhirConnector` `transaction` / `create` (conditional by operation identifier) and `read` / `search` |
| Asynchronous gateway callbacks (`on-init`, `on-confirm`, consent notifications) | `FhirSubscriptionReceiver`-style receiver → inbox; the callbacks are not FHIR Subscriptions, so a thin JSON receiver maps them to `InboundMessage` (correlation on `requestId`) — integration note |
| NHCX claim / pre-auth (FHIR Claim, ClaimResponse, CoverageEligibilityRequest) | `FhirConnector` and `PasConnector.interpret` (same Claim/ClaimResponse structures; outcome mapping applies) |
| Fidelius encryption of HI data | not here (crypto belongs to the ABDM HIP/HIU adapter); gap |

The existing India packs (`sec.health.*`) keep their connector ids (`abdm.*`); binding them to these classes is
configuration in `PackConnectorBindings`.

## Integration notes

- Register the connectors in the `ConnectorRegistry` per tenant; bind pack ids with `HealthConnectors.packBindings()`.
- Mount `FhirSubscriptionReceiver.receive` and `CdsHooksService` on REST endpoints in `sangam-server`
  (`POST /api/v1/hooks/fhir/{channel}`, `GET|POST /cds-services/**`); sink = `inboxService::receive`.
- PAS pended answers arrive as `sangam.ushealth.pasUpdate` (event type mapping `ClaimResponse` →
  `sangam.ushealth.pasUpdate`); 835 files from SFTP become `sangam.ushealth.remit` messages correlated on the
  patient control number.
- Live configuration: tenant egress rules for the payer and clearinghouse hosts, mTLS where required, secrets by
  reference.

## Third-party dependencies

None new. Jackson (Apache-2.0) and slf4j (MIT) come through sangam-core / sangam-integration.

## Known gaps

- No tests run (phase rule).
- No FHIR profile validation (US Core, PAS); no Bulk Data `$export` polling helper (an `operation` call starts it; the
  status polling loop is not written).
- X12: no full TR3 situational-rule validation and no 277CA decoder beyond the generic 277; 999 generation reports
  envelope errors only (no IK3/IK4 element-level errors); CAQH CORE SOAP/MIME envelope not implemented (HTTP body
  transport with CORE headers only).
- CDS Hooks service: JWT verification of the caller is the REST layer's job (not written here).
- SMART: no JWKS publishing endpoint for the client's public key (operational setup).
