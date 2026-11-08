// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events.channel;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSException;
import jakarta.jms.JMSProducer;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageListener;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import jakarta.jms.Topic;

/**
 * In-memory stand-ins for the {@code jakarta.jms} client interfaces (dynamic proxies; no provider, no broker). Records
 * what the adapters do: sent messages with their properties, the registered listener, acknowledgements and session
 * recoveries.
 */
final class JmsStubs {

    private JmsStubs() {
    }

    /** One message the outbound adapter sent. */
    record Sent(String destinationType, String destination, String body, String correlationId, Map<String, Object> properties) {
    }

    /** One fake connection factory (a "broker" endpoint as the deployment would provide it). */
    static final class Factory {
        final List<Sent> sent = new CopyOnWriteArrayList<>();
        final AtomicInteger contextsOpened = new AtomicInteger();
        final AtomicInteger contextsClosed = new AtomicInteger();
        final AtomicInteger connectionsOpened = new AtomicInteger();
        final AtomicInteger connectionsClosed = new AtomicInteger();
        final AtomicInteger recovers = new AtomicInteger();
        final List<String> consumers = new CopyOnWriteArrayList<>();
        volatile MessageListener listener;
        volatile boolean started;
        volatile boolean failConnect;
        volatile boolean failConsumer;

        final ConnectionFactory cf = proxy(ConnectionFactory.class, (m, a) -> switch (m) {
            case "createContext" -> context();
            case "createConnection" -> {
                if (failConnect) {
                    throw new JMSException("connection refused");
                }
                connectionsOpened.incrementAndGet();
                yield connection();
            }
            default -> throw new UnsupportedOperationException(m);
        });

        private JMSContext context() {
            contextsOpened.incrementAndGet();
            return proxy(JMSContext.class, (m, a) -> switch (m) {
                case "createQueue" -> queue((String) a[0]);
                case "createTopic" -> topic((String) a[0]);
                case "createProducer" -> producer();
                case "close" -> {
                    contextsClosed.incrementAndGet();
                    yield null;
                }
                default -> throw new UnsupportedOperationException(m);
            });
        }

        private JMSProducer producer() {
            Map<String, Object> props = new LinkedHashMap<>();
            String[] corr = new String[1];
            JMSProducer[] self = new JMSProducer[1];
            self[0] = proxy(JMSProducer.class, (m, a) -> switch (m) {
                case "setJMSCorrelationID" -> {
                    corr[0] = (String) a[0];
                    yield self[0];
                }
                case "setProperty" -> {
                    props.put((String) a[0], a[1]);
                    yield self[0];
                }
                case "send" -> {
                    Object d = a[0];
                    String type = d instanceof Topic ? "topic" : "queue";
                    String name = d instanceof Topic t ? t.getTopicName() : ((Queue) d).getQueueName();
                    sent.add(new Sent(type, name, (String) a[1], corr[0], Map.copyOf(props)));
                    yield self[0];
                }
                default -> throw new UnsupportedOperationException(m);
            });
            return self[0];
        }

        private Connection connection() {
            return proxy(Connection.class, (m, a) -> switch (m) {
                case "createSession" -> {
                    if ((int) a[1] != Session.CLIENT_ACKNOWLEDGE) {
                        throw new AssertionError("inbound must use CLIENT_ACKNOWLEDGE");
                    }
                    yield session();
                }
                case "start" -> {
                    started = true;
                    yield null;
                }
                case "close" -> {
                    connectionsClosed.incrementAndGet();
                    yield null;
                }
                default -> throw new UnsupportedOperationException(m);
            });
        }

        private Session session() {
            return proxy(Session.class, (m, a) -> switch (m) {
                case "createQueue" -> queue((String) a[0]);
                case "createTopic" -> topic((String) a[0]);
                case "createConsumer" -> {
                    if (failConsumer) {
                        throw new jakarta.jms.InvalidDestinationException("no such queue");
                    }
                    consumers.add("consumer:" + name(a[0]));
                    yield consumer();
                }
                case "createSharedDurableConsumer" -> {
                    consumers.add("shared-durable:" + name(a[0]) + ":" + a[1]);
                    yield consumer();
                }
                case "recover" -> {
                    recovers.incrementAndGet();
                    yield null;
                }
                default -> throw new UnsupportedOperationException(m);
            });
        }

        private MessageConsumer consumer() {
            return proxy(MessageConsumer.class, (m, a) -> {
                if ("setMessageListener".equals(m)) {
                    listener = (MessageListener) a[0];
                    return null;
                }
                throw new UnsupportedOperationException(m);
            });
        }

        private static String name(Object d) throws JMSException {
            return d instanceof Topic t ? "topic/" + t.getTopicName() : "queue/" + ((Queue) d).getQueueName();
        }
    }

    /** An inbound text message; counts acknowledgements. */
    static final class Msg {
        final AtomicInteger acks = new AtomicInteger();
        final TextMessage message;

        Msg(String jmsMessageId, String body, Map<String, Object> properties) {
            Map<String, Object> props = new LinkedHashMap<>(properties);
            message = proxy(TextMessage.class, (m, a) -> switch (m) {
                case "getText" -> body;
                case "getPropertyNames" -> Collections.enumeration(new ArrayList<>(props.keySet()));
                case "getObjectProperty" -> props.get((String) a[0]);
                case "getJMSMessageID" -> jmsMessageId;
                case "acknowledge" -> {
                    acks.incrementAndGet();
                    yield null;
                }
                default -> throw new UnsupportedOperationException(m);
            });
        }
    }

    static Queue queue(String n) {
        return proxy(Queue.class, (m, a) -> switch (m) {
            case "getQueueName", "toString" -> n;
            default -> throw new UnsupportedOperationException(m);
        });
    }

    static Topic topic(String n) {
        return proxy(Topic.class, (m, a) -> switch (m) {
            case "getTopicName", "toString" -> n;
            default -> throw new UnsupportedOperationException(m);
        });
    }

    @FunctionalInterface
    interface Handler {
        Object handle(String method, Object[] args) throws Exception;
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, Handler h) {
        return (T) Proxy.newProxyInstance(JmsStubs.class.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
            if (m.getDeclaringClass() == Object.class) {
                return switch (m.getName()) {
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    default -> type.getSimpleName() + "@stub";
                };
            }
            return h.handle(m.getName(), a == null ? new Object[0] : a);
        });
    }
}
