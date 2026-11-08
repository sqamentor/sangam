// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

/**
 * Event definition ({@code .event} model, FLOWABLE-INTO-OPERATON FC-02/FLOWABLE-INTO-OPERATON FC-03). The JSON shape is Flowable's
 * ({@code key, name, payload[], correlationParameters[]}) plus Sangam fields, so Flowable {@code .event} files deploy
 * unchanged.
 *
 * @param key                   event key; also the BPMN message name the event correlates to (see README)
 * @param payload               typed payload fields copied into process variables
 * @param correlationParameters fields that must equal a variable of the waiting instance; {@code scope=local}
 *                              matches a local variable of the waiting execution (branch addressing, FLOWABLE-INTO-OPERATON D-35)
 * @param businessKeyField      payload field used as business key for start and correlation (optional)
 * @param startUniqueReference  correlation parameter whose value must be unique per process definition key; a second
 *                              event with the same value does not start a second instance (FLOWABLE-INTO-OPERATON FC-04, R38 uniqueStart)
 * @param ttlSeconds            how long an unmatched event stays buffered (FLOWABLE-INTO-OPERATON D-33); default 86400
 * @param startProcessKey       process definition key started by this event when the process has no message start
 *                              event named after the event (event-to-process start)
 * @param startCaseKey          case definition key started by this event (event-to-case start; CMMN has no message
 *                              start event in Operaton)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventDefinition(
        @JsonProperty("key") String key,
        @JsonProperty("name") String name,
        @JsonProperty("payload") List<Field> payload,
        @JsonProperty("correlationParameters") List<Field> correlationParameters,
        @JsonProperty("businessKeyField") String businessKeyField,
        @JsonProperty("startUniqueReference") String startUniqueReference,
        @JsonProperty("ttlSeconds") Long ttlSeconds,
        @JsonProperty("startProcessKey") String startProcessKey,
        @JsonProperty("startCaseKey") String startCaseKey) {

    public EventDefinition {
        Objects.requireNonNull(key, "event key");
        payload = payload == null ? List.of() : List.copyOf(payload);
        correlationParameters = correlationParameters == null ? List.of() : List.copyOf(correlationParameters);
    }

    public long ttlOrDefault() {
        return ttlSeconds == null || ttlSeconds <= 0 ? 86_400 : ttlSeconds;
    }

    /** A typed payload field: {@code string, integer, long, double, boolean, json, date}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Field(@JsonProperty("name") String name, @JsonProperty("type") String type,
                        @JsonProperty("scope") String scope, @JsonProperty("required") Boolean required) {
        public boolean local() {
            return "local".equalsIgnoreCase(scope);
        }

        public boolean isRequired() {
            return Boolean.TRUE.equals(required);
        }
    }
}
