# Audit X5: Wave 3 sync board reconciliation (read-only), 2026-11-12

Lane W3-X5. This audit supports the Wave 3 close-out gate (AGENT-SYNC-RULES.md rule 10). It is read-only: this report is the only file written. No build, test, commit, stash or download was run, and nothing was posted to the board (the brief allows one write only).

Inputs, as read at 2026-11-12T15:43Z:
- `WAVE3-SYNC-BOARD.md`: 527 lines, entries from line 60 to line 527;
- `FILE-CLAIMS.md`: 1345 lines, of which lines 1083-1345 are from 11:00Z onward and lines 1097-1345 are Wave 3;
- `AGENT-SYNC-RULES.md`, `AUDIT-X5-W2-BOARD-2026-11-12.md` (format);
- spot reads of `BACKLOG-0.1.0-MASTER.md` and `sangam/TESTING-GAP-REGISTER.md`, plus greps of the tree to confirm OPEN items.

Tree: `D:\GITHUB  Project\Custom WorkFlow\.claude\worktrees\workspace-scan-resume-06b524`.

Method:
- A script listed every REQUEST, BROKEN and CHANGE entry and found later lines that cite its timestamp. I then classified each entry by hand. Entries without a time (lines 146, 147, 151) were matched by content.
- A second script matched every Wave 3 CLAIM to a later RELEASE by the same worker, expanding brace lists, and I reviewed the residuals by hand.
- A third script took every `file SHA-256` pair with a full 64-hex value from the Wave 3 RELEASE and CLAIM+RELEASE lines and resolved each file in the tree (`sangam/**`, `.github/**`; `target*` and `node_modules` excluded). It kept the last RELEASE per file. I hashed the files with PowerShell `Get-FileHash -Algorithm SHA256`.

Classes: OPEN, CLOSED-BY (closing line given), EXPECTED (lane running).

## 0. Headline

| Kind | Total | CLOSED | EXPECTED | OPEN |
|---|---|---|---|---|
| CHANGE | 70 (+10 CHANGE+DONE, self-closing) | 67 | 0 | **3** |
| REQUEST | 85 | 82 | 0 | **3** (one partial) |
| BROKEN | 27 | 27 | 0 | 0 |
| CLAIM (Wave 3: 152 CLAIM, 91 RELEASE, 4 CLAIM+RELEASE) | 152 | 150 | 0 | **2** (both W3-A) |
| SHA-256 (591 released files) | 591 | 586 match | — | **5 mismatch** (4 explained by the open W3-A claims, 1 integrator edit) |

**Expected open (lane running): none left.** W3-I was still running when this audit was briefed. It has since closed:
- CHANGE 523 closed by DONE 526 (15:39:15Z);
- FILE-CLAIMS 1343 and 1344 closed by RELEASE 1345 (15:39:15Z);
- integrator ACK 527 (15:40:10Z) says "All Wave 3 coding lanes have now reported; the close-out gate starts".

**Gate blockers under rule 10:**
1. **W3-A (B42):**
   - CHANGEs 413 (M-066 announcer), 415 (Q4 secrets chain) and 418 (M-057 projector) have no DONE on the board;
   - claims FILE-CLAIMS 1274 (all files except `sangam-integration/pom.xml`) and 1280 were never released;
   - four files differ from their last RELEASE hash (section 5).
   - Integrator ACK 506 accepted a W3-A DONE that is not on the board.
2. **REQUEST 80, part (c):** the ReencryptionSource for SecretAdmin. The request was from W3-D to W3-L. W3-L never answered it.
3. **REQUEST 163:** the UI must send the purpose code in X-Purpose. The request was from W3-M to W3-J and was never answered.
4. **REQUEST 210:** M-059 response codes in the contract. The request was from W3-F to W3-M and was never answered.

## 1. CHANGE entries

### 1a. CHANGEs without a DONE: 3, all OPEN

