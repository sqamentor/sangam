// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.replay;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Decision 151: one input the engine took from outside the model while a scenario ran (shape ACKed by W4-G on the
 * board, 2026-11-13T02:11:44Z). {@code type} and {@code outcome} are the exact names of the modeller's
 * {@code ReplayFixture.EventType} and {@code TaskOutcome}; sangam-engine cannot import them (sangam-modeller depends on
 * the engine), so the names are checked here and the modeller-side adapter maps them to {@code FixtureRecorder.record}.
 *
 * @param type               one of {@link #TYPES}
 * @param executionId        the execution that took the input, or null when none is active (a standalone decision)
 * @param activityId         its activity, or null
 * @param activityInstanceId its activity instance, or null
 * @param attempt            1 for the first attempt; a retried job or task counts up
 * @param at                 when the input was taken, on the engine clock
 * @param outcome            one of {@link #OUTCOMES} for {@code EXTERNAL_TASK_RESULT}, else null
 * @param retriesLeft        for an {@code EXTERNAL_TASK_RESULT} failure, the retries the worker reported, else null
 * @param payload            typed values only: {@link BigDecimal}, {@link Long}, {@link Boolean}, {@link String},
 *                           {@link Instant}, {@link ZonedDateTime} or null; anything else is refused here, so a
 *                           capture point cannot hand the adapter a value the fixture cannot carry
 */
public record CapturedInput(String type, String executionId, String activityId, String activityInstanceId, int attempt,
                            Instant at, String outcome, Integer retriesLeft, Map<String, Object> payload) {

    public static final String CLOCK_READ = "CLOCK_READ";
    public static final String TIMER_SCHEDULED = "TIMER_SCHEDULED";
    public static final String TIMER_FIRED = "TIMER_FIRED";
    public static final String MESSAGE_DELIVERED = "MESSAGE_DELIVERED";
    public static final String EXTERNAL_TASK_RESULT = "EXTERNAL_TASK_RESULT";
    public static final String RANDOM_VALUE = "RANDOM_VALUE";
    public static final String GENERATED_ID = "GENERATED_ID";
    public static final String EXTERNAL_LOOKUP = "EXTERNAL_LOOKUP";

    /** The modeller's {@code ReplayFixture.EventType} names. */
    public static final Set<String> TYPES = Set.of(CLOCK_READ, TIMER_SCHEDULED, TIMER_FIRED, MESSAGE_DELIVERED,
            EXTERNAL_TASK_RESULT, RANDOM_VALUE, GENERATED_ID, EXTERNAL_LOOKUP);
    /** The modeller's {@code ReplayFixture.TaskOutcome} names. */
    public static final Set<String> OUTCOMES = Set.of("SUCCESS", "FAILURE", "BPMN_ERROR", "TIMEOUT");

    public CapturedInput {
        if (type == null || !TYPES.contains(type)) {
            throw new IllegalArgumentException("decision 151: unknown input type " + type + ", expected one of " + TYPES);
        }
        Objects.requireNonNull(at, "at");
        if (attempt < 1) {
            throw new IllegalArgumentException("decision 151: attempt starts at 1: " + attempt);
        }
        if (EXTERNAL_TASK_RESULT.equals(type)) {
            if (outcome == null || !OUTCOMES.contains(outcome)) {
                throw new IllegalArgumentException("decision 151: an external task result needs an outcome of "
                        + OUTCOMES + ": " + outcome);
            }
        } else if (outcome != null || retriesLeft != null) {
            throw new IllegalArgumentException("decision 151: outcome and retriesLeft belong to EXTERNAL_TASK_RESULT only");
        }
        if (retriesLeft != null && retriesLeft < 0) {
            throw new IllegalArgumentException("decision 151: retriesLeft must not be negative: " + retriesLeft);
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        if (payload != null) {
            for (Map.Entry<String, Object> e : payload.entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank()) {
                    throw new IllegalArgumentException("decision 151: a payload name must not be blank");
                }
                Object v = e.getValue();
                if (v != null && !(v instanceof BigDecimal || v instanceof Long || v instanceof Boolean
                        || v instanceof String || v instanceof Instant || v instanceof ZonedDateTime)) {
                    throw new IllegalArgumentException("decision 151: payload '" + e.getKey() + "' is a "
                            + v.getClass().getName() + "; only BigDecimal, Long, Boolean, String, Instant and "
                            + "ZonedDateTime can be replayed");
                }
                copy.put(e.getKey(), v);
            }
        }
        payload = Collections.unmodifiableMap(copy);
    }
}
