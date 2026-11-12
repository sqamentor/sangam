// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.persistence.entity.DeploymentEntity;
import org.operaton.bpm.engine.impl.persistence.entity.ResourceEntity;
import org.sangam.engine.deploy.OperationRefPinning;
import org.sangam.integration.connector.contract.ApiContractStore;
import org.sangam.rest.adapter.operaton.OperatonSupport;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PackagePort;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.RequestContext;

/**
 * M-068 (integrator rulings 14:38Z and on the forged package deployment, decision 115): the server's pinner resolves
 * {@code sangam:operationRef} only with the proof of a package's own deploy, in the package of the proof's tenant that
 * is APPROVED with the proof's key and hash; an unversioned ref pins the newest stored version; every refusal reaches
 * the API as 422 MODEL_INVALID. Written for the test phase (not run).
 */
class EngineWiringOperationRefPinnerTest {

    private static final String HASH = "9f2c4e0b7a1d3f5e6c8b0a2d4f6e8c0b1a3d5f7e9c1b3a5d7f9e1c3b5a7d9f1e";
    private static final OperationRefPinning.PackageDeploy PROOF = new OperationRefPinning.PackageDeploy("t1", "orders", HASH);

    private final ApiContractStore store = new ApiContractStore.InMemory();
    private final List<Object[]> packages = new ArrayList<>(); // {tenant, Package}

    static String openApi(String title, String operationId) {
        return "{\"openapi\":\"3.0.3\",\"info\":{\"title\":\"" + title + "\",\"version\":\"1\"},\"paths\":{\"/orders\":"
                + "{\"post\":{\"operationId\":\"" + operationId + "\",\"responses\":{\"200\":{\"description\":\"ok\"}}}}}}";
    }

    static PackagePort.Package pkg(String id, String key, String state, String hash) {
        Instant t = Instant.parse("2026-11-12T00:00:00Z");
        return new PackagePort.Package(id, key, key, 1, state, hash, Map.of(), "author", null, null, null, null, null,
                null, t, t, List.of(), Map.of());
    }

    /** A PackagePort that answers list(ctx, key, state, page) from {@code rows} ({tenant, Package}), per tenant. */
    static PackagePort packagePort(List<Object[]> rows) {
        return (PackagePort) Proxy.newProxyInstance(PackagePort.class.getClassLoader(), new Class<?>[]{PackagePort.class},
                (proxy, m, args) -> {
                    if (!m.getName().equals("list")) {
                        throw new UnsupportedOperationException(m.getName());
                    }
                    RequestContext ctx = (RequestContext) args[0];
                    List<PackagePort.Package> all = rows.stream()
                            .filter(p -> p[0].equals(ctx.tenantId()))
                            .map(p -> (PackagePort.Package) p[1])
                            .filter(p -> args[1] == null || args[1].equals(p.key()))
                            .filter(p -> args[2] == null || args[2].equals(p.state()))
                            .toList();
                    return Page.slice(all, (Page.PageRequest) args[3], 10_000);
                });
    }

    private OperationRefPinning.Pinner pinner() {
        PackagePort port = packagePort(packages);
        return EngineWiring.operationRefPinner(() -> store, () -> port);
    }

