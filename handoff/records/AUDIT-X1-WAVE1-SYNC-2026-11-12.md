# Audit X1: Wave 1 cross-lane sync (retroactive), 2026-11-12

Auditor: X1. This audit was read-only: no source edits, no Maven, npm or tests, and only read-only git.
Target: checkpoint commit `0e53f91b`, built on `1105d9c`. All evidence below is `0e53f91b:<path>:<line>` unless it is marked **live** (the working tree at the time of the audit, with Wave 2 in flight).
Scope: Wave 1 lanes B11 to B28, taken from the FILE-CLAIMS lines dated 2026-11-12, register rows SL-125 to SL-147, and ledger sections 6w to 6z. The WAVE2-SYNC-BOARD rules were applied retroactively.

## Summary

| ID | Sev | Conf | One line | Fix owner | Live tree |
|---|---|---|---|---|---|
| X1-01 | HIGH | 98% | The programme configuration publish screen can no longer publish, and configurations already stored stop loading: SL-125 made `fiscalYearStartMonth` required, but the UI never sends it. | W2-C (UI) + programme owner | **still open** |
| X1-02 | MEDIUM | 97% | The number-series form offers the fiscal month only when reset is FISCAL_YEAR, but the server also requires it for `{fy}`/`{fyStart}` patterns. The form also lacks DAILY, which billing's RECEIPT series uses. | W2-C (UI) | still open |
| X1-03 | MEDIUM | 95% | Fiscal-year logic exists 4 times, with 3 independent sources of the start month and 3 label formats. Only one copy honours `startDay`. | integrator decision; config + registry + programme | open |
| X1-04 | MEDIUM | 98% | Six Wave 1 features were coded but their consumers were not wired at 0e53f91b, so each was inert (TenantAdmission, billing numbering, idempotency encryption, CDS client JWT, offline AAD). | W2-A / W2-N (done) | **resolved live** |
| X1-05 | LOW | 99% | The leaf-pool derivation was written twice (QuotaWiring and ComplianceWiring.leafPool), and the property names diverge. | W2-A | Quota copy still open |
| X1-06 | MEDIUM | 97% | Denial-audit rules conflicted between lanes at the checkpoint: compliance denials survive a rollback, security denials did not. The 32-hex chain-hash formula now exists in 4 copies. | W2-E/W2-L + compliance owner | partly resolved |
| X1-07 | LOW | 97% | The long-poll back-off schedule is written 3 times. WorkLongPoll's settings do not reach the blocking fallback. | sangam-rest owner (W2-L) | open |
| X1-08 | LOW | 97% | The "explicit period, else current fiscal period" rule is written 3 times. Ledger accepts the `fiscalYear` alias; billing does not. | ledger/billing owner | open |
| X1-09 | LOW | 99% | Stale docs after Wave 1: the billing README constructor, the sangam-rest README long-poll line, and the SANGAM.md must-fix table. | W2-J | **still open** |
| X1-10 | LOW | 97% | The coverage `@{argLine}` is on surefire only. Failsafe ITs never get the JaCoCo agent. | integrator (pom owner) | open |

---

## 1. Interface and consumer consistency

### X1-01 HIGH: programme config publish broken by SL-125 (B11), UI not updated
- **Change (B11, SL-125):** `ProgrammeConfig.parse` now refuses a missing `fiscalYearStartMonth` (it used to default to 1).
  - `sangam/sangam-programme/src/main/java/org/sangam/programme/config/ProgrammeConfig.java:54` (`Month fy = fiscalYearStartMonth(m)`)
  - same file `:221-224`, which throws `INVALID_CONFIG "fiscalYearStartMonth is required"`
