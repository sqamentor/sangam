# B11: fail closed on a missing fiscal year (SL-125), phase 1 evidence (2026-11-12)

Tree: `.claude/worktrees/workspace-scan-resume-06b524` (branch claude/workspace-scan-resume-06b524). Phase 1 only: no repo
file edited, no Maven, no tests run. One read-only helper script (scratchpad `fiscal_chains.py`) approximated profile
resolution (extends, adopt sections and scope prefixes) to check which chains have `locale.fiscal`. All paths below are
relative to `sangam/`.

## 0. Headline findings

1. **US already fails profile validation.** `layers.yaml:11-13` (generated from `tools/profilegen/central.py:13`) makes
   `locale.fiscal` REQUIRED at the `jurisdiction` layer. US is a jurisdiction document (`us/US.yaml:6`) with chain
   INTL>US and no fiscal. So `ProfileResolver.compose` (`ProfileResolver.java:241-250`) adds an ERROR
   `REQUIRED_MISSING locale.fiscal` to every US resolve once the server loads the layer model
   (`ConfigWiring.java:86-95`). Release import skips validation (`ConfigAdminService.importActive:155-161`), so US still
   goes ACTIVE. But any later activation whose dependent chain includes US is refused (`ConfigAdminService:133-142`,
   ACTIVATION_REFUSED), and ConfigConnector or ConfigController validation reports `valid:false`. No test catches
   this: `ProfileDataTest.everyGovernmentAndDepartmentChainResolvesWithoutErrors` (:66-82) covers only the government
   and department layers, and the country test (:99-108) lists the seven INTL countries, not US.
   TESTING-GAP-REGISTER:122 already records this as an "open data question".
   **This clashes with the owner's line "US stays without a fiscal year (the tenant sets it)". It is the one decision
   I need (section 4, D1).**
2. The silent default is reached far more often than the catch in `LedgerTenantDefinitions.period`. For an unknown
   tenant, `ProfileResolver.resolve` does not throw: it returns empty data with a `PROFILE_NOT_FOUND` finding
   (`ProfileResolver.java:171-175`). `locale().fiscal()` then takes the empty map and returns `calendarYear(zone)`. So
   every tenant without a profile, and US, gets a 1 January / "START" label with no error.
3. If the new refusal is simply thrown, it turns into a retry loop rather than a clear failure.
   `LedgerConnector.invoke` catches only `IllegalArgumentException` (`LedgerConnector.java:121`). Anything else
   propagates to `ConnectorInvoker.invoke:207-214`, which records OutcomeUnknown ("connector threw ..."). The ledger
   connector is class A (`LedgerConnector.java:68`), so lookup returns NotFound, then `retryAfterUnknown`
   (`ConnectorInvoker.java:236-250`). The refusal must therefore come back as a `BusinessFailure`.
4. The US health packs never call the fiscal resolver. `sec-ushealth-claim` and `-remittance` set `period=OPEN`
   (bpmn `cl_defaults` / `rm_defaults`). Failing closed does not break US health.

## 1. Inventory: defaults and swallowed failures

