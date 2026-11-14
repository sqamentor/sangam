// SPDX-License-Identifier: Apache-2.0
package org.sangam.esign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.DefaultDigestAlgorithmIdentifierFinder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.TSPAlgorithms;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampResponseGenerator;
import org.bouncycastle.tsp.TimeStampTokenGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.integration.connector.EgressPolicy;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.RetryPolicy;
import org.sangam.integration.connector.SecretResolver;

/**
 * M-080 RFC 3161 client against a Bouncy Castle time-stamp authority on 127.0.0.1: the token over the signature value
 * is added as the unsigned signature-time-stamp attribute and the validator verifies it; the Authorization header comes
 * from a secret reference; a refusal, a wrong status or an unreachable TSA leaves the CMS unchanged.
 */
class TimestampAuthorityClientTest {

    static final Instant TSA_TIME = Instant.parse("2026-11-12T10:00:05Z");

    HttpServer server;
    String base;
    final List<String> authorizations = new CopyOnWriteArrayList<>();
    volatile int status = 200;
    TimeStampResponseGenerator tsa;

    @BeforeEach
    void start() throws Exception {
        SignTestKit.Signer tsaCert = SignTestKit.selfSigned("Test TSA", true);
        TimeStampTokenGenerator tokens = new TimeStampTokenGenerator(
                new JcaSignerInfoGeneratorBuilder(new JcaDigestCalculatorProviderBuilder().build())
                        .build(new JcaContentSignerBuilder("SHA256withRSA").build(tsaCert.keys().getPrivate()), tsaCert.cert()),
                new JcaDigestCalculatorProviderBuilder().build().get(new DefaultDigestAlgorithmIdentifierFinder().find("SHA-256")),
                new ASN1ObjectIdentifier("1.2.3.4.1"));
        tokens.addCertificates(new JcaCertStore(List.of(tsaCert.cert())));
        tsa = new TimeStampResponseGenerator(tokens, TSPAlgorithms.ALLOWED);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tsa", x -> {
            authorizations.add(String.valueOf(x.getRequestHeaders().getFirst("Authorization")));
            byte[] body;
            try {
                TimeStampRequest req = new TimeStampRequest(x.getRequestBody().readAllBytes());
                body = tsa.generate(req, BigInteger.ONE, Date.from(TSA_TIME)).getEncoded();
            } catch (Exception e) {
                throw new IOException(e);
            }
            x.getResponseHeaders().add("Content-Type", "application/timestamp-reply");
            x.sendResponseHeaders(status, body.length);
            x.getResponseBody().write(body);
            x.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    TimestampAuthorityClient client(String authRef, Map<String, String> secrets) {
        HttpTransport t = new HttpTransport(EgressPolicy.of(List.of(EgressPolicy.Rule.parse("*", base, true)), 0, 1 << 20),
                null, Duration.ofSeconds(2), Duration.ofSeconds(5), RetryPolicy.none());
        return new TimestampAuthorityClient(URI.create(base + "/tsa"), t, SecretResolver.of(secrets), authRef);
    }

    @Test
    void theTokenIsAddedToTheSignerAndTheValidatorVerifiesIt() throws Exception {
        SignTestKit.Signer signer = SignTestKit.selfSigned("Signer", false);
        PadesSigning.Prepared p = PadesSigning.prepare(SignTestKit.onePagePdf(), null, null,
                Instant.parse("2026-11-12T10:00:00Z"), 0);
        byte[] cms = SignTestKit.detachedCms(signer, SignTestKit.signedContent(p.pdf(), p.byteRange()), false);

        byte[] stamped = client("secret://tsa/auth", Map.of("secret://tsa/auth", "Bearer tsa-token"))
                .addSignatureTimestamp("t1", cms, 0);
        assertEquals(List.of("Bearer tsa-token"), authorizations);

        byte[] signed = PadesSigning.embed(p.pdf(), p.digestSha256(), null, stamped);
        PdfSignatureValidator.SignatureReport s = new PdfSignatureValidator(null,
                Clock.fixed(TSA_TIME, ZoneOffset.UTC)).validate(signed).signatures().get(0);
        assertTrue(s.timestamped());
        assertEquals(Boolean.TRUE, s.timestampValid());
        assertEquals(TSA_TIME, s.timestampTime());
        assertTrue(s.integrityValid(), "an unsigned attribute does not change the signed attributes");
    }

    @Test
    void aFailedTimestampLeavesTheCmsUnchangedAndSaysWhy() throws Exception {
        SignTestKit.Signer signer = SignTestKit.selfSigned("Signer", false);
        byte[] cms = SignTestKit.detachedCms(signer, "content".getBytes(), false);

        status = 500;
        assertThrows(TimestampAuthorityClient.TimestampUnavailable.class,
                () -> client(null, Map.of()).addSignatureTimestamp("t1", cms, 0));

        status = 200;
        assertThrows(TimestampAuthorityClient.TimestampUnavailable.class,
                () -> client("secret://tsa/missing", Map.of()).addSignatureTimestamp("t1", cms, 0),
                "an unresolved authorization reference: the request is not sent");
        assertEquals(1, authorizations.size());

        assertThrows(TimestampAuthorityClient.TimestampUnavailable.class,
                () -> client(null, Map.of()).addSignatureTimestamp("t1", new byte[] {1, 2}, 0));

        HttpTransport denied = new HttpTransport(EgressPolicy.of(List.of(), 0, 1 << 20), null, Duration.ofSeconds(2),
                Duration.ofSeconds(5), RetryPolicy.none());
        assertThrows(TimestampAuthorityClient.TimestampUnavailable.class, () -> new TimestampAuthorityClient(
                URI.create(base + "/tsa"), denied, null, null).addSignatureTimestamp("t1", cms, 0),
                "the tenant egress policy applies");
        assertEquals(1, authorizations.size());
    }

    @Test
    void theTsaUrlMustBeAnAbsoluteHttpUrl() {
        HttpTransport t = new HttpTransport(EgressPolicy.of(List.of(), 0, 1 << 20), null, Duration.ofSeconds(2),
                Duration.ofSeconds(5), RetryPolicy.none());
        assertThrows(IllegalArgumentException.class, () -> new TimestampAuthorityClient(URI.create("ftp://tsa.example"), t,
                null, null));
        assertThrows(IllegalArgumentException.class, () -> new TimestampAuthorityClient(URI.create("/relative"), t,
                null, null));
        assertThrows(IllegalArgumentException.class, () -> new TimestampAuthorityClient(URI.create("https://tsa.example"),
                t, null, "secret://tsa/auth"), "a reference needs a resolver");
        assertTrue(authorizations.isEmpty());
    }
}
