// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.OptimisticLockingException;
import org.operaton.bpm.engine.impl.cmd.DefaultJobRetryCmd;
import org.operaton.bpm.engine.impl.interceptor.Command;
import org.sangam.core.engine.EngineMetrics;
import org.sangam.core.layer.Jdbc;

/**
 * SL-89: a job whose L2 root lock wait times out (SQL Server 1222, seen as ENGINE-14006 with a
 * {@code Jdbc.LayerSqlException} on {@code SELECT root_id FROM ROOT_SEQ WITH (UPDLOCK, ROWLOCK)} in the SQL Server
 * matrix) must keep its retries, as Operaton keeps them for an {@link OptimisticLockingException}; every other
 * failure must reach the engine unchanged. The retry decision is checked with Operaton's own
 * {@code JobRetryCmd.shouldDecrementRetriesFor}.
 */
class CountingFailedJobCommandFactoryTest {

    /** Exposes Operaton's real retry decision ({@code JobRetryCmd.shouldDecrementRetriesFor}). */
    static final class EngineRetryDecision extends DefaultJobRetryCmd {
        EngineRetryDecision() {
            super("job", null);
        }

        boolean decrements(Throwable t) {
            return shouldDecrementRetriesFor(t);
        }
    }

    private static final EngineRetryDecision ENGINE = new EngineRetryDecision();

    private final List<Throwable> handedToEngine = new ArrayList<>();
    private final List<String> counted = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private static final Object ENGINE_RESULT = new Object();
    private final List<String> engineRuns = new ArrayList<>();
    private final Command<Object> engineCommand = cc -> {
        engineRuns.add("run");
        return ENGINE_RESULT;
    };

    private final CountingFailedJobCommandFactory factory = new CountingFailedJobCommandFactory(
            (jobId, e) -> {
                handedToEngine.add(e);
                return engineCommand;
            },
            new EngineMetrics() {
                @Override
                public void jobFailed(String cause, boolean conflict) {
                    counted.add(cause + (conflict ? " conflict" : ""));
                }
            },
            warnings::add);

    private static Jdbc.LayerSqlException sqlError(String state, int code) {
        return new Jdbc.LayerSqlException("SELECT root_id FROM ROOT_SEQ WITH (UPDLOCK, ROWLOCK) WHERE tenant_id = ? AND root_id = ?",
                new SQLException("Lock request time out period exceeded.", state, code));
    }

    @Test
    void sqlServerLockTimeoutOnTheRootLockKeepsTheJobsRetries() {
        Jdbc.LayerSqlException timeout = sqlError("S0001", 1222);
        assertTrue(ENGINE.decrements(timeout), "precondition: the engine charges a retry for the raw failure");

        Command<Object> handedBack = factory.getCommand("job-1", timeout);
        // decision 302: the engine's own failed-job command runs (once) behind the failure-recording wrapper
        assertSame(ENGINE_RESULT, handedBack.execute(null), "the engine's failed-job command runs and its result is returned");
        assertEquals(List.of("run"), engineRuns);

        Throwable seen = handedToEngine.get(0);
        assertInstanceOf(OptimisticLockingException.class, seen);
        assertSame(timeout, seen.getCause(), "the original failure stays the cause (job stack trace)");
        assertFalse(ENGINE.decrements(seen), "a transient lock timeout must not cost a job retry");
        assertEquals(List.of("LayerSqlException conflict"), counted,
                "the metric counts the original failure, tagged as a kept-retry conflict");
    }

    @Test
    void aConvertedConflictIsNeverSilent() {
        factory.getCommand("job-77", sqlError("S0001", 1222));

        assertEquals(1, warnings.size(), "one WARN per conversion");
        String w = warnings.get(0);
        assertTrue(w.contains("job-77"), w);
        assertTrue(w.contains("LayerSqlException SQLState=S0001 code=1222"), w);
        assertTrue(w.contains("conflict=\"true\""), w);
    }

    @Test
    void ordinaryFailuresAreCountedWithoutTheConflictTagAndWithoutAWarning() {
        factory.getCommand("job-8", new IllegalStateException("delegate failed"));
        assertEquals(List.of("IllegalStateException"), counted);
        assertTrue(warnings.isEmpty());
    }

    @Test
    void anEngineMetricsWithoutTheTaggedOverloadStillCountsEveryFailure() {
        List<String> legacy = new ArrayList<>();
        CountingFailedJobCommandFactory f = new CountingFailedJobCommandFactory((jobId, e) -> engineCommand,
                new EngineMetrics() {
                    @Override
                    public void jobFailed(String cause) {
                        legacy.add(cause);
                    }
                }, warnings::add);
        f.getCommand("job-9", sqlError("S0001", 1222));
        assertEquals(List.of("LayerSqlException"), legacy);
    }

    @Test
    void deadlocksAndOtherDialectLockTimeoutsAreConflictsToo() {
        for (Throwable t : List.of(sqlError("40001", 1205), sqlError("55P03", 0), sqlError("40P01", 0),
                sqlError("61000", 30006), sqlError("HY000", 1213))) {
            Throwable seen = CountingFailedJobCommandFactory.asEngineConflict(t);
            assertInstanceOf(OptimisticLockingException.class, seen, t.getMessage());
            assertFalse(ENGINE.decrements(seen));
        }
    }

    @Test
    void aWrappedConflictIsFoundThroughTheCauseChain() {
        RuntimeException wrapped = new RuntimeException("job failed", sqlError("S0001", 1222));
        assertInstanceOf(OptimisticLockingException.class, CountingFailedJobCommandFactory.asEngineConflict(wrapped));
    }

    @Test
    void businessFailuresStillCostARetry() {
        IllegalStateException boom = new IllegalStateException("delegate failed");
        factory.getCommand("job-2", boom);
        assertSame(boom, handedToEngine.get(0));
        assertTrue(ENGINE.decrements(handedToEngine.get(0)));
    }

    @Test
    void aDuplicateKeyIsNotTreatedAsTransient() {
        Jdbc.LayerSqlException dup = new Jdbc.LayerSqlException("INSERT INTO T(id) VALUES (?)",
                new SQLException("Violation of PRIMARY KEY constraint", "23000", 2627));
        assertSame(dup, CountingFailedJobCommandFactory.asEngineConflict(dup));
    }

    @Test
    void anEngineConflictAndNullPassThroughUnchanged() {
        OptimisticLockingException ole = new OptimisticLockingException("engine conflict");
        assertSame(ole, CountingFailedJobCommandFactory.asEngineConflict(ole));
        assertNull(CountingFailedJobCommandFactory.asEngineConflict(null));
        factory.getCommand("job-3", null);
        assertEquals(List.of("unknown"), counted);
        assertTrue(warnings.isEmpty());
        assertNull(handedToEngine.get(0));
    }
}