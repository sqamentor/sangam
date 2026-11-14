// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.signature;

import java.io.ByteArrayInputStream;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Owner decision 140: the ESP's W3C XML signature over a whole eSign response, checked with the JDK's XML digital
 * signature API (no third-party library). The CCA eSign 2.1 specification does not fix the algorithm, canonicalisation
 * or key information, so this accepts the standard choices and refuses everything weaker:
 *
 * <ul>
 *   <li>exactly one {@code ds:Signature}, a direct child of the root element;</li>
 *   <li>exactly one reference, {@code URI=""} (the whole document), with the enveloped-signature transform and otherwise
 *       only canonicalisation transforms;</li>
 *   <li>signature RSA or ECDSA with SHA-256/384/512, digest SHA-256/384/512 (no SHA-1, no HMAC);</li>
 *   <li>the key is one of the configured ESP response-signing certificates: {@code KeyInfo} in the response is ignored,
 *       so a certificate that arrives in the response is never trusted;</li>
 *   <li>the JDK's secure validation mode is on.</li>
 * </ul>
 */
public final class XmlResponseSignature {

    /** Parsed response plus the result of the XML signature check. */
    public record Result(Document document, String failureCode, String detail, X509Certificate signer) {
        public boolean valid() {
            return failureCode == null;
        }
    }

    static final Set<String> SIGNATURE_METHODS = Set.of(
            "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256", "http://www.w3.org/2001/04/xmldsig-more#rsa-sha384",
            "http://www.w3.org/2001/04/xmldsig-more#rsa-sha512", "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha256",
            "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha384", "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha512");
    static final Set<String> DIGEST_METHODS = Set.of("http://www.w3.org/2001/04/xmlenc#sha256",
            "http://www.w3.org/2001/04/xmldsig-more#sha384", "http://www.w3.org/2001/04/xmlenc#sha512");
    static final Set<String> CANONICALISATIONS = Set.of(CanonicalizationMethod.INCLUSIVE,
            CanonicalizationMethod.INCLUSIVE_WITH_COMMENTS, CanonicalizationMethod.EXCLUSIVE,
            CanonicalizationMethod.EXCLUSIVE_WITH_COMMENTS, "http://www.w3.org/2006/12/xml-c14n11",
            "http://www.w3.org/2006/12/xml-c14n11#WithComments");

    private XmlResponseSignature() {
    }

    /** Parses (hardened: no DTD, no external entities) and checks the signature against {@code espCertificates}. */
    public static Result check(byte[] xml, List<X509Certificate> espCertificates) {
        Document doc;
        try {
            doc = parse(xml);
        } catch (Exception e) {
            return new Result(null, "MALFORMED_RESPONSE", "the response is not well-formed XML: " + e.getMessage(), null);
        }
        Element root = doc.getDocumentElement();
        NodeList sigs = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
        if (sigs.getLength() != 1) {
            return new Result(doc, "RESPONSE_SIGNATURE_MISSING", "expected exactly one XML signature, found "
                    + sigs.getLength(), null);
        }
        Node sigNode = sigs.item(0);
        if (sigNode.getParentNode() != root) {
            return new Result(doc, "RESPONSE_SIGNATURE_INVALID", "the XML signature is not a child of the root element",
                    null);
        }
        XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");
        String structural = null;
        for (X509Certificate cert : espCertificates) {
            try {
                DOMValidateContext ctx = new DOMValidateContext(cert.getPublicKey(), sigNode);
                ctx.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.TRUE);
                XMLSignature sig = fac.unmarshalXMLSignature(ctx);
                structural = structure(sig.getSignedInfo());
                if (structural != null) {
                    return new Result(doc, "ALGORITHM_NOT_ACCEPTED", structural, null);
                }
                if (sig.validate(ctx)) {
                    return new Result(doc, null, null, cert);
                }
            } catch (Exception e) {
                // a key of the wrong type for this signature: try the next configured certificate
            }
        }
        return new Result(doc, "RESPONSE_SIGNATURE_INVALID",
                "the XML signature does not verify with any configured ESP response-signing certificate", null);
    }

    /** Null when the signed info is acceptable, else why not. */
    static String structure(SignedInfo si) {
        if (!SIGNATURE_METHODS.contains(si.getSignatureMethod().getAlgorithm())) {
            return "signature method not accepted: " + si.getSignatureMethod().getAlgorithm();
        }
        if (!CANONICALISATIONS.contains(si.getCanonicalizationMethod().getAlgorithm())) {
            return "canonicalisation not accepted: " + si.getCanonicalizationMethod().getAlgorithm();
        }
        List<?> refs = si.getReferences();
        if (refs.size() != 1) {
            return "expected one reference, found " + refs.size();
        }
        Reference ref = (Reference) refs.get(0);
        if (ref.getURI() == null || !ref.getURI().isEmpty()) {
            return "the reference must cover the whole document (URI=\"\")";
        }
        if (!DIGEST_METHODS.contains(ref.getDigestMethod().getAlgorithm())) {
            return "digest method not accepted: " + ref.getDigestMethod().getAlgorithm();
        }
        boolean enveloped = false;
        for (Object o : ref.getTransforms()) {
            String alg = ((Transform) o).getAlgorithm();
            if (Transform.ENVELOPED.equals(alg)) {
                enveloped = true;
            } else if (!CANONICALISATIONS.contains(alg)) {
                return "transform not accepted: " + alg;
            }
        }
        return enveloped ? null : "the enveloped-signature transform is required";
    }

    static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        b.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
            @Override
            public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
                throw e;
            }
        });
        return b.parse(new ByteArrayInputStream(xml));
    }
}
