// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.sangam.core.layer.Dialect;
import org.sangam.ports.PortsH2Engine;
import org.sangam.ports.events.InboundEventProcessor.StoredEvent;
import org.sangam.ports.support.EngineTx;
import org.sangam.packs.engine.DecimalValueSerializer;
import org.sangam.packs.engine.LocalDateValueSerializer;

/**
 * G27: a {@code string} correlation parameter (Flowable's event payload types have no decimal or date type) matches a
 * process variable stored as the engine's decimal or local-date type when the string is exactly that variable's
 * canonical text; a string variable keeps matching; any other text, and an engine without those types, match as
 * before.
 */
class EventDispatcherTypedMatchH2Test {

    private static final String WAIT = """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
              <message id="m" name="paid"/>
              <process id="order" isExecutable="true">
                <startEvent id="s"/><sequenceFlow id="f1" sourceRef="s" targetRef="w"/>
                <intermediateCatchEvent id="w"><messageEventDefinition messageRef="m"/></intermediateCatchEvent>
                <sequenceFlow id="f2" sourceRef="w" targetRef="after"/>
                <userTask id="after"/><sequenceFlow id="f3" sourceRef="after" targetRef="e"/><endEvent id="e"/>
              </process>
            </definitions>""";

    /** What SangamProcessEnginePlugin registers on every Sangam engine (decisions 138 and 333). */
    static final class SangamTypes extends AbstractProcessEnginePlugin {
        @Override
        public void preInit(ProcessEngineConfigurationImpl cfg) {
            DecimalValueSerializer.register(cfg);
            LocalDateValueSerializer.register(cfg);
        }
    }

    private PortsH2Engine h2;
    private EventRegistry reg;
    private EventDispatcher dispatcher;

    private void start(boolean sangamTypes) {
        h2 = sangamTypes ? PortsH2Engine.start(null, new SangamTypes()) : PortsH2Engine.start(null);
        reg = new EventRegistry(Dialect.POSTGRESQL, EngineTx.transactions(h2.engine), Duration.ZERO);
        dispatcher = new EventDispatcher(h2.engine, reg, new EventLog(Dialect.POSTGRESQL, false), null, null);
        h2.engine.getRepositoryService().createDeployment().addString("w.bpmn", WAIT).deploy();
        reg.deployEvent("", "{\"key\":\"paid\",\"correlationParameters\":[{\"name\":\"due\",\"type\":\"string\"},"
                + "{\"name\":\"amount\",\"type\":\"string\"},{\"name\":\"ref\",\"type\":\"string\"}]}", null);
    }

    @AfterEach
    void tearDown() {
        if (h2 != null) {
            h2.close();
        }
    }

    private String instance(Map<String, Object> vars) {
        return h2.engine.getRuntimeService().startProcessInstanceByKey("order", vars).getId();
    }

    /** The process instances the event reached. */
    private Set<String> deliver(Map<String, Object> corr) {
        StoredEvent ev = new StoredEvent("ch", "", "paid", "msg-" + System.nanoTime(), Map.of(), corr, null);
        var def = reg.event("", "paid").orElseThrow().definition();
        Set<String> before = waitingInstances();
        EngineTx.execute(h2.engine, cc -> dispatcher.deliver(EngineTx.connection(), ev, def));
        Set<String> reached = new java.util.HashSet<>(before);
        reached.removeAll(waitingInstances());
        return reached;
    }

    private Set<String> waitingInstances() {
        return h2.engine.getRuntimeService().createExecutionQuery().messageEventSubscriptionName("paid").list().stream()
                .map(e -> e.getProcessInstanceId()).collect(Collectors.toSet());
    }

    @Test
    void aStringMatchesATypedDateOrDecimalByItsCanonicalText() {
        start(true);
        String typedDay = instance(Map.of("due", LocalDate.of(2026, 3, 31)));
        String textDay = instance(Map.of("due", "2026-03-31"));
        String otherDay = instance(Map.of("due", LocalDate.of(2026, 4, 1)));
        assertEquals(Set.of(typedDay, textDay), deliver(Map.of("due", "2026-03-31")),
                "a typed LocalDate and a string variable with that text both match");

        String amount = instance(Map.of("amount", new BigDecimal("1250.50")));
        assertEquals(Set.of(), deliver(Map.of("amount", "1250.5")), "value and scale, as the decimal type's equality");
        assertEquals(Set.of(), deliver(Map.of("amount", "1.25050E+3")), "only the canonical text");
        assertEquals(Set.of(amount), deliver(Map.of("amount", "1250.50")));

        String thousand = instance(Map.of("amount", new BigDecimal("1E+3")));
        assertEquals(Set.of(), deliver(Map.of("amount", "1000")));
        assertEquals(Set.of(thousand), deliver(Map.of("amount", "1E+3")));
        assertTrue(waitingInstances().contains(otherDay));
    }

    @Test
    void everyParameterMustMatchAndOtherTextIsUnchanged() {
        start(true);
        String both = instance(Map.of("due", LocalDate.of(2026, 3, 31), "ref", "R-1"));
        String wrongRef = instance(Map.of("due", LocalDate.of(2026, 3, 31), "ref", "R-2"));
        String wrongDay = instance(Map.of("due", LocalDate.of(2026, 3, 30), "ref", "R-1"));
        assertEquals(Set.of(both), deliver(Map.of("due", "2026-03-31", "ref", "R-1")));
        assertEquals(Set.of(), deliver(Map.of("due", "2026-3-30", "ref", "R-1")), "not format v1: no date match");
        assertEquals(Set.of(wrongDay), deliver(Map.of("due", "2026-03-30", "ref", "R-1")));
        assertTrue(waitingInstances().contains(wrongRef));
    }

    @Test
    void anEngineWithoutTheSangamTypesMatchesStringsAsBefore() {
        start(false);
        String textDay = instance(Map.of("due", "2026-03-31"));
        assertEquals(List.of(), EventDispatcher.storedTypeAlternatives(h2.engine, "2026-03-31"));
        assertEquals(Set.of(textDay), deliver(Map.of("due", "2026-03-31")));
    }
}
