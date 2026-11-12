// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.deploy;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M-091: the Sangam alert rules. The compose observability profile loads
 * {@code deploy/compose/observability/rules/sangam-alerts.yml}; the chart renders the same rules as a PrometheusRule
 * ({@code templates/prometheusrule.yaml}) with the thresholds of {@code metrics.prometheusRule.thresholds}. helm and
 * promtool are not in the unit-test build, so this test checks the texts:
 *
 * <ol>
 *   <li>Every metric a rule reads is one Sangam registers (Micrometer name in the Java source, rendered by the
 *       Prometheus naming convention: dots to underscores, a seconds base unit as {@code _seconds}, a timer's
 *       {@code _max}), or the scrape's {@code up}, or cert-manager's expiry gauge (chart only).</li>
 *   <li>The chart template, with the values.yaml thresholds and the compose job selector substituted, has exactly the
 *       compose rules, plus the cert-manager rule.</li>
 *   <li>prometheus.yml loads the rules directory and scrapes the job the rules select; the chart's selector is the job
 *       the ServiceMonitor produces (the {@code -server-management} Service).</li>
 * </ol>
 */
class PrometheusAlertRulesTraceTest {

    private static final Path DEPLOY = Path.of("..", "deploy");
    private static final Path CHART = DEPLOY.resolve(Path.of("helm", "sangam"));
    private static final Path COMPOSE_RULES = DEPLOY.resolve(Path.of("compose", "observability", "rules", "sangam-alerts.yml"));
    private static final Path SERVER_MAIN = Path.of("src", "main", "java", "org", "sangam", "server");
    private static final String COMPOSE_SELECTOR = "job=\"sangam-server\"";
    private static final String CERT_RULE = "SangamCertificateExpiring";

    /** Prometheus series name -> {source file, Micrometer meter name}. */
    private static final Map<String, String[]> SANGAM_METRICS = Map.of(
            "sangam_outbox_oldest_age_seconds", new String[] {"metrics/SangamMetrics.java", "sangam.outbox.oldest.age"},
            "sangam_incidents_open", new String[] {"metrics/SangamMetrics.java", "sangam.incidents.open"},
            "sangam_reconciliation_items", new String[] {"metrics/SangamMetrics.java", "sangam.reconciliation.items"},
            "sangam_outbox_capacity_used_ratio", new String[] {"metrics/SangamMetrics.java", "sangam.outbox.capacity.used.ratio"},
            "sangam_outbox_parked", new String[] {"metrics/SangamMetrics.java", "sangam.outbox.parked"},
            "sangam_relay_last_cycle_age_seconds", new String[] {"metrics/SangamMetrics.java", "sangam.relay.last.cycle.age"},
            "sangam_engine_root_lock_wait_seconds_max", new String[] {"bridge/EngineMicrometerMetrics.java",
                    "sangam.engine.root.lock.wait"});

    /**
     * The series Micrometer's Prometheus naming gives the meter registered by the first {@code Gauge|Timer.builder("name"
     * ... .register(registry)} chain in {@code source}: dots to underscores; a gauge with {@code .baseUnit("seconds")} gets
     * {@code _seconds}; a timer is exposed in seconds and its maximum as {@code _seconds_max}.
     */
    private static String prometheusName(String source, String meter) {
        // Up to the statement's .register(registry): descriptions may contain ';'.
        Matcher m = Pattern.compile("(Gauge|Timer)\\.builder\\(\"" + Pattern.quote(meter) + "\".*?\\.register\\(registry\\)",
                Pattern.DOTALL).matcher(source);
        assertTrue(m.find(), meter + " is registered");
        String base = meter.replace('.', '_');
        if (m.group(1).equals("Timer")) {
            return base + "_seconds_max";
        }
        return m.group().contains(".baseUnit(\"seconds\")") ? base + "_seconds" : base;
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p).replace("\r\n", "\n");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml(String text) {
        return (Map<String, Object>) new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> rulesByAlert(Map<String, Object> groupsHolder) {
        List<Map<String, Object>> groups = (List<Map<String, Object>>) groupsHolder.get("groups");
        assertEquals(1, groups.size(), "one rule group");
        assertEquals("sangam", groups.get(0).get("name"));
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> r : (List<Map<String, Object>>) groups.get(0).get("rules")) {
            String alert = (String) r.get("alert");
            assertFalse(out.containsKey(alert), "alert names are unique: " + alert);
            out.put(alert, r);
        }
        return out;
    }

