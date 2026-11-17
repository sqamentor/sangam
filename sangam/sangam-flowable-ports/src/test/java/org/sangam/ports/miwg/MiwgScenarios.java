// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.miwg;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which MIWG models are run, and why the others are not. MIWG is the OMG model-interchange suite: its models test
 * that a tool reads and writes BPMN without loss, and most of them are descriptive (isExecutable="false") or bind
 * their work and conditions to the authoring tool. A model is "not applicable" to the RUN stage only for a reason
 * stated here; IMPORT, SCHEMA and DEPLOY apply to every model.
 */
final class MiwgScenarios {

    private MiwgScenarios() {
    }

    static final String DESCRIPTIVE = "Descriptive model: every process is isExecutable=\"false\" (interchange only).";

    /** model -> what the run scenario asserts. */
    static final Map<String, String> RUNS = new LinkedHashMap<>();
    /** model -> why it is not run. */
    static final Map<String, String> NOT_APPLICABLE = new LinkedHashMap<>();

    static {
        RUNS.put("C.1.0", "Invoice: message start, reject, clarify, approve, bank transfer, archive (external) to 'Invoice processed'.");
        RUNS.put("C.9.1", "Document request: external e-mail, document message to 'Document received'; daily non-interrupting timer sends a reminder.");

        for (String m : new String[] {"A.1.0", "A.2.0", "A.2.1", "A.3.0", "A.4.0", "A.4.1", "B.1.0", "B.2.0", "C.2.0", "C.8.0", "C.10.0"}) {
            NOT_APPLICABLE.put(m, DESCRIPTIVE);
        }
        NOT_APPLICABLE.put("C.1.1", "Conditions are XPath (bpmn:getDataObject) over data objects; Sangam evaluates JUEL/FEEL, not XPath (see XPath note).");
        NOT_APPLICABLE.put("C.3.0", "Gateway conditions are free text ('Service Level == 'Premium'') with XPath declared; not an evaluable expression.");
        NOT_APPLICABLE.put("C.4.0", "Process has no isExecutable attribute and only manual/WebService work with XPath conditions; no binding in the model.");
        NOT_APPLICABLE.put("C.5.0", "No isExecutable attribute; called process and data states only, no executable binding.");
        NOT_APPLICABLE.put("C.6.0", "No isExecutable attribute; WebService tasks without WSDL operations, timeDate without a date.");
        NOT_APPLICABLE.put("C.7.0", "No isExecutable attribute; FEEL item definitions of the Trisotech type language, WebService work without operations.");
        NOT_APPLICABLE.put("C.8.1", "Bound to KnowProcess implementations (kp:http, kp:mail, kp:dmn) and FEEL conditions over DMN item definitions of the Trisotech type language.");
        NOT_APPLICABLE.put("C.9.0", "Conditions use Zeebe FEEL syntax ('= approved'); the business-rule task needs a decision not in the model.");
        NOT_APPLICABLE.put("C.9.2", "Conditions use Zeebe FEEL syntax ('=fraud').");
    }
}