- **Consumer 1, UI (not updated):** `sangam/sangam-ui/src/modules/programme.tsx:125`. The publish toolbar sends only `indicators`, `returns` and `kinds`, and the hint at `en.json:2920` says the same. `PUT /programme-config` then reaches `ProgrammeController.java:125-127`, `ProgrammeRestAdapter.java:85-88` and `IndicatorRegistry.publish` (`IndicatorRegistry.java:42`, `ProgrammeConfig.parse(configMap)`). Every UI publish is now refused.
  - **Live:** W2-C renamed `kinds` to `programmeKinds` at programme.tsx:165, but there is still no `fiscalYearStartMonth`.
- **Consumer 2, stored data:** `IndicatorRegistry.config` re-parses the stored `PG_CONFIG.config_json` on every cache miss (`IndicatorRegistry.java:117`). A configuration published before SL-125 without the key now throws for that tenant. That breaks:
  - `ReturnCampaignDelegate.java:46-48`
  - `ProgrammeRestAdapter.java:106` (period generation)
  
  The SL-125 register caveat covers stored number series only (`TESTING-GAP-REGISTER.md:158`), not stored programme configurations.
- **No other producer:** no test, SDK, pack or seed sends a programme configuration. Only `FormulaAndValidationEngineTest` was updated.
- **Fix:**
  - UI: add a `fiscalYearStartMonth` select (1 to 12) to the publish form and update the hint.
  - Server: for stored rows, either migrate them, or refuse with a clear 409 naming the key and keep reads working. The owner decides; nothing may be assumed.
  - Add a contract test: UI publish fields ⊇ the keys `ProgrammeConfig.parse` requires.

### X1-02 MEDIUM: number-series UI is narrower than the server rule
- **Server (B11):** a series needs `fiscalStartMonth` when reset is FISCAL_YEAR **or** the pattern has a `{fy}` / `{fyStart}` token.
  - `NumberSeriesDefinition.java:40-57`
  - Accepted resets are NEVER, YEARLY, FISCAL_YEAR, MONTHLY and DAILY (`:71`).
- **UI (B11):** `sangam-ui/src/modules/registryNumbering.ts:17-19` offers months only when `values.reset === 'FISCAL_YEAR'`, and `:12` lists resets without DAILY. As a result:
  - `{prefix}/{fy}/{seq:6}` with reset NEVER or YEARLY cannot be defined from the UI: the server refuses it with "need fiscalStartMonth", and the form offers no month.
  - B20's billing RECEIPT series is `reset: DAILY` (`sangam-billing/.../numbering/RegistryDocumentNumbers.java:49-50`). An operator who redefines RECEIPT on this screen cannot keep DAILY.
- **Fix:** offer the month when the reset is FISCAL_YEAR or the pattern matches `\{fy(Start)?\}`, add DAILY, and extend `registryNumbering.test.ts`.

### X1-04 MEDIUM: Wave 1 features inert at 0e53f91b because their consumers were not wired (all resolved in the live tree)
Retroactive sync-board rule 1 was broken: each producer changed and its consumer in another lane's file stayed on the old path, so nothing broke at compile time.

