# Audit X5: Wave 2 sync board reconciliation (read-only), 2026-11-12

This audit supports the Wave 2 close-out gate (AGENT-SYNC-RULES.md rule 10). It is read-only: no file was edited other than this report, and no build, test, commit or stash was run.

Inputs:
- `WAVE2-SYNC-BOARD.md` (256 entry lines, read through line 256);
- `FILE-CLAIMS.md` (1082 lines);
- `WAVE2-CARRY-2026-11-12.md`;
- `BACKLOG-0.1.0-MASTER.md`;
- `backlog-status.json` (written 10:42:54Z).

Tree: `D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524` (HEAD 0e53f91b plus the uncommitted Wave 2 work).

Method:
- I classified every REQUEST and BROKEN entry by hand against the later board lines.
- A script took the SHA-256 values from every DONE and CHANGE+DONE line (167 file/hash pairs), resolved each file in the tree and hashed it.
- A second script matched every Wave 2 CLAIM in FILE-CLAIMS to a later RELEASE by the same worker. I expanded brace lists and reviewed each residual by hand.
- Greps confirmed in the tree each requested change named below.

## 0. Headline

| Kind | Total | CLOSED | DEFERRED-BY-PLAN | OPEN |
|---|---|---|---|---|
| REQUEST | 60 | 53 | 5 | 2 (both partial) |
| BROKEN | 11 | 11 | 0 | 0 |
| **All** | **71** | **64** | **5** | **2** |

- CHANGE entries: 48 CHANGE and 3 CHANGE+DONE. **Every one has a DONE.**
- SHA-256 spot-check: of 167 board hashes, 131 match the live tree. The other 36 differ, and a later board DONE accounts for each of them. **No unexplained mismatch.**
- Claims: 156 Wave 2 CLAIMs. **None is truly unreleased.** Two have no file-specific RELEASE line. One integrator STANDING-CLAIM is permanent by design.
- Status gaps: every Wave 2 M-id has a status. **Five or more are stale**, and four contract-first M-ids have no lane (section 5).

**The two OPEN items block the close-out gate under rule 10.**
- **REQUEST line 40, second half:** remove the deprecated `SlaService` Outbox constructor. Owner: W2-N.
- **REQUEST line 119, second half:** document the SL-158 binding in the worker/SDK guides. Owner: W2-J.

There are also unrouted findings outside the REQUEST/BROKEN types. These are not gate blockers by the letter of the rule, but they have no owner (section 6).

## 1. REQUEST entries (60)

Legend: C = CLOSED (closing lines given); D = DEFERRED-BY-PLAN; O = OPEN.

