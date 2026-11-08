// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.sangam.ports.PortsH2Engine;
import org.sangam.ports.links.InstanceLinkService;

/** FC-43 event bus and the ports' history handler (bus feed, FC-32 child links). */
class EngineEventBusH2Test {

    private static final String PARENT = """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
              <process id="parent" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="call"/>
                <callActivity id="call" calledElement="child"/>
                <sequenceFlow id="f2" sourceRef="call" targetRef="e"/><endEvent id="e"/>
              </process>
              <process id="child" isExecutable="true">
                <startEvent id="cs"/><sequenceFlow id="cf" sourceRef="cs" targetRef="ct"/><userTask id="ct"/>
                <sequenceFlow id="cf2" sourceRef="ct" targetRef="ce"/><endEvent id="ce"/>
              </process>
            </definitions>""";

    @Test
    void prefixFilteringAndFailurePolicy() throws Exception {
        EngineEventBus bus = new EngineEventBus();
        List<String> all = new CopyOnWriteArrayList<>();
        List<String> tasks = new CopyOnWriteArrayList<>();
        AutoCloseable reg = bus.register("", e -> all.add(e.type()), true);
        bus.register("task.", e -> tasks.add(e.type()), true);
        bus.register("task.", e -> { throw new IllegalStateException("tolerated"); }, false);
        bus.dispatch(new EngineEventBus.EngineEvent("task.create", "t", null, null, null, "1", Map.of()));
        bus.dispatch(new EngineEventBus.EngineEvent("process.start", "t", "p", null, null, null, Map.of()));
        assertEquals(List.of("task.create", "process.start"), all);
        assertEquals(List.of("task.create"), tasks);
        reg.close();
        bus.dispatch(new EngineEventBus.EngineEvent("process.end", "t", "p", null, null, null, Map.of()));
        assertEquals(2, all.size(), "unregistered");

        bus.register("process.", e -> { throw new IllegalStateException("veto"); }, true);
        assertThrows(IllegalStateException.class,
                () -> bus.dispatch(new EngineEventBus.EngineEvent("process.end", "t", "p", null, null, null, Map.of())));
    }

    @Test
    void historyHandlerFeedsTheBusAndWritesChildLinks() {
        try (PortsH2Engine h2 = PortsH2Engine.start(null)) {
            List<EngineEventBus.EngineEvent> seen = new CopyOnWriteArrayList<>();
            h2.plugin.eventBus().register("", seen::add, true);
            h2.engine.getRepositoryService().createDeployment().addString("p.bpmn", PARENT).deploy();
            String parent = h2.engine.getRuntimeService().startProcessInstanceByKey("parent").getId();
            String child = h2.engine.getRuntimeService().createProcessInstanceQuery().superProcessInstanceId(parent).singleResult().getId();
            String task = h2.engine.getTaskService().createTaskQuery().processInstanceId(child).singleResult().getId();

            assertTrue(seen.stream().anyMatch(e -> e.type().startsWith("process") && e.type().endsWith(".start")
                    && parent.equals(e.processInstanceId())), seen.toString());
            assertTrue(seen.stream().anyMatch(e -> e.type().endsWith(".create") && task.equals(e.taskId())), seen.toString());

            InstanceLinkService links = h2.plugin.links();
            List<InstanceLinkService.Link> out = links.outgoing("", "process", parent);
            assertEquals(1, out.size());
            assertEquals("child", out.get(0).linkType());
            assertEquals("root", out.get(0).hierarchyType());
            assertEquals(child, out.get(0).targetId());
            assertEquals(parent, out.get(0).rootId());
            assertTrue(links.descendants("", "process", parent, 5).containsAll(List.of("process:" + child, "task:" + task)));
            assertEquals(2, links.byRoot("", "process", parent).size(), "process child + task, both under the root");
        }
    }

    @Test
    void referenceLinksAreIdempotent() {
        try (PortsH2Engine h2 = PortsH2Engine.start(null)) {
            InstanceLinkService links = h2.plugin.links();
            assertTrue(links.addReference("t", "process", "a", "case", "b", "u"));
            assertTrue(!links.addReference("t", "process", "a", "case", "b", "u"), "identical link is not inserted twice");
            assertEquals(1, links.incoming("t", "case", "b").size());
            assertTrue(links.outgoing("other", "process", "a").isEmpty(), "tenant isolation");
        }
    }
}
