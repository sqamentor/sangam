// SPDX-License-Identifier: Apache-2.0
package org.sangam.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * {@code sangam upgrade check} (M-092, SANGAM.md 14.7 "an upgrade-checker CLI that scans models and config before an
 * upgrade"; R32). It runs on the operator's machine with the CLI of the TARGET release and needs no server:
 * <ul>
 *   <li><b>models</b> ({@code --models DIR}, repeatable): BPMN, DMN and CMMN files are parsed (no DTDs, no external
 *       entities) and every element or attribute the target release removes or deprecates is reported with its file;</li>
 *   <li><b>config</b> ({@code --config FILE}, repeatable): {@code .yaml}/{@code .yml}/{@code .properties} keys (Spring
 *       relaxed form: case, {@code -} and {@code _} ignored) and {@code .env} variable names against the target's
 *       removed and renamed settings;</li>
 *   <li><b>API</b>: the target's deprecation registry (the {@code sangam-api-deprecations.yaml} that the server's
 *       {@code ApiDeprecationRegistry} loads, bundled into the CLI at build time): operations already past their sunset
 *       on {@code --as-of} (default today, UTC) answer 410 {@code API_SUNSET} after the upgrade; deprecated ones are
 *       warnings with their successor.</li>
 * </ul>
 * The target's removals live in {@code upgrade-rules.json} (bundled; {@code --rules FILE} overrides), which every
 * release extends. Output is one JSON report on stdout; exit 0 without blocking findings, 1 with any, 2 on usage.
 * With {@code --schema DATABASE} the CLI also reads the installation's applied changesets ({@code GET /ops/schema-status},
 * platform operators) and compares them with the target's changelog ({@link SchemaCheck}); without it the report lists
 * the schema under {@code notChecked} instead of claiming it.
 */
final class UpgradeCheck {

    static final String RULES_RESOURCE = "upgrade-rules.json";
    static final String DEPRECATIONS_RESOURCE = "sangam-api-deprecations.yaml";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final YAMLMapper YAML = new YAMLMapper();
    private static final int MAX_FILES = 20_000;

    /** One finding: {@code BLOCKING} stops the upgrade, {@code WARNING} does not, {@code INFO} is what the upgrade will do. */
    record Finding(String severity, String area, String where, String message) {
    }

    private final PrintStream out;
    private final PrintStream err;
    private final Clock clock;

    UpgradeCheck(PrintStream out, PrintStream err, Clock clock) {
        this.out = out;
        this.err = err;
        this.clock = clock;
    }

    /** Runs the check; a file that cannot be read is a usage error (exit 2), not a network one. */
    int run(List<String> modelDirs, List<String> configFiles, String rulesFile, String deprecationsFile, String asOf) {
        return run(modelDirs, configFiles, rulesFile, deprecationsFile, asOf, null, null);
    }

    /**
     * As above, plus the schema comparison (M-092) when {@code database} and the server's {@code SchemaStatus}
     * ({@code GET /ops/schema-status}, fetched by {@link Main}) are given.
     */
    int run(List<String> modelDirs, List<String> configFiles, String rulesFile, String deprecationsFile, String asOf,
            String database, JsonNode schemaStatus) {
        try {
            return check(modelDirs, configFiles, rulesFile, deprecationsFile, asOf, database, schemaStatus);
        } catch (IOException e) {
            err.println("cannot read: " + e.getMessage());
            return Main.USAGE;
        } catch (java.time.format.DateTimeParseException e) {
            err.println("a date must be yyyy-MM-dd: " + e.getParsedString());
            return Main.USAGE;
        } catch (IllegalArgumentException e) {
            err.println("usage: " + e.getMessage());
            return Main.USAGE;
        }
    }

