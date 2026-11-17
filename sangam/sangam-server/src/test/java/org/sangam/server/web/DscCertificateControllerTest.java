// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.sangam.integration.dsc.DscEnrolment;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.TxSupport;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * Owner decision 227: DSC certificate enrolment over REST: the signer enrols, an admin other than the signer decides,
 * every change is audited inside the same transaction, refusals change nothing and write no record.
 */
class DscCertificateControllerTest {

    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
    private static final Properties CERTS = new Properties();

    static {
        try (InputStream in = DscCertificateControllerTest.class.getResourceAsStream("/dsc/test-certificates.properties")) {
            CERTS.load(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private final RequestContext asha = ctx("user:asha");
    private final RequestContext ravi = ctx("user:ravi");
    private final RequestContext admin = ctx("user:admin");
    private final Map<String, Set<String>> granted = Map.of(
            "user:asha", Set.of("task.write"),
            "user:ravi", Set.of("task.write"),
            "user:admin", Set.of("task.write", DscCertificateController.APPROVE_PERMISSION));
    private final List<String> events = new ArrayList<>();
    private final List<String> chains = new ArrayList<>();
    private int txDepth;

    private static RequestContext ctx(String principal) {
        return new RequestContext("T1", principal, Set.of(), null, null, null, null, null, "OIDC");
    }

    private TxSupport tx() {
        return new TxSupport(null, null) {
            @Override
            public <T> T inTxWithStatus(Function<TxStatus, T> work) {
                txDepth++;
                try {
                    return work.apply(new TxStatus() {
                        @Override
                        public void setRollbackOnly() {
                        }

                        @Override
                        public boolean isRollbackOnly() {
                            return false;
                        }
                    });
                } finally {
                    txDepth--;
                }
            }

            @Override
            public <T> T withReadConnection(org.sangam.core.api.ReadWork<T> work) {
                txDepth++;
                try {
                    return work.read(connection());
                } finally {
                    txDepth--;
                }
            }

            @Override
            public <T> T inNewTx(Supplier<T> work) {
                throw new AssertionError("the audit record joins the change's transaction");
            }

            @Override
            public <T> T inNewReadTx(org.sangam.core.api.ReadWork<T> work) {
                throw new AssertionError("the audit record joins the change's transaction");
            }

            @Override
            public Connection connection() {
                throw new AssertionError("not used");
            }

            @Override
            public void afterCommit(Runnable work) {
                work.run();
            }
        };
    }

    private AuditPort audit() {
        return (AuditPort) Proxy.newProxyInstance(AuditPort.class.getClassLoader(), new Class<?>[]{AuditPort.class},
                (p, m, a) -> {
                    if (!"record".equals(m.getName())) {
                        throw new AssertionError("not used: " + m.getName());
                    }
                    assertTrue(txDepth > 0, "the audit record is written in the change's transaction");
                    chains.add((String) a[1]);
                    events.add((String) a[2]);
                    return "evt";
                });
    }

    private PermissionPort permissions() {
        return (PermissionPort) Proxy.newProxyInstance(PermissionPort.class.getClassLoader(),
                new Class<?>[]{PermissionPort.class}, (p, m, a) -> {
                    if (m.isDefault()) {
                        return java.lang.reflect.InvocationHandler.invokeDefault(p, m, a);
                    }
                    if ("has".equals(m.getName())) {
                        return granted.getOrDefault(((RequestContext) a[0]).principalId(), Set.of()).contains(a[1]);
                    }
                    throw new AssertionError("not used: " + m.getName());
                });
    }

    private DscCertificateController controller(boolean installed) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (installed) {
            beans.addBean("enrolment", new DscEnrolment(new DscSigningControllerTest.Enrolments(),
                    Clock.fixed(NOW, ZoneOffset.UTC)));
        }
        return new DscCertificateController(beans.getBeanProvider(DscEnrolment.class), tx(), audit(), permissions());
    }

    private static DscCertificateController.EnrolRequest rsa() {
        return new DscCertificateController.EnrolRequest(CERTS.getProperty("rsa"));
    }

    private static DscCertificateController.DecisionRequest reason(String r) {
        return new DscCertificateController.DecisionRequest(r);
    }

    private static ApiProblem problem(org.junit.jupiter.api.function.Executable e) {
        return assertThrows(ApiProblem.class, e);
    }

    private static String sha(String name) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(Base64.getDecoder().decode(CERTS.getProperty(name))));
    }

    @Test
    void withoutTheOptInEveryRouteIsNotInstalled() {
        DscCertificateController c = controller(false);
        assertEquals(501, problem(() -> c.enrol(asha, rsa())).status());
        assertEquals(501, problem(() -> c.mine(asha)).status());
        assertEquals(501, problem(() -> c.approve(admin, "e1", reason(null))).status());
        assertTrue(events.isEmpty());
    }

