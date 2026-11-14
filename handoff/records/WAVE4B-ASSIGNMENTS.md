# Wave 4b assignments (integrator, 2026-11-13)

Source of truth: owner decisions 137-189 in `OWNER-DECISIONS-2026-11-12.md`. Read every decision named in your section IN FULL before coding; the requirement lists there are binding, not this summary.

## Rules (all lanes, in addition to WAVE4-LANE-BRIEF.md rules 1-14 and AGENT-SYNC-RULES.md)
1. CLAIM in FILE-CLAIMS.md before editing; CHANGE line on WAVE4-SYNC-BOARD.md before, DONE after (5 parts split by " | ", no pipe inside text). Files owned by another lane: REQUEST/ACK only.
2. Code and unit tests written, NOT run (coding phase). Only the offline compile gate runs: `.\mvnw.cmd -q -B -o clean test-compile -pl <modules> -am "-Dsangam.target=target-<lane>" "-Duser.timezone=UTC"` with JAVA_HOME as in the brief. Find consumers with a filesystem grep (untracked files included), never only git grep.
3. Status words: CODED (compiles, tests written), PARTIAL, BLOCKED, NOT_RUN. Never PASS for unexecuted checks. Oracle and SQL Server verification is required by most decisions: write those tests, mark them NOT_RUN.
4. No item closes on compilation alone. Never weaken an assertion. Data/schema only through generators (W4-G owns generate_changelogs.py; send table/column REQUESTs).
5. Framework independence (decision 137(4)): business rules in core/shared modules; Spring/Quarkus only in adapters; optional modules (eSign BC, health/HAPI, Teams, Quarkus, OCR, pgvector, Temporal) never become mandatory; absence keeps unrelated features working and keeps required controls fail-closed.
6. Downloads only under decision 137(2), recorded first in DOWNLOADS-WAVE4.md (exact version, source, licence evidence, transitives). No install scripts, no terms accepted. Secrets never typed, printed or read.
7. Below 97% confidence on any point: stop that point, post INFO with evidence and the exact question; continue the rest.
8. No commit, no push. Report: per item status, files with SHA-256, gate command and exit code, open REQUESTs.

## W4-A money and audit (a5e956d09c23c36f3)
- 141 RM-03: ACCEPT_UNVERIFIED channel/tenant/office scoped, at the application-service boundary (REST extra layer), PAYMENT_ACCEPTED_UNVERIFIED pending state, downstream restrictions, reconciliation/ageing/resolution, atomic recheck, duplicate prevention. Bind the UnverifiedPaymentGate to it. Security model part via REQUEST to W4-B.
- 142 RM-08: single framework-independent power-limit evaluator in sangam-core, versioned contract DENY/LIMITED/UNLIMITED, monetary from trusted case-type metadata, all constraints must pass, re-read at apply, audit fields, map both old contracts with a behaviour-difference inventory. Coordinate W4-B (security service caller) and W4-K (packs listener caller).

## W4-B security (a27411da5b8ae3415)
- 143 RM-06: one shared framework-independent environment-policy resolver; full combination matrix; registry and all modules consume it.
- 152 SL-175 DEV_STATIC bounds.
- 141 support: ACCEPT_UNVERIFIED action and scopes in the existing authorisation model (document why no PAYMENT_CHANNEL resource, or why one is needed).
- 145 shared tenant-resolution policy (framework-independent) used by all execution paths; W4-K removes the Vars fallback on top of it, W4-E uses it for 144.
- 166 M-083: remote deny-only policy check at command entry, outside lock scope, fail closed when required.
- 154 support: grant W4-I the SCIM code in sangam-security or code the security side on REQUEST.

## W4-C supply chain (a8dd92c3abdbcaed8)
- 176 per-evidence decisions for the 12 REVIEW REQUIRED rows; 177 Semgrep paths widened; 178 cyclonedx 2.9.1 scoped fetch and pin; 179 cosign/GPG verification in CI; 180 SANGAM_ACCEPT_ORACLE_FREE_TERMS; 181 publish job (dry run, package-release env, groupId per decision 105); 182 Go support recheck online, propose to owner via integrator, do not change 106; 185 versions-maven-plugin latest stable scoped fetch and pin; 188 pgvector image evaluation record (exact tag and digest) then fetch.
- Integrator ruling: db-matrix.yml SQL Server lanes compare SANGAM_ACCEPT_MSSQL_EULA to `Y`.
- Open W4-M REQUESTs: ci-build.yml cmp list adds sangam-pdf-validator-api.yaml; sdks.yml tier2-rust `cargo test --locked`. dependabot.yml (C-29). decision-register.json rows (operator closure, pdf-validator closure, BC confined to signing module).