| Producer (lane) | Consumer at 0e53f91b | Effect at checkpoint | Live |
|---|---|---|---|
| `TenantAdmission` bean `CellWiring.java:103-106` (B21, M-008) | `BrokerInboxWiring.java:115-116` and `:141-142` still use the 6-arg constructors, so admission falls back to `TenantAdmission.ALL` (`KafkaInboxConsumer.java:83`, `JmsInboxListener.java:74`) | Broker messages are stored and acked for FROZEN, IMPORTING, MOVED and PURGED tenants (M-008 inert) | Fixed by W2-A/B29 (BrokerInboxWiring 47DFE5F4) |
| `RegistryDocumentNumbers` (B20, SL-130/131) | `BillingWiring.java:76` uses `new BillingService(ledger, tariffs, series, …)`, so DocumentNumbers is `overSeries`, a node-local map (`BillingService.java:64-69`) | Retry-safe numbering is single-node only; the README says production must pass RegistryDocumentNumbers | Fixed (live BillingWiring:87,92) |
| `JdbcIdempotencyAdapter(tx, props, encryptor)` (B20, M-007) | `SangamRestAutoConfiguration.java:126` uses the 2-arg constructor | response_body stays plaintext (M-007 inert) | Fixed (live :132) |
| `HealthHooksController` + `CdsClientVerifier` (B22, M-006) | `SecurityConfig.java:89` puts `/cds-services/**` under `.authenticated()` (platform JWT), and no `CdsClientVerifier` bean exists | An EHR JWT is refused by the resource server; a platform JWT is then refused with 401 `CDS_CLIENT_NOT_CONFIGURED`. CDS is unreachable, which fails closed | Fixed by W2-A (SL-138) |
| UI offline AAD (B25) `sangam-ui/src/lib/offline/crypto.ts:34-36` = `t\|d\|op\|user` | `LocalQueueContract.aad` `sangam-field/.../sync/LocalQueueContract.java:58-60` = `t\|d\|op` | The two sides of one contract had different AAD formats (owner decision Q-01a was applied on the UI side only) | Fixed by W2-N (live LocalQueueContract.java:65-68, same order) |
| `ComplianceAuditPool` property `sangam.compliance.denial-audit.pool.maximum-size` (B22) | not in `application.yaml` at the checkpoint | Undocumented key (default 2 applies) | Added by W2-A (live application.yaml:164) |

Recommendation: before any later checkpoint commit, the integrator should block until every producer CHANGE with a cross-lane consumer has a DONE entry on the board.

### Checked and found consistent (interface and consumer)
- **SL-125/127 fiscal (B11):** every main-code consumer of `LocaleSettings.fiscal()` and of the period resolvers goes through `LedgerTenantDefinitions.periodResolver()`:
  - consumers: `LedgerWiring.java:204`, `PackGapWiring.java:72`, `BillingWiring.java:85`, `BillingController.java:199-208`
  - `BusinessFailure FISCAL_YEAR_NOT_CONFIGURED` maps to 422 through `ModuleConnectors.java:35`
  - No calendar-year default is left in main code (grep for `getYear()` and `substring(0, 4)` in billing, ledger and rest).
  - `pack-requires.json` covers every pack whose pack.json names ledger or billing connectors. The exception is `sec.ushealth.claim`, which always passes `period=OPEN` (`sangam-sec-ushealth-claim.bpmn:12`), so it needs no fiscal year.
  - The UI, contract and SDK texts contain no "current year" default.
- **SL-102 profile money (B12):** the converted keys (`objectGroupLimits`, `amountAbove`, `ladder.upTo`, `preInvestmentUpTo`, `model1Threshold`, pension min/max, `gpf.annualCeiling`) have one consumer, packgen `gov_ifms.py:562,583,1168,1173`, which uses `profile_major`. No Java main code reads them; `ResolvedProfile.amount` has no main caller. API money stays a decimal string (`BillingController.java` money, `_v2_spring.py` `_V2_STRINGS`). Profile and pack money is in minor units. Both conventions are documented, and no consumer mixes them.
- **M-055 exclusive jobs (B24):** these all agree:
  - `SangamEngineSettings.java:160` (default true) and `:342-343` (env)
  - the Helm values `values.yaml:147`, the schema `values.schema.json:59` and the configmap `server-configmap.yaml:52` (`SANGAM_JOB_EXCLUSIVE_OVER_PROCESS_HIERARCHIES`)
  - `SangamProcessEnginePlugin.java:136` and the engine README `:221`
  - An empty env value keeps the default (`boolEnv`, `:362-365`).
- **M-012 history none/auto refused (B24):** no repo configuration sets a global none or auto:
  - the server `application.yaml:58` is `full`; `SangamEngineSettings.history` defaults to `"full"`
  - every test engine in sangam-packs and flowable-ports calls `setHistory("full")`
  - the control plane (`application.yaml:29`, audit) runs a plain Operaton without sangam-engine
