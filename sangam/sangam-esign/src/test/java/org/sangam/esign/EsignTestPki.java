// SPDX-License-Identifier: Apache-2.0
package org.sangam.esign;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.cms.CMSAttributes;
import org.bouncycastle.asn1.cms.Time;
import org.bouncycastle.asn1.ocsp.OCSPObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AccessDescription;
import org.bouncycastle.asn1.x509.AuthorityInformationAccess;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.CRLDistPoint;
import org.bouncycastle.asn1.x509.CRLReason;
import org.bouncycastle.asn1.x509.DistributionPoint;
import org.bouncycastle.asn1.x509.DistributionPointName;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cert.ocsp.BasicOCSPRespBuilder;
import org.bouncycastle.cert.ocsp.CertificateStatus;
import org.bouncycastle.cert.ocsp.OCSPReq;
import org.bouncycastle.cert.ocsp.OCSPRespBuilder;
import org.bouncycastle.cert.ocsp.Req;
import org.bouncycastle.cert.ocsp.RespID;
import org.bouncycastle.cert.ocsp.RevokedStatus;
import org.bouncycastle.cms.CMSAbsentContent;
import org.bouncycastle.cms.CMSAttributeTableGenerator;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.DefaultSignedAttributeTableGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/**
 * Owner decision 140 test material: a CA, end-entity certificates naming a local OCSP responder and CRL, a CMS over a
 * given document hash, and ESP-signed CCA eSign 2.1 responses (JDK XML signature). Public so the framework-independent
 * {@code org.sangam.integration.signature} tests can use it.
 */
public final class EsignTestPki implements AutoCloseable {

    public static final Instant NOT_BEFORE = Instant.parse("2020-01-01T00:00:00Z");
    public static final Instant NOT_AFTER = Instant.parse("2100-01-01T00:00:00Z");

    /** A key pair and its certificate. */
    public record Party(KeyPair keys, X509Certificate cert) {
    }

    /** How the local revocation service answers. */
    /**
     * Simulator behaviour. NO_NONCE: OCSP answers fresh but does not echo the nonce (RFC 5019 style); NO_NONCE_OPEN:
     * the same without nextUpdate; OPEN: the nonce is echoed and there is no nextUpdate; AGED: nonce echoed, nextUpdate
     * in an hour, but thisUpdate {@link #agedBy()} ago (decision 259).
     */
    public enum Mode { NORMAL, DOWN, WRONG_SIGNER, STALE, WRONG_NONCE, NO_NONCE, NO_NONCE_OPEN, OPEN, AGED }

    private volatile java.time.Duration agedBy = java.time.Duration.ofHours(25);

    /** How far back thisUpdate lies in {@link Mode#AGED}. */
    public java.time.Duration agedBy() {
        return agedBy;
    }

    public void agedBy(java.time.Duration d) {
        agedBy = d;
    }

    private static final AtomicLong SERIALS = new AtomicLong(1000);

    private final HttpServer server;
    private final Supplier<Instant> now;
    private final Map<BigInteger, Instant> revoked = new ConcurrentHashMap<>();
    private final Party ca;
    private final Party stranger;
    private volatile Mode ocspMode = Mode.NORMAL;
    private volatile Mode crlMode = Mode.NORMAL;
    private final AtomicLong ocspCalls = new AtomicLong();
    private volatile byte[] lastOcspNonce;
    private final AtomicLong crlCalls = new AtomicLong();

    /** Starts the OCSP and CRL endpoints on loopback; {@code now} is the clock of the verifier under test. */
    public EsignTestPki(Supplier<Instant> now) throws Exception {
        this.now = now;
        this.ca = selfSigned("Test Root CA", true);
        this.stranger = selfSigned("Stranger CA", true);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/ocsp", this::ocsp);
        server.createContext("/crl", this::crl);
        server.start();
    }

    public Party ca() {
        return ca;
    }

    /** Another self-signed CA: a path to it is never trusted. */
    public Party stranger() {
        return stranger;
    }