## W4-D sidecars (af73b6515e0080be6)
- 153 validator shared-token auth over HTTPS behind a replaceable interface (server and RemotePdfValidator client); 157 Ubuntu snapshot apt pinning (NOT_RUN until first build); 167 own OCR sidecar on the same pattern with a fail-closed client; 184 simulator launcher in sangam-integration (grant: new launcher package plus the simulators-app profile in sangam-integration/pom.xml; REQUEST W4-H for anything else there) implementing the simulators.Dockerfile contract.

## W4-E engine (ac43365ca5d6b7bb8)
- 138 engine decimal variable type (versioned id, lossless text, registered on every engine host) with W4-K for Vars/delegates; Double-variable migration inventory.
- 144 engine adapter system-audit-scope resolution by metadata; 146 M-056; 147 fetch-lock verification tests plus deferred-adapter acceptance criteria; 148 D-27 reproduction and root-cause fix; 149 M-060 (table REQUEST to W4-G); 150 M-114 inventory and changesets with W4-G; 151 replay capture side (fixture schema agreed with W4-G first); 156 CockroachDB dialect mapping plus 40001 retry; 158 DMN 1.6; 168 FC-18 and EC-06.

## W4-F hosts (abcde7a1949f91488)
- Finish M-141c (ratchet to 0). Then 186 M-143 Quarkus host as an optional adapter (CAPABILITY_MISSING), no Quarkus dependency anywhere else; download records under rule 6 if needed.

## W4-G persistence and modeller (ae54fd78040d646e1)
- Generator changesets for 149, 150 (reversible, idempotent, dry run), 154 SCIM table, 160 M-131 (legacy:<job> chain, HRM_* with source marker), any table REQUESTs from other lanes. 151 reproducer/comparison side; 159 importer emits operationRef tasks; 169 M-135 Flowable pipeline on a synthetic corpus.

