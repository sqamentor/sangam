// SPDX-License-Identifier: Apache-2.0
package org.sangam.spike.dmn;

import org.operaton.bpm.dmn.engine.DmnEngine;
import org.operaton.bpm.dmn.engine.impl.DefaultDmnEngineConfiguration;
import org.operaton.bpm.dmn.engine.impl.transform.DefaultDmnTransformer;

import java.util.ArrayList;
import java.util.List;

/**
 * Option A spike: Operaton's DMN engine configuration with a Drools-backed decision logic. Operaton still parses
 * every model (deployment, DecisionDefinition rows, tenancy, history); a model that {@link Routing} sends to Drools
 * gets {@link DroolsDecisionLogic} per decision instead of Operaton's table / literal logic.
 */
public class HybridDmnEngineConfiguration extends DefaultDmnEngineConfiguration {

    /** Which models the Drools logic evaluates. */
    public enum Routing {
        /** only models that use a construct Operaton cannot execute (BKM, boxed expressions, decision service, import) */
        BOXED_ONLY,
        /** every model (Operaton keeps deployment and history; Drools evaluates) */
        ALL
    }

    private final Routing routing;
    private final List<byte[]> companionModels = new ArrayList<>();

    public HybridDmnEngineConfiguration(Routing routing) {
        this.routing = routing;
        setTransformer(new HybridTransformer((DefaultDmnTransformer) getTransformer(), this));
    }

    /** Operaton's transformer with the hybrid transform factory (same pattern as Sangam's Dmn16Support). */
    static final class HybridTransformer extends DefaultDmnTransformer {
        HybridTransformer(DefaultDmnTransformer base, HybridDmnEngineConfiguration configuration) {
            this.transformFactory = new HybridTransformFactory(configuration);
            this.transformListeners = base.getTransformListeners();
            this.elementTransformHandlerRegistry = base.getElementTransformHandlerRegistry();
            this.dataTypeTransformerRegistry = base.getDataTypeTransformerRegistry();
            this.hitPolicyHandlerRegistry = base.getHitPolicyHandlerRegistry();
        }
    }

    public Routing routing() {
        return routing;
    }

    /** Other DMN documents that a routed model may import (the same deployment, in the product). */
    public List<byte[]> companionModels() {
        return companionModels;
    }

    @Override
    public DmnEngine buildEngine() {
        init();
        return new HybridDmnEngine(this);
    }
}
