# AUDIT X3: Wave 1 hygiene and truthfulness (retroactive, read-only)

- **Auditor:** X3, 2026-11-12
- **Baseline:** checkpoint `0e53f91b` (2026-11-12 12:37:55 +0530, which is 07:07:55Z), on top of `1105d9c`.
- **Method:**
  - All repo evidence comes from `git show`, `git grep`, `git ls-tree` and `git diff` at `0e53f91b`. Nothing was read from the live working tree, except where a status claim is marked "true now".
  - No build, test or Maven run was made. No file was edited except this report.

## Severity and confidence

Severity: HIGH = wrong in a way that misleads a release or security decision. MEDIUM = a false or overstated claim someone could act on. LOW = a stale reference or a format problem. INFO = clean, or context only.

Confidence is given per finding.

## Summary

| Area | Result |
|---|---|
| RELEASE SHA-256 vs `0e53f91b` | **Clean.** All 208 final Wave 1 release hashes match the file at the checkpoint. No file was edited after its release. 135 hashes are of the CRLF form and 73 of the LF form. |
| Files changed after 03:53Z (Wave 1 window) | 191 files. 190 were claimed and released. 1 had no claim: `TESTING-GAP-REGISTER.md` (integrator-only). |
| Cross-lane overlaps | **None** on 2026-11-12. Every file touched by two lanes was edited one lane after the other. |
| Open claims at the checkpoint | **None** for Wave 1 lanes. The last release before the checkpoint is B26 at 07:03:36Z. |
| Edit before claim (rule breaks) | B21: 4 files with no CLAIM line. B25: 1 file claimed late. B19: admitted, but the claims file cannot show it. |
| B22 claims-file restore | Wave 1 lines are structurally complete. It cannot be fully proven, because no copy from before the corruption exists. |
| Register truth (≈30 rows) | No named class, test, file or changeset is missing. Wrong or overstated: 6 MEDIUM and about 15 LOW, mostly stale line numbers. |
| backlog-status.json | M-004 CLOSED and M-008 CODED overstate. Six notes describe Wave 2 work that was not in `0e53f91b`. |

---

## 1. FILE-CLAIMS hygiene

Scope: every path in `git diff --name-status -M 1105d9c 0e53f91b` (1,903 paths: 368 added, 8 deleted, 12 renamed, 1,515 modified), checked against the FILE-CLAIMS lines up to 07:07:55Z.

The script (scratchpad `x3/claims.py`) placed each path in time by comparing the commit with the two handoff snapshots:

