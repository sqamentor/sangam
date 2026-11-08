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
 */
public final class JmsInboxListener implements Runnable, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(JmsInboxListener.class);

    private final ConnectionFactory factory;
    private final Function<Session, Destination> destination;
    private final ChannelBinding binding;
    private final InboundMapper<Message> mapper;
    private final InboxService inbox;
    private final PoisonInbound poison;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public JmsInboxListener(ConnectionFactory factory, Function<Session, Destination> destination, ChannelBinding binding,
                            InboundMapper<Message> mapper, InboxService inbox) {
        this(factory, destination, binding, mapper, inbox, null);
    }

    /** @param poisonOrNull dead-letter path for messages that can never be stored (null: log and drop) */
    public JmsInboxListener(ConnectionFactory factory, Function<Session, Destination> destination, ChannelBinding binding,
                            InboundMapper<Message> mapper, InboxService inbox, PoisonInbound poisonOrNull) {
        this.poison = poisonOrNull;
        this.factory = factory;
        this.destination = destination;
        this.binding = binding;
        this.mapper = mapper;
        this.inbox = inbox;
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

    private boolean store(Message m) throws JMSException {
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
