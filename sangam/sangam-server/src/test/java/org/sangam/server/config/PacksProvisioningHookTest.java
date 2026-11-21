// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.sangam.packs.spi.PackDeployer;
import org.sangam.packs.spi.PackRegistry;
import org.sangam.rest.port.AdminPort;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.server.bridge.PacksTenantDeployer;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Register E-33 (e): the pack hook of {@link PacksWiring} around {@code AdminPort}. A tenant reaches status ACTIVE by
 * three calls: {@code createTenant}, {@code updateTenant} (PUT with status ACTIVE) and {@code activateTenant}
 * ({@code POST /admin/tenants/{id}/activate}, the route of AJ-01 step 10). Each must ensure the tenant's packs; a call
 * that leaves the tenant in another status, or is refused, must deploy nothing.
 * <p>
 * The hook is the real one (the bean post-processor of {@code PacksWiring}) around a stand-in AdminPort that answers
 * the tenant in a given status; the deployer is the real {@link PacksTenantDeployer} over a registry without packs,
 * so no engine is needed and each deployment shows as one {@code PACKS_DEPLOYED} audit record.
 */
class PacksProvisioningHookTest {

    /** {@code PACKS_DEPLOYED} records as {@code chain}, in order. */
    private final List<String> deployed = new ArrayList<>();

    private AdminPort hooked(String statusAnswered) {
        AuditPort audit = (AuditPort) Proxy.newProxyInstance(AuditPort.class.getClassLoader(), new Class<?>[]{AuditPort.class},
                (proxy, m, args) -> {
                    if (m.getName().equals("record") && "PACKS_DEPLOYED".equals(args[2])) {
                        deployed.add((String) args[1]);
                    }
                    return null;
                });
        PackRegistry none = PackRegistry.discover(new ClassLoader(null) { });
        PacksTenantDeployer deployer = new PacksTenantDeployer(new PackDeployer(null, none), none, audit);
        AdminPort target = (AdminPort) Proxy.newProxyInstance(AdminPort.class.getClassLoader(), new Class<?>[]{AdminPort.class},
                (proxy, m, args) -> {
                    if (statusAnswered == null) {
                        throw ApiProblem.conflict("ACTIVATION_BLOCKED", "evidence is missing");
                    }
                    if (m.getReturnType() == AdminPort.Tenant.class) {
                        String id = args[1] instanceof String s ? s : ((AdminPort.Tenant) args[1]).id();
                        return new AdminPort.Tenant(id, id, statusAnswered, null, null, null, List.of(), Map.of(),
                                Instant.EPOCH, 1L);
                    }
                    return null;
                });
        ObjectProvider<PacksTenantDeployer> provider = new ObjectProvider<>() {
            @Override
            public PacksTenantDeployer getObject() {
                return deployer;
            }

            @Override
            public PacksTenantDeployer getIfAvailable() {
                return deployer;
            }
        };
        return (AdminPort) PacksWiring.sangamPacksProvisioningDecorator(provider)
                .postProcessAfterInitialization(target, "adminPort");
    }

    private static AdminPort.Tenant tenant(String id, String status) {
        return new AdminPort.Tenant(id, id, status, null, null, null, List.of(), Map.of(), Instant.EPOCH, 1L);
    }

    @Test
    void activationByItsOwnRouteDeploysThePacksOfTheTenant() {
        AdminPort admin = hooked("ACTIVE");
        assertEquals("ACTIVE", admin.activateTenant(null, "GOI.FIN", "go-live").status());
        assertEquals(List.of("sec:GOI.FIN"), deployed, "POST /admin/tenants/{id}/activate ensures the tenant's packs");
        admin.activateTenant(null, "GOI.FIN", "again");
        assertEquals(List.of("sec:GOI.FIN"), deployed, "once per tenant on this node; a repeated call deploys nothing");
    }

    @Test
    void createAndUpdateToActiveDeployAsBefore() {
        AdminPort admin = hooked("ACTIVE");
        admin.createTenant(null, tenant("t.create", "ACTIVE"));
        admin.updateTenant(null, "t.update", tenant("t.update", "ACTIVE"));
        assertEquals(List.of("sec:t.create", "sec:t.update"), deployed);
    }

    @Test
    void aTenantLeftInAnotherStatusOrARefusedCallDeploysNothing() {
        AdminPort created = hooked("CREATED");
        created.createTenant(null, tenant("t1", "CREATED"));
        created.updateTenant(null, "t1", tenant("t1", "CREATED"));
        created.activateTenant(null, "t1", "a suspended tenant resumed into CONFIGURED would answer so");
        created.suspendTenant(null, "t1", "suspension never deploys");
        assertEquals(List.of(), deployed, "no pack for a tenant that is not ACTIVE");

        AdminPort refused = hooked(null);
        ApiProblem p = assertThrows(ApiProblem.class, () -> refused.activateTenant(null, "t2", "evidence missing"));
        assertEquals("ACTIVATION_BLOCKED", p.code(), "the refusal passes through the hook unchanged");
        assertEquals(List.of(), deployed, "a refused activation deploys nothing");

        AdminPort suspended = hooked("ACTIVE");
        suspended.suspendTenant(null, "t3", "the answer of another lifecycle call is not an activation");
        assertEquals(List.of(), deployed, "only create, update and activate are provisioning calls");
    }
}