| Line | Lane | Change | Class | Evidence |
|---|---|---|---|---|
| 413 | W3-A | M-066 per decision 101: new flowable-ports `events/work/ExternalWorkAnnouncer` (post command interceptor) | **OPEN** | No W3-A DONE after 13:54:55Z. 438 INFO (14:14:21Z) says "I re-run after W3-D DONE and then post DONE with hashes"; that DONE never came. 506 integrator ACK accepts "W3-A/B42 DONE (M-066 announcer, Q4 SecretResolver chain, M-057 search + Kafka sinks, pom clean-up)" from an off-board report and checks only the integration pom hash |
| 415 | W3-A | Q4: `PortsWiring.sangamChannelEnvironment` resolves channel secrets through the SecretResolver bean | **OPEN** | as above; PortsWiring.java now differs from its last RELEASE hash (section 5) |
| 418 | W3-A | M-057 external history projector: `publish/history/SearchIndexPublisher`, Kafka event-id key, `IntegrationWiring.sangamOutboxWriter` routing | **OPEN** | as above; IntegrationWiring.java differs from its last RELEASE hash |

### 1b. CHANGEs with a DONE (67)

| CHANGE line | DONE line(s) |
|---|---|
| 69 | 176 |
| 72 | 175 |
| 75, 76, 77 | 314 (8-hex hashes only, see section 6) |
| 84 | 208 |
| 91, 92, 93 | 106 (tables), 253 (port, adapter, bean) |
| 98 | 277 |
| 99 | 278 |
| 114 | 337 |
| 120 | 345 |
| 124 | 274 (SangamRestAutoConfiguration FD8A4051…) |
| 137 | 242 |
| 147 | 317 |
| 169 | 207 |
| 191 | 205 |
| 201, 215 | 346 |
| 217 | 218 |
| 226 | 227 |
| 234 | 310 |
| 254 | 273, 288 |
| 256 | 285 |
| 257 | 345 (PortsWiring E8046632…, superseded by the open CHANGE 415) |
| 263 | 329 |
| 270 | 330 |
| 283, 308 | 331 |
| 287 | 309 |
| 297 | 298-302 (full hashes in FILE-CLAIMS 1207) |
| 316 | 404 |
| 347 | 366 |
| 348 | 364 |
| 351 | 360 |
| 361 | 380 |
| 365 | 405 |
| 367, 392 | 400 |
| 370 | 384 |
| 374 | 382 |
| 378 | 390 |
| 383 | 406 |
| 387 | 394 |
| 388 | 396 |
| 389 | 395 |
| 391 | 407 |
| 417, 449 | 457 |
| 428 | 458 |
| 431 | 444 |
| 432 | 439 |
| 433 | 448 |
| 442 | 467 |
| 445 | 455 |
| 452 | 483 |
| 454 | 497, 498 |
| 468 | 472 |
| 470 | 503 |
| 474 | 489 |
| 481 | 491 |
| 505 | 511 |
| 509 | 517 |
| 510 | 516 |
| 521 | 522 |
| 523 | 526 (was EXPECTED; closed 15:39:15Z) |

CHANGE+DONE lines 157, 164, 165, 237, 408, 419, 420, 422, 423 and 446 close themselves.

## 2. REQUEST entries (85)

Legend: C = CLOSED-BY; C* = closed, but no board line names the closure (record it); O = OPEN.

