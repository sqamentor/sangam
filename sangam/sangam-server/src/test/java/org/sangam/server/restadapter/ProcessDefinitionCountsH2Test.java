// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.restadapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.rest.adapter.operaton.OperatonOperationIds;
import org.sangam.rest.adapter.operaton.OperatonProcessAdapter;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.ProcessPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.RestProperties;

/**
 * UI-DEMO-5: {@code GET /process-definitions} and {@code GET /process-definitions/by-id?id=} carry each definition's
 * {@code runningInstances} and {@code incidents}, counted over the caller tenant's instances only, so the process
 * list can show load and trouble without one instance query per row. A shared definition (no tenant) counts only the
 * caller tenant's instances of it.
 */
class ProcessDefinitionCountsH2Test {

    /** The delegate of {@link #FAILING}: its job fails every time it is executed. */
    public static final class Failing implements JavaDelegate {
        @Override
        public void execute(DelegateExecution execution) {
            throw new IllegalStateException("delegate failed");
        }
    }

    /** One asynchronous service task whose delegate fails: its job fails when it is executed. */
    static final String FAILING = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="failJob" isExecutable="true">
                <startEvent id="s"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="boom"/>
                <serviceTask id="boom" operaton:asyncBefore="true" operaton:class="org.sangam.server.restadapter.ProcessDefinitionCountsH2Test$Failing"/>
                <sequenceFlow id="f2" sourceRef="boom" targetRef="e"/>
                <endEvent id="e"/>
              </process>
            </definitions>
            """.strip();

    static RequestContext tenant(String t) {
        return new RequestContext(t, "alice", Set.of(), null, null, null, null, "127.0.0.1", "DEV_TOKEN");
    }

    @Test
    void definitionsCarryTheCallerTenantsRunningInstanceAndOpenIncidentCounts() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:pdcounts" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform");
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true"); // empty in-memory database owned by this test
        s.setJobExecutorActivate(false);
        s.setDeployAllowedClasses(Map.of("*", Set.of(Failing.class.getName())));
        try (OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2")) {
            ProcessEngine engine = a.processEngine();
            RuntimeService runtime = engine.getRuntimeService();
            RepositoryService repository = engine.getRepositoryService();
            for (String t : List.of("t1", "t2")) {
                a.deploy(t, "waitMsg", Map.of("waitMsg.bpmn",
                        MessageCaptureCorrelationKeyH2Test.BPMN.getBytes(StandardCharsets.UTF_8)));
                a.deploy(t, "failJob", Map.of("failJob.bpmn", FAILING.getBytes(StandardCharsets.UTF_8)));
            }
            // a shared definition (no tenant) with one instance outside any tenant
            repository.createDeployment().name("shared")
                    .addString("shared.bpmn", MessageCaptureCorrelationKeyH2Test.BPMN.replace("waitMsg", "sharedMsg"))
                    .deploy();
            String sharedId = repository.createProcessDefinitionQuery().processDefinitionKey("sharedMsg")
                    .withoutTenantId().singleResult().getId();
            runtime.createProcessInstanceById(sharedId).execute();

            OperatonProcessAdapter rest = new OperatonProcessAdapter(runtime, repository, engine.getHistoryService(),
                    engine.getExternalTaskService(), MessageCaptureCorrelationKeyH2Test.tx(ds),
                    MessageCaptureCorrelationKeyH2Test.audit(), new RestProperties(),
                    new OperatonOperationIds(runtime, engine.getHistoryService(), engine.getExternalTaskService()));

            a.startProcess("t1", "waitMsg", "bk-1", Map.of());
            a.startProcess("t1", "waitMsg", "bk-2", Map.of());
            a.startProcess("t2", "waitMsg", "bk-3", Map.of());
            // an open incident the way the engine opens one: a job of t1's instance that fails with no retries left
            String failing = a.startProcess("t1", "failJob", "bk-5", Map.of());
            String job = engine.getManagementService().createJobQuery().processInstanceId(failing).singleResult()
                    .getId();
            engine.getManagementService().setJobRetries(job, 1);
            assertThrows(RuntimeException.class, () -> engine.getManagementService().executeJob(job));
            assertEquals(1, runtime.createIncidentQuery().processInstanceId(failing).count());
            String ended = a.startProcess("t1", "waitMsg", "bk-4", Map.of());
            runtime.deleteProcessInstance(ended, "not running any more");

            Page<ProcessPort.ProcessDefinition> page = rest.definitions(tenant("t1"), null,
                    Page.PageRequest.of(0, 50));
            ProcessPort.ProcessDefinition own = only(page, "waitMsg");
            assertEquals("t1", own.tenantId());
            assertEquals(2, own.runningInstances(), "t1's two running instances; the deleted one is not running");
            assertEquals(0, own.incidents(), "no incident on this definition");
            ProcessPort.ProcessDefinition fail = only(page, "failJob");
            assertEquals(1, fail.runningInstances(), "the instance with the failed job is still running");
            assertEquals(1, fail.incidents(), "t1's one open incident");
            ProcessPort.ProcessDefinition shared = only(page, "sharedMsg");
            assertEquals(0, shared.runningInstances(), "the shared definition's instance is not t1's");
            assertEquals(0, shared.incidents());

            ProcessPort.ProcessDefinition one = rest.definition(tenant("t1"), fail.id());
            assertEquals(1, one.runningInstances(), "the single read carries the same counts");
            assertEquals(1, one.incidents());

            ProcessPort.ProcessDefinition t2 = only(rest.definitions(tenant("t2"), null, Page.PageRequest.of(0, 50)),
                    "waitMsg");
            assertFalse(t2.id().equals(own.id()));
            assertEquals(1, t2.runningInstances());
            assertEquals(0, t2.incidents());
            ProcessPort.ProcessDefinition t2fail = only(rest.definitions(tenant("t2"), null,
                    Page.PageRequest.of(0, 50)), "failJob");
            assertEquals(0, t2fail.runningInstances());
            assertEquals(0, t2fail.incidents(), "t1's incident is not counted for t2");
        }
    }

    private static ProcessPort.ProcessDefinition only(Page<ProcessPort.ProcessDefinition> page, String key) {
        List<ProcessPort.ProcessDefinition> m = page.items().stream().filter(d -> d.key().equals(key)).toList();
        assertTrue(m.size() == 1, key + ": " + m);
        return m.get(0);
    }
}
