// SPDX-License-Identifier: Apache-2.0
package org.sangam.security.scim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.sangam.core.security.SecurityPorts;
import org.sangam.security.support.Sql;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SCIM 2.0 shapes (RFC 7643) of Sangam persons and groups: request parsing with the limits of the SCIM table, the
 * resource JSON the service returns, ETags, the ListResponse and {@code attributes} / {@code excludedAttributes}
 * projection. Plain Java and Jackson; no framework.
 */
public final class ScimResources {

    public static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";
    public static final String GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";
    public static final String SANGAM_USER = "urn:sangam:params:scim:schemas:extension:2.0:User";
    public static final String LIST_RESPONSE = "urn:ietf:params:scim:api:messages:2.0:ListResponse";
    public static final String ERROR = "urn:ietf:params:scim:api:messages:2.0:Error";
    /** Base of {@code meta.location}; the web adapter may prefix the server's external URL. */
    public static final String BASE = "/scim/v2/";

    /**
     * The resource URI of a SCIM resource (RFC 7644 section 3.2): the id is one path segment, so every octet outside
     * the RFC 3986 unreserved set is percent-encoded. An id carrying '/' or ':' (an adopted person id) stays one
     * segment and the location resolves to the same resource.
     */
    public static String location(String collection, String id) {
        StringBuilder s = new StringBuilder(BASE).append(collection).append('/');
        for (byte b : id.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '.' || c == '_' || c == '~') {
                s.append((char) c);
            } else {
                s.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return s.toString();
    }
    /** The core attributes Sangam keeps verbatim in {@code SEC_SCIM_RESOURCE.attributes}. */
    public static final List<String> KEPT = List.of("name", "emails", "phoneNumbers", "locale", "preferredLanguage",
            "timezone", "title");
    /** Column limit of the string columns ({str}). */
    public static final int MAX_STRING = 255;
    /** Upper bound of the kept attributes as JSON text. */
    public static final int MAX_ATTRIBUTES = 32 * 1024;

    private ScimResources() {
    }

    /** A posting as SCIM shows it (read-only, decision 154): seat, kind and the UTC dates. */
    public record Posting(String seatId, String kind, String validFrom, String validTo) {
    }

    /** A parsed User body. {@code postings} is null when the request does not carry the attribute. */
    public record UserInput(String userName, String externalId, String displayName, Boolean active, String email,
                            String personName, String attributes, Set<Posting> postings) {
    }

    /** A parsed Group body. */
    public record GroupInput(String displayName, String externalId, Set<String> members) {
    }

    // ------------------------------------------------------------------ requests

    public static UserInput user(JsonNode body) {
        requireObject(body, USER_SCHEMA);
        String userName = string(body, "userName", true);
        if (userName == null || userName.isBlank()) {
            throw ScimException.invalidValue("userName is required");
        }
        String externalId = externalId(body);
        String displayName = string(body, "displayName", true);
        Boolean active = bool(ScimPatch.field(body, "active"));
        ObjectNode kept = Sql.JSON.createObjectNode();
        for (String k : KEPT) {
            JsonNode v = ScimPatch.field(body, k);
            if (v != null && !v.isNull()) {
                kept.set(k, v);
            }
        }
        String attributes = kept.isEmpty() ? null : kept.toString();
        if (attributes != null && attributes.length() > MAX_ATTRIBUTES) {
            throw ScimException.invalidValue("the user's attributes exceed " + MAX_ATTRIBUTES + " characters");
        }
        String personName = displayName != null && !displayName.isBlank() ? displayName : formattedName(kept, userName);
        return new UserInput(userName.trim(), externalId, displayName, active, email(kept), cap(personName), attributes,
                postings(body));
    }

    public static GroupInput group(JsonNode body) {
        requireObject(body, GROUP_SCHEMA);
        String displayName = string(body, "displayName", true);
        if (displayName == null || displayName.isBlank()) {
            throw ScimException.invalidValue("displayName is required");
        }
        Set<String> members = new LinkedHashSet<>();
        JsonNode m = ScimPatch.field(body, "members");
        if (m != null && !m.isNull()) {
            if (!m.isArray()) {
                throw ScimException.invalidValue("members must be an array");
            }
            for (JsonNode e : m) {
                JsonNode type = ScimPatch.field(e, "type");
                if (type != null && "group".equalsIgnoreCase(type.asText())) {
                    throw ScimException.invalidValue("a group cannot be a member: groups hold persons only (no nested groups)");
                }
                JsonNode v = ScimPatch.field(e, "value");
                if (v == null || !v.isTextual() || v.asText().isBlank()) {
                    throw ScimException.invalidValue("every member needs value (the User id)");
                }
                members.add(v.asText());
            }
        }
        return new GroupInput(displayName.trim(), externalId(body), members);
    }

    private static void requireObject(JsonNode body, String schema) {
        if (body == null || !body.isObject()) {
            throw ScimException.invalidSyntax("the body must be a JSON object");
        }
        if (!ScimPatch.hasSchema(body, schema)) {
            throw ScimException.invalidSyntax("schemas must contain " + schema);
        }
    }

    private static String externalId(JsonNode body) {
        String externalId = string(body, "externalId", false);
        if (externalId != null && externalId.length() > ScimStore.MAX_EXTERNAL_ID) {
            throw ScimException.invalidValue("externalId longer than " + ScimStore.MAX_EXTERNAL_ID + " characters");
        }
        return externalId == null || externalId.isEmpty() ? null : externalId;
    }

    private static String string(JsonNode body, String name, boolean limit) {
        JsonNode v = ScimPatch.field(body, name);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isTextual()) {
            throw ScimException.invalidValue(name + " must be a string");
        }
        if (limit && v.asText().length() > MAX_STRING) {
            throw ScimException.invalidValue(name + " longer than " + MAX_STRING + " characters");
        }
        return v.asText();
    }

