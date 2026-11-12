// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.integration.relay.TenantAdmission;

/**
 * M-008: a JMS message of a tenant the admission answers REDELIVER for (a yes/no admission that says no; the cell's
 * FROZEN or IMPORTING) is not stored and the transacted session rolls back (the existing back-pressure path), so the
 * broker redelivers it. Never dropped, never acked. An admitted tenant is stored and committed as before. MOVED and
 * PURGED tenants are parked and then acked (SL-142): see {@link InboxParkThenAckTest}.
 */
class JmsInboxListenerTenantStateTest {

    static final String TENANT = "T1";
    static final ChannelBinding BINDING = ChannelBinding.of("jms:payments", TENANT, "broker");

    H2InboxFixture db;
    final AtomicBoolean frozen = new AtomicBoolean();
    final TenantAdmission admission = tenant -> !frozen.get();

    @BeforeEach
    void setUp() throws Exception {
        db = new H2InboxFixture("jmsadmission");
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    static TextMessage text(String id, String body) {
        return (TextMessage) Proxy.newProxyInstance(JmsInboxListenerTenantStateTest.class.getClassLoader(),
                new Class<?>[]{TextMessage.class}, (p, m, args) -> switch (m.getName()) {
                    case "getPropertyNames" -> Collections.emptyEnumeration();
                    case "getJMSMessageID" -> id;
                    case "getText" -> body;
                    case "toString" -> "TextMessage(" + id + ")";
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == args[0];
                    default -> null;
                });
    }

    JmsInboxListener listener(ConnectionFactory f, TenantAdmission a) {
        return new JmsInboxListener(f, s -> null, BINDING, JmsInboxListener.cloudEventsOrJmsId("PaymentAck"), db.inbox,
                null, a);
    }

    @Test
    void aFrozenTenantsMessageIsNotStoredAndNotAcked() throws Exception {
        frozen.set(true);
        JmsInboxListener l = listener(null, admission);
        assertFalse(l.store(text("ID:1", "{\"amount\":1}")), "not admitted: the session must roll back");
        assertTrue(db.stored(TENANT).isEmpty());
        frozen.set(false);
        assertTrue(l.store(text("ID:1", "{\"amount\":1}")), "the redelivered message is stored once admitted");
        assertEquals(List.of("ID:1"), db.stored(TENANT));
    }

    @Test
    void aFailingAdmissionCheckRedeliversInsteadOfDropping() throws Exception {
        JmsInboxListener l = listener(null, tenant -> {
            throw new IllegalStateException("tenant state store unavailable");
        });
        assertFalse(l.store(text("ID:2", "{}")));
        assertTrue(db.stored(TENANT).isEmpty());
    }

    @Test
    void withoutAnAdmissionEveryTenantIsStoredAsBefore() throws Exception {
        JmsInboxListener l = listener(null, null);
        assertTrue(l.store(text("ID:3", "{}")));
        assertEquals(List.of("ID:3"), db.stored(TENANT));
    }

    @Test
    void theListenerRollsTheSessionBackForAFrozenTenant() throws Exception {
        frozen.set(true);
        List<String> session = new CopyOnWriteArrayList<>();
        BlockingQueue<Message> deliveries = new LinkedBlockingQueue<>(List.of(text("ID:9", "{\"amount\":9}")));
        ClassLoader cl = getClass().getClassLoader();
        MessageConsumer consumer = (MessageConsumer) Proxy.newProxyInstance(cl, new Class<?>[]{MessageConsumer.class},
                (p, m, args) -> m.getName().equals("receive") ? deliveries.poll(20, TimeUnit.MILLISECONDS) : null);
        Session s = (Session) Proxy.newProxyInstance(cl, new Class<?>[]{Session.class}, (p, m, args) -> {
            switch (m.getName()) {
                case "createConsumer" -> {
                    return consumer;
                }
                case "commit", "rollback" -> session.add(m.getName());
                default -> {
                }
            }
            return null;
        });
        Connection c = (Connection) Proxy.newProxyInstance(cl, new Class<?>[]{Connection.class},
                (p, m, args) -> m.getName().equals("createSession") ? s : null);
        ConnectionFactory f = (ConnectionFactory) Proxy.newProxyInstance(cl, new Class<?>[]{ConnectionFactory.class},
                (p, m, args) -> m.getName().equals("createConnection") ? c : null);

        JmsInboxListener l = listener(f, admission);
        Thread t = new Thread(l, "jms-inbox-test");
        t.start();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (session.isEmpty() && System.nanoTime() < end) {
            Thread.sleep(5);
        }
        l.close();
        t.join(5_000);
        assertEquals(List.of("rollback"), session, "rolled back, never committed, for a frozen tenant");
        assertTrue(db.stored(TENANT).isEmpty());
    }
}
