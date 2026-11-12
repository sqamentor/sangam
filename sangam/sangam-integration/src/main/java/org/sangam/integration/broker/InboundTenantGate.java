// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.broker;

import java.util.Objects;
import java.util.function.Supplier;

import org.sangam.integration.inbox.ChannelBinding;
import org.sangam.integration.inbox.InboundMessage;
import org.sangam.integration.inbox.PoisonInbound;
import org.sangam.integration.relay.TenantAdmission;
import org.sangam.ports.events.InboundTenantAdmission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The event-gateway channels' tenant admission (SL-142, carry C3-06): bridges the flowable-ports port
 * {@link InboundTenantAdmission} onto the one decision ({@link TenantAdmission#decide}, the cell tenant state store,
 * ADR-D-21) and the one park ({@link PoisonInbound#park}: dead letter with the full message, a reconciliation item
 * marked parked, {@code INBOUND_PARKED} audit). No second copy of either.
 *
 * <ul>
 *   <li>ADMIT: the processor stores the message and the adapter acknowledges it.</li>
 *   <li>REDELIVER (FROZEN, IMPORTING, a failing check): not stored, not acknowledged; the broker offers it again.</li>
 *   <li>PARK (MOVED, PURGED): parked under the resolved tenant, then acknowledged. A park that failed (-1) or a cell
 *       without the poison store answers REDELIVER: a message is never dropped.</li>
 * </ul>
 * The parked payload is the stored-event JSON, so an operator replay writes the inbox row this cell would have written.
 * Binding: source {@code evt:<channel>} (the inbox source of event channels), principal {@code channel:<channel>}.
 */
public final class InboundTenantGate implements InboundTenantAdmission {

    private static final Logger LOG = LoggerFactory.getLogger(InboundTenantGate.class);

    private final Supplier<TenantAdmission> admission;
    private final Supplier<PoisonInbound> poison;

    /**
     * @param admission the cell tenant admission (null result: no cells, every tenant admitted)
     * @param poison    the poison/park store (null result: a PARK decision is redelivered instead)
     */
    public InboundTenantGate(Supplier<TenantAdmission> admission, Supplier<PoisonInbound> poison) {
        this.admission = Objects.requireNonNull(admission, "admission");
        this.poison = Objects.requireNonNull(poison, "poison");
    }

    @Override
    public Verdict admit(String tenant, Message message) {
        TenantAdmission a = admission.get();
        if (a == null) {
            return Verdict.ADMIT;
        }
        TenantAdmission.Decision d;
        try {
            d = a.decide(tenant);
        } catch (RuntimeException e) {
            LOG.warn("tenant admission for {} failed; message {} on channel {} is redelivered: {}", tenant,
                    message.messageId(), message.channelKey(), e.toString());
            return Verdict.REDELIVER;
        }
        if (d == null) {
            return Verdict.REDELIVER;
        }
        return switch (d.action()) {
            case ADMIT -> Verdict.ADMIT;
            case REDELIVER -> Verdict.REDELIVER;
            case PARK -> park(tenant, message, d);
        };
    }

    private Verdict park(String tenant, Message message, TenantAdmission.Decision d) {
        PoisonInbound p = poison.get();
        if (p == null) {
            LOG.warn("tenant {} is {} but this node has no park store; message {} on channel {} is redelivered", tenant,
                    d.tenantState(), message.messageId(), message.channelKey());
            return Verdict.REDELIVER;
        }
        String id = message.messageId();
        if (id == null || id.isEmpty() || id.length() > 255) {
            LOG.warn("message on channel {} for {} tenant {} has no usable message id; redelivered", message.channelKey(),
                    d.tenantState(), tenant);
            return Verdict.REDELIVER;
        }
        String eventKey = message.eventKey() == null ? "" : message.eventKey();
        long item = p.park(ChannelBinding.of(message.source(), tenant, "channel:" + message.channelKey()), tenant,
                new InboundMessage(id, eventKey, message.businessKey(), tenant, message.storedEventJson()), d);
        return item >= 0 ? Verdict.PARKED : Verdict.REDELIVER;
    }
}
