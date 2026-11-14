# sangam-modeller

Back ends for modeller features. Independent implementations from public behaviour; no bpmn.io add-on or vendor format was copied. The template format is Sangam's own.

**Evidence level:** compiled, with `ModellerTest` run on H2 (an earlier run; not re-run on the current tree).

**Screens (sangam-ui).** `/modeller/tools` (`ModellerToolsPage` in `src/modules/platform.tsx`, roles MODELLER or REVIEWER) has templates, actions (with run), service mocks, the extension catalogue and the landscape view. The package workspace (`src/areas/modeller/Modeller.tsx`) shows the model lease banner, the token walk for a BPMN file, the bulk test report and the FEEL playground. The REST side is `sangam-rest` `ModellerController` and `ServiceMocksController`. These screens have not been run against a live server, and the UI gaps that remain are listed in the sangam-ui README ("Not built").

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
| M-125 (§3.3d L1) | `diff/ModelDiff` | Back end done (tests written, not run) | Semantic diff of BPMN, DMN, CMMN and form JSON versions: elements matched by id (form fields by `id`/`key`), changes classed ELEMENT, FLOW, EXPRESSION or PROPERTY; diagram interchange and modeller-tool attributes ignored; DOCTYPE refused. Consumers: REST `POST /models/diff` (`ModellerController`, both versions empty is 400 MODEL_UNREADABLE) and the offline `sangam-cli diff` with `--fail-on` for PR checks (both written, tests not run). **Gap:** UI compare view (W4-J) |
| M-069 (decisions 159, 204) | `arazzo/ArazzoImporter`, `arazzo/ArazzoYaml` | Importer coded (tests written, not run) | Arazzo JSON or YAML (YAML through SnakeYAML safe composition only: one document, no anchors, aliases, merge keys or custom tags, YAML 1.1 forms such as `yes` or `0755` refused, numbers exact; a supplied OpenAPI document may be YAML too; `arazzo` 1.0.x or 1.1.x; no conformance claim, the 1.1.0 changes were not available offline, so every unknown field is an ERROR) to a BPMN draft: one process per workflow; operation steps become external service tasks on topic `sangam.operation` with `sangam:operationRef="contractId#operationId"` (unpinned, the package deploy pins it) and the Arazzo request, success criteria and outputs verbatim in static `operaton:properties`; workflow steps become call activities; one unconditional success `end`/`goto` becomes the sequence flow. Retry, conditional or multiple actions, failure `goto`, external workflows: ERROR (no draft). `operationPath` resolves only against a supplied OpenAPI document; nothing is fetched. **Gap:** runtime step (W4-H), REST import endpoint and UI |
| M-136 (decision 151) | `replay/ReplayRun`, `replay/ReplayFixtures`, `replay/ReplayComparison` | PARTIAL (tests written, not run) | Deterministic replay of a captured fixture (`sangam.replay-fixture/1`, JSON Schema `replay-fixture-1.schema.json`): recorded clock reads, timers, messages, external task results, random values, generated ids and lookups are served back in EXACT or UPGRADE mode; the observed end state, transitions, decisions, deadlines and side-effect intents are compared with named normalisation rules. DECIMAL values are the canonical `BigDecimal.toString()` text (at most 1000 characters); local date-times carry a named zone; credential-like configuration keys are refused; `activityInstanceId` on an event is kept for diagnostics only and never compared. End-to-end verifier `replay/ReplayVerifier` (decision 151): capture (through `ReplayCapture`, recorded with `FixtureRecorder`; real data refused unless allowed), fixture (written, read back, written again byte for byte, SHA-256 recorded; map keys sorted so the document is canonical), replay (at least two runs, all equivalent) and the changed-input proof (each declared input or rule change must differ in the named areas, and the original must still replay equivalent); an unsupported capture is UNSUPPORTED, never VERIFIED. Test `ReplayVerifierTest`. The engine-side capture and driver are not in this module. **Gap:** Operaton-backed `ReplayCapture` and `ReplayDriver` and an executed end-to-end run including Oracle and SQL Server (W4-E), REST and UI |
| M-127 (R45, T43) | `cmmn/CmmnToBpmnConverter` | Back end done (tests written, not run) | Bounded CMMN 1.1 to BPMN conversion: supported subset listed in the Javadoc; any unsupported construct is an ERROR and gives no BPMN (manual handling or run-off on the CMMN engine); a converted draft is REVIEW_REQUIRED, has no diagram, and comes with a plan item to activity state-migration mapping. The EC-21 assessment (`POST /packages/assessment`, sangam-rest `CmmnConversionRows`) adds a `cmmn:toBpmn` verdict row (MAPPED or REJECTED) and one row per ERROR or WARNING (tests written, not run). **Gap:** a REST call that returns the BPMN draft and mapping, and UI |
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

`operaton-bpmn-model` (Apache-2.0) and Jackson (Apache-2.0). H2 for tests only. The diff and the CMMN converter use only the JDK XML parser and Jackson.
