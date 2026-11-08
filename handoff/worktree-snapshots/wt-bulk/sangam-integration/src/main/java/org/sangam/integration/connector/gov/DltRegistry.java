// SPDX-License-Identifier: Apache-2.0
// Validation logic ported from spike/simulators org.sangam.spike.sim.dlt.DltSmsSimulator. Modified by Sangam.
package org.sangam.integration.connector.gov;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-tenant TRAI DLT registry (R60, GOV-10): the tenant's principal-entity id, its registered headers (sender ids),
 * its content templates and its whitelisted URL prefixes. Messages are validated against it <i>before</i> they are
 * sent, with the scrubbing rules that are public:
 * <ul>
 *   <li>the template id is registered for the tenant ({@code TEMPLATE_NOT_REGISTERED});</li>
 *   <li>the header is registered for the tenant ({@code HEADER_NOT_REGISTERED}) and bound to the template
 *       ({@code HEADER_MISMATCH}); a {@code -G} Government-message suffix is accepted (TCCCPR amendment 12 Feb 2025);</li>
 *   <li>the mobile number is an Indian mobile number, optionally with {@code 91} ({@code INVALID_MOBILE});</li>
 *   <li>the content matches the template, each {@code {#var#}} standing for 1 to 30 characters
 *       ({@code CONTENT_MISMATCH});</li>
 *   <li>every URL starts with a whitelisted fixed part ({@code URL_NOT_WHITELISTED}).</li>
 * </ul>
 * A message whose template or URL is not registered fails validation (R60 acceptance). Thread-safe.
 */
public final class DltRegistry {

    public static final String GOV_SUFFIX = "-G";
    public static final String VAR = "{#var#}";
    public static final int MAX_VAR_LENGTH = 30;

    private static final Pattern URL = Pattern.compile("(?i)\\b((?:https?://|www\\.)\\S+)");
    private static final Pattern MOBILE = Pattern.compile("(?:91)?[6-9]\\d{9}");

    /** One registered content template. */
    public record Template(String id, String header, String body, Pattern content, int variableCount) {
    }

    /** A message refused by validation; {@link #reason()} is the DLT reason code. */
    public static final class ValidationException extends RuntimeException {
        private final String reason;

        public ValidationException(String reason, String message) {
            super(reason + ": " + message);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    /** The DLT registration of one tenant. */
    public static final class Tenant {
        private final String tenantId;
        private volatile String entityId;
        private final Set<String> headers = ConcurrentHashMap.newKeySet();
        private final Map<String, Template> templates = new ConcurrentHashMap<>();
        private final Set<String> urlWhitelist = ConcurrentHashMap.newKeySet();

        private Tenant(String tenantId) {
            this.tenantId = tenantId;
        }

        public String tenantId() {
            return tenantId;
        }

        public String entityId() {
            return entityId;
        }

        /** Principal-entity (PE) id of the tenant's DLT registration. */
        public Tenant entityId(String id) {
            this.entityId = id;
            return this;
        }

        /** Register a header (sender id, without the {@code -G} suffix). */
        public Tenant header(String header) {
            headers.add(Objects.requireNonNull(header, "header"));
            return this;
        }

        /**
         * Register a content template bound to one header (registering the header too). {@code body} uses
         * {@code {#var#}} placeholders.
         */
        public Tenant template(String templateId, String header, String body) {
            Objects.requireNonNull(templateId, "templateId");
            Objects.requireNonNull(header, "header");
            Objects.requireNonNull(body, "body");
            StringBuilder re = new StringBuilder();
            int from = 0;
            int at;
            int vars = 0;
            while ((at = body.indexOf(VAR, from)) >= 0) {
                re.append(Pattern.quote(body.substring(from, at))).append("(.{1,").append(MAX_VAR_LENGTH).append("})");
                from = at + VAR.length();
                vars++;
            }
            re.append(Pattern.quote(body.substring(from)));
            headers.add(header);
            templates.put(templateId, new Template(templateId, header, body,
                    Pattern.compile(re.toString(), Pattern.DOTALL), vars));
            return this;
        }

        /** Whitelist the fixed part of a URL (e.g. {@code https://dept.gov.in/track/}). */
        public Tenant whitelistUrl(String fixedPrefix) {
            urlWhitelist.add(fixedPrefix.toLowerCase(Locale.ROOT));
            return this;
        }

        public Set<String> headers() {
            return Set.copyOf(headers);
        }

        public Collection<Template> templates() {
            return List.copyOf(templates.values());
        }

        public Optional<Template> template(String templateId) {
            return Optional.ofNullable(templateId == null ? null : templates.get(templateId));
        }

        public Set<String> urlWhitelist() {
            return Set.copyOf(urlWhitelist);
        }
    }

    private final Map<String, Tenant> tenants = new ConcurrentHashMap<>();

    /** The tenant's registration, created empty on first use. */
    public Tenant forTenant(String tenantId) {
        return tenants.computeIfAbsent(Objects.requireNonNull(tenantId, "tenantId"), Tenant::new);
    }

    public Optional<Tenant> find(String tenantId) {
        return Optional.ofNullable(tenantId == null ? null : tenants.get(tenantId));
    }

    public Set<String> tenantIds() {
        return new LinkedHashSet<>(tenants.keySet());
    }

    /**
     * Validates a message for a tenant. Returns the DLT reason code, or null when the message is acceptable.
     */
    public String validate(String tenantId, String header, String templateId, String mobile, String message) {
        if (header == null || templateId == null || mobile == null || message == null) {
            return "BAD_REQUEST";
        }
        Tenant tenant = tenants.get(tenantId);
        if (tenant == null) {
            return "TENANT_NOT_REGISTERED";
        }
        Template t = tenant.templates.get(templateId);
        if (t == null) {
            return "TEMPLATE_NOT_REGISTERED";
        }
        String base = header.endsWith(GOV_SUFFIX) ? header.substring(0, header.length() - GOV_SUFFIX.length()) : header;
        if (!tenant.headers.contains(base) && !tenant.headers.contains(header)) {
            return "HEADER_NOT_REGISTERED";
        }
        if (!base.equals(t.header()) && !header.equals(t.header())) {
            return "HEADER_MISMATCH";
        }
        if (!MOBILE.matcher(mobile).matches()) {
            return "INVALID_MOBILE";
        }
        if (!t.content().matcher(message).matches()) {
            return "CONTENT_MISMATCH";
        }
        Matcher m = URL.matcher(message);
        while (m.find()) {
            String url = m.group(1).replaceAll("[.,;:!?)\\]]+$", "").toLowerCase(Locale.ROOT);
            String normalised = url.startsWith("www.") ? "https://" + url : url;
            boolean ok = tenant.urlWhitelist.stream().anyMatch(p -> url.startsWith(p) || normalised.startsWith(p));
            if (!ok) {
                return "URL_NOT_WHITELISTED";
            }
        }
        return null;
    }

    /**
     * Renders a template with its variables in order.
     *
     * @throws ValidationException {@code TENANT_NOT_REGISTERED}, {@code TEMPLATE_NOT_REGISTERED},
     *                             {@code VARIABLE_COUNT_MISMATCH} or {@code VARIABLE_INVALID} (empty, longer than
     *                             30 characters, or containing a line break)
     */
    public String render(String tenantId, String templateId, List<String> variables) {
        Tenant tenant = tenants.get(tenantId);
        if (tenant == null) {
            throw new ValidationException("TENANT_NOT_REGISTERED", "no DLT registration for tenant " + tenantId);
        }
        Template t = tenant.template(templateId).orElseThrow(() ->
                new ValidationException("TEMPLATE_NOT_REGISTERED", "template " + templateId));
        List<String> vars = variables == null ? List.of() : variables;
        if (vars.size() != t.variableCount()) {
            throw new ValidationException("VARIABLE_COUNT_MISMATCH",
                    "template " + templateId + " has " + t.variableCount() + " variables, got " + vars.size());
        }
        StringBuilder sb = new StringBuilder();
        int from = 0;
        int i = 0;
        int at;
        while ((at = t.body().indexOf(VAR, from)) >= 0) {
            String v = vars.get(i++);
            if (v == null || v.isEmpty() || v.length() > MAX_VAR_LENGTH || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
                throw new ValidationException("VARIABLE_INVALID",
                        "variable " + i + " must have 1 to " + MAX_VAR_LENGTH + " characters and no line break");
            }
            sb.append(t.body(), from, at).append(v);
            from = at + VAR.length();
        }
        sb.append(t.body().substring(from));
        return sb.toString();
    }

    /** The tenant's header bound to a template, or null. */
    public String headerOf(String tenantId, String templateId) {
        return find(tenantId).flatMap(t -> t.template(templateId)).map(Template::header).orElse(null);
    }

    /** All registrations as (tenant → templates) for wiring a simulator. */
    public Map<String, List<Template>> snapshot() {
        Map<String, List<Template>> out = new LinkedHashMap<>();
        tenants.forEach((k, v) -> out.put(k, new ArrayList<>(v.templates())));
        return out;
    }
}
