// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.channels.ChannelsSchema;
import org.sangam.channels.messenger.InboundMediaQueue;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Transactions;

/**
 * {@link ChannelMediaWorker#close()} returns only after the poller has stopped. A claim still in flight after close
 * could commit after the next start's {@code resumeOwn()} on the same node and leave its rows claimed until the lease
 * runs out (ci20: {@code ChannelMediaWorkerRestartH2Test.aRetryScheduledBeforeTheStopIsKeptAcrossTheRestart} waited 10
 * seconds for a row such a late claim held for 10 minutes).
 */
class ChannelMediaWorkerCloseTest {

    @Test
    void closeWaitsForAClaimInFlight() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection c = h2.getConnection()) {
            ChannelsSchema.create(c, Dialect.POSTGRESQL);
        }
        GatedDataSource gated = new GatedDataSource(h2);
        Transactions tx = Transactions.of(gated);
        MediaQueueFixture f = new MediaQueueFixture();
        ChannelMediaWorker worker = new ChannelMediaWorker(new InboundMediaQueue(Dialect.POSTGRESQL, tx, "node-1", Duration.ofMinutes(10)), tx,
                t -> MediaQueueFixture.ENTRIES, (t, e, ref) -> MediaQueueFixture.media(ref), f.store, (n, b) -> true, f.bot, 1,
                Duration.ofMillis(20), List.of(Duration.ofMillis(10)), (c, m, reason) -> { });
        worker.start();
        gated.arm();
        assertTrue(gated.entered.await(10, TimeUnit.SECONDS), "the poller asks for a connection to claim due rows");

        AtomicBoolean inFlightWhenClosed = new AtomicBoolean(true);
        Thread closer = new Thread(() -> {
            worker.close();
            inFlightWhenClosed.set(gated.inFlight.get());
        });
        closer.start();
        closer.join(300);
        gated.release.countDown();
        closer.join(TimeUnit.SECONDS.toMillis(30));
        assertFalse(closer.isAlive(), "close() returns once the claim has finished");
        assertFalse(inFlightWhenClosed.get(), "close() returned while the poller's claim was still in flight");
    }

    /** Hands out connections; once armed, the next request waits (ignoring interrupts, like a JDBC call in progress) until released. */
    static final class GatedDataSource implements DataSource {
        private final DataSource delegate;
        private final AtomicBoolean armed = new AtomicBoolean();
        final AtomicBoolean inFlight = new AtomicBoolean();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        GatedDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        void arm() {
            armed.set(true);
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (armed.compareAndSet(true, false)) {
                inFlight.set(true);
                entered.countDown();
                boolean interrupted = false;
                while (true) {
                    try {
                        release.await();
                        break;
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                inFlight.set(false);
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return delegate.getConnection();
        }

        @Override
        public Connection getConnection(String user, String password) throws SQLException {
            return getConnection();
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }
}
