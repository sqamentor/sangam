// SPDX-License-Identifier: Apache-2.0
package org.sangam.esign;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.SignerInformationStore;
import org.bouncycastle.tsp.TSPAlgorithms;
import org.bouncycastle.tsp.TSPException;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampResponse;
import org.bouncycastle.tsp.TimeStampToken;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.SecretResolver;

/**
 * M-080 RFC 3161 time-stamp client. It asks a Time-Stamp Authority for a token over the SHA-256 of a CMS signer's
 * signature value and adds the token as the unsigned {@code signature-time-stamp} attribute (the PAdES B-T level), so
 * the signing time is attested by a third party and the signature can be validated after the signer's certificate
 * expires.
 *
 * <p>The request goes through the shared {@link HttpTransport} (tenant egress policy, L2 lock rule, bounded answer) as
 * {@code POST application/timestamp-query}; an optional {@code Authorization} header value comes from a secret
 * reference. The answer must be {@code 200} with a granted status; it is checked against the request (nonce, imprint,
 * algorithm, certificate requested) before the token is used. Any failure throws {@link TimestampUnavailable} and the
 * CMS is left as it was: the caller decides whether a signature without a timestamp is acceptable.
 */
public final class TimestampAuthorityClient {

    /** The time-stamp could not be obtained or did not match the request. */
    public static final class TimestampUnavailable extends RuntimeException {
        public TimestampUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final SecureRandom NONCES = new SecureRandom();

    private final URI tsa;
    private final HttpTransport transport;
    private final SecretResolver secrets;
    private final String authorizationRef;

    /**
     * @param authorizationRef secret reference of the {@code Authorization} header value the TSA requires, or null
     */
    public TimestampAuthorityClient(URI tsa, HttpTransport transport, SecretResolver secrets, String authorizationRef) {
        this.tsa = Objects.requireNonNull(tsa, "tsa");
        String scheme = tsa.getScheme() == null ? "" : tsa.getScheme().toLowerCase(Locale.ROOT);
        if (tsa.getHost() == null || !(scheme.equals("https") || scheme.equals("http"))) {
            throw new IllegalArgumentException("TSA URL must be an absolute http(s) URL: " + tsa);
        }
        this.transport = Objects.requireNonNull(transport, "transport");
        this.secrets = secrets;
        this.authorizationRef = authorizationRef == null || authorizationRef.isBlank() ? null : authorizationRef;
        if (this.authorizationRef != null && secrets == null) {
            throw new IllegalArgumentException("an authorization reference needs a secret resolver");
        }
    }

    /** The CMS (DER, detached, one signer) with a signature time-stamp token added to its signer. */
    public byte[] addSignatureTimestamp(String tenantId, byte[] cms, long deadlineMillis) {
        CMSSignedData sd;
        SignerInformation signer;
        try {
            sd = new CMSSignedData(cms);
            if (sd.getSignerInfos().size() != 1) {
                throw new TimestampUnavailable("the CMS must have exactly one signer", null);
            }
            signer = sd.getSignerInfos().getSigners().iterator().next();
        } catch (CMSException e) {
            throw new TimestampUnavailable("not a CMS SignedData: " + e.getMessage(), e);
        }
        TimeStampToken token = request(tenantId, PadesSigning.sha256(signer.getSignature()), deadlineMillis);
        AttributeTable unsigned = signer.getUnsignedAttributes() == null
                ? new AttributeTable(new ASN1EncodableVector()) : signer.getUnsignedAttributes();
        unsigned = unsigned.add(PKCSObjectIdentifiers.id_aa_signatureTimeStampToken,
                token.toCMSSignedData().toASN1Structure());
        SignerInformation stamped = SignerInformation.replaceUnsignedAttributes(signer, unsigned);
        try {
            return CMSSignedData.replaceSigners(sd, new SignerInformationStore(stamped)).getEncoded("DER");
        } catch (IOException e) {
            throw new TimestampUnavailable("the time-stamped CMS cannot be encoded: " + e.getMessage(), e);
        }
    }

    /** A validated time-stamp token over a SHA-256 imprint. */
    public TimeStampToken request(String tenantId, byte[] sha256Imprint, long deadlineMillis) {
        TimeStampRequestGenerator gen = new TimeStampRequestGenerator();
        gen.setCertReq(true);
        TimeStampRequest req = gen.generate(TSPAlgorithms.SHA256, sha256Imprint, new BigInteger(64, NONCES));
        byte[] body;
        try {
            body = req.getEncoded();
        } catch (IOException e) {
            throw new TimestampUnavailable("the time-stamp request cannot be encoded", e);
        }
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/timestamp-query");
        h.put("Accept", "application/timestamp-reply");
        if (authorizationRef != null) {
            String a;
            try {
                a = secrets.require(authorizationRef);
            } catch (RuntimeException e) {
                throw new TimestampUnavailable(authorizationRef + " cannot be resolved; request not sent", e);
            }
            if (a == null || a.isBlank() || !HttpTransport.isFieldValue(a) || !a.equals(a.strip())) {
                throw new TimestampUnavailable(authorizationRef + " resolves to an empty or untidy value (not shown);"
                        + " request not sent", null);
            }
            h.put("Authorization", a);
        }
        // a time-stamp request has no effect on the TSA beyond issuing a token, so it may be repeated
        HttpTransport.Outcome o = transport.execute(tenantId, HttpTransport.Call.post(tsa, h, body, true), deadlineMillis);
        if (!(o instanceof HttpTransport.Outcome.Response r)) {
            throw new TimestampUnavailable("TSA not reached: " + switch (o) {
                case HttpTransport.Outcome.NoEffect ne -> ne.reason();
                case HttpTransport.Outcome.Unknown u -> u.reason();
                default -> "?";
            }, null);
        }
        if (r.status() != 200) {
            throw new TimestampUnavailable("TSA answered HTTP " + r.status(), null);
        }
        try {
            TimeStampResponse resp = new TimeStampResponse(r.body());
            resp.validate(req);
            TimeStampToken token = resp.getTimeStampToken();
            if (token == null) {
                throw new TimestampUnavailable("TSA refused: status " + resp.getStatus()
                        + (resp.getStatusString() == null ? "" : " " + resp.getStatusString()), null);
            }
            return token;
        } catch (TSPException | IOException e) {
            throw new TimestampUnavailable("TSA answer invalid: " + e.getMessage(), e);
        }
    }
}
