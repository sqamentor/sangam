// SPDX-License-Identifier: Apache-2.0
package org.sangam.connectors.health.x12;

import org.sangam.connectors.health.HealthHttp;
import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.integration.connector.HttpTransport;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * X12 005010 connector to a clearinghouse or payer gateway (US-HLT-085…092).
 *
 * <p>Transport: HTTP POST of the X12 payload ({@code Content-Type: application/edi-x12}) to {@code realtimePath} or
 * {@code batchPath}, with the CAQH CORE-style envelope headers {@code X-Payload-ID} = operationId,
 * {@code X-Processing-Mode} (RealTime / Batch) and {@code X-Payload-Type}. The interchange control number is derived
 * from the operationId, so a repeat carries the same ISA13 / PayloadID and a clearinghouse that deduplicates on it
 * returns the first answer.
 *
 * <p>Operations:
 * <ul>
 *   <li>{@code eligibility} (270 → 271), {@code claimStatus} (276 → 277): real-time, no effect.</li>
 *   <li>{@code serviceReview} (278 request → 278 response): real-time; idempotent by PayloadID.</li>
 *   <li>{@code submitClaim} (837P/I → 999 + optional 277CA): batch; class (b), lookup by
 *       {@code GET {statusPath}/{payloadId}}.</li>
 *   <li>{@code parse}: decodes an X12 text given as {@code x12} (835 remittances, 277CA, 999 received by SFTP or the
 *       inbox); no network.</li>
 *   <li>{@code acknowledge}: builds the 999 for a received interchange ({@code x12}); no network.</li>
 * </ul>
 * Outputs: {@code x12Request}, {@code x12Response}, {@code decoded} (list), plus the first decoded transaction's
 * fields at the top level ({@code eligibility}, {@code decision}, {@code status}, …).
 */
public final class X12Connector implements SangamConnector {

    public static final String TYPE = "x12-005010";

    /**
     * @param id              connector instance id
     * @param baseUri         clearinghouse base
     * @param senderId        ISA06 / GS02 (submitter id assigned by the clearinghouse)
     * @param receiverId      ISA08 / GS03
     * @param production      ISA15 P or T
     * @param realtimePath    real-time endpoint path
     * @param batchPath       batch endpoint path
     * @param statusPath      batch status endpoint prefix (lookup)
     * @param authorization   value of the Authorization header (resolved secret), may be null
     * @param transport       HTTP transport
     * @param profile         live or simulator
     * @param timeout         request timeout (CORE real-time: 20 s)
     */
    public record Config(String id, URI baseUri, String senderId, String receiverId, boolean production,
                         String realtimePath, String batchPath, String statusPath, String authorization,
                         HttpTransport transport, ConnectorProfile profile, Duration timeout) {
        public Config {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(baseUri, "baseUri");
            Objects.requireNonNull(transport, "transport");
            realtimePath = realtimePath == null ? "/x12/realtime" : realtimePath;
            batchPath = batchPath == null ? "/x12/batch" : batchPath;
            statusPath = statusPath == null ? "/x12/status" : statusPath;
            timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
            profile = profile == null ? ConnectorProfile.LIVE : profile;
        }
    }

    private final Config config;
    private final ConnectorDescriptor descriptor;

    public X12Connector(Config config) {
        this.config = Objects.requireNonNull(config, "config");
        this.descriptor = new ConnectorDescriptor(config.id(), TYPE, CapabilityClass.B_LOOKUP_ONLY,
                List.of("eligibility", "claimStatus", "serviceReview", "submitClaim", "parse", "acknowledge"),
                config.profile());
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return descriptor;
    }

    /** ISA13 from the operationId: 9 digits, stable across retries. */
    public static long controlNumber(UUID operationId) {
        return Math.floorMod(operationId.getMostSignificantBits() ^ operationId.getLeastSignificantBits(), 999_999_999L) + 1;
    }

