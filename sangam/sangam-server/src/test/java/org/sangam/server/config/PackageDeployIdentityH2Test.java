// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngineException;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.engine.deploy.OperationRefPinning;
import org.sangam.integration.connector.contract.ApiContractStore;
import org.sangam.rest.adapter.operaton.OperatonDeploymentAdapter;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.DeploymentPort;
import org.sangam.rest.port.PackagePort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.TxSupport;
import org.sangam.server.bridge.PortsDeploymentBridge;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * Integrator ruling on the forged package deployment (M-068): the package deploy identity ({@code pkg:<key>:<hash>},
 * source {@code sangam-package}) belongs to the package's own deploy only. On a real H2 engine with the server's
 * pinner: a raw deployment named like an APPROVED package, or with the package source, is refused with 422
 * MODEL_INVALID; the package's own deploy ({@code DeploymentPort.deployPackage}) still pins; a raw deployment with
 * {@code sangam:operationRef} still gets 422; the engine refuses the identity on a path that bypasses the REST port.
 * Written for the test phase (not run).
 */
class PackageDeployIdentityH2Test {

    private static final String HASH = "4d1c3b5a7f9e1d3c5b7a9f1e3d5c7b9a1f3e5d7c9b1a3f5e7d9c1b3a5f7e9d1c";
    private static final RequestContext CTX = RequestContext.system("t1", null);

    private final List<Object[]> packages = new ArrayList<>();
    private final ApiContractStore store = new ApiContractStore.InMemory();
    private OperatonEngineAdapter engine;
    private OperatonDeploymentAdapter deployments;

    private static String model(String ref) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
                             xmlns:s="http://sangam.org/schema/1.0/bpmn" targetNamespace="t">
                  <process id="order" isExecutable="true">
                    <startEvent id="st"/><sequenceFlow id="f1" sourceRef="st" targetRef="call"/>
                    <serviceTask id="call" operaton:type="external" operaton:topic="op"%s/>
                    <sequenceFlow id="f2" sourceRef="call" targetRef="e"/><endEvent id="e"/>
                  </process>
                </definitions>
                """.formatted(ref == null ? "" : " s:operationRef=\"" + ref + "\"");
    }

    private static Map<String, byte[]> resources(String ref) {
        return Map.of("order.bpmn", model(ref).getBytes(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void start() {
        packages.add(new Object[]{"t1", EngineWiringOperationRefPinnerTest.pkg("P1", "orders", "APPROVED", HASH)});
        store.put("t1", "P1", "orderApi", EngineWiringOperationRefPinnerTest.openApi("v1", "createOrder"));
        store.put("t1", "P1", "orderApi", EngineWiringOperationRefPinnerTest.openApi("v2", "createOrder"));
        PackagePort port = EngineWiringOperationRefPinnerTest.packagePort(packages);
        SangamEngineSettings s = new SangamEngineSettings();
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        s.setJobExecutorActivate(false);
        s.setOperationRefPinner(EngineWiring.operationRefPinner(() -> store, () -> port));
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:pkgid" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        engine = SangamEngineFactory.build(ds, s, "h2");
        AuditPort audit = (AuditPort) Proxy.newProxyInstance(AuditPort.class.getClassLoader(), new Class<?>[]{AuditPort.class},
                (proxy, m, args) -> m.getName().equals("record") ? "audit-1" : null);
        deployments = new OperatonDeploymentAdapter(engine.processEngine().getRepositoryService(),
                new TxSupport(ds, new DataSourceTransactionManager(ds), TxSupport.detectDialect(ds)), audit);
    }

    @AfterEach
    void stop() {
        engine.close();
    }

    private static void assertModelInvalid(ApiProblem p, String reasonPart) {
        assertEquals(422, p.status());
        assertEquals("MODEL_INVALID", p.code());
        assertTrue(p.detail().contains(reasonPart), p.detail());
    }

    @Test
    void aRawDeploymentNamedLikeAnApprovedPackageIsRefused() {
        String forged = "pkg:orders:" + HASH;
        assertModelInvalid(assertThrows(ApiProblem.class, () -> deployments.deploy(CTX,
                new DeploymentPort.DeployRequest(forged, "sangam-rest", false, resources("orderApi#createOrder")))),
                "reserved for a package's own deploy");
        assertModelInvalid(assertThrows(ApiProblem.class, () -> deployments.deploy(CTX,
                new DeploymentPort.DeployRequest(forged, null, false, resources(null)))), "reserved");
        // the app-bundle bridge refuses it before choosing a path
        PortsDeploymentBridge bridge = new PortsDeploymentBridge(deployments, null, null);
        assertModelInvalid(assertThrows(ApiProblem.class, () -> bridge.deploy(CTX, new DeploymentPort.DeployRequest(
                forged, null, false, Map.of("sangam-app.json", "{}".getBytes(StandardCharsets.UTF_8))))), "reserved");
    }

    @Test
    void aRawDeploymentWithThePackageSourceIsRefused() {
        assertModelInvalid(assertThrows(ApiProblem.class, () -> deployments.deploy(CTX,
                new DeploymentPort.DeployRequest("innocent", "sangam-package", false, resources(null)))),
                "reserved for a package's own deploy");
    }

    @Test
    void thePackagesOwnDeployStillPins() {
        DeploymentPort.Deployment d = deployments.deployPackage(CTX, "orders", HASH, resources("orderApi#createOrder"));
        assertEquals("pkg:orders:" + HASH, d.name());
        assertEquals("sangam-package", d.source());
        String stored = new String(deployments.resource(CTX, d.id(), "order.bpmn"), StandardCharsets.UTF_8);
        assertTrue(stored.contains("s:operationRef=\"orderApi@2#createOrder\""), stored);
    }

    @Test
    void aRawDeploymentWithAnOperationRefStillGets422() {
        assertModelInvalid(assertThrows(ApiProblem.class, () -> deployments.deploy(CTX,
                new DeploymentPort.DeployRequest("raw", null, false, resources("orderApi#createOrder")))),
                "only a package's own deploy");
        // a raw deployment without the identity or refs still deploys
        assertEquals("raw-plain", deployments.deploy(CTX,
                new DeploymentPort.DeployRequest("raw-plain", null, false, resources(null))).name());
    }

    @Test
    void theEngineRefusesTheIdentityOnAPathThatBypassesThePort() {
        var repo = engine.processEngine().getRepositoryService();
        ProcessEngineException e = assertThrows(ProcessEngineException.class, () -> repo.createDeployment()
                .name("pkg:orders:" + HASH).tenantId("t1").addString("order.bpmn", model(null)).deploy());
        assertTrue(e.getMessage().contains("422 MODEL_INVALID"), e.getMessage());
        // a proof for this deployment is what the package deploy path binds; with it the engine accepts the identity
        OperationRefPinning.PackageDeploy proof = new OperationRefPinning.PackageDeploy("t1", "orders", HASH);
        String id = OperationRefPinning.asPackageDeploy(proof, () -> repo.createDeployment().name(proof.deploymentName())
                .source(OperationRefPinning.PACKAGE_SOURCE).tenantId("t1").addString("order.bpmn", model(null)).deploy()
                .getId());
        assertTrue(id != null && !id.isBlank());
        assertEquals(DeploymentPort.PACKAGE_NAME_PREFIX, OperationRefPinning.PACKAGE_NAME_PREFIX);
        assertEquals(DeploymentPort.PACKAGE_SOURCE, OperationRefPinning.PACKAGE_SOURCE);
    }
}
