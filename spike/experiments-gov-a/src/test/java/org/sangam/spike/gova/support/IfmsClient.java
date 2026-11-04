package org.sangam.spike.gova.support;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.sangam.spike.sim.ifms.IfmsJitSimulator;

/** Minimal worker-side client of {@link IfmsJitSimulator} (PI payment instruction, PD payment status). */
public final class IfmsClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final IfmsJitSimulator sim;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private String token;

    public IfmsClient(IfmsJitSimulator sim) {
        this.sim = sim;
    }

    /** Thrown for a definite partner error (HTTP 4xx/5xx with a status line): nothing was done. */
    public static final class PartnerError extends Exception {
        public final int status;

        PartnerError(int status, String body) {
            super("HTTP " + status + ": " + body);
            this.status = status;
        }
    }

    private JsonNode post(String path, String body, boolean auth) throws IOException, InterruptedException, PartnerError {
        HttpRequest.Builder b = HttpRequest.newBuilder(sim.uri(path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (auth) {
            b.header("Authorization", "Bearer " + token());
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 400) {
            throw new PartnerError(r.statusCode(), r.body());
        }
        return JSON.readTree(r.body());
    }

    private String token() throws IOException, InterruptedException {
        if (token == null) {
            try {
                token = post(IfmsJitSimulator.AUTH_PATH, "{\"clientId\":\"sim-client\",\"clientSecret\":\"sim-secret\"}", false)
                        .path("authToken").asText();
            } catch (PartnerError e) {
                throw new IOException(e);
            }
        }
        return token;
    }

    /** PI: returns the paymentRef. IOException = outcome unknown (e.g. response lost). */
    public String pay(String operationId, String billId, long amount, String accountNo)
            throws IOException, InterruptedException, PartnerError {
        ObjectNode p = JSON.createObjectNode();
        p.put("serviceCode", "PI");
        ObjectNode params = p.putObject("params");
        params.put("billId", billId);
        params.put("amount", String.valueOf(amount));
        params.put("operationId", operationId);
        ObjectNode ben = params.putObject("beneficiary");
        ben.put("name", "Synthetic Contractor");
        ben.put("accountNo", accountNo);
        ben.put("ifsc", "SBIN0000001");
        return post(IfmsJitSimulator.DATA_PATH, p.toString(), true).path("paymentRef").asText();
    }

    /** PD: SUCCESS | FAILED | NOT_FOUND. */
    public String status(String operationId) throws IOException, InterruptedException, PartnerError {
        return post(IfmsJitSimulator.DATA_PATH,
                "{\"serviceCode\":\"PD\",\"params\":{\"operationId\":\"" + operationId + "\"}}", true)
                .path("status").asText();
    }
}
