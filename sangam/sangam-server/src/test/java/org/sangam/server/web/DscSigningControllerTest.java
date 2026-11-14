// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.sangam.integration.dsc.DscCertificateEnrolments;
import org.sangam.integration.dsc.DscEnrolment;
import org.sangam.integration.dsc.DscPreparedStore;
import org.sangam.integration.dsc.DscSigningFlow;
import org.sangam.integration.signature.DscSignatureVerifier;
import org.sangam.integration.signature.DscSigning;
import org.sangam.integration.signature.SignatureVerification;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.DocumentPort;
import org.sangam.rest.port.Download;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.port.TaskPort;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * Owner decisions 165, 227 and 228: the DSC prepare and complete endpoints. The ports are in-memory stand-ins; the flow is the real
 * one with a stand-in for sangam-esign and the verifier.
 */
class DscSigningControllerTest {

    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
    private static final byte[] PDF = "%PDF-1.7 original".getBytes(StandardCharsets.US_ASCII);
    private static final Properties CERTS = new Properties();

    static {
        try (InputStream in = DscSigningControllerTest.class.getResourceAsStream("/dsc/test-certificates.properties")) {
            CERTS.load(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private final RequestContext asha = new RequestContext("T1", "user:asha", Set.of(), null, null, null, null, null, "OIDC");
    private final RequestContext ravi = new RequestContext("T1", "user:ravi", Set.of(), null, null, null, null, null, "OIDC");

    private String assignee = "user:asha";
    private String taskState = "ASSIGNED";
    private final Map<String, DocumentPort.Document> docs = new HashMap<>();
    private final Map<String, byte[]> contents = new HashMap<>();
    private final List<DocumentPort.UploadRequest> newVersions = new ArrayList<>();
    private final Set<String> denied = new java.util.HashSet<>();
    private SignatureVerification.Outcome verdict = SignatureVerification.Outcome.VERIFIED_VALID;
    private final Store store = new Store();
    private byte[] concurrentUpload;
    /** Owner decision 227: Asha's RSA certificate is enrolled and approved by an admin; nothing else is. */
    private final DscEnrolment enrolment = approvedRsa(new DscEnrolment(new Enrolments(), Clock.fixed(NOW,
            ZoneOffset.UTC)));

    private static DscEnrolment approvedRsa(DscEnrolment s) {
        DscCertificateEnrolments.Enrolment e = s.enrol("T1", "user:asha", CERTS.getProperty("rsa"));
        s.approve("T1", e.enrolmentId(), "user:admin", null);
        return s;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.util.function.BiFunction<String, Object[], Object> calls) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
            if (m.isDefault()) {
                return java.lang.reflect.InvocationHandler.invokeDefault(p, m, a);
            }
            return calls.apply(m.getName(), a);
        });
    }

    private TaskPort tasks() {
        return proxy(TaskPort.class, (name, a) -> {
            if (!"get".equals(name)) {
                throw new AssertionError("not used: " + name);
            }
            if (!"task-1".equals(a[1])) {
                throw ApiProblem.notFound("task", (String) a[1]);
            }
            TaskPort.Task t = new TaskPort.Task("task-1", "Sign", null, "sign", "pi-1", "def", null, "BK-1", assignee,
                    null, null, "T1", NOW, null, null, null, null, taskState, 50, null, null);
            return new TaskPort.TaskDetail(t, Map.of(), null, 0, null, null, null, null, null, null, null, null, null,
                    null, null, null, null);
        });
    }

