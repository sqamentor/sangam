// SPDX-License-Identifier: Apache-2.0
package org.sangam.connectors.health.cdshooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.sangam.integration.connector.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Server side of CDS Hooks 2.0 (US-HLT-074): lets a Sangam tenant act as a CDS service (for example a payer running
 * CRD rules as DMN, or a provider organisation exposing its own checks to the EHR). Framework-neutral: the REST
 * layer maps {@code GET /cds-services} to {@link #discovery()} and {@code POST /cds-services/{id}} to
 * {@link #handle(String, byte[], String)}.
 *
 * <p>A {@link Handler} is typically a DMN evaluation (the integrator binds {@code DecisionDelegate}-style evaluation
 * per service id); it returns cards and system actions. JWT verification of the caller is done before
 * {@link #handle} by the REST layer with the client's JWKS (the {@code authorizedClient} argument is the verified
 * {@code iss}).
 */
public final class CdsHooksService {

    /** One hook implementation. */
    @FunctionalInterface
    public interface Handler {
        /** @return {@code {cards:[…], systemActions:[…]}} */
        JsonNode handle(JsonNode request, String authorizedClient);
    }

    /** Discovery entry. */
    public record Service(String id, String hook, String title, String description, Map<String, String> prefetch,
                          Handler handler) {
        public Service {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(hook, "hook");
            Objects.requireNonNull(handler, "handler");
            prefetch = prefetch == null ? Map.of() : Map.copyOf(prefetch);
        }
    }

    /** HTTP status and JSON body to answer. */
    public record Reply(int status, String json) {
    }

    private final Map<String, Service> services = new LinkedHashMap<>();

    public CdsHooksService register(Service s) {
        services.put(s.id(), s);
        return this;
    }

    public Reply discovery() {
        ObjectNode root = Json.MAPPER.createObjectNode();
        ArrayNode arr = root.putArray("services");
        for (Service s : services.values()) {
            ObjectNode o = arr.addObject();
            o.put("hook", s.hook()).put("id", s.id()).put("title", s.title()).put("description", s.description());
            if (!s.prefetch().isEmpty()) {
                ObjectNode p = o.putObject("prefetch");
                s.prefetch().forEach(p::put);
            }
        }
        return new Reply(200, Json.write(root));
    }

    public Reply handle(String serviceId, byte[] body, String authorizedClient) {
        Service s = services.get(serviceId);
        if (s == null) {
            return new Reply(404, "{\"error\":\"unknown service\"}");
        }
        JsonNode req = Json.tree(body);
        if (req == null || !req.path("hook").isTextual() || !req.path("hookInstance").isTextual()
                || !req.path("context").isObject()) {
            return new Reply(400, "{\"error\":\"hook, hookInstance and context are required\"}");
        }
        if (!s.hook().equals(req.path("hook").asText())) {
            return new Reply(400, "{\"error\":\"service does not implement hook " + req.path("hook").asText()
                    .replace("\"", "") + "\"}");
        }
        JsonNode resp;
        try {
            resp = s.handler().handle(req, authorizedClient);
        } catch (RuntimeException e) {
            // CDS Hooks: a service failure must not block the clinician; answer with no cards
            return new Reply(200, "{\"cards\":[]}");
        }
        ObjectNode out = Json.MAPPER.createObjectNode();
        out.set("cards", resp != null && resp.has("cards") ? resp.get("cards") : Json.MAPPER.createArrayNode());
        if (resp != null && resp.has("systemActions")) {
            out.set("systemActions", resp.get("systemActions"));
        }
        return new Reply(200, Json.write(out));
    }

    /** Helper for handlers: one information card. */
    public static ObjectNode card(String summary, String indicator, String detail, String sourceLabel) {
        ObjectNode c = Json.MAPPER.createObjectNode();
        c.put("uuid", java.util.UUID.randomUUID().toString());
        c.put("summary", summary.length() > 140 ? summary.substring(0, 140) : summary);
        c.put("indicator", indicator == null ? "info" : indicator);
        if (detail != null) {
            c.put("detail", detail);
        }
        c.putObject("source").put("label", sourceLabel == null ? "Sangam" : sourceLabel);
        return c;
    }

    public List<String> serviceIds() {
        return List.copyOf(services.keySet());
    }
}
