// SPDX-License-Identifier: Apache-2.0
package org.sangam.core.layer;

/**
 * The one operator repair contract for a PARKED outbox delivery (VI.2.3 rule 5). Every caller (the operator API, a
 * notification resend, a future tool) repairs through the implementation the deployment wires, so whatever the
 * deployment keeps alongside a parked delivery (its dead letter, metrics) stays consistent with the delivery itself.
 * {@link Relay} is the plain implementation; the production relay service decorates it.
 */
public interface OutboxRepair {

    /** A PARKED delivery goes back to PENDING with a fresh attempt budget. Audited. False when it is not PARKED. */
    boolean requeue(long outboxId, String destination, String actor, String reason);

    /** A PARKED delivery is SKIPPED with a reason, unblocking its line. Audited. False when it is not PARKED. */
    boolean skip(long outboxId, String destination, String actor, String reason);
}
