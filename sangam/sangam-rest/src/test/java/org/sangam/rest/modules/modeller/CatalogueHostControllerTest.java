// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.modeller;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditCheckpoints;
import org.sangam.core.layer.Dialect;
import org.sangam.core.layer.Hashes;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.modeller.catalogue.CatalogueClient;
import org.sangam.modeller.catalogue.CatalogueSource;
import org.sangam.modeller.catalogue.ExtensionCatalogue;
import org.sangam.rest.config.SpringTxSupport;
import org.sangam.modeller.template.ElementTemplates;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.AuditPort;
import org.sangam.rest.port.PackagePort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.DocumentStore;
import org.sangam.rest.support.Permissions;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M-128 / NG-17 step 2, owner decision 380: the hosting side. A publisher submits a signed bundle, another person
 * approves it, the host signs the index with its own catalogue key (secret references), and a consumer configured with
 * the host's public key installs the entry through the hosted index into its own tenant.
 */
class CatalogueHostControllerTest {

    static final RequestContext ALICE = ModellerControllerDiffTest.CTX;
    static final RequestContext BOB = new RequestContext("t-platform", "bob", Set.of(), null, null, null, null, "127.0.0.1", "DEV_TOKEN");
    static final RequestContext CAROL = new RequestContext("t2", "carol", Set.of(), null, null, null, null, "127.0.0.1", "DEV_TOKEN");
    static final String BASE = "https://catalogue.example.org/api/v1/catalogue/hosted/";

    final KeyPair hostKey;
    final List<PackagePort.PackageImportRequest> imports = new ArrayList<>();
    boolean bundleVerifies = true;
    DocumentStore docs;
    SpringTxSupport tx;
    ElementTemplates templates;
    /** Every audit record: chain, event type, payload. */
    final List<Object[]> audited = new ArrayList<>();
    boolean auditFails;

