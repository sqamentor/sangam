// SPDX-License-Identifier: Apache-2.0
package org.sangam.channels.messenger;

import com.fasterxml.jackson.databind.JsonNode;
import org.sangam.channels.messenger.ProviderTokens.TokenSource;
import org.sangam.core.ports.DocumentStore;
import org.sangam.integration.connector.HttpTransport;
import org.sangam.integration.connector.Json;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads the media an inbound message refers to ({@link InboundMessageParsers.Parsed#mediaRef()}), from the providers'
 * public media APIs, through the tenant's egress-limited transport (NG-14). A download is a read: it never causes an
 * effect, so every failure is safe to repeat. {@link #store} scans the bytes and keeps them as an immutable document (R74).
 * The egress allow-list must include each provider's media host (for WhatsApp the media URL host the API returns).
 */
public final class MediaDownloads {

    /** Downloaded media. {@code fileName} may be null. */
    public record Media(byte[] content, String contentType, String fileName) {
    }

    /** A stored download. */
    public record StoredMedia(String documentKey, String fileName, String contentType, long sizeBytes, String sha256) {
    }

    /** The download failed; {@code retryable} is false when the provider refused the reference itself. */
    public static final class MediaUnavailableException extends RuntimeException {
        private final boolean retryable;

        public MediaUnavailableException(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    private record Fetched(byte[] body, String contentType, String disposition) {
    }

    private static final Pattern FILENAME = Pattern.compile("filename=\"?([^\";]+)\"?");
    private static final Pattern MXC = Pattern.compile("^mxc://([A-Za-z0-9.:\\-\\[\\]]+)/([A-Za-z0-9_\\-]+)$");

    private final HttpTransport transport;

    public MediaDownloads(HttpTransport transport) {
        this.transport = transport;
    }

    /**
     * WeChat temporary media: {@code GET {base}/cgi-bin/media/get?access_token=&media_id=}. WeChat answers an error as JSON
     * with {@code errcode}; a token error refreshes the token once.
     */
    public Media weChat(String tenantId, URI apiBase, TokenSource tokens, String mediaId) {
        String base = apiBase.toString().replaceAll("/+$", "");
        Fetched first = get(tenantId, URI.create(base + "/cgi-bin/media/get?access_token=" + MessengerConnectors.encode(tokens.token(tenantId))
                + "&media_id=" + MessengerConnectors.encode(mediaId)), Map.of());
        JsonNode err = jsonError(first);
        if (err != null && tokens.refreshable() && MessengerConnectors.WECHAT_TOKEN_ERRORS.contains(err.path("errcode").asInt(0))) {
            tokens.invalidate(tenantId);
            first = get(tenantId, URI.create(base + "/cgi-bin/media/get?access_token=" + MessengerConnectors.encode(tokens.token(tenantId))
                    + "&media_id=" + MessengerConnectors.encode(mediaId)), Map.of());
            err = jsonError(first);
        }
        if (err != null) {
            int code = err.path("errcode").asInt(0);
            throw new MediaUnavailableException("WECHAT_" + code + " " + err.path("errmsg").asText(""), code == -1 || code == 45009);
        }
        return new Media(first.body(), first.contentType(), fileName(first.disposition()));
    }

    /**
     * WhatsApp Cloud API media: {@code GET {base}/{mediaId}} returns {@code url}, {@code mime_type} and {@code sha256}; then
     * {@code GET url} with the same bearer token. The content must match the published SHA-256.
     */
    public Media whatsApp(String tenantId, URI apiBase, TokenSource tokens, String mediaId) {
        Map<String, String> auth = Map.of("Authorization", "Bearer " + tokens.token(tenantId));
        Fetched meta = get(tenantId, URI.create(apiBase.toString().replaceAll("/+$", "") + "/" + MessengerConnectors.encode(mediaId)), auth);
        JsonNode m = Json.tree(meta.body());
        String url = m == null ? null : m.path("url").asText(null);
        if (url == null) {
            throw new MediaUnavailableException("WhatsApp media " + mediaId + " has no url", false);
        }
        Fetched file = get(tenantId, URI.create(url), auth);
        String want = m.path("sha256").asText("");
        if (!want.isEmpty() && !want.equalsIgnoreCase(sha256(file.body()))) {
            throw new MediaUnavailableException("WhatsApp media " + mediaId + " does not match its published SHA-256", true);
        }
        return new Media(file.body(), m.path("mime_type").asText(file.contentType()), fileName(file.disposition()));
    }

    /**
     * Matrix authenticated media ({@code GET {homeserver}/_matrix/client/v1/media/download/{server}/{mediaId}}) for an
     * {@code mxc://server/mediaId} reference.
     */
    public Media matrix(String tenantId, URI homeserver, TokenSource tokens, String mxcUrl) {
        Matcher mx = MXC.matcher(mxcUrl == null ? "" : mxcUrl);
        if (!mx.matches()) {
            throw new MediaUnavailableException("not an mxc:// media reference", false);
        }
        Fetched r = get(tenantId, URI.create(homeserver.toString().replaceAll("/+$", "") + "/_matrix/client/v1/media/download/"
                + MessengerConnectors.encode(mx.group(1)) + "/" + mx.group(2)), Map.of("Authorization", "Bearer " + tokens.token(tenantId)));
        return new Media(r.body(), r.contentType(), fileName(r.disposition()));
    }

    /** Slack files: {@code files.info?file=} gives {@code url_private_download}, read with the same bot token. */
    public Media slack(String tenantId, URI apiBase, TokenSource tokens, String fileId) {
        Map<String, String> auth = Map.of("Authorization", "Bearer " + tokens.token(tenantId));
        String base = (apiBase == null ? MessengerConnectors.SLACK_API : apiBase).toString().replaceAll("/+$", "");
        JsonNode info = Json.tree(get(tenantId, URI.create(base + "/files.info?file=" + MessengerConnectors.encode(fileId)), auth).body());
        if (info == null || !info.path("ok").asBoolean(false)) {
            throw new MediaUnavailableException("SLACK_" + (info == null ? "invalid_response" : info.path("error").asText("unknown_error")), false);
        }
        JsonNode f = info.path("file");
        String url = f.path("url_private_download").asText(null);
        if (url == null) {
            throw new MediaUnavailableException("Slack file " + fileId + " has no download URL", false);
        }
        Fetched r = get(tenantId, URI.create(url), auth);
        return new Media(r.body(), f.path("mimetype").asText(r.contentType()), f.path("name").asText(null));
    }

    /**
     * Scans and stores a download under {@code <tenant>/media/<channel>/<uuid>}. A file the scanner refuses is not stored
     * ({@code MEDIA_REFUSED_BY_SCANNER}).
     */
    public static StoredMedia store(DocumentStore documents, BiPredicate<String, byte[]> scanner, String tenantId, String channel, Media media) throws IOException {
        String name = media.fileName() == null ? "media" : media.fileName();
        if (!scanner.test(name, media.content())) {
            throw new IllegalArgumentException("MEDIA_REFUSED_BY_SCANNER");
        }
        String key = tenantId + "/media/" + channel.toLowerCase(Locale.ROOT) + "/" + UUID.randomUUID();
        DocumentStore.StoredObject o = documents.put(key, new ByteArrayInputStream(media.content()), media.contentType());
        return new StoredMedia(o.key(), name, media.contentType(), o.sizeBytes(), o.sha256Hex());
    }

    // ---- shared --------------------------------------------------------------------------------

    private Fetched get(String tenantId, URI uri, Map<String, String> headers) {
        return switch (transport.execute(tenantId, HttpTransport.Call.get(uri, headers), 0)) {
            case HttpTransport.Outcome.Response r when r.is2xx() ->
                    new Fetched(r.body(), r.header("Content-Type").orElse("application/octet-stream"), r.header("Content-Disposition").orElse(null));
            case HttpTransport.Outcome.Response r -> throw new MediaUnavailableException("HTTP " + r.status() + " from " + uri.getHost(),
                    r.status() == 429 || r.status() >= 500);
            case HttpTransport.Outcome.NoEffect n -> throw new MediaUnavailableException(n.reason(), true);
            case HttpTransport.Outcome.Unknown u -> throw new MediaUnavailableException(u.reason(), true);
        };
    }

    private static JsonNode jsonError(Fetched r) {
        String ct = r.contentType().toLowerCase(Locale.ROOT);
        if (!ct.startsWith("application/json") && !ct.startsWith("text/plain")) {
            return null;
        }
        JsonNode t = Json.tree(r.body());
        return t != null && t.has("errcode") && t.path("errcode").asInt(0) != 0 ? t : null;
    }

    static String fileName(String disposition) {
        if (disposition == null) {
            return null;
        }
        Matcher m = FILENAME.matcher(disposition);
        if (!m.find()) {
            return null;
        }
        String n = m.group(1).trim();
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        return slash >= 0 ? n.substring(slash + 1) : n;
    }

    private static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
