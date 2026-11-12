// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.worker;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.sangam.sdk.model.TypedValue;

/**
 * Saga and compensation worker helper (M-026, §3.2, R36): registers one forward action and its compensation as two
 * external-task handlers, and carries the forward action's identity to the compensation.
 *
 * <p>In the model, the forward step is a service task with topic {@code actionTopic}, and its compensation handler
 * (attached through a compensation boundary event, {@code isForCompensation="true"}) is a service task with topic
 * {@code compensationTopic}. When the forward action completes, this helper adds the process variable
 * {@link #recordVariable(String)} holding a {@link SagaRecord} (the forward operationId, provider reference and work
 * id). When the engine compensates, the compensation handler gets that record in its {@link CompensationContext} and
 * reuses the forward operationId: the undo call's idempotency key is derived from it, so compensating the same
 * forward action twice undoes it once.
 *
 * <p>One record per step name and process scope: a step repeated in a loop or multi-instance body needs its own
 * name per iteration, or a compensation that reads the iteration's data from local variables.
 *
 * <pre>{@code
 * Saga.step("charge")
 *     .action("payment.charge", ctx -> Map.of("chargeId", charge(ctx)))
 *     .compensation("payment.refund", c -> { refund(c.forwardProviderRef(), c.idempotencyKey()); return Map.of(); })
 *     .build()
 *     .registerOn(worker);
 * }</pre>
 */
public final class Saga {

    /** Prefix of the record variables. */
    public static final String RECORD_PREFIX = "sangamSaga_";

    private final String step;
    private final TopicSubscription action;
    private final TopicSubscription compensation;

    private Saga(String step, TopicSubscription action, TopicSubscription compensation) {
        this.step = step;
        this.action = action;
        this.compensation = compensation;
    }

    /** Starts a saga step named {@code name} (letters, digits, '.', '-', '_'; at most 64 characters). */
    public static Builder step(String name) {
        return new Builder(name);
    }

    /** The process variable that holds the record of step {@code name}: {@code sangamSaga_<name>}. */
    public static String recordVariable(String name) {
        return RECORD_PREFIX + name.replace('.', '_').replace('-', '_');
    }

    public String name() { return step; }

    /** The forward action's subscription. */
    public TopicSubscription action() { return action; }

    /** The compensation's subscription. */
    public TopicSubscription compensation() { return compensation; }

    /** Both subscriptions, forward action first. */
    public List<TopicSubscription> subscriptions() { return List.of(action, compensation); }

    /** Subscribes both handlers on {@code worker}. */
    public SangamWorker registerOn(SangamWorker worker) {
        worker.subscribe(action);
        return worker.subscribe(compensation);
    }

    /** Subscribes both handlers on a worker being built. */
    public SangamWorker.Builder registerOn(SangamWorker.Builder builder) {
        return builder.subscribe(action).subscribe(compensation);
    }

    static Map<String, Object> withRecord(String step, TaskContext ctx, Map<String, Object> vars) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (vars != null) {
            out.putAll(vars);
        }
        SagaRecord r = new SagaRecord(step, ctx.operationId(), ctx.outcomeReference(), ctx.workId());
        out.put(recordVariable(step), TypedValue.json(r.toMap()));
        return out;
    }

    static SagaRecord recordOf(String step, TaskContext ctx) {
        Object raw = ctx.variables().get(recordVariable(step));
        if (raw == null) {
            return null;
        }
        Map<?, ?> m = TypedValue.unwrap(raw, Map.class, ctx.json());
        return SagaRecord.fromMap(m);
    }

    public static final class Builder {
        private final String name;
        private String actionTopic;
        private SagaAction actionHandler;
        private String compensationTopic;
        private SagaCompensation compensationHandler;
        private Duration lockDuration = Duration.ofMinutes(5);
        private int retries = 3;
        private boolean irreversible;
        private List<String> variables;

        Builder(String name) {
            Objects.requireNonNull(name, "name");
            if (!name.matches("[A-Za-z0-9._-]{1,64}")) {
                throw new IllegalArgumentException("saga step name must be 1-64 of [A-Za-z0-9._-]: " + name);
            }
            this.name = name;
        }

        /** The forward action: topic and handler. */
        public Builder action(String topic, SagaAction handler) {
            this.actionTopic = Objects.requireNonNull(topic, "topic");
            this.actionHandler = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /** The compensation: topic and handler. */
        public Builder compensation(String topic, SagaCompensation handler) {
            this.compensationTopic = Objects.requireNonNull(topic, "topic");
            this.compensationHandler = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /** Lease per fetch for both topics (default 5 min). */
        public Builder lockDuration(Duration v) { this.lockDuration = v; return this; }

        /** Retries for the first failure of either handler (default 3). */
        public Builder retries(int v) { this.retries = v; return this; }

        /** The forward action has an irreversible effect (VI.3.2 rule 5): unexpected errors become outcome-unknown. */
        public Builder irreversible(boolean v) { this.irreversible = v; return this; }

        /** Variables to fetch for both handlers (null = all); the record variable is always added. */
        public Builder variables(List<String> v) { this.variables = v; return this; }

        public Saga build() {
            if (actionHandler == null || compensationHandler == null) {
                throw new IllegalStateException("saga step " + name + " needs an action and a compensation");
            }
            if (actionTopic.equals(compensationTopic)) {
                throw new IllegalStateException("saga step " + name + ": action and compensation need their own topics");
            }
            String step = name;
            SagaAction a = actionHandler;
            SagaCompensation comp = compensationHandler;
            TopicSubscription forward = TopicSubscription.topic(actionTopic, ctx -> withRecord(step, ctx, a.run(ctx)))
                    .lockDuration(lockDuration).retries(retries).irreversible(irreversible)
                    .variables(variables).build();
            List<String> compVars = null;
            if (variables != null) {
                compVars = new ArrayList<>(variables);
                compVars.add(recordVariable(step));
            }
            TopicSubscription undo = TopicSubscription.topic(compensationTopic,
                            ctx -> comp.compensate(new CompensationContext(ctx, recordOf(step, ctx), step)))
                    .lockDuration(lockDuration).retries(retries).variables(compVars).build();
            return new Saga(step, forward, undo);
        }
    }
}
