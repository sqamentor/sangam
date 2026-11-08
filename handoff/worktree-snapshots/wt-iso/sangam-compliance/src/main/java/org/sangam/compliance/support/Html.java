// SPDX-License-Identifier: Apache-2.0
package org.sangam.compliance.support;

/** Minimal HTML escaping for generated notices and reports (no template engine dependency). */
public final class Html {

    private Html() {
    }

    public static String esc(Object value) {
        if (value == null) {
            return "";
        }
        String s = String.valueOf(value);
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** A link only for http(s) URLs; anything else is shown as text. */
    public static String link(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String u = url.trim();
        if (u.startsWith("https://") || u.startsWith("http://")) {
            return "<a href=\"" + esc(u) + "\">" + esc(u) + "</a>";
        }
        return esc(u);
    }
}