| Board line | From → owner | Ask (short) | Class | Closing line(s) / evidence |
|---|---|---|---|---|
| 20 | integrator → W2-D, W2-E | publish W2-JOB-SIDE-TABLES-CONTRACT.md | C | 58 DONE (contract F06AAD91; live hash matches); 122 W2-E tables |
| 21 | integrator → W2-D, W2-L | SQL-only storedOperationIdOf | C | 47 DONE (SangamLeaseTable 175EB35C matches); 76 W2-L fold |
| 22 | integrator → W2-H | regenerate AsyncAPI (140 names) | C | 65, 95 DONE (asyncapi F0816DB1 matches) |
| 23 | integrator → W2-A | wiring batch + M-014 | C | 25-29, 79-81 CHANGE; 93 DONE (hashes in FILE-CLAIMS 927/928) |
| 33 | W2-G → integrator | compose prometheus rules mount | C | 36 ACK (done 08:20Z; FILE-CLAIMS 762/763). Tree: docker-compose.yml:353 `./observability/rules:/etc/prometheus/rules:ro` |
| 34 | W2-G → W2-J | deploy README private exposure, prometheusRule | C | 176 DONE (deploy/README.md 445894D4 matches) |
| 35 | W2-G → integrator | kubeconform skip, only if enabled | C | 37 ACK (no change needed) |
| 39 | integrator → W2-L | JdbcTenantKeyStore compile | C | 60 INFO, 70 DONE (A294E8E0 matches) |
| 40 | integrator → W2-A | (a) erasure RelatedAuditChains; (b) delete SlaService deprecated Outbox ctor via W2-N | **O (partial)** | (a) closed by 135. (b) W2-A posted INFO 120 to W2-N ("may remove"); W2-N never answered. The tree still has `SlaService.java:79-80` `@Deprecated(forRemoval = true) public SlaService(Dialect, Transactions, Outbox unused, Function)`. Owner W2-N/B41 |
| 42 | integrator → W2-J | deploy README + server README:346 registry.events | C | 176, 179 DONE |
| 45 | W2-B → W2-D, W2-H | M-062 ExternalWork trace field | C | 115 CHANGE, 153 DONE (contract), 190 DONE (SDK) |
| 46 | W2-B → integrator | .gitattributes eol=lf for generated SDK | C | 88 DONE. Tree: .gitattributes:34-37 holds all 4 lines |
| 52 | W2-N → W2-A | erasure overload in IntegrationWiring | C | 135 DONE (IntegrationWiring 0C33ECA6 matches) |
| 63 | W2-L → W2-A | SL-161 field encryptor on leaf pool | C | 133 DONE (SecurityKeysWiring F01827F0 matches) |
| 64 | W2-L → W2-A | SL-158 WorkerTopicGuard bean | C | 132 DONE (SecurityWiring 58BED012 matches) |
| 68 | W2-H → W2-B | regenerate SDK vs 0B5A695A | C | 86, then 190 (57B0F608), 251 (217C21BE) |
| 75 | W2-K → W2-J, W2-A | document compliance seats | C | 178 DONE (compliance README 72520294 matches); 136 application.yaml (superseded by 183, matches) |
| 82 | W2-C → W2-H, W2-L | /me locale field | C | 116 CHANGE, 154 DONE (contract), 234/253 DONE (server), 256 DONE (wiring) |
| 83 | W2-C → W2-H | 4 contract gaps | C (residual) | 117 CHANGE, 155 DONE for items (1)-(3). For item (4), W2-H answered "not changed, a code question for the modeller owner". That residual is unrouted: see section 6 |
| 90 | integrator → W2-A | process all W2-A requests | C | 132, 133, 134, 135 DONE |
| 91 | integrator → W2-E | side tables + AuditChain.denialChain | C | 122, 124 DONE |
| 92 | integrator → W2-H, W2-D | M-062 field, /me locale, W2-C gaps | C | 153, 154, 155 DONE |
| 97 | W2-H → integrator | M-025 step-journal server side | **D** | Master backlog M-025 row :101: Wave 3, lane **W3-I** (SDK helpers + `sangam-rest` route). Board 107/108 INFO; carry list :33 |
| 98 | W2-H → W2-D + integrator | M-059 engine + controller | **D** | Master M-059 :150: Wave 3, lane **W3-F** Engine features. Board 107/108; carry :33 |
| 99 | W2-H → integrator, W2-E, W2-A | M-074 packages/env-bindings | **D** | Master M-074 :175: Wave 3, lane **W3-G** Packages and persistence. Board 107/108; carry :33 |
| 100 | W2-H → W2-L + integrator | M-099 SCIM | **D** | Master M-099 :215: Wave 4, lane **W4-C** Security and identity. Board 108; carry :33 |
| 105 | integrator → W2-E | side tables, M-025/M-074 tables, denialChain | C | 122, 124 DONE; the M-025/M-074 table part was withdrawn by 108 (it goes to W3-I/W3-G) |
| 106 | integrator → W2-L | traceparent; SCIM | C | traceparent 189 (2nd entry) DONE; the SCIM part was withdrawn by 108 (W4-C) |
| 111 | W2-L → W2-H, W2-A | contract name for trace field | C | 115 CHANGE, 153 DONE; 189 W2-L DONE; 185 W2-A setTraceLinks |
| 114 | W2-K → W2-E, integrator | CMP_BREACH missing cols + plan guard col | C | 139 route, 145 CHANGE, 163 DONE; 219 W2-K uses the column |
| 118 | W2-H → W2-L | M-045 server side | C | 130, 225 ACK; 234 CHANGE; 253 DONE (WorkspacePort A98EECED matches) |
| 119 | W2-A → W2-J | SL-158 ENFORCE docs in sangam-server README **and the worker/SDK guides** | **O (partial)** | 177 DONE covers sangam-server/README.md (:391 `403 WORKER_TOPIC_NOT_BOUND`), the engine README and the security README. A grep of `sangam/sdks/**/*.md` for WORKER_TOPIC_NOT_BOUND, "bound to" or "binding" finds nothing, so the worker/SDK guide part is not done and no line declines it. Owner W2-J (B38); the SDK README files are W2-B's (`sdks/**`), so this needs a grant or a W2-B hand-off |
| 125 | W2-E → W2-L | delegate both copies to AuditChain | C | 128 CHANGE, 189 (1st entry) DONE (SecurityAuditService AB879AB4, DenialAudit A316F161 match) |
| 126 | W2-E → W2-A | erasure uses AuditChain.denialChain | C | 135 DONE |
| 127 | integrator → W2-D | can a node id exceed 64 chars | C | 221 answer (REQUEST to W2-E), 223 CHANGE, 229 DONE |
| 139 | integrator → W2-E | SL-162 cols, plan col, hashedChain | C | 145 CHANGE, 163 DONE (superseded hashes → 229, all match) |
| 140 | integrator → W2-C | X1-01 fiscalYearStartMonth, X1-02 | C | 165 DONE (programme.tsx 193DF128 matches; .ts files superseded by 245, which matches) |
| 141 | integrator → W2-A | X1-05 shared leafPool, X1-10 failsafe argLine | C | 144 CHANGE, 183 DONE (QuotaWiring 39C44B67, pom E0B43478 match) |
| 142 | integrator → W2-L | X1-07 one back-off; SL-158 OperatonWorkAdapter hook | C | 148/149 CHANGE, 189 (3rd entry) DONE; WorkLongPoll superseded by 233 (matches) |
| 143 | integrator → W2-J | X1-09 docs + pending doc requests | C | 180 DONE (billing README 8CB5FE00, rest README E8A60E02 match), 176-179 |
| 147 | integrator → W2-A | SL-160 yaml docs + operaton-juel in engine pom | C | 174 CHANGE, 184 DONE (engine pom D9CD2ACC matches) |
| 150 | W2-L → W2-A | guard on both adapters, long-poll settings, setTraceLinks | C | 172 ACK, 173 CHANGE, 185 DONE, 254 INFO |
| 161 | integrator → W2-B | regenerate vs 57B0F608; TaskContext traceparent | C | 169 ACK, 190 DONE |
| 162 | integrator → W2-L | WorkLongPoll fix, traceContext, Me.locale | C | 175 INFO, 189 DONE (2nd, 3rd), 253 DONE |
| 164 | W2-E → W2-K | ComplianceAudit → AuditChain.hashedChain | C | 208 ACK, 209 CHANGE, 219 DONE (ComplianceAudit 3A54AD46 matches) |
| 166 | W2-C → W2-H, programme owner | GET /programme-config (current config) | **D** | Carry list :36 assigns it to the "programme lane (Wave 3)". Caveat: the master backlog has **no M-id row and no Wave 3 programme lane** for it. The tree has only `PUT /programme-config` and `/programme-config/history` (sangam-api.yaml:33499, :33548). An M-id and lane are needed |
| 193 | integrator → W2-D | X2-01 PATCHES allow-list, X2-03 citations | C | 204 CHANGE, 220 DONE (all 5 hashes match) |
| 194 | integrator → W2-K | X2-02, X2-03 Vars.java, ComplianceAudit | C | 208 ACK, 219 DONE |
| 195 | integrator → W2-L | X2-04 class-size, X2-03 WorkLongPoll:29, Me.locale | C | 224 CHANGE, 233 DONE, 253 DONE |
| 196 | integrator → W2-N | X2-03 topic words | C | 203 CHANGE, 205 DONE (both hashes match) |
| 197 | integrator → W2-F | X2-05, X2-09, fiscalStartDay | C | 227, 215 CHANGE, 226 DONE |
| 198 | integrator → W2-B | X2-04 split, X2-03, X2-08 | C | 230 ACK, 231 CHANGE, 232 INFO, 237 DONE |
| 199 | integrator → W2-H | X2-06 AgentUsage, fiscalStartDay contract | C | 212 INFO, 213 CHANGE, 238 DONE, 214 ACK, 239 DONE; 240 owner decision, 249, 252 |
| 200 | integrator → W2-J | ADR-D-27 topic words | C | 206 CHANGE, 210 DONE (decision-topics.txt 3DE3D595 matches) |
| 201 | integrator → W2-C | fiscalStartDay forms | C | 207 INFO (scope), 245 DONE (all 8 hashes match) |
| 216 | W2-F → integrator | grant ReturnCampaignDelegate, ProgrammeRestAdapter | C | 228 ACK (granted), 248 DONE |
| 217 | W2-F → W2-H, W2-B, W2-C | fiscalStartDay in contract, SDK, UI | C | 239 (W2-H), 251 (W2-B), 245 (W2-C) |
| 221 | W2-D → W2-E | widen owner_node to 255 | C | 223 CHANGE, 229 DONE (all 8 hashes match) |
| 241 | integrator → W2-B | regenerate vs 217C21BE; .NET one file per class | C | 242 CHANGE, 251 DONE |
| 253 (3rd entry) | W2-L → W2-A | setProfiles on workspace adapter | C | 255 CHANGE, 256 DONE (SangamRestAutoConfiguration 03886824 matches) |

