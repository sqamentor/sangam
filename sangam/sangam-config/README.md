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
| `catalogue/NumberSeries.java` | Number series (token per office per day, voucher per month ...) | IFC-095, IFS-048 |
| `locale/NumberGrouping.java`, `MoneyFormat.java`, `Money.java` | ISO 4217 money; grouping 3/3 or 3/2; amount units from configuration | ADR-D-20 locale |
| `locale/FiscalCalendar.java` | Any start month / day / zone, labels, periods, quarters, closing instants | IFC-070, INTL gap 13 |
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
- **Start-up:** `ConfigAdminService.importActive(ProfileLoader.loadIndex(cl, "profiles/profiles.idx"), "release")`; load the
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
