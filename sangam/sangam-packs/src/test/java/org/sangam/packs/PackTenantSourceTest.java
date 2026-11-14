// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.sangam.core.tenant.TenantRefusal;
import org.sangam.core.tenant.TenantResolutionException;
import org.sangam.packs.core.ConnectorCallDelegate;
import org.sangam.packs.core.PackStartListener;
import org.sangam.packs.core.Vars;
import org.sangam.packs.port.ConnectorGateway;
import org.sangam.packs.port.PackRuntime;

/**
 * Decision 145 (W4-B REQUEST 19:04:55Z): pack work is tenant-owned. The tenant is the engine tenant only; the
 * {@code tenantId} variable is an untrusted claim. A forged claim is refused before any connector call, and a pack
 * instance without an engine tenant fails closed; a claim equal to the engine tenant is accepted.
 */
class PackTenantSourceTest {

    private PackRuntime.Ports previousPorts;
    private final List<String> gatewayCalls = new ArrayList<>();

    @BeforeEach
    void recordingGateway() {
        previousPorts = PackRuntime.ports();
        ConnectorGateway recording = (ConnectorGateway) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ConnectorGateway.class}, (p, m, a) -> {
                    if (m.getDeclaringClass() == Object.class) {
                        return m.getName().equals("equals") ? p == a[0] : m.getName().equals("hashCode")
                                ? System.identityHashCode(p) : "recording gateway";
                    }
                    gatewayCalls.add(m.getName());
                    throw new AssertionError("connector gateway reached: " + m.getName());
                });
        PackRuntime.install(previousPorts.withGateway(recording));
    }

    @AfterEach
    void restore() {
        PackRuntime.install(previousPorts);
    }

    @Test
    void aForgedTenantVariableIsRefusedBeforeAnyConnectorCall() throws Exception {
        for (String mode : List.of("query", "submit")) {
            Map<String, Object> vars = new HashMap<>(Map.of(Vars.TENANT_VARIABLE, "t-other"));
            TenantResolutionException e = assertThrows(TenantResolutionException.class,
                    () -> connectorCall(mode).execute(execution(PackTestTenant.ID, vars)), mode);
            assertEquals(TenantRefusal.CONFLICTING_TENANT, e.refusal(), mode);
            assertEquals("TENANT_CONTEXT_CONFLICT", e.refusal().code(), mode);
        }
        assertTrue(gatewayCalls.isEmpty(), "gateway calls: " + gatewayCalls);
    }

    @Test
    void anUntenantedPackInstanceFailsClosed() throws Exception {
        Map<String, Object> claimOnly = new HashMap<>(Map.of(Vars.TENANT_VARIABLE, PackTestTenant.ID));
        TenantResolutionException call = assertThrows(TenantResolutionException.class,
                () -> connectorCall("query").execute(execution(null, claimOnly)));
        assertEquals("TENANT_CONTEXT_MISSING", call.refusal().code(), "the variable is never a fallback");
        assertTrue(gatewayCalls.isEmpty(), "gateway calls: " + gatewayCalls);

        Map<String, Object> none = new HashMap<>();
        TenantResolutionException start = assertThrows(TenantResolutionException.class,
                () -> new PackStartListener().notify(execution(null, none)));
        assertEquals(TenantRefusal.MISSING_ENGINE_TENANT, start.refusal());
        assertTrue(none.isEmpty(), "the start listener writes nothing before the tenant is resolved: " + none);
    }

    @Test
    void aForgedTenantVariableStopsThePackStartBeforeAnyWrite() {
        Map<String, Object> vars = new HashMap<>(Map.of(Vars.TENANT_VARIABLE, "t-other"));
        TenantResolutionException e = assertThrows(TenantResolutionException.class,
                () -> new PackStartListener().notify(execution(PackTestTenant.ID, vars)));
        assertEquals("TENANT_CONTEXT_CONFLICT", e.refusal().code());
        assertEquals(Map.of(Vars.TENANT_VARIABLE, "t-other"), vars, "nothing written");
    }

    @Test
    void theEngineTenantIsTheTenantAndAMatchingClaimIsAccepted() {
        assertEquals(PackTestTenant.ID, Vars.tenant(execution(PackTestTenant.ID, new HashMap<>())));
        assertEquals(PackTestTenant.ID, Vars.tenant(execution(PackTestTenant.ID,
                new HashMap<>(Map.of(Vars.TENANT_VARIABLE, PackTestTenant.ID)))));
    }

    /** A connector call of {@code mode} to a test connector, with its fields injected as the engine would. */
    private static ConnectorCallDelegate connectorCall(String mode) throws ReflectiveOperationException {
        ConnectorCallDelegate d = new ConnectorCallDelegate();
        inject(d, "connector", "test.registry");
        inject(d, "operation", "lookup");
        inject(d, "mode", mode);
        return d;
    }

    private static void inject(Object target, String name, String value) throws ReflectiveOperationException {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, Proxy.newProxyInstance(PackTenantSourceTest.class.getClassLoader(),
                new Class<?>[]{Expression.class}, (p, m, a) -> switch (m.getName()) {
                    case "getValue", "getExpressionText" -> value;
                    case "isLiteralText" -> true;
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    default -> null;
                }));
    }

    /** A delegate execution of engine tenant {@code tenant} over {@code vars}; other methods answer a neutral default. */
    private static DelegateExecution execution(String tenant, Map<String, Object> vars) {
        return (DelegateExecution) Proxy.newProxyInstance(PackTenantSourceTest.class.getClassLoader(),
                new Class<?>[]{DelegateExecution.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "getTenantId":
                            return tenant;
                        case "getProcessInstanceId":
                            return "pi-1";
                        case "getProcessBusinessKey":
                            return "bk-1";
                        case "getVariable":
                            return vars.get((String) a[0]);
                        case "hasVariable":
                            return vars.containsKey((String) a[0]);
                        case "getVariables":
                            return vars;
                        case "setVariable":
                            vars.put((String) a[0], a[1]);
                            return null;
                        default:
                            Class<?> r = m.getReturnType();
                            if (r == boolean.class) {
                                return false;
                            }
                            if (r == int.class || r == long.class) {
                                return r == int.class ? 0 : 0L;
                            }
                            return null;
                    }
                });
    }
}
