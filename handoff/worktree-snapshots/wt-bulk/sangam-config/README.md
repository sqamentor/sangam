<!-- SPDX-License-Identifier: Apache-2.0 -->
# sangam-config: layered configuration, master data, document-type catalogue, locale

Owner: IFMS-IMPLEMENTATION workstream (generic module per ADR-D-20). Base package `org.sangam.config`. Depends on
`sangam-core` only (no Operaton). No class names a country, government, sector or regulation: those are data, for example
`sangam-packs/src/main/resources/profiles/` (GOI, its departments, the 36 States / UTs).

## Files and what they cover

| File | Purpose | Ids |
|---|---|---|
| `common/Tree.java` | Data-tree navigation and inheritance merge (maps deep, keyed lists by `id`/`code`, `remove: true`, explicit null unsets) | ADR-D-20, GA-01, IFS-067 |
| `common/EffectiveDated.java` | Effective-dated rows (`effectiveFrom` / `effectiveTo`) | IFC-016, IFS-030 |
| `common/Finding.java`, `common/ConfigException.java` | Outcomes with severity (ERROR / WARNING / PLACEHOLDER / INFO) and rule citation | IFC-016, IFS-067 |
| `profile/ScopeRef.java` | n-level scopes (`RJ`, `RJ.PWD`, `A.B.C.D`) = engine tenant; office overlays `<scope>/<office>` | GA-30, IFS-036 |
| `profile/LayerModel.java` | Ordered layers from configuration, non-overridable layers, required paths per layer | GA-01, IFS 6.1, INTL 4.1 (regime preset layer) |
| `profile/ProfileDocument.java` | Versioned, effective-dated document: `extends`, `adopt` (donor sections), placeholders, locked paths, content hash | GA-01, GA-17, IFS-067 |
| `profile/ProfileLoader.java` | YAML (SnakeYAML SafeConstructor, alias limit) / JSON loading; directory and classpath index; decimals kept exact as strings (R33) | GA-01 |
| `profile/ProfileRepository.java`, `InMemoryProfileRepository.java` | Storage with lifecycle (DRAFT / SUBMITTED / APPROVED / ACTIVE / RETIRED / REJECTED); effective version selection | GA-01, GA-25 |
| `profile/ProfileResolver.java` | Inheritance chain per scope + office + date; layer order; locked paths; placeholders; binding string `id@v>...#hash` for the case | GA-01, GA-30, GP-19 |
| `profile/ResolvedProfile.java` | Typed accessors; amounts with locale units (`10L`, `5M`); `isPlaceholder` | GA-01 |
| `profile/ProfileValidator.java` | Profile linter: structure, resolved chain, data-declared rules (`validation.required`, `validation.enums`), plug-in checks | IFS 6.5 step 3 |
| `admin/ConfigAdminService.java` | Change control: maker ≠ checker, approval bound to the content hash, activation validates dependent chains, append-only change register | IFS-075, GA-25 |
| `master/ClassificationScheme.java` | Segmented code scheme: widths, optional, character set (pattern), owner layer, cross-scheme validation, render template, derivation rules | IFC-029..042, IFS-001..010, IFS-038, IFS-039, GA-02 |
| `master/MasterCode.java`, `MasterStore.java` | Effective-dated master codes per scope with source (correction slip) | IFC-032, IFS-002, IFS-003 |
| `master/ClassificationRegistry.java` | Parse (separated / compact), validate through scope prefixes and reference scopes, derive attributes, apply correction slips (ADD / MODIFY / RETIRE / REMAP) | IFC-030, IFC-032, IFS-003, IFS-004 |
| `master/Crosswalk.java` | Crosswalks (national → GFS / COFOG, version → version, % split), combination rules, %-split tags | INTL gap 4, IFC-034, IFS-038 |
| `master/SeedLoader.java` | Bulk CSV / YAML seed loading | IFC-032 |
| `catalogue/DocumentType.java`, `DocumentTypeCatalogue.java` | Document-type catalogue (form number, legacy forms, fields, attachments, certificates, allowed classifications, uniform segments, sanction, chain, rail, time bar, nil allowed, limits, retention) and chain templates (roles, return-by, no-edit, queue / token, signature bands, sequence); instance checks with citations | IFC-091..125, IFS-044..049, GA-05, GA-07, GA-23 |
| `catalogue/TwoSidedDocument.java` | Documents owned by two or more tenants (buyer / seller), each approving the same content hash; effective when all approve | PFM-053 |
| `catalogue/NumberSeries.java` | Number series (token per office per day, voucher per month ...) | IFC-095, IFS-048 |
| `locale/NumberGrouping.java`, `MoneyFormat.java`, `Money.java` | ISO 4217 money; grouping 3/3 or 3/2; amount units from configuration | ADR-D-20 locale |
| `locale/FiscalCalendar.java` | Any start month / day / zone, labels, periods, quarters, closing instants, adjustment periods P13 / P14 | IFC-070, INTL gap 13 |
| `locale/BusinessCalendar.java` | Weekends, holidays, extra working days, named cut-offs with next-business-day roll (e.g. 15:00 payment-file cut-off) | IFS-015 |
| `locale/ExchangeRates.java` | Effective-dated rates, conversion, exchange difference | IFC-209, IFC-229, IFC-230 |
| `locale/LocaleSettings.java` | Locale section: zone, money, fiscal calendar, languages (tag, script, direction, scope), region languages, calendars, rates | IFS-072, GA-22 |
| `api/ConfigConnector.java` | In-process `SangamConnector` for the pack connector ids `config.profile`, `config.classification`, `config.documentType`, `config.series` (operations listed in the class) | GA-01, GA-05 |
| `api/PayeeDirectory.java`, `api/PayeeConnector.java` | Payee / beneficiary golden-record port and the `registry.party` connector (operations `payee.resolve`, `payee.register`); implemented over the sangam-registry party registry by `org.sangam.billing.party.RegistryPayeeDirectory` | GA-12, IFS-056 |
| `api/ConfigPorts.java` | REST ports and the endpoint list for WS-REST | - |
| `store/ConfigSchema.java` | DDL (portable, `Dialect` tokens) | - |
| `store/JdbcConfigStore.java` | JDBC profile repository, master store, number series (row lock), change log, case binding | A-15 |

