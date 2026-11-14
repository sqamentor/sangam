# Wave 4 owner questions (pending batch)

## W4-E (done, waits)
- Q1 M-056 partition re-cut: (A rec) minimal movement, non-contiguous slot runs; (B) contiguous re-cut. Needs route POST /admin/executor/partitions + audit EXECUTOR_PARTITION_COUNT_CHANGED.
- Q2 M-106 D-19 SKIP LOCKED external-task fetch: (A rec) keep SANGAM_FETCH_LOCK interim for 0.1.0, per-DB statement later; (B) write now.
- Q3 M-106 D-27 MI compensation without loopCounter: (A rec) repro test now, fix after test phase; (B) fix now.
- Q4 M-060 continue-as-new: (A rec) spec row first then single-tx engine command; (B) extend LinkedInstanceDelegate.
- Q5 M-114 UTC: (A rec) strict-UTC JVM rule as mechanism + reversible conversion changesets (W4-G) with operator source zone; (B) prebuild SqlSessionFactory; (C) UTC-calendar DataSource wrapper.
- Q6 M-136 replay fixtures: (A rec) split ownership (engine capture W4-E, modeller diff/reproducer W4-G), agree 0.1.0 input kinds; (B) W4-E all.

## W4-I (done)
- M-099 SCIM postings: (A rec) postings read-only in SCIM (400 mutability), SCIM only persons/groups/memberships; separate SCIM table via W4-G; code in sangam-security (W4-B grant); (B) SCIM posting -> pending assignment needing IX.5 approval; (C) apply directly.
- M-102 backup console: scope (A rec) list targets, trigger now, run history, restore stays offline procedure; (B) + restore with two-person approval. K8s access: (A rec) shared KubeHttp module, namespaced Role off by default; (B) server never calls k8s, operator triggers.
- M-108 CockroachDB/YugabyteDB: (A rec) defer past 0.1.0, mark unsupported; (B) Yugabyte only as PostgreSQL; (C) both with engine patch + 40001 retry.

## W4-D (done)
- Validator auth: (A rec) no auth, internal network only (W4-C keeps the port unpublished); (B) shared-token header.
- Script-worker apt pinning of transitive deps: (A rec) Ubuntu snapshot archive (syntax below 97%: verify in test phase); (B) a build arg per dependency.
- Node LICENSE path /usr/local/LICENSE: confirm at first test-phase build (no question, test-phase item).

## W4-G
- M-126 DMN: who codes engine-side (A rec: W4-E once the integrator supplies the DMN 1.6 namespace URI from OMG formal/25-12-02; B: grant W4-G sangam-engine/feel). 1.6 URI needs an online lookup (approval).
- M-069 Arazzo runtime: (A rec) new runtime step in sangam-integration that runs a pinned operationRef via ContractOperations; importer emits operationRef tasks; (B) importer emits HTTP tasks with literal paths; (C) authoring-only import. Arazzo 1.1.0 delta needs an online lookup.
- M-131 legacy history: in scope? owner of sangam-ops? (A rec) own audit chain legacy:<job> + new event names; ACT_HI_* -> HRM_* with source marker column; (B) read-only view over OPS_MIG_ROW only.

## W4-J (done; resume later for M-125 compare view + M-101 once W4-M contract lands)
- M-041 CMMN editor: (A rec) own CMMN 1.1 editor on installed diagram-js 14.11.3, XML editor fallback (XL, own lane); (B) fork cmmn-js 0.20.0 (download, unmaintained); (C) XML editor only in 0.1.0.
- M-109 Teams sign-in: (A rec) Teams pop-up reusing OIDC (oidc-client-ts), any IdP; (B) Teams SSO + IdP token exchange (Entra only); (C) defer. A/B need @microsoft/teams-js 2.x (MIT) download.

