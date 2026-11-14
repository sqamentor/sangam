// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.cmmn;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The result of one bounded CMMN to BPMN conversion (R45 / E16, T43): the BPMN draft, the diagnostics report and the
 * state-migration mapping. The status is never "done": a converted model always needs a human review before it is
 * deployed, and a model with an unsupported construct is not converted at all (manual handling, or run-off of the
 * existing cases on the CMMN engine, which stays available).
 *
 * @param resource    the CMMN resource name
 * @param status      {@link Status#REVIEW_REQUIRED} with a BPMN draft, or {@link Status#MANUAL} without one
 * @param bpmn        the BPMN 2.0 draft (UTF-8, no diagram interchange), null when {@link Status#MANUAL}
 * @param diagnostics every finding, in document order: errors (unsupported), warnings (converted, behaviour to
 *                    review) and notes
 * @param mapping     plan item to BPMN activity, for migrating the state of a running case by hand (empty when
 *                    {@link Status#MANUAL})
 */
public record CmmnConversion(String resource, Status status, byte[] bpmn, List<Diagnostic> diagnostics,
                             List<StateMapping> mapping) {

    /** Outcome of a conversion. */
    public enum Status {
        /** Every construct is in the supported subset: a BPMN draft exists and must be reviewed before deployment. */
        REVIEW_REQUIRED,
        /** At least one construct is unsupported: no BPMN; handle the model manually or let its cases run off. */
        MANUAL
    }

    /** How serious a finding is. */
    public enum Severity { ERROR, WARNING, INFO }

    /**
     * One finding.
     *
     * @param severity  ERROR: unsupported, nothing converted; WARNING: converted, but the behaviour differs or was
     *                  dropped and must be reviewed; INFO: a note
     * @param code      stable code ({@code CMMN-UNSUPPORTED-REPETITION}, ...)
     * @param elementId the CMMN element (empty for the whole model)
     * @param message   what was found and what to do
     */
    public record Diagnostic(Severity severity, String code, String elementId, String message) {
        public Diagnostic {
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(code, "code");
            elementId = elementId == null ? "" : elementId;
            message = message == null ? "" : message;
        }
    }

    /**
     * One row of the state-migration mapping.
     *
     * @param caseId          the CMMN case
     * @param planItemId      the plan item (its id in a running case's activity tree)
     * @param cmmnType        humanTask, processTask, decisionTask, task, stage or milestone
     * @param processId       the BPMN process converted from the case
     * @param activityId      the BPMN element (same id as the plan item)
     * @param bpmnType        userTask, callActivity, businessRuleTask, subProcess or intermediateThrowEvent
     * @param waitState       true when an ACTIVE plan item of this kind maps to a BPMN wait state a migrated instance
     *                        can start at (user tasks, call activities, sub-processes); false for elements a token
     *                        only passes (milestones, decisions)
     */
    public record StateMapping(String caseId, String planItemId, String cmmnType, String processId, String activityId,
                               String bpmnType, boolean waitState) {
    }

    public CmmnConversion {
        Objects.requireNonNull(status, "status");
        diagnostics = List.copyOf(diagnostics);
        mapping = List.copyOf(mapping);
        if (status == Status.MANUAL && bpmn != null) {
            throw new IllegalArgumentException("a MANUAL conversion has no BPMN");
        }
        if (status == Status.REVIEW_REQUIRED && bpmn == null) {
            throw new IllegalArgumentException("a REVIEW_REQUIRED conversion has a BPMN draft");
        }
        bpmn = bpmn == null ? null : bpmn.clone();
    }

    @Override
    public byte[] bpmn() {
        return bpmn == null ? null : bpmn.clone();
    }

    public boolean converted() {
        return status == Status.REVIEW_REQUIRED;
    }

    public List<Diagnostic> errors() {
        return diagnostics.stream().filter(d -> d.severity() == Severity.ERROR).toList();
    }

    /** The diagnostics report (T43) as a JSON-ready map; the BPMN is included as text when converted. */
    public Map<String, Object> toJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("resource", resource);
        out.put("status", status.name());
        List<Map<String, Object>> d = new ArrayList<>();
        for (Diagnostic x : diagnostics) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("severity", x.severity().name());
            m.put("code", x.code());
            m.put("elementId", x.elementId());
            m.put("message", x.message());
            d.add(m);
        }
        out.put("diagnostics", d);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (StateMapping s : mapping) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("caseId", s.caseId());
            m.put("planItemId", s.planItemId());
            m.put("cmmnType", s.cmmnType());
            m.put("processId", s.processId());
            m.put("activityId", s.activityId());
            m.put("bpmnType", s.bpmnType());
            m.put("waitState", s.waitState());
            rows.add(m);
        }
        out.put("mapping", rows);
        out.put("bpmn", bpmn == null ? null : new String(bpmn, StandardCharsets.UTF_8));
        return out;
    }
}
