// SPDX-License-Identifier: Apache-2.0
package org.sangam.esign;

import java.io.ByteArrayInputStream;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertStore;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.PKIXCertPathBuilderResult;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.cms.CMSAttributes;
import org.bouncycastle.asn1.cms.ContentInfo;
import org.bouncycastle.asn1.cms.Time;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSVerifierCertificateNotValidException;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.tsp.TimeStampToken;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.signature.EsignExpectation;
import org.sangam.integration.signature.EsignResponseVerifier;
import org.sangam.integration.signature.SignatureVerification;
import org.sangam.integration.signature.SignaturePolicy;
import org.sangam.integration.signature.XmlResponseSignature;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Owner decision 140 (RM-07): Bouncy Castle verification of a CCA eSign 2.1 response, in this order (the first failure
 * decides):
 *
 * <ol>
 *   <li>policy complete (trust anchors and ESP response certificates configured), else INDETERMINATE;</li>
 *   <li>ESP XML signature over the whole response ({@link XmlResponseSignature}) with a configured certificate that is
 *       valid now;</li>
 *   <li>binding: {@code status="1"}, the request's {@code txn}, a response time inside [request - skew, request +
 *       maximum age] and not in the future (a replayed or substituted response fails here);</li>
 *   <li>exactly one {@code DocSignature}: a CMS SignedData (PKCS7 / PKCS7pdf / PKCS7complete) with one signer whose
 *       certificate is the response's {@code UserX509Certificate}; signed {@code messageDigest} equal to the expected
 *       document hash; SHA-256/384/512 only; the signature verifies; raw RSA/ECDSA answers are INDETERMINATE
 *       (unsupported);</li>
 *   <li>the intended signer (subject common name, optionally the exact certificate), key size and key usage
 *       (digitalSignature or nonRepudiation);</li>
 *   <li>validity at the signing time (a verified signature time-stamp, else the signed signing-time, else the
 *       response time), and a PKIX path to a configured anchor at that time (certificates in the CMS only help build
 *       the path; they are never trusted);</li>
 *   <li>revocation of every certificate on the path (OCSP, then CRL): revoked before the signing time is INVALID, no
 *       authentic current answer is INDETERMINATE (retryable).</li>
 * </ol>
 */
public final class EsignResponseVerification implements EsignResponseVerifier {

    /** ServiceLoader entry (META-INF/services). */
    public static final class Provider implements EsignResponseVerifier.Factory {
        @Override
        public EsignResponseVerifier create(SignaturePolicy policy, HttpTransport revocationTransport, Clock clock) {
            return new EsignResponseVerification(policy, revocationTransport, clock);
        }
    }

    private final SignaturePolicy policy;
    private final RevocationChecker revocation;
    private final Clock clock;

