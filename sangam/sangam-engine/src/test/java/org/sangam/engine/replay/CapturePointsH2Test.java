// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.api.EngineAdapter.ExternalWork;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;

/**
 * Decision 151 capture points through a real engine on H2: an external task result, a message to a waiting execution,
 * a timer scheduled and fired, a lease that expires (TIMEOUT), a message that starts an instance, and a variable the
 * fixture cannot carry reported as unsupported. The order of the captured inputs is the engine's order. Without a
 * listener nothing is captured ({@link CapturePoints#NONE}). H2 only; Oracle and SQL Server NOT_RUN (the capture points
 * issue no SQL of their own). Written, NOT_RUN.
 */
class CapturePointsH2Test {

    private static final Instant T0 = Instant.parse("2026-11-13T03:00:00Z");

    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <message id="goMsg" name="go"/>
              <message id="newMsg" name="open"/>
              <process id="cap" isExecutable="true">
                <startEvent id="s"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="ext"/>
                <serviceTask id="ext" operaton:type="external" operaton:topic="pay"/>
                <sequenceFlow id="f2" sourceRef="ext" targetRef="m"/>
                <intermediateCatchEvent id="m"><messageEventDefinition messageRef="goMsg"/></intermediateCatchEvent>
                <sequenceFlow id="f3" sourceRef="m" targetRef="tm"/>
                <intermediateCatchEvent id="tm"><timerEventDefinition><timeDuration>PT1H</timeDuration></timerEventDefinition></intermediateCatchEvent>
                <sequenceFlow id="f4" sourceRef="tm" targetRef="w"/>
                <userTask id="w"/>
                <sequenceFlow id="f5" sourceRef="w" targetRef="e"/>
                <endEvent id="e"/>
              </process>
              <process id="byMessage" isExecutable="true">
                <startEvent id="ms"><messageEventDefinition messageRef="newMsg"/></startEvent>
                <sequenceFlow id="g1" sourceRef="ms" targetRef="w2"/>
                <userTask id="w2"/>
                <sequenceFlow id="g2" sourceRef="w2" targetRef="e2"/>
                <endEvent id="e2"/>
              </process>
            </definitions>
            """.strip();

    private final List<CapturedInput> seen = new CopyOnWriteArrayList<>();
    private final List<String> unsupported = new CopyOnWriteArrayList<>();
    private OperatonEngineAdapter engine;

    private final CaptureListener listener = new CaptureListener() {
        @Override
        public void onInput(CapturedInput input) {
            seen.add(input);
        }

        @Override
        public void unsupported(String reason) {
            unsupported.add(reason);
        }
    };

    private OperatonEngineAdapter start(CaptureListener l) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:cap" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings settings = new SangamEngineSettings();
        settings.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        settings.setAllowMultipleEnginesPerJvm(true);
        settings.setEngineSchemaUpdate("true"); // empty in-memory database owned by this test
        if (l != null) {
            settings.enableReplayCapture(l, false);
        }
        engine = SangamEngineFactory.build(ds, settings, "h2", List.of());
        engine.setClock(T0);
        engine.deploy("t1", "cap", Map.of("cap.bpmn", BPMN.getBytes(StandardCharsets.UTF_8)));
        return engine;
    }

    @AfterEach
    void stop() {
        if (engine != null) {
            engine.setClock(null);
            engine.close();
        }
    }

    private List<String> types() {
        List<String> out = new ArrayList<>();
        seen.forEach(i -> out.add(i.type() + (i.outcome() == null ? "" : ":" + i.outcome())));
        return out;
    }

    @Test
    void externalResultMessageAndTimerAreCapturedInEngineOrder() {
        OperatonEngineAdapter e = start(listener);
        e.startProcess("t1", "cap", "bk-1", Map.of());
        ExternalWork w = e.fetchAndLock("w1", "pay", 1, Duration.ofMinutes(5)).get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("amount", new BigDecimal("10.50"));
        result.put("count", 3);
        result.put("ratio", 1.5d);
        e.completeExternal(w.id(), "w1", w.lockVersion(), result);
        e.correlateMessage("t1", "go", "bk-1", Map.of("approved", true));
        e.setClock(T0.plus(Duration.ofHours(2)));
        e.executeDueJobs();

        assertEquals(List.of("EXTERNAL_TASK_RESULT:SUCCESS", "MESSAGE_DELIVERED", "TIMER_SCHEDULED", "TIMER_FIRED"),
                types());
        CapturedInput ext = seen.get(0);
        assertEquals("ext", ext.activityId());
        assertEquals(w.activityInstanceId(), ext.activityInstanceId());
        assertEquals(1, ext.attempt(), "the lease's first attempt");
        assertEquals(new BigDecimal("10.50"), ext.payload().get("amount"));
        assertEquals(3L, ext.payload().get("count"), "an Integer becomes the exact Long");
        assertTrue(!ext.payload().containsKey("ratio"), "a double is not carried");
        assertEquals(1, unsupported.size(), unsupported.toString());
        assertTrue(unsupported.get(0).contains("ratio"), unsupported.toString());

        CapturedInput msg = seen.get(1);
        assertEquals("m", msg.activityId());
        assertNotNull(msg.executionId());
        assertEquals("go", msg.payload().get("messageName"));
        assertEquals("bk-1", msg.payload().get("correlationKey"));
        assertEquals(Boolean.TRUE, msg.payload().get("approved"));

        CapturedInput scheduled = seen.get(2);
        assertEquals("tm", scheduled.activityId());
        assertEquals(T0.plus(Duration.ofHours(1)), scheduled.payload().get("dueAt"));
        CapturedInput fired = seen.get(3);
        assertEquals(scheduled.payload().get("timerId"), fired.payload().get("timerId"));
        assertEquals(T0.plus(Duration.ofHours(2)), fired.at(), "fired at the controlled clock time");
        assertEquals(1, fired.attempt());
        for (int i = 1; i < seen.size(); i++) {
            assertTrue(!seen.get(i).at().isBefore(seen.get(i - 1).at()), "the captured clock never goes back");
        }
    }

    @Test
    void anExpiredLeaseIsATimeout() {
        OperatonEngineAdapter e = start(listener);
        e.startProcess("t1", "cap", "bk-2", Map.of());
        ExternalWork w = e.fetchAndLock("w1", "pay", 1, Duration.ofSeconds(30)).get(0);
        e.setClock(T0.plusSeconds(60));
        e.executeDueJobs(); // runs the lease sweep first, whatever the expiry policy then decides
        assertEquals(List.of("EXTERNAL_TASK_RESULT:TIMEOUT"), types());
        CapturedInput in = seen.get(0);
        assertEquals("ext", in.activityId());
        assertEquals(w.activityInstanceId(), in.activityInstanceId());
        assertEquals(1, in.attempt());
        assertNull(in.retriesLeft());
        assertEquals(T0.plusSeconds(60), in.at());
    }

    @Test
    void aFailureCarriesItsMessageAndRetries() {
        OperatonEngineAdapter e = start(listener);
        e.startProcess("t1", "cap", "bk-5", Map.of());
        ExternalWork w = e.fetchAndLock("w2", "pay", 1, Duration.ofMinutes(5)).get(0);
        e.failExternal(w.id(), "w2", w.lockVersion(), "rail down", 2, Duration.ZERO);
        assertEquals(List.of("EXTERNAL_TASK_RESULT:FAILURE"), types());
        assertEquals("rail down", seen.get(0).payload().get("errorMessage"));
        assertEquals(2, seen.get(0).retriesLeft());
        assertEquals(1, seen.get(0).attempt());
    }

    @Test
    void aMessageThatStartsAnInstanceIsCapturedBeforeTheInstanceRuns() {
        OperatonEngineAdapter e = start(listener);
        e.correlateMessage("t1", "open", "bk-3", Map.of("n", 7L));
        assertEquals(List.of("MESSAGE_DELIVERED"), types());
        CapturedInput in = seen.get(0);
        assertNull(in.executionId(), "not known before the start");
        assertEquals("open", in.payload().get("messageName"));
        assertEquals("bk-3", in.payload().get("correlationKey"));
        assertEquals(7L, in.payload().get("n"));
    }

    @Test
    void withoutAListenerNothingIsCapturedAndNoneDoesNothing() {
        OperatonEngineAdapter e = start(null);
        e.startProcess("t1", "cap", "bk-4", Map.of());
        ExternalWork w = e.fetchAndLock("w1", "pay", 1, Duration.ofMinutes(5)).get(0);
        e.completeExternal(w.id(), "w1", w.lockVersion(), Map.of());
        assertTrue(seen.isEmpty());
        assertSame(CapturePoints.NONE, CapturePoints.of(null, Instant::now));
        assertTrue(!CapturePoints.NONE.active());
        assertNull(CapturePoints.NONE.interceptor());
    }

    @Test
    void replayableValuesAreExactOrRefused() {
        assertEquals(5L, CapturePoints.replayable(5));
        assertEquals(5L, CapturePoints.replayable((short) 5));
        assertEquals(new BigDecimal("12345678901234567890"), CapturePoints.replayable(new BigInteger("12345678901234567890")));
        assertEquals(T0, CapturePoints.replayable(Date.from(T0)));
        assertNull(CapturePoints.replayable(1.5d));
        assertNull(CapturePoints.replayable(1.5f));
        assertNull(CapturePoints.replayable(new Object()));
        assertEquals("x", CapturePoints.replayable("x"));
        assertThrows(IllegalStateException.class, () -> ((CaptureListener) in -> { }).unsupported("why"),
                "the default refuses, so an approximate fixture is never recorded");
    }
}
