// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.signature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sangam.esign.EsignTestPki;
import org.w3c.dom.Document;

/** Owner decision 140: the ESP's XML response signature, checked with the JDK only against configured certificates. */
class XmlResponseSignatureTest {

    private static final String BODY = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<EsignResp ver=\"2.1\" status=\"1\" ts=\"2026-06-01T09:59:00Z\" txn=\"T-1\" resCode=\"RC-1\">"
            + "<UserX509Certificate>AAAA</UserX509Certificate><Signatures><DocSignature id=\"1\">AAAA</DocSignature>"
            + "</Signatures></EsignResp>";

    private static EsignTestPki.Party esp;
    private static EsignTestPki.Party rogue;

    @BeforeAll
    static void keys() throws Exception {
        esp = EsignTestPki.selfSigned("Test ESP", false);
        rogue = EsignTestPki.selfSigned("Rogue ESP", false);
    }

    private static byte[] signed(EsignTestPki.Party key) throws Exception {
        return EsignTestPki.signXml(BODY, key, EsignTestPki.SignatureMethodUri.RSA_SHA256, DigestMethod.SHA256, null);
    }

    @Test
    void aResponseSignedByAConfiguredCertificateVerifies() throws Exception {
        XmlResponseSignature.Result r = XmlResponseSignature.check(signed(esp), List.of(esp.cert()));
        assertTrue(r.valid(), r::toString);
        assertEquals(esp.cert(), r.signer());
        assertEquals("EsignResp", r.document().getDocumentElement().getTagName());
    }

    @Test
    void aRotatedCertificateListIsTriedInTurn() throws Exception {
        XmlResponseSignature.Result r = XmlResponseSignature.check(signed(esp), List.of(rogue.cert(), esp.cert()));
        assertTrue(r.valid(), r::toString);
        assertEquals(esp.cert(), r.signer());
    }

    @Test
    void anyChangeAfterSigningBreaksTheSignature() throws Exception {
        byte[] tampered = EsignTestPki.replace(signed(esp), "txn=\"T-1\"", "txn=\"T-2\"");
        XmlResponseSignature.Result r = XmlResponseSignature.check(tampered, List.of(esp.cert()));
        assertEquals("RESPONSE_SIGNATURE_INVALID", r.failureCode());
        assertNull(r.signer());
    }

    @Test
    void aCertificateInKeyInfoIsNeverTrusted() throws Exception {
        byte[] xml = EsignTestPki.signXml(BODY, rogue, EsignTestPki.SignatureMethodUri.RSA_SHA256, DigestMethod.SHA256,
                rogue.cert());
        assertEquals("RESPONSE_SIGNATURE_INVALID", XmlResponseSignature.check(xml, List.of(esp.cert())).failureCode());
    }

    @Test
    void aMissingOrMisplacedSignatureIsRefused() throws Exception {
        assertEquals("RESPONSE_SIGNATURE_MISSING", XmlResponseSignature.check(BODY.getBytes(StandardCharsets.UTF_8),
                List.of(esp.cert())).failureCode());
        String inner = new String(signed(esp), StandardCharsets.UTF_8).replaceFirst("^<\\?xml[^>]*\\?>", "");
        byte[] wrapped = ("<Wrapper>" + inner + "</Wrapper>").getBytes(StandardCharsets.UTF_8);
        assertEquals("RESPONSE_SIGNATURE_INVALID", XmlResponseSignature.check(wrapped, List.of(esp.cert())).failureCode());
    }

    @Test
    void sha1IsNeverAccepted() throws Exception {
        byte[] xml = EsignTestPki.signXml(BODY, esp, EsignTestPki.SignatureMethodUri.RSA_SHA1, DigestMethod.SHA1, null);
        XmlResponseSignature.Result r = XmlResponseSignature.check(xml, List.of(esp.cert()));
        assertFalse(r.valid(), "an RSA-SHA1 response signature is refused");
        Document doc = XmlResponseSignature.parse(xml);
        DOMValidateContext ctx = new DOMValidateContext(esp.cert().getPublicKey(),
                doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature").item(0));
        ctx.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.FALSE);
        XMLSignature sig = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(ctx);
        assertTrue(XmlResponseSignature.structure(sig.getSignedInfo()).startsWith("signature method not accepted"));
    }

    @Test
    void aDoctypeIsRefusedAsMalformed() {
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/hosts\">]><x>&e;</x>";
        XmlResponseSignature.Result r = XmlResponseSignature.check(xml.getBytes(StandardCharsets.UTF_8),
                List.of(esp.cert()));
        assertEquals("MALFORMED_RESPONSE", r.failureCode());
        assertNull(r.document());
    }
}
