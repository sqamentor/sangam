// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.history;

import java.util.Date;

import org.operaton.bpm.engine.impl.history.producer.CacheAwareHistoryEventProducer;

/**
 * M-012: Operaton's default BPMN history event producer, with one difference. Creating a root's
 * {@code PROCESS_INSTANCE_END} event also <b>writes</b>: under the "end" removal-time strategy it sets the removal time
 * of every {@code ACT_HI_*} row of the root's tree ({@code DefaultHistoryEventProducer.createProcessInstanceEndEvt} →
 * {@code addRemovalTimeToHistoricProcessInstances} / {@code addRemovalTimeToHistoricDecisions}, javap on Operaton
 * 2.2.0). When the end event exists only for the mandatory audit ({@link MandatoryAudit}), the configured level would
 * not have created it, so those writes are skipped and the history rows stay what the configured level wrote.
 */
public class MandatoryAuditHistoryEventProducer extends CacheAwareHistoryEventProducer {

    static final String END = "PROCESS_INSTANCE_END";

    @Override
    protected void addRemovalTimeToHistoricProcessInstances(String rootProcessInstanceId, Date removalTime) {
        if (!MandatoryAudit.peek(END)) {
            super.addRemovalTimeToHistoricProcessInstances(rootProcessInstanceId, removalTime);
        }
    }

    @Override
    protected void addRemovalTimeToHistoricDecisions(String rootProcessInstanceId, Date removalTime) {
        if (!MandatoryAudit.peek(END)) {
            super.addRemovalTimeToHistoricDecisions(rootProcessInstanceId, removalTime);
        }
    }
}
