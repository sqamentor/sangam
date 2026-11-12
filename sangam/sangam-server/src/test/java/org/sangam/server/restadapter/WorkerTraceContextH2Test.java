// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.restadapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.Jdbc;
import org.sangam.engine.trace.EngineTracing;
import org.sangam.engine.trace.JobTraceContexts;
import org.sangam.rest.port.WorkPort;

/**
 * M-062 (R13): with the engine's {@code trace-links} on, a fetched work item carries the trace context stored when the
 * engine created it ({@code JOB_TRACE_CONTEXT}, kind {@code EXTERNAL_TASK}) as {@code traceContext}; without a stored
 * context the field is absent (never an empty string); with the setting off, fetch is unchanged.
 */
class WorkerTraceContextH2Test {

    static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    WorkerEngineFixture fx;

    @BeforeEach
    void setUp() {
        fx = new WorkerEngineFixture("m062");
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    static WorkPort.FetchAndLockRequest fetch() {
        return new WorkPort.FetchAndLockRequest("w1", List.of(new WorkPort.TopicRequest(WorkerEngineFixture.TOPIC,
                60_000L, null, null, null, null)), 10, 0L, null);
    }

    /** The external task of the instance on the shared topic, with a stored creating context. */
    String storeContext(String pi, String tracestate) {
        return fx.engine.inEngineTransaction(c -> {
            String id = Jdbc.one(c, "SELECT ID_ FROM ACT_RU_EXT_TASK WHERE PROC_INST_ID_=? AND TOPIC_NAME_=?",
                    rs -> rs.getString(1), pi, WorkerEngineFixture.TOPIC).orElseThrow();
            JobTraceContexts.insert(c, id, JobTraceContexts.Kind.EXTERNAL_TASK,
                    new EngineTracing.TraceContext(TRACEPARENT, tracestate), 0L);
            return id;
        });
    }

    @Test
    void aStoredContextComesBackOnFetch() throws Exception {
        fx.work.setTraceLinks(true);
        String id = storeContext(fx.start("tA", "A-1"), "vendor=abc");
        WorkPort.ExternalWork w = fx.work.fetchAndLock(WorkerEngineFixture.worker("tA", "w1"), fetch()).get(0);
        assertEquals(id, w.id());
        assertEquals(TRACEPARENT, w.traceContext().traceparent());
        assertEquals("vendor=abc", w.traceContext().tracestate());
        String json = JSON.writeValueAsString(w);
        assertTrue(json.contains("\"traceContext\":{\"traceparent\":\"" + TRACEPARENT + "\",\"tracestate\":\"vendor=abc\"}"),
                json);
    }

    @Test
    void withoutAStoredContextOrTracestateTheFieldsAreAbsent() throws Exception {
        fx.work.setTraceLinks(true);
        fx.start("tA", "A-2");
        WorkPort.ExternalWork none = fx.work.fetchAndLock(WorkerEngineFixture.worker("tA", "w1"), fetch()).get(0);
        assertNull(none.traceContext());
        assertFalse(JSON.writeValueAsString(none).contains("traceContext"), "absent, not null or empty");

        storeContext(fx.start("tA", "A-3"), null);
        WorkPort.ExternalWork noState = fx.work.fetchAndLock(WorkerEngineFixture.worker("tA", "w1"), fetch()).get(0);
        assertEquals(TRACEPARENT, noState.traceContext().traceparent());
        assertFalse(JSON.writeValueAsString(noState).contains("tracestate"));
    }

    @Test
    void withTheSettingOffFetchIsUnchanged() throws Exception {
        storeContext(fx.start("tA", "A-4"), "vendor=abc");   // stored, but trace links are off (the default)
        WorkPort.ExternalWork w = fx.work.fetchAndLock(WorkerEngineFixture.worker("tA", "w1"), fetch()).get(0);
        assertNull(w.traceContext());
        assertFalse(JSON.writeValueAsString(w).contains("traceContext"));
    }
}
