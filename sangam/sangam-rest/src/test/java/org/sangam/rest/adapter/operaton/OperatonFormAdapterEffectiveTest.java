// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.rest.extension.TenantCustomFieldRegistry.FieldDefinition;
import org.sangam.rest.port.FormPort;

/**
 * SL-166 f2 / owner decision 63: the portal's form is the form's data schema with the tenant custom fields in force merged
 * by the SAME merge validate() applies (shipped properties win, required custom fields join {@code required}); the version
 * hash, layout and field policy are the form's own, so a submission made from the merged form validates.
 */
class OperatonFormAdapterEffectiveTest {

    static final LocalDate FROM = LocalDate.parse("2026-01-01");

    static FieldDefinition field(String key, boolean required, Map<String, Object> schema) {
        return new FieldDefinition("form:birth-cert", key, 1, key, required, false, schema, FROM, null);
    }

    static FormPort.FormVersion form(Object dataSchema) {
        return new FormPort.FormVersion("birth-cert", "vh", "sh", "lh", "ph", dataSchema, Map.of("components", List.of()),
                Map.of("start", Map.of("name", "editable")), Map.of("en", Map.of("k", "v")), "d1", "ACTIVE");
    }

    @Test
    void customFieldsAreMergedIntoTheDataSchema() {
        FormPort.FormVersion base = form(Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string")),
                "required", List.of("name")));
        FormPort.FormVersion merged = OperatonFormAdapter.withCustomFields(base, List.of(
                field("ward", true, Map.of("type", "string", "maxLength", 40)),
                field("name", false, Map.of("type", "integer"))));

        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) merged.dataSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertEquals(Map.of("type", "string", "maxLength", 40), props.get("ward"));
        assertEquals(Map.of("type", "string"), props.get("name"), "a shipped property wins over a custom field of the same name");
        assertEquals(List.of("name", "ward"), schema.get("required"));
        assertEquals("vh", merged.versionHash(), "the version is the form's own");
        assertSame(base.layout(), merged.layout());
        assertSame(base.fieldPolicy(), merged.fieldPolicy());
        assertEquals(base.i18n(), merged.i18n());
        assertEquals("ACTIVE", merged.state());
    }

    @Test
    void aFormWithoutASchemaGetsAnObjectSchemaAndNoFieldsMeansTheFormItself() {
        FormPort.FormVersion layoutOnly = form(null);
        assertSame(layoutOnly, OperatonFormAdapter.withCustomFields(layoutOnly, List.of()), "nothing in force: unchanged");
        FormPort.FormVersion merged = OperatonFormAdapter.withCustomFields(layoutOnly, List.of(field("ward", false, Map.of("type", "string"))));
        assertEquals(Map.of("type", "object", "properties", Map.of("ward", Map.of("type", "string"))), merged.dataSchema());
    }

    @Test
    void validationAndThePortalUseTheSameMerge() {
        FormPort.FormVersion base = form(Map.of("type", "object"));
        List<FieldDefinition> custom = List.of(field("ward", true, Map.of("type", "string")));
        assertEquals(OperatonFormAdapter.mergedSchema(base, custom), OperatonFormAdapter.withCustomFields(base, custom).dataSchema());
    }
}
