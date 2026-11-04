// SPDX-License-Identifier: Apache-2.0
package org.sangam.sdk.error;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RFC 9457 {@code application/problem+json} body. {@code code} is the Sangam machine code
 * (for example {@code STALE_LEASE}, {@code GONE_INSTANCE_DELETED}); unknown members land in {@link #extensions()}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class Problem {
    public String type;
    public String title;
    public Integer status;
    public String detail;
    public String instance;
    public String code;
    public String traceId;
    private final Map<String, Object> extensions = new LinkedHashMap<>();

    @JsonAnySetter
    public void extension(String name, Object value) {
        extensions.put(name, value);
    }

    @JsonAnyGetter
    public Map<String, Object> extensions() {
        return extensions;
    }

    /** True when either {@code code} or the last path segment of {@code type} equals {@code name} (case and -/_ insensitive). */
    public boolean is(String name) {
        String n = norm(name);
        if (code != null && norm(code).equals(n)) return true;
        if (type != null) {
            String t = type;
            int slash = t.lastIndexOf('/');
            if (slash >= 0) t = t.substring(slash + 1);
            int hash = t.lastIndexOf('#');
            if (hash >= 0) t = t.substring(hash + 1);
            return norm(t).equals(n);
        }
        return false;
    }

    private static String norm(String s) {
        return s.toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    @Override
    public String toString() {
        return "Problem{type=" + type + ", title=" + title + ", status=" + status + ", code=" + code
                + ", detail=" + detail + "}";
    }
}