    /** RFC 7643 boolean; the strings "true" and "false" (any case) are accepted, as some identity providers send them. */
    static Boolean bool(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        if (v.isTextual() && ("true".equalsIgnoreCase(v.asText()) || "false".equalsIgnoreCase(v.asText()))) {
            return Boolean.parseBoolean(v.asText().toLowerCase(Locale.ROOT));
        }
        throw ScimException.invalidValue("active must be a boolean");
    }

    private static String formattedName(ObjectNode kept, String userName) {
        JsonNode name = kept.get("name");
        if (name != null && name.isObject()) {
            JsonNode f = ScimPatch.field(name, "formatted");
            if (f != null && f.isTextual() && !f.asText().isBlank()) {
                return f.asText();
            }
            JsonNode given = ScimPatch.field(name, "givenName");
            JsonNode family = ScimPatch.field(name, "familyName");
            String joined = ((given == null ? "" : given.asText()) + " " + (family == null ? "" : family.asText())).trim();
            if (!joined.isEmpty()) {
                return joined;
            }
        }
        return userName;
    }

    /** The primary e-mail, else the first one. */
    private static String email(ObjectNode kept) {
        JsonNode emails = kept.get("emails");
        if (emails == null || !emails.isArray()) {
            return null;
        }
        String first = null;
        for (JsonNode e : emails) {
            JsonNode v = ScimPatch.field(e, "value");
            if (v == null || !v.isTextual()) {
                continue;
            }
            Boolean primary = bool(ScimPatch.field(e, "primary"));
            if (Boolean.TRUE.equals(primary)) {
                return cap(v.asText());
            }
            if (first == null) {
                first = v.asText();
            }
        }
        return cap(first);
    }

    private static String cap(String s) {
        if (s != null && s.length() > MAX_STRING) {
            throw ScimException.invalidValue("value longer than " + MAX_STRING + " characters: " + s.substring(0, 40) + "...");
        }
        return s;
    }

    /** The postings the request carries in the Sangam extension, or null when it does not mention them. */
    private static Set<Posting> postings(JsonNode body) {
        JsonNode ext = ScimPatch.field(body, SANGAM_USER);
        JsonNode p = ext == null ? null : ScimPatch.field(ext, "postings");
        if (p == null) {
            return null;
        }
        Set<Posting> out = new LinkedHashSet<>();
        if (p.isNull()) {
            return out;
        }
        if (!p.isArray()) {
            throw ScimException.mutability("postings are read-only in SCIM");
        }
        for (JsonNode e : p) {
            out.add(new Posting(text(e, "seatId"), upper(text(e, "kind")), text(e, "validFrom"), text(e, "validTo")));
        }
        return out;
    }

