// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.modeller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;

/**
 * M-125 {@code POST /models/diff} (operationId diffModels, W4-M contract DONE 2026-11-12T17:04:57Z): package.read is
 * required; the answer is the ModelDiffReport shape; a missing or too long resource and a side over 5 MiB are 400
 * VALIDATION_FAILED; an unreadable side or two formats are 400 MODEL_UNREADABLE.
 */
class ModellerControllerDiffTest {

    static final RequestContext CTX = new RequestContext("t1", "alice", Set.of(), null, null, null, null, "127.0.0.1",
            "DEV_TOKEN");

    static final class Grants implements PermissionPort {
        final Set<String> granted;
        final List<String> checked = new ArrayList<>();

        Grants(String... granted) {
            this.granted = Set.of(granted);
        }

        @Override
        public boolean has(RequestContext ctx, String permission) {
            checked.add(permission);
            return granted.contains(permission);
        }

        @Override
        public Set<String> permissions(RequestContext ctx) {
            return granted;
        }

        @Override
        public boolean mayReplay(RequestContext ctx, String commandType, String resourceRef) {
            return true;
        }
    }

    static ModellerController controller(Grants g) {
        return new ModellerController(null, g, null, null, null, null);
    }

    static final String V1 = "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" id=\"d\" targetNamespace=\"t\">"
            + "<process id=\"p\"><startEvent id=\"s\"/></process></definitions>";
    static final String V2 = V1.replace("<startEvent id=\"s\"/>", "<startEvent id=\"s\"/><endEvent id=\"e\" name=\"Done\"/>");

    @Test
    @SuppressWarnings("unchecked")
    void reportShapeOfTheContract() {
        Grants g = new Grants(Permissions.PACKAGE_READ);
        Map<String, Object> r = controller(g).diffModels(CTX, new ModellerController.ModelDiffRequest("order.bpmn", V1, V2));
        assertEquals(List.of(Permissions.PACKAGE_READ), g.checked);
        assertEquals("order.bpmn", r.get("resource"));
        assertEquals("BPMN", r.get("format"));
        assertEquals(Boolean.TRUE, r.get("changed"));
        assertEquals(Map.of("ELEMENT", 1, "FLOW", 0, "EXPRESSION", 0, "PROPERTY", 0), r.get("summary"));
        List<Map<String, Object>> changes = (List<Map<String, Object>>) r.get("changes");
        assertEquals(1, changes.size());
        Map<String, Object> c = changes.get(0);
        assertEquals(List.of("level", "kind", "elementId", "elementType", "name", "path", "before", "after"),
                List.copyOf(c.keySet()));
        assertEquals("ADDED", c.get("kind"));
        assertEquals("e", c.get("elementId"));
        assertEquals("endEvent", c.get("elementType"));
        assertEquals("Done", c.get("name"));
        assertEquals("", c.get("path"));
        assertTrue(c.containsKey("before") && c.get("before") == null);
    }

    @Test
    void newFileIsAllowedAndIdenticalVersionsHaveNoChanges() {
        Grants g = new Grants(Permissions.PACKAGE_READ);
        assertEquals(Boolean.FALSE, controller(g).diffModels(CTX,
                new ModellerController.ModelDiffRequest("order.bpmn", V1, V1)).get("changed"));
        assertEquals(Boolean.TRUE, controller(g).diffModels(CTX,
                new ModellerController.ModelDiffRequest("order.bpmn", null, V1)).get("changed"));
    }

    /** Decision 329: a new or deleted file reports every element of the present version, not the root alone. */
    @Test
    @SuppressWarnings("unchecked")
    void aNewOrDeletedFileListsEveryElement() {
        Grants g = new Grants(Permissions.PACKAGE_READ);
        for (boolean created : new boolean[] {true, false}) {
            Map<String, Object> r = controller(g).diffModels(CTX, new ModellerController.ModelDiffRequest("order.bpmn",
                    created ? null : V2, created ? V2 : ""));
            List<Map<String, Object>> changes = (List<Map<String, Object>>) r.get("changes");
            assertEquals(List.of("d", "e", "p", "s"), changes.stream().map(c -> c.get("elementId")).toList());
            assertTrue(changes.stream().allMatch(c -> (created ? "ADDED" : "REMOVED").equals(c.get("kind"))
                    && "".equals(c.get("path"))));
            assertEquals(Map.of("ELEMENT", 4, "FLOW", 0, "EXPRESSION", 0, "PROPERTY", 0), r.get("summary"));
        }
    }

    @Test
    void withoutPackageReadIsForbidden() {
        ApiProblem p = assertThrows(ApiProblem.class, () -> controller(new Grants()).diffModels(CTX,
                new ModellerController.ModelDiffRequest("order.bpmn", V1, V2)));
        assertEquals(403, p.status());
    }

    @Test
    void validationFailures() {
        ModellerController c = controller(new Grants(Permissions.PACKAGE_READ));
        for (ModellerController.ModelDiffRequest bad : List.of(
                new ModellerController.ModelDiffRequest(null, V1, V2),
                new ModellerController.ModelDiffRequest(" ", V1, V2),
                new ModellerController.ModelDiffRequest("x".repeat(ModellerController.MAX_RESOURCE_LENGTH) + ".bpmn", V1, V2),
                new ModellerController.ModelDiffRequest("order.bpmn", "x".repeat(ModellerController.MAX_MODEL_BYTES + 1), V2))) {
            ApiProblem p = assertThrows(ApiProblem.class, () -> c.diffModels(CTX, bad));
            assertEquals(400, p.status());
            assertEquals("VALIDATION_FAILED", p.code());
        }
    }

    @Test
    void unreadableModels() {
        ModellerController c = controller(new Grants(Permissions.PACKAGE_READ));
        for (ModellerController.ModelDiffRequest bad : List.of(
                new ModellerController.ModelDiffRequest("order.bpmn", "<not xml", V2),
                new ModellerController.ModelDiffRequest("order.bpmn", "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///x\">]><x>&e;</x>", V2),
                new ModellerController.ModelDiffRequest("model.xml", "{\"fields\":[]}", V2),
                new ModellerController.ModelDiffRequest("order.bpmn", null, null))) {
            ApiProblem p = assertThrows(ApiProblem.class, () -> c.diffModels(CTX, bad));
            assertEquals(400, p.status());
            assertEquals("MODEL_UNREADABLE", p.code());
        }
    }
}
