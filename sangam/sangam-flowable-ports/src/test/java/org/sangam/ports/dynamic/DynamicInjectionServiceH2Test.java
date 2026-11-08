// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.dynamic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.engine.task.Task;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.ports.PortsH2Engine;
import org.sangam.ports.dynamic.DynamicInjectionService.Injection;
import org.sangam.ports.dynamic.DynamicInjectionService.UserTaskSpec;
import org.sangam.ports.support.EngineTx;
import org.sangam.ports.support.PortsProblem;

/** FC-15 dynamic injection: variant deploy + migration + start, governance, tenant, guards. */
class DynamicInjectionServiceH2Test {

    private static final String HOST = """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
              <process id="host" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="a"/>
                <userTask id="a" name="A"/><sequenceFlow id="f2" sourceRef="a" targetRef="e"/><endEvent id="e"/>
              </process>
            </definitions>""";

    private static final String FRAGMENT = """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="f">
              <process id="frag">
                <startEvent id="fs"/><sequenceFlow id="g1" sourceRef="fs" targetRef="check"/>
                <userTask id="check" name="Check"/><sequenceFlow id="g2" sourceRef="check" targetRef="fe"/><endEvent id="fe"/>
              </process>
            </definitions>""";

    private PortsH2Engine h2;
    private DynamicInjectionService svc;

    @BeforeEach
    void setUp() {
        h2 = PortsH2Engine.start(null);
        h2.engine.getRepositoryService().createDeployment().addString("host.bpmn", HOST).deploy();
        h2.engine.getRepositoryService().createDeployment().addString("host.bpmn", HOST).tenantId("t1").deploy();
        svc = new DynamicInjectionService(h2.engine, Dialect.POSTGRESQL, h2.plugin.links(), DynamicInjectionService.InjectionPolicy.ALLOW_ALL);
    }

    @AfterEach
    void tearDown() {
        h2.close();
    }

    private String startHost(String tenant) {
        var b = h2.engine.getRuntimeService().createProcessInstanceByKey("host");
        if (tenant == null) {
            b.processDefinitionWithoutTenantId();
        } else {
            b.processDefinitionTenantId(tenant);
        }
        return b.execute().getId();
    }

    private long injections(String pid) {
        return EngineTx.execute(h2.engine, cc -> Jdbc.one(EngineTx.connection(), "SELECT COUNT(*) FROM DYN_INJECTION WHERE process_instance_id = ?",
                rs -> rs.getLong(1), pid).orElse(0L));
    }

    private long deployments() {
        return h2.engine.getRepositoryService().createDeploymentQuery().count();
    }

    @Test
    void injectedUserTaskRunsBesideTheCurrentTokenOnADerivedDefinition() {
        String pid = startHost(null);
        String sourceDef = h2.engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(pid).singleResult().getProcessDefinitionId();
        Injection inj = svc.injectUserTask(pid, new UserTaskSpec("Second opinion", "alice", List.of("reviewers"), "form:x", null), "admin");

        ProcessDefinition derived = h2.engine.getRepositoryService().getProcessDefinition(inj.derivedDefinitionId());
        assertTrue(derived.getKey().startsWith("host__dyn"), derived.getKey());
        assertEquals(inj.derivedDefinitionId(),
                h2.engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(pid).singleResult().getProcessDefinitionId());
        List<Task> tasks = h2.engine.getTaskService().createTaskQuery().processInstanceId(pid).list();
        assertEquals(2, tasks.size(), "original token kept, injected task added");
        Task injected = tasks.stream().filter(t -> inj.injectedActivityId().equals(t.getTaskDefinitionKey())).findFirst().orElseThrow();
        assertEquals("Second opinion", injected.getName());
        assertEquals("alice", injected.getAssignee());
        assertEquals("form:x", h2.engine.getFormService().getTaskFormKey(inj.derivedDefinitionId(), inj.injectedActivityId()));
        assertEquals(1, h2.engine.getTaskService().createTaskQuery().taskId(injected.getId()).taskCandidateGroup("reviewers").includeAssignedTasks().count());

        // new starts are unaffected: the original key's latest version is still the source
        assertEquals(sourceDef, h2.engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey("host")
                .withoutTenantId().latestVersion().singleResult().getId());
        assertEquals(1, injections(pid));
        assertEquals(1, h2.plugin.links().outgoing(null, "process", pid).stream()
                .filter(l -> "dynamic-injection".equals(l.linkType()) && inj.derivedDefinitionId().equals(l.targetId())).count());

        // both tokens complete normally
        tasks.forEach(t -> h2.engine.getTaskService().complete(t.getId()));
        assertEquals(0, h2.engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(pid).count());

        // a second injection into an already derived instance derives from the base key again
        String pid2 = startHost(null);
        Injection i1 = svc.injectUserTask(pid2, new UserTaskSpec(null, null, null, null, null), "admin");
        Injection i2 = svc.injectUserTask(pid2, new UserTaskSpec("again", null, null, null, null), "admin");
        String k2 = h2.engine.getRepositoryService().getProcessDefinition(i2.derivedDefinitionId()).getKey();
        assertTrue(k2.startsWith("host__dyn") && k2.indexOf("__dyn") == k2.lastIndexOf("__dyn"), k2);
        assertNotEquals(i1.injectedActivityId(), i2.injectedActivityId());
        assertEquals(3, h2.engine.getTaskService().createTaskQuery().processInstanceId(pid2).count());
    }