    private int check(List<String> modelDirs, List<String> configFiles, String rulesFile, String deprecationsFile, String asOf,
                      String database, JsonNode schemaStatus) throws IOException {
        JsonNode rules = rulesFile == null ? resource(RULES_RESOURCE, JSON) : JSON.readTree(Path.of(rulesFile).toFile());
        JsonNode deprecations = deprecationsFile == null ? resource(DEPRECATIONS_RESOURCE, YAML)
                : YAML.readTree(Path.of(deprecationsFile).toFile());
        LocalDate date = asOf == null ? LocalDate.now(clock.withZone(ZoneOffset.UTC)) : LocalDate.parse(asOf);
        List<Finding> findings = new ArrayList<>();
        for (String d : modelDirs) {
            scanModels(Path.of(d), rules.path("models"), findings);
        }
        for (String f : configFiles) {
            scanConfig(Path.of(f), rules.path("config"), findings);
        }
        checkApi(deprecations, date, findings);
        List<String> bundled = null;
        if (schemaStatus != null) {
            bundled = SchemaCheck.bundledIds(SchemaCheck.database(database));
            SchemaCheck.compare(bundled, schemaStatus, findings);
        }
        long blocking = findings.stream().filter(f -> f.severity().equals("BLOCKING")).count();
        long warnings = findings.stream().filter(f -> f.severity().equals("WARNING")).count();
        ObjectNode report = JSON.createObjectNode();
        report.put("targetRelease", rules.path("release").asText("unknown"));
        report.put("asOf", date.toString());
        report.put("blocking", blocking);
        report.put("warnings", warnings);
        report.put("info", findings.size() - blocking - warnings);
        ArrayNode list = report.putArray("findings");
        for (Finding f : findings) {
            list.addObject().put("severity", f.severity()).put("area", f.area()).put("where", f.where()).put("message", f.message());
        }
        ArrayNode notChecked = report.putArray("notChecked");
        if (schemaStatus == null) {
            notChecked.add("schema version: pass --schema DATABASE to compare the server's applied changesets (GET /ops/schema-status)");
        } else {
            ObjectNode schema = report.putObject("schema");
            schema.put("database", SchemaCheck.database(database));
            schema.put("targetChangesets", bundled.size());
            schema.put("appliedChangesets", schemaStatus.path("product").path("applied").asInt());
            schema.put("engineSchemaVersion", schemaStatus.path("engine").path("schemaVersion").asText(""));
        }
        out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        return blocking > 0 ? Main.REFUSED : Main.OK;
    }

    // ---- models --------------------------------------------------------------------------------

