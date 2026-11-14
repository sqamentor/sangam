// SPDX-License-Identifier: Apache-2.0
package org.sangam.esign;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1Set;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.cms.CMSAttributes;
import org.bouncycastle.asn1.cms.Time;
import org.bouncycastle.asn1.ess.ESSCertIDv2;
import org.bouncycastle.asn1.ess.SigningCertificateV2;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cms.CMSAbsentContent;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.SignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

/**
 * Owner decision 165: the server half of DSC signing. The private key never leaves the token: the server builds the CMS
 * signed attributes for a prepared PDF ({@link #toSign}), the loopback agent signs exactly those bytes on the token,
 * and the server checks that signature and assembles the detached CMS ({@link #assemble}) for
 * {@link PadesSigning#embed}. The signed PDF is then verified by {@code DscSignatureVerification} before any signed
 * transition; this class proves nothing by itself.
 *
 * <p>Signed attributes: content-type {@code id-data}, signing-time, {@code messageDigest} = the prepared byte-range
 * digest, and {@code signingCertificateV2} (SHA-256 of the signer certificate, as PAdES baseline requires). SHA-256
 * with RSA (PKCS#1 v1.5) or ECDSA only.
 */
public final class DscCms {

    /** Signature algorithms the agent may use, by JCA name. */
    public static final List<String> ALGORITHMS = List.of("SHA256withRSA", "SHA256withECDSA");

    /** What the agent signs: {@code signedAttributes} is the DER of the SET, the exact input of the signature. */
    public record ToSign(byte[] signedAttributes, String preparedDigestSha256, String signerCertificateSha256,
                         Instant signingTime) {
        public ToSign {
            signedAttributes = signedAttributes.clone();
        }

        @Override
        public byte[] signedAttributes() {
            return signedAttributes.clone();
        }
    }

    private DscCms() {
    }

