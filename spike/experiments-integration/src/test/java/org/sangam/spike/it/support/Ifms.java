package org.sangam.spike.it.support;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.sangam.spike.sim.ifms.IfmsJitSimulator;

/** Minimal REST client of the IFMS JIT simulator (authenticate, PI, PD), as a payment worker would use it. */
public final class Ifms {

    /** Outcome of one call. {@code httpStatus = -1} means no response (connection dropped or timed out). */
    public record Result(int httpStatus, String status, boolean duplicate, String paymentRef, String errorCode, String body) {
        public boolean accepted() {
            return httpStatus == 200 && "ACCEPTED".equals(status);
        }

        public boolean noResponse() {
            return httpStatus < 0;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final IfmsJitSimulator sim;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private String token;

    public Ifms(IfmsJitSimulator sim) {
        this.sim = sim;
    }

    private synchronized String token() throws IOException, InterruptedException {
        if (token == null) {
            ObjectNode b = JSON.createObjectNode().put("clientId", "sim-client").put("clientSecret", "sim-secret");
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(sim.uri(IfmsJitSimulator.AUTH_PATH))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(b.toString())).build(), HttpResponse.BodyHandlers.ofString());
            token = JSON.readTree(r.body()).path("authToken").asText();
        }
        return token;
    }

    /** Payment instruction (PI) carrying the operationId as the idempotency key (VI.3 rule 4). */
    public Result pay(String operationId, String billId, long amount, String accountNo) {
        ObjectNode params = JSON.createObjectNode()
                .put("billId", billId).put("amount", Long.toString(amount)).put("operationId", operationId);
        params.putObject("beneficiary").put("name", "Synthetic Supplier").put("accountNo", accountNo).put("ifsc", "SIMB0000001");
        ObjectNode body = JSON.createObjectNode().put("serviceCode", "PI");
        body.set("params", params);
        return call(body);
    }

    /** Payment status (PD) by operationId: the outcome lookup of capability classes (a) and (b). */
    public Result lookup(String operationId) {
        ObjectNode body = JSON.createObjectNode().put("serviceCode", "PD");
        body.putObject("params").put("operationId", operationId);
        return call(body);
    }

    private Result call(ObjectNode body) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(sim.uri(IfmsJitSimulator.DATA_PATH))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token())
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
            JsonNode n = JSON.readTree(r.body());
            String paymentRef = n.path("paymentRef").asText(null);
            if (paymentRef == null && n.path("payments").isArray() && !n.path("payments").isEmpty()) {
                paymentRef = n.path("payments").get(0).path("paymentRef").asText(null);
            }
            return new Result(r.statusCode(), n.path("status").asText(null), n.path("duplicate").asBoolean(false),
                    paymentRef, n.path("errorCode").asText(null), r.body());
        } catch (IOException e) {
            return new Result(-1, null, false, null, "NO_RESPONSE: " + e.getMessage(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, null, false, null, "INTERRUPTED", null);
        }
    }
}