    private DocumentPort documents() {
        return proxy(DocumentPort.class, (name, a) -> switch (name) {
            case "get" -> Optional.ofNullable(docs.get((String) a[1])).orElseThrow(
                    () -> ApiProblem.notFound("document", (String) a[1]));
            case "content" -> new Download("application/pdf", "x.pdf", contents.get(a[1] + "@" + a[2]));
            case "newVersion" -> {
                if (a.length != 4) {
                    throw new AssertionError("decision 228: the signed copy is saved only by compare-and-set");
                }
                if (concurrentUpload != null) {   // another writer wins between the pre-check and the write
                    DocumentPort.Document cur = docs.get((String) a[1]);
                    docs.put(cur.id(), doc(cur.id(), cur.version() + 1, concurrentUpload, cur.taskId(),
                            cur.processInstanceId(), "application/pdf", "CLEAN"));
                }
                DocumentPort.Document old = docs.get((String) a[1]);
                if (old.version() != (int) a[2]) {
                    throw ApiProblem.conflict(DocumentPort.VERSION_CONFLICT, "not at version " + a[2]);
                }
                DocumentPort.UploadRequest u = (DocumentPort.UploadRequest) a[3];
                newVersions.add(u);
                DocumentPort.Document d = doc(old.id(), old.version() + 1, u.content(), old.taskId(),
                        old.processInstanceId(), "application/pdf", "CLEAN");
                docs.put(d.id(), d);
                yield d;
            }
            default -> throw new AssertionError("not used: " + name);
        });
    }

    private PermissionPort permissions() {
        return proxy(PermissionPort.class, (name, a) -> {
            if ("has".equals(name)) {
                return !denied.contains((String) a[1]);
            }
            throw new AssertionError("not used: " + name);
        });
    }

