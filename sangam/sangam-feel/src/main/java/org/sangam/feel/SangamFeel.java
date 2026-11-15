// SPDX-License-Identifier: Apache-2.0
package org.sangam.feel;

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

    /** Decision 151: a standalone Sangam FEEL engine whose {@code now()} and {@code today()} read {@code clock}. */
    public static FeelEngine feelEngine(FeelClock clock) {
        return new SangamFeelEngine(List.of(), java.util.Objects.requireNonNull(clock, "clock"));
    }

    public static DefaultDmnEngineConfiguration install(DefaultDmnEngineConfiguration dmn) {
        return install(dmn, null, false);
    }

    /**
     * Decision 151: as {@link #install(DefaultDmnEngineConfiguration)}, and FEEL {@code now()} and {@code today()}
     * read {@code clock} (a replay capture or a replay engine; never needed in production).
     */
    public static DefaultDmnEngineConfiguration install(DefaultDmnEngineConfiguration dmn, FeelClock clock) {
        return install(dmn, java.util.Objects.requireNonNull(clock, "clock"), true);
    }

    private static DefaultDmnEngineConfiguration install(DefaultDmnEngineConfiguration dmn, FeelClock clock,
                                                         boolean setClock) {
        JacksonCompatibilityGuard.check(); // SG-33: fail fast before the FEEL engine (jackson-module-scala) is built
        // an existing Sangam factory (and its clock) is kept by a plain install; a clock install replaces it
        if (setClock || !(dmn.getFeelEngineFactory() instanceof SangamFeelEngineFactory)) {
            dmn.setFeelEngineFactory(new SangamFeelEngineFactory(dmn, clock));
        }
        List<DmnDecisionEvaluationListener> l = dmn.getCustomPreDecisionEvaluationListeners() == null
                ? new ArrayList<>() : new ArrayList<>(dmn.getCustomPreDecisionEvaluationListeners());
        if (l.stream().noneMatch(StrictNullInputListener.class::isInstance)) {
            l.add(0, new StrictNullInputListener());
        }
        dmn.setCustomPreDecisionEvaluationListeners(l);
        Dmn16Support.install(dmn); // decision 158: DMN 1.6 documents (namespace 20240513), validated as DMN 1.5 form
        dmn.getTransformer().getDataTypeTransformerRegistry()
                .addTransformer(NumberDataTypeTransformer.TYPE, new NumberDataTypeTransformer());
        var types = dmn.getTransformer().getDataTypeTransformerRegistry();
        var dateType = types.getTransformer(DateDataTypeTransformer.TYPE);
        if (!(dateType instanceof DateDataTypeTransformer)) { // a repeated install keeps the one wrapper
            types.addTransformer(DateDataTypeTransformer.TYPE, new DateDataTypeTransformer(dateType));
        }
        return dmn;
    }

    /**
     * FEEL engine SangamFeelEngineFactory; reads the configuration's custom function providers when the engine is created (the
     * process engine sets them after plugins ran).
     */
    public static final class SangamFeelEngineFactory implements FeelEngineFactory {
        private final DefaultDmnEngineConfiguration dmn;
        private final FeelClock clock;

        SangamFeelEngineFactory(DefaultDmnEngineConfiguration dmn, FeelClock clock) {
            this.dmn = dmn;
            this.clock = clock;
        }

        /** Decision 151: the FEEL clock of the engines this factory creates; null = feel-scala's default. */
        public FeelClock clock() {
            return clock;
        }

        @Override
        public FeelEngine createInstance() {
            List<FeelCustomFunctionProvider> p = dmn.getFeelCustomFunctionProviders();
            return new SangamFeelEngine(p == null ? List.of() : p, clock);
        }
    }
}
