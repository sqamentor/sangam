// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.sangam.rest.port.ApiProblem;

/** SL-77: required fields of a task's Sangam form, read from the task's deployment and enforced on completion. */
class TaskFormRequirementsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String TRIAGE = """
            {"key":"sec-health/triage","fields":[
              {"id":"triageLevel","type":"select","required":true,"label":{"en":"Triage level"}},
              {"id":"clinicalNotes","type":"text","required":false},
              {"key":"wardType","type":"select","required":true},
              {"id":"notes2","type":"text"}]}""";
    private static final String MAINT = """
            {"key":"assets/maintenance","fields":[{"id":"condition","required":false}],
             "decision":{"variable":"conditionDecision","required":true,"options":[{"value":"fit"}]}}""";

    @Test
    void formKeyConvention() {
        assertEquals("sec-health/triage", TaskFormRequirements.formKeyOf("sangam:sec-health/triage"));
        assertEquals("sec-health/triage", TaskFormRequirements.formKeyOf("sangam:sec-health/triage:3"));
        assertEquals("a:b", TaskFormRequirements.formKeyOf("sangam:a:b"));
        assertNull(TaskFormRequirements.formKeyOf("embedded:app:forms/x.html"));
        assertNull(TaskFormRequirements.formKeyOf("operaton-forms:deployment:x.form"));
        assertNull(TaskFormRequirements.formKeyOf("sangam:"));
        assertNull(TaskFormRequirements.formKeyOf(null));
    }

    @Test
    void requiredFieldsAndRequiredDecision() throws Exception {
        assertEquals(List.of("triageLevel", "wardType"), TaskFormRequirements.requiredOf(JSON.readTree(TRIAGE)));
        assertEquals(List.of("conditionDecision"), TaskFormRequirements.requiredOf(JSON.readTree(MAINT)));
        assertEquals(List.of(), TaskFormRequirements.requiredOf(JSON.readTree("{\"key\":\"k\"}")));
    }

    @Test
    void absentValues() {
        Map<String, Object> v = new HashMap<>();
        v.put("blank", "  ");
        v.put("nul", null);
        v.put("emptyList", List.of());
        v.put("emptyMap", Map.of());
        v.put("emptyArray", new String[0]);
        v.put("no", Boolean.FALSE);
        v.put("zero", 0);
        v.put("text", "x");
        List<String> required = List.of("blank", "nul", "emptyList", "emptyMap", "emptyArray", "absent", "no", "zero",
                "text");
        assertEquals(List.of("blank", "nul", "emptyList", "emptyMap", "emptyArray", "absent"),
                TaskFormRequirements.missing(required, v));
        assertEquals(List.of("a"), TaskFormRequirements.missing(List.of("a"), null));
    }

    @Test
    void problemIs422NamingEveryField() {
        ApiProblem p = TaskFormRequirements.problem("sangam:k", List.of("a", "b"));
        assertEquals(422, p.status());
        assertEquals("REQUIRED_FIELDS_MISSING", p.code());
        assertTrue(p.detail().contains("a, b"), p.detail());
        assertEquals(List.of("a", "b"), p.extensions().get("fields"));
        assertEquals("sangam:k", p.extensions().get("formKey"));
    }

    @Test
    void readsTheFormFromTheTasksDeploymentAndCachesIt() {
        Map<String, String> res = new LinkedHashMap<>();
        res.put("packs/sec-health/sangam-sec-health.bpmn", "<definitions/>");
        res.put("packs/sec-health/forms/triage.form.json", TRIAGE);
        res.put("packs/assets/forms/maintenance.form.json", MAINT);
        AtomicInteger listings = new AtomicInteger();
        TaskFormRequirements r = new TaskFormRequirements(repository(Map.of("dep1", res), listings));
        assertEquals(List.of("triageLevel", "wardType"), r.requiredForTask("pd1", null, "sangam:sec-health/triage"));
        assertEquals(List.of("triageLevel", "wardType"), r.requiredForTask("pd1", null, "sangam:sec-health/triage:2"));
        assertEquals(List.of("conditionDecision"), r.requiredForTask("pd1", null, "sangam:assets/maintenance"));
        assertEquals(List.of(), r.requiredForTask("pd1", null, "sangam:unknown/form"));
        assertEquals(List.of(), r.requiredForTask("pd1", null, "embedded:app:x.html"));
        assertEquals(List.of(), r.requiredForTask(null, null, "sangam:sec-health/triage"), "standalone task");
        assertEquals(1, listings.get(), "a deployment is indexed once");
    }

    @Test
    void unreadableOrAmbiguousFormsFailClosed() {
        Map<String, String> bad = Map.of("forms/x.form.json", "{not json");
        TaskFormRequirements r1 = new TaskFormRequirements(repository(Map.of("dep1", bad), new AtomicInteger()));
        ApiProblem p1 = assertThrows(ApiProblem.class, () -> r1.requiredForTask("pd1", null, "sangam:any"));
        assertEquals("FORM_INVALID", p1.code());

        Map<String, String> twice = new LinkedHashMap<>();
        twice.put("a/forms/triage.form.json", TRIAGE);
        twice.put("b/forms/triage.form.json", TRIAGE);
        TaskFormRequirements r2 = new TaskFormRequirements(repository(Map.of("dep1", twice), new AtomicInteger()));
        ApiProblem p2 = assertThrows(ApiProblem.class, () -> r2.requiredForTask("pd1", null, "sangam:sec-health/triage"));
        assertEquals("FORM_AMBIGUOUS", p2.code());
        assertFalse(p2.detail().isBlank());
    }

    /**
     * PACKFORM-FIELD-KEY: the shipped compliance forms, deployed as the compliance pack deploys them, name their
     * required fields by {@code id}, and completing such a task without them is refused. Covers the forms whose fields
     * were named {@code key} before the fix (grievance, breach, erasure, rectification, restriction, objection,
     * nomination, automated-decision review, incident).
     */
    @Test
    void shippedComplianceFormsRequireTheirFields() throws Exception {
        java.nio.file.Path dir = java.nio.file.Path.of("..", "sangam-compliance", "src", "main", "resources", "packs",
                "compliance", "forms");
        Map<String, String> res = new LinkedHashMap<>();
        try (var s = java.nio.file.Files.list(dir)) {
            for (java.nio.file.Path p : s.filter(x -> x.toString().endsWith(".form.json")).sorted().toList()) {
                res.put("packs/compliance/forms/" + p.getFileName(), java.nio.file.Files.readString(p));
            }
        }
        assertEquals(21, res.size());
        for (String form : res.values()) {
            for (var f : JSON.readTree(form).path("fields")) {
                assertTrue(f.hasNonNull("id") && !f.has("key"), "field named by id only: " + f);
            }
        }
        TaskFormRequirements r = new TaskFormRequirements(repository(Map.of("dep1", res), new AtomicInteger()));
        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put("compliance/grievance-examine", List.of("grievanceResponse"));
        expected.put("compliance/breach-risk-assessment", List.of("riskFactors", "affectedCount"));
        for (var e : expected.entrySet()) {
            List<String> required = r.requiredForTask("pd1", null, "sangam:" + e.getKey());
            assertEquals(e.getValue(), required, e.getKey());
            assertEquals(e.getValue(), TaskFormRequirements.missing(required, Map.of()), "completion without them");
        }
    }

    /** A RepositoryService answering resource listings/streams of the given deployments; pd1 belongs to dep1. */
    private static RepositoryService repository(Map<String, Map<String, String>> deployments, AtomicInteger listings) {
        ProcessDefinition pd = (ProcessDefinition) Proxy.newProxyInstance(TaskFormRequirementsTest.class.getClassLoader(),
                new Class<?>[]{ProcessDefinition.class}, (proxy, m, args) -> switch (m.getName()) {
                    case "getDeploymentId" -> "dep1";
                    case "getId" -> "pd1";
                    default -> null;
                });
        return (RepositoryService) Proxy.newProxyInstance(TaskFormRequirementsTest.class.getClassLoader(),
                new Class<?>[]{RepositoryService.class}, (proxy, m, args) -> switch (m.getName()) {
                    case "getDeploymentResourceNames" -> {
                        listings.incrementAndGet();
                        yield new ArrayList<>(deployments.getOrDefault((String) args[0], Map.of()).keySet());
                    }
                    case "getResourceAsStream" -> new ByteArrayInputStream(deployments.get((String) args[0])
                            .get((String) args[1]).getBytes(StandardCharsets.UTF_8));
                    case "getProcessDefinition" -> pd;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }
}
