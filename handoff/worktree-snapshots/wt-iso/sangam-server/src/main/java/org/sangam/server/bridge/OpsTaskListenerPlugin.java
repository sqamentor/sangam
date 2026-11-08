// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import java.util.ArrayList;
import java.util.List;

import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.sangam.ops.listener.GlobalTaskListeners;

/** Adds the global task listeners' parse listener to the engine, after the module plugins (see the customizer's ORDER). */
public final class OpsTaskListenerPlugin extends AbstractProcessEnginePlugin {

    private final GlobalTaskListeners listeners;

    public OpsTaskListenerPlugin(GlobalTaskListeners listeners) {
        this.listeners = listeners;
    }

    @Override
    public void preInit(ProcessEngineConfigurationImpl cfg) {
        List<BpmnParseListener> post = cfg.getCustomPostBPMNParseListeners();
        if (post == null) {
            post = new ArrayList<>();
            cfg.setCustomPostBPMNParseListeners(post);
        }
        post.add(listeners.parseListener());
    }
}
