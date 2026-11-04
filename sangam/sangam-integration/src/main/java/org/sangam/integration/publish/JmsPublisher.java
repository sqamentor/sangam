// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.publish;

import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.DeliveryMode;
import jakarta.jms.Destination;
import jakarta.jms.InvalidDestinationException;
import jakarta.jms.JMSException;
import jakarta.jms.JMSSecurityException;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Publisher;
import org.sangam.integration.relay.PermanentPublishException;

import java.util.Map;
import java.util.function.Function;

/**
 * JMS 3.x publisher (R71 "AMQP/JMS"; Flowable FC-03 JMS channel). Optional: needs {@code jakarta.jms-api} and a broker
 * client (ActiveMQ Artemis, IBM MQ, ...) supplying the {@link ConnectionFactory}.
 *
 * <ul>
 *   <li>Persistent delivery in a <b>transacted</b> session; the relay marks DONE only after {@code commit()} returned.</li>
 *   <li>{@code JMSXGroupID = tenant:rootId} keeps per-root order on brokers that support message groups.</li>
 *   <li>CloudEvents JMS mapping: properties {@code cloudEvents_<attr>} (dots are illegal in JMS property names);
 *       {@code JMSCorrelationID} = event id for consumer dedup.</li>
 *   <li>Invalid destination and security errors → {@link PermanentPublishException}.</li>
 * </ul>
 * One connection is shared; sessions are per call (sessions are not thread safe).
 */
public final class JmsPublisher implements Publisher, AutoCloseable {

    /** destination name → JMS destination factory. */
    public interface DestinationResolver {
        Destination resolve(Session session, String destination) throws JMSException;

        static DestinationResolver queues(Function<String, String> nameOf) {
            return (s, d) -> s.createQueue(nameOf.apply(d));
        }

        static DestinationResolver topics(Function<String, String> nameOf) {
            return (s, d) -> s.createTopic(nameOf.apply(d));
        }
    }

    private final ConnectionFactory factory;
    private final DestinationResolver resolver;
    private final String source;
    private volatile Connection connection;

    public JmsPublisher(ConnectionFactory factory, DestinationResolver resolver, String source) {
        this.factory = factory;
        this.resolver = resolver;
        this.source = source;
    }

    private synchronized Connection connection() throws JMSException {
        if (connection == null) {
            Connection c = factory.createConnection();
            c.setExceptionListener(e -> reset());
            c.start();
            connection = c;
        }
        return connection;
    }

    private synchronized void reset() {
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (JMSException ignored) {
            // reconnect on next publish
        }
        connection = null;
    }

    @Override
    public void publish(String destination, Outbox.OutboxEvent event) throws Exception {
        Session session = null;
        try {
            session = connection().createSession(true, Session.SESSION_TRANSACTED);
            Destination d = resolver.resolve(session, destination);
            MessageProducer producer = session.createProducer(d);
            producer.setDeliveryMode(DeliveryMode.PERSISTENT);
            TextMessage m = session.createTextMessage(event.payload());
            m.setJMSCorrelationID(event.eventId().toString());
            m.setStringProperty("JMSXGroupID", event.tenantId() + ":" + event.rootId());
            for (Map.Entry<String, String> a : CloudEvents.attributes(source, destination, event).entrySet()) {
                m.setStringProperty("cloudEvents_" + a.getKey(), a.getValue());
            }
            producer.send(m);
            session.commit();
        } catch (InvalidDestinationException | JMSSecurityException e) {
            throw new PermanentPublishException("JMS rejected event for " + destination + ": " + e.getMessage(), e);
        } catch (JMSException e) {
            reset();
            throw e;
        } finally {
            if (session != null) {
                try {
                    session.close();
                } catch (JMSException ignored) {
                    // closing
                }
            }
        }
    }

    @Override
    public void close() {
        reset();
    }
}
