// SPDX-License-Identifier: Apache-2.0
package org.sangam.esign;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509CRLEntry;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.ocsp.OCSPObjectIdentifiers;
import org.bouncycastle.asn1.x509.AccessDescription;
import org.bouncycastle.asn1.x509.AuthorityInformationAccess;
import org.bouncycastle.asn1.x509.CRLDistPoint;
import org.bouncycastle.asn1.x509.DistributionPoint;
import org.bouncycastle.asn1.x509.DistributionPointName;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.CertificateStatus;
import org.bouncycastle.cert.ocsp.OCSPReqBuilder;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.RevokedStatus;
import org.bouncycastle.cert.ocsp.SingleResp;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.sangam.integration.connector.HttpTransport;

/**
 * Owner decision 140: revocation status of one certificate, always required. OCSP first (the responder named in the
 * certificate's authority information access, request with a nonce, answer signed by the issuer or by a responder the
 * issuer delegated with id-kp-OCSPSigning, fresh by thisUpdate/nextUpdate; decision 251: a wrongly echoed nonce is
 * refused, and an answer without the nonce counts only with a nextUpdate that has not passed; decision 259: an answer
 * whose thisUpdate is older than the policy's OCSP maximum age is never used), then the CRL
 * distribution points (CRL
 * signed by the issuer, current). Every fetch goes through the shared {@link HttpTransport}, so the tenant's egress
 * policy and the lock rule apply; nothing is fetched outside it. When no source gives a fresh, authentic answer the
 * status is {@link State#UNAVAILABLE}: never "good".
 */
final class RevocationChecker {

    enum State { GOOD, REVOKED, UNAVAILABLE }

    /** The answer for one certificate; {@code source} is "OCSP url" or "CRL url" (kept as evidence). */
    record Status(State state, String source, Instant revokedAt, String detail) {
    }

    private static final SecureRandom NONCES = new SecureRandom();

    private final HttpTransport transport;
    private final Duration timeout;
    private final Duration skew;
    private final Duration ocspMaxAge;
    private final Clock clock;

