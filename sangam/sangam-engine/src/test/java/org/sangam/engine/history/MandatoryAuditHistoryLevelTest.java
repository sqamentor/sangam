// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.impl.history.HistoryLevel;
import org.operaton.bpm.engine.impl.history.event.HistoricIncidentEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoricProcessInstanceEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoryEvent;
import org.operaton.bpm.engine.impl.history.event.HistoryEventTypes;
import org.operaton.bpm.engine.impl.history.handler.HistoryEventHandler;
import org.sangam.core.engine.AuditSink;
import org.sangam.core.engine.EngineEventSink;

/** M-012 unit level: the wrapper's answers and records, and the routing of audit-only events. */
class MandatoryAuditHistoryLevelTest {

    private static final Set<String> AUDITED = Set.of("PROCESS_INSTANCE_START", "PROCESS_INSTANCE_END", "INCIDENT_CREATE");
    private static final Object ENTITY = new Object();

    @AfterEach
    void clear() {
        MandatoryAudit.clear();
    }

    @Test
    void idAndNameAreTheConfiguredLevels() {
        for (HistoryLevel l : List.of(HistoryLevel.HISTORY_LEVEL_NONE, HistoryLevel.HISTORY_LEVEL_ACTIVITY,
                HistoryLevel.HISTORY_LEVEL_AUDIT, HistoryLevel.HISTORY_LEVEL_FULL)) {
            MandatoryAuditHistoryLevel w = new MandatoryAuditHistoryLevel(l, AUDITED);
            assertEquals(l.getId(), w.getId(), "ACT_GE_PROPERTY historyLevel id unchanged");
            assertEquals(l.getName(), w.getName(), "the configured name still selects it");
        }
        PerDefinitionHistoryLevel pd = new PerDefinitionHistoryLevel(HistoryLevel.HISTORY_LEVEL_FULL, Map.of("k", "none"));
        assertEquals(PerDefinitionHistoryLevel.ID, new MandatoryAuditHistoryLevel(pd, AUDITED).getId());
        assertEquals(PerDefinitionHistoryLevel.NAME, new MandatoryAuditHistoryLevel(pd, AUDITED).getName());
    }

    @Test
    void anAuditedTypeTheLevelRefusesIsProducedAsAuditOnly() {
        MandatoryAuditHistoryLevel w = new MandatoryAuditHistoryLevel(HistoryLevel.HISTORY_LEVEL_AUDIT, AUDITED);
        assertFalse(HistoryLevel.HISTORY_LEVEL_AUDIT.isHistoryEventProduced(HistoryEventTypes.INCIDENT_CREATE, ENTITY),
                "precondition: Operaton's audit level has no incident events");
        assertTrue(w.isHistoryEventProduced(HistoryEventTypes.INCIDENT_CREATE, ENTITY));
        assertTrue(MandatoryAudit.peek("INCIDENT_CREATE"));
        assertTrue(MandatoryAudit.consume("INCIDENT_CREATE"));
        assertFalse(MandatoryAudit.consume("INCIDENT_CREATE"), "consumed once");
    }

    @Test
    void anAuditedTypeTheLevelProducesIsNotAuditOnly() {
        MandatoryAuditHistoryLevel w = new MandatoryAuditHistoryLevel(HistoryLevel.HISTORY_LEVEL_AUDIT, AUDITED);
        assertTrue(w.isHistoryEventProduced(HistoryEventTypes.PROCESS_INSTANCE_START, ENTITY));
        assertFalse(MandatoryAudit.consume("PROCESS_INSTANCE_START"), "a normal event: written as before");
    }

    @Test
    void otherTypesFollowTheConfiguredLevel() {
        MandatoryAuditHistoryLevel w = new MandatoryAuditHistoryLevel(HistoryLevel.HISTORY_LEVEL_ACTIVITY, AUDITED);
        for (HistoryEventTypes t : HistoryEventTypes.values()) {
            if (!AUDITED.contains(t.name())) {
                assertEquals(HistoryLevel.HISTORY_LEVEL_ACTIVITY.isHistoryEventProduced(t, ENTITY),
                        w.isHistoryEventProduced(t, ENTITY), t.name());
                assertFalse(MandatoryAudit.consume(t.name()), "no record for " + t.name());
            }
        }
    }

    @Test
    void parseTimeQuestionsInstallTheListenersButRecordNothing() {
        MandatoryAuditHistoryLevel w = new MandatoryAuditHistoryLevel(
                new PerDefinitionHistoryLevel(HistoryLevel.HISTORY_LEVEL_NONE, Map.of("k", "full")), AUDITED);
        assertTrue(w.isHistoryEventProduced(HistoryEventTypes.PROCESS_INSTANCE_END, null));
        assertFalse(MandatoryAudit.consume("PROCESS_INSTANCE_END"));
    }

    @Test
    void anEmptyAuditedSetIsTheConfiguredLevelExactly() {
        MandatoryAuditHistoryLevel w = new MandatoryAuditHistoryLevel(HistoryLevel.HISTORY_LEVEL_AUDIT, Set.of());
        for (HistoryEventTypes t : HistoryEventTypes.values()) {
            assertEquals(HistoryLevel.HISTORY_LEVEL_AUDIT.isHistoryEventProduced(t, ENTITY),
                    w.isHistoryEventProduced(t, ENTITY), t.name());
            assertFalse(MandatoryAudit.consume(t.name()));
        }
    }

    @Test
    void theHandlerRoutesAuditOnlyEventsToTheChainOnly() {
        List<HistoryEvent> delegated = new ArrayList<>();
        List<HistoryEvent> auditOnly = new ArrayList<>();
        HistoryEventHandler delegate = new HistoryEventHandler() {
            @Override
            public void handleEvent(HistoryEvent e) {
                delegated.add(e);
            }

            @Override
            public void handleEvents(List<HistoryEvent> es) {
                delegated.addAll(es);
            }
        };
        SangamHistoryEventHandler chain = new SangamHistoryEventHandler(AuditSink.NONE, EngineEventSink.NONE,
                AUDITED, Set.of(), "default") {
            @Override
            public void handleAuditOnly(HistoryEvent e) {
                auditOnly.add(e);
            }
        };
        MandatoryAuditHistoryEventHandler gate = new MandatoryAuditHistoryEventHandler(delegate, chain);
        MandatoryAuditHistoryLevel w = new MandatoryAuditHistoryLevel(HistoryLevel.HISTORY_LEVEL_AUDIT, AUDITED);

        HistoricIncidentEventEntity incident = new HistoricIncidentEventEntity();
        incident.setEventType(HistoryEventTypes.INCIDENT_CREATE.getEventName());
        assertTrue(w.isHistoryEventProduced(HistoryEventTypes.INCIDENT_CREATE, ENTITY));
        gate.handleEvent(incident);
        assertEquals(List.of(incident), auditOnly, "audit-only: the chain only");
        assertTrue(delegated.isEmpty(), "no ACT_HI_* row, no typed event");

        HistoricProcessInstanceEventEntity start = new HistoricProcessInstanceEventEntity();
        start.setEventType(HistoryEventTypes.PROCESS_INSTANCE_START.getEventName());
        assertTrue(w.isHistoryEventProduced(HistoryEventTypes.PROCESS_INSTANCE_START, ENTITY));
        gate.handleEvents(List.of(start));
        assertEquals(List.of(start), delegated, "a normal event goes the old way");
        assertEquals(1, auditOnly.size());
    }
}