    private DscSigningFlow flow() {
        DscSigning signing = new DscSigning() {
            @Override
            public Prepared prepare(byte[] pdf, X509Certificate signer, Instant signingTime) {
                try {
                    return new Prepared("prepared".getBytes(StandardCharsets.US_ASCII), "b".repeat(64),
                            new byte[]{0x31, 0x00}, DscSigningControllerTest.sha256(signer.getEncoded()), signingTime);
                } catch (java.security.cert.CertificateEncodingException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public byte[] complete(Prepared prepared, X509Certificate signer, List<X509Certificate> chain, String algorithm,
                                   byte[] signature) {
                if (!"good".equals(new String(signature, StandardCharsets.US_ASCII))) {
                    throw new Refused("DSC_SIGNATURE_INVALID", "no", null);
                }
                return "%PDF-1.7 signed".getBytes(StandardCharsets.US_ASCII);
            }
        };
        DscSignatureVerifier verifier = (expected, pdf) -> verdict == SignatureVerification.Outcome.VERIFIED_VALID
                ? new SignatureVerification(verdict, null, null, false, "p1", "CN=Asha Verma",
                expected.signerCertificateSha256(), null, null, null, null, NOW, false)
                : SignatureVerification.indeterminate("REVOCATION_UNAVAILABLE", "ocsp", true, "p1", NOW);
        return new DscSigningFlow(signing, verifier, store, enrolment, Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(10), "p1");
    }

    private DscSigningController controller(boolean installed) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (installed) {
            beans.addBean("flow", flow());
        }
        return new DscSigningController(beans.getBeanProvider(DscSigningFlow.class), tasks(), documents(),
                permissions());
    }

    private static DocumentPort.Document doc(String id, int version, byte[] content, String taskId, String pi,
                                             String type, String scan) {
        return new DocumentPort.Document(id, "T1", "approval.pdf", type, content.length, sha256(content), version, taskId,
                pi, null, scan, "user:x", NOW, Map.of());
    }

    private void attach(String id, String taskId, String pi, String type, String scan) {
        docs.put(id, doc(id, 1, PDF, taskId, pi, type, scan));
        contents.put(id + "@1", PDF);
    }

    private static String sha256(byte[] b) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.US_ASCII));
    }

    private static List<String> chain() {
        return List.of(CERTS.getProperty("rsa"));
    }

    private static ApiProblem problem(org.junit.jupiter.api.function.Executable e) {
        return assertThrows(ApiProblem.class, e);
    }

    @Test
    void withoutTheModuleBothStepsAreNotInstalled() {
        attach("doc-1", "task-1", null, "application/pdf", "CLEAN");
        DscSigningController c = controller(false);
        assertEquals(501, problem(() -> c.prepare(asha, "task-1",
                new DscSigningController.PrepareRequest("doc-1", chain()))).status());
        assertEquals(501, problem(() -> c.complete(asha, "task-1",
                new DscSigningController.CompleteRequest("p", b64("good"), "SHA256withRSA", chain()))).status());
    }

    @Test
    void prepareThenCompleteStoresTheSignedPdfAsTheNextVersion() {
        attach("doc-1", "task-1", null, "application/pdf", "CLEAN");
        DscSigningController c = controller(true);
        DscSigningController.PrepareResponse p = c.prepare(asha, "task-1",
                new DscSigningController.PrepareRequest("doc-1", chain())).getBody();
        assertEquals("SHA256withRSA", p.algorithm());
        assertEquals(1, p.documentVersion());
        assertEquals(NOW.plus(Duration.ofMinutes(10)), p.expiresAt());
        DscSigningController.CompleteResponse r = c.complete(asha, "task-1", new DscSigningController.CompleteRequest(
                p.prepareId(), b64("good"), "SHA256withRSA", chain())).getBody();
        assertEquals("VERIFIED_VALID", r.outcome());
        assertEquals(2, r.signedDocumentVersion());
        assertEquals(1, newVersions.size());
        assertEquals("DSC", newVersions.get(0).metadata().get("signatureType"));
        assertEquals("VERIFIED_VALID", store.outcomes.get(p.prepareId()));
        assertEquals(409, problem(() -> c.complete(asha, "task-1", new DscSigningController.CompleteRequest(
                p.prepareId(), b64("good"), "SHA256withRSA", chain()))).status(), "used once only");
    }

    @Test
    void anUnverifiedSignatureStoresNothing() {
        attach("doc-1", "task-1", null, "application/pdf", "CLEAN");
        verdict = SignatureVerification.Outcome.INDETERMINATE;
        DscSigningController c = controller(true);
        String id = c.prepare(asha, "task-1", new DscSigningController.PrepareRequest("doc-1", chain())).getBody()
                .prepareId();
        DscSigningController.CompleteResponse r = c.complete(asha, "task-1", new DscSigningController.CompleteRequest(id,
                b64("good"), "SHA256withRSA", chain())).getBody();
        assertEquals("INDETERMINATE", r.outcome());
        assertTrue(r.retryable());
        assertNull(r.signedDocumentVersion());
        assertTrue(newVersions.isEmpty());
        String id2 = c.prepare(asha, "task-1", new DscSigningController.PrepareRequest("doc-1", chain())).getBody()
                .prepareId();
        assertEquals("VERIFIED_INVALID", c.complete(asha, "task-1", new DscSigningController.CompleteRequest(id2,
                b64("bad"), "SHA256withRSA", chain())).getBody().outcome());
        assertTrue(newVersions.isEmpty());
    }

    @Test
    void onlyTheAssigneeOfALiveTaskCanSign() {
        attach("doc-1", "task-1", null, "application/pdf", "CLEAN");
        DscSigningController c = controller(true);
        assertEquals(403, problem(() -> c.prepare(ravi, "task-1",
                new DscSigningController.PrepareRequest("doc-1", chain()))).status());
        String id = c.prepare(asha, "task-1", new DscSigningController.PrepareRequest("doc-1", chain())).getBody()
                .prepareId();
        assertTrue(id.matches("[0-9a-f]{32}"));
        taskState = "SUSPENDED";
        assertEquals(409, problem(() -> c.prepare(asha, "task-1",
                new DscSigningController.PrepareRequest("doc-1", chain()))).status());
        taskState = "ASSIGNED";
        assignee = null;
        assertEquals(403, problem(() -> c.complete(asha, "task-1", new DscSigningController.CompleteRequest(id,
                b64("good"), "SHA256withRSA", chain()))).status());
        assertTrue(newVersions.isEmpty());
    }

    @Test
    void onlyACleanPdfOfThisTaskOrItsProcessCanBePrepared() {
        attach("of-process", null, "pi-1", "application/pdf", "NOT_SCANNED");
        attach("other", "task-9", "pi-9", "application/pdf", "CLEAN");
        attach("word", "task-1", null, "application/msword", "CLEAN");
        attach("infected", "task-1", null, "application/pdf", "INFECTED");
        attach("pending", "task-1", null, "application/pdf", "PENDING");
        DscSigningController c = controller(true);
        assertEquals(200, c.prepare(asha, "task-1", new DscSigningController.PrepareRequest("of-process", chain()))
                .getStatusCode().value());
        assertEquals("DOCUMENT_NOT_OF_TASK", problem(() -> c.prepare(asha, "task-1",
                new DscSigningController.PrepareRequest("other", chain()))).code());
        assertEquals("DOCUMENT_NOT_PDF", problem(() -> c.prepare(asha, "task-1",
                new DscSigningController.PrepareRequest("word", chain()))).code());
        assertEquals("DOCUMENT_NOT_CLEAN", problem(() -> c.prepare(asha, "task-1",
                new DscSigningController.PrepareRequest("infected", chain()))).code());
        assertEquals("DOCUMENT_NOT_CLEAN", problem(() -> c.prepare(asha, "task-1",
                new DscSigningController.PrepareRequest("pending", chain()))).code());
    }

    @Test
    void contentThatDoesNotMatchItsChecksumIsRefused() {
        attach("doc-1", "task-1", null, "application/pdf", "CLEAN");
        contents.put("doc-1@1", "%PDF-1.7 swapped".getBytes(StandardCharsets.US_ASCII));
        assertEquals("DOCUMENT_CHANGED", problem(() -> controller(true).prepare(asha, "task-1",
                new DscSigningController.PrepareRequest("doc-1", chain()))).code());
    }

    @Test
    void aDocumentChangedSincePrepareIsNotOverwritten() {
        attach("doc-1", "task-1", null, "application/pdf", "CLEAN");
        DscSigningController c = controller(true);
        String id = c.prepare(asha, "task-1", new DscSigningController.PrepareRequest("doc-1", chain())).getBody()
                .prepareId();
        byte[] v2 = "%PDF-1.7 edited".getBytes(StandardCharsets.US_ASCII);
        docs.put("doc-1", doc("doc-1", 2, v2, "task-1", null, "application/pdf", "CLEAN"));
        assertEquals("DOCUMENT_CHANGED", problem(() -> c.complete(asha, "task-1", new DscSigningController.CompleteRequest(
                id, b64("good"), "SHA256withRSA", chain()))).code());
        assertTrue(newVersions.isEmpty());
        assertEquals("INDETERMINATE", store.outcomes.get(id));
    }

    @Test
    void aSignedCopyIsNotSavedWhenAnotherVersionWinsTheWrite() {
        // owner decision 228: the version moves after the pre-check; the compare-and-set refuses the save
        attach("doc-1", "task-1", null, "application/pdf", "CLEAN");
        DscSigningController c = controller(true);
        String id = c.prepare(asha, "task-1", new DscSigningController.PrepareRequest("doc-1", chain())).getBody()
                .prepareId();
        concurrentUpload = "%PDF-1.7 someone else".getBytes(StandardCharsets.US_ASCII);
        ApiProblem p = problem(() -> c.complete(asha, "task-1", new DscSigningController.CompleteRequest(id,
                b64("good"), "SHA256withRSA", chain())));
        assertEquals(409, p.status());
        assertEquals("DOCUMENT_CHANGED", p.code());
        assertTrue(newVersions.isEmpty(), "the signed copy is not stored");
        assertEquals("INDETERMINATE", store.outcomes.get(id), "recorded as not stored, so it must be signed again");
        assertEquals(2, docs.get("doc-1").version());
    }

    @Test
    void onlyTheCallersApprovedCertificateIsAcceptedNotItsNameAndAMissingFieldIsRejected() {
        attach("doc-1", "task-1", null, "application/pdf", "CLEAN");
        DscSigningController c = controller(true);
        ApiProblem p = problem(() -> c.prepare(asha, "task-1", new DscSigningController.PrepareRequest("doc-1",
                List.of(CERTS.getProperty("other")))));
        assertEquals(422, p.status());
        assertEquals("CERTIFICATE_NOT_ENROLLED", p.code());
        // owner decision 227: the EC certificate shows the same name as the approved one and is still refused
        assertEquals("CERTIFICATE_NOT_ENROLLED", problem(() -> c.prepare(asha, "task-1",
                new DscSigningController.PrepareRequest("doc-1", List.of(CERTS.getProperty("ec"))))).code());
        assertEquals(400, problem(() -> c.prepare(asha, "task-1", new DscSigningController.PrepareRequest(null,
                chain()))).status());
        assertEquals(400, problem(() -> c.complete(asha, "task-1", new DscSigningController.CompleteRequest("p", null,
                "SHA256withRSA", chain()))).status());
    }

    @Test
    void permissionsAreCheckedFirst() {
        attach("doc-1", "task-1", null, "application/pdf", "CLEAN");
        DscSigningController c = controller(true);
        denied.add("document.write");
        assertEquals(403, problem(() -> c.complete(asha, "task-1", new DscSigningController.CompleteRequest("p",
                b64("good"), "SHA256withRSA", chain()))).status());
        denied.add("task.write");
        assertEquals(403, problem(() -> c.prepare(asha, "task-1",
                new DscSigningController.PrepareRequest("doc-1", chain()))).status());
    }

    /** The enrolment store's contract in memory: one row per tenant and certificate, conditional decisions. */
    static final class Enrolments implements DscCertificateEnrolments {
        final Map<String, Enrolment> rows = new HashMap<>();

        @Override
        public boolean enrol(Enrolment e) {
            Enrolment cur = rows.get(e.tenantId() + "|" + e.certificateSha256());
            if (cur != null && LIVE.contains(cur.status())) {
                return false;
            }
            rows.put(e.tenantId() + "|" + e.certificateSha256(), cur == null ? e : cur.reenrolled(e));
            return true;
        }

        @Override
        public Optional<Enrolment> byId(String tenantId, String enrolmentId) {
            return rows.values().stream().filter(e -> e.tenantId().equals(tenantId)
                    && e.enrolmentId().equals(enrolmentId)).findFirst();
        }

        @Override
        public Optional<Enrolment> byCertificate(String tenantId, String certificateSha256) {
            return Optional.ofNullable(rows.get(tenantId + "|" + certificateSha256));
        }

        @Override
        public List<Enrolment> ofPrincipal(String tenantId, String principalId) {
            return rows.values().stream().filter(e -> e.tenantId().equals(tenantId)
                    && e.principalId().equals(principalId)).toList();
        }

        @Override
        public List<Enrolment> withStatus(String tenantId, Status status, int limit) {
            return rows.values().stream().filter(e -> e.tenantId().equals(tenantId) && e.status() == status)
                    .limit(limit).toList();
        }

        @Override
        public boolean decide(String tenantId, String enrolmentId, Set<Status> from, Status to, String decidedBy,
                              Instant at, String reason) {
            Optional<Enrolment> e = byId(tenantId, enrolmentId);
            if (e.isEmpty() || !from.contains(e.get().status())) {
                return false;
            }
            rows.put(tenantId + "|" + e.get().certificateSha256(), e.get().decided(to, decidedBy, at, reason));
            return true;
        }
    }

    /** The store's contract in memory. */
    static final class Store implements DscPreparedStore {
        final Map<String, Entry> entries = new HashMap<>();
        final Set<String> used = new java.util.HashSet<>();
        final Map<String, String> outcomes = new HashMap<>();

        @Override
        public void save(Entry e) {
            entries.put(e.prepareId(), e);
        }

        @Override
        public Optional<Entry> consume(String tenantId, String prepareId, String taskId, String principalId, Instant now) {
            Entry e = entries.get(prepareId);
            if (e == null || used.contains(prepareId) || !e.tenantId().equals(tenantId) || !e.taskId().equals(taskId)
                    || !e.principalId().equals(principalId) || !e.expiresAt().isAfter(now)) {
                return Optional.empty();
            }
            used.add(prepareId);
            return Optional.of(e);
        }

        @Override
        public void recordOutcome(String tenantId, String prepareId, String outcome, String reasonCode,
                                  Integer signedDocumentVersion, Instant at) {
            outcomes.put(prepareId, outcome);
        }

        @Override
        public List<VerifiedSignature> verifiedSignatures(String tenantId, String taskId, String documentId,
                                                          String principalId) {
            throw new AssertionError("not used by the controller");
        }

        @Override
        public int purgeExpired(Instant before) {
            return 0;
        }
    }
}
