// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.integration;

import java.util.Objects;

import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Transactions;
import org.sangam.integration.relay.WakeupListener;
import org.sangam.integration.relay.WakeupNotifier;

/**
 * How one host receives the relay wake-up (M-005), decided once and by name, never by what a pool happens to offer.
 *
 * <p>The wake-up is a hint (R1): after a cycle that published, the outbox relay tells waiting subscribers (the worker
 * long-poll, the task-list push) to poll again at once. Every subscriber keeps its own authoritative poll, and the
 * relay itself always polls the outbox at its own interval, so the mode changes latency only, never a result.
 *
 * <ul>
 *   <li>{@link Mode#LISTEN}: PostgreSQL {@code LISTEN} on a dedicated physical connection the host can open outside its
 *       pool ({@link #listen}); a notification from any node wakes this node's subscribers.</li>
 *   <li>{@link Mode#POLLING}: no {@code LISTEN} session ({@link #polling}). The listener is in-JVM only: a relay cycle on
 *       this node wakes this node's subscribers; a cycle on another node is seen at the subscribers' next poll. On
 *       PostgreSQL the relay still sends {@code pg_notify}, so nodes of a host that does listen are woken by this
 *       one. A host whose pool hands out no physical connection (owner decision 316: Quarkus on Agroal) asks for this
 *       mode explicitly; it never tries to {@code LISTEN} and falls back.</li>
 * </ul>
 *
 * @param mode     the mode, as chosen by the host
 * @param listener the receiving side; {@link WakeupListener#usesListen()} is true exactly in {@link Mode#LISTEN}
 * @param notifier the sending side, handed to the outbox relay
 */
public record RelayWakeup(Mode mode, WakeupListener listener, WakeupNotifier notifier) {

    /** How a host receives the relay wake-up. */
    public enum Mode {
        /** PostgreSQL {@code LISTEN} on a dedicated connection. */
        LISTEN,
        /** No {@code LISTEN}: in-JVM wake-up, and the subscribers' own poll for other nodes' cycles. */
        POLLING
    }

    public RelayWakeup {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(notifier, "notifier");
        if (listener.usesListen() != (mode == Mode.LISTEN)) {
            throw new IllegalArgumentException("the wake-up listener " + (listener.usesListen() ? "uses" : "does not use")
                    + " LISTEN, which is not mode " + mode);
        }
    }

    /**
     * {@link Mode#LISTEN}: PostgreSQL only, with a source of physical connections outside the pool.
     *
     * @throws IllegalArgumentException for another dialect or without a source: a host that cannot listen says so with
     *                                  {@link #polling}, it is never downgraded here
     */
    public static RelayWakeup listen(Dialect dialect, Transactions productTx, WakeupListener.ConnectionSource source) {
        Objects.requireNonNull(productTx, "productTx");
        if (dialect != Dialect.POSTGRESQL || source == null) {
            throw new IllegalArgumentException("LISTEN needs PostgreSQL and a source of physical connections (dialect "
                    + dialect + ", source " + (source == null ? "absent" : "present") + "); use polling()");
        }
        WakeupListener listener = WakeupListener.forDialect(dialect, source, WakeupNotifier.CHANNEL);
        return new RelayWakeup(Mode.LISTEN, listener,
                WakeupNotifier.postgres(dialect, productTx, WakeupNotifier.CHANNEL));
    }

    /**
     * {@link Mode#POLLING}, on any dialect: an in-JVM listener that never opens a connection, and a notifier that wakes
     * this JVM's subscribers and, on PostgreSQL, also sends {@code pg_notify} in its own short transaction on
     * {@code productTx} for the nodes that do listen.
     */
    public static RelayWakeup polling(Dialect dialect, Transactions productTx) {
        Objects.requireNonNull(dialect, "dialect");
        Objects.requireNonNull(productTx, "productTx");
        // a null source is the listener's in-JVM-only form, on PostgreSQL too
        WakeupListener listener = WakeupListener.forDialect(dialect, null, WakeupNotifier.CHANNEL);
        WakeupNotifier inJvm = listener.inJvmNotifier();
        WakeupNotifier notifier = dialect == Dialect.POSTGRESQL
                ? WakeupNotifier.postgres(dialect, productTx, WakeupNotifier.CHANNEL).andThen(inJvm)
                : inJvm;
        return new RelayWakeup(Mode.POLLING, listener, notifier);
    }

    /** One line for the start-up log and the documentation of a host difference. */
    public String describe() {
        return mode == Mode.LISTEN
                ? "relay wake-up: PostgreSQL LISTEN on a dedicated connection (channel " + WakeupNotifier.CHANNEL + ")"
                : "relay wake-up: polling (no LISTEN session); subscribers on this node are woken in-JVM, cycles of"
                        + " other nodes are seen at the subscribers' next poll; only latency differs, never results";
    }
}
