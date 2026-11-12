// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.template;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** C3-14 (owner decision 58, W3-J request): validation per template kind. */
class ElementTemplatesKindTest {

    private static final List<Map<String, Object>> FIELDS = List.of(Map.of("name", "topic", "binding", "operaton:topic"));

    @Test
    void aPaletteEntryNamesExactlyOneBpmnType() {
        assertTrue(ElementTemplates.validate(ElementTemplates.Kind.PALETTE,
                Map.of("id", "p", "appliesTo", List.of("bpmn:ServiceTask"), "fields", FIELDS)).isEmpty());
        assertFalse(ElementTemplates.validate(ElementTemplates.Kind.PALETTE,
                Map.of("id", "p", "appliesTo", List.of("bpmn:ServiceTask", "bpmn:UserTask"), "fields", FIELDS)).isEmpty());
        assertFalse(ElementTemplates.validate(ElementTemplates.Kind.PALETTE,
                Map.of("id", "p", "appliesTo", List.of("ServiceTask"), "fields", FIELDS)).isEmpty());
        assertFalse(ElementTemplates.validate(ElementTemplates.Kind.PALETTE,
                Map.of("id", "p", "appliesTo", List.of("bpmn:ServiceTask"))).isEmpty(), "fields are required as for ELEMENT");
        assertFalse(ElementTemplates.validate(ElementTemplates.Kind.PALETTE, Map.of("id", "p", "appliesTo",
                List.of("bpmn:ServiceTask"), "fields", List.of(Map.of("name", "x", "binding", "camunda:x")))).isEmpty(),
                "the ELEMENT binding rule applies");
    }

    @Test
    void aFormComponentNeedsTypedComponents() {
        assertTrue(ElementTemplates.validate(ElementTemplates.Kind.FORM_COMPONENT,
                Map.of("id", "f", "name", "Address", "components", List.of(Map.of("type", "textfield")))).isEmpty());
        assertFalse(ElementTemplates.validate(ElementTemplates.Kind.FORM_COMPONENT,
                Map.of("id", "f", "components", List.of())).isEmpty());
        assertFalse(ElementTemplates.validate(ElementTemplates.Kind.FORM_COMPONENT,
                Map.of("id", "f", "components", List.of(Map.of("label", "no type")))).isEmpty());
        assertFalse(ElementTemplates.validate(ElementTemplates.Kind.FORM_COMPONENT, Map.of("id", "f")).isEmpty());
    }

    @Test
    void processAndDmnTemplatesNeedNoFields() {
        assertTrue(ElementTemplates.validate(ElementTemplates.Kind.PROCESS, Map.of("id", "x")).isEmpty());
        assertTrue(ElementTemplates.validate(ElementTemplates.Kind.DMN, Map.of("id", "x")).isEmpty());
    }
}
