// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.port;

/**
 * RM-30 (decision 386(2)): the host-neutral inbox. A message from an external source is recorded once per
 * {@code (tenant, source, messageId)}; a second delivery of the same id is a duplicate and is not processed again
 * (exactly-once processing on at-least-once delivery). Every host offers the same adapter over the core inbox
 * ({@code org.sangam.rest.adapter.InboxPortAdapter}), so the dedupe rule is the same on each.
 */
public interface InboxPort {

    /** What a delivery did. */
    enum Outcome { ACCEPTED, DUPLICATE }

    /** The outcome and the message's stored state after it ({@code RECEIVED}, {@code PROCESSED}, ...). */
    record Receipt(Outcome outcome, String state) {
    }

    /** Records a delivery for the caller's tenant. */
    Receipt receive(RequestContext ctx, String source, String messageId, String correlationKey, String payload);

    /** The stored state of a message of the caller's tenant, or {@code null} when it was never received. */
    String state(RequestContext ctx, String source, String messageId);
}
