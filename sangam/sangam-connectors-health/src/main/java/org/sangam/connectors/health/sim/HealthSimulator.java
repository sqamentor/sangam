// SPDX-License-Identifier: Apache-2.0
package org.sangam.connectors.health.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.sangam.connectors.health.fhir.FhirConnector;
import org.sangam.connectors.health.pas.PasConnector;
import org.sangam.connectors.health.x12.X12;
import org.sangam.connectors.health.x12.X12Builders;
import org.sangam.connectors.health.x12.X12Decoders;
import org.sangam.integration.connector.Json;
import org.sangam.integration.sim.EffectsLedger;
import org.sangam.integration.sim.Fault;
import org.sangam.integration.sim.FaultInjector;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-process simulator of a US payer / EHR / clearinghouse for the SIMULATOR profile (ARCHITECTURE §1.9), bound to
 * loopback on a free port. One server, three faces:
 *
 * <ul>
 *   <li><b>FHIR R4</b> at {@code /fhir}: SMART token endpoint ({@code POST /fhir/token}), {@code metadata},
 *       {@code .well-known/smart-configuration}, read, search by {@code identifier} / {@code _id} / {@code patient},
 *       conditional create ({@code If-None-Exist}), update, transaction, {@code Claim/$submit} and
 *       {@code Claim/$inquire} (Da Vinci PAS), {@code Questionnaire/$questionnaire-package} (DTR).</li>
 *   <li><b>CDS Hooks</b> at {@code /cds-services}: one CRD service {@code crd-order-sign}.</li>
 *   <li><b>X12 clearinghouse</b> at {@code /x12}: real-time 270→271, 276→277, 278→278; batch 837→999; status.</li>
 * </ul>
 *
 * Deterministic rules so that packs and tests can steer outcomes:
 * <ul>
 *   <li>PAS / 278: procedure code starting with {@code D} → denied (A3, reason {@code MED-NEC} "not medically
 *       necessary"); starting with {@code P} → pended (A4); otherwise approved (A1) with an authorisation number.</li>
 *   <li>CRD: procedure codes starting with {@code 7} (imaging) → {@code pa-needed=auth-needed} with a DTR
 *       questionnaire; otherwise {@code no-auth}.</li>
 *   <li>271: member id starting with {@code X} → AAA*N**72 (invalid member id); starting with {@code T} → inactive
 *       (EB*6); otherwise active (EB*1) with a deductible.</li>
 *   <li>277: patient control number starting with {@code DEN} → F2 denied; {@code PEN} → P1 pending; else F1 paid.</li>
 * </ul>
 * Faults ({@link #faults()}) apply by request path; every effect is recorded in {@link #ledger()}.
 */
public final class HealthSimulator implements AutoCloseable {

    public static final String TOKEN = "sim-access-token";

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final FaultInjector faults = new FaultInjector();
    private final EffectsLedger ledger = new EffectsLedger();
    private final Map<String, ObjectNode> store = new ConcurrentHashMap<>();   // "Type/id" → resource
    private final Map<String, ObjectNode> pas = new ConcurrentHashMap<>();     // operation identifier → ClaimResponse
    private final Map<String, String> batches = new ConcurrentHashMap<>();     // payloadId → 999
    private final AtomicLong ids = new AtomicLong(1000);
    private volatile boolean requireAuth = true;

    public HealthSimulator() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(executor);
        server.createContext("/", this::dispatch);
    }

    public HealthSimulator start() {
        server.start();
        return this;
    }

    public URI baseUri() {
        InetSocketAddress a = server.getAddress();
        return URI.create("http://" + a.getAddress().getHostAddress() + ":" + a.getPort());
    }

    public URI fhirBase() {
        return URI.create(baseUri() + "/fhir");
    }

    public FaultInjector faults() {
        return faults;
    }

    public EffectsLedger ledger() {
        return ledger;
    }

    public HealthSimulator requireAuth(boolean v) {
        requireAuth = v;
        return this;
    }

    /** Stored ClaimResponse for a PAS operation id (to push a pended → final change in tests). */
    public ObjectNode pasResponse(String operationId) {
        return pas.get(operationId);
    }

    /** Finalises a pended PAS request (A4 → A1 or A3) and returns the updated ClaimResponse for a notification. */
    public ObjectNode finalisePended(String operationId, boolean approve) {
        ObjectNode cr = pas.get(operationId);
        if (cr != null) {
            setReview(cr, approve ? "A1" : "A3", approve ? null : "MED-NEC", approve ? null : "Not medically necessary");
            cr.put("outcome", "complete");
            ledger.record("PAS_FINAL", operationId, Map.of("action", approve ? "A1" : "A3"));
        }
        return cr;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------------------------------------ dispatch

    private record Resp(int status, String type, String body, Map<String, String> headers) {
        static Resp json(int s, Object body) {
            return new Resp(s, "application/fhir+json", body instanceof String str ? str : Json.write(body), Map.of());
        }

        static Resp x12(String body) {
            return new Resp(200, "application/edi-x12", body, Map.of());
        }
    }

    private void dispatch(HttpExchange ex) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getRawQuery();
            String method = ex.getRequestMethod().toUpperCase(java.util.Locale.ROOT);
            byte[] body = ex.getRequestBody().readAllBytes();
            FaultInjector.Active active = faults.next(path);
            active.sleepDelay();
            Fault err = active.error();
            if (err != null) {
                send(ex, Resp.json(err.status(), outcome("transient", "injected fault")));
                return;
            }
            Resp r;
            try {
                r = route(method, path, query, body, ex);
                if (active.duplicate()) {
                    route(method, path, query, body, ex);
                }
            } catch (RuntimeException e) {
                r = Resp.json(500, outcome("exception", String.valueOf(e.getMessage())));
            }
            if (active.dropResponse()) {
                return;
            }
            send(ex, r);
        }
    }

    private Resp route(String method, String path, String query, byte[] body, HttpExchange ex) {
        if (path.startsWith("/fhir")) {
            String rest = path.substring(5);
            if (rest.equals("/token") && method.equals("POST")) {
                return new Resp(200, "application/json", "{\"access_token\":\"" + TOKEN
                        + "\",\"token_type\":\"bearer\",\"expires_in\":300,\"scope\":\"system/*.rs\"}", Map.of());
            }
            if (rest.equals("/metadata")) {
                return Resp.json(200, "{\"resourceType\":\"CapabilityStatement\",\"status\":\"active\",\"fhirVersion\":\"4.0.1\","
                        + "\"kind\":\"instance\",\"format\":[\"json\"]}");
            }
            if (rest.equals("/.well-known/smart-configuration")) {
                return new Resp(200, "application/json", "{\"token_endpoint\":\"" + fhirBase() + "/token\","
                        + "\"token_endpoint_auth_methods_supported\":[\"private_key_jwt\"],"
                        + "\"grant_types_supported\":[\"client_credentials\"]}", Map.of());
            }
            if (requireAuth && !("Bearer " + TOKEN).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                return Resp.json(401, outcome("login", "missing or invalid token"));
            }
            return fhir(method, rest, query, body, ex);
        }
        if (path.equals("/cds-services") && method.equals("GET")) {
            return new Resp(200, "application/json", "{\"services\":[{\"hook\":\"order-sign\",\"id\":\"crd-order-sign\","
                    + "\"title\":\"Coverage requirements (simulated payer)\",\"description\":\"Da Vinci CRD\"}]}", Map.of());
        }
        if (path.startsWith("/cds-services/") && method.equals("POST")) {
            return crd(Json.tree(body));
        }
        if (path.equals("/x12/realtime") && method.equals("POST")) {
            return realtime(new String(body, StandardCharsets.UTF_8));
        }
        if (path.equals("/x12/batch") && method.equals("POST")) {
            return batch(new String(body, StandardCharsets.UTF_8), ex.getRequestHeaders().getFirst("X-Payload-ID"));
        }
        if (path.startsWith("/x12/status/") && method.equals("GET")) {
            String ack = batches.get(path.substring("/x12/status/".length()));
            return ack == null ? new Resp(404, "text/plain", "unknown payload", Map.of()) : Resp.x12(ack);
        }
        return new Resp(404, "text/plain", "not found", Map.of());
    }

    // ------------------------------------------------------------------------------------------------ FHIR

    private Resp fhir(String method, String rest, String query, byte[] body, HttpExchange ex) {
        if (rest.isEmpty() || rest.equals("/")) {
            return method.equals("POST") ? transaction(Json.tree(body)) : Resp.json(405, outcome("not-supported", "base"));
        }
        String[] p = rest.substring(1).split("/");
        String type = p[0];
        if (p.length == 2 && p[1].equals("$submit") && type.equals("Claim")) {
            return pasSubmit(Json.tree(body));
        }
        if (p.length == 2 && p[1].equals("$inquire") && type.equals("Claim")) {
            return pasInquire(Json.tree(body));
        }
        if (p.length == 2 && p[1].equals("$questionnaire-package")) {
            return dtrPackage();
        }
        switch (method) {
            case "GET" -> {
                if (p.length == 2) {
                    ObjectNode r = store.get(type + "/" + p[1]);
                    return r == null ? Resp.json(404, outcome("not-found", type + "/" + p[1])) : Resp.json(200, r);
                }
                return Resp.json(200, searchset(type, params(query)));
            }
            case "POST" -> {
                ObjectNode res = (ObjectNode) Json.tree(body);
                String cond = ex.getRequestHeaders().getFirst("If-None-Exist");
                return create(type, res, cond);
            }
            case "PUT" -> {
                ObjectNode res = (ObjectNode) Json.tree(body);
                String key = type + "/" + p[1];
                ObjectNode prev = store.get(key);
                int v = prev == null ? 1 : prev.path("meta").path("versionId").asInt(1) + 1;
                res.put("id", p[1]);
                res.putObject("meta").put("versionId", String.valueOf(v));
                store.put(key, res);
                ledger.record("FHIR_UPDATE", key, Map.of("version", String.valueOf(v)));
                return Resp.json(prev == null ? 201 : 200, res);
            }
            default -> {
                return Resp.json(405, outcome("not-supported", method));
            }
        }
    }

    private Resp create(String type, ObjectNode res, String cond) {
        if (cond != null) {
            List<ObjectNode> found = search(type, params(cond));
            if (!found.isEmpty()) {
                return Resp.json(200, found.get(0));
            }
        }
        String id = String.valueOf(ids.incrementAndGet());
        res.put("id", id);
        res.putObject("meta").put("versionId", "1");
        store.put(type + "/" + id, res);
        ledger.record("FHIR_CREATE", type + "/" + id, Map.of("type", type));
        return new Resp(201, "application/fhir+json", Json.write(res), Map.of("Location", fhirBase() + "/" + type + "/" + id
                + "/_history/1"));
    }

    private Resp transaction(JsonNode bundle) {
        if (bundle == null || !"Bundle".equals(bundle.path("resourceType").asText())) {
            return Resp.json(400, outcome("invalid", "Bundle expected"));
        }
        ObjectNode out = Json.MAPPER.createObjectNode();
        out.put("resourceType", "Bundle").put("type", bundle.path("type").asText("transaction") + "-response");
        ArrayNode entries = out.putArray("entry");
        for (JsonNode e : bundle.path("entry")) {
            String m = e.path("request").path("method").asText("POST");
            ObjectNode res = e.path("resource").isObject() ? (ObjectNode) e.path("resource").deepCopy() : null;
            String type = res == null ? e.path("request").path("url").asText() : res.path("resourceType").asText();
            Resp r = "PUT".equals(m) && res != null ? putEntry(type, res) : res == null ? Resp.json(400, "{}")
                    : create(type, res, e.path("request").path("ifNoneExist").asText(null));
            JsonNode created = Json.tree(r.body());
            ObjectNode resp = entries.addObject().putObject("response");
            resp.put("status", r.status() + (r.status() == 201 ? " Created" : " OK"));
            if (created != null && created.has("id")) {
                resp.put("location", type + "/" + created.path("id").asText() + "/_history/"
                        + created.path("meta").path("versionId").asText("1"));
            }
        }
        return Resp.json(200, out);
    }

    private Resp putEntry(String type, ObjectNode res) {
        String key = type + "/" + res.path("id").asText();
        res.putObject("meta").put("versionId", "1");
        store.put(key, res);
        return Resp.json(200, res);
    }

    private ObjectNode searchset(String type, Map<String, String> params) {
        List<ObjectNode> found = search(type, params);
        ObjectNode b = Json.MAPPER.createObjectNode();
        b.put("resourceType", "Bundle").put("type", "searchset").put("total", found.size());
        ArrayNode e = b.putArray("entry");
        for (ObjectNode r : found) {
            e.addObject().put("fullUrl", fhirBase() + "/" + type + "/" + r.path("id").asText()).set("resource", r);
        }
        return b;
    }

    private List<ObjectNode> search(String type, Map<String, String> params) {
        List<ObjectNode> out = new ArrayList<>();
        for (Map.Entry<String, ObjectNode> e : store.entrySet()) {
            if (!e.getKey().startsWith(type + "/")) {
                continue;
            }
            ObjectNode r = e.getValue();
            boolean ok = true;
            for (Map.Entry<String, String> q : params.entrySet()) {
                ok &= switch (q.getKey()) {
                    case "identifier" -> hasIdentifier(r, q.getValue());
                    case "_id" -> r.path("id").asText().equals(q.getValue());
                    case "patient", "subject" -> r.toString().contains(q.getValue());
                    case "_count", "_sort" -> true;
                    default -> r.path(q.getKey()).asText("").equalsIgnoreCase(q.getValue());
                };
            }
            if (ok) {
                out.add(r);
            }
        }
        return out;
    }

    private static boolean hasIdentifier(JsonNode r, String token) {
        int bar = token.indexOf('|');
        String sys = bar < 0 ? null : token.substring(0, bar);
        String val = bar < 0 ? token : token.substring(bar + 1);
        for (JsonNode i : r.path("identifier")) {
            if (val.equals(i.path("value").asText()) && (sys == null || sys.equals(i.path("system").asText()))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> params(String query) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        if (query == null) {
            return m;
        }
        for (String kv : query.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) {
                m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
                        URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return m;
    }

    // ------------------------------------------------------------------------------------------------ PAS / DTR / CRD

    private Resp pasSubmit(JsonNode bundle) {
        JsonNode claim = firstOfType(bundle, "Claim");
        if (claim == null) {
            return Resp.json(400, outcome("invalid", "PAS bundle without Claim"));
        }
        String op = opId(claim);
        if ("cancelled".equals(claim.path("status").asText()) && op != null && pas.containsKey(op)) {
            ObjectNode cr = pas.get(op);
            cr.put("status", "cancelled");
            ledger.record("PAS_CANCEL", op, Map.of());
            return Resp.json(200, wrap(cr));
        }
        String code = claim.path("item").path(0).path("productOrService").path("coding").path(0).path("code").asText("");
        ObjectNode cr = Json.MAPPER.createObjectNode();
        cr.put("resourceType", "ClaimResponse").put("id", "cr-" + ids.incrementAndGet()).put("status", "active");
        cr.put("use", "preauthorization").put("created", java.time.Instant.now().toString());
        cr.putObject("request").putObject("identifier").put("system", FhirConnector.OP_SYSTEM)
                .put("value", op == null ? "" : op);
        cr.putArray("identifier").addObject().put("system", FhirConnector.OP_SYSTEM).put("value", op == null ? "" : op);
        cr.putArray("item").addObject().put("itemSequence", 1);
        if (code.startsWith("D")) {
            cr.put("outcome", "complete");
            setReview(cr, "A3", "MED-NEC", "Not medically necessary");
        } else if (code.startsWith("P")) {
            cr.put("outcome", "queued");
            setReview(cr, "A4", null, null);
        } else {
            cr.put("outcome", "complete");
            setReview(cr, "A1", null, null);
            cr.put("preAuthRef", "AUTH-" + ids.incrementAndGet());
        }
        if (op != null) {
            ObjectNode prev = pas.putIfAbsent(op, cr);
            if (prev != null) {
                return Resp.json(200, wrap(prev));
            }
        }
        ledger.record("PAS_SUBMIT", String.valueOf(op), Map.of("code", code));
        return Resp.json(200, wrap(cr));
    }

    private Resp pasInquire(JsonNode bundle) {
        JsonNode claim = firstOfType(bundle, "Claim");
        String op = claim == null ? null : opId(claim);
        ObjectNode cr = op == null ? null : pas.get(op);
        ObjectNode b = Json.MAPPER.createObjectNode();
        b.put("resourceType", "Bundle").put("type", "collection");
        ArrayNode e = b.putArray("entry");
        if (cr != null) {
            e.addObject().set("resource", cr);
        }
        return Resp.json(200, b);
    }

    private static void setReview(ObjectNode cr, String action, String reason, String text) {
        ObjectNode item = (ObjectNode) cr.path("item").path(0);
        ArrayNode exts = item.putArray("extension");
        ObjectNode ra = exts.addObject().put("url", PasConnector.EXT_REVIEW_ACTION);
        ArrayNode sub = ra.putArray("extension");
        sub.addObject().put("url", PasConnector.EXT_REVIEW_CODE).putObject("valueCodeableConcept").putArray("coding")
                .addObject().put("system", "https://codesystem.x12.org/005010/306").put("code", action);
        if (reason != null) {
            sub.addObject().put("url", "reasonCode").putObject("valueCodeableConcept").putArray("coding").addObject()
                    .put("system", "https://codesystem.x12.org/external/886").put("code", reason).put("display", text);
        }
        if ("A1".equals(action)) {
            sub.addObject().put("url", "number").put("valueString", "AUTH-" + cr.path("id").asText());
        }
    }

    private static ObjectNode wrap(ObjectNode cr) {
        ObjectNode b = Json.MAPPER.createObjectNode();
        b.put("resourceType", "Bundle").put("type", "collection");
        b.putArray("entry").addObject().set("resource", cr);
        return b;
    }

    private Resp dtrPackage() {
        ObjectNode q = Json.MAPPER.createObjectNode();
        q.put("resourceType", "Questionnaire").put("id", "imaging-necessity").put("status", "active")
                .put("url", "http://payer.example/Questionnaire/imaging-necessity").put("title", "Imaging medical necessity");
        ArrayNode items = q.putArray("item");
        items.addObject().put("linkId", "1").put("text", "Conservative therapy tried for 6 weeks?").put("type", "boolean");
        items.addObject().put("linkId", "2").put("text", "Red-flag symptoms present?").put("type", "boolean");
        ObjectNode out = Json.MAPPER.createObjectNode();
        out.put("resourceType", "Parameters");
        ObjectNode pkg = out.putArray("parameter").addObject().put("name", "PackageBundle").putObject("resource");
        pkg.put("resourceType", "Bundle").put("type", "collection");
        pkg.putArray("entry").addObject().set("resource", q);
        return Resp.json(200, out);
    }

    private Resp crd(JsonNode req) {
        String code = req == null ? "" : req.findValues("code").stream().map(JsonNode::asText)
                .filter(s -> s.matches("[0-9A-Z]{5}")).findFirst().orElse("");
        boolean pa = code.startsWith("7") || code.startsWith("D") || code.startsWith("P");
        ObjectNode resp = Json.MAPPER.createObjectNode();
        resp.putArray("cards").add(org.sangam.connectors.health.cdshooks.CdsHooksService.card(
                pa ? "Prior authorization required" : "No prior authorization required", pa ? "warning" : "info",
                null, "Simulated payer"));
        ObjectNode action = resp.putArray("systemActions").addObject();
        action.put("type", "update").put("description", "coverage information");
        ObjectNode res = action.putObject("resource");
        res.put("resourceType", "ServiceRequest").put("id", "order-1");
        ArrayNode subs = res.putArray("extension").addObject().put("url",
                org.sangam.connectors.health.cdshooks.CdsHooksConnector.EXT_COVERAGE_INFO).putArray("extension");
        subs.addObject().put("url", "covered").put("valueCode", "covered");
        subs.addObject().put("url", "pa-needed").put("valueCode", pa ? "auth-needed" : "no-auth");
        if (pa) {
            subs.addObject().put("url", "doc-needed").put("valueCode", "clinical");
            subs.addObject().put("url", "questionnaire").put("valueCanonical",
                    "http://payer.example/Questionnaire/imaging-necessity");
        }
        return new Resp(200, "application/json", Json.write(resp), Map.of());
    }

    // ------------------------------------------------------------------------------------------------ X12

    private Resp realtime(String text) {
        X12.Interchange ic = X12.parse(text);
        X12.Transaction t = ic.transactions().isEmpty() ? null : ic.transactions().get(0);
        X12.Envelope env = new X12.Envelope("ZZ", ic.receiver(), "ZZ", ic.sender(), null, null,
                Long.parseLong(ic.controlNumber().trim()), false, null, false);
        if (t == null || !ic.valid()) {
            return Resp.x12(X12.write(env, "FA", "999", X12Builders.V999, ic.groups().isEmpty() ? List.of()
                    : List.of(X12Builders.ack999(ic.groups().get(0), ic.errors().isEmpty() ? List.of("no transaction")
                    : ic.errors())), null));
        }
        List<X12.Segment> out = new ArrayList<>();
        String trace = find(t, "TRN", 2);
        switch (t.type()) {
            case "270" -> {
                String member = findNm1(t, "IL", 9);
                out.add(X12.Segment.of("BHT", "0022", "11", trace, "20260101", "1200"));
                out.add(X12.Segment.of("HL", "1", "", "20", "1"));
                out.add(X12.Segment.of("NM1", "PR", "2", "SIM PAYER", "", "", "", "", "PI", "SIMPAYER"));
                out.add(X12.Segment.of("HL", "2", "1", "21", "1"));
                out.add(X12.Segment.of("NM1", "1P", "2", "PROVIDER", "", "", "", "", "XX", findNm1(t, "1P", 9)));
                out.add(X12.Segment.of("HL", "3", "2", "22", "0"));
                out.add(X12.Segment.of("TRN", "2", trace, "9SIMPAYER"));
                out.add(X12.Segment.of("NM1", "IL", "1", findNm1(t, "IL", 3), findNm1(t, "IL", 4), "", "", "", "MI", member));
                if (member.startsWith("X")) {
                    out.add(X12.Segment.of("AAA", "N", "", "72", "C"));
                } else if (member.startsWith("T")) {
                    out.add(X12.Segment.of("EB", "6", "IND", "30"));
                } else {
                    out.add(X12.Segment.of("EB", "1", "IND", "30", "PR", "SIM GOLD PPO"));
                    out.add(X12.Segment.of("EB", "C", "IND", "30", "", "", "23", "1500", "", "", "", "", "Y"));
                    out.add(X12.Segment.of("EB", "A", "IND", "30", "", "", "27", "", "0.2", "", "", "", "Y"));
                }
                return Resp.x12(X12.write(env, "HB", "271", X12Builders.V270, List.of(out), null));
            }
            case "276" -> {
                String pcn = find(t, "REF", 2);
                String cat = pcn.startsWith("DEN") ? "F2" : pcn.startsWith("PEN") ? "P1" : "F1";
                out.add(X12.Segment.of("BHT", "0010", "08", trace, "20260101", "1200", "DG"));
                out.add(X12.Segment.of("HL", "1", "", "20", "1"));
                out.add(X12.Segment.of("NM1", "PR", "2", "SIM PAYER", "", "", "", "", "PI", "SIMPAYER"));
                out.add(X12.Segment.of("HL", "2", "1", "21", "1"));
                out.add(X12.Segment.of("HL", "3", "2", "19", "1"));
                out.add(X12.Segment.of("HL", "4", "3", "22", "0"));
                out.add(X12.Segment.of("TRN", "2", trace));
                out.add(X12.Segment.of("STC", cat + ":" + ("F2".equals(cat) ? "88" : "1") + ":PR", "20260101", "",
                        find(t, "AMT", 2), "F1".equals(cat) ? find(t, "AMT", 2) : "0"));
                out.add(X12.Segment.of("REF", "1K", "PCN" + Math.abs(pcn.hashCode())));
                out.add(X12.Segment.of("REF", "EJ", pcn));
                return Resp.x12(X12.write(env, "HN", "277", X12Builders.V276, List.of(out), null));
            }
            case "278" -> {
                String code = "";
                for (X12.Segment s : t.segments()) {
                    if ("SV1".equals(s.id())) {
                        code = s.comp(1, 2, ic.delimiters());
                    }
                }
                String action = code.startsWith("D") ? "A3" : code.startsWith("P") ? "A4" : "A1";
                out.add(X12.Segment.of("BHT", "0007", "11", trace, "20260101", "1200"));
                out.add(X12.Segment.of("HL", "1", "", "20", "1"));
                out.add(X12.Segment.of("NM1", "X3", "2", "SIM PAYER", "", "", "", "", "PI", "SIMPAYER"));
                out.add(X12.Segment.of("HL", "2", "1", "21", "1"));
                out.add(X12.Segment.of("HL", "3", "2", "22", "1"));
                out.add(X12.Segment.of("HL", "4", "3", "EV", "0"));
                out.add(X12.Segment.of("TRN", "2", trace, "9SIMPAYER"));
                out.add(X12.Segment.of("HCR", action, "A1".equals(action) ? "CERT" + Math.abs(trace.hashCode()) : "",
                        "A3".equals(action) ? "MED-NEC" : ""));
                ledger.record("X12_278", trace, Map.of("action", action));
                return Resp.x12(X12.write(env, "HI", "278", X12Builders.V278, List.of(out), null));
            }
            default -> {
                return Resp.x12(X12.write(env, "FA", "999", X12Builders.V999,
                        List.of(X12Builders.ack999(ic.groups().get(0), List.of("unsupported " + t.type()))), null));
            }
        }
    }

    private Resp batch(String text, String payloadId) {
        X12.Interchange ic = X12.parse(text);
        X12.Envelope env = new X12.Envelope("ZZ", ic.receiver(), "ZZ", ic.sender(), null, null,
                Long.parseLong(ic.controlNumber().trim()), false, null, false);
        String ack = X12.write(env, "FA", "999", X12Builders.V999, ic.groups().isEmpty() ? List.of()
                : List.of(X12Builders.ack999(ic.groups().get(0), ic.errors())), null);
        if (payloadId != null) {
            String prev = batches.putIfAbsent(payloadId, ack);
            if (prev != null) {
                return Resp.x12(prev);
            }
        }
        for (X12.Transaction t : ic.transactions()) {
            ledger.record("X12_837", find(t, "CLM", 1), Map.of("payloadId", String.valueOf(payloadId)));
        }
        return Resp.x12(ack);
    }

    /**
     * An 835 for one claim, for tests and the claim pack simulator: {@code paid} = charge − contractual (CO-45) −
     * patient responsibility (PR-1); paid 0 with CO-50 makes a denial (CLP02 = 4).
     */
    public static String remittance835(String patientControlNumber, String charge, String paid, String patientResp,
                                       boolean denied) {
        java.math.BigDecimal c = new java.math.BigDecimal(charge);
        java.math.BigDecimal p = denied ? java.math.BigDecimal.ZERO : new java.math.BigDecimal(paid);
        java.math.BigDecimal pr = denied ? java.math.BigDecimal.ZERO : new java.math.BigDecimal(patientResp);
        java.math.BigDecimal co = c.subtract(p).subtract(pr);
        List<X12.Segment> b = new ArrayList<>();
        b.add(X12.Segment.of("BPR", "I", p.toPlainString(), "C", "ACH", "CCP", "01", "999999999", "DA", "123456",
                "1999999999", "", "01", "888888888", "DA", "654321", "20260115"));
        b.add(X12.Segment.of("TRN", "1", "EFT" + Math.abs(patientControlNumber.hashCode()), "1999999999"));
        b.add(X12.Segment.of("N1", "PR", "SIM PAYER"));
        b.add(X12.Segment.of("N1", "PE", "SIM PROVIDER", "XX", "1234567893"));
        b.add(X12.Segment.of("LX", "1"));
        b.add(X12.Segment.of("CLP", patientControlNumber, denied ? "4" : "1", c.toPlainString(), p.toPlainString(),
                pr.toPlainString(), "12", "PCN" + Math.abs(patientControlNumber.hashCode())));
        if (denied) {
            b.add(X12.Segment.of("CAS", "CO", "50", c.toPlainString()));
            b.add(X12.Segment.of("LQ", "HE", "N115"));
        } else {
            if (co.signum() != 0) {
                b.add(X12.Segment.of("CAS", "CO", "45", co.toPlainString()));
            }
            if (pr.signum() != 0) {
                b.add(X12.Segment.of("CAS", "PR", "1", pr.toPlainString()));
            }
        }
        return X12.write(new X12.Envelope("ZZ", "SIMPAYER", "ZZ", "SANGAM", null, null, 835, false, null, false),
                "HP", "835", X12Builders.V835, List.of(b), null);
    }

    /** Decodes an 835 (convenience for tests). */
    public static Map<String, Object> decode835(String x12) {
        X12.Interchange ic = X12.parse(x12);
        return X12Decoders.decode(ic.transactions().get(0), ic.delimiters());
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static String find(X12.Transaction t, String seg, int el) {
        for (X12.Segment s : t.segments()) {
            if (seg.equals(s.id())) {
                return s.el(el);
            }
        }
        return "";
    }

    private static String findNm1(X12.Transaction t, String entity, int el) {
        for (X12.Segment s : t.segments()) {
            if ("NM1".equals(s.id()) && entity.equals(s.el(1))) {
                return s.el(el);
            }
        }
        return "";
    }

    private static JsonNode firstOfType(JsonNode bundle, String type) {
        if (bundle == null) {
            return null;
        }
        for (JsonNode e : bundle.path("entry")) {
            if (type.equals(e.path("resource").path("resourceType").asText())) {
                return e.path("resource");
            }
        }
        return null;
    }

    private static String opId(JsonNode claim) {
        for (JsonNode i : claim.path("identifier")) {
            if (FhirConnector.OP_SYSTEM.equals(i.path("system").asText())) {
                return i.path("value").asText();
            }
        }
        return null;
    }

    private static String outcome(String code, String diag) {
        return "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"" + code
                + "\",\"diagnostics\":\"" + diag.replace("\"", "'") + "\"}]}";
    }

    private static void send(HttpExchange ex, Resp r) throws IOException {
        ex.getResponseHeaders().set("Content-Type", r.type());
        r.headers().forEach((k, v) -> ex.getResponseHeaders().set(k, v));
        byte[] b = r.body().getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(r.status(), b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        }
    }
}
