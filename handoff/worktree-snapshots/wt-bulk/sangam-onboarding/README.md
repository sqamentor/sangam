<!-- SPDX-License-Identifier: Apache-2.0 -->
# sangam-onboarding

Plug-and-play tenant onboarding by configuration alone (SANGAM.md X.0e, ADR-D-20 to 24). Base package `org.sangam.onboarding`. No class names a country, department or sector. Test plan: `../ACCEPTANCE-PLUG-AND-PLAY.md`.

| Package | Purpose |
|---|---|
| `api` | Request, run, step, problem and dry-run report records |
| `deps` | `SemVer`, `VersionRange`, `PackRequirements`, `PackRequirementsCatalog` (descriptor, `META-INF/sangam/pack-requires.json`, manifest `requires` / `conflicts` / `upgrades`), `PackDependencyResolver` (wraps `PackRegistry`), `PlatformInfo` |
| `profile` | `ProfilePlanner`: documents from the request; preview resolution of the planned state |
| `readiness` | `ReadinessChecker`, `DmnOverrideValidator`: the go-live checklist |
| `bundle` | `BundleService`, `BundleSigner` (Ed25519), `TreeDiff`: signed export, import under a new scope, diff |
| `flow` | `OnboardingService`, the BPMN process `sangam-onboarding`, `OnboardingStepDelegate`, `OnboardingStore` (`ONB_RUN`) |
| `port` | `OnboardingPorts`: bound to the modules in `sangam-server` (`bridge/OnboardingBridges`, `config/OnboardingWiring`) |

Flow: TENANT, PLACEMENT, PROFILE, PACKS, CONNECTORS, SECURITY, SEEDS, COMPLIANCE, READINESS, then the checker, then GO_LIVE. Each step is an async service task, idempotent and skipped when done for the request revision. Configuration problems go to the maker's fix task; infrastructure failures become job retries, then `FAILED`, then `resume`.

## Integration notes

- `ONB_RUN` is in `sangam-persistence` (`sangam-0002-onboarding.sql`) and in `SangamSchema`.
- Connector bindings are per tenant (`PackConnectorBindings.bindForTenant`); other tenants keep the platform binding or the simulator fallback.
- The SECURITY step installs the enabled packs' seed roles, offices and seats. Their values are the packs' synthetic pilot data until the tenant replaces them.
- Calendars in `seeds.calendars` are saved through the resources `CalendarService` (`RES_CALENDAR`).
- Tenant moves: `CellTenantMover` copies `ONB_RUN` through its `tenant_id` column and the tenant's `CFG_*` rows by scope, plus any chain profile the target lacks. A move is refused (409 `ONBOARDING_IN_PROGRESS`) while a run is open. Activation on the target restores bindings, pack selection and compliance jurisdiction.
- Go-live evidence: `TenantAdminService` resolves effective jurisdictions in one place (stored config, compliance settings, planned in a dry run); items come from the jurisdiction registry, plugin packs included.

## Dependencies

Existing only: Jackson (with jsr310), Operaton, sangam-core, sangam-config, sangam-packs (all Apache-2.0), slf4j (MIT).
