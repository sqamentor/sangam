// SPDX-License-Identifier: Apache-2.0
package org.sangam.geo.feel;

import org.operaton.bpm.dmn.feel.impl.scala.function.FeelCustomFunctionProvider;
import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code ProcessEnginePlugin} (ADR-D-16) that registers the spatial FEEL functions with the engine's DMN/FEEL
 * configuration, keeping any providers other plugins registered. No engine source is changed.
 */
public final class GeoEnginePlugin extends AbstractProcessEnginePlugin {

    private final GeoFeelFunctionProvider provider;

    public GeoEnginePlugin(GeoFeelFunctionProvider provider) {
        this.provider = provider;
    }

    @Override
    public void preInit(ProcessEngineConfigurationImpl cfg) {
        List<FeelCustomFunctionProvider> providers = new ArrayList<>();
        if (cfg.getDmnFeelCustomFunctionProviders() != null) {
            providers.addAll(cfg.getDmnFeelCustomFunctionProviders());
        }
        providers.add(provider);
        cfg.setDmnFeelCustomFunctionProviders(providers);
    }

    @Override
    public String toString() {
        return "GeoEnginePlugin" + provider.getFunctionNames();
    }
}