    @Override
    public ConnectorResult invoke(ConnectorRequest r) {
        try {
            return switch (r.operation()) {
                case "eligibility" -> realtime(r, "HS", "270", X12Builders.V270,
                        X12Builders.eligibility270(r.input(), trace(r)), true);
                case "claimStatus" -> realtime(r, "HR", "276", X12Builders.V276,
                        X12Builders.claimStatus276(r.input(), trace(r)), true);
                case "serviceReview" -> realtime(r, "HI", "278", X12Builders.V278,
                        X12Builders.serviceReview278(r.input(), trace(r)), true);
                case "submitClaim" -> submit(r);
                case "parse" -> parse(r);
                case "acknowledge" -> acknowledge(r);
                default -> ConnectorResult.BusinessFailure.of("UNSUPPORTED_OPERATION", r.operation());
            };
        } catch (IllegalArgumentException | X12.X12Exception e) {
            return ConnectorResult.BusinessFailure.of("INVALID_INPUT", e.getMessage());
        }
    }

    @Override
    public LookupResult lookup(ConnectorRequest r) {
        if (!"submitClaim".equals(r.operation())) {
            return new LookupResult.NotFound(); // the other operations have no lasting effect or are idempotent
        }
        String payloadId = r.operationId().toString();
        HttpTransport.Call call = new HttpTransport.Call("GET", HealthHttp.join(config.baseUri(),
                config.statusPath() + "/" + payloadId), headers("Batch", "X12_Status", payloadId), null, config.timeout(), true);
        HttpTransport.Outcome o = config.transport().execute(r.tenantId(), call, HealthHttp.deadline(r));
        if (o instanceof HttpTransport.Outcome.Response resp) {
            if (resp.status() == 404) {
                return new LookupResult.NotFound();
            }
            if (resp.is2xx()) {
                Map<String, Object> out = decodeOutput(null, resp.bodyString());
                boolean ok = Boolean.TRUE.equals(out.get("accepted"));
                return ok ? LookupResult.Found.success(out, payloadId)
                        : LookupResult.Found.failed("X12_REJECTED", out);
            }
        }
        return HealthHttp.lookupFailure("X12 " + config.id(), o);
    }

    private ConnectorResult realtime(ConnectorRequest r, String fid, String type, String version,
                                     List<X12.Segment> body, boolean idempotent) {
        String x12 = X12.write(envelope(r, true), fid, type, version, List.of(body), null);
        HttpTransport.Call call = new HttpTransport.Call("POST", HealthHttp.join(config.baseUri(), config.realtimePath()),
                headers("RealTime", "X12_" + type + "_Request_" + version, r.operationId().toString()), x12.getBytes(StandardCharsets.UTF_8),
                HealthHttp.timeout(config.timeout(), r), idempotent);
        HttpTransport.Outcome o = config.transport().execute(r.tenantId(), call, HealthHttp.deadline(r));
        if (!(o instanceof HttpTransport.Outcome.Response resp) || !resp.is2xx()) {
            return HealthHttp.failure("X12 " + config.id(), o, idempotent);
        }
        Map<String, Object> out = decodeOutput(x12, resp.bodyString());
        Object err = out.get("envelopeErrors");
        if (err instanceof List<?> l && !l.isEmpty()) {
            return new ConnectorResult.OutcomeUnknown("X12 " + config.id() + ": malformed response " + l, null);
        }
        if (Boolean.FALSE.equals(out.get("accepted"))) {
            return new ConnectorResult.BusinessFailure("X12_REJECTED", "interchange rejected by the receiver", out);
        }
        return ConnectorResult.Success.of(out, String.valueOf(out.getOrDefault("controlNumber", "")));
    }

    private ConnectorResult submit(ConnectorRequest r) {
        boolean inst = Boolean.parseBoolean(r.str("institutional", "false"));
        String version = inst ? X12Builders.V837I : X12Builders.V837P;
        String x12 = X12.write(envelope(r, true), "HC", "837", version, List.of(X12Builders.claim837(r.input(), trace(r))),
                null);
        HttpTransport.Call call = new HttpTransport.Call("POST", HealthHttp.join(config.baseUri(), config.batchPath()),
                headers("Batch", "X12_837_Request_" + version, r.operationId().toString()), x12.getBytes(StandardCharsets.UTF_8),
                HealthHttp.timeout(config.timeout(), r), false);
        HttpTransport.Outcome o = config.transport().execute(r.tenantId(), call, HealthHttp.deadline(r));
        if (!(o instanceof HttpTransport.Outcome.Response resp) || !resp.is2xx()) {
            return HealthHttp.failure("X12 " + config.id(), o, false);
        }
        Map<String, Object> out = decodeOutput(x12, resp.bodyString());
        out.put("payloadId", r.operationId().toString());
        if (Boolean.FALSE.equals(out.get("accepted"))) {
            return new ConnectorResult.BusinessFailure("X12_REJECTED", "claim rejected at the front end (999/TA1)", out);
        }
        return ConnectorResult.Success.of(out, r.operationId().toString());
    }

