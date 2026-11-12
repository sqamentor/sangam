// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.gov;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.integration.sim.aadhaar.AadhaarSubAuaSimulator;

/**
 * M-078 (owner decision 67): the AUA-neutral Sub-AUA connector against its simulator. OTP then OTP authentication,
 * demographic authentication, idempotency by txn, lookup, and the refusals that happen before any call (consent,
 * biometrics, malformed number). The number never appears in an output.
 */
class AadhaarSubAuaConnectorTest {

    AadhaarSubAuaSimulator sim;
    AadhaarSubAuaConnector connector;

    @BeforeEach
    void start() {
        sim = new AadhaarSubAuaSimulator().start();
        connector = AadhaarSubAuaConnector.simulator(sim);
    }

    @AfterEach
    void stop() {
        sim.close();
    }

    static ConnectorRequest request(UUID id, String op, Map<String, ?> input) {
        return new ConnectorRequest("t1", id, id.toString(), 1, null, "bk", op, new LinkedHashMap<>(input), null);
    }

    static Map<String, Object> base() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("idType", "AADHAAR");
        in.put("idNumber", AadhaarSubAuaSimulator.TEST_NUMBER);
        in.put("consent", true);
        in.put("purpose", "beneficiary-verification");
        return in;
    }

    @Test
    void otpThenOtpAuthenticationSucceedsAndTheOtpIsUsedOnce() {
        UUID otpId = UUID.randomUUID();
        ConnectorResult otp = connector.invoke(request(otpId, AadhaarSubAuaConnector.OP_OTP, base()));
        ConnectorResult.Success sent = assertInstanceOf(ConnectorResult.Success.class, otp);
        assertEquals("OTP_SENT", sent.output().get("status"));
        assertEquals("XXXXXX4321", sent.output().get("maskedMobile"));
        assertEquals("XXXXXXXX2346", sent.output().get("idMasked"));
        assertFalse(sent.output().toString().contains(AadhaarSubAuaSimulator.TEST_NUMBER), "the number is never in the output");

        Map<String, Object> auth = base();
        auth.put("authType", "OTP");
        auth.put("otpTxn", sent.output().get("otpTxn"));
        auth.put("otp", AadhaarSubAuaSimulator.TEST_OTP);
        ConnectorResult.Success ok = assertInstanceOf(ConnectorResult.Success.class,
                connector.invoke(request(UUID.randomUUID(), AadhaarSubAuaConnector.OP_AUTHENTICATE, auth)));
        assertEquals(true, ok.output().get("authenticated"));
        assertTrue(ok.output().containsKey("authCode"));

        ConnectorResult.Success again = assertInstanceOf(ConnectorResult.Success.class,
                connector.invoke(request(UUID.randomUUID(), AadhaarSubAuaConnector.OP_AUTHENTICATE, auth)));
        assertEquals(false, again.output().get("authenticated"));
        assertEquals("OTP_EXPIRED", again.output().get("errorCode"));
    }

    @Test
    void aRepeatedTxnIsADuplicateWithNoNewEffectAndCanBeLookedUp() {
        UUID id = UUID.randomUUID();
        ConnectorRequest req = request(id, AadhaarSubAuaConnector.OP_OTP, base());
        assertFalse(((ConnectorResult.Success) connector.invoke(req)).duplicate());
        assertTrue(((ConnectorResult.Success) connector.invoke(req)).duplicate());
        assertEquals(1, sim.ledger().count(AadhaarSubAuaSimulator.EFFECT_OTP));
        assertEquals(CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, connector.capabilityClass());

        LookupResult.Found found = assertInstanceOf(LookupResult.Found.class, connector.lookup(req));
        assertTrue(found.succeeded());
        assertInstanceOf(LookupResult.NotFound.class,
                connector.lookup(request(UUID.randomUUID(), AadhaarSubAuaConnector.OP_OTP, base())));
    }

    @Test
    void demographicAuthenticationMatchesOrNot() {
        Map<String, Object> in = base();
        in.put("authType", "DEMOGRAPHIC");
        in.put("demographics", Map.of("name", "asha devi", "dob", "1990-04-01"));
        ConnectorResult.Success yes = assertInstanceOf(ConnectorResult.Success.class,
                connector.invoke(request(UUID.randomUUID(), AadhaarSubAuaConnector.OP_AUTHENTICATE, in)));
        assertEquals(true, yes.output().get("authenticated"));
        in.put("demographics", Map.of("name", "Someone Else"));
        ConnectorResult.Success no = assertInstanceOf(ConnectorResult.Success.class,
                connector.invoke(request(UUID.randomUUID(), AadhaarSubAuaConnector.OP_AUTHENTICATE, in)));
        assertEquals("DEMOGRAPHIC_MISMATCH", no.output().get("errorCode"));
    }

    @Test
    void consentBiometricsAndMalformedNumbersAreRefusedBeforeAnyCall() {
        long before = sim.requestsReceived();
        Map<String, Object> noConsent = base();
        noConsent.put("consent", false);
        assertEquals("CONSENT_REQUIRED", code(connector.invoke(request(UUID.randomUUID(), AadhaarSubAuaConnector.OP_OTP, noConsent))));

        Map<String, Object> bio = base();
        bio.put("authType", "BIOMETRIC");
        assertEquals("BIOMETRIC_NOT_SUPPORTED",
                code(connector.invoke(request(UUID.randomUUID(), AadhaarSubAuaConnector.OP_AUTHENTICATE, bio))));
        Map<String, Object> pid = base();
        pid.put("pidBlock", "<Pid/>");
        assertEquals("BIOMETRIC_NOT_SUPPORTED", code(connector.invoke(request(UUID.randomUUID(), AadhaarSubAuaConnector.OP_OTP, pid))));

        Map<String, Object> bad = base();
        bad.put("idNumber", "12345");
        assertEquals("INVALID_ID", code(connector.invoke(request(UUID.randomUUID(), AadhaarSubAuaConnector.OP_OTP, bad))));
        assertEquals("UNSUPPORTED_OPERATION", code(connector.invoke(request(UUID.randomUUID(), "ekyc", base()))));
        assertEquals(before, sim.requestsReceived(), "nothing reached the AUA");
    }

    @Test
    void anUnknownNumberIsABusinessFailureFromTheAua() {
        Map<String, Object> in = base();
        in.put("idNumber", "999941057058");
        ConnectorResult.BusinessFailure f = assertInstanceOf(ConnectorResult.BusinessFailure.class,
                connector.invoke(request(UUID.randomUUID(), AadhaarSubAuaConnector.OP_OTP, in)));
        assertEquals("ID_NOT_FOUND", f.code());
        assertFalse(f.message().contains("999941057058"));
    }

    @Test
    void theMaskKeepsOnlyTheLastFourDigits() {
        assertEquals("XXXXXXXXXXXX3456", AadhaarSubAuaConnector.masked(request(UUID.randomUUID(), "otp",
                Map.of("idNumber", "1234 5678 9012 3456"))));
        assertEquals("XXXX", AadhaarSubAuaConnector.masked(request(UUID.randomUUID(), "otp", Map.of())));
    }

    private static String code(ConnectorResult r) {
        return assertInstanceOf(ConnectorResult.BusinessFailure.class, r).code();
    }
}
