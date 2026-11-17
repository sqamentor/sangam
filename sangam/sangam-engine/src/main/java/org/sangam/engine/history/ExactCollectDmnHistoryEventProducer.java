// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.history;

import org.operaton.bpm.engine.impl.history.producer.DefaultDmnHistoryEventProducer;
import org.operaton.bpm.engine.variable.value.TypedValue;

import java.math.BigDecimal;

/**
 * ADR-DMN-ENGINE D1 / HIT-8: Sangam's COLLECT SUM, MIN and MAX return exact values
 * ({@code org.sangam.feel.DecisionTableRules}: {@code Long} when whole, otherwise {@code BigDecimal}, as untyped
 * values). Operaton's history column for an aggregate ({@code ACT_HI_DECINST.COLLECT_VALUE_}) is a double, and its
 * producer refuses any value that is not an Integer, Long or Double typed value. This producer stores the aggregate
 * there as a double (a display copy); the exact aggregate is the decision result, and each matched rule's exact
 * output is kept in {@code ACT_HI_DEC_OUT}, so the value is reproducible from history.
 */
public class ExactCollectDmnHistoryEventProducer extends DefaultDmnHistoryEventProducer {

    @Override
    protected Double getCollectResultValue(TypedValue collectResultValue) {
        Object v = collectResultValue == null ? null : collectResultValue.getValue();
        if (v instanceof BigDecimal b) {
            return b.doubleValue();
        }
        if (v instanceof Long l) {
            return l.doubleValue();
        }
        return super.getCollectResultValue(collectResultValue);
    }
}
