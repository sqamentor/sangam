// SPDX-License-Identifier: Apache-2.0
package org.sangam.assembly.engine;

import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;

/**
 * The last engine plugin ({@link EngineAssembly#orderPlugins}): re-pins the database type the database guard detected,
 * which a host's own datasource plugin would otherwise overwrite (it is never inferred from the driver), and keeps
 * Operaton's own authorization off, because the product authorizes every call.
 */
public final class EngineSettingsReapplyPlugin extends AbstractProcessEnginePlugin {

    private final String databaseType;

    public EngineSettingsReapplyPlugin(String databaseType) {
        this.databaseType = databaseType;
    }

    public String databaseType() {
        return databaseType;
    }

    @Override
    public void preInit(ProcessEngineConfigurationImpl cfg) {
        if (databaseType != null) {
            cfg.setDatabaseType(databaseType);
        }
        cfg.setAuthorizationEnabled(false);
    }

    @Override
    public String toString() {
        return "SangamEngineSettingsReapply[" + databaseType + "]";
    }
}