| # | file:line | current behaviour | fiscal semantics? |
|---|---|---|---|
| A1 | `sangam-config/.../locale/FiscalCalendar.java:45-48` | null or empty map → `calendarYear(defaultZone)` (1 Jan, label START) | yes, the core default |
| A2 | `FiscalCalendar.java:50-51` | `startMonth` and `startDay` default to 1 inside a non-empty map | yes |
| A3 | `FiscalCalendar.java:30` | `labelFormat` null → `"START-END2"` (the Indian 2026-27 form) | yes; an India-flavoured default (SL-104 spirit) |
| A4 | `FiscalCalendar.java:29` | zone null → UTC; `from` passes the locale zone (`:52`) | inheritance, acceptable |
| A5 | `LocaleSettings.java:62` | builds fiscal eagerly in `from` | constructor path |
| A6 | `LocaleSettings.java:42` | locale `zone` missing → UTC | zone default, not fiscal (out of scope, noted) |
| B1 | `sangam-rest/.../ledger/LedgerTenantDefinitions.java:75-77` | `profiles == null` → `day.getYear()` | yes |
| B2 | `LedgerTenantDefinitions.java:78-82` | `catch (RuntimeException)` → `day.getYear()` | yes, swallowed |
| B3 | `sangam-ledger/.../LedgerConnector.java:81-84` | `period` / `fiscalYear` missing or `current` → resolver (B1/B2) | consumer |
| B4 | `LedgerConnector.java:121` | only IAE becomes `LEDGER_REQUEST_INVALID`; other throws → OutcomeUnknown → retry | failure surface |
| C1 | `sangam-registry/.../kernel/ValidityRule.java:46` | `fiscalStartMonth` defaults to 1, even with `alignTo: FISCAL_YEAR_END` (`:139-145`) | yes |
| C2 | `sangam-registry/.../numbering/NumberSeriesDefinition.java:52` | `fiscalStartMonth` defaults to 1 with `reset: FISCAL_YEAR` (`:75`) and tokens `{fy}`, `{fyStart}`, `{period}` (`:88-90,107-109`) | yes |
| C3 | `NumberSeriesDefinition.java:61-64` (`toMap`) | always writes `fiscalStartMonth` (so a defaulted 1 is stored as if explicit) | data consequence |
| D1 | `sangam-programme/.../config/ProgrammeConfig.java:54` | `fiscalYearStartMonth` defaults to 1 | yes |
| D2 | `ReturnCampaignDelegate.java:46-48`, `ProgrammeRestAdapter.java:106-107` | use D1's value | consumers |
| D3 | `sangam-rest/.../programme/ProgrammeController.java:70` | `GeneratePeriodsRequest(int fiscalStartYear)`: a primitive, so a missing field is likely 0 and `LocalDate.of(0, m, 1)` builds year-0 periods. **Unverified**: depends on Jackson `FAIL_ON_NULL_FOR_PRIMITIVES` / missing-creator settings | yes (input validation) |
| E1 | `sangam-billing/.../BillingConnector.java:71` | missing `period` → calendar year `today.substring(0,4)` | billing period label; not stated as fiscal (D2) |
| E2 | `sangam-rest/.../billing/BillingController.java:183` | refund missing `period` → `today.getYear()` (matches E1) | same |
| F1 | `sangam-config/.../catalogue/NumberSeries.java:22-29` | `period(Reset, d, fiscalStartYear)`: **no callers** (grep); `series.next` uses `asOf` (`ConfigConnector.java:201`) | dead code |

Out of scope (calendar year by design, never described as fiscal): case numbering YEARLY
(`sangam-case/.../NumberService.java:42-46`), resources booking and allotment refs
(`BookingService.java:839`, `AllotmentService.java:131`, which uses UTC: a separate zone note), and the portal
reference (`OperatonPortalAdapter.java:148`).

All `locale()` callers in main code (must not throw on a missing fiscal):
- `ResolvedProfile.amount:46` (money)
- `ProfileBusinessClock:98` (zone)
- `LedgerWiring:133` (exchange rates)
- `PacksWiring:177` and `ResourcesWiring:145` (business calendars)
- `LedgerTenantDefinitions:79`: the only `fiscal()` caller in main.

Tests: only `ProfileDataTest:107` calls `fiscal()`. No unit test of `FiscalCalendar` exists (grep).

## 2. Data audit

**Profiles** (profiles.idx and intl.idx; 66 documents):
- 47 Indian chains (IN, GOI, 9 GOI departments, 36 states and UTs) inherit `IN.yaml:35-38` `{4, 1, START-END2}`.
- The seven INTL countries all state the month, day and label:
  - BGD 7/1/END
  - BRA 1/1/START
  - GBR 4/1/END
  - IDN 1/1/START
  - KEN 7/1/END
  - PHL 1/1/START
  - USA 10/1/END

Resolved chains with NO `locale.fiscal`:
- **US** (jurisdiction, INTL>US): the only tenant-grade chain.
- Non-tenant presets: INTL, INTL-PFM, the 5 REGIME-* documents, and the 5 IN-PRESET-* (category; adopted by states).

Every shipped fiscal map states `startMonth`, `startDay` and `label` explicitly. So making all three required changes
no shipped profile. Test fixtures: `AT-5.json` (onboarding and server) carries `fiscal {1, 1, START}`, explicit.

**Pack requirements:** `sangam-onboarding/.../pack-requires.json:4-7` already requires `locale.fiscal` for `gov.ifms.*`.
`ReadinessChecker.profileKeys:77-86` gives PROFILE_KEY_MISSING at onboarding. `sec.health.*` requires only
`locale.currency`.

**Packs that reach the fiscal resolver** (missing or `current` period, from a BPMN and pack.json scan):
- ifms-* (bill, budget-allocation, budget-formulation, loans-advances, reappropriation, sna-sparsh,
  surrender-lapse with `fiscalYear=current`)
- budget-reappropriation
- gov-intl-pfm-execution

All of these run on Indian or INTL-country profiles, which set fiscal. sec-ushealth uses `period=OPEN`. billing-cycle
and sec-util-* use `billing.*` (E1).

