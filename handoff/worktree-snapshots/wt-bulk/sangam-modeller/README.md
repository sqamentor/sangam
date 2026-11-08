# sangam-modeller

Back ends for modeller features. Independent implementations from public behaviour; no bpmn.io add-on or vendor format was copied. The template format is Sangam's own.

**Evidence level:** compiled, with `ModellerTest` run on H2. No screen exists for any of this yet.

## Coverage

| NG | File | Status | Stated limits |
|---|---|---|---|
| NG-17 | `catalogue/ExtensionCatalogue` | Partial | Signed index, licence, checksum and compatibility checks; an offline file works. The vendor feature remains unverified **[U]**. **Gap:** no hosted catalogue, publishing flow or install step |
| NG-18 | `simulation/TokenWalk` | Back end done | Pure model walk with gateway choices. Limits are in the Javadoc: boundary events, event sub-processes and compensation are not walked. **Gap:** diagram animation in the UI |
| NG-19 | `template/ElementTemplates` | Back end done | Element, palette and form-component kinds; versioned; apply and "save as template". **Gap:** properties panel, palette and form editor |
| NG-20 | `landscape/ProcessLandscape` | Back end done | Missing, dynamic and cyclic targets. The caller filters by R19 first. **Gap:** graph screen |
| NG-21 | `lease/EditLeases` | Back end done | Lease, heartbeat, takeover, fence. The package save must call `checkFence`. **Gap:** read-only mode and takeover dialog |
| NG-22 | `mock/ServiceMocks` | Back end done | Refused on production. Rules must be loaded from test models. **Gap:** mock editor |
| NG-26 | `auth/TokenExchange` | Back end done | RFC 8693 tested against a loopback endpoint only. It sits here because it has no other module yet, and it needs a call site in the connector auth layer |
| NG-28 | `action/ActionModels` | Back end done | Server-side permission, versioning, idempotent invocation. Vendor semantics were not fully read. `Dispatcher` binding not written. **Gap:** action menu in the UI |
| NG-32 | `test/BulkModelTests` | Back end done | Bulk run, stored report, sealed export bundle. `Executor` not bound, because the REST test endpoint is proposed only. **Gap:** report screen |

## Integration notes

- **Tables:** `ModellerSchema` (`MOD_EDIT_LEASE`, `MOD_TAKEOVER_DRAFT`, `MOD_TEMPLATE`, `MOD_ACTION`, `MOD_ACTION_RUN`, `MOD_TEST_RUN`) must be added to the persistence generator.
- **Server wiring:**
  - `EditLeases.checkFence` inside the package save transaction.
  - `ServiceMocks.wrap` around connectors on non-production profiles; `productionProfile` must come from the same check as R80.
  - `ActionModels.Dispatcher` and `Permission`.
  - `BulkModelTests.Executor`, including the `sangam-ai` judge results as `NOT_JUDGED`, PASSED or FAILED with `deterministic=false`.
  - `TokenExchange` in the connector auth layer, with the acting user added to the R42 event.
- **REST endpoints needed:** `GET /landscape`, `POST /models/token-walk`, `/models/{file}/lease` (acquire, heartbeat, release, takeover), `/templates`, `/actions`, `POST /packages/{id}/tests/run`, test reports and export, `/catalogue`. `tools/sangam-cli` expects `POST /packages/{id}/tests/run` to answer with `passed` and `failed`.
- **Screens needed:** landscape graph, token animation with gateway choice, lease banner with takeover, template picker and "save as template", custom palette, composite form components, mock editor with global and per-service toggles, action menu and action admin, bulk test report with non-deterministic results apart, catalogue browser.

## Third-party dependencies

`operaton-bpmn-model` (Apache-2.0) and Jackson (Apache-2.0). H2 for tests only.