## W4-H integration (a3529b5ce32797c9a)
- 140 RM-07 eSign verification (ESP XML and CMS/PKCS#7, binding, trust anchors, revocation, three outcomes, evidence; BC only in the signing module; EsignConnector must stop accepting unverified signatures). 165 DSC loopback agent with server verification under 140. M-100 JGit lands now (D-1 done; apply W4H-STAGED). 159 Arazzo operationRef runtime step. 163 Temporal start-and-await. 189 SL-170 Event Grid inbound and Service Bus binary mode. Camel stays deferred.

## W4-I platform and ops (a8ea3668dde738388)
- 154 M-099 SCIM (postings read-only, 400 mutability). 155 M-102 backup console (list, trigger, history; shared KubeHttp; Role off by default). M-092 remaining part.

## W4-J UI (a2fd12bc8d9ca16e6)
- 161 own CMMN 1.1 editor on diagram-js 14.11.3 with XML fallback. 162 Teams app as optional module, pop-up OIDC, teams-js 2.59.0 from the recorded tarballs with --ignore-scripts. Fix the presence key mismatch (presence by package id vs LeaseBanner by file name). UI never turns decimal strings into floats (138).

## W4-K domain packs (a8c9a77cec05f224c)
- 138 Vars/SetVariables/ManifestPack/JSON reader/delegates never produce Double; pack test engines. 139 rounding policy per calculation (instalment, TCS, PF, ESI separately, refund due, bill amount, P2P amount) researched from repo sources then authoritative online sources; UNVERIFIED/CONFLICTING blocks production; versioned policies kept on each transaction. 145 remove the tenantId variable fallback. 142 packs listener caller migration. 170 pgvector and Oracle native vector adapters (off by default). 171 A2A v1.0.1 and MCP 2026-07-28. 172 CAQH headers gap. 173 Fidelius. 183 HAPI in an optional module with the exclusions/overrides and smoke proof. 187 make sangam-connectors-health optional, off by default. 175 PFM order and IFC-189 bumps through packgen.

## W4-M contracts and SDK (acdbbc4c7815ec9e6)
- Contracts and events for every new surface: partition resize and EXECUTOR_PARTITION_COUNT_CHANGED, ACCEPT_UNVERIFIED and payment states, decimal type in API/SDKs (string, never float), power-limit decision, SCIM, backup console, validator auth, A2A/MCP, Event Grid inbound, continue-as-new. 164 Robot Framework worker example in the SDK. 181 SDK groupId per decision 105. M-022 remaining.


# Round 2 (2026-11-13T00:10:30Z): lane follow-ups after the Wave 4b reports
Read owner decisions 190-220 IN FULL first; they answer your lane's questions. Same rules as above. Do not touch files INT-H2 has claimed (check FILE-CLAIMS); INT-H2 does root-pom module registration, the W4-I parked files, SANGAM.md audit names, decision 143 test-setup fallout and register rows.

- W4-A: 193-196 and 201 into UnverifiedPayments/BillingService (accrual pause, manual VERIFIED with evidence plus maker-checker plus its own audit event, signed-in staff only); fix the list bug W4-M found (500-row cap before filtering, page/size ignored); move to the manifest metadata source (193) with W4-K.
- W4-B: 198 (production wins, warning), 199 (keep both refusals), 200 (deny-only check also on /engine-rest and worker calls); move EngineAssembly, SangamRestAutoConfiguration, OperatonDebugAdapter production checks to the shared resolver if W4-F has not (REQUEST/ACK with W4-F first).
- W4-C: 190 Go floor 1.26/CI 1.27.4 (sdks.yml, go.mod, docs); 191 record the two checksum-only tools; 192 dependabot.yml; set SANGAM_SECURITY_PRODUCTION explicitly in compose, .env.example, READMEs and Helm (W4-B request); W4-D deploy requests (validator token and TLS secrets, network policy, demo credentials; OCR service, image and release job; UBUNTU_SNAPSHOT build arg; simulators port 2525); W4-J licence rows for teams-js deps and diagram-js; W4-I Helm backup.console.enabled with Role, RoleBinding, NetworkPolicy; 213 Teams frame-ancestors deploy setting (look up Microsoft's published host list, record it) and nginx headers for /teams.html only; teams.enabled in /config.json from SANGAM_TEAMS_ENABLED; paranamer copyright lines into the notices bundle.
- W4-E: 197 shared-deployment registry and 144; 149 coding (table 0044-02 exists, permission process.continue mapped); 150 engine ACT_* part as the operator tool (203) with W4-G; 151 capture with the 10th argument activityInstanceId; 146 (W4-M contract done); 147; 168 FC-18 and EC-06; 205 CockroachDB; 206 then 158 DMN 1.6; W4-K's exact FEEL value mapper; W4-H's ExternalWork properties for the Arazzo topic sangam.operation.
- W4-F: continue M-143 (D-5).
- W4-G: 203 the operator-run UTC conversion tool (dry run, snapshot, reconcile, run records); 204 YAML import via SnakeYAML 2.4 safe loading; embedding_vec column for W4-K if still open; W4-H ACK on 159 names is done.
- W4-H: 217 move signing code to a new optional module sangam-esign (Bouncy Castle only there; add the module through a REQUEST to the integrator for the root pom line); 218 Service Bus quoting; 219 Event Grid Entra delivery tokens; 216 Temporal lookup and finish; register TemporalConnector and ArazzoOperationStep wiring in the server via REQUEST if outside your files.
- W4-I: 220 VM/compose backup adapter; SCIM server files ScimController, ScimWiring, ScimAcceptanceIT are granted to you (check claims first).
- W4-J: 212 MyWork numbers as decimal text (coordinate PacketHasher change with its owner); 214 remove the meta CSP; 213 frame-ancestors consumption; UI calls to the DSC loopback agent and its CSP connect-src entry (W4-H request); decimal type name from W4-M (DecimalString).
- W4-K: 193 manifest monetary metadata and PowerLimitGuardListener migration to the new evaluator; 202 rule adoption (start rule first); 207 Oracle vector adapter after the docs lookup; 208 Fidelius via fidelius-cli test vectors after a licence check; 209 xpp3 licence reading then HAPI per 183; 175 PFM order and IFC-189 bumps; per-employee PF/ESI computation (the total-payroll placeholder must go); keep every SYNTHETIC_DEMO rounding policy blocked for production.
- W4-M: 210 groupId io.github.sqamentor everywhere; 211 money as DecimalString in API and all SDKs (Task.amount); W4-A list paging; contract updates for 195/196/201, 200, 219 and 220; /a2a contract and 503 CAPABILITY_MISSING for 187 (W4-K request).
