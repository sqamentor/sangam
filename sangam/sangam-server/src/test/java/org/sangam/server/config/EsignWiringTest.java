// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.integration.connector.ConnectorRegistry;
import org.sangam.core.integration.SangamConnector;
import org.sangam.integration.connector.gov.EsignConnector;
import org.sangam.integration.connector.gov.EsignRequestSigner;
import org.sangam.integration.sim.SimulatorProfile;
import org.sangam.integration.signature.EsignResponseVerifier;
import org.sangam.integration.signature.SignaturePolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

/**
 * DEC-140 server wiring: the live eSign connector is off by default; enabled, it is registered with the decision 140
 * verifier the loader returned; enabled without sangam-esign, trust anchors or a required setting, start-up fails
 * with {@code ESIGN_MODULE_MISSING} or {@code ESIGN_CONFIG_INVALID} and no connector exists.
 */
class EsignWiringTest {

    static final String TENANT = "t1";

    @TempDir
    static Path dir;
    static Path keyStore;
    static Path passwordFile;
    static Path pem;
    static X509Certificate cert;

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(EsignWiring.class);

    /** The ASP key from the running JDK's keytool; its certificate also stands in for the CCA root and the ESP. */
    @BeforeAll
    static void material() throws Exception {
        Path keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "keytool.exe" : "keytool");
        assertTrue(Files.isExecutable(keytool), "keytool of the running JDK: " + keytool);
        keyStore = dir.resolve("asp.p12");
        Process p = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "asp", "-keyalg", "RSA", "-keysize",
                "2048", "-dname", "CN=Test ASP", "-validity", "2", "-storetype", "PKCS12", "-keystore",
                keyStore.toString(), "-storepass", "changeit", "-keypass", "changeit").redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "keytool finished");
        assertEquals(0, p.exitValue(), "keytool exit code");
        passwordFile = dir.resolve("asp.p12.pass");
        Files.writeString(passwordFile, "changeit\n", StandardCharsets.UTF_8);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keyStore)) {
            ks.load(in, "changeit".toCharArray());
        }
        cert = (X509Certificate) ks.getCertificate("asp");
        pem = dir.resolve("root.pem");
        Files.writeString(pem, "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(cert.getEncoded())
                + "\n-----END CERTIFICATE-----\n", StandardCharsets.US_ASCII);
    }

    /** Every setting an enabled live eSign connector needs. */
    static String[] complete() {
        return new String[] {"sangam.integration.esign.enabled=true",
                "sangam.integration.esign.base-uri=https://esp.example/esign/2.1",
                "sangam.integration.esign.asp-id=ASP-1",
                "sangam.integration.esign.response-sig-type=PKCS7",
                "sangam.integration.esign.policy-version=esign-test-1",
                "sangam.integration.esign.trust-anchors=" + pem,
                "sangam.integration.esign.response-signer-certificates=" + pem,
                "sangam.integration.esign.response-time-zone=Asia/Kolkata",
                "sangam.integration.esign.revocation-egress=http://ocsp.ca.example",
                "sangam.integration.esign.request-signature.key-store=" + keyStore,
                "sangam.integration.esign.request-signature.key-store-password-file=" + passwordFile,
                "sangam.integration.esign.request-signature.canonicalization=c14n",
                "sangam.integration.esign.request-signature.signature-method=rsa-sha256",
                "sangam.integration.esign.request-signature.digest-method=sha256",
                "sangam.integration.esign.request-signature.include-certificate=true"};
    }

    static MockEnvironment env(String... properties) {
        MockEnvironment env = new MockEnvironment().withProperty("sangam.tenant.default", TENANT);
        for (String p : properties) {
            int eq = p.indexOf('=');
            env.setProperty(p.substring(0, eq), p.substring(eq + 1));
        }
        return env;
    }

    static MockEnvironment without(String key) {
        MockEnvironment out = new MockEnvironment().withProperty("sangam.tenant.default", TENANT);
        for (String p : complete()) {
            if (!p.startsWith("sangam.integration.esign." + key + "=")) {
                int eq = p.indexOf('=');
                out.setProperty(p.substring(0, eq), p.substring(eq + 1));
            }
        }
        return out;
    }

    static <T> ObjectProvider<T> provider(Class<T> type, T beanOrNull) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (beanOrNull != null) {
            beans.addBean("bean", beanOrNull);
        }
        return beans.getBeanProvider(type);
    }

    static Throwable root(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    /** A stand-in for the sangam-esign verifier; records the policy it was loaded with. */
    static final class StubLoader implements EsignWiring.VerifierLoader {
        final EsignResponseVerifier verifier = (expected, xml) -> {
            throw new AssertionError("not called in the wiring test");
        };
        final AtomicReference<SignaturePolicy> policy = new AtomicReference<>();

        @Override
        public Optional<EsignResponseVerifier> load(SignaturePolicy p, org.sangam.integration.connector.HttpTransport r,
                                                    java.time.Clock clock) {
            assertNotNull(r, "a revocation transport");
            policy.set(p);
            return Optional.of(verifier);
        }
    }

    @Test
    void offByDefaultNothingIsBuiltOrRegistered() {
        runner.run(ctx -> {
            assertFalse(ctx.containsBean("sangamLiveEsignConnector"));
            assertTrue(ctx.getBeansOfType(EsignConnector.class).isEmpty());
        });
        runner.withPropertyValues("sangam.integration.esign.enabled=false").run(ctx ->
                assertTrue(ctx.getBeansOfType(EsignConnector.class).isEmpty()));
        // complete settings but not enabled: still nothing
        String[] notEnabled = complete();
        notEnabled[0] = "sangam.integration.esign.base-uri=https://esp.example/esign/2.1";
        runner.withPropertyValues(notEnabled).run(ctx -> {
            assertFalse(ctx.containsBean("sangamLiveEsignConnector"));
            assertTrue(ctx.getStartupFailure() == null);
        });
        ConnectorRegistry registry = new IntegrationWiring().sangamConnectorRegistry(provider(SimulatorProfile.class,
                null), env(), null, provider(EsignConnector.class, null));
        assertTrue(registry.descriptors().isEmpty(), "no simulators, no live eSign: " + registry.descriptors());
        assertTrue(registry.find(TENANT, "esign").isEmpty());
    }

    @Test
    void enabledWithAVerifierTheRegistryHoldsTheLiveConnectorWithTheLoadedVerifier() throws Exception {
        StubLoader loader = new StubLoader();
        EsignConnector connector = EsignWiring.connector(env(complete()), loader);
        EsignConnector.Config c = connector.config();
        assertSame(loader.verifier, c.verifier(), "the verifier is the loaded one");
        assertEquals(ConnectorProfile.LIVE, c.profile());
        assertEquals("PKCS7", c.responseSigType());
        assertEquals("ASP-1", c.aspId());
        assertEquals("esign", connector.descriptor().id());
        SignaturePolicy policy = loader.policy.get();
        assertEquals("esign-test-1", policy.version());
        assertEquals(1, policy.trustAnchors().size());
        assertEquals(cert, policy.responseSignerCertificates().get(0));
        assertEquals(java.time.ZoneId.of("Asia/Kolkata"), policy.responseTimeZone());

        // the request signer is the configured ASP key under the configured profile, and its output verifies
        EsignRequestSigner signer = (EsignRequestSigner) c.requestSigner();
        assertEquals(cert, signer.certificate());
        assertTrue(signer.profile().includeCertificate());
        String signed = signer.apply("<Esign ver=\"2.1\" aspId=\"ASP-1\" txn=\"t\"><Docs/></Esign>");
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        Document doc = f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(signed.getBytes(StandardCharsets.UTF_8)));
        NodeList sigs = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
        XMLSignature sig = XMLSignatureFactory.getInstance("DOM")
                .unmarshalXMLSignature(new DOMValidateContext(cert.getPublicKey(), sigs.item(0)));
        assertTrue(sig.validate(new DOMValidateContext(cert.getPublicKey(), sigs.item(0))));

        ConnectorRegistry registry = new IntegrationWiring().sangamConnectorRegistry(provider(SimulatorProfile.class,
                null), env(), null, provider(EsignConnector.class, connector));
        SangamConnector found = registry.find(TENANT, "esign").orElseThrow();
        assertSame(connector, found);
        assertSame(loader.verifier, ((EsignConnector) found).config().verifier());
    }

    @Test
    void enabledWithoutSangamEsignStartUpFailsWithTheNamedError() {
        assertFalse(ServiceLoader.load(EsignResponseVerifier.Factory.class).iterator().hasNext(),
                "sangam-esign is not on the server test class path (decision 217), so the real loader finds none");
        runner.withPropertyValues(complete()).run(ctx -> {
            Throwable failure = ctx.getStartupFailure();
            assertNotNull(failure, "start-up must fail");
            assertTrue(root(failure).getMessage().startsWith(EsignWiring.MODULE_MISSING + ": "),
                    root(failure).getMessage());
            assertTrue(root(failure).getMessage().contains("sangam-esign"));
        });
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> EsignWiring.connector(env(complete()), (p, r, clock) -> Optional.empty()));
        assertTrue(e.getMessage().startsWith("ESIGN_MODULE_MISSING: "), e.getMessage());
    }

    @Test
    void enabledWithoutTrustAnchorsOrARequiredSettingStartUpFailsWithTheNamedError() {
        for (String key : new String[] {"trust-anchors", "response-signer-certificates", "policy-version", "asp-id",
                "base-uri", "request-signature.key-store", "request-signature.key-store-password-file",
                "request-signature.canonicalization", "request-signature.signature-method",
                "request-signature.digest-method", "request-signature.include-certificate"}) {
            StubLoader loader = new StubLoader();
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> EsignWiring.connector(without(key), loader), key);
            assertTrue(e.getMessage().startsWith("ESIGN_CONFIG_INVALID: "), key + ": " + e.getMessage());
        }
        // trust anchors missing in a real start-up: refused before any verifier is looked for
        String[] noAnchors = java.util.Arrays.stream(complete())
                .filter(p -> !p.startsWith("sangam.integration.esign.trust-anchors=")).toArray(String[]::new);
        runner.withPropertyValues(noAnchors).run(ctx -> {
            assertNotNull(ctx.getStartupFailure());
            String m = root(ctx.getStartupFailure()).getMessage();
            assertTrue(m.startsWith("ESIGN_CONFIG_INVALID: sangam.integration.esign.trust-anchors"), m);
        });
    }

    @Test
    void unusableValuesAreRefusedNotGuessed() {
        String[][] cases = {
                {"base-uri", "http://esp.example/esign"},
                {"base-uri", "not a uri"},
                {"trust-anchors", dir.resolve("missing.pem").toString()},
                {"response-sig-type", "XML"},
                {"response-time-zone", "Mars/Olympus"},
                {"request-signature.signature-method", "http://www.w3.org/2000/09/xmldsig#rsa-sha1"},
                {"request-signature.digest-method", "sha1"},
                {"request-signature.include-certificate", "yes"},
                {"request-signature.key-alias", "other"},
                {"request-signature.key-store-password-file", dir.resolve("missing.pass").toString()}};
        for (String[] c : cases) {
            MockEnvironment env = env(complete());
            env.setProperty("sangam.integration.esign." + c[0], c[1]);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> EsignWiring.connector(env, new StubLoader()), c[0] + "=" + c[1]);
            assertTrue(e.getMessage().startsWith("ESIGN_CONFIG_INVALID: "), c[0] + ": " + e.getMessage());
        }
        MockEnvironment wrongPassword = env(complete());
        Path bad = dir.resolve("bad.pass");
        try {
            Files.writeString(bad, "wrong\n", StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            throw new AssertionError(ex);
        }
        wrongPassword.setProperty("sangam.integration.esign.request-signature.key-store-password-file", bad.toString());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> EsignWiring.connector(wrongPassword, new StubLoader()));
        assertTrue(e.getMessage().startsWith("ESIGN_CONFIG_INVALID: sangam.integration.esign.request-signature.key-store "),
                e.getMessage());
        assertFalse(e.getMessage().contains("wrong"), "the password is never echoed");
    }
}
