// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.restadapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.layer.AuditCheckpoints;
import org.sangam.core.layer.Jdbc;
import org.sangam.persistence.SangamSchema;
import org.sangam.rest.adapter.jdbc.JdbcAuditAdapter;
import org.sangam.rest.adapter.jdbc.JdbcPackageAdapter;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.DeploymentPort;
import org.sangam.rest.port.PackagePort;
import org.sangam.rest.port.PackagePort.EnvBinding;
import org.sangam.rest.port.PackagePort.PackageBundle;
import org.sangam.rest.port.PackagePort.PackageSignature;
import org.sangam.rest.port.PackagePort.PackageVerification;
import org.sangam.rest.port.Page;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.PackageSigning;
import org.sangam.rest.support.RestProperties;
import org.sangam.rest.support.TxSupport;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * M-074 (R43, X.2.4.5, T70, T50) on the real changelog (H2): two environments, each with its own package signing key
 * (Q-04). The source signs at submit (author) and approve (approver) and verifies on deploy; the bundle moves the same
 * bytes; the target verifies hash, artifacts and signatures, refuses a changed byte, a missing signature, an untrusted
 * key and a bad signature, imports once, and blocks activation until every ENV_BINDING the manifest names is approved
 * (four-eyes; in production the binding approver is not a deployer of a package that uses it).
 */
class PackagePromotionH2Test {

    static final String T = "dept-x";
    static final String MANIFEST = "{\"envBindings\":[\"treasury-endpoint\"],\"profile\":{\"bpmn\":\"sangam-bpmn-1\"}}";

    static RequestContext ctx(String who, String seat) {
        return new RequestContext(T, who, Set.of(), seat, null, null, null, null, "OIDC");
    }

    static final RequestContext ALICE = ctx("alice", "seat-a");   // author
    static final RequestContext BOB = ctx("bob", "seat-b");       // approver
    static final RequestContext CAROL = ctx("carol", "seat-c");   // deployer
    static final RequestContext ERIN = ctx("erin", "seat-e");     // binding approver

    record Env(JdbcPackageAdapter packages, TxSupport tx) {
    }

    static final PermissionPort ALL = new PermissionPort() {
        @Override
        public boolean has(RequestContext ctx, String permission) {
            return true;
        }

        @Override
        public Set<String> permissions(RequestContext ctx) {
            return Set.of();
        }

        @Override
        public boolean mayReplay(RequestContext ctx, String commandType, String resourceRef) {
            return true;
        }
    };

