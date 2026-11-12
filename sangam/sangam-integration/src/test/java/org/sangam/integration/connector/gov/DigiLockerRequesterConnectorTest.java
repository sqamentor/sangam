// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.gov;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.integration.sim.SimulatorProfile;
import org.sangam.integration.sim.digilocker.DigiLockerRequesterSimulator;

/** M-072: the DigiLocker requester against its simulator. */
class DigiLockerRequesterConnectorTest {

    private SimulatorProfile sims;
    private DigiLockerRequesterSimulator sim;
    private DigiLockerRequesterConnector connector;

    @BeforeEach
    void start() {
        sims = new SimulatorProfile();
        sim = sims.digiLockerRequester();
        connector = DigiLockerRequesterConnector.simulator(sims);
    }

    @AfterEach
    void stop() {
        sims.close();
    }

    private Map<String, String> consent(DigiLockerRequesterSimulator.Person p) {
        DigiLockerRequesterConnector.AuthorizationRequest a = connector.authorization("state-1");
        String code = sim.consent(p, DigiLockerRequesterSimulator.challengeOf(a.codeVerifier()));
        Map<String, String> ev = new LinkedHashMap<>();
        ev.put("code", code);
        ev.put("codeVerifier", a.codeVerifier());
        ev.put("redirectUri", DigiLockerRequesterSimulator.REDIRECT_URI);
        return ev;
    }

    private static DigiLockerRequesterSimulator.Person person(String id, LocalDate dob) {
        return new DigiLockerRequesterSimulator.Person(id, "Test Person", dob,
                Map.of("in.gov.sim-CERT-1", "pdf-bytes".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void theConsentUrlCarriesAnS256ChallengeAndTheState() {
        DigiLockerRequesterConnector.AuthorizationRequest a = connector.authorization("s-42");
        String q = a.url().getRawQuery();
        assertTrue(q.contains("code_challenge_method=S256"), q);
        assertTrue(q.contains("code_challenge=" + DigiLockerRequesterSimulator.challengeOf(a.codeVerifier())), q);
        assertTrue(q.contains("state=s-42"), q);
        assertThrows(IllegalArgumentException.class, () -> connector.authorization(" "));
    }

    @Test
    void anAdultIsVerifiedWithATokenRefAndNoDateOfBirth() {
        DigiLockerRequesterConnector.AdultCheck a = connector.verifyAdult("t1", consent(person("DL-1", LocalDate.of(1990, 1, 1))));
        assertTrue(a.identifiable());
        assertTrue(a.adult());
        assertEquals("ADULT_VERIFIED", a.detail());
        assertTrue(a.tokenRef().startsWith("dl:"));
        assertFalse(a.tokenRef().contains("DL-1"), "the DigiLocker id is not exposed");
        assertFalse(a.toString().contains("1990"), "the date of birth is not returned");
    }

    @Test
    void aMinorIsIdentifiableButNotAdult() {
        LocalDate dob = LocalDate.of(2020, 1, 1);   // a minor until 2038
        DigiLockerRequesterConnector.AdultCheck a = connector.verifyAdult("t1", consent(person("DL-2", dob)));
        assertTrue(a.identifiable());
        assertFalse(a.adult());
        assertEquals("MINOR", a.detail());
    }

    @Test
    void theTokenRefIsStablePerTenantAndPerson() {
        String a = connector.verifyAdult("t1", consent(person("DL-3", LocalDate.of(1980, 5, 5)))).tokenRef();
        String b = connector.verifyAdult("t1", consent(person("DL-3", LocalDate.of(1980, 5, 5)))).tokenRef();
        String other = connector.verifyAdult("t2", consent(person("DL-3", LocalDate.of(1980, 5, 5)))).tokenRef();
        assertEquals(a, b);
        assertFalse(a.equals(other), "another tenant cannot link the same person");
    }

    @Test
    void missingConsentIsReportedWithoutACall() {
        assertEquals("CONSENT_MISSING", connector.verifyAdult("t1", Map.of()).detail());
        assertTrue(sim.ledger().all().isEmpty());
    }

    @Test
    void aReusedCodeIsAConsentFailure() {
        Map<String, String> ev = consent(person("DL-4", LocalDate.of(1990, 1, 1)));
        connector.verifyAdult("t1", ev);
        assertThrows(DigiLockerRequesterConnector.ConsentException.class, () -> connector.verifyAdult("t1", ev));
        ConnectorResult r = connector.invoke(request("verify-adult", new LinkedHashMap<>(ev)));
        assertEquals("CONSENT_INVALID", assertInstanceOf(ConnectorResult.BusinessFailure.class, r).code());
    }

    @Test
    void aWrongVerifierIsAConsentFailure() {
        Map<String, String> ev = new LinkedHashMap<>(consent(person("DL-5", LocalDate.of(1990, 1, 1))));
        ev.put("codeVerifier", "not-the-verifier-not-the-verifier-xx");
        assertThrows(DigiLockerRequesterConnector.ConsentException.class, () -> connector.verifyAdult("t1", ev));
    }

    @Test
    void pullListsIssuedDocumentsAndFetchesTheNamedOnes() {
        Map<String, Object> in = new LinkedHashMap<>(consent(person("DL-6", LocalDate.of(1990, 1, 1))));
        in.put("uris", List.of("in.gov.sim-CERT-1"));
        ConnectorResult r = connector.invoke(request("pull", in));
        ConnectorResult.Success s = assertInstanceOf(ConnectorResult.Success.class, r, String.valueOf(r));
        assertNotNull(s.output().get("issued"));
        assertEquals(1, ((List<?>) s.output().get("files")).size());
    }

    @Test
    void anUnknownOperationIsRefused() {
        ConnectorResult r = connector.invoke(request("delete", Map.of()));
        assertEquals("UNSUPPORTED_OPERATION", assertInstanceOf(ConnectorResult.BusinessFailure.class, r).code());
    }

    @Test
    void lookupIsNotFoundSoARetryIsSafe() {
        assertInstanceOf(org.sangam.core.integration.LookupResult.NotFound.class,
                connector.lookup(request("verify-adult", Map.of())));
    }

    private static ConnectorRequest request(String op, Map<String, ?> input) {
        UUID id = UUID.randomUUID();
        return new ConnectorRequest("t1", id, id.toString(), 1, null, "bk", op, new LinkedHashMap<>(input), null);
    }
}
