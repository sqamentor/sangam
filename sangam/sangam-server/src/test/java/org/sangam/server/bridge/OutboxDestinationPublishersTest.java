// SPDX-License-Identifier: Apache-2.0
package org.sangam.server.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * SL-146 (owner decision 2026-11-12): every outbox destination that product code writes has a relay publisher. An
 * unknown destination is a permanent failure ({@code PublisherRegistry.publish}: {@code PermanentPublishException}), so
 * every delivery to it would park or be skipped and dead-lettered.
 *
 * <p>The test reads the product sources. A destination is every argument of {@code Outbox.Destination.ordered/unordered}
 * in {@code src/main/java} of every module, as a literal, a {@code static final String} constant (local or
 * {@code Class.NAME}), or a literal/constant followed by {@code + <dynamic>} (a prefix, such as {@code "channel:" + name}).
 * A publisher is the first argument of a two-argument {@code register(name, publisher)} call on a
 * {@code PublisherRegistry} in sangam-server and sangam-integration, exact or a prefix ending in {@code *}
 * ({@code channel:*}, {@code xcell:*}, {@code webhook:*}). Destinations that come from configuration are listed in
 * {@link #CONFIGURED} with the reason; anything else unresolvable fails the test.
 */
class OutboxDestinationPublishersTest {

    /** Expressions whose value is not in the source: file name + expression → why it is not checked here. */
    static final Map<String, String> CONFIGURED = Map.of(
            "IntegrationWiring.java|spec.substring(0, spec.length() - \":ordered\".length())",
            "sangam.integration.default-destinations: operator configuration",
            "IntegrationWiring.java|spec", "sangam.integration.default-destinations: operator configuration",
            "PortsHistoryEventHandler.java|x", "configured extra history destinations",
            "CaseModuleNotifier.java|destination()",
            "the same CaseModuleNotifier.destination() is registered by CaseWiring.notifier (publishers.register(n.destination(), ...))",
            "CaseWiring.java|n.destination()", "registration of CaseModuleNotifier.destination() (see above)",
            "LedgerJournal.java|gl.destination()",
            "the destination of LedgerJournal.GlExport; its one construction (LedgerWiring.journal) is scanned as a"
                    + " destination, see DESTINATION");

    /**
     * A destination: the argument of {@code Outbox.Destination.ordered/unordered}, or the first argument of a
     * {@code LedgerJournal.GlExport}, which the journal hands to {@code Outbox.Destination.unordered} unchanged.
     */
    static final Pattern DESTINATION = Pattern.compile("Outbox\\.Destination\\.(?:un)?ordered\\(|new\\s+LedgerJournal\\.GlExport\\(");
    static final Pattern REGISTER = Pattern.compile("\\b(pubs|publishers|registry|p)\\.register\\(");
    static final Pattern CONSTANT = Pattern.compile("static\\s+final\\s+String\\s+(\\w+)\\s*=\\s*\"([^\"]*)\"\\s*;");

    record Source(Path path, String name, String text) {
    }

    /** A resolved name: {@code prefix} true for {@code "x" + dynamic} (destinations) or {@code "x*"} (publishers). */
    record Name(String value, boolean prefix, String where) {
    }

    static Path root() {
        for (Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("sangam-server/src/main/java")) && Files.isDirectory(p.resolve("sangam-core/src/main/java"))) {
                return p;
            }
        }
        throw new AssertionError("sangam root not found above " + System.getProperty("user.dir"));
    }

    static List<Source> sources(Path root) {
        List<Source> out = new ArrayList<>();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path m : modules.filter(Files::isDirectory).toList()) {
                Path main = m.resolve("src/main/java");
                if (!Files.isDirectory(main)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(main)) {
                    for (Path f : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                        out.add(new Source(f, f.getFileName().toString(), Files.readString(f, StandardCharsets.UTF_8)));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** The source text without comments, so javadoc examples are not read as code. */
    static String code(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        int n = text.length();
        while (i < n) {
            char ch = text.charAt(i);
            if (text.startsWith("\"\"\"", i)) {   // text block: copied as is
                int end = text.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
                out.append(text, i, end);
                i = end;
            } else if (ch == '"' || ch == '\'') {   // string or char literal, with escapes
                int j = i + 1;
                while (j < n && text.charAt(j) != ch) {
                    j += text.charAt(j) == '\\' ? 2 : 1;
                }
                j = Math.min(n, j + 1);
                out.append(text, i, j);
                i = j;
            } else if (text.startsWith("//", i)) {
                int end = text.indexOf('\n', i);
                i = end < 0 ? n : end;
            } else if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                out.append(' ');
            } else {
                out.append(ch);
                i++;
            }
        }
        return out.toString();
    }

    /** The first argument of the call whose '(' ends at {@code open}, and whether a second argument follows. */
    static String[] firstArgument(String s, int open) {
        int depth = 0;
        boolean quoted = false;
        for (int i = open; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' && s.charAt(i - 1) != '\\') {
                quoted = !quoted;
            } else if (!quoted) {
                if (ch == '(') {
                    depth++;
                } else if (ch == ')') {
                    if (depth == 0) {
                        return new String[]{s.substring(open, i).trim(), "1"};
                    }
                    depth--;
                } else if (ch == ',' && depth == 0) {
                    return new String[]{s.substring(open, i).trim(), "2"};
                }
            }
        }
        throw new AssertionError("unbalanced call at " + open);
    }

    /** Splits {@code a + b + c} at top level. */
    static List<String> operands(String e) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean quoted = false;
        int start = 0;
        for (int i = 0; i < e.length(); i++) {
            char ch = e.charAt(i);
            if (ch == '"' && (i == 0 || e.charAt(i - 1) != '\\')) {
                quoted = !quoted;
            } else if (!quoted) {
                if (ch == '(') {
                    depth++;
                } else if (ch == ')') {
                    depth--;
                } else if (ch == '+' && depth == 0) {
                    out.add(e.substring(start, i).trim());
                    start = i + 1;
                }
            }
        }
        out.add(e.substring(start).trim());
        return out;
    }

    static String value(String operand, Source in, Map<String, String> constants) {
        if (operand.matches("\"[^\"]*\"")) {
            return operand.substring(1, operand.length() - 1);
        }
        if (operand.matches("(?:[a-z]\\w*\\.)*[A-Z]\\w*\\.[A-Z][A-Z0-9_]*")) {   // Class.NAME or pkg.Class.NAME
            String[] parts = operand.split("\\.");
            return constants.get(parts[parts.length - 2] + "." + parts[parts.length - 1]);
        }
        if (operand.matches("[A-Z][A-Z0-9_]*")) {
            return constants.get(in.name().replace(".java", "") + "." + operand);
        }
        return null;
    }

    /** Resolves an expression, or null when it is not a literal/constant (optionally followed by "*" or a dynamic part). */
    static Name resolve(String expr, Source in, Map<String, String> constants) {
        List<String> ops = operands(expr);
        String head = value(ops.get(0), in, constants);
        if (head == null) {
            return null;
        }
        StringBuilder v = new StringBuilder(head);
        boolean prefix = false;
        for (String op : ops.subList(1, ops.size())) {
            String part = value(op, in, constants);
            if (part == null) {
                prefix = true;   // a dynamic suffix: the head is a prefix
                break;
            }
            v.append(part);
        }
        String s = v.toString();
        if (s.endsWith("*")) {
            return new Name(s.substring(0, s.length() - 1), true, in.name());
        }
        return new Name(s, prefix, in.name());
    }

    static Map<String, String> constants(List<Source> sources) {
        Map<String, String> out = new HashMap<>();
        for (Source s : sources) {
            Matcher m = CONSTANT.matcher(s.text());
            while (m.find()) {
                out.put(s.name().replace(".java", "") + "." + m.group(1), m.group(2));
            }
        }
        return out;
    }

    record Scan(List<Name> destinations, List<Name> publishers, Set<String> configured, List<String> unresolved) {
    }

    static Scan scan() {
        Path root = root();
        List<Source> all = sources(root);
        Map<String, String> constants = constants(all);
        List<Name> destinations = new ArrayList<>();
        List<Name> publishers = new ArrayList<>();
        Set<String> configured = new TreeSet<>();
        List<String> unresolved = new ArrayList<>();
        for (Source s : all) {
            String code = code(s.text());
            Matcher d = DESTINATION.matcher(code);
            while (d.find()) {
                String expr = firstArgument(code, d.end())[0];
                Name n = resolve(expr, s, constants);
                if (n != null) {
                    destinations.add(n);
                } else if (CONFIGURED.containsKey(s.name() + "|" + expr)) {
                    configured.add(s.name() + "|" + expr);
                } else {
                    unresolved.add("destination " + s.path() + ": " + expr);
                }
            }
            boolean serverOrIntegration = s.path().startsWith(root.resolve("sangam-server"))
                    || s.path().startsWith(root.resolve("sangam-integration"));
            if (!serverOrIntegration || !s.text().contains("PublisherRegistry")) {
                continue;
            }
            Matcher r = REGISTER.matcher(code);
            while (r.find()) {
                String[] arg = firstArgument(code, r.end());
                if (!"2".equals(arg[1])) {
                    continue;   // register(connector): a connector registry, not a publisher
                }
                Name n = resolve(arg[0], s, constants);
                if (n != null) {
                    publishers.add(n);
                } else if (CONFIGURED.containsKey(s.name() + "|" + arg[0])) {
                    configured.add(s.name() + "|" + arg[0]);
                } else {
                    unresolved.add("publisher " + s.path() + ": " + arg[0]);
                }
            }
        }
        return new Scan(destinations, publishers, configured, unresolved);
    }

    static boolean covered(Name destination, List<Name> publishers) {
        for (Name p : publishers) {
            if (p.prefix() ? destination.value().startsWith(p.value())
                    : !destination.prefix() && destination.value().equals(p.value())) {
                return true;
            }
        }
        return false;
    }

    @Test
    void everyProductOutboxDestinationHasARegisteredPublisher() {
        Scan scan = scan();
        assertTrue(scan.unresolved().isEmpty(), "destinations or publishers that are neither a literal nor a constant;"
                + " resolve them or list them in CONFIGURED with the reason: " + scan.unresolved());
        assertEquals(new TreeSet<>(CONFIGURED.keySet()), scan.configured(),
                "every CONFIGURED entry must still match the source (remove stale ones)");
        Set<String> missing = new LinkedHashSet<>();
        for (Name d : scan.destinations()) {
            if (!covered(d, scan.publishers())) {
                missing.add(d.value() + (d.prefix() ? "*" : "") + " (" + d.where() + ")");
            }
        }
        if (!missing.isEmpty()) {
            fail("outbox destinations without a registered publisher (deliveries would park): " + missing
                    + "; publishers: " + scan.publishers());
        }
    }

    @Test
    void theScanSeesTheKnownDestinationsAndPrefixes() {
        Scan scan = scan();
        Set<String> d = new TreeSet<>();
        scan.destinations().forEach(n -> d.add(n.value() + (n.prefix() ? "*" : "")));
        Set<String> p = new TreeSet<>();
        scan.publishers().forEach(n -> p.add(n.value() + (n.prefix() ? "*" : "")));
        for (String known : List.of("case-events", "history-projector", "channel:*", "ledger:gl", "pack-dispatch",
                "programme-events", "programme-dashboard", "sangam.registry.publish", "sangam.resources.notification")) {
            assertTrue(d.contains(known), "the scan must find destination " + known + ": " + d);
        }
        for (String known : List.of("channel:*", "xcell:*", "webhook:*", "rollup:national", "case-events", "history-projector")) {
            assertTrue(p.contains(known), "the scan must find publisher " + known + ": " + p);
        }
        assertFalse(d.contains("notifications"), "SL-146: SLA events go through OutboxWriter subscriptions");
        assertFalse(d.contains("sangam.registry.events"), "SL-146: registry events go through OutboxWriter subscriptions");
    }

    @Test
    void resolutionRules() {
        Source s = new Source(Path.of("X.java"), "X.java", "");
        Map<String, String> c = Map.of("X.LOCAL", "local", "Other.PREFIX", "xcell:");
        assertEquals(new Name("a", false, "X.java"), resolve("\"a\"", s, c));
        assertEquals(new Name("local", false, "X.java"), resolve("LOCAL", s, c));
        assertEquals(new Name("xcell:", true, "X.java"), resolve("Other.PREFIX + \"*\"", s, c));
        assertEquals(new Name("channel:", true, "X.java"), resolve("\"channel:\" + channel", s, c));
        assertEquals(null, resolve("n.destination()", s, c));
        List<Name> pubs = List.of(new Name("channel:", true, "P"), new Name("ledger:gl", false, "P"));
        assertTrue(covered(new Name("channel:", true, "D"), pubs));
        assertTrue(covered(new Name("channel:sms", false, "D"), pubs));
        assertTrue(covered(new Name("ledger:gl", false, "D"), pubs));
        assertFalse(covered(new Name("ledger:", true, "D"), pubs), "an exact publisher does not cover a dynamic prefix");
        assertFalse(covered(new Name("notifications", false, "D"), pubs));
        String stripped = code("x(\"/api/*\", '\"') /* c(\"no\") */ y // z(\"no\")\nw");
        assertTrue(stripped.contains("x(\"/api/*\", '\"')"), "literals are kept, even with /* inside: " + stripped);
        assertFalse(stripped.contains("no"), "comments are removed: " + stripped);
        assertTrue(stripped.contains("y") && stripped.endsWith("\nw"));
    }
}
