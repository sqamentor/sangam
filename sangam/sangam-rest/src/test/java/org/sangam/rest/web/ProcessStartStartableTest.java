// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.ProcessPort;
import org.sangam.rest.port.RequestContext;

/**
 * Owner decision 402 (10), R7UI-F7-5 residual: POST /api/v1/process-instances refuses a process definition that is not
 * startable in the tasklist (operaton:isStartableInTasklist="false": started by a service, a parent or an event, which
 * pass variables no person supplies), before anything reaches the engine. Internal starters call the port directly
 * and are not affected.
 */
class ProcessStartStartableTest {

    static final RequestContext CTX = new RequestContext("t1", "alice", Set.of(), null, null, null, null, "127.0.0.1",
            "OIDC");

    final List<String> started = new ArrayList<>();

    static ProcessPort.ProcessDefinition def(String key, int version, String tenant, boolean startable) {
        return new ProcessPort.ProcessDefinition(key + ":" + version + ":" + tenant, key, key, version, "dep", tenant,
                "1.0.2", false, key + ".bpmn", startable, 0, 0);
    }

    ProcessPort port(List<ProcessPort.ProcessDefinition> defs) {
        return (ProcessPort) Proxy.newProxyInstance(ProcessPort.class.getClassLoader(), new Class<?>[] {ProcessPort.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "definitions" -> {
                        ProcessPort.DefinitionFilter f = (ProcessPort.DefinitionFilter) args[1];
                        yield Page.of(defs.stream().filter(d -> d.key().equals(f.key())).toList(), defs.size(), false);
                    }
                    case "start" -> {
                        started.add(((ProcessPort.StartProcessRequest) args[1]).key());
                        yield new ProcessPort.ProcessInstance("pi-1", "d", "k", 1, null, "t1", "ACTIVE", false, null,
                                null, List.of(), 1L, "alice", null, null, "pi-1", null);
                    }
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    static final PermissionPort ALL = new PermissionPort() {
        @Override
        public boolean has(RequestContext ctx, String permission) {
            return true;
        }

        @Override
        public Set<String> permissions(RequestContext ctx) {
            return Set.of();
        }

        @Override
        public boolean mayReplay(RequestContext ctx, String commandType, String resourceRef) {
            return false;
        }
    };

    ProcessController controller(List<ProcessPort.ProcessDefinition> defs) {
        return new ProcessController(port(defs), null, null, null, ALL);
    }

    static ProcessPort.StartProcessRequest req(String key, Integer version) {
        return new ProcessPort.StartProcessRequest(key, null, Map.of(), version, null, null, null);
    }

    @Test
    void aProcessNotStartableInTheTasklistIsRefusedBeforeTheEngine() {
        ProcessController c = controller(List.of(def("sangam-up01-stp-order", 3, "t1", false)));
        ApiProblem p = assertThrows(ApiProblem.class, () -> c.start(CTX, req("sangam-up01-stp-order", null)));
        assertEquals(422, p.status());
        assertTrue(p.code().equals("PROCESS_NOT_STARTABLE"), p.code());
        assertTrue(started.isEmpty(), "nothing reached the engine");
    }

    @Test
    void theTenantDefinitionDecidesAndAStartableProcessStarts() {
        // the shared definition is not startable, the tenant's own latest is: the tenant's one is what starts
        ProcessController c = controller(List.of(def("leave", 2, "t1", true), def("leave", 1, null, false)));
        c.start(CTX, req("leave", null));
        assertEquals(List.of("leave"), started);
    }

    @Test
    void aRequestedVersionIsCheckedAsThatVersion() {
        ProcessController c = controller(List.of(def("leave", 2, "t1", true), def("leave", 1, "t1", false)));
        assertThrows(ApiProblem.class, () -> c.start(CTX, req("leave", 1)));
        c.start(CTX, req("leave", 2));
        assertEquals(List.of("leave"), started);
    }
}
