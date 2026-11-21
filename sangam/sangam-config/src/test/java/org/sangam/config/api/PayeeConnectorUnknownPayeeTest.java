// SPDX-License-Identifier: Apache-2.0
package org.sangam.config.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;

/**
 * Register E-26: a payee the party registry does not hold. The directory answers it without a party id (status
 * NOT_FOUND, no other holder of the account). The connector put that null id into its answer, and a connector answer
 * holds no null value (ConnectorResult.Success copies it with Map.copyOf): the NullPointerException was caught by the
 * connector's own handler and came out as PAYEE_DIRECTORY_ERROR with no message. So the payee check of every bill
 * whose payee is named by account and is not yet a registered party read "check not confirmed" and the bill was
 * rejected (PAYEE_CHECK_NOT_CONFIRMED), as if the registry had failed (demo sequence on PostgreSQL, CI run
 * 44857134210: six payees, each {@code BusinessFailure[code=PAYEE_DIRECTORY_ERROR, message=null]}). The answer of an
 * unknown payee is a completed answer that says so; a directory that really fails is still PAYEE_DIRECTORY_ERROR.
 */
class PayeeConnectorUnknownPayeeTest {

    private static PayeeDirectory answering(PayeeDirectory.Resolution r) {
        return new PayeeDirectory() {
            @Override
            public Resolution resolve(String tenant, Map<String, Object> payee) {
                return r;
            }

            @Override
            public Resolution register(String tenant, Map<String, Object> payee, String operationId, String actor) {
                return r;
            }
        };
    }

    private static ConnectorRequest resolve() {
        return new ConnectorRequest("ZZ", UUID.randomUUID(), "a1", 1, null, null, "payee.resolve",
                Map.of("payee", "A", "payeeAccount", "00004207100101", "ifsc", "ZXRJ9JPR001"), null);
    }

    /**
     * Follow-up 2 (vi), integrator 2026-11-21 (joint decision EV-M-0964): an unregistered payee is refused fail-closed,
     * with a reason, never passed through as NOT_FOUND. The answer is a business failure with its own code and a
     * message that says why; the bill's payee check then reads FAILED and the bill is rejected
     * (PAYEE_CHECK_NOT_CONFIRMED), the failed check audited with this code (ConnectorCallDelegate).
     */
    @Test
    void aPayeeTheRegistryDoesNotHoldIsRefusedAsNotRegistered() {
        ConnectorResult r = new PayeeConnector(answering(new PayeeDirectory.Resolution(null, "NOT_FOUND", false, false,
                List.of()))).invoke(resolve());
        ConnectorResult.BusinessFailure f = assertInstanceOf(ConnectorResult.BusinessFailure.class, r, String.valueOf(r));
        assertEquals("PAYEE_NOT_REGISTERED", f.code());
        assertFalse(f.message() == null || f.message().isBlank(), "the refusal says why: " + f);
        assertFalse(f.output().containsKey("partyId"), "no party, so no party id: " + f.output());
    }

    @Test
    void aPayeeTheRegistryHoldsIsAnsweredAsBefore() {
        ConnectorResult r = new PayeeConnector(answering(new PayeeDirectory.Resolution("PTY-9", "ACTIVE", true, false,
                List.of()))).invoke(resolve());
        ConnectorResult.Success s = assertInstanceOf(ConnectorResult.Success.class, r, String.valueOf(r));
        assertEquals("PTY-9", s.output().get("partyId"));
        assertEquals("ACTIVE", s.output().get("partyStatus"));
        assertEquals(false, s.output().get("multiple"));
    }

    @Test
    void aDirectoryThatFailsIsStillADirectoryErrorWithItsMessage() {
        PayeeDirectory failing = new PayeeDirectory() {
            @Override
            public Resolution resolve(String tenant, Map<String, Object> payee) {
                throw new IllegalStateException("party not found: PTY-1");
            }

            @Override
            public Resolution register(String tenant, Map<String, Object> payee, String operationId, String actor) {
                throw new IllegalStateException("party not found: PTY-1");
            }
        };
        ConnectorResult.BusinessFailure f = assertInstanceOf(ConnectorResult.BusinessFailure.class,
                new PayeeConnector(failing).invoke(resolve()));
        assertEquals("PAYEE_DIRECTORY_ERROR", f.code());
        assertEquals("party not found: PTY-1", f.message());
    }
}
