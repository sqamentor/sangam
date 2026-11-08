// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.history;

import java.util.Set;

import org.operaton.bpm.engine.impl.history.event.HistoricActivityInstanceEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoricCaseActivityInstanceEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoricCaseInstanceEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoricProcessInstanceEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoricTaskInstanceEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoricVariableUpdateEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoryEvent;
import org.operaton.bpm.engine.impl.history.handler.DbHistoryEventHandler;

/**
 * IR-5 "outbox-only history" per definition (SCALE-ARCHITECTURE, FLOWABLE-INTO-OPERATON FC-25, FLOWABLE-INTO-OPERATON D-41): replaces Operaton's default
 * {@link DbHistoryEventHandler}. For a definition listed in {@code outboxOnlyHistoryKeys}, the engine's {@code ACT_HI_*}
 * tables keep only the <b>audit level</b> (process / case instances, activity / case-activity instances, tasks,
 * variable instances); variable details, form properties, incidents, job and external-task logs, identity links,
 * decisions and user-operation logs of that definition are not written. Every event still reaches the Sangam history
 * handler and so the outbox → read model ({@code history-projector}), which keeps the full detail. Other definitions are
 * written exactly as Operaton writes them.
 *
 * <p>Requires an {@code EngineEventSink} that writes the outbox (WS-INTEGRATION); the plugin warns otherwise.
 */
public class OutboxOnlyDbHistoryEventHandler extends DbHistoryEventHandler {

    private final Set<String> outboxOnlyKeys;

    public OutboxOnlyDbHistoryEventHandler(Set<String> outboxOnlyKeys) {
        this.outboxOnlyKeys = Set.copyOf(outboxOnlyKeys);
    }

    @Override
    public void handleEvent(HistoryEvent event) {
        if (outboxOnly(event) && !auditLevel(event)) {
            return; // detail goes only through the outbox / read model
        }
        super.handleEvent(event);
    }

    @Override
    protected boolean shouldWriteHistoricDetail(HistoricVariableUpdateEventEntity event) {
        return !outboxOnly(event) && super.shouldWriteHistoricDetail(event);
    }

    private boolean outboxOnly(HistoryEvent e) {
        String key = e.getProcessDefinitionKey() != null ? e.getProcessDefinitionKey() : e.getCaseDefinitionKey();
        return key != null && outboxOnlyKeys.contains(key);
    }

    /** Events the AUDIT history level keeps (Operaton HistoryLevelAudit = ACTIVITY + variable instances). */
    static boolean auditLevel(HistoryEvent e) {
        return e instanceof HistoricVariableUpdateEventEntity
                || e instanceof HistoricProcessInstanceEventEntity
                || e instanceof HistoricCaseInstanceEventEntity
                || e instanceof HistoricActivityInstanceEventEntity
                || e instanceof HistoricCaseActivityInstanceEventEntity
                || e instanceof HistoricTaskInstanceEventEntity;
    }
}
