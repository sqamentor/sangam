// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.bpmn.instance.StartEvent;

import org.sangam.packs.port.PackRuntime;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;

/**
 * UI-DEMO-7: every process the start catalogue offers (the latest definitions startable in the tasklist, as
 * {@code OperatonWorkspaceAdapter.startCatalogue} lists them) starts from what a person can give it: the fields of its
 * start form, nothing else, plus what the server adds to every start ({@code OperatonProcessAdapter.start}: initiator,
 * seat, case version) and the shipped simulator answers the demo server falls back to. A process that needs a variable
 * only a parent or a service passes (a batch child's {@code key}, a core block's inputs) must not be offered.
 *
 * <p>{@link AllPacksSmokeTest} starts the same processes with a generic bag that holds such variables, so it could not
 * see the defect: "Annual leave credit for one employee" was offered and failed on {@code key}.
 */
class StartCatalogueStartFormTest {

    static ProcessEngine engine;

    @BeforeAll
    static void setUp() {
        PackRuntime.reset();
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:sangam-start-catalogue;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate("create-drop");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        PackTestEngines.configure(cfg); // decisions 138 and FEEL (sangam-feel): as SangamProcessEnginePlugin does
        engine = cfg.buildProcessEngine();
        new PackDeployer(engine, PackRegistry.discover()).deployAll(PackTestTenant.ID);
    }

    @AfterAll
    static void tearDown() {
        engine.close();
    }

    /** The variables of one start from the catalogue: the start form's required fields, typed as the form declares. */
    static Map<String, Object> startFormValues(String formKey) {
        Map<String, Object> v = new HashMap<>();
        JsonNode form = FormAnswers.form(formKey);
        if (form != null) {
            for (JsonNode fld : form.path("fields")) {
                Object val = fld.path("required").asBoolean() ? FormAnswers.sample(fld) : null;
                if (val != null) {
                    v.put(fld.path("id").asText(), val);
                }
            }
        }
        return v;
    }

    /** What OperatonProcessAdapter.start adds to every start of a signed-in person on a seat. */
    static void serverStartVariables(Map<String, Object> v) {
        v.put("initiator", "catalogue.user");
        v.put("initiatorPerson", "catalogue.user");
        v.put("initiatorSeat", "CATALOGUE-SEAT");
        v.put("actingSeat", "CATALOGUE-SEAT");
        v.put("sangamInitiator", "catalogue.user");
        v.put("sangamCaseVersion", 1L);
    }

    @Test
    void everyCatalogueProcessStartsFromItsStartForm() {
        List<String> failures = new ArrayList<>();
        PackDriver driver = new PackDriver(engine);
        int started = 0;
        for (ProcessDefinition pd : engine.getRepositoryService().createProcessDefinitionQuery().latestVersion()
                .startableInTasklist().list()) {
            BpmnModelInstance model = engine.getRepositoryService().getBpmnModelInstance(pd.getId());
            boolean noneStart = model.getModelElementsByType(StartEvent.class).stream()
                    .anyMatch(se -> se.getParentElement() instanceof org.operaton.bpm.model.bpmn.instance.Process
                            && se.getEventDefinitions().isEmpty());
            if (!noneStart) {
                // as AllPacksSmokeTest, an event start is not started here; but one whose only starts are message or
                // signal events gets its data from the event, so without a start form it must not be offered
                List<StartEvent> top = model.getModelElementsByType(StartEvent.class).stream()
                        .filter(se -> se.getParentElement() instanceof org.operaton.bpm.model.bpmn.instance.Process)
                        .toList();
                boolean dataFromEventOnly = top.stream().allMatch(se -> se.getEventDefinitions().stream().anyMatch(
                        d -> d instanceof org.operaton.bpm.model.bpmn.instance.MessageEventDefinition
                                || d instanceof org.operaton.bpm.model.bpmn.instance.SignalEventDefinition));
                if (dataFromEventOnly && top.stream().noneMatch(se -> se.getOperatonFormKey() != null)) {
                    failures.add(pd.getKey() + ": offered, but only a message or signal event starts it with data");
                }
                continue;
            }
            String formKey = engine.getFormService().getStartFormKey(pd.getId());
            Map<String, Object> vars = startFormValues(formKey);
            serverStartVariables(vars);
            vars.putAll(AllPacksSmokeTest.simulatedChecks());
            String bk = "CATALOGUE/" + pd.getKey();
            engine.getIdentityService().setAuthentication("catalogue.user", List.of(), List.of(PackTestTenant.ID));
            try {
                ProcessInstance pi = engine.getRuntimeService().startProcessInstanceById(pd.getId(), bk, vars);
                started++;
                driver.runJobs();
                engine.getRuntimeService().createIncidentQuery().list().stream()
                        .filter(i -> pi.getId().equals(i.getProcessInstanceId())
                                || engine.getRuntimeService().createProcessInstanceQuery()
                                        .processInstanceId(i.getProcessInstanceId()).processInstanceBusinessKeyLike(bk + "%")
                                        .count() > 0)
                        .forEach(i -> failures.add(pd.getKey() + " (form " + formKey + "): incident " + i.getActivityId()
                                + ": " + i.getIncidentMessage()));
            } catch (RuntimeException e) {
                failures.add(pd.getKey() + " (form " + formKey + "): " + AllPacksSmokeTest.rootMessage(e));
                // the driver runs every due job of the engine: a failed job left behind would fail the next starts too
                engine.getRuntimeService().createProcessInstanceQuery().processInstanceBusinessKeyLike(bk + "%").list()
                        .forEach(i -> engine.getRuntimeService().deleteProcessInstanceIfExists(i.getId(),
                                "start-catalogue test: failed start", true, true, true, true));
            } finally {
                engine.getIdentityService().clearAuthentication();
            }
        }
        assertTrue(failures.isEmpty(), started + " started from the catalogue; failures:\n" + String.join("\n", failures));
        assertTrue(started > 0, "the catalogue offers no process");
    }

    /**
     * The sangam-compliance models (packgen compliance.py) declare no start form and read seats, deadlines and a request
     * id bound by their starter services, so none is offered in the start catalogue. They are not deployed in this
     * module; their generated files are read.
     */
    @Test
    void noComplianceModelIsOfferedInTheStartCatalogue() throws IOException {
        Path dir = Path.of("..", "sangam-compliance", "src", "main", "resources", "packs", "compliance");
        List<String> offered = new ArrayList<>();
        int models = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".bpmn")).sorted().toList()) {
                BpmnModelInstance m = Bpmn.readModelFromFile(f.toFile());
                for (org.operaton.bpm.model.bpmn.instance.Process p : m.getModelElementsByType(
                        org.operaton.bpm.model.bpmn.instance.Process.class)) {
                    models++;
                    boolean form = m.getModelElementsByType(StartEvent.class).stream()
                            .anyMatch(se -> se.getOperatonFormKey() != null);
                    if (p.isOperatonStartableInTasklist() && !form) {
                        offered.add(p.getId());
                    }
                }
            }
        }
        assertTrue(models > 0, "no compliance model found under " + dir.toAbsolutePath());
        assertTrue(offered.isEmpty(), "offered without a start form: " + offered);
    }
}
