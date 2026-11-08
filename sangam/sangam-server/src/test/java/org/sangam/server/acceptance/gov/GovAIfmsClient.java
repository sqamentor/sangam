// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.acceptance.gov;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.sangam.integration.sim.ifms.IfmsJitSimulator;

/**
 * Worker-side client of Sangam's {@link IfmsJitSimulator} (PI payment instruction, PD payment status), the same
 * calls as the spike's {@code IfmsClient}. JSON is written by hand and read by field name, so the test needs no JSON
 * library of a particular major version.
 */
final class GovAIfmsClient {

    private final IfmsJitSimulator sim;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private String token;

    GovAIfmsClient(IfmsJitSimulator sim) {
        this.sim = sim;
    }

    /** A definite partner error (HTTP 4xx/5xx): nothing was done. */
    static final class PartnerError extends Exception {
        final int status;

        PartnerError(int status, String body) {
            super("HTTP " + status + ": " + body);
            this.status = status;
        }
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    private String post(String path, String body, boolean auth) throws IOException, InterruptedException, PartnerError {
        HttpRequest.Builder b = HttpRequest.newBuilder(sim.uri(path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (auth) {
            b.header("Authorization", "Bearer " + token());
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 400) {
            throw new PartnerError(r.statusCode(), r.body());
        }
        return r.body();
    }

    private String token() throws IOException, InterruptedException {
        if (token == null) {
            try {
                token = field(post(IfmsJitSimulator.AUTH_PATH,
                        "{\"clientId\":\"sim-client\",\"clientSecret\":\"sim-secret\"}", false), "authToken");
            } catch (PartnerError e) {
                throw new IOException(e);
            }
        }
        return token;
    }

    /** PI: returns the paymentRef. IOException = outcome unknown (e.g. response lost). */
    String pay(String operationId, String billId, long amount, String accountNo)
            throws IOException, InterruptedException, PartnerError {
        String body = "{\"serviceCode\":\"PI\",\"params\":{\"billId\":\"" + billId + "\",\"amount\":\"" + amount
                + "\",\"operationId\":\"" + operationId + "\",\"beneficiary\":{\"name\":\"Synthetic Contractor\","
                + "\"accountNo\":\"" + accountNo + "\",\"ifsc\":\"SBIN0000001\"}}}";
        return field(post(IfmsJitSimulator.DATA_PATH, body, true), "paymentRef");
    }

    /** PD: SUCCESS | FAILED | NOT_FOUND. */
    String status(String operationId) throws IOException, InterruptedException, PartnerError {
        return field(post(IfmsJitSimulator.DATA_PATH,
                "{\"serviceCode\":\"PD\",\"params\":{\"operationId\":\"" + operationId + "\"}}", true), "status");
    }
}
