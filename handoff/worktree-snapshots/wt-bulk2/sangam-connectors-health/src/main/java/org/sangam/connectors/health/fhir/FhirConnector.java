// SPDX-License-Identifier: Apache-2.0
package org.sangam.connectors.health.fhir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.sangam.connectors.health.HealthHttp;
import org.sangam.core.integration.ConnectorDescriptor;
import org.sangam.core.integration.ConnectorProfile;
import org.sangam.core.integration.ConnectorRequest;
import org.sangam.core.integration.ConnectorResult;
import org.sangam.core.integration.LookupResult;
import org.sangam.core.integration.SangamConnector;
import org.sangam.core.layer.CapabilityClass;
import org.sangam.integration.connector.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Generic FHIR R4 connector (US-HLT-075…079) on {@link FhirClient}.
 *
 * <p>Operations (input keys in brackets):
 * <ul>
 *   <li>{@code read} [{@code type}, {@code id}] and {@code search} [{@code type}, {@code params} map,
 *       {@code maxPages}]: no effect.</li>
 *   <li>{@code create} [{@code resource}]: <b>conditional create</b>. The connector adds the identifier
 *       {@code urn:sangam:operation|<operationId>} and sends {@code If-None-Exist} with it, so a repeat finds the first
 *       resource instead of creating a second one (class a). Lookup searches by that identifier.</li>
 *   <li>{@code update} [{@code resource}, {@code ifMatch}]: PUT is idempotent by definition (class a).</li>
 *   <li>{@code transaction} [{@code bundle}]: each POST entry gets an operation identifier
 *       {@code <operationId>-<index>} and an {@code ifNoneExist}, so the whole transaction is idempotent (class a).
 *       Lookup searches the first POST entry by its identifier.</li>
 *   <li>{@code operation} [{@code path}, {@code parameters}, {@code idempotent}]: a FHIR operation (for example
 *       {@code Patient/$everything}, {@code $export}); class (c) unless the caller declares it idempotent.</li>
 * </ul>
 * The resource, bundle and parameters may be given as a map or as a JSON string. Outputs: {@code status},
 * {@code resource} (or {@code bundle}), {@code id}, {@code versionId}, {@code location}, {@code total}.
 *
 * <p>Reuse for India: the ABDM HIP/HIU bundles and the NHCX claim bundles are FHIR R4, so the same connector serves
 * them with a different {@link FhirClient.Config} (gateway base URI, client-secret session token, {@code X-CM-ID}
 * header). See the module README.
 */
public final class FhirConnector implements SangamConnector {

    public static final String TYPE = "fhir-r4";
    public static final String OP_SYSTEM = "urn:sangam:operation";
    public static final List<String> OPERATIONS = List.of("read", "search", "create", "update", "transaction",
            "operation");

    private final FhirClient client;
    private final ConnectorDescriptor descriptor;
    private final String system;

    public FhirConnector(String id, FhirClient client, ConnectorProfile profile) {
        this.client = Objects.requireNonNull(client, "client");
        this.descriptor = new ConnectorDescriptor(id, TYPE, CapabilityClass.A_IDEMPOTENT_WITH_LOOKUP, OPERATIONS,
                profile);
        this.system = "FHIR " + id;
    }

    public FhirClient client() {
        return client;
    }

    @Override
    public ConnectorDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ConnectorResult invoke(ConnectorRequest r) {
        String rid = r.operationId().toString();
        long dl = HealthHttp.deadline(r);
        try {
            return switch (r.operation()) {
                case "read" -> single(client.read(r.tenantId(), r.required("type"), r.required("id"), rid, dl), false);
                case "search" -> search(r, rid, dl);
                case "create" -> {
                    ObjectNode res = object(r.input().get("resource"), "resource");
                    tagOperation(res, rid);
                    yield single(client.create(r.tenantId(), res, "identifier=" + OP_SYSTEM + "|" + rid, rid, dl), true);
                }
                case "update" -> single(client.update(r.tenantId(), object(r.input().get("resource"), "resource"),
                        r.str("ifMatch"), rid, dl), true);
                case "transaction" -> {
                    ObjectNode b = object(r.input().get("bundle"), "bundle");
                    makeIdempotent(b, rid);
                    yield bundle(client.transaction(r.tenantId(), b, rid, dl, true), true);
                }
                case "operation" -> {
                    boolean idem = Boolean.parseBoolean(r.str("idempotent", "false"));
                    Object p = r.input().get("parameters");
                    JsonNode params = p == null ? null : object(p, "parameters");
                    FhirClient.Answer a = client.operation(r.tenantId(), r.required("path"), params, rid, dl, idem);
                    yield a.body() != null && "Bundle".equals(a.body().path("resourceType").asText())
                            ? bundle(a, idem) : single(a, idem);
                }
                default -> ConnectorResult.BusinessFailure.of("UNSUPPORTED_OPERATION", r.operation());
            };
        } catch (IllegalArgumentException e) {
            return ConnectorResult.BusinessFailure.of("INVALID_INPUT", e.getMessage());
        }
    }

