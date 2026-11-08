// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.forms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.ports.support.Json;

/** FC-39 form model, form-js / Flowable conversion and server-side validation (pure, no engine). */
class FormsTest {

    private static final String SANGAM = """
            {"key":"apply","name":"Apply","fields":[
              {"id":"name","type":"text","required":true,"minLength":2,"maxLength":5},
              {"id":"age","type":"integer","min":18,"max":60},
              {"id":"amount","type":"decimal"},
              {"id":"agree","type":"boolean"},
              {"id":"mail","type":"email"},
              {"id":"phone","type":"phone"},
              {"id":"code","type":"text","pattern":"[A-Z]{3}"},
              {"id":"kind","type":"select","options":[{"value":"a"},{"value":"b"}]},
              {"id":"tags","type":"multiselect","options":[{"value":"x"},{"value":"y"}]},
              {"id":"dob","type":"date"},
              {"id":"ref","type":"text","readOnly":true},
              {"id":"calc","type":"expression"},
              {"id":"reason","type":"text","visibleIf":{"field":"kind","in":["b"]},"requiredIf":{"field":"kind","in":["b"]}},
              {"id":"note","type":"text","defaultValue":"none","variable":"noteVar","scope":"local"}
            ]}""";

    private static FormDefinition def() {
        return FormJsConverter.convert("apply", SANGAM);
    }

    @Test
    void definitionModelDefaultsAndLookup() {
        FormDefinition d = def();
        assertEquals("apply", d.key());
        assertTrue(d.outcomes().isEmpty());
        assertEquals("noteVar", d.field("note").variableName());
        assertEquals("name", d.field("name").variableName());
        assertTrue(d.field("note").local());
        assertTrue(d.field("calc").isReadOnly(), "expression fields are read-only");
        assertNull(d.field("nope"));
        assertEquals("text", new FormDefinition.Field("f", null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null).typeOrText());
        assertThrows(NullPointerException.class, () -> new FormDefinition(null, "n", null, null));
        assertThrows(UnsupportedOperationException.class, () -> d.fields().clear(), "immutable");
        FormDefinition round = Json.read(Json.write(d), FormDefinition.class);
        assertEquals(d, round, "JSON round trip");
    }

    @Test
    void validSubmissionIsCoercedAndUnknownFieldsDropped() {
        Map<String, Object> in = new HashMap<>();
        in.put("name", "Ann");
        in.put("age", "30");
        in.put("amount", "12.5");
        in.put("agree", "on");
        in.put("mail", "a@b.org");
        in.put("phone", "+91 98765 43210");
        in.put("code", "ABC");
        in.put("kind", "a");
        in.put("tags", "x,y");
        in.put("dob", "2000-01-31");
        in.put("evil", "mass-assignment");
        FormValidator.Result r = FormValidator.validate(def(), in);
        assertTrue(r.valid(), r.errors().toString());
        assertEquals(30L, r.values().get("age"));
        assertEquals(12.5, r.values().get("amount"));
        assertEquals(true, r.values().get("agree"));
        assertEquals(List.of("x", "y"), r.values().get("tags"));
        assertEquals("2000-01-31", r.values().get("dob"));
        assertEquals("none", r.values().get("note"), "default applied");
        assertFalse(r.values().containsKey("evil"));
        assertFalse(r.values().containsKey("reason"), "hidden field ignored");
    }

    @Test
    void invalidSubmissionReportsEveryFieldError() {
        Map<String, Object> in = new HashMap<>();
        in.put("name", "A");
        in.put("age", "17");
        in.put("amount", "abc");
        in.put("agree", "maybe");
        in.put("mail", "nope");
        in.put("phone", "12");
        in.put("code", "abc");
        in.put("kind", "b");
        in.put("tags", List.of("x", "z"));
        in.put("ref", "set");
        in.put("calc", "set");
        FormValidator.Result r = FormValidator.validate(def(), in);
        assertFalse(r.valid());
        for (String f : List.of("name", "age", "amount", "agree", "mail", "phone", "code", "tags", "ref", "calc", "reason")) {
            assertTrue(r.errors().containsKey(f), "error expected for " + f + ": " + r.errors());
        }
        assertFalse(r.errors().containsKey("kind"));
        assertTrue(r.errors().get("reason").get(0).contains("required"), "requiredIf");
        assertTrue(FormValidator.validate(def(), Map.of()).errors().containsKey("name"));
        assertFalse(FormValidator.validate(def(), null).valid());
        var tooLong = FormValidator.validate(def(), Map.of("name", "Annabel"));
        assertTrue(tooLong.errors().get("name").get(0).contains("at most 5"));
    }