## 2. BROKEN entries (11): all CLOSED

| Board line | Reporter → owner | Break | Closing line(s) |
|---|---|---|---|
| 18 | integrator → W2-A | PortsWiring:493 Outbox vs EventSink | 25 CHANGE, 38 INFO, 73 DONE (PortsWiring 54A44401 matches) |
| 26 | W2-A → W2-L | JdbcTenantKeyStore:[90,85] | 60 INFO, 70 DONE, 73 (gate green) |
| 121 | W2-A → W2-E | DenialChainFormulaTest, denialChain missing | 124 DONE, 135 ("cleared") |
| 152 | W2-A → W2-E | DenialChainFormulaTest, hashedChain missing | 163 DONE, 186 INFO ("cleared") |
| 156 | W2-H → W2-L | WorkLongPoll:150 duplicate settings() | 175 INFO, 189 (3rd entry) DONE, 188 |
| 159 | W2-E → W2-L | same WorkLongPoll break | 188 DONE ("my BROKEN cleared") |
| 218 | W2-K → W2-L | OperatonEngineAdapter:1088 RouteCorrection missing | 233 DONE (RouteCorrection CD6F91AB matches); 251 server gate green. W2-K never posted its own clear |
| 222 | W2-D → W2-L | same RouteCorrection break | 233 DONE |
| 236 | W2-B → W2-L | TenantLocales:50 subject() private | 253 (1st, 2nd entries), 250, 251 (server gate green) |
| 244 | W2-F → W2-L | TenantLocales:51 | 246 update, 250 INFO ("resolved") |
| 246 | W2-F → W2-L | TenantLocalesTest:65 ambiguous of() | 250 INFO, 253 (2nd entry) |

