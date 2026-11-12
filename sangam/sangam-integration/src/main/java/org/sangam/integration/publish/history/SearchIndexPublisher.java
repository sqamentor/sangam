// SPDX-License-Identifier: Apache-2.0
package org.sangam.integration.publish.history;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.sangam.core.layer.Outbox;
import org.sangam.core.layer.Publisher;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;
import org.sangam.integration.connector.SecretResolver;
import org.sangam.integration.relay.DeferPublishException;
import org.sangam.integration.relay.PermanentPublishException;

/**
 * M-057 external history projector, search-index sink: each engine history event the outbox carries
 * ({@code sangam.history.*}) becomes one document in an OpenSearch or Elasticsearch index through their common document
 * API, {@code PUT {url}/{index}/_doc/{eventId}}. The document id is the outbox event id, so a relay redelivery (after a
 * lost acknowledgement) overwrites the same document: an idempotent upsert, never a duplicate.
 *
 * <ul>
 *   <li><b>Document.</b> {@code eventId}, {@code tenantId}, {@code rootId}, {@code rootSeq}, {@code eventType} and
 *       {@code data} (the event payload as JSON; a payload that is not JSON is kept as the string {@code dataText}).</li>
 *   <li><b>Index.</b> A fixed name, or one per tenant with the {@code {tenant}} placeholder (folded to lower case, as
 *       index names must be). A name that is still not a valid index name is a permanent failure for that event.</li>
 *   <li><b>Credentials by reference.</b> {@code Basic} (user name + password reference) or {@code ApiKey} (key reference),
 *       resolved on every call, so a rotated secret needs no restart; none when the cluster is reached without
 *       authentication (a private network the egress rule allows).</li>
 *   <li><b>Outcomes.</b> 2xx done; 400, 413, 415, 422 permanent (the document is the problem; dead letter); 429 or 503
 *       with {@code Retry-After} deferred; anything else retried by the relay; an egress refusal is permanent.</li>
 * </ul>
 */
public final class SearchIndexPublisher implements Publisher {

    /** Outbox destination name of this sink (server wiring). */
    public static final String DESTINATION = "history-search";
    static final Set<Integer> PERMANENT = Set.of(400, 413, 415, 422);
    static final Duration MAX_RETRY_AFTER = Duration.ofMinutes(5);
    /**
     * Lower case; none of {@code \ / * ? " < > | , # :} or space; not starting with {@code - _ +}; not {@code .} or
     * {@code ..}; at most 255 characters (OpenSearch/Elasticsearch index name rules).
     */
    private static final Pattern INDEX = Pattern.compile("(?!\\.{1,2}$)[a-z0-9.][^\\\\/*?\"<>|,#: A-Z]{0,254}");

    /**
     * @param url         cluster base URL (https in production)
     * @param index       index name, may contain {@code {tenant}}
     * @param username    Basic user name (with {@code passwordRef}), or null
     * @param passwordRef secret reference of the Basic password, or null
     * @param apiKeyRef   secret reference of an {@code ApiKey} credential (the encoded id:key), or null
     */
    public record Settings(URI url, String index, String username, String passwordRef, String apiKeyRef) {
        public Settings {
            Objects.requireNonNull(url, "url");
            if (url.getScheme() == null || url.getHost() == null) {
                throw new IllegalArgumentException("history search url must be absolute: " + url);
            }
            if (index == null || index.isBlank()) {
                throw new IllegalArgumentException("history search index is required");
            }
            boolean basic = present(username) || present(passwordRef);
            if (basic && !(present(username) && present(passwordRef))) {
                throw new IllegalArgumentException("history search Basic credentials need both username and password-ref");
            }
            if (basic && present(apiKeyRef)) {
                throw new IllegalArgumentException("history search: configure either Basic credentials or api-key-ref, not both");
            }
        }

        private static boolean present(String s) {
            return s != null && !s.isBlank();
        }
    }

    private final HttpTransport transport;
    private final Settings settings;
    private final SecretResolver secrets;

    public SearchIndexPublisher(HttpTransport transport, Settings settings, SecretResolver secrets) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
    }

    @Override
    public void publish(String destination, Outbox.OutboxEvent event) throws Exception {
        String index = index(settings.index(), event.tenantId());
        URI uri = documentUri(settings.url(), index, event.eventId().toString());
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        String auth = authorization();
        if (auth != null) {
            h.put("Authorization", auth);
        }
        HttpTransport.Outcome o = transport.execute(event.tenantId(),
                new HttpTransport.Call("PUT", uri, h, Json.bytes(document(event)), null, true), 0);
        switch (o) {
            case HttpTransport.Outcome.Response r when r.is2xx() -> {
            }
            case HttpTransport.Outcome.Response r when PERMANENT.contains(r.status()) ->
                    throw new PermanentPublishException("history index " + index + " rejected event " + event.eventId()
                            + ": HTTP " + r.status());
            case HttpTransport.Outcome.Response r when (r.status() == 429 || r.status() == 503) && r.retryAfter().isPresent() -> {
                Duration d = r.retryAfter().get();
                throw new DeferPublishException("history index asked to retry after " + d.toSeconds() + " s",
                        d.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : d);
            }
            case HttpTransport.Outcome.Response r -> throw new IllegalStateException("history index HTTP " + r.status());
            case HttpTransport.Outcome.NoEffect ne -> {
                if (ne.reason().startsWith("EGRESS_DENIED")) {
                    throw new PermanentPublishException(ne.reason());
                }
                throw new IllegalStateException(ne.reason());
            }
            case HttpTransport.Outcome.Unknown u -> throw new IllegalStateException(u.reason());
        }
    }

    /** The document of one event (field names are the public projection contract). */
    static ObjectNode document(Outbox.OutboxEvent e) {
        ObjectNode d = Json.MAPPER.createObjectNode();
        d.put("eventId", e.eventId().toString());
        d.put("tenantId", e.tenantId());
        d.put("rootId", e.rootId());
        d.put("rootSeq", e.rootSeq());
        d.put("eventType", e.eventType());
        JsonNode data = Json.tree(e.payload());
        if (data != null) {
            d.set("data", data);
        } else if (e.payload() != null) {
            d.put("dataText", e.payload());
        }
        return d;
    }

    /** The index of a tenant's event: {@code {tenant}} replaced and folded to lower case; refused when not a valid name. */
    static String index(String pattern, String tenant) throws PermanentPublishException {
        String name = pattern.replace("{tenant}", tenant == null ? "" : tenant).toLowerCase(Locale.ROOT);
        if (!INDEX.matcher(name).matches()) {
            throw new PermanentPublishException("not a valid history index name: " + name);
        }
        return name;
    }

    static URI documentUri(URI base, String index, String id) {
        String b = base.toString();
        String root = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        return URI.create(root + "/" + enc(index) + "/_doc/" + enc(id));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String authorization() {
        if (settings.apiKeyRef() != null && !settings.apiKeyRef().isBlank()) {
            return "ApiKey " + secrets.require(settings.apiKeyRef());
        }
        if (settings.username() != null && !settings.username().isBlank()) {
            String pair = settings.username() + ":" + secrets.require(settings.passwordRef());
            return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
        }
        return null;
    }
}
