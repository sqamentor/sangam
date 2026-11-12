// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.history;

import java.util.Set;

import org.operaton.bpm.engine.impl.history.HistoryLevel;
import org.operaton.bpm.engine.impl.history.event.HistoryEventType;

/**
 * M-012 (X.2.3, T62): class-B audit cannot be switched off by the history level. Wraps the configured level and keeps
 * its id and name (so {@code ACT_GE_PROPERTY.historyLevel} and every id comparison stay what they were). For a type in
 * the audited set ({@code SangamEngineSettings.auditedHistoryEvents}) it always answers {@code true}, so Operaton
 * creates the event; when the configured level would have said no, it records that the event is <b>audit-only</b>
 * ({@link MandatoryAudit}). {@link MandatoryAuditHistoryEventHandler} then hands that event to the audit chain only:
 * no {@code ACT_HI_*} row and no typed engine event, exactly as the configured level decided. All other types are
 * answered by the configured level unchanged.
 *
 * <p>Calls with a {@code null} entity are Operaton's parse-time questions ("is a process-end listener needed?"); they
 * get {@code true} for audited types so the listeners exist, and record nothing.
 */
public final class MandatoryAuditHistoryLevel implements HistoryLevel {

    private final HistoryLevel configured;
    private final Set<String> audited;

    public MandatoryAuditHistoryLevel(HistoryLevel configured, Set<String> auditedTypeNames) {
        this.configured = configured;
        this.audited = Set.copyOf(auditedTypeNames);
    }

    public HistoryLevel configured() {
        return configured;
    }

    @Override
    public int getId() {
        return configured.getId();
    }

    @Override
    public String getName() {
        return configured.getName();
    }

    @Override
    public boolean isHistoryEventProduced(HistoryEventType eventType, Object entity) {
        boolean byLevel = configured.isHistoryEventProduced(eventType, entity);
        if (eventType == null || audited.isEmpty()) {
            return byLevel;
        }
        String name = MandatoryAudit.name(eventType);
        if (!audited.contains(name)) {
            return byLevel;
        }
        if (entity != null) {
            MandatoryAudit.record(name, !byLevel);
        }
        return true;
    }

    @Override
    public String toString() {
        return "MandatoryAudit(" + configured.getName() + ")";
    }
}
