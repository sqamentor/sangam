// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.task.IdentityLink;
import org.operaton.bpm.engine.task.Task;

import org.sangam.packs.PackDriver.Answer;
import org.sangam.packs.port.PackRuntime;

/**
 * Answers a user task from configuration only: the person is the holder today of the task's candidate seat (seed
 * data), the decision is the form's affirmative option, required fields not yet on the case get a typed value. No
 * pack-specific Java: the same answerer drives every sector.
 */
final class FormAnswers {

    static final List<String> AFFIRMATIVE = List.of("approve", "accept", "recommend", "sanction", "falsePositive",
            "feasible", "pass", "eligible", "release", "confirm", "allow", "grant", "proceed", "yes", "complete",
            "verified", "agree", "settle", "pay", "close");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ProcessEngine engine;
    private final Map<String, Map<String, Object>> overrides = new HashMap<>();

    FormAnswers(ProcessEngine engine) {
        this.engine = engine;
    }

    /** Scenario data for one task (wins over the form-derived value). */
    FormAnswers with(String taskKey, Object... kv) {
        Map<String, Object> m = overrides.computeIfAbsent(taskKey, k -> new HashMap<>());
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i].toString(), kv[i + 1]);
        }
        return this;
    }

    static JsonNode form(String formKey) {
        if (formKey == null || !formKey.startsWith("sangam:")) {
            return null;
        }
        String[] p = formKey.substring("sangam:".length()).split("/", 2);
        try (InputStream in = FormAnswers.class.getClassLoader()
                .getResourceAsStream("packs/" + p[0] + "/forms/" + p[1] + ".form.json")) {
            return in == null ? null : JSON.readTree(in);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    Answer answer(Task t) {
        String seat = engine.getTaskService().getIdentityLinksForTask(t.getId()).stream()
                .map(IdentityLink::getGroupId).filter(Objects::nonNull).findFirst().orElse("none");
        String person = PackRuntime.seats().holderOf(null, seat, java.time.LocalDate.now()).orElse("user." + seat);
        Map<String, Object> v = new HashMap<>();
        JsonNode f = form(engine.getFormService().getTaskFormKey(t.getProcessDefinitionId(), t.getTaskDefinitionKey()));
        Map<String, Object> existing = engine.getTaskService().getVariables(t.getId());
        if (f != null) {
            for (JsonNode fld : f.path("fields")) {
                String id = fld.path("id").asText();
                if (!fld.path("required").asBoolean() || existing.get(id) != null) {
                    continue;
                }
                Object val = switch (fld.path("type").asText()) {
                    case "decimal", "money", "number" -> 1000.0;
                    case "integer" -> 1;
                    case "boolean" -> true;
                    case "date", "datetime" -> new Date(System.currentTimeMillis() + 86_400_000L);
                    case "json" -> "[]";
                    case "readonly", "hidden" -> null;
                    default -> "X-" + id;
                };
                if (val != null) {
                    v.put(id, val);
                }
            }
            JsonNode d = f.path("decision");
            if (!d.isMissingNode()) {
                List<String> opts = new ArrayList<>();
                d.path("options").forEach(o -> opts.add(o.path("value").asText()));
                v.put(d.path("variable").asText(), AFFIRMATIVE.stream().filter(opts::contains).findFirst()
                        .orElse(opts.isEmpty() ? null : opts.get(0)));
            }
        }
        v.putAll(overrides.getOrDefault(t.getTaskDefinitionKey(), Map.of()));
        return new Answer(person, seat, v);
    }
}
