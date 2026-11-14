// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ManagementService;
import org.operaton.bpm.engine.runtime.Job;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;

/**
 * M-044: the engine records the starting trace id of every new process instance in PROCESS_TRACE (0043-12, applied
 * with the product schema) in the starting transaction; a child inherits its root's id, also when it starts later in
 * a job with another (or no) current trace; no current trace means no row.
 */
class ProcessTraceRecorderH2Test {

    private static final String TRACE_A = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String TRACE_B = "0af7651916cd43dd8448eb211c80319c";

    private final AtomicReference<String> current = new AtomicReference<>();
    private JdbcDataSource ds;

    private static final String MODELS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <process id="child" isExecutable="true">
                <startEvent id="cs"/><sequenceFlow id="c1" sourceRef="cs" targetRef="work"/>
                <userTask id="work"/><sequenceFlow id="c2" sourceRef="work" targetRef="ce"/><endEvent id="ce"/>
              </process>
              <process id="parentSync" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="call"/>
                <callActivity id="call" calledElement="child" operaton:calledElementBinding="deployment"/>
                <sequenceFlow id="f2" sourceRef="call" targetRef="e"/><endEvent id="e"/>
              </process>
              <process id="parentAsync" isExecutable="true">
                <startEvent id="s2"/><sequenceFlow id="g1" sourceRef="s2" targetRef="call2"/>
                <callActivity id="call2" calledElement="child" operaton:calledElementBinding="deployment"
                              operaton:asyncBefore="true"/>
                <sequenceFlow id="g2" sourceRef="call2" targetRef="e2"/><endEvent id="e2"/>
              </process>
            </definitions>
            """;

    private OperatonEngineAdapter engine() {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        s.setJobExecutorActivate(false);
        s.setTraceIdSource(current::get);
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:m044" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2");
        a.deploy("t1", "m044-" + UUID.randomUUID(), Map.of("m044.bpmn", MODELS.getBytes(StandardCharsets.UTF_8)));
        return a;
    }

    private Optional<String> trace(String processInstanceId) throws Exception {
        try (Connection c = ds.getConnection()) {
            return ProcessTraces.read(c, processInstanceId);
        }
    }

    private static String childOf(OperatonEngineAdapter a, String parent) {
        return a.processEngine().getRuntimeService().createProcessInstanceQuery().superProcessInstanceId(parent)
                .singleResult().getId();
    }

    @Test
    void aRootAndItsSynchronousChildShareTheStartingTrace() throws Exception {
        try (OperatonEngineAdapter a = engine()) {
            current.set(TRACE_A);
            String parent = a.startProcess("t1", "parentSync", "B-" + UUID.randomUUID(), Map.of());
            assertEquals(Optional.of(TRACE_A), trace(parent));
            assertEquals(Optional.of(TRACE_A), trace(childOf(a, parent)), "same command: the child inherits");
        }
    }

    @Test
    void aChildStartedLaterInAJobInheritsTheRootsTraceNotTheJobs() throws Exception {
        try (OperatonEngineAdapter a = engine()) {
            current.set(TRACE_A);
            String parent = a.startProcess("t1", "parentAsync", "B-" + UUID.randomUUID(), Map.of());
            ManagementService m = a.processEngine().getManagementService();
            Job job = m.createJobQuery().processInstanceId(parent).singleResult();
            current.set(TRACE_B);
            m.executeJob(job.getId());
            assertEquals(Optional.of(TRACE_A), trace(childOf(a, parent)), "inherited from the root, not the job's trace");
        }
    }

    @Test
    void withoutACurrentTraceNothingIsRecordedAndNothingIsMadeUp() throws Exception {
        try (OperatonEngineAdapter a = engine()) {
            current.set(null);
            String parent = a.startProcess("t1", "parentSync", "B-" + UUID.randomUUID(), Map.of());
            assertTrue(trace(parent).isEmpty());
            assertTrue(trace(childOf(a, parent)).isEmpty());
            current.set("00000000000000000000000000000000");
            String invalid = a.startProcess("t1", "parentSync", "B-" + UUID.randomUUID(), Map.of());
            assertTrue(trace(invalid).isEmpty(), "the W3C invalid id is not a trace");
        }
    }
}