    @Override
    public LookupResult lookup(ConnectorRequest r) {
        String rid = r.operationId().toString();
        String type;
        String value = rid;
        try {
            switch (r.operation()) {
                case "create" -> type = object(r.input().get("resource"), "resource").path("resourceType").asText();
                case "transaction" -> {
                    type = firstPostType(object(r.input().get("bundle"), "bundle"));
                    value = rid + "-0";
                }
                case "read", "search", "update" -> {
                    return new LookupResult.NotFound(); // repeat is harmless (no effect / idempotent PUT)
                }
                default -> {
                    return new LookupResult.NotSupported();
                }
            }
        } catch (IllegalArgumentException e) {
            return new LookupResult.Unavailable(e.getMessage());
        }
        if (type == null || type.isBlank()) {
            return new LookupResult.NotFound();
        }
        FhirClient.Answer a = client.search(r.tenantId(), type, Map.of("identifier", OP_SYSTEM + "|" + value), rid,
                HealthHttp.deadline(r));
        if (!a.ok() || a.body() == null) {
            return HealthHttp.lookupFailure(system, a.outcome());
        }
        List<JsonNode> found = FhirClient.entries(a.body());
        if (found.isEmpty()) {
            return new LookupResult.NotFound();
        }
        JsonNode res = found.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("resource", HealthHttp.map(res));
        out.put("id", res.path("id").asText());
        out.put("versionId", res.path("meta").path("versionId").asText(""));
        return LookupResult.Found.success(out, res.path("resourceType").asText() + "/" + res.path("id").asText());
    }

    @Override
    public boolean healthy() {
        return client.capabilities("health-probe", 0).ok();
    }

    private ConnectorResult search(ConnectorRequest r, String rid, long dl) {
        Map<String, Object> raw = r.map("params");
        Map<String, String> params = new LinkedHashMap<>();
        raw.forEach((k, v) -> params.put(k, String.valueOf(v)));
        int maxPages = Integer.parseInt(r.str("maxPages", "1"));
        FhirClient.Answer a = client.search(r.tenantId(), r.required("type"), params, rid, dl);
        if (!a.ok() || a.body() == null) {
            return HealthHttp.failure(system, a.outcome(), true);
        }
        ArrayNode all = Json.MAPPER.createArrayNode();
        JsonNode page = a.body();
        int pages = 1;
        while (true) {
            FhirClient.entries(page).forEach(all::add);
            if (pages >= maxPages) {
                break;
            }
            FhirClient.Answer n = client.next(r.tenantId(), page, rid, dl);
            if (n == null || !n.ok() || n.body() == null) {
                break;
            }
            page = n.body();
            pages++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", a.status());
        out.put("total", a.body().path("total").asInt(all.size()));
        out.put("resources", Json.MAPPER.convertValue(all, List.class));
        out.put("count", all.size());
        return ConnectorResult.Success.of(HealthHttp.noNulls(out), null);
    }

    private ConnectorResult single(FhirClient.Answer a, boolean idem) {
        if (!a.ok()) {
            return HealthHttp.failure(system, a.outcome(), idem);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", a.status());
        String location = a.header("Location");
        if (location != null) {
            out.put("location", location);
        }
        String ref = location;
        if (a.body() != null) {
            out.put("resource", HealthHttp.map(a.body()));
            out.put("id", a.body().path("id").asText(""));
            out.put("versionId", a.body().path("meta").path("versionId").asText(""));
            if (ref == null && a.body().has("id")) {
                ref = a.body().path("resourceType").asText() + "/" + a.body().path("id").asText();
            }
        }
        // 200 to a conditional create = the resource already existed (repeat of the same operation)
        return new ConnectorResult.Success(HealthHttp.noNulls(out), ref, a.status() == 200 && idem);
    }

    private ConnectorResult bundle(FhirClient.Answer a, boolean idem) {
        if (!a.ok() || a.body() == null) {
            return HealthHttp.failure(system, a.outcome(), idem);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", a.status());
        out.put("bundle", HealthHttp.map(a.body()));
        java.util.ArrayList<String> locations = new java.util.ArrayList<>();
        for (JsonNode e : a.body().path("entry")) {
            String loc = e.path("response").path("location").asText(null);
            if (loc != null) {
                locations.add(loc);
            }
        }
        out.put("locations", locations);
        return ConnectorResult.Success.of(out, a.body().path("id").asText(null));
    }

    static ObjectNode object(Object v, String name) {
        JsonNode n = v instanceof String s ? Json.tree(s) : v == null ? null : Json.MAPPER.valueToTree(v);
        if (n == null || !n.isObject() || !n.has("resourceType")) {
            throw new IllegalArgumentException("connector input '" + name + "' must be a FHIR resource");
        }
        return (ObjectNode) n.deepCopy();
    }

    static void tagOperation(ObjectNode res, String value) {
        ArrayNode ids = res.has("identifier") && res.get("identifier").isArray() ? (ArrayNode) res.get("identifier")
                : res.putArray("identifier");
        for (JsonNode i : ids) {
            if (OP_SYSTEM.equals(i.path("system").asText())) {
                return;
            }
        }
        ids.addObject().put("system", OP_SYSTEM).put("value", value);
    }

    static void makeIdempotent(ObjectNode bundle, String rid) {
        int i = 0;
        for (JsonNode e : bundle.path("entry")) {
            if (e instanceof ObjectNode en && "POST".equalsIgnoreCase(en.path("request").path("method").asText())
                    && en.get("resource") instanceof ObjectNode res) {
                String v = rid + "-" + i++;
                tagOperation(res, v);
                ((ObjectNode) en.get("request")).put("ifNoneExist", "identifier=" + OP_SYSTEM + "|" + v);
            }
        }
    }

    private static String firstPostType(JsonNode bundle) {
        for (JsonNode e : bundle.path("entry")) {
            if ("POST".equalsIgnoreCase(e.path("request").path("method").asText())) {
                return e.path("resource").path("resourceType").asText();
            }
        }
        return null;
    }
}
