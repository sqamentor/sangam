// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.relay;

/**
 * Per-tenant admission of inbound broker messages (M-008, ADR-D-21, IR-4/IR-6), next to the capacity-based
 * {@link BackpressureGate}. A broker consumer asks it before it stores a message: when the tenant is not admitted (a
 * cell tenant that is FROZEN, IMPORTING, MOVED or PURGED) the message is <b>not acknowledged</b> and takes the
 * consumer's existing back-pressure path (Kafka: seek back and pause; JMS: roll the transacted session back), so the
 * broker redelivers it later. A message is never dropped and never acked for a tenant that is not admitted.
 *
 * <p>The cell wiring supplies the implementation from its tenant state store; without cells every tenant is admitted
 * ({@link #ALL}).
 */
@FunctionalInterface
public interface TenantAdmission {

    /**
     * True when a message of {@code tenant} may be stored and acknowledged now. False, or an exception, means: do not
     * acknowledge, the broker redelivers it.
     */
    boolean admits(String tenant);

    /** Admits every tenant (no cell tenant states). */
    TenantAdmission ALL = tenant -> true;
}