## 3. CHANGE entries: all have a DONE

| CHANGE line | DONE line |
|---|---|
| 25 | 73 |
| 27, 28, 29 | 93 (hashes in FILE-CLAIMS 927/928) |
| 30 | 32 |
| 44 | 86 |
| 48, 50, 51 | 54 |
| 61, 62 | 76 |
| 74 | 87 |
| 77, 78 | 94 |
| 79, 80, 81 | 93 (80 also 136) |
| 109 | 146 |
| 110 | 122, 124 |
| 113 | 131 |
| 115 | 153 |
| 116 | 154 |
| 117 | 155 |
| 128 | 189 (1st entry) |
| 129 | 189 (2nd entry) |
| 144 | 183 |
| 145 | 163 |
| 148, 149 | 189 (3rd entry) |
| 157 | 187 |
| 170 | 190 |
| 171 | 191 |
| 173 | 185 |
| 174 | 184 |
| 203 | 205 |
| 204 | 220 |
| 206 | 210 |
| 209 | 219 |
| 213 | 238 |
| 215 | 226 |
| 223 | 229 |
| 224 | 233 |
| 231 | 237 |
| 234 | 253 (1st entry) |
| 235 | 248 |
| 242 | 251 |
| 243 | 249 |
| 255 | 256 |

Lines 66, 67 and 102 are CHANGE+DONE and close themselves.

**CHANGEs without a DONE: none.**

Consumer asks embedded in CHANGE lines got no answer on the board. They are not REQUEST entries, so they are noted rather than counted:
- **CHANGE 79 → W2-I:** "make T35RestoreIT:146 skip -plain.jar explicitly". No W2-I entry appears anywhere on the board. The tree has it: `T35RestoreIT.java:147` `!...endsWith("-plain.jar")`. The work is done; only the board record is missing.
- **CHANGE 80 and 144 → W2-G:** Helm/compose env for the new keys. W2-G never answered. A grep of `sangam/deploy/**` finds no `SANGAM_QUOTA_POOL_SIZE`, no `SANGAM_TRACING_ENGINE_ENABLED`, and no ConfigMap entry for `SANGAM_SECURITY_DENIAL_AUDIT_POOL_SIZE` (only deploy/README.md:360 text). Defaults apply, so nothing breaks. If the chart should expose these keys, that needs an owner decision.
- **CHANGE+DONE 67 (SL-142 parked inbound):** "controller to be written by the owning lane: `ParkedInboundController` (W2-A/integrator to route)". It was never routed. The carry list does not include it. The only Java match for `parked-inbound` in the tree is the generated SDK `OpsReconciliationApi.java`, so the server serves no `/ops/reconciliation/parked-inbound` routes. backlog-status M-008 also says "replay controller open". **This needs a lane and a wave.**