**Registry and programme data:** no shipped pack, packgen or seed defines a number series with `reset: FISCAL_YEAR`
or `{fy}`, a validity with `alignTo: FISCAL_YEAR_END`, or a programme config (grep of `packs/`, `tools/` and all test
trees for `FISCAL_YEAR`, `fiscalStartMonth`, `{fy}`, `alignTo`, `fiscalYearStartMonth` and `programme-config`). No
registry or programme test touches these defaults. No generator change is needed for registry or programme data.

Side finding: `sangam-ui/src/modules/registry.tsx:66` sends `resetPolicy`, but `NumberSeriesDefinition.from:44` reads
`reset`, so a UI-defined FISCAL_YEAR series likely becomes NEVER. **Unverified** how `f.select` maps to the body key.
The UI also has no `fiscalStartMonth` field.

**Tests relying on the defaults:**
- None call `calendarYear` or rely on an empty fiscal map.
- `IfmsPacksIT:164-167` calls `defs.period` for a GOI tenant (fiscal set): still fine.
- `LedgerJdbcH2Test`, `MixedRemittanceTest` and `RemittanceAndSeedsTest` inject lambda resolvers: unaffected.
- No test asserts that a resolved profile has no findings at all; all filter to errors or by code
  (`ProfileDataTest:105`, ActivationOwnScopeTest, ProfilePinningTest, CatalogueProfileCheckTest,
  DocumentTypeAdoptionTest). So a new WARNING finding is safe.

## 3. Proposed root design (ADR-D-20 generic; SL-104: no India defaults)

| # | change (file:line) | proposed behaviour | callers affected | data / tests | conf. |
|---|---|---|---|---|---|
| P1 | `FiscalCalendar.from:45-55` | `from` returns `Optional<FiscalCalendar>` (empty map → empty). Inside a present map, `startMonth` is required (`FISCAL_START_MONTH_MISSING` naming the path `locale.fiscal.startMonth`); `adjustmentPeriods` stays at default 0 | `LocaleSettings.from` only | none shipped (section 2) | 98% |
| P2 | `FiscalCalendar.java:51` startDay | **required too.** Evidence: no doc or javadoc says startDay defaults to 1 (record doc `:16` says only "1..28"), and every shipped map states it. Registry and programme assume day 1 implicitly (P6, P7). | none | none | 92% (contract choice; the alternative "default 1 = start of month" is not documented anywhere) |
| P3 | `FiscalCalendar.java:30` label default `START-END2` | required in `from` (no India-form default); the canonical constructor keeps rejecting null | 4-arg constructor callers: none in main | every shipped map has a label | 95% |
| P4 | `LocaleSettings.java:30,62` | keep the record component as nullable or Optional. `fiscal()` throws a typed `FiscalYearNotConfiguredException extends ConfigException` (code `FISCAL_YEAR_NOT_CONFIGURED`, message names scope/binding and `locale.fiscal`). Add `hasFiscal()` / `fiscalOrEmpty()`. `from(...)` never throws for a missing fiscal; it still throws for a malformed one | money, zone, calendar and rate users unaffected | `ProfileDataTest:107` still green | 97%. To name the profile, `ResolvedProfile.locale()` should pass `scope` and `binding` into the exception (`ResolvedProfile.java:62-64`) |
| P5 | `ProfileResolver.compose` (~`:241-252`) | when the resolved data has no `locale.fiscal`, add `Finding.warn("FISCAL_NOT_CONFIGURED", "locale.fiscal", "locale.fiscal not configured; fiscal operations are refused", null)`. **The Finding model supports WARNING** (`Finding.java:19,25`); `anyError` ignores it. Better in `compose` than in `ProfileValidator.validateResolved`, so it reaches every `r.findings()` consumer | ConfigController and activation outputs show it | no test asserts zero findings | 97% (it also shows on presets resolved alone; acceptable: they are not tenants) |
| P6 | `LedgerTenantDefinitions.java:74-83` | no catch, no invented year:<br>(a) `profiles == null` → `FISCAL_YEAR_NOT_CONFIGURED` ("no configuration profile resolver: the fiscal period of tenant X cannot be resolved");<br>(b) a `PROFILE_NOT_FOUND` finding → refuse naming the tenant;<br>(c) otherwise `locale().fiscal()` (P4 throws) | `periodResolver()` users: `LedgerWiring:204`, `PackGapWiring:72`, IfmsPacksIT | IfmsPacksIT unaffected | 97% |
| P7 | `LedgerConnector.java:81-84,121` | catch the typed exception and return `BusinessFailure("FISCAL_YEAR_NOT_CONFIGURED", msg)`. No effect happened, so it settles as a failure (`ConnectorInvoker:223` settleFailure) instead of OutcomeUnknown and retry. In the process, `ConnectorCallDelegate:69-80` sets `<prefix>Status=FAILED` and `ReasonCode`, and a BpmnError if `errorOnFailure` is set. REST: the ledger REST endpoints never call the resolver (LedgerController uses `body.period()`), so no HTTP path changes | `LedgerDefiningConnector`, PackGap remittance | new unit test: resolver throws → BusinessFailure with code | 97% |
| P8 | `ValidityRule.from:46` | `fiscalStartMonth` required when `alignTo == FISCAL_YEAR_END` (`RegistryException.invalid("validity: alignTo FISCAL_YEAR_END needs fiscalStartMonth")`). When not fiscal, store none (`toMap` omits it; record uses 0 or null) | ObjectTypeDefinition parsing | no shipped data | 97% |
| P9 | `NumberSeriesDefinition.from:52` | required when `reset == FISCAL_YEAR` or the pattern contains `{fy}` / `{fyStart}`; otherwise not stored. **Migration note:** C3 means rows defined before the fix already hold `fiscalStartMonth:1` as if explicit, so they cannot be told apart and stay valid. Offer an operator check (list FISCAL_YEAR series with month 1) or accept. Module boundary: sangam-registry depends only on sangam-core (pom), so the month cannot come from the profile without a new port; explicit in the definition is the minimal root fix | `NumberingService.define:86`, re-reads `:104,110` | none shipped; UI form needs a `fiscalStartMonth` field (plus the `resetPolicy` side bug) | 95% (the stored-row point is a known limit) |
| P10 | `ProgrammeConfig.parse:54` | `fiscalYearStartMonth` required (ProgrammeException CONFIG "fiscalYearStartMonth is required (1..12)"). Source: the tenant's published programme config (`IndicatorRegistry.publish:40-42` validates through `parse`). sangam-programme depends only on sangam-core and flowable-ports (pom), so the value cannot come from the profile without a port. Optional follow-up: a server-side warning when it differs from the profile's `locale.fiscal.startMonth` | ReturnCampaignDelegate, ProgrammeRestAdapter | no shipped config or test | 95% |
| P11 | `ProgrammeController.java:70` | `Integer fiscalStartYear` and reject null with 400 VALIDATION_FAILED | generatePeriods | none | 90% (the year-0 path is unverified, D3) |
| P12 | `layers.yaml:11-13` / `central.py:13` | per D1 | | | |
| P13 | docs | `LedgerConnector.java:36,57-58` javadoc, `sangam-ledger/README.md:35`, `sangam-config` README locale rows, SL-125 row | | | |

