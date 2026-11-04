// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.bpmn;

import org.sangam.compliance.SangamCompliance;
import org.sangam.compliance.rights.DataSubjectRightsService;
import org.sangam.compliance.rights.NominationService;
import org.sangam.compliance.support.Sql;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Engine-neutral service-task steps of the compliance BPMN packs. A step reads process variables and returns the
 * variables to set. The Operaton bridge is {@link ComplianceStepDelegate}; an external-task worker can call
 * {@link #run} with the same names.
 */
public final class ComplianceSteps {

    private final SangamCompliance cmp;
    private final Map<String, BiFunction<String, Map<String, Object>, Map<String, Object>>> steps = new LinkedHashMap<>();

    public ComplianceSteps(SangamCompliance cmp) {
        this.cmp = cmp;
        DataSubjectRightsService r = cmp.rights;
        steps.put("rights.start", (t, v) -> tx(c -> {
            r.markInProgress(c, t, s(v, "requestId"), actor(v));
            return Map.of();
        }));
        steps.put("rights.accessSummary", (t, v) -> tx(c -> {
            Map<String, Object> summary = r.buildAccessSummary(c, t, s(v, "requestId"), actor(v));
            return Map.of("accessSummaryHash", Sql.evidenceHash(summary));
        }));
        steps.put("rights.closeAccess", (t, v) -> tx(c -> {
            r.close(c, t, s(v, "requestId"), DataSubjectRightsService.Status.RESOLVED, "ACCESS SUMMARY PROVIDED " + v.get("accessSummaryHash"), actor(v));
            return Map.of();
        }));
        steps.put("rights.applyCorrection", (t, v) -> tx(c -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> corr = v.get("corrections") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            return Map.of("fieldsChanged", r.applyCorrection(c, t, s(v, "requestId"), corr, Boolean.TRUE.equals(v.get("correctionAccepted")),
                    (String) v.get("decisionReason"), actor(v)).size());
        }));
        steps.put("rights.erase", (t, v) -> {
            DataSubjectRightsService.ErasureReport rep = r.executeErasure(t, s(v, "requestId"), actor(v));
            return Map.of("rootsErased", rep.erased().size(), "rootsRetained", rep.retained().size());
        });
        steps.put("rights.resolveGrievance", (t, v) -> tx(c -> {
            r.resolveGrievance(c, t, s(v, "requestId"), s(v, "grievanceResponse"), actor(v));
            return Map.of();
        }));
        steps.put("rights.reject", (t, v) -> tx(c -> {
            r.reject(c, t, s(v, "requestId"), s(v, "decisionReason"), actor(v));
            return Map.of();
        }));
        steps.put("rights.escalate", (t, v) -> tx(c -> {
            r.escalate(c, t, s(v, "requestId"), "system:sla");
            return Map.of("escalated", true);
        }));
        steps.put("nomination.register", (t, v) -> tx(c -> {
            DataSubjectRightsService.RightsRequest req = r.get(c, t, s(v, "requestId"));
            NominationService.Nomination n = cmp.nominations.nominate(c, t, req.principalRef(), s(v, "nomineeRef"),
                    (String) v.get("nomineeName"), (String) v.get("relationship"), (String) v.get("nomineeContactRef"), actor(v));
            r.close(c, t, req.requestId(), DataSubjectRightsService.Status.RESOLVED, "NOMINATION " + n.nominationId(), actor(v));
            return Map.of("nominationId", n.nominationId());
        }));
        steps.put("breach.notifyAuthority", (t, v) -> tx(c -> Map.of("authorityRef", cmp.breaches.notifyAuthority(c, t, s(v, "breachId"), actor(v)))));
        steps.put("breach.notifySubjects", (t, v) -> tx(c -> Map.of("subjectsNotified",
                cmp.breaches.notifySubjects(c, t, s(v, "breachId"), strings(v.get("subjectNotice")), actor(v)))));
        steps.put("breach.submitReport", (t, v) -> tx(c -> Map.of("reportRef",
                cmp.breaches.submitDetailedReport(c, t, s(v, "breachId"), strings(v.get("detailedReport")), actor(v)))));
        steps.put("breach.overdue", (t, v) -> tx(c -> Map.of("overdue", !cmp.breaches.overdue(c, t).isEmpty())));
        steps.put("breach.close", (t, v) -> tx(c -> {
            cmp.breaches.close(c, t, s(v, "breachId"), actor(v));
            return Map.of();
        }));
        steps.put("incident.submit", (t, v) -> tx(c -> Map.of("reportRefs",
                String.join(",", cmp.incidents.submit(c, t, s(v, "incidentId"), (String) v.get("actionsTaken"), actor(v))))));
        steps.put("incident.overdue", (t, v) -> tx(c -> Map.of("overdue", !cmp.incidents.overdue(c, t).isEmpty())));
        steps.put("incident.followUp", (t, v) -> tx(c -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> d = v.get("followUpDetails") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            cmp.incidents.followUp(c, t, s(v, "incidentId"), d, actor(v));
            return Map.of();
        }));
        steps.put("incident.close", (t, v) -> tx(c -> {
            cmp.incidents.close(c, t, s(v, "incidentId"), actor(v));
            return Map.of();
        }));
    }

    public Map<String, Object> run(String step, String tenantId, Map<String, Object> variables) {
        BiFunction<String, Map<String, Object>, Map<String, Object>> f = steps.get(step);
        if (f == null) {
            throw new IllegalArgumentException("unknown compliance step " + step);
        }
        return f.apply(tenantId, variables);
    }

    public java.util.Set<String> names() {
        return steps.keySet();
    }

    private Map<String, Object> tx(java.util.function.Function<java.sql.Connection, Map<String, Object>> work) {
        return cmp.tx.inTransaction(work);
    }

    private static String s(Map<String, Object> v, String name) {
        Object o = v.get(name);
        if (o == null) {
            throw new IllegalArgumentException("process variable " + name + " is required");
        }
        return String.valueOf(o);
    }

    private static String actor(Map<String, Object> v) {
        Object a = v.get("sangamActor");
        return a == null ? "system:bpmn" : String.valueOf(a);
    }

    private static Map<String, String> strings(Object o) {
        return Sql.strings(o);
    }
}