## 4. SHA-256 spot-check (live tree)

- **Pairs checked:** 167 file/hash pairs from all DONE and CHANGE+DONE lines. This includes the composite lines 189 and 253 and the 16-hex prefixes on line 163.
- **MATCH:** 131.
- **Superseded by a later DONE for the same name:** 27. In each of these, the live hash is the later DONE's hash and also appears in FILE-CLAIMS.
- **Flagged as mismatches by name, but explained by a later DONE that uses a path-qualified name:** 9.
  - Line 163 (generate_changelogs.py, the 5 baselines, SangamSchema.java) is superseded by line 229. Live hashes: 68852C97, PG B3A0699F, Oracle 34076520, MSSQL A1DEE257, MySQL C78E5FE6, H2 042F9700, SangamSchema 22B48C36. All equal the line 229 values.
  - Line 165 (programmeConfig.ts, registryNumbering.ts) is superseded by line 245 (35B0635C, FA5E1604 match).
- **Unexplained mismatches: 0.**
- **Hash checks for the OPEN items:**
  - SlaService.java is still 393A5569… (board 54) and still has the deprecated constructor.
  - sangam-server/README.md is 08AC2FDB… (board 177, matches). SDK READMEs: no SL-158 text.
- **Grep confirmation of the main requested code changes:** `AuditChain.hashedChain` (ComplianceAudit), `AuditChain.denialChain` (SecurityAuditService, DenialAudit, IntegrationWiring), `setTopicGuard`, `setLongPollSettings`, `setTraceLinks`, `setProfiles` (SangamRestAutoConfiguration, OperatonWorkspaceAdapter), `JobTraceContexts.read` (EngineAdapterWorkAdapter), and `leafPool(` (QuotaWiring, SecurityAuditPoolWiring, ComplianceWiring). All are present in `src/main`.

## 5. FILE-CLAIMS: unreleased claims (Wave 2 workers B29-B41, X1/X2/X3, integrator)

- **Volume:** 156 CLAIM lines and 234 RELEASE lines for these workers. X1, X2 and X3 have no lines.
- **Every claim has a later RELEASE by the same worker that names the file, or a blanket "holds no claims" RELEASE.** Two have no file-specific RELEASE:

| Claim line | Worker | File | Finding |
|---|---|---|---|
| 852 | B32 (W2-D) | `sangam-engine/.../deploy/SangamBpmnParseListener.java` | Not in RELEASE 946; that release ends "B32 holds no claims". The file is unmodified against HEAD (git status clean), so the claim was released without an edit. Formal gap only |
| 945 | B29 (W2-A) | `sangam-server/.../config/AuditCheckpointWiring.java` | Not in RELEASE 964 (which names the new AuditVerifyWiring instead); 964 ends "B29 holds no claims". The live hash C5D687F6… equals RELEASE 928, so the file was not changed under claim 945. Formal gap only |

- **Standing claim:** line 1021 `INTEGRATOR STANDING-CLAIM sangam/TESTING-GAP-REGISTER.md and sangam-handoff trackers`. It is permanent by design (integrator-only files). The gate should list it as an accepted exception, because rule 10 says no lane may hold a claim at checkpoint.
- **Lanes reporting no open claims:** B29 (line 256 on the board and FILE-CLAIMS 964/1082), B32 (946, 1036), B30 (922), and B34 (the RELEASE of "all claims above").

## 6. Unrouted findings (not REQUEST or BROKEN, no owner on the board)

1. **INFO 69 (W2-H), security, SSRF-shaped.** `ExternalLayerGateway.query` lets a caller-supplied `baseUrl` steer the outbound WFS/WMS request. The entry said "integrator to register and route (geo owner)". The board has no follow-up, and TESTING-GAP-REGISTER has no geo/baseUrl row. **The tree still has the issue:** `sangam-geo/.../ExternalLayerGateway.java:53` `in.putIfAbsent("baseUrl", x.baseUrl())`, and :55-59 also use putIfAbsent. **This is the highest-risk loose end.**
2. **INFO 84 (W2-C) server findings were never routed:**
   - the tenant admin lacks `form.read`;
   - portal citizens cannot read custom fields;
   - actions are permission-checked without the subject (ModellerWiring:117-119);
   - REST targets start with `/` at publish but dispatch splits on `connectorId:operation`;
   - a non-ASCII purpose cannot travel in the header;
   - meeting-notice ack returns 204 for any id.
