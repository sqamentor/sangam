// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.relay;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Jdbc;
import org.sangam.core.layer.Transactions;

/**
 * Relay-driven wake-up (R1, VI.2.3 rule 6; fixes Operaton D-40 "long polling is not cluster-aware").
 *
 * <p>One batched notification per claim cycle that published something, carrying <b>no payload</b> (no tenant data on
 * a broadly visible channel), sent by the relay outside any command transaction. Listeners (worker long-poll, task
 * list push) treat it as a hint only: their 1 s poll stays authoritative, so a lost notification loses no work.
 */
@FunctionalInterface
public interface WakeupNotifier {

    /** Called once per relay cycle that published at least one event. Must not throw. */
    void cycleCompleted(int published);

    WakeupNotifier NONE = published -> {
    };

    /**
     * PostgreSQL {@code pg_notify(channel, '')} in its own short transaction. Other dialects have no cheap equivalent
     * and use {@link #NONE} (poll only); an in-JVM listener can be combined with {@link #andThen}.
     */
    static WakeupNotifier postgres(Dialect dialect, Transactions tx, String channel) {
        if (dialect != Dialect.POSTGRESQL) {
            return NONE;
        }
        return published -> {
            try {
                tx.inTransaction(c -> Jdbc.query(c, "SELECT pg_notify(?, '')", rs -> 1, channel));
            } catch (RuntimeException ignored) {
                // a lost wake-up is recovered by the authoritative poll (R1)
            }
        };
    }

    default WakeupNotifier andThen(WakeupNotifier next) {
        return published -> {
            try {
                cycleCompleted(published);
            } catch (RuntimeException ignored) {
                // never fail the relay because of a hint
            }
            next.cycleCompleted(published);
        };
    }
}
