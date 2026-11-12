// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels.mail;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.channels.messenger.ProviderTokens;
import org.sangam.channels.messenger.ProviderTokens.TokenSource;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Microsoft Graph mailbox as an {@link InboundMail.Source} (NG-27), over the public Graph v1.0 REST API with plain HTTP
 * through the tenant's egress-limited {@link HttpTransport}; no vendor SDK. The app needs the application permission
 * {@code Mail.ReadWrite} on the mailbox (an Exchange application access policy can limit it to that mailbox); the token is
 * the client-credentials token from {@link ProviderTokens#oauth2} with {@link ProviderTokens#GRAPH_SCOPE}.
 *
 * <ul>
 *   <li>{@link #fetch}: unread messages of the folder ({@code isRead eq false}), text body
 *       ({@code Prefer: outlook.body-content-type="text"}), the RFC 5322 {@code internetMessageId} as the dedupe key.</li>
 *   <li>Attachments: a {@code fileAttachment} carries its bytes; an {@code itemAttachment} (an attached mail or event) is
 *       read as MIME through {@code /$value}; a {@code referenceAttachment} (a link to a cloud file) has no bytes, so its
 *       {@link InboundMail.Attachment#content()} is null and {@link InboundMail} records the mail as poison instead of
 *       dropping the attachment silently.</li>
 *   <li>{@link #acknowledge}: {@code PATCH .../messages/{id}} with {@code isRead=true}.</li>
 * </ul>
 * A failed Graph call throws {@link IOException}, so the poll leaves the mail unread and the next poll retries it.
 */
public final class GraphMailSource implements InboundMail.Source {

    public static final URI GRAPH_V1 = URI.create("https://graph.microsoft.com/v1.0");

    /**
     * @param mailbox user id or user principal name of the mailbox
     * @param folder  a well-known folder name ({@code inbox}) or a folder id
     */
    public record Config(String tenantId, URI graphBase, String mailbox, String folder) {
        public Config {
            if (mailbox == null || mailbox.isBlank()) {
                throw new IllegalArgumentException("graph mail source needs a mailbox");
            }
            graphBase = graphBase == null ? GRAPH_V1 : graphBase;
            folder = folder == null || folder.isBlank() ? "inbox" : folder;
        }
    }

    private final Config config;
    private final HttpTransport transport;
    private final TokenSource tokens;
    private final String user;
    /** internetMessageId -> Graph message id, for the messages of the last fetch. */
    private final Map<String, String> open = new HashMap<>();

    public GraphMailSource(Config config, HttpTransport transport, TokenSource tokens) {
        this.config = config;
        this.transport = transport;
        this.tokens = tokens;
        this.user = config.graphBase().toString().replaceAll("/+$", "") + "/users/" + enc(config.mailbox());
    }

    @Override
    public List<InboundMail.Mail> fetch(int max) throws IOException {
        open.clear();
        int top = Math.max(1, Math.min(max, 100));
        URI uri = URI.create(user + "/mailFolders/" + enc(config.folder()) + "/messages?$filter=isRead%20eq%20false&$top=" + top
                + "&$select=id,internetMessageId,from,subject,body,hasAttachments");
        JsonNode page = json(call("GET", uri, null, Map.of("Prefer", "outlook.body-content-type=\"text\"")));
        List<InboundMail.Mail> out = new ArrayList<>();
        for (JsonNode m : page.path("value")) {
            if (out.size() >= max) {
                break;
            }
            String graphId = m.path("id").asText();
            String messageId = m.path("internetMessageId").asText(null);
            JsonNode from = m.path("from").path("emailAddress");
            String sender = from.isMissingNode() ? null : from.path("address").asText(null);
            List<InboundMail.Attachment> atts = m.path("hasAttachments").asBoolean(false) ? attachments(graphId) : List.of();
            out.add(new InboundMail.Mail(messageId, sender, m.path("subject").asText(null), m.path("body").path("content").asText(""), atts));
            if (messageId != null) {
                open.put(messageId, graphId);
            }
        }
        return out;
    }

    private List<InboundMail.Attachment> attachments(String graphId) throws IOException {
        List<InboundMail.Attachment> out = new ArrayList<>();
        URI next = URI.create(user + "/messages/" + enc(graphId) + "/attachments");
        while (next != null) {
            JsonNode page = json(call("GET", next, null, Map.of()));
            for (JsonNode a : page.path("value")) {
                String type = a.path("@odata.type").asText();
                String name = a.path("name").asText("attachment");
                String contentType = a.path("contentType").asText("application/octet-stream");
                switch (type) {
                    case "#microsoft.graph.fileAttachment" ->
                            out.add(new InboundMail.Attachment(name, contentType, Base64.getDecoder().decode(a.path("contentBytes").asText(""))));
                    case "#microsoft.graph.itemAttachment" -> {
                        byte[] mime = call("GET", URI.create(user + "/messages/" + enc(graphId) + "/attachments/" + enc(a.path("id").asText()) + "/$value"), null, Map.of()).body();
                        out.add(new InboundMail.Attachment(name.endsWith(".eml") ? name : name + ".eml", "message/rfc822", mime));
                    }
                    default -> out.add(new InboundMail.Attachment(name, contentType, null));
                }
            }
            String link = page.path("@odata.nextLink").asText(null);
            next = link == null ? null : sameService(URI.create(link));
        }
        return out;
    }

    /** A next link must stay on the configured Graph service, or the token would be sent elsewhere. */
    private URI sameService(URI link) throws IOException {
        URI base = config.graphBase();
        if (link.getScheme() == null || !link.getScheme().equalsIgnoreCase(base.getScheme())
                || link.getHost() == null || !link.getHost().equalsIgnoreCase(base.getHost()) || link.getPort() != base.getPort()) {
            throw new IOException("Graph nextLink points outside " + base.getHost());
        }
        return link;
    }

    @Override
    public void acknowledge(String messageId) throws IOException {
        String graphId = open.remove(messageId);
        if (graphId == null) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("isRead", true);
        call("PATCH", URI.create(user + "/messages/" + enc(graphId)), Json.bytes(body), Map.of("Content-Type", "application/json"));
    }

    @Override
    public void close() {
        open.clear();
    }

    private HttpTransport.Outcome.Response call(String method, URI uri, byte[] body, Map<String, String> extra) throws IOException {
        HttpTransport.Outcome o = send(method, uri, body, extra);
        if (tokens.refreshable() && o instanceof HttpTransport.Outcome.Response r && r.status() == 401) {
            tokens.invalidate(config.tenantId());
            o = send(method, uri, body, extra);
        }
        return switch (o) {
            case HttpTransport.Outcome.Response r when r.is2xx() -> r;
            case HttpTransport.Outcome.Response r -> throw new IOException("Graph " + method + " " + uri.getPath() + ": HTTP " + r.status());
            case HttpTransport.Outcome.NoEffect n -> throw new IOException("Graph " + method + ": " + n.reason());
            case HttpTransport.Outcome.Unknown u -> throw new IOException("Graph " + method + ": " + u.reason());
        };
    }

    private HttpTransport.Outcome send(String method, URI uri, byte[] body, Map<String, String> extra) throws IOException {
        Map<String, String> h = new LinkedHashMap<>(extra);
        h.put("Accept", "application/json");
        try {
            h.put("Authorization", "Bearer " + tokens.token(config.tenantId()));
        } catch (ProviderTokens.TokenUnavailableException e) {
            throw new IOException("Graph token unavailable: " + e.getMessage(), e);
        }
        // GET and the isRead PATCH are idempotent: repeating them has no further effect
        return transport.execute(config.tenantId(), new HttpTransport.Call(method, uri, h, body, null, true), 0);
    }

    private static JsonNode json(HttpTransport.Outcome.Response r) throws IOException {
        JsonNode t = Json.tree(r.body());
        if (t == null) {
            throw new IOException("Graph answered with no JSON");
        }
        return t;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