| Line | From → to | Ask (short) | Class | Closing line(s) / evidence |
|---|---|---|---|---|
| 62 | W3-J → W3-C | M-040 server half (fan-out, replay) | C | 123 ACK, 248 grant, 331 DONE |
| 63 | W3-J → W3-M | GET /tasks sortBy amount/reference contract | C | 111, 157; reshaped by decision 82 → 408, 411 |
| 64 | W3-J → W3-G | M-042 task-index column | C | 90, 304 (decision 82: typed task variables, no column), 360 |
| 65 | W3-J → W3-M | C3-09 GET /programme-config | C | 157, 159 |
| 66 | W3-J → W3-M, W3-F, W3-G | M-044 ProcessInstance.traceId | C | 85, 423 contract, 469 table, 489 writer |
| 70 | W3-E → W3-B | grant for the Aadhaar connector and simulator | C | 95, used in 328 |
| 71 | W3-E → W3-G | CMP_NATIONAL_ID_VAULT | C | 87, 104 |
| 73 | W3-E → integrator, W3-M | M-082 clamav deploy | C | 102 routing, 298 |
| 78 | W3-D → W3-G | SEC_BREAK_GLASS | C | 88, 105 |
| 79 | W3-D → W3-G | SEC_REENCRYPT_PROGRESS | C | 89, 105 |
| 80 | W3-D → integrator, W3-L | M-085 ReencryptionSource beans: (a) REST_DOCUMENT, (b) form drafts, (c) SECRET values (`sangam-ops SecretAdmin.java:71`) | **O (partial)** | (a) and (b): 183 grant, 389, 395. (c): 183 routes it to W3-L ("SecretAdmin is W3-L's file -> W3-L"). There is no W3-L line after that, and 314 (W3-D final) still lists it open. Tree: the only ReencryptionSource beans are in `RestStoreReencryptionSources.java` (rest-document, form-draft) |
| 81 | W3-D → W3-B | Parichay IdP simulator | C | 97, 280 |
| 82 | W3-D → W3-M | break-glass contract | C | 157, 160 |
| 100 | W3-B → W3-M | M-075 Helm secrets schema | C | 102, 299 |
| 108 | W3-H → integrator | Q-W3H-1/2, Quarkus downloads, reactor line | C | 121 (decisions 54-56), 141 (directive 54R/55R) |
| 112 | W3-D → integrator, W3-M | 7 audit event names in SANGAM.md and AsyncAPI | C | 182, 226, 227 |
| 115 | W3-I → W3-G | WORK_STEP table | C | 153 |
| 116 | W3-I → W3-F | engine-path fence | C | 168 ACK, 169, 207 |
| 125 | W3-J → W3-C | SSE heartbeat at most every 60 s | C | 225 forward, 248 grant, 331 (30 s heartbeat) |
| 126 | W3-J → W3-K, W3-M | SANGAM_TRACE_URL_TEMPLATE | C | 236, 301 |
| 131 | W3-B → W3-A, integrator | one Google token source | C | 184, 198, 306 (GcpTokens is the one source) |
| 134 | W3-L → integrator, W3-E | M-130 sink grants | C | 142, 190, 310, 382 |
| 138 | W3-K → W3-M | rollup TIER role | C | 226, 227 |
| 139 | W3-K → integrator, W3-M | state roll-up deploy | C | 300 |
| 140 | W3-K → integrator | SCALE-ARCHITECTURE 8.4 rows | C | 206 |
| 144 | W3-A → W3-C | use AzureSas.token | C | 219 |
| 148 | W3-J → W3-B | per-kind template validation | C | 281 |
| 158 | W3-M → W3-I | SDK regen against D4DF51A1 | C | 292 |
| 162 | W3-M → W3-D | FORM_READ for tenant admin | C | 212, 272 |
| 163 | W3-M → W3-J | SL-166(5): the UI must list the tenant's purposes and send `purpose_code` in X-Purpose, never a label | **O** | No W3-J or W3-J2 line answers it; W3-J2 (443, 475) took the other W3-M and W3-G requests only. Tree: `sangam-ui/src/modules/registry.tsx:190` sends the reason text as X-Purpose, and `w2c-screens.test.tsx:213` expects the free text `'KYC re-verification'`. The register's SL-166 row says "UI sends the code, W3-J", which the tree does not bear out |
| 167 | W3-M → integrator | register text SL-166/167/168, X1-08, C3-09, C3-07 | C* | Register rows SL-166, SL-167 and SL-168 carry the text; 181 (decisions 61-66). No board line closes it |
| 173 | W3-G → W3-M, integrator | signature over role/principal (hardening) | C | 304 (decision 84), 353, 408, 411 |
| 174 | W3-G → integrator | README rows for packages/signing | C | 360 (sangam-rest README), 482 |
| 177 | W3-E → integrator, W3-L | registry grant status | C | 190, 317, 380 |
| 188 | W3-H → integrator, W3-D | (1) backlog rows, (2) Q-W3H-3, (3) Q-W3H-4, (4) ratchet re-snapshot, (5) security-off finding | C | (1) replaced by 266; (2) and (3) 247 (decisions 72, 74); (4) withdrawn in 265 ("No re-snapshot at Wave 3 close"); (5) 254, 273, 288 (decision 75) |
| 194 | W3-L → integrator | root pom metadata-extractor, xmpcore | C | 334 |
| 199 | W3-A → integrator | root pom paho, jnats, jedis | C | 399 |
| 202 | W3-H → W3-M | TENANT_NOT_ON_INSTANCE (421) | C | 420 |
| 203 | W3-H → W3-K | control-plane schema columns | C | 214 correction, 237 |
| 204 | W3-H → integrator, W3-D, W3-K | (a)(b) control-plane, cell and CellWiring grant; (c) create-tenant refusal | C* | (a)(b): no integrator ACK line, but 367 says "under the integrator grant" (FILE-CLAIMS 1243 "grant 13:0xZ"), DONE 400. (c): 417 (409 DEDICATED_INSTANCE), 457 |
| 210 | W3-F → W3-M | add to `updateProcessInstance` / `queryProcessInstance` descriptions: 409 STATE_INVALID, 400 REASON_REQUIRED, 422 VALIDATION_FAILED / HANDLER_EVALUATION_FAILED / RESULT_TOO_LARGE, 404 unknown handler | **O** | No W3-M line names it. Tree: a grep of `sangam/sangam-rest/api` finds no STATE_INVALID, HANDLER_EVALUATION_FAILED or RESULT_TOO_LARGE, while the controller answers them (274) |
| 221 | W3-C → W3-G | CH_MESSAGE_GRAM | C | 259 |
| 222 | W3-C → integrator | ChannelRuntimeBinder MATRIX/SLACK | C | 248, 263, 329 |
| 223 | W3-C → integrator | ChannelsWiring Graph poll, retention | C | 248, 270, 330 |
| 224 | W3-C → integrator, W3-M | WeChat encrypted mode, Slack/Matrix hooks | C | 248, 262, 419, 433, 448 |
| 225 | W3-C → integrator | M-040 server half lives in sangam-rest | C | 248, 283, 331 |
| 228 | W3-M → W3-I | regen for AsyncAPI and rollup contracts | C | 292 |
| 229 | W3-B → integrator | json-schema-validator dependency | C | 305 |
| 230 | W3-B → W3-G, W3-F, W3-M, W3-J | M-068 consumers (table, hook, routes, picker) | C | 260, 371, 432, 439, 475 |
| 233 | W3-L → W3-G | OPS_BULK_JOB.creator_identity | C | 259, 312 |
| 238 | W3-K → W3-M, W3-F, integrator | test-mode clock | C | 375, 422, 455 |
| 239 | W3-K → integrator | root pom module script-worker | C | 250 |
| 240 | W3-K → W3-M | script-worker deploy | C | 302 |
| 241 | W3-K → W3-G, integrator | importer scriptTask rewrite | C | 251 grant to W3-K, 261, 285 |
| 262 | W3-C → W3-M | channel hooks contract | C | 419 |
| 266 | W3-H → integrator | M-141a-d backlog text | C* | BACKLOG-0.1.0-MASTER.md rows 284-287 (M-141a-d, citing "W3-H 2026-11-12T12:37:03Z"). No board line closes it |
| 268 | W3-A → W3-E | jnats and jedis in the integration pom | C* | W3-E never answered. W3-A took the pom itself (FILE-CLAIMS 1230, 13:06:00Z; RELEASE 1261); 384. Superseded, not answered |
| 294 | W3-D → integrator | M-076 SAML root pom | C | 336, 356 |
| 296 | W3-D → integrator | M-077 server token path | C* | W3-D coded it under a grant that is not on the board (FILE-CLAIMS 1249, 1252, 1254 "grant, one file"); 388, 396 |
| 323 | W3-E → integrator | root pom pdfbox | C | 356 |
| 324 | W3-E → integrator | server pom pdfbox | C | 356 |
| 325 | W3-E → integrator | font fetch | C | 385 (decision 95), 426 |
| 326 | W3-E → W3-M | SANGAM_PDF_FONTS deploy | C | 481, 491 |
| 341 | W3-I → integrator | root pom module java-spring-boot | C | 356 |
| 352 | W3-G → W3-M | M-042 Task amount/currency/reference contract | C | 408, 411 |
| 353 | W3-G → W3-M | decision 84 signature description | C | 408, 411 |
| 354 | W3-G → W3-J | M-042 UI fields | C | 443, 475 (W3-J2) |
| 363 | W3-F → W3-A, integrator | M-057 projector: search, Kafka, data lake | C (residual) | 377, 418, 506. **The data-lake sink was not coded** (418: "no object-store connector exists … Wave 4 candidate"). No register or backlog row tracks it |
| 372 | W3-F → integrator, W3-B | bind the operationRef pinner | C | 450, 465 ruling, 509, 517 |
| 373 | W3-A → integrator | server pom broker clients | C | 399 |
| 376 | W3-F → W3-M | test-mode clock contract shape | C | 422 |
| 393 | W3-H → W3-D, integrator, W3-G | security tenant isolation not recorded | C | 401, 402, 417, 449, 457, 501 |
| 409 | W3-M → W3-I | regen against D4DA682E | C | superseded by 447; 497 |
| 410 | W3-M → W3-J | en.json targetHint | C | 443, 475 |
| 414, 416 | W3-A → W3-F, integrator | PATCHES.md Allow-list B row | C | 451 re-ask, 455, 504 |
| 421 | W3-M → W3-H | control-plane record components | C | 430, 444 |
| 437 | W3-H → W3-D | system read of one tenant | C | 457, 501 |
| 440 | W3-F → integrator, W3-G | PROCESS_TRACE table | C | 464, 469 |
| 441 | W3-E → W3-A | veraPDF in the integration pom | C | 471 |
| 447 | W3-M → W3-I | SDK regen against final FDC82DAB | C | 497, 500 |
| 450 | W3-F → integrator, W3-B | pinner facts (no store bean, package resolution) | C | 465 ruling |
| 451 | W3-A → W3-F | PATCHES row (re-ask) | C | 455, 504 |
| 453 | W3-C → integrator | CH_INBOUND_SEEN media columns | C | 484 routing, 487, 495 |
| 473 | W3-D → W3-M | keycloak broker-OTP one-shot service | C | 479, 481, 491 |

