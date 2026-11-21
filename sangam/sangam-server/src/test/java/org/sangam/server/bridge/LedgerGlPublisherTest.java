// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Publisher;
import org.sangam.integration.relay.PermanentPublishException;

/**
 * RM-05 and owner decision 126: no accounting entry may be dropped on the way to the GL. Every connector answer either
 * settles the delivery (accepted; outcome unknown, which the connector ledger already holds as a reconciliation item)
 * or is handed back to the outbox relay: a transient answer is retried, a permanent refusal is a
 * {@link PermanentPublishException}, which the relay parks with an audit row and an OPEN dead letter (operator requeue
 * or skip). Before this fix a permanent {@code Failed} answer was only logged and the delivery marked DONE.
 */
class LedgerGlPublisherTest {

    private static final Outbox.OutboxEvent EVENT = new Outbox.OutboxEvent(7L, UUID.fromString(
            "00000000-0000-0000-0000-0000000000a1"), "t1", "ledger:root", 1L, LedgerGlPublisher.EVENT_TYPE,
            "{\"entryId\":\"E-1\",\"amount\":\"100.00\"}");

    private static Publisher publisher(ComplianceConnectors.Result answer, List<String> calls) {
        return new LedgerGlPublisher((tenant, root, activity, connector, op, input) -> {
            calls.add(tenant + "|" + root + "|" + activity + "|" + connector + "|" + op + "|" + input.get("entryId"));
            return answer;
        }, "gl-1", "post").publisher();
    }

    @Test
    void aPermanentFailureIsHandedToTheRelayDeadLetterPathNotDropped() {
        List<String> calls = new ArrayList<>();
        Publisher p = publisher(new ComplianceConnectors.Result(false, null, "GL_REJECTED", "account closed", false), calls);
        Exception e = assertThrows(Exception.class, () -> p.publish(LedgerGlPublisher.DESTINATION, EVENT));
        PermanentPublishException perm = assertInstanceOf(PermanentPublishException.class, e,
                "a permanent GL refusal must park and dead-letter the delivery, never mark it DONE");
        assertTrue(perm.getMessage().contains("GL_REJECTED") && perm.getMessage().contains("gl-1")
                && perm.getMessage().contains(EVENT.eventId().toString()), perm.getMessage());
        assertEquals(List.of("t1|ledger:root|gl:" + EVENT.eventId() + "|gl-1|post|E-1"), calls);
    }

    @Test
    void guardEveryConnectorAnswerIsSettledOrHandedBack() {
        List<String> calls = new ArrayList<>();
        // accepted: delivered
        assertDoesNotThrow(() -> publisher(new ComplianceConnectors.Result(true, "REF-1", null, null, false), calls)
                .publish(LedgerGlPublisher.DESTINATION, EVENT));
        // outcome unknown: the invoker already holds a reconciliation item; no blind resend (VI.3 rule 5)
        assertDoesNotThrow(() -> publisher(new ComplianceConnectors.Result(false, UUID.randomUUID().toString(),
                "OUTCOME_UNKNOWN", "outcome unknown; reconciliation item 9", false), calls)
                .publish(LedgerGlPublisher.DESTINATION, EVENT));
        // transient: retried by the relay with back-off (dead-lettered when attempts are exhausted)
        Exception retry = assertThrows(Exception.class, () -> publisher(new ComplianceConnectors.Result(false, null,
                "RETRY", "timeout", true), calls).publish(LedgerGlPublisher.DESTINATION, EVENT));
        assertFalse(retry instanceof PermanentPublishException, "a transient answer is retried, not parked at once");
        // stale lease: another attempt holds the operation; retried (the operation ledger keeps it exactly-once)
        Exception stale = assertThrows(Exception.class, () -> publisher(new ComplianceConnectors.Result(false, null,
                "STALE", "lease lost", false), calls).publish(LedgerGlPublisher.DESTINATION, EVENT));
        assertFalse(stale instanceof PermanentPublishException);
        // any other refusal code is permanent: dead-lettered
        for (String code : List.of("GL_REJECTED", "VALIDATION", "AUTH")) {
            assertInstanceOf(PermanentPublishException.class, assertThrows(Exception.class, () -> publisher(
                    new ComplianceConnectors.Result(false, null, code, "no", false), calls)
                    .publish(LedgerGlPublisher.DESTINATION, EVENT)), code);
        }
    }
}
