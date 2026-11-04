// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.channel;

import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Destination;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSException;
import jakarta.jms.JMSProducer;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.sangam.ports.events.InboundEventProcessor;
import org.sangam.ports.events.model.ChannelDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * JMS channel adapters (optional dependency {@code jakarta.jms:jakarta.jms-api}; the provider, e.g. ActiveMQ
 * Artemis, is chosen by the deployment and supplied as a named {@link ConnectionFactory} through
 * {@link ChannelAdapters.Environment#jmsConnectionFactory}, property {@code connectionFactory}).
 *
 * <p>Inbound: {@code CLIENT_ACKNOWLEDGE}; a message is acknowledged after it is stored in the inbox, otherwise the
 * session is recovered and the broker redelivers. Outbound: one {@code JMSContext} per send (use a pooling
 * factory), persistent delivery, {@code JMSCorrelationID} = message id.
 */
final class JmsAdapters {

    private static final Logger LOG = LoggerFactory.getLogger(JmsAdapters.class);

    private JmsAdapters() {
    }

    static ConnectionFactory factory(ChannelDefinition ch, ChannelAdapters.Environment env) {
        String name = ch.property("connectionFactory", "default");
        Object cf = env.jmsConnectionFactory(name)
                .orElseThrow(() -> new IllegalStateException("jms channel " + ch.key() + ": connection factory '" + name + "' not provided"));
        if (!(cf instanceof ConnectionFactory f)) {
            throw new IllegalStateException("jms connection factory '" + name + "' is not a jakarta.jms.ConnectionFactory");
        }
        return f;
    }

    static boolean isTopic(ChannelDefinition ch) {
        return "topic".equalsIgnoreCase(ch.property("destinationType", "queue"));
    }

    static String destinationName(ChannelDefinition ch) {
        String d = ch.destination() != null ? ch.destination() : ch.queue();
        if (d == null) {
            throw new IllegalArgumentException("jms channel " + ch.key() + " needs destination");
        }
        return d;
    }

    static final class Outbound implements ChannelAdapters.OutboundChannelAdapter {
        private final ChannelDefinition ch;
        private final ConnectionFactory cf;

        Outbound(ChannelDefinition ch, ChannelAdapters.Environment env) {
            this.ch = ch;
            this.cf = factory(ch, env);
            destinationName(ch);
        }

        @Override
        public void send(String tenantId, String eventKey, String messageId, String body) {
            try (JMSContext ctx = cf.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
                Destination d = isTopic(ch) ? ctx.createTopic(destinationName(ch)) : ctx.createQueue(destinationName(ch));
                JMSProducer p = ctx.createProducer().setJMSCorrelationID(messageId)
                        .setProperty("messageId", messageId).setProperty("eventKey", eventKey);
                if (tenantId != null) {
                    p.setProperty("tenantId", tenantId);
                }
                p.send(d, body);
            }
        }
    }

    static final class Inbound implements ChannelAdapters.InboundChannelAdapter {
        private final ChannelDefinition ch;
        private final String tenant;
        private final InboundEventProcessor processor;
        private final ConnectionFactory cf;
        private Connection connection;

        Inbound(ChannelDefinition ch, String tenant, InboundEventProcessor processor, ChannelAdapters.Environment env) {
            this.ch = ch;
            this.tenant = tenant;
            this.processor = processor;
            this.cf = factory(ch, env);
        }

        @Override
        public synchronized void start() {
            try {
                connection = cf.createConnection();
                Session session = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE);
                Destination d = isTopic(ch) ? session.createTopic(destinationName(ch)) : session.createQueue(destinationName(ch));
                MessageConsumer consumer = ch.properties().containsKey("subscriptionName") && isTopic(ch)
                        ? session.createSharedDurableConsumer((jakarta.jms.Topic) d, ch.properties().get("subscriptionName"))
                        : session.createConsumer(d);
                consumer.setMessageListener(m -> onMessage(session, m));
                connection.start();
            } catch (JMSException e) {
                throw new IllegalStateException("jms channel " + ch.key() + " cannot start", e);
            }
        }

        private void onMessage(Session session, Message m) {
            try {
                String body = m instanceof TextMessage t ? t.getText() : m.getBody(String.class);
                Map<String, String> headers = new HashMap<>();
                Enumeration<?> names = m.getPropertyNames();
                while (names.hasMoreElements()) {
                    String n = (String) names.nextElement();
                    headers.put(n, String.valueOf(m.getObjectProperty(n)));
                }
                String id = headers.containsKey("messageId") ? headers.get("messageId") : m.getJMSMessageID();
                processor.receive(tenant, ch.key(), body, headers, id);
                m.acknowledge();
            } catch (Exception e) {
                LOG.warn("jms channel {}: message not stored, recovering session: {}", ch.key(), e.toString());
                try {
                    session.recover();
                } catch (JMSException ex) {
                    LOG.warn("jms channel {}: recover failed: {}", ch.key(), ex.toString());
                }
            }
        }

        @Override
        public synchronized void close() {
            if (connection != null) {
                try {
                    connection.close();
                } catch (JMSException e) {
                    LOG.warn("jms channel {}: close failed: {}", ch.key(), e.toString());
                }
                connection = null;
            }
        }
    }
}