    public EsignResponseVerification(SignaturePolicy policy, HttpTransport revocationTransport, Clock clock) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.revocation = new RevocationChecker(Objects.requireNonNull(revocationTransport, "revocationTransport"),
                policy.revocationTimeout(), policy.clockSkew(), policy.ocspMaxAge(), this.clock);
    }

    @Override
    public SignatureVerification verify(EsignExpectation expected, byte[] responseXml) {
        try {
            return check(expected, responseXml);
        } catch (RuntimeException e) {
            return SignatureVerification.indeterminate("VERIFIER_ERROR", e.getClass().getSimpleName() + ": "
                    + e.getMessage(), false, policy.version(), clock.instant());
        }
    }

    private SignatureVerification check(EsignExpectation expected, byte[] responseXml) {
        Instant now = clock.instant();
        String v = policy.version();
        String missing = policy.incomplete();
        if (missing != null) {
            return SignatureVerification.indeterminate("POLICY_INCOMPLETE", missing, false, v, now);
        }
        if (responseXml == null || responseXml.length == 0) {
            return SignatureVerification.invalid("MALFORMED_RESPONSE", "empty response", v, now);
        }
        List<X509Certificate> espCerts = policy.responseSignerCertificates().stream().filter(c -> validAt(c, now)).toList();
        if (espCerts.isEmpty()) {
            return SignatureVerification.indeterminate("RESPONSE_SIGNER_EXPIRED",
                    "no configured ESP response-signing certificate is valid now", false, v, now);
        }
        XmlResponseSignature.Result xml = XmlResponseSignature.check(responseXml, espCerts);
        if (!xml.valid()) {
            return SignatureVerification.invalid(xml.failureCode(), xml.detail(), v, now);
        }
        Element root = xml.document().getDocumentElement();
        if (!"EsignResp".equals(root.getLocalName() == null ? root.getTagName() : root.getLocalName())
                || !"1".equals(root.getAttribute("status"))) {
            return SignatureVerification.invalid("NOT_A_SIGNED_RESPONSE", "not an EsignResp with status 1", v, now);
        }
        if (!expected.txn().equals(root.getAttribute("txn"))) {
            return SignatureVerification.invalid("TXN_MISMATCH", "the response is for another transaction", v, now);
        }
        Instant ts;
        try {
            ts = responseTime(root.getAttribute("ts"));
        } catch (DateTimeParseException | IllegalStateException e) {
            return SignatureVerification.indeterminate("RESPONSE_TIME_UNREADABLE", e.getMessage(), false, v, now);
        }
        if (ts.isAfter(now.plus(policy.clockSkew())) || (expected.requestedAt() != null
                && (ts.isBefore(expected.requestedAt().minus(policy.clockSkew()))
                || ts.isAfter(expected.requestedAt().plus(policy.maxResponseAge()))))) {
            return SignatureVerification.invalid("RESPONSE_TIME_OUT_OF_WINDOW",
                    "the response time " + ts + " does not belong to the request of " + expected.requestedAt(), v, now);
        }
        NodeList docSigs = root.getElementsByTagName("DocSignature");
        NodeList userCerts = root.getElementsByTagName("UserX509Certificate");
        if (docSigs.getLength() != 1 || userCerts.getLength() != 1) {
            return SignatureVerification.invalid("MALFORMED_RESPONSE",
                    "expected exactly one DocSignature and one UserX509Certificate", v, now);
        }
        X509Certificate user;
        byte[] cmsBytes;
        try {
            user = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(
                    Base64.getMimeDecoder().decode(userCerts.item(0).getTextContent().trim())));
            cmsBytes = Base64.getMimeDecoder().decode(docSigs.item(0).getTextContent().trim());
        } catch (Exception e) {
            return SignatureVerification.invalid("MALFORMED_RESPONSE", "certificate or signature is not Base64 DER: "
                    + e.getMessage(), v, now);
        }
        String userSha = HexFormat.of().formatHex(sha256Quiet(user));
        String subject = user.getSubjectX500Principal().getName();
        CmsCheck cms = cms(cmsBytes, user, expected.documentHashHex());
        if (cms.code() != null) {
            SignatureVerification r = cms.unsupported()
                    ? SignatureVerification.indeterminate(cms.code(), cms.detail(), false, v, now)
                    : SignatureVerification.invalid(cms.code(), cms.detail(), v, now);
            return r.withSigner(subject, userSha);
        }
        if (!expected.namesIntendedSigner(commonName(user))
                || (expected.intendedSignerCertificateSha256() != null
                && !expected.intendedSignerCertificateSha256().equals(userSha))) {
            return SignatureVerification.invalid("SIGNER_MISMATCH", "the signer is not the intended signer", v, now)
                    .withSigner(subject, userSha);
        }
        return signer(expected.tenantId(), user, cms, ts, now);
    }

    /**
     * The checks that follow a verified CMS, shared with the DSC verification: key size and usage, validity at the
     * signing time (time-stamp, else signed signing-time, else {@code fallbackTime}), a PKIX path to a configured anchor
     * and revocation of every certificate on it.
     */
    SignatureVerification signer(String tenant, X509Certificate user, CmsCheck cms, Instant fallbackTime, Instant now) {
        String v = policy.version();
        String userSha = HexFormat.of().formatHex(sha256Quiet(user));
        String subject = user.getSubjectX500Principal().getName();
        String key = keyProblem(user);
        if (key != null) {
            return SignatureVerification.invalid("KEY_NOT_ACCEPTED", key, v, now).withSigner(subject, userSha);
        }
        Instant at = cms.timestampTime() != null ? cms.timestampTime() : cms.signingTime() != null ? cms.signingTime()
                : fallbackTime;
        if (!validAt(user, at)) {
            return SignatureVerification.invalid("CERTIFICATE_NOT_VALID_AT_SIGNING",
                    "the signer certificate is not valid at " + at, v, now).withSigner(subject, userSha);
        }
        List<X509Certificate> path = new ArrayList<>();
        X509Certificate anchor;
        try {
            PKIXCertPathBuilderResult built = path(user, cms.extraCertificates(), at);
            built.getCertPath().getCertificates().forEach(c -> path.add((X509Certificate) c));
            anchor = built.getTrustAnchor().getTrustedCert();
        } catch (Exception e) {
            return SignatureVerification.invalid("UNTRUSTED_CHAIN", "no path to a configured trust anchor: "
                    + e.getMessage(), v, now).withSigner(subject, userSha);
        }
        if (anchor == null) {
            return SignatureVerification.indeterminate("POLICY_INCOMPLETE", "trust anchors must be configured as "
                    + "certificates (the issuer is needed for revocation checks)", false, v, now).withSigner(subject, userSha);
        }
        List<String> sources = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            X509Certificate issuer = i + 1 < path.size() ? path.get(i + 1) : anchor;
            RevocationChecker.Status s = revocation.check(tenant, path.get(i), issuer);
            switch (s.state()) {
                case GOOD -> sources.add(s.source());
                case REVOKED -> {
                    if (cms.timestampTime() == null || !s.revokedAt().isAfter(at)) {
                        return SignatureVerification.invalid("REVOKED", "certificate " + i + " of the path was revoked at "
                                + s.revokedAt() + " (" + s.source() + ")", v, now).withSigner(subject, userSha);
                    }
                    sources.add(s.source() + " (revoked after the time-stamped signing time)");
                }
                case UNAVAILABLE -> {
                    return SignatureVerification.indeterminate("REVOCATION_UNAVAILABLE", s.detail(), true, v, now)
                            .withSigner(subject, userSha);
                }
            }
        }
        List<String> chain = path.stream().map(c -> HexFormat.of().formatHex(sha256Quiet(c))).toList();
        return new SignatureVerification(SignatureVerification.Outcome.VERIFIED_VALID, null, null, false, v, subject,
                userSha, chain, sources, cms.signingTime(), cms.timestampTime(), now, false);
    }

    /** What the CMS check found; {@code code} null when it passed. */
    record CmsCheck(String code, String detail, boolean unsupported, Instant signingTime, Instant timestampTime,
                    List<X509Certificate> extraCertificates) {
        static CmsCheck fail(String code, String detail) {
            return new CmsCheck(code, detail, false, null, null, List.of());
        }
    }

    static CmsCheck cms(byte[] der, X509Certificate user, String documentHashHex) {
        CMSSignedData sd;
        try {
            sd = new CMSSignedData(der);
            if (sd.getSignedContent() == null) {
                // A detached signature over the document hash (PKCS7pdf, PKCS7complete): the expected SHA-256 hash is the
                // content digest. Without it Bouncy Castle would digest absent content and never match messageDigest.
                sd = new CMSSignedData(Map.of(SHA256_OID, HexFormat.of().parseHex(documentHashHex)), der);
            }
        } catch (Exception e) {
            return new CmsCheck("UNSUPPORTED_SIGNATURE_FORMAT", "DocSignature is not a CMS SignedData (raw RSA/ECDSA "
                    + "answers are not supported)", true, null, null, List.of());
        }
        return cms(sd, user, documentHashHex);
    }

    private static final String SHA256_OID = "2.16.840.1.101.3.4.2.1";

    /**
     * Checks a parsed CMS whose content digest is known (attached content, the content passed in, or the expected hash
     * as a precomputed digest): one signer that is {@code user}, an accepted digest, signed {@code messageDigest} equal
     * to {@code documentHashHex}, the signature, signing-time and an optional signature time-stamp.
     */
    static CmsCheck cms(CMSSignedData sd, X509Certificate user, String documentHashHex) {
        if (sd.getSignerInfos().size() != 1) {
            return CmsCheck.fail("CMS_INVALID", "expected one signer, found " + sd.getSignerInfos().size());
        }
        SignerInformation signer = sd.getSignerInfos().getSigners().iterator().next();
        try {
            if (!signer.getSID().match(new X509CertificateHolder(user.getEncoded()))) {
                return CmsCheck.fail("SIGNER_SUBSTITUTED", "the CMS signer is not the response's UserX509Certificate");
            }
        } catch (Exception e) {
            return CmsCheck.fail("CMS_INVALID", e.getMessage());
        }
        if (!SignaturePolicy.ACCEPTED_DIGESTS.contains(signer.getDigestAlgOID())) {
            return CmsCheck.fail("ALGORITHM_NOT_ACCEPTED", "digest " + signer.getDigestAlgOID() + " is not accepted");
        }
        AttributeTable signed = signer.getSignedAttributes();
        Attribute md = signed == null ? null : signed.get(CMSAttributes.messageDigest);
        if (md == null) {
            return new CmsCheck("UNSUPPORTED_SIGNATURE_FORMAT", "the CMS has no signed attributes", true, null, null,
                    List.of());
        }
        byte[] signedDigest = ASN1OctetString.getInstance(md.getAttrValues().getObjectAt(0)).getOctets();
        if (!HexFormat.of().formatHex(signedDigest).equals(documentHashHex)) {
            return CmsCheck.fail("DIGEST_MISMATCH", "the signature is over another document");
        }
        try {
            if (!signer.verify(new JcaSimpleSignerInfoVerifierBuilder().build(user))) {
                return CmsCheck.fail("SIGNATURE_INVALID", "the CMS signature does not verify");
            }
        } catch (CMSVerifierCertificateNotValidException e) {
            // Bouncy Castle checks the signer certificate against the signed signing-time itself
            return CmsCheck.fail("CERTIFICATE_NOT_VALID_AT_SIGNING", "the signer certificate is not valid at the "
                    + "signed signing-time");
        } catch (Exception e) {
            return CmsCheck.fail("SIGNATURE_INVALID", "the CMS signature does not verify: " + e.getMessage());
        }
        Instant signingTime = null;
        Attribute st = signed.get(CMSAttributes.signingTime);
        if (st != null) {
            try {
                signingTime = Time.getInstance(st.getAttrValues().getObjectAt(0)).getDate().toInstant();
            } catch (RuntimeException e) {
                return CmsCheck.fail("CMS_INVALID", "unreadable signing-time");
            }
        }
        Instant tsTime = null;
        Attribute tsAttr = signer.getUnsignedAttributes() == null ? null
                : signer.getUnsignedAttributes().get(PKCSObjectIdentifiers.id_aa_signatureTimeStampToken);
        if (tsAttr != null) {
            try {
                TimeStampToken token = new TimeStampToken(new CMSSignedData(
                        ContentInfo.getInstance(tsAttr.getAttrValues().getObjectAt(0))));
                @SuppressWarnings("unchecked")
                var tsa = token.getCertificates().getMatches(token.getSID());
                if (tsa.isEmpty() || !java.util.Arrays.equals(token.getTimeStampInfo().getMessageImprintDigest(),
                        PadesSigning.sha256(signer.getSignature()))) {
                    return CmsCheck.fail("TIMESTAMP_INVALID", "the signature time-stamp does not match the signature");
                }
                token.validate(new JcaSimpleSignerInfoVerifierBuilder().build(
                        (X509CertificateHolder) tsa.iterator().next()));
                tsTime = token.getTimeStampInfo().getGenTime().toInstant();
            } catch (Exception e) {
                return CmsCheck.fail("TIMESTAMP_INVALID", "the signature time-stamp does not verify: " + e.getMessage());
            }
        }
        List<X509Certificate> extra = new ArrayList<>();
        JcaX509CertificateConverter conv = new JcaX509CertificateConverter();
        for (Object o : sd.getCertificates().getMatches(null)) {
            try {
                extra.add(conv.getCertificate((X509CertificateHolder) o));
            } catch (Exception e) {
                return CmsCheck.fail("CMS_INVALID", "unreadable certificate in the CMS");
            }
        }
        return new CmsCheck(null, null, false, signingTime, tsTime, extra);
    }

    private PKIXCertPathBuilderResult path(X509Certificate user, List<X509Certificate> offered, Instant at) throws Exception {
        List<X509Certificate> pool = new ArrayList<>(offered);
        pool.addAll(policy.intermediates());
        pool.add(user);
        X509CertSelector target = new X509CertSelector();
        target.setCertificate(user);
        PKIXBuilderParameters params = new PKIXBuilderParameters(policy.trustAnchors(), target);
        params.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(pool)));
        params.setRevocationEnabled(false);   // revocation is checked below, through the egress-controlled transport
        params.setDate(Date.from(at));
        return (PKIXCertPathBuilderResult) CertPathBuilder.getInstance("PKIX").build(params);
    }

    private String keyProblem(X509Certificate c) {
        if (c.getPublicKey() instanceof RSAPublicKey rsa && rsa.getModulus().bitLength() < policy.minRsaBits()) {
            return "RSA key of " + rsa.getModulus().bitLength() + " bits (minimum " + policy.minRsaBits() + ")";
        }
        if (c.getPublicKey() instanceof ECPublicKey ec && ec.getParams().getCurve().getField().getFieldSize() < 256) {
            return "EC key below 256 bits";
        }
        boolean[] ku = c.getKeyUsage();
        if (ku != null && !(ku[0] || ku[1])) {
            return "the key usage allows neither digitalSignature nor nonRepudiation";
        }
        return null;
    }

    Instant responseTime(String ts) {
        if (ts == null || ts.isBlank()) {
            throw new IllegalStateException("the response has no ts");
        }
        try {
            return OffsetDateTime.parse(ts.trim()).toInstant();
        } catch (DateTimeParseException e) {
            if (policy.responseTimeZone() == null) {
                throw new IllegalStateException("ts has no offset and no response time zone is configured");
            }
            return LocalDateTime.parse(ts.trim()).atZone(policy.responseTimeZone()).toInstant();
        }
    }

    private static boolean validAt(X509Certificate c, Instant at) {
        try {
            c.checkValidity(Date.from(at));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static String commonName(X509Certificate c) {
        try {
            for (Rdn r : new LdapName(c.getSubjectX500Principal().getName()).getRdns()) {
                if ("CN".equalsIgnoreCase(r.getType())) {
                    return String.valueOf(r.getValue());
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private static byte[] sha256Quiet(X509Certificate c) {
        try {
            return PadesSigning.sha256(c.getEncoded());
        } catch (Exception e) {
            return new byte[0];
        }
    }
}
