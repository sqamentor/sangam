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
        install(this);
    }

    /**
     * Registers the event-listener item handler in an existing registry, in place (SL-12). This is how the plugin
     * installs it: Operaton builds its CMMN deployer twice ({@code initDeployers}: once for
     * {@code configuration.deployers}, once more for the deployment cache, which is the one that actually deploys),
     * and both transformers share the configuration's single {@code cmmnElementHandlerRegistry}. Replacing the
     * registry on one transformer (the former approach) reached only the unused copy, so event listeners deployed with
     * no handler and never fired; mutating the shared registry reaches every CMMN deployer of the engine.
     */
    public static void install(DefaultCmmnElementHandlerRegistry registry) {
        registry.getPlanItemElementHandlers().put(EventListener.class, new SangamEventListenerItemHandler());
    }

    /** The item handler: Operaton's, with the Sangam behaviour. */
    public static class SangamEventListenerItemHandler extends EventListenerItemHandler {
        @Override
        protected CmmnActivityBehavior getActivityBehavior() {
            return new SangamEventListenerActivityBehavior();
        }
    }
}
