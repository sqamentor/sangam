// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.plugins;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.operaton.bpm.dmn.feel.impl.scala.function.CustomFunction;
import org.operaton.bpm.dmn.feel.impl.scala.function.FeelCustomFunctionProvider;
import org.operaton.bpm.dmn.feel.impl.scala.function.builder.CustomFunctionBuilder;
import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.sangam.core.plugin.DmnFunctionProvider;

/** Adds the plugins' FEEL functions to whatever providers earlier engine plugins registered. */
public final class PluginFeelEnginePlugin extends AbstractProcessEnginePlugin {

    private final Map<String, DmnFunctionProvider.DmnFunction> functions;

    public PluginFeelEnginePlugin(Map<String, DmnFunctionProvider.DmnFunction> functions) {
        this.functions = functions;
    }

    @Override
    public void preInit(ProcessEngineConfigurationImpl cfg) {
        if (functions.isEmpty()) {
            return;
        }
        List<FeelCustomFunctionProvider> all = new ArrayList<>();
        if (cfg.getDmnFeelCustomFunctionProviders() != null) {
            all.addAll(cfg.getDmnFeelCustomFunctionProviders());
        }
        all.add(new FeelCustomFunctionProvider() {
            @Override
            public Optional<CustomFunction> resolveFunction(String name) {
                DmnFunctionProvider.DmnFunction f = functions.get(name);
                if (f == null) {
                    return Optional.empty();
                }
                CustomFunctionBuilder b = CustomFunction.create().setParams(f.params().toArray(new String[0]))
                        .setFunction(f.body());
                if (f.varargs()) {
                    b = b.enableVarargs();
                }
                return Optional.of(b.build());
            }

            @Override
            public Collection<String> getFunctionNames() {
                return functions.keySet();
            }
        });
        cfg.setDmnFeelCustomFunctionProviders(all);
    }
}
