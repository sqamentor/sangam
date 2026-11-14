# Wave 4 owner questions, batch 1 (integrator, 2026-11-12)

These questions come from the lanes that have finished: W4-A, W4-B, W4-D, W4-E, W4-G, W4-H, W4-I, W4-J and W4-K. Each lane stopped the item because it was below 97% confidence or because the item needs something only the owner can give.

**Recommendations follow the 0.1.0 scope rule.** Every known item is in 0.1.0. An item is deferred only when it is blocked on something outside the repository: a vendor document behind terms (decision 112), a customer corpus, or a design rule that already says "on proven demand only".

**Evidence** (file:line) is in the lane reports on `WAVE4-SYNC-BOARD.md`.

## A. Design choices (recommended option first)

### Engine (W4-E)

| ID | Item | Recommended | Alternatives |
|---|---|---|---|
| W4Q-01 | M-056 partition re-cut | Minimal movement: each partition keeps a prefix of its slots and moves the surplus, so slot runs need not be contiguous. Route `POST /admin/executor/partitions`; audit event `EXECUTOR_PARTITION_COUNT_CHANGED` (class B). | Contiguous re-cut; about two thirds of the slots move from 8 to 12 partitions. |
| W4Q-02 | M-106 D-19, SKIP LOCKED on external-task fetch | Keep the current correct `SANGAM_FETCH_LOCK` route in 0.1.0. Write the per-database statement after the database matrix proves it. | Write the per-database statement now. |
| W4Q-03 | M-106 D-27, multi-instance compensation | Write the reproduction test now. Choose the fix after it runs in the test phase. | Fix now without a reproduction. |
| W4Q-04 | M-060 continue-as-new | First write a spec row: how the old instance ends, moved messages, original deadlines, the chain link stored in a W4-G column. Then build it as one engine command in a single transaction. | Extend `LinkedInstanceDelegate` with only an atomic end and start. |
| W4Q-05 | M-114 UTC persistence | The strict-UTC JVM rule stays the mechanism (decision 104). Add reversible conversion changesets for existing rows (W4-G), using an operator-supplied source zone and cutover date, plus DST-fold tests. | Pre-build the SqlSessionFactory (fragile); or a UTC-calendar DataSource wrapper. |
| W4Q-06 | M-136 replay fixtures | Split the work: engine capture (W4-E), modeller diff and reproducer (W4-G). For 0.1.0, capture FEEL clock reads, timers, messages and external-task results. | One lane builds all of it. |

### Platform and operations (W4-I)

| ID | Item | Recommended | Alternatives |
|---|---|---|---|
| W4Q-07 | M-099 SCIM postings | Postings are read-only in SCIM (a write gets 400 `mutability`). SCIM manages persons, groups and memberships only. A separate SCIM table (W4-G); code in sangam-security. | A SCIM posting becomes a pending assignment that needs IX.5 approval; or apply postings directly. |
| W4Q-08 | M-102 backup console | List targets, trigger a backup now, show run history. Restore stays the documented offline procedure. Move `KubeHttp` into one shared module; the server gets a namespaced Role, off by default. | Also restore, behind two-person approval; or the server never calls Kubernetes. |
| W4Q-09 | M-108 experimental database tier | YugabyteDB as an experimental, PostgreSQL-compatible database that does not gate the release. CockroachDB marked unsupported: Operaton has no CockroachDB mapping, and its licence changed (verify). | Defer both; or both, with an engine patch plus a retry on error 40001. |

### PDF validator (W4-D)

| ID | Item | Recommended | Alternatives |
|---|---|---|---|
| W4Q-10 | Validator authentication | None. The validator stays on the internal network only, and W4-C never publishes its port. | A shared-token header. |
| W4Q-11 | Script-worker apt pinning | Pin through an Ubuntu snapshot archive. Verify the syntax at the first test-phase build. | One build argument per dependency. |

### Modeller (W4-G)