3. **REQUEST 83 item (4).** `ModellerController.RunActionRequest.reason` is still never read (ModellerController.java:103 declares it, :326-333 do not use it). W2-H passed it to "the modeller owner", but no owner was named.
4. **SL-142 `ParkedInboundController`.** The contract routes exist; the server controller does not (section 3).
5. **Board hygiene:** lines 189 and 253 each hold three entries run together on one line. This breaks the one-entry-per-line rule (AGENT-SYNC-RULES "Each entry is one line"). Line 189 holds three W2-L DONEs. Line 253 holds W2-L DONE, INFO and REQUEST. Any line-based tooling will miscount them.

## 7. backlog-status.json: Wave 2 status gaps

Every Wave 2 M-id named in the master backlog's Wave 2 table has a status. That covers M-003, M-014, M-020…M-023, M-029…M-039, M-045, M-046, M-047, M-049, M-051, M-052, M-054, M-056, M-062, M-073, M-091, M-095, M-097, M-098, M-106, M-113, M-116, and the routes for M-025/M-059/M-074/M-099.

**Stale or incomplete:**

| M-id | Current status / note | Why stale (board evidence) | Suggested |
|---|---|---|---|
| M-062 | PARTIAL, "worker field (W2-H then W2-L) open" | 153 contract, 189 (2nd entry) server fill, 185 setTraceLinks wiring, 190 SDK TaskContext | CODED |
| M-073 | CODED_NOT_LIVE, "verify integration W2-L" | 76 JdbcAuditAdapter.setCheckpoints, 134 AuditVerifyWiring | CODED |
| M-010 (W1 item, W2-K lane) | PARTIAL, "11 of 12 … breach plan start coding" | 131 (12 of 12 + plan start), 219 stored second-start guard; M-010d fixed in tree (`gov1.py:444` routes `p3_duplicate` to `${duplicateSeat}`) | CODED |
| M-006 (W1 item, W2-A action) | CODED_NOT_LIVE, "pass JdbcReplayGuard to clientVerifier (W2-A)" | 27 CHANGE, 93 DONE | CODED |
| M-001 (W1 item, W2 server part) | note "server LocalQueueCipher AAD \|userId -> W2" | 53 INFO; tree `LocalQueueContract.java:65` `aad(tenantId, deviceId, operationId, userId)` | update note |
| M-025, M-059, M-074, M-099 | PARTIAL with **empty lane** | master: W3-I, W3-F, W3-G, W4-C | fill lane |
| M-008 | PARTIAL, "replay controller … open" | no lane or wave anywhere (not in carry) | assign lane (section 6, item 4) |
| M-020 | note "regen after W2-H final contract" | done at 251 (217C21BE) | update note |
| M-098 | lane "W1-H B26" only | the Wave 2 freeze was done by W2-D/B32 (FILE-CLAIMS 744/813) | add W2-D |
| M-021, M-036 | PARTIAL, remaining part has no lane | server test-mode image; palette/form templates | name the follow-up lane |

Also, `BACKLOG-STATUS-0.1.0.md` (10:19:14Z) is older than `backlog-status.json` (10:42:54Z), so `gen_backlog_status.py` has not been rerun since the last JSON update.

## 8. What closes the gate

1. **W2-N:** remove the deprecated `SlaService(Dialect, Transactions, Outbox, Function)` constructor (no main caller uses it, per board 120), or post why it stays. Either way, close REQUEST 40.
2. **W2-J, with W2-B for `sdks/**`:** add the SL-158 binding and 403 `WORKER_TOPIC_NOT_BOUND` migration note to the worker/SDK guides, or have the integrator record that the server README is enough. Close REQUEST 119.
3. **Integrator:**
   - route the INFO 69 SSRF finding and the INFO 84 findings;
   - give `ParkedInboundController` (M-008/SL-142) and GET /programme-config an M-id, a lane and a wave;
   - name the modeller owner for `RunActionRequest.reason`;
   - refresh the stale backlog-status rows and regenerate the status page;
   - record the STANDING-CLAIM exception.
