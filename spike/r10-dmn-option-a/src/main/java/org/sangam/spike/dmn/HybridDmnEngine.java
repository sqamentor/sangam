// SPDX-License-Identifier: Apache-2.0
package org.sangam.spike.dmn;

import org.operaton.bpm.dmn.engine.DmnDecision;
import org.operaton.bpm.dmn.engine.DmnDecisionResult;
import org.operaton.bpm.dmn.engine.impl.DefaultDmnDecisionContext;
import org.operaton.bpm.dmn.engine.impl.DefaultDmnEngine;
import org.operaton.bpm.dmn.engine.impl.DefaultDmnEngineConfiguration;
import org.operaton.bpm.engine.variable.context.VariableContext;

import java.util.Objects;

/** Operaton's engine; the per-evaluation decision context also knows {@link DroolsDecisionLogic}. */
public class HybridDmnEngine extends DefaultDmnEngine {

    public HybridDmnEngine(DefaultDmnEngineConfiguration configuration) {
        super(configuration);
    }

    @Override
    public DmnDecisionResult evaluateDecision(DmnDecision decision, VariableContext variableContext) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(variableContext, "variableContext");
        return new HybridDecisionContext(dmnEngineConfiguration).evaluateDecision(decision, variableContext);
    }

    static final class HybridDecisionContext extends DefaultDmnDecisionContext {
        HybridDecisionContext(DefaultDmnEngineConfiguration configuration) {
            super(configuration);
            evaluationHandlers.put(DroolsDecisionLogic.class, new DroolsEvaluationHandler());
        }
    }
}
