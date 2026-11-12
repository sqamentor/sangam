// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.inbox;

import jakarta.jms.BytesMessage;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Destination;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.sangam.integration.relay.BackpressureGate;
import org.sangam.integration.relay.TenantAdmission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * JMS → inbox (R38 rule 1, FLOWABLE-INTO-OPERATON FC-03 inbound JMS channel). Optional: needs {@code jakarta.jms-api}.
 *
 * <p>Transacted session: for each message, {@link InboxService#receive} commits to the database first, then the JMS
 * session commits (ack). On back-pressure or an inbox failure the session rolls back, so the broker redelivers. A
 * message the mapper rejects is committed (dropped) and logged. Message id: CloudEvents id property if present, else
 * {@code JMSMessageID}.
 *
 * <p>M-008 (ADR-D-21, IR-4/IR-6): a message of a tenant that the {@link TenantAdmission} answers REDELIVER for (a cell
 * tenant that is FROZEN or IMPORTING) takes the same path as back-pressure: the session rolls back and the broker
 * redelivers it later. SL-142: a message of a tenant answered PARK for (MOVED or PURGED: it is never taken here again)
 * is parked through {@link PoisonInbound#park} (dead letter plus reconciliation item, for an operator replay to the
 * tenant's new cell) and then the session commits; if the park write fails, or no dead-letter store is configured, the
 * session rolls back. A message is never dropped.
 */
public final class JmsInboxListener implements Runnable, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(JmsInboxListener.class);

    private final ConnectionFactory factory;
    private final Function<Session, Destination> destination;
    private final ChannelBinding binding;
    private final InboundMapper<Message> mapper;
    private final InboxService inbox;
    private final PoisonInbound poison;
    private final TenantAdmission admission;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public JmsInboxListener(ConnectionFactory factory, Function<Session, Destination> destination, ChannelBinding binding,
                            InboundMapper<Message> mapper, InboxService inbox) {
        this(factory, destination, binding, mapper, inbox, null);
    }

    /** @param poisonOrNull dead-letter path for messages that can never be stored (null: log and drop) */
    public JmsInboxListener(ConnectionFactory factory, Function<Session, Destination> destination, ChannelBinding binding,
                            InboundMapper<Message> mapper, InboxService inbox, PoisonInbound poisonOrNull) {
        this(factory, destination, binding, mapper, inbox, poisonOrNull, null);
    }

    /**
     * @param poisonOrNull    dead-letter path for messages that can never be stored (null: log and drop)
     * @param admissionOrNull per-tenant admission (M-008); null admits every tenant. A PARK decision (SL-142) needs
     *                        {@code poisonOrNull}; without it the session rolls back
     */
    public JmsInboxListener(ConnectionFactory factory, Function<Session, Destination> destination, ChannelBinding binding,
                            InboundMapper<Message> mapper, InboxService inbox, PoisonInbound poisonOrNull,
                            TenantAdmission admissionOrNull) {
        this.poison = poisonOrNull;
        this.factory = factory;
        this.destination = destination;
        this.binding = binding;
        this.mapper = mapper;
        this.inbox = inbox;
        this.admission = admissionOrNull == null ? TenantAdmission.ALL : admissionOrNull;
    }

    /** CloudEvents properties, falling back to JMSMessageID / JMSCorrelationID. */
    public static InboundMapper<Message> cloudEventsOrJmsId(String fallbackEventType) {
        InboundMapper<Message> ce = InboundMapper.cloudEvents(fallbackEventType);
        return (m, headers, body) -> {
            if (InboundMapper.ce(headers, "id") != null) {
                return ce.map(m, headers, body);
            }
            try {
                return new InboundMessage(m.getJMSMessageID(), fallbackEventType, m.getJMSCorrelationID(), null, body);
            } catch (JMSException e) {
                throw new IllegalArgumentException(e);
            }
        };
    }

    @Override
    public void run() {
        while (running.get()) {
            try (Connection c = factory.createConnection()) {
                Session s = c.createSession(true, Session.SESSION_TRANSACTED);
                MessageConsumer consumer = s.createConsumer(destination.apply(s));
                c.start();
                while (running.get()) {
                    Message m = consumer.receive(1000);
                    if (m == null) {
                        continue;
                    }
                    if (store(m)) {
                        s.commit();
                    } else {
                        s.rollback();
                        Thread.sleep(1000);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (JMSException e) {
                LOG.warn("JMS inbox connection failed; reconnecting", e);
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** True when the message is stored (or deliberately dropped) and the session may commit (ack). */
    boolean store(Message m) throws JMSException {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<?> names = m.getPropertyNames();
        while (names.hasMoreElements()) {
            String n = String.valueOf(names.nextElement());
            headers.put(n, m.getStringProperty(n));
        }
        String jmsId = m.getJMSMessageID();
        String body;
        try {
            body = bodyOf(m);
        } catch (JMSException | RuntimeException e) {
            return poison(jmsId, null, null, "unreadable JMS body (" + m.getClass().getSimpleName() + "): " + e.getMessage());
        }
        InboundMessage im;
        try {
            im = mapper.map(m, headers, body);
        } catch (RuntimeException e) {
            return poison(jmsId, null, body, "unmappable message: " + e.getMessage());
        }
        if (im == null) {
            return true;
        }
        Admission a = admissionOf(im);
        if (a.decision().action() == TenantAdmission.Action.REDELIVER) {
            LOG.debug("JMS message {}: tenant not admitted (cell tenant state); rolled back for redelivery", jmsId);
            return false;
        }
        if (a.decision().action() == TenantAdmission.Action.PARK) {
            return park(a.tenant(), im, a.decision(), jmsId);
        }
        try {
            inbox.receive(binding, im);
            return true;
        } catch (PoisonInbound.PoisonMessageException e) {
            return poison(im.messageId(), im.eventType(), body, e.getMessage());
        } catch (BackpressureGate.BackpressureException e) {
            return false;
        } catch (SecurityException e) {
            LOG.warn("dropping JMS message {} with a foreign tenant: {}", m.getJMSMessageID(), e.getMessage());
            return true;
        } catch (RuntimeException e) {
            LOG.warn("inbox unavailable; JMS message {} will be redelivered", m.getJMSMessageID(), e);
            return false;
        }
    }

    /** The resolved tenant and the admission's decision for it. */
    private record Admission(String tenant, TenantAdmission.Decision decision) {
    }

    /**
     * M-008: the admission decision for the message's tenant now. The tenant is resolved from the binding exactly as
     * {@link InboxService#receive} does; a claim the binding refuses is left to {@code receive}, which refuses it as
     * before (ADMIT here). A failing or empty admission check counts as REDELIVER (never drop).
     */
    private Admission admissionOf(InboundMessage m) {
        String tenant;
        try {
            tenant = binding.tenantFor(m.claimedTenant());
        } catch (SecurityException e) {
            return new Admission(null, TenantAdmission.Decision.ADMIT);
        }
        try {
            TenantAdmission.Decision d = admission.decide(tenant);
            return new Admission(tenant, d == null ? TenantAdmission.Decision.REDELIVER : d);
        } catch (RuntimeException e) {
            LOG.warn("tenant admission check for {} failed; the message is redelivered later: {}", tenant, e.toString());
            return new Admission(tenant, TenantAdmission.Decision.REDELIVER);
        }
    }

    /**
     * SL-142: a message of a tenant that left this cell for good (MOVED, PURGED) is parked (dead letter plus
     * reconciliation item) and then acked (session commit). Without a dead-letter store, or when the park write fails,
     * the session rolls back and the broker redelivers it: never dropped.
     */
    private boolean park(String tenant, InboundMessage m, TenantAdmission.Decision d, String jmsId) {
        if (poison == null) {
            LOG.warn("JMS message {}: tenant {} is {} but no dead-letter store is configured; rolled back", jmsId, tenant,
                    d.tenantState());
            return false;
        }
        long item = poison.park(binding, tenant, m, d);
        if (item < 0) {
            return false;
        }
        LOG.info("JMS message {} of tenant {} ({}) parked (reconciliation item {}); acked", m.messageId(), tenant,
                d.tenantState(), item);
        return true;
    }

    /** Text, or a bytes message decoded as UTF-8; other message types are poison. */
    private static String bodyOf(Message m) throws JMSException {
        if (m instanceof TextMessage t) {
            return t.getText();
        }
        if (m instanceof BytesMessage b) {
            byte[] data = new byte[(int) Math.min(Integer.MAX_VALUE, b.getBodyLength())];
            b.readBytes(data);
            return new String(data, StandardCharsets.UTF_8);
        }
        return m.getBody(String.class);
    }

    /** Poison: dead letter + POISON_INBOUND item, then ack (session commit); rolled back if even that write failed. */
    private boolean poison(String messageId, String eventType, String body, String reason) {
        if (poison == null) {
            LOG.warn("dropping poison JMS message {} (no dead-letter store configured): {}", messageId, reason);
            return true;
        }
        long item = poison.record(binding, messageId, eventType, body, reason);
        if (item < 0) {
            return false;
        }
        LOG.warn("poison JMS message {} dead-lettered (reconciliation item {}): {}", messageId, item, reason);
        return true;
    }

    @Override
    public void close() {
        running.set(false);
    }
}
