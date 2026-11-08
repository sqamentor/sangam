// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.bridge.operaton;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.operaton.bpm.engine.impl.history.handler.HistoryEventHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code ProcessEnginePlugin} of the integration layer (ADR-D-16: behaviour added only through plugins). Registers the
 * {@link OutboxHistoryEventHandler} as an additional custom history handler (the default DB handler stays on). The
 * connector delegates need no registration: they are referenced by class name or as beans.
 */
public final class SangamIntegrationEnginePlugin implements ProcessEnginePlugin {

    private final OutboxHistoryEventHandler historyToOutbox;

    /** @param historyToOutboxOrNull null disables the history → outbox feed */
    public SangamIntegrationEnginePlugin(OutboxHistoryEventHandler historyToOutboxOrNull) {
        this.historyToOutbox = historyToOutboxOrNull;
    }

    @Override
    public void preInit(ProcessEngineConfigurationImpl configuration) {
        if (historyToOutbox == null) {
            return;
        }
        List<HistoryEventHandler> handlers = configuration.getCustomHistoryEventHandlers() == null ? new ArrayList<>()
                : new ArrayList<>(configuration.getCustomHistoryEventHandlers());
        handlers.add(historyToOutbox);
        configuration.setCustomHistoryEventHandlers(handlers);
        configuration.setEnableDefaultDbHistoryEventHandler(true);
    }

    @Override
    public void postInit(ProcessEngineConfigurationImpl configuration) {
    }

    @Override
    public void postProcessEngineBuild(ProcessEngine processEngine) {
    }
}
