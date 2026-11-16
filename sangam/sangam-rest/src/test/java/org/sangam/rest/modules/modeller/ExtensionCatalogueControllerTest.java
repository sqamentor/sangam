// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.modeller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sangam.core.layer.Hashes;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.modeller.catalogue.CatalogueClient;
import org.sangam.modeller.catalogue.ExtensionCatalogue;
import org.sangam.rest.port.ApiProblem;
import org.sangam.rest.port.PackagePort;
import org.sangam.rest.support.Permissions;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M-128 / NG-17, owner decision 380: the catalogue routes. Install of kind package runs the catalogue checks and then
 * {@link PackagePort#importBundle} in the caller's tenant; a code kind is 422 CATALOGUE_KIND_NOT_INSTALLABLE with no
 * import; trusted keys are secret references (the literal key is refused at start-up); a hosted catalogue without an
 * allow-list entry fetches nothing.
 */
class ExtensionCatalogueControllerTest {

    static final String BUNDLE = "{\"format\":1,\"packageHash\":\"ab12\",\"manifest\":{\"key\":\"pay\"},\"artifacts\":{\"pay.bpmn\":\"<x/>\"},"
            + "\"signatures\":[],\"sourceEnvironment\":\"dev\",\"exportedAt\":\"2026-11-01T00:00:00Z\"}";
    static final String JAR = "PK-code";

    final KeyPair kp;
    final List<PackagePort.PackageImportRequest> imports = new ArrayList<>();
    final PackagePort packages;

    ExtensionCatalogueControllerTest() throws Exception {
        kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        packages = (PackagePort) Proxy.newProxyInstance(PackagePort.class.getClassLoader(), new Class<?>[] {PackagePort.class},
                (proxy, m, args) -> {
                    if (m.getName().equals("importBundle")) {
                        imports.add((PackagePort.PackageImportRequest) args[1]);
                        return new PackagePort.Package("pkg-1", "pay", "pay", 1, "APPROVED", "ab12", Map.of(), "alice", null, null,
                                null, null, null, null, Instant.EPOCH, Instant.EPOCH, List.of(), Map.of());
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
    }

    static String entry(String id, String kind, String licence, String artefact, String path) {
        return "{\"id\":\"" + id + "\",\"version\":\"1.0.0\",\"kind\":\"" + kind + "\",\"publisher\":\"acme\",\"licence\":\"" + licence
                + "\",\"sha256\":\"" + Hashes.hex(Hashes.sha256(artefact.getBytes(StandardCharsets.UTF_8)))
                + "\",\"minPlatform\":\"0.1.0\",\"artifact\":\"" + path + "\"}";
    }

    /** A mirrored catalogue with a package entry, a connector (code) entry and a GPL package entry. */
    MockEnvironment mirror(Path dir) throws Exception {
        byte[] idx = ("{\"entries\":[" + entry("pay", "package", "Apache-2.0", BUNDLE, "packs/pay.json") + ","
                + entry("conn", "connector", "Apache-2.0", JAR, "code/conn.jar") + ","
                + entry("gpl", "package", "GPL-3.0", BUNDLE, "packs/pay.json") + "]}").getBytes(StandardCharsets.UTF_8);
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(kp.getPrivate());
        s.update(idx);
        Files.write(dir.resolve("index.json"), idx);
        Files.writeString(dir.resolve("index.json.sig"), Base64.getEncoder().encodeToString(s.sign()));
        Files.createDirectories(dir.resolve("packs"));
        Files.createDirectories(dir.resolve("code"));
        Files.writeString(dir.resolve("packs/pay.json"), BUNDLE);
        Files.writeString(dir.resolve("code/conn.jar"), JAR);
        return new MockEnvironment()
                .withProperty(ExtensionCatalogueController.P + "index", dir.resolve("index.json").toString())
                .withProperty(ExtensionCatalogueController.P + "signature", dir.resolve("index.json.sig").toString())
                .withProperty(ExtensionCatalogueController.P + "trusted-public-key-refs[0]", "secret://catalogue/platform")
                .withProperty(ExtensionCatalogueController.P + "allowed-licences", "Apache-2.0,MIT")
                .withProperty("sangam.version", "0.1.0");
    }

    SecretResolver secrets() {
        return SecretResolver.of(Map.of("secret://catalogue/platform", Base64.getEncoder().encodeToString(kp.getPublic().getEncoded())));
    }

    ExtensionCatalogueController controller(MockEnvironment env, String... granted) {
        CatalogueClient c = ExtensionCatalogueController.client(env, secrets());
        return new ExtensionCatalogueController(packages, new ModellerControllerDiffTest.Grants(granted), c);
    }

    @Test
    void installOfAPackageEntryImportsTheBundleIntoTheCallersTenant(@TempDir Path dir) throws Exception {
        ExtensionCatalogueController c = controller(mirror(dir), Permissions.PACKAGE_READ, Permissions.PACKAGE_DEPLOY);
        assertEquals(3, c.catalogue(ModellerControllerDiffTest.CTX).getBody().size());
        ResponseEntity<PackagePort.Package> r = c.install(ModellerControllerDiffTest.CTX, "pay", "1.0.0",
                new ExtensionCatalogueController.CatalogueInstallRequest("payments pack"));
        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        assertEquals("/api/v1/packages/pkg-1", r.getHeaders().getLocation().toString());
        assertEquals(1, imports.size());
        assertEquals("ab12", imports.get(0).bundle().packageHash());
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), imports.get(0).bundle().exportedAt());
        assertEquals("catalogue pay@1.0.0 (acme): payments pack", imports.get(0).reason());
    }

    /** P2 (a): a code kind is 422 CATALOGUE_KIND_NOT_INSTALLABLE and nothing is imported. */
    @Test
    void codeKindIsRefusedWith422AndNothingIsImported(@TempDir Path dir) throws Exception {
        ExtensionCatalogueController c = controller(mirror(dir), Permissions.PACKAGE_DEPLOY);
        ExtensionCatalogue.CatalogueException e = assertThrows(ExtensionCatalogue.CatalogueException.class,
                () -> c.install(ModellerControllerDiffTest.CTX, "conn", "1.0.0", null));
        assertEquals("CATALOGUE_KIND_NOT_INSTALLABLE", e.code());
        ApiProblem p = ExtensionCatalogueController.problem(e);
        assertEquals(422, p.status());
        assertEquals("CATALOGUE_KIND_NOT_INSTALLABLE", p.code());
        assertTrue(imports.isEmpty());
    }

    @Test
    void licenceOutsideTheAllowListIsRefusedAndUnknownEntryIs404(@TempDir Path dir) throws Exception {
        ExtensionCatalogueController c = controller(mirror(dir), Permissions.PACKAGE_DEPLOY);
        ExtensionCatalogue.CatalogueException e = assertThrows(ExtensionCatalogue.CatalogueException.class,
                () -> c.install(ModellerControllerDiffTest.CTX, "gpl", "1.0.0", null));
        assertEquals("CATALOGUE_CHECK_FAILED", e.code());
        assertEquals(422, ExtensionCatalogueController.problem(e).status());
        ExtensionCatalogue.CatalogueException nf = assertThrows(ExtensionCatalogue.CatalogueException.class,
                () -> c.install(ModellerControllerDiffTest.CTX, "none", "1.0.0", null));
        assertEquals(404, ExtensionCatalogueController.problem(nf).status());
        assertTrue(imports.isEmpty());
    }

    @Test
    void installNeedsPackageDeploy(@TempDir Path dir) throws Exception {
        ExtensionCatalogueController c = controller(mirror(dir), Permissions.PACKAGE_READ);
        assertThrows(ApiProblem.class, () -> c.install(ModellerControllerDiffTest.CTX, "pay", "1.0.0", null));
        assertTrue(imports.isEmpty());
    }

    /** P3: the literal base64 key is no longer read; start-up refuses it, and an unresolved reference. */
    @Test
    void literalKeyAndUnresolvedReferenceAreRefusedAtStartUp(@TempDir Path dir) throws Exception {
        MockEnvironment literal = mirror(dir).withProperty(ExtensionCatalogueController.P + "public-key",
                Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()));
        assertThrows(IllegalStateException.class, () -> ExtensionCatalogueController.client(literal, secrets()));
        MockEnvironment unresolved = mirror(dir);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ExtensionCatalogueController.client(unresolved, SecretResolver.of(Map.of())));
        assertTrue(e.getMessage().contains("CATALOGUE_KEY_NOT_FOUND"), e.getMessage());
        MockEnvironment both = mirror(dir).withProperty(ExtensionCatalogueController.P + "url", "https://cat.example.org/index.json");
        assertThrows(IllegalStateException.class, () -> ExtensionCatalogueController.client(both, secrets()));
        assertNull(ExtensionCatalogueController.client(new MockEnvironment(), secrets()));
        ApiProblem p = assertThrows(ApiProblem.class, () -> new ExtensionCatalogueController(packages,
                new ModellerControllerDiffTest.Grants(Permissions.PACKAGE_READ), null).catalogue(ModellerControllerDiffTest.CTX));
        assertEquals(404, p.status());
    }

    /** P1: a hosted catalogue without an allow-list entry sends nothing: 422 CATALOGUE_EGRESS_DENIED. */
    @Test
    void hostedCatalogueWithoutAllowListEntryIsEgressDenied() {
        MockEnvironment env = new MockEnvironment()
                .withProperty(ExtensionCatalogueController.P + "url", "https://203.0.113.10/v1/index.json")
                .withProperty(ExtensionCatalogueController.P + "trusted-public-key-refs", "secret://catalogue/platform")
                .withProperty(ExtensionCatalogueController.P + "allowed-licences", "Apache-2.0");
        ExtensionCatalogueController c = controller(env, Permissions.PACKAGE_READ);
        ExtensionCatalogue.CatalogueException e = assertThrows(ExtensionCatalogue.CatalogueException.class,
                () -> c.catalogue(ModellerControllerDiffTest.CTX));
        assertEquals("CATALOGUE_EGRESS_DENIED", e.code());
        assertEquals(422, ExtensionCatalogueController.problem(e).status());
        MockEnvironment http = new MockEnvironment()
                .withProperty(ExtensionCatalogueController.P + "url", "http://cat.example.org/index.json")
                .withProperty(ExtensionCatalogueController.P + "trusted-public-key-refs", "secret://catalogue/platform");
        assertThrows(IllegalStateException.class, () -> ExtensionCatalogueController.client(http, secrets()));
    }
}
