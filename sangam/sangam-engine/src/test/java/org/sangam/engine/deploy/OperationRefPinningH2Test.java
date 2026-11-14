// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.operaton.bpm.engine.impl.RepositoryServiceImpl;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;

/** M-068: sangam:operationRef is pinned to a contract version in the stored model at deployment, or refused. */
class OperationRefPinningH2Test {

    private static String model(String key, String element, String ref) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:operaton="http://operaton.org/schema/1.0/bpmn"
                             xmlns:s="http://sangam.org/schema/1.0/bpmn" targetNamespace="t">
                  <process id="%s" isExecutable="true">
                    <startEvent id="st"/><sequenceFlow id="f1" sourceRef="st" targetRef="call"/>
                    <%s id="call" operaton:type="external" operaton:topic="op" s:operationRef='%s'/>
                    <sequenceFlow id="f2" sourceRef="call" targetRef="e"/><endEvent id="e"/>
                  </process>
                </definitions>
                """.formatted(key, element, ref);
    }

    private static OperatonEngineAdapter engine(OperationRefPinning.Pinner pinner) {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope("system:platform"); // decision 144: required at engine start
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        s.setOperationRefPinner(pinner);
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:m068" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        return SangamEngineFactory.build(ds, s, "h2");
    }

    /** A package's own deploy: the deployment named after the proof, run with the proof bound. */
    private static String deploy(OperatonEngineAdapter a, String key, String xml) {
        OperationRefPinning.PackageDeploy proof = new OperationRefPinning.PackageDeploy("t1", key, hash());
        return OperationRefPinning.asPackageDeploy(proof, () -> a.deploy("t1", proof.deploymentName(),
                Map.of(key + ".bpmn", xml.getBytes(StandardCharsets.UTF_8))));
    }

    /** A raw deployment: no proof bound. */
    private static String raw(OperatonEngineAdapter a, String name, String key, String xml) {
        return a.deploy("t1", name, Map.of(key + ".bpmn", xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hash() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String message(RuntimeException e) {
        return e.getMessage() + (e.getCause() == null ? "" : " " + e.getCause().getMessage());
    }

    @Test
    void anUnversionedRefIsPinnedInTheStoredModelAndExposedOnTheActivity() throws Exception {
        List<String> asked = new CopyOnWriteArrayList<>();
        try (OperatonEngineAdapter a = engine((proof, ref) -> {
            asked.add(proof.tenantId() + " " + ref);
            return ref.contains("@") ? ref : ref.replace("#", "@3#");
        })) {
            deploy(a, "pay", model("pay", "serviceTask", "billing#charge"));
            assertEquals(List.of("t1 billing#charge"), asked);
            RepositoryService repo = a.processEngine().getRepositoryService();
            var def = repo.createProcessDefinitionQuery().processDefinitionKey("pay").singleResult();
            try (InputStream in = repo.getResourceAsStream(def.getDeploymentId(), "pay.bpmn")) {
                String stored = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(stored.contains("s:operationRef='billing@3#charge'"), stored);
            }
            ProcessDefinitionEntity entity = (ProcessDefinitionEntity) ((RepositoryServiceImpl) repo)
                    .getDeployedProcessDefinition(def.getId());
            assertEquals("billing@3#charge", entity.findActivity("call")
                    .getProperty(SangamBpmnParseListener.OPERATION_REF_PROPERTY));
        }
    }

    @Test
    void refusalsFailClosed() {
        try (OperatonEngineAdapter none = engine(null)) {
            assertTrue(message(assertThrows(RuntimeException.class,
                    () -> deploy(none, "x", model("x", "serviceTask", "billing#charge")))).contains("no API contract store"));
        }
        try (OperatonEngineAdapter a = engine((proof, ref) -> {
            throw new IllegalArgumentException("unknown contract");
        })) {
            assertTrue(message(assertThrows(RuntimeException.class,
                    () -> deploy(a, "y", model("y", "serviceTask", "nope#op")))).contains("unknown contract"));
        }
        try (OperatonEngineAdapter a = engine((proof, ref) -> ref)) {
            assertTrue(message(assertThrows(RuntimeException.class,
                    () -> deploy(a, "z", model("z", "userTask", "billing@1#charge")))).contains("M-068"));
            assertTrue(message(assertThrows(RuntimeException.class,
                    () -> deploy(a, "w", model("w", "serviceTask", "billing#charge")))).contains("contractId@version#operationId"),
                    "a store answer that is not pinned is refused");
        }
    }

    /** Integrator ruling on the forged package deployment: the name or source alone is never proof. */
    @Test
    void thePackageDeployIdentityIsReservedAndOnlyTheProofBindsContracts() {
        List<String> asked = new CopyOnWriteArrayList<>();
        try (OperatonEngineAdapter a = engine((proof, ref) -> {
            asked.add(ref);
            return ref.replace("#", "@1#");
        })) {
            String plain = model("plain", "serviceTask", "unused@1#x").replace(" s:operationRef='unused@1#x'", "");
            String bound = model("bound", "serviceTask", "billing#charge");
            // a raw deployment named like a package deploy is refused, with or without contract operations
            String forged = "pkg:orders:" + hash();
            assertTrue(message(assertThrows(RuntimeException.class, () -> raw(a, forged, "f1", bound)))
                    .contains("422 MODEL_INVALID: the deployment name prefix pkg:"));
            assertTrue(message(assertThrows(RuntimeException.class, () -> raw(a, forged, "f2", plain)))
                    .contains("reserved for a package's own deploy"));
            // a raw deployment with the package source is refused
            RepositoryService repo = a.processEngine().getRepositoryService();
            assertTrue(message(assertThrows(RuntimeException.class, () -> repo.createDeployment().name("innocent")
                    .source(OperationRefPinning.PACKAGE_SOURCE).tenantId("t1")
                    .addString("s.bpmn", plain).deploy())).contains("reserved for a package's own deploy"));
            // a raw deployment with operationRef is refused as 422, the pinner is never asked
            assertTrue(message(assertThrows(RuntimeException.class, () -> raw(a, "raw-" + hash(), "r", bound)))
                    .contains("422 MODEL_INVALID: deployment"));
            // a proof for another deployment or tenant is no proof
            OperationRefPinning.PackageDeploy other = new OperationRefPinning.PackageDeploy("t1", "orders", hash());
            assertThrows(RuntimeException.class, () -> OperationRefPinning.asPackageDeploy(other,
                    () -> raw(a, "pkg:orders:" + hash(), "o1", bound)));
            OperationRefPinning.PackageDeploy t2 = new OperationRefPinning.PackageDeploy("t2", "orders", hash());
            assertThrows(RuntimeException.class, () -> OperationRefPinning.asPackageDeploy(t2,
                    () -> raw(a, t2.deploymentName(), "o2", bound)));
            assertEquals(List.of(), asked);
            // the package's own deploy pins, and a raw deployment without the identity still deploys
            deploy(a, "bound", bound);
            assertEquals(List.of("billing#charge"), asked);
            raw(a, "plain-" + hash(), "plain", plain);
        }
    }

    @Test
    void rewriteKeepsEveryOtherByte() {
        byte[] in = "<a x:operationRef=\"c#o\" y=\"c#o\"/>".getBytes(StandardCharsets.UTF_8);
        assertEquals("<a x:operationRef=\"c@2#o\" y=\"c#o\"/>",
                new String(OperationRefPinning.rewrite(in, Map.of("c#o", "c@2#o")), StandardCharsets.UTF_8));
    }
}
