// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SG-39 / M-113 (owner decision Q-09) and M-091: the chart values are validated against {@code values.schema.json}
 * the way helm does it (the chart defaults coalesced with the user values, then the draft-07 schema). helm is not in
 * the unit-test build (ci-build.yml deploy-lint runs {@code helm lint --strict} on every example), so the test carries
 * a small draft-07 evaluator for exactly the keywords the chart schema uses; {@link #schemaUsesOnlyTheKeywordsThisEvaluatorImplements}
 * fails as soon as the schema uses another keyword, so no constraint is ever skipped silently.
 *
 * <p>Private ingress: with {@code ingress.private.enabled=true} the public {@code ingress.className} is refused, an
 * internal class is required, user annotations cannot select a public load balancer, GKE's public class {@code gce} is
 * refused, and each cloud preset (AWS, Azure, GCP) is pinned to its internal-load-balancer annotation.
 */
class HelmValuesSchemaTest {

    private static final Path CHART = Path.of("..", "deploy", "helm", "sangam");

    // ------------------------------------------------------------------------------------------------ fixtures

    private static Map<String, Object> schema() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> s = new ObjectMapper().readValue(Files.readString(CHART.resolve("values.schema.json")), Map.class);
        return s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml(String text) {
        Object v = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        return v == null ? new LinkedHashMap<>() : (Map<String, Object>) v;
    }

    private static Map<String, Object> defaults() throws IOException {
        return yaml(Files.readString(CHART.resolve("values.yaml")));
    }

    /** helm's coalesce: maps merge key by key, any other value replaces, a null removes the key. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> coalesce(Map<String, Object> base, Map<String, Object> over) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> e : over.entrySet()) {
            Object b = out.get(e.getKey());
            if (e.getValue() == null) {
                out.remove(e.getKey());
            } else if (e.getValue() instanceof Map<?, ?> m && b instanceof Map<?, ?> bm) {
                out.put(e.getKey(), coalesce((Map<String, Object>) bm, (Map<String, Object>) m));
            } else {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private static List<String> errors(String overrideYaml) throws IOException {
        return new MiniSchema(schema()).validate(coalesce(defaults(), yaml(overrideYaml)));
    }

    private static void accepted(String name, String overrideYaml) throws IOException {
        List<String> e = errors(overrideYaml);
        assertTrue(e.isEmpty(), name + " is accepted, got " + e);
    }

    /** Refused, and refused at {@code where} (a path fragment), so the case fails for the intended reason. */
    private static void refused(String name, String overrideYaml, String where) throws IOException {
        List<String> e = errors(overrideYaml);
        assertFalse(e.isEmpty(), name + " is refused");
        assertTrue(e.stream().anyMatch(m -> m.contains(where)), name + " is refused at " + where + ", got " + e);
    }

    // ------------------------------------------------------------------------------------------------ tests

    @Test
    void schemaUsesOnlyTheKeywordsThisEvaluatorImplements() throws IOException {
        Set<String> used = new TreeSet<>();
        MiniSchema.keywords(schema(), used);
        Set<String> unknown = new TreeSet<>(used);
        unknown.removeAll(MiniSchema.KEYWORDS);
        assertTrue(unknown.isEmpty(), "keywords the test evaluator does not implement: " + unknown);
        assertEquals("https://json-schema.org/draft-07/schema#", schema().get("$schema"), "draft-07, as evaluated here");
    }

    @Test
    void chartDefaultsAndEveryExampleValidate() throws IOException {
        MiniSchema s = new MiniSchema(schema());
        Map<String, Object> defaults = defaults();
        assertEquals(List.of(), s.validate(defaults), "values.yaml");
        Map<String, Object> postgres = yaml(Files.readString(CHART.resolve("examples/values-postgres.yaml")));
        try (var files = Files.list(CHART.resolve("examples"))) {
            for (Path f : files.filter(p -> p.getFileName().toString().matches("values-.*\\.yaml")).sorted().toList()) {
                // ci-build.yml: the air-gap file is an overlay used with the postgres example.
                Map<String, Object> base = f.getFileName().toString().contains("airgap") ? coalesce(defaults, postgres) : defaults;
                assertEquals(List.of(), s.validate(coalesce(base, yaml(Files.readString(f)))), f.getFileName().toString());
            }
        }
    }

    @Test
    void theEvaluatorRefusesWhatTheSchemaAlreadyRefused() throws IOException {
        refused("an unknown database type", "database: { type: db2 }", "$.database.type");
        // B24 (M-055) kept: server.jobExecutor.exclusiveOverProcessHierarchies is a boolean.
        refused("a non-boolean jobExecutor flag", "server: { jobExecutor: { exclusiveOverProcessHierarchies: 'yes' } }",
                "$.server.jobExecutor.exclusiveOverProcessHierarchies");
        refused("a missing required section", "database: null", "database");
    }

    @Test
    void privateIngressIsOffByDefaultAndThePublicIngressIsUnchanged() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> ingress = (Map<String, Object>) defaults().get("ingress");
        assertEquals(false, ingress.get("enabled"), "no ingress by default (no public inbound)");
        @SuppressWarnings("unchecked")
        Map<String, Object> priv = (Map<String, Object>) ingress.get("private");
        assertEquals(false, priv.get("enabled"));
        assertEquals("", priv.get("className"));
        assertEquals("", priv.get("preset"));
        accepted("a public ingress with a public class", "ingress: { enabled: true, className: nginx }");
    }

    @Test
    void privateIngressWithEachPresetOrAnInternalClassIsAccepted() throws IOException {
        for (String preset : List.of("aws", "azure", "gcp")) {
            accepted("preset " + preset, "ingress: { enabled: true, private: { enabled: true, preset: " + preset + " } }");
        }
        accepted("an own internal class without a preset",
                "ingress: { enabled: true, private: { enabled: true, className: nginx-internal } }");
        accepted("AWS with an own IngressClass name",
                "ingress: { enabled: true, private: { enabled: true, preset: aws, className: alb-internal } }");
        accepted("user annotations that agree with the preset", """
                ingress:
                  enabled: true
                  annotations: { alb.ingress.kubernetes.io/scheme: internal, alb.ingress.kubernetes.io/target-type: ip }
                  private: { enabled: true, preset: aws }
                """);
    }

    @Test
    void aPublicClassOrAPublicLoadBalancerWithPrivateTrueIsRefused() throws IOException {
        refused("the public ingress.className with private=true",
                "ingress: { enabled: true, className: nginx, private: { enabled: true, className: nginx-internal } }",
                "$.ingress.className");
        refused("private=true without any internal class", "ingress: { enabled: true, private: { enabled: true } }",
                "$.ingress.private.className");
        refused("GKE's public class gce as the private class",
                "ingress: { enabled: true, private: { enabled: true, className: gce } }", "$.ingress.private.className");
        refused("a className with the GCP preset (GKE takes the class from the annotation only)",
                "ingress: { enabled: true, private: { enabled: true, preset: gcp, className: gce-internal } }",
                "$.ingress.private.className");
        refused("private=true with the ingress itself disabled", "ingress: { private: { enabled: true, preset: aws } }",
                "$.ingress.enabled");
        refused("an internet-facing ALB annotation", """
                ingress:
                  enabled: true
                  annotations: { alb.ingress.kubernetes.io/scheme: internet-facing }
                  private: { enabled: true, preset: aws }
                """, "alb.ingress.kubernetes.io/scheme");
        refused("an Application Gateway public IP", """
                ingress:
                  enabled: true
                  annotations: { appgw.ingress.kubernetes.io/use-private-ip: 'false' }
                  private: { enabled: true, preset: azure }
                """, "appgw.ingress.kubernetes.io/use-private-ip");
        refused("the gce class through the legacy annotation", """
                ingress:
                  enabled: true
                  annotations: { kubernetes.io/ingress.class: gce }
                  private: { enabled: true, className: lb-internal }
                """, "kubernetes.io/ingress.class");
        refused("an unknown preset", "ingress: { enabled: true, private: { enabled: true, preset: oci } }",
                "$.ingress.private.preset");
        refused("an unknown private key", "ingress: { private: { scheme: internal } }", "$.ingress.private");
    }

    @Test
    void thePresetsArePinnedToTheirInternalLoadBalancer() throws IOException {
        refused("AWS preset made internet-facing",
                "ingress: { private: { presets: { aws: { annotations: { alb.ingress.kubernetes.io/scheme: internet-facing } } } } }",
                "$.ingress.private.presets.aws.annotations");
        refused("Azure preset made public",
                "ingress: { private: { presets: { azure: { annotations: { appgw.ingress.kubernetes.io/use-private-ip: 'false' } } } } }",
                "$.ingress.private.presets.azure.annotations");
        refused("GCP preset made external",
                "ingress: { private: { presets: { gcp: { annotations: { kubernetes.io/ingress.class: gce } } } } }",
                "$.ingress.private.presets.gcp.annotations");
        refused("GCP preset with an ingressClassName", "ingress: { private: { presets: { gcp: { className: gce-internal } } } }",
                "$.ingress.private.presets.gcp.className");
        refused("a preset removed", "ingress: { private: { presets: { aws: null } } }", "$.ingress.private.presets");
        refused("a non-string annotation value (Kubernetes annotations are strings)",
                "ingress: { annotations: { appgw.ingress.kubernetes.io/use-private-ip: true } }",
                "$.ingress.annotations.appgw.ingress.kubernetes.io/use-private-ip");
    }

    @Test
    void prometheusRuleValues() throws IOException {
        accepted("PrometheusRule on, with ruleSelector labels",
                "metrics: { prometheusRule: { enabled: true, labels: { release: kube-prometheus-stack } } }");
        accepted("a narrowed cert-manager selector", "metrics: { prometheusRule: { certManager: { selector: 'namespace=\"sangam\"' } } }");
        refused("a zero root-lock threshold", "metrics: { prometheusRule: { thresholds: { rootLockWaitSeconds: 0 } } }",
                "$.metrics.prometheusRule.thresholds.rootLockWaitSeconds");
        refused("a non-integer outbox age", "metrics: { prometheusRule: { thresholds: { outboxOldestAgeSeconds: 5m } } }",
                "$.metrics.prometheusRule.thresholds.outboxOldestAgeSeconds");
        refused("an unknown threshold", "metrics: { prometheusRule: { thresholds: { parked: 1 } } }",
                "$.metrics.prometheusRule.thresholds");
        refused("a selector that would break the rule's quoting",
                "metrics: { prometheusRule: { certManager: { selector: \"namespace='sangam'\" } } }",
                "$.metrics.prometheusRule.certManager.selector");
    }

    /** M-054: SANGAM_JOB_VIRTUAL_THREADS through the chart is a boolean. */
    @Test
    void jobVirtualThreadsIsABoolean() throws IOException {
        accepted("virtual threads on", "server: { jobExecutor: { virtualThreads: true } }");
        refused("a string flag", "server: { jobExecutor: { virtualThreads: 'true' } }", "$.server.jobExecutor.virtualThreads");
    }

    /** networkPolicy.ingressFrom.cidrs: a list of IPv4/IPv6 CIDRs (NetworkPolicy ipBlock.cidr), empty by default. */
    @Test
    void ingressFromCidrs() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> np = (Map<String, Object>) defaults().get("networkPolicy");
        assertEquals(Map.of("cidrs", List.of()), np.get("ingressFrom"), "none by default");
        accepted("IPv4 and IPv6 CIDRs", "networkPolicy: { ingressFrom: { cidrs: [10.0.0.0/16, 10.1.2.3/32, 'fd00::/8', '2001:db8::/32'] } }");
        refused("an address without a prefix", "networkPolicy: { ingressFrom: { cidrs: [10.0.0.0] } }",
                "$.networkPolicy.ingressFrom.cidrs[0]");
        refused("an IPv4 prefix above 32", "networkPolicy: { ingressFrom: { cidrs: [10.0.0.0/16, 10.0.0.0/33] } }",
                "$.networkPolicy.ingressFrom.cidrs[1]");
        refused("a host name", "networkPolicy: { ingressFrom: { cidrs: [alb.example/24] } }", "$.networkPolicy.ingressFrom.cidrs[0]");
        refused("a single string instead of a list", "networkPolicy: { ingressFrom: { cidrs: 10.0.0.0/16 } }",
                "$.networkPolicy.ingressFrom.cidrs");
        refused("an unknown key", "networkPolicy: { ingressFrom: { except: [10.0.0.1/32] } }", "$.networkPolicy.ingressFrom");
    }

    /** M-091 follow-up thresholds: capacity ratio in (0, 1], parked count, relay heartbeat seconds. */
    @Test
    void outboxAndRelayThresholds() throws IOException {
        accepted("an earlier capacity alert", "metrics: { prometheusRule: { thresholds: { outboxCapacityRatio: 0.4 } } }");
        refused("a capacity ratio above 1", "metrics: { prometheusRule: { thresholds: { outboxCapacityRatio: 1.5 } } }",
                "$.metrics.prometheusRule.thresholds.outboxCapacityRatio");
        refused("a zero capacity ratio", "metrics: { prometheusRule: { thresholds: { outboxCapacityRatio: 0 } } }",
                "$.metrics.prometheusRule.thresholds.outboxCapacityRatio");
        refused("a negative parked count", "metrics: { prometheusRule: { thresholds: { outboxParked: -1 } } }",
                "$.metrics.prometheusRule.thresholds.outboxParked");
        refused("a zero relay heartbeat", "metrics: { prometheusRule: { thresholds: { relayLastCycleAgeSeconds: 0 } } }",
                "$.metrics.prometheusRule.thresholds.relayLastCycleAgeSeconds");
    }

    // ------------------------------------------------------------------------------------------------ evaluator

    /** Draft-07 subset: exactly {@link #KEYWORDS}. Errors are "path: reason". */
    static final class MiniSchema {

        static final Set<String> KEYWORDS = Set.of("$schema", "$comment", "title", "description", "definitions", "$ref",
                "type", "properties", "required", "additionalProperties", "enum", "const", "minimum", "maximum",
                "exclusiveMinimum", "minLength", "pattern", "allOf", "anyOf", "not", "if", "then", "else", "items", "minItems");

        private final Map<String, Object> root;

        MiniSchema(Map<String, Object> root) {
            this.root = root;
        }

        List<String> validate(Object instance) {
            List<String> errors = new ArrayList<>();
            check(root, instance, "$", errors);
            return errors;
        }

        /** Every keyword used anywhere in the schema (property and definition names are not keywords). */
        @SuppressWarnings("unchecked")
        static void keywords(Object schema, Set<String> out) {
            if (!(schema instanceof Map<?, ?> m)) {
                return;
            }
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
                out.add(e.getKey());
                switch (e.getKey()) {
                    case "properties", "definitions" -> ((Map<String, Object>) e.getValue()).values().forEach(v -> keywords(v, out));
                    case "allOf", "anyOf" -> ((List<Object>) e.getValue()).forEach(v -> keywords(v, out));
                    case "enum", "const", "required", "$schema", "$comment", "title", "description", "type", "$ref",
                         "minimum", "maximum", "exclusiveMinimum", "minLength", "pattern", "minItems" -> { }
                    default -> keywords(e.getValue(), out); // additionalProperties, items, not, if, then, else
                }
            }
        }

        @SuppressWarnings("unchecked")
        private void check(Object schemaNode, Object v, String path, List<String> errors) {
            if (schemaNode instanceof Boolean b) {
                if (!b) {
                    errors.add(path + ": not allowed");
                }
                return;
            }
            Map<String, Object> s = (Map<String, Object>) schemaNode;
            if (s.containsKey("$ref")) { // draft-07: the siblings of $ref are ignored
                check(resolve((String) s.get("$ref")), v, path, errors);
                return;
            }
            // draft-07: "type" is one name or a list of names, any of which may match
            Object type = s.get("type");
            if (type != null && (type instanceof List<?> names ? names : List.of(type)).stream()
                    .noneMatch(t -> typeMatches((String) t, v))) {
                errors.add(path + ": expected type " + s.get("type") + ", got " + v);
                return;
            }
            if (s.containsKey("enum") && ((List<Object>) s.get("enum")).stream().noneMatch(x -> same(x, v))) {
                errors.add(path + ": " + v + " is not one of " + s.get("enum"));
            }
            if (s.containsKey("const") && !same(s.get("const"), v)) {
                errors.add(path + ": expected " + s.get("const") + ", got " + v);
            }
            if (v instanceof Number n && !(v instanceof Boolean)) {
                BigDecimal d = dec(n);
                if (s.containsKey("minimum") && d.compareTo(dec((Number) s.get("minimum"))) < 0) {
                    errors.add(path + ": " + v + " < minimum " + s.get("minimum"));
                }
                if (s.containsKey("maximum") && d.compareTo(dec((Number) s.get("maximum"))) > 0) {
                    errors.add(path + ": " + v + " > maximum " + s.get("maximum"));
                }
                if (s.containsKey("exclusiveMinimum") && d.compareTo(dec((Number) s.get("exclusiveMinimum"))) <= 0) {
                    errors.add(path + ": " + v + " <= exclusiveMinimum " + s.get("exclusiveMinimum"));
                }
            }
            if (v instanceof String str) {
                if (s.containsKey("minLength") && str.codePointCount(0, str.length()) < ((Number) s.get("minLength")).intValue()) {
                    errors.add(path + ": shorter than " + s.get("minLength"));
                }
                if (s.containsKey("pattern") && !Pattern.compile((String) s.get("pattern")).matcher(str).find()) {
                    errors.add(path + ": does not match " + s.get("pattern"));
                }
            }
            if (v instanceof Map<?, ?> obj) {
                Map<String, Object> o = (Map<String, Object>) obj;
                for (Object r : (List<Object>) s.getOrDefault("required", List.of())) {
                    if (!o.containsKey((String) r)) {
                        errors.add(path + ": required property " + r + " is missing");
                    }
                }
                Map<String, Object> props = (Map<String, Object>) s.getOrDefault("properties", Map.of());
                for (Map.Entry<String, Object> e : o.entrySet()) {
                    String child = path + "." + e.getKey();
                    if (props.containsKey(e.getKey())) {
                        check(props.get(e.getKey()), e.getValue(), child, errors);
                    } else if (s.containsKey("additionalProperties")) {
                        check(s.get("additionalProperties"), e.getValue(), child, errors);
                    }
                }
            }
            if (v instanceof List<?> list && s.containsKey("minItems") && list.size() < ((Number) s.get("minItems")).intValue()) {
                errors.add(path + ": fewer than " + s.get("minItems") + " items");
            }
            if (v instanceof List<?> list && s.containsKey("items")) {
                for (int i = 0; i < list.size(); i++) {
                    check(s.get("items"), list.get(i), path + "[" + i + "]", errors);
                }
            }
            for (Object sub : (List<Object>) s.getOrDefault("allOf", List.of())) {
                check(sub, v, path, errors);
            }
            if (s.containsKey("anyOf")) {
                boolean any = false;
                for (Object sub : (List<Object>) s.get("anyOf")) {
                    List<String> inner = new ArrayList<>();
                    check(sub, v, path, inner);
                    any |= inner.isEmpty();
                }
                if (!any) {
                    errors.add(path + ": matches none of anyOf");
                }
            }
            if (s.containsKey("not")) {
                List<String> inner = new ArrayList<>();
                check(s.get("not"), v, path, inner);
                if (inner.isEmpty()) {
                    errors.add(path + ": " + v + " matches a schema it must not match");
                }
            }
            if (s.containsKey("if")) {
                List<String> cond = new ArrayList<>();
                check(s.get("if"), v, path, cond);
                Object branch = cond.isEmpty() ? s.get("then") : s.get("else");
                if (branch != null) {
                    check(branch, v, path, errors);
                }
            }
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> resolve(String ref) {
            assertTrue(ref.startsWith("#/definitions/"), "only local definitions are used: " + ref);
            Map<String, Object> defs = (Map<String, Object>) root.get("definitions");
            Object target = defs.get(ref.substring("#/definitions/".length()));
            assertTrue(target instanceof Map, "definition exists: " + ref);
            return (Map<String, Object>) target;
        }

        private static boolean typeMatches(String type, Object v) {
            return switch (type) {
                case "object" -> v instanceof Map;
                case "array" -> v instanceof List;
                case "string" -> v instanceof String;
                case "boolean" -> v instanceof Boolean;
                case "integer" -> v instanceof Integer || v instanceof Long || v instanceof BigInteger
                        || (v instanceof Double d && d == Math.rint(d) && !d.isInfinite());
                case "number" -> v instanceof Number;
                case "null" -> v == null;
                default -> throw new AssertionError("unknown type " + type);
            };
        }

        private static boolean same(Object a, Object b) {
            if (a instanceof Number x && b instanceof Number y) {
                return dec(x).compareTo(dec(y)) == 0;
            }
            return Objects.equals(a, b);
        }

        private static BigDecimal dec(Number n) {
            return new BigDecimal(n.toString());
        }
    }
}
