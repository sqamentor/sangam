// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * M-017: the roll-up and gateway contracts ({@code api/sangam-rollup-api.yaml}, {@code api/sangam-gateway-api.yaml}) cannot
 * drift from the code. Those modules have no test dependencies, so this test reads their sources: the roll-up's
 * mappings, query parameters and response keys, and the gateway's problem types, statuses and header handling. The
 * control plane has its own reflection test ({@code ControlPlaneContractTest}).
 */
class AppContractDriftTest {

    private static final Path ROLLUP_SRC = Path.of("..", "sangam-rollup", "src", "main", "java", "org", "sangam", "rollup",
            "RollupController.java");
    private static final Path GATEWAY_SRC = Path.of("..", "sangam-gateway", "src", "main", "java", "org", "sangam", "gateway");
    private static final Pattern MAPPING = Pattern.compile(
            "@(Get|Post|Put|Delete|Patch)Mapping\\(\"([^\"]+)\"\\)\\s*public\\s+[^(]+\\(([^)]*)\\)");
    private static final Pattern REQUEST_PARAM = Pattern.compile("@RequestParam(?:\\([^)]*\\))?\\s+[\\w<>]+\\s+(\\w+)");
    private static final Pattern RESPONSE_KEY = Pattern.compile("(?:Map\\.of\\(|\\.put\\(|,\\s*)\"(\\w+)\",");

    // ---------------------------------------------------------------- roll-up

    @Test
    @SuppressWarnings("unchecked")
    void rollupContractMatchesTheController() throws IOException {
        String src = Files.readString(ROLLUP_SRC, StandardCharsets.UTF_8);
        assertTrue(src.contains("@RequestMapping(\"/api/v1\")"), "RollupController base path");
        Map<String, Object> doc = read(Path.of("api", "sangam-rollup-api.yaml"));
        Map<String, Map<String, Object>> contract = operations(doc);

        Map<String, Handler> code = new TreeMap<>();
        Matcher m = MAPPING.matcher(src);
        int previous = -1;
        String previousKey = null;
        while (m.find()) {
            if (previousKey != null) {
                code.get(previousKey).body = src.substring(previous, m.start());
            }
            Handler h = new Handler();
            Matcher p = REQUEST_PARAM.matcher(m.group(3));
            while (p.find()) {
                h.queryParams.add(p.group(1));
            }
            previousKey = m.group(1).toUpperCase() + " " + m.group(2);
            code.put(previousKey, h);
            previous = m.end();
        }
        code.get(previousKey).body = src.substring(previous);
        assertEquals(new TreeSet<>(code.keySet()), new TreeSet<>(contract.keySet()), "roll-up operations");

        for (var e : code.entrySet()) {
            Map<String, Object> op = contract.get(e.getKey());
            Set<String> contractQuery = new TreeSet<>();
            for (Object o : (List<Object>) op.getOrDefault("parameters", List.of())) {
                Map<String, Object> prm = (Map<String, Object>) o;
                if ("query".equals(prm.get("in"))) {
                    contractQuery.add((String) prm.get("name"));
                }
            }
            assertEquals(e.getValue().queryParams, contractQuery, e.getKey() + ": query parameters");

            Set<String> keys = new TreeSet<>();
            Matcher k = RESPONSE_KEY.matcher(e.getValue().body);
            while (k.find()) {
                keys.add(k.group(1));
            }
            Map<String, Object> success = successSchema(doc, op);
            assertNotNull(success, e.getKey() + ": success response schema");
            // the handler builds its response maps (top level and rows) with Map.of / put: compare every key it writes
            // with the properties of the response schema and of the row schemas it references
            Set<String> contractKeys = new TreeSet<>();
            for (var prop : ((Map<String, Object>) success.get("properties")).entrySet()) {
                contractKeys.add(prop.getKey());
                Map<String, Object> ps = (Map<String, Object>) prop.getValue();
                Map<String, Object> row = "array".equals(ps.get("type")) ? (Map<String, Object>) ps.get("items") : ps;
                if (row.containsKey("$ref")) {
                    contractKeys.addAll(((Map<String, Object>) resolve(doc, row).get("properties")).keySet());
                }
            }
            assertEquals(keys, contractKeys, e.getKey() + ": response keys");
        }
    }

    // ---------------------------------------------------------------- gateway

