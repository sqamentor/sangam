// SPDX-License-Identifier: Apache-2.0
package org.sangam.spike.dmn;

import org.junit.jupiter.api.Test;
import org.kie.api.io.ResourceType;
import org.kie.dmn.api.core.DMNContext;
import org.kie.dmn.api.core.DMNResult;
import org.kie.dmn.api.core.DMNRuntime;
import org.kie.dmn.core.internal.utils.DMNRuntimeBuilder;
import org.kie.internal.io.ResourceFactory;

import java.util.List;

/** Probe: which messages does Drools give for a mistyped input (strict-mode design input). */
class DroolsMessagesProbeTest {
    @Test
    void probe() throws Exception {
        var r = ResourceFactory.newByteArrayResource(getClass().getResourceAsStream("/boxed.dmn").readAllBytes());
        r.setSourcePath("boxed.dmn");
        r.setResourceType(ResourceType.DMN);
        DMNRuntime rt = DMNRuntimeBuilder.fromDefaults().buildConfiguration().fromResources(List.of(r)).getOrElseThrow(RuntimeException::new);
        ((org.kie.dmn.core.impl.DMNRuntimeImpl) rt).setOption(new org.kie.dmn.core.compiler.RuntimeTypeCheckOption(true));
        var m = rt.getModels().get(0);
        DMNContext ctx = rt.newContext();
        ctx.set("Amount", "x");
        DMNResult res = rt.evaluateByName(m, ctx, "Charge");
        System.out.println("[probe] status=" + res.getDecisionResultByName("Charge").getEvaluationStatus()
                + " value=" + res.getDecisionResultByName("Charge").getResult());
        res.getMessages().forEach(x -> System.out.println("[probe] " + x.getSeverity() + " " + x.getMessageType() + " " + x.getText()));
    }
}