## W4-H (done)
- Camel (M-105): (A) optional module camel-core-engine 4.8.0 (download); (B rec?) drop from 0.1.0.
- M-070 Temporal: (A rec) integrator supplies Temporal API evidence (online lookup), then start-and-await connector workflowId=operationId; (B) generic HTTP start-and-poll; (C) defer.
- M-104 RPA: (A) Robot Framework worker in SDK; (B) orchestrator REST adapters; (C) generic external-worker pattern only.
- M-079 DSC: (A rec) loopback desktop agent with SunPKCS11, server verifies via PdfSignatureValidator; (B) server-side HSM PKCS#11; (C) ESP eSign only.
- M-083 OPA/Cedar: (A) in-process (cedar-java native / OPA wasm; download); (B) remote check only at command entry outside lock; (C) drop from 0.1.0.
- M-103 OCR: (A rec) own OCR sidecar like decision 114, fail-closed client; (B) third-party tesseract-server image; (C) tess4j in-process (download).
- JGit (M-100): ~/.m2 copy recorded under repo id central-8fac3ebd so offline reactor refuses it: (A rec) re-fetch JGit 7.8.0 + JavaEWAH 1.2.3 through central (approval for a download, ~3.6 MB, EDL-1.0/Apache-2.0); (B) defer M-100.

## W4-K (done; resumed for RM-36)
- M-107 CMMN plan-item extensions: (A rec) keep P-4/P-5/FC-18 as documented gaps for 0.1.0 with run-off; (B) engine patch after M-106 (XL).
- M-135 Flowable live migration: (A) owner supplies a Flowable corpus; (B) defer.
- M-129 native vector index: (A rec) portable only for 0.1.0; (B) Oracle + pgvector (new image download, W4-G column); (C) pgvector only.
- M-129 A2A/MCP: (A) owner gives spec versions; (B) defer.
- M-133 CAQH CORE envelope: (A) owner obtains spec (accepting CAQH terms is owner's act); (B) HTTP with CORE headers, documented gap.
- M-133 Fidelius (ABDM): (A) owner provides spec; (B) gap.
- M-133 US-HLT-061 Patient Access PA info: (A) next pack later wave; (B) traceability only.
- M-134 remaining PFM rows priority + IFC-189 version bumps of existing packs: approve or not.
- Download: hapi-fhir-validation + structures-r4 + validation-resources-r4 (Apache-2.0, ~60 MB) + US Core/PAS IG packages (HL7 licence) for profile validation.

## W4-A (done; resumed for RM-49)
- RM-03 who approves unverified payment: (A rec) per-channel permission billing.channel.<channel> via PermissionPort in BillingController, refused from pack steps; (B) new resource type PAYMENT_CHANNEL / ACCEPT_UNVERIFIED in sangam-security.
- RM-08 one power-limit rule: (rec) DMN contract maxAmount/UNLIMITED/allowed wins; single evaluator in sangam-core; case types declare monetary so a missing amount fails; packs listener gets the posting cap. Work then to W4-B + W4-K.

## W4-K RM-36 (P0, blocked)
- Exact decimals in engine: (A rec) new engine variable type storing BigDecimal as text (W4-E registers in plugin, W4-K Vars/SetVariables/ManifestPack/JSON reader + pack test engines); (B) money only as whole minor units, fail loudly on fractional money, ratios stay Double; (C) defer.
- Rounding of 6 JUEL money calcs (instalment, TCS, PF/ESI, refundDue, billAmount, P2P amount): (A) owner gives per-calc rules; (B) half-up to whole minor unit, marked verify.

## W4-B (done)
- RM-06 production detection: (A rec) keep registry dev-profile list AND ProductionMode (no profile + no flag stays refused); (B) ProductionMode only.
- RM-07 eSign verification: (A) BC required dep + PKCS#7 digest, ESP XML sig, chain vs sangam.esign.trust-anchors, simulator test CA (needs owner trust anchors/revocation answer + CCA eSign 2.1 spec); (B) JDK only: ESP XML sig + chain; per-document sig via PDF validation later; (C) move to 0.2.
- SL-175 scope: DEV_STATIC identities still copy groups as IdP memberships: keep? (rec keep, dev only)
- nimbus-jose-jwt 10.9.1 test pin: integrator moves to root dependencyManagement (no owner question; integrator action).