Regression tests for phase 2:
- FiscalCalendar unit tests: empty map → empty; missing startMonth, startDay or label refused.
- LocaleSettings: fiscal-less locale still parses money, and `fiscal()` throws with the path.
- ProfileDataTest: US resolves with a FISCAL_NOT_CONFIGURED warning and (under D1-A) no errors.
- LedgerTenantDefinitions: no resolver, unknown tenant and US all refuse.
- LedgerConnector: BusinessFailure code.
- ValidityRule, NumberSeriesDefinition and ProgrammeConfig: missing month refused, present month accepted.

## 4. Decisions needed before code

**D1 (blocking):** the jurisdiction layer requires `locale.fiscal`, but the owner wants US to stay unset.
- **A (recommended):** drop `locale.fiscal` from the layer's `required` (`central.py:13`, then regenerate
  `layers.yaml`) and rely on P5's warning plus runtime refusal (P4, P6, P7). Packs that need fiscal are already gated
  at onboarding by `pack-requires.json` (`gov.ifms.*`). Extend the same `profileKeys` to the other fiscal-resolver
  packs (`gov-intl-pfm-execution`, `budget-reappropriation`, and so on; their pack ids still need checking against
  the rule globs). This matches "refused until the tenant sets it" and keeps US activatable.
- **B:** keep the requirement. US then stays invalid on its own, and only a tenant overlay that sets fiscal makes the
  chain valid. That is stricter, but it contradicts "US stays without a fiscal year".
- My confidence that A is what the owner means: about 85%, below 97%, so please confirm.

**D2:** are the billing defaults E1 and E2 (calendar-year period when the caller omits it) in SL-125's scope? They
invent a period label but are not described as fiscal. My recommendation is a separate ID: require an explicit
`period`, or resolve it from the ledger definition. Confidence that they belong in SL-125: 60%.

**D3:** P2. Is `startDay` required (recommended), or does it default to 1 as a documented contract?
