import os
import sys

os.chdir(sys.argv[1])


def edit(p, o, n):
    s = open(p, encoding='utf-8').read()
    assert s.count(o) == 1, p
    s = s.replace(o, n)
    with open(p, 'w', encoding='utf-8', newline='') as f:
        f.write(s)


edit('sangam-ops/README.md',
     "Built on a synthetic corpus only, with no production-readiness claim: Flowable table and column names are not "
     "verified against a real Flowable schema, `json` and `longString` variables are rejected, and task listeners during "
     "apply are an open question. Tests `FlowableMigrationPipelineH2Test`, `OperatonEngineStateWriterH2Test`, "
     "`StateClassifierTest`, `FlowableRestCompatibilityTest`, sangam-server `FlowableMigrationStoreIT`. **Gap:** REST "
     "`/ops/flowable-migrations` (W4-M) and UI |",
     "Built on a synthetic corpus only, with no production-readiness claim. Checked against the Flowable source (local "
     "reference copy, 8.1.0-SNAPSHOT, file and line in `FlowableTables`, `FlowableModelListeners`, `FlowableInventory`, "
     "`StateClassifier`): every table and column name read exists in the H2 create scripts; a timer row without "
     "`ELEMENT_ID_` (written before 6.5.0.1) takes its activity from `HANDLER_CFG_` as Flowable does; only an "
     "`async-continuation` job is an asyncBefore wait, any other handler is REJECT `UNSUPPORTED_JOB_HANDLER`; "
     "`longString` and oversized `json` kept as bytes are REJECT `LONG_TEXT_VARIABLE`, `json` in text is REJECT "
     "`JSON_VARIABLE` (no JSON type in the target); the deployed source model is read from `ACT_GE_BYTEARRAY`, and the "
     "delete or all task listeners and process event listeners that ending an instance on Flowable fires are WARN "
     "`SOURCE_DELETE_LISTENER` and `SOURCE_EVENT_LISTENER` (`SOURCE_MODEL_UNREAD` when the model cannot be read). Tests "
     "`FlowableMigrationPipelineH2Test`, `OperatonEngineStateWriterH2Test`, `StateClassifierTest`, "
     "`FlowableSourceEvidenceTest`, `FlowableRestCompatibilityTest`, sangam-server `FlowableMigrationStoreIT`. **Gap:** "
     "only the H2 scripts of one Flowable version were read (no Oracle, SQL Server or older release, no live Flowable "
     "database); engine-wide listeners, an `EndProcessInstanceInterceptor` and execution end listeners on cancel are not "
     "visible from the model (stated in the cut-over report); whether a source delete listener should REJECT instead of "
     "WARN waits for the owner; REST `/ops/flowable-migrations` (W4-M) and UI |")

edit('sangam-modeller/README.md',
     "The engine-side capture and driver are not in this module. **Gap:** capture side (W4-E), end-to-end verifier, "
     "REST and UI |",
     "End-to-end verifier `replay/ReplayVerifier` (decision 151): capture (through `ReplayCapture`, recorded with "
     "`FixtureRecorder`; real data refused unless allowed), fixture (written, read back, written again byte for byte, "
     "SHA-256 recorded; map keys sorted so the document is canonical), replay (at least two runs, all equivalent) and "
     "the changed-input proof (each declared input or rule change must differ in the named areas, and the original must "
     "still replay equivalent); an unsupported capture is UNSUPPORTED, never VERIFIED. Test `ReplayVerifierTest`. The "
     "engine-side capture and driver are not in this module. **Gap:** Operaton-backed `ReplayCapture` and "
     "`ReplayDriver` and an executed end-to-end run including Oracle and SQL Server (W4-E), REST and UI |")
print("ok")
