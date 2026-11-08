// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.fwt.parsing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Dialect;
import org.sangam.ports.history.HistoryQueryService;
import org.sangam.ports.support.Paging;
import org.sangam.ports.support.PortsProblem;
import org.sangam.ports.worker.FlowableWorkerCompat;

/** FW-07 input guards and FW-16 paging/predicate scenarios (FLOWABLE-WEAKNESS-AUDIT §5). */
class FwtInputGuardsTest {

    private static void invalid(Runnable r) {
        PortsProblem p = assertThrows(PortsProblem.class, r::run);
        assertEquals("INVALID_INPUT", p.code(), p.getMessage());
        assertEquals(422, p.status());
    }

    // FWT-012
    @Test
    void fwt012_malformedDurationsAndMissingWorkerIdAreInvalidInput_numberOfTasksClampedTo100() {
        invalid(() -> FlowableWorkerCompat.toFetchAndLock("{\"topic\":\"t\",\"workerId\":\"w\",\"lockDuration\":\"10 minutes\"}"));
        invalid(() -> FlowableWorkerCompat.toFail("{\"workerId\":\"w\",\"retryTimeout\":\"soon\"}", 1));
        invalid(() -> FlowableWorkerCompat.toFetchAndLock("{\"topic\":\"t\",\"lockDuration\":\"PT1M\"}"));
        invalid(() -> FlowableWorkerCompat.toComplete("{\"variables\":[]}", 1));
        invalid(() -> FlowableWorkerCompat.toFail("{\"errorMessage\":\"x\"}", 1));
        Map<String, Object> m = FlowableWorkerCompat.toFetchAndLock(
                "{\"topic\":\"t\",\"workerId\":\"w\",\"lockDuration\":\"PT1M\",\"numberOfTasks\":10000}");
        assertEquals(100, m.get("maxTasks"));
        assertEquals(FlowableWorkerCompat.MAX_TASKS, 100);
    }

    // FWT-018
    @Test
    void fwt018_page300Size500IsPageTooDeep422() {
        PortsProblem p = assertThrows(PortsProblem.class, () -> new Paging.PageRequest(300, 500));
        assertEquals("PAGE_TOO_DEEP", p.code());
        assertEquals(422, p.status());
        new Paging.PageRequest(200, 500); // row 100000: the deepest allowed start
    }

    // FWT-019
    @Test
    void fwt019_historyQueryWithNullPredicateIsIllegalArgument() {
        HistoryQueryService h = new HistoryQueryService(null, Dialect.POSTGRESQL,
                new org.sangam.core.layer.Transactions() {
                    @Override
                    public <T> T inTransaction(java.util.function.Function<java.sql.Connection, T> work) {
                        throw new AssertionError("no query may run without a predicate");
                    }
                });
        HistoryQueryService.Filter f = new HistoryQueryService.Filter("t1", null, null, null, null, null, null, null, null,
                null, null, null);
        Paging.PageRequest p = new Paging.PageRequest(0, 10);
        List<Runnable> calls = List.of(() -> h.processInstances(f, null, p), () -> h.caseInstances(f, null, p),
                () -> h.activityInstances(f, null, p), () -> h.tasks(f, null, p), () -> h.openActivities("t1", "pi", null));
        for (Runnable r : calls) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, r::run);
            assertEquals(IllegalArgumentException.class, e.getClass(), e.toString());
        }
    }
}