    CatalogueHostControllerTest() throws Exception {
        hostKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    @BeforeEach
    void db() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:cathost" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            // REST_DOCUMENT as in sangam-persistence (changeset 0016-01-rest_document)
            s.execute("CREATE TABLE REST_DOCUMENT (tenant_id VARCHAR(64) NOT NULL, kind VARCHAR(64) NOT NULL,"
                    + " doc_id VARCHAR(64) NOT NULL, doc_key VARCHAR(255), revision BIGINT NOT NULL, state VARCHAR(32),"
                    + " owner_id VARCHAR(255), ref_id VARCHAR(255), body CHARACTER LARGE OBJECT NOT NULL, created_by VARCHAR(255),"
                    + " created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,"
                    + " CONSTRAINT PK_REST_DOCUMENT PRIMARY KEY (tenant_id, kind, doc_id))");
            // MOD_TEMPLATE as in sangam-persistence (baseline)
            s.execute("CREATE TABLE MOD_TEMPLATE (tenant_id VARCHAR(64) NOT NULL, template_id VARCHAR(255) NOT NULL,"
                    + " version INTEGER NOT NULL, kind VARCHAR(64) NOT NULL, document CHARACTER LARGE OBJECT NOT NULL,"
                    + " created_by VARCHAR(255) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,"
                    + " CONSTRAINT PK_MOD_TEMPLATE PRIMARY KEY (tenant_id, template_id, version))");
        }
        tx = new SpringTxSupport(ds, new DataSourceTransactionManager(ds), Dialect.POSTGRESQL);
        docs = new DocumentStore(tx);
        templates = new ElementTemplates(Dialect.POSTGRESQL, tx.newTransactions());
    }

    /** Records every class-B event; fails each write while {@link #auditFails} is set. */
    AuditPort audit() {
        return (AuditPort) Proxy.newProxyInstance(AuditPort.class.getClassLoader(), new Class<?>[] {AuditPort.class},
                (proxy, m, args) -> {
                    if (!m.getName().equals("record")) {
                        throw new UnsupportedOperationException(m.getName());
                    }
                    if (auditFails) {
                        throw new IllegalStateException("audit chain unavailable");
                    }
                    // the event joins the caller's transaction: the connection the entry change was written on
                    assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),
                            "audit outside a transaction");
                    audited.add(new Object[] {args[1], args[2], args[3]});
                    return "ev-" + audited.size();
                });
    }

    List<String> events() {
        return audited.stream().map(a -> (String) a[1]).toList();
    }

    PackagePort packages() {
        return (PackagePort) Proxy.newProxyInstance(PackagePort.class.getClassLoader(), new Class<?>[] {PackagePort.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "verify" -> {
                        PackagePort.PackageBundle b = (PackagePort.PackageBundle) args[1];
                        yield new PackagePort.PackageVerification(b.packageHash(), bundleVerifies ? b.packageHash() : "other",
                                bundleVerifies, true, List.of(new PackagePort.SignatureCheck("APPROVER", "k1", true, "OK")),
                                List.of(), List.of(), List.of(), bundleVerifies);
                    }
                    case "importBundle" -> {
                        imports.add((PackagePort.PackageImportRequest) args[1]);
                        yield new PackagePort.Package("pkg-9", "pay", "pay", 1, "APPROVED", "ab12", Map.of(), "carol", null, null,
                                null, null, null, null, Instant.EPOCH, Instant.EPOCH, List.of(), Map.of());
                    }
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    static PackagePort.PackageBundle bundle() {
        return new PackagePort.PackageBundle(1, "ab12", Map.of("key", "pay"), Map.of("pay.bpmn", "<x/>"),
                List.of(new PackagePort.PackageSignature("ab12", "APPROVER", "dave", null, "Ed25519", "k1", "c2ln", Instant.parse("2026-11-01T00:00:00Z"))),
                "dev", Instant.parse("2026-11-01T00:00:00Z"));
    }

    SecretResolver hostSecrets() {
        return SecretResolver.of(Map.of(
                "secret://catalogue/host-private", Base64.getEncoder().encodeToString(hostKey.getPrivate().getEncoded()),
                "secret://catalogue/host-public", Base64.getEncoder().encodeToString(hostKey.getPublic().getEncoded())));
    }

    MockEnvironment hostEnv() {
        return new MockEnvironment()
                .withProperty(CatalogueHostController.P + "enabled", "true")
                .withProperty(CatalogueHostController.P + "signing-key-ref", "secret://catalogue/host-private")
                .withProperty(CatalogueHostController.P + "public-key-ref", "secret://catalogue/host-public");
    }

    CatalogueHostController host(String... granted) {
        return new CatalogueHostController(packages(), new ModellerControllerDiffTest.Grants(granted), docs,
                CatalogueHostController.signer(hostEnv(), hostSecrets()), audit(), tx);
    }

    static CatalogueHostController.CatalogueSubmissionRequest submission(String id) {
        return new CatalogueHostController.CatalogueSubmissionRequest(id, "1.0.0", "Apache-2.0", "0.1.0", bundle(), "payments pack");
    }

    /** The hosted URLs answered by the host controller, as the consumer's transport would fetch them. */
    static CatalogueSource.Fetch over(CatalogueHostController h) {
        return uri -> {
            String path = uri.toString().substring(BASE.length());
            if (path.equals("index.json")) {
                return h.index().getBody();
            }
            if (path.equals("index.json.sig")) {
                return h.signature().getBody().getBytes(StandardCharsets.UTF_8);
            }
            String[] p = path.split("/");
            try {
                return h.artifact(p[1], p[2]).getBody();
            } catch (ApiProblem e) {
                throw new ExtensionCatalogue.CatalogueException("CATALOGUE_UNREACHABLE", "HTTP " + e.status());
            }
        };
    }

    @Test
    void submittedEntryReachesTheSignedIndexOnlyAfterAnotherPersonApprovesIt() throws Exception {
        CatalogueHostController publisher = host(CatalogueHostController.PUBLISH, CatalogueHostController.REVIEW);
        var created = publisher.submit(ALICE, submission("pay"));
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        assertEquals("SUBMITTED", created.getBody().state());
        assertEquals("t1/alice", created.getBody().publisher());
        assertEquals("artifacts/pay/1.0.0", created.getBody().artifactPath());
        assertEquals("{\"format\":1,\"entries\":[]}", new String(publisher.index().getBody(), StandardCharsets.UTF_8));
        assertEquals(404, assertThrows(ApiProblem.class, () -> publisher.artifact("pay", "1.0.0")).status());

        // four-eyes: the publisher may hold the review permission and still cannot approve the own entry
        ApiProblem sod = assertThrows(ApiProblem.class, () -> publisher.approve(ALICE, "pay", "1.0.0", null));
        assertEquals("SEGREGATION_OF_DUTIES", sod.code());
        assertEquals("SUBMITTED", publisher.entries(ALICE, null).getBody().get(0).state());

        CatalogueHostController reviewer = host(CatalogueHostController.REVIEW);
        var approved = reviewer.approve(BOB, "pay", "1.0.0", new CatalogueHostController.CatalogueReviewRequest("checked"));
        assertEquals("APPROVED", approved.state());
        assertEquals("bob", approved.reviewedBy());
        assertEquals("CATALOGUE_ENTRY_NOT_SUBMITTED", assertThrows(ApiProblem.class,
                () -> reviewer.reject(BOB, "pay", "1.0.0", new CatalogueHostController.CatalogueReviewRequest("late"))).code());

        // the consumer trusts the host's public key (by reference) and installs into its own tenant
        CatalogueClient consumer = new CatalogueClient(new CatalogueSource.Hosted(URI.create(BASE + "index.json"), over(reviewer)),
                List.of(hostKey.getPublic()), Set.of("Apache-2.0"), "0.1.0");
        ExtensionCatalogue.Entry e = consumer.entries().get(0);
        assertEquals("package", e.kind());
        assertEquals(Hashes.hex(Hashes.sha256(reviewer.artifact("pay", "1.0.0").getBody())), e.sha256());
        ExtensionCatalogueController install = new ExtensionCatalogueController(packages(),
                new ModellerControllerDiffTest.Grants(Permissions.PACKAGE_DEPLOY), consumer);
        assertEquals(HttpStatus.CREATED, install.install(CAROL, "pay", "1.0.0", null).getStatusCode());
        assertEquals(1, imports.size());
        assertEquals("ab12", imports.get(0).bundle().packageHash());
        assertEquals("catalogue pay@1.0.0 (t1/alice)", imports.get(0).reason());

        // a consumer that does not trust the host key refuses the same index
        CatalogueClient stranger = new CatalogueClient(new CatalogueSource.Hosted(URI.create(BASE + "index.json"), over(reviewer)),
                List.of(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic()), Set.of("Apache-2.0"), "0.1.0");
        assertEquals("CATALOGUE_SIGNATURE_INVALID", assertThrows(ExtensionCatalogue.CatalogueException.class, stranger::entries).code());
    }

    @Test
    void rejectedEntryNeverReachesTheIndexAndAVersionIsNeverReplaced() {
        CatalogueHostController h = host(CatalogueHostController.PUBLISH, CatalogueHostController.REVIEW);
        h.submit(ALICE, submission("pay"));
        assertEquals("CATALOGUE_ENTRY_EXISTS", assertThrows(ApiProblem.class, () -> h.submit(ALICE, submission("pay"))).code());
        assertEquals("REASON_REQUIRED", assertThrows(ApiProblem.class, () -> h.reject(BOB, "pay", "1.0.0", null)).code());
        assertEquals("REJECTED", h.reject(BOB, "pay", "1.0.0", new CatalogueHostController.CatalogueReviewRequest("unsigned pack")).state());
        assertEquals("{\"format\":1,\"entries\":[]}", new String(h.index().getBody(), StandardCharsets.UTF_8));
        assertEquals(404, assertThrows(ApiProblem.class, () -> h.artifact("pay", "1.0.0")).status());
        assertEquals(1, h.entries(BOB, "REJECTED").getBody().size());
        assertEquals(0, h.entries(BOB, "APPROVED").getBody().size());
    }

    @Test
    void unverifiedBundleAndInvalidIdAreRefusedAndNothingIsStored() {
        CatalogueHostController h = host(CatalogueHostController.PUBLISH);
        bundleVerifies = false;
        assertEquals("CATALOGUE_BUNDLE_UNVERIFIED", assertThrows(ApiProblem.class, () -> h.submit(ALICE, submission("pay"))).code());
        bundleVerifies = true;
        for (String bad : List.of("../x", "Pay", "a/b", "", "a..b")) {
            assertEquals("CATALOGUE_ENTRY_ID_INVALID", assertThrows(ExtensionCatalogue.CatalogueException.class,
                    () -> h.submit(ALICE, submission(bad))).code(), bad);
        }
        assertEquals(0, docs.all(DocumentStore.GLOBAL, CatalogueHostController.KIND, DocumentStore.Filter.ALL).size());
    }

    /** The routes need the platform-only permissions; a tenant persona group holds neither. */
    @Test
    void submitAndReviewNeedThePlatformOnlyPermissions() {
        CatalogueHostController none = host(Permissions.PACKAGE_DEPLOY, Permissions.PACKAGE_REVIEW, Permissions.PACKAGE_AUTHOR);
        assertThrows(ApiProblem.class, () -> none.submit(ALICE, submission("pay")));
        assertThrows(ApiProblem.class, () -> none.approve(BOB, "pay", "1.0.0", null));
        assertThrows(ApiProblem.class, () -> none.entries(BOB, null));
        for (var groupPermissions : Permissions.DEFAULT_GROUP_PERMISSIONS.values()) {
            assertTrue(!groupPermissions.contains(CatalogueHostController.PUBLISH) && !groupPermissions.contains(CatalogueHostController.REVIEW));
        }
    }

    /** Hosting is off by default; when on, the two key references must resolve to one Ed25519 pair. */
    @Test
    void hostKeysAreSecretReferencesOfOnePair() throws Exception {
        assertNull(CatalogueHostController.signer(new MockEnvironment(), hostSecrets()));
        CatalogueHostController off = new CatalogueHostController(packages(), new ModellerControllerDiffTest.Grants(CatalogueHostController.PUBLISH), docs, null, null, null);
        assertEquals(404, assertThrows(ApiProblem.class, off::index).status());
        assertThrows(IllegalStateException.class, () -> CatalogueHostController.signer(
                new MockEnvironment().withProperty(CatalogueHostController.P + "enabled", "true"), hostSecrets()));
        MockEnvironment literal = hostEnv().withProperty(CatalogueHostController.P + "signing-key-ref",
                Base64.getEncoder().encodeToString(hostKey.getPrivate().getEncoded()));
        assertTrue(assertThrows(IllegalStateException.class, () -> CatalogueHostController.signer(literal, hostSecrets()))
                .getMessage().contains("CATALOGUE_KEY_NOT_A_REFERENCE"));
        KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        SecretResolver mismatched = SecretResolver.of(Map.of(
                "secret://catalogue/host-private", Base64.getEncoder().encodeToString(hostKey.getPrivate().getEncoded()),
                "secret://catalogue/host-public", Base64.getEncoder().encodeToString(other.getPublic().getEncoded())));
        assertTrue(assertThrows(IllegalStateException.class, () -> CatalogueHostController.signer(hostEnv(), mismatched))
                .getMessage().contains("CATALOGUE_KEY_INVALID"));
        assertThrows(IllegalStateException.class, () -> new CatalogueHostController(packages(),
                new ModellerControllerDiffTest.Grants(), null, CatalogueHostController.signer(hostEnv(), hostSecrets()), audit(), tx));
        // hosting on without the audit port or the transactions is refused: no entry may change unaudited
        assertThrows(IllegalStateException.class, () -> new CatalogueHostController(packages(),
                new ModellerControllerDiffTest.Grants(), docs, CatalogueHostController.signer(hostEnv(), hostSecrets()), null, tx));
        assertThrows(IllegalStateException.class, () -> new CatalogueHostController(packages(),
                new ModellerControllerDiffTest.Grants(), docs, CatalogueHostController.signer(hostEnv(), hostSecrets()), audit(), null));
        AuditCheckpoints.Signer s = CatalogueHostController.signer(hostEnv(), hostSecrets());
        assertEquals(AuditCheckpoints.keyId(hostKey.getPublic()), s.keyId());
        CatalogueHostController h = host(CatalogueHostController.PUBLISH);
        java.security.Signature v = java.security.Signature.getInstance("Ed25519");
        v.initVerify(hostKey.getPublic());
        v.update(h.index().getBody());
        assertTrue(v.verify(Base64.getDecoder().decode(h.signature().getBody())));
        assertArrayEquals(h.index().getBody(), h.index().getBody());
    }

    /** D4: submit, approve and reject each write their catalogued class-B event on the entry's chain. */
    @Test
    void submitApproveAndRejectAreAuditedOnTheEntryChain() {
        CatalogueHostController h = host(CatalogueHostController.PUBLISH, CatalogueHostController.REVIEW);
        var created = h.submit(ALICE, submission("pay")).getBody();
        h.approve(BOB, "pay", "1.0.0", new CatalogueHostController.CatalogueReviewRequest("checked"));
        h.submit(ALICE, submission("ledger"));
        h.reject(BOB, "ledger", "1.0.0", new CatalogueHostController.CatalogueReviewRequest("unsigned pack"));
        assertEquals(List.of("CATALOGUE_ENTRY_SUBMITTED", "CATALOGUE_ENTRY_APPROVED", "CATALOGUE_ENTRY_SUBMITTED",
                "CATALOGUE_ENTRY_REJECTED"), events());
        assertEquals(CatalogueHostController.AUDIT_EVENTS, List.of(CatalogueHostController.SUBMITTED_EVENT,
                CatalogueHostController.APPROVED_EVENT, CatalogueHostController.REJECTED_EVENT));
        assertEquals("catalogue:pay@1.0.0", audited.get(0)[0]);
        assertEquals("catalogue:ledger@1.0.0", audited.get(3)[0]);
        @SuppressWarnings("unchecked")
        Map<String, Object> submitted = (Map<String, Object>) audited.get(0)[2];
        assertEquals("pay", submitted.get("entryId"));
        assertEquals("1.0.0", submitted.get("entryVersion"));
        assertEquals("package", submitted.get("kind"));
        assertEquals(created.sha256(), submitted.get("sha256"));
        assertEquals("SUBMITTED", submitted.get("state"));
        assertEquals("alice", submitted.get("submittedBy"));
        assertEquals("payments pack", submitted.get("reason"));
        @SuppressWarnings("unchecked")
        Map<String, Object> approved = (Map<String, Object>) audited.get(1)[2];
        assertEquals("APPROVED", approved.get("state"));
        assertEquals("bob", approved.get("reviewedBy"));
        assertEquals("checked", approved.get("reason"));
        @SuppressWarnings("unchecked")
        Map<String, Object> rejected = (Map<String, Object>) audited.get(3)[2];
        assertEquals("REJECTED", rejected.get("state"));
        assertEquals("unsigned pack", rejected.get("reason"));

        // refused calls change nothing and write no event
        int before = audited.size();
        assertThrows(ApiProblem.class, () -> h.submit(ALICE, submission("pay")));
        assertThrows(ApiProblem.class, () -> h.approve(ALICE, "pay", "1.0.0", null));
        assertThrows(ApiProblem.class, () -> h.approve(BOB, "pay", "1.0.0", null));
        assertEquals(before, audited.size());
    }

    /** D4: the event and the entry change commit together; a failed audit write leaves the entry as it was. */
    @Test
    void aFailedAuditWriteUndoesTheSubmissionAndTheReview() {
        CatalogueHostController h = host(CatalogueHostController.PUBLISH, CatalogueHostController.REVIEW);
        auditFails = true;
        assertThrows(IllegalStateException.class, () -> h.submit(ALICE, submission("pay")));
        assertEquals(0, docs.all(DocumentStore.GLOBAL, CatalogueHostController.KIND, DocumentStore.Filter.ALL).size());
        auditFails = false;
        h.submit(ALICE, submission("pay"));
        auditFails = true;
        assertThrows(IllegalStateException.class, () -> h.approve(BOB, "pay", "1.0.0", null));
        assertThrows(IllegalStateException.class, () -> h.reject(BOB, "pay", "1.0.0", new CatalogueHostController.CatalogueReviewRequest("no")));
        auditFails = false;
        assertEquals("SUBMITTED", h.entries(BOB, null).getBody().get(0).state());
        assertEquals("{\"format\":1,\"entries\":[]}", new String(h.index().getBody(), StandardCharsets.UTF_8));
    }

    static final String TEMPLATES = "{\"templates\":[{\"id\":\"acme-erp.create-order\",\"name\":\"ERP: create order\","
            + "\"appliesTo\":[\"bpmn:ServiceTask\"],\"fields\":[{\"name\":\"type\",\"binding\":\"operaton:type\","
            + "\"required\":true,\"default\":\"external\"},{\"name\":\"topic\",\"binding\":\"operaton:topic\",\"required\":true}]}]}";

    @SuppressWarnings("unchecked")
    static CatalogueHostController.CatalogueSubmissionRequest templateSubmission(String id, String json) {
        return new CatalogueHostController.CatalogueSubmissionRequest(id, "1.0.0", "Apache-2.0", "0.1.0", null, "ERP templates",
                ExtensionCatalogue.KIND_CONNECTOR_TEMPLATE, org.sangam.integration.connector.Json.readMap(json));
    }

    /**
     * D5, decision 380 P2 (b): connector templates go through the same hosted catalogue (submit, four-eyes approval,
     * host-signed index) and are installed per tenant as element templates, audited in the same transaction.
     */
    @Test
    void connectorTemplatesAreHostedReviewedAndInstalledPerTenant() {
        CatalogueHostController h = host(CatalogueHostController.PUBLISH, CatalogueHostController.REVIEW);
        var created = h.submit(ALICE, templateSubmission("acme-erp", TEMPLATES)).getBody();
        assertEquals("connector-template", created.kind());
        h.approve(BOB, "acme-erp", "1.0.0", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> submitted = (Map<String, Object>) audited.get(0)[2];
        assertEquals("connector-template", submitted.get("kind"));

        CatalogueClient consumer = new CatalogueClient(new CatalogueSource.Hosted(URI.create(BASE + "index.json"), over(h)),
                List.of(hostKey.getPublic()), Set.of("Apache-2.0"), "0.1.0");
        assertEquals("connector-template", consumer.entries().get(0).kind());
        ExtensionCatalogueController install = new ExtensionCatalogueController(packages(),
                new ModellerControllerDiffTest.Grants(Permissions.PACKAGE_AUTHOR, Permissions.PACKAGE_DEPLOY), consumer, templates, audit());

        // a templates entry is never read as a package, and a code kind is never installed
        assertEquals("CATALOGUE_KIND_MISMATCH", assertThrows(ExtensionCatalogue.CatalogueException.class,
                () -> install.install(CAROL, "acme-erp", "1.0.0", null)).code());
        assertTrue(imports.isEmpty());

        audited.clear();
        var done = install.installTemplates(CAROL, "acme-erp", "1.0.0", new ExtensionCatalogueController.CatalogueInstallRequest("ERP rollout"));
        assertEquals(List.of(new ExtensionCatalogueController.CatalogueInstalledTemplate("acme-erp.create-order", 1)), done.published());
        assertEquals(1, templates.find("t2", "acme-erp.create-order", null).orElseThrow().version());
        assertTrue(templates.find("t1", "acme-erp.create-order", null).isEmpty(), "installed in the caller's tenant only");
        assertEquals(List.of("CATALOGUE_TEMPLATES_INSTALLED"), events());
        assertEquals("catalogue-install:acme-erp@1.0.0", audited.get(0)[0]);
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) audited.get(0)[2];
        assertEquals(List.of("acme-erp.create-order@1"), p.get("templates"));
        assertEquals("t1/alice", p.get("publisher"));
        assertEquals("ERP rollout", p.get("reason"));

        // a repeated install writes nothing
        var again = install.installTemplates(CAROL, "acme-erp", "1.0.0", null);
        assertTrue(again.published().isEmpty());
        assertEquals(List.of("acme-erp.create-order"), again.unchanged());
        assertEquals(1, audited.size());

        // the install needs the template author permission
        ExtensionCatalogueController deployerOnly = new ExtensionCatalogueController(packages(),
                new ModellerControllerDiffTest.Grants(Permissions.PACKAGE_DEPLOY), consumer, templates, audit());
        assertThrows(ApiProblem.class, () -> deployerOnly.installTemplates(CAROL, "acme-erp", "1.0.0", null));
        // without the template store the route answers 501, it never installs half
        ExtensionCatalogueController bare = new ExtensionCatalogueController(packages(),
                new ModellerControllerDiffTest.Grants(Permissions.PACKAGE_AUTHOR), consumer);
        assertEquals(501, assertThrows(ApiProblem.class, () -> bare.installTemplates(CAROL, "acme-erp", "1.0.0", null)).status());
    }

    /** D5: a failed install audit leaves no template behind in the tenant. */
    @Test
    void aFailedInstallAuditLeavesNoTemplate() {
        CatalogueHostController h = host(CatalogueHostController.PUBLISH, CatalogueHostController.REVIEW);
        h.submit(ALICE, templateSubmission("acme-erp", TEMPLATES));
        h.approve(BOB, "acme-erp", "1.0.0", null);
        CatalogueClient consumer = new CatalogueClient(new CatalogueSource.Hosted(URI.create(BASE + "index.json"), over(h)),
                List.of(hostKey.getPublic()), Set.of("Apache-2.0"), "0.1.0");
        ExtensionCatalogueController install = new ExtensionCatalogueController(packages(),
                new ModellerControllerDiffTest.Grants(Permissions.PACKAGE_AUTHOR), consumer, templates, audit());
        auditFails = true;
        assertThrows(IllegalStateException.class, () -> install.installTemplates(CAROL, "acme-erp", "1.0.0", null));
        assertTrue(templates.find("t2", "acme-erp.create-order", null).isEmpty());
    }

    /** D5: the host refuses a templates entry that is not data-only templates of its own namespace, and mixed artefacts. */
    @Test
    void theHostRefusesInvalidTemplateEntriesAndStoresNothing() {
        CatalogueHostController h = host(CatalogueHostController.PUBLISH);
        assertEquals("CATALOGUE_ARTIFACT_INVALID", assertThrows(ExtensionCatalogue.CatalogueException.class,
                () -> h.submit(ALICE, templateSubmission("acme-erp", TEMPLATES.replace("acme-erp.create-order", "sangam.connector.aws-lambda")))).code());
        assertEquals("CATALOGUE_TEMPLATES_REQUIRED", assertThrows(ApiProblem.class, () -> h.submit(ALICE,
                new CatalogueHostController.CatalogueSubmissionRequest("acme-erp", "1.0.0", "Apache-2.0", "0.1.0", bundle(), null,
                        ExtensionCatalogue.KIND_CONNECTOR_TEMPLATE, null))).code());
        assertEquals("CATALOGUE_BUNDLE_REQUIRED", assertThrows(ApiProblem.class, () -> h.submit(ALICE,
                new CatalogueHostController.CatalogueSubmissionRequest("pay", "1.0.0", "Apache-2.0", "0.1.0", bundle(), null,
                        "package", org.sangam.integration.connector.Json.readMap(TEMPLATES)))).code());
        assertEquals("CATALOGUE_KIND_NOT_INSTALLABLE", assertThrows(ApiProblem.class, () -> h.submit(ALICE,
                new CatalogueHostController.CatalogueSubmissionRequest("conn", "1.0.0", "Apache-2.0", "0.1.0", bundle(), null,
                        "connector", null))).code());
        assertEquals(0, docs.all(DocumentStore.GLOBAL, CatalogueHostController.KIND, DocumentStore.Filter.ALL).size());
        assertTrue(audited.isEmpty());
    }
}
