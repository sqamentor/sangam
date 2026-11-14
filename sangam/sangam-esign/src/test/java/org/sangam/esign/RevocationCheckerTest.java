// SPDX-License-Identifier: Apache-2.0
package org.sangam.esign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.signature.SignaturePolicy;

/** Owner decision 140: revocation is required; no fresh, authentic answer is never "good". */
class RevocationCheckerTest {

    private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");

    private EsignTestPki pki;
    private RevocationChecker checker;

    @BeforeEach
    void setUp() throws Exception {
        pki = new EsignTestPki(() -> NOW);
        checker = new RevocationChecker(HttpTransport.forSimulator(), Duration.ofSeconds(5), Duration.ofMinutes(5),
                SignaturePolicy.DEFAULT_OCSP_MAX_AGE, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        pki.close();
    }

    @Test
    void theResponderAndDistributionPointAreReadFromTheCertificate() throws Exception {
        EsignTestPki.Party u = pki.user("A");
        assertEquals(List.of(pki.ocspUrl()), RevocationChecker.ocspUrls(u.cert()));
        assertEquals(List.of(pki.crlUrl()), RevocationChecker.crlUrls(u.cert()));
    }

    @Test
    void aGoodOcspAnswerIsGoodAndARevokedOneCarriesTheTime() throws Exception {
        EsignTestPki.Party u = pki.user("A");
        RevocationChecker.Status s = checker.check("t1", u.cert(), pki.ca().cert());
        assertEquals(RevocationChecker.State.GOOD, s.state(), s::toString);
        assertTrue(s.source().startsWith("OCSP "));
        Instant at = NOW.minusSeconds(3600);
        pki.revoke(u.cert(), at);
        s = checker.check("t1", u.cert(), pki.ca().cert());
        assertEquals(RevocationChecker.State.REVOKED, s.state());
        assertEquals(at, s.revokedAt());
        assertEquals(0, pki.crlCalls(), "OCSP answered: the CRL is not fetched");
    }

    @Test
    void aCertificateNamingNoServiceIsUnavailableNeverGood() throws Exception {
        EsignTestPki.Party bare = EsignTestPki.selfSigned("Bare", false);
        RevocationChecker.Status s = checker.check("t1", bare.cert(), bare.cert());
        assertEquals(RevocationChecker.State.UNAVAILABLE, s.state());
        assertNull(s.source());
        assertEquals(0, pki.ocspCalls() + pki.crlCalls());
    }

    @Test
    void everyOcspRequestCarriesAFreshNonce() throws Exception {
        // owner decision 251: a nonce is always sent, 16 random bytes, new per request
        EsignTestPki.Party u = pki.user("A");
        checker.check("t1", u.cert(), pki.ca().cert());
        byte[] first = pki.lastOcspNonce();
        assertNotNull(first, "the OCSP request carries the nonce extension");
        assertEquals(16, first.length);
        checker.check("t1", u.cert(), pki.ca().cert());
        assertFalse(java.util.Arrays.equals(first, pki.lastOcspNonce()), "a new nonce per request");
    }

    @Test
    void aWronglyEchoedNonceIsRefusedEvenWhenTheAnswerIsSignedAndFresh() throws Exception {
        EsignTestPki.Party u = pki.user("A");
        pki.ocspMode(EsignTestPki.Mode.WRONG_NONCE);
        pki.crlMode(EsignTestPki.Mode.DOWN);
        RevocationChecker.Status s = checker.check("t1", u.cert(), pki.ca().cert());
        assertEquals(RevocationChecker.State.UNAVAILABLE, s.state());
        assertTrue(s.detail().contains("nonce mismatch"), s.detail());
    }

    @Test
    void anAnswerWithoutTheNonceCountsOnlyWhenItsOwnDatesShowItIsFresh() throws Exception {
        EsignTestPki.Party u = pki.user("A");
        pki.crlMode(EsignTestPki.Mode.DOWN);
        pki.ocspMode(EsignTestPki.Mode.NO_NONCE);   // thisUpdate a minute ago, nextUpdate in an hour
        RevocationChecker.Status s = checker.check("t1", u.cert(), pki.ca().cert());
        assertEquals(RevocationChecker.State.GOOD, s.state(), s::toString);
        assertTrue(s.source().startsWith("OCSP "));
        pki.ocspMode(EsignTestPki.Mode.NO_NONCE_OPEN);   // the same without nextUpdate: freshness unknown
        s = checker.check("t1", u.cert(), pki.ca().cert());
        assertEquals(RevocationChecker.State.UNAVAILABLE, s.state());
        assertTrue(s.detail().contains("no nonce and no nextUpdate"), s.detail());
        pki.revoke(u.cert(), NOW.minusSeconds(60));
        assertEquals(RevocationChecker.State.UNAVAILABLE, checker.check("t1", u.cert(), pki.ca().cert()).state(),
                "an open-ended unnonced answer is not trusted either way");
    }

    @Test
    void anOpenEndedAnswerIsAcceptedWhenTheNonceIsEchoed() throws Exception {
        // RFC 6960: no nextUpdate means newer information is always available; the matching nonce proves it is current
        EsignTestPki.Party u = pki.user("A");
        pki.crlMode(EsignTestPki.Mode.DOWN);
        pki.ocspMode(EsignTestPki.Mode.OPEN);
        assertEquals(RevocationChecker.State.GOOD, checker.check("t1", u.cert(), pki.ca().cert()).state());
    }

    @Test
    void anAnswerOlderThanTheOcspMaximumAgeIsNotUsedEvenWithAMatchingNonce() throws Exception {
        // owner decision 259: thisUpdate older than sangam.dsc.ocsp-max-age (default 24h) = unavailable = INDETERMINATE
        EsignTestPki.Party u = pki.user("A");
        pki.crlMode(EsignTestPki.Mode.DOWN);
        pki.ocspMode(EsignTestPki.Mode.AGED);
        pki.agedBy(Duration.ofHours(24).plusSeconds(1));
        RevocationChecker.Status s = checker.check("t1", u.cert(), pki.ca().cert());
        assertEquals(RevocationChecker.State.UNAVAILABLE, s.state());
        assertTrue(s.detail().contains("older than the OCSP maximum age"), s.detail());
        pki.agedBy(Duration.ofHours(24).plusMinutes(4));
        assertEquals(RevocationChecker.State.UNAVAILABLE, checker.check("t1", u.cert(), pki.ca().cert()).state(),
                "the clock skew does not extend the maximum age");
        pki.agedBy(Duration.ofHours(23));
        assertEquals(RevocationChecker.State.GOOD, checker.check("t1", u.cert(), pki.ca().cert()).state(),
                "within the maximum age, nonce matching, nextUpdate ahead");
        RevocationChecker strict = new RevocationChecker(HttpTransport.forSimulator(), Duration.ofSeconds(5),
                Duration.ofMinutes(5), Duration.ofHours(1), Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals(RevocationChecker.State.UNAVAILABLE, strict.check("t1", u.cert(), pki.ca().cert()).state(),
                "the configured maximum age applies");
    }

    @Test
    void theOcspMaximumAgeDefaultsTo24HoursAndMustBePositive() {
        SignaturePolicy p = new SignaturePolicy("v", null, null, null, null, null, 2048, null, null);
        assertEquals(Duration.ofHours(24), p.ocspMaxAge());
        assertEquals(Duration.ofHours(2), new SignaturePolicy("v", null, null, null, null, null, 2048, null, null,
                Duration.ofHours(2)).ocspMaxAge());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new SignaturePolicy("v",
                null, null, null, null, null, 2048, null, null, Duration.ZERO));
    }

    @Test
    void everyFailedSourceIsReportedWhenNothingAnswers() throws Exception {
        EsignTestPki.Party u = pki.user("A");
        pki.ocspMode(EsignTestPki.Mode.WRONG_NONCE);
        pki.crlMode(EsignTestPki.Mode.STALE);
        RevocationChecker.Status s = checker.check("t1", u.cert(), pki.ca().cert());
        assertEquals(RevocationChecker.State.UNAVAILABLE, s.state());
        assertTrue(s.detail().contains("nonce mismatch"), s.detail());
        assertTrue(s.detail().contains("not current"), s.detail());
    }
}
