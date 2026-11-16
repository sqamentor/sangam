// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.gov;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * DEC-140: the ASP's enveloped request signature verifies with the ASP certificate's public key under the configured
 * profile, carries KeyInfo only when asked, breaks when the signed request is changed, and refuses SHA-1 and non-RSA keys.
 */
class EsignRequestSignerTest {

    static final String REQUEST = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Esign ver=\"2.1\" sc=\"Y\" ts=\"2026-11-16T10:00:00\""
            + " txn=\"op-1\" ekycIdType=\"A\" aspId=\"ASP-1\" AuthMode=\"1\" responseSigType=\"PKCS7pdf\"><Docs>"
            + "<InputHash id=\"1\" hashAlgorithm=\"SHA256\" docInfo=\"order\">ab12</InputHash></Docs></Esign>";

    @TempDir
    static Path dir;
    static PrivateKey key;
    static X509Certificate cert;

    /** An RSA key and self-signed certificate from the running JDK's keytool (no test PKI library on this path). */
    @BeforeAll
    static void asp() throws Exception {
        Path keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "keytool.exe" : "keytool");
        assertTrue(Files.isExecutable(keytool), "keytool of the running JDK: " + keytool);
        Path ks = dir.resolve("asp.p12");
        Process p = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "asp", "-keyalg", "RSA", "-keysize",
                "2048", "-dname", "CN=Test ASP", "-validity", "2", "-storetype", "PKCS12", "-keystore", ks.toString(),
                "-storepass", "changeit", "-keypass", "changeit").redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "keytool finished");
        assertEquals(0, p.exitValue(), "keytool exit code");
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(ks)) {
            store.load(in, "changeit".toCharArray());
        }
        key = (PrivateKey) store.getKey("asp", "changeit".toCharArray());
        cert = (X509Certificate) store.getCertificate("asp");
    }

    static EsignRequestSigner.Profile profile(boolean includeCertificate) {
        return new EsignRequestSigner.Profile(CanonicalizationMethod.INCLUSIVE, SignatureMethod.RSA_SHA256,
                DigestMethod.SHA256, includeCertificate);
    }

    static Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    static boolean verifies(String signed) throws Exception {
        Document doc = parse(signed);
        NodeList sigs = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
        assertEquals(1, sigs.getLength(), "one enveloped signature");
        XMLSignature sig = XMLSignatureFactory.getInstance("DOM")
                .unmarshalXMLSignature(new DOMValidateContext(cert.getPublicKey(), sigs.item(0)));
        return sig.validate(new DOMValidateContext(cert.getPublicKey(), sigs.item(0)));
    }

    @Test
    void theSignatureVerifiesWithTheAspCertificateUnderTheConfiguredProfile() throws Exception {
        String signed = new EsignRequestSigner(key, cert, profile(false)).apply(REQUEST);
        assertTrue(verifies(signed), signed);
        Document doc = parse(signed);
        Element root = doc.getDocumentElement();
        assertEquals("Esign", root.getTagName());
        assertEquals("op-1", root.getAttribute("txn"), "the request itself is unchanged");
        Element si = (Element) doc.getElementsByTagNameNS(XMLSignature.XMLNS, "SignedInfo").item(0);
        assertEquals(CanonicalizationMethod.INCLUSIVE, ((Element) si.getElementsByTagNameNS(XMLSignature.XMLNS,
                "CanonicalizationMethod").item(0)).getAttribute("Algorithm"));
        assertEquals(SignatureMethod.RSA_SHA256, ((Element) si.getElementsByTagNameNS(XMLSignature.XMLNS,
                "SignatureMethod").item(0)).getAttribute("Algorithm"));
        Element ref = (Element) si.getElementsByTagNameNS(XMLSignature.XMLNS, "Reference").item(0);
        assertEquals("", ref.getAttribute("URI"), "the whole document");
        assertEquals(0, doc.getElementsByTagNameNS(XMLSignature.XMLNS, "KeyInfo").getLength(), "no KeyInfo unless asked");
    }

    @Test
    void keyInfoCarriesTheCertificateOnlyWhenTheProfileAsksAndExclusiveC14nWorks() throws Exception {
        String signed = new EsignRequestSigner(key, cert, new EsignRequestSigner.Profile(CanonicalizationMethod.EXCLUSIVE,
                SignatureMethod.RSA_SHA512, DigestMethod.SHA512, true)).apply(REQUEST);
        assertTrue(verifies(signed));
        assertEquals(1, parse(signed).getElementsByTagNameNS(XMLSignature.XMLNS, "X509Certificate").getLength());
    }

    @Test
    void aChangedRequestNoLongerVerifies() throws Exception {
        String signed = new EsignRequestSigner(key, cert, profile(false)).apply(REQUEST);
        assertFalse(verifies(signed.replace(">ab12<", ">ab13<")), "the input hash is covered by the signature");
        assertFalse(verifies(signed.replace("aspId=\"ASP-1\"", "aspId=\"ASP-2\"")), "the ASP id is covered");
    }

    @Test
    void sha1AndUnknownAlgorithmsAndNonRsaKeysAreRefused() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new EsignRequestSigner.Profile(CanonicalizationMethod.INCLUSIVE,
                "http://www.w3.org/2000/09/xmldsig#rsa-sha1", DigestMethod.SHA256, false));
        assertThrows(IllegalArgumentException.class, () -> new EsignRequestSigner.Profile(CanonicalizationMethod.INCLUSIVE,
                SignatureMethod.RSA_SHA256, DigestMethod.SHA1, false));
        assertThrows(IllegalArgumentException.class, () -> new EsignRequestSigner.Profile(null,
                SignatureMethod.RSA_SHA256, DigestMethod.SHA256, false));
        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(256);
        assertThrows(IllegalArgumentException.class,
                () -> new EsignRequestSigner(ec.generateKeyPair().getPrivate(), cert, profile(false)));
        assertEquals(SignatureMethod.RSA_SHA256, EsignRequestSigner.algorithm("rsa-sha256"));
        assertEquals(CanonicalizationMethod.EXCLUSIVE, EsignRequestSigner.algorithm(" exc-c14n "));
    }

    @Test
    void anUnreadableRequestIsNotSentUnsigned() {
        assertThrows(IllegalStateException.class, () -> new EsignRequestSigner(key, cert, profile(false))
                .apply("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><Esign>&e;</Esign>"));
    }
}
