// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.feel;

import java.util.ArrayList;
import java.util.List;

import org.operaton.bpm.dmn.engine.DmnEngineConfiguration;
import org.operaton.bpm.dmn.engine.delegate.DmnDecisionEvaluationListener;
import org.operaton.bpm.dmn.engine.impl.DefaultDmnEngineConfiguration;
import org.operaton.bpm.dmn.feel.impl.FeelEngine;
import org.operaton.bpm.dmn.feel.impl.FeelEngineFactory;
import org.operaton.bpm.dmn.feel.impl.scala.function.FeelCustomFunctionProvider;

/**
 * ADR-D-26: the single place a DMN engine configuration gets Sangam's FEEL policies (exact numbers, strict
 * evaluation, strict null input). Every Sangam DMN engine goes through here: process engines through
 * {@code SangamProcessEnginePlugin} (Spring server and {@code SangamEngineFactory}), standalone DMN engines through
 * {@link #dmnEngineConfiguration()}. Idempotent.
 */
public final class SangamFeel {

    private SangamFeel() {
    }

    /** A default DMN engine configuration with the Sangam FEEL policies installed. */
    public static DefaultDmnEngineConfiguration dmnEngineConfiguration() {
        return install((DefaultDmnEngineConfiguration) DmnEngineConfiguration.createDefaultDmnEngineConfiguration());
    }

    /** A standalone Sangam FEEL engine (no custom functions). */
    public static FeelEngine feelEngine() {
        return new SangamFeelEngine(List.of());
    }

    public static DefaultDmnEngineConfiguration install(DefaultDmnEngineConfiguration dmn) {
        if (!(dmn.getFeelEngineFactory() instanceof SangamFeelEngineFactory)) {
            dmn.setFeelEngineFactory(new SangamFeelEngineFactory(dmn));
        }
        List<DmnDecisionEvaluationListener> l = dmn.getCustomPreDecisionEvaluationListeners() == null
                ? new ArrayList<>() : new ArrayList<>(dmn.getCustomPreDecisionEvaluationListeners());
        if (l.stream().noneMatch(StrictNullInputListener.class::isInstance)) {
            l.add(0, new StrictNullInputListener());
        }
        dmn.setCustomPreDecisionEvaluationListeners(l);
        dmn.getTransformer().getDataTypeTransformerRegistry()
                .addTransformer(NumberDataTypeTransformer.TYPE, new NumberDataTypeTransformer());
        return dmn;
    }

    /**
     * FEEL engine SangamFeelEngineFactory; reads the configuration's custom function providers when the engine is created (the
     * process engine sets them after plugins ran).
     */
    public static final class SangamFeelEngineFactory implements FeelEngineFactory {
        private final DefaultDmnEngineConfiguration dmn;

        SangamFeelEngineFactory(DefaultDmnEngineConfiguration dmn) {
            this.dmn = dmn;
        }

        @Override
        public FeelEngine createInstance() {
            List<FeelCustomFunctionProvider> p = dmn.getFeelCustomFunctionProviders();
            return new SangamFeelEngine(p == null ? List.of() : p);
        }
    }
}
