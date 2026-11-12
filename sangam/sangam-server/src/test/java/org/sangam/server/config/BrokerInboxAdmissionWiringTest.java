// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;

import jakarta.jms.ConnectionFactory;

import org.junit.jupiter.api.Test;
import org.sangam.integration.inbox.JmsInboxListener;
import org.sangam.integration.relay.TenantAdmission;

/**
 * SL-142 (M-008): the broker inbox wiring hands the cell's {@link TenantAdmission} to every consumer it builds, so
 * the consumers' admission check is active on a cell. Before the fix both bean methods used the constructor without
 * an admission, and every consumer admitted every tenant ({@link TenantAdmission#ALL}) even on a cell whose tenant was
 * FROZEN, IMPORTING, MOVED or PURGED. Without cells (no admission bean) the consumers still admit every tenant.
 */
class BrokerInboxAdmissionWiringTest {

    /** A factory that is never used: building a listener does not connect. */
    static final ConnectionFactory UNUSED = (ConnectionFactory) Proxy.newProxyInstance(
            BrokerInboxAdmissionWiringTest.class.getClassLoader(), new Class<?>[] {ConnectionFactory.class},
            (p, m, a) -> {
                throw new AssertionError("no connection while the listeners are built: " + m.getName());
            });

    static BrokerInboxWiring.Settings jms(int concurrency) {
        BrokerInboxWiring.Channel queue = new BrokerInboxWiring.Channel();
        queue.setSource("bank");
        queue.setTenant("T1");
        queue.setQueue("in.bank");
        queue.setConcurrency(concurrency);
        BrokerInboxWiring.Channel topic = new BrokerInboxWiring.Channel();
        topic.setSource("gov");
        topic.setTenant("T2");
        topic.setTopic("in.gov");
        BrokerInboxWiring.Settings s = new BrokerInboxWiring.Settings();
        s.setEnabled(true);
        s.setChannels(List.of(queue, topic));
        return s;
    }

    static TenantAdmission admissionOf(Runnable listener) throws Exception {
        Field f = JmsInboxListener.class.getDeclaredField("admission");
        f.setAccessible(true);
        return (TenantAdmission) f.get(listener);
    }

    @Test
    void everyJmsListenerGetsTheCellsAdmission() throws Exception {
        TenantAdmission cell = tenant -> "T1".equals(tenant);
        List<Runnable> listeners = BrokerInboxWiring.jmsListeners(jms(2), UNUSED, null, null, cell);
        assertEquals(3, listeners.size(), "two for the queue channel, one for the topic channel");
        for (Runnable l : listeners) {
            assertSame(cell, admissionOf(l));
        }
    }

    /** SL-142 owner decision: ACTIVE admits, MOVED/PURGED park (with target cell and move id), the rest redeliver. */
    @Test
    void theCellDecidesPerTenantState() {
        java.time.Instant now = java.time.Instant.parse("2026-11-12T00:00:00Z");
        for (org.sangam.server.bridge.CellTenantStateStore.State s
                : org.sangam.server.bridge.CellTenantStateStore.State.values()) {
            TenantAdmission.Decision d = CellWiring.inboundDecision(
                    new org.sangam.server.bridge.CellTenantStateStore.TenantState("T1", s, "mv-7", "cell-b", now));
            switch (s) {
                case ACTIVE -> assertSame(TenantAdmission.Decision.ADMIT, d);
                case MOVED, PURGED -> {
                    assertEquals(TenantAdmission.Action.PARK, d.action(), s.name());
                    assertEquals(s.name(), d.tenantState());
                    assertEquals("cell-b", d.targetCellOrNull());
                    assertEquals("mv-7", d.moveIdOrNull());
                }
                case IMPORTING, FROZEN -> assertSame(TenantAdmission.Decision.REDELIVER, d, s.name());
            }
            assertEquals(s == org.sangam.server.bridge.CellTenantStateStore.State.ACTIVE,
                    CellWiring.admitsInbound(s), "admits() agrees with decide(): " + s);
        }
    }

    @Test
    void withoutCellsEveryTenantIsAdmittedAsBefore() throws Exception {
        for (Runnable l : BrokerInboxWiring.jmsListeners(jms(1), UNUSED, null, null, null)) {
            assertSame(TenantAdmission.ALL, admissionOf(l));
        }
    }
}
