// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.gov;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

import javax.xml.XMLConstants;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;

/**
 * DEC-140 (decision 140, CCA eSign 2.1): the ASP's enveloped XML signature over the {@code <Esign>} request, made with
 * the JDK's XML Digital Signature API (no Bouncy Castle, decision 217). The algorithms are not chosen here: the operator
 * states the ESP's confirmed profile (decision 215: confirmed against the ESP sandbox before live use), and only the
 * algorithms listed in {@link #CANONICALIZATIONS}, {@link #SIGNATURE_METHODS} and {@link #DIGEST_METHODS} are accepted
 * (no SHA-1). The signature is one {@code <Signature>} appended to the root, over the whole document ({@code URI=""},
 * enveloped-signature transform), with the ASP certificate in {@code KeyInfo} only when the profile asks for it.
 */
public final class EsignRequestSigner implements UnaryOperator<String> {

    /** Inclusive and exclusive canonical XML 1.0, without comments. */
    public static final Set<String> CANONICALIZATIONS = Set.of(CanonicalizationMethod.INCLUSIVE,
            CanonicalizationMethod.EXCLUSIVE);
    /** RSA with SHA-256, SHA-384 or SHA-512 (RFC 6931 identifiers). */
    public static final Set<String> SIGNATURE_METHODS = Set.of(SignatureMethod.RSA_SHA256, SignatureMethod.RSA_SHA384,
            SignatureMethod.RSA_SHA512);
    /** SHA-256, SHA-384 or SHA-512. */
    public static final Set<String> DIGEST_METHODS = Set.of(DigestMethod.SHA256, DigestMethod.SHA384,
            DigestMethod.SHA512);

    /** The ESP-confirmed request signature profile. */
    public record Profile(String canonicalization, String signatureMethod, String digestMethod, boolean includeCertificate) {
        public Profile {
            require(CANONICALIZATIONS, canonicalization, "canonicalization");
            require(SIGNATURE_METHODS, signatureMethod, "signature method");
            require(DIGEST_METHODS, digestMethod, "digest method");
        }

        private static void require(Set<String> allowed, String value, String what) {
            if (value == null || !allowed.contains(value)) {
                throw new IllegalArgumentException("eSign request " + what + " must be one of " + allowed);
            }
        }
    }

    private final PrivateKey key;
    private final X509Certificate certificate;
    private final Profile profile;

    public EsignRequestSigner(PrivateKey key, X509Certificate certificate, Profile profile) {
        this.key = Objects.requireNonNull(key, "the ASP signing key");
        this.certificate = Objects.requireNonNull(certificate, "the ASP certificate");
        this.profile = Objects.requireNonNull(profile, "the request signature profile");
        if (!"RSA".equalsIgnoreCase(key.getAlgorithm())) {
            throw new IllegalArgumentException("the ASP signing key must be an RSA key, not " + key.getAlgorithm());
        }
    }

    public Profile profile() {
        return profile;
    }

    public X509Certificate certificate() {
        return certificate;
    }

    /** The request with its enveloped signature; any failure is an {@link IllegalStateException} (nothing is sent). */
    @Override
    public String apply(String requestXml) {
        Objects.requireNonNull(requestXml, "requestXml");
        try {
            Document doc = parse(requestXml.getBytes(StandardCharsets.UTF_8));
            XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");
            Reference ref = fac.newReference("", fac.newDigestMethod(profile.digestMethod(), null),
                    List.of(fac.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null)), null, null);
            SignedInfo si = fac.newSignedInfo(fac.newCanonicalizationMethod(profile.canonicalization(),
                    (C14NMethodParameterSpec) null), fac.newSignatureMethod(profile.signatureMethod(), null), List.of(ref));
            KeyInfo ki = null;
            if (profile.includeCertificate()) {
                KeyInfoFactory kif = fac.getKeyInfoFactory();
                ki = kif.newKeyInfo(List.of(kif.newX509Data(List.of(certificate))));
            }
            fac.newXMLSignature(si, ki).sign(new DOMSignContext(key, doc.getDocumentElement()));
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.setOutputProperty(OutputKeys.INDENT, "no");
            StringWriter out = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(out));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("the eSign request could not be signed: " + e.getMessage(), e);
        }
    }

    private static Document parse(byte[] bytes) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    /** Short names an operator may write instead of the algorithm URIs. */
    public static final Map<String, String> ALIASES = Map.of(
            "c14n", CanonicalizationMethod.INCLUSIVE, "exc-c14n", CanonicalizationMethod.EXCLUSIVE,
            "rsa-sha256", SignatureMethod.RSA_SHA256, "rsa-sha384", SignatureMethod.RSA_SHA384,
            "rsa-sha512", SignatureMethod.RSA_SHA512,
            "sha256", DigestMethod.SHA256, "sha384", DigestMethod.SHA384, "sha512", DigestMethod.SHA512);

    /** An alias from {@link #ALIASES} or the value unchanged (then checked by {@link Profile}). */
    public static String algorithm(String value) {
        return value == null ? null : ALIASES.getOrDefault(value.trim(), value.trim());
    }
}
