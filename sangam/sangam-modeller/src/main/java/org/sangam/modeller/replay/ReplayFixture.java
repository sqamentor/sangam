// SPDX-License-Identifier: Apache-2.0
package org.sangam.modeller.replay;

import java.util.List;
import java.util.Map;

/**
 * M-136 (owner decision 151): one replay fixture, schema {@value #SCHEMA_VERSION}. A fixture is everything a replay
 * needs and nothing it may take from the live system: the models (inline content or immutable references, always with
 * their SHA-256), the initial state, the configuration, the tenant context, the engine and evaluator versions, every
 * captured nondeterministic input in capture order, and the observed outcome the replay is compared with.
 *
 * <p>JSON form: {@code src/main/resources/org/sangam/modeller/replay/replay-fixture-1.schema.json}; read and checked
 * by {@link ReplayFixtures}. The engine side (W4-E) writes fixtures; this module reads, replays (through
 * {@link ReplayDriver}) and compares them.
 *
 * <p>Value semantics: every variable, payload and output value is a {@link Value} with an explicit type. Decimals are
 * exact decimal strings (never binary floating point). Timestamps are either {@code INSTANT} (ISO-8601 UTC with
 * {@code Z}) or {@code LOCAL_DATE_TIME}/{@code LOCAL_DATE} business values, which carry the zone they belong to.
 *
 * @param schemaVersion  must be {@value #SCHEMA_VERSION}; any other value is refused (INCOMPATIBLE_VERSION)
 * @param fixtureId      stable id of this fixture
 * @param scenario       human description of the scenario
 * @param synthetic      true for synthetic data (the default and the only kind shipped with the product)
 * @param models         the deployed models the scenario ran on
 * @param initialState   how the scenario starts
 * @param configuration  engine and module settings that influence behaviour (key to value, as text)
 * @param tenant         tenant, actor and zone context
 * @param versions       engine, evaluator and product versions at capture
 * @param startedAt      the controlled clock's value when the scenario starts (INSTANT text)
 * @param events         captured nondeterministic inputs, in capture order ({@link Event#seq} strictly increasing)
 * @param observed       what the captured run produced
 * @param unsupported    nondeterminism the capture could neither record nor control; a non-empty list makes the
 *                       scenario unsupported for replay (refused, never replayed approximately)
 */
