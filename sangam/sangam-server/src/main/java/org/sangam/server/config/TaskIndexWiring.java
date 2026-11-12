// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.sangam.ops.listener.GlobalTaskListeners;
import org.sangam.rest.adapter.operaton.TaskIndex;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * M-042 (decision 82): the {@value TaskIndex#HANDLER} task listener handler. Its per-process mappings are read from the
 * one place per-process task listener rules already live, {@code sangam.ops.task-listeners-json} (OpsWiring builds the
 * rules and resolves this bean by that name): every rule whose {@code handler} is {@value TaskIndex#HANDLER} carries a
 * {@code taskIndex} object (see {@link TaskIndex}) and must listen to {@code create}. A rule without a valid
 * {@code taskIndex}, or without the create event, fails the start-up instead of leaving an index that is never set.
 */
@Configuration(proxyBeanMethods = false)
public class TaskIndexWiring {

    static final String RULES = "sangam.ops.task-listeners-json";

    @Bean(name = TaskIndex.HANDLER)
    public GlobalTaskListeners.Handler sangamTaskIndex(Environment env) {
        TaskIndex.Recorder recorder = recorder(env.getProperty(RULES));
        return recorder::record;
    }

    /** The recorder of the {@value TaskIndex#HANDLER} rules in a {@code sangam.ops.task-listeners-json} value. */
    public static TaskIndex.Recorder recorder(String json) {
        List<Map<String, Object>> rules;
        try {
            rules = json == null || json.isBlank() ? List.of()
                    : new ObjectMapper().readValue(json, new TypeReference<List<Map<String, Object>>>() {
                    });
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(RULES + " is not a JSON list of rules", e);
        }
        Map<String, TaskIndex.Mapping> mappings = new LinkedHashMap<>();
        for (Map<String, Object> r : rules) {
            if (!TaskIndex.HANDLER.equals(String.valueOf(r.get("handler")))) {
                continue;
            }
            String id = String.valueOf(r.get("id"));
            if (!(r.get("events") instanceof List<?> events) || !events.contains("create")) {
                throw new IllegalStateException(RULES + " rule " + id + ": " + TaskIndex.HANDLER
                        + " must listen to the create event");
            }
            try {
                if (mappings.put(id, TaskIndex.Mapping.of(r.get("taskIndex"))) != null) {
                    throw new IllegalStateException(RULES + ": two " + TaskIndex.HANDLER + " rules have the id " + id);
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(RULES + " rule " + id + ": " + e.getMessage(), e);
            }
        }
        return new TaskIndex.Recorder(mappings);
    }
}
