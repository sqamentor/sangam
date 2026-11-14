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

## Optional module, off by default (owner decision 187)

This module is optional and off by default (decision 39: core on, domain modules opt-in). Turn it on with
`sangam.modules.connectors-health.enabled=true` (`SANGAM_MODULE_CONNECTORS_HEALTH=true`).

| | With the module on | With the module off or absent |
|---|---|---|
| What it adds | FHIR / SMART, Da Vinci PAS and DTR, CDS Hooks, X12 connectors; FHIR subscription and CDS Hooks endpoints; the 835 remittance hook | nothing is registered |
| Health packs (`sec.ushealth.*`, `sec.health.*`) | pack connector ids bound to these connectors | pack connector ids stay unbound and every call fails closed with `CONNECTOR_NOT_CONFIGURED` (`IntegrationConnectorGateway`); no request is sent and nothing is recorded as done |
| Health REST surfaces | mapped | reported unavailable (requested: 503 `CAPABILITY_MISSING`, W4-M) |
| Everything else | unchanged | unchanged: no other module depends on this one |

Heavy or separately licensed parts (HAPI FHIR profile validation, decision 183; ABDM Fidelius with Bouncy Castle,
decision 173) may only enter this module, never a default-on module. HAPI is declared as an optional Maven dependency
of this module, so no module that depends on this one (sangam-server, sangam-rest) receives it; a deployment that
wants profile validation adds the HAPI jars. Fidelius (decision 243) uses Bouncy Castle `bcprov-jdk18on`, also an
optional dependency of this module only: a deployment that uses it takes the eSign add-on's jar and never runs the NATS
add-on (decision 235).

Wiring status: the starter already ships the flag off (`sangam-defaults.yaml`). The standalone server default and the
wiring condition are changed by the server owner (board REQUEST, decision 187); until then the standalone server
still turns the module on by default.

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

## HAPI FHIR: optional profile validation only (decisions 183, 187, 209)

`hapi-fhir-structures-r4` alone is about 20 MB (plus `hapi-fhir-base` and its dependencies). Sangam routes resources
and reads a few fields; it does not hold the clinical record (ADR-D-22). The connectors therefore use Jackson trees and
never need HAPI.

Profile validation is the one place HAPI FHIR 8.12.1 is used, behind a port free of HAPI types:

| File | What |
|---|---|
| `validation/FhirProfileValidation` | the port: validate one R4 resource (JSON) and return issues; valid only without ERROR or FATAL |
| `validation/FhirProfileValidations` | checks that HAPI is on the class path and loads the implementation by name; otherwise `CAPABILITY_MISSING` (`CapabilityMissingException`), never "valid" |
| `validation/hapi/HapiFhirProfileValidation` | HAPI chain: R4 base definitions, the conformance resources the caller supplies, snapshot generation, in-memory terminology, common code systems; no network |