- **M-053/M-005 long-poll (B21):** these all agree:
  - the `application.yaml` keys `sangam.work.long-poll.{enabled,max-waiters,poll-threads}`
  - `IntegrationWiring.java:419-424`, the WorkController async timeout (`:118`), and the register text
  
  No Helm keys are needed (defaults apply).
- **M-006 CDS config (B22):** `HealthConnectorsProperties` `@ConfigurationProperties("sangam.connectors-health")` with `cds-service.clients.*` matches the controller message and the class Javadoc.
- **M-028 route guards (B25):** all 15 `ROUTE_ACCESS` permissions in `sangam-ui/src/app/access.ts` equal the contract's `x-sangam-permission` on each screen's first GET. All 15 were checked with a script against sangam-api.yaml.
- **M-001 field sync shapes (B25):**
  - `registerDevice` body vs `DeviceRegistry.Registration` (`DeviceRegistry.java:55`): match
  - `DeviceView` status enum vs `Status {ACTIVE, REVOKED, LOST}` (`:28`): match
  - `SyncOutcome` vs `SyncService.java:57`: match
  - upload and pull paths vs `FieldWorkController.java:191-232`: match
- **M-013 (B19):** `UiPluginController.ASSET_MAPPING` `/{id}/assets/{*path}` matches the contract's `path` parameter, and the leading `/` is stripped.
- **M-009 (B22):** moving the denials to `cmp:d:` hides nothing from the readers. `AuditIntegrityVerifier.java:38` and `LogArchiveService.java:88` select every chain of the tenant, not a `cmp:p:` prefix.
- **M-007 (B20):** `ErasureService.java:109` sets `response_body=NULL`, which also erases encrypted bodies. The only other `response_body` user is `Schema.java`.
- **i18n (B11 + B25):** en and hi both have 2989 flattened keys with no difference. `opt.fiscalMonth.1..12` and `opt.DAILY` exist. `i18n-dynamic.json` covers `opt.` and `field.error.`.
- **SL-104 Helm:** `SANGAM_CELL_JURISDICTION` is `required` in the configmap, and every new configmap env name (`SANGAM_DB_NETWORK_TIMEOUT`, `SANGAM_DB_UNAVAILABLE_RETRY_AFTER`, `SANGAM_SECURITY_PLATFORM_*`, `SANGAM_ONBOARDING_ENVIRONMENT`) is read in `application.yaml`.
- **Licence policy (B26):** the keys `build_time_tools`, `forbidden_components` and `forbidden_jar_entries` are read with the same names by `.github/scripts/build_tools_not_shipped.py:44` and `pip_licence_report.py:75`.

## 2. Duplicated logic

### X1-03 MEDIUM: fiscal-year computation in 4 copies with 3 start-month sources
| Copy | Location (0e53f91b) | Start-month source | startDay | Label |
|---|---|---|---|---|
| FiscalCalendar | `sangam-config/.../locale/FiscalCalendar.java:89-92`, label `:166-173` | profile `locale.fiscal` | yes | `2026-27` / `2026` / `2027` / `2026/2027` |
| NumberSeriesDefinition | `sangam-registry/.../numbering/NumberSeriesDefinition.java:91-94` (fyLabel), `:104` (periodKey), `:112-117` | per-series `fiscalStartMonth` | no | `{fy}` = `2026-27` or `2026`; counter key `FY2026` |
| ValidityRule | `sangam-registry/.../kernel/ValidityRule.java:149-153` | per-object-type `fiscalStartMonth` | no | none |
| PeriodService | `sangam-programme/.../period/PeriodService.java:49-57` | programme config `fiscalYearStartMonth` | no | `FY2026-27` / `FY2026` |

- **Behavioural differences:**
  - With `startDay` ≠ 1, FiscalCalendar's year boundary differs from the other three.
  - One tenant can have profile April, programme July and a series January. B11 made each of them required but did not tie them together.