    static final DeploymentPort DEPLOYMENTS = new DeploymentPort() {
        @Override
        public Deployment deploy(RequestContext ctx, DeployRequest request) {
            return new Deployment("dep-" + UUID.randomUUID(), request.name(), request.source(), ctx.tenantId(),
                    Instant.now(), new ArrayList<>(request.resources().keySet()), List.of());
        }

        @Override
        public Page<Deployment> list(RequestContext ctx, String name, Page.PageRequest page) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Deployment get(RequestContext ctx, String deploymentId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] resource(RequestContext ctx, String deploymentId, String resourceName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(RequestContext ctx, String deploymentId, boolean cascade, String reason) {
            throw new UnsupportedOperationException();
        }
    };

    KeyPair sourceKey;
    KeyPair targetKey;

    @BeforeEach
    void keys() throws Exception {
        sourceKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        targetKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    /** A fresh database on the product changelog, with a package adapter for one environment. */
    static Env env(String label, KeyPair own, List<KeyPair> trusts, boolean allowUnsigned, boolean production) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:m074" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        SangamSchema.apply(ds);
        TxSupport tx = new TxSupport(ds, new DataSourceTransactionManager(ds), TxSupport.detectDialect(ds));
        List<String> trusted = new ArrayList<>();
        AuditCheckpoints.Signer signer = null;
        if (own != null) {
            signer = AuditCheckpoints.ed25519(b64(own.getPrivate().getEncoded()), b64(own.getPublic().getEncoded()));
            trusted.add(b64(own.getPublic().getEncoded()));
        }
        trusts.forEach(k -> trusted.add(b64(k.getPublic().getEncoded())));
        PackageSigning signing = new PackageSigning(signer, AuditCheckpoints.ed25519Keys(trusted), allowUnsigned,
                production, label);
        RestProperties props = new RestProperties();
        JdbcAuditAdapter audit = new JdbcAuditAdapter(tx, null, null, props);
        return new Env(new JdbcPackageAdapter(tx, DEPLOYMENTS, ALL, audit, props, signing), tx);
    }

    static PackagePort.Package approved(Env env, String key, String manifest) {
        Map<String, String> resources = new LinkedHashMap<>();
        resources.put("bill.bpmn", "<definitions id=\"bill\"/>");
        resources.put("logo.png", b64(new byte[]{1, 2, 3}));
        if (manifest != null) {
            resources.put("sangam-package.json", manifest);
        }
        PackagePort.Package p = env.packages().create(ALICE, new PackagePort.CreatePackageRequest(key, "Bill", resources));
        env.packages().transition(ALICE, p.id(), new PackagePort.TransitionRequest("submit", null, null, null));
        return env.packages().transition(BOB, p.id(), new PackagePort.TransitionRequest("approve", "ok", null, null));
    }

    static String code(Runnable r) {
        return assertThrows(ApiProblem.class, r::run).code();
    }

    @Test
    void submitAndApproveSignTheFrozenHashAndDeployVerifiesIt() {
        Env dev = env("dev", sourceKey, List.of(), false, false);
        PackagePort.Package p = approved(dev, "p1-bill", MANIFEST);
        List<PackageSignature> sigs = dev.packages().signatures(BOB, p.id());
        assertEquals(List.of("approver", "author"), sigs.stream().map(PackageSignature::role).toList());
        assertTrue(sigs.stream().allMatch(s -> p.packageHash().equals(s.packageHash()) && "Ed25519".equals(s.alg())));
        assertEquals("alice", sigs.get(1).principalId());
        assertEquals("seat-b", sigs.get(0).seatId());

        PackagePort.Package deployed = dev.packages().transition(CAROL, p.id(),
                new PackagePort.TransitionRequest("deploy", null, null, null));
        assertEquals("DEPLOYED", deployed.state());
        assertEquals("dev", deployed.environment(), "the deploy environment defaults to this environment's label");
    }

    @Test
    void anUnsignedPackageIsRefusedOutsideDevelopmentAndAllowedInDevelopment() {
        Env strict = env("uat", null, List.of(), false, false);
        PackagePort.Package p = approved(strict, "p1-bill", null);
        assertEquals(List.of(), strict.packages().signatures(BOB, p.id()), "no key: nothing signed");
        assertEquals("PACKAGE_UNSIGNED", code(() -> strict.packages().transition(CAROL, p.id(),
                new PackagePort.TransitionRequest("deploy", null, null, null))));

        Env dev = env("dev", null, List.of(), true, false);
        PackagePort.Package d = approved(dev, "p1-bill", null);
        assertEquals("DEPLOYED", dev.packages().transition(CAROL, d.id(),
                new PackagePort.TransitionRequest("deploy", null, null, null)).state());
        assertThrows(IllegalStateException.class, () -> new PackageSigning(null, null, true, true, "prod"),
                "unsigned packages are never allowed in production");
    }

    @Test
    void theBundleVerifiesAndImportsAsTheSameBytesOnce() {
        Env dev = env("dev", sourceKey, List.of(), false, false);
        Env prod = env("prod", targetKey, List.of(sourceKey), false, true);
        PackagePort.Package p = approved(dev, "p1-bill", MANIFEST);
        PackageBundle bundle = dev.packages().export(CAROL, p.id());
        assertEquals(1, bundle.format());
        assertEquals("dev", bundle.sourceEnvironment());
        assertEquals(p.packageHash(), bundle.packageHash());
        assertEquals(2, bundle.signatures().size());

        PackageVerification v = prod.packages().verify(CAROL, bundle);
        assertTrue(v.ok(), v.toString());
        assertTrue(v.hashMatches() && v.signed());
        assertEquals(p.packageHash(), v.recomputedHash());
        assertEquals(List.of("treasury-endpoint"), v.envBindingsRequired());
        assertEquals(List.of("treasury-endpoint"), v.envBindingsMissing(), "verification changes nothing");

        PackagePort.Package imported = prod.packages().importBundle(CAROL, new PackagePort.PackageImportRequest(bundle,
                "release 2.3"));
        assertEquals("APPROVED", imported.state());
        assertEquals(p.packageHash(), imported.packageHash(), "same bytes, same hash: nothing rebuilt");
        assertEquals("alice", imported.author(), "the author from the verified author signature");
        assertEquals("bob", imported.approver());
        assertEquals(p.resources(), imported.resources());
        assertEquals(2, prod.packages().signatures(CAROL, imported.id()).size(), "the source's signatures are kept");
        assertEquals("PACKAGE_EXISTS", code(() -> prod.packages().importBundle(CAROL,
                new PackagePort.PackageImportRequest(bundle, null))));
    }

    @Test
    void theTargetRejectsAChangedByteAMissingSignatureAnUntrustedKeyAndABadSignature() {
        Env dev = env("dev", sourceKey, List.of(), false, false);
        PackagePort.Package p = approved(dev, "p1-bill", MANIFEST);
        PackageBundle bundle = dev.packages().export(CAROL, p.id());
        Env prod = env("prod", targetKey, List.of(sourceKey), false, true);

        Map<String, String> changed = new LinkedHashMap<>(bundle.artifacts());
        changed.put("bill.bpmn", "<definitions id=\"bill\" />");
        PackageBundle tampered = new PackageBundle(1, bundle.packageHash(), bundle.manifest(), changed,
                bundle.signatures(), "dev", bundle.exportedAt());
        PackageVerification v = prod.packages().verify(CAROL, tampered);
        assertFalse(v.hashMatches());
        assertFalse(v.ok());
        assertEquals(List.of("bill.bpmn"), v.artifactMismatches());
        assertEquals("PACKAGE_HASH_MISMATCH", code(() -> prod.packages().importBundle(CAROL,
                new PackagePort.PackageImportRequest(tampered, null))));

        PackageBundle unsigned = new PackageBundle(1, bundle.packageHash(), bundle.manifest(), bundle.artifacts(),
                List.of(), "dev", bundle.exportedAt());
        assertFalse(prod.packages().verify(CAROL, unsigned).signed());
        assertEquals("PACKAGE_UNSIGNED", code(() -> prod.packages().importBundle(CAROL,
                new PackagePort.PackageImportRequest(unsigned, null))));

        Env untrusting = env("prod", targetKey, List.of(), false, true);
        assertEquals("PACKAGE_KEY_UNKNOWN", code(() -> untrusting.packages().importBundle(CAROL,
                new PackagePort.PackageImportRequest(bundle, null))));

        List<PackageSignature> bad = new ArrayList<>();
        for (PackageSignature s : bundle.signatures()) {
            byte[] sig = Base64.getDecoder().decode(s.signature());
            sig[5] ^= 1;
            bad.add(new PackageSignature(s.packageHash(), s.role(), s.principalId(), s.seatId(), s.alg(), s.keyId(),
                    b64(sig), s.signedAt()));
        }
        PackageBundle badlySigned = new PackageBundle(1, bundle.packageHash(), bundle.manifest(), bundle.artifacts(), bad,
                "dev", bundle.exportedAt());
        assertEquals("PACKAGE_SIGNATURE_INVALID", code(() -> prod.packages().importBundle(CAROL,
                new PackagePort.PackageImportRequest(badlySigned, null))));
        int imported = prod.tx().withConnection(c -> Jdbc.one(c, "SELECT COUNT(*) FROM PACKAGE_VERSION",
                rs -> rs.getInt(1)).orElseThrow());
        assertEquals(0, imported, "nothing was imported");
    }

    @Test
    void aMissingEnvBindingBlocksActivationUntilApprovedByAnotherPerson() {
        Env dev = env("dev", sourceKey, List.of(), false, false);
        Env prod = env("prod", targetKey, List.of(sourceKey), false, true);
        PackageBundle bundle = dev.packages().export(CAROL, approved(dev, "p1-bill", MANIFEST).id());
        PackagePort.Package imported = prod.packages().importBundle(CAROL, new PackagePort.PackageImportRequest(bundle, null));
        assertEquals("SEGREGATION_OF_DUTIES", code(() -> prod.packages().transition(ALICE, imported.id(),
                new PackagePort.TransitionRequest("deploy", null, null, null))), "the source's author cannot deploy");
        PackagePort.Package deployed = prod.packages().transition(CAROL, imported.id(),
                new PackagePort.TransitionRequest("deploy", null, "prod", null));
        ApiProblem missing = assertThrows(ApiProblem.class, () -> prod.packages().transition(CAROL, deployed.id(),
                new PackagePort.TransitionRequest("activate", null, null, null)));
        assertEquals("ENV_BINDING_MISSING", missing.code());
        assertEquals(409, missing.status());

        assertEquals("VALIDATION_FAILED", code(() -> prod.packages().setEnvBinding(BOB, "treasury-endpoint",
                new PackagePort.EnvBindingRequest("prod", "plain-secret-value", "x"))), "never a secret value");
        EnvBinding pending = prod.packages().setEnvBinding(BOB, "treasury-endpoint",
                new PackagePort.EnvBindingRequest("prod", "https://treasury.example/api", "go-live"));
        assertEquals(1, pending.version());
        assertEquals("PENDING", pending.state());
        assertEquals("ENV_BINDING_MISSING", code(() -> prod.packages().transition(CAROL, deployed.id(),
                new PackagePort.TransitionRequest("activate", null, null, null))), "a PENDING binding is not in force");

        assertEquals("SEGREGATION_OF_DUTIES", code(() -> prod.packages().approveEnvBinding(BOB, "treasury-endpoint",
                new PackagePort.EnvBindingApproval("prod", 1, "self"))), "four-eyes: not the person who set it");
        assertEquals("SEGREGATION_OF_DUTIES", code(() -> prod.packages().approveEnvBinding(CAROL, "treasury-endpoint",
                new PackagePort.EnvBindingApproval("prod", 1, "deployer"))),
                "production: not a deployer of a package that uses it");
        EnvBinding approvedBinding = prod.packages().approveEnvBinding(ERIN, "treasury-endpoint",
                new PackagePort.EnvBindingApproval("prod", 1, "checked"));
        assertEquals("APPROVED", approvedBinding.state());
        assertEquals("erin", approvedBinding.approvedBy());
        assertEquals("INVALID_TRANSITION", code(() -> prod.packages().approveEnvBinding(ERIN, "treasury-endpoint",
                new PackagePort.EnvBindingApproval("prod", 1, "again"))));

        assertEquals("ACTIVE", prod.packages().transition(CAROL, deployed.id(),
                new PackagePort.TransitionRequest("activate", null, null, null)).state());
    }

    @Test
    void aNewBindingVersionWaitsWhileTheApprovedOneStaysInForce() {
        Env prod = env("prod", targetKey, List.of(), false, false);
        prod.packages().setEnvBinding(BOB, "esign-asp", new PackagePort.EnvBindingRequest("prod", "secret://esign/v1", "first"));
        prod.packages().approveEnvBinding(ERIN, "esign-asp", new PackagePort.EnvBindingApproval("prod", 1, "ok"));
        prod.packages().setEnvBinding(BOB, "esign-asp", new PackagePort.EnvBindingRequest("prod", "secret://esign/v2", "rotate"));

        List<EnvBinding> latest = prod.packages().envBindings(BOB, "prod", null, null);
        assertEquals(1, latest.size(), "latest version per name and environment");
        assertEquals(2, latest.get(0).version());
        assertEquals("PENDING", latest.get(0).state());
        EnvBinding inForce = prod.packages().envBindings(BOB, "prod", "esign-asp", "APPROVED").get(0);
        assertEquals("secret://esign/v1", inForce.valueRef());

        prod.packages().approveEnvBinding(ERIN, "esign-asp", new PackagePort.EnvBindingApproval("prod", 2, "rotated"));
        assertEquals("secret://esign/v2", prod.packages().envBindings(BOB, "prod", "esign-asp", "APPROVED").get(0).valueRef());
        EnvBinding retired = prod.packages().envBindings(BOB, "prod", "esign-asp", "RETIRED").get(0);
        assertEquals(1, retired.version());
        assertEquals("first", retired.reason(), "the row keeps the reason it was set with");
        assertEquals("VALIDATION_FAILED", code(() -> prod.packages().envBindings(BOB, null, null, "ACTIVE")));
        assertNull(prod.packages().envBindings(BOB, "uat", null, null).stream().findFirst().orElse(null));
    }

    @Test
    void exportNeedsAnApprovedPackageAndAnEnvironmentLabel() {
        Env dev = env("dev", sourceKey, List.of(), false, false);
        PackagePort.Package draft = dev.packages().create(ALICE, new PackagePort.CreatePackageRequest("p1-bill", "Bill",
                Map.of("bill.bpmn", "<definitions/>")));
        assertEquals("INVALID_TRANSITION", code(() -> dev.packages().export(CAROL, draft.id())));
        Env unlabelled = env(null, sourceKey, List.of(), false, false);
        PackagePort.Package p = approved(unlabelled, "p1-bill", null);
        assertEquals("PACKAGE_ENVIRONMENT_NOT_SET", code(() -> unlabelled.packages().export(CAROL, p.id())));
    }

    /** Decision 83 (changeset 0043-10): drafts carry no hash, so a tenant holds any number of them. */
    @Test
    void oneTenantHoldsManyDraftsAndTheHashIsFrozenOnlyAtSubmit() {
        Env dev = env("dev", sourceKey, List.of(), false, false);
        Map<String, String> same = Map.of("bill.bpmn", "<definitions/>");
        PackagePort.Package d1 = dev.packages().create(ALICE, new PackagePort.CreatePackageRequest("p1-bill", "Bill", same));
        PackagePort.Package d2 = dev.packages().create(ALICE, new PackagePort.CreatePackageRequest("p2-pay", "Pay", same));
        PackagePort.Package d3 = dev.packages().create(BOB, new PackagePort.CreatePackageRequest("p3-fee", "Fee",
                Map.of("fee.bpmn", "<definitions id=\"fee\"/>")));
        assertEquals(3, dev.packages().list(ALICE, null, null, new Page.PageRequest(0, 20)).items().size());
        int nullHashes = dev.tx().withConnection(c -> Jdbc.one(c, "SELECT COUNT(*) FROM PACKAGE_VERSION WHERE tenant_id=?"
                + " AND package_hash IS NULL", rs -> rs.getInt(1), T).orElseThrow());
        assertEquals(3, nullHashes, "a draft stores NULL, never a placeholder");

        PackagePort.Package submitted = dev.packages().transition(ALICE, d1.id(),
                new PackagePort.TransitionRequest("submit", null, null, null));
        int frozen = dev.tx().withConnection(c -> Jdbc.one(c, "SELECT COUNT(*) FROM PACKAGE_VERSION WHERE tenant_id=?"
                + " AND package_id=? AND package_hash IS NOT NULL", rs -> rs.getInt(1), T, d1.id()).orElseThrow());
        assertEquals(1, frozen);
        assertEquals(64, submitted.packageHash().length());
        assertNull(dev.packages().get(ALICE, d2.id()).packageHash());
        assertNull(dev.packages().get(ALICE, d3.id()).packageHash());
    }

    /** Decision 84: the role is inside the signed bytes, so a bundle cannot pass the author's signature as approval. */
    @Test
    void aBundleWhoseSignatureRoleWasRelabelledIsRefused() {
        Env dev = env("dev", sourceKey, List.of(), false, false);
        PackageBundle bundle = dev.packages().export(CAROL, approved(dev, "p1-bill", MANIFEST).id());
        PackageSignature author = bundle.signatures().stream().filter(s -> "author".equals(s.role())).findFirst()
                .orElseThrow();
        PackageSignature asApprover = new PackageSignature(author.packageHash(), "approver", author.principalId(),
                author.seatId(), author.alg(), author.keyId(), author.signature(), author.signedAt());
        PackageBundle relabelled = new PackageBundle(1, bundle.packageHash(), bundle.manifest(), bundle.artifacts(),
                List.of(author, asApprover), "dev", bundle.exportedAt());
        Env prod = env("prod", targetKey, List.of(sourceKey), false, true);
        PackageVerification v = prod.packages().verify(CAROL, relabelled);
        assertFalse(v.ok());
        assertTrue(v.signatures().stream().anyMatch(c -> "approver".equals(c.role()) && "BAD_SIGNATURE".equals(c.reason())));
        assertEquals("PACKAGE_SIGNATURE_INVALID", code(() -> prod.packages().importBundle(CAROL,
                new PackagePort.PackageImportRequest(relabelled, null))));
        assertTrue(prod.packages().verify(CAROL, bundle).ok(), "the bundle as exported still verifies");
    }
}
