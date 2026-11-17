// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.idempotency;

import org.sangam.core.api.ReadWork;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.IdempotencyStore;
import org.sangam.core.layer.Transactions;

/**
 * RM-27: {@link IdempotencyService} has no permissive default. A wiring that passes no {@link ReplayAuthorizer} used to
 * get {@link ReplayAuthorizer#ALLOW_ALL} (every stored response replayed to its caller without the object check and
 * without the current field masks) and one that passes no {@link IdempotencyProfile} got the optional profile; both
 * are refused at construction now, so production cannot reach either by omission.
 */
class IdempotencyServiceExplicitPolicyTest {

    private static final Transactions TX = new Transactions() {
        @Override
        public <T> T inTransaction(Function<Connection, T> work) {
            throw new IllegalStateException("no database in this test");
        }

        @Override
        public <T> T read(ReadWork<T> work) {
            throw new IllegalStateException("no database in this test");
        }
    };

    private static IdempotencyService service(IdempotencyProfile profile, ReplayAuthorizer authorizer) {
        return new IdempotencyService(new IdempotencyStore(null, TX, "node-1"), null, TX, profile, authorizer, true);
    }

    @Test
    void aMissingReplayAuthorizerIsRefusedNotReplacedByAllowAll() {
        NullPointerException e = assertThrows(NullPointerException.class,
                () -> service(IdempotencyProfile.government(), null));
        assertTrue(e.getMessage().contains("authorizer"), e.getMessage());
    }

    @Test
    void aMissingProfileIsRefusedNotReplacedByTheOptionalOne() {
        NullPointerException e = assertThrows(NullPointerException.class,
                () -> service(null, ReplayAuthorizer.ALLOW_ALL));
        assertTrue(e.getMessage().contains("profile"), e.getMessage());
    }

    @Test
    void anExplicitChoiceIsAccepted() {
        assertDoesNotThrow(() -> service(IdempotencyProfile.optional(), ReplayAuthorizer.ALLOW_ALL),
                "a development host states the permissive choice itself");
    }
}