| ID | Item | Recommended | Alternatives |
|---|---|---|---|
| W4Q-12 | M-126 DMN 1.6 | W4-E codes the engine side after the integrator looks up the DMN 1.6 namespace URI (online lookup, see C). DMN 1.4 and 1.5 already run on Operaton 2.2.0. | Grant W4-G `sangam-engine/feel`. |
| W4Q-13 | M-069 Arazzo | A new runtime step in sangam-integration runs a pinned `operationRef` as an HTTP call. The importer emits operationRef tasks. The Arazzo 1.1.0 changes come from an online lookup (see C). | The importer emits HTTP tasks with literal paths; or import for authoring only. |
| W4Q-14 | M-131 legacy history | In scope. W4-G owns `sangam-ops` for this item. Imported entries get their own audit chain `legacy:<job>` with new event names. `ACT_HI_*` maps to `HRM_*` with a source column. | A read-only view over `OPS_MIG_ROW` only. |

### UI (W4-J)

| ID | Item | Recommended | Alternatives |
|---|---|---|---|
| W4Q-15 | M-041 CMMN editor | A CMMN 1.1 editor of our own on diagram-js 14.11.3, which is already installed, with the XML editor as fallback. This is XL work, given to its own lane. | Fork the unmaintained cmmn-js 0.20.0; or only the XML editor in 0.1.0. |
| W4Q-16 | M-109 Teams app | A Teams pop-up sign-in that reuses the UI's OIDC login, so it works with any identity provider, including air-gapped Keycloak. Needs the `@microsoft/teams-js` download (see B). | Teams SSO with token exchange (Entra only); or defer. |

### Integrations (W4-H)

| ID | Item | Recommended | Alternatives |
|---|---|---|---|
| W4Q-17 | M-105 Camel | An optional module with `camel-core-engine` 4.8.0 (download, see B). Off by default. | Drop Camel from 0.1.0. |
| W4Q-18 | M-070 Temporal | A start-and-await connector, with workflowId set to operationId, after an online lookup of the Temporal HTTP API (see C). | A generic HTTP start-and-poll connector; or defer. |
| W4Q-19 | M-104 RPA | A Robot Framework worker example in the SDK, on the existing external-worker pattern. Nothing new on the server side. | Orchestrator REST adapters; or the generic pattern only. |
| W4Q-20 | M-079 DSC | A loopback desktop agent using the JDK's SunPKCS11. The server checks signatures with `PdfSignatureValidator`. | A server-side HSM over PKCS#11; or ESP eSign only. |
| W4Q-21 | M-083 OPA/Cedar | A remote deny-only check at command entry, outside the lock scope, so decision 86 holds. No in-process library. | In-process evaluation (download); or drop. |
| W4Q-22 | M-103 OCR | A Sangam OCR sidecar using the same pattern as decision 114, with a client that fails closed. | A third-party tesseract-server image; or tess4j in-process (download). |

### Domain packs (W4-K)

| ID | Item | Recommended | Alternatives |
|---|---|---|---|
| W4Q-23 | M-107 CMMN plan-item extensions | Keep P-4, P-5 and FC-18 as documented gaps with run-off. PATCHES.md already says "on proven demand only". | Engine patch after M-106 (XL). |
| W4Q-24 | M-135 Flowable live migration | Defer until you supply a real Flowable corpus. | You supply the corpus now. |
| W4Q-25 | M-129 vector index | The portable index for 0.1.0, which is already coded and exact. Database-native indexes later. | Oracle native plus pgvector (new image and column); or pgvector only. |
| W4Q-26 | M-129 A2A and MCP | Look up the current A2A and MCP specification versions online (see C), then code. | Defer. |
| W4Q-27 | M-133 CAQH CORE envelope | HTTP with CORE headers, recorded as a gap. The full spec needs CAQH terms to be accepted, which is your act (decision 112). | You obtain the spec. |
| W4Q-28 | M-133 ABDM Fidelius | Look up the public ABDM specification online (see C), then code. | Keep it as a gap. |
| W4Q-29 | M-133 US-HLT-061 Patient Access PA information | The next pack, in Wave 5. | Traceability only. |
| W4Q-30 | M-134 remaining PFM rows | This order: budget preparation and document bundle, standard reports, cash plan, transfer-deadline monitor, budget-to-accounting reconciliation, replenishment, Do-Not-Pay, then PFM-056/065/069/080. Approve the IFC-189 version bumps of the existing packs. | A different order, or no IFC-189 bumps. |