Dependency rules in `pom.xml`: every HAPI artifact is `optional`; `jakarta-regexp` (no licence; no class of the closure
uses it) and `sqlite-jdbc` (native libraries; only optional package-index, AI text and importer paths use it) are
excluded; javassist 3.33.0-GA (decision 117 election), jakarta.annotation-api 3.0.0 and caffeine 3.1.8 (the evaluated
version) are pinned here; Jackson 2.22.3 and JGit 7.8.0 come from the root pom. Other versions follow the root
dependency management (gson 2.13.2, commons-codec 1.21.0, commons-lang3 3.20.0, nimbus-jose-jwt 10.9.1, kotlin-stdlib
2.3.21 instead of HAPI's own); the offline smoke test `HapiFhirProfileValidationSmokeTest` is the proof. It ran once,
owner-approved (decision 226), on 2026-11-13 after the parser swap below; Maven reported "Tests run: 5, Failures: 0,
Errors: 0, Skipped: 0" and BUILD SUCCESS (record on WAVE4-SYNC-BOARD.md). It ran once more, offline, after the mxparser
swap (decision 263) on 2026-11-13 from 04:39:50Z to 04:40:33Z: "Tests run: 6, Failures: 0, Errors: 0, Skipped: 0" and
BUILD SUCCESS. The other tests of this module are NOT_RUN.

The XML pull parser the FHIR parsers need (decision 226, replacing `org.ogce:xpp3:1.1.6`, which HAPI's
`org.hl7.fhir.validation` brings and which is excluded here; decisions 254 and 263): `xmlpull:xmlpull:1.1.3.1` is the
only `org.xmlpull.v1` API jar (public domain), and the parser is `io.github.x-stream:mxparser:1.2.2` (MXParser, a fork
of xpp3_min 1.1.7 with the parser only and no `org.xmlpull.v1` class; Indiana University Extreme! Lab Software License
1.2, BSD-style with a required acknowledgement, which goes into NOTICE). The HAPI and `org.hl7.fhir` parsers reach it
only through `XmlPullParserFactory` and its `META-INF/services` entry. It replaces `xpp3:xpp3_min:1.1.4c`, whose two
`org.xmlpull.v1` classes differ in bytes from xmlpull's. Both jars are optional like HAPI. Records in
DOWNLOADS-WAVE4.md. US profiles (US Core, PAS) are supplied per tenant and stay US-only, never a universal or Indian
default.

## Reuse for India (ABDM, NHCX)

ABDM and NHCX exchange FHIR R4 bundles, so the same classes apply:

| ABDM / NHCX need | Use |
|---|---|
| Gateway session token (`/gateway/v0.5/sessions`, client id + secret) | `SmartBackendAuth` mode `CLIENT_SECRET` with the gateway token URL (or a small adapter that posts the JSON body); the token is cached the same way |
| `X-CM-ID`, `X-HIP-ID`, `X-HIU-ID`, `REQUEST-ID`, `TIMESTAMP` headers | `FhirClient.Config.headers` (static) plus `X-Request-Id` = operationId |
| HIP data push, HIU fetch, care-context bundles (OPConsultRecord, DischargeSummary, DiagnosticReport documents) | `FhirConnector` `transaction` / `create` (conditional by operation identifier) and `read` / `search` |
| Asynchronous gateway callbacks (`on-init`, `on-confirm`, consent notifications) | `FhirSubscriptionReceiver`-style receiver → inbox; the callbacks are not FHIR Subscriptions, so a thin JSON receiver maps them to `InboundMessage` (correlation on `requestId`) — integration note |
| NHCX claim / pre-auth (FHIR Claim, ClaimResponse, CoverageEligibilityRequest) | `FhirConnector` and `PasConnector.interpret` (same Claim/ClaimResponse structures; outcome mapping applies) |
| Fidelius encryption of HI data | `org.sangam.connectors.health.fidelius.Fidelius` (decisions 173 and 243): ECDH on Bouncy Castle `curve25519`, HKDF-SHA256, AES-256-GCM; keys as 65-byte points or X.509; checked against a byte-exact copy of the MIT abdm_fidelius test vectors (tests NOT_RUN); not yet called by a connector; no ABDM conformance claimed |

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

## CMS-0057-F payer APIs (M-133, owner decision 107)

The payer APIs are FHIR servers the payer hosts; Sangam runs the workflows behind them. The packs
`sec.ushealth.payer-to-payer` (US-HLT-063, 064) and `sec.ushealth.provider-access` (US-HLT-062, 064) call these pack
connector ids, which a tenant binds to `FhirConnector` operations (Bulk Data `$export` and `$member-match` through
the `operation` call) in `PackConnectorBindings`:

| Pack connector id | What it does |
|---|---|
| `payer.memberMatch` | member match at the other payer (`$member-match`); answer `patientId` |
| `payer.p2pData` | Payer-to-Payer data request (answer `bundleRef`) and response (answer `receiptRef`) |
| `payer.memberRecord` | this payer's member record: `match` (`patientId`), `optOut` (`optedOut`), `export` (`bundleRef`), `ingest` (`recordRef`) |
| `payer.attribution` | provider attribution check (answer `attributed`) |
| `payer.providerAccess` | Provider Access answer (answer `receiptRef`) |

A previous payer that has the data ready later sends `sangam.ushealth.p2pDataReady` (message correlated on the
business key). No Surprises Act items (US-HLT-114, 115, 116, 122) are traceability only (owner decision 107).

## Third-party dependencies

Jackson (Apache-2.0) and slf4j (MIT) come through sangam-core / sangam-integration. Optional only: HAPI FHIR 8.12.1
and its closure (licences per coordinate in DOWNLOADS-WAVE4.md, D-2 and the decision 209 records), see the HAPI
section. Optional only: Bouncy Castle `bcprov-jdk18on` 1.84 (MIT-style Bouncy Castle licence, the eSign jar) for
Fidelius. Test resource only: the abdm_fidelius test vectors (MIT, Copyright (c) 2026 Prashant Sharma, notice in
`LICENSE-abdm_fidelius.txt` beside them).

## Known gaps

- No tests run (phase rule), except the one owner-approved HAPI smoke run (decision 226, see the HAPI section).
- No Bulk Data `$export` polling helper (an `operation` call starts it; the status polling loop is not written).
- FHIR profile validation is not yet called by any connector, and no deployment packaging adds the optional HAPI jars
  yet. (The `org.ogce:xpp3:1.1.6` jar with its unattributed `javax/xml/namespace/QName.class` is no longer on the
  class path, decision 226.)
- X12: no full TR3 situational-rule validation and no 277CA decoder beyond the generic 277; 999 generation reports
  envelope errors only (no IK3/IK4 element-level errors).
- CAQH CORE (owner decision 172): HTTP with the CORE headers only. The full CORE SOAP/MIME envelope is an explicit,
  recorded gap. No CAQH CORE conformance is claimed and no CAQH terms were accepted.
- ABDM Fidelius (decisions 173 and 243): the primitive exists, but no ABDM connector calls it yet, and its only
  evidence is the abdm_fidelius MIT test vectors (generated with fidelius-cli 1.2.0), not a published ABDM
  specification. The tests are NOT_RUN. No ABDM conformance is claimed.
- CDS Hooks service: JWT verification of the caller is the REST layer's job (not written here).
- SMART: no JWKS publishing endpoint for the client's public key (operational setup).