- `snapshots/20261111-1924` (19:24Z on 11-11)
- `snapshots/20261112-0923` (03:53Z on 11-12; it holds B11's work but nothing from B12 onwards)

| Bucket | Files | Claims |
|---|---|---|
| Unchanged since 19:24Z on 11-11 | 216 | 165 have no claim line. This work predates or overlaps the start of the protocol at 18:46Z. |
| Changed between 19:24Z on 11-11 and 03:53Z on 11-12 | 1,496 | 1,340 matched a claim. The 156 without one are listed in X3-1.9. |
| Changed after 03:53Z on 11-12 (Wave 1 lanes B12 to B28 and the integrator) | 191 | 190 claimed and released, 1 unclaimed (X3-1.3) |

### X3-1.1 Release SHA-256 values match the checkpoint (INFO, clean; confidence 99%)

I took the final released hash of every Wave 1 file (208 files, FILE-CLAIMS L522 to L695) from a hand-built table in scratchpad `x3/hashes.py`. Each was compared with SHA-256 of `git show 0e53f91b:<path>` in three forms: raw, LF-normalised and CRLF.

**Result: 208 of 208 match, with 0 mismatches.**

- 135 match the CRLF form (the CRLF work tree).
- 73 match the LF or raw form.
- The 8-hex prefixes used by B11 and B12 also match.
- **No file was edited after its last release up to the checkpoint.**

Intermediate releases that a later claim superseded were not checked. Examples are B24's five successive `SangamProcessEnginePlugin` hashes, B26's first `pom.xml`, `security.yml` and `ci-build.yml` hashes, and B25's first `fieldSync.ts`.

### X3-1.2 Release hashes do not say which line ending was hashed (LOW; confidence 95%)

135 of the 208 hashes are of the CRLF work-tree bytes, not of the committed LF blob. The rules say "release with its SHA-256" but not of which form. Only B23 says "CRLF kept".

Example: SL-135's "SangamProcessEnginePlugin hash A2048CD8" is the CRLF form. The committed LF blob hashes to `5486eb6d…`.

Anyone who checks against `git show` will see false mismatches. Recommendation: state `sha256(lf)` or `sha256(worktree)` in the release format.

### X3-1.3 `TESTING-GAP-REGISTER.md` was edited with no claim line (LOW; confidence 95%)

The file changed after 03:53Z (new rows SL-125 to SL-146, AC-01 to AC-11, AT-6 and others) but has no CLAIM or RELEASE line. The rules make it integrator-only, so there was no clash.

The integrator logged its other two Wave 1 edits:

- L557/L558: `generate_scale_changelogs.py`
- L691/L692: `.gitattributes`

The register was not logged. So "the integrator's own edits are logged as INTEGRATOR" holds for those two files only.

### X3-1.4 `semgrep/` files released without per-file hashes (LOW; confidence 99%)

B26 claimed the directory at L662 ("new semgrep/ rule directory"). It released it at L695 with "semgrep/ (new, 5 rules + 5 test files, hashes in B26 report)".

The 10 files cannot be checked from FILE-CLAIMS. They are `semgrep/sangam-{jdbc-sql-concat,print-stack-trace,runtime-exec,secret-in-log,tls-verification-disabled}.{yaml,java}`.

### X3-1.5 Edits before a claim (rule breaks)

| ID | Lane | Files | Evidence | Severity | Confidence |
|---|---|---|---|---|---|
| a | B21 | `sangam-integration/.../inbox/H2InboxFixture.java`, `sangam-rest/.../adapter/operaton/FakeWorkPort.java`, `sangam-rest/.../web/WorkControllerLongPollTest.java`, `sangam-server/.../config/WakeupLongPollWiringTest.java` | All four are new at `0e53f91b` and released at L625, L632, L634 and L638 with no CLAIM line. B21's L639 NOTE admits "Extra new test files beyond the claim lines". | MEDIUM | 99% |
| b | B25 | `sangam/sangam-ui/scripts/i18n-dynamic.json` | CLAIM at L642 06:21:41Z ("claimed late, edited inside the W1-G lane"). The RELEASE at L644 has the same second, so the edit came before the claim. Admitted. | LOW | 99% |
| c | B19 | Admitted by the owner | FILE-CLAIMS cannot show it. Every B19 file has a CLAIM line before its release: L568, L569, L601, L646 and L654, all released at L664 to L690. No snapshot falls inside 05:36Z to 06:43Z, so the edit times cannot be recovered. **Unverifiable.** | LOW | n/a |
| d | B36 | (Wave 2) | B36's first claim is at 07:43:13Z, after the checkpoint, so none of its edits are in `0e53f91b`. Out of scope for Wave 1. | INFO | 99% |

### X3-1.6 Files edited by more than one lane on 2026-11-12: no overlaps (INFO; confidence 95%)

| File | Lanes and windows (UTC) |
|---|---|
| `HANDOFF.md` | B16 04:24–04:29, then B28 05:40–05:55 |
| `SANGAM.md` | B16 04:24–04:29, then B28 05:40–05:55 |
| `sangam/sangam-server/README.md` | B16 04:24–04:29, then B28 05:40–05:55 |
| `sangam-config/.../profile/ResolvedProfile.java` | B11 02:08–02:32, then B12 04:23–04:38 |
| `sangam-config/.../ProfileDataTest.java` | B11 02:08–02:32, then B12 04:23–04:38 |
| `profilegen/central.py`, `profiles/**` | B11 02:08–02:32, then B12 04:23–04:38 and 04:42–04:49 |
| `sangam-ui/src/i18n/locales/{en,hi}.json` | B11 02:08–02:32, then B25 05:54–06:21 and 06:24–06:33 |
| `.github/workflows/ci-build.yml` | B4 (11-11, released 20:01:36Z), then B26 05:43–06:36 and 06:46–07:03 |
| `sangam/pom.xml` | B4 (11-11, released), then B26 05:43–06:36 and 06:40–07:03 |
| `.gitattributes` | B4 (11-11, released), then INTEGRATOR 06:45:41 |
| `ArchitectureGuardTest.java` | SL-106 and B2 (11-11, released), then B26 05:27–05:38 |
| `sangam-engine/README.md` | D1 (11-11, released), then B24 several claim/release cycles from 05:44 to 06:40 |

My matcher also reported overlaps on many `packs/**/forms/*.form.json` and `profiles/in/dmn/**` files. Those are false positives: the release text was abbreviated (see X3-1.7). No real cross-lane overlap exists.

Cross-lane edits made with permission recorded in the claim line:

- B24 `server-configmap.yaml` (L640, "integrator ownership grant").
- B26 `ci-build.yml` (L693, "W1-A request").

I found no edit to another lane's file without recorded agreement. I did not check every file against the BACKLOG §3 lane ownership table, so the confidence on "none" is 85%.

### X3-1.7 Release format makes machine checks unreliable (LOW; confidence 95%)

- **Glob claims are released only in prose.** B11 claimed `profiles/**` at L515, and B12 claimed `profiles/**`, `packs/**` and `META-INF/**` at L532 and L559. Their releases (L523, L555, L561) say "ONE regeneration, exactly N files changed" without restating the glob. A parser therefore sees those claims as still open.
- **Some releases name a module and class, not a path.** Examples: B8, B10, B11 and B12 ("sangam-config ProfileResolver 6FB79E7A").
- **Hash length varies.** B10 to B12 use 8-hex prefixes; B13 onwards use the full 64 hex.
- **B26 released `governance.yml` twice.** L572 and L573 both list it, as M-096 and M-094/M-096. This is harmless.

### X3-1.8 B22's corruption and restore of the claims file

There is no copy of FILE-CLAIMS.md from before the corruption: the snapshots cover the repo, not this file. Completeness therefore cannot be proven. Structural checks (scratchpad `x3/integrity.py`):

- The file is valid UTF-8, with no NUL bytes, no BOM and **0 duplicate lines**.
- All lines dated 2026-11-12 carry a timestamp. Up to the checkpoint they are in time order. The one out-of-order line, L765, comes after the checkpoint.
- Six lines have no timestamp. All six are from 11-11:
  - L65 and L74 to L77 are IFMS-WF lines.
  - L147 is B5's claim. L148 explains it: "the line above without a time is the same claim".

  These may be corruption leftovers or original mistakes; the file cannot tell which. Without a time, the IFMS-WF RELEASE of `ProfileDataTest.java` at L76 does not close its CLAIM at L38 for a time-based check. Severity LOW.
- **Line endings are mixed.** L1 to L600 are LF. From L601 (05:49:46Z, three minutes after B22's claims at L595 to L600), 136 lines are CRLF, interleaved with LF lines. This shows that at least two different writers appended to the file around B22's time. It is not proof that anything was lost.
- **B22's own lines are complete.** Its claims (L595 to L600) and releases (L613 to L616) cover every file B22 changed. All 15 B22 release hashes match `0e53f91b` (X3-1.1).
- **Every Wave 1 lane has its claims and releases present.** That covers B11 to B16, B19 to B26, B28 and INTEGRATOR, and every lane's last release says "holds no claims". All 190 claimed files changed after 03:53Z pair with a claim, so no Wave 1 claim line appears to be missing.

**Verdict:** the restore looks complete for the Wave 1 window (confidence 85%). The six timestamp-less 11-11 lines and the mixed line endings are the only irregularities.

### X3-1.9 Context: unclaimed edits before Wave 1 (INFO; confidence 90%)

156 files changed between 19:24Z on 11-11 and 03:53Z on 11-12 have no claim line at all. B11's files in that window are all claimed, so these are earlier lanes' work (11-11 evening, or 11-12 before 02:08Z). At that time the rule required claims for listed shared files only (see FILE-CLAIMS L22).

The list is at `C:\Users\LOKEND~2\AppData\Local\Temp\claude\b920001b93f0ae3b\6e98c2a7-cdac-4783-9098-832546572812\scratchpad\x3\unclaimed_1924_0923.txt`:

| Area | Files |
|---|---|
| `sdks/{java,php,dotnet,python,typescript}` | 55 |
| `sangam-server` | 21 |
| `sangam-flowable-ports` | 16 |
| `sangam-engine` | 9 |
| `sangam-core` | 6 |
| `sangam-rest` | 7 |
| `perf/results` | 5 |
| `spike/results` | 3 |
| Others | 34 |

These are outside the Wave 1 window, so they are not counted as Wave 1 findings.

---

## 2. Register truth (`sangam/TESTING-GAP-REGISTER.md` at `0e53f91b`)

Rows checked: SL-102, SL-125 to SL-146, AC-01 to AC-11, AT-6, LMMH-HIER, JDBC-ONE and SG-39.

- **SL-147 has no row at `0e53f91b`.** It is used only later, as the reg for M-045 in Wave 2.
- **SG-39 is OPEN**, which is accurate: `values.schema.json` had no ingress entry at the checkpoint.

**No row names a class, test, file or changeset that does not exist at `0e53f91b`.** Every test count matches, except the two in the table below.

### MEDIUM

| Row | Claim | Evidence at `0e53f91b` | Confidence |
|---|---|---|---|
| SL-137 | "Docs: compliance README:162-164 gap closed" | `sangam-compliance/README.md:162-164` still reads "Denial audits roll back… is not done here because of the ROOT_SEQ deadlock risk". B22 did not claim or edit the README. WAVE2-CARRY row W1-D/B22 lists "Close the gap note in `sangam-compliance/README.md:162-164`" as work still to do. **False.** | 97% |
| SL-135 | "MandatoryAuditH2Test (8 …)" | The file has **12** `@Test` methods. It grew with the variable/activity and auto tests that the same row describes further on. The count is stale. | 98% |
| SL-145 | Status "OPEN … ci-build cmp lines for the 4 new files (W1-H)" | `.github/workflows/ci-build.yml:66-69` already loops `cmp` over the control-plane, rollup, gateway and asyncapi contracts. SL-144 says the integrator verified that loop. The two rows contradict each other; SL-145's open item is stale. | 97% |
| SL-127 | "already covered by RequestStatusContractH2Test" (missing `fiscalStartYear`) | `aMissingRequiredNumberIsRefusedNamingTheField` (lines 154-162) tests `/ops/cleanup/ttl` and its field `ttlDays`. It does not touch ProgrammeController or `GeneratePeriodsRequest`. It shows the generic Jackson behaviour, not this DTO. **Overstated.** "Jackson 3.1.7" also cannot be checked: `sangam/pom.xml:68` pins `version.jackson` 2.22.3. | 90% (60% for the Jackson version) |
| SL-129 | Row integrity | The row ends `… ||`. It has 18 pipes against 6 in a normal row, because it holds two status cells. The two cells disagree: "CODED…being finished" vs "CODE DONE", and AAD `tenant\|device\|operation\|user` vs `tenant\|installation\|operation\|user`. | 99% |
| SL-136 | "configmap pass-through being added" | Already present: `deploy/helm/sangam/templates/server-configmap.yaml:52` sets `SANGAM_JOB_EXCLUSIVE_OVER_PROCESS_HIERARCHIES`, released by B24 at L648. **Understated**, so not misleading for safety. Rated LOW-MEDIUM. | 90% |

### LOW: stale or wrong references

All confidences are 90-95% unless stated.

| Row | Claim | Actual |
|---|---|---|
| SL-102 | `gov_ifms.py:530-533` re-appropriation, `:1105-1109` appraisal ladder (pre-fix) | At `1105d9c` these are `:501-505` and `:1071-1078`. |
| SL-102 | "23 keys in 7 profilegen files" | Unit-text values appear in 5 files (central, delta, goi, skeletons, states), and the fix column says 25 values. Confidence 70%. |
| SL-128 | `:109` (`root = os.path.join(module, …)`) | Line 108 at `1105d9c`. |
| SL-129 | "Ref P3-026, SANGAM.md:6676" | Line 6676 is XII.4 item #2 (idempotency). The PWA item is #8 at :6682. P3-026 does not appear in SANGAM.md. |
| SL-130 / SL-131 | Problem-column line references | They mix trees. `ConfigConnector:60, :196-207`, `JdbcConfigStore.next:166-178` and `LedgerService.arrears:176-185` match only `0e53f91b`. The `BillingService` lines match only `1105d9c`. |
| SL-133 | `IntegrationWiring.java:265` | Now `:271-272`. |
| SL-133 | `GuardedJobHandler:94-97` (root lock) | `:91-92`. Lines 94-97 are the timer logic. |
| SL-133 | `SangamConnectorDelegate:85` | `:83`. |
| SL-135 | `postInit:270` | Declared at :272; the refusal call is at :275. |
| SL-135 | `postProcessEngineBuild:299` | Declared at :306; the check is at :311. |
| SL-135 | `postInit:280` | `:282`. |
| SL-135 | `mandatoryAudit:335` | `:358`. |
| SL-135 | `SangamEngineSettings:120-124` | `:123` at `0e53f91b`, `:110` at `1105d9c`. |
| SL-135 | Hash A2048CD8 | This is the CRLF form; see X3-1.2. |
| SL-136 | `SangamEngineSettings:153 false` | `= false` was at :140 at `1105d9c`. At `0e53f91b` the default `= true` is at :160. |
| SL-143 | `App.tsx:191-222` | The 15 routes are at :191-192 and :205-217. Line 222 is `/notifications`. |
| SL-144 | `build_tools_not_shipped.py` "5 tests" | `test_build_tools_not_shipped.py` defines **6** `test_` methods. Confidence 99%. |
| SL-145 | `SANGAM.md:6677` (no contracts) | The statement is at :6683, and it names only the control plane and roll-up, not the gateway. |
| SL-145 | "pp_rest asserts it is the last segment" | The assert is in `op()` at `gen_openapi.py:658`. `pp_rest` only sets the flag. |
| AC-01..06 | `acceptance_runs.py:97-136` | The AC-06 entry ends at :137. |
| SL-126 | Design gap, not a false claim | The form offers `fiscalStartMonth` only for reset FISCAL_YEAR. The server also requires it for any `{fy}`/`{fyStart}` pattern (`NumberSeriesDefinition.java:40-42`). Confidence 80%. |

### Verified true (summary)

Everything else checked was correct. About 230 concrete claims passed.

- **Fiscal fail-closed chain (SL-125, SL-127):** every class and code exists, and all 8 tests are present.
- **Billing numbering (SL-130, SL-131):** changeset `0037-01-bil_document` is present in all five baselines. BillingRetryTest has 8 tests and RegistryDocumentNumbersH2Test has 5.
- **NOT LIVE / NOT ACTIVE claims are all true at `0e53f91b`:**
  - `BillingWiring:73-76` uses the old constructor.
  - `SangamRestAutoConfiguration:126` uses the 2-argument adapter.
  - `BrokerInboxWiring:115,141` passes no admission.
  - `SecurityConfig:89` is `.authenticated()`.
- **Test counts that match:**
  - ClientJwtVerifierTest 18, HealthHooksControllerCdsTest 6, HealthConnectorsPropertiesCdsTest 6.
  - WakeupListenerTest 9, WorkLongPollTest 11, TrackingWorkPortLongPollTest 4, WorkControllerLongPollTest 7.
  - KafkaInboxConsumerTenantStateTest 5, JmsInboxListenerTenantStateTest 4.
  - DenialAuditSurvivesRollbackH2Test 6, ComplianceDenialAuditPoolTest 4.
  - JobExclusiveOverProcessHierarchiesH2Test 5, MandatoryAuditHistoryLevelTest 7, access.test.ts 5, JdbcOneTest 4.
- **SL-144:** every tool version, the `@{argLine}` setting, the 5 Semgrep rules with their test cases, and the 4+4 LGPL entries are present.
- **SL-145:** the control plane has 17 ops and the roll-up 5. The gateway has 10 problem types. The AsyncAPI has 5 channels and 17 messages.
- **SL-135:** the `AUTO_REFUSED` and `GLOBAL_NONE_REFUSED` refusals, and the `PerDefinitionHistoryLevelBase.of:391` refusal, exist as described.
- **SL-140 / SL-141:** the WakeupListener back-off, liveness check, channel validation and non-PG warning, and the WorkLongPoll defaults (1000/4) match.
- **AT-6:** PlugAndPlayIT:41-45 is the AT-6 block.
- **LMMH-HIER:** `ClassificationRegistry.java:164-166,251` and `ProfileDataTest:153-169` match.
- **JDBC-ONE:** `Jdbc.java:93-112` matches.
- **PilotScenarioTest:** all 5 methods exist.
- **SL-132, SL-133, SL-134 and SL-146:** their OPEN status is accurate.

---

## 3. Backlog status truth (`sangam-handoff/backlog-status.json`, Wave 1 M-ids)

The JSON is a live file and is already updated for Wave 2. It has no "as-of" commit, so a status cannot be tied to the checkpoint. I checked each Wave 1 M-id at `0e53f91b`, and checked against the work tree only where a note describes later work.

### Overstated statuses (true at neither the checkpoint nor now)

| M-id | Status | Problem | Severity | Confidence |
|---|---|---|---|---|
| M-004 | CLOSED | Per the JSON's own vocabulary, CODED means tests written but not run. The LMMH-HIER register row says "FIXED in tree; **run pending** (test phase)", and the seed slips 1128-1132 still wait on owner Q-13. The code exists, but CLOSED claims verification that has not happened. It should be CODED. | MEDIUM | 90% |
| M-008 | CODED | The note itself says "replay controller pending; flowable-ports path W3-A". The register (SL-142) says "PARTLY CODED", with the park-then-ack path for MOVED/PURGED and the flowable-ports Rabbit/Kafka/JMS path open. At `0e53f91b` the admission was not even passed to the consumers (`BrokerInboxWiring:115,141`). It should be PARTIAL. | MEDIUM | 90% |

### Notes that describe Wave 2 work, not `0e53f91b`

These were true at the time of the audit (I checked the work tree read-only), but false at the checkpoint. At `0e53f91b` the right statuses were CODED_NOT_LIVE (M-002, M-007) and PARTIAL (M-095, M-096, M-098).

| M-id | Note | At `0e53f91b` | Now (work tree) |
|---|---|---|---|
| M-002 | "live wiring done (BillingWiring); series.next durable reservation done" | No `DocumentNumbers` in sangam-server main. ConfigConnector series dedupe is the node-local `issued` map (`ConfigConnector.java:60`, :197, :206). | BillingWiring imports `DocumentNumbers`; ConfigConnector has durable reservations (`SeriesReservations`). |
| M-007 | "encryptor wired (SangamRestAutoConfiguration)" | `new JdbcIdempotencyAdapter(tx, props)` at :126 (2 arguments). | 3 arguments, with the encryptor. |
| M-006 | "route+verifier wired; cluster jti coded" | `/cds-services` is `.authenticated()`; there is no replay-guard class. | `SecurityConfig.CDS_SERVICES_PATHS`; `JdbcReplayGuard.java` exists. The status (CODED_NOT_LIVE) is still correct. |
| M-095 | "jqwik/Jazzer/Cucumber harnesses in src/test/java" | None exist (`PackParserFuzzTest`, `ManualJourneysCucumberTest` and `ScenarioDriverPropertiesTest` are absent). The master backlog schedules the harnesses for Wave 2. | Present (B37, Wave 2). |
| M-096 | "+ GLOSSARY.md + THREAT-MODEL.md (TM-1..23)" | Neither file exists in `0e53f91b`. | `sangam/docs/GLOSSARY.md` and `THREAT-MODEL.md` exist. |
| M-098 | "settings frozen after configure" | Only the class-size gate exists. `SangamEngineSettings` has no freeze; the master backlog puts the freeze in Wave 2. | Freeze javadoc and code are present. |

### Confirmed at `0e53f91b` (code exists; status not overstated)

| M-id | Status | Evidence |
|---|---|---|
| M-001 | CODED | sangam-ui offline queue and LogoutPrompt. The server-side AAD work is correctly marked as Wave 2. |
| M-005 | CODED | WakeupListener, wired in `IntegrationWiring:365-385`. |
| M-009 | CODED | Compliance leaf pool and `cmp:d:` chain. |
| M-012 | CODED | Mandatory audit wrappers and refusals. |
| M-013, M-017, M-050 | CODED | Asset route, 3 app contracts and baselines, AsyncAPI. |
| M-028 | CODED | `access.ts` and 15 guarded routes. |
| M-053 | CODED | WorkLongPoll / DeferredResult. |
| M-055 | CODED | Default true, Helm values, schema, configmap. |
| M-094 | CODED | Report-only scanners. They have never run, which the JSON's note discloses. |
| M-110, M-111 | CODED | README, SECTOR-CONFORMANCE and SANGAM.md X.5.3 edits were released by B28 and B16, and their hashes match. |
| M-115 | CODED | AC rows present. |
| M-118 | CODED | Rows SL-53a, AT-6, Q22/CF-11, UI-UPGRADE-1, LMMH-HIER, JDBC-ONE and IFMSPACKS-EXEC-RACE present. The status-word fixes were not checked one by one. |
| M-119 | CLOSED | `D:\GITHUB  Project\JOINT-LEDGER-SANGAM-IFMS.md:300` has "[ACK] C-19 IFMS→SANGAM". |
| M-120 / M-122 | REFERENCE "FIXED in tree" | SL-102 and SL-78 are verified above. |

---

## What was clean

- Every final Wave 1 RELEASE hash (208 files) matches `0e53f91b`. No edit was made after a release.
- No claim overlaps in time between lanes. No open claims at the checkpoint. All Wave 1 lanes ended with "holds no claims".
- Every file changed in the Wave 1 window has a claim line, except the integrator-only register and B21's four admitted test files.
- No register row names a non-existent class, test, file or changeset. All NOT LIVE / NOT ACTIVE caveats were truthful at the checkpoint.
- The three stray root files in `1105d9c` (`gen_openapi.py`, `CmmnTimerEventListenerH2Test.java`, `PackAcceptanceTest.java`) are deleted at `0e53f91b`. The E46 fixture moves are byte-identical renames (R100), as SG-37 claims.

## Recommendations

None of these were applied; the audit is read-only.

1. **Register:**
   - Fix the SL-137 docs claim, or close the README gap.
   - SL-135: change the test count to 12 and update the line numbers.
   - SL-145: remove the stale "ci-build cmp" open item.
   - SL-127: restate the RequestStatusContractH2Test coverage.
   - SL-129: merge the duplicated status cell.
   - SL-144: change the test count to 6.
2. **backlog-status.json:**
   - M-004: CLOSED to CODED.
   - M-008: CODED to PARTIAL.
   - Add a commit/as-of field so a status can be tied to a checkpoint.
3. **FILE-CLAIMS format:**
   - Say which form each hash is of (LF index or work-tree CRLF).
   - Name the glob in glob releases, and use full paths.
   - Give a hash for each file under `semgrep/`.
   - Have the integrator log its register edits.
