# sangam-ops

Operations back ends. Independent implementations from public behaviour and the published Camunda 7 / Operaton table names.

**Evidence level:** compiled, with `OpsTest` run on H2, using hand-made tables that hold only the columns these classes read. Not run against real `ACT_*` schemas or a Tier-1 database.

## Coverage

| NG | File | Status | Stated limits |
|---|---|---|---|
| NG-24 | `metrics/UsageMetrics` | Back end done | Never used for licensing or gating. Needs a daily schedule |
| NG-25 | `migrate/LegacyHistoryMigrator` | Back end done | Copies rows as JSON into `OPS_MIG_ROW` with source ids, resumable, dry run, reconciliation; never copies password hashes or binary columns. **Gap:** no mapping into the audit chain or product views, because that decision is open. The recheck says to decide first whether anyone needs it. Column case in `NEVER_COPY` relies on lower-casing labels |
| NG-29 | `search/SearchIndexMaintenance` | Back end done | Rebuild, progress, alias switch, drift check on a relational table. R65 has no external index, so this is the index |
| NG-30 | `search/GlobalSearch` | Back end done | Predicate-first, capped per type. The real `AuthorizationPolicy` predicate cannot run on H2, so tests used an equivalent predicate. Substring scan, so slow on large tables. **Gap:** `Ctrl+K` dialog |
| NG-31 | `outlier/OutlierAnalysis` | Back end done | IQR threshold, Fisher exact test, minimum sample. No multiple-comparison correction. `readSamples` is not covered by a test. **Gap:** screen |
| NG-34 | `vars/ScopedVariables` | Back end done | Merged cluster and tenant scopes. Expressions need the bean registered. **Gap:** edit UI |
| NG-35 | `listener/GlobalTaskListeners` | Back end done | Config validated by test; attachment to a running engine **not** run |
| NG-36 | `retention/HierarchyRetention` | Back end done | A tree is one unit. Needs a `Purger` bound to the engine's history service and a legal-hold check. It does not replace the engine's history cleanup job, so the two must not both purge the same definitions |
| NG-38 | `secrets/SecretAdmin` | Back end done | Metadata listing, bulk import, encrypted bundle. Tests used a fake encryptor. **Gap:** UI |
| NG-41 | `sequence/SequenceAdmin` | Back end done | Forward-only. Tested on a sample table. **Gap:** admin screen |

## Integration notes

- **Tables:** `OpsSchema` (`OPS_MIG_CHECKPOINT`, `OPS_MIG_ROW`, `OPS_USAGE_DAILY`, `OPS_SEARCH_DOC`, `OPS_SEARCH_ALIAS`, `OPS_INDEX_JOB`, `OPS_VARIABLE`, `OPS_SECRET`) must be added to the persistence generator.
- **Server wiring:**
  - `GlobalTaskListeners.parseListener()` in `customPostBPMNParseListeners`, with rules from configuration.
  - `ScopedVariables` as the engine bean `sangamVars`.
  - `SecretAdmin.resolver` as the vault-backed `SecretResolver`, with the real `FieldEncryptor`.
  - `SequenceAdmin` tables: `RES_BOOKING_SEQ` and `CFG_SERIES`. Check `CFG_SERIES` column names at wiring time.
  - Schedules: `UsageMetrics.rollup`, `HierarchyRetention`, `SearchIndexMaintenance.drift`.
  - `GlobalSearch.engineSources`: resource type names must equal those the policy compiler writes.
- **REST endpoints needed:** search, usage, outliers, variables, secrets, sequences, index jobs, retention plan and execute, migration status. Every one needs R19 checks; these classes trust the caller's principal.
- **Screens needed:** `Ctrl+K` search, usage dashboard, outlier view stating "correlation, not cause", variable editor, secret manager (no value display), sequence admin, index maintenance, retention plan preview, migration reconciliation.

## Third-party dependencies

`operaton-engine` (Apache-2.0, optional, provided at runtime) and Jackson (Apache-2.0). H2 for tests only.
