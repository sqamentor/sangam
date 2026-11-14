// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.relay;

import java.util.Objects;

/**
 * Per-tenant admission of inbound broker messages (M-008, ADR-D-21, IR-4/IR-6), next to the capacity-based
 * {@link BackpressureGate}. A broker consumer asks it before it stores a message, and acts on the {@link Decision}:
 *
 * <ul>
 *   <li>{@link Action#ADMIT}: store the message and acknowledge it (an ACTIVE cell tenant).</li>
 *   <li>{@link Action#REDELIVER}: do <b>not</b> acknowledge; the message takes the consumer's existing back-pressure
 *       path (Kafka: seek back and pause; JMS: roll the transacted session back) and the broker redelivers it later
 *       (a tenant that is FROZEN or IMPORTING and will take the message here or after the move).</li>
 *   <li>{@link Action#PARK} (SL-142, owner decision 2026-11-12): the tenant will never take the message in this cell
 *       again (MOVED or PURGED). The consumer parks it (dead letter with the full message plus a reconciliation item,
 *       so an operator can replay it to the tenant's new cell) and only then acknowledges it. If the park write fails
 *       the message is not acknowledged.</li>
 * </ul>
 * A message is never dropped. A failing admission check counts as {@link Action#REDELIVER}.
 *
 * <p>The cell wiring supplies the implementation from its tenant state store; without cells every tenant is admitted
 * ({@link #ALL}). An implementation that only answers {@link #admits} never parks: a tenant it does not admit is
 * redelivered.
 */
@FunctionalInterface
public interface TenantAdmission {

    /** What a broker consumer does with a message of one tenant. */
    enum Action { ADMIT, REDELIVER, PARK }

    /**
     * The answer for one tenant. For {@link Action#PARK}, {@code tenantState} names the reason (for example
     * {@code MOVED}) and {@code targetCellOrNull} / {@code moveIdOrNull} tell the operator where the tenant went.
     */
    record Decision(Action action, String tenantState, String targetCellOrNull, String moveIdOrNull) {

        public static final Decision ADMIT = new Decision(Action.ADMIT, null, null, null);
        public static final Decision REDELIVER = new Decision(Action.REDELIVER, null, null, null);

        public Decision {
            Objects.requireNonNull(action, "action");
            if (action == Action.PARK && (tenantState == null || tenantState.isBlank())) {
                throw new IllegalArgumentException("a PARK decision needs the tenant state it is parked for");
            }
        }

        /** Park, then acknowledge: the tenant left this cell for good ({@code MOVED}, {@code PURGED}). */
        public static Decision park(String tenantState, String targetCellOrNull, String moveIdOrNull) {
            return new Decision(Action.PARK, tenantState, targetCellOrNull, moveIdOrNull);
        }
    }

    /**
     * True when a message of {@code tenant} may be stored and acknowledged now. False, or an exception, means: do not
     * store it here.
     */
    boolean admits(String tenant);

    /**
     * The full decision for {@code tenant}. The default derives it from {@link #admits}: admitted, else redelivered.
     * An implementation that knows a tenant has left the cell for good overrides this to return
     * {@link Decision#park}.
     */
    default Decision decide(String tenant) {
        return admits(tenant) ? Decision.ADMIT : Decision.REDELIVER;
    }

    /** Admits every tenant (no cell tenant states). */
    TenantAdmission ALL = tenant -> true;
}