- **Fix (needs an owner decision, not a lane edit):** pick one source per tenant, the profile `locale.fiscal`, and use FiscalCalendar for all four:
  - Registry and programme default their own field from it, or are refused when they disagree with it.
  - Keep one label helper.
  - Contract test: the same date gives the same fiscal start year in all four.

### X1-05 LOW: leaf-pool derivation copied
- `QuotaWiring.sangamQuotaPool`, `QuotaWiring.java:94-110`.
- `ComplianceWiring.leafPool`, `ComplianceWiring.java:127-144` (B22). B22's own Javadoc says the pool is wired "as QuotaWiring does".

The two copies behave the same: `copyStateTo`, `minIdle=min(1,size)`, autoCommit true. Only the pool name, the default size (4 vs 2) and the error text differ.

- **Live:** W2-A reuses `ComplianceWiring.leafPool(ds,size,name)` for `SecurityAuditPoolWiring.java:82`, but QuotaWiring still has its own copy (live `QuotaWiring.java:101`).
- **Property names diverge:** `sangam.quotas.pool.maximum-size` vs `sangam.compliance.denial-audit.pool.maximum-size` vs `sangam.security.denial-audit.pool.maximum-size`.
- **Fix:** move `leafPool` to a small `LeafPools` class in server config, have QuotaWiring call it, and document all three keys together.

### X1-06 MEDIUM: denial chains, a conflicting rule at the checkpoint and a 4-copy hash
- **Conflict at 0e53f91b:**
  - B22 made compliance denials commit on a leaf pool so they survive the caller's rollback (`ComplianceAudit.java` `denial()`, the M-009 class comment).
  - The security denials still appended in-line on the caller's connection, so they rolled back:
    - `AuthorizationService.java:74`
    - `SegregationOfDutiesService.java:113`
    - `DelegationOfPowersService.java:197`
    - `SeatTaskListener.java:195`
  - So one condition (a refusal that throws) had two durability rules. This is registered as SL-132 and is resolved live by W2-L/W2-A (SecurityAuditPool and DenialAudit).
- **Duplicate formula:** `Hashes.hex(Hashes.sha256(tenant + "\u0000" + key)).substring(0, 32)` now exists in 4 places (live):
  - `ComplianceAudit.java:128` (B22, `principalHash`, `cmp:p:`/`cmp:d:`)
  - `AuditChain.java:79` (core, W2-E)
  - `DenialAudit.java:59` (rest)
  - `SecurityAuditService.java:90` (security)
  
  All four behave the same today. The board entry at 09:07Z proposes the core copy as the single source.
- **Fix:** have all three non-core copies delegate to a core helper, for example `AuditChain.chainHash(tenant, key)` with the prefix kept by the caller, and keep the cross-check test.

### X1-07 LOW: long-poll back-off written 3 times
- **The copies:**
  - `WorkLongPoll.java:79-81` and `:325-329` (B21, Settings: 100 ms doubling to 1 s)
  - `EngineAdapterWorkAdapter.java:99-104` (hard-coded 100 → `Math.min(1000, sleep*2)`)
  - `OperatonWorkAdapter.java:92-97` (the same)
- **Behaviour:** the same today. The blocking fallback (used above `max-waiters` or when disabled) does not follow `WorkLongPoll.Settings`.
- **Fix:** both adapters read `WorkLongPoll.Settings.defaults()` (or one constant).

### X1-08 LOW: "explicit period, else current fiscal period" in 3 copies (one lane, B11)
- **The copies:**
  - `LedgerConnector.java:85-86`: accepts `period` or the alias `fiscalYear`; `current` → resolver
  - `BillingConnector.java:70-80`: `period` only; blank or `current` → resolver
  - `BillingController.refundPeriod` `:199-208`: `period` only; blank or `current` → resolver
