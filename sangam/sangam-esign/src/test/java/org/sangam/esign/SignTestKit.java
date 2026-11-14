// SPDX-License-Identifier: Apache-2.0
package org.sangam.esign;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.ess.ESSCertIDv2;
import org.bouncycastle.asn1.ess.SigningCertificateV2;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.DefaultSignedAttributeTableGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

/** Test material for M-080: a one-page PDF, self-signed signer and TSA certificates, and a detached CMS over bytes. */
final class SignTestKit {

    static final Instant NOT_BEFORE = Instant.parse("2020-01-01T00:00:00Z");
    static final Instant NOT_AFTER = Instant.parse("2100-01-01T00:00:00Z");

    record Signer(KeyPair keys, X509Certificate cert) {
    }

    private SignTestKit() {
    }

    static byte[] onePagePdf() throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(out);
            return out.toByteArray();
        }
    }

    static KeyPair rsa() throws NoSuchAlgorithmException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    /** A self-signed certificate; {@code timeStamping} adds the critical id-kp-timeStamping extended key usage. */
    static Signer selfSigned(String cn, boolean timeStamping) throws Exception {
        KeyPair kp = rsa();
        X500Name name = new X500Name("CN=" + cn);
        X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(System.nanoTime()),
                Date.from(NOT_BEFORE), Date.from(NOT_AFTER), name, kp.getPublic());
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(!timeStamping));
        if (timeStamping) {
            b.addExtension(Extension.extendedKeyUsage, true, new ExtendedKeyUsage(KeyPurposeId.id_kp_timeStamping));
        }
        ContentSigner cs = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        return new Signer(kp, new JcaX509CertificateConverter().getCertificate(b.build(cs)));
    }

    /** The bytes a signature over {@code pdf}'s last byte range covers. */
    static byte[] signedContent(byte[] pdf, int[] br) {
        byte[] out = new byte[br[1] + br[3]];
        System.arraycopy(pdf, br[0], out, 0, br[1]);
        System.arraycopy(pdf, br[2], out, br[1], br[3]);
        return out;
    }

    /** A detached CMS (SHA-256 with RSA) over {@code content}; {@code cades} adds ESS signing-certificate-v2. */
    static byte[] detachedCms(Signer s, byte[] content, boolean cades) throws Exception {
        ContentSigner cs = new JcaContentSignerBuilder("SHA256withRSA").build(s.keys().getPrivate());
        JcaSignerInfoGeneratorBuilder sib = new JcaSignerInfoGeneratorBuilder(new JcaDigestCalculatorProviderBuilder().build());
        if (cades) {
            Attribute scv2 = new Attribute(PKCSObjectIdentifiers.id_aa_signingCertificateV2, new DERSet(
                    new SigningCertificateV2(new ESSCertIDv2(PadesSigning.sha256(s.cert().getEncoded())))));
            sib.setSignedAttributeGenerator(new DefaultSignedAttributeTableGenerator(new AttributeTable(scv2)));
        }
        CMSSignedDataGenerator g = new CMSSignedDataGenerator();
        g.addSignerInfoGenerator(sib.build(cs, s.cert()));
        g.addCertificates(new JcaCertStore(List.of(s.cert())));
        return g.generate(new CMSProcessableByteArray(content), false).getEncoded("DER");
    }
}
