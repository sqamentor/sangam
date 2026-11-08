// SPDX-License-Identifier: Apache-2.0
package org.sangam.ports.content;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Output templates (EC-18 "output templates"; letters, orders, certificates as text/HTML documents). Placeholders
 * {@code {{name}}} and {@code {{a.b}}} are replaced from a variable map; for HTML templates every value is
 * HTML-escaped (no template code execution, no expression language: templates are data, R6). Missing variables fail
 * the render unless {@code lenient}. The rendered document is stored with {@link ContentService} so it is versioned,
 * checksummed and attached to its scope. PDF output is produced by the deployment's renderer worker (out of process).
 */
public class OutputTemplateRenderer {

    private static final Pattern P = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");

    private final ContentService content;

    public OutputTemplateRenderer(ContentService content) {
        this.content = content;
    }

    public String render(String template, Map<String, Object> vars, boolean html, boolean lenient) {
        Matcher m = P.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = lookup(vars, m.group(1));
            if (v == null && !lenient) {
                throw new IllegalArgumentException("template variable '" + m.group(1) + "' missing");
            }
            String s = v == null ? "" : String.valueOf(v);
            m.appendReplacement(sb, Matcher.quoteReplacement(html ? escape(s) : s));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public ContentService.ContentItem renderToDocument(String tenant, String name, String template, Map<String, Object> vars, boolean html,
                                                       String scopeType, String scopeId, String user) {
        String out = render(template, vars, html, false);
        return content.create(tenant, name, html ? "text/html" : "text/plain", scopeType, scopeId, null,
                new ByteArrayInputStream(out.getBytes(StandardCharsets.UTF_8)), user);
    }

    @SuppressWarnings("unchecked")
    private static Object lookup(Map<String, Object> vars, String path) {
        Object cur = vars;
        for (String part : path.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) {
                return null;
            }
            cur = ((Map<String, Object>) m).get(part);
        }
        return cur;
    }

    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