    void scanModels(Path dir, JsonNode modelRules, List<Finding> findings) throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("--models must be a directory: " + dir);
        }
        List<Path> files;
        try (Stream<Path> s = Files.walk(dir)) {
            files = s.filter(Files::isRegularFile).filter(UpgradeCheck::isModel).limit(MAX_FILES + 1L).sorted().toList();
        }
        if (files.size() > MAX_FILES) {
            throw new IllegalArgumentException("more than " + MAX_FILES + " model files under " + dir);
        }
        DocumentBuilder parser = secureParser();
        for (Path f : files) {
            Document doc;
            try (InputStream in = Files.newInputStream(f)) {
                doc = parser.parse(in);
            } catch (SAXException e) {
                findings.add(new Finding("BLOCKING", "model", f.toString(), "not a well-formed model: " + e.getMessage()));
                continue;
            }
            NodeList all = doc.getElementsByTagNameNS("*", "*");
            for (int i = 0; i < all.getLength(); i++) {
                Element e = (Element) all.item(i);
                for (JsonNode rule : modelRules.path("constructs")) {
                    if (matches(rule, e)) {
                        String id = e.getAttribute("id");
                        findings.add(new Finding(severity(rule), "model", f + (id.isEmpty() ? "" : "#" + id),
                                describe(rule) + ": " + rule.path("note").asText("")));
                    }
                }
            }
        }
    }

    static boolean isModel(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".bpmn") || n.endsWith(".bpmn20.xml") || n.endsWith(".dmn") || n.endsWith(".cmmn");
    }

    /** A rule names a namespace and an element ({@code *} = any) and, optionally, an attribute ({@code {ns}local}). */
    static boolean matches(JsonNode rule, Element e) {
        String ns = rule.path("namespace").asText("*");
        String element = rule.path("element").asText("*");
        if (!(ns.equals("*") || ns.equals(e.getNamespaceURI())) || !(element.equals("*") || element.equals(e.getLocalName()))) {
            return false;
        }
        String attribute = rule.path("attribute").asText("");
        if (attribute.isEmpty()) {
            return !element.equals("*");
        }
        String attrNs = rule.path("attributeNamespace").asText("");
        NamedNodeMap attrs = e.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            String local = a.getLocalName() == null ? a.getNodeName() : a.getLocalName();
            String aNs = a.getNamespaceURI() == null ? "" : a.getNamespaceURI();
            if (local.equals(attribute) && (attrNs.isEmpty() || attrNs.equals(aNs))) {
                String value = rule.path("value").asText("");
                if (value.isEmpty() || value.equals(a.getNodeValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String describe(JsonNode rule) {
        String a = rule.path("attribute").asText("");
        return rule.path("element").asText("*") + (a.isEmpty() ? "" : "@" + a)
                + (rule.path("value").asText("").isEmpty() ? "" : "=" + rule.path("value").asText());
    }

    static DocumentBuilder secureParser() {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(new org.xml.sax.helpers.DefaultHandler()); // no stderr noise; fatal errors still throw
            return b;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- config --------------------------------------------------------------------------------

    void scanConfig(Path file, JsonNode configRules, List<Finding> findings) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("--config must be a file: " + file);
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        Map<String, String> keys = new LinkedHashMap<>();
        boolean env = name.endsWith(".env");
        if (name.endsWith(".yaml") || name.endsWith(".yml")) {
            for (JsonNode docNode : YAML.readValues(YAML.createParser(file.toFile()), JsonNode.class).readAll()) {
                flatten("", docNode, keys);
            }
        } else if (name.endsWith(".properties")) {
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(r);
            }
            p.stringPropertyNames().stream().sorted().forEach(k -> keys.put(k, k));
        } else if (env) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String l = line.strip();
                if (l.startsWith("export ")) {
                    l = l.substring(7).strip();
                }
                int eq = l.indexOf('=');
                if (!l.isEmpty() && !l.startsWith("#") && eq > 0) {
                    keys.put(l.substring(0, eq).strip(), l.substring(0, eq).strip());
                }
            }
        } else {
            throw new IllegalArgumentException("--config takes .yaml, .yml, .properties or .env files: " + file);
        }
        JsonNode removed = configRules.path(env ? "removedEnv" : "removed");
        JsonNode renamed = configRules.path(env ? "renamedEnv" : "renamed");
        for (String key : keys.keySet()) {
            String k = env ? key : relaxed(key);
            for (Iterator<Map.Entry<String, JsonNode>> it = removed.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> r = it.next();
                if (covers(env ? r.getKey() : relaxed(r.getKey()), k)) {
                    findings.add(new Finding("BLOCKING", "config", file + ": " + key, "removed setting: " + r.getValue().asText("")));
                }
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = renamed.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> r = it.next();
                if (covers(env ? r.getKey() : relaxed(r.getKey()), k)) {
                    findings.add(new Finding("BLOCKING", "config", file + ": " + key, "renamed to " + r.getValue().asText()));
                }
            }
        }
    }

    /** Spring relaxed binding for comparison: lower case, {@code -} and {@code _} ignored, list indexes dropped. */
    static String relaxed(String key) {
        return key.replaceAll("\\[\\d+]", "").toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }

    /** {@code a.b} covers {@code a.b} and {@code a.b.c}. */
    static boolean covers(String ruleKey, String key) {
        return key.equals(ruleKey) || key.startsWith(ruleKey + ".");
    }

    static void flatten(String prefix, JsonNode n, Map<String, String> out) {
        if (n == null) {
            return;
        }
        if (n.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                flatten(prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), e.getValue(), out);
            }
        } else if (n.isArray()) {
            for (int i = 0; i < n.size(); i++) {
                flatten(prefix + "[" + i + "]", n.get(i), out);
            }
        } else if (!prefix.isEmpty()) {
            out.put(prefix, prefix);
        }
    }

    // ---- API -----------------------------------------------------------------------------------

    static void checkApi(JsonNode registry, LocalDate asOf, List<Finding> findings) {
        for (JsonNode e : registry.path("deprecations")) {
            String op = e.path("method").asText("").toUpperCase(Locale.ROOT) + " " + e.path("path").asText("");
            LocalDate sunset = LocalDate.parse(e.path("sunset").asText());
            LocalDate deprecated = LocalDate.parse(e.path("deprecated").asText());
            String successor = e.path("successor").asText("");
            String next = successor.isEmpty() ? "" : "; use " + successor;
            if (!asOf.isBefore(sunset)) {
                findings.add(new Finding("BLOCKING", "api", op, "sunset on " + sunset + ": answers 410 API_SUNSET" + next
                        + " (" + e.path("link").asText("") + ")"));
            } else if (!asOf.isBefore(deprecated)) {
                findings.add(new Finding("WARNING", "api", op, "deprecated since " + deprecated + ", sunset " + sunset + next
                        + " (" + e.path("link").asText("") + ")"));
            }
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static String severity(JsonNode rule) {
        return "deprecated".equals(rule.path("status").asText("removed")) ? "WARNING" : "BLOCKING";
    }

    private JsonNode resource(String name, ObjectMapper mapper) throws IOException {
        try (InputStream in = UpgradeCheck.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                err.println("note: " + name + " is not bundled in this build; nothing to compare for it");
                return JSON.createObjectNode();
            }
            JsonNode n = mapper.readTree(in);
            return n == null ? JSON.createObjectNode() : n;
        }
    }
}