### Money and audit (W4-A, W4-K)

| ID | Item | Recommended | Alternatives |
|---|---|---|---|
| W4Q-31 | RM-03: who approves an unverified payment | A per-channel permission `billing.channel.<channel>`, checked through PermissionPort in BillingController. Pack steps can never accept an unverified payment, because their actor input can be faked. | A new security resource type PAYMENT_CHANNEL with action ACCEPT_UNVERIFIED. |
| W4Q-32 | RM-08: one power-limit rule | The DMN contract `maxAmount`/`UNLIMITED`/`allowed` wins. One evaluator, in sangam-core. A case type declares itself monetary, so a missing amount fails. The packs listener applies the posting cap. W4-B and W4-K implement it. | You choose another contract. |
| W4Q-33 | RM-36: exact decimals in the engine (P0) | A new engine variable type that stores a BigDecimal as text. W4-E registers it. W4-K changes Vars and the delegates so they never produce a Double. | Money as whole minor units only, with fractional money refused loudly; or defer. |
| W4Q-34 | RM-36: rounding of the six JUEL money calculations | Round half-up to a whole minor unit, each marked "verify" in its pack. You correct any of them later through packgen. | You give a rule for each: instalment, TCS, PF/ESI, refund due, bill amount, P2P amount. |

### Security (W4-B)

| ID | Item | Recommended | Alternatives |
|---|---|---|---|
| W4Q-35 | RM-06: how production is detected | Keep both: the registry's development-profile list and ProductionMode. A start with no profile and no production flag stays refused. | ProductionMode only. |
| W4Q-36 | RM-07: eSign verification (P0) | Bouncy Castle becomes a required dependency, already managed in the root pom. Check the PKCS#7 digest against the input hash, the ESP's XML signature, and the certificate chain against operator-configured `sangam.esign.trust-anchors`, with OCSP/CRL when configured. The simulator signs with a generated test CA. Anything else is OutcomeUnknown. The CCA eSign 2.1 signature format comes from an online lookup of the public spec (see C). | JDK only: the ESP XML signature and the chain, with the per-document check later; or move to 0.2. |
| W4Q-37 | SL-175: DEV_STATIC identities | Keep copying groups for DEV_STATIC identities. It is development only, and production refuses DEV_STATIC. | Stop it there too. |

## B. Downloads that need approval (pinned versions, licence-checked, logged in DOWNLOADS-WAVE4.md)

| ID | Artifact | Licence | Approximate size | For |
|---|---|---|---|---|
| D-1 | `org.eclipse.jgit:org.eclipse.jgit:7.8.0.202609011348-r` and `com.googlecode.javaewah:JavaEWAH:1.2.3`, fetched again through `central`. The current `~/.m2` copy is recorded under another repository id, so the offline build refuses it. | EDL-1.0 / Apache-2.0 | 3.6 MB | M-100 Git package sync |
| D-2 | `ca.uhn.hapi.fhir:hapi-fhir-validation`, `hapi-fhir-structures-r4`, `hapi-fhir-validation-resources-r4`, plus the US Core and PAS IG packages | Apache-2.0; HL7 IG licence | about 60 MB | M-133 profile validation |
| D-3 | `@microsoft/teams-js` 2.x (exact version pinned at download) | MIT | 1-2 MB | M-109 Teams app |
| D-4 | `org.apache.camel:camel-core-engine:4.8.0` and its dependency closure | Apache-2.0 | large | M-105 Camel |