    @Test
    void derivedDefinitionStaysInTheInstancesTenant() {
        String pid = startHost("t1");
        Injection inj = svc.injectUserTask(pid, new UserTaskSpec("x", null, null, null, null), "admin");
        assertEquals("t1", h2.engine.getRepositoryService().getProcessDefinition(inj.derivedDefinitionId()).getTenantId());
        assertEquals("t1", h2.engine.getTaskService().createTaskQuery().processInstanceId(pid).taskDefinitionKey(inj.injectedActivityId())
                .singleResult().getTenantId());
    }

    @Test
    void expressionsPolicyAndSuspensionRefuseWithoutSideEffects() {
        String pid = startHost(null);
        long before = deployments();
        PortsProblem p = assertThrows(PortsProblem.class,
                () -> svc.injectUserTask(pid, new UserTaskSpec("${bean.run()}", null, null, null, null), "u"));
        assertEquals("EXPRESSION_NOT_ALLOWED", p.code());
        assertThrows(PortsProblem.class, () -> svc.injectUserTask(pid, new UserTaskSpec("n", null, List.of("ok", "#{x}"), null, null), "u"));
        assertThrows(PortsProblem.class, () -> svc.injectUserTask(pid, null, "u"));

        DynamicInjectionService refusing = new DynamicInjectionService(h2.engine, Dialect.POSTGRESQL, h2.plugin.links(),
                (t, i, k, s, u) -> {
                    throw new IllegalStateException("not approved");
                });
        assertThrows(IllegalStateException.class, () -> refusing.injectUserTask(pid, new UserTaskSpec("n", null, null, null, null), "u"));

        h2.engine.getRuntimeService().suspendProcessInstanceById(pid);
        assertThrows(IllegalStateException.class, () -> svc.injectUserTask(pid, new UserTaskSpec("n", null, null, null, null), "u"));
        assertThrows(ProcessEngineException.class, () -> svc.injectUserTask("no-such-instance", new UserTaskSpec("n", null, null, null, null), "u"));

        assertEquals(before, deployments(), "nothing deployed");
        assertEquals(0, injections(pid));
        assertEquals(1, h2.engine.getTaskService().createTaskQuery().processInstanceId(pid).count());
    }

    @Test
    void embeddedSubProcessFragmentIsPrefixedAndRuns() {
        String pid = startHost(null);
        Injection inj = svc.injectEmbeddedSubProcess(pid, "Extra checks", FRAGMENT.getBytes(StandardCharsets.UTF_8), "admin");
        Task check = h2.engine.getTaskService().createTaskQuery().processInstanceId(pid).taskDefinitionKey(inj.injectedActivityId() + "_check")
                .singleResult();
        assertEquals("Check", check.getName());
        h2.engine.getTaskService().complete(check.getId());
        assertEquals(1, h2.engine.getTaskService().createTaskQuery().processInstanceId(pid).count(), "sub-process ended, host token remains");
    }

    @Test
    void invalidFragmentsAreRefused() {
        String pid = startHost(null);
        String twoStarts = FRAGMENT.replace("<startEvent id=\"fs\"/>", "<startEvent id=\"fs\"/><startEvent id=\"fs2\"/>");
        PortsProblem p = assertThrows(PortsProblem.class, () -> svc.injectEmbeddedSubProcess(pid, "x", twoStarts.getBytes(StandardCharsets.UTF_8), "u"));
        assertEquals("FRAGMENT_INVALID", p.code());
        String noEnd = FRAGMENT.replace("<endEvent id=\"fe\"/>", "<userTask id=\"fe\"/>");
        assertThrows(PortsProblem.class, () -> svc.injectEmbeddedSubProcess(pid, "x", noEnd.getBytes(StandardCharsets.UTF_8), "u"));
        String rootRef = FRAGMENT.replace("<userTask id=\"check\" name=\"Check\"/>",
                "<intermediateCatchEvent id=\"check\"><messageEventDefinition messageRef=\"hostMessage\"/></intermediateCatchEvent>");
        assertThrows(PortsProblem.class, () -> svc.injectEmbeddedSubProcess(pid, "x", rootRef.getBytes(StandardCharsets.UTF_8), "u"));
        String noProcess = "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" targetNamespace=\"f\"/>";
        assertThrows(PortsProblem.class, () -> svc.injectEmbeddedSubProcess(pid, "x", noProcess.getBytes(StandardCharsets.UTF_8), "u"));
        assertEquals(0, injections(pid));
    }
}
