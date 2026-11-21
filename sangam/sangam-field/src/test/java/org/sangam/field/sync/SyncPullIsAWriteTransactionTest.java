// SPDX-License-Identifier: Apache-2.0
package org.sangam.field.sync;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.sangam.core.api.ReadWork;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Transactions;

/**
 * Register P-2 (CodeQL alert 375, owner decision 2026-11-20: the device pull stays a GET and keeps recording contact):
 * {@code SyncService.pull} updates {@code FLD_DEVICE.last_seen_at}, so it is write work and runs in
 * {@link Transactions#inTransaction}. It must not run as {@link ReadWork}: a {@code ReadWork} callback is the
 * product's statement that nothing is written (round 9), and a write inside one makes that statement false.
 */
class SyncPullIsAWriteTransactionTest {

    private static final class WriteTransaction extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    @Test
    void thePullRunsInAWriteTransactionBecauseItRecordsContact() {
        Transactions tx = new Transactions() {
            @Override
            public <T> T inTransaction(Function<Connection, T> work) {
                throw new WriteTransaction();
            }

            @Override
            public <T> T read(ReadWork<T> work) {
                throw new AssertionError("SyncService.pull ran as ReadWork, but it updates FLD_DEVICE.last_seen_at");
            }
        };
        SyncService sync = new SyncService(tx, Dialect.POSTGRESQL, null, null, null, null, null, null, null, null, null);
        assertThrows(WriteTransaction.class, () -> sync.pull("t1", "user-1", "device-1", 0));
    }
}