    private ConnectorResult parse(ConnectorRequest r) {
        Map<String, Object> out = parseFocused(r.required("x12"), r.str("eraTraceNumber"), r.str("patientControlNumber"));
        return ConnectorResult.Success.of(out, String.valueOf(out.getOrDefault("controlNumber", "")));
    }

    /**
     * Decodes an X12 file. With a trace number, the top level describes the 835 transaction of that TRN02 (a file may
     * hold several); with a patient control number, it is scoped to that claim ({@link X12Decoders#focusClaim}), so a
     * process posts and routes only its own claim of a mixed 835.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseFocused(String x12, String traceNumber, String patientControlNumber) {
        Map<String, Object> out = decodeOutput(null, x12);
        List<Map<String, Object>> decoded = (List<Map<String, Object>>) out.get("decoded");
        Map<String, Object> pick = null;
        for (Map<String, Object> d : decoded) {
            if (traceNumber != null && traceNumber.equals(d.get("traceNumber"))) {
                pick = d;
            }
        }
        if (pick == null && !decoded.isEmpty()) {
            pick = decoded.get(0);
        }
        if (pick == null) {
            return out;
        }
        if (patientControlNumber != null && !patientControlNumber.isBlank() && "835".equals(pick.get("type"))) {
            pick = X12Decoders.focusClaim(pick, patientControlNumber);
        }
        for (Map.Entry<String, Object> e : pick.entrySet()) {
            if (!(e.getValue() instanceof List<?>) || e.getKey().equals("claims")) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return HealthHttp.noNulls(out);
    }

    private ConnectorResult acknowledge(ConnectorRequest r) {
        X12.Interchange ic = X12.parse(r.required("x12"));
        List<List<X12.Segment>> bodies = new java.util.ArrayList<>();
        for (X12.Group g : ic.groups()) {
            bodies.add(X12Builders.ack999(g, ic.errors()));
        }
        String ack = X12.write(envelope(r, false), "FA", "999", X12Builders.V999, bodies, ic.delimiters());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("x12Ack", ack);
        out.put("accepted", ic.valid());
        out.put("envelopeErrors", ic.errors());
        return ConnectorResult.Success.of(out, ic.controlNumber());
    }

    /** Decodes a response; {@code accepted} false when a TA1/999 rejects or envelope errors exist. */
    static Map<String, Object> decodeOutput(String request, String response) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (request != null) {
            out.put("x12Request", request);
        }
        out.put("x12Response", response);
        X12.Interchange ic = X12.parse(response);
        List<Map<String, Object>> decoded = X12Decoders.decodeAll(ic);
        out.put("decoded", decoded);
        out.put("envelopeErrors", ic.errors());
        out.put("interchangeControlNumber", ic.controlNumber());
        boolean accepted = true;
        for (Map<String, Object> d : decoded) {
            if (Boolean.FALSE.equals(d.get("accepted"))) {
                accepted = false;
            }
        }
        if (!decoded.isEmpty()) {
            decoded.get(0).forEach((k, v) -> {
                if (!(v instanceof List<?>) || k.equals("claims") || k.equals("benefits")) {
                    out.putIfAbsent(k, v);
                }
            });
        }
        out.put("accepted", accepted);
        return HealthHttp.noNulls(out);
    }

    private X12.Envelope envelope(ConnectorRequest r, boolean ack) {
        return new X12.Envelope("ZZ", config.senderId(), "ZZ", config.receiverId(), null, null,
                controlNumber(r.operationId()), config.production(), null, ack);
    }

    private Map<String, String> headers(String mode, String payloadType, String payloadId) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/edi-x12");
        h.put("Accept", "application/edi-x12");
        h.put("X-Payload-ID", payloadId);
        h.put("X-Processing-Mode", mode);
        h.put("X-Payload-Type", payloadType);
        h.put("X-CORE-Rule-Version", "2.2.0");
        if (config.authorization() != null) {
            h.put("Authorization", config.authorization());
        }
        return h;
    }

    private static String trace(ConnectorRequest r) {
        return r.operationId().toString().replace("-", "").substring(0, 30).toUpperCase(java.util.Locale.ROOT);
    }

}
