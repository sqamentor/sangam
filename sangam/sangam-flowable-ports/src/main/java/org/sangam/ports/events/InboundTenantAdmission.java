// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events;

/**
 * Port: per-tenant admission of a broker message on an event-gateway channel (SL-142, M-008, ADR-D-21 cell tenant
 * states, IR-4/IR-6). {@link InboundEventProcessor#deliver} asks it after the pipeline resolved the tenant and before
 * the inbox write.
 *
 * <p>This module has no decision or parking logic of its own: the server bridges the port onto the integration
 * module's {@code TenantAdmission.decide} (the cell tenant state store) and {@code PoisonInbound.park} (dead letter with
 * the full message, a reconciliation item marked parked, {@code INBOUND_PARKED} audit), so there is one copy of each
 * (owner decision 2026-11-12: MOVED/PURGED park then acknowledge, FROZEN/IMPORTING not acknowledged).
 */
@FunctionalInterface
public interface InboundTenantAdmission {

    /** What became of the message. */
    enum Verdict {
        /** Store it in the inbox and acknowledge the broker (an ACTIVE tenant). */
        ADMIT,
        /** Do not store and do not acknowledge: the broker redelivers it later (FROZEN, IMPORTING, or a failing check). */
        REDELIVER,
        /** Parked durably for an operator replay to the tenant's new cell: acknowledge it (MOVED, PURGED). */
        PARKED
    }

    /**
     * The message as it would be stored: {@code storedEventJson} is the {@link InboundEventProcessor.StoredEvent} JSON
     * (channel, tenant, event key, message id, payload, correlation, business key), so a replay writes exactly the
     * inbox row this cell would have written, under the source {@code evt:<channelKey>}.
     */
    record Message(String channelKey, String source, String messageId, String eventKey, String businessKey,
                   String storedEventJson) {
    }

    /**
     * Decides for {@code tenant} and, for a tenant that left the cell for good, parks {@code message}. Returns
     * {@link Verdict#PARKED} only after the park committed; a failed park is {@link Verdict#REDELIVER}. Must not throw
     * for the message itself; an exception is treated as {@link Verdict#REDELIVER} (never drop).
     */
    Verdict admit(String tenant, Message message);

    /** Admits every tenant (no cell tenant states). */
    InboundTenantAdmission ALL = (tenant, message) -> Verdict.ADMIT;
}
