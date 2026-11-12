// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** M-067/M-068/M-072: the built-in connector element templates. */
class ConnectorTemplatesTest {

    @Test
    void everyBuiltInTemplateIsAValidElementTemplateWithAUniqueId() {
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> d : ConnectorTemplates.documents()) {
            assertTrue(ElementTemplates.validate(ElementTemplates.Kind.ELEMENT, d).isEmpty(), String.valueOf(d.get("id")));
            assertTrue(ids.add(String.valueOf(d.get("id"))), "duplicate " + d.get("id"));
        }
        assertEquals(Set.of(ConnectorTemplates.AWS_LAMBDA, ConnectorTemplates.AZURE_FUNCTION, ConnectorTemplates.GCP_CLOUD_RUN,
                ConnectorTemplates.ASYNC_SERVERLESS, ConnectorTemplates.DIGILOCKER_REQUESTER,
                ConnectorTemplates.OPERATION_REF), ids);
    }

    @Test
    void applyingTheLambdaTemplateBindsAnExternalTaskOnATopic() {
        ElementTemplates.Template t = ElementTemplates.of(ElementTemplates.Kind.ELEMENT, ConnectorTemplates.documents().get(0));
        ElementTemplates.Applied a = ElementTemplates.apply(t, "bpmn:ServiceTask",
                Map.of("topic", "lambda.greet", "path", "/2015-03-31/functions/greet/invocations"));
        assertTrue(a.errors().isEmpty(), a.errors().toString());
        assertEquals("external", a.properties().get("operaton:type"));
        assertEquals("lambda.greet", a.properties().get("operaton:topic"));
        assertEquals("/2015-03-31/functions/greet/invocations", a.properties().get("input:path"));
        assertFalse(ElementTemplates.apply(t, "bpmn:ServiceTask", Map.of("topic", "x", "path", "/elsewhere"))
                .errors().isEmpty(), "the path pattern is enforced");
        assertFalse(ElementTemplates.apply(t, "bpmn:ServiceTask", Map.of()).errors().isEmpty(), "the topic is required");
    }

    @Test
    void theOperationRefTemplateWritesTheSangamAttribute() {
        Map<String, Object> doc = ConnectorTemplates.documents().stream()
                .filter(d -> ConnectorTemplates.OPERATION_REF.equals(d.get("id"))).findFirst().orElseThrow();
        ElementTemplates.Template t = ElementTemplates.of(ElementTemplates.Kind.ELEMENT, doc);
        ElementTemplates.Applied ok = ElementTemplates.apply(t, "bpmn:ServiceTask",
                Map.of("topic", "api.pets", "operationRef", "pets@2#getPet"));
        assertTrue(ok.errors().isEmpty(), ok.errors().toString());
        assertEquals("pets@2#getPet", ok.properties().get("sangam:operationRef"));
        assertFalse(ElementTemplates.apply(t, "bpmn:ServiceTask", Map.of("topic", "api.pets", "operationRef", "pets"))
                .errors().isEmpty());
    }

    @Test
    void noTemplateCarriesACredentialField() {
        for (Map<String, Object> d : ConnectorTemplates.documents()) {
            for (Object f : (List<?>) d.get("fields")) {
                String name = String.valueOf(((Map<?, ?>) f).get("name")).toLowerCase(java.util.Locale.ROOT);
                assertFalse(name.contains("secret") || name.contains("password") || name.contains("key"), name);
            }
        }
    }

    @Test
    void sameIgnoresTheStoredVersionNumber() {
        Map<String, Object> d = ConnectorTemplates.documents().get(1);
        Map<String, Object> stored = new java.util.LinkedHashMap<>(d);
        stored.put("version", 7);
        assertTrue(ConnectorTemplates.same(stored, d));
        stored.put("description", "changed");
        assertFalse(ConnectorTemplates.same(stored, d));
    }
}
