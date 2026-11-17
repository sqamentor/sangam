// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;

import org.sangam.packs.port.PackRuntime;

/**
 * Decision 394 (R7UI-F7-3): the exam cycle's registrationWindow is an ISO-8601 duration counted from the cycle start.
 * The start form declares it as type {@code duration}, packgen names it in the start listener's {@code durationFields},
 * and {@code PackStartListener} refuses a start whose value is not a positive ISO-8601 duration with
 * {@code 422 VALIDATION_FAILED} naming the field, before anything is written. Before the decision the field was a free
 * string and a start with "two weeks" failed later in the engine's timer parser (ENGINE-09027).
 */
class StartDurationFieldsTest {

    private static final String DIR = "packs/sec-edu-examination/";
    private static final ObjectMapper JSON = new ObjectMapper();
    static ProcessEngine engine;

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-start-durations;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        PackTestEngines.configure(cfg);
        engine = cfg.buildProcessEngine();
        String ns = "xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" xmlns:operaton=\"http://operaton.org/schema/1.0/bpmn\""
                + " targetNamespace=\"t\"";
        String model = "<definitions " + ns + "><process id=\"dur\" isExecutable=\"true\" operaton:historyTimeToLive=\"30\">"
                + "<extensionElements><operaton:executionListener event=\"start\" class=\"org.sangam.packs.core.PackStartListener\">"
                + "<operaton:field name=\"packId\"><operaton:string>test.dur</operaton:string></operaton:field>"
                + "<operaton:field name=\"packVersion\"><operaton:string>1.0.0</operaton:string></operaton:field>"
                + "<operaton:field name=\"durationFields\"><operaton:string>registrationWindow</operaton:string></operaton:field>"
                + "</operaton:executionListener></extensionElements>"
                + "<startEvent id=\"s\"/><userTask id=\"hold\"/><endEvent id=\"e\"/>"
                + "<sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"hold\"/>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"hold\" targetRef=\"e\"/></process></definitions>";
        engine.getRepositoryService().createDeployment().tenantId(PackTestTenant.ID).addString("dur.bpmn", model).deploy();
    }

    @AfterAll
    static void tearDown() {
        engine.close();
        // R8-heap2: a test class stays loaded for the whole run; its statics must not keep a closed engine
        engine = null;
        PackRuntime.reset();
    }

    private static void start(Object window) {
        Map<String, Object> v = new HashMap<>();
        v.put("initiator", "user.a");
        if (window != null) {
            v.put("registrationWindow", window);
        }
        engine.getRuntimeService().createProcessInstanceByKey("dur").processDefinitionTenantId(PackTestTenant.ID)
                .setVariables(v).execute();
    }

    @Test
    void aValueThatIsNotAPositiveIsoDurationIsRefusedNamingTheField() {
        long before = engine.getRuntimeService().createProcessInstanceQuery().count();
        for (String bad : new String[] {"two weeks", "X-registrationWindow", "15", "-P1D", "P0D", "2026-12-01"}) {
            ProcessEngineException e = assertThrows(ProcessEngineException.class, () -> start(bad), bad);
            assertTrue(e.getMessage().contains("422 VALIDATION_FAILED: registrationWindow"), bad + ": " + e.getMessage());
        }
        assertEquals(before, engine.getRuntimeService().createProcessInstanceQuery().count(), "nothing was started");
    }

    @Test
    void aPositiveIsoDurationOrNoValueStarts() {
        long before = engine.getRuntimeService().createProcessInstanceQuery().count();
        start("P15D");
        start("PT36H");
        start("P1M");
        start(null);
        assertEquals(before + 4, engine.getRuntimeService().createProcessInstanceQuery().count());
    }

    @Test
    void theExamCycleDeclaresRegistrationWindowAsADurationFromItsStartForm() throws Exception {
        JsonNode form;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(DIR + "forms/notify.form.json")) {
            assertNotNull(in, "notify form");
            form = JSON.readTree(in);
        }
        JsonNode field = null;
        for (JsonNode f : form.path("fields")) {
            if ("registrationWindow".equals(f.path("id").asText())) {
                field = f;
            }
        }
        assertNotNull(field, "registrationWindow on the start form");
        assertEquals("duration", field.path("type").asText());
        String bpmn;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(DIR + "sangam-sec-edu-exam-cycle.bpmn")) {
            assertNotNull(in, "exam cycle model");
            bpmn = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(bpmn.contains("<operaton:field name=\"durationFields\"><operaton:string>registrationWindow"
                + "</operaton:string></operaton:field>"), "the start listener checks registrationWindow");
        assertTrue(bpmn.contains("duration=\"${registrationWindow}\"") || bpmn.contains(">${registrationWindow}<"),
                "the registration-close timer counts the window");
    }
}