## Integration notes

- **Liquibase:** add the statements of `ConfigSchema.TEMPLATES` (tables `CFG_PROFILE`, `CFG_CHANGE`, `CFG_MASTER_CODE`,
  `CFG_SERIES`, `CFG_CASE_BINDING`) to sangam-persistence, per dialect via `Dialect.expand`.
- **Connector bindings:** bind the pack connector ids `config.profile`, `config.documentType`, `config.classification`,
  `config.series` to one `ConfigConnector(resolver, registry, store)` (sangam-integration `PackConnectorBindings`).
- **Start-up:** `ConfigAdminService.importActive(ProfileLoader.loadIndex(cl, "profiles/profiles.idx"), "release")` and the same for
  `profiles/intl.idx` (international PFM reference, regime presets and the seven country profiles); load the
  seeds listed under `seeds:` of each profile with `SeedLoader`; the layer model is `profiles/layers.yaml`.
- **Payee master:** bind `registry.party` to `new PayeeConnector(new RegistryPayeeDirectory(ds, partyService, "BANK_ACCOUNT", null))`
  (define the identifier type `BANK_ACCOUNT` in the party registry's identifier catalogue per tenant).
- **Case binding:** done in sangam-packs (`PackStartListener` + `port/ProfileBindings`): it asks `config.profile`
  (`profile.binding`) at start and stores `profileVersion`; install a direct binder with `ProfileBindings.install(...)` if preferred.
- **REST endpoints** (WS-REST): listed in `api/ConfigPorts.java`.

## Dependencies

| Dependency | Licence |
|---|---|
| org.sangam:sangam-core | Apache-2.0 |
| com.fasterxml.jackson.core:jackson-databind (BOM 2.22.3) | Apache-2.0 |
| org.yaml:snakeyaml (Spring Boot BOM, 2.6) | Apache-2.0 (new direct dependency) |
| org.slf4j:slf4j-api | MIT |
| junit-jupiter, h2 (test) | EPL-2.0, MPL-2.0 / EPL-1.0 |

## Tests (fast)

`ProfileDataTest`: loads every shipped profile, resolves all 46 government and department chains with no ERROR finding,
checks donor adoption (HR ← PB), the department layer (GOI.RAILWAYS), units in amounts (`100CR`), the Union 15-digit
head of account against the seeds, and the per-state structures of Karnataka (SH1-DH2-OH3) and Bihar (19 digits + V/C).

## Known gaps

- The master-code and profile JDBC paths are compiled but not yet run against the four databases (testing phase).
- `ProfileResolver.remove` for a locked path inside a keyed list clears the overlay element; a finer per-field
  removal is a follow-up.
- Language packs for form labels (beyond `en` / `hi` in the form JSON) are not generated yet (GA-22).

## International PFM coverage (spike/results/INTERNATIONAL-PFM-REQUIREMENTS.md)

Status: C implemented as a generic capability (with data where needed), D data only, P partial, G gap. Counts over PFM-001..080 (72 ids): C 39, D 2, P 27, G 4; PFM-CH-1..7: 7 D. The same map is in the sangam-config and sangam-ledger READMEs. Country profiles (ISO 3166 alpha-3 ids, so they never collide with Indian state codes): USA, GBR, BRA, KEN, IDN, PHL, BGD under profiles/intl/countries/, on the regime presets PRESIDENTIAL-APPORTIONMENT, WESTMINSTER-SUPPLY, CIVIL-LAW-EMPENHO, DIPA-TREASURY-OFFICE, ALLOTMENT-NCA, indexed by profiles/intl.idx (load it with ProfileLoader.loadIndex next to profiles.idx). KEN, IDN, PHL, BGD are [U] throughout.

| Id | St | Where |
|---|---|---|
| PFM-001 | C | sangam-config: master/ClassificationScheme.java (N segments, widths, patterns, owners); data: profiles/intl/countries/*.yaml#classification |
| PFM-002 | P | data: profiles/intl/seeds/gfsm-2014-economic.csv, intl/INTL-PFM.yaml (GFS scheme): two levels only [U] |
| PFM-003 | P | data: profiles/intl/INTL.yaml, intl/seeds/cofog-divisions.csv: divisions only (groups / classes to load) |
| PFM-004 | C | sangam-config: master/Crosswalk.java (effective-dated, %-split, version to version); data: profiles/in/IN.yaml#crosswalks, intl/INTL-PFM.yaml#crosswalks |
| PFM-005 | C | sangam-config: master/Crosswalk.java#checkCombinations; api/ConfigConnector (classification.validate) |
| PFM-006 | D | data: profiles/intl/INTL-PFM.yaml#classification.attributes.counterpartySector |
| PFM-007 | D | data: profiles/intl/INTL-PFM.yaml#classification.attributes.institutionalSector |
| PFM-008 | C | sangam-config: admin/ConfigAdminService.java, master/ClassificationRegistry.java (correction slips), ClassificationScheme owner |
| PFM-009 | P | programme segment in country schemes; KPIs / targets on programmes not modelled |
| PFM-010 | C | sangam-config: master/Crosswalk.java#checkSplitTags; data: profiles/intl/INTL-PFM.yaml#classification.tags |
| PFM-011 | P | data: profiles/institutionalSector attribute; extrabudgetary-unit registry as tenants (no reporting-obligation tracker) |
| PFM-020 | P | pack gov.ifms.budget-formulation (generic flow); intl.pfm.budget-preparation not built |
| PFM-021 | C | sangam-ledger: LedgerService#commitMultiYear (outer-year ceilings as later-period accounts) |
| PFM-022 | P | core DocumentDelegate outputs; budget-document bundle not built |
| PFM-023 | C | sangam-ledger: ALLOCATE at the top stage with auditRef = legal reference; level of control = stages / line in the profile |
| PFM-024 | P | pack gov.ifms.scheme-appraisal (generic ladder DMN) |
| PFM-025 | C | sangam-ledger: ADJUST; pack gov.ifms.supplementary; data: profiles/intl/countries/BRA.yaml#creditosAdicionais |
| PFM-026 | P | pack gov.ifms.supplementary (contingency route); repayment tracking as ledger data |
| PFM-027 | C | sangam-ledger: LedgerService transfer rules (sameAttributes, blocked flags, DecisionPort); DMN ifms-reapp-rules; data: profiles/intl/countries/GBR.yaml#ledgers.transfer |
| PFM-028 | C | sangam-ledger: LedgerService#allocateFormula; data: profiles/USA apportionment.interimAuthority, GBR estimates.voteOnAccount |
| PFM-029 | C | sangam-ledger: LedgerDefinition allowNegative + one account per limit type; data: profiles/intl/countries/GBR.yaml#estimates |
| PFM-030 | C | sangam-ledger: LedgerDefinition stages / checks (N-stage chain); data: profiles/regime/*.yaml, intl/countries/*.yaml#ledgers |
| PFM-031 | P | sangam-ledger: period / sub dimension for quarterly cotas and Category A; release-above-plan approval not specific |
| PFM-032 | P | sangam-ledger: line / sub dimension for Category B purpose phasing; Category C as later periods |
| PFM-033 | C | sangam-ledger: account attribute conditions + LedgerPorts.DecisionPort (executable conditions) |
| PFM-034 | C | sangam-ledger: COMMIT guarded under the row lock (JdbcLedgerStore) |
| PFM-035 | C | sangam-ledger: COMMIT / UNCOMMIT adjustments, ENCUMBER from commitment; data: profiles/intl/INTL-PFM.yaml COMMITMENT subtypes |
| PFM-036 | C | sangam-ledger: LedgerService#commitMultiYear |
| PFM-037 | C | sangam-ledger: FreezeOrder.java + LedgerControlStore (durable, LDG_FREEZE) |
| PFM-038 | C | sangam-ledger: LedgerService#payablesAgeing (buckets from config); RECEIVABLE arrears |
| PFM-039 | P | sangam-ledger: LedgerPorts.BreachSink event; data: profiles/INTL-PFM chain PFM-CH-7; breach case pack not built |
| PFM-040 | P | sangam-ledger: LedgerStore#find by segment / stage / period; standard reports not built |
| PFM-041 | C | core SoD guard; data: profiles/intl/INTL-PFM.yaml PFM-CH-3 sod pairs |
| PFM-042 | P | release notice date as document field (RELEASE_ORDER.noticeDate); no SLA report |
| PFM-043 | C | sangam-ledger: LedgerService#closePeriod / cancelExpired, availability ANNUAL / MULTI_YEAR / NO_YEAR, carry-forward |
| PFM-044 | C | sangam-ledger: LedgerDefinition mode SHADOW + ExternalLedgerPort + LedgerService#reconcile |
| PFM-045 | C | sangam-ledger: TsaTopology.java (main, ledger-only, real, zero-balance, collection, exception; sweeps; lint) |
| PFM-046 | P | pack gov.ifms.receipts (scroll reconciliation); transfer-deadline monitor not built |
| PFM-047 | P | sangam-ledger: LedgerService#cashForecast (input from commitments); cash-plan process not built |
| PFM-048 | C | sangam-config: catalogue/DocumentTypeCatalogue.ChainTemplate#liableRoles; data: profiles/PFM-CH-3 certifying officer |
| PFM-049 | P | sangam-config: api/PayeeDirectory (party registry) + duplicate check; exclusion-list (Do Not Pay) connector not built |
| PFM-050 | C | rail catalogue (profile paymentRails) + core execution with UNKNOWN_OUTCOME |
| PFM-051 | P | sangam-ledger: RECEIVABLE ledger for advances (dueDate); suspense ageing workflow not built |
| PFM-052 | P | sangam-ledger: closePeriod + config period lock; pack gov.ifms.monthly-accounts |
| PFM-053 | C | sangam-config: catalogue/TwoSidedDocument.java; data: profiles/IG_AGREEMENT (parties BUYER / SELLER) |
| PFM-054 | C | sangam-ledger: accounting mapping filtered by LedgerDefinition bases; data: profiles/accountingBasis (effective-dated) per country |
| PFM-055 | P | sangam-ledger: AccountingEntry (cash / accrual) to AccountingSink; budget-to-accounting reconciliation report not built |
| PFM-056 | G | fiscal-transparency outputs and open data: not built |
| PFM-057 | P | sangam-security read roles + audit export; SAI cross-tenant role not configured |
| PFM-058 | P | connector accounts.render (gov.ifms.monthly-accounts); consolidation-return tracking not built |
| PFM-059 | C | sangam-ledger: AccountKey.sub = funding source / agreement; data: profiles/FONTE scheme (BRA) |
| PFM-060 | C | sangam-ledger: FinancingAgreements.java (agreement-level limits, currency, closing date) |
| PFM-061 | C | sangam-ledger: FinancingAgreements#check (category, % financed, retroactive window, procurement method) |
| PFM-062 | P | sangam-ledger: TsaTopology EXCEPTION account + WITHDRAWAL_APPLICATION / PFM-CH-6 data; replenishment pack not built |
| PFM-063 | P | sangam-config: n-level scopes + core referral; data: profiles/KEN regime.transferFormula; formula DMN not written |
| PFM-064 | P | pack gov.ifms.salary-monthly |
| PFM-065 | G | procurement complaints: not built |
| PFM-066 | C | data: profiles/in/dmn/<GOV>/ifms-procurement-mode.dmn (per government) |
| PFM-067 | P | existing pack gov.assets |
| PFM-068 | P | sangam-ledger: a guarantee register is a LIMIT ledger by configuration; no register definition shipped |
| PFM-069 | G | debt-operations approval chain: not built |
| PFM-070 | C | sangam-ledger: transaction vs functional currency, AccountingEntry.functionalAmount |
| PFM-071 | C | sangam-ledger: LedgerPorts.RatePort (rate types); sangam-config: locale/ExchangeRates.java |
| PFM-072 | C | sangam-ledger: LedgerService#revalue |
| PFM-073 | C | sangam-ledger: account currency (limits in a foreign currency) |
| PFM-074 | C | sangam-config: locale/FiscalCalendar.java (any start month, adjustmentPeriods P13 / P14) |
| PFM-075 | C | sangam-config: locale/LocaleSettings.java (languages, scripts, direction); data: profiles/intl/countries/*.yaml#locale |
| PFM-076 | C | sangam-config: ChainTemplate#externalSteps; data: profiles/intl/countries/KEN.yaml KEN-EXCHEQUER (Controller of Budget) |
| PFM-077 | P | data: profiles/intl/countries/GBR.yaml#accountability.ministerialDirection; no pack |
| PFM-078 | C | sangam-ledger: FiscalRuleEvaluator.java (aggregates + ratio rules + DMN) |
| PFM-079 | C | sangam-config: profile versioning, effective dating, ruleRef on findings |
| PFM-080 | G | PEFA evidence pack: not built |
| PFM-CH-1 | D | Release: template in data: profiles/intl/INTL-PFM.yaml#approvalChains (generic chain mechanics: config ChainTemplate; no intl.pfm pack yet) |
| PFM-CH-2 | D | Commitment: template in data: profiles/intl/INTL-PFM.yaml#approvalChains (generic chain mechanics: config ChainTemplate; no intl.pfm pack yet) |
| PFM-CH-3 | D | Verification and payment: template in data: profiles/intl/INTL-PFM.yaml#approvalChains (generic chain mechanics: config ChainTemplate; no intl.pfm pack yet) |
| PFM-CH-4 | D | Virement: template in data: profiles/intl/INTL-PFM.yaml#approvalChains (generic chain mechanics: config ChainTemplate; no intl.pfm pack yet) |
| PFM-CH-5 | D | Year-end: template in data: profiles/intl/INTL-PFM.yaml#approvalChains (generic chain mechanics: config ChainTemplate; no intl.pfm pack yet) |
| PFM-CH-6 | D | Donor replenishment: template in data: profiles/intl/INTL-PFM.yaml#approvalChains (generic chain mechanics: config ChainTemplate; no intl.pfm pack yet) |
| PFM-CH-7 | D | Breach: template in data: profiles/intl/INTL-PFM.yaml#approvalChains (generic chain mechanics: config ChainTemplate; no intl.pfm pack yet) |
