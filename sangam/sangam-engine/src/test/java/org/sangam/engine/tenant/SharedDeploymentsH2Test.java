// SPDX-License-Identifier: Apache-2.0
package org.sangam.engine.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.sangam.core.engine.AuditSink;
import org.sangam.core.security.SecurityIdentity;
import org.sangam.engine.OperatonEngineAdapter;
import org.sangam.engine.SangamEngineFactory;
import org.sangam.engine.SangamEngineSettings;
import org.sangam.engine.tenant.SharedDeployments.Registration;
import org.sangam.engine.tenant.SharedDeployments.SharedDeploymentException;

/**
 * Decision 144 / 197 registry: register and revoke write the row and the audit event (class B, root
 * {@code platform:shared-deployment:<id>}, under the system audit scope) together; the W4-M codes; a platform deployer
 * only; revocation is final; the active lookup. H2 only (product changelog 0045-06-shared_deployment); Oracle and SQL
 * Server NOT_RUN. Written, NOT_RUN.
 */
class SharedDeploymentsH2Test {

    private static final String SCOPE = "system:platform";
    private static final Instant T0 = Instant.parse("2026-11-13T04:00:00Z");
    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="t">
              <process id="p" isExecutable="true"><startEvent id="s"/><sequenceFlow id="f" sourceRef="s" targetRef="e"/>
                <endEvent id="e"/></process>
            </definitions>
            """;

    record Audited(String tenant, String root, String type, Map<String, Object> payload) {
    }

    private final List<Audited> audited = new CopyOnWriteArrayList<>();

    private OperatonEngineAdapter engine() {
        SangamEngineSettings s = new SangamEngineSettings();
        s.setSystemAuditScope(SCOPE);
        s.setAllowMultipleEnginesPerJvm(true);
        s.setEngineSchemaUpdate("true");
        s.setApplyEnvironment(false);
        s.setAuditSink((AuditSink) (c, t, r, e, p) -> audited.add(new Audited(t, r, e, p)));
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:sd" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        OperatonEngineAdapter a = SangamEngineFactory.build(ds, s, "h2");
        a.setClock(T0);
        return a;
    }

    private static SecurityIdentity identity(Set<String> groups, boolean system) {
        return new SecurityIdentity("platform", "ops-1", "ops-1", groups, Set.of(), Set.of(), Set.of(), "OIDC", null,
                null, system, Map.of());
    }

    private static final SecurityIdentity DEPLOYER =
            identity(Set.of(SharedDeployments.PLATFORM_MARKER, SharedDeployments.DEPLOYER_GROUP), false);

    private static String code(Runnable r) {
        return assertThrows(SharedDeploymentException.class, r::run).code();
    }

    private static byte[] bpmn() {
        return BPMN.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void registerAndRevokeWriteTheRowAndTheAuditEventTogether() {
        try (OperatonEngineAdapter a = engine()) {
            String dep = a.deploy(null, "shared", Map.of("p.bpmn", bpmn()));
            SharedDeployments reg = new SharedDeployments(a);

            Registration r = reg.register(DEPLOYER, dep, "platform housekeeping");
            assertTrue(r.active());
            assertEquals("ops-1", r.registeredBy());
            assertEquals(T0, r.registeredAt());
            assertEquals(r, reg.find(dep).orElseThrow());
            assertTrue(a.<Boolean>inEngineTransaction(c -> SharedDeployments.isActive(c, dep)).booleanValue());
            Audited reg1 = audited.get(audited.size() - 1);
            assertEquals(SharedDeployments.REGISTERED, reg1.type());
            assertEquals(SCOPE, reg1.tenant(), "under the system audit scope");
            assertEquals("platform:shared-deployment:" + dep, reg1.root());
            assertEquals(Map.of("deploymentId", dep, "actor", "ops-1", "reason", "platform housekeeping",
                    "registeredAt", T0.toString()), reg1.payload());

            a.setClock(T0.plusSeconds(60));
            Registration v = reg.revoke(DEPLOYER, dep, "no longer shared");
            assertFalse(v.active());
            assertEquals(T0.plusSeconds(60), v.revokedAt());
            assertFalse(a.<Boolean>inEngineTransaction(c -> SharedDeployments.isActive(c, dep)).booleanValue());
            Audited rev = audited.get(audited.size() - 1);
            assertEquals(SharedDeployments.REVOKED, rev.type());
            assertEquals(SCOPE, rev.tenant());
            assertEquals("ops-1", rev.payload().get("registeredBy"));
            assertEquals(T0.toString(), rev.payload().get("registeredAt"));
            assertEquals(T0.plusSeconds(60).toString(), rev.payload().get("revokedAt"));

            assertEquals(List.of(), reg.list(false));
            assertEquals(List.of(v), reg.list(true));
        }
    }

    @Test
    void theContractCodes() {
        try (OperatonEngineAdapter a = engine()) {
            String shared = a.deploy(null, "shared", Map.of("p.bpmn", bpmn()));
            String owned = a.deploy("t1", "owned", Map.of("p.bpmn", bpmn()));
            SharedDeployments reg = new SharedDeployments(a);
            assertEquals(SharedDeployments.VALIDATION_FAILED, code(() -> reg.register(DEPLOYER, "", "r")));
            assertEquals(SharedDeployments.VALIDATION_FAILED, code(() -> reg.register(DEPLOYER, "x".repeat(65), "r")));
            assertEquals(SharedDeployments.VALIDATION_FAILED, code(() -> reg.register(DEPLOYER, shared, " ")));
            assertEquals(SharedDeployments.VALIDATION_FAILED, code(() -> reg.register(DEPLOYER, shared, "r".repeat(1001))));
            assertEquals(SharedDeployments.INVALID, code(() -> reg.register(DEPLOYER, "no-such-deployment", "r")));
            assertEquals(SharedDeployments.INVALID, code(() -> reg.register(DEPLOYER, owned, "r")), "tenant-owned");
            assertEquals(SharedDeployments.NOT_FOUND, code(() -> reg.revoke(DEPLOYER, shared, "r")));
            reg.register(DEPLOYER, shared, "r");
            assertEquals(SharedDeployments.REGISTERED, code(() -> reg.register(DEPLOYER, shared, "again")));
            reg.revoke(DEPLOYER, shared, "done");
            assertEquals(SharedDeployments.REVOKED, code(() -> reg.revoke(DEPLOYER, shared, "again")));
            assertEquals(SharedDeployments.REVOKED, code(() -> reg.register(DEPLOYER, shared, "again")),
                    "revocation is final");
            int before = audited.size();
            assertEquals(SharedDeployments.NOT_PERMITTED, code(() -> reg.register(null, shared, "r")));
            assertEquals(SharedDeployments.NOT_PERMITTED,
                    code(() -> reg.register(identity(Set.of(SharedDeployments.PLATFORM_MARKER), false), shared, "r")));
            assertEquals(SharedDeployments.NOT_PERMITTED,
                    code(() -> reg.register(identity(Set.of(SharedDeployments.DEPLOYER_GROUP), false), shared, "r")),
                    "a tenant token never carries the platform marker");
            assertEquals(SharedDeployments.NOT_PERMITTED, code(() -> reg.register(identity(
                    Set.of(SharedDeployments.PLATFORM_MARKER, SharedDeployments.DEPLOYER_GROUP), true), shared, "r")),
                    "never the system identity");
            assertEquals(before, audited.size(), "a refusal writes no audit");
        }
    }

    @Test
    void blankIdsAreNeverActive() {
        assertFalse(SharedDeployments.isActive(null, null));
        assertFalse(SharedDeployments.isActive(null, " "));
    }
}
