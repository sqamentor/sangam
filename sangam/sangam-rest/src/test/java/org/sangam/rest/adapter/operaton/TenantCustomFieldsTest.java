// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.adapter.operaton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.sangam.rest.extension.TenantCustomFieldRegistry;
import org.sangam.rest.extension.TenantCustomFieldRegistry.FieldDefinition;
import org.sangam.rest.port.FormPort.FieldError;

/** SL-40: tenant custom fields: configuration, validation through FormSchemaValidator, audit, effective dating. */
class TenantCustomFieldsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FORM = "form:grant-application";
    private static final Map<String, Object> BASE = Map.of("type", "object", "additionalProperties", false,
            "required", List.of("applicant"), "properties", Map.of("applicant", Map.of("type", "string")));

    private final List<String> audit = new ArrayList<>();
    private final TenantCustomFieldRegistry reg = new TenantCustomFieldRegistry(Set.of("object:vendor"),
            (t, actor, action, p) -> audit.add(t + "|" + actor + "|" + action + "|" + p.get("key") + "|v" + p.get("version")
                    + "|" + p.get("effectiveFrom") + ".." + p.get("effectiveTo")));

    private List<String> codes(String tenant, LocalDate on, Map<String, Object> data) {
        Map<String, Object> schema = TenantCustomFieldRegistry.extendSchema(BASE, reg.inForce(tenant, FORM, on));
        List<FieldError> errors = new FormSchemaValidator().validate(JSON.valueToTree(schema), JSON.valueToTree(data));
        return errors.stream().map(e -> e.path() + " " + e.code()).sorted().toList();
    }

    private void loadDefaultConfig() {
        reg.loadConfig("t1", "admin", Map.of("customFields", List.of(
                Map.of("objectType", FORM, "key", "x_district_code", "label", "District code", "required", true,
                        "schema", Map.of("type", "string", "pattern", "^[0-9]{3}$"), "effectiveFrom", "2026-04-01"),
                Map.of("objectType", FORM, "key", "x_grant_amount", "required", false,
                        "schema", Map.of("type", "string", "format", "decimal", "x-sangam-scale", 2),
                        "effectiveFrom", "2026-04-01"))));
    }

    @Test
    void configuredFieldsAreValidatedByTheFormValidator() {
        loadDefaultConfig();
        LocalDate on = LocalDate.of(2026, 11, 8);
        assertEquals(List.of(), codes("t1", on, Map.of("applicant", "A", "x_district_code", "123", "x_grant_amount", "10.50")));
        assertEquals(List.of("/x_district_code REQUIRED"), codes("t1", on, Map.of("applicant", "A")));
        assertEquals(List.of("/x_district_code PATTERN", "/x_grant_amount DECIMAL_SCALE"),
                codes("t1", on, Map.of("applicant", "A", "x_district_code", "12", "x_grant_amount", "10.505")));
        // another tenant has no such fields: additionalProperties=false refuses them
        assertEquals(List.of("/x_district_code ADDITIONAL_PROPERTY"),
                codes("t2", on, Map.of("applicant", "A", "x_district_code", "123")));
    }

    @Test
    void fieldsAreEffectiveDatedAndVersionedWithoutRewritingHistory() {
        loadDefaultConfig();
        assertEquals(List.of("/x_district_code ADDITIONAL_PROPERTY"),
                codes("t1", LocalDate.of(2026, 3, 31), Map.of("applicant", "A", "x_district_code", "123")),
                "not in force before effectiveFrom");
        // v2 from 2027-01-01 widens the code to 4 digits; v1 still applies in 2026
        reg.define("t1", "admin", FORM, "x_district_code", "District code", true, false,
                Map.of("type", "string", "pattern", "^[0-9]{4}$"), LocalDate.of(2027, 1, 1), null);
        assertEquals(List.of(), codes("t1", LocalDate.of(2026, 12, 31), Map.of("applicant", "A", "x_district_code", "123")));
        assertEquals(List.of("/x_district_code PATTERN"),
                codes("t1", LocalDate.of(2027, 1, 1), Map.of("applicant", "A", "x_district_code", "123")));
        assertThrows(IllegalArgumentException.class, () -> reg.define("t1", "admin", FORM, "x_district_code", null, false,
                false, Map.of("type", "string"), LocalDate.of(2026, 6, 1), null), "a back-dated version is refused");
        reg.retire("t1", "admin", FORM, "x_grant_amount", LocalDate.of(2027, 4, 1));
        assertTrue(reg.inForce("t1", FORM, LocalDate.of(2027, 4, 1)).stream().noneMatch(f -> f.key().equals("x_grant_amount")));
        List<FieldDefinition> history = reg.history("t1");
        assertEquals(3, history.size(), "nothing is deleted");
        assertEquals(LocalDate.of(2027, 1, 1), history.get(0).effectiveTo(), "v1 is closed on v2's start");
    }

    @Test
    void everyDefinitionAndRetirementIsAudited() {
        loadDefaultConfig();
        reg.retire("t1", "officer-7", FORM, "x_grant_amount", LocalDate.of(2027, 4, 1));
        assertEquals(List.of(
                "t1|admin|CUSTOM_FIELD_DEFINED|x_district_code|v1|2026-04-01..null",
                "t1|admin|CUSTOM_FIELD_DEFINED|x_grant_amount|v1|2026-04-01..null",
                "t1|officer-7|CUSTOM_FIELD_RETIRED|x_grant_amount|v1|2026-04-01..2027-04-01"), audit);
    }

    @Test
    void invalidDefinitionsAreRefused() {
        LocalDate d = LocalDate.of(2026, 1, 1);
        Map<String, Object> s = Map.of("type", "string");
        record Bad(String objectType, String key, Map<String, Object> schema) {
        }
        for (Bad b : List.of(
                new Bad("object:unregistered", "x_a", s),
                new Bad(FORM, "district", s),
                new Bad(FORM, "x_A", s),
                new Bad(FORM, "x_a", Map.of("type", "number")),
                new Bad(FORM, "x_a", Map.of("type", "object")),
                new Bad(FORM, "x_a", Map.of("type", "string", "$ref", "#/x")),
                new Bad(FORM, "x_a", Map.of("type", "string", "pattern", "([")),
                new Bad(FORM, "x_a", Map.of("type", "string", "format", "uri")),
                new Bad(FORM, "x_a", Map.of("type", "array")))) {
            assertThrows(IllegalArgumentException.class,
                    () -> reg.define("t1", "admin", b.objectType(), b.key(), null, false, false, b.schema(), d, null), b.toString());
        }
        assertThrows(IllegalArgumentException.class,
                () -> reg.define("t1", "admin", FORM, "x_a", null, true, true, s, d, null), "read-only and required");
        assertThrows(IllegalArgumentException.class,
                () -> reg.define("t1", "admin", FORM, "x_a", null, false, false, s, d, d), "empty validity");
        assertTrue(audit.isEmpty(), "refused definitions leave no audit row and no field");
        reg.define("t1", "admin", "object:vendor", "x_gstin", null, false, false, s, d, null);
        assertEquals(1, reg.inForce("t1", "object:vendor", d).size(), "registered object types are extensible");
    }

    @Test
    void shippedFieldsWinOverACustomFieldOfTheSameName() {
        Map<String, Object> merged = TenantCustomFieldRegistry.extendSchema(
                Map.of("type", "object", "properties", Map.of("x_note", Map.of("type", "integer"))),
                List.of(new FieldDefinition(FORM, "x_note", 1, null, true, false, Map.of("type", "string"),
                        LocalDate.of(2026, 1, 1), null)));
        assertEquals(Map.of("type", "integer"), ((Map<?, ?>) merged.get("properties")).get("x_note"));
        assertTrue(!merged.containsKey("required"));
    }

    @Test
    void customFieldsAreEditableUnderDefaultDenyUnlessReadOnly() {
        JsonNode policy = JSON.valueToTree(Map.of("fields", Map.of("applicant", "editable")));
        JsonNode data = JSON.valueToTree(Map.of("applicant", "A", "x_district_code", "123", "x_locked", "1", "other", "z"));
        List<String> errors = OperatonFormAdapter.applyPolicy(policy, null, data, Set.of("x_district_code"))
                .stream().map(e -> e.path() + " " + e.code()).sorted().toList();
        assertEquals(List.of("/other FIELD_NOT_EDITABLE", "/x_locked FIELD_NOT_EDITABLE"), errors);
    }
}