    /** @param ocspMaxAge owner decision 259: the oldest OCSP thisUpdate accepted, nonce or not */
    RevocationChecker(HttpTransport transport, Duration timeout, Duration skew, Duration ocspMaxAge, Clock clock) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.timeout = timeout;
        this.skew = skew;
        this.ocspMaxAge = Objects.requireNonNull(ocspMaxAge, "ocspMaxAge");
        this.clock = clock;
    }

    Status check(String tenant, X509Certificate cert, X509Certificate issuer) {
        List<String> tried = new ArrayList<>();
        for (String url : ocspUrls(cert)) {
            Status s = ocsp(tenant, url, cert, issuer);
            if (s.state() != State.UNAVAILABLE) {
                return s;
            }
            tried.add(s.detail());
        }
        for (String url : crlUrls(cert)) {
            Status s = crl(tenant, url, cert, issuer);
            if (s.state() != State.UNAVAILABLE) {
                return s;
            }
            tried.add(s.detail());
        }
        return new Status(State.UNAVAILABLE, null, null, tried.isEmpty()
                ? "the certificate names no OCSP responder and no CRL distribution point" : String.join("; ", tried));
    }

    private Status ocsp(String tenant, String url, X509Certificate cert, X509Certificate issuer) {
        String src = "OCSP " + url;
        try {
            var digests = new JcaDigestCalculatorProviderBuilder().build();
            X509CertificateHolder issuerHolder = new JcaX509CertificateHolder(issuer);
            CertificateID id = new CertificateID(digests.get(CertificateID.HASH_SHA1), issuerHolder, cert.getSerialNumber());
            byte[] nonce = new byte[16];
            NONCES.nextBytes(nonce);
            // RFC 8954: the extension value is the DER encoding of Nonce ::= OCTET STRING, so the nonce is an OCTET
            // STRING inside the extension's own OCTET STRING (the echo below is read the same way). Raw nonce bytes
            // as the value are not a Nonce, and a responder that parses the extension refuses the request.
            byte[] req = new OCSPReqBuilder().addRequest(id).setRequestExtensions(new Extensions(new Extension(
                    OCSPObjectIdentifiers.id_pkix_ocsp_nonce, false, new DEROctetString(new DEROctetString(nonce)))))
                    .build().getEncoded();
            HttpTransport.Outcome.Response r = fetch(tenant, new HttpTransport.Call("POST", URI.create(url),
                    Map.of("Content-Type", "application/ocsp-request", "Accept", "application/ocsp-response"), req,
                    timeout, true));
            if (r == null) {
                return unavailable(src, "not reached");
            }
            OCSPResp resp = new OCSPResp(r.body());
            if (resp.getStatus() != OCSPResp.SUCCESSFUL) {
                return unavailable(src, "responder status " + resp.getStatus());
            }
            BasicOCSPResp basic = (BasicOCSPResp) resp.getResponseObject();
            X509Certificate responder = responder(basic, issuer);
            if (responder == null || !basic.isSignatureValid(new JcaContentVerifierProviderBuilder().build(responder))) {
                return unavailable(src, "the answer is not signed by the issuer or a delegated responder");
            }
            // decision 251: the request always carries a nonce; a wrong echo is a replayed or foreign answer
            Extension echoed = basic.getExtension(OCSPObjectIdentifiers.id_pkix_ocsp_nonce);
            if (echoed != null && !Arrays.equals(ASN1OctetString.getInstance(echoed.getParsedValue()).getOctets(), nonce)) {
                return unavailable(src, "nonce mismatch (replayed answer)");
            }
            boolean nonced = echoed != null;
            Instant now = clock.instant();
            for (SingleResp s : basic.getResponses()) {
                if (!id.equals(s.getCertID())) {
                    continue;
                }
                if (s.getThisUpdate().toInstant().isAfter(now.plus(skew))
                        || (s.getNextUpdate() != null && s.getNextUpdate().toInstant().isBefore(now.minus(skew)))) {
                    return unavailable(src, "the answer is not current");
                }
                if (s.getThisUpdate().toInstant().isBefore(now.minus(ocspMaxAge))) {
                    // decision 259: too old to rely on, even when the nonce matches (no skew is added to the limit)
                    return unavailable(src, "the answer is older than the OCSP maximum age " + ocspMaxAge);
                }
                if (!nonced && s.getNextUpdate() == null) {
                    // decision 251: without the nonce only the answer's own validity window shows it is fresh
                    // (RFC 5019 pre-produced answers); an open-ended unnonced answer could be any old replay
                    return unavailable(src, "the answer has no nonce and no nextUpdate, so its freshness is unknown");
                }
                CertificateStatus st = s.getCertStatus();
                if (st == CertificateStatus.GOOD) {
                    return new Status(State.GOOD, src, null, null);
                }
                if (st instanceof RevokedStatus rs) {
                    return new Status(State.REVOKED, src, rs.getRevocationTime().toInstant(), "revoked");
                }
                return unavailable(src, "status unknown to the responder");
            }
            return unavailable(src, "no answer for this certificate");
        } catch (Exception e) {
            return unavailable(src, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** The issuer itself, or a certificate in the answer issued and signed by it with id-kp-OCSPSigning. */
    private static X509Certificate responder(BasicOCSPResp basic, X509Certificate issuer) throws Exception {
        X509CertificateHolder[] certs = basic.getCerts();
        if (certs == null || certs.length == 0) {
            return issuer;
        }
        JcaX509CertificateConverter conv = new JcaX509CertificateConverter();
        for (X509CertificateHolder h : certs) {
            X509Certificate c = conv.getCertificate(h);
            if (c.equals(issuer)) {
                return issuer;
            }
            List<String> eku = c.getExtendedKeyUsage();
            if (eku != null && eku.contains(KeyPurposeId.id_kp_OCSPSigning.getId())
                    && c.getIssuerX500Principal().equals(issuer.getSubjectX500Principal())) {
                try {
                    c.verify(issuer.getPublicKey());
                    c.checkValidity();
                    return c;
                } catch (Exception e) {
                    // not a valid delegation: keep looking
                }
            }
        }
        return null;
    }

    private Status crl(String tenant, String url, X509Certificate cert, X509Certificate issuer) {
        String src = "CRL " + url;
        try {
            HttpTransport.Outcome.Response r = fetch(tenant, new HttpTransport.Call("GET", URI.create(url),
                    Map.of("Accept", "application/pkix-crl"), null, timeout, true));
            if (r == null) {
                return unavailable(src, "not reached");
            }
            X509CRL crl = (X509CRL) CertificateFactory.getInstance("X.509").generateCRL(new ByteArrayInputStream(r.body()));
            if (!crl.getIssuerX500Principal().equals(issuer.getSubjectX500Principal())) {
                return unavailable(src, "the CRL is from another issuer");
            }
            crl.verify(issuer.getPublicKey());
            Instant now = clock.instant();
            if (crl.getThisUpdate().toInstant().isAfter(now.plus(skew)) || crl.getNextUpdate() == null
                    || crl.getNextUpdate().toInstant().isBefore(now.minus(skew))) {
                return unavailable(src, "the CRL is not current");
            }
            X509CRLEntry e = crl.getRevokedCertificate(cert);
            return e == null ? new Status(State.GOOD, src, null, null)
                    : new Status(State.REVOKED, src, e.getRevocationDate().toInstant(), "revoked");
        } catch (Exception e) {
            return unavailable(src, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private HttpTransport.Outcome.Response fetch(String tenant, HttpTransport.Call call) {
        // The transport's deadline is wall-clock time (it compares with System.currentTimeMillis()). The injected clock is
        // the verification time the answers are judged at; as a deadline it would refuse every call whenever it is not now.
        HttpTransport.Outcome o = transport.execute(tenant, call, System.currentTimeMillis() + timeout.toMillis());
        return o instanceof HttpTransport.Outcome.Response r && r.status() == 200 ? r : null;
    }

    private static Status unavailable(String src, String why) {
        return new Status(State.UNAVAILABLE, src, null, src + ": " + why);
    }

    static List<String> ocspUrls(X509Certificate cert) {
        List<String> out = new ArrayList<>();
        try {
            byte[] v = cert.getExtensionValue(Extension.authorityInfoAccess.getId());
            if (v == null) {
                return out;
            }
            AuthorityInformationAccess aia = AuthorityInformationAccess.getInstance(
                    ASN1OctetString.getInstance(v).getOctets());
            for (AccessDescription d : aia.getAccessDescriptions()) {
                if (AccessDescription.id_ad_ocsp.equals(d.getAccessMethod())) {
                    uri(d.getAccessLocation(), out);
                }
            }
        } catch (RuntimeException e) {
            // an unreadable extension names no responder
        }
        return out;
    }

    static List<String> crlUrls(X509Certificate cert) {
        List<String> out = new ArrayList<>();
        try {
            byte[] v = cert.getExtensionValue(Extension.cRLDistributionPoints.getId());
            if (v == null) {
                return out;
            }
            CRLDistPoint dp = CRLDistPoint.getInstance(ASN1OctetString.getInstance(v).getOctets());
            for (DistributionPoint p : dp.getDistributionPoints()) {
                DistributionPointName n = p.getDistributionPoint();
                if (n != null && n.getType() == DistributionPointName.FULL_NAME) {
                    for (GeneralName g : GeneralNames.getInstance(n.getName()).getNames()) {
                        uri(g, out);
                    }
                }
            }
        } catch (RuntimeException e) {
            // an unreadable extension names no distribution point
        }
        return out;
    }

    private static void uri(GeneralName g, List<String> out) {
        if (g.getTagNo() == GeneralName.uniformResourceIdentifier) {
            String s = g.getName().toString();
            String l = s.toLowerCase(Locale.ROOT);
            if (l.startsWith("http://") || l.startsWith("https://")) {
                out.add(s);
            }
        }
    }
}