    public String ocspUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/ocsp";
    }

    public String crlUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/crl";
    }

    public void ocspMode(Mode m) {
        ocspMode = m;
    }

    public void crlMode(Mode m) {
        crlMode = m;
    }

    /** The nonce octets of the last OCSP request, or null when it carried none. */
    public byte[] lastOcspNonce() {
        byte[] n = lastOcspNonce;
        return n == null ? null : n.clone();
    }

    public long ocspCalls() {
        return ocspCalls.get();
    }

    public long crlCalls() {
        return crlCalls.get();
    }

    public void revoke(X509Certificate c, Instant at) {
        revoked.put(c.getSerialNumber(), at);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ---- certificates ----------------------------------------------------------------------------

    /** Smallest RSA modulus of an ordinary test key. */
    public static final int MIN_RSA_BITS = 2048;

    /** An RSA test key of at least {@link #MIN_RSA_BITS} bits; a smaller size is refused (see {@link #weakRsaForNegativeTest}). */
    public static KeyPair rsa(int bits) throws Exception {
        if (bits < MIN_RSA_BITS) {
            throw new IllegalArgumentException("a test key has at least " + MIN_RSA_BITS + " bits, not " + bits
                    + "; a negative test that needs a weak key calls weakRsaForNegativeTest");
        }
        return generateRsa(bits);
    }

    /**
     * A deliberately weak RSA key (below {@link #MIN_RSA_BITS} bits), only for tests that prove such a key is
     * <b>refused</b>. The name is the opt-in: no other method of this kit produces a key this small.
     */
    public static KeyPair weakRsaForNegativeTest(int bits) throws Exception {
        if (bits >= MIN_RSA_BITS) {
            throw new IllegalArgumentException(bits + " bits is not a weak key; use rsa(bits)");
        }
        return generateRsa(bits);
    }

    private static KeyPair generateRsa(int bits) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(bits);
        return g.generateKeyPair();
    }

    public static Party selfSigned(String cn, boolean isCa) throws Exception {
        KeyPair kp = rsa(2048);
        X500Name name = new X500Name("CN=" + cn);
        X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(SERIALS.incrementAndGet()),
                Date.from(NOT_BEFORE), Date.from(NOT_AFTER), name, kp.getPublic());
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(isCa));
        if (isCa) {
            b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        }
        return new Party(kp, sign(b, kp));
    }

    /**
     * An end-entity signer issued by {@code issuer}, naming this kit's OCSP responder and CRL. {@code rsaBits} below
     * {@link #MIN_RSA_BITS} is refused; see {@link #userWithWeakKeyForNegativeTest}.
     */
    public Party user(String cn, Party issuer, int keyUsage, int rsaBits, Instant notAfter) throws Exception {
        return issue(cn, issuer, keyUsage, rsa(rsaBits), notAfter);
    }

    /** As {@link #user(String, Party, int, int, Instant)} with a deliberately weak key, for a test that expects its refusal. */
    public Party userWithWeakKeyForNegativeTest(String cn, Party issuer, int keyUsage, int weakRsaBits, Instant notAfter)
            throws Exception {
        return issue(cn, issuer, keyUsage, weakRsaForNegativeTest(weakRsaBits), notAfter);
    }

    private Party issue(String cn, Party issuer, int keyUsage, KeyPair kp, Instant notAfter) throws Exception {
        X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(
                new JcaX509CertificateHolder(issuer.cert()).getSubject(), BigInteger.valueOf(SERIALS.incrementAndGet()),
                Date.from(NOT_BEFORE), Date.from(notAfter), new X500Name("CN=" + cn + ",O=Test"), kp.getPublic());
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        b.addExtension(Extension.keyUsage, true, new KeyUsage(keyUsage));
        b.addExtension(Extension.authorityInfoAccess, false, new AuthorityInformationAccess(
                new AccessDescription(AccessDescription.id_ad_ocsp,
                        new GeneralName(GeneralName.uniformResourceIdentifier, ocspUrl()))));
        b.addExtension(Extension.cRLDistributionPoints, false, new CRLDistPoint(new DistributionPoint[]{
                new DistributionPoint(new DistributionPointName(new GeneralNames(
                        new GeneralName(GeneralName.uniformResourceIdentifier, crlUrl()))), null, null)}));
        return new Party(kp, sign(b, issuer.keys()));
    }

    public Party user(String cn) throws Exception {
        return user(cn, ca, KeyUsage.digitalSignature | KeyUsage.nonRepudiation, 2048, NOT_AFTER);
    }

    private static X509Certificate sign(X509v3CertificateBuilder b, KeyPair issuerKeys) throws Exception {
        ContentSigner cs = new JcaContentSignerBuilder("SHA256withRSA").build(issuerKeys.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(b.build(cs));
    }

    // ---- CMS -------------------------------------------------------------------------------------

    /**
     * A detached CMS SignedData by {@code signer} whose signed {@code messageDigest} is {@code hash} (hash signing, as an
     * ESP does for an InputHash), with the signing-time {@code signingTime} and the certificates {@code certs}.
     */
    public static byte[] cmsOverHash(Party signer, byte[] hash, Instant signingTime, List<X509Certificate> certs,
                                     String digest) throws Exception {
        ContentSigner cs = new JcaContentSignerBuilder(digest + "withRSA").build(signer.keys().getPrivate());
        Hashtable<ASN1ObjectIdentifier, Attribute> base = new Hashtable<>();
        base.put(CMSAttributes.signingTime, new Attribute(CMSAttributes.signingTime,
                new DERSet(new Time(Date.from(signingTime)))));
        CMSAttributeTableGenerator std = new DefaultSignedAttributeTableGenerator(new AttributeTable(base));
        CMSAttributeTableGenerator withHash = params -> {
            Map<Object, Object> p = new HashMap<>(params);
            p.put(CMSAttributeTableGenerator.DIGEST, hash.clone());
            return std.getAttributes(Collections.unmodifiableMap(p));
        };
        JcaSignerInfoGeneratorBuilder sib = new JcaSignerInfoGeneratorBuilder(new JcaDigestCalculatorProviderBuilder().build());
        sib.setSignedAttributeGenerator(withHash);
        CMSSignedDataGenerator g = new CMSSignedDataGenerator();
        g.addSignerInfoGenerator(sib.build(cs, signer.cert()));
        g.addCertificates(new JcaCertStore(certs));
        return g.generate(new CMSAbsentContent(), false).getEncoded("DER");
    }

    public static byte[] cmsOverHash(Party signer, byte[] hash, Instant signingTime) throws Exception {
        return cmsOverHash(signer, hash, signingTime, List.of(signer.cert()), "SHA256");
    }

    // ---- ESP response ----------------------------------------------------------------------------

    /** An EsignResp with status 1, the user certificate and one DocSignature, enveloped-signed by {@code esp}. */
    public static byte[] response(Party esp, String txn, String ts, X509Certificate user, byte[] docSignature,
                                  X509Certificate keyInfoCert) throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<EsignResp ver=\"2.1\" status=\"1\" ts=\"" + ts + "\" txn=\"" + txn + "\" resCode=\"RC-" + txn
                + "\" errCode=\"NA\" errMsg=\"NA\"><UserX509Certificate>"
                + Base64.getEncoder().encodeToString(user.getEncoded()) + "</UserX509Certificate><Signatures>"
                + "<DocSignature id=\"1\" sigHashAlgorithm=\"SHA256\">"
                + Base64.getEncoder().encodeToString(docSignature) + "</DocSignature></Signatures></EsignResp>";
        return signXml(xml, esp, SignatureMethodUri.RSA_SHA256, DigestMethod.SHA256, keyInfoCert);
    }

    /** Signature method URIs used by the tests. */
    public static final class SignatureMethodUri {
        public static final String RSA_SHA256 = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256";
        public static final String RSA_SHA1 = "http://www.w3.org/2000/09/xmldsig#rsa-sha1";

        private SignatureMethodUri() {
        }
    }

    /** Enveloped XML signature over the whole document (reference URI=""), exclusive canonicalisation. */
    public static byte[] signXml(String xml, Party key, String signatureMethod, String digestMethod,
                                 X509Certificate keyInfoCert) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        Document doc = f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");
        Reference ref = fac.newReference("", fac.newDigestMethod(digestMethod, null),
                List.of(fac.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null)), null, null);
        SignedInfo si = fac.newSignedInfo(fac.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE,
                (C14NMethodParameterSpec) null), fac.newSignatureMethod(signatureMethod, null), List.of(ref));
        KeyInfo ki = null;
        if (keyInfoCert != null) {
            KeyInfoFactory kif = fac.getKeyInfoFactory();
            ki = kif.newKeyInfo(List.of(kif.newX509Data(List.of(keyInfoCert))));
        }
        XMLSignature sig = fac.newXMLSignature(si, ki);
        sig.sign(new DOMSignContext(key.keys().getPrivate(), doc.getDocumentElement()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TransformerFactory.newInstance().newTransformer().transform(new DOMSource(doc), new StreamResult(out));
        return out.toByteArray();
    }

    // ---- revocation service ----------------------------------------------------------------------

    private void ocsp(HttpExchange ex) throws IOException {
        ocspCalls.incrementAndGet();
        try {
            if (ocspMode == Mode.DOWN) {
                ex.sendResponseHeaders(503, -1);
                return;
            }
            byte[] body;
            try (InputStream in = ex.getRequestBody()) {
                body = in.readAllBytes();
            }
            OCSPReq req = new OCSPReq(body);
            Instant t = now.get();
            Instant thisUpdate = ocspMode == Mode.STALE ? t.minusSeconds(7200)
                    : ocspMode == Mode.AGED ? t.minus(agedBy) : t.minusSeconds(60);
            Instant nextUpdate = ocspMode == Mode.STALE ? t.minusSeconds(3600) : t.plusSeconds(3600);
            BasicOCSPRespBuilder b = new BasicOCSPRespBuilder(new RespID(
                    new JcaX509CertificateHolder(ca.cert()).getSubject()));
            for (Req r : req.getRequestList()) {
                Instant rev = revoked.get(r.getCertID().getSerialNumber());
                CertificateStatus st = rev == null ? CertificateStatus.GOOD
                        : new RevokedStatus(Date.from(rev), CRLReason.keyCompromise);
                b.addResponse(r.getCertID(), st, Date.from(thisUpdate),
                        ocspMode == Mode.NO_NONCE_OPEN || ocspMode == Mode.OPEN ? null : Date.from(nextUpdate), null);
            }
            Extension nonce = req.getExtension(OCSPObjectIdentifiers.id_pkix_ocsp_nonce);
            lastOcspNonce = nonce == null ? null
                    : ((org.bouncycastle.asn1.ASN1OctetString) nonce.getParsedValue()).getOctets();
            if (nonce != null && ocspMode != Mode.NO_NONCE && ocspMode != Mode.NO_NONCE_OPEN) {
                ASN1Encodable value = ocspMode == Mode.WRONG_NONCE ? new DEROctetString(new byte[16])
                        : nonce.getParsedValue();
                b.setResponseExtensions(new Extensions(new Extension(OCSPObjectIdentifiers.id_pkix_ocsp_nonce, false,
                        new DEROctetString(((org.bouncycastle.asn1.ASN1OctetString) value).getOctets()))));
            }
            KeyPair signing = ocspMode == Mode.WRONG_SIGNER ? stranger.keys() : ca.keys();
            ContentSigner cs = new JcaContentSignerBuilder("SHA256withRSA").build(signing.getPrivate());
            byte[] resp = new OCSPRespBuilder().build(OCSPRespBuilder.SUCCESSFUL,
                    b.build(cs, (X509CertificateHolder[]) null, Date.from(t))).getEncoded();
            reply(ex, "application/ocsp-response", resp);
        } catch (Exception e) {
            ex.sendResponseHeaders(500, -1);
        } finally {
            ex.close();
        }
    }

    private void crl(HttpExchange ex) throws IOException {
        crlCalls.incrementAndGet();
        try {
            if (crlMode == Mode.DOWN) {
                ex.sendResponseHeaders(503, -1);
                return;
            }
            Instant t = now.get();
            Instant thisUpdate = crlMode == Mode.STALE ? t.minusSeconds(7200) : t.minusSeconds(60);
            Instant nextUpdate = crlMode == Mode.STALE ? t.minusSeconds(3600) : t.plusSeconds(3600);
            X509v2CRLBuilder b = new X509v2CRLBuilder(new JcaX509CertificateHolder(ca.cert()).getSubject(),
                    Date.from(thisUpdate));
            b.setNextUpdate(Date.from(nextUpdate));
            revoked.forEach((serial, at) -> b.addCRLEntry(serial, Date.from(at), CRLReason.keyCompromise));
            KeyPair signing = crlMode == Mode.WRONG_SIGNER ? stranger.keys() : ca.keys();
            byte[] crl = b.build(new JcaContentSignerBuilder("SHA256withRSA").build(signing.getPrivate())).getEncoded();
            reply(ex, "application/pkix-crl", crl);
        } catch (Exception e) {
            ex.sendResponseHeaders(500, -1);
        } finally {
            ex.close();
        }
    }

    private static void reply(HttpExchange ex, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(body);
        }
    }

    /** UTF-8 helper for tests that edit a signed response. */
    public static byte[] replace(byte[] xml, String from, String to) {
        String s = new String(xml, StandardCharsets.UTF_8);
        if (!s.contains(from)) {
            throw new IllegalArgumentException("not found: " + from);
        }
        return s.replace(from, to).getBytes(StandardCharsets.UTF_8);
    }
}