    private static String text(JsonNode e, String name) {
        JsonNode v = ScimPatch.field(e, name);
        return v == null || v.isNull() ? null : v.asText().trim();
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ responses

    /** The posting shown in SCIM: the security kind REGULAR is the contract's SUBSTANTIVE (IX.5). */
    public static Posting posting(SecurityPorts.SeatHolder h) {
        String kind = "REGULAR".equals(h.kind()) ? "SUBSTANTIVE" : h.kind();
        return new Posting(h.seatId(), kind, date(h.validFrom()), date(h.validTo()));
    }

    private static String date(Instant t) {
        return t == null ? null : t.atOffset(ZoneOffset.UTC).toLocalDate().toString();
    }

    public static String etag(long version) {
        return "W/\"" + version + "\"";
    }

    /** True when {@code ifMatch} is absent, {@code *}, or lists the current ETag (weak or strong form). */
    public static boolean matches(String ifMatch, long version) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return true;
        }
        for (String tag : ifMatch.split(",")) {
            String t = tag.trim();
            if (t.equals("*") || t.equals(etag(version)) || t.equals("\"" + version + "\"")) {
                return true;
            }
        }
        return false;
    }

    public static ObjectNode meta(String type, String id, ScimStore.Row row) {
        ObjectNode meta = Sql.JSON.createObjectNode();
        meta.put("resourceType", type);
        meta.put("created", row.created().toString());
        meta.put("lastModified", row.updated().toString());
        meta.put("location", location(type.equals("User") ? "Users" : "Groups", id));
        meta.put("version", etag(row.version()));
        return meta;
    }

    public static ArrayNode schemas(String... urns) {
        ArrayNode a = Sql.JSON.createArrayNode();
        for (String u : urns) {
            a.add(u);
        }
        return a;
    }

    /** RFC 7644 section 3.4.2 ListResponse. */
    public static ObjectNode listResponse(int total, int startIndex, List<ObjectNode> resources) {
        ObjectNode r = Sql.JSON.createObjectNode();
        r.set("schemas", schemas(LIST_RESPONSE));
        r.put("totalResults", total);
        r.put("startIndex", startIndex);
        r.put("itemsPerPage", resources.size());
        ArrayNode a = r.putArray("Resources");
        resources.forEach(a::add);
        return r;
    }

    /** RFC 7644 section 3.12 error body. */
    public static ObjectNode error(ScimException e) {
        ObjectNode r = Sql.JSON.createObjectNode();
        r.set("schemas", schemas(ERROR));
        r.put("status", Integer.toString(e.status()));
        if (e.scimType() != null) {
            r.put("scimType", e.scimType());
        }
        r.put("detail", e.detail());
        return r;
    }

    /**
     * RFC 7644 section 3.9 projection on top-level attributes (case-insensitive, core schema URN prefix allowed).
     * {@code id} and {@code schemas} are always returned; {@code attributes} wins over {@code excludedAttributes}.
     */
    public static ObjectNode project(ObjectNode resource, String attributes, String excludedAttributes) {
        Set<String> include = names(attributes);
        Set<String> exclude = names(excludedAttributes);
        if (include.isEmpty() && exclude.isEmpty()) {
            return resource;
        }
        ObjectNode out = resource.deepCopy();
        Iterator<Map.Entry<String, JsonNode>> it = out.properties().iterator();
        List<String> drop = new ArrayList<>();
        while (it.hasNext()) {
            String k = it.next().getKey();
            String lk = k.toLowerCase(Locale.ROOT);
            if (lk.equals("id") || lk.equals("schemas")) {
                continue;
            }
            if (!include.isEmpty() ? !include.contains(lk) : exclude.contains(lk)) {
                drop.add(k);
            }
        }
        drop.forEach(out::remove);
        return out;
    }

    private static Set<String> names(String list) {
        Set<String> out = new LinkedHashSet<>();
        if (list == null || list.isBlank()) {
            return out;
        }
        for (String n : list.split(",")) {
            String s = n.trim().toLowerCase(Locale.ROOT);
            for (String core : List.of(USER_SCHEMA, GROUP_SCHEMA)) {
                String prefix = core.toLowerCase(Locale.ROOT) + ":";
                if (s.startsWith(prefix)) {
                    s = s.substring(prefix.length());
                }
            }
            int dot = s.indexOf('.');
            if (!s.startsWith("urn:") && dot > 0) {
                s = s.substring(0, dot);       // a sub-attribute keeps its parent
            }
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }
}