public record ReplayFixture(String schemaVersion, String fixtureId, String scenario, boolean synthetic,
                            List<ModelRef> models, InitialState initialState, Map<String, String> configuration,
                            TenantContext tenant, Versions versions, String startedAt, List<Event> events,
                            Observation observed, List<String> unsupported) {

    /** The only schema version this code reads. */
    public static final String SCHEMA_VERSION = "sangam.replay-fixture/1";

    public ReplayFixture {
        models = models == null ? List.of() : List.copyOf(models);
        configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
        events = events == null ? List.of() : List.copyOf(events);
        unsupported = unsupported == null ? List.of() : List.copyOf(unsupported);
    }

    /** Kinds of model a fixture can reference. */
    public enum ModelKind { BPMN, DMN, CMMN, FORM }

    /**
     * A model the scenario ran on: inline {@code content}, or an immutable {@code reference} (for example a frozen
     * package hash and resource name). The SHA-256 is always present and checked against inline content.
     *
     * @param resource      resource name
     * @param kind          model kind
     * @param definitionKey process, decision or case key
     * @param version       the definition version the scenario ran on (never resolved to the latest at replay)
     * @param sha256        lower-case hex SHA-256 of the model bytes (UTF-8 for inline content)
     * @param content       the model text, or null when {@code reference} is given
     * @param reference     an immutable reference, or null when {@code content} is given
     */
    public record ModelRef(String resource, ModelKind kind, String definitionKey, Integer version, String sha256,
                           String content, String reference) {
    }

    /** Value types; see the class comment for timestamp and decimal semantics. */
    public enum ValueType { NULL, STRING, BOOLEAN, LONG, DECIMAL, INSTANT, LOCAL_DATE, LOCAL_DATE_TIME, JSON, BYTES_SHA256 }

    /**
     * A typed value in text form.
     *
     * @param type  the type
     * @param value the canonical text (null only for NULL): DECIMAL the canonical {@code BigDecimal.toString()} text
     *              (engine decimal text v1, at most 1000 characters, e.g. {@code 0.10} or {@code 1E+3}), INSTANT
     *              ISO-8601 UTC ending in Z, LOCAL_DATE yyyy-MM-dd, LOCAL_DATE_TIME ISO local date-time, JSON canonical JSON text,
     *              BYTES_SHA256 the hex hash of binary content (the bytes themselves are not kept)
     * @param zone  the zone id a LOCAL_DATE or LOCAL_DATE_TIME belongs to (required for those, else null)
     */
    public record Value(ValueType type, String value, String zone) {
        public static Value of(ValueType type, String value) {
            return new Value(type, value, null);
        }
    }

    /**
     * The start of the scenario.
     *
     * @param definitionKey process (or case) definition key started
     * @param businessKey   business key, may be null
     * @param variables     start variables
     */
    public record InitialState(String definitionKey, String businessKey, Map<String, Value> variables) {
        public InitialState {
            variables = variables == null ? Map.of() : Map.copyOf(variables);
        }
    }

    /**
     * Tenant context of the run.
     *
     * @param tenantId the tenant (synthetic)
     * @param actorId  the starting actor (synthetic)
     * @param zoneId   the tenant's business zone (IANA id), used only for local business values
     */
    public record TenantContext(String tenantId, String actorId, String zoneId) {
    }

    /**
     * Versions at capture.
     *
     * @param engine    engine name and version (for example {@code operaton 2.2.0})
     * @param evaluator FEEL/DMN evaluator name and version
     * @param product   Sangam version
     */
    public record Versions(String engine, String evaluator, String product) {
    }

    /** Captured input kinds. The first five are required capture in 0.1.0; the rest are other controlled sources. */
    public enum EventType {
        /** A FEEL {@code now()}/{@code today()} or other engine clock read: {@code at} is the value read. */
        CLOCK_READ,
        /** A timer was scheduled: payload {@code dueAt} (INSTANT), {@code timerId}. */
        TIMER_SCHEDULED,
        /** A timer fired: {@code at} is the controlled clock time of firing, payload {@code timerId}. */
        TIMER_FIRED,
        /** A message was delivered: payload {@code messageName}, {@code correlationKey}, message variables. */
        MESSAGE_DELIVERED,
        /** An external-task attempt ended: {@code outcome} and payload (result variables or error). */
        EXTERNAL_TASK_RESULT,
        /** A random value the process used: payload {@code value}. */
        RANDOM_VALUE,
        /** A generated id (instance, execution, business number): payload {@code value}. */
        GENERATED_ID,
        /** An external lookup answer (connector read): payload {@code request}, {@code response}. */
        EXTERNAL_LOOKUP
    }

    /** End of an external-task attempt. */
    public enum TaskOutcome { SUCCESS, FAILURE, BPMN_ERROR, TIMEOUT }

    /**
     * One captured input, tied to the execution, activity and attempt that consumed it.
     *
     * @param seq         capture order, strictly increasing within the fixture
     * @param type        what was captured
     * @param executionId the consuming execution (as generated in the captured run)
     * @param activityId  the consuming activity, or null for a process-level read
     * @param attempt     attempt number, 1 for the first (retries count up)
     * @param at          controlled clock time of the event (INSTANT text)
     * @param outcome     for EXTERNAL_TASK_RESULT only, else null
     * @param retriesLeft for EXTERNAL_TASK_RESULT failures: the retries the worker reported, else null
     * @param payload     typed values of the event
     * @param activityInstanceId the consuming activity instance as generated in the captured run, or null; recorded for
     *                    diagnostics only and never compared (instance ids differ between runs)
     */
    public record Event(long seq, EventType type, String executionId, String activityId, int attempt, String at,
                        TaskOutcome outcome, Integer retriesLeft, Map<String, Value> payload,
                        String activityInstanceId) {
        public Event {
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }

    /** A state transition of one activity instance. */
    public enum TransitionKind { STARTED, COMPLETED, CANCELLED, FAILED }

    /**
     * @param executionId the execution
     * @param activityId  the activity
     * @param kind        what happened
     */
    public record Transition(String executionId, String activityId, TransitionKind kind) {
    }

    /**
     * A decision evaluation.
     *
     * @param activityId    the business-rule task, or null for a direct evaluation
     * @param decisionKey   decision key
     * @param inputs        evaluated inputs
     * @param matchedRules  matched rule ids in order
     * @param outputs       result entries
     */
    public record Decision(String activityId, String decisionKey, Map<String, Value> inputs, List<String> matchedRules,
                           List<Map<String, Value>> outputs) {
        public Decision {
            inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
            matchedRules = matchedRules == null ? List.of() : List.copyOf(matchedRules);
            outputs = outputs == null ? List.of() : outputs.stream().map(Map::copyOf).toList();
        }
    }

    /**
     * A business deadline as computed by the run.
     *
     * @param activityId the activity (timer, task due date, SLA)
     * @param kind       TIMER, DUE_DATE, FOLLOW_UP or SLA
     * @param dueAt      INSTANT text
     */
    public record Deadline(String activityId, String kind, String dueAt) {
    }

    /**
     * An effect the run asked for (never performed during replay): a message, a payment, a notification, a connector
     * write. Compared as an intent, not executed.
     *
     * @param activityId     the requesting activity
     * @param channel        topic, connector or message name
     * @param idempotencyKey the key the intent carried, may be null
     * @param payloadSha256  hex SHA-256 of the canonical payload
     */
    public record SideEffectIntent(String activityId, String channel, String idempotencyKey, String payloadSha256) {
    }

    /**
     * What a run produced: compared field by field.
     *
     * @param endState    ACTIVE, COMPLETED, TERMINATED or INCIDENT
     * @param outputs     final process variables
     * @param transitions activity transitions in order
     * @param decisions   decision evaluations in order
     * @param deadlines   deadlines in order of computation
     * @param sideEffects side-effect intents in order
     */
    public record Observation(String endState, Map<String, Value> outputs, List<Transition> transitions,
                              List<Decision> decisions, List<Deadline> deadlines, List<SideEffectIntent> sideEffects) {
        public Observation {
            outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
            transitions = transitions == null ? List.of() : List.copyOf(transitions);
            decisions = decisions == null ? List.of() : List.copyOf(decisions);
            deadlines = deadlines == null ? List.of() : List.copyOf(deadlines);
            sideEffects = sideEffects == null ? List.of() : List.copyOf(sideEffects);
        }
    }
}