    @Test
    @SuppressWarnings("unchecked")
    void gatewayContractMatchesTheProxy() throws IOException {
        String proxy = Files.readString(GATEWAY_SRC.resolve("CellProxy.java"), StandardCharsets.UTF_8);
        Map<String, Object> doc = read(Path.of("api", "sangam-gateway-api.yaml"));

        // problem types and statuses: one entry per CellProxy.problem(HttpStatus.X, "<type>" ..., requestId, ...) call
        Map<String, Integer> code = new TreeMap<>();
        String[] calls = proxy.split("problem\\(HttpStatus\\.");
        Pattern slug = Pattern.compile("\"([a-z]+(?:-[a-z]+)+)\"");
        for (int i = 1; i < calls.length; i++) {
            String call = calls[i];
            int end = call.indexOf("requestId");
            if (end < 0) {
                continue;
            }
            int status = HttpStatus.valueOf(call.substring(0, call.indexOf(','))).value();
            Matcher s = slug.matcher(call.substring(0, end));
            while (s.find()) {
                code.put(s.group(1), status);
            }
        }
        Map<String, Integer> contract = new TreeMap<>();
        for (Object o : (List<Object>) doc.get("x-sangam-gateway-problems")) {
            Map<String, Object> p = (Map<String, Object>) o;
            contract.put((String) p.get("type"), (Integer) p.get("status"));
        }
        assertEquals(code, contract, "gateway problem types and statuses");
        Map<String, Object> responses = (Map<String, Object>) ((Map<String, Object>) doc.get("components")).get("responses");
        for (int status : new TreeSet<>(code.values())) {
            assertTrue(responses.containsKey("Gateway" + status), "components.responses.Gateway" + status);
        }
        assertTrue(proxy.contains("\"https://sangam.org/problems/\" + type"), "problem type URI prefix");

        // headers set on the upstream request, and hop-by-hop headers dropped
        Set<String> upstream = new TreeSet<>();
        Matcher set = Pattern.compile("out\\.set\\(\"([\\w-]+)\"").matcher(proxy);
        while (set.find()) {
            upstream.add(set.group(1));
        }
        assertEquals(upstream, new TreeSet<>(((Map<String, Object>) doc.get("x-sangam-upstream-headers")).keySet()),
                "headers the gateway sets upstream");
        Matcher hop = Pattern.compile("HOP_BY_HOP = Set\\.of\\(([^)]*)\\)").matcher(proxy);
        assertTrue(hop.find(), "CellProxy.HOP_BY_HOP");
        Set<String> dropped = new TreeSet<>();
        Matcher lit = Pattern.compile("\"([\\w-]+)\"").matcher(hop.group(1));
        while (lit.find()) {
            dropped.add(lit.group(1));
        }
        assertEquals(dropped, new TreeSet<>((List<String>) doc.get("x-sangam-dropped-headers")), "dropped headers");

        // the catch-all route leaves the actuator out, and the tenant header default is the contract's parameter
        String routes = Files.readString(GATEWAY_SRC.resolve("GatewayRoutes.java"), StandardCharsets.UTF_8);
        assertTrue(routes.contains("RequestPredicates.path(\"/actuator/**\").negate()"), "catch-all route");
        String props = Files.readString(GATEWAY_SRC.resolve("GatewayProperties.java"), StandardCharsets.UTF_8);
        Map<String, Object> params = (Map<String, Object>) ((Map<String, Object>) doc.get("components")).get("parameters");
        String header = (String) ((Map<String, Object>) params.get("TenantHeader")).get("name");
        assertTrue(props.contains("tenantHeader == null ? \"" + header + "\""), "tenant header default " + header);
    }

    // ---------------------------------------------------------------- helpers

    private static final class Handler {
        final Set<String> queryParams = new TreeSet<>();
        String body = "";
    }

    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Object>> operations(Map<String, Object> doc) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        ((Map<String, Object>) doc.get("paths")).forEach((path, item) -> ((Map<String, Object>) item).forEach(
                (method, op) -> out.put(method.toUpperCase() + " " + path, (Map<String, Object>) op)));
        return out;
    }

    /** The resolved schema of the 2xx JSON response; for an array, its item schema. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> successSchema(Map<String, Object> doc, Map<String, Object> op) {
        for (var r : ((Map<String, Object>) op.get("responses")).entrySet()) {
            if (!r.getKey().startsWith("2") || !(r.getValue() instanceof Map<?, ?> resp)
                    || !(resp.get("content") instanceof Map<?, ?> content)) {
                continue;
            }
            Map<String, Object> s = (Map<String, Object>) ((Map<String, Object>) content.get("application/json")).get("schema");
            if ("array".equals(s.get("type"))) {
                s = (Map<String, Object>) s.get("items");
            }
            return resolve(doc, s);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> resolve(Map<String, Object> doc, Map<String, Object> s) {
        if (s.get("$ref") instanceof String ref) {
            String name = ref.substring(ref.lastIndexOf('/') + 1);
            return (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) doc.get("components"))
                    .get("schemas")).get(name);
        }
        return s;
    }

    static Map<String, Object> read(Path p) throws IOException {
        LoaderOptions o = new LoaderOptions();
        o.setCodePointLimit(64 * 1024 * 1024);
        try (InputStream in = Files.newInputStream(p)) {
            return new Yaml(new SafeConstructor(o)).load(in);
        }
    }
}
