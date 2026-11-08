// SPDX-License-Identifier: Apache-2.0
package org.sangam.casefile.cmmn;

import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cmmn.transformer.CmmnTransformListener;
import org.operaton.bpm.engine.ProcessEngine;
import org.sangam.casefile.CaseModule;

import java.util.ArrayList;
import java.util.List;

/**
 * ADR-D-16 registration of the case module: adds {@link CaseFileCmmnListener} as a post CMMN transform listener and,
 * when given, installs the {@link CaseModule} for the delegates. Register after WS-ENGINE's and WS-PORTS' plugins.
 */
public class CaseFileEnginePlugin implements ProcessEnginePlugin {

    private final CaseModule module;
    private final boolean closeFileWithCase;

    /** @param moduleOrNull installed at engine build; {@code null} when sangam-server installs it itself */
    public CaseFileEnginePlugin(CaseModule moduleOrNull, boolean closeFileWithCase) {
        this.module = moduleOrNull;
        this.closeFileWithCase = closeFileWithCase;
    }

    @Override
    public void preInit(ProcessEngineConfigurationImpl cfg) {
        List<CmmnTransformListener> cmmn = cfg.getCustomPostCmmnTransformListeners() == null ? new ArrayList<>()
                : new ArrayList<>(cfg.getCustomPostCmmnTransformListeners());
        cmmn.add(new CaseFileCmmnListener(closeFileWithCase));
        cfg.setCustomPostCmmnTransformListeners(cmmn);
    }

    @Override
    public void postInit(ProcessEngineConfigurationImpl cfg) {
        if (module != null) {
            CaseModule.install(module);
        }
    }

    @Override
    public void postProcessEngineBuild(ProcessEngine engine) {
    }
}
