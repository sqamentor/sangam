// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.cmmn;

import org.operaton.bpm.engine.impl.cmmn.behavior.CmmnActivityBehavior;
import org.operaton.bpm.engine.impl.cmmn.handler.DefaultCmmnElementHandlerRegistry;
import org.operaton.bpm.engine.impl.cmmn.handler.EventListenerItemHandler;
import org.operaton.bpm.model.cmmn.instance.EventListener;

/**
 * FLOWABLE-INTO-OPERATON FC-55 / FLOWABLE-INTO-OPERATON D-08: Operaton 2.2.0 leaves CMMN event listeners unregistered
 * ({@code DefaultCmmnElementHandlerRegistry}: "EventListener is currently not supported"), so a timer or user event
 * listener deploys silently and never fires (GP-21). This registry, installed with
 * {@code CmmnTransformer.setCmmnElementHandlerRegistry} (route 1 on an impl class), registers an item handler whose
 * behaviour implements the missing {@code created} step: a timer listener schedules a case timer job
 * ({@link CmmnTimerJobHandler}); a user/generic listener waits for {@link CmmnEventListenerService#occur}.
 */
public class SangamCmmnElementHandlerRegistry extends DefaultCmmnElementHandlerRegistry {

    public SangamCmmnElementHandlerRegistry() {
        super();
        planItemElementHandlers.put(EventListener.class, new SangamEventListenerItemHandler());
    }

    /** The item handler: Operaton's, with the Sangam behaviour. */
    public static class SangamEventListenerItemHandler extends EventListenerItemHandler {
        @Override
        protected CmmnActivityBehavior getActivityBehavior() {
            return new SangamEventListenerActivityBehavior();
        }
    }
}