    @Test
    void enrolThenApproveByAnotherAdminIsAuditedOnTheCertificatesChain() throws Exception {
        DscCertificateController c = controller(true);
        var created = c.enrol(asha, rsa());
        assertEquals(201, created.getStatusCode().value());
        DscCertificateController.CertificateView v = created.getBody();
        assertEquals("PENDING", v.status());
        assertEquals(sha("rsa"), v.certificateSha256());
        assertEquals("user:asha", v.principalId());
        assertEquals(1, c.mine(asha).getBody().size());
        assertEquals(1, c.list(admin, null, null).getBody().size(), "the admin queue is PENDING by default");
        DscCertificateController.CertificateView a = c.approve(admin, v.enrolmentId(), reason("token seen")).getBody();
        assertEquals("APPROVED", a.status());
        assertEquals("user:admin", a.decidedBy());
        assertEquals(List.of("DSC_CERTIFICATE_ENROLLED", "DSC_CERTIFICATE_APPROVED"), events);
        assertEquals(List.of("dsc-certificate:" + sha("rsa"), "dsc-certificate:" + sha("rsa")), chains);
        assertEquals("APPROVED", c.get(admin, v.enrolmentId()).getBody().status());
    }

    @Test
    void theSignerCannotApproveTheirOwnEnrolmentAndARefusalWritesNothing() {
        DscCertificateController c = controller(true);
        String id = c.enrol(asha, rsa()).getBody().enrolmentId();
        events.clear();
        assertEquals(403, problem(() -> c.approve(asha, id, reason(null))).status(), "no admin permission");
        // the admin enrols a certificate of their own and tries to approve it
        String own = c.enrol(admin, new DscCertificateController.EnrolRequest(CERTS.getProperty("ec"))).getBody()
                .enrolmentId();
        events.clear();
        ApiProblem self = problem(() -> c.approve(admin, own, reason(null)));
        assertEquals(403, self.status());
        assertEquals("SELF_APPROVAL", self.code());
        assertEquals("REASON_REQUIRED", problem(() -> c.reject(admin, id, reason(" "))).code());
        assertEquals("ENROLMENT_NOT_FOUND", problem(() -> c.approve(admin, "nope", reason(null))).code());
        assertEquals(404, problem(() -> c.approve(admin, "nope", reason(null))).status());
        assertTrue(events.isEmpty(), "nothing is audited for a refused request");
    }

    @Test
    void theSignerOrAnAdminRevokesAndAStrangerCannot() {
        DscCertificateController c = controller(true);
        String id = c.enrol(asha, rsa()).getBody().enrolmentId();
        c.approve(admin, id, reason(null));
        assertEquals(404, problem(() -> c.revokeMine(ravi, id, reason("not mine"))).status());
        assertEquals("REVOKED", c.revokeMine(asha, id, reason("token lost")).getBody().status());
        assertEquals("DSC_CERTIFICATE_REVOKED", events.get(events.size() - 1));
        assertEquals("ENROLMENT_NOT_LIVE", problem(() -> c.revoke(admin, id, reason("again"))).code());
        String again = c.enrol(asha, rsa()).getBody().enrolmentId();
        assertEquals("REVOKED", c.revoke(admin, again, reason("left")).getBody().status());
    }

    @Test
    void badInputIsRefused() {
        DscCertificateController c = controller(true);
        assertEquals(400, problem(() -> c.enrol(asha, null)).status());
        ApiProblem bad = problem(() -> c.enrol(asha, new DscCertificateController.EnrolRequest("bm90IGEgY2VydA==")));
        assertEquals(422, bad.status());
        assertEquals("CERTIFICATE_INVALID", bad.code());
        assertEquals(422, problem(() -> c.enrol(asha, new DscCertificateController.EnrolRequest(
                CERTS.getProperty("dsa")))).status());
        c.enrol(asha, rsa());
        assertEquals(409, problem(() -> c.enrol(ravi, rsa())).status(), "a live certificate is enrolled once");
        assertEquals(400, problem(() -> c.list(admin, "SOMETIMES", null)).status());
        assertEquals("VALIDATION_FAILED", problem(() -> c.list(admin, "PENDING", 0)).code(), "limit is 1 to 200");
        assertEquals(400, problem(() -> c.list(admin, "PENDING", 201)).status());
        assertEquals(1, c.list(admin, "PENDING", 200).getBody().size());
        assertEquals(403, problem(() -> c.list(asha, "PENDING", null)).status());
    }
}