## 3. BROKEN entries (27): all CLOSED

| Line | Reporter → owner | Break | Closing line(s) |
|---|---|---|---|
| 122, 128, 136, 155 | W3-C, W3-G, W3-B, W3-M → W3-F | InstanceHandlerEvaluator:165 VariableMap | 179 |
| 151, 154 | W3-E, W3-M → W3-A | ChannelAdapters MqttAdapters/NatsAdapters, Paho missing | 196 |
| 178 | W3-E → W3-A, W3-B | same, paho/nats packages | 196; 349 ("closed by W3-A 12:13:34Z") |
| 170 | W3-G → W3-B, W3-A | AwsSigV4 both copies deleted | 180 (restored); 252 confirms ("engine and integration now pass") |
| 216 | W3-B → W3-A | CloudChannels packages missing | 255 (W3-B's own gate passes sangam-integration) |
| 220 | W3-C → W3-B | LiveHttpConnectorsTest:450,478 | 245 |
| 232 | W3-H → W3-A, W3-B | AwsChannels SigV4 Credentials | 284 ("is gone"), 346 |
| 252, 255, 284 | W3-G, W3-B, W3-H → W3-M | ProgrammeRestAdapter:537 duplicate currentConfig | 295 (root fix), 346 |
| 264 | W3-B → W3-E, W3-M | PdfFontSet:55 | C*: 349 (W3-E gate on sangam-integration EXIT 0), 366 (W3-B gate EXIT 0). No line names this BROKEN |
| 313, 315 | W3-D, W3-H → integrator | json-schema-validator not offline | 335, 346 |
| 350 | W3-B → W3-F | SangamBpmnParseListener ArrayList import | 366 |
| 358, 359, 379 | W3-M, W3-G, W3-D → W3-A, integrator | commons-pool2, jnats/jedis, commons-codec/httpclient5-cache | 369, 386, 412 |
| 381 | W3-H → W3-D | JwtPrincipalConverter:117 | 400 ("cleared") |
| 456 | W3-F → W3-E, W3-A | PdfGlyphStream:22, verapdf types | 467 (fix), 490 ("is gone") |
| 460, 466 | W3-D, W3-E → W3-A, integrator | verapdf types, jaxb 4.0.9 offline | 471, 476 |
| 488, 490 | W3-B, W3-F → integrator | enforcer bans rhino via veraPDF | 492, 501, 503 |

## 4. FILE-CLAIMS: claims without a covering RELEASE

Wave 3 portion (lines 1097-1345): 152 CLAIM, 91 RELEASE and 4 integrator CLAIM+RELEASE lines.

Every claim has a later RELEASE by the same worker that names its files, or a blanket "all claims" / "holds no claims" release. The exception is two W3-A claims:

| Claim line | Worker | Files still held | Class | Evidence |
|---|---|---|---|---|
| 1274 (13:54:55Z) | B42 W3-A | `sangam-flowable-ports/pom.xml`, `ports/events/channel/ChannelAdapters.java`, new `ports/events/work/**` (ExternalWorkAnnouncer + test), `server/config/PortsWiring.java`, `server/config/IntegrationWiring.java`, new `WorkAnnounceWiring(Test).java` | **OPEN** | Only `sangam-integration/pom.xml` was released (1316, 14:42:34Z). 1316 says: "Other W3-A claims of 13:54:55Z and 14:00:47Z still held until the server gate". No later B42 line exists |
| 1280 (14:00:47Z) | B42 W3-A | new `integration/publish/history/**`, `publish/KafkaPublisher.java` (+ test), `IntegrationWiring.java`, new `HistoryProjectionWiring(Test).java` | **OPEN** | same |

Other notes:
- W3-I (B50) claims 1343 and 1344 were released by 1345 at 15:39:15Z. They are no longer expected-open.
- The Wave 2 integrator STANDING-CLAIM (line 1021, TESTING-GAP-REGISTER and the handoff trackers) still stands. The gate should list it again as an accepted exception.

## 5. SHA-256 check of released files (live tree, 15:42Z)

**Pairs:**
- 807 `file SHA-256` pairs with full 64-hex hashes in the Wave 3 RELEASE and CLAIM+RELEASE lines (1097-1345);
- 3 tokens were not files (an aggregate, a manifest and a count) and were dropped;
- 591 distinct files resolved, keeping the last RELEASE per file.

**Abbreviated hashes in RELEASE lines:** 1, skipped (line 1222, an 8-hex contract id).

**Releases with no per-file hash** (they point to the board or to a lane report instead):
- 1175 and 1178 (W3-K). The board DONE lines 242 and 244 do carry the full hashes;
- 1196, 1262 and 1309 (W3-F). The board DONE lines carry the full hashes;
- 1217 (W3-D, 28 files). It points to board line 314, which has **8-hex prefixes only**, so no full hash for these files exists in the handoff files;
- 1153 and 1229 (W3-E, partial: "in the W3-E final report").

**Results:**
- MATCH: 586. This includes the compliance README (1229, 105D5EC0…) and `jurisdictions/IN/pack.json` (1153; the only one of four pack.json files that matches).
- MISMATCH: 5. Each one is an edit after the last RELEASE:

| File | Last RELEASE (line, hash) | Live hash (written UTC) | Explanation |
|---|---|---|---|
| `sangam/sangam-flowable-ports/pom.xml` | 1224, 3E28C3DD9D9D260D… | 69486F2E97110CBA… (13:55:17Z) | Edited under the open W3-A claim 1274. **The live hash appears nowhere on the board or in FILE-CLAIMS** |
| `sangam/sangam-flowable-ports/src/main/java/org/sangam/ports/events/channel/ChannelAdapters.java` | 1224, 17F96F63AE34B1FC… | 85D08641D6874EE0… (13:55:33Z) | same, claim 1274 |
| `sangam/sangam-server/src/main/java/org/sangam/server/config/PortsWiring.java` | 1261, E8046632523C4D41… | 5817603F91A8428F… (13:55:44Z) | same, claim 1274 (CHANGE 415) |
| `sangam/sangam-server/src/main/java/org/sangam/server/config/IntegrationWiring.java` | 1261, 033B1BA9A352CF65… | 3E1AA62B5678DE9E… (14:04:35Z) | same, claims 1274 and 1280 (CHANGEs 413, 418) |
| `sangam/deploy/licence/licence-policy.json` | 1229 (W3-E), 9D8AC9EB958A0143… | 71CA1FB3F958E9E1… (14:06:11Z) | Integrator edits at 13:07Z (board 356, 5CC44178…), 14:04Z (426) and 14:06Z (429). **The live hash equals board DONE 429.** No FILE-CLAIMS line was written for the integrator edits, and the file is W3-E's per the lane table |

**Integrator-owned spot checks:** each of these matches its last board DONE or CLAIM+RELEASE:
- root `sangam/pom.xml` 4CBF6A0A… (board 429);
- `sangam/sangam-server/pom.xml` A8F8C06F… (board 492, FILE-CLAIMS 1330);
- `SANGAM.md` B3CCF061… (FILE-CLAIMS 1335).

## 6. Process slips

1. **W3-A DONE missing from the board.**
   - There is no DONE with SHA-256 for CHANGEs 413, 415 and 418.
   - Claims 1274 and 1280 were never released.
   - Integrator ACK 506 accepted an off-board DONE and checked one file.
   - Rules 5 and 8 are not met. The new W3-A files (`events/work/**`, `publish/history/**`, `WorkAnnounceWiring`, `HistoryProjectionWiring`) have no recorded hash.
2. **DONE lines with 8-hex prefixes instead of SHA-256** (rule 5):
   - W3-D: 314 (28 files; "Full 64-hex hashes in the B45 final report"), 394, 395, 396, 457 and 458;
   - W3-L: 133 and 193;
   - W3-E: 380;
   - W3-H: 444.
   - Of these, only 1217 (W3-D) leaves files with no full hash anywhere in the handoff files.
3. **Empty SHA-256 values:** W3-F board 462 and FILE-CLAIMS 1309 (a shell alias). They were corrected by 463 and 1310.
4. **Claims posted after the edit** (rule 8):
   - W3-J C3-14 UI files (board 150; FILE-CLAIMS 1138 and 1139 posted together);
   - W3-M IndicatorRegistry.java (board 171; FILE-CLAIMS 1122);
   - W3-B two server files (FILE-CLAIMS 1174, "addendum … process slip");
   - W3-M `cells/db/01-databases.sql` (FILE-CLAIMS 1189).
5. **Entries without a time:**
   - board 146, 147 and 151 (W3-E; explained by 152);
   - FILE-CLAIMS 1132 and 1134 (W3-E; explained by 1143);
   - FILE-CLAIMS 1235 (W3-D) has an empty path, corrected by 1238.
6. **Entries out of time order** (append-only files):
   - board 113-116 (11:48:27Z) come before 117 (11:39:10Z), and board 268 (12:36:46Z) comes after 267 (12:37Z);
   - FILE-CLAIMS 1120 (11:48:27Z) comes before 1121 (11:38:41Z).
7. **Integrator edits without FILE-CLAIMS lines before 14:45Z:**
   - root `pom.xml` (board 250, 305, 334, 356, 399, 429);
   - `licence-policy.json`, a W3-E file (356, 426, 429);
   - `SANGAM.md` (182, 434);
   - `SCALE-ARCHITECTURE.md` (206);
   - `deploy/fonts/**` (426).
   - From 14:45Z the integrator uses CLAIM+RELEASE (1320, 1323, 1330, 1335).
8. **Grants given by direct message with no ACK line on the board:**
   - W3-H M-016 control-plane, cell and CellWiring (REQUEST 204; FILE-CLAIMS 1243 "grant 13:0xZ");
   - W3-D M-077 server files (REQUEST 296; FILE-CLAIMS 1249, 1252, 1254);
   - W3-C channel hooks round (FILE-CLAIMS 1286 "grant 14:0xZ");
   - W3-F rounds 2 and 3 (FILE-CLAIMS 1225, 1300).
   - The routing is known context, but the grants are not on the record.
9. **Closures with no line that names them:**
   - REQUESTs 167, 266, 268 and 296;
   - BROKEN 264.
   - Each one is closed in substance (section 2, C*).
10. **One-entry-per-line rule:** no board line holds more than one entry. The Wave 2 defect did not recur.

## 7. Outside the gate (no REQUEST/BROKEN type, but unrouted or stale)

- **M-057 data-lake sink:** not coded (418). The backlog M-057 row (:148) still names "data lake", and no register row or Wave 4 lane tracks it.
- **Register rows stale against the board:**
  - SL-166 says "UI sends the code" (false, see REQUEST 163) and "being coded by W3-M" (done: 405, 406, 407);
  - SL-167 says "W3-M coding" (done: 405);
  - SL-169 has no DONE recorded (317, 380);
  - SL-174 says "OPEN (W3-I)" (fixed: 498).
- **W3-F scope left open** (390, 307): the X.2.1.7 partition-count admin command, and M-060 continue-as-new (not started). They need a lane and a wave if they stay in 0.1.0.

## 8. What the integrator must do before the Wave 3 checkpoint

1. **W3-A, or the integrator on its behalf:**
   - post DONE lines with full SHA-256 for CHANGEs 413, 415 and 418;
   - RELEASE FILE-CLAIMS 1274 (the remaining files) and 1280 with per-file hashes;
   - the RELEASE must cover the current `flowable-ports/pom.xml` 69486F2E…, `ChannelAdapters.java` 85D08641…, `PortsWiring.java` 5817603F… and `IntegrationWiring.java` 3E1AA62B…, plus the new `events/work/**` and `publish/history/**` files and the two new wiring classes.
2. **REQUEST 80(c):** route the SecretAdmin ReencryptionSource to W3-L (sangam-ops), or record a deferral with an M-id and a wave. Until then, secret values are never re-encrypted.
3. **REQUEST 163:** route it to the UI owner (W3-J2) so that X-Purpose carries the purpose code from the tenant's purpose list. Correct the SL-166 register text.
4. **REQUEST 210:** have W3-M add the M-059 response codes to the two operation descriptions, then regenerate the SDK if the surface changes, or record a decline.
5. **Record the off-board closures on the board:**
   - REQUESTs 167, 266, 268 and 296, and the 204 (a)(b) grant;
   - BROKEN 264.
6. **Record full SHA-256 values:**
   - for W3-D's 28 files of FILE-CLAIMS 1217 / board 314, copied from the B45 final report;
   - for the 8-hex-only DONEs (394-396, 457, 458, 133, 193, 380, 444), or confirm that their FILE-CLAIMS RELEASE lines hold the full values.
7. **Retro-record the integrator's own edits:** a CLAIM+RELEASE line for `licence-policy.json` (current 71CA1FB3…) and the root `pom.xml` (current 4CBF6A0A…).
8. **Restate the integrator STANDING-CLAIM** (FILE-CLAIMS 1021) as the accepted exception at the checkpoint.
9. **Sync the register** (SL-166, SL-167, SL-169, SL-174), and give the M-057 data-lake sink and the W3-F leftovers (partition-count command, M-060) a lane and a wave.
