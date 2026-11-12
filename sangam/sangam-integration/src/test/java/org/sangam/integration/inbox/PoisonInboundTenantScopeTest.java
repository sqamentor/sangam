// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditChain;
import org.sangam.integration.relay.DeadLetterStore;
import org.sangam.integration.relay.TenantAdmission;

/**
 * SL-142 / owner decision 61: the cell operator lists the parked messages of EVERY tenant of the cell
 * ({@link PoisonInbound#listParked}); a tenant-scoped caller sees only its own items ({@link PoisonInbound#parked(String,
 * long)}, {@link PoisonInbound#item}): another tenant's id is "not found", exactly like an unknown id. The replay goes
 * through the one park store (replayParked) and the transport gets the original source, message id and event type.
 */
class PoisonInboundTenantScopeTest {

    H2InboxFixture db;
    PoisonInbound poison;

    @BeforeEach
    void setUp() throws Exception {
        db = new H2InboxFixture("poisonscope");
        poison = new PoisonInbound(H2InboxFixture.D, db.tx(), new DeadLetterStore(H2InboxFixture.D, db.tx()),
                new AuditChain(H2InboxFixture.D));
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    long park(String tenant, String messageId) {
        long item = poison.park(ChannelBinding.of("kafka:payments", tenant, "broker"), tenant,
                new InboundMessage(messageId, "PaymentAck", "corr-" + messageId, tenant, "{\"m\":\"" + messageId + "\"}"),
                TenantAdmission.Decision.park("MOVED", "cell-b", "mv-1"));
        assertTrue(item > 0, "parked");
        return item;
    }

    @Test
    void theCellOperatorListsTheParkedMessagesOfEveryTenantButNotPoisonItems() {
        long a = park("T1", "m-1");
        long b = park("T2", "m-2");
        poison.record(ChannelBinding.of("kafka:payments", "T1", "broker"), "bad-1", "PaymentAck", "{not json", "malformed");

        List<PoisonInbound.Item> all = poison.listParked();
        assertEquals(List.of(a, b), all.stream().map(PoisonInbound.Item::itemId).toList(), "both tenants, oldest first");
        assertTrue(all.stream().allMatch(PoisonInbound.Item::parked), "a poison item is not a parked message");
        assertEquals(2, poison.listOpen("T1").size(), "the tenant list still shows its poison item and its parked one");
    }

    @Test
    void aTenantScopedReadNeverReachesAnotherTenantsItem() {
        long a = park("T1", "m-1");
        assertEquals("T1", poison.parked("T1", a).tenantId());
        assertEquals("m-1", poison.item("T1", a).messageId());

        IllegalArgumentException other = assertThrows(IllegalArgumentException.class, () -> poison.parked("T2", a));
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () -> poison.parked("T2", a + 999));
        assertEquals(unknown.getMessage().replace(Long.toString(a + 999), "#"), other.getMessage().replace(Long.toString(a), "#"),
                "another tenant's item reads exactly like an unknown id");
        assertThrows(IllegalArgumentException.class, () -> poison.item("T2", a));
        assertThrows(IllegalArgumentException.class, () -> poison.parked(" ", a), "a blank tenant is refused");
        assertEquals("T1", poison.parked(a).tenantId(), "the cell-operator read is unscoped");
    }

    @Test
    void replayHandsTheOriginalMessageToTheTransportAndClosesTheItemOnce() {
        long a = park("T1", "m-1");
        List<PoisonInbound.Parked> sent = new CopyOnWriteArrayList<>();
        poison.replayParked(a, sent::add, "cell-op", "tenant now in cell-b");

        assertEquals(1, sent.size());
        PoisonInbound.Parked p = sent.get(0);
        assertEquals("kafka:payments", p.source(), "original source, not xcell:*");
        assertEquals("m-1", p.messageId(), "original message id: the target inbox deduplicates on it");
        assertEquals("PaymentAck", p.eventType(), "original event type");
        assertEquals("corr-m-1", p.correlationKey());
        assertTrue(poison.listParked().isEmpty(), "closed");
        assertFalse(poison.item("T1", a).state().equals("OPEN"));
        assertThrows(IllegalStateException.class, () -> poison.replayParked(a, sent::add, "cell-op", "again"),
                "a closed item is not replayed twice");
        assertEquals(1, sent.size());
    }
}