    private static Map<String, Map<String, Object>> composeRules() throws IOException {
        return rulesByAlert(yaml(read(COMPOSE_RULES)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> prometheusRuleValues() throws IOException {
        Map<String, Object> metrics = (Map<String, Object>) yaml(read(CHART.resolve("values.yaml"))).get("metrics");
        return (Map<String, Object>) metrics.get("prometheusRule");
    }

    /**
     * The chart template's spec with the defaults substituted: the {@code if}/{@code end} lines of the optional
     * cert-manager rule dropped, {@code $sel} replaced by the compose job selector, {@code $t.x} by the values.yaml
     * threshold and the cert-manager selector by its default; selector braces normalised to {@code {a="b"}}.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> chartRulesWithDefaults() throws IOException {
        String t = read(CHART.resolve("templates/prometheusrule.yaml"));
        String spec = t.substring(t.indexOf("spec:\n"));
        spec = spec.replace("\n        {{- if .Values.metrics.prometheusRule.certManager.enabled }}", "")
                .replace("\n        {{- end }}", "")
                .replace("\n{{- end }}", "");
        Map<String, Object> v = prometheusRuleValues();
        Map<String, Object> thresholds = (Map<String, Object>) v.get("thresholds");
        Matcher m = Pattern.compile("\\{\\{ \\$t\\.(\\w+) }}").matcher(spec);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object value = thresholds.get(m.group(1));
            assertNotNull(value, "threshold " + m.group(1) + " has a values.yaml default");
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(value)));
        }
        m.appendTail(sb);
        spec = sb.toString().replace("{{ $sel }}", COMPOSE_SELECTOR)
                .replace("{{ .Values.metrics.prometheusRule.certManager.selector }}",
                        (String) ((Map<String, Object>) v.get("certManager")).get("selector"));
        assertFalse(spec.contains("{{"), "every template expression of the rules was substituted:\n" + spec);
        Map<String, Map<String, Object>> rules = rulesByAlert((Map<String, Object>) yaml(spec).get("spec"));
        for (Map<String, Object> r : rules.values()) {
            r.put("expr", ((String) r.get("expr")).replaceAll("\\{\\s+", "{").replaceAll("\\s+}", "}"));
        }
        return rules;
    }

    @Test
    void everyComposeRuleIsComplete() throws IOException {
        Map<String, Map<String, Object>> rules = composeRules();
        assertEquals(Set.of("SangamServerDown", "SangamOutboxDeliveryStale", "SangamOutboxCapacityHigh",
                "SangamOutboxBackpressure", "SangamOutboxDeliveryParked", "SangamRelayStalled", "SangamRootLockWaitHigh",
                "SangamIncidentsOpen", "SangamReconciliationItemsOpen"), rules.keySet());
        for (Map.Entry<String, Map<String, Object>> e : rules.entrySet()) {
            Map<String, Object> r = e.getValue();
            assertTrue(r.get("expr") instanceof String s && !s.isBlank(), e.getKey() + " expr");
            assertTrue(r.get("for") instanceof String f && f.matches("[0-9]+[smh]"), e.getKey() + " for");
            @SuppressWarnings("unchecked")
            Map<String, Object> labels = (Map<String, Object>) r.get("labels");
            assertTrue(Set.of("warning", "critical").contains(labels.get("severity")), e.getKey() + " severity");
            @SuppressWarnings("unchecked")
            Map<String, Object> annotations = (Map<String, Object>) r.get("annotations");
            assertTrue(annotations.get("summary") instanceof String, e.getKey() + " summary");
            assertTrue(((String) r.get("expr")).contains("{" + COMPOSE_SELECTOR), e.getKey() + " selects the Sangam scrape job");
        }
    }

    @Test
    void everyMetricTheRulesReadIsRegistered() throws IOException {
        Set<String> referenced = new TreeSet<>();
        Pattern series = Pattern.compile("\\b([a-z_][a-z0-9_]*)\\{");
        for (Map<String, Object> r : chartRulesWithDefaults().values()) {
            Matcher m = series.matcher((String) r.get("expr"));
            while (m.find()) {
                referenced.add(m.group(1));
            }
        }
        Set<String> expected = new TreeSet<>(SANGAM_METRICS.keySet());
        expected.add("up");
        expected.add("certmanager_certificate_expiration_timestamp_seconds");
        assertEquals(expected, referenced, "the series the rules read");
        for (Map.Entry<String, String[]> e : SANGAM_METRICS.entrySet()) {
            assertEquals(e.getKey(), prometheusName(read(SERVER_MAIN.resolve(e.getValue()[0])), e.getValue()[1]),
                    e.getValue()[1] + " in " + e.getValue()[0]);
        }
        String metrics = read(SERVER_MAIN.resolve("metrics/SangamMetrics.java"));
        assertTrue(metrics.contains(".tag(\"state\", state.name())"), "sangam_reconciliation_items{state}");
        assertTrue(read(Path.of("..", "sangam-core", "src", "main", "java", "org", "sangam", "core", "layer",
                "ReconciliationQueue.java")).contains("public enum State { OPEN, RESOLVED }"), "state=\"OPEN\" is a real state");
    }

    /**
     * The database gauges (same value on every pod) are aggregated with max(); the relay heartbeat is per pod and is
     * compared per instance, so one stalled relay fires even while the others run.
     */
    @Test
    void databaseGaugesAreAggregatedAndTheRelayHeartbeatIsPerPod() throws IOException {
        Map<String, Map<String, Object>> rules = composeRules();
        for (String alert : List.of("SangamOutboxDeliveryStale", "SangamOutboxCapacityHigh", "SangamOutboxBackpressure",
                "SangamOutboxDeliveryParked", "SangamIncidentsOpen", "SangamReconciliationItemsOpen")) {
            assertTrue(((String) rules.get(alert).get("expr")).startsWith("max("), alert + " aggregates with max()");
        }
        assertEquals("sangam_relay_last_cycle_age_seconds{" + COMPOSE_SELECTOR + "} > 60",
                rules.get("SangamRelayStalled").get("expr"), "no aggregation: one series per pod");
    }

    /** R40 levels: the alert threshold is the 50% ALERT level and the fixed backpressure rule is the 80% level. */
    @Test
    void capacityRulesFollowTheR40Levels() throws IOException {
        String monitor = read(Path.of("..", "sangam-integration", "src", "main", "java", "org", "sangam", "integration",
                "relay", "OutboxCapacityMonitor.java"));
        assertTrue(monitor.contains("if (ratio >= 0.80) {\n            return Level.BACKPRESSURE;"), "BACKPRESSURE at 0.80");
        assertTrue(monitor.contains("if (ratio >= 0.50) {\n            return Level.ALERT;"), "ALERT at 0.50");
        Map<String, Map<String, Object>> rules = composeRules();
        assertTrue(((String) rules.get("SangamOutboxCapacityHigh").get("expr")).endsWith(") >= 0.5"));
        assertTrue(((String) rules.get("SangamOutboxBackpressure").get("expr")).endsWith(") >= 0.8"));
        @SuppressWarnings("unchecked")
        Map<String, Object> thresholds = (Map<String, Object>) prometheusRuleValues().get("thresholds");
        assertEquals(0.5, ((Number) thresholds.get("outboxCapacityRatio")).doubleValue(), "chart default = R40 ALERT level");
        String template = read(CHART.resolve("templates/prometheusrule.yaml"));
        assertTrue(template.contains("max(sangam_outbox_capacity_used_ratio{ {{ $sel }} }) >= 0.8'"),
                "the BACKPRESSURE level is not a value: it is where the server starts refusing");
    }

    /**
     * The relay heartbeat default is twice the claim lease: a healthy cycle completes within the lease (a longer one
     * loses its claims to the sweeper) and the next starts within the poll interval; the server uses the defaults.
     */
    @Test
    void relayHeartbeatThresholdFollowsTheRelayTiming() throws IOException {
        Path integration = Path.of("..", "sangam-integration", "src", "main", "java", "org", "sangam", "integration");
        assertTrue(read(Path.of("..", "sangam-core", "src", "main", "java", "org", "sangam", "core", "layer", "Relay.java"))
                .contains("return new Config(node, 100, Duration.ofSeconds(30), 10,"), "claim lease 30 s");
        assertTrue(read(integration.resolve(Path.of("relay", "OutboxRelayService.java")))
                .contains("return new Settings(Relay.Config.defaults(node), Duration.ofSeconds(1),"), "poll interval 1 s");
        assertTrue(read(SERVER_MAIN.resolve("config/IntegrationWiring.java"))
                .contains("OutboxRelayService.Settings.defaults(rest.getNode())"), "the server runs the relay with the defaults");
        @SuppressWarnings("unchecked")
        Map<String, Object> thresholds = (Map<String, Object>) prometheusRuleValues().get("thresholds");
        assertEquals(60, thresholds.get("relayLastCycleAgeSeconds"), "2 x the 30 s lease (> lease + 1 s poll)");
    }

    @Test
    void theChartRendersTheComposeRulesPlusCertificateExpiry() throws IOException {
        Map<String, Map<String, Object>> compose = composeRules();
        Map<String, Map<String, Object>> chart = chartRulesWithDefaults();
        Set<String> extra = new TreeSet<>(chart.keySet());
        extra.removeAll(compose.keySet());
        assertEquals(Set.of(CERT_RULE), extra, "the chart adds only the cert-manager rule");
        for (String alert : compose.keySet()) {
            assertEquals(compose.get(alert), chart.get(alert), alert + ": chart with defaults = compose");
        }
        assertEquals("(certmanager_certificate_expiration_timestamp_seconds{} - time()) < 14 * 86400",
                chart.get(CERT_RULE).get("expr"), "cert expiry with the default selector and 14 days");
    }

    @Test
    void theChartRuleIsOptInAndSelectsTheServiceMonitorJob() throws IOException {
        String t = read(CHART.resolve("templates/prometheusrule.yaml"));
        assertTrue(t.contains("{{- if .Values.metrics.prometheusRule.enabled }}"), "rendered only when enabled");
        assertTrue(t.contains("kind: PrometheusRule"));
        assertTrue(t.contains("{{- with .Values.metrics.prometheusRule.labels }}"), "ruleSelector labels");
        assertTrue(t.contains("{{- $sel := printf \"job=%q, namespace=%q\" (printf \"%s-server-management\" $fullname) .Release.Namespace -}}"),
                "the job of the ServiceMonitor target, in the release namespace");
        assertEquals(false, prometheusRuleValues().get("enabled"), "off by default (needs the Prometheus Operator CRD)");

        // The ServiceMonitor selects the Service labelled sangam.io/metrics=true, which is -server-management; the
        // Prometheus Operator sets job to the Service name.
        String monitor = read(CHART.resolve("templates/servicemonitor.yaml"));
        assertTrue(monitor.contains("sangam.io/metrics: \"true\"") && monitor.contains("- port: management"));
        String services = read(CHART.resolve("templates/server-service.yaml"));
        assertTrue(Pattern.compile("name: \\{\\{ include \"sangam\\.fullname\" \\. }}-server-management\\n(?:.*\\n){1,5}?"
                + "\\s+sangam\\.io/metrics: \"true\"").matcher(services).find(), "only -server-management carries the label");
        assertEquals(1, services.split("sangam\\.io/metrics: \"true\"", -1).length - 1);
    }

    @Test
    void composePrometheusLoadsTheRulesAndScrapesTheSelectedJob() throws IOException {
        Map<String, Object> prometheus = yaml(read(DEPLOY.resolve(Path.of("compose", "observability", "prometheus.yml"))));
        assertEquals(List.of("/etc/prometheus/rules/*.yml"), prometheus.get("rule_files"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scrapes = (List<Map<String, Object>>) prometheus.get("scrape_configs");
        assertTrue(scrapes.stream().anyMatch(s -> "sangam-server".equals(s.get("job_name"))
                && "/actuator/prometheus".equals(s.get("metrics_path"))), "the job the rules select");
        assertTrue(COMPOSE_RULES.getFileName().toString().endsWith(".yml"), "matched by the rule_files glob");
    }
}
