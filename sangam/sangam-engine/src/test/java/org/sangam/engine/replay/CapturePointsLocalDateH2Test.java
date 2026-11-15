// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.api.EngineAdapter.ExternalWork;
import org.sangam.core.time.BusinessClock;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.packs.engine.LocalDateValueType;

/**
 * Decision 354: a LocalDate variable is captured as {@code LOCAL_DATE} with the tenant's business zone
 * ({@link LocalDateInZone}), on the external-task result, the message to a waiting execution and the message that
 * starts an instance; it is never reported unsupported. The zone is the tenant zone source's ({@link BusinessClock}).
 */
class CapturePointsLocalDateH2Test {

    private static final Instant T0 = Instant.parse("2026-11-13T03:00:00Z");
    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:operaton="http://operaton.org/schema/1.0/bpmn" targetNamespace="t">
              <message id="goMsg" name="go"/>
              <message id="newMsg" name="open"/>
              <process id="cap" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="ext"/>
                <serviceTask id="ext" operaton:type="external" operaton:topic="pay"/>
                <sequenceFlow id="f2" sourceRef="ext" targetRef="m"/>
                <intermediateCatchEvent id="m"><messageEventDefinition messageRef="goMsg"/></intermediateCatchEvent>
                <sequenceFlow id="f3" sourceRef="m" targetRef="w"/><userTask id="w"/>
                <sequenceFlow id="f4" sourceRef="w" targetRef="e"/><endEvent id="e"/>
              </process>
              <process id="byMessage" isExecutable="true">
                <startEvent id="ms"><messageEventDefinition messageRef="newMsg"/></startEvent>
                <sequenceFlow id="g1" sourceRef="ms" targetRef="w2"/><userTask id="w2"/>
                <sequenceFlow id="g2" sourceRef="w2" targetRef="e2"/><endEvent id="e2"/>
              </process>
            </definitions>
            """.strip();

    private final List<CapturedInput> seen = new CopyOnWriteArrayList<>();
    private final List<String> unsupported = new CopyOnWriteArrayList<>();
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
    private OperatonEngineAdapter engine;
    private BusinessClock previous;

    @BeforeEach
    void zones() {
        // the tenant zone source, as the server installs it from the tenant profiles
        previous = BusinessClock.install(BusinessClock.of(Clock.systemUTC(), t -> "t1".equals(t) ? KOLKATA : null));
    }

    @AfterEach
    void stop() {
        if (engine != null) {
            engine.setClock(null);
            engine.close();
        }
        BusinessClock.install(previous);
    }

    private OperatonEngineAdapter start() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:cap354" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        SangamEngineSettings settings = new SangamEngineSettings();
        settings.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        settings.setAllowMultipleEnginesPerJvm(true);
        settings.setEngineSchemaUpdate("true");
        settings.enableReplayCapture(listener, false);
        engine = SangamEngineFactory.build(ds, settings, "h2", List.of());
        engine.setClock(T0);
        engine.deploy("t1", "cap354", Map.of("cap.bpmn", BPMN.getBytes(StandardCharsets.UTF_8)));
        return engine;
    }

    @Test
    void aDateIsCapturedWithTheTenantZoneOnEveryPath() {
        OperatonEngineAdapter e = start();
        e.startProcess("t1", "cap", "bk-1", Map.of());
        ExternalWork w = e.fetchAndLock("w1", "pay", 1, Duration.ofMinutes(5)).get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valueDate", LocalDate.of(2026, 3, 31));
        e.completeExternal(w.id(), "w1", w.lockVersion(), result);
        e.correlateMessage("t1", "go", "bk-1", Map.of("due", LocalDateValueType.localDate(LocalDate.of(2026, 4, 1))));
        e.correlateMessage("t1", "open", "bk-2", Map.of("filed", LocalDate.of(2026, 2, 28)));

        assertEquals(List.of(), unsupported, "a date is never dropped");
        assertEquals(List.of(CapturedInput.EXTERNAL_TASK_RESULT, CapturedInput.MESSAGE_DELIVERED,
                CapturedInput.MESSAGE_DELIVERED), seen.stream().map(CapturedInput::type).toList());
        assertEquals(new LocalDateInZone(LocalDate.of(2026, 3, 31), KOLKATA), seen.get(0).payload().get("valueDate"));
        assertEquals(new LocalDateInZone(LocalDate.of(2026, 4, 1), KOLKATA), seen.get(1).payload().get("due"),
                "a typed date value too");
        assertEquals(new LocalDateInZone(LocalDate.of(2026, 2, 28), KOLKATA), seen.get(2).payload().get("filed"),
                "the message that starts an instance");
    }

    @Test
    void aSharedOrUnknownTenantIsUtcAsANamedZone() {
        CapturePoints c = CapturePoints.of(listener, () -> T0, t -> "t2".equals(t) ? ZoneOffset.UTC : null);
        assertEquals(ZoneId.of("UTC"), c.zoneOf(null), "shared: the UTC fallback");
        assertEquals(ZoneId.of("UTC"), c.zoneOf("t2"), "the offset Z is recorded as the named zone UTC");
        assertEquals(ZoneId.of("UTC"), CapturePoints.of(listener, () -> T0).zoneOf("nobody"),
                "BusinessClock's fallback for an unknown tenant");
        assertEquals(KOLKATA, CapturePoints.of(listener, () -> T0).zoneOf("t1"));
        Map<String, Object> p = new LinkedHashMap<>();
        c.putVariables(p, Map.of("d", LocalDate.of(2026, 1, 26)), "test", null);
        assertEquals(new LocalDateInZone(LocalDate.of(2026, 1, 26), ZoneId.of("UTC")), p.get("d"));
    }

    @Test
    void anInputCarriesALocalDateInZoneAndNothingWithoutAZone() {
        CapturedInput in = new CapturedInput(CapturedInput.CLOCK_READ, null, null, null, 1, T0, null, null,
                Map.of("d", new LocalDateInZone(LocalDate.of(2026, 3, 31), KOLKATA)));
        assertEquals(KOLKATA, ((LocalDateInZone) in.payload().get("d")).zone());
        assertThrows(IllegalArgumentException.class, () -> new CapturedInput(CapturedInput.CLOCK_READ, null, null, null,
                1, T0, null, null, Map.of("d", LocalDate.of(2026, 3, 31))), "a bare LocalDate has no zone");
        assertThrows(NullPointerException.class, () -> new LocalDateInZone(LocalDate.of(2026, 3, 31), null));
        assertTrue(CapturePoints.replayable(LocalDate.of(2026, 3, 31)) == null, "replayable alone has no zone");
    }
}