## C. Read-only online lookups of public specifications (no download kept, no terms accepted)

1. The DMN 1.6 namespace URI (OMG formal/25-12-02), for M-126.
2. The Arazzo 1.1.0 changes from 1.0.1 (OAI repository), for M-069.
3. The Temporal HTTP API, for M-070.
4. The current A2A and MCP specification versions, for M-129.
5. The ABDM Fidelius encryption specification, for M-133.
6. The CCA eSign 2.1 API signature format, for RM-07.

## D. Integrator actions already done (no question needed)

- nimbus-jose-jwt is now managed in the root pom.
- Bouncy Castle is now managed in the root pom.
- The ResourceRegistry defect is fixed (SL-182).
- Every lane request to the integrator has been answered on the board.

## E. Added after the batch was sent (W4-A, W4-C, W4-M final reports)

| ID | Item | Recommended | Alternatives |
|---|---|---|---|
| W4Q-38 | RM-49 and engine audit of untenanted work | Keep the SL-86 rule: engine audits of untenanted work go to `sangam.tenant.default`. Only tighten it so a blank engine tenant is treated like null. Core now refuses null/blank tenants for every sink. | Extend RM-49 to the engine: every untenanted process, job or incident fails to audit, which breaks shared definitions. |
| W4Q-39 | W4-K `Vars.tenant` falls back to the `tenantId` process variable | Pack audit and connector calls use only the engine tenant, so a case variable can never choose a tenant. | Keep the fallback. |
| W4Q-40 | 12 REVIEW REQUIRED register components (paranamer, 6 Jersey jars, @carbon/grid, @carbon/layout, @ibm/telemetry-js; the 2 @fontsource rows are gone) | Decide each row from its licence evidence in the E16 register. Any that cannot be cleared are removed from the shipped closure. | Remove all 12 from the closure. |
| W4Q-41 | Semgrep release-gate paths | Security, rest, billing, ledger and control-plane audit, as W4-C chose. | You give the list. |
| W4Q-42 | cyclonedx-maven-plugin version | Keep 2.9.1 (decision 134). | 2.9.3, with a download. |
| W4Q-43 | Vendor signatures of the CI tool checksum files | Add cosign/GPG verification in CI: the cosign-installer action is already pinned, and CI downloads the tool at run time. | HTTPS-only for 0.1.0. |
| W4Q-44 | Oracle Free in compose has no acceptance variable | Add a `SANGAM_ACCEPT_ORACLE_FREE_TERMS` guard to that profile, the same pattern as decision 122. | Opt-in profile only. |
| W4Q-45 | Java SDK on Maven Central | Add a publish job to `publish-packages.yml`: dry run by default, real publish only through the `package-release` environment. Publishing itself is always your action (decision 112). | Not in 0.1.0. |
| W4Q-46 | M-022 Go and Kotlin compile gates | Let CI compile them. Decision 106 already approves Go 1.22.10, kotlin-maven-plugin 2.0.21 and openapi-generator-cli 7.10.0 for CI. Nothing is downloaded locally. | Download locally now. |

Downloads named by W4-C (decision 134 batch; the exact list is in the W4-C report on the board):
- the tools lockfile;
- the SPDX licence texts;
- 27 base-image digests;
- the script-worker nsjail and kafel commits and noble package versions;
- the .NET, composer and typescript-nestjs lockfiles;
- PyPI build 1.2.2.post1 and twine 6.1.0;
- the Playwright browsers.

## Status 2026-11-12T18:31:17Z
All W4Q-01..46 answered by the owner in chat (re-asked in groups). Answers recorded as decisions 138-185 in OWNER-DECISIONS-2026-11-12.md; W4Q-17 Camel deferred by decision 137. Downloads: D-1 JGit done, D-2 HAPI done with licence flags (decision 183), D-3 teams-js done and adopted (decision 162), D-4 Camel deferred.