    private static void assertRefused(Runnable r, String reasonPart) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, r::run);
        assertTrue(e.getMessage().startsWith("422 MODEL_INVALID: "), e.getMessage());
        assertTrue(e.getMessage().contains(reasonPart), e.getMessage());
    }

    @Test
    void anUnversionedRefPinsTheNewestStoredVersionAndAVersionedOneStays() {
        packages.add(new Object[]{"t1", pkg("P1", "orders", "APPROVED", HASH)});
        store.put("t1", "P1", "orderApi", openApi("v1", "createOrder"));
        store.put("t1", "P1", "orderApi", openApi("v2", "createOrder"));
        OperationRefPinning.Pinner p = pinner();
        assertEquals("orderApi@2#createOrder", p.pin(PROOF, "orderApi#createOrder"));
        assertEquals("orderApi@1#createOrder", p.pin(PROOF, "orderApi@1#createOrder"));
    }

    @Test
    void withoutTheProofOfAPackageDeployNothingIsPinned() {
        packages.add(new Object[]{"t1", pkg("P1", "orders", "APPROVED", HASH)});
        store.put("t1", "P1", "orderApi", openApi("v1", "createOrder"));
        assertRefused(() -> pinner().pin(null, "orderApi#createOrder"), "only a package's own deploy");
    }

    @Test
    void thePackageMustBeTheTenantsOneApprovedPackageWithTheProofsKeyAndHash() {
        store.put("t1", "P1", "orderApi", openApi("v1", "createOrder"));
        OperationRefPinning.Pinner p = pinner();
        assertRefused(() -> p.pin(PROOF, "orderApi#createOrder"), "no APPROVED package orders");
        packages.add(new Object[]{"t1", pkg("P1", "orders", "DEPLOYED", HASH)});
        packages.add(new Object[]{"t1", pkg("P0", "orders", "APPROVED", "0".repeat(64))});
        packages.add(new Object[]{"t2", pkg("P2", "orders", "APPROVED", HASH)});
        assertRefused(() -> p.pin(PROOF, "orderApi#createOrder"), "no APPROVED package orders");
        packages.add(new Object[]{"t1", pkg("P3", "orders", "APPROVED", HASH)});
        packages.add(new Object[]{"t1", pkg("P4", "orders", "APPROVED", HASH)});
        assertRefused(() -> p.pin(PROOF, "orderApi#createOrder"), "ambiguous");
    }

    @Test
    void contractsOfAnotherPackageOrTenantAreNotVisible() {
        packages.add(new Object[]{"t1", pkg("P1", "orders", "APPROVED", HASH)});
        packages.add(new Object[]{"t2", pkg("P1", "orders", "APPROVED", HASH)});
        store.put("t1", "OTHER", "orderApi", openApi("v1", "createOrder"));
        store.put("t2", "P1", "orderApi", openApi("v1", "createOrder"));
        assertRefused(() -> pinner().pin(PROOF, "orderApi#createOrder"), "contract orderApi is not stored for package P1");
    }

    @Test
    void anUnknownVersionOperationOrMalformedRefIsRefused() {
        packages.add(new Object[]{"t1", pkg("P1", "orders", "APPROVED", HASH)});
        store.put("t1", "P1", "orderApi", openApi("v1", "createOrder"));
        OperationRefPinning.Pinner p = pinner();
        assertRefused(() -> p.pin(PROOF, "orderApi@2#createOrder"), "orderApi@2 is not stored");
        assertRefused(() -> p.pin(PROOF, "orderApi#cancelOrder"), "operation cancelOrder is not in orderApi@1");
        assertRefused(() -> p.pin(PROOF, "orderApi"), "contractId[@version]#operationId");
    }

    @Test
    void withoutAContractStoreOrPackagePortEveryRefIsRefused() {
        packages.add(new Object[]{"t1", pkg("P1", "orders", "APPROVED", HASH)});
        PackagePort port = packagePort(packages);
        assertRefused(() -> EngineWiring.operationRefPinner(() -> null, () -> port).pin(PROOF, "orderApi#createOrder"),
                "no API contract store");
        assertRefused(() -> EngineWiring.operationRefPinner(() -> store, () -> null).pin(PROOF, "orderApi#createOrder"),
                "no package store");
    }

    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:sangam="%s" targetNamespace="t">
              <process id="p" isExecutable="true">
                <serviceTask id="call" sangam:operationRef="%s"/>
              </process>
            </definitions>
            """;

    private static DeploymentEntity deployment(String name, String ref) {
        DeploymentEntity d = new DeploymentEntity();
        d.setName(name);
        d.setTenantId("t1");
        d.setNew(true);
        ResourceEntity r = new ResourceEntity();
        r.setName("p.bpmn");
        r.setBytes(BPMN.formatted(org.sangam.core.engine.RoutingPolicy.NS_URI, ref).getBytes(StandardCharsets.UTF_8));
        d.addResource(r);
        return d;
    }

    @Test
    void thePreDeployerPinsOnlyUnderTheProofAndARefusalIsA422ModelInvalid() {
        packages.add(new Object[]{"t1", pkg("P1", "orders", "APPROVED", HASH)});
        store.put("t1", "P1", "orderApi", openApi("v1", "createOrder"));
        store.put("t1", "P1", "orderApi", openApi("v2", "createOrder"));
        OperationRefPinning.DeployRule rule = new OperationRefPinning.DeployRule(pinner());

        DeploymentEntity ok = deployment(PROOF.deploymentName(), "orderApi#createOrder");
        OperationRefPinning.asPackageDeploy(PROOF, () -> {
            rule.deploy(ok);
            return null;
        });
        String stored = new String(ok.getResource("p.bpmn").getBytes(), StandardCharsets.UTF_8);
        assertTrue(stored.contains("sangam:operationRef=\"orderApi@2#createOrder\""), stored);

        // the package's name without the proof: the reserved identity is refused
        DeploymentEntity forged = deployment(PROOF.deploymentName(), "orderApi#createOrder");
        ApiProblem problem = assertThrows(ApiProblem.class, () -> OperatonSupport.call(() -> {
            rule.deploy(forged);
            return null;
        }));
        assertEquals(422, problem.status());
        assertEquals("MODEL_INVALID", problem.code());
        assertTrue(problem.detail().contains("reserved for a package's own deploy"), problem.detail());

        // a raw deployment with a contract operation
        DeploymentEntity direct = deployment("deployment", "orderApi#createOrder");
        ApiProblem raw = assertThrows(ApiProblem.class, () -> OperatonSupport.call(() -> {
            rule.deploy(direct);
            return null;
        }));
        assertEquals(422, raw.status());
        assertEquals("MODEL_INVALID", raw.code());

        DeploymentEntity missing = deployment(PROOF.deploymentName(), "orderApi#cancelOrder");
        ProcessEngineException e = assertThrows(ProcessEngineException.class,
                () -> OperationRefPinning.asPackageDeploy(PROOF, () -> {
                    rule.deploy(missing);
                    return null;
                }));
        assertTrue(e.getMessage().contains("422 MODEL_INVALID: "), e.getMessage());
    }
}
