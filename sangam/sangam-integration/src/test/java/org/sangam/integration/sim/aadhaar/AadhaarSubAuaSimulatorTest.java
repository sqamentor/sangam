// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.sim.aadhaar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** M-078: the neutral Sub-AUA simulator's own rules (txn reuse, refusals, lookup, no number in the ledger). */
class AadhaarSubAuaSimulatorTest {

    AadhaarSubAuaSimulator sim;
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() {
        sim = new AadhaarSubAuaSimulator().start();
    }

    @AfterEach
    void stop() {
        sim.close();
    }

    HttpResponse<String> post(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(sim.uri(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    static String otp(String txn, String number, boolean consent) {
        return "{\"txn\":\"" + txn + "\",\"idType\":\"AADHAAR\",\"idNumber\":\"" + number + "\",\"consent\":" + consent + "}";
    }

    @Test
    void aTxnReusedWithADifferentRequestIsAConflict() throws Exception {
        assertEquals(200, post(AadhaarSubAuaSimulator.OTP_PATH, otp("t-1", AadhaarSubAuaSimulator.TEST_NUMBER, true)).statusCode());
        HttpResponse<String> same = post(AadhaarSubAuaSimulator.OTP_PATH, otp("t-1", AadhaarSubAuaSimulator.TEST_NUMBER, true));
        assertTrue(same.body().contains("\"duplicate\":true"), same.body());
        HttpResponse<String> other = post(AadhaarSubAuaSimulator.OTP_PATH,
                "{\"txn\":\"t-1\",\"idType\":\"AADHAAR\",\"idNumber\":\"" + AadhaarSubAuaSimulator.TEST_NUMBER
                        + "\",\"consent\":true,\"purpose\":\"other\"}");
        assertEquals(409, other.statusCode());
        assertTrue(other.body().contains("TXN_REUSED"));
        assertEquals(1, sim.ledger().count(AadhaarSubAuaSimulator.EFFECT_OTP));
    }

    @Test
    void refusalsAndLookup() throws Exception {
        assertEquals(403, post(AadhaarSubAuaSimulator.OTP_PATH, otp("t-2", AadhaarSubAuaSimulator.TEST_NUMBER, false)).statusCode());
        assertEquals(400, post(AadhaarSubAuaSimulator.OTP_PATH, otp("t-3", "0123", true)).statusCode());
        HttpResponse<String> bio = post(AadhaarSubAuaSimulator.AUTH_PATH, "{\"txn\":\"t-4\",\"idType\":\"AADHAAR\",\"idNumber\":\""
                + AadhaarSubAuaSimulator.TEST_NUMBER + "\",\"consent\":true,\"authType\":\"BIOMETRIC\"}");
        assertEquals(400, bio.statusCode());
        assertTrue(bio.body().contains("BIOMETRIC_NOT_SUPPORTED"));
        assertEquals(404, http.send(HttpRequest.newBuilder(sim.uri(AadhaarSubAuaSimulator.TXN_PREFIX + "nope")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertFalse(sim.ledger().all().toString().contains(AadhaarSubAuaSimulator.TEST_NUMBER), "no number in the ledger");
    }
}