    /** The signed attributes for a prepared document and the signer certificate the user chose on the agent. */
    public static ToSign toSign(String preparedDigestSha256, X509Certificate signer, Instant signingTime) {
        Objects.requireNonNull(signer, "signer");
        Objects.requireNonNull(signingTime, "signingTime");
        byte[] digest = HexFormat.of().parseHex(Objects.requireNonNull(preparedDigestSha256, "preparedDigestSha256"));
        if (digest.length != 32) {
            throw new IllegalArgumentException("the prepared digest must be SHA-256");
        }
        byte[] certSha = PadesSigning.sha256(encoded(signer));
        ASN1EncodableVector v = new ASN1EncodableVector();
        v.add(new Attribute(CMSAttributes.contentType, new DERSet(PKCSObjectIdentifiers.data)));
        v.add(new Attribute(CMSAttributes.signingTime, new DERSet(new Time(Date.from(signingTime)))));
        v.add(new Attribute(CMSAttributes.messageDigest, new DERSet(new DEROctetString(digest))));
        v.add(new Attribute(PKCSObjectIdentifiers.id_aa_signingCertificateV2, new DERSet(
                new SigningCertificateV2(new ESSCertIDv2[]{new ESSCertIDv2(certSha)}))));
        try {
            return new ToSign(new DERSet(v).getEncoded(ASN1Encoding.DER), HexFormat.of().formatHex(digest),
                    HexFormat.of().formatHex(certSha), signingTime);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Checks the agent's signature over {@code toSign} with the signer's public key, then builds the detached CMS
     * SignedData (the signer and the offered chain certificates included; none of them is trusted for that).
     *
     * @throws PadesSigning.SigningException {@code DSC_SIGNATURE_INVALID} when the signature does not verify, or
     *                                       {@code DSC_ALGORITHM} for an algorithm or key that is not accepted
     */
    public static byte[] assemble(ToSign toSign, X509Certificate signer, List<X509Certificate> chain, String algorithm,
                                  byte[] signature) {
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(signer, "signer");
        if (algorithm == null || !ALGORITHMS.contains(algorithm)
                || (algorithm.endsWith("RSA") != signer.getPublicKey() instanceof RSAPublicKey)
                || (algorithm.endsWith("ECDSA") != signer.getPublicKey() instanceof ECPublicKey)) {
            throw new PadesSigning.SigningException("DSC_ALGORITHM", "algorithm " + algorithm
                    + " does not match the signer key or is not accepted (" + ALGORITHMS + ")", null);
        }
        if (!HexFormat.of().formatHex(PadesSigning.sha256(encoded(signer))).equals(toSign.signerCertificateSha256())) {
            throw new PadesSigning.SigningException("DSC_SIGNER_CHANGED",
                    "the signing certificate is not the one the attributes were built for", null);
        }
        byte[] attrs = toSign.signedAttributes();
        try {
            Signature check = Signature.getInstance(algorithm);
            check.initVerify(signer.getPublicKey());
            check.update(attrs);
            if (!check.verify(signature)) {
                throw new PadesSigning.SigningException("DSC_SIGNATURE_INVALID",
                        "the token's signature does not verify over the signed attributes", null);
            }
        } catch (GeneralSecurityException e) {
            throw new PadesSigning.SigningException("DSC_SIGNATURE_INVALID", "the token's signature cannot be checked: "
                    + e.getMessage(), e);
        }
        try {
            AttributeTable fixed = new AttributeTable(ASN1Set.getInstance(attrs));
            CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
            gen.addSignerInfoGenerator(new SignerInfoGeneratorBuilder(new JcaDigestCalculatorProviderBuilder().build())
                    .setSignedAttributeGenerator(params -> fixed)
                    .build(new Presigned(algorithm, attrs, signature), new JcaX509CertificateHolder(signer)));
            List<X509Certificate> certs = new ArrayList<>();
            certs.add(signer);
            for (X509Certificate c : chain == null ? List.<X509Certificate>of() : chain) {
                if (!c.equals(signer)) {
                    certs.add(c);
                }
            }
            gen.addCertificates(new JcaCertStore(certs));
            return gen.generate(new CMSAbsentContent(), false).getEncoded(ASN1Encoding.DER);
        } catch (PadesSigning.SigningException e) {
            throw e;
        } catch (Exception e) {
            throw new PadesSigning.SigningException("DSC_ASSEMBLY", "the CMS cannot be assembled: " + e.getMessage(), e);
        }
    }

    /**
     * A {@link ContentSigner} that returns the token's signature, after checking that Bouncy Castle asks it to sign
     * exactly the bytes the agent signed (a re-encoding of the attributes would otherwise go unnoticed until
     * verification).
     */
    private static final class Presigned implements ContentSigner {
        private final AlgorithmIdentifier id;
        private final byte[] expected;
        private final byte[] signature;
        private final ByteArrayOutputStream written = new ByteArrayOutputStream();

        Presigned(String algorithm, byte[] expected, byte[] signature) {
            this.id = new DefaultSignatureAlgorithmIdentifierFinder().find(algorithm);
            this.expected = expected;
            this.signature = signature.clone();
        }

        @Override
        public AlgorithmIdentifier getAlgorithmIdentifier() {
            return id;
        }

        @Override
        public OutputStream getOutputStream() {
            return written;
        }

        @Override
        public byte[] getSignature() {
            if (!Arrays.equals(written.toByteArray(), expected)) {
                throw new PadesSigning.SigningException("DSC_ASSEMBLY",
                        "the CMS signed attributes differ from the bytes the token signed", null);
            }
            return signature.clone();
        }
    }

    /** The attributes of {@code toSign} by OID, for tests and evidence. */
    static Map<String, ASN1Encodable> attributes(ToSign toSign) {
        Map<String, ASN1Encodable> m = new java.util.LinkedHashMap<>();
        new AttributeTable(ASN1Set.getInstance(toSign.signedAttributes())).toHashtable()
                .forEach((k, a) -> m.put(k.toString(), ((Attribute) a).getAttrValues().getObjectAt(0)));
        return m;
    }

    private static byte[] encoded(X509Certificate c) {
        try {
            return c.getEncoded();
        } catch (CertificateEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
