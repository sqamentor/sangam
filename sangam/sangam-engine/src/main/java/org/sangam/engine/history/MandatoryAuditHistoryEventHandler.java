// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.history;

import java.util.List;

import org.operaton.bpm.engine.impl.history.event.HistoryEvent;
import org.operaton.bpm.engine.impl.history.handler.HistoryEventHandler;

/**
 * M-012 (X.2.3, T62): the engine's history event handler, installed in {@code postInit} around the one Operaton built
 * from the default DB handler and every custom handler (Sangam's, other plugins'). An event that exists only for the
 * mandatory audit ({@link MandatoryAudit}) goes to the audit chain and nowhere else: no {@code ACT_HI_*} row, no typed
 * engine event, no projection. Every other event goes to the wrapped handler unchanged.
 */
public final class MandatoryAuditHistoryEventHandler implements HistoryEventHandler {

    private final HistoryEventHandler delegate;
    private final SangamHistoryEventHandler audit;

    public MandatoryAuditHistoryEventHandler(HistoryEventHandler delegate, SangamHistoryEventHandler audit) {
        this.delegate = delegate;
        this.audit = audit;
    }

    public HistoryEventHandler delegate() {
        return delegate;
    }

    @Override
    public void handleEvent(HistoryEvent e) {
        if (MandatoryAudit.consume(SangamHistoryEventHandler.typeName(e))) {
            audit.handleAuditOnly(e);
        } else {
            delegate.handleEvent(e);
        }
    }

    @Override
    public void handleEvents(List<HistoryEvent> events) {
        boolean[] auditOnly = new boolean[events.size()];
        boolean any = false;
        for (int i = 0; i < auditOnly.length; i++) {
            auditOnly[i] = MandatoryAudit.consume(SangamHistoryEventHandler.typeName(events.get(i)));
            any |= auditOnly[i];
        }
        if (!any) {
            delegate.handleEvents(events); // unchanged path, unchanged order
            return;
        }
        for (int i = 0; i < auditOnly.length; i++) {
            if (auditOnly[i]) {
                audit.handleAuditOnly(events.get(i));
            } else {
                delegate.handleEvent(events.get(i));
            }
        }
    }
}
