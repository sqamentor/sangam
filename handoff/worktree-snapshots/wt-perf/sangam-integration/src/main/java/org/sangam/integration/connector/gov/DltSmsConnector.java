// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.connector.gov;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.sim.SimulatorProfile;
import org.sangam.integration.sim.dlt.DltSmsSimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * SMS connector for a DLT-scrubbed government SMS gateway (R60, GOV-10; NIC SMS / Mobile Seva-like JSON shape as
 * accepted by {@link DltSmsSimulator}).
 *
 * <p><b>Operation {@code send}</b>: input {@code templateId}, {@code mobile}, and either {@code message} (the final
 * text) or {@code variables} (list of strings, rendered into the template's {@code {#var#}} placeholders by the
 * {@link DltRegistry}); optional {@code header} (default: the header the template is bound to). The message is
 * validated against the tenant's DLT registry <i>before</i> sending (template, header, content, whitelisted URLs);
 * a validation failure is a {@code BusinessFailure} with the DLT reason code and no call is made (R60 acceptance).
 * The gateway call is {@code POST /dlt/v1/sms} {@code {header, templateId, mobile, message, clientRef, entityId}} with
 * {@code clientRef} = operationId (R36) also sent as {@code Idempotency-Key}. Output {@code messageId}.
 *
 * <p><b>Capability class (c)</b> by default (A-8): an SMS gateway neither de-duplicates nor offers a lookup, so a lost
 * answer is {@code OutcomeUnknown} and goes to reconciliation. A site whose gateway does offer a status query by
 * {@code clientRef} can declare (b) (or (a) when it also de-duplicates by clientRef) together with
 * {@link Config#statusPath()}; the constructor refuses (a)/(b) without a status path, because a declared class must be
 * true (SangamConnector rules).
 */
public final class DltSmsConnector implements SangamConnector {

    public static final String TYPE = "sms-dlt";
    public static final String OP_SEND = "send";
    public static final String SMS_PATH = "/dlt/v1/sms";

    private static final Logger LOG = LoggerFactory.getLogger(DltSmsConnector.class);
    private static final String SYSTEM = "SMS gateway";

    /**
     * Configuration of one gateway account.
     *
     * @param id              connector instance id
     * @param baseUri         gateway base URI
     * @param registry        per-tenant DLT registry used for validation and rendering
     * @param apiKeyRef       gateway API key reference (sent as bearer token); null = none
     * @param secrets         resolver for the key reference
     * @param transport       HTTP transport
     * @param profile         live or simulator
     * @param capabilityClass A-2 class; null = (c)
     * @param statusPath      status query path ({@code GET <path>?clientRef=}); required for classes (a)/(b)
     * @param timeout         per-request timeout; null = 15 s
     */
    public record Config(String id, URI baseUri, DltRegistry registry, String apiKeyRef, SecretResolver secrets,
                         HttpTransport transport, ConnectorProfile profile, CapabilityClass capabilityClass,
                         String statusPath, Duration timeout) {
        public Config {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(baseUri, "baseUri");
            Objects.requireNonNull(registry, "registry");
            Objects.requireNonNull(transport, "transport");
            secrets = secrets == null ? SecretResolver.fromEnvironment() : secrets;
            profile = profile == null ? ConnectorProfile.LIVE : profile;
            capabilityClass = capabilityClass == null ? CapabilityClass.C_NEITHER : capabilityClass;
            if (capabilityClass != CapabilityClass.C_NEITHER && (statusPath == null || statusPath.isBlank())) {
                throw new IllegalArgumentException("capability class " + capabilityClass
                        + " needs a statusPath: the gateway must offer an outcome lookup");
            }
            timeout = timeout == null ? Duration.ofSeconds(15) : timeout;
        }
    }

    private final Config config;
    private final ConnectorDescriptor descriptor;

    public DltSmsConnector(Config config) {
        this.config = Objects.requireNonNull(config, "config");
        this.descriptor = new ConnectorDescriptor(config.id(), TYPE, config.capabilityClass(), List.of(OP_SEND),
                config.profile());
    }

    /**
     * Wired to the DLT SMS simulator of {@code sims}, class (c). Every template and whitelisted URL of
     * {@code tenantId} in {@code registry} is registered in the simulator, so the gateway scrubs with the same rules.
     */
    public static DltSmsConnector simulator(SimulatorProfile sims, DltRegistry registry, String tenantId) {
        DltSmsSimulator sim = sims.dlt();
        registry.find(tenantId).ifPresent(t -> {
            for (DltRegistry.Template tpl : t.templates()) {
                sim.registerTemplate(tpl.id(), tpl.header(), tpl.body());
            }
            for (String url : t.urlWhitelist()) {
                sim.whitelistUrl(url);
            }
        });
        return new DltSmsConnector(new Config("sms-dlt-sim", sim.baseUri(), registry, null, SecretResolver.of(Map.of()),
                HttpTransport.forSimulator(), ConnectorProfile.SIMULATOR, CapabilityClass.C_NEITHER, null,
                Duration.ofSeconds(10)));
    }

    /** Wired to the DLT SMS simulator with the registrations of every tenant in {@code registry}. */
    public static DltSmsConnector simulator(SimulatorProfile sims, DltRegistry registry) {
        DltSmsConnector c = null;
        for (String tenant : registry.tenantIds()) {
            c = simulator(sims, registry, tenant);
        }
        return c != null ? c : simulator(sims, registry, "*");
    }

    public Config config() {
        return config;
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ConnectorResult invoke(ConnectorRequest request) {
        if (!OP_SEND.equals(request.operation())) {
            return ConnectorResult.BusinessFailure.of("UNSUPPORTED_OPERATION",
                    "SMS supports only '" + OP_SEND + "', got '" + request.operation() + "'");
        }
        String templateId = request.str("templateId");
        String mobile = normaliseMobile(request.str("mobile"));
        if (templateId == null || templateId.isBlank() || mobile == null) {
            return ConnectorResult.BusinessFailure.of("BAD_REQUEST", "templateId and mobile are required");
        }
        String header = request.str("header", config.registry().headerOf(request.tenantId(), templateId));
        String message = request.str("message");
        if (message == null) {
            try {
                message = config.registry().render(request.tenantId(), templateId, variables(request));
            } catch (DltRegistry.ValidationException e) {
                return validationFailure(e.reason(), e.getMessage());
            }
        }
        String reason = config.registry().validate(request.tenantId(), header, templateId, mobile, message);
        if (reason != null) {
            return validationFailure(reason, "message refused by DLT validation before sending");
        }
        try {
            return send(request, header, templateId, mobile, message);
        } catch (RuntimeException e) {
            LOG.warn("SMS for operation {} failed unexpectedly", request.operationId(), e);
            return ConnectorResult.OutcomeUnknown.of("SMS: unexpected " + e.getClass().getSimpleName() + ": "
                    + e.getMessage());
        }
    }

    private ConnectorResult send(ConnectorRequest request, String header, String templateId, String mobile,
                                 String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("header", header);
        body.put("templateId", templateId);
        body.put("mobile", mobile);
        body.put("message", message);
        body.put("clientRef", request.operationId().toString());
        config.registry().find(request.tenantId()).map(t -> t.entityId())
                .ifPresent(e -> body.put("entityId", e));
        boolean idempotent = config.capabilityClass() == CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP;
        Map<String, String> headers;
        try {
            headers = headers(request);
        } catch (IllegalStateException e) {
            return ConnectorResult.TransientFailure.noEffect("SMS gateway: " + e.getMessage());
        }
        HttpTransport.Call call = new HttpTransport.Call("POST", GovHttp.join(config.baseUri(), SMS_PATH),
                headers, Json.bytes(body), GovHttp.timeout(config.timeout(), request), idempotent);
        HttpTransport.Outcome o = config.transport().execute(request.tenantId(), call, GovHttp.deadline(request));
        if (!(o instanceof HttpTransport.Outcome.Response r) || !r.is2xx()) {
            return GovHttp.failure(SYSTEM, o, idempotent);
        }
        JsonNode n = Json.tree(r.body());
        if (n == null || !n.isObject()) {
            return ConnectorResult.OutcomeUnknown.of("SMS gateway: unreadable answer (HTTP " + r.status() + ")");
        }
        String status = GovHttp.text(n, "status");
        String messageId = GovHttp.text(n, "messageId");
        if ("ACCEPTED".equals(status) && messageId != null && !messageId.isBlank()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("messageId", messageId);
            out.put("status", status);
            out.put("header", header);
            out.put("templateId", templateId);
            return ConnectorResult.Success.of(out, messageId);
        }
        if ("REJECTED".equals(status)) {
            String reason = GovHttp.text(n, "reason");
            return validationFailure(reason == null ? "REJECTED" : reason, "gateway rejected the message");
        }
        return ConnectorResult.OutcomeUnknown.of("SMS gateway: unexpected status " + status);
    }

    @Override
    public LookupResult lookup(ConnectorRequest request) {
        if (config.capabilityClass() == CapabilityClass.C_NEITHER) {
            return new LookupResult.NotSupported();
        }
        try {
            URI uri = URI.create(GovHttp.join(config.baseUri(), config.statusPath()) + "?clientRef="
                    + URLEncoder.encode(request.operationId().toString(), StandardCharsets.UTF_8));
            Map<String, String> headers = headers(request);
            headers.remove("Content-Type");
            HttpTransport.Call call = new HttpTransport.Call("GET", uri, headers, null,
                    GovHttp.timeout(config.timeout(), request), true);
            HttpTransport.Outcome o = config.transport().execute(request.tenantId(), call, GovHttp.deadline(request));
            if (!(o instanceof HttpTransport.Outcome.Response r)) {
                return GovHttp.lookupFailure(SYSTEM, o);
            }
            if (r.status() == 404) {
                return new LookupResult.NotFound();
            }
            if (!r.is2xx()) {
                return GovHttp.lookupFailure(SYSTEM, o);
            }
            JsonNode n = Json.tree(r.body());
            if (n == null || !n.isObject()) {
                return new LookupResult.Unavailable("SMS gateway: unreadable status answer");
            }
            String status = String.valueOf(GovHttp.text(n, "status")).toUpperCase(Locale.ROOT);
            Map<String, Object> out = GovHttp.jsonMap(n);
            String messageId = GovHttp.text(n, "messageId");
            return switch (status) {
                case "ACCEPTED", "SENT", "SUBMITTED", "DELIVERED" -> LookupResult.Found.success(out, messageId);
                case "FAILED", "REJECTED", "UNDELIVERED" -> {
                    String reason = GovHttp.text(n, "reason");
                    yield new LookupResult.Found(false, out, messageId, reason == null ? status : reason);
                }
                case "NOT_FOUND" -> new LookupResult.NotFound();
                default -> new LookupResult.Unavailable("SMS gateway status " + status);
            };
        } catch (RuntimeException e) {
            LOG.warn("SMS status for operation {} failed unexpectedly", request.operationId(), e);
            return new LookupResult.Unavailable("SMS: unexpected " + e.getClass().getSimpleName());
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private Map<String, String> headers(ConnectorRequest request) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json");
        h.put("Idempotency-Key", request.operationId().toString());
        if (config.apiKeyRef() != null) {
            h.put("Authorization", "Bearer " + config.secrets().require(config.apiKeyRef()));
        }
        return h;
    }

    private static ConnectorResult validationFailure(String reason, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reason", reason);
        return new ConnectorResult.BusinessFailure(reason, message, out);
    }

    private static List<String> variables(ConnectorRequest request) {
        Object raw = request.input().get("variables");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new DltRegistry.ValidationException("BAD_REQUEST", "variables must be a list");
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            out.add(o == null ? null : o.toString());
        }
        return out;
    }

    /** Strips spaces, dashes and a leading {@code +}; null for blank input. */
    static String normaliseMobile(String mobile) {
        if (mobile == null || mobile.isBlank()) {
            return null;
        }
        String m = mobile.replaceAll("[\\s-]", "");
        return m.startsWith("+") ? m.substring(1) : m;
    }
}