    @Test
    void patternGuardsAndBadPatterns() {
        FormDefinition d = new FormDefinition("p", "p", List.of(
                new FormDefinition.Field("bad", "text", "Bad", null, null, null, null, null, null, null, null, "([", null, null, null, null),
                new FormDefinition.Field("big", "text", null, null, null, null, null, null, null, null, null, "a*", null, null, null, null)),
                null);
        var r = FormValidator.validate(d, Map.of("bad", "x", "big", "a".repeat(10_001)));
        assertTrue(r.errors().get("bad").get(0).startsWith("Bad has an invalid pattern"), r.errors().toString());
        assertTrue(r.errors().get("big").get(0).contains("too long"));
    }

    @Test
    void formJsSchemaConversion() {
        String formJs = """
                {"id":"leave","components":[
                  {"type":"text","text":"# Header"},
                  {"key":"days","type":"number","label":"Days","validate":{"required":true,"min":1,"max":30}},
                  {"type":"group","components":[
                    {"key":"type","type":"select","label":"Type","values":[{"label":"Casual","value":"cl"},{"label":"Earned","value":"el"}]},
                    {"key":"halfDay","type":"checkbox","readonly":true}
                  ]},
                  {"key":"skills","type":"taglist"},
                  {"key":"comment","type":"textfield","defaultValue":"n/a","validate":{"pattern":"[a-z/ ]*","maxLength":20}}
                ]}""";
        FormDefinition d = FormJsConverter.convert("leave-form", formJs);
        assertEquals("leave-form", d.key());
        assertEquals("leave", d.name());
        assertEquals(List.of("days", "type", "halfDay", "skills", "comment"), d.fields().stream().map(FormDefinition.Field::id).toList());
        var days = d.field("days");
        assertEquals("decimal", days.type());
        assertTrue(days.isRequired());
        assertEquals(1.0, days.min());
        assertEquals(30.0, days.max());
        assertEquals(List.of("cl", "el"), d.field("type").options().stream().map(FormDefinition.Option::value).toList());
        assertEquals("Casual", d.field("type").options().get(0).label());
        assertTrue(d.field("halfDay").isReadOnly());
        assertEquals("boolean", d.field("halfDay").type());
        assertEquals("multiselect", d.field("skills").type());
        assertEquals("n/a", d.field("comment").defaultValue());
        assertEquals(20, d.field("comment").maxLength());
        assertFalse(FormValidator.validate(d, Map.of("days", 31)).valid());
        assertTrue(FormValidator.validate(d, Map.of("days", 3, "type", "cl")).valid());
        assertEquals("text", FormJsConverter.mapFormJsType("unknown-widget"));
    }

    @Test
    void flowableFormJsonConversion() {
        String flowable = """
                {"name":"Review","fields":[
                  {"id":"remarks","type":"multi-line-text","name":"Remarks","required":true},
                  {"id":"decision","type":"dropdown","options":[{"id":"ok","name":"Approve"},{"id":"no","name":"Reject"}]},
                  {"id":"proof","type":"upload"},
                  {"id":"total","type":"expression","value":"42"}
                ],"outcomes":[{"id":"approve","name":"Approve"},{"name":"reject"}]}""";
        FormDefinition d = FormJsConverter.convert("review", flowable);
        assertEquals("Review", d.name());
        assertEquals("textarea", d.field("remarks").type());
        assertTrue(d.field("remarks").isRequired());
        assertEquals("select", d.field("decision").type());
        assertEquals("Reject", d.field("decision").options().get(1).label());
        assertEquals("document", d.field("proof").type());
        assertTrue(d.field("total").isReadOnly());
        assertEquals(List.of("approve", "reject"), d.outcomes().stream().map(FormDefinition.Outcome::id).toList());
        assertThrows(RuntimeException.class, () -> FormJsConverter.convert("x", "not json"));
    }
}