- **Difference:** a billing step that passes `fiscalYear` (accepted by ledger) gets the current period instead.
- **Fix:** one `LedgerTenantDefinitions.periodOr(explicit, tenant, day)` helper, with the same alias list in every caller.

### Checked for duplicates, none found in Wave 1 code
- **UUID sources:** Wave 1 added no new UUIDv5 or v7 helper. B25's TS UUIDv5 and canonical JSON are a necessary cross-language port, cross-checked against PacketHasher (B25 RELEASE).
- **Problem and error mappers:** B11 reuses `ApiProblem.unprocessable` and `ModuleConnectors`; B22 reuses `ApiProblem`.
- **JSON canonicalisation and retry helpers:** none added.
- **Clock and business date:** BillingConnector's "today" (`engineClock` in the ledger zone) and BillingController's `ledger().today()` (`ProfileBusinessClock` over the same `engineClock`, `ConfigWiring.java:107-108`) give the same date in production.
- **Tenant-state checks:** `CellWiring.admitsInbound` (ACTIVE only) agrees with `CellTenantStateFilter` (FROZEN refuses mutations and inbound events; IMPORTING refuses everything; MOVED and PURGED redirect or are gone).

## 3. Conflicting decisions between lanes
- **X1-06:** denial durability, compliance vs security, at the checkpoint (above).
- **X1-02:** registry resets. The B20 billing series uses DAILY, and the B11 UI cannot express it.
- **X1-04 (AAD row):** the B25 UI applied owner decision Q-01a (`|userId`) while `LocalQueueContract` (sangam-field) did not, so one contract had two AAD formats until W2-N.
- **Checked and not conflicting:**
  - The error codes `FISCAL_YEAR_NOT_CONFIGURED` (runtime, 422) vs `FISCAL_NOT_CONFIGURED` / `FISCAL_INVALID` (profile findings) are documented in `CHANGES-SINCE-1105d9c.md:88-96,164`.
  - Money: decimal string at the API vs minor units in profiles and packs (SD-398 / PACKS4) is applied consistently.
  - Hash formats `cmp:d:` / `sec:d:`: same body, different prefix. There is no collision between the prefixes.

## 4. Docs and build consistency
- **X1-09 LOW (still open live):**
  - `sangam/sangam-billing/README.md:17-18` shows `new BillingConnector(billingService, ledgerService, profileResolver, accountSource, clock)`. The real constructor has 6 arguments with `periodResolver` (`BillingConnector.java:60-61`; B11). B20 edited the README after B11 and kept the old line.
  - `sangam/sangam-rest/README.md:101` still says "Long-poll blocks a servlet thread (up to 30 s)" (fixed by B21, SL-141).
  - `SANGAM.md:6676-6682` (live `:6715`): the must-fix rows 2, 3, 5, 6, 7 and 8 list as open gaps M-007, M-009, SL-130, M-053, M-008, M-006 and M-001, which Wave 1 coded. They have no status note.
  - Owner: W2-J.
- **X1-10 LOW:** in `sangam/pom.xml`, surefire `:334` has `@{argLine}`, but failsafe `:347` does not. Under `-Psangam-coverage` the IT JVMs get no JaCoCo agent, so the coverage report leaves out every IT. The fix is to add `@{argLine}` to the failsafe argLine. Owner: integrator / pom owner (B26 successor).

## 5. Not checked (out of scope or not evidenced)
- **External consumers:** IFMS adapters reading Sangam profiles. Profile money is now minor units, so IFMS should be told (no repo evidence of a reader).
- **SDK generated surfaces:** Wave 2 (W2-B) work.
- **Internal and docs-only lanes:** B13 (test-only), B15 (quota internals), B23 and B28 (docs, apart from the X1-09 items).
- **B26 workflow YAML** beyond the argLine and licence-key checks.
- **Profilegen data values:** whether the money values themselves are correct (owned by the B12 tests).
