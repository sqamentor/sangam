// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.pnp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.engine.deploy.ModelCodeAllowList;

/**
 * R8-pnp PNP-2: host approvals of SL-160 are exact names only. Before R8-pnp a pattern such as {@code recorder.*} or
 * {@code com.dept.*} was accepted at start and silently approved nothing (or, for a context name, looked like an
 * approval of the engine's own objects); now the rule refuses to be built with it ({@value ModelCodeAllowList#ALLOW_ENTRY}).
 */
class HostApprovalEntriesTest {

    private static final Set<String> BEANS = Set.copyOf(ModelCodeAllowList.SANGAM_BEANS);

    @Test
    void wildcardsPatternsAndContextObjectsAreRefused() {
        for (String bad : new String[] {"*", "recorder.*", "*.record", "recorder.record.x", "", " recorder",
                "execution", "task.getVariable", "recorder.getClass", "recorder.class", "getClassLoader"}) {
            IllegalArgumentException x = assertThrows(IllegalArgumentException.class,
                    () -> new ModelCodeAllowList.DeployRule(Map.of(), Map.of("*", Set.of(bad))), bad);
            assertTrue(x.getMessage().startsWith(ModelCodeAllowList.ALLOW_ENTRY), x.getMessage());
        }
        for (String bad : new String[] {"com.dept.*", "*", "Delegate", "com.dept.", "com..X"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ModelCodeAllowList.DeployRule(Map.of("t1", Set.of(bad)), Map.of()), bad);
        }
        assertThrows(IllegalArgumentException.class,
                () -> new ModelCodeAllowList.DeployRule(Map.of(), Map.of(" ", Set.of("recorder"))));
    }

    @Test
    void exactNamesAreAccepted() {
        assertTrue(ModelCodeAllowList.invalidEntries(
                Map.of("*", Set.of("com.dept.Delegate", "com.dept.Outer$Inner")),
                Map.of("*", Set.of("recorder", "recorder.record"), "t1", Set.of("ledgerBean.post"))).isEmpty());
    }

    @Test
    void approvalOpensOnlyTheListedMember() {
        byte[] model = ("<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
                + "xmlns:operaton=\"http://operaton.org/schema/1.0/bpmn\" targetNamespace=\"t\"><process id=\"p\">"
                + "<serviceTask id=\"a\" operaton:expression=\"${recorder.record('x')}\"/>"
                + "<serviceTask id=\"b\" operaton:expression=\"${recorder.wipe()}\"/></process></definitions>")
                .getBytes(StandardCharsets.UTF_8);
        var withApproval = new java.util.HashSet<>(BEANS);
        withApproval.add("recorder.record");
        var findings = ModelCodeAllowList.check("m.bpmn", model, Set.of(), withApproval);
        assertEquals(1, findings.size(), String.valueOf(findings));
        assertTrue(findings.get(0).toString().contains("'b'"), findings.toString());
        assertEquals(2, ModelCodeAllowList.check("m.bpmn", model, Set.of(), BEANS).size(), "fail closed by default");
    }
}
