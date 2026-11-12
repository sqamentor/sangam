// SPDX-License-Identifier: Apache-2.0
package org.sangam.packs.spi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

/**
 * SL-54 roll-forward: the content of one pack version re-stated under another version, so it is internally consistent
 * under that version and every (key, versionTag) it carries is new. Only the version-bearing parts change:
 *
 * <ul>
 *   <li>BPMN: {@code versionTag} of every {@code <process>} (always the new version: no definition of the new deployment
 *       can then share a version tag with an existing one); {@code exporterVersion} of {@code <definitions>} and the
 *       start listener's {@code packVersion} field when they name the source version; a call activity's
 *       {@code calledElementVersionTag} and a business rule task's {@code decisionRefVersionTag} pinned by
 *       {@code versionTag} to a process or decision of the same content (pins to other packs are left alone);</li>
 *   <li>DMN: {@code versionTag} of every {@code <decision>}; {@code exporterVersion} when it names the source
 *       version;</li>
 *   <li>forms ({@code .form.json}, {@code .form}): the top-level {@code "version"} when it is the source version.</li>
 * </ul>
 *
 * Every other resource, and every other byte, is unchanged.
 */
public final class PackVersionRewriter {

    private static final Pattern PROCESS = Pattern.compile("<(?:[\\w.-]+:)?process\\b[^>]*>");
    private static final Pattern DECISION = Pattern.compile("<(?:[\\w.-]+:)?decision\\b[^>]*>");
    private static final Pattern DEFINITIONS = Pattern.compile("<(?:[\\w.-]+:)?definitions\\b[^>]*>");
    private static final Pattern CALL = Pattern.compile("<(?:[\\w.-]+:)?callActivity\\b[^>]*>");
    private static final Pattern RULE_TASK = Pattern.compile("<(?:[\\w.-]+:)?businessRuleTask\\b[^>]*>");
    private static final Pattern VERSION_TAG = Pattern.compile("(\\b[\\w.-]+:versionTag=\")[^\"]*(\")");
    private static final Pattern ID = Pattern.compile("\\sid=\"([^\"]*)\"");
    private static final Pattern PACK_VERSION_FIELD = Pattern.compile(
            "(<(?:[\\w.-]+:)?field\\s+name=\"packVersion\"\\s*>\\s*<(?:[\\w.-]+:)?string>)([^<]*)(</)"
                    + "|(<(?:[\\w.-]+:)?field\\s+name=\"packVersion\"\\s+stringValue=\")([^\"]*)(\")");

    private PackVersionRewriter() {
    }

    /** The resources (name to bytes, order kept) of version {@code from} re-stated as version {@code to}. */
    public static Map<String, byte[]> rewrite(Map<String, byte[]> resources, String from, String to) {
        Set<String> processes = new LinkedHashSet<>();
        Set<String> decisions = new LinkedHashSet<>();
        for (Map.Entry<String, byte[]> e : resources.entrySet()) {
            String n = e.getKey().toLowerCase(java.util.Locale.ROOT);
            if (isBpmn(n)) {
                ids(PROCESS, utf8(e.getValue()), processes);
            } else if (n.endsWith(".dmn")) {
                ids(DECISION, utf8(e.getValue()), decisions);
            }
        }
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : resources.entrySet()) {
            String n = e.getKey().toLowerCase(java.util.Locale.ROOT);
            byte[] b = e.getValue();
            if (isBpmn(n)) {
                b = bytes(bpmn(utf8(b), from, to, processes, decisions));
            } else if (n.endsWith(".dmn")) {
                String x = replaceInTags(DECISION, utf8(b), t -> setVersionTag(t, to));
                b = bytes(replaceInTags(DEFINITIONS, x, t -> replaceAttr(t, "exporterVersion", from, to)));
            } else if (n.endsWith(".form.json") || n.endsWith(".form")) {
                b = bytes(formVersion(utf8(b), from, to));
            }
            out.put(e.getKey(), b);
        }
        return out;
    }

    static String bpmn(String xml, String from, String to, Set<String> processes, Set<String> decisions) {
        String x = replaceInTags(PROCESS, xml, t -> setVersionTag(t, to));
        x = replaceInTags(DEFINITIONS, x, t -> replaceAttr(t, "exporterVersion", from, to));
        x = replaceInTags(CALL, x, t -> processes.contains(attr(t, "calledElement"))
                && "versionTag".equals(attr(t, "calledElementBinding"))
                ? replaceAttr(t, "calledElementVersionTag", null, to) : t);
        x = replaceInTags(RULE_TASK, x, t -> decisions.contains(attr(t, "decisionRef"))
                && "versionTag".equals(attr(t, "decisionRefBinding"))
                ? replaceAttr(t, "decisionRefVersionTag", null, to) : t);
        Matcher m = PACK_VERSION_FIELD.matcher(x);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int g = m.group(1) != null ? 1 : 4;
            String value = m.group(g + 1);
            String repl = value.trim().equals(from) ? m.group(g) + value.replace(from, to) + m.group(g + 2) : m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** The top-level {@code "version"} of a JSON document, when it is {@code from}, set to {@code to}; nothing else. */
    static String formVersion(String json, String from, String to) {
        try (JsonParser p = new JsonFactory().createParser(json)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                return json;
            }
            JsonToken t;
            while ((t = p.nextToken()) != null && t != JsonToken.END_OBJECT) {
                if (t == JsonToken.FIELD_NAME && "version".equals(p.currentName())) {
                    if (p.nextToken() == JsonToken.VALUE_STRING && from.equals(p.getText())) {
                        int at = (int) p.currentTokenLocation().getCharOffset();
                        String literal = "\"" + from + "\"";
                        if (at >= 0 && json.startsWith(literal, at)) {
                            return json.substring(0, at) + "\"" + to + "\"" + json.substring(at + literal.length());
                        }
                    }
                    return json;
                }
                if (t == JsonToken.FIELD_NAME) {
                    p.nextToken();
                    p.skipChildren();
                }
            }
            return json;
        } catch (IOException e) {
            return json; // not JSON: no version to restate
        }
    }

    private static boolean isBpmn(String lowerName) {
        return lowerName.endsWith(".bpmn") || lowerName.endsWith(".bpmn20.xml");
    }

    private static void ids(Pattern element, String xml, Set<String> into) {
        Matcher m = element.matcher(xml);
        while (m.find()) {
            Matcher id = ID.matcher(m.group());
            if (id.find()) {
                into.add(id.group(1));
            }
        }
    }

    private static String replaceInTags(Pattern element, String xml, java.util.function.UnaryOperator<String> f) {
        Matcher m = element.matcher(xml);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(f.apply(m.group())));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String setVersionTag(String tag, String to) {
        return VERSION_TAG.matcher(tag).replaceAll(r -> Matcher.quoteReplacement(r.group(1) + to + r.group(2)));
    }

    /** The value of an attribute (any namespace prefix) of one start tag, or null. */
    private static String attr(String tag, String local) {
        Matcher m = Pattern.compile("[\\s:]" + local + "=\"([^\"]*)\"").matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    /** Sets an attribute's value to {@code to}, only when it is {@code from} (any value when {@code from} is null). */
    private static String replaceAttr(String tag, String local, String from, String to) {
        Matcher m = Pattern.compile("([\\s:]" + local + "=\")([^\"]*)(\")").matcher(tag);
        if (!m.find() || (from != null && !from.equals(m.group(2)))) {
            return tag;
        }
        return tag.substring(0, m.start(2)) + to + tag.substring(m.end(2));
    }

    private static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

}
