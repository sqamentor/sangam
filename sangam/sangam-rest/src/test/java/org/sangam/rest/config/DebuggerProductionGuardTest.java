// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.core.security.ProductionMode;
import org.sangam.rest.adapter.operaton.OperatonDebugAdapter;
import org.sangam.rest.port.RequestContext;

/**
 * SL-109 with decisions 143 and 198: the model debugger is on only with {@code sangam.debug.enabled=true} outside
 * production, where production is the shared EnvironmentPolicy resolution (a missing or invalid switch counts as
 * production; true wins over a development profile). The adapter's own second check classifies the profiles by the
 * same convention. Written for the test phase (not run here).
 */
class DebuggerProductionGuardTest {

    private static Map<String, Object> debugOn(String productionSwitch) {
        Map<String, Object> m = new HashMap<>();
        m.put("sangam.debug.enabled", "true");
        if (productionSwitch != null) {
            m.put(ProductionMode.PROPERTY, productionSwitch);
        }
        return m;
    }

    @Test
    void onlyAnExplicitNonProductionStartEnablesTheDebugger() {
        assertTrue(SangamRestAutoConfiguration.debuggerEnabled(PermissionFallbackFailClosedTest.env(debugOn("false"), "dev")));
        assertTrue(SangamRestAutoConfiguration.debuggerEnabled(
                PermissionFallbackFailClosedTest.env(debugOn("false"), "postgres")));
        assertFalse(SangamRestAutoConfiguration.debuggerEnabled(
                PermissionFallbackFailClosedTest.env(Map.of(ProductionMode.PROPERTY, "false"), "dev")),
                "off by default");
    }

    @Test
    void productionAndARefusedConfigurationKeepItOff() {
        // decision 198: true wins over a development profile
        assertFalse(SangamRestAutoConfiguration.debuggerEnabled(PermissionFallbackFailClosedTest.env(debugOn("true"), "h2")));
        // a production profile, with or without the switch
        assertFalse(SangamRestAutoConfiguration.debuggerEnabled(PermissionFallbackFailClosedTest.env(debugOn(null), "prod")));
        // decision 143: a missing, invalid or contradictory switch counts as production
        assertFalse(SangamRestAutoConfiguration.debuggerEnabled(PermissionFallbackFailClosedTest.env(debugOn(null), "dev")));
        assertFalse(SangamRestAutoConfiguration.debuggerEnabled(PermissionFallbackFailClosedTest.env(debugOn("on"), "dev")));
        assertFalse(SangamRestAutoConfiguration.debuggerEnabled(
                PermissionFallbackFailClosedTest.env(debugOn("false"), "prod-eu")));
    }

    @Test
    void theAdapterRefusesAProductionProfileEvenWhenEnabled() {
        RequestContext ctx = RequestContext.system("t1", null);
        assertFalse(new OperatonDebugAdapter(null, null, null, true, "postgres,PROD-eu", null, null).status(ctx).enabled());
        assertFalse(new OperatonDebugAdapter(null, null, null, true, "preprod", null, null).status(ctx).enabled());
        assertTrue(new OperatonDebugAdapter(null, null, null, true, "dev,postgres", null, null).status(ctx).enabled());
        assertTrue(new OperatonDebugAdapter(null, null, null, true, "", null, null).status(ctx).enabled());
        assertFalse(new OperatonDebugAdapter(null, null, null, false, "dev", null, null).status(ctx).enabled());
    }
}
